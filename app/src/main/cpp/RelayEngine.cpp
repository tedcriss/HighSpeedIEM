#include "RelayEngine.h"

#include <dlfcn.h>

#include <algorithm>
#include <cmath>
#include <cstring>

#include <android/log.h>

#define LOG_TAG "RelayEngine"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)

namespace {

constexpr int64_t kStateTimeoutNs = 2'000'000'000LL;
constexpr int32_t kMinScratchFrames = 8192;
// Like Oboe's full-duplex helper, discard startup capture backlog. Each
// callback performs bounded non-blocking reads, including on MMAP. The
// nominal counts are caps: phases may end earlier on confirmed stability
// and the drain may extend while an obvious backlog remains, always bounded
// by the hard caps and the total startup frame budget below.
constexpr int32_t kStartupDrainCallbacks = 20;
// OboeTester FullDuplexStream startup sequence: after the drain, optionally
// let input accumulate a cushion (0 = skip), then run discard callbacks so
// input and output reach equilibrium before forwarding starts.
constexpr int32_t kStartupCushionCallbacks = 0;
constexpr int32_t kStartupDiscardCallbacks = 30;
// Consecutive "stable" callbacks (actual short reads plus a settled
// availability hint) that confirm a startup phase is done.
constexpr int32_t kStartupStableCallbacks = 3;
// The discard phase must not enter normal forwarding on its count alone:
// after the nominal count runs out it keeps confirming equilibrium until
// this many consecutive stable callbacks, bounded by the hard cap.
constexpr int32_t kDiscardStableCallbacks = 5;
// Absolute callback caps for the drain/discard phases, including any
// extensions granted while a backlog remains (~2s and ~1.5s of callbacks).
constexpr int32_t kStartupDrainHardCap = 200;
constexpr int32_t kStartupDiscardHardCap = 150;
// Total frames the whole startup sync may discard (4 seconds of input).
constexpr int64_t kStartupBudgetSeconds = 4;
// Runtime backlog recovery: per-episode discard budget (1 second of input)
// and post-episode cooldown (1 second of output) before a retrigger.
constexpr int64_t kBacklogEpisodeSeconds = 1;
constexpr int64_t kBacklogCooldownSeconds = 1;

/**
 * Shared availability probe used by the callback input gate and streamInfo():
 * FramesWritten - FramesRead on one stream, via the 64-bit counters.
 * Input stream: frames captured but not yet read by the app. Output stream:
 * frames written but not yet consumed by the endpoint (queued for playback),
 * NOT the free writable space. Returns a negative value when a counter query
 * fails or the difference is nonsensical; callers must never treat a negative
 * result as a frame count. Read-only and realtime-safe: never calls
 * AAudioStream_read()/write(), never allocates.
 */
int64_t getAvailableFrames(AAudioStream* stream) {
    if (!stream) return -1;
    int64_t written = AAudioStream_getFramesWritten(stream);
    if (written < 0) return written;
    int64_t read = AAudioStream_getFramesRead(stream);
    if (read < 0) return read;
    int64_t available = written - read;
    return available >= 0 ? available : -1;
}

// AAudio_setMMapPolicy / AAudio_getMMapPolicy / AAudioStream_isMMapUsed
// exist from API 29; resolve them at runtime so minSdk 27 builds keep working.
using SetMMapPolicyFn = aaudio_result_t (*)(aaudio_policy_t);
using GetMMapPolicyFn = aaudio_policy_t (*)();
using IsMMapUsedFn = bool (*)(AAudioStream*);

SetMMapPolicyFn setMMapPolicyFn() {
    static SetMMapPolicyFn fn = [] {
        void* lib = dlopen("libaaudio.so", RTLD_NOW);
        return lib ? reinterpret_cast<SetMMapPolicyFn>(dlsym(lib, "AAudio_setMMapPolicy")) : nullptr;
    }();
    return fn;
}

GetMMapPolicyFn getMMapPolicyFn() {
    static GetMMapPolicyFn fn = [] {
        void* lib = dlopen("libaaudio.so", RTLD_NOW);
        return lib ? reinterpret_cast<GetMMapPolicyFn>(dlsym(lib, "AAudio_getMMapPolicy")) : nullptr;
    }();
    return fn;
}

IsMMapUsedFn isMMapUsedFn() {
    static IsMMapUsedFn fn = [] {
        void* lib = dlopen("libaaudio.so", RTLD_NOW);
        return lib ? reinterpret_cast<IsMMapUsedFn>(dlsym(lib, "AAudioStream_isMMapUsed")) : nullptr;
    }();
    return fn;
}

int32_t bytesPerFrame(aaudio_format_t format) {
    switch (format) {
        case AAUDIO_FORMAT_PCM_I16: return 2;
        case AAUDIO_FORMAT_PCM_I24_PACKED: return 3;
        case AAUDIO_FORMAT_PCM_I32:
        case AAUDIO_FORMAT_PCM_FLOAT: return 4;
        default: return 0;
    }
}

const char* formatName(int32_t v) {
    switch (v) {
        case AAUDIO_FORMAT_INVALID: return "AAUDIO_FORMAT_INVALID";
        case AAUDIO_FORMAT_UNSPECIFIED: return "AAUDIO_FORMAT_UNSPECIFIED";
        case AAUDIO_FORMAT_PCM_I16: return "AAUDIO_FORMAT_PCM_I16";
        case AAUDIO_FORMAT_PCM_FLOAT: return "AAUDIO_FORMAT_PCM_FLOAT";
        case AAUDIO_FORMAT_PCM_I24_PACKED: return "AAUDIO_FORMAT_PCM_I24_PACKED";
        case AAUDIO_FORMAT_PCM_I32: return "AAUDIO_FORMAT_PCM_I32";
        default: return "AAUDIO_FORMAT_UNKNOWN";
    }
}

const char* performanceName(int32_t v) {
    switch (v) {
        case AAUDIO_PERFORMANCE_MODE_NONE: return "AAUDIO_PERFORMANCE_MODE_NONE";
        case AAUDIO_PERFORMANCE_MODE_POWER_SAVING: return "AAUDIO_PERFORMANCE_MODE_POWER_SAVING";
        case AAUDIO_PERFORMANCE_MODE_LOW_LATENCY: return "AAUDIO_PERFORMANCE_MODE_LOW_LATENCY";
        default: return "AAUDIO_PERFORMANCE_MODE_UNKNOWN";
    }
}

const char* sharingName(int32_t v) {
    switch (v) {
        case AAUDIO_SHARING_MODE_EXCLUSIVE: return "AAUDIO_SHARING_MODE_EXCLUSIVE";
        case AAUDIO_SHARING_MODE_SHARED: return "AAUDIO_SHARING_MODE_SHARED";
        default: return "AAUDIO_SHARING_MODE_UNKNOWN";
    }
}

const char* presetName(int32_t v) {
    switch (v) {
        case AAUDIO_INPUT_PRESET_GENERIC: return "AAUDIO_INPUT_PRESET_GENERIC";
        case AAUDIO_INPUT_PRESET_CAMCORDER: return "AAUDIO_INPUT_PRESET_CAMCORDER";
        case AAUDIO_INPUT_PRESET_VOICE_RECOGNITION: return "AAUDIO_INPUT_PRESET_VOICE_RECOGNITION";
        case AAUDIO_INPUT_PRESET_VOICE_COMMUNICATION:
            return "AAUDIO_INPUT_PRESET_VOICE_COMMUNICATION";
        case AAUDIO_INPUT_PRESET_UNPROCESSED: return "AAUDIO_INPUT_PRESET_UNPROCESSED";
        case AAUDIO_INPUT_PRESET_VOICE_PERFORMANCE: return "AAUDIO_INPUT_PRESET_VOICE_PERFORMANCE";
        case AAUDIO_INPUT_PRESET_SYSTEM_ECHO_REFERENCE:
            return "AAUDIO_INPUT_PRESET_SYSTEM_ECHO_REFERENCE";
        case AAUDIO_INPUT_PRESET_SYSTEM_HOTWORD: return "AAUDIO_INPUT_PRESET_SYSTEM_HOTWORD";
        default: return "AAUDIO_INPUT_PRESET_UNKNOWN";
    }
}

const char* usageName(int32_t v) {
    switch (v) {
        case AAUDIO_USAGE_MEDIA: return "AAUDIO_USAGE_MEDIA";
        case AAUDIO_USAGE_VOICE_COMMUNICATION: return "AAUDIO_USAGE_VOICE_COMMUNICATION";
        case AAUDIO_USAGE_VOICE_COMMUNICATION_SIGNALLING:
            return "AAUDIO_USAGE_VOICE_COMMUNICATION_SIGNALLING";
        case AAUDIO_USAGE_ALARM: return "AAUDIO_USAGE_ALARM";
        case AAUDIO_USAGE_NOTIFICATION: return "AAUDIO_USAGE_NOTIFICATION";
        case AAUDIO_USAGE_NOTIFICATION_RINGTONE: return "AAUDIO_USAGE_NOTIFICATION_RINGTONE";
        case AAUDIO_USAGE_NOTIFICATION_EVENT: return "AAUDIO_USAGE_NOTIFICATION_EVENT";
        case AAUDIO_USAGE_ASSISTANCE_ACCESSIBILITY: return "AAUDIO_USAGE_ASSISTANCE_ACCESSIBILITY";
        case AAUDIO_USAGE_ASSISTANCE_NAVIGATION_GUIDANCE:
            return "AAUDIO_USAGE_ASSISTANCE_NAVIGATION_GUIDANCE";
        case AAUDIO_USAGE_ASSISTANCE_SONIFICATION: return "AAUDIO_USAGE_ASSISTANCE_SONIFICATION";
        case AAUDIO_USAGE_GAME: return "AAUDIO_USAGE_GAME";
        case AAUDIO_USAGE_ASSISTANT: return "AAUDIO_USAGE_ASSISTANT";
        default: return "AAUDIO_USAGE_UNKNOWN";
    }
}

const char* contentTypeName(int32_t v) {
    switch (v) {
        case AAUDIO_CONTENT_TYPE_SPEECH: return "AAUDIO_CONTENT_TYPE_SPEECH";
        case AAUDIO_CONTENT_TYPE_MUSIC: return "AAUDIO_CONTENT_TYPE_MUSIC";
        case AAUDIO_CONTENT_TYPE_MOVIE: return "AAUDIO_CONTENT_TYPE_MOVIE";
        case AAUDIO_CONTENT_TYPE_SONIFICATION: return "AAUDIO_CONTENT_TYPE_SONIFICATION";
        default: return "AAUDIO_CONTENT_TYPE_UNKNOWN";
    }
}

std::string valueOrError(int32_t v) {
    return v >= 0 ? std::to_string(v) : AAudio_convertResultToText(v);
}

int32_t unpack24(const uint8_t* p) {
    int32_t v = static_cast<int32_t>(p[0]) | (static_cast<int32_t>(p[1]) << 8) |
                (static_cast<int32_t>(p[2]) << 16);
    if (v & 0x800000) v |= ~0xFFFFFF;  // sign extend
    return v;
}

void pack24(uint8_t* p, int32_t v) {
    p[0] = static_cast<uint8_t>(v & 0xFF);
    p[1] = static_cast<uint8_t>((v >> 8) & 0xFF);
    p[2] = static_cast<uint8_t>((v >> 16) & 0xFF);
}

template <typename T, typename Acc>
void mixTyped(const T* in, int32_t inChannels, T* out, int32_t outChannels, int32_t frames,
              const std::array<uint32_t, 32>& masks, const std::array<int32_t, 32>& counts) {
    for (int32_t f = 0; f < frames; ++f) {
        const T* src = in + static_cast<size_t>(f) * inChannels;
        T* dst = out + static_cast<size_t>(f) * outChannels;
        for (int32_t o = 0; o < outChannels; ++o) {
            uint32_t mask = masks[o];
            if (!mask) {
                dst[o] = T{0};
                continue;
            }
            Acc sum = Acc{0};
            while (mask) {
                sum += static_cast<Acc>(src[__builtin_ctz(mask)]);
                mask &= mask - 1;
            }
            dst[o] = static_cast<T>(sum / static_cast<Acc>(counts[o]));
        }
    }
}

void mix24(const uint8_t* in, int32_t inChannels, uint8_t* out, int32_t outChannels, int32_t frames,
           const std::array<uint32_t, 32>& masks, const std::array<int32_t, 32>& counts) {
    for (int32_t f = 0; f < frames; ++f) {
        const uint8_t* src = in + static_cast<size_t>(f) * inChannels * 3;
        uint8_t* dst = out + static_cast<size_t>(f) * outChannels * 3;
        for (int32_t o = 0; o < outChannels; ++o) {
            uint32_t mask = masks[o];
            int32_t value = 0;
            if (mask) {
                int64_t sum = 0;
                while (mask) {
                    sum += unpack24(src + static_cast<size_t>(__builtin_ctz(mask)) * 3);
                    mask &= mask - 1;
                }
                value = static_cast<int32_t>(sum / static_cast<int64_t>(counts[o]));
            }
            pack24(dst + static_cast<size_t>(o) * 3, value);
        }
    }
}

}  // namespace

