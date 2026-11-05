/*
 * Vibegram X: model downloads, the selected model and the transcription queue.
 */
package org.thunderdog.challegram.stt;

import android.content.ComponentCallbacks2;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.os.Build;
import android.os.SystemClock;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.drinkless.tdlib.TdApi;
import org.thunderdog.challegram.Log;
import org.thunderdog.challegram.R;
import org.thunderdog.challegram.core.Lang;
import org.thunderdog.challegram.telegram.Tdlib;
import org.thunderdog.challegram.tool.Strings;
import org.thunderdog.challegram.tool.UI;
import org.thunderdog.challegram.unsorted.AppContext;
import org.thunderdog.challegram.unsorted.Settings;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;

public final class SttManager {
  public interface ModelListener {
    void onModelStateChanged (SttModel model);

    /** Only the download percentage of the model changed. */
    default void onModelDownloadProgress (SttModel model) {
      onModelStateChanged(model);
    }
  }

  public interface TranscriptionListener {
    void onTranscriptionStateChanged (String key);
  }

  public static final int STATE_NONE = 0;
  public static final int STATE_RUNNING = 1;
  public static final int STATE_DONE = 2;
  public static final int STATE_FAILED = 3;

  /** Longest voice message or video note that is recognized: 16 kHz samples of 20 minutes take ~77 MB. */
  public static final int MAX_VOICE_SECONDS = 20 * 60;
  /** Longest video whose audio track is recognized. */
  public static final int MAX_VIDEO_SECONDS = 5 * 60;

  // ABI

  private static volatile Boolean supported;

  /**
   * whisper.cpp, sherpa-onnx and ONNX Runtime are shipped for arm64-v8a only: elsewhere the models
   * would download fine and then fail to load, so the whole feature is hidden.
   */
  public static boolean isSupported () {
    Boolean result = supported;
    if (result == null) {
      boolean ok = false;
      String[] abis = Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP ? Build.SUPPORTED_ABIS : new String[] {Build.CPU_ABI};
      for (String abi : abis) {
        if ("arm64-v8a".equals(abi)) {
          ok = true;
          break;
        }
      }
      // a 32-bit APK split on a 64-bit phone runs as a 32-bit process without the arm64 libraries
      String arch = System.getProperty("os.arch");
      ok = ok && arch != null && (arch.equals("aarch64") || arch.startsWith("arm64"));
      supported = result = ok;
    }
    return result;
  }

  public static final class Transcription {
    final String key;
    public volatile int state;
    public volatile @Nullable String text;
    public volatile @Nullable String error;
    public boolean expanded;
    /** 0..1 while running; -1 while waiting in the queue behind another message (or for the file). */
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
    /** File being downloaded for this run (UI thread), 0 when none. Cancelled together with the run. */
    int downloadingFileId;
    @Nullable Tdlib downloadingTdlib;
    boolean cancelDownloadWithRun;

    // Change notifications from the recognition thread are coalesced: at most one per NOTIFY_INTERVAL_MS.
    final AtomicBoolean notifyPending = new AtomicBoolean();
    final Runnable notifyRunnable;

    Transcription (String key) {
      this.key = key;
      this.notifyRunnable = () -> {
        notifyPending.set(false);
        SttManager.instance().notifyTranscriptionNow(key);
      };
    }

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

  private static volatile SttManager instance;

  public static SttManager instance () {
    if (instance == null) {
      synchronized (SttManager.class) {
        if (instance == null) {
          instance = new SttManager(AppContext.get());
        }
      }
    }
    return instance;
  }

  private static final long NOTIFY_INTERVAL_MS = 250;
  /** Finished results kept in memory; older ones are recognized again when opened. */
  private static final int MAX_TRANSCRIPTIONS = 200;

