#include <jni.h>
#include <stdlib.h>
#include <string.h>
#include <math.h>
#include <time.h>
#include <stdatomic.h>
#include <pthread.h>
#include <android/log.h>
#include "whisper.h"

#define TAG "VgWhisper"

typedef struct {
  struct whisper_context *ctx;
  atomic_int cancelled;
} vg_ctx;

typedef struct {
  JNIEnv *env;
  jobject listener;
  jmethodID onProgress;
  jmethodID onSegment;
  jmethodID onLive;
  const float *samples;
  int n_samples;
  float speech_rms;   // typical loudness of the speech in this message
  int total_frames;   // audio length in 10 ms frames (the unit of whisper's seek)
  int window_frames;  // start of the 30 s window being decoded
  int last_reported;  // last percent sent to Java
  int last_step;      // number of tokens at the last live update (one call per decoding step)
  long long last_live_ms;
  char *live;         // buffer for the live text
  size_t live_cap;
  pthread_t thread;   // the thread that called whisper_full(): the only one where env is valid
} vg_progress;

static long long vg_now_ms (void) {
  struct timespec ts;
  clock_gettime(CLOCK_MONOTONIC, &ts);
  return (long long) ts.tv_sec * 1000 + ts.tv_nsec / 1000000;
}

// Text goes to Java as UTF-8 bytes: whisper tokens may split a multi-byte character, and NewStringUTF
// aborts the app on invalid UTF-8. Java decodes the bytes and replaces broken characters.
static jbyteArray vg_bytes (JNIEnv *env, const char *text, size_t len) {
  jbyteArray arr = (*env)->NewByteArray(env, (jsize) len);
  if (arr == NULL) {
    if ((*env)->ExceptionCheck(env)) (*env)->ExceptionClear(env);
    return NULL;
  }
  (*env)->SetByteArrayRegion(env, arr, 0, (jsize) len, (const jbyte *) text);
  return arr;
}

static void vg_call_bytes (vg_progress *p, jmethodID method, const char *text, size_t len) {
  if (p->listener == NULL || method == NULL) return;
  jbyteArray arr = vg_bytes(p->env, text, len);
  if (arr == NULL) return;
  (*p->env)->CallVoidMethod(p->env, p->listener, method, arr);
  if ((*p->env)->ExceptionCheck(p->env)) (*p->env)->ExceptionClear(p->env);
  (*p->env)->DeleteLocalRef(p->env, arr);
}

static void vg_report (vg_progress *p, int percent) {
  if (percent > 100) percent = 100;
  if (percent <= p->last_reported || p->listener == NULL || p->onProgress == NULL) return;
  p->last_reported = percent;
  (*p->env)->CallVoidMethod(p->env, p->listener, p->onProgress, (jint) percent);
  if ((*p->env)->ExceptionCheck(p->env)) (*p->env)->ExceptionClear(p->env);
}

static bool vg_is_silence (struct whisper_context *ctx, int seg);

static bool vg_abort (void *data) {
  vg_ctx *c = (vg_ctx *) data;
  return atomic_load(&c->cancelled) != 0;
}

// whisper_full() calls all callbacks on the thread that called it, so the JNIEnv of transcribe() is valid there.

// Called once per 30 s window, with the position of the window start.
static void vg_on_progress (struct whisper_context *ctx, struct whisper_state *state, int progress, void *data) {
  vg_progress *p = (vg_progress *) data;
  int frames = (int) ((long long) progress * p->total_frames / 100);
  int segs = whisper_full_n_segments_from_state(state);
  if (segs > 0) {
    // the next window starts where the text of the previous one ended: more precise than the percent
    int t1 = (int) whisper_full_get_segment_t1_from_state(state, segs - 1);
    if (t1 > frames && t1 <= p->total_frames) frames = t1;
  }
  p->window_frames = frames;
  p->last_step = -1;
  vg_report(p, progress);
}

static int vg_cmp_float (const void *a, const void *b) {
  float x = *(const float *) a, y = *(const float *) b;
  return x < y ? -1 : x > y ? 1 : 0;
}

#define VG_RMS_WINDOW 1600 // 100 ms

static float vg_window_rms (const float *s, int from, int count) {
  double sum = 0;
  for (int i = 0; i < count; i++) sum += (double) s[from + i] * s[from + i];
  return (float) sqrt(sum / count);
}

