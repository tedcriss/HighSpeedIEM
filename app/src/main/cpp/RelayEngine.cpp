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

// AAudio_setMMapPolicy / AAudioStream_isMMapUsed exist from API 29; resolve
// them at runtime so minSdk 27 builds keep working.
using SetMMapPolicyFn = aaudio_result_t (*)(aaudio_policy_t);
using IsMMapUsedFn = bool (*)(AAudioStream*);

SetMMapPolicyFn setMMapPolicyFn() {
    static SetMMapPolicyFn fn = [] {
        void* lib = dlopen("libaaudio.so", RTLD_NOW);
        return lib ? reinterpret_cast<SetMMapPolicyFn>(dlsym(lib, "AAudio_setMMapPolicy")) : nullptr;
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
              const std::vector<std::vector<int32_t>>& sources) {
    for (int32_t f = 0; f < frames; ++f) {
        const T* src = in + static_cast<size_t>(f) * inChannels;
        T* dst = out + static_cast<size_t>(f) * outChannels;
        for (int32_t o = 0; o < outChannels; ++o) {
            const auto& chans = sources[o];
            if (chans.empty()) {
                dst[o] = T{0};
                continue;
            }
            Acc sum = Acc{0};
            for (int32_t c : chans) sum += static_cast<Acc>(src[c]);
            dst[o] = static_cast<T>(sum / static_cast<Acc>(chans.size()));
        }
    }
}

void mix24(const uint8_t* in, int32_t inChannels, uint8_t* out, int32_t outChannels, int32_t frames,
           const std::vector<std::vector<int32_t>>& sources) {
    for (int32_t f = 0; f < frames; ++f) {
        const uint8_t* src = in + static_cast<size_t>(f) * inChannels * 3;
        uint8_t* dst = out + static_cast<size_t>(f) * outChannels * 3;
        for (int32_t o = 0; o < outChannels; ++o) {
            const auto& chans = sources[o];
            int32_t value = 0;
            if (!chans.empty()) {
                int64_t sum = 0;
                for (int32_t c : chans) sum += unpack24(src + static_cast<size_t>(c) * 3);
                value = static_cast<int32_t>(sum / static_cast<int64_t>(chans.size()));
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
    AAudioStreamBuilder* builder = nullptr;
    aaudio_result_t result = AAudio_createStreamBuilder(&builder);
    if (result != AAUDIO_OK) {
        return std::string("AAudio_createStreamBuilder: ") + AAudio_convertResultToText(result);
    }

    // ---- input stream (read from the output data callback, no own callback) ----
    AAudioStreamBuilder_setDirection(builder, AAUDIO_DIRECTION_INPUT);
    AAudioStreamBuilder_setDeviceId(builder, cfg.inDeviceId);
    if (cfg.inChannelCount > 0) AAudioStreamBuilder_setChannelCount(builder, cfg.inChannelCount);
    if (cfg.inSampleRate > 0) AAudioStreamBuilder_setSampleRate(builder, cfg.inSampleRate);
    if (cfg.inFormat != AAUDIO_FORMAT_UNSPECIFIED) {
        AAudioStreamBuilder_setFormat(builder, static_cast<aaudio_format_t>(cfg.inFormat));
    }
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
        return std::string("打开输入流失败 AAudioStreamBuilder_openStream(input): ") +
               AAudio_convertResultToText(result);
    }
    input = inStream;

    // ---- output stream (owns the data callback that pulls the input) ----
    builder = nullptr;
    result = AAudio_createStreamBuilder(&builder);
    if (result != AAUDIO_OK) {
        std::string ignored;
        closeStreams(ignored);
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
        std::string err = std::string("打开输出流失败 AAudioStreamBuilder_openStream(output): ") +
                          AAudio_convertResultToText(result);
        std::string ignored;
        closeStreams(ignored);
        return err;
    }
    output = outStream;

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
    inBurst = std::max(1, AAudioStream_getFramesPerBurst(input));
    outBurst = std::max(1, AAudioStream_getFramesPerBurst(output));

    scratchFrames = std::max(kMinScratchFrames, outBurst * 4);
    scratch.assign(static_cast<size_t>(scratchFrames) * inChannels * bytesPerFrame(format), 0);

    pendingMasks = mixerMasks;
    applyPlanLocked();

    // ---- start: input first, then the callback-driven output ----
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
    if (auto fn = setMMapPolicyFn()) {
        aaudio_result_t r = fn(AAUDIO_POLICY_AUTO);
        if (r != AAUDIO_OK) LOGE("AAudio_setMMapPolicy(AUTO): %s", AAudio_convertResultToText(r));
    }
    std::string error = openStreams(cfg, mixerMasks);
    if (error.empty()) running = true;
    return error;
}

void RelayEngine::closeStreams(std::string& firstError) {
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
    {
        std::lock_guard<std::mutex> g(planMutex);
        plan.reset();
    }
}

std::string RelayEngine::stop() {
    std::lock_guard<std::mutex> lock(lifecycleMutex);
    running = false;
    std::string firstError;
    closeStreams(firstError);
    scratch.clear();
    scratch.shrink_to_fit();
    scratchFrames = 0;
    inChannels = outChannels = inRate = outRate = inBurst = outBurst = 0;
    return firstError;
}

void RelayEngine::applyPlanLocked() {
    auto p = std::make_shared<Plan>();
    p->inputChannels = inChannels;
    p->outputChannels = outChannels;
    p->sources.resize(outChannels);
    for (int32_t o = 0; o < outChannels; ++o) {
        int32_t mask = o < static_cast<int32_t>(pendingMasks.size()) ? pendingMasks[o] : 0;
        for (int32_t i = 0; i < inChannels; ++i) {
            if (mask & (1 << i)) p->sources[o].push_back(i);
        }
    }
    {
        std::lock_guard<std::mutex> g(planMutex);
        plan = p;
    }
}

void RelayEngine::setMixerMask(int32_t outChannel, int32_t mask) {
    std::lock_guard<std::mutex> lock(lifecycleMutex);
    if (outChannel < 0) return;
    if (outChannel >= static_cast<int32_t>(pendingMasks.size())) {
        pendingMasks.resize(outChannel + 1, 0);
    }
    pendingMasks[outChannel] = mask;
    if (running.load()) applyPlanLocked();
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
                                   reinterpret_cast<float*>(dst), outChannels, frames, p.sources);
            break;
        case AAUDIO_FORMAT_PCM_I16:
            mixTyped<int16_t, int32_t>(static_cast<const int16_t*>(in), inChannels,
                                       reinterpret_cast<int16_t*>(dst), outChannels, frames,
                                       p.sources);
            break;
        case AAUDIO_FORMAT_PCM_I32:
            mixTyped<int32_t, int64_t>(static_cast<const int32_t*>(in), inChannels,
                                       reinterpret_cast<int32_t*>(dst), outChannels, frames,
                                       p.sources);
            break;
        case AAUDIO_FORMAT_PCM_I24_PACKED:
            mix24(static_cast<const uint8_t*>(in), inChannels, dst, outChannels, frames, p.sources);
            break;
        default:
            std::memset(dst, 0, static_cast<size_t>(frames) * frameBytes);
            break;
    }
}

aaudio_data_callback_result_t RelayEngine::render(void* audioData, int32_t numFrames) {
    std::shared_ptr<const Plan> p;
    {
        std::lock_guard<std::mutex> g(planMutex);
        p = plan;
    }
    AAudioStream* in = input;
    if (!p || !in || !running.load()) {
        fillSilence(audioData, 0, numFrames);
        return AAUDIO_CALLBACK_RESULT_CONTINUE;
    }

    // Input start is asynchronous; stay silent until it actually runs.
    if (AAudioStream_getState(in) != AAUDIO_STREAM_STATE_STARTED) {
        fillSilence(audioData, 0, numFrames);
        return AAUDIO_CALLBACK_RESULT_CONTINUE;
    }

    // InputMargin guard: only read when the input buffer already holds at
    // least one output-burst worth of input frames (converted by sample rate,
    // rounded up) plus one input burst. Otherwise emit silence and let the
    // input accumulate, instead of underrunning every callback.
    int64_t required =
            static_cast<int64_t>(std::ceil(static_cast<double>(outBurst) * inRate / outRate)) +
            inBurst;
    int64_t available = AAudioStream_getFramesWritten(in) - AAudioStream_getFramesRead(in);
    if (available < required) {
        fillSilence(audioData, 0, numFrames);
        return AAUDIO_CALLBACK_RESULT_CONTINUE;
    }

    // No resampling in this version: start() rejects mismatched rates, so the
    // converted frame count equals numFrames. The formula keeps the future
    // resampling path explicit.
    int32_t framesToRead = static_cast<int32_t>(
            std::ceil(static_cast<double>(numFrames) * inRate / outRate));

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
            mixFrames(scratch.data(), audioData, got, outOffset, *p);
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
    if (auto fn = isMMapUsedFn()) {
        add("MMapUsed", fn(s) ? "true" : "false");
    } else {
        add("MMapUsed", "Unavailable");
    }
    return info;
}
