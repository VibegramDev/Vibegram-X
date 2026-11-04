/*
 * Vibegram X: model downloads, the selected model and the transcription queue.
 */
package org.thunderdog.challegram.stt;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

import androidx.annotation.Nullable;

import org.thunderdog.challegram.Log;
import org.thunderdog.challegram.tool.UI;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

public final class SttManager {
  public interface ModelListener {
    void onModelStateChanged (SttModel model);
  }

  public interface TranscriptionListener {
    void onTranscriptionStateChanged (String key);
  }

  public static final int STATE_NONE = 0;
  public static final int STATE_RUNNING = 1;
  public static final int STATE_DONE = 2;
  public static final int STATE_FAILED = 3;

  public static final class Transcription {
    public int state;
    public @Nullable String text;
    public @Nullable String error;
    public boolean expanded;
    /** 0..1 while running; -1 while waiting in the queue behind another message. */
    public volatile float progress;
    /**
     * Incremented by every start and every cancel. A run works for the generation it was started with:
     * once that changes, the run was cancelled and must not touch the visible state any more.
     * Neither engine can stop instantly (sherpa-onnx finishes the current chunk, whisper.cpp the current
     * graph computation), so the UI is reset right at the tap and the late result is thrown away.
     */
    volatile int generation;
    // Progress estimate between engine callbacks, from the speed of the audio processed so far.
    volatile long runStartTime;
    volatile float runStartProgress;

    /**
     * Progress 0..1 shown to the user; -1 while queued.
     * The engine reports real progress in steps (whisper.cpp: by decoded text position, Parakeet: by chunk),
     * between the steps it is estimated from time: by the speed measured in this run once there is some progress,
     * before that by the speed of previous messages on this device (or a default for the model).
     * Never goes back and stays below 99% until the engine is done.
     */
    public float getDisplayProgress () {
      float p = progress;
      if (p < 0 || runStartTime == 0) {
        return p;
      }
      long now = SystemClock.uptimeMillis();
      long elapsed = now - runStartTime;
      float expectedTotal = estimatedTotalMs;
      float done = p - runStartProgress;
      long reportedAt = lastProgressTime - runStartTime;
      if (done > 0.02f && reportedAt > 0) {
        // speed measured up to the moment of the last real progress report
        // (dividing the current time by the old progress would only repeat the old progress)
        expectedTotal = reportedAt / done;
      }
      float estimate = expectedTotal > 0 ? elapsed / expectedTotal : 0f;
      float result = Math.max(p, Math.min(estimate, .99f));
      if (result < shownProgress) {
        result = shownProgress;
      }
      shownProgress = result;
      return result;
    }

    private float shownProgress;
    /** Decoding the audio and loading the model, before the recognition itself. */
    public volatile boolean preparing;

    volatile long lastProgressTime;
    volatile long estimatedTotalMs;
  }

  private static SttManager instance;

  public static SttManager instance () {
    if (instance == null) {
      synchronized (SttManager.class) {
        if (instance == null) {
          instance = new SttManager(org.thunderdog.challegram.unsorted.AppContext.get());
        }
      }
    }
    return instance;
  }

  private final Context context;
  private final SharedPreferences prefs;
  private final Handler ui = new Handler(Looper.getMainLooper());
  private final ExecutorService downloadExecutor = Executors.newFixedThreadPool(2);
  private final ExecutorService recognitionExecutor = Executors.newSingleThreadExecutor();

  private final Map<String, Float> downloadProgress = new HashMap<>();
  private final Map<String, Future<?>> downloads = new HashMap<>();
  private final List<ModelListener> modelListeners = new ArrayList<>();
  private final List<TranscriptionListener> transcriptionListeners = new ArrayList<>();
  private final Map<String, Transcription> transcriptions = new HashMap<>();

  private @Nullable SttEngine engine;
  /** Bumped after every recognition run; an unload scheduled after an older run is skipped. Recognition thread only. */
  private long engineUseCount;
  /** The model is unloaded after this much idle time: Parakeet takes over 2 GB of RAM while loaded. */
  private static final long ENGINE_IDLE_UNLOAD_MS = 60_000;
  private @Nullable String engineModelId;
  private volatile @Nullable String runningKey;

  private SttManager (Context context) {
    this.context = context;
    this.prefs = context.getSharedPreferences("vg_stt", Context.MODE_PRIVATE);
    if (prefs.getString("model", null) != null && getSelectedModel() == null) {
      prefs.edit().remove("model").apply();
    }
    downloadExecutor.submit(this::deleteRemovedModels);
  }