// Loudness of the speech: 75th percentile of 100 ms windows.
static float vg_speech_rms (const float *s, int n) {
  int windows = n / VG_RMS_WINDOW;
  if (windows <= 0) return 0;
  float *rms = (float *) malloc(sizeof(float) * windows);
  for (int i = 0; i < windows; i++) rms[i] = vg_window_rms(s, i * VG_RMS_WINDOW, VG_RMS_WINDOW);
  qsort(rms, windows, sizeof(float), vg_cmp_float);
  float result = rms[windows * 3 / 4];
  free(rms);
  return result;
}

// Called before encoding each 30 s window. Returning false ends the transcription keeping the text so far.
// The text of a window usually ends before the window does, so whisper starts one more window on the tail;
// when the tail is short or silent this costs a whole extra encoder pass (seconds on a phone) for no text.
static bool vg_on_encoder_begin (struct whisper_context *ctx, struct whisper_state *state, void *data) {
  vg_progress *p = (vg_progress *) data;
  if (whisper_full_n_segments_from_state(state) == 0) return true;
  int from = p->window_frames * 160;
  int remaining = p->n_samples - from;
  if (remaining < 16000) {
    __android_log_print(ANDROID_LOG_INFO, TAG, "skip tail: %.2f s", remaining / 16000.0f);
    return false;
  }
  float loudest = 0;
  for (int pos = from; pos + VG_RMS_WINDOW <= p->n_samples; pos += VG_RMS_WINDOW / 2) {
    float rms = vg_window_rms(p->samples, pos, VG_RMS_WINDOW);
    if (rms > loudest) loudest = rms;
  }
  if (loudest < p->speech_rms * 0.2f) {
    __android_log_print(ANDROID_LOG_INFO, TAG, "skip silent tail: %.2f s, loudest %.4f, speech %.4f", remaining / 16000.0f, loudest, p->speech_rms);
    return false;
  }
  return true;
}

// Called before every decoded token, once per decoder.
// 1. The last timestamp token tells how far into the window the text has got: real progress inside a window.
// 2. The tokens decoded so far are sent to Java as live text, so it can be read while it is being recognized.
static void vg_on_token (struct whisper_context *ctx, struct whisper_state *state, const whisper_token_data *tokens, int n_tokens, float *logits, void *data) {
  vg_progress *p = (vg_progress *) data;
  // With temperature fallback whisper.cpp runs several decoders and processes their logits on worker threads,
  // where JNIEnv cannot be used (calling Java there aborts the app). Live updates come only from the main decoder.
  if (!pthread_equal(pthread_self(), p->thread)) return;
  if (n_tokens == p->last_step) return; // the same step for another decoder
  p->last_step = n_tokens;
  if (p->total_frames <= 0) return;
  whisper_token beg = whisper_token_beg(ctx), eot = whisper_token_eot(ctx);
  for (int i = n_tokens - 1; i >= 0; i--) {
    if (tokens[i].id > beg) {
      int frames = p->window_frames + (tokens[i].id - beg) * 2; // one timestamp step is 20 ms
      vg_report(p, (int) ((long long) frames * 100 / p->total_frames));
      break;
    }
  }
  if (p->onLive == NULL || n_tokens == 0) return;
  long long now = vg_now_ms();
  if (now - p->last_live_ms < 150) return;
  p->last_live_ms = now;
  size_t len = 0;
  for (int i = 0; i < n_tokens; i++) {
    if (tokens[i].id >= eot) continue;
    const char *piece = whisper_token_to_str(ctx, tokens[i].id);
    if (piece == NULL) continue;
    size_t l = strlen(piece);
    if (len + l + 1 > p->live_cap) {
      size_t cap = (len + l + 1) * 2;
      char *grown = (char *) realloc(p->live, cap);
      if (grown == NULL) return;
      p->live = grown;
      p->live_cap = cap;
    }
    memcpy(p->live + len, piece, l);
    len += l;
  }
  // drop an incomplete UTF-8 character at the end: its other bytes come with the next token
  size_t end = len, back = 0;
  while (end > 0 && back < 4 && (((unsigned char) p->live[end - 1]) & 0xC0) == 0x80) { end--; back++; }
  if (end > 0) {
    unsigned char lead = (unsigned char) p->live[end - 1];
    size_t need = lead >= 0xF0 ? 4 : lead >= 0xE0 ? 3 : lead >= 0xC0 ? 2 : 1;
    if (need == back + 1) end = len; else if (need > 1) len = end - 1; else len = end;
  }
  vg_call_bytes(p, p->onLive, p->live, len);
}

