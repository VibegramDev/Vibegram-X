/*
 * Vibegram X: JNI bridge between org.thunderdog.challegram.stt.LlmNative and llama.cpp,
 * used by «Enhanced transcription» to correct the recognized text with a small language model.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <https://www.gnu.org/licenses/>.
 *
 * Built by scripts/stt/build_vgllama.sh into libvgllama.so, libvgllama_v82.so and libvgllama_v86.so.
 * One loaded model per handle; every generate() call creates its own context sized for the prompt and
 * decodes greedily (always the most probable token), so the same text always gets the same answer.
 */

#ifndef VG_HOST_TEST
#include <jni.h>
#endif

#include <algorithm>
#include <atomic>
#include <cstring>
#include <string>
#include <vector>

#include "llama.h"

#ifdef __ANDROID__
#include <android/log.h>
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, "VgLlm", __VA_ARGS__)
#else
#include <cstdio>
#define LOGE(...) (fprintf(stderr, __VA_ARGS__), fputc('\n', stderr))
#endif

struct VgLlm {
  llama_model * model = nullptr;
  std::atomic<bool> cancelled{false};
};

static bool abort_callback (void * data) {
  return static_cast<VgLlm *>(data)->cancelled.load();
}

static void log_callback (ggml_log_level level, const char * text, void *) {
  if (level == GGML_LOG_LEVEL_ERROR) {
    LOGE("%s", text);
  }
}

static llama_model * load_model (const char * path) {
  static bool backend = false;
  if (!backend) {
    llama_log_set(log_callback, nullptr);
    llama_backend_init();
    backend = true;
  }
  llama_model_params mp = llama_model_default_params();
  mp.n_gpu_layers = 0;
  // Read the file instead of mapping it: the CPU backend repacks the 4-bit weights into its own layout in memory,
  // so a mapping would keep a second, unused copy of the file resident.
  mp.load_mode = LLAMA_LOAD_MODE_NONE;
  return llama_model_load_from_file(path, mp);
}

// Greedy generation for a prompt with the chat template already applied (special tokens are parsed).
// on_token(generated) is called after every token on the calling thread. Returns false on failure or cancel.
template <typename F>
static bool vg_generate (VgLlm * llm, const std::string & prompt, int maxTokens, int threads, std::string & out, F on_token) {
  const llama_vocab * vocab = llama_model_get_vocab(llm->model);
  int n = -llama_tokenize(vocab, prompt.c_str(), (int32_t) prompt.size(), nullptr, 0, true, true);
  if (n <= 0) {
    return false;
  }
  std::vector<llama_token> tokens(n);
  if (llama_tokenize(vocab, prompt.c_str(), (int32_t) prompt.size(), tokens.data(), n, true, true) < 0) {
    return false;
  }

  llama_context_params cp = llama_context_default_params();
  cp.n_ctx = (uint32_t) (n + maxTokens + 8);
  cp.n_batch = 512;
  cp.n_ubatch = 512;
  cp.n_threads = threads;
  cp.n_threads_batch = threads;
  cp.abort_callback = abort_callback;
  cp.abort_callback_data = llm;
  cp.no_perf = true;
  llama_context * ctx = llama_init_from_model(llm->model, cp);
  if (ctx == nullptr) {
    LOGE("context failed for %d tokens", n);
    return false;
  }
  llama_sampler * sampler = llama_sampler_chain_init(llama_sampler_chain_default_params());
  llama_sampler_chain_add(sampler, llama_sampler_init_greedy());

  bool ok = true;
  // the prompt in batches of n_batch tokens
  for (int i = 0; i < n && ok; i += (int) cp.n_batch) {
    int count = std::min((int) cp.n_batch, n - i);
    if (llama_decode(ctx, llama_batch_get_one(tokens.data() + i, count)) != 0) {
      ok = false;
    }
  }
  int generated = 0;
  while (ok && generated < maxTokens) {
    if (llm->cancelled) {
      ok = false;
      break;
    }
    llama_token token = llama_sampler_sample(sampler, ctx, -1);
    if (llama_vocab_is_eog(vocab, token)) {
      break;
    }
    char piece[256];
    int length = llama_token_to_piece(vocab, token, piece, sizeof(piece), 0, false);
    if (length > 0) {
      out.append(piece, length);
    }
    generated++;
    on_token(generated);
    if (llama_decode(ctx, llama_batch_get_one(&token, 1)) != 0) {
      ok = false;
    }
  }
  llama_sampler_free(sampler);
  llama_free(ctx);
  return ok && !llm->cancelled;
}

