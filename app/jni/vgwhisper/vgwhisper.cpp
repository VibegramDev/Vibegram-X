/*
 * Vibegram X: JNI bridge between org.thunderdog.challegram.stt.WhisperNative and whisper.cpp.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <https://www.gnu.org/licenses/>.
 *
 * Built by scripts/stt/build_vgwhisper.sh into libvgwhisper.so, libvgwhisper_v82.so and libvgwhisper_v86.so.
 */

#include <jni.h>
#include <pthread.h>

#include <algorithm>
#include <atomic>
#include <cmath>
#include <cstdint>
#include <cstdlib>
#include <cstring>
#include <ctime>
#include <string>
#include <vector>

#include "whisper.h"

#define TAG "VgWhisper"
#ifdef __ANDROID__
#include <android/log.h>
#define LOG(priority, ...) __android_log_print(ANDROID_LOG_ ## priority, TAG, __VA_ARGS__)
#else
#include <cstdio>
#define LOG(priority, ...) (fprintf(stderr, TAG " " #priority ": " __VA_ARGS__), fputc('\n', stderr))
#endif

// 100 ms of 16 kHz audio
static constexpr int FRAME = WHISPER_SAMPLE_RATE / 10;
// whisper.cpp times are in 10 ms units
static constexpr int SAMPLES_PER_TIME_UNIT = WHISPER_SAMPLE_RATE / 100;

struct Context {
  whisper_context * ctx;
  std::atomic<int> cancelled;
};

struct Session {
  JNIEnv * env;
  jobject listener;
  jmethodID onProgress, onSegment, onLive;
  const float * samples;
  int sampleCount;
  float speechLevel;
  int duration;
  int windowStart;
  int lastProgress;
  int lastTokenCount;
  int64_t lastLiveMs;
  char * live;
  size_t liveCapacity;
  pthread_t thread;
};

static float rms (const float * samples, int count) {
  double sum = 0;
  for (int i = 0; i < count; i++) {
    sum += (double) samples[i] * samples[i];
  }
  return (float) std::sqrt(sum / count);
}

/** Typical loudness of speech in the message: 75th percentile of the loudness of 100 ms frames. */
static float speechLevel (const float * samples, int count) {
  const int frames = count / FRAME;
  if (frames == 0) {
    return 0;
  }
  std::vector<float> levels(frames);
  for (int i = 0; i < frames; i++) {
    levels[i] = rms(samples + i * FRAME, FRAME);
  }
  std::sort(levels.begin(), levels.end());
  return levels[frames * 3 / 4];
}

/**
 * Whisper invents text on silence and noise (subtitle credits and the like). Such a segment has a high no-speech
 * probability and a low average log probability of its text tokens: the same check as OpenAI's
 * no_speech_threshold / logprob_threshold.
 */
static bool isSpeech (whisper_context * ctx, int segment) {
  if (!(whisper_full_get_segment_no_speech_prob(ctx, segment) > 0.6f)) {
    return true;
  }
  const int tokenCount = whisper_full_n_tokens(ctx, segment);
  const whisper_token eot = whisper_token_eot(ctx);
  double sum = 0;
  int count = 0;
  for (int i = 0; i < tokenCount; i++) {
    whisper_token_data token = whisper_full_get_token_data(ctx, segment, i);
    if (token.id < eot) {
      sum += token.plog;
      count++;
    }
  }
  return count > 0 && !(sum / count < -1.0);
}

static void clearException (JNIEnv * env) {
  if (env->ExceptionCheck()) {
    env->ExceptionClear();
  }
}

static void reportProgress (Session * s, int progress) {
  progress = std::min(progress, 100);
  if (progress > s->lastProgress && s->listener != nullptr && s->onProgress != nullptr) {
    s->lastProgress = progress;
    s->env->CallVoidMethod(s->listener, s->onProgress, progress);
    clearException(s->env);
  }
}