  private final Context context;
  private final ExecutorService downloadExecutor = Executors.newFixedThreadPool(2);
  /**
   * One recognition thread. Tasks wait in {@link #recognitionQueue}: dictation goes to its head, so it never waits
   * behind queued messages (only behind the one already running). Files are downloaded before a task is queued.
   */
  private final ExecutorService recognitionExecutor = Executors.newSingleThreadExecutor();
  private final ArrayDeque<Runnable> recognitionQueue = new ArrayDeque<>();

  // UI thread
  private final Map<String, Float> downloadProgress = new HashMap<>();
  private final Map<String, DownloadTask> downloads = new HashMap<>();
  /** Cancelled downloads whose thread has not finished yet: a new download of the model waits for them. */
  private final Map<String, DownloadTask> stoppingDownloads = new HashMap<>();
  private final Set<String> restartAfterStop = new HashSet<>();
  private final List<ModelListener> modelListeners = new ArrayList<>();
  private final List<TranscriptionListener> transcriptionListeners = new ArrayList<>();
  private final LinkedHashMap<String, Transcription> transcriptions = new LinkedHashMap<String, Transcription>(64, .75f, true) {
    @Override
    protected boolean removeEldestEntry (Map.Entry<String, Transcription> eldest) {
      return size() > MAX_TRANSCRIPTIONS && eldest.getValue().state != STATE_RUNNING;
    }
  };

  /** Model files present on disk, by model id: computed once, updated by downloads and deletions. */
  private final Map<String, Boolean> downloadedCache = new ConcurrentHashMap<>();
  private final Map<String, Boolean> partialCache = new ConcurrentHashMap<>();

  private volatile @Nullable SttEngine engine;
  /** Bumped after every recognition run; an unload scheduled after an older run is skipped. Recognition thread only. */
  private long engineUseCount;
  /** The model is unloaded after this much idle time: Parakeet takes over 2 GB of RAM while loaded. */
  private static final long ENGINE_IDLE_UNLOAD_MS = 60_000;
  private @Nullable String engineModelId;
  private volatile @Nullable String runningKey;
  private volatile boolean recognitionBusy;
  private final Object engineLock = new Object();

  private SttManager (Context context) {
    this.context = context;
    migrateSettings(context);
    String selectedId = Settings.instance().getSttModelId();
    selectedModel = SttModel.find(selectedId);
    if (selectedId != null && selectedModel == null) {
      Settings.instance().setSttModelId(null);
    }
    downloadExecutor.submit(this::deleteRemovedModels);
    context.registerComponentCallbacks(new ComponentCallbacks2() {
      @Override
      public void onTrimMemory (int level) {
        if (level >= ComponentCallbacks2.TRIM_MEMORY_BACKGROUND) {
          unloadEngineIfIdle("trim memory " + level);
        }
      }

      @Override
      public void onConfigurationChanged (@NonNull Configuration newConfig) { }

      @Override
      public void onLowMemory () {
        unloadEngineIfIdle("low memory");
      }
    });
  }

