#pragma once
#include "RelayEventDispatcher.h"
#include <aaudio/AAudio.h>
#include <atomic>
#include <cstddef>
class RelayEngine;
struct RelayAudioOps {
    int32_t (*read)(AAudioStream*, void*, int32_t, int64_t) = AAudioStream_read;
    int64_t (*written)(AAudioStream*) = AAudioStream_getFramesWritten;
    int64_t (*readFrames)(AAudioStream*) = AAudioStream_getFramesRead;
    int32_t (*xruns)(AAudioStream*) = AAudioStream_getXRunCount;
    int32_t (*bufferSize)(AAudioStream*) = AAudioStream_getBufferSizeInFrames;
};
class RelayCallbackMachine {
public:
    enum class State { DRAIN, DISCARD, RUNNING, EXIT };
    explicit RelayCallbackMachine(RelayEngine& owner) : engine(owner) {}
    void prepare(int64_t threshold, int32_t backlog);
    aaudio_data_callback_result_t process(void* data, int32_t frames);
    void requestExit(const RelayErrorRecord& error);
    uint32_t stateSnapshot() const { return visibleState.load(std::memory_order_relaxed); }
    static bool checkedMultiply(size_t a, size_t b, size_t& result);
private:
    friend std::string runCallbackMachineTests();
    void transitionTo(State next);
    void enterDrain(); void enterDiscard(); void enterRunning(); void enterExit(const RelayErrorRecord& error);
    aaudio_data_callback_result_t processDrain(void*, int32_t);
    aaudio_data_callback_result_t processDiscard(void*, int32_t);
    aaudio_data_callback_result_t processRunning(void*, int32_t);
    aaudio_data_callback_result_t processExit(void*, int32_t);
    aaudio_data_callback_result_t fail(void*, int32_t, RelayErrorKind, int32_t, int32_t, uint64_t = 0);
    int64_t available() const;
    int32_t read(int32_t frames);
    void discarded(int32_t got);
    struct DrainStateData { int executions = 0, stable = 0; } drain;
    struct DiscardStateData { int executions = 0, stable = 0; int64_t previousAvailable = -1; } discard;
    struct RunningStateData { int32_t inputXrun = -1, outputXrun = -1; int64_t gateStallFrames = 0; bool stallNotified = false; } running;
    struct ExitStateData { bool stopped = false; } exit;
    State state = State::DRAIN;
    std::atomic<uint32_t> visibleState{0};
    RelayEngine& engine;
    RelayAudioOps ops;
    int64_t inputThresholdFrames = 0;
    int32_t backlogThresholdFrames = 0;
    bool startup = true;
};