RelayEngine& RelayEngine::get() {
    static RelayEngine engine;
    return engine;
}

void RelayEngine::attachListener(JNIEnv* env, jobject newListener) {
    env->GetJavaVM(&jvm);
    std::lock_guard<std::mutex> lock(listenerMutex);
    if (listener) {
        env->DeleteGlobalRef(listener);
        listener = nullptr;
        onErrorMethod = nullptr;
    }
    if (newListener) {
        listener = env->NewGlobalRef(newListener);
        jclass cls = env->GetObjectClass(listener);
        onErrorMethod = env->GetMethodID(cls, "onNativeError", "(Ljava/lang/String;)V");
        env->DeleteLocalRef(cls);
        if (!onErrorMethod) {
            env->ExceptionClear();
            LOGE("listener missing onNativeError(String)");
        }
    }
}

void RelayEngine::notifyError(const std::string& text) {
    if (errorNotified.exchange(true)) return;  // report only the first failure per run
    LOGE("%s", text.c_str());
    std::lock_guard<std::mutex> lock(listenerMutex);
    if (!jvm || !listener || !onErrorMethod) return;
    JNIEnv* env = nullptr;
    bool attached = false;
    if (jvm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6) == JNI_EDETACHED) {
        if (jvm->AttachCurrentThread(&env, nullptr) != JNI_OK) return;
        attached = true;
    }
    jstring jtext = env->NewStringUTF(text.c_str());
    env->CallVoidMethod(listener, onErrorMethod, jtext);
    env->DeleteLocalRef(jtext);
    if (env->ExceptionCheck()) {
        env->ExceptionDescribe();
        env->ExceptionClear();
    }
    if (attached) jvm->DetachCurrentThread();
}

