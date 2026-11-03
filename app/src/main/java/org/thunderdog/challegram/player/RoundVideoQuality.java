/*
 * This file is a part of Vibegram X
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

import android.media.MediaCodecInfo;
import android.media.MediaCodecList;
import android.media.MediaFormat;
import android.os.Build;
import android.util.Range;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.thunderdog.challegram.Log;
import org.thunderdog.challegram.R;
import org.thunderdog.challegram.core.Lang;
import org.thunderdog.challegram.tool.UI;
import org.thunderdog.challegram.ui.camera.legacy.CameraManagerLegacy;
import org.thunderdog.challegram.unsorted.Settings;

import java.lang.ref.WeakReference;
import java.util.Locale;

/**
 * Vibegram: encoding parameters of a round video (video note).
 *
 * Defaults are the maximum the device can do. The user request from {@link Settings}
 * (0 = max for every value) is checked against the H.264 encoder capabilities and the
 * camera stream and stepped down along the ladders below until it is supported.
 *
 * Public API for the settings screen:
 * <ul>
 *   <li>{@link #SIDE_OPTIONS}, {@link #FPS_OPTIONS}, {@link #BITRATE_OPTIONS_KBPS} (or the getters returning copies): values to offer (0 is "max", not included)</li>
 *   <li>{@link #isSupported(int, int)}: whether the encoder can do side@fps (grey out the rest)</li>
 *   <li>{@link #getMaxSupported()}: what "max" resolves to on this device (encoder only, cached)</li>
 *   <li>{@link #resolve(Settings)}: what the next recording will use (camera fps is not known yet)</li>
 *   <li>{@link #getMaxBitrate(int, int)}: kbit/s that "max" bitrate means for side@fps</li>
 *   <li>labels: {@link #getLabel()}, {@link #getSideLabel(int)}, {@link #getFrameRateLabel(int)},
 *   {@link #getBitrateLabel(int)}, {@link #getMaxLabel()}, {@link #getSizePerMinuteLabel()}</li>
 * </ul>
 */
public final class RoundVideoQuality {
  /** TDLib rejects InputVideoNote.length above this value. */
  public static final int MAX_SIDE = 640;
  /**
   * TDLib uploads video notes as small files (upload.saveFilePart, never the big-file parts),
   * and the server accepts small files only up to 10 MB: a bigger round video fails to send with error 400.
   */
  public static final long MAX_FILE_SIZE = 10L << 20;
  /** Longest round video: the recording limit (59.5 s) plus what the encoder adds at the edges. */
  private static final long FIT_DURATION_MS = 60_000L;
  /** MP4 index (sample tables) for a minute at 60 fps plus the audio track: ~40 KB measured, with a margin. */
  private static final long CONTAINER_OVERHEAD = 64L << 10;
  /** Constant bitrate encoders still deviate a little from the target. */
  private static final double ENCODER_MARGIN = 1.04;

  /**
   * Highest constant video bitrate (kbit/s) at which a full minute with this audio fits into {@link #MAX_FILE_SIZE}:
   * 1200 kbit/s with 128 kbit/s audio. Does not depend on the side or the frame rate, only on the length.
   */
  public static int getFitVideoKbps (int audioBitrate) {
    long audioBytes = (long) audioBitrate * FIT_DURATION_MS / 8000L;
    long videoBytes = MAX_FILE_SIZE - CONTAINER_OVERHEAD - audioBytes;
    double bps = videoBytes * 8000.0 / FIT_DURATION_MS / ENCODER_MARGIN;
    return Math.max(200, (int) (bps / 1000) / 50 * 50);
  }

  /** Never ask the camera for a stream smaller than this: the downscale needs detail. */
  public static final int MIN_CAPTURE_SIDE = 480;

  // All sides are multiples of 16: hardware encoders work on 16x16 macroblocks.
  // Do not modify these arrays: they are the ladders the step-down walks on.
  public static final int[] SIDE_OPTIONS = {640, 576, 512, 480, 384, 320, 240};
  public static final int[] FPS_OPTIONS = {60, 30};
  /** The first one is the most that fits a minute into Telegram's 10 MB, see {@link #getFitVideoKbps}. */
  public static final int[] BITRATE_OPTIONS_KBPS = {1200, 1000, 800, 600, 400};