static void sendText (Session * s, jmethodID method, const char * text, size_t length) {
  if (method == nullptr || s->listener == nullptr) {
    return;
  }
  JNIEnv * env = s->env;
  jbyteArray array = env->NewByteArray((jsize) length);
  if (array == nullptr) {
    clearException(env);
    return;
  }
  env->SetByteArrayRegion(array, 0, (jsize) length, (const jbyte *) text);
  env->CallVoidMethod(s->listener, method, array);
  clearException(env);
  env->DeleteLocalRef(array);
}

/** Length without an incomplete UTF-8 sequence at the end: a token can end in the middle of a character. */
static size_t completeUtf8Length (const char * text, size_t length) {
  size_t start = length, continuation = 0;
  while (start > 0 && continuation < 4 && (text[start - 1] & 0xc0) == 0x80) {
    start--;
    continuation++;
  }
  if (start == 0) {
    return length;
  }
  const auto lead = (unsigned char) text[start - 1];
  const size_t expected = lead >= 0xf0 ? 4 : lead >= 0xe0 ? 3 : lead >= 0xc0 ? 2 : 1;
  return expected == continuation + 1 ? length : start - 1;
}

static int64_t uptimeMs () {
  timespec time {};
  clock_gettime(CLOCK_MONOTONIC, &time);
  return (int64_t) time.tv_sec * 1000 + time.tv_nsec / 1000000;
}

/** Called before each 30 s window: remembers where the window starts. */
static void onProgress (whisper_context *, whisper_state * state, int progress, void * data) {
  auto * s = (Session *) data;
  int position = (int) ((int64_t) s->duration * progress / 100);
  const int segmentCount = whisper_full_n_segments_from_state(state);
  if (segmentCount > 0) {
    // progress is rounded to 1% of the audio; the window starts where the last segment ended
    const int end = (int) whisper_full_get_segment_t1_from_state(state, segmentCount - 1);
    if (end > position && end <= s->duration) {
      position = end;
    }
  }
  s->windowStart = position;
  s->lastTokenCount = -1;
  reportProgress(s, progress);
}

/**
 * Stops before a window with less than a second of audio or without speech: on such a tail
 * Whisper only repeats itself or invents text.
 */
static bool onEncoderBegin (whisper_context *, whisper_state * state, void * data) {
  auto * s = (Session *) data;
  if (whisper_full_n_segments_from_state(state) == 0) {
    // language detection or the first window
    return true;
  }
  const int start = s->windowStart * SAMPLES_PER_TIME_UNIT;
  const int remaining = s->sampleCount - start;
  if (remaining < WHISPER_SAMPLE_RATE) {
    LOG(INFO, "skip tail: %.2f s", remaining / (float) WHISPER_SAMPLE_RATE);
    return false;
  }
  float loudest = 0;
  for (int i = start; i + FRAME <= s->sampleCount; i += FRAME / 2) {
    loudest = std::max(loudest, rms(s->samples + i, FRAME));
  }
  if (loudest < s->speechLevel * 0.2f) {
    LOG(INFO, "skip silent tail: %.2f s, loudest %.4f, speech %.4f", remaining / (float) WHISPER_SAMPLE_RATE, loudest, s->speechLevel);
    return false;
  }
  return true;
}

