#include "RelayCallbackMachine.h"
#include "RelayEngine.h"
#include <algorithm>
#include <limits>

bool RelayCallbackMachine::checkedMultiply(size_t a, size_t b, size_t& result) {
    if (b && a > std::numeric_limits<size_t>::max() / b) return false;
    result = a * b; return true;
}
void RelayCallbackMachine::prepare(int64_t threshold, int32_t backlog, RelayPhaseLimits limits) {
    state = State::DRAIN; visibleState = 0; startup = true;
    drain = {}; discard = {}; running = {}; exit = {};
    inputThresholdFrames = threshold; backlogThresholdFrames = backlog;
    phaseLimits = limits;
    visibleBacklogLimit = backlog > 0 ? backlog : 0;
    recoveryCount = 0;
}
void RelayCallbackMachine::requestExit(const RelayErrorRecord& error) { engine.events.requestExit(error); }
void RelayCallbackMachine::enterDrain() {
    drain = {};
    recoveryCount.fetch_add(1, std::memory_order_relaxed);
    engine.events.publish(RelayEventType::Drain);
}
void RelayCallbackMachine::enterDiscard() { discard = {}; engine.events.publish(RelayEventType::Discard); }
void RelayCallbackMachine::enterRunning() {
    running = {};
    running.inputXrun = ops.xruns(engine.input);
    running.outputXrun = ops.xruns(engine.output);
    startup = false;
    engine.events.publish(RelayEventType::Running);
}
void RelayCallbackMachine::enterExit(const RelayErrorRecord& error) {
    requestExit(error); transitionTo(State::EXIT);
}
void RelayCallbackMachine::transitionTo(State next) {
    if (state == State::EXIT || state == next) return;
    state = next; visibleState.store(static_cast<uint32_t>(state), std::memory_order_relaxed);
    switch (next) {
        case State::DRAIN: enterDrain(); break;
        case State::DISCARD: enterDiscard(); break;
        case State::RUNNING: enterRunning(); break;
        case State::EXIT: exit.stopped = true; break;
    }
}
int64_t RelayCallbackMachine::available() const {
    const int64_t w = ops.written(engine.input), r = ops.readFrames(engine.input);
    return w >= 0 && r >= 0 && w >= r ? w - r : -1;
}
int32_t RelayCallbackMachine::read(int32_t frames) { return ops.read(engine.input, engine.scratch.data(), frames, 0); }
void RelayCallbackMachine::discarded(int32_t got) {
    if (got > 0) (startup ? engine.startupFramesDiscarded : engine.runtimeFramesDiscarded)
            .fetch_add(static_cast<uint32_t>(got), std::memory_order_relaxed);
}
aaudio_data_callback_result_t RelayCallbackMachine::fail(void* data, int32_t frames,
        RelayErrorKind kind, int32_t code, int32_t requested, uint64_t required) {
    enterExit({kind, code, static_cast<int32_t>(state), requested, required, engine.scratch.size()});
    return processExit(data, frames);
}
aaudio_data_callback_result_t RelayCallbackMachine::processExit(void* data, int32_t frames) {
    engine.fillSilence(data, 0, frames); return AAUDIO_CALLBACK_RESULT_STOP;
}
aaudio_data_callback_result_t RelayCallbackMachine::process(void* data, int32_t frames) {
    size_t outBytes = 0, inBytes = 0;
    if (!data || frames <= 0 || !engine.outputBytesPerFrame ||
        !checkedMultiply(static_cast<size_t>(frames), engine.outputBytesPerFrame, outBytes)) {
        enterExit({RelayErrorKind::Bounds, AAUDIO_ERROR_OUT_OF_RANGE, static_cast<int32_t>(state), frames, 0, engine.scratch.size()});
        return AAUDIO_CALLBACK_RESULT_STOP; // output length/pointer is unsafe
    }
    if (engine.events.exiting() || !engine.callbackReady.load(std::memory_order_acquire)) {
        transitionTo(State::EXIT); return processExit(data, frames);
    }
    if (!engine.input || !engine.output || !engine.inputBytesPerFrame ||
        !checkedMultiply(static_cast<size_t>(frames), engine.inputBytesPerFrame, inBytes) ||
        std::max(outBytes, inBytes) > engine.scratch.size()) {
        return fail(data, frames, RelayErrorKind::Bounds, AAUDIO_ERROR_OUT_OF_RANGE, frames,
                    std::max<uint64_t>(outBytes, static_cast<uint64_t>(frames) * engine.inputBytesPerFrame));
    }
    switch (state) {
        case State::DRAIN: return processDrain(data, frames);
        case State::DISCARD: return processDiscard(data, frames);
        case State::RUNNING: return processRunning(data, frames);
        case State::EXIT: return processExit(data, frames);
    }
    return processExit(data, frames);
}
aaudio_data_callback_result_t RelayCallbackMachine::processDrain(void* data, int32_t frames) {
    size_t required = 0;
    if (engine.inputCapacity <= 0 || !checkedMultiply(engine.inputCapacity, engine.inputBytesPerFrame, required) || required > engine.scratch.size())
        return fail(data, frames, RelayErrorKind::Bounds, AAUDIO_ERROR_OUT_OF_RANGE, engine.inputCapacity,
                    static_cast<uint64_t>(std::max(0, engine.inputCapacity)) * engine.inputBytesPerFrame);
    const int got = read(engine.inputCapacity);
    if (got < 0) return fail(data, frames, RelayErrorKind::Read, got, engine.inputCapacity);
    discarded(got);
    const int64_t a = available(), slack = std::max(int64_t(frames) * 2, int64_t(engine.outBurst) * 2);
    drain.stable = got < engine.inputCapacity && a >= 0 && a <= slack ? drain.stable + 1 : 0;
    if (++drain.executions >= phaseLimits.drainMax || drain.stable >= phaseLimits.drainStable) transitionTo(State::DISCARD);
    engine.fillSilence(data, 0, frames); return AAUDIO_CALLBACK_RESULT_CONTINUE;
}
aaudio_data_callback_result_t RelayCallbackMachine::processDiscard(void* data, int32_t frames) {
    const int got = read(frames);
    if (got < 0) return fail(data, frames, RelayErrorKind::Read, got, frames);
    discarded(got);
    const int64_t a = available(), prev = discard.previousAvailable;
    const bool stable = got < frames || (a >= 0 && prev >= 0 && (a >= prev ? a - prev : prev - a) <= frames);
    if (a >= 0) discard.previousAvailable = a;
    discard.stable = stable ? discard.stable + 1 : 0;
    if (++discard.executions >= phaseLimits.discardMax || discard.stable >= phaseLimits.discardStable) transitionTo(State::RUNNING);
    engine.fillSilence(data, 0, frames); return AAUDIO_CALLBACK_RESULT_CONTINUE;
}
aaudio_data_callback_result_t RelayCallbackMachine::processRunning(void* data, int32_t frames) {
    const int32_t ix = ops.xruns(engine.input), ox = ops.xruns(engine.output);
    const int64_t di = ix >= 0 && running.inputXrun >= 0 && ix > running.inputXrun ? int64_t(ix) - running.inputXrun : 0;
    const int64_t dout = ox >= 0 && running.outputXrun >= 0 && ox > running.outputXrun ? int64_t(ox) - running.outputXrun : 0;
    running.inputXrun = ix; running.outputXrun = ox;
    if (di || dout) {
        running.backlogHighFrames = 0;
        if (di) {
            size_t required = 0;
            if (engine.inputCapacity <= 0 || !checkedMultiply(engine.inputCapacity, engine.inputBytesPerFrame, required) || required > engine.scratch.size())
                return fail(data, frames, RelayErrorKind::Bounds, AAUDIO_ERROR_OUT_OF_RANGE, engine.inputCapacity,
                            static_cast<uint64_t>(std::max(0, engine.inputCapacity)) * engine.inputBytesPerFrame);
            const int got = read(engine.inputCapacity);
            if (got < 0) return fail(data, frames, RelayErrorKind::Read, got, engine.inputCapacity);
            discarded(got);
        }
        engine.events.publish(RelayEventType::XRun, di, dout);
        engine.fillSilence(data, 0, frames); return AAUDIO_CALLBACK_RESULT_CONTINUE;
    }
    const int size = inputThresholdFrames > 0 ? ops.bufferSize(engine.input) : 0;
    const int64_t threshold = size > 0 ? std::min(inputThresholdFrames, int64_t(size)) : inputThresholdFrames;
    // Same nominal sample rate does not synchronize capture/playback clocks.
    // A short read (or an output-only XRun) can also leave a permanent extra
    // queue: normal callbacks consume at most exactly as much as they output.
    // Automatic mode keeps two scheduling quanta above a complete read/gate,
    // and requires a full second of continuously high *callback* observations.
    // UI polling phase and isolated scheduling jitter cannot trigger recovery.
    const bool automaticBacklog = backlogThresholdFrames == -1;
    const int64_t quantum = std::max({int64_t(frames), int64_t(engine.inBurst), int64_t(engine.outBurst)});
    const int64_t limit = automaticBacklog
            ? std::max(threshold, int64_t(frames)) + 2 * quantum : backlogThresholdFrames;
    visibleBacklogLimit.store(static_cast<uint32_t>(std::max(int64_t(0),
            std::min(limit, int64_t(INT32_MAX)))), std::memory_order_relaxed);
    const bool high = limit > 0 && available() > limit;
    running.backlogHighFrames = high ? running.backlogHighFrames + frames : 0;
    if (high && (!automaticBacklog || running.backlogHighFrames >= std::max(1, engine.outRate))) {
        transitionTo(State::DRAIN);
        engine.fillSilence(data, 0, frames); return AAUDIO_CALLBACK_RESULT_CONTINUE;
    }
    if (inputThresholdFrames > 0) {
        const int64_t a = available();
        if (a >= 0 && a < threshold) {
            running.gateStallFrames += frames;
            if (running.gateStallFrames <= std::max(1, engine.outRate)) {
                engine.fillSilence(data, 0, frames); return AAUDIO_CALLBACK_RESULT_CONTINUE;
            }
            running.gateStallFrames = 0; // allow one read, then protect again
            if (!running.stallNotified) {
                running.stallNotified = true; engine.events.publish(RelayEventType::GateStall, a, threshold);
            }
        } else { running.gateStallFrames = 0; running.stallNotified = false; }
    }
    const int got = read(frames);
    if (got < 0) return fail(data, frames, RelayErrorKind::Read, got, frames);
    if (got > frames) return fail(data, frames, RelayErrorKind::Bounds, AAUDIO_ERROR_OUT_OF_RANGE, got);
    RelayEngine::Plan plan;
    const uint32_t valid = UINT32_MAX >> (32 - engine.inChannels);
    for (int o = 0; o < engine.outChannels; ++o) {
        plan.masks[o] = engine.routingMasks[o].load(std::memory_order_relaxed) & valid;
        plan.counts[o] = __builtin_popcount(plan.masks[o]);
    }
    if (got > 0) engine.mixFrames(engine.scratch.data(), data, got, 0, plan);
    engine.fillSilence(data, got, frames);
    return AAUDIO_CALLBACK_RESULT_CONTINUE;
}
