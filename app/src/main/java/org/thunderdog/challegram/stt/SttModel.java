/*
 * Vibegram X: voice message transcription — catalog of downloadable speech recognition models.
 *
 * Every model detects the spoken language by itself and outputs text with punctuation and capitalization.
 * The files are downloaded on request into the app's private storage; nothing is bundled into the APK.
 */
package org.thunderdog.challegram.stt;

import androidx.annotation.Nullable;
import androidx.annotation.StringRes;

import org.thunderdog.challegram.R;

public final class SttModel {
  public static final int ENGINE_WHISPER_CPP = 0;
  public static final int ENGINE_SHERPA_NEMO_TDT = 1;
  /** Sber GigaAM (RNN-T, sherpa-onnx): 64 mel features instead of 80 and chunks of at most 30 s. */
  public static final int ENGINE_SHERPA_GIGAAM = 2;

  public static final class FileSpec {
    public final String name, url;
    public final long size;
    public final @Nullable String sha256;

    FileSpec (String name, String url, long size, @Nullable String sha256) {
      this.name = name;
      this.url = url;
      this.size = size;
      this.sha256 = sha256;
    }
  }

  public final String id;
  public final String title;
  public final @StringRes int languagesRes;
  /**
   * Recognition quality in stars among the multilingual models (1 = the weakest, the best has all of them), from our
   * comparison on real voice messages (slang, mixed ru/en, long notes): wrong and invented words, punctuation.
   * 0 = not rated, no stars shown (single-language models).
   */
  public final int quality;
  public final int engine;
  public final FileSpec[] files;

  private SttModel (String id, String title, @StringRes int languagesRes, int quality, int engine, FileSpec... files) {
    this.id = id;
    this.title = title;
    this.languagesRes = languagesRes;
    this.quality = quality;
    this.engine = engine;
    this.files = files;
  }

  public long totalSize () {
    long size = 0;
    for (FileSpec file : files) {
      size += file.size;
    }
    return size;
  }

  public int sizeMb () {
    return (int) Math.round(totalSize() / (1024.0 * 1024.0));
  }

  /** «252 MB» or «2.3 GB» (1024-based, as the catalog always showed) in the app language. */
  public String sizeText () {
    int mb = sizeMb();
    if (mb < 1000) {
      return org.thunderdog.challegram.core.Lang.getString(R.string.fileSize_MB, String.valueOf(mb));
    }
    return org.thunderdog.challegram.core.Lang.getString(R.string.fileSize_GB, org.thunderdog.challegram.core.Lang.formatNumber(Math.round(mb / 1024.0 * 10) / 10.0));
  }

  /** The first downloaded file whose name starts with the prefix and ends with .onnx (e.g. encoder.int8.onnx or encoder.onnx). */
  public String onnxFile (String prefix) {
    for (FileSpec file : files) {
      if (file.name.startsWith(prefix) && file.name.endsWith(".onnx")) {
        return file.name;
      }
    }
    throw new IllegalStateException(prefix + " for " + id);
  }

  private static final String FUTO = "https://dl.keyboard.futo.org/";
  // Converted by us (see the Parakeet Ultra entry); built from ~/Koding/Vibegram/models/parakeet-ultra-sherpa-onnx.
  private static final String PARAKEET_ULTRA = "https://huggingface.co/aleksey336/parakeet-ultra-sherpa-onnx-int8/resolve/main/";
  private static final String GIGAAM = "https://huggingface.co/Alexxerm/gigaam-v3-e2e-rnnt-sherpa-onnx/resolve/main/";

  private static FileSpec f (String name, String url, long size, @Nullable String sha256) {
    return new FileSpec(name, url, size, sha256);
  }