#ifndef VG_HOST_TEST
extern "C" {

JNIEXPORT jlong JNICALL
Java_org_thunderdog_challegram_stt_LlmNative_init (JNIEnv * env, jclass, jstring jpath) {
  const char * path = env->GetStringUTFChars(jpath, nullptr);
  llama_model * model = load_model(path);
  env->ReleaseStringUTFChars(jpath, path);
  if (model == nullptr) {
    return 0;
  }
  VgLlm * llm = new VgLlm();
  llm->model = model;
  return reinterpret_cast<jlong>(llm);
}

JNIEXPORT void JNICALL
Java_org_thunderdog_challegram_stt_LlmNative_free (JNIEnv *, jclass, jlong ptr) {
  VgLlm * llm = reinterpret_cast<VgLlm *>(ptr);
  if (llm != nullptr) {
    llama_model_free(llm->model);
    delete llm;
  }
}

JNIEXPORT void JNICALL
Java_org_thunderdog_challegram_stt_LlmNative_cancel (JNIEnv *, jclass, jlong ptr) {
  reinterpret_cast<VgLlm *>(ptr)->cancelled = true;
}

JNIEXPORT void JNICALL
Java_org_thunderdog_challegram_stt_LlmNative_resetCancel (JNIEnv *, jclass, jlong ptr) {
  reinterpret_cast<VgLlm *>(ptr)->cancelled = false;
}

// prompt: UTF-8 bytes (Java strings are not passed as modified UTF-8). Returns UTF-8 bytes, null on failure or cancel.
JNIEXPORT jbyteArray JNICALL
Java_org_thunderdog_challegram_stt_LlmNative_generate (JNIEnv * env, jclass, jlong ptr, jbyteArray jprompt, jint maxTokens, jint threads, jobject listener) {
  VgLlm * llm = reinterpret_cast<VgLlm *>(ptr);
  jsize promptLength = env->GetArrayLength(jprompt);
  std::string prompt(promptLength, '\0');
  env->GetByteArrayRegion(jprompt, 0, promptLength, reinterpret_cast<jbyte *>(&prompt[0]));
  jmethodID onToken = nullptr;
  if (listener != nullptr) {
    jclass cls = env->GetObjectClass(listener);
    onToken = env->GetMethodID(cls, "onToken", "(I)V");
    env->DeleteLocalRef(cls);
  }
  std::string out;
  bool ok = vg_generate(llm, prompt, maxTokens, threads, out, [&](int generated) {
    if (onToken != nullptr) {
      env->CallVoidMethod(listener, onToken, (jint) generated);
      if (env->ExceptionCheck()) {
        env->ExceptionClear();
      }
    }
  });
  if (!ok) {
    return nullptr;
  }
  jbyteArray result = env->NewByteArray((jsize) out.size());
  env->SetByteArrayRegion(result, 0, (jsize) out.size(), reinterpret_cast<const jbyte *>(out.data()));
  return result;
}

}
#else
#include <fstream>
#include <sstream>
// Desktop test: vgllama_test model.gguf prompt.txt prints the answer, the same code path as the app
int main (int argc, char ** argv) {
  if (argc < 3) {
    fprintf(stderr, "usage: %s model.gguf prompt.txt\n", argv[0]);
    return 2;
  }
  VgLlm llm;
  llm.model = load_model(argv[1]);
  if (llm.model == nullptr) {
    return 1;
  }
  std::ifstream f(argv[2]);
  std::stringstream b;
  b << f.rdbuf();
  std::string out;
  bool ok = vg_generate(&llm, b.str(), 2048, 4, out, [](int) {});
  printf("%s", out.c_str());
  llama_model_free(llm.model);
  return ok ? 0 : 1;
}
#endif
