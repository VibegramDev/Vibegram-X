/*
 * This file is a part of Vibegram X, a fork of Telegram X
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <https://www.gnu.org/licenses/>.
 */
package org.thunderdog.challegram.player;

import android.os.SystemClock;

import androidx.annotation.Nullable;
import androidx.annotation.StringRes;

import org.thunderdog.challegram.R;
import org.thunderdog.challegram.core.Lang;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Vibegram: frame rate diagnostics of the last round video recording (Settings → Video messages → Diagnostics).
 * Counts the frames at every stage: delivered by the camera, rendered, queued to the encoder thread, drawn into
 * the encoder. The stage where the rate drops is where frames are lost. Only counters while recording.
 */
public final class RoundRecordStats {
  private RoundRecordStats () { }

  public static final class Row {
    public final @StringRes int label;
    public final String value;

    Row (@StringRes int label, String value) {
      this.label = label;
      this.value = value;
    }
  }

  public static final class Section {
    public final @StringRes int title;
    public final List<Row> rows = new ArrayList<>();

    Section (@StringRes int title) {
      this.title = title;
    }

    Section add (@StringRes int label, String value) {
      rows.add(new Row(label, value));
      return this;
    }
  }

  public static final class Report {
    public final long date;
    public final List<Section> sections = new ArrayList<>();

    Report (long date) {
      this.date = date;
    }

    /** Plain text of the whole report, to share it. */
    public String toText () {
      StringBuilder b = new StringBuilder(Lang.getString(R.string.vg_RoundDiagTitle));
      for (Section section : sections) {
        b.append("\n\n").append(Lang.getString(section.title));
        for (Row row : section.rows) {
          b.append('\n').append(Lang.getString(row.label)).append(": ").append(row.value);
        }
      }
      return b.toString();
    }
  }

  static volatile boolean active;
  private static volatile long startMs;
  static final AtomicInteger cameraFrames = new AtomicInteger();
  static final AtomicInteger renders = new AtomicInteger();
  static final AtomicInteger queued = new AtomicInteger();
  static final AtomicInteger dropped = new AtomicInteger();
  static final AtomicInteger handled = new AtomicInteger();
  static final AtomicInteger drawn = new AtomicInteger();
  private static final AtomicLong frameDurationSumNs = new AtomicLong();
  private static final AtomicInteger frameDurationCount = new AtomicInteger();
  private static final AtomicLong exposureSumNs = new AtomicLong();
  private static final AtomicLong exposureMaxNs = new AtomicLong();
  private static final AtomicLong swapMaxNs = new AtomicLong();
  private static final AtomicLong handleMaxNs = new AtomicLong();
  private static final AtomicInteger waits = new AtomicInteger();
  private static final AtomicInteger waitTimeouts = new AtomicInteger();
  private static final AtomicLong waitSumNs = new AtomicLong();
  private static final AtomicLong waitMaxNs = new AtomicLong();
  private static final AtomicInteger drains = new AtomicInteger();
  private static final AtomicLong drainSumNs = new AtomicLong();

  public static final int STABILIZATION_OFF = 0, STABILIZATION_ON = 1, STABILIZATION_PREVIEW = 2;

  private static volatile String camera, cameraSize, cameraFpsRange, cameraFpsRanges;
  private static volatile boolean cameraLogical, cameraOis;
  private static volatile int cameraStabilization;
  private static volatile String cameraLens;
  private static volatile String encoder;
  private static volatile int encoderSide, encoderFps, encoderKbps;
  private static volatile String encoderMode;
  private static volatile boolean encoderFences;
  private static volatile Report lastReport;

  /** Camera2 session config; null values for Camera1. */
  public static void setCameraConfig (@Nullable String camera, boolean logical, @Nullable String size, @Nullable String fpsRange, @Nullable String fpsRanges, int stabilization, boolean ois, @Nullable String lens) {
    RoundRecordStats.cameraLens = lens;
    RoundRecordStats.camera = camera;
    RoundRecordStats.cameraLogical = logical;
    RoundRecordStats.cameraSize = size;
    RoundRecordStats.cameraFpsRange = fpsRange;
    RoundRecordStats.cameraFpsRanges = fpsRanges;
    RoundRecordStats.cameraStabilization = stabilization;
    RoundRecordStats.cameraOis = ois;
  }