  /** Frees the space taken by models that are no longer in the catalog. */
  private void deleteRemovedModels () {
    File[] dirs = getModelsDir().listFiles();
    if (dirs == null) {
      return;
    }
    for (File dir : dirs) {
      if (dir.isDirectory() && !dir.getName().equals("transcripts") && SttModel.find(dir.getName()) == null) {
        Log.i("STT: deleting removed model %s", dir.getName());
        deleteRecursive(dir);
      }
    }
    // Transcriptions are no longer saved: remove the files saved by older builds.
    deleteRecursive(new File(getModelsDir(), "transcripts"));
  }

  // Storage

  public File getModelsDir () {
    return new File(context.getFilesDir(), "stt");
  }

  public File getModelDir (SttModel model) {
    return new File(getModelsDir(), model.id);
  }

  public boolean isDownloaded (SttModel model) {
    File dir = getModelDir(model);
    for (SttModel.FileSpec file : model.files) {
      File f = new File(dir, file.name);
      if (!f.exists() || f.length() != file.size) {
        return false;
      }
    }
    return true;
  }

  public boolean isDownloading (SttModel model) {
    return downloads.containsKey(model.id);
  }

  public float getDownloadProgress (SttModel model) {
    Float progress = downloadProgress.get(model.id);
    return progress != null ? progress : 0f;
  }

  // Selection

  public @Nullable SttModel getSelectedModel () {
    return SttModel.find(prefs.getString("model", null));
  }

  public void setSelectedModel (@Nullable SttModel model) {
    SttModel old = getSelectedModel();
    prefs.edit().putString("model", model != null ? model.id : null).apply();
    if (old != null) {
      notifyModel(old);
    }
    if (model != null) {
      notifyModel(model);
    }
    forgetFinishedTranscriptions();
    // Transcriptions are kept per model: let visible messages switch to the new model's state.
    notifyTranscription(null);
  }

  /** Identifies how a message is recognized; results and the measured speed are kept per run id. */
  private String runId (SttModel model) {
    return model.id;
  }

  // Listeners

  public void addModelListener (ModelListener listener) {
    modelListeners.add(listener);
  }

  public void removeModelListener (ModelListener listener) {
    modelListeners.remove(listener);
  }

  public void addTranscriptionListener (TranscriptionListener listener) {
    if (!transcriptionListeners.contains(listener)) {
      transcriptionListeners.add(listener);
    }
  }

  public void removeTranscriptionListener (TranscriptionListener listener) {
    transcriptionListeners.remove(listener);
  }

  private void notifyModel (SttModel model) {
    UI.post(() -> {
      for (ModelListener listener : new ArrayList<>(modelListeners)) {
        listener.onModelStateChanged(model);
      }
    });
  }

  private void notifyTranscription (@Nullable String key) {
    UI.post(() -> {
      for (TranscriptionListener listener : new ArrayList<>(transcriptionListeners)) {
        listener.onTranscriptionStateChanged(key);
      }
    });
  }

  // Downloads

  public void download (SttModel model) {
    if (downloads.containsKey(model.id) || isDownloaded(model)) {
      return;
    }
    downloadProgress.put(model.id, 0f);
    Future<?> future = downloadExecutor.submit(() -> {
      boolean ok = false;
      long missingSpace = 0;
      try {
        downloadFiles(model);
        ok = true;
      } catch (NoSpaceException e) {
        missingSpace = e.missingBytes;
        Log.w("STT: no space for %s, %d bytes more needed", model.id, missingSpace);
      } catch (Throwable t) {
        Log.w("STT: download of %s failed", t, model.id);
      }
      final boolean success = ok;
      final long missing = missingSpace;
      final boolean interrupted = Thread.currentThread().isInterrupted();
      ui.post(() -> {
        boolean wasActive = downloads.remove(model.id) != null;
        downloadProgress.remove(model.id);
        if (success && getSelectedModel() == null) {
          setSelectedModel(model);
        }
        if (!success && !interrupted && wasActive) {
          if (missing > 0) {
            UI.showToast(org.thunderdog.challegram.core.Lang.getString(org.thunderdog.challegram.R.string.vg_SttNoSpace, sizeText(missing)), android.widget.Toast.LENGTH_LONG);
          } else {
            UI.showToast(org.thunderdog.challegram.R.string.vg_SttDownloadFailed, android.widget.Toast.LENGTH_SHORT);
          }
        }
        notifyModel(model);
      });
    });
    downloads.put(model.id, future);
    notifyModel(model);
  }