// A finished segment: sent to Java right away.
static void vg_on_segment (struct whisper_context *ctx, struct whisper_state *state, int n_new, void *data) {
  vg_progress *p = (vg_progress *) data;
  int n = whisper_full_n_segments(ctx);
  for (int i = n - n_new; i < n; i++) {
    if (i < 0 || vg_is_silence(ctx, i)) continue;
    const char *text = whisper_full_get_segment_text(ctx, i);
    vg_call_bytes(p, p->onSegment, text, strlen(text));
  }
  p->last_step = -1;
}

static void vg_log (enum ggml_log_level level, const char *text, void *user_data) {
  if (level >= GGML_LOG_LEVEL_WARN) __android_log_print(ANDROID_LOG_WARN, TAG, "%s", text);
}

JNIEXPORT jlong JNICALL
Java_org_thunderdog_challegram_stt_WhisperNative_init (JNIEnv *env, jclass clazz, jstring jpath, jboolean flashAttn) {
  whisper_log_set(vg_log, NULL);
  const char *path = (*env)->GetStringUTFChars(env, jpath, NULL);
  struct whisper_context_params cp = whisper_context_default_params();
  cp.use_gpu = false;
  cp.flash_attn = flashAttn;
  struct whisper_context *ctx = whisper_init_from_file_with_params(path, cp);
  (*env)->ReleaseStringUTFChars(env, jpath, path);
  if (ctx == NULL) return 0;
  vg_ctx *c = (vg_ctx *) calloc(1, sizeof(vg_ctx));
  c->ctx = ctx;
  return (jlong) (intptr_t) c;
}

JNIEXPORT void JNICALL
Java_org_thunderdog_challegram_stt_WhisperNative_free (JNIEnv *env, jclass clazz, jlong ptr) {
  vg_ctx *c = (vg_ctx *) (intptr_t) ptr;
  if (c == NULL) return;
  whisper_free(c->ctx);
  free(c);
}

JNIEXPORT void JNICALL
Java_org_thunderdog_challegram_stt_WhisperNative_cancel (JNIEnv *env, jclass clazz, jlong ptr) {
  vg_ctx *c = (vg_ctx *) (intptr_t) ptr;
  if (c != NULL) atomic_store(&c->cancelled, 1);
}

JNIEXPORT void JNICALL
Java_org_thunderdog_challegram_stt_WhisperNative_resetCancel (JNIEnv *env, jclass clazz, jlong ptr) {
  vg_ctx *c = (vg_ctx *) (intptr_t) ptr;
  if (c != NULL) atomic_store(&c->cancelled, 0);
}

// Segments that are most likely silence, the same rule as openai/whisper: no_speech_prob > 0.6 and avg logprob < -1.
static bool vg_is_silence (struct whisper_context *ctx, int seg) {
  if (whisper_full_get_segment_no_speech_prob(ctx, seg) <= 0.6f) return false;
  int n = whisper_full_n_tokens(ctx, seg);
  whisper_token eot = whisper_token_eot(ctx);
  double sum = 0;
  int count = 0;
  for (int i = 0; i < n; i++) {
    whisper_token_data d = whisper_full_get_token_data(ctx, seg, i);
    if (d.id >= eot) continue;
    sum += d.plog;
    count++;
  }
  return count == 0 || sum / count < -1.0;
}

