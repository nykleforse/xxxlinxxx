#include <jni.h>
#include <vector>
#include <cstdint>
#include <cstring>
#include <android/log.h>

extern "C" {
#include "codec2.h"
}

#define LOG_TAG "CODEC2_JNI"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

extern "C"
JNIEXPORT jlong JNICALL
Java_com_example_xxxlinkxxx_Codec2Bridge_nativeCreate(JNIEnv *env, jobject thiz, jint mode) {
    struct CODEC2 *codec2 = codec2_create(mode);
    return reinterpret_cast<jlong>(codec2);
}

extern "C"
JNIEXPORT void JNICALL
Java_com_example_xxxlinkxxx_Codec2Bridge_nativeDestroy(JNIEnv *env, jobject thiz, jlong ptr) {
    if (ptr == 0) return;
    auto *codec2 = reinterpret_cast<struct CODEC2 *>(ptr);
    codec2_destroy(codec2);
}

extern "C"
JNIEXPORT jint JNICALL
Java_com_example_xxxlinkxxx_Codec2Bridge_nativeSamplesPerFrame(JNIEnv *env, jobject thiz, jlong ptr) {
    if (ptr == 0) return 0;
    auto *codec2 = reinterpret_cast<struct CODEC2 *>(ptr);
    return codec2_samples_per_frame(codec2);
}

extern "C"
JNIEXPORT jint JNICALL
Java_com_example_xxxlinkxxx_Codec2Bridge_nativeBitsPerFrame(JNIEnv *env, jobject thiz, jlong ptr) {
    if (ptr == 0) return 0;
    auto *codec2 = reinterpret_cast<struct CODEC2 *>(ptr);
    return codec2_bits_per_frame(codec2);
}

extern "C"
JNIEXPORT jbyteArray JNICALL
Java_com_example_xxxlinkxxx_Codec2Bridge_nativeEncode(JNIEnv *env, jobject thiz, jlong ptr, jshortArray pcm) {
    if (ptr == 0 || pcm == nullptr) return nullptr;

    auto *codec2 = reinterpret_cast<struct CODEC2 *>(ptr);
    int samples = codec2_samples_per_frame(codec2);
    int bits = codec2_bits_per_frame(codec2);
    int bytes = (bits + 7) / 8;

    jsize pcmLen = env->GetArrayLength(pcm);
    if (pcmLen < samples) return nullptr;

    jshort *pcmData = env->GetShortArrayElements(pcm, nullptr);

    std::vector<unsigned char> out(bytes);
    codec2_encode(codec2, out.data(), reinterpret_cast<short *>(pcmData));

    env->ReleaseShortArrayElements(pcm, pcmData, JNI_ABORT);

    jbyteArray result = env->NewByteArray(bytes);
    env->SetByteArrayRegion(result, 0, bytes, reinterpret_cast<jbyte *>(out.data()));
    return result;
}

extern "C"
JNIEXPORT jshortArray JNICALL
Java_com_example_xxxlinkxxx_Codec2Bridge_nativeDecode(JNIEnv *env, jobject thiz, jlong ptr, jbyteArray data) {
    if (ptr == 0 || data == nullptr) return nullptr;

    auto *codec2 = reinterpret_cast<struct CODEC2 *>(ptr);
    int samples = codec2_samples_per_frame(codec2);
    int bits = codec2_bits_per_frame(codec2);
    int bytes = (bits + 7) / 8;

    jsize inLen = env->GetArrayLength(data);
    if (inLen < bytes) return nullptr;

    jbyte *inData = env->GetByteArrayElements(data, nullptr);

    std::vector<short> pcm(samples);
    codec2_decode(codec2, pcm.data(), reinterpret_cast<unsigned char *>(inData));

    env->ReleaseByteArrayElements(data, inData, JNI_ABORT);

    jshortArray result = env->NewShortArray(samples);
    env->SetShortArrayRegion(result, 0, samples, pcm.data());
    return result;
}