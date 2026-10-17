#include <jni.h>

#include <string>
#include <vector>

#include "RelayEngine.h"

#define JNI_CLASS "cn/xiaotao29/HighSpeedIEM/audio/NativeRelay"
#define JNI_FN(name) Java_cn_xiaotao29_HighSpeedIEM_audio_NativeRelay_##name

namespace {

// Keep identical to Kotlin ConfigField; no optional or legacy tail fields.
enum ConfigField { IN_DEVICE, IN_CHANNELS, IN_RATE, IN_FORMAT, IN_EXCLUSIVE, IN_PERFORMANCE, IN_PRESET, IN_CAPACITY, IN_SIZE, OUT_DEVICE, OUT_CHANNELS, OUT_RATE, OUT_FORMAT, OUT_EXCLUSIVE, OUT_PERFORMANCE, OUT_USAGE, OUT_CONTENT, OUT_CAPACITY, OUT_SIZE, READ_BURSTS, READ_EXTRA, BACKLOG, IN_MMAP, OUT_MMAP, CONFIG_FIELD_COUNT };

std::vector<int32_t> readIntArray(JNIEnv* env, jintArray array) {
    std::vector<int32_t> out;
    if (!array) return out;
    jsize len = env->GetArrayLength(array);
    out.resize(len);
    if (len > 0) {
        env->GetIntArrayRegion(array, 0, len, reinterpret_cast<jint*>(out.data()));
    }
    return out;
}

jstring toJString(JNIEnv* env, const std::string& text) {
    return text.empty() ? nullptr : env->NewStringUTF(text.c_str());
}

}  // namespace

extern "C" {

JNIEXPORT jstring JNICALL JNI_FN(nativePrepare)(JNIEnv* env, jobject /*thiz*/, jintArray cfgArray,
                                              jintArray maskArray) {
    try {
    std::vector<int32_t> cfg = readIntArray(env, cfgArray);
    if (cfg.size() != CONFIG_FIELD_COUNT) {
        return env->NewStringUTF("启动转发被拒绝：原生配置参数不完整");
    }
    StartConfig cfgStruct;
    cfgStruct.inDeviceId = cfg[IN_DEVICE];
    cfgStruct.inChannelCount = cfg[IN_CHANNELS];
    cfgStruct.inSampleRate = cfg[IN_RATE];
    cfgStruct.inFormat = cfg[IN_FORMAT];
    cfgStruct.inExclusive = cfg[IN_EXCLUSIVE] != 0;
    cfgStruct.inPerformanceMode = cfg[IN_PERFORMANCE];
    cfgStruct.inInputPreset = cfg[IN_PRESET];
    cfgStruct.inBufferCapacity = cfg[IN_CAPACITY];
    cfgStruct.inBufferSizeBursts = cfg[IN_SIZE];
    cfgStruct.outDeviceId = cfg[OUT_DEVICE];
    cfgStruct.outChannelCount = cfg[OUT_CHANNELS];
    cfgStruct.outSampleRate = cfg[OUT_RATE];
    cfgStruct.outFormat = cfg[OUT_FORMAT];
    cfgStruct.outExclusive = cfg[OUT_EXCLUSIVE] != 0;
    cfgStruct.outPerformanceMode = cfg[OUT_PERFORMANCE];
    cfgStruct.outUsage = cfg[OUT_USAGE];
    cfgStruct.outContentType = cfg[OUT_CONTENT];
    cfgStruct.outBufferCapacity = cfg[OUT_CAPACITY];
    cfgStruct.outBufferSizeBursts = cfg[OUT_SIZE];
    cfgStruct.inputReadThresholdBursts = cfg[READ_BURSTS];
    cfgStruct.inputReadThresholdExtraFrames = cfg[READ_EXTRA];
    cfgStruct.inputBacklogThresholdFrames = cfg[BACKLOG];
    cfgStruct.inMMapEnabled = cfg[IN_MMAP] != 0;
    cfgStruct.outMMapEnabled = cfg[OUT_MMAP] != 0;
    return toJString(env, RelayEngine::get().prepare(cfgStruct, readIntArray(env, maskArray)));
    } catch (const std::exception& e) { RelayEngine::get().stop(); return env->NewStringUTF(e.what()); }
}

JNIEXPORT jstring JNICALL JNI_FN(nativeStop)(JNIEnv* env, jobject /*thiz*/) {
    try { return toJString(env, RelayEngine::get().stop()); }
    catch (const std::exception& e) { return env->NewStringUTF(e.what()); }
}

JNIEXPORT void JNICALL JNI_FN(nativeSetMixer)(JNIEnv* /*env*/, jobject /*thiz*/, jint outChannel,
                                              jint mask) {
    RelayEngine::get().setMixerMask(outChannel, mask);
}

JNIEXPORT jobjectArray JNICALL JNI_FN(nativeStreamInfo)(JNIEnv* env, jobject /*thiz*/,
                                                      jint direction) {
    try {
    auto info = RelayEngine::get().streamInfo(direction);
    jclass stringClass = env->FindClass("java/lang/String");
    jobjectArray result = env->NewObjectArray(static_cast<jsize>(info.size() * 2), stringClass,
                                              nullptr);
    jsize index = 0;
    for (const auto& [key, value] : info) {
        jstring jkey = env->NewStringUTF(key.c_str());
        env->SetObjectArrayElement(result, index++, jkey);
        env->DeleteLocalRef(jkey);
        jstring jvalue = env->NewStringUTF(value.c_str());
        env->SetObjectArrayElement(result, index++, jvalue);
        env->DeleteLocalRef(jvalue);
    }
    env->DeleteLocalRef(stringClass);
    return result;
    } catch (const std::exception& e) {
        jclass cls = env->FindClass("java/lang/IllegalStateException");
        if (cls) env->ThrowNew(cls, e.what());
        return nullptr;
    }
}

JNIEXPORT void JNICALL JNI_FN(nativeAttachListener)(JNIEnv* env, jobject /*thiz*/,
                                                    jobject listener) {
    RelayEngine::get().attachListener(env, listener);
}

JNIEXPORT jlong JNICALL JNI_FN(nativeSessionId)(JNIEnv*, jobject) {
    return RelayEngine::get().sessionId();
}
JNIEXPORT jstring JNICALL JNI_FN(nativeStartPrepared)(JNIEnv* env, jobject, jintArray thresholds) {
    try { return toJString(env, RelayEngine::get().startPrepared(readIntArray(env, thresholds))); }
    catch (const std::exception& e) { RelayEngine::get().stop(); return env->NewStringUTF(e.what()); }
}

}  // extern "C"