  // "Max" bitrate at 640×640, scaled by pixel count for smaller sides.

  private static final String VIDEO_MIME_TYPE = MediaFormat.MIMETYPE_VIDEO_AVC;
  private static final String AUDIO_MIME_TYPE = MediaFormat.MIMETYPE_AUDIO_AAC;

  private static final String KEY_WARNED_PAIR = "vg_round_quality_warned";

  /** Output side in px (square). */
  public final int side;
  /** Frames per second passed to the encoder (KEY_FRAME_RATE). */
  public final int fps;
  /** Video bitrate in kbit/s. */
  public final int videoKbps;
  /** Video bitrate in bit/s. */
  public final int videoBitrate;
  /** Audio bitrate in bit/s (AAC-LC mono). */
  public final int audioBitrate;
  /** Preferred audio sample rate in Hz (AudioRecord falls back to lower ones). */
  public final int audioSampleRate;

  // What was asked for (0 = max), needed for step-downs and warnings.
  final int requestedSide, requestedFps, requestedBitrate;
  // Caps that came from the camera stream (0 = none).
  private final int sideCap, fpsCap;

  private RoundVideoQuality (int side, int fps, int videoKbps, int audioBitrate, int audioSampleRate,
                             int requestedSide, int requestedFps, int requestedBitrate, int sideCap, int fpsCap) {
    this.side = side;
    this.fps = fps;
    this.videoKbps = videoKbps;
    this.videoBitrate = videoKbps * 1000;
    this.audioBitrate = audioBitrate;
    this.audioSampleRate = audioSampleRate;
    this.requestedSide = requestedSide;
    this.requestedFps = requestedFps;
    this.requestedBitrate = requestedBitrate;
    this.sideCap = sideCap;
    this.fpsCap = fpsCap;
  }

  // Options

  public static int[] getSideOptions () {
    return SIDE_OPTIONS.clone();
  }

  public static int[] getFrameRateOptions () {
    return FPS_OPTIONS.clone();
  }

  public static int[] getBitrateOptions () {
    return BITRATE_OPTIONS_KBPS.clone();
  }

  /** Video bitrate in bit/s, for MediaFormat.KEY_BIT_RATE. */
  public int getVideoBitrateBps () {
    return videoBitrate;
  }

  /** Approximate file size of one minute in bytes. */
  public long getBytesPerMinute () {
    return ((long) videoKbps * 1000L + audioBitrate) * 60L / 8L;
  }

  /** Camera stream the recorder needs: short side of the preview. */
  public int getCaptureMinSide () {
    return side > MIN_CAPTURE_SIDE ? 720 : MIN_CAPTURE_SIDE;
  }

  /** True when the effective values are lower than what was asked ("max" means 640 and 60 fps). */
  public boolean isLowerThanRequested () {
    int wantSide = requestedSide > 0 ? requestedSide : SIDE_OPTIONS[0];
    int wantFps = requestedFps > 0 ? requestedFps : FPS_OPTIONS[0];
    return side < wantSide || fps < wantFps || (requestedBitrate > 0 && videoKbps < requestedBitrate);
  }

  @Override
  public boolean equals (Object o) {
    if (this == o) return true;
    if (!(o instanceof RoundVideoQuality)) return false;
    RoundVideoQuality q = (RoundVideoQuality) o;
    return side == q.side && fps == q.fps && videoKbps == q.videoKbps && audioBitrate == q.audioBitrate && audioSampleRate == q.audioSampleRate;
  }

  @Override
  public int hashCode () {
    return ((side * 31 + fps) * 31 + videoKbps) * 31 + audioBitrate;
  }

  @NonNull
  @Override
  public String toString () {
    return side + "x" + side + "@" + fps + " " + videoKbps + "kbps, audio " + audioBitrate + "bps@" + audioSampleRate;
  }

  // Encoder capabilities (cached)