  public void cancelDownload (SttModel model) {
    Future<?> future = downloads.remove(model.id);
    if (future != null) {
      future.cancel(true);
    }
    downloadProgress.remove(model.id);
    notifyModel(model);
  }

  public void delete (SttModel model) {
    cancelDownload(model);
    recognitionExecutor.submit(() -> {
      if (model.id.equals(engineModelId) && engine != null) {
        engine.release();
        engine = null;
        engineModelId = null;
      }
      deleteRecursive(getModelDir(model));
      notifyModel(model);
    });
    if (getSelectedModel() == model) {
      setSelectedModel(null);
    }
  }

  private static void deleteRecursive (File file) {
    File[] children = file.listFiles();
    if (children != null) {
      for (File child : children) {
        deleteRecursive(child);
      }
    }
    //noinspection ResultOfMethodCallIgnored
    file.delete();
  }

  /** Not enough free space for the files of a model. */
  private static class NoSpaceException extends IOException {
    final long missingBytes;

    NoSpaceException (long missingBytes) {
      super("No space left");
      this.missingBytes = missingBytes;
    }
  }

  /** Kept free on the device after a download, so the phone does not run out of space because of a model. */
  private static final long SPACE_RESERVE = 300L * 1024 * 1024;

  private static String sizeText (long bytes) {
    long mb = Math.max(1, (bytes + 1024 * 1024 - 1) / (1024 * 1024));
    if (mb < 1000) {
      return org.thunderdog.challegram.core.Lang.getString(org.thunderdog.challegram.R.string.fileSize_MB, String.valueOf(mb));
    }
    return org.thunderdog.challegram.core.Lang.getString(org.thunderdog.challegram.R.string.fileSize_GB, org.thunderdog.challegram.core.Lang.formatNumber(Math.round(mb / 1024.0 * 10) / 10.0));
  }

  private void downloadFiles (SttModel model) throws IOException {
    File dir = getModelDir(model);
    final long total = model.totalSize();
    long done = 0;
    // check the space before writing gigabytes: a full disk breaks the app and the whole phone, not only the download
    long needed = 0;
    for (SttModel.FileSpec spec : model.files) {
      File target = new File(dir, spec.name);
      if (target.exists() && target.length() == spec.size) {
        continue;
      }
      File part = new File(target.getPath() + ".part");
      needed += spec.size - (part.exists() && part.length() <= spec.size ? part.length() : 0);
    }
    File existing = dir.exists() ? dir : getModelsDir();
    if (!existing.exists()) {
      //noinspection ResultOfMethodCallIgnored
      existing.mkdirs();
    }
    long usable = existing.getUsableSpace();
    if (needed > 0 && usable < needed + SPACE_RESERVE) {
      throw new NoSpaceException(needed + SPACE_RESERVE - usable);
    }
    for (SttModel.FileSpec spec : model.files) {
      File target = new File(dir, spec.name);
      if (target.exists() && target.length() == spec.size) {
        done += spec.size;
        continue;
      }
      File parent = target.getParentFile();
      if (parent != null && !parent.exists() && !parent.mkdirs()) {
        throw new IOException("mkdirs");
      }
      File part = new File(target.getPath() + ".part");
      long have = part.exists() ? part.length() : 0;
      if (have > spec.size) {
        //noinspection ResultOfMethodCallIgnored
        part.delete();
        have = 0;
      }
      if (have < spec.size) {
        HttpURLConnection connection = (HttpURLConnection) new URL(spec.url).openConnection();
        connection.setInstanceFollowRedirects(true);
        connection.setConnectTimeout(20000);
        connection.setReadTimeout(30000);
        connection.setRequestProperty("User-Agent", "Vibegram-X");
        if (have > 0) {
          connection.setRequestProperty("Range", "bytes=" + have + "-");
        }
        int code = connection.getResponseCode();
        if (code == HttpURLConnection.HTTP_OK) {
          have = 0;
        } else if (code != HttpURLConnection.HTTP_PARTIAL) {
          throw new IOException("HTTP " + code + " for " + spec.url);
        }
        try (InputStream in = connection.getInputStream(); OutputStream out = new FileOutputStream(part, have > 0)) {
          byte[] buffer = new byte[128 * 1024];
          int read;
          long lastNotify = 0;
          while ((read = in.read(buffer)) != -1) {
            if (Thread.currentThread().isInterrupted()) {
              throw new IOException("Cancelled");
            }
            out.write(buffer, 0, read);
            have += read;
            long now = System.currentTimeMillis();
            if (now - lastNotify > 300) {
              lastNotify = now;
              final float progress = (float) ((done + have) / (double) total);
              ui.post(() -> {
                if (downloads.containsKey(model.id)) {
                  downloadProgress.put(model.id, progress);
                  notifyModel(model);
                }
              });
            }
          }
        } finally {
          connection.disconnect();
        }
      }
      if (part.length() != spec.size) {
        throw new IOException("Size mismatch for " + spec.name);
      }
      if (spec.sha256 != null && !spec.sha256.equalsIgnoreCase(sha256(part))) {
        //noinspection ResultOfMethodCallIgnored
        part.delete();
        throw new IOException("Checksum mismatch for " + spec.name);
      }
      if (!part.renameTo(target)) {
        throw new IOException("rename");
      }
      done += spec.size;
    }
  }