  /** @param mode CBR, VBR or null when the encoder chose */
  static void setEncoderConfig (String encoder, int side, int fps, int kbps, @Nullable String mode) {
    RoundRecordStats.encoder = encoder;
    RoundRecordStats.encoderSide = side;
    RoundRecordStats.encoderFps = fps;
    RoundRecordStats.encoderKbps = kbps;
    RoundRecordStats.encoderMode = mode;
  }

  static void setEncoderSync (boolean fences) {
    encoderFences = fences;
  }

  static void start () {
    cameraFrames.set(0); renders.set(0); queued.set(0); dropped.set(0); handled.set(0); drawn.set(0);
    frameDurationSumNs.set(0); frameDurationCount.set(0); exposureSumNs.set(0); exposureMaxNs.set(0);
    swapMaxNs.set(0); handleMaxNs.set(0); waits.set(0); waitTimeouts.set(0); waitSumNs.set(0); waitMaxNs.set(0);
    drains.set(0); drainSumNs.set(0);
    startMs = SystemClock.uptimeMillis();
    active = true;
  }

  public static void onCameraFrame (@Nullable Long frameDurationNs, @Nullable Long exposureNs) {
    if (!active) {
      return;
    }
    cameraFrames.incrementAndGet();
    if (frameDurationNs != null && frameDurationNs > 0) {
      frameDurationSumNs.addAndGet(frameDurationNs);
      frameDurationCount.incrementAndGet();
    }
    if (exposureNs != null && exposureNs > 0) {
      exposureSumNs.addAndGet(exposureNs);
      exposureMaxNs.accumulateAndGet(exposureNs, Math::max);
    }
  }

  static void onWait (long ns, boolean timedOut) {
    if (!active) {
      return;
    }
    waits.incrementAndGet();
    if (timedOut) {
      waitTimeouts.incrementAndGet();
    }
    waitSumNs.addAndGet(ns);
    waitMaxNs.accumulateAndGet(ns, Math::max);
  }

  static void onDrawn (long swapNs, long handleNs) {
    if (!active) {
      return;
    }
    drawn.incrementAndGet();
    swapMaxNs.accumulateAndGet(swapNs, Math::max);
    handleMaxNs.accumulateAndGet(handleNs, Math::max);
  }

  static void onDrain (long ns) {
    if (!active) {
      return;
    }
    drains.incrementAndGet();
    drainSumNs.addAndGet(ns);
  }

  private static String fps (int count, double s) {
    return Lang.getString(R.string.vg_RoundDiagFps, String.format(Locale.US, "%.1f", count / s));
  }

  private static String ms (double value) {
    return Lang.getString(R.string.vg_RoundDiagMs, String.format(Locale.US, "%.1f", value));
  }

  private static String stabilizationName () {
    int res;
    switch (cameraStabilization) {
      case STABILIZATION_PREVIEW: res = R.string.vg_RoundDiagStabPreview; break;
      case STABILIZATION_ON: res = R.string.vg_RoundDiagStabOn; break;
      default: res = R.string.vg_RoundDiagStabOff; break;
    }
    return Lang.getString(R.string.vg_RoundDiagStabValue, Lang.getString(res), Lang.getString(cameraOis ? R.string.vg_RoundDiagStabOn : R.string.vg_RoundDiagOisDefault));
  }

  private static String orNone (@Nullable String value) {
    return value != null ? value : "—";
  }