std::string RelayEngine::openStreams(const StartConfig& cfg, const std::vector<int32_t>& mixerMasks) {
    callbackReady = false;

    // "Allow MMAP" preserves the platform policy, including UNSPECIFIED (0).
    // Forcing AUTO bypasses Android 8/9's default NEVER on devices without
    // media.aaudio, where service lookup can block for minutes before fallback.
    // A disabled direction temporarily uses NEVER; the next enabled direction
    // must restore the captured policy instead of inheriting that override.
    SetMMapPolicyFn setPolicy = setMMapPolicyFn();
    GetMMapPolicyFn getPolicy = getMMapPolicyFn();
    const aaudio_policy_t originalPolicy = getPolicy ? getPolicy() : AAUDIO_UNSPECIFIED;
    bool policyChanged = false;
    auto applyMMapPolicy = [&](bool allow, const char* what) -> std::string {
        if (allow && !policyChanged) return "";
        if (!setPolicy || !getPolicy) {
            return std::string("无法设置 MMAP 策略（") + what +
                   "）: AAudio_setMMapPolicy / AAudio_getMMapPolicy 不可用";
        }
        const aaudio_policy_t requested = allow ? originalPolicy : AAUDIO_POLICY_NEVER;
        const aaudio_result_t r = setPolicy(requested);
        if (r == AAUDIO_OK) {
            policyChanged = true;
            return "";
        }
        return std::string("MMAP 策略设置失败（") + what +
               "）: AAudio_setMMapPolicy: " + AAudio_convertResultToText(r);
    };
    auto restoreMMapPolicy = [&] {
        if (!policyChanged || !setPolicy) return;
        aaudio_result_t r = setPolicy(originalPolicy);
        if (r != AAUDIO_OK) {
            LOGE("恢复 MMAP 策略失败 AAudio_setMMapPolicy: %s", AAudio_convertResultToText(r));
        }
    };

    // ---- output stream first (owns the data callback that pulls the input) ----
    std::string policyError = applyMMapPolicy(cfg.outMMapEnabled, "输出");
    if (!policyError.empty()) return policyError;
    AAudioStreamBuilder* builder = nullptr;
    aaudio_result_t result = AAudio_createStreamBuilder(&builder);
    if (result != AAUDIO_OK) {
        restoreMMapPolicy();
        return std::string("AAudio_createStreamBuilder: ") + AAudio_convertResultToText(result);
    }
    AAudioStreamBuilder_setDirection(builder, AAUDIO_DIRECTION_OUTPUT);
    AAudioStreamBuilder_setDeviceId(builder, cfg.outDeviceId);
    if (cfg.outChannelCount > 0) AAudioStreamBuilder_setChannelCount(builder, cfg.outChannelCount);
    if (cfg.outSampleRate > 0) AAudioStreamBuilder_setSampleRate(builder, cfg.outSampleRate);
    if (cfg.outFormat != AAUDIO_FORMAT_UNSPECIFIED) {
        AAudioStreamBuilder_setFormat(builder, static_cast<aaudio_format_t>(cfg.outFormat));
    }
    AAudioStreamBuilder_setSharingMode(builder,
            cfg.outExclusive ? AAUDIO_SHARING_MODE_EXCLUSIVE : AAUDIO_SHARING_MODE_SHARED);
    AAudioStreamBuilder_setPerformanceMode(builder,
            static_cast<aaudio_performance_mode_t>(cfg.outPerformanceMode));
    if (__builtin_available(android 28, *)) {
        AAudioStreamBuilder_setUsage(builder, static_cast<aaudio_usage_t>(cfg.outUsage));
        AAudioStreamBuilder_setContentType(builder,
                static_cast<aaudio_content_type_t>(cfg.outContentType));
    }
    if (__builtin_available(android 32, *)) {
        AAudioStreamBuilder_setSpatializationBehavior(builder, AAUDIO_SPATIALIZATION_BEHAVIOR_NEVER);
    }
    if (cfg.outBufferCapacity > 0) {
        AAudioStreamBuilder_setBufferCapacityInFrames(builder, cfg.outBufferCapacity);
    }
    AAudioStreamBuilder_setDataCallback(builder, dataCallback, this);
    AAudioStreamBuilder_setErrorCallback(builder, errorCallback, this);
    AAudioStream* outStream = nullptr;
    result = AAudioStreamBuilder_openStream(builder, &outStream);
    AAudioStreamBuilder_delete(builder);
    if (result != AAUDIO_OK) {
        restoreMMapPolicy();
        return std::string("打开输出流失败 AAudioStreamBuilder_openStream(output): ") +
               AAudio_convertResultToText(result);
    }
    output = outStream;

    // ---- input stream (read from the output data callback, no own callback) ----
    policyError = applyMMapPolicy(cfg.inMMapEnabled, "输入");
    if (!policyError.empty()) {
        restoreMMapPolicy();
        std::string ignored;
        closeStreams(ignored);
        return policyError;
    }
    builder = nullptr;
    result = AAudio_createStreamBuilder(&builder);
    if (result != AAUDIO_OK) {
        restoreMMapPolicy();
        std::string ignored;
        closeStreams(ignored);
        return std::string("AAudio_createStreamBuilder: ") + AAudio_convertResultToText(result);
    }
    AAudioStreamBuilder_setDirection(builder, AAUDIO_DIRECTION_INPUT);
    AAudioStreamBuilder_setDeviceId(builder, cfg.inDeviceId);
    if (cfg.inChannelCount > 0) AAudioStreamBuilder_setChannelCount(builder, cfg.inChannelCount);
    // The relay has no resampler/converter. An automatic input must use the
    // already-open output's PCM settings rather than independently choosing
    // another hardware default (e.g. USB 44100 Hz vs speaker 48000 Hz).
    AAudioStreamBuilder_setSampleRate(builder, cfg.inSampleRate > 0
            ? cfg.inSampleRate : AAudioStream_getSampleRate(output));
    AAudioStreamBuilder_setFormat(builder, cfg.inFormat != AAUDIO_FORMAT_UNSPECIFIED
            ? static_cast<aaudio_format_t>(cfg.inFormat) : AAudioStream_getFormat(output));
    AAudioStreamBuilder_setSharingMode(builder,
            cfg.inExclusive ? AAUDIO_SHARING_MODE_EXCLUSIVE : AAUDIO_SHARING_MODE_SHARED);
    AAudioStreamBuilder_setPerformanceMode(builder,
            static_cast<aaudio_performance_mode_t>(cfg.inPerformanceMode));
    if (__builtin_available(android 28, *)) {
        AAudioStreamBuilder_setInputPreset(builder,
                static_cast<aaudio_input_preset_t>(cfg.inInputPreset));
    }
    if (cfg.inBufferCapacity > 0) {
        AAudioStreamBuilder_setBufferCapacityInFrames(builder, cfg.inBufferCapacity);
    }
    AAudioStreamBuilder_setErrorCallback(builder, errorCallback, this);
    AAudioStream* inStream = nullptr;
    result = AAudioStreamBuilder_openStream(builder, &inStream);
    AAudioStreamBuilder_delete(builder);
    if (result != AAUDIO_OK) {
        restoreMMapPolicy();
        std::string err = std::string("打开输入流失败 AAudioStreamBuilder_openStream(input): ") +
                          AAudio_convertResultToText(result);
        std::string ignored;
        closeStreams(ignored);
        return err;
    }
    input = inStream;
    // Both streams are open; the process-global policy no longer matters.
    restoreMMapPolicy();

    // ---- post-open validation: no resampling / format conversion yet ----
    AAudioStream* in = input.load();
    AAudioStream* out = output.load();
    inChannels = AAudioStream_getChannelCount(in);
    outChannels = AAudioStream_getChannelCount(out);
    inRate = AAudioStream_getSampleRate(in);
    outRate = AAudioStream_getSampleRate(out);
    aaudio_format_t inFormat = AAudioStream_getFormat(in);
    aaudio_format_t outFormat = AAudioStream_getFormat(out);
    if (inRate <= 0 || outRate <= 0 || inChannels <= 0 || outChannels <= 0) {
        std::string err = "开流后读取参数失败: input=" + valueOrError(inRate) + "/" +
                          valueOrError(inChannels) + " output=" + valueOrError(outRate) + "/" +
                          valueOrError(outChannels);
        std::string ignored;
        closeStreams(ignored);
        return err;
    }
    if (inChannels > 32 || outChannels > 32) {
        std::string ignored;
        closeStreams(ignored);
        return "转发已停止：混音矩阵最多支持 32 个输入和 32 个输出声道";
    }
    if (inRate != outRate) {
        std::string err = "转发已停止：输入流采样率 " + std::to_string(inRate) + " Hz 与输出流采样率 " +
                          std::to_string(outRate) + " Hz 不一致（当前版本暂不支持重采样）";
        std::string ignored;
        closeStreams(ignored);
        return err;
    }
    if (inFormat != outFormat) {
        std::string err = std::string("转发已停止：输入流格式 ") + formatName(inFormat) +
                          " 与输出流格式 " + formatName(outFormat) + " 不一致（当前版本暂不支持格式转换）";
        std::string ignored;
        closeStreams(ignored);
        return err;
    }
    format = outFormat;
    outBurst = std::max(1, AAudioStream_getFramesPerBurst(output));

    // Capacity is an allocation limit, not the amount queued for playback.
    // AAudio's default can be many bursts (8 on the tested Xiaomi). With the
    // automatic output sizing, match OboeTester's default of two bursts for
    // the requested low-latency mode, including when a device falls back to
    // PERFORMANCE_MODE_NONE.
    outputCapacity = AAudioStream_getBufferCapacityInFrames(out);
    if (cfg.outAutoBufferSize) {
        tuneBuffer = cfg.outPerformanceMode == AAUDIO_PERFORMANCE_MODE_LOW_LATENCY;
        if (tuneBuffer) {
            int32_t requested = std::min(outputCapacity, outBurst * 2);
            result = AAudioStream_setBufferSizeInFrames(out, requested);
            if (result < 0) {
                std::string err = std::string("AAudioStream_setBufferSizeInFrames(output): ") +
                                  AAudio_convertResultToText(result);
                std::string ignored;
                closeStreams(ignored);
                return err;
            }
            LOGI("output BufferSizeInFrames auto: requested %d -> actual %d", requested, result);
        }
    } else {
        // Manual sizing: never adaptively retune, tuneOutputBuffer() would
        // override the user's choice. The setter may not honor the request
        // exactly; record the actual value it returns.
        tuneBuffer = false;
        if (cfg.outBufferSizeBursts > 0) {
            int32_t requested = outBurst * cfg.outBufferSizeBursts;
            if (outputCapacity > 0) requested = std::min(requested, outputCapacity);
            result = AAudioStream_setBufferSizeInFrames(out, requested);
            if (result < 0) {
                std::string err = std::string("AAudioStream_setBufferSizeInFrames(output): ") +
                                  AAudio_convertResultToText(result);
                std::string ignored;
                closeStreams(ignored);
                return err;
            }
            LOGI("output BufferSizeInFrames manual: requested %d -> actual %d", requested, result);
        }
    }

    // Input sizing is best effort: not every backend honors it and it is not
    // a guaranteed input-latency control, so log and continue on failure.
    if (cfg.inBufferSizeBursts > 0) {
        int32_t inBurst = std::max(1, AAudioStream_getFramesPerBurst(in));
        int32_t inCapacity = AAudioStream_getBufferCapacityInFrames(in);
        int32_t requested = inBurst * cfg.inBufferSizeBursts;
        if (inCapacity > 0) requested = std::min(requested, inCapacity);
        int32_t actual = AAudioStream_setBufferSizeInFrames(in, requested);
        if (actual < 0) {
            LOGE("AAudioStream_setBufferSizeInFrames(input): %s（按后端实际支持继续）",
                 AAudio_convertResultToText(actual));
        } else {
            LOGI("input BufferSizeInFrames: requested %d -> actual %d", requested, actual);
        }
    }

    // Input read threshold F = bursts * actual output burst + extra frames;
    // 0 keeps the always-read path and skips every callback-side query.
    inputThresholdFrames = static_cast<int64_t>(cfg.inputReadThresholdBursts) * outBurst +
                           cfg.inputReadThresholdExtraFrames;
    gateStallFrames = 0;
    gateStallLogged = false;
    // Ignore startup underruns, then check at most four times per second.
    tuneCountdown = outRate / 2;
    previousXRunCount = -1;
    startupDrainCallbacks = kStartupDrainCallbacks;
    startupCushionCallbacks = kStartupCushionCallbacks;
    startupDiscardCallbacks = kStartupDiscardCallbacks;
    startupDrainHardLeft = kStartupDrainHardCap;
    startupDiscardHardLeft = kStartupDiscardHardCap;
    startupStableCount = 0;
    startupPrevAvailable = -1;
    startupBudgetFrames = kStartupBudgetSeconds * std::max(inRate, outRate);
    startupFramesDiscarded = 0;
    // Runtime backlog recovery: 0 keeps the feature fully disabled.
    backlogThresholdFrames = std::max(0, cfg.inputBacklogThresholdFrames);
    backlogRecovering = false;
    backlogEpisodeBudget = 0;
    backlogPrevAvailable = -1;
    backlogCooldownFrames = 0;
    resyncEvents.store(0, std::memory_order_relaxed);

    scratchFrames = std::max(kMinScratchFrames, outBurst * 4);
    scratch.assign(static_cast<size_t>(scratchFrames) * inChannels * bytesPerFrame(format), 0);
    inputDrainFrames = std::max(1, AAudioStream_getBufferCapacityInFrames(in));
#ifndef NDEBUG
    nonSilentOutputSeen = false;
#endif

    for (int32_t o = 0; o < outChannels; ++o) {
        routingMasks[o].store(o < static_cast<int32_t>(mixerMasks.size())
                             ? static_cast<uint32_t>(mixerMasks[o]) : 0,
                             std::memory_order_relaxed);
    }

    // ---- start: request input, wait for input STARTED, request output,
    // wait for output STARTED. callbackReady arms the data callback before
    // the output request so callbacks firing during the output STARTED wait
    // already run the startup drain instead of being gated by running. ----
    callbackReady.store(true, std::memory_order_release);
    aaudio_stream_state_t nextState = AAUDIO_STREAM_STATE_UNKNOWN;
    result = AAudioStream_requestStart(input);
    if (result == AAUDIO_OK) {
        result = AAudioStream_waitForStateChange(input, AAUDIO_STREAM_STATE_STARTING, &nextState,
                                                 kStateTimeoutNs);
        if (result == AAUDIO_OK && nextState != AAUDIO_STREAM_STATE_STARTED) {
            result = AAUDIO_ERROR_INVALID_STATE;
        }
    }
    if (result != AAUDIO_OK) {
        std::string err = std::string("启动输入流失败 AAudioStream_requestStart(input): ") +
                          AAudio_convertResultToText(result);
        std::string ignored;
        closeStreams(ignored);
        return err;
    }
    nextState = AAUDIO_STREAM_STATE_UNKNOWN;
    result = AAudioStream_requestStart(output);
    if (result == AAUDIO_OK) {
        result = AAudioStream_waitForStateChange(output, AAUDIO_STREAM_STATE_STARTING, &nextState,
                                                 kStateTimeoutNs);
        if (result == AAUDIO_OK && nextState != AAUDIO_STREAM_STATE_STARTED) {
            result = AAUDIO_ERROR_INVALID_STATE;
        }
    }
    if (result != AAUDIO_OK) {
        std::string err = std::string("启动输出流失败 AAudioStream_requestStart(output): ") +
                          AAudio_convertResultToText(result);
        std::string ignored;
        closeStreams(ignored);
        return err;
    }
    LOGI("relay started: %dch %dHz %s -> %dch, mmap=%d", inChannels, inRate, formatName(format),
         outChannels, isMMapUsedFn() ? (int)isMMapUsedFn()(output) : -1);
    return "";
}