  /** The selected model and the measured speeds used to live in their own SharedPreferences. */
  private static void migrateSettings (Context context) {
    Settings settings = Settings.instance();
    if (!settings.needSttMigration()) {
      return;
    }
    try {
      SharedPreferences old = context.getSharedPreferences("vg_stt", Context.MODE_PRIVATE);
      Map<String, ?> all = old.getAll();
      if (!all.isEmpty()) {
        for (Map.Entry<String, ?> entry : all.entrySet()) {
          String key = entry.getKey();
          Object value = entry.getValue();
          if ("model".equals(key) && value instanceof String) {
            settings.setSttModelId((String) value);
          } else if (key.startsWith("speed_") && value instanceof Float) {
            settings.setSttSpeed(key.substring("speed_".length()), (Float) value);
          }
        }
        old.edit().clear().apply();
      }
    } catch (Throwable t) {
      Log.w("STT: settings migration failed", t);
    }
    settings.markSttMigrated();
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

  /** All files of the model are on disk. Cached: safe to call while drawing. */
  public boolean isDownloaded (SttModel model) {
    Boolean cached = downloadedCache.get(model.id);
    if (cached == null) {
      cached = checkDownloaded(model);
      downloadedCache.put(model.id, cached);
    }
    return cached;
  }

  private boolean checkDownloaded (SttModel model) {
    File dir = getModelDir(model);
    for (SttModel.FileSpec file : model.files) {
      File f = new File(dir, file.name);
      if (!f.exists() || f.length() != file.size) {
        return false;
      }
    }
    return true;
  }

  /** A stopped download left files of the model (finished files or .part) that take space. Cached. */
  public boolean hasPartialDownload (SttModel model) {
    if (isDownloaded(model)) {
      return false;
    }
    Boolean cached = partialCache.get(model.id);
    if (cached == null) {
      String[] names = getModelDir(model).list();
      cached = names != null && names.length > 0;
      partialCache.put(model.id, cached);
    }
    return cached;
  }

  private void invalidateFileState (SttModel model) {
    downloadedCache.remove(model.id);
    partialCache.remove(model.id);
  }

  public boolean isDownloading (SttModel model) {
    return downloads.containsKey(model.id) || restartAfterStop.contains(model.id);
  }

  public float getDownloadProgress (SttModel model) {
    Float progress = downloadProgress.get(model.id);
    return progress != null ? progress : 0f;
  }

  // Selection

  /** Cached copy of the setting: read for every visible message. */
  private volatile @Nullable SttModel selectedModel;

  public @Nullable SttModel getSelectedModel () {
    return selectedModel;
  }

  public void setSelectedModel (@Nullable SttModel model) {
    SttModel old = getSelectedModel();
    selectedModel = model;
    Settings.instance().setSttModelId(model != null ? model.id : null);
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
      for (int i = modelListeners.size() - 1; i >= 0; i--) {
        if (i < modelListeners.size()) {
          modelListeners.get(i).onModelStateChanged(model);
        }
      }
    });
  }

  private void notifyTranscription (@Nullable String key) {
    UI.post(() -> notifyTranscriptionNow(key));
  }

  /** UI thread. */
  private void notifyTranscriptionNow (@Nullable String key) {
    for (int i = transcriptionListeners.size() - 1; i >= 0; i--) {
      if (i < transcriptionListeners.size()) {
        transcriptionListeners.get(i).onTranscriptionStateChanged(key);
      }
    }
  }

  /** Any thread: at most one change notification per {@link #NOTIFY_INTERVAL_MS} for the transcription. */
  private static void notifyThrottled (Transcription t) {
    if (t.notifyPending.compareAndSet(false, true)) {
      UI.post(t.notifyRunnable, NOTIFY_INTERVAL_MS);
    }
  }

  // Downloads

  private static final class DownloadTask {
    final SttModel model;
    volatile boolean cancelled;
    Future<?> future;

    DownloadTask (SttModel model) {
      this.model = model;
    }
  }

  public void download (SttModel model) {
    if (downloads.containsKey(model.id) || isDownloaded(model)) {
      return;
    }
    if (stoppingDownloads.containsKey(model.id) || deletingModels.contains(model.id) || pendingDeletes.contains(model.id)) {
      // the cancelled download still writes its .part file, or the files are being deleted: start again after that
      restartAfterStop.add(model.id);
      downloadProgress.put(model.id, 0f);
      notifyModel(model);
      return;
    }
    downloadProgress.put(model.id, 0f);
    final DownloadTask task = new DownloadTask(model);
    task.future = downloadExecutor.submit(() -> {
      boolean ok = false;
      long missingSpace = 0;
      try {
        if (task.cancelled) {
          // cancelled while waiting for a thread: nothing was written
          throw new IOException("Cancelled");
        }
        downloadFiles(task);
        ok = true;
      } catch (NoSpaceException e) {
        missingSpace = e.missingBytes;
        Log.w("STT: no space for %s, %d bytes more needed", model.id, missingSpace);
      } catch (Throwable t) {
        Log.w("STT: download of %s failed", t, model.id);
      }
      final boolean success = ok;
      final long missing = missingSpace;
      UI.post(() -> onDownloadFinished(task, success, missing));
    });
    downloads.put(model.id, task);
    notifyModel(model);
  }

