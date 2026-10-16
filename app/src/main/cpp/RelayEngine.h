#ifndef RELAYENGINE_H
#define RELAYENGINE_H

#include <aaudio/AAudio.h>

#include <atomic>
#include <array>
#include <cstdint>
#include <mutex>
#include <string>
#include <utility>
#include <vector>

#include <jni.h>
#include "RelayCallbackMachine.h"

/**
 * Single input -> single output AAudio relay engine.
 *
 * The output stream owns the data callback and pulls frames from the input
 * stream with AAudioStream_read (OboeTester-style callback-driven relay).
 * Per output channel, zero or more input channels are averaged into the
 * output sample (mixer mask = bit i set means input channel i feeds it).
 *
 * Threading: prepare()/startPrepared()/stop() are blocking and serialize on lifecycleMutex.
 * Callbacks never take that mutex. Routing uses lock-free 32-bit masks;
 * the callback snapshots them without allocating or releasing heap objects.
 */
struct StartConfig {
    int32_t inDeviceId = 0;
    int32_t inChannelCount = 0;   // 0 = let AAudio choose
    int32_t inSampleRate = 0;
    int32_t inFormat = 0;         // AAUDIO_FORMAT_UNSPECIFIED
    bool inExclusive = false;
    int32_t inPerformanceMode = AAUDIO_PERFORMANCE_MODE_LOW_LATENCY;
    int32_t inInputPreset = AAUDIO_INPUT_PRESET_GENERIC;
    int32_t inBufferCapacity = 0;
    // 0 = keep the system default buffer size (no setter call);
    // N = request N * actual FramesPerBurst, capped at actual capacity.
    int32_t inBufferSizeBursts = 0;

    int32_t outDeviceId = 0;
    int32_t outChannelCount = 0;
    int32_t outSampleRate = 0;
    int32_t outFormat = 0;
    bool outExclusive = false;
    int32_t outPerformanceMode = AAUDIO_PERFORMANCE_MODE_LOW_LATENCY;
    int32_t outUsage = AAUDIO_USAGE_MEDIA;
    int32_t outContentType = AAUDIO_CONTENT_TYPE_MUSIC;
    int32_t outBufferCapacity = 0;
    int32_t outBufferSizeBursts = 4;
    // Input read threshold F (minimum available input frames before the
    // output callback may read): bursts * actual output FramesPerBurst +
    // extra frames. 0 disables the gate entirely.
    int32_t inputReadThresholdBursts = 0;
    int32_t inputReadThresholdExtraFrames = 0;

    // A positive backlog threshold triggers RUNNING -> DRAIN. Zero disables
    // the check. Each phase is bounded; the number of recovery cycles is not.
    int32_t inputBacklogThresholdFrames = 0;

    // Per-direction MMAP policy request: true = platform policy (system
    // decides, may fall back to non-MMAP), false = AAUDIO_POLICY_NEVER.
    // AAUDIO_POLICY_ALWAYS is deliberately never used. Independent of the
    // inExclusive/outExclusive sharing-mode choices.
    bool inMMapEnabled = true;
    bool outMMapEnabled = true;
};

class RelayEngine {
public:
    static RelayEngine& get();

    // Blocking. Returns empty string on success, human-readable error otherwise.
    std::string prepare(const StartConfig& cfg, const std::vector<int32_t>& mixerMasks);
    std::string startPrepared(const std::vector<int32_t>& thresholds);
    uint32_t sessionId() const { return currentSession.load(); }
    // Blocking and idempotent; safe when streams already died from an error.
    std::string stop();
#ifndef NDEBUG
    void failNextScratchAllocation() { failAllocation = true; }
#endif
    bool isRunning() const { return running.load(); }

    // Realtime-safe mixer update; outChannel is 0-based.
    void setMixerMask(int32_t outChannel, int32_t mask);

    // Live stream parameters for the UI, direction = AAUDIO_DIRECTION_*.
    // Returns flat key/value pairs; empty when no stream is open.
    std::vector<std::pair<std::string, std::string>> streamInfo(int32_t direction);

    // Only the dispatcher calls JNI; listeners post work to the main thread.
    void attachListener(JNIEnv* env, jobject listener);

    void deliverError(uint32_t session, const RelayErrorRecord& error);
    static std::string errorText(const RelayErrorRecord& error);
    void deliverEvent(const RelayEvent& event);

    static aaudio_data_callback_result_t dataCallback(
            AAudioStream* stream, void* userData, void* audioData, int32_t numFrames);
    static void errorCallback(AAudioStream* stream, void* userData, aaudio_result_t error);

private:
    RelayEngine() : callbackMachine(*this) {}
    friend class RelayCallbackMachine;
    friend std::string runCallbackMachineTests();

    struct Plan {
        std::array<uint32_t, 32> masks{};
        std::array<int32_t, 32> counts{};
    };

    aaudio_data_callback_result_t render(void* audioData, int32_t numFrames);
    void mixFrames(const void* input, void* output, int32_t frames, int32_t outFrameOffset,
                   const Plan& plan);
    void fillSilence(void* audioData, int32_t fromFrame, int32_t toFrame);
    std::string openStreams(const StartConfig& cfg, const std::vector<int32_t>& mixerMasks);
    void closeStreams(std::string& firstError);


    std::mutex lifecycleMutex;
    // Atomic so the audio callback can snapshot them without the mutex.
    std::atomic<AAudioStream*> input{nullptr};
    std::atomic<AAudioStream*> output{nullptr};
    std::atomic<bool> running{false};
    // Independent of running: enabled once cached geometry and callback
    // state are initialized and before requestStart(output), so callbacks
    // firing while the output STARTED wait is still in progress already run
    // the startup input drain instead of being gated by running == false.
    std::atomic<bool> callbackReady{false};
    std::atomic<uint32_t> currentSession{0};
    RelayEventDispatcher events;
    RelayCallbackMachine callbackMachine;

    static_assert(std::atomic<uint32_t>::is_always_lock_free);
    static_assert(std::atomic<AAudioStream*>::is_always_lock_free);
    std::array<std::atomic<uint32_t>, 32> routingMasks{};

    // Cached stream geometry, valid while running.
    aaudio_format_t format = AAUDIO_FORMAT_UNSPECIFIED;
    int32_t inChannels = 0, outChannels = 0;
    int32_t inRate = 0, outRate = 0;
    int32_t outBurst = 0;
    int32_t inputCapacity = 0, outputCapacity = 0;
    size_t inputBytesPerFrame = 0, outputBytesPerFrame = 0;
    std::atomic<uint32_t> startupFramesDiscarded{0}, runtimeFramesDiscarded{0};
    std::vector<uint8_t> scratch;
#ifndef NDEBUG
    bool failAllocation = false;
#endif
#ifndef NDEBUG
    // Debug-only evidence that the callback has mixed real PCM, not just zeros.
    std::atomic<bool> nonSilentOutputSeen{false};
#endif

    std::atomic<JavaVM*> jvm{nullptr};
    std::mutex listenerMutex;
    jobject listener = nullptr;   // global ref
    jmethodID onErrorMethod = nullptr, onEventMethod = nullptr;
};

#endif // RELAYENGINE_H