std::string RelayEngine::start(const StartConfig& cfg, const std::vector<int32_t>& mixerMasks) {
    std::lock_guard<std::mutex> lock(lifecycleMutex);
    if (input || output) {
        return "启动转发被拒绝：引擎仍持有未释放的音频流，请先停止转发";
    }
    errorNotified = false;
    // The MMAP policy is per-direction now and applied inside openStreams
    // right before each open; no process-global default is forced here.
    std::string error = openStreams(cfg, mixerMasks);
    if (error.empty()) running = true;
    return error;
}

void RelayEngine::closeStreams(std::string& firstError) {
    // Disarm the data callback before touching the streams so any callback
    // still in flight (or firing during requestStop) outputs silence.
    callbackReady.store(false, std::memory_order_release);
    AAudioStream* streams[2] = {output, input};
    for (AAudioStream* s : streams) {
        if (!s) continue;
        aaudio_stream_state_t nextState = AAUDIO_STREAM_STATE_UNKNOWN;
        AAudioStream_requestStop(s);
        AAudioStream_waitForStateChange(s, AAUDIO_STREAM_STATE_STOPPING, &nextState, kStateTimeoutNs);
        aaudio_result_t r = AAudioStream_close(s);
        if (r != AAUDIO_OK) {
            std::string text = std::string("AAudioStream_close: ") + AAudio_convertResultToText(r);
            LOGE("%s", text.c_str());
            if (firstError.empty()) firstError = text;
        }
    }
    output = nullptr;
    input = nullptr;
}

