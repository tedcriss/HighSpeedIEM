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
    JavaVM* vm = nullptr;
    env->GetJavaVM(&vm);
    jvm.store(vm, std::memory_order_release);
    std::lock_guard<std::mutex> lock(listenerMutex);
    if (listener) {
        env->DeleteGlobalRef(listener);
        listener = nullptr;
        onErrorMethod = nullptr;
        onEventMethod = nullptr;
    }
    if (newListener) {
        listener = env->NewGlobalRef(newListener);
        jclass cls = env->GetObjectClass(listener);
        onErrorMethod = env->GetMethodID(cls, "onNativeError", "(JLjava/lang/String;)V");
        onEventMethod = env->GetMethodID(cls, "onNativeEvent", "(JJIJJ)V");
        env->DeleteLocalRef(cls);
        if (!onErrorMethod) {
            env->ExceptionClear();
            LOGE("listener missing onNativeError(long, String)");
        }
    }
}

std::string RelayEngine::errorText(const RelayErrorRecord& error) {
    std::string text;
    switch (error.kind) {
        case RelayErrorKind::Read: text = "AAudioStream_read(input): "; break;
        case RelayErrorKind::Platform: text = "AAudio 错误回调: "; break;
        case RelayErrorKind::Bounds: text = "音频内存边界错误: "; break;
        case RelayErrorKind::Geometry: text = "音频几何参数错误: "; break;
    }
    text += AAudio_convertResultToText(error.code);
    text += " state=" + std::to_string(error.state) + " requestedFrames=" + std::to_string(error.requestedFrames)
          + " requiredBytes=" + std::to_string(error.requiredBytes) + " scratchBytes=" + std::to_string(error.scratchBytes);
    return text;
}
void RelayEngine::deliverError(uint32_t session, const RelayErrorRecord& error) {
    const auto text = errorText(error);
    JNIEnv* env = nullptr;
    JavaVM* vm = jvm.load(std::memory_order_acquire);
    if (!vm) return;
    bool attached = vm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6) == JNI_EDETACHED;
    if (attached && vm->AttachCurrentThread(&env, nullptr) != JNI_OK) return;
    jobject target = nullptr; jmethodID method = nullptr;
    { std::lock_guard<std::mutex> lock(listenerMutex);
      if (listener) target = env->NewLocalRef(listener); method = onErrorMethod; }
    if (target && method) {
        jstring value = env->NewStringUTF(text.c_str());
        env->CallVoidMethod(target, method, static_cast<jlong>(session), value);
        env->DeleteLocalRef(value);
    }
    if (target) env->DeleteLocalRef(target);
    if (env->ExceptionCheck()) { env->ExceptionDescribe(); env->ExceptionClear(); }
    if (attached) vm->DetachCurrentThread();
}
void RelayEngine::deliverEvent(const RelayEvent& event) {
    JNIEnv* env = nullptr;
    JavaVM* vm = jvm.load(std::memory_order_acquire);
    if (!vm) return;
    bool attached = vm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6) == JNI_EDETACHED;
    if (attached && vm->AttachCurrentThread(&env, nullptr) != JNI_OK) return;
    jobject target = nullptr; jmethodID method = nullptr;
    { std::lock_guard<std::mutex> lock(listenerMutex);
      if (listener) target = env->NewLocalRef(listener); method = onEventMethod; }
    if (target && method) env->CallVoidMethod(target, method, static_cast<jlong>(event.sessionId),
        static_cast<jlong>(event.sequence), static_cast<jint>(event.type),
        static_cast<jlong>(event.value1), static_cast<jlong>(event.value2));
    if (target) env->DeleteLocalRef(target);
    if (env->ExceptionCheck()) { env->ExceptionDescribe(); env->ExceptionClear(); }
    if (attached) vm->DetachCurrentThread();
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
    if (cfg.inSampleRate > 0) AAudioStreamBuilder_setSampleRate(builder, cfg.inSampleRate);
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
    outBurst = AAudioStream_getFramesPerBurst(out);
    if (outBurst <= 0) {
        const auto error = std::string("输出 FramesPerBurst 无效: ") + valueOrError(outBurst);
        std::string ignored; closeStreams(ignored); return error;
    }

    outputCapacity = AAudioStream_getBufferCapacityInFrames(out);
    inputCapacity = AAudioStream_getBufferCapacityInFrames(in);
    if (outputCapacity <= 0 || inputCapacity <= 0 || bytesPerFrame(format) <= 0) {
        std::string ignored; closeStreams(ignored);
        return "无效音频 capacity / PCM format";
    }
    if (cfg.outBufferSizeBursts > 0) {
        const int32_t requested = static_cast<int32_t>(std::min<int64_t>(outputCapacity,
                int64_t(outBurst) * cfg.outBufferSizeBursts));
        result = AAudioStream_setBufferSizeInFrames(out, requested);
        if (result < 0) {
            std::string err = std::string("AAudioStream_setBufferSizeInFrames(output): ") + AAudio_convertResultToText(result);
            std::string ignored; closeStreams(ignored); return err;
        }
        LOGI("output BufferSizeInFrames requested %d -> actual %d", requested, result);
    }

    // Input sizing is best effort: not every backend honors it and it is not
    // a guaranteed input-latency control, so log and continue on failure.
    if (cfg.inBufferSizeBursts > 0) {
        int32_t inBurst = std::max(1, AAudioStream_getFramesPerBurst(in));
        int32_t inCapacity = AAudioStream_getBufferCapacityInFrames(in);
        int32_t requested = static_cast<int32_t>(std::min<int64_t>(inCapacity, int64_t(inBurst) * cfg.inBufferSizeBursts));
        if (inCapacity > 0) requested = std::min(requested, inCapacity);
        int32_t actual = AAudioStream_setBufferSizeInFrames(in, requested);
        if (actual < 0) {
            LOGE("AAudioStream_setBufferSizeInFrames(input): %s（按后端实际支持继续）",
                 AAudio_convertResultToText(actual));
        } else {
            LOGI("input BufferSizeInFrames: requested %d -> actual %d", requested, actual);
        }
    }

    inputBytesPerFrame = static_cast<size_t>(inChannels) * bytesPerFrame(inFormat);
    outputBytesPerFrame = static_cast<size_t>(outChannels) * bytesPerFrame(outFormat);
    size_t scratchBytes = 0;
    if (!RelayCallbackMachine::checkedMultiply(std::max(inputCapacity, outputCapacity),
            std::max(inChannels, outChannels), scratchBytes) ||
        !RelayCallbackMachine::checkedMultiply(scratchBytes,
            std::max(bytesPerFrame(inFormat), bytesPerFrame(outFormat)), scratchBytes)) {
        std::string ignored; closeStreams(ignored); return "scratch allocation size_t overflow";
    }