// Returns UTF-8 bytes of "lang\ntext" or NULL on failure/cancel.
JNIEXPORT jbyteArray JNICALL
Java_org_thunderdog_challegram_stt_WhisperNative_transcribe (JNIEnv *env, jclass clazz, jlong ptr, jfloatArray jsamples, jint threads, jint beamSize, jstring jprompt, jobject listener) {
  vg_ctx *c = (vg_ctx *) (intptr_t) ptr;
  if (c == NULL || atomic_load(&c->cancelled)) return NULL;
  jsize n = (*env)->GetArrayLength(env, jsamples);
  jfloat *samples = (*env)->GetFloatArrayElements(env, jsamples, NULL);
  const char *prompt = jprompt != NULL ? (*env)->GetStringUTFChars(env, jprompt, NULL) : NULL;

  vg_progress progress;
  memset(&progress, 0, sizeof(progress));
  progress.env = env;
  progress.listener = listener;
  progress.samples = samples;
  progress.n_samples = n;
  progress.speech_rms = vg_speech_rms(samples, n);
  progress.total_frames = n / 160;
  progress.last_reported = -1;
  progress.last_step = -1;
  progress.thread = pthread_self();
  if (listener != NULL) {
    jclass cls = (*env)->GetObjectClass(env, listener);
    progress.onProgress = (*env)->GetMethodID(env, cls, "onProgress", "(I)V");
    if ((*env)->ExceptionCheck(env)) { (*env)->ExceptionClear(env); progress.onProgress = NULL; }
    progress.onSegment = (*env)->GetMethodID(env, cls, "onSegment", "([B)V");
    if ((*env)->ExceptionCheck(env)) { (*env)->ExceptionClear(env); progress.onSegment = NULL; }
    progress.onLive = (*env)->GetMethodID(env, cls, "onLive", "([B)V");
    if ((*env)->ExceptionCheck(env)) { (*env)->ExceptionClear(env); progress.onLive = NULL; }
    (*env)->DeleteLocalRef(env, cls);
  }

  // beamSize > 1: keep that many candidate texts and pick the most probable one at the end (slower, fewer errors);
  // otherwise take the most probable token at every step.
  struct whisper_full_params p = whisper_full_default_params(beamSize > 1 ? WHISPER_SAMPLING_BEAM_SEARCH : WHISPER_SAMPLING_GREEDY);
  if (beamSize > 1) {
    p.beam_search.beam_size = beamSize;
    p.greedy.best_of = beamSize;
  }
  p.n_threads = threads;
  p.language = "auto";
  p.detect_language = false;
  p.translate = false;
  p.no_context = true;
  p.no_timestamps = false;
  p.single_segment = false;
  p.print_progress = false;
  p.print_realtime = false;
  p.print_special = false;
  p.print_timestamps = false;
  p.suppress_blank = true;
  p.suppress_nst = true;
  // Fewer fallback steps (0, 0.5, 1.0 instead of 0, 0.2 ... 1.0): a window that fails the checks
  // is decoded at most 3 times instead of 6, which made long messages many times slower.
  p.temperature = 0.0f;
  p.temperature_inc = 0.5f;
  p.initial_prompt = prompt;
  p.abort_callback = vg_abort;
  p.abort_callback_user_data = c;
  p.progress_callback = vg_on_progress;
  p.progress_callback_user_data = &progress;
  p.encoder_begin_callback = vg_on_encoder_begin;
  p.encoder_begin_callback_user_data = &progress;
  p.logits_filter_callback = vg_on_token;
  p.logits_filter_callback_user_data = &progress;
  p.new_segment_callback = vg_on_segment;
  p.new_segment_callback_user_data = &progress;

  int rc = whisper_full(c->ctx, p, samples, n);
  (*env)->ReleaseFloatArrayElements(env, jsamples, samples, JNI_ABORT);
  if (prompt != NULL) (*env)->ReleaseStringUTFChars(env, jprompt, prompt);
  free(progress.live);
  if (rc != 0 || atomic_load(&c->cancelled)) return NULL;

  const char *lang = whisper_lang_str(whisper_full_lang_id(c->ctx));
  if (lang == NULL) lang = "";
  int segs = whisper_full_n_segments(c->ctx);
  size_t cap = strlen(lang) + 2;
  for (int i = 0; i < segs; i++) cap += strlen(whisper_full_get_segment_text(c->ctx, i));
  char *out = (char *) malloc(cap + 1);
  strcpy(out, lang);
  strcat(out, "\n");
  for (int i = 0; i < segs; i++) {
    if (vg_is_silence(c->ctx, i)) continue;
    strcat(out, whisper_full_get_segment_text(c->ctx, i));
  }
  jbyteArray res = vg_bytes(env, out, strlen(out));
  free(out);
  return res;
}