std::string RelayEngine::stop() {
    std::lock_guard<std::mutex> lock(lifecycleMutex);
    running = false;
    std::string firstError;
    closeStreams(firstError);
    scratch.clear();
    scratch.shrink_to_fit();
    scratchFrames = 0;
    inChannels = outChannels = inRate = outRate = outBurst = 0;
    inputThresholdFrames = 0;
    gateStallFrames = 0;
    gateStallLogged = false;
    startupDrainCallbacks = 0;
    startupCushionCallbacks = 0;
    startupDiscardCallbacks = 0;
    startupStableCount = 0;
    startupPrevAvailable = -1;
    startupBudgetFrames = 0;
    backlogThresholdFrames = 0;
    backlogRecovering = false;
    backlogEpisodeBudget = 0;
    backlogPrevAvailable = -1;
    backlogCooldownFrames = 0;
    return firstError;
}

void RelayEngine::setMixerMask(int32_t outChannel, int32_t mask) {
    std::lock_guard<std::mutex> lock(lifecycleMutex);
    if (outChannel >= 0 && outChannel < outChannels) {
        routingMasks[outChannel].store(static_cast<uint32_t>(mask), std::memory_order_relaxed);
    }
}

aaudio_data_callback_result_t RelayEngine::dataCallback(AAudioStream* stream, void* userData,
                                                        void* audioData, int32_t numFrames) {
    return static_cast<RelayEngine*>(userData)->render(audioData, numFrames);
}

void RelayEngine::errorCallback(AAudioStream* stream, void* userData, aaudio_result_t error) {
    auto* engine = static_cast<RelayEngine*>(userData);
    const char* dir = AAudioStream_getDirection(stream) == AAUDIO_DIRECTION_INPUT ? "输入流" : "输出流";
    engine->notifyError(std::string("AAudio 错误回调（") + dir + "）: " +
                        AAudio_convertResultToText(error));
}

