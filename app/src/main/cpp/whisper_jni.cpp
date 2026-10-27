// JNI bridge to whisper.cpp for com.yairsarig.pinlink.transcribe.WhisperEngine.
#include <jni.h>
#include <android/log.h>
#include <string>
#include "whisper.h"

#define TAG "PINLINK"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)

extern "C" {

JNIEXPORT jlong JNICALL
Java_com_yairsarig_pinlink_transcribe_WhisperEngine_nativeInit(JNIEnv *env, jclass, jstring modelPath) {
    const char *path = env->GetStringUTFChars(modelPath, nullptr);
    whisper_context_params cparams = whisper_context_default_params();
    whisper_context *ctx = whisper_init_from_file_with_params(path, cparams);
    LOGI("whisper init %s -> %p", path, ctx);
    env->ReleaseStringUTFChars(modelPath, path);
    return reinterpret_cast<jlong>(ctx);
}

JNIEXPORT void JNICALL
Java_com_yairsarig_pinlink_transcribe_WhisperEngine_nativeFree(JNIEnv *, jclass, jlong ctx) {
    whisper_free(reinterpret_cast<whisper_context *>(ctx));
}

/**
 * Transcribes 16 kHz mono float samples. Returns UTF-8 bytes of the segments joined by newlines, or
 * null on failure. Bytes, not jstring: NewStringUTF aborts on sequences outside modified UTF-8.
 */
JNIEXPORT jbyteArray JNICALL
Java_com_yairsarig_pinlink_transcribe_WhisperEngine_nativeTranscribe(
        JNIEnv *env, jclass, jlong ctxPtr, jfloatArray samples, jint threads, jstring language) {
    auto *ctx = reinterpret_cast<whisper_context *>(ctxPtr);
    const char *lang = env->GetStringUTFChars(language, nullptr);

    whisper_full_params p = whisper_full_default_params(WHISPER_SAMPLING_GREEDY);
    p.n_threads = threads;
    p.language = lang;
    p.translate = false;
    p.no_context = true;
    p.print_progress = false;
    p.print_realtime = false;
    p.print_special = false;
    p.print_timestamps = false;

    jfloat *data = env->GetFloatArrayElements(samples, nullptr);
    jsize n = env->GetArrayLength(samples);
    int rc = whisper_full(ctx, p, data, n);
    env->ReleaseFloatArrayElements(samples, data, JNI_ABORT);
    env->ReleaseStringUTFChars(language, lang);
    if (rc != 0) {
        LOGI("whisper_full failed: %d", rc);
        return nullptr;
    }

    std::string out;
    const int segments = whisper_full_n_segments(ctx);
    for (int i = 0; i < segments; ++i) {
        if (!out.empty()) out += '\n';
        out += whisper_full_get_segment_text(ctx, i);
    }
    whisper_print_timings(ctx);
    jbyteArray result = env->NewByteArray(static_cast<jsize>(out.size()));
    env->SetByteArrayRegion(result, 0, static_cast<jsize>(out.size()), reinterpret_cast<const jbyte *>(out.data()));
    return result;
}

JNIEXPORT jstring JNICALL
Java_com_yairsarig_pinlink_transcribe_WhisperEngine_nativeSystemInfo(JNIEnv *env, jclass) {
    return env->NewStringUTF(whisper_print_system_info());
}

}  // extern "C"