#ifndef NDEBUG
// Debug-only instrumentation seam: simulate a platform that disables MMAP.
#include <dlfcn.h>
extern "C" JNIEXPORT jint JNICALL
Java_cn_xiaotao29_HighSpeedIEM_audio_MMapPolicyTest_nativePolicy(
        JNIEnv*, jobject, jint policy) {
    using GetPolicy = aaudio_policy_t (*)();
    using SetPolicy = aaudio_result_t (*)(aaudio_policy_t);
    auto get = reinterpret_cast<GetPolicy>(dlsym(RTLD_DEFAULT, "AAudio_getMMapPolicy"));
    auto set = reinterpret_cast<SetPolicy>(dlsym(RTLD_DEFAULT, "AAudio_setMMapPolicy"));
    if (!get || !set) return AAUDIO_ERROR_UNAVAILABLE;
    if (policy >= 0) {
        const auto result = set(policy);
        if (result != AAUDIO_OK) return result;
    }
    return get();
}
#endif

#ifndef NDEBUG
std::string runCallbackMachineTests();
extern "C" JNIEXPORT jstring JNICALL
Java_cn_xiaotao29_HighSpeedIEM_audio_CallbackMachineTest_nativeRun(JNIEnv* env, jobject) {
    return env->NewStringUTF(runCallbackMachineTests().c_str());
}
extern "C" JNIEXPORT void JNICALL
Java_cn_xiaotao29_HighSpeedIEM_audio_PreparedLifecycleTest_nativeInjectError(JNIEnv*, jobject, jint error) {
    RelayEngine::errorCallback(nullptr, &RelayEngine::get(), error);
}
extern "C" JNIEXPORT void JNICALL
Java_cn_xiaotao29_HighSpeedIEM_audio_AutoRestartTest_nativeInjectError(JNIEnv*, jobject, jint error) {
    RelayEngine::errorCallback(nullptr, &RelayEngine::get(), error);
}
#endif

#ifndef NDEBUG
extern "C" JNIEXPORT void JNICALL
Java_cn_xiaotao29_HighSpeedIEM_audio_PreparedLifecycleTest_nativeFailAllocation(JNIEnv*, jobject) {
    RelayEngine::get().failNextScratchAllocation();
}
#endif