#ifndef NDEBUG
    if (failAllocation) { failAllocation = false; throw std::bad_alloc(); }
#endif
    scratch.assign(scratchBytes, 0);
    startupFramesDiscarded = 0; runtimeFramesDiscarded = 0;
#ifndef NDEBUG
    nonSilentOutputSeen = false;
#endif

    for (int32_t o = 0; o < outChannels; ++o) {
        routingMasks[o].store(o < static_cast<int32_t>(mixerMasks.size())
                             ? static_cast<uint32_t>(mixerMasks[o]) : 0,
                             std::memory_order_relaxed);
    }

    return "";
}

std::string RelayEngine::startPrepared(const std::vector<int32_t>& thresholds) {
    std::lock_guard<std::mutex> lock(lifecycleMutex);
    if (!input || !output || running || callbackReady || thresholds.size() != 7 || events.exiting())
        return "startPrepared: invalid state / thresholds / stop requested";
    const int bursts = thresholds[0], extra = thresholds[1], backlog = thresholds[2];
    const RelayPhaseLimits limits{thresholds[3], thresholds[4], thresholds[5], thresholds[6]};
    const auto validCount = [](int n) {
        return (n >= 1 && n < 10) || (n >= 10 && n <= 100 && n % 5 == 0)
                || (n >= 200 && n <= 2000 && n % 100 == 0);
    };
    if (!validCount(limits.drainStable) || !validCount(limits.drainMax) ||
        !validCount(limits.discardStable) || !validCount(limits.discardMax))
        return "startPrepared: DRAIN/DISCARD exit counts are outside allowed options";
    if (limits.drainMax < limits.drainStable)
        return "startPrepared: DRAIN 强制退出次数（" + std::to_string(limits.drainMax) +
                "）不能小于稳定阈值（" + std::to_string(limits.drainStable) + "）";
    if (limits.discardMax < limits.discardStable)
        return "startPrepared: DISCARD 强制退出次数（" + std::to_string(limits.discardMax) +
                "）不能小于稳定阈值（" + std::to_string(limits.discardStable) + "）";
    const bool validExtra = extra == 0 || (extra > 0 && extra <= 1024 && (extra & (extra - 1)) == 0);
    const bool validBacklog = backlog == 0 || (backlog >= 100 && backlog <= 1000 && backlog % 100 == 0)
            || (backlog >= 2000 && backlog <= 10000 && backlog % 1000 == 0);
    if (bursts < 0 || bursts > 10 || !validExtra || !validBacklog)
        return "startPrepared: invalid thresholds";
    const int64_t raw = int64_t(bursts) * outBurst + extra;
    const int size = AAudioStream_getBufferSizeInFrames(input);
    if (backlog > 0 && backlog <= (size > 0 ? std::min(raw, int64_t(size)) : raw))
        return "startPrepared: inputBacklogThresholdFrames must exceed F_effective";
    callbackMachine.prepare(raw, backlog, limits);
    aaudio_result_t result;
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
    if (events.exiting()) {
        std::string ignored; closeStreams(ignored);
        RelayErrorRecord record;
        return events.fatalRecord(record) ? errorText(record) : "startPrepared: stop requested";
    }
    running = true;
    LOGI("relay started: %dch %dHz %s -> %dch, mmap=%d", inChannels, inRate, formatName(format),
         outChannels, isMMapUsedFn() ? (int)isMMapUsedFn()(output) : -1);
    return "";
}