void RelayEngine::fillSilence(void* audioData, int32_t fromFrame, int32_t toFrame) {
    if (fromFrame >= toFrame) return;
    int32_t frameBytes = outChannels * bytesPerFrame(format);
    auto* base = static_cast<uint8_t*>(audioData) + static_cast<size_t>(fromFrame) * frameBytes;
    std::memset(base, 0, static_cast<size_t>(toFrame - fromFrame) * frameBytes);
}

void RelayEngine::mixFrames(const void* in, void* out, int32_t frames, int32_t outFrameOffset,
                            const Plan& p) {
    int32_t frameBytes = outChannels * bytesPerFrame(format);
    auto* dst = static_cast<uint8_t*>(out) + static_cast<size_t>(outFrameOffset) * frameBytes;
    switch (format) {
        case AAUDIO_FORMAT_PCM_FLOAT:
            mixTyped<float, float>(static_cast<const float*>(in), inChannels,
                                   reinterpret_cast<float*>(dst), outChannels, frames, p.masks, p.counts);
            break;
        case AAUDIO_FORMAT_PCM_I16:
            mixTyped<int16_t, int32_t>(static_cast<const int16_t*>(in), inChannels,
                                       reinterpret_cast<int16_t*>(dst), outChannels, frames,
                                       p.masks, p.counts);
            break;
        case AAUDIO_FORMAT_PCM_I32:
            mixTyped<int32_t, int64_t>(static_cast<const int32_t*>(in), inChannels,
                                       reinterpret_cast<int32_t*>(dst), outChannels, frames,
                                       p.masks, p.counts);
            break;
        case AAUDIO_FORMAT_PCM_I24_PACKED:
            mix24(static_cast<const uint8_t*>(in), inChannels, dst, outChannels, frames, p.masks, p.counts);
            break;
        default:
            std::memset(dst, 0, static_cast<size_t>(frames) * frameBytes);
            break;
    }
#ifndef NDEBUG
    if (!nonSilentOutputSeen.load(std::memory_order_relaxed)) {
        bool hasSignal;
        if (format == AAUDIO_FORMAT_PCM_FLOAT) {
            const auto* samples = reinterpret_cast<const float*>(dst);
            hasSignal = std::any_of(samples, samples + static_cast<size_t>(frames) * outChannels,
                                    [](float sample) { return std::isfinite(sample) && sample != 0; });
        } else {
            hasSignal = std::any_of(dst, dst + static_cast<size_t>(frames) * frameBytes,
                                    [](uint8_t byte) { return byte != 0; });
        }
        if (hasSignal) nonSilentOutputSeen.store(true, std::memory_order_relaxed);
    }
#endif
}

aaudio_result_t RelayEngine::tuneOutputBuffer(int32_t numFrames) {
    if (!tuneBuffer) return AAUDIO_OK;
    tuneCountdown -= numFrames;
    if (tuneCountdown > 0) return AAUDIO_OK;
    tuneCountdown = std::max(1, outRate / 4);
    AAudioStream* out = output.load();
    int32_t xruns = AAudioStream_getXRunCount(out);
    if (xruns < 0) {
        // Some backends cannot report xruns. Keep the initial buffer size.
        tuneBuffer = false;
        return AAUDIO_OK;
    }
    if (previousXRunCount >= 0 && xruns > previousXRunCount) {
        int32_t size = AAudioStream_getBufferSizeInFrames(out);
        if (size < outputCapacity) {
            int32_t result = AAudioStream_setBufferSizeInFrames(
                    out, std::min(outputCapacity, size + outBurst));
            if (result < 0) return result;
        }
    }
    previousXRunCount = xruns;
    return AAUDIO_OK;
}

aaudio_data_callback_result_t RelayEngine::render(void* audioData, int32_t numFrames) {
    AAudioStream* in = input;
    if (!in || !callbackReady.load(std::memory_order_acquire)) {
        fillSilence(audioData, 0, numFrames);
        return AAUDIO_CALLBACK_RESULT_CONTINUE;
    }

    // Always enter the non-blocking read below. Exclusive MMAP capture processes
    // service timestamps and refreshes its clock model inside read(); frame
    // getters alone can remain at zero until that happens. Gating the first
    // read on those getters deadlocks capture while output keeps playing silence.
    // read(..., 0) also processes pending input errors and never waits for data.

    if (startupDrainCallbacks > 0) {
        // Per-callback work is bounded by one input capacity and by the
        // remaining startup budget, so neither a continuously producing
        // device nor an anomalous MMAP counter can make this loop unbounded.
        int32_t cap = inputDrainFrames;
        if (startupBudgetFrames < cap) {
            cap = static_cast<int32_t>(std::max<int64_t>(0, startupBudgetFrames));
        }
        int32_t total = 0;
        bool shortRead = false;
        while (total < cap) {
            int32_t want = std::min({numFrames, scratchFrames, cap - total});
            int32_t got = AAudioStream_read(in, scratch.data(), want, 0);
            if (got < 0) {
                notifyError(std::string("AAudioStream_read(input): ") + AAudio_convertResultToText(got));
                fillSilence(audioData, 0, numFrames);
                return AAUDIO_CALLBACK_RESULT_STOP;
            }
            total += got;
            if (got < want) {
                shortRead = true;  // actual reads found nothing more right now
                break;
            }
        }
        if (total > 0) {
            startupFramesDiscarded.fetch_add(static_cast<uint32_t>(total), std::memory_order_relaxed);
            startupBudgetFrames -= total;
        }
        fillSilence(audioData, 0, numFrames);

        // Completion is confirmed by the actual read result first: a short
        // read means the input was consumed to the last immediately-readable
        // frame. The availability hint only corroborates that no obvious
        // backlog remains — it is never treated as the readable count.
        int64_t available = getAvailableFrames(in);
        int64_t slack = std::max(numFrames * 2, outBurst * 2);
        bool backlogHint = available > slack;
        bool hitCap = cap > 0 && total >= cap && !shortRead;
        if (shortRead && !backlogHint) ++startupStableCount;
        else startupStableCount = 0;

        bool drainDone;
        if (startupStableCount >= kStartupStableCallbacks || startupBudgetFrames <= 0 ||
            startupDrainHardLeft <= 0) {
            drainDone = true;  // sync confirmed, or a hard bound forces the exit
        } else if (hitCap && backlogHint) {
            // Work cap hit with an obvious backlog left: keep the nominal
            // count and continue draining on subsequent callbacks. Only the
            // hard cap and the total budget bound this extension.
            --startupDrainHardLeft;
            drainDone = false;
        } else {
            --startupDrainCallbacks;
            --startupDrainHardLeft;
            drainDone = startupDrainCallbacks <= 0;
        }
        if (drainDone) {
            startupDrainCallbacks = 0;
            startupStableCount = 0;
            startupPrevAvailable = -1;
        }
        return AAUDIO_CALLBACK_RESULT_CONTINUE;
    }

    if (startupCushionCallbacks > 0) {
        // Cushion phase: skip reading entirely and let input accumulate a
        // little data so the read pointer stays off the write pointer.
        --startupCushionCallbacks;
        fillSilence(audioData, 0, numFrames);
        return AAUDIO_CALLBACK_RESULT_CONTINUE;
    }

    if (startupDiscardCallbacks > 0) {
        // Equilibrium phase: one bounded non-blocking attempt at up to this
        // callback's numFrames, discarded. Never loop-drain the whole input
        // here and never wait for a full read; the remaining startup budget
        // caps the total work.
        int32_t cap = numFrames;
        if (startupBudgetFrames < cap) {
            cap = static_cast<int32_t>(std::max<int64_t>(0, startupBudgetFrames));
        }
        int32_t readOffset = 0;
        while (readOffset < cap) {
            int32_t want = std::min(cap - readOffset, scratchFrames);
            int32_t got = AAudioStream_read(in, scratch.data(), want, 0);
            if (got < 0) {
                notifyError(std::string("AAudioStream_read(input): ") +
                            AAudio_convertResultToText(got));
                fillSilence(audioData, 0, numFrames);
                return AAUDIO_CALLBACK_RESULT_STOP;
            }
            if (got == 0) break;  // nothing immediately readable; do not spin
            readOffset += got;
        }
        if (readOffset > 0) {
            startupFramesDiscarded.fetch_add(static_cast<uint32_t>(readOffset),
                                             std::memory_order_relaxed);
            startupBudgetFrames -= readOffset;
        }
        fillSilence(audioData, 0, numFrames);

        // Equilibrium confirmation combines the actual read result (a short
        // read consumed everything immediately readable) with a settled
        // availability hint across callbacks. The hint is never trusted as
        // the readable count on its own.
        int64_t available = getAvailableFrames(in);
        int64_t drift = (available >= 0 && startupPrevAvailable >= 0)
                        ? (available > startupPrevAvailable ? available - startupPrevAvailable
                                                            : startupPrevAvailable - available)
                        : -1;
        bool settled = readOffset < cap || (drift >= 0 && drift <= numFrames);
        if (available >= 0) startupPrevAvailable = available;
        if (settled) ++startupStableCount;
        else startupStableCount = 0;

        --startupDiscardHardLeft;
        bool forced = startupDiscardHardLeft <= 0 || startupBudgetFrames <= 0;
        if (startupDiscardCallbacks > 1 && !forced) {
            --startupDiscardCallbacks;
        } else if (startupStableCount >= kDiscardStableCallbacks || forced) {
            // Nominal count exhausted: normal forwarding starts only with
            // confirmed equilibrium, or when a hard bound forces the exit.
            startupDiscardCallbacks = 0;
            startupStableCount = 0;
            startupPrevAvailable = -1;
        }
        // else: count exhausted but equilibrium not yet confirmed — keep
        // confirming on subsequent callbacks, bounded by the hard cap.
        return AAUDIO_CALLBACK_RESULT_CONTINUE;
    }

    int32_t tuneResult = tuneOutputBuffer(numFrames);
    if (tuneResult < 0) {
        notifyError(std::string("AAudioStream_setBufferSizeInFrames(output): ") +
                    AAudio_convertResultToText(tuneResult));
        fillSilence(audioData, 0, numFrames);
        return AAUDIO_CALLBACK_RESULT_STOP;
    }

    // Runtime backlog recovery, independent of the startup sync above and of
    // the read-threshold gate below; threshold 0 disables it completely. The
    // availability hint is not the immediately-readable count, so recovery
    // acts only through bounded timeout-0 reads and aborts whenever they
    // stop making progress — a stale counter can never discard into
    // ever-longer silence. Hysteresis (recover to half the threshold) plus a
    // post-episode cooldown prevent retrigger flapping. One lightweight
    // atomic event is recorded per episode; the UI polls it off the realtime
    // thread. The output is silent while an episode discards old input.
    if (backlogThresholdFrames > 0) {
        if (backlogCooldownFrames > 0) backlogCooldownFrames -= numFrames;
        int64_t available = getAvailableFrames(in);
        if (!backlogRecovering && backlogCooldownFrames <= 0 &&
            available > backlogThresholdFrames) {
            backlogRecovering = true;
            backlogEpisodeBudget = kBacklogEpisodeSeconds * std::max(inRate, 1);
            backlogPrevAvailable = available;
            resyncEvents.fetch_add(1, std::memory_order_relaxed);
            LOGI("输入积压恢复触发: available=%lld > threshold=%lld",
                 static_cast<long long>(available),
                 static_cast<long long>(backlogThresholdFrames));
        }
        if (backlogRecovering) {
            int32_t cap = inputDrainFrames;
            if (backlogEpisodeBudget < cap) {
                cap = static_cast<int32_t>(std::max<int64_t>(0, backlogEpisodeBudget));
            }
            int32_t total = 0;
            while (total < cap) {
                int32_t want = std::min({numFrames, scratchFrames, cap - total});
                int32_t got = AAudioStream_read(in, scratch.data(), want, 0);
                if (got < 0) {
                    notifyError(std::string("AAudioStream_read(input): ") +
                                AAudio_convertResultToText(got));
                    fillSilence(audioData, 0, numFrames);
                    return AAUDIO_CALLBACK_RESULT_STOP;
                }
                total += got;
                if (got < want) break;
            }
            if (total > 0) backlogEpisodeBudget -= total;
            int64_t after = getAvailableFrames(in);
            bool recovered = after >= 0 && after <= backlogThresholdFrames / 2;
            // Reads found nothing, or the hint did not drop although frames
            // were consumed: no progress, end the episode now.
            bool noProgress = total == 0 ||
                              (after >= 0 && backlogPrevAvailable >= 0 &&
                               after >= backlogPrevAvailable);
            if (recovered || noProgress || backlogEpisodeBudget <= 0) {
                backlogRecovering = false;
                backlogPrevAvailable = -1;
                backlogCooldownFrames = kBacklogCooldownSeconds * std::max(outRate, 1);
            } else {
                backlogPrevAvailable = after;
            }
            fillSilence(audioData, 0, numFrames);
            return AAUDIO_CALLBACK_RESULT_CONTINUE;
        }
    }

    // Input read threshold gate (F), normal forwarding phase only: the
    // startup drain/cushion/discard phases above always read, which also
    // services the exclusive-MMAP timestamps and clock model before the gate
    // ever runs. F == 0 keeps the legacy always-read path and skips every
    // availability query below. Reads that pass the gate still request
    // numFrames — F is a minimum-availability floor before reading, never the
    // read size. No blocking, no locks, no allocation on any gate path.
    if (inputThresholdFrames > 0) {
        // Re-read the actual input buffer size every callback: the setter may
        // not have honored the request exactly, and F must never exceed it.
        int64_t threshold = inputThresholdFrames;
        int32_t inBufferSize = AAudioStream_getBufferSizeInFrames(in);
        if (inBufferSize > 0 && threshold > inBufferSize) threshold = inBufferSize;
        int64_t available = getAvailableFrames(in);
        if (available >= 0 && available < threshold) {
            // Below the floor: do not consume input this callback; silence.
            // Safety valve: the frame counters can stall (exclusive MMAP
            // services them inside read()/timestamp processing), and a stalled
            // low value would otherwise mute the output forever. After one
            // second of continuous gating — far beyond any legitimate fill
            // time, since threshold <= input buffer size — fall back to the
            // normal non-blocking read, degrading to pre-gate behavior
            // instead of permanent silence.
            gateStallFrames += numFrames;
            if (gateStallFrames <= std::max(1, outRate)) {
                fillSilence(audioData, 0, numFrames);
                return AAUDIO_CALLBACK_RESULT_CONTINUE;
            }
            if (!gateStallLogged) {
                gateStallLogged = true;
                LOGE("render: input gate stalled (available=%lld < F=%lld) for over 1s; "
                     "falling back to ungated reads",
                     static_cast<long long>(available), static_cast<long long>(threshold));
            }
        } else {
            // Threshold met, or the availability query failed: fail open and
            // read normally; a failed probe must never gate audio.
            gateStallFrames = 0;
        }
    }

    Plan p;
    const uint32_t validInputs = UINT32_MAX >> (32 - inChannels);
    for (int32_t o = 0; o < outChannels; ++o) {
        p.masks[o] = routingMasks[o].load(std::memory_order_relaxed) & validInputs;
        p.counts[o] = __builtin_popcount(p.masks[o]);
    }

    // Equal sample rates are validated before starting; frames map 1:1.
    int32_t framesToRead = numFrames;

    int32_t readOffset = 0;
    int32_t outOffset = 0;
    while (readOffset < framesToRead) {
        int32_t want = std::min(framesToRead - readOffset, scratchFrames);
        int32_t got = AAudioStream_read(in, scratch.data(), want, 0);
        if (got < 0) {
            LOGE("render: AAudioStream_read -> %s", AAudio_convertResultToText(got));
            notifyError(std::string("AAudioStream_read(input): ") + AAudio_convertResultToText(got));
            fillSilence(audioData, outOffset, numFrames);
            return AAUDIO_CALLBACK_RESULT_STOP;
        }
        if (got > 0) {
            mixFrames(scratch.data(), audioData, got, outOffset, p);
            outOffset += got;  // equal rates: input frames map 1:1 to output
            readOffset += got;
        }
        if (got < want) {
            // Short read: pad the tail with silence up to the requested size.
            fillSilence(audioData, outOffset, numFrames);
            return AAUDIO_CALLBACK_RESULT_CONTINUE;
        }
    }
    return AAUDIO_CALLBACK_RESULT_CONTINUE;
}

