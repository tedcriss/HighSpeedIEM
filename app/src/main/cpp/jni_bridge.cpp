#include <jni.h>

#include <string>
#include <vector>

#include "RelayEngine.h"

#define JNI_CLASS "cn/xiaotao29/HighSpeedIEM/audio/NativeRelay"
#define JNI_FN(name) Java_cn_xiaotao29_HighSpeedIEM_audio_NativeRelay_##name

namespace {

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

JNIEXPORT jstring JNICALL JNI_FN(nativeStart)(JNIEnv* env, jobject /*thiz*/, jintArray cfgArray,
                                              jintArray maskArray) {
    std::vector<int32_t> cfg = readIntArray(env, cfgArray);
    if (cfg.size() < 22) {
        return env->NewStringUTF("启动转发被拒绝：原生配置参数不完整");
    }
    StartConfig cfgStruct;
    cfgStruct.inDeviceId = cfg[0];
    cfgStruct.inChannelCount = cfg[1];
    cfgStruct.inSampleRate = cfg[2];
    cfgStruct.inFormat = cfg[3];
    cfgStruct.inExclusive = cfg[4] != 0;
    cfgStruct.inPerformanceMode = cfg[5];
    cfgStruct.inInputPreset = cfg[6];
    cfgStruct.inBufferCapacity = cfg[7];
    cfgStruct.inBufferSizeBursts = cfg[8];
    cfgStruct.outDeviceId = cfg[9];
    cfgStruct.outChannelCount = cfg[10];
    cfgStruct.outSampleRate = cfg[11];
    cfgStruct.outFormat = cfg[12];
    cfgStruct.outExclusive = cfg[13] != 0;
    cfgStruct.outPerformanceMode = cfg[14];
    cfgStruct.outUsage = cfg[15];
    cfgStruct.outContentType = cfg[16];
    cfgStruct.outBufferCapacity = cfg[17];
    cfgStruct.outBufferSizeBursts = cfg[18];
    cfgStruct.outAutoBufferSize = cfg[19] != 0;
    cfgStruct.inputReadThresholdBursts = cfg[20];
    cfgStruct.inputReadThresholdExtraFrames = cfg[21];
    return toJString(env, RelayEngine::get().start(cfgStruct, readIntArray(env, maskArray)));
}

JNIEXPORT jstring JNICALL JNI_FN(nativeStop)(JNIEnv* env, jobject /*thiz*/) {
    return toJString(env, RelayEngine::get().stop());
}

JNIEXPORT void JNICALL JNI_FN(nativeSetMixer)(JNIEnv* /*env*/, jobject /*thiz*/, jint outChannel,
                                              jint mask) {
    RelayEngine::get().setMixerMask(outChannel, mask);
}

JNIEXPORT jobjectArray JNICALL JNI_FN(nativeStreamInfo)(JNIEnv* env, jobject /*thiz*/,
                                                      jint direction) {
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
}

JNIEXPORT void JNICALL JNI_FN(nativeAttachListener)(JNIEnv* env, jobject /*thiz*/,
                                                    jobject listener) {
    RelayEngine::get().attachListener(env, listener);
}

}  // extern "C"
