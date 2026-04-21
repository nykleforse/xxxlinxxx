#include <jni.h>
#include <android/log.h>
#include "codec2.h"
#include <vector>

#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, "Codec2Bridge", __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, "Codec2Bridge", __VA_ARGS__)

extern "C" {

JNIEXPORT jlong JNICALL
Java_com_example_p2pcodec2_Codec2Bridge_nativeCreate(JNIEnv *, jobject, jint mode) {
    struct CODEC2 *c2 = codec2_create(mode);
    return reinterpret_cast<jlong>(c2);
}

JNIEXPORT void JNICALL
Java_com_example_p2pcodec2_Codec2Bridge_nativeDestroy(JNIEnv *, jobject, jlong handle) {
    if (handle == 0) return;
    codec2_destroy(reinterpret_cast<CODEC2*>(handle));
}

JNIEXPORT jint JNICALL
Java_com_example_p2pcodec2_Codec2Bridge_nativeSamplesPerFrame(JNIEnv *, jobject, jlong handle) {
    if (handle == 0) return 0;
    CODEC2 *c2 = reinterpret_cast<CODEC2*>(handle);
    return codec2_samples_per_frame(c2);
}

JNIEXPORT jint JNICALL
Java_com_example_p2pcodec2_Codec2Bridge_nativeBytesPerFrame(JNIEnv *, jobject, jlong handle) {
    if (handle == 0) return 0;
    CODEC2 *c2 = reinterpret_cast<CODEC2*>(handle);
    return codec2_bytes_per_frame(c2);
}

JNIEXPORT jbyteArray JNICALL
Java_com_example_p2pcodec2_Codec2Bridge_nativeEncode(JNIEnv *env, jobject, jlong handle, jshortArray pcm) {
    if (handle == 0 || pcm == nullptr) return nullptr;

    CODEC2 *c2 = reinterpret_cast<CODEC2*>(handle);
    int samples = codec2_samples_per_frame(c2);
    int nbytes = codec2_bytes_per_frame(c2);

    if (env->GetArrayLength(pcm) < samples) {
        LOGE("PCM frame is shorter than codec2 frame");
        return nullptr;
    }

    jshort *pcmData = env->GetShortArrayElements(pcm, nullptr);
    if (pcmData == nullptr) return nullptr;

    std::vector<unsigned char> bits(nbytes);
    codec2_encode(c2, bits.data(), reinterpret_cast<short *>(pcmData));
    env->ReleaseShortArrayElements(pcm, pcmData, JNI_ABORT);

    jbyteArray out = env->NewByteArray(nbytes);
    env->SetByteArrayRegion(out, 0, nbytes, reinterpret_cast<jbyte*>(bits.data()));
    return out;
}

JNIEXPORT jshortArray JNICALL
Java_com_example_p2pcodec2_Codec2Bridge_nativeDecode(JNIEnv *env, jobject, jlong handle, jbyteArray encoded) {
    if (handle == 0 || encoded == nullptr) return nullptr;

    CODEC2 *c2 = reinterpret_cast<CODEC2*>(handle);
    int samples = codec2_samples_per_frame(c2);
    int nbytes = codec2_bytes_per_frame(c2);

    if (env->GetArrayLength(encoded) < nbytes) {
        LOGE("Encoded frame is shorter than codec2 frame");
        return nullptr;
    }

    jbyte *enc = env->GetByteArrayElements(encoded, nullptr);
    if (enc == nullptr) return nullptr;

    std::vector<short> pcm(samples);
    codec2_decode(c2, pcm.data(), reinterpret_cast<unsigned char*>(enc));
    env->ReleaseByteArrayElements(encoded, enc, JNI_ABORT);

    jshortArray out = env->NewShortArray(samples);
    env->SetShortArrayRegion(out, 0, samples, pcm.data());
    return out;
}
}
