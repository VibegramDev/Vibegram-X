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
   * Recognition quality in stars (1 = the weakest), from our comparison on real Russian voice messages
   * (slang, mixed ru/en, long notes): wrong and invented words, punctuation.
   */
  public final int quality;
  public final int engine;
  /** Whisper finetuned with ACFT (FUTO): the encoder may run on the actual audio length instead of 30 s. */
  public final boolean acft;
  public final FileSpec[] files;

  private SttModel (String id, String title, @StringRes int languagesRes, int quality, int engine, boolean acft, FileSpec... files) {
    this.id = id;
    this.title = title;
    this.languagesRes = languagesRes;
    this.quality = quality;
    this.engine = engine;
    this.acft = acft;
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

  private static final String FUTO = "https://dl.keyboard.futo.org/";
  private static final String PARAKEET = "https://huggingface.co/csukuangfj/sherpa-onnx-nemo-parakeet-tdt-0.6b-v3-int8/resolve/main/";

  private static FileSpec f (String name, String url, long size, @Nullable String sha256) {
    return new FileSpec(name, url, size, sha256);
  }

  /** From the lowest quality to the highest. */
  public static final SttModel[] ALL = {
    // OpenAI Whisper (whisper.cpp ggml q8_0) in FUTO Keyboard builds:
    // finetuned (ACFT) to run on the real audio length, which makes short messages faster.
    new SttModel("futo_74", "Whisper Base (FUTO)", R.string.vg_SttLangWhisper, 2, ENGINE_WHISPER_CPP, true,
      f("model.bin", FUTO + "voice-input-multilingual-74.bin", 81768602L, "e44f352c9aa2c3609dece20c733c4ad4a75c28cd9ab07d005383df55fa96efc4")),
    new SttModel("futo_244", "Whisper Small (FUTO)", R.string.vg_SttLangWhisper, 3, ENGINE_WHISPER_CPP, true,
      f("model.bin", FUTO + "voice-input-multilingual-244.bin", 264464624L, "15ef255465a6dc582ecf1ec651a4618c7ee2c18c05570bbe46493d248d465ac4")),

    // NVIDIA Parakeet, int8 export for sherpa-onnx
    new SttModel("parakeet_v3", "Parakeet TDT 0.6B v3", R.string.vg_SttLangParakeet, 4, ENGINE_SHERPA_NEMO_TDT, false,
      f("encoder.int8.onnx", PARAKEET + "encoder.int8.onnx", 652184281L, "acfc2b4456377e15d04f0243af540b7fe7c992f8d898d751cf134c3a55fd2247"),
      f("decoder.int8.onnx", PARAKEET + "decoder.int8.onnx", 11845275L, "179e50c43d1a9de79c8a24149a2f9bac6eb5981823f2a2ed88d655b24248db4e"),
      f("joiner.int8.onnx", PARAKEET + "joiner.int8.onnx", 6355277L, "3164c13fc2821009440d20fcb5fdc78bff28b4db2f8d0f0b329101719c0948b3"),
      f("tokens.txt", PARAKEET + "tokens.txt", 93939L, "d58544679ea4bc6ac563d1f545eb7d474bd6cfa467f0a6e2c1dc1c7d37e3c35d")),
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
  };

  public static final Credit[] ENGINE_CREDITS = {
    new Credit("whisper.cpp", "https://github.com/ggml-org/whisper.cpp"),
    new Credit("sherpa-onnx", "https://github.com/k2-fsa/sherpa-onnx"),
  };

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