  private static String sha256 (File file) throws IOException {
    try (InputStream in = new FileInputStream(file)) {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      byte[] buffer = new byte[256 * 1024];
      int read;
      while ((read = in.read(buffer)) != -1) {
        digest.update(buffer, 0, read);
      }
      StringBuilder b = new StringBuilder();
      for (byte x : digest.digest()) {
        b.append(String.format("%02x", x));
      }
      return b.toString();
    } catch (java.security.NoSuchAlgorithmException e) {
      throw new IOException(e);
    }
  }

  // Transcription

  public static String key (int fileId, @Nullable String uniqueId) {
    return uniqueId != null && !uniqueId.isEmpty() ? uniqueId : "file" + fileId;
  }

  public Transcription getTranscription (String key) {
    SttModel model = getSelectedModel();
    return getTranscription(key, model != null ? runId(model) : "none");
  }

  /** Results live only in memory, so a message can be recognized again and compared between models. */
  private Transcription getTranscription (String key, String runId) {
    String fullKey = key + "@" + runId;
    Transcription t = transcriptions.get(fullKey);
    if (t == null) {
      t = new Transcription();
      transcriptions.put(fullKey, t);
    }
    return t;
  }

  /** On a model or mode switch, finished results are dropped: going back to a model recognizes the message again. */
  private void forgetFinishedTranscriptions () {
    Iterator<Transcription> it = transcriptions.values().iterator();
    while (it.hasNext()) {
      if (it.next().state != STATE_RUNNING) {
        it.remove();
      }
    }
  }

  public interface AudioPathProvider {
    /** Blocks until the file is available locally, returns its path or null. */
    @Nullable String awaitLocalPath () throws InterruptedException;
  }

  /**
   * @param onDone called on the UI thread when this run produced text
   * @return false when no model is downloaded and selected
   */
  public boolean transcribe (String key, AudioPathProvider audio, @Nullable Runnable onDone) {
    SttModel model = getSelectedModel();
    if (model == null || !isDownloaded(model)) {
      return false;
    }
    final String runId = runId(model);
    final Transcription t = getTranscription(key, runId);
    if (t.state == STATE_RUNNING) {
      return true;
    }
    t.state = STATE_RUNNING;
    t.error = null;
    t.text = null;
    t.expanded = true;
    t.progress = -1f;
    t.runStartTime = 0;
    final int generation = ++t.generation;
    notifyTranscription(key);
    recognitionExecutor.submit(() -> {
      if (t.generation != generation) {
        return;
      }
      t.progress = 0f;
      t.shownProgress = 0f;
      t.preparing = true;
      t.lastProgressTime = t.runStartTime = SystemClock.uptimeMillis();
      t.runStartProgress = 0f;
      runningKey = key;
      notifyTranscription(key);
      try {
        String path = audio.awaitLocalPath();
        if (path == null) {
          throw new IOException("File is not available");
        }
        long startTime = SystemClock.uptimeMillis();
        float[] samples = AudioDecoder.decode(path, null);
        long decodeTime = SystemClock.uptimeMillis();
        SttEngine engine = obtainEngine(model);
        engine.resetCancel();
        if (t.generation != generation) {
          throw new IOException("Cancelled");
        }
        // decoding and model loading are done: measure the speed of recognition only
        t.lastProgressTime = t.runStartTime = SystemClock.uptimeMillis();
        t.preparing = false;
        // until the first chunk is done, estimate the progress from the speed of previous messages
        float knownSpeed = prefs.getFloat("speed_" + model.id, defaultSpeed(model));
        t.estimatedTotalMs = knownSpeed > 0 ? (long) (knownSpeed * samples.length / (float) AudioDecoder.SAMPLE_RATE) : 0;
        final long[] lastNotify = {0};
        String text = engine.transcribe(samples, new SttEngine.Listener() {
          @Override
          public void onProgress (float progress) {
            if (t.generation != generation) {
              return;
            }
            if (progress > t.progress) {
              t.progress = progress;
              t.lastProgressTime = SystemClock.uptimeMillis();
            }
            long now = System.currentTimeMillis();
            if (now - lastNotify[0] >= 250) {
              lastNotify[0] = now;
              notifyTranscription(key);
            }
          }

          @Override
          public void onPartialText (String partial) {
            if (t.generation != generation) {
              return;
            }
            t.text = partial;
            notifyTranscription(key);
          }
        }).replaceAll("\\s+", " ").trim();
        long end = SystemClock.uptimeMillis();
        float audioSeconds = samples.length / (float) AudioDecoder.SAMPLE_RATE;
        long recognitionMs = end - t.runStartTime;
        android.util.Log.i("VgStt", String.format(java.util.Locale.US, "%s: %.1fs audio, decode %dms, load %dms, recognition %dms", model.id, audioSeconds, decodeTime - startTime, t.runStartTime - decodeTime, recognitionMs));
        if (audioSeconds >= 3f) {
          rememberSpeed("speed_" + model.id, recognitionMs / audioSeconds);
        }
        if (t.generation != generation) {
          // cancelled while the engine was finishing its last chunk
          throw new IOException("Cancelled");
        }
        t.text = text;
        t.state = STATE_DONE;
        if (onDone != null && !text.isEmpty()) {
          UI.post(onDone);
        }
      } catch (Throwable e) {
        if (t.generation != generation) {
          // the UI was already reset by cancelTranscription()
          Log.i("STT: transcription cancelled");
        } else {
          Log.w("STT: transcription failed", e);
          t.text = null;
          t.state = STATE_FAILED;
          t.error = e.getMessage();
        }
      }
      runningKey = null;
      if (t.generation == generation) {
        t.preparing = false;
        notifyTranscription(key);
      }
      scheduleEngineUnload();
    });
    return true;
  }