std::string RelayEngine::prepare(const StartConfig& cfg, const std::vector<int32_t>& mixerMasks) {
    std::lock_guard<std::mutex> lock(lifecycleMutex);
    if (input || output) return "准备被拒绝：请先停止转发";
    if (cfg.inChannelCount < 0 || cfg.inChannelCount > 32 || cfg.outChannelCount < 0 || cfg.outChannelCount > 32 ||
        cfg.inSampleRate < 0 || cfg.outSampleRate < 0 || cfg.inBufferCapacity < 0 || cfg.outBufferCapacity < 0 ||
        cfg.inBufferSizeBursts < 0 || cfg.inBufferSizeBursts > 16 || cfg.outBufferSizeBursts < 0 || cfg.outBufferSizeBursts > 16)
        return "音频准备失败：无效的通道数、采样率或缓冲参数";
    {
        std::lock_guard<std::mutex> listenerLock(listenerMutex);
        if (!listener || !onErrorMethod || !onEventMethod)
            return "音频准备失败：原生事件监听器尚未就绪";
    }
    events.stop();
    currentSession.fetch_add(1);
    if (auto error = events.prepare(*this, currentSession.load()); !error.empty()) return error;
    try {
        auto error = openStreams(cfg, mixerMasks);
        if (error.empty() && events.exiting()) {
            std::string ignored; closeStreams(ignored);
            RelayErrorRecord record;
            error = events.fatalRecord(record) ? errorText(record) : "prepare: stop requested";
        }
        if (!error.empty()) { std::string ignored; closeStreams(ignored); scratch.clear(); events.stop(); }
        return error;
    } catch (const std::exception& e) {
        std::string ignored; closeStreams(ignored); scratch.clear(); events.stop();
        return std::string("音频准备失败: ") + e.what();
    }
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
    events.requestStop();
    std::string firstError;
    closeStreams(firstError);
    events.stop();
    std::vector<uint8_t>().swap(scratch);
    inChannels = outChannels = inRate = outRate = outBurst = 0;
    inputBytesPerFrame = outputBytesPerFrame = 0;
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
    engine->callbackMachine.requestExit({RelayErrorKind::Platform, error,
            static_cast<int32_t>(engine->callbackMachine.stateSnapshot()), 0, 0, 0});
}

void RelayEngine::fillSilence(void* audioData, int32_t fromFrame, int32_t toFrame) {
    size_t safeBytes;
    if (!audioData || fromFrame < 0 || fromFrame >= toFrame || !outputBytesPerFrame ||
        !RelayCallbackMachine::checkedMultiply(static_cast<size_t>(toFrame), outputBytesPerFrame, safeBytes)) return;
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

aaudio_data_callback_result_t RelayEngine::render(void* audioData, int32_t numFrames) {
    return callbackMachine.process(audioData, numFrames);
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
        add("RuntimeFramesDiscarded", std::to_string(runtimeFramesDiscarded.load()));
        const char* states[] = {"DRAIN", "DISCARD", "RUNNING", "EXIT"};
        const auto state = callbackMachine.stateSnapshot();
        add("CallbackState", state < 4 ? states[state] : "Unknown");
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
