#pragma once
#include <atomic>
#include <array>
#include <cstdint>
#include <thread>
#include <string>

enum class RelayEventType : int32_t { Drain = 1, Discard, Running, XRun, GateStall };
struct RelayEvent { uint32_t sessionId = 0, sequence = 0; RelayEventType type{}; int64_t value1 = 0, value2 = 0; };
enum class RelayErrorKind : int32_t { Read, Bounds, Geometry, Platform };
struct RelayErrorRecord {
    RelayErrorKind kind{};
    int32_t code = 0, state = 0, requestedFrames = 0;
    uint64_t requiredBytes = 0, scratchBytes = 0;
};
class RelayEngine;
// Ordinary events have exactly one producer: the output data callback. Errors
// use a separate first-writer mailbox shared by control and AAudio threads.
class RelayEventDispatcher {
public:
    ~RelayEventDispatcher();
    std::string prepare(RelayEngine& engine, uint32_t session);
    void stop(); // only after every AAudio producer has finished
    void publish(RelayEventType type, int64_t a = 0, int64_t b = 0);
    void requestExit(const RelayErrorRecord& error);
    void requestStop() { stopRequested.store(true, std::memory_order_release); }
    bool fatalRecord(RelayErrorRecord& result) const {
        if (errorState.load(std::memory_order_acquire) < 2) return false;
        result = firstError; return true;
    }
    bool exiting() const { return stopRequested.load(std::memory_order_acquire); }
private:
    friend std::string runCallbackMachineTests();
    void wake();
    void consume(RelayEngine& engine);
    static constexpr uint32_t capacity = 64;
    std::array<RelayEvent, capacity> queue{};
    std::atomic<uint32_t> head{0}, tail{0}, errorState{0};
    // Single-producer overflow slots. Every field is atomic (including split
    // 64-bit values), so an interrupted snapshot has no C++ data race. Readers
    // retry on the next event wake; the audio producer never retries or waits.
    struct OverflowSlot {
        std::atomic<uint32_t> version{0}, sequence{0}, type{0};
        std::atomic<uint32_t> low1{0}, high1{0}, low2{0}, high2{0};
        void store(const RelayEvent& event);
        bool take(uint32_t& seen, RelayEvent& event, uint32_t session) const;
        void reset();
    } overflowState, overflowXrun, overflowGate;
    uint64_t overflowInputTotal = 0, overflowOutputTotal = 0; // producer-owned
    std::atomic<bool> stopRequested{false}, quitting{false};
    RelayErrorRecord firstError{};
    uint32_t sessionId = 0, sequence = 0;
    int fd = -1; // immutable from prepare until all producers + worker finish
    std::thread worker;
    static_assert(std::atomic<uint32_t>::is_always_lock_free);
    static_assert(std::atomic<bool>::is_always_lock_free);
};