/** Called for each decoded token: precise progress from the last timestamp token and the live text of the current segment. */
static void onLogits (whisper_context * ctx, whisper_state *, const whisper_token_data * tokens, int tokenCount, float *, void * data) {
  auto * s = (Session *) data;
  // with several decoders (temperature fallback, beam search) whisper.cpp filters logits on its own threads, which have no JNIEnv
  if (!pthread_equal(pthread_self(), s->thread) || tokenCount == s->lastTokenCount) {
    return;
  }
  s->lastTokenCount = tokenCount;
  if (s->duration <= 0) {
    return;
  }
  const whisper_token beg = whisper_token_beg(ctx);
  const whisper_token eot = whisper_token_eot(ctx);
  for (int i = tokenCount - 1; i >= 0; i--) {
    if (tokens[i].id > beg) {
      // timestamp tokens go in 20 ms steps
      reportProgress(s, (int) (((int64_t) s->windowStart + 2 * (tokens[i].id - beg)) * 100 / s->duration));
      break;
    }
  }

  if (tokenCount == 0 || s->onLive == nullptr) {
    return;
  }
  const int64_t now = uptimeMs();
  if (now - s->lastLiveMs < 150) {
    return;
  }
  s->lastLiveMs = now;
  size_t length = 0;
  for (int i = 0; i < tokenCount; i++) {
    if (tokens[i].id >= eot) {
      continue;
    }
    const char * text = whisper_token_to_str(ctx, tokens[i].id);
    if (text == nullptr) {
      continue;
    }
    const size_t textLength = strlen(text);
    if (length + textLength + 1 > s->liveCapacity) {
      const size_t capacity = (length + textLength + 1) * 2;
      auto * live = (char *) realloc(s->live, capacity);
      if (live == nullptr) {
        return;
      }
      s->live = live;
      s->liveCapacity = capacity;
    }
    memcpy(s->live + length, text, textLength);
    length += textLength;
  }
  sendText(s, s->onLive, s->live, completeUtf8Length(s->live, length));
}

static void onNewSegment (whisper_context * ctx, whisper_state *, int newCount, void * data) {
  auto * s = (Session *) data;
  const int segmentCount = whisper_full_n_segments(ctx);
  for (int i = std::max(0, segmentCount - newCount); i < segmentCount; i++) {
    if (isSpeech(ctx, i)) {
      const char * text = whisper_full_get_segment_text(ctx, i);
      sendText(s, s->onSegment, text, strlen(text));
    }
  }
  s->lastTokenCount = -1;
}

static bool onAbort (void * data) {
  return ((Context *) data)->cancelled != 0;
}

static void onLog (ggml_log_level level, const char * text, void *) {
  if (level >= GGML_LOG_LEVEL_WARN) {
    LOG(WARN, "%s", text);
  }
}

static jmethodID findMethod (JNIEnv * env, jclass cls, const char * name, const char * signature) {
  jmethodID method = env->GetMethodID(cls, name, signature);
  if (env->ExceptionCheck()) {
    env->ExceptionClear();
    return nullptr;
  }
  return method;
}

/** "language\ntext" in UTF-8 */
static jbyteArray recognizedText (JNIEnv * env, whisper_context * ctx) {
  const char * language = whisper_lang_str(whisper_full_lang_id(ctx));
  std::string result = language != nullptr ? language : "";
  result += '\n';
  const int segmentCount = whisper_full_n_segments(ctx);
  for (int i = 0; i < segmentCount; i++) {
    if (isSpeech(ctx, i)) {
      result += whisper_full_get_segment_text(ctx, i);
    }
  }
  jbyteArray array = env->NewByteArray((jsize) result.size());
  if (array == nullptr) {
    clearException(env);
    return nullptr;
  }
  env->SetByteArrayRegion(array, 0, (jsize) result.size(), (const jbyte *) result.data());
  return array;
}

