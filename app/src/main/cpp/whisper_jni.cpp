// JNI bridge between com.pocketagent.WhisperLib and whisper.cpp (on-device speech recognition).
#include <jni.h>
#include <android/log.h>
#include <string>
#include "whisper.h"

#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, "PocketWhisper", __VA_ARGS__)

extern "C" {

JNIEXPORT jlong JNICALL
Java_com_pocketagent_WhisperLib_initContext(JNIEnv *env, jobject, jstring path) {
    const char *p = env->GetStringUTFChars(path, nullptr);
    whisper_context_params cparams = whisper_context_default_params();
    cparams.use_gpu = false;
    whisper_context *ctx = whisper_init_from_file_with_params(p, cparams);
    LOGI("model %s -> %p", p, (void *) ctx);
    env->ReleaseStringUTFChars(path, p);
    return (jlong) ctx;
}

JNIEXPORT void JNICALL
Java_com_pocketagent_WhisperLib_freeContext(JNIEnv *, jobject, jlong ctx) {
    if (ctx) whisper_free((whisper_context *) ctx);
}

// Returns raw UTF-8 bytes rather than a jstring: whisper can emit partial multi-byte sequences
// (hallucinations on silence/noise) and NewStringUTF() aborts the whole process on those.
// Kotlin decodes with String(bytes, UTF_8), which replaces invalid sequences instead.
JNIEXPORT jbyteArray JNICALL
Java_com_pocketagent_WhisperLib_transcribeBytes(JNIEnv *env, jobject, jlong ctxp, jfloatArray pcm,
                                                jstring lang, jint threads, jboolean translate, jstring prompt) {
    auto *ctx = (whisper_context *) ctxp;
    if (!ctx) return env->NewByteArray(0);
    jfloat *data = env->GetFloatArrayElements(pcm, nullptr);
    const jsize n = env->GetArrayLength(pcm);
    const char *l = env->GetStringUTFChars(lang, nullptr);
    std::string language(l ? l : "auto");
    env->ReleaseStringUTFChars(lang, l);
    std::string initial;
    if (prompt) {
        const char *pr = env->GetStringUTFChars(prompt, nullptr);
        if (pr) initial = pr;
        env->ReleaseStringUTFChars(prompt, pr);
    }

    whisper_full_params p = whisper_full_default_params(WHISPER_SAMPLING_GREEDY);
    p.print_realtime = false;
    p.print_progress = false;
    p.print_timestamps = false;
    p.print_special = false;
    p.translate = translate;
    p.language = language.c_str();
    p.n_threads = threads > 0 ? threads : 4;
    p.no_context = true;
    p.single_segment = false;
    p.suppress_blank = true;
    // A short domain prompt biases decoding toward coding vocabulary (Python, git, npm...).
    if (!initial.empty()) p.initial_prompt = initial.c_str();

    const int rc = whisper_full(ctx, p, data, n);
    env->ReleaseFloatArrayElements(pcm, data, JNI_ABORT);
    if (rc != 0) {
        LOGI("whisper_full failed: %d", rc);
        return env->NewByteArray(0);
    }
    std::string out;
    const int ns = whisper_full_n_segments(ctx);
    for (int i = 0; i < ns; i++) {
        const char *t = whisper_full_get_segment_text(ctx, i);
        if (t) out += t;
    }
    jbyteArray arr = env->NewByteArray((jsize) out.size());
    if (arr && !out.empty()) env->SetByteArrayRegion(arr, 0, (jsize) out.size(), (const jbyte *) out.data());
    return arr;
}

JNIEXPORT jstring JNICALL
Java_com_pocketagent_WhisperLib_systemInfo(JNIEnv *env, jobject) {
    return env->NewStringUTF(whisper_print_system_info());
}

} // extern "C"