  private static final class Caps {
    @Nullable String videoEncoderName;
    @Nullable MediaCodecInfo.VideoCapabilities video;
    @Nullable MediaCodecInfo.EncoderCapabilities encoder;
    @Nullable MediaCodecInfo.CodecProfileLevel[] profileLevels;
    int audioBitrate = 64000, audioSampleRate = 44100;
  }

  private static volatile Caps caps;
  private static volatile RoundVideoQuality maxSupported;

  private static Caps caps () {
    Caps result = caps;
    if (result == null) {
      synchronized (RoundVideoQuality.class) {
        result = caps;
        if (result == null) {
          result = loadCaps();
          caps = result;
        }
      }
    }
    return result;
  }

  private static Caps loadCaps () {
    Caps result = new Caps();
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) {
      return result;
    }
    try {
      MediaCodecInfo[] infos = new MediaCodecList(MediaCodecList.REGULAR_CODECS).getCodecInfos();
      MediaCodecInfo videoInfo = null, audioInfo = null;
      for (MediaCodecInfo info : infos) {
        if (!info.isEncoder()) {
          continue;
        }
        for (String type : info.getSupportedTypes()) {
          // The first encoder for a type is the one MediaCodec.createEncoderByType() returns.
          if (videoInfo == null && VIDEO_MIME_TYPE.equalsIgnoreCase(type)) {
            videoInfo = info;
          } else if (audioInfo == null && AUDIO_MIME_TYPE.equalsIgnoreCase(type)) {
            audioInfo = info;
          }
        }
      }
      if (videoInfo != null) {
        MediaCodecInfo.CodecCapabilities c = videoInfo.getCapabilitiesForType(VIDEO_MIME_TYPE);
        result.videoEncoderName = videoInfo.getName();
        result.video = c.getVideoCapabilities();
        result.encoder = c.getEncoderCapabilities();
        result.profileLevels = c.profileLevels;
      }
      if (audioInfo != null) {
        MediaCodecInfo.AudioCapabilities a = audioInfo.getCapabilitiesForType(AUDIO_MIME_TYPE).getAudioCapabilities();
        if (a != null) {
          result.audioSampleRate = a.isSampleRateSupported(48000) ? 48000 : 44100;
          Range<Integer> range = a.getBitrateRange();
          for (int bitrate : new int[] {128000, 96000, 64000}) {
            if (range == null || range.contains(bitrate)) {
              result.audioBitrate = bitrate;
              break;
            }
          }
        } else {
          result.audioSampleRate = 48000;
          result.audioBitrate = 128000;
        }
      }
    } catch (Throwable t) {
      Log.w(Log.TAG_ROUND, "Cannot read encoder capabilities", t);
    }
    return result;
  }

  /** Name of the H.264 encoder the checks were made against, null when unknown. */
  @Nullable
  static String getVideoEncoderName () {
    return caps().videoEncoderName;
  }

  /** Whether the encoder supports H.264 High profile at a level that fits side@fps. */
  static boolean supportsHighProfile (int level) {
    MediaCodecInfo.CodecProfileLevel[] levels = caps().profileLevels;
    if (levels == null) {
      return false;
    }
    for (MediaCodecInfo.CodecProfileLevel pl : levels) {
      if (pl.profile == MediaCodecInfo.CodecProfileLevel.AVCProfileHigh && pl.level >= level) {
        return true;
      }
    }
    return false;
  }

  /** Constant bitrate keeps the file size predictable: with VBR some encoders ignore low targets on motion. */
  static boolean supportsCbr () {
    MediaCodecInfo.EncoderCapabilities encoder = caps().encoder;
    return Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP && encoder != null &&
      encoder.isBitrateModeSupported(MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR);
  }

  static boolean supportsVbr () {
    MediaCodecInfo.EncoderCapabilities encoder = caps().encoder;
    return Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP && encoder != null &&
      encoder.isBitrateModeSupported(MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR);
  }

  /** Smallest H.264 level that fits side×side at fps (macroblocks per second and per frame). */
  static int getAvcLevel (int side, int fps) {
    int mbs = (side + 15) / 16;
    long frameMbs = (long) mbs * mbs;
    long mbps = frameMbs * fps;
    if (mbps <= 40500 && frameMbs <= 1620) return MediaCodecInfo.CodecProfileLevel.AVCLevel3;
    if (mbps <= 108000 && frameMbs <= 3600) return MediaCodecInfo.CodecProfileLevel.AVCLevel31;
    if (mbps <= 216000 && frameMbs <= 5120) return MediaCodecInfo.CodecProfileLevel.AVCLevel32;
    return MediaCodecInfo.CodecProfileLevel.AVCLevel4;
  }

  /** Whether the H.264 encoder can do side×side at fps. */
  public static boolean isSupported (int side, int fps) {
    if (side <= 0 || side > MAX_SIDE) {
      return false;
    }
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) {
      // No capability API: stay close to what always worked, the runtime fallback covers the rest.
      return side <= 480 && fps <= 30;
    }
    MediaCodecInfo.VideoCapabilities video = caps().video;
    if (video == null) {
      return fps <= 30;
    }
    try {
      if (side % video.getWidthAlignment() != 0 || side % video.getHeightAlignment() != 0) {
        return false;
      }
      if (!video.getSupportedWidths().contains(side) || !video.getSupportedHeights().contains(side)) {
        return false;
      }
      return video.areSizeAndRateSupported(side, side, fps);
    } catch (Throwable t) {
      Log.w(Log.TAG_ROUND, "Cannot check %dx%d@%d", t, side, side, fps);
      return false;
    }
  }

  private static int encoderMaxBitrateKbps () {
    MediaCodecInfo.VideoCapabilities video = Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP ? caps().video : null;
    if (video != null) {
      try {
        Integer upper = video.getBitrateRange().getUpper();
        if (upper != null && upper > 0) {
          return upper / 1000;
        }
      } catch (Throwable ignored) { }
    }
    return Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP ? BITRATE_OPTIONS_KBPS[0] : 1500;
  }

  /** Largest ladder bitrate not above the encoder limit and the 10 MB fit. */
  private static int capBitrate (int kbps) {
    int upper = Math.min(encoderMaxBitrateKbps(), getFitVideoKbps(caps().audioBitrate));
    if (kbps <= upper) {
      return kbps;
    }
    for (int value : BITRATE_OPTIONS_KBPS) {
      if (value <= upper) {
        return value;
      }
    }
    return Math.max(upper, 100);
  }

  /**
   * kbit/s that "max" bitrate means for side@fps on this device: the most that fits a minute into 10 MB.
   * The limit is the file size, so it is the same for every side and frame rate: at this bitrate 640×640 still
   * looks best (VMAF on a real 60 fps round video: 640 — 85.8, 576 — 84.2, 512 — 83.4, 480 — 82.9).
   */
  public static int getMaxBitrate (int side, int fps) {
    return capBitrate(getFitVideoKbps(caps().audioBitrate));
  }

  private static int ladderFloor (int[] ladder, int value) {
    for (int item : ladder) {
      if (item <= value) {
        return item;
      }
    }
    return ladder[ladder.length - 1];
  }

  // Resolving

  /** What "max" resolves to on this device, encoder only (camera is not known here). Cached. */
  @NonNull
  public static RoundVideoQuality getMaxSupported () {
    RoundVideoQuality result = maxSupported;
    if (result == null) {
      result = resolve(0, 0, 0, 0, 0);
      maxSupported = result;
    }
    return result;
  }

  /** What the next recording will use with the current settings (before the camera caps it). */
  @NonNull
  public static RoundVideoQuality resolve (Settings settings) {
    return resolve(settings.getRoundVideoSide(), settings.getRoundVideoFps(), settings.getRoundVideoBitrate(), 0, 0);
  }

  /**
   * @param requestedSide 0 = max
   * @param requestedFps 0 = max
   * @param requestedBitrate kbit/s, 0 = max for the chosen side and fps
   * @param cameraMaxFps max fps the camera delivers, 0 = unknown
   * @param cameraShortSide short side of the camera preview, 0 = unknown
   */
  @NonNull
  public static RoundVideoQuality resolve (int requestedSide, int requestedFps, int requestedBitrate, int cameraMaxFps, int cameraShortSide) {
    int sideCap = 0, fpsCap = 0;
    if (cameraMaxFps > 0) {
      // Declaring 60 fps to the encoder while the camera gives 30 halves the bits per frame.
      fpsCap = cameraMaxFps >= 55 ? 60 : 30;
    }
    if (cameraShortSide > 0) {
      // Upscaling the camera picture only makes the file bigger.
      sideCap = ladderFloor(SIDE_OPTIONS, cameraShortSide);
    }
    RoundVideoQuality result = findCandidate(requestedSide, requestedFps, requestedBitrate, sideCap, fpsCap, null);
    if (result == null) {
      Caps c = caps();
      result = new RoundVideoQuality(SIDE_OPTIONS[SIDE_OPTIONS.length - 1], 30, requestedBitrate > 0 ? capBitrate(requestedBitrate) : getMaxBitrate(SIDE_OPTIONS[SIDE_OPTIONS.length - 1], 30),
        c.audioBitrate, c.audioSampleRate, requestedSide, requestedFps, requestedBitrate, sideCap, fpsCap);
    }
    return result;
  }

  /**
   * Next lower quality after the encoder refused this one: side (or fps, see {@link #findCandidate}),
   * then bitrate. Null when there is nothing lower.
   */
  @Nullable
  public RoundVideoQuality nextLower () {
    RoundVideoQuality next = findCandidate(requestedSide, requestedFps, requestedBitrate, sideCap, fpsCap, this);
    if (next != null) {
      return next;
    }
    for (int value : BITRATE_OPTIONS_KBPS) {
      if (value < videoKbps) {
        return new RoundVideoQuality(side, fps, value, audioBitrate, audioSampleRate, requestedSide, requestedFps, requestedBitrate, sideCap, fpsCap);
      }
    }
    return null;
  }

  /**
   * Walks side/fps pairs from the best down. The value the user picked explicitly is kept longest:
   * with fps on "max" the fps drops first (640@30 beats 480@60 for a face), with fps pinned the side drops first.
   *
   * @param after when not null, returns the first supported pair strictly after it
   */
  @Nullable
  private static RoundVideoQuality findCandidate (int requestedSide, int requestedFps, int requestedBitrate, int sideCap, int fpsCap, @Nullable RoundVideoQuality after) {
    int maxSide = requestedSide > 0 ? ladderFloor(SIDE_OPTIONS, Math.min(requestedSide, MAX_SIDE)) : SIDE_OPTIONS[0];
    if (sideCap > 0) {
      maxSide = Math.min(maxSide, sideCap);
    }
    int maxFps = requestedFps > 0 ? ladderFloor(FPS_OPTIONS, requestedFps) : FPS_OPTIONS[0];
    if (fpsCap > 0) {
      maxFps = Math.min(maxFps, fpsCap);
    }
    boolean fpsFirst = requestedFps <= 0;
    int[] outer = fpsFirst ? SIDE_OPTIONS : FPS_OPTIONS;
    int[] inner = fpsFirst ? FPS_OPTIONS : SIDE_OPTIONS;
    boolean passed = after == null;
    Caps c = caps();
    for (int o : outer) {
      for (int i : inner) {
        int side = fpsFirst ? o : i;
        int fps = fpsFirst ? i : o;
        if (side > maxSide || fps > maxFps) {
          continue;
        }
        if (!passed) {
          if (side == after.side && fps == after.fps) {
            passed = true;
          }
          continue;
        }
        if (!isSupported(side, fps)) {
          continue;
        }
        int bitrate = requestedBitrate > 0 ? capBitrate(requestedBitrate) : getMaxBitrate(side, fps);
        return new RoundVideoQuality(side, fps, bitrate, c.audioBitrate, c.audioSampleRate, requestedSide, requestedFps, requestedBitrate, sideCap, fpsCap);
      }
    }
    return null;
  }

  // Recording session

  private static volatile WeakReference<CameraManagerLegacy> pendingManager;
  private static volatile RoundVideoQuality pendingQuality;
  private static volatile String lastKey;
  private static volatile RoundVideoQuality lastEffective;

  /**
   * UI thread, before the round camera opens: resolves the settings and tells the camera which stream is needed.
   */
  public static RoundVideoQuality prepareCapture (@NonNull CameraManagerLegacy manager) {
    RoundVideoQuality quality = resolve(Settings.instance());
    pendingQuality = quality;
    pendingManager = new WeakReference<>(manager);
    manager.setRoundCaptureRequest(quality.getCaptureMinSide(), quality.fps);
    Log.i(Log.TAG_ROUND, "Round capture request: %s, camera min side: %d", quality, quality.getCaptureMinSide());
    return quality;
  }

  /**
   * Encoder start: the settings capped by what the camera really delivers.
   * @param previewWidth camera buffer width, 0 when unknown
   * @param previewHeight camera buffer height, 0 when unknown
   */
  @NonNull
  static RoundVideoQuality forEncoder (int previewWidth, int previewHeight) {
    Settings settings = Settings.instance();
    int cameraMaxFps = 0;
    WeakReference<CameraManagerLegacy> ref = pendingManager;
    CameraManagerLegacy manager = ref != null ? ref.get() : null;
    if (manager != null) {
      cameraMaxFps = manager.getRoundActualMaxFps();
      if (previewWidth <= 0 || previewHeight <= 0) {
        previewWidth = manager.getRoundActualPreviewWidth();
        previewHeight = manager.getRoundActualPreviewHeight();
      }
    }
    if (cameraMaxFps <= 0) {
      // Unknown camera rate: 30 is what every camera gives, see the note in resolve().
      cameraMaxFps = 30;
    }
    int shortSide = previewWidth > 0 && previewHeight > 0 ? Math.min(previewWidth, previewHeight) : 0;
    return resolve(settings.getRoundVideoSide(), settings.getRoundVideoFps(), settings.getRoundVideoBitrate(), cameraMaxFps, shortSide);
  }

  /** Encoder thread: the encoder runs with these values. Stores device limits and warns once if lower than asked. */
  static void onEncoderStarted (String key, @NonNull RoundVideoQuality effective) {
    lastKey = key;
    lastEffective = effective;
    Log.i(Log.TAG_ROUND, "Round encoder started: %s (requested %d/%d/%d)", effective, effective.requestedSide, effective.requestedFps, effective.requestedBitrate);
    try {
      saveDeviceLimits(effective);
    } catch (Throwable t) {
      Log.w(Log.TAG_ROUND, "Cannot save round device limits", t);
    }
    UI.post(() -> maybeWarn(effective));
  }

  /** The quality a finished recording was made with: by key, else the last one, else null. */
  @Nullable
  public static RoundVideoQuality getRecorded (@Nullable String key) {
    RoundVideoQuality last = lastEffective;
    if (last != null && key != null && !key.equals(lastKey)) {
      // Only one round video is recorded at a time, so the last encoder start is still the best guess.
      Log.w(Log.TAG_ROUND, "Round quality for %s is unknown, using the last one (%s)", key, lastKey);
    }
    return last;
  }

  /** InputVideoNote.length for a recording: the real output side, never above the TDLib limit. */
  public static int getVideoNoteLength (@Nullable String key) {
    RoundVideoQuality recorded = getRecorded(key);
    int side = recorded != null ? recorded.side : resolve(Settings.instance()).side;
    return Math.max(1, Math.min(MAX_SIDE, side));
  }

  private static void saveDeviceLimits (RoundVideoQuality effective) {
    Settings settings = Settings.instance();
    int[] old = settings.getRoundDeviceLimits();
    RoundVideoQuality max = getMaxSupported();
    int side = old != null ? old[0] : max.side;
    int fps = old != null ? old[1] : max.fps;
    int kbps = old != null ? old[2] : max.videoKbps;
    // A value says something about the device only when it was "max" or had to be lowered.
    boolean sideIsLimit = effective.requestedSide <= 0 || effective.side < effective.requestedSide;
    boolean fpsIsLimit = effective.requestedFps <= 0 || effective.fps < effective.requestedFps;
    boolean kbpsIsLimit = (effective.requestedBitrate <= 0 && sideIsLimit && fpsIsLimit) ||
      (effective.requestedBitrate > 0 && effective.videoKbps < effective.requestedBitrate);
    if (sideIsLimit) side = effective.side;
    if (fpsIsLimit) fps = effective.fps;
    if (kbpsIsLimit) kbps = effective.videoKbps;
    if (old == null || old[0] != side || old[1] != fps || old[2] != kbps) {
      settings.setRoundDeviceLimits(side, fps, kbps);
    }
  }

  private static void maybeWarn (RoundVideoQuality effective) {
    if (!effective.isLowerThanRequested()) {
      return;
    }
    boolean showBitrate = effective.requestedBitrate > 0;
    int wantSide = effective.requestedSide > 0 ? effective.requestedSide : SIDE_OPTIONS[0];
    int wantFps = effective.requestedFps > 0 ? effective.requestedFps : FPS_OPTIONS[0];
    String pair = wantSide + "|" + wantFps + "|" + effective.requestedBitrate + ">" + effective.side + "|" + effective.fps + "|" + (showBitrate ? effective.videoKbps : 0);
    Settings settings = Settings.instance();
    if (pair.equals(settings.getString(KEY_WARNED_PAIR, null))) {
      // Already told the user about exactly this step-down: do not nag on every recording.
      return;
    }
    settings.putString(KEY_WARNED_PAIR, pair);
    String wanted = describe(wantSide, wantFps, showBitrate ? effective.requestedBitrate : 0);
    String actual = describe(effective.side, effective.fps, showBitrate ? effective.videoKbps : 0);
    UI.showToast(Lang.getString(R.string.vg_RoundQualityLowered, wanted, actual), Toast.LENGTH_LONG);
  }

  // Labels

  private static String describe (int side, int fps, int kbps) {
    StringBuilder b = new StringBuilder(getSideLabel(side)).append(' ').append(getFrameRateLabel(fps));
    if (kbps > 0) {
      b.append(' ').append(getBitrateLabel(kbps));
    }
    return b.toString();
  }

  /** "640×640" */
  public static String getSideLabel (int side) {
    return side + "×" + side;
  }

  /** "60 fps" / "60 кадров/с" */
  public static String getFrameRateLabel (int fps) {
    return Lang.getString(R.string.vg_RoundQualityFps, fps);
  }

  /** "6 Mbit/s", "2.5 Mbit/s", "600 kbit/s" */
  public static String getBitrateLabel (int kbps) {
    if (kbps >= 1000) {
      String value = kbps % 1000 == 0 ? Integer.toString(kbps / 1000) : formatDecimal(kbps / 1000.0);
      return Lang.getString(R.string.vg_RoundQualityMbps, value);
    }
    return Lang.getString(R.string.vg_RoundQualityKbps, kbps);
  }

  /** "Maximum" — the label for the 0 value of every option. */
  public static String getMaxLabel () {
    return Lang.getString(R.string.vg_RoundQualityMax);
  }

  /** "Maximum (640×640)" style label: getMaxLabel(valueLabel). */
  public static String getMaxLabel (String valueLabel) {
    return Lang.getString(R.string.vg_RoundQualityMaxValue, valueLabel);
  }

  /** "640×640 · 60 fps · 6 Mbit/s" */
  public String getLabel () {
    return getSideLabel(side) + " · " + getFrameRateLabel(fps) + " · " + getBitrateLabel(videoKbps);
  }

  /** "≈46 MB per minute" */
  public String getSizePerMinuteLabel () {
    double mb = getBytesPerMinute() / 1000.0 / 1000.0;
    return Lang.getString(R.string.vg_RoundQualitySizePerMinute, mb >= 10 ? Long.toString(Math.round(mb)) : formatDecimal(mb));
  }

  private static String formatDecimal (double value) {
    String text = String.format(Locale.US, "%.1f", value);
    if (text.endsWith(".0")) {
      text = text.substring(0, text.length() - 2);
    }
    // Russian and most other locales use a comma.
    return Lang.getString(R.string.vg_RoundQualityDecimalSeparator).equals(",") ? text.replace('.', ',') : text;
  }
}