  /** From the lowest quality to the highest. Russian-only models are listed in the settings in their own section. */
  public static final SttModel[] ALL = {
    // OpenAI Whisper (whisper.cpp ggml q8_0) in FUTO Keyboard builds (finetuned with ACFT).
    // The finetuning allows running the encoder on the real length of messages under 30 s, which is faster,
    // but Base then sometimes stops after the first sentence: we always run the full 30 s window.
    // Stars compare the multilingual models with each other, the best of them gets all five:
    // Base 1 (often stops after the first sentence), Small 3 (19.7% WER on our 9 notes even with beam search),
    // Parakeet 5 (Parakeet Ultra: 8.7% on our 8-note test, many times faster than Small).
    new SttModel("futo_74", "Whisper Base (FUTO)", R.string.vg_SttLangWhisper, 1, ENGINE_WHISPER_CPP,
      f("model.bin", FUTO + "voice-input-multilingual-74.bin", 81768602L, "e44f352c9aa2c3609dece20c733c4ad4a75c28cd9ab07d005383df55fa96efc4")),
    new SttModel("futo_244", "Whisper Small (FUTO)", R.string.vg_SttLangWhisper, 3, ENGINE_WHISPER_CPP,
      f("model.bin", FUTO + "voice-input-multilingual-244.bin", 264464624L, "15ef255465a6dc582ecf1ec651a4618c7ee2c18c05570bbe46493d248d465ac4")),

    // Sber GigaAM v3, end-to-end RNN-T variant: writes punctuation, capitals and numbers itself. Russian only.
    // int8 encoder, sherpa-onnx export by Alexxerm (MIT, from istupakov/gigaam-v3-onnx).
    // Shown on its own under «Лучший для русского языка»: on Russian speech it beats every multilingual model here
    // (independent benchmark: 6.6–7.0% WER vs 9.3–10.8% for Parakeet), but it does not know other languages.
    // No stars (0): the app is international, and stars on the multilingual scale would mislead for other languages.
    new SttModel("gigaam_v3_e2e", "Sber GigaAM v3", R.string.vg_SttLangGigaAm, 0, ENGINE_SHERPA_GIGAAM,
      f("encoder.int8.onnx", GIGAAM + "encoder.int8.onnx", 224571327L, "aa4e9bd1d8f1b30534c0b56d743970be53fd102dba639c2b1cc06e2aa898ada0"),
      f("decoder.onnx", GIGAAM + "decoder.onnx", 1159351L, "ea08443676485385c0dd31d938233bffaed6e52d47d84844c9b568589f1c9b27"),
      f("joiner.onnx", GIGAAM + "joiner.onnx", 687791L, "ade116563dbf66e503b0994efab6b5861412743e52bf31c39fc3fffa3783d5d1"),
      f("tokens.txt", GIGAAM + "tokens.txt", 13354L, "39abae20e692998290c574e606f11a9edef2902a1995463fcff63d1490cf22b7")),

    // Parakeet Ultra (moondream): nvidia/parakeet-tdt-0.6b-v3 post-trained further, same size, tokenizer and speed.
    // Encoder: Olicorne's ONNX export with 8-bit block-wise weights (MatMulNBits, block 64) plus the sherpa-onnx metadata;
    // decoder and joiner: the sherpa-onnx v3 graphs with the Ultra weights (fp32). On our 8 Russian test notes
    // 8.7% WER vs 17.1% for the previous Parakeet int8 (csukuangfj, per-tensor int8) at the same speed on a Mac CPU:
    // most of that gap was the per-tensor quantization, the rest is Ultra's training. Its joiner never emits <unk>.
    new SttModel("parakeet_ultra", "Parakeet Ultra 0.6B", R.string.vg_SttLangParakeet, 5, ENGINE_SHERPA_NEMO_TDT,
      f("encoder.int8.onnx", PARAKEET_ULTRA + "encoder.int8.onnx", 649431984L, "f7716e2483661a410658239f4923955a3e98eb4a75fe61b40200bc0470a5db8a"),
      f("decoder.onnx", PARAKEET_ULTRA + "decoder.onnx", 47233743L, "3f09873ec6ac5e17c80542496ee6dcf79490c59a92f68bbdeba2a525208bc3b1"),
      f("joiner.onnx", PARAKEET_ULTRA + "joiner.onnx", 25286330L, "432fad3750296cf9bccad92870625367ffbda9ec733243f6958e75f42ab63ba4"),
      f("tokens.txt", PARAKEET_ULTRA + "tokens.txt", 93939L, "d58544679ea4bc6ac563d1f545eb7d474bd6cfa467f0a6e2c1dc1c7d37e3c35d")),
  };

  /**
   * Number of stars: the best model of the catalog gets them all,
   * so a full row always means "the best available here".
   */
  public static final int MAX_QUALITY = maxQuality();

  private static int maxQuality () {
    int max = 1;
    for (SttModel model : ALL) {
      max = Math.max(max, model.quality);
    }
    return max;
  }

  /** Original authors of the models and engines, shown at the bottom of the settings screen. */
  public static final class Credit {
    public final String title, url;
    public final boolean github;

    Credit (String title, String url) {
      this.title = title;
      this.url = url;
      this.github = url.startsWith("https://github.com/");
    }

    /** The address without the scheme, as the row subtitle. */
    public String displayUrl () {
      return url.substring(url.indexOf("://") + 3);
    }
  }

  public static final Credit[] MODEL_CREDITS = {
    new Credit("OpenAI Whisper", "https://github.com/openai/whisper"),
    new Credit("FUTO Whisper ACFT", "https://github.com/futo-org/whisper-acft"),
    new Credit("NVIDIA Parakeet TDT 0.6B v3", "https://huggingface.co/nvidia/parakeet-tdt-0.6b-v3"),
    new Credit("Moondream Parakeet Ultra", "https://huggingface.co/moondream/parakeet-ultra"),
    new Credit("Parakeet Ultra ONNX (Olicorne)", "https://huggingface.co/Olicorne/parakeet-tdt-0.6b-v3-ultra-onnx"),
    new Credit("Sber GigaAM v3", "https://github.com/salute-developers/GigaAM"),
  };

  public static final Credit[] ENGINE_CREDITS = {
    new Credit("whisper.cpp", "https://github.com/ggml-org/whisper.cpp"),
    new Credit("sherpa-onnx", "https://github.com/k2-fsa/sherpa-onnx"),
  };

  /** Knows only Russian: shown separately as the best model for Russian. */
  public boolean isRussianOnly () {
    return engine == ENGINE_SHERPA_GIGAAM;
  }

  public static @Nullable SttModel find (@Nullable String id) {
    if (id == null) {
      return null;
    }
    for (SttModel model : ALL) {
      if (model.id.equals(id)) {
        return model;
      }
    }
    return null;
  }
}