std::vector<std::pair<std::string, std::string>> RelayEngine::streamInfo(int32_t direction) {
    std::vector<std::pair<std::string, std::string>> info;
    // Serialize against start()/stop() so the pointers stay valid while read.
    std::lock_guard<std::mutex> lock(lifecycleMutex);
    AAudioStream* s = (direction == AAUDIO_DIRECTION_INPUT) ? input.load() : output.load();
    if (!s) return info;

    auto add = [&info](const char* key, std::string value) {
        info.emplace_back(key, std::move(value));
    };
    add("BufferCapacityInFrames", valueOrError(AAudioStream_getBufferCapacityInFrames(s)));
    add("BufferSizeInFrames", valueOrError(AAudioStream_getBufferSizeInFrames(s)));
    add("ChannelCount", valueOrError(AAudioStream_getChannelCount(s)));
    if (__builtin_available(android 28, *)) {
        if (direction == AAUDIO_DIRECTION_OUTPUT) {
            add("ContentType", contentTypeName(AAudioStream_getContentType(s)));
        }
    }
    add("DeviceId", valueOrError(AAudioStream_getDeviceId(s)));
    add("Direction", AAudioStream_getDirection(s) == AAUDIO_DIRECTION_INPUT
                            ? "AAUDIO_DIRECTION_INPUT"
                            : "AAUDIO_DIRECTION_OUTPUT");
    add("Format", formatName(AAudioStream_getFormat(s)));
    add("FramesPerBurst", valueOrError(AAudioStream_getFramesPerBurst(s)));
    add("FramesRead", std::to_string(AAudioStream_getFramesRead(s)));
    add("FramesWritten", std::to_string(AAudioStream_getFramesWritten(s)));
    // FramesWritten - FramesRead via the shared probe (state-only, never
    // consumes audio): input = captured but unread frames; output = queued
    // frames not yet consumed by the endpoint. Negative probe results are
    // surfaced as an explicit unavailable state, never as a frame count.
    {
        int64_t available = getAvailableFrames(s);
        add("getAvailableFrames()",
            available >= 0 ? std::to_string(available) : "Unavailable");
    }
    if (__builtin_available(android 28, *)) {
        if (direction == AAUDIO_DIRECTION_INPUT) {
            add("InputPreset", presetName(AAudioStream_getInputPreset(s)));
        }
    }
    add("PerformanceMode", performanceName(AAudioStream_getPerformanceMode(s)));
    add("SampleRate", valueOrError(AAudioStream_getSampleRate(s)));
    if (__builtin_available(android 28, *)) {
        int32_t session = AAudioStream_getSessionId(s);
        if (session == AAUDIO_SESSION_ID_NONE) {
            add("SessionId", "AAUDIO_SESSION_ID_NONE");
        } else if (session == AAUDIO_SESSION_ID_ALLOCATE) {
            add("SessionId", "AAUDIO_SESSION_ID_ALLOCATE");
        } else {
            add("SessionId", valueOrError(session));
        }
    }
    add("SharingMode", sharingName(AAudioStream_getSharingMode(s)));
    aaudio_stream_state_t state = AAudioStream_getState(s);
    add("State", AAudio_convertStreamStateToText(state));
    if (__builtin_available(android 28, *)) {
        if (direction == AAUDIO_DIRECTION_OUTPUT) {
            add("Usage", usageName(AAudioStream_getUsage(s)));
        }
    }
    add("XRunCount", valueOrError(AAudioStream_getXRunCount(s)));
    if (direction == AAUDIO_DIRECTION_INPUT) {
        add("StartupFramesDiscarded", std::to_string(startupFramesDiscarded.load()));
        add("BacklogResyncEvents", std::to_string(resyncEvents.load(std::memory_order_relaxed)));
    }
#ifndef NDEBUG
    if (direction == AAUDIO_DIRECTION_OUTPUT) {
        add("NonSilentOutputSeen", nonSilentOutputSeen.load() ? "true" : "false");
    }
#endif
    if (auto fn = isMMapUsedFn()) {
        add("MMapUsed", fn(s) ? "true" : "false");
    } else {
        add("MMapUsed", "Unavailable");
    }
    return info;
}