  private void rememberSpeed (String prefKey, float speed) {
    float old = prefs.getFloat(prefKey, 0f);
    prefs.edit().putFloat(prefKey, old > 0 ? old * .5f + speed * .5f : speed).apply();
  }

  /**
   * Unloads the model after a minute without recognition. Runs queue up on the single recognition thread,
   * so a run queued before the unload task finishes first and makes it obsolete.
   */
  private void scheduleEngineUnload () {
    final long useCount = ++engineUseCount;
    UI.post(() -> recognitionExecutor.submit(() -> {
      if (engineUseCount != useCount) {
        return;
      }
      if (engine != null) {
        android.util.Log.i("VgStt", "unloading " + engineModelId + " after " + ENGINE_IDLE_UNLOAD_MS / 1000 + "s idle");
        engine.release();
        engine = null;
        engineModelId = null;
      }
    }), ENGINE_IDLE_UNLOAD_MS);
  }

  /**
   * Milliseconds of recognition per second of audio on a mid-range phone, used for the progress estimate
   * until this device has transcribed a message with the model (then the measured speed is used).
   */
  private static float defaultSpeed (SttModel model) {
    switch (model.id) {
      case "futo_74": return 120f;
      case "futo_244": return 400f;
      case "parakeet_ultra": return 150f;
      case "gigaam_v3_e2e": return 60f;
    }
    return 500f;
  }

  /** Stops a running or queued transcription. */
  public void cancelTranscription (String key) {
    Transcription t = getTranscription(key);
    if (t.state != STATE_RUNNING) {
      return;
    }
    t.generation++;
    if (key.equals(runningKey)) {
      SttEngine engine = this.engine;
      if (engine != null) {
        engine.cancel();
      }
    }
    // Reset right away, whether the run is queued, preparing or recognizing: the engine stops on its own time.
    t.state = STATE_NONE;
    t.expanded = false;
    t.text = null;
    t.preparing = false;
    t.progress = -1f;
    notifyTranscription(key);
  }

  public void setExpanded (String key, boolean expanded) {
    Transcription t = getTranscription(key);
    if (t.expanded != expanded) {
      t.expanded = expanded;
      notifyTranscription(key);
    }
  }

  private SttEngine obtainEngine (SttModel model) throws IOException {
    if (engine != null && model.id.equals(engineModelId)) {
      return engine;
    }
    if (engine != null) {
      engine.release();
      engine = null;
      engineModelId = null;
    }
    long start = System.currentTimeMillis();
    engine = SttEngine.create(model, getModelDir(model));
    engineModelId = model.id;
    Log.i("STT: loaded %s in %dms", model.id, System.currentTimeMillis() - start);
    return engine;
  }
}
