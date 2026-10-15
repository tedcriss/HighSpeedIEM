#ifndef RELAYENGINE_H
#define RELAYENGINE_H

#include <aaudio/AAudio.h>

#include <atomic>
#include <cstdint>
#include <memory>
#include <mutex>
#include <string>
#include <utility>
#include <vector>

#include <jni.h>

/**
 * Single input -> single output AAudio relay engine.
 *
 * The output stream owns the data callback and pulls frames from the input
 * stream with AAudioStream_read (OboeTester-style callback-driven relay).
 * Per output channel, zero or more input channels are averaged into the
 * output sample (mixer mask = bit i set means input channel i feeds it).
 *
 * Threading: start()/stop() are blocking and serialize on lifecycleMutex.
 * Callbacks never take that mutex. The mixing plan is swapped as an
 * atomically-published shared_ptr so setMixerMask() stays realtime-safe.
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

    int32_t outDeviceId = 0;
    int32_t outChannelCount = 0;
    int32_t outSampleRate = 0;
    int32_t outFormat = 0;
    bool outExclusive = false;
    int32_t outPerformanceMode = AAUDIO_PERFORMANCE_MODE_LOW_LATENCY;
    int32_t outUsage = AAUDIO_USAGE_MEDIA;
    int32_t outContentType = AAUDIO_CONTENT_TYPE_MUSIC;
    int32_t outBufferCapacity = 0;
};

class RelayEngine {
public:
    static RelayEngine& get();

    // Blocking. Returns empty string on success, human-readable error otherwise.
    std::string start(const StartConfig& cfg, const std::vector<int32_t>& mixerMasks);
    // Blocking and idempotent; safe when streams already died from an error.
    std::string stop();
    bool isRunning() const { return running.load(); }

    // Realtime-safe mixer update; outChannel is 0-based.
    void setMixerMask(int32_t outChannel, int32_t mask);

    // Live stream parameters for the UI, direction = AAUDIO_DIRECTION_*.
    // Returns flat key/value pairs; empty when no stream is open.
    std::vector<std::pair<std::string, std::string>> streamInfo(int32_t direction);

    // Listener receives void onNativeError(String) on arbitrary threads.
    void attachListener(JNIEnv* env, jobject listener);

    void notifyError(const std::string& text);

    static aaudio_data_callback_result_t dataCallback(
            AAudioStream* stream, void* userData, void* audioData, int32_t numFrames);
    static void errorCallback(AAudioStream* stream, void* userData, aaudio_result_t error);

private:
    RelayEngine() = default;

    struct Plan {
        int32_t inputChannels = 0;
        int32_t outputChannels = 0;
        // Per output channel: list of input channel indices to average.
        std::vector<std::vector<int32_t>> sources;
    };

    aaudio_data_callback_result_t render(void* audioData, int32_t numFrames);
    void mixFrames(const void* input, void* output, int32_t frames, int32_t outFrameOffset,
                   const Plan& plan);
    void fillSilence(void* audioData, int32_t fromFrame, int32_t toFrame);
    std::string openStreams(const StartConfig& cfg, const std::vector<int32_t>& mixerMasks);
    void closeStreams(std::string& firstError);
    // Rebuild the published mixing plan from pendingMasks and cached geometry.
    void applyPlanLocked();

    std::mutex lifecycleMutex;
    // Atomic so the audio callback can snapshot them without the mutex.
    std::atomic<AAudioStream*> input{nullptr};
    std::atomic<AAudioStream*> output{nullptr};
    std::atomic<bool> running{false};
    std::atomic<bool> errorNotified{false};

    std::mutex planMutex;
    std::shared_ptr<const Plan> plan;
    std::vector<int32_t> pendingMasks;   // masks given at start, used to (re)build the plan

    // Cached stream geometry, valid while running.
    aaudio_format_t format = AAUDIO_FORMAT_UNSPECIFIED;
    int32_t inChannels = 0, outChannels = 0;
    int32_t inRate = 0, outRate = 0;
    int32_t inBurst = 0, outBurst = 0;

    std::vector<uint8_t> scratch;
    int32_t scratchFrames = 0;

    JavaVM* jvm = nullptr;
    std::mutex listenerMutex;
    jobject listener = nullptr;   // global ref
    jmethodID onErrorMethod = nullptr;
};

#endif // RELAYENGINE_H