  private void onDownloadFinished (DownloadTask task, boolean success, long missingSpace) {
    final SttModel model = task.model;
    invalidateFileState(model);
    boolean wasActive = downloads.remove(model.id, task);
    stoppingDownloads.remove(model.id, task);
    if (wasActive) {
      downloadProgress.remove(model.id);
    }
    if (success && getSelectedModel() == null) {
      setSelectedModel(model);
    }
    if (!success && wasActive && !task.cancelled) {
      if (missingSpace > 0) {
        UI.showToast(Lang.getString(R.string.vg_SttNoSpace, Strings.buildSize(missingSpace)), android.widget.Toast.LENGTH_LONG);
      } else {
        UI.showToast(R.string.vg_SttDownloadFailed, android.widget.Toast.LENGTH_SHORT);
      }
    }
    if (!stoppingDownloads.containsKey(model.id)) {
      if (pendingDeletes.contains(model.id)) {
        deleteFilesWhenStopped(model);
      } else if (restartAfterStop.remove(model.id)) {
        downloadProgress.remove(model.id);
        download(model);
      }
    }
    notifyModel(model);
  }

  public void cancelDownload (SttModel model) {
    restartAfterStop.remove(model.id);
    DownloadTask task = downloads.remove(model.id);
    if (task != null) {
      // Not Future.cancel(): a task cancelled before it starts would never report back, and the model
      // would stay "stopping" forever. The task checks the flag between buffers and finishes on its own.
      task.cancelled = true;
      stoppingDownloads.put(model.id, task);
    }
    downloadProgress.remove(model.id);
    partialCache.remove(model.id);
    notifyModel(model);
  }

  /** Deletes the model, or the files left by a stopped download. */
  public void delete (SttModel model) {
    cancelDownload(model);
    downloadedCache.put(model.id, false);
    partialCache.put(model.id, false);
    if (getSelectedModel() == model) {
      setSelectedModel(null);
    }
    // a download requested before the files are gone waits for the deletion
    pendingDeletes.add(model.id);
    submitRecognitionTask(false, () -> {
      synchronized (engineLock) {
        if (model.id.equals(engineModelId) && engine != null) {
          engine.release();
          engine = null;
          engineModelId = null;
        }
      }
      UI.post(() -> deleteFilesWhenStopped(model));
    });
  }

  /** Model ids whose files are deleted once their cancelled download has stopped writing. UI thread. */
  private final Set<String> pendingDeletes = new HashSet<>();

  /** UI thread. A cancelled download still writing its .part would leave it behind: wait for it to stop. */
  private void deleteFilesWhenStopped (SttModel model) {
    if (!pendingDeletes.contains(model.id) || stoppingDownloads.containsKey(model.id)) {
      // already deleted, or onDownloadFinished() calls this again once the download has stopped
      return;
    }
    pendingDeletes.remove(model.id);
    // a download requested meanwhile waits for the deletion (see download())
    deletingModels.add(model.id);
    downloadExecutor.submit(() -> {
      deleteRecursive(getModelDir(model));
      UI.post(() -> {
        deletingModels.remove(model.id);
        invalidateFileState(model);
        if (restartAfterStop.remove(model.id)) {
          downloadProgress.remove(model.id);
          download(model);
        }
        notifyModel(model);
      });
    });
  }

  /** Model ids whose directory is being deleted. UI thread. */
  private final Set<String> deletingModels = new HashSet<>();

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

