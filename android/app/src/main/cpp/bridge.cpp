#include <jni.h>
#include <whisper.h>
#include <algorithm>
#include <string>
#include <vector>

static void fail(JNIEnv *env, const char *message) {
    env->ThrowNew(env->FindClass("java/lang/IllegalStateException"), message);
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_meetingsidekick_app_WhisperNative_load(JNIEnv *env, jobject, jstring path) {
    whisper_log_set([](enum ggml_log_level, const char *, void *) {}, nullptr);
    const char *file = env->GetStringUTFChars(path, nullptr);
    if (!file) return 0;
    auto params = whisper_context_default_params();
    params.use_gpu = false;
    auto *context = whisper_init_from_file_with_params(file, params);
    env->ReleaseStringUTFChars(path, file);
    if (!context) fail(env, "Whisper could not load this model. Import a valid ggml tiny.en or base.en model.");
    return reinterpret_cast<jlong>(context);
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_meetingsidekick_app_WhisperNative_transcribe(JNIEnv *env, jobject, jlong handle, jfloatArray audio, jint audio_context) {
    auto *context = reinterpret_cast<whisper_context *>(handle);
    const auto count = env->GetArrayLength(audio);
    if (!context || count < 1600 || count > 30 * 16000) {
        fail(env, "Invalid audio window.");
        return nullptr;
    }
    if (audio_context != 0 && (audio_context != 768 || count > 8 * 16000 ||
            audio_context > whisper_model_n_audio_ctx(context))) {
        fail(env, "Invalid live audio context.");
        return nullptr;
    }
    std::vector<float> samples(count);
    env->GetFloatArrayRegion(audio, 0, count, samples.data());
    if (env->ExceptionCheck()) return nullptr;
    auto params = whisper_full_default_params(WHISPER_SAMPLING_GREEDY);
    params.n_threads = 4;
    // Short live clips can use less encoder context; file imports keep the default.
    params.audio_ctx = audio_context;
    params.language = "en";
    params.translate = false;
    params.no_context = true;
    params.no_timestamps = true;
    params.single_segment = false;
    params.print_realtime = false;
    params.print_progress = false;
    params.print_timestamps = false;
    params.print_special = false;
    params.suppress_blank = true;
    params.suppress_nst = true;
    params.temperature_inc = 0.0f;
    if (whisper_full(context, params, samples.data(), count) != 0) {
        fail(env, "Whisper transcription failed.");
        return nullptr;
    }
    std::string result;
    for (int i = 0; i < whisper_full_n_segments(context); ++i) {
        result += whisper_full_get_segment_text(context, i);
    }
    // Construct through UTF-8 bytes: NewStringUTF expects modified UTF-8.
    jbyteArray bytes = env->NewByteArray(static_cast<jsize>(result.size()));
    if (!bytes) return nullptr;
    env->SetByteArrayRegion(bytes, 0, static_cast<jsize>(result.size()), reinterpret_cast<const jbyte *>(result.data()));
    jclass stringClass = env->FindClass("java/lang/String");
    jmethodID ctor = env->GetMethodID(stringClass, "<init>", "([BLjava/lang/String;)V");
    jstring charset = env->NewStringUTF("UTF-8");
    return static_cast<jstring>(env->NewObject(stringClass, ctor, bytes, charset));
}

extern "C" JNIEXPORT void JNICALL
Java_com_meetingsidekick_app_WhisperNative_free(JNIEnv *, jobject, jlong handle) {
    if (handle) whisper_free(reinterpret_cast<whisper_context *>(handle));
}
