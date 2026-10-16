#include "RelayEventDispatcher.h"
#include "RelayEngine.h"
#include <sys/eventfd.h>
#include <poll.h>
#include <unistd.h>
#include <cerrno>
#include <cstring>
#include <algorithm>

RelayEventDispatcher::~RelayEventDispatcher() { stop(); }
std::string RelayEventDispatcher::prepare(RelayEngine& engine, uint32_t session) {
    sessionId = session; sequence = 0; head = 0; tail = 0;
    errorState = 0; stopRequested = false; quitting = false;
    overflowState.reset(); overflowXrun.reset(); overflowGate.reset();
    overflowInputTotal = overflowOutputTotal = 0;
    fd = eventfd(0, EFD_CLOEXEC | EFD_NONBLOCK);
    if (fd < 0) return std::string("eventfd: ") + strerror(errno);
    try { worker = std::thread([this, &engine] { consume(engine); }); }
    catch (const std::exception& e) { close(fd); fd = -1; return e.what(); }
    return "";
}
void RelayEventDispatcher::wake() {
    if (fd < 0) return;
    const uint64_t one = 1;
    // A bounded retry handles an interrupted write. EAGAIN means a wake is
    // already pending. Records remain authoritative, including fatal errors.
    if (write(fd, &one, sizeof(one)) < 0 && errno == EINTR)
        (void)write(fd, &one, sizeof(one));
}
void RelayEventDispatcher::publish(RelayEventType type, int64_t a, int64_t b) {
    const uint32_t h = head.load(std::memory_order_relaxed);
    const uint32_t seq = ++sequence;
    if (h - tail.load(std::memory_order_acquire) < capacity) {
        queue[h % capacity] = {sessionId, seq, type, a, b};
        head.store(h + 1, std::memory_order_release);
    } else if (type == RelayEventType::XRun) {
        overflowInputTotal += a; overflowOutputTotal += b;
        overflowXrun.store({sessionId, seq, type, static_cast<int64_t>(overflowInputTotal), static_cast<int64_t>(overflowOutputTotal)});
    } else if (type == RelayEventType::GateStall) {
        overflowGate.store({sessionId, seq, type, a, b});
    } else {
        overflowState.store({sessionId, seq, type, a, b});
    }
    wake();
}
void RelayEventDispatcher::requestExit(const RelayErrorRecord& error) {
    uint32_t expected = 0;
    if (errorState.compare_exchange_strong(expected, 1, std::memory_order_acq_rel)) {
        firstError = error;
        errorState.store(2, std::memory_order_release);
    }
    stopRequested.store(true, std::memory_order_release);
    wake();
}
void RelayEventDispatcher::consume(RelayEngine& engine) {
    uint32_t stateSeen = 0, xrunSeen = 0, gateSeen = 0;
    int64_t inputSeen = 0, outputSeen = 0;
    for (;;) {
        pollfd p{fd, POLLIN, 0};
        if (poll(&p, 1, -1) < 0) { if (errno == EINTR) continue; return; }
        uint64_t count;
        while (read(fd, &count, sizeof(count)) < 0 && errno == EINTR) {}
        if (errorState.load(std::memory_order_acquire) == 2) {
            engine.deliverError(sessionId, firstError);
            errorState.store(3, std::memory_order_release);
        }
        RelayEvent latest{}, xrun{}, gate{};
        bool hasLatest = false, hasXrun = false, hasGate = false;
        uint32_t t = tail.load(std::memory_order_relaxed);
        const uint32_t h = head.load(std::memory_order_acquire);
        while (t != h) {
            auto e = queue[t++ % capacity];
            if (e.type == RelayEventType::XRun) {
                xrun = {e.sessionId, e.sequence, e.type, xrun.value1 + e.value1, xrun.value2 + e.value2};
                hasXrun = true;
            } else if (e.type == RelayEventType::GateStall) { gate = e; hasGate = true; }
            else { latest = e; hasLatest = true; }
        }
        tail.store(t, std::memory_order_release);
        RelayEvent overflow;
        if (overflowState.take(stateSeen, overflow, sessionId) && (!hasLatest || overflow.sequence > latest.sequence)) { latest = overflow; hasLatest = true; }
        if (overflowXrun.take(xrunSeen, overflow, sessionId)) {
            xrun = {sessionId, std::max(overflow.sequence, xrun.sequence), RelayEventType::XRun,
                xrun.value1 + overflow.value1 - inputSeen, xrun.value2 + overflow.value2 - outputSeen};
            inputSeen = overflow.value1; outputSeen = overflow.value2; hasXrun = true;
        }
        if (overflowGate.take(gateSeen, overflow, sessionId) && (!hasGate || overflow.sequence > gate.sequence)) { gate = overflow; hasGate = true; }
        if (!exiting()) {
            std::array<RelayEvent, 3> batch{};
            size_t n = 0;
            if (hasLatest) batch[n++] = latest;
            if (hasXrun) batch[n++] = xrun;
            if (hasGate) batch[n++] = gate;
            std::sort(batch.begin(), batch.begin() + n, [](const auto& a, const auto& b) { return a.sequence < b.sequence; });
            for (size_t i = 0; i < n; ++i) engine.deliverEvent(batch[i]);
        }
        if (quitting.load(std::memory_order_acquire)) return;
    }
}
void RelayEventDispatcher::stop() {
    quitting.store(true, std::memory_order_release); wake();
    // Listener callbacks only post onto Main, never call nativeStop inline.
    if (worker.joinable()) worker.join();
    if (fd >= 0) { close(fd); fd = -1; }
}

void RelayEventDispatcher::OverflowSlot::reset() {
    version = 0; sequence = 0; type = 0; low1 = 0; high1 = 0; low2 = 0; high2 = 0;
}
void RelayEventDispatcher::OverflowSlot::store(const RelayEvent& e) {
    const auto v = version.load(std::memory_order_relaxed);
    version.store(v + 1, std::memory_order_seq_cst);
    sequence.store(e.sequence, std::memory_order_seq_cst);
    type.store(static_cast<uint32_t>(e.type), std::memory_order_seq_cst);
    low1.store(static_cast<uint32_t>(e.value1), std::memory_order_seq_cst);
    high1.store(static_cast<uint64_t>(e.value1) >> 32, std::memory_order_seq_cst);
    low2.store(static_cast<uint32_t>(e.value2), std::memory_order_seq_cst);
    high2.store(static_cast<uint64_t>(e.value2) >> 32, std::memory_order_seq_cst);
    version.store(v + 2, std::memory_order_seq_cst);
}
bool RelayEventDispatcher::OverflowSlot::take(uint32_t& seen, RelayEvent& e, uint32_t session) const {
    const auto v = version.load(std::memory_order_seq_cst);
    if (v == seen || (v & 1)) return false;
    RelayEvent candidate{session, sequence.load(std::memory_order_seq_cst), static_cast<RelayEventType>(type.load(std::memory_order_seq_cst)),
        static_cast<int64_t>((uint64_t(high1.load(std::memory_order_seq_cst)) << 32) | low1.load(std::memory_order_seq_cst)),
        static_cast<int64_t>((uint64_t(high2.load(std::memory_order_seq_cst)) << 32) | low2.load(std::memory_order_seq_cst))};
    if (v != version.load(std::memory_order_seq_cst)) return false;
    seen = v; e = candidate; return true;
}