  private void downloadFiles (DownloadTask task) throws IOException {
    final SttModel model = task.model;
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
      if (task.cancelled) {
        throw new IOException("Cancelled");
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
            if (task.cancelled) {
              throw new IOException("Cancelled");
            }
            out.write(buffer, 0, read);
            have += read;
            long now = SystemClock.uptimeMillis();
            if (now - lastNotify > 300) {
              lastNotify = now;
              final float progress = (float) ((done + have) / (double) total);
              UI.post(() -> {
                if (downloads.get(model.id) == task) {
                  downloadProgress.put(model.id, progress);
                  for (int i = modelListeners.size() - 1; i >= 0; i--) {
                    if (i < modelListeners.size()) {
                      modelListeners.get(i).onModelDownloadProgress(model);
                    }
                  }
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
      if (task.cancelled) {
        throw new IOException("Cancelled");
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

  // Recognition queue

  private void submitRecognitionTask (boolean urgent, Runnable task) {
    synchronized (recognitionQueue) {
      if (urgent) {
        recognitionQueue.addFirst(task);
      } else {
        recognitionQueue.addLast(task);
      }
    }
    // one drain per task: each runs whatever is at the head of the queue at that moment
    recognitionExecutor.execute(this::runNextRecognitionTask);
  }

  private void runNextRecognitionTask () {
    Runnable task;
    synchronized (recognitionQueue) {
      task = recognitionQueue.pollFirst();
    }
    if (task != null) {
      recognitionBusy = true;
      try {
        task.run();
      } catch (Throwable t) {
        Log.e("STT: recognition task failed", t);
      } finally {
        recognitionBusy = false;
      }
    }
  }

  // Transcription

  public static String key (int fileId, @Nullable String uniqueId) {
    return uniqueId != null && !uniqueId.isEmpty() ? uniqueId : "file" + fileId;
  }

  private static String fullKey (String key, SttModel model) {
    return key + "@" + (model != null ? model.id : "none");
  }

  /** The transcription of the message with the selected model, or null when it was never started. Does not create one. */
  public @Nullable Transcription peekTranscription (String key) {
    return transcriptions.get(fullKey(key, getSelectedModel()));
  }

  /** Results live only in memory, so a message can be recognized again and compared between models. */
  private Transcription obtainTranscription (String key, @Nullable SttModel model) {
    String fullKey = fullKey(key, model);
    Transcription t = transcriptions.get(fullKey);
    if (t == null) {
      t = new Transcription(key);
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

  /**
   * Recognizes the audio of a file. UI thread. The file is downloaded first (asynchronously, without holding
   * the recognition thread); the recognition is queued once its path is known.
   *
   * @param maxSeconds longer audio fails instead of taking all the memory
   * @param onDone called on the UI thread when this run produced text
   * @return false when no model is downloaded and selected
   */
  public boolean transcribe (Tdlib tdlib, TdApi.File file, String key, int maxSeconds, @Nullable Runnable onDone) {
    final SttModel model = getSelectedModel();
    if (model == null || !isDownloaded(model)) {
      return false;
    }
    final Transcription t = obtainTranscription(key, model);
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
    if (file.local != null && file.local.isDownloadingCompleted) {
      // TDLib checks that the file is still there and answers right away
      t.cancelDownloadWithRun = false;
    } else {
      t.cancelDownloadWithRun = file.local == null || !file.local.isDownloadingActive;
    }
    t.downloadingFileId = file.id;
    t.downloadingTdlib = tdlib;
    tdlib.send(new TdApi.DownloadFile(file.id, 32, 0, 0, true), (result, error) -> UI.post(() -> {
      if (t.generation != generation) {
        return;
      }
      t.downloadingFileId = 0;
      t.downloadingTdlib = null;
      String path = result != null && result.local != null && result.local.isDownloadingCompleted ? result.local.path : null;
      if (path == null) {
        fail(t, key, error != null ? error.message : "File is not available");
        return;
      }
      submitRecognitionTask(false, () -> runTranscription(t, key, generation, model, path, maxSeconds, onDone));
    }));
    return true;
  }

  /** UI thread. */
  private void fail (Transcription t, String key, @Nullable String error) {
    Log.w("STT: transcription failed: %s", error);
    t.text = null;
    t.state = STATE_FAILED;
    t.error = error;
    t.preparing = false;
    notifyTranscriptionNow(key);
  }

  /** Recognition thread. */
  private void runTranscription (Transcription t, String key, int generation, SttModel model, String path, int maxSeconds, @Nullable Runnable onDone) {
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
      long startTime = SystemClock.uptimeMillis();
      float[] samples = AudioDecoder.decode(path, maxSeconds, () -> t.generation != generation);
      long decodeTime = SystemClock.uptimeMillis();
      if (t.generation != generation) {
        throw new IOException("Cancelled");
      }
      SttEngine engine = obtainEngine(model);
      engine.resetCancel();
      if (t.generation != generation) {
        throw new IOException("Cancelled");
      }
      // decoding and model loading are done: measure the speed of recognition only
      t.lastProgressTime = t.runStartTime = SystemClock.uptimeMillis();
      t.preparing = false;
      // until the first chunk is done, estimate the progress from the speed of previous messages
      float knownSpeed = Settings.instance().getSttSpeed(model.id);
      if (knownSpeed <= 0) {
        knownSpeed = defaultSpeed(model);
      }
      t.estimatedTotalMs = (long) (knownSpeed * samples.length / (float) AudioDecoder.SAMPLE_RATE);
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
          notifyThrottled(t);
        }

        @Override
        public void onPartialText (String partial) {
          if (t.generation != generation || partial.equals(t.text)) {
            return;
          }
          t.text = partial;
          notifyThrottled(t);
        }
      }).replaceAll("\\s+", " ").trim();
      long end = SystemClock.uptimeMillis();
      float audioSeconds = samples.length / (float) AudioDecoder.SAMPLE_RATE;
      long recognitionMs = end - t.runStartTime;
      Log.i(Log.TAG_VOICE, "STT: %s: %.1fs audio, decode %dms, load %dms, recognition %dms", model.id, audioSeconds, decodeTime - startTime, t.runStartTime - decodeTime, recognitionMs);
      if (audioSeconds >= 3f) {
        rememberSpeed(model.id, recognitionMs / audioSeconds);
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
  }

  // Dictation

  public interface DictationCallback {
    /** UI thread. @param text recognized text, empty when no speech was found, null on failure */
    void onDictationResult (@Nullable String text);
  }

  /** True when dictation can run: a model is selected and downloaded. */
  public boolean canDictate () {
    if (!isSupported()) {
      return false;
    }
    SttModel model = getSelectedModel();
    return model != null && isDownloaded(model);
  }

  /** Loads the model while the user is still speaking, so recognition starts right after the recording. */
  public void prepareDictation () {
    final SttModel model = getSelectedModel();
    if (model == null || !isDownloaded(model)) {
      return;
    }
    submitRecognitionTask(true, () -> {
      try {
        obtainEngine(model);
      } catch (Throwable t) {
        Log.w("STT: dictation model load failed", t);
      }
      scheduleEngineUnload();
    });
  }

  /**
   * Recognizes dictated speech (16 kHz mono samples) with the selected model. Goes ahead of the queued
   * voice message transcriptions: the user is waiting for it in the composer.
   *
   * @return false when no model is downloaded and selected
   */
  public boolean transcribeSamples (final float[] samples, final DictationCallback callback) {
    final SttModel model = getSelectedModel();
    if (model == null || !isDownloaded(model)) {
      return false;
    }
    submitRecognitionTask(true, () -> {
      String result;
      try {
        long start = SystemClock.uptimeMillis();
        SttEngine engine = obtainEngine(model);
        engine.resetCancel();
        long loaded = SystemClock.uptimeMillis();
        result = engine.transcribe(samples, null).replaceAll("\\s+", " ").trim();
        Log.i(Log.TAG_VOICE, "STT: %s: dictation %.1fs audio, load %dms, recognition %dms", model.id, samples.length / (float) AudioDecoder.SAMPLE_RATE, loaded - start, SystemClock.uptimeMillis() - loaded);
      } catch (Throwable t) {
        Log.w("STT: dictation failed", t);
        result = null;
      }
      final String text = result;
      UI.post(() -> callback.onDictationResult(text));
      scheduleEngineUnload();
    });
    return true;
  }

  private static void rememberSpeed (String modelId, float speed) {
    float old = Settings.instance().getSttSpeed(modelId);
    Settings.instance().setSttSpeed(modelId, old > 0 ? old * .5f + speed * .5f : speed);
  }

  /**
   * Unloads the model after a minute without recognition. Runs queue up on the single recognition thread,
   * so a run queued before the unload task finishes first and makes it obsolete.
   */
  private void scheduleEngineUnload () {
    final long useCount = ++engineUseCount;
    UI.post(() -> submitRecognitionTask(false, () -> {
      if (engineUseCount == useCount) {
        releaseEngine("idle " + ENGINE_IDLE_UNLOAD_MS / 1000 + "s");
      }
    }), ENGINE_IDLE_UNLOAD_MS);
  }

  /** The app went to the background or the system is low on memory: free the model unless it is in use. */
  private void unloadEngineIfIdle (String reason) {
    if (engine == null || recognitionBusy) {
      return;
    }
    submitRecognitionTask(false, () -> {
      boolean queueEmpty;
      synchronized (recognitionQueue) {
        queueEmpty = recognitionQueue.isEmpty();
      }
      if (queueEmpty) {
        engineUseCount++;
        releaseEngine(reason);
      }
    });
  }

  /** Recognition thread. */
  private void releaseEngine (String reason) {
    synchronized (engineLock) {
      if (engine != null) {
        Log.i(Log.TAG_VOICE, "STT: unloading %s: %s", engineModelId, reason);
        engine.release();
        engine = null;
        engineModelId = null;
      }
    }
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

  /** Stops a running or queued transcription. UI thread. */
  public void cancelTranscription (String key) {
    Transcription t = peekTranscription(key);
    if (t == null || t.state != STATE_RUNNING) {
      return;
    }
    t.generation++;
    if (t.downloadingFileId != 0 && t.downloadingTdlib != null) {
      if (t.cancelDownloadWithRun) {
        // the download was started for the transcription only
        t.downloadingTdlib.send(new TdApi.CancelDownloadFile(t.downloadingFileId, false), t.downloadingTdlib.typedOkHandler());
      }
      t.downloadingFileId = 0;
      t.downloadingTdlib = null;
    }
    if (key.equals(runningKey)) {
      // engine.cancel() is safe against a concurrent release(): WhisperCpp guards its context with a lock
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

  /** UI thread. */
  public void setExpanded (String key, boolean expanded) {
    Transcription t = peekTranscription(key);
    if (t != null && t.expanded != expanded) {
      t.expanded = expanded;
      notifyTranscription(key);
    }
  }

  /** Recognition thread. */
  private SttEngine obtainEngine (SttModel model) throws IOException {
    synchronized (engineLock) {
      if (engine != null && model.id.equals(engineModelId)) {
        return engine;
      }
      if (engine != null) {
        engine.release();
        engine = null;
        engineModelId = null;
      }
    }
    long start = SystemClock.uptimeMillis();
    SttEngine created = SttEngine.create(model, getModelDir(model));
    synchronized (engineLock) {
      engine = created;
      engineModelId = model.id;
    }
    Log.i("STT: loaded %s in %dms", model.id, SystemClock.uptimeMillis() - start);
    return created;
  }
}