extern "C" {

JNIEXPORT jlong JNICALL Java_org_thunderdog_challegram_stt_WhisperNative_init (JNIEnv * env, jclass, jstring modelPath, jboolean flashAttn) {
  whisper_log_set(onLog, nullptr);
  const char * path = env->GetStringUTFChars(modelPath, nullptr);
  whisper_context_params params = whisper_context_default_params();
  params.use_gpu = false;
  params.flash_attn = flashAttn;
  whisper_context * ctx = whisper_init_from_file_with_params(path, params);
  env->ReleaseStringUTFChars(modelPath, path);
  if (ctx == nullptr) {
    return 0;
  }
  return (jlong) new Context {ctx, {0}};
}

JNIEXPORT void JNICALL Java_org_thunderdog_challegram_stt_WhisperNative_free (JNIEnv *, jclass, jlong ptr) {
  auto * context = (Context *) ptr;
  if (context != nullptr) {
    whisper_free(context->ctx);
    delete context;
  }
}

JNIEXPORT void JNICALL Java_org_thunderdog_challegram_stt_WhisperNative_cancel (JNIEnv *, jclass, jlong ptr) {
  auto * context = (Context *) ptr;
  if (context != nullptr) {
    context->cancelled = 1;
  }
}

JNIEXPORT void JNICALL Java_org_thunderdog_challegram_stt_WhisperNative_resetCancel (JNIEnv *, jclass, jlong ptr) {
  auto * context = (Context *) ptr;
  if (context != nullptr) {
    context->cancelled = 0;
  }
}

JNIEXPORT jbyteArray JNICALL Java_org_thunderdog_challegram_stt_WhisperNative_transcribe (JNIEnv * env, jclass, jlong ptr, jfloatArray samples, jint threads, jint beamSize, jstring prompt, jobject listener) {
  auto * context = (Context *) ptr;
  if (context == nullptr || context->cancelled != 0) {
    return nullptr;
  }
  const jsize sampleCount = env->GetArrayLength(samples);
  jfloat * data = env->GetFloatArrayElements(samples, nullptr);
  const char * promptChars = prompt != nullptr ? env->GetStringUTFChars(prompt, nullptr) : nullptr;

  Session s {};
  s.env = env;
  s.listener = listener;
  s.samples = data;
  s.sampleCount = sampleCount;
  s.speechLevel = speechLevel(data, sampleCount);
  s.duration = sampleCount / SAMPLES_PER_TIME_UNIT;
  s.lastProgress = -1;
  s.lastTokenCount = -1;
  s.thread = pthread_self();
  if (listener != nullptr) {
    jclass cls = env->GetObjectClass(listener);
    s.onProgress = findMethod(env, cls, "onProgress", "(I)V");
    s.onSegment = findMethod(env, cls, "onSegment", "([B)V");
    s.onLive = findMethod(env, cls, "onLive", "([B)V");
    env->DeleteLocalRef(cls);
  }

  whisper_full_params params = whisper_full_default_params(beamSize > 1 ? WHISPER_SAMPLING_BEAM_SEARCH : WHISPER_SAMPLING_GREEDY);
  if (beamSize > 1) {
    params.beam_search.beam_size = beamSize;
    params.greedy.best_of = beamSize;
  }
  params.n_threads = threads;
  params.translate = false;
  params.no_context = true;
  params.no_timestamps = false;
  params.single_segment = false;
  params.print_special = false;
  params.print_progress = false;
  params.print_realtime = false;
  params.print_timestamps = false;
  params.initial_prompt = promptChars;
  params.language = "auto";
  params.detect_language = false;
  params.suppress_blank = true;
  params.suppress_nst = true;
  params.temperature = 0.0f;
  // fewer fallback passes than the default 0.2 step when a window fails the entropy / log probability check
  params.temperature_inc = 0.5f;
  params.new_segment_callback = onNewSegment;
  params.new_segment_callback_user_data = &s;
  params.progress_callback = onProgress;
  params.progress_callback_user_data = &s;
  params.encoder_begin_callback = onEncoderBegin;
  params.encoder_begin_callback_user_data = &s;
  params.logits_filter_callback = onLogits;
  params.logits_filter_callback_user_data = &s;
  params.abort_callback = onAbort;
  params.abort_callback_user_data = context;

  const int result = whisper_full(context->ctx, params, data, sampleCount);

  env->ReleaseFloatArrayElements(samples, data, JNI_ABORT);
  if (promptChars != nullptr) {
    env->ReleaseStringUTFChars(prompt, promptChars);
  }
  free(s.live);
  if (result != 0 || context->cancelled != 0) {
    return nullptr;
  }
  return recognizedText(env, context->ctx);
}

}