  static void finish () {
    if (!active) {
      return;
    }
    active = false;
    long elapsedMs = SystemClock.uptimeMillis() - startMs;
    if (elapsedMs < 500) {
      return;
    }
    double s = elapsedMs / 1000.0;
    Report report = new Report(System.currentTimeMillis());

    report.sections.add(new Section(R.string.vg_RoundDiagRecording)
      .add(R.string.vg_RoundDiagDuration, Lang.getString(R.string.vg_RoundDiagSeconds, String.format(Locale.US, "%.1f", s)))
      .add(R.string.vg_RoundDiagVideo, encoderSide == 0 ? "—" : encoderSide + "×" + encoderSide + ", " +
        Lang.getString(R.string.vg_RoundDiagFps, String.valueOf(encoderFps)) + ", " +
        org.thunderdog.challegram.ui.SettingsRoundVideoController.formatBitrate(encoderKbps) + (encoderMode != null ? ", " + encoderMode : ""))
      .add(R.string.vg_RoundDiagEncoder, orNone(encoder)));

    int frames = cameraFrames.get();
    int durations = frameDurationCount.get();
    Section cameraSection = new Section(R.string.vg_RoundDiagCamera);
    if (camera != null) {
      cameraSection
        .add(R.string.vg_RoundDiagCameraId, cameraLogical ? Lang.getString(R.string.vg_RoundDiagLogical, camera) : camera)
        .add(R.string.vg_RoundDiagCameraSize, orNone(cameraSize))
        .add(R.string.vg_RoundDiagFpsRange, orNone(cameraFpsRange))
        .add(R.string.vg_RoundDiagFpsRanges, orNone(cameraFpsRanges))
        .add(R.string.vg_RoundDiagLens, orNone(cameraLens))
        .add(R.string.vg_RoundDiagStabilization, stabilizationName())
        .add(R.string.vg_RoundDiagCameraFrames, fps(frames, s))
        .add(R.string.vg_RoundDiagFrameDuration, durations > 0 ? ms(frameDurationSumNs.get() / 1e6 / durations) : "—")
        .add(R.string.vg_RoundDiagExposure, frames > 0 ? ms(exposureSumNs.get() / 1e6 / frames) + " / " + ms(exposureMaxNs.get() / 1e6) : "—");
    } else {
      cameraSection.add(R.string.vg_RoundDiagCameraId, "Camera1");
    }
    report.sections.add(cameraSection);

    int waitCount = waits.get();
    report.sections.add(new Section(R.string.vg_RoundDiagRender)
      .add(R.string.vg_RoundDiagRendered, fps(renders.get(), s))
      .add(R.string.vg_RoundDiagQueued, fps(queued.get(), s))
      .add(R.string.vg_RoundDiagDropped, fps(dropped.get(), s))
      .add(R.string.vg_RoundDiagWaits, waitCount == 0 ? Lang.getString(R.string.vg_RoundDiagNever) : Lang.getString(R.string.vg_RoundDiagWaitsValue,
        String.valueOf(waitCount), ms(waitSumNs.get() / 1e6 / waitCount), ms(waitMaxNs.get() / 1e6), String.valueOf(waitTimeouts.get()))));

    int drainCount = drains.get();
    report.sections.add(new Section(R.string.vg_RoundDiagEncoding)
      .add(R.string.vg_RoundDiagHandled, fps(handled.get(), s))
      .add(R.string.vg_RoundDiagDrawn, fps(drawn.get(), s))
      .add(R.string.vg_RoundDiagLongestFrame, ms(handleMaxNs.get() / 1e6))
      .add(R.string.vg_RoundDiagLongestSwap, ms(swapMaxNs.get() / 1e6))
      .add(R.string.vg_RoundDiagDrain, drainCount == 0 ? "—" : Lang.getString(R.string.vg_RoundDiagDrainValue, String.valueOf(drainCount),
        ms(drainSumNs.get() / 1e6 / drainCount), Lang.getString(R.string.vg_RoundDiagMsPerSecond, String.format(Locale.US, "%.0f", drainSumNs.get() / 1e6 / s))))
      .add(R.string.vg_RoundDiagSync, Lang.getString(R.string.vg_RoundDiagSyncValue, encoderFences ? "GPU fence" : "glFinish", "8")));

    lastReport = report;
  }

  @Nullable
  public static Report getLastReport () {
    return lastReport;
  }
}
