/*
 * This file is a part of Telegram X
 * Copyright © 2014 (tgx-android@pm.me)
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <https://www.gnu.org/licenses/>.
 *
 * File created on 18/09/2017
 */
package org.thunderdog.challegram.ui.camera.legacy;

import java.util.Arrays;
import android.content.Context;
import android.graphics.SurfaceTexture;
import android.os.Handler;
import android.os.Message;
import android.os.SystemClock;

import androidx.annotation.Nullable;
import androidx.annotation.WorkerThread;

import org.thunderdog.challegram.Log;
import org.thunderdog.challegram.config.Config;
import org.thunderdog.challegram.core.BaseThread;
import org.thunderdog.challegram.loader.ImageGalleryFile;
import org.thunderdog.challegram.player.RoundVideoRecorder;
import org.thunderdog.challegram.tool.UI;
import org.thunderdog.challegram.ui.camera.CameraFeatures;
import org.thunderdog.challegram.ui.camera.CameraManager;

import java.util.List;

import me.vkryl.core.lambda.RunnableData;

public abstract class CameraApi {

  protected final Context context;
  protected final CameraManagerLegacy manager;
  protected final CameraThread cameraThread;
  protected final BaseThread backgroundThread;

  protected int mPreviewWidth, mPreviewHeight;
  protected int mDisplayOrientation;

  protected int mSurfaceWidth, mSurfaceHeight;

  protected CameraFeatures mFeatures;
  protected int mFlashMode = CameraFeatures.FEATURE_FLASH_OFF;
  protected float mZoom;

  protected boolean isCameraActive;

  private boolean cameraPreviewRequested;
  protected SurfaceTexture mSurface;

  protected int mForcedOutputOrientation = -1;

  protected int mNumberOfCameras = -1;
  private int mRequestedCameraIndex;

  public CameraApi (Context context, CameraManagerLegacy manager) {
    this.context = context;
    this.manager = manager;
    this.cameraThread = new CameraThread(this, false);
    this.backgroundThread = new CameraThread(this, true);
  }

  public final Handler getCameraHandler () {
    return cameraThread.getHandler();
  }

  public final Handler getBackgroundHandler () {
    return backgroundThread.getHandler();
  }

  /**
   * Preview size changed
   *
   * TODO: As an optimization, it's possible to call {@code manager.setAspectRatio(width, height)} right in this method.
   * */
  public final void setPreviewSize (int viewWidth, int viewHeight) {
    if (!checkCameraThread()) {
      sendMessage(ACTION_SET_PREVIEW_SIZE, viewWidth, viewHeight);
      return;
    }

    if (mPreviewWidth != viewWidth || mPreviewHeight != viewHeight) {
      mPreviewWidth = viewWidth;
      mPreviewHeight = viewHeight;
      onPreviewSizeChanged(viewWidth, viewHeight);
      if (isCameraActive && !isCameraBusy()) {
        setCameraActive(false);
        setCameraActive(true);
      }
    }
  }
  protected abstract void onPreviewSizeChanged (int newWidth, int newHeight);

  private boolean isCameraBusy () {
    return isVideoCapturing || (roundRecorder != null && roundRecorder.isCapturing());
  }

  /**
   * Open camera preview
   */
  public final boolean openPreview () {
    setCameraPreviewRequested(true);
    return true;
  }

  /**
   * Close camera preview
   */
  public final boolean closePreview () {
    setCameraPreviewRequested(false);
    return true;
  }

  private void setCameraPreviewRequested (boolean isRequested) {
    if (!checkCameraThread()) {
      sendMessage(ACTION_SET_PREVIEW_REQUESTED, isRequested ? 1 : 0, 0);
      return;
    }

    if (this.cameraPreviewRequested != isRequested) {
      this.cameraPreviewRequested = isRequested;
      checkCameraActive();
    }
  }

  private void checkCameraActive () {
    if (!checkCameraThread()) {
      sendMessage(ACTION_CHECK_CAMERA_ACTIVE);
      return;
    }
    setCameraActive(cameraPreviewRequested && mSurface != null);
  }

  /**
   * Switch to next camera, if possible
   * */
  public final void switchToNextCameraDevice () {
    if (!checkCameraThread()) {
      if (!hasMessages(ACTION_SWITCH_TO_NEXT_CAMERA)) {
        sendMessage(ACTION_SWITCH_TO_NEXT_CAMERA);
      }
      return;
    }

    if (roundRecorder != null) {
      if (roundRecorder.canSwitchToNewCamera()) {
        onNextCameraSourceRequested();
      }
      return;
    }

    if (isCameraActive && cameraOpened) {
      onNextCameraSourceRequested();
    }
  }
  protected abstract void onNextCameraSourceRequested ();

  protected final void setCameraDeviceFeatures (CameraFeatures features) {
    synchronized (manager) {
      if (this.mFeatures != features) {
        this.mFeatures = features;
        // TODO ?
      }
    }
  }

  /**
   * Toggle flash mode, if possible.
   *
   * Possible values:
   * {@link CameraFeatures#FEATURE_FLASH_OFF}
   * {@link CameraFeatures#FEATURE_FLASH_ON}
   * {@link CameraFeatures#FEATURE_FLASH_AUTO}
   */
  public final void toggleFlashMode () {
    if (!checkCameraThread()) {
      if (!hasMessages(ACTION_TOGGLE_FLASH_MODE)) {
        sendMessage(ACTION_TOGGLE_FLASH_MODE);
      }
      return;
    }
    if (isCameraActive && mFeatures != null && mFeatures.canFlash(true)) {
      setFlashMode(getNextFlashMode());
    }
  }
  protected abstract void onFlashModeChange (int newFlashMode) throws Throwable;

  // Torch: a steady light for round videos, kept across camera switches and reopenings

  protected boolean mTorchRequested;

  public final void setTorchEnabled (boolean enabled) {
    if (!checkCameraThread()) {
      cameraThread.getHandler().removeMessages(ACTION_SET_TORCH);
      sendMessage(ACTION_SET_TORCH, enabled ? 1 : 0, 0);
      return;
    }
    if (mTorchRequested != enabled) {
      mTorchRequested = enabled;
      if (isCameraActive) {
        try {
          onTorchChange(enabled);
        } catch (Throwable t) {
          Log.w(Log.TAG_CAMERA, "Cannot change torch state", t);
        }
      }
    }
  }

  /** Applies {@link #mTorchRequested} to the open camera; cameras without a torch ignore it. */
  protected void onTorchChange (boolean enabled) throws Throwable { }

  // Vibegram: zoom as a ratio for round videos, the way camera apps show it
  // (1 = main lens, below 1 = ultra-wide, the telephoto lens at its native factor).
  // The backend reports what the open camera can do with setZoomCapabilities()
  // and applies a requested ratio in onZoomRatioChanged().

  protected volatile float mZoomRatio = 1f;
  protected volatile float mMinZoomRatio = 1f, mMaxZoomRatio = 1f;
  protected volatile float[] mZoomStops = new float[] {1f};

  // Beyond the camera's own maximum the zoom goes on in software: the round recorder crops
  // the center of every frame (preview and video) by mSoftwareZoom, like camera apps do past their digital limit.
  // Samsung opens 0.6–10× to other apps, its camera app goes up to 30× (3× lens, then crop with processing).
  // Camera apps do not publish their limit, so ours is the same for every phone: the longest lens ×10, at most 100×
  // (3× lens: 30× like Galaxy S24, 5× lens: 50×, no telephoto: 10×).
  /** The zoom goes up to this many times the longest lens... */
  public static final float SOFTWARE_ZOOM_LENS_FACTOR = 10f;
  /** ...and never past this ratio in total. */
  public static final float SOFTWARE_ZOOM_MAX_RATIO = 100f;
  protected volatile float mHardwareMaxZoomRatio = 1f;
  private volatile float mSoftwareZoom = 1f;

  /** Extra center crop on top of the camera zoom, 1 when the camera zoom alone is enough. Any thread. */
  public final float getSoftwareZoom () {
    return mSoftwareZoom;
  }

  /** Camera thread. The ratio was set by a hardware-only path (volume keys): no software crop. */
  protected final void resetSoftwareZoom () {
    mSoftwareZoom = 1f;
  }

  public final float getZoomRatio () {
    return mZoomRatio;
  }

  public final float getMinZoomRatio () {
    return mMinZoomRatio;
  }

  public final float getMaxZoomRatio () {
    return mMaxZoomRatio;
  }

  /** Ratios of the zoom buttons: ultra-wide (if any), 1, 2, native telephoto factors, all within min..max. */
  public final float[] getZoomStops () {
    return mZoomStops;
  }

  /** Any thread. Requests are coalesced: only the latest ratio is applied. */
  public final void requestZoomRatio (float ratio) {
    requestZoomRatio(ratio, ratio);
  }

  /**
   * Any thread. Requests are coalesced: only the latest ratio is applied.
   * @param target where the zoom is going (the stop of a zoom animation), the ratio itself otherwise:
   * the Camera2 lens lock picks the lens by it, so the lens switches at the start of the animation.
   */
  public final void requestZoomRatio (float ratio, float target) {
    if (!checkCameraThread()) {
      cameraThread.getHandler().removeMessages(ACTION_REQUEST_ZOOM_RATIO);
      sendMessage(ACTION_REQUEST_ZOOM_RATIO, Float.floatToIntBits(ratio), Float.floatToIntBits(target));
      return;
    }
    ratio = Math.max(mMinZoomRatio, Math.min(mMaxZoomRatio, ratio));
    mZoomTarget = Math.max(mMinZoomRatio, Math.min(mMaxZoomRatio, target));
    ratio = resolveZoomRatio(ratio, mZoomTarget);
    if (!isCameraActive || mZoomRatio == ratio) {
      return;
    }
    try {
      float hardwareRatio = getHardwareZoomRatio(ratio);
      if (needsZoomUpdate(mZoomRatio, ratio)) {
        onZoomRatioChanged(hardwareRatio);
      }
      mSoftwareZoom = computeSoftwareZoom(ratio);
      mZoomRatio = ratio;
      manager.onZoomRatioChanged(ratio);
    } catch (Throwable t) {
      Log.w(Log.TAG_CAMERA, "Cannot change zoom ratio", t);
    }
  }

  /** Camera thread. Applies the ratio to the open camera. */
  protected void onZoomRatioChanged (float ratio) throws Throwable { }

  /** Where the zoom is going: see {@link #requestZoomRatio(float, float)}; 0 = the current ratio. */
  protected volatile float mZoomTarget;
  /** Set before reopening the same camera (lens lock): {@link #setZoomCapabilities} keeps this ratio instead of 1×. */
  protected float mKeepZoomRatio;

  /** Camera thread: the ratio to apply for a requested one (Camera2 lens lock: the target when it is on another lens). */
  protected float resolveZoomRatio (float ratio, float target) {
    return ratio;
  }

  /** Camera thread: the software crop for a ratio. */
  protected float computeSoftwareZoom (float ratio) {
    return Math.max(1f, ratio / getHardwareZoomRatio(ratio));
  }

  /**
   * Any thread: false while a camera reopened on another lens (Camera2 lens lock) settles its exposure:
   * the round recorder keeps the blurred previous frame meanwhile.
   */
  public boolean isCameraSettled () {
    return true;
  }

  /** Camera thread: whether going from one ratio to another changes what the camera does (not only the software crop). */
  protected boolean needsZoomUpdate (float fromRatio, float toRatio) {
    return getHardwareZoomRatio(fromRatio) != getHardwareZoomRatio(toRatio);
  }

  /** The part of the zoom ratio the camera does itself, the rest is the software crop. */
  protected float getHardwareZoomRatio (float ratio) {
    return Math.min(ratio, mHardwareMaxZoomRatio);
  }

  /** Whether the round recorder (if any) can take frames of a reopened camera now. */
  protected final boolean canSwitchRoundCamera () {
    return roundRecorder == null || roundRecorder.canSwitchToNewCamera();
  }

  /** Camera thread. Called by the backend when a camera has been opened (or closed: 1, 1, {1}). */
  protected final void setZoomCapabilities (float minRatio, float maxRatio, float[] stops) {
    setZoomCapabilities(minRatio, maxRatio, stops, 0f);
  }

  /**
   * @param longestLens ratio of the longest lens (1 without a telephoto lens), or 0 for no software zoom.
   * The zoom then goes past maxRatio by cropping frames, up to the longest lens × {@link #SOFTWARE_ZOOM_LENS_FACTOR}
   * (at most {@link #SOFTWARE_ZOOM_MAX_RATIO}), and this last ratio is added to the stops.
   */
  protected final void setZoomCapabilities (float minRatio, float maxRatio, float[] stops, float longestLens) {
    mHardwareMaxZoomRatio = maxRatio;
    mSoftwareZoom = 1f;
    if (longestLens > 0f && maxRatio > 1f) {
      float extended = Math.min(SOFTWARE_ZOOM_MAX_RATIO, Math.max(1f, longestLens) * SOFTWARE_ZOOM_LENS_FACTOR);
      // Whole numbers only: 30×, not 26.4×
      extended = (float) Math.floor(extended);
      if (extended > maxRatio + 0.5f) {
        maxRatio = extended;
        stops = Arrays.copyOf(stops, stops.length + 1);
        stops[stops.length - 1] = extended;
      }
    }
    mMinZoomRatio = minRatio;
    mMaxZoomRatio = maxRatio;
    mZoomStops = stops;
    float keep = mKeepZoomRatio;
    mKeepZoomRatio = 0f;
    if (keep > 0f) {
      // The same camera reopened on another lens: the zoom stays, no jump to 1× and back
      mZoomRatio = Math.max(minRatio, Math.min(maxRatio, keep));
      mSoftwareZoom = Math.max(1f, mZoomRatio / getHardwareZoomRatio(mZoomRatio));
    } else {
      mZoomRatio = Math.max(minRatio, Math.min(maxRatio, 1f));
      mZoomTarget = 0f;
    }
    manager.onZoomCapabilitiesChanged(minRatio, maxRatio, stops, mZoomRatio);
  }

  /** Whether this backend is Camera2 (used for settings and diagnostics). */
  public boolean isCamera2 () {
    return false;
  }

  /**
   * Camera thread. The zoom was changed by ratio: keeps the legacy zoom index
   * (volume keys, {@link #getZoom()}) in sync without applying it again.
   */
  protected final void syncZoomIndex (float zoom) {
    if (this.mZoom != zoom) {
      this.mZoom = zoom;
      manager.onZoomChanged(zoom);
    }
  }

  protected final boolean hasRoundRecorder () {
    return roundRecorder != null;
  }

  public static final int FACING_UNKNOWN = -1, FACING_BACK = 0, FACING_FRONT = 1;

  /** Facing of the camera opened last, so a replacement backend can continue with the same one. */
  protected volatile int mCurrentFacing = FACING_UNKNOWN;

  public final int getCurrentFacing () {
    return mCurrentFacing;
  }

  // Vibegram: round video stream selection shared by both backends

  /**
   * The smallest size whose short side is at least {@code minSide}: 16:9 first, then 4:3, then any ratio.
   * Sizes with a long side above 1920 are only used when nothing smaller fits.
   * When no size is big enough, the largest one is returned.
   *
   * @param sizes {width, height} pairs
   */
  protected static int[] chooseRoundPreviewSize (List<int[]> sizes, int minSide) {
    if (sizes == null || sizes.isEmpty()) {
      return null;
    }
    final int maxLongSide = Math.max(1920, minSide * 16 / 9 + 1);
    int[] best169 = null, best43 = null, bestAny = null, largest = null;
    for (int[] size : sizes) {
      int w = size[0], h = size[1];
      if (w <= 0 || h <= 0) {
        continue;
      }
      if (largest == null || (long) w * h > (long) largest[0] * largest[1]) {
        largest = size;
      }
      int shortSide = Math.min(w, h), longSide = Math.max(w, h);
      if (shortSide < minSide || longSide > maxLongSide) {
        continue;
      }
      float ratio = (float) longSide / (float) shortSide;
      if (Math.abs(ratio - 16f / 9f) < 0.02f) {
        best169 = smallerSize(best169, size);
      } else if (Math.abs(ratio - 4f / 3f) < 0.02f) {
        best43 = smallerSize(best43, size);
      } else {
        bestAny = smallerSize(bestAny, size);
      }
    }
    if (best169 != null) {
      return best169;
    }
    if (best43 != null) {
      return best43;
    }
    if (bestAny != null) {
      return bestAny;
    }
    // Nothing fits under the cap: the smallest one above it, or the largest when none is big enough
    int[] bigEnough = null;
    for (int[] size : sizes) {
      if (size[0] > 0 && size[1] > 0 && Math.min(size[0], size[1]) >= minSide) {
        bigEnough = smallerSize(bigEnough, size);
      }
    }
    return bigEnough != null ? bigEnough : largest;
  }

  private static int[] smallerSize (int[] current, int[] candidate) {
    if (current == null) {
      return candidate;
    }
    long a = (long) current[0] * current[1], b = (long) candidate[0] * candidate[1];
    return b < a ? candidate : current;
  }

  /**
   * The best fps range for {@code fps}: the highest upper bound not above {@code fps},
   * a fixed range first, then the highest lower bound. Falls back to the range with the lowest upper bound.
   *
   * @param ranges {min, max} pairs, multiplied by {@code scale} (1000 for Camera1, 1 for Camera2)
   */
  protected static int[] chooseFpsRange (List<int[]> ranges, int fps, int scale) {
    if (ranges == null || ranges.isEmpty() || fps <= 0) {
      return null;
    }
    // Some HALs report 29.97 fps as 29970 or 30 as 30010
    final int limit = fps * scale + (scale >= 1000 ? 100 : 0);
    int[] best = null, lowest = null;
    for (int[] range : ranges) {
      if (range == null || range.length < 2 || range[1] <= 0) {
        continue;
      }
      if (lowest == null || range[1] < lowest[1] || (range[1] == lowest[1] && range[0] > lowest[0])) {
        lowest = range;
      }
      if (range[1] > limit) {
        continue;
      }
      if (best == null || range[1] > best[1] || (range[1] == best[1] && range[0] > best[0])) {
        best = range;
      }
    }
    return best != null ? best : lowest;
  }

  protected final void setRequestedCameraIndex (int index) {
    if (index >= mNumberOfCameras) {
      index = 0;
    }
    if (mRequestedCameraIndex != index) {
      mRequestedCameraIndex = index;
    }
  }

  protected final int getRequestedCameraIndex () {
    if (mRequestedCameraIndex >= mNumberOfCameras || mRequestedCameraIndex < 0)
      mRequestedCameraIndex = 0;
    return mRequestedCameraIndex;
  }

  protected final int getNextCameraIndex () {
    int nextCameraIndex = mRequestedCameraIndex + 1;
    if (nextCameraIndex >= mNumberOfCameras)
      nextCameraIndex = 0;
    return nextCameraIndex;
  }

  /**
   * Change zoom, if possible.
   * */
  public final void requestZoom (float zoom) {
    if (!checkCameraThread()) {
      sendMessage(ACTION_REQUEST_ZOOM, Float.floatToIntBits(zoom), 0);
      return;
    }
    if (isCameraActive && mFeatures.canZoom()) {
      setZoom(zoom);
    }
  }
  protected abstract void onZoomChanged (float zoom);

  public void resetRequestedSettings () {
    if (!checkCameraThread()) {
      if (!hasMessages(ACTION_RESET_REQUESTED_SETTINGS)) {
        sendMessage(ACTION_RESET_REQUESTED_SETTINGS);
      }
      return;
    }
    if (!isCameraActive) {
      setFlashMode(CameraFeatures.FEATURE_FLASH_OFF);
      setZoom(0f);
      setRequestedCameraIndex(0);
      onResetRequestedSettings();
    }
  }
  protected abstract void onResetRequestedSettings ();

  public float getMaxZoom () {
    return mFeatures != null ? mFeatures.getMaxZoom() : 0f;
  }

  private int getNextFlashMode () {
    assertCameraThread();
    if (mFeatures == null) {
      return mFlashMode;
    }
    switch (mFlashMode) {
      case CameraFeatures.FEATURE_FLASH_OFF:
        if (mFeatures.has(CameraFeatures.FEATURE_FLASH_ON)) {
          return CameraFeatures.FEATURE_FLASH_ON;
        } else if (mFeatures.has(CameraFeatures.FEATURE_FACING_FRONT)) {
          return CameraFeatures.FEATURE_FLASH_FAKE;
        }
        break;
      case CameraFeatures.FEATURE_FLASH_ON:
      case CameraFeatures.FEATURE_FLASH_FAKE:
        if (Config.CAMERA_AUTO_FLASH_ENABLED && mFeatures.has(CameraFeatures.FEATURE_FLASH_AUTO)) {
          return CameraFeatures.FEATURE_FLASH_AUTO;
        }
        break;
      case CameraFeatures.FEATURE_FLASH_AUTO:
        if (mFeatures.canFlash(true)) {
          return CameraFeatures.FEATURE_FLASH_OFF;
        }
        break;
    }
    return mFlashMode;
  }

  protected final void resetFlashMode () {
    setFlashMode(CameraFeatures.FEATURE_FLASH_OFF);
  }

  private void setFlashMode (int mode) {
    assertCameraThread();
    if (this.mFlashMode != mode) {
      boolean success = false;
      try {
        if (mFeatures == null || mode == CameraFeatures.FEATURE_FLASH_OFF || mode == CameraFeatures.FEATURE_FLASH_FAKE || mFeatures.canFlash(false)) {
          onFlashModeChange(mode);
        }
        success = true;
      } catch (Throwable t) {
        Log.w(Log.TAG_CAMERA, "Cannot change flash mode", t);
      }
      if (success) {
        this.mFlashMode = mode;
        manager.onFlashModeChanged(mode);
      }
    }
  }

  protected final void resetZoom () {
    setZoom(0f);
  }

  public final float getZoom () {
    return mZoom;
  }

  private void setZoom (float zoom) {
    assertCameraThread();
    if (this.mZoom != zoom) {
      boolean success = false;
      try {
        if (mFeatures == null || mFeatures.canZoom()) {
          onZoomChanged(zoom);
        }
        success = true;
      } catch (Throwable t) {
        Log.w(Log.TAG_CAMERA, "Cannot zoom", t);
      }
      if (success) {
        this.mZoom = zoom;
        manager.onZoomChanged(zoom);
      }
    }
  }

  /**
   * Async request to take photo.
   * */
  public final void takePhoto (int trimWidth, int trimHeight, int orientation) {
    if (!checkCameraThread()) {
      if (!hasMessages(ACTION_TAKE_PHOTO)) {
        sendMessage(ACTION_TAKE_PHOTO, trimWidth, trimHeight, orientation);
      }
      return;
    }
    if (isCameraActive) {
      manager.onPerformSuccessHint(false);
      try {
        onTakePhoto(trimWidth, trimHeight, orientation);
      } catch (Throwable t) {
        Log.w(Log.TAG_CAMERA, "Cannot take photo", t);
        manager.onTakeMediaError(false);
      }
    }
  }
  protected abstract void onTakePhoto (int trimWidth, int trimHeight, int orientation);

  /**
   * @param orientation -1 When output orientation is no longer blocked.
   *                    0, 90, 180, 270 otherwise
   * */
  protected final void forceOutputOrientation (int orientation) {
    if (!checkCameraThread()) {
      sendMessage(ACTION_FORCE_OUTPUT_ORIENTATION, orientation, 0);
      return;
    }
    if (mForcedOutputOrientation != orientation) {
      mForcedOutputOrientation = orientation;
      onForcedOrientationChange();
    }
  }
  protected abstract void onForcedOrientationChange ();

  private int outputVideoOrientation;

  /**
   * Async request to start video capture.
   * */
  public final void startVideoCapture (int outputOrientation) {
    if (!manager.isCapturingVideo() && isCameraActive) {
      outputVideoOrientation = outputOrientation;
      setVideoCapturing(true);
    }
  }

  /**
   * Async request to stop video capture.
   */
  public final void finishOrCancelVideoCapture () {
    if (manager.isCapturingVideo()) {
      setVideoCapturing(false);
    }
  }

  protected long mVideoCaptureStartTime;

  private boolean isVideoCapturing;

  private void setVideoCapturing (boolean isCapturing) {
    if (!checkCameraThread()) {
      sendMessage(ACTION_CHANGE_CAPTURE_STATE, isCapturing ? 1 : 0, 0);
      return;
    }
    if (this.isVideoCapturing != isCapturing) {
      if (isCapturing) {
        boolean done = false;
        try {
          mVideoCaptureStartTime = 0;
          forceOutputOrientation(outputVideoOrientation);
          onStartVideoCapture();
          mVideoCaptureStartTime = SystemClock.uptimeMillis();
          done = true;
        } catch (Throwable t) {
          Log.w(Log.TAG_CAMERA, "Cannot start video capture", t);
        }
        if (done) {
          this.isVideoCapturing = true;
          manager.setTakingVideo(true, mVideoCaptureStartTime);
          return;
        }
      }
      forceOutputOrientation(-1);
      boolean needFile = mVideoCaptureStartTime != 0 && SystemClock.uptimeMillis() - mVideoCaptureStartTime > CameraManager.MINIMUM_VIDEO_DURATION;
      this.isVideoCapturing = false;
      manager.setTakingVideo(false, -1);
      onFinishVideoCapture(needFile, galleryFile -> {
        if (galleryFile != null)
          manager.onTakeMediaResult(galleryFile, true);
      });
    }
  }

  protected abstract void onStartVideoCapture () throws Throwable;
  protected abstract void onFinishVideoCapture (boolean saveFile, RunnableData<ImageGalleryFile> callback);

  /**
   * Tells whether video recording is implemented in current API instance
   */
  public abstract boolean isVideoRecordSupported ();

  /**
   * Informs about current display rotation
   * @param degrees Rotation degrees, always divisible by 90
   */
  public final void setDisplayOrientation (int degrees) {
    if (!checkCameraThread()) {
      sendMessage(ACTION_SET_DISPLAY_ORIENTATION, degrees, 0);
      return;
    }

    synchronized (this) {
      if (this.mDisplayOrientation != degrees) {
        this.mDisplayOrientation = degrees;
        onDisplayOrientationChanged();
      }
    }
  }

  protected abstract void onDisplayOrientationChanged ();

  protected static int compareOutputSizes (int w1, int h1, int w2, int h2, float desiredRatio, int maxResolution) {
    if (maxResolution <= 0) {
      maxResolution = 1080;
    }

    boolean e1 = Math.max(w1, h1) <= maxResolution;
    boolean e2 = Math.max(w2, h2) <= maxResolution;

    if (e1 != e2) {
      return e1 ? -1 : 1;
    }

    final float r1 = (float) Math.max(w1, h1) / (float) Math.min(w1, h1);
    final float r2 = (float) Math.max(w2, h2) / (float) Math.min(w2, h2);

    final float rd1 = Math.abs(r1 - desiredRatio);
    final float rd2 = Math.abs(r2 - desiredRatio);

    if (rd1 != rd2) {
      return rd1 < rd2 ? -1 : 1;
    }

    final long s1 = (long) w1 * h1;
    final long s2 = (long) w2 * h2;

    if (s1 != s2) {
      return s1 > s2 ? -1 : 1;
    }

    if (w1 != w2) {
      return w1 > w2 ? -1 : 1;
    }

    if (h1 != h2) {
      return h1 > h2 ? -1 : 1;
    }

    return 0;
  }
  
  protected static int comparePreviewSizes (int w1, int h1, int w2, int h2, long desiredSquare, float desiredRatio) {
    final long s1 = (long) w1 * h1;
    final long s2 = (long) w2 * h2;

    final long s1Diff = Math.abs(desiredSquare - s1);
    final long s2Diff = Math.abs(desiredSquare - s2);

    if (s1Diff != s2Diff) {
      return s1Diff < s2Diff ? -1 : 1;
    }

    if (s1 != s2) {
      return s1 > s2 ? -1 : 1;
    }

    final float r1 = (float) Math.max(w1, h1) / (float) Math.min(w1, h1);
    final float r2 = (float) Math.max(w2, h2) / (float) Math.min(w2, h2);

    final float r1Diff = Math.abs(desiredRatio - r1);
    final float r2Diff = Math.abs(desiredRatio - r2);

    if (r1Diff != r2Diff) {
      return r1Diff < r2Diff ? -1 : 1;
    }

    return 0;
  }

  // TextureView

  public void onSurfaceTextureAvailable (SurfaceTexture surface, int width, int height) {
    synchronized (this) {
      this.mSurface = surface;
      this.mSurfaceWidth = width;
      this.mSurfaceHeight = height;
    }
    checkCameraActive();
  }

  public void onSurfaceTextureSizeChanged (SurfaceTexture surface, int width, int height) {
    synchronized (this) {
      this.mSurfaceWidth = width;
      this.mSurfaceHeight = height;
    }
  }

  public void onSurfaceTextureDestroyed (SurfaceTexture surface) {
    synchronized (this) {
      this.mSurface = null;
      this.mSurfaceWidth = this.mSurfaceHeight = 0;
    }
    checkCameraActive();
  }

  // Thread

  public final boolean checkCameraThread () {
    return Thread.currentThread() == cameraThread;
  }

  public final void assertCameraThread () {
    if (Thread.currentThread() != cameraThread) {
      throw new RuntimeException();
    }
  }

  public final boolean checkBackgroundThread () {
    return Thread.currentThread() == backgroundThread;
  }

  public final void assertBackgroundThread () {
    if (Thread.currentThread() != backgroundThread) {
      throw new RuntimeException();
    }
  }

  protected void handleMessage (Message msg) { }
  protected void handleBackgroundMessage (Message msg) { }

  protected final void sendMessage (int what) {
    cameraThread.sendMessage(Message.obtain(cameraThread.getHandler(), what), 0);
  }

  protected final void sendMessage (int what, Object obj) {
    cameraThread.sendMessage(Message.obtain(cameraThread.getHandler(), what, obj), 0);
  }

  protected final void sendMessage (int what, int arg1, int arg2) {
    cameraThread.sendMessage(Message.obtain(cameraThread.getHandler(), what, arg1, arg2), 0);
  }

  protected final void sendMessage (int what, int arg1, int arg2, Object obj) {
    cameraThread.sendMessage(Message.obtain(cameraThread.getHandler(), what, arg1, arg2, obj), 0);
  }

  protected final boolean hasMessages (int what) {
    return cameraThread.getHandler().hasMessages(what);
  }

  public final void post (Runnable runnable, int delay) {
    cameraThread.post(runnable, delay);
  }

  private static class CameraThread extends BaseThread {
    private final CameraApi context;
    private final boolean isBackground;

    public CameraThread (CameraApi context, boolean isBackground) {
      super("CameraThread");
      this.context = context;
      this.isBackground = isBackground;
    }

    @Override
    protected void process (Message msg) {
      if (msg.what >= 0) {
        if (isBackground) {
          context.handleBackgroundMessage(msg);
        } else {
          context.handleMessage(msg);
        }
      } else {
        if (isBackground) {
          context.handleInternalBackgroundMessage(msg);
        } else {
          context.handleInternalMessage(msg);
        }
      }
    }
  }

  // COMMON

  private static final int ACTION_SET_PREVIEW_SIZE = -1;
  private static final int ACTION_SET_DISPLAY_ORIENTATION = -2;
  private static final int ACTION_SWITCH_TO_NEXT_CAMERA = -3;
  private static final int ACTION_TOGGLE_FLASH_MODE = -4;
  private static final int ACTION_CHANGE_CAMERA_STATE = -6;
  private static final int ACTION_SET_PREVIEW_REQUESTED = -7;
  private static final int ACTION_CHECK_CAMERA_ACTIVE = -8;
  private static final int ACTION_RESET_REQUESTED_SETTINGS = -9;
  private static final int ACTION_REQUEST_ZOOM = -10;
  private static final int ACTION_TAKE_PHOTO = -11;
  private static final int ACTION_FORCE_OUTPUT_ORIENTATION = -12;
  private static final int ACTION_CHANGE_CAPTURE_STATE = -13;
  private static final int ACTION_START_ROUND_CAPTURE = -14;
  private static final int ACTION_FINISH_ROUND_CAPTURE = -15;
  private static final int ACTION_OPEN_CAMERA_IF_NEEDED = -16;
  private static final int ACTION_DESTROY_ROUND_RENDERER = -17;
  private static final int ACTION_SET_TORCH = -18;
  private static final int ACTION_REQUEST_ZOOM_RATIO = -19;

  private void handleInternalMessage (Message msg) {
    switch (msg.what) {
      case ACTION_SET_PREVIEW_SIZE:
        setPreviewSize(msg.arg1, msg.arg2);
        break;
      case ACTION_SET_DISPLAY_ORIENTATION:
        setDisplayOrientation(msg.arg1);
        break;
      case ACTION_SWITCH_TO_NEXT_CAMERA:
        switchToNextCameraDevice();
        break;
      case ACTION_TOGGLE_FLASH_MODE:
        toggleFlashMode();
        break;
      case ACTION_CHANGE_CAMERA_STATE:
        setCameraActive(msg.arg1 == 1);
        break;
      case ACTION_SET_PREVIEW_REQUESTED:
        setCameraPreviewRequested(msg.arg1 == 1);
        break;
      case ACTION_CHECK_CAMERA_ACTIVE:
        checkCameraActive();
        break;
      case ACTION_RESET_REQUESTED_SETTINGS:
        resetRequestedSettings();
        break;
      case ACTION_REQUEST_ZOOM:
        requestZoom(Float.intBitsToFloat(msg.arg1));
        break;
      case ACTION_TAKE_PHOTO:
        takePhoto(msg.arg1, msg.arg2, (Integer) msg.obj);
        break;
      case ACTION_FORCE_OUTPUT_ORIENTATION:
        forceOutputOrientation(msg.arg1);
        break;
      case ACTION_CHANGE_CAPTURE_STATE:
        setVideoCapturing(msg.arg1 == 1);
        break;
      case ACTION_START_ROUND_CAPTURE: {
        Object[] data = (Object[]) msg.obj;
        startRoundVideoCapture((String) data[0], (RoundVideoRecorder.Delegate) data[1], (String) data[2]);
        data[0] = null;
        data[1] = null;
        data[2] = null;
        break;
      }
      case ACTION_FINISH_ROUND_CAPTURE:
        finishOrCancelRoundVideoCapture((String) msg.obj, msg.arg1 == 1);
        break;
      case ACTION_OPEN_CAMERA_IF_NEEDED:
        openCameraIfStillNeeded((RoundVideoRecorder) msg.obj);
        break;
      case ACTION_DESTROY_ROUND_RENDERER:
        destroyRoundRenderer();
        break;
      case ACTION_SET_TORCH:
        setTorchEnabled(msg.arg1 == 1);
        break;
      case ACTION_REQUEST_ZOOM_RATIO:
        requestZoomRatio(Float.intBitsToFloat(msg.arg1), Float.intBitsToFloat(msg.arg2));
        break;
    }
  }

  private void handleInternalBackgroundMessage (Message msg) {

  }

  @WorkerThread
  protected abstract boolean prepareCamera () throws Throwable;

  private boolean cameraOpened;

  @WorkerThread
  protected abstract boolean openCamera (SurfaceTexture texture) throws Throwable;

  public final void openCameraIfStillNeeded (RoundVideoRecorder recorder) {
    if (!checkCameraThread()) {
      sendMessage(ACTION_OPEN_CAMERA_IF_NEEDED, recorder);
      return;
    }
    if (this.roundRecorder == recorder && isCameraActive && !cameraOpened) {
      boolean opened = false;
      try {
        opened = openCamera(recorder.getCameraTexture());
      } catch (Throwable t) {
        Log.w(Log.TAG_CAMERA, "Cannot open camera lately", t);
      }
      if (opened) {
        cameraOpened = true;
      } else {
        setCameraActive(false);
        if (isCamera2()) {
          // Vibegram: the capture session could not be configured, continue with Camera1
          manager.switchToLegacyApi(this);
        }
      }
    }
  }

  @WorkerThread
  protected abstract int calculateDisplayOrientation ();
  @WorkerThread
  protected abstract int getCameraOutputWidth ();
  @WorkerThread
  protected abstract int getCameraOutputHeight ();
  @WorkerThread
  protected abstract int getSensorOrientation ();

  @WorkerThread
  protected abstract void closeCamera () throws Throwable;

  @WorkerThread
  protected final void setNumberOfCameras (int num) {
    if (mNumberOfCameras != num) {
      mNumberOfCameras = num;
      manager.setAvailableCameraCount(num);
    }
  }

  /**
   * Any thread. Closes the camera, destroys the round renderer (so another backend can draw
   * into the same TextureView), releases backend resources and quits both threads.
   * {@code onReleased} runs on the UI thread once all of that is done.
   */
  public final void release (@Nullable Runnable onReleased) {
    if (!checkCameraThread()) {
      post(() -> release(onReleased), 0);
      return;
    }
    cameraPreviewRequested = false;
    setCameraActive(false);
    destroyRoundRenderer();
    try {
      onRelease();
    } catch (Throwable t) {
      Log.w(Log.TAG_CAMERA, "Cannot release camera backend", t);
    }
    // The renderer is destroyed on the background thread: wait for it before reporting
    backgroundThread.post(() -> {
      if (onReleased != null) {
        UI.post(onReleased);
      }
      cameraThread.post(() -> cameraThread.quitLooper(true), 0);
      backgroundThread.quitLooper(true);
    }, 0);
  }

  /** Camera thread, the camera is already closed. Releases what the backend keeps across openings. */
  protected void onRelease () throws Throwable { }

  public final void destroyRoundRenderer () {
    if (!checkCameraThread()) {
      sendMessage(ACTION_DESTROY_ROUND_RENDERER);
      return;
    }
    if (roundRecorder != null) {
      roundRecorder.destroy();
      roundRecorder = null;
    }
  }

  @WorkerThread
  protected final void setCameraActive (boolean isActive) {
    if (this.isCameraActive != isActive) {
      boolean success = false;
      if (isActive) {
        try {
          success = prepareCamera();
        } catch (Throwable t) {
          Log.w(Log.TAG_CAMERA, "Cannot prepare camera", t);
        }
        if (success) {
          try {
            SurfaceTexture surface;
            synchronized (manager) {
              surface = mSurface;
            }
            if (surface == null) {
              throw new NullPointerException();
            }
            if (manager.useRoundRender()) {
              if (roundRecorder == null) {
                roundRecorder = new RoundVideoRecorder(this, surface, mSurfaceWidth, mSurfaceHeight);
                roundRecorder.setPreviewSizes(getCameraOutputWidth(), getCameraOutputHeight(), calculateDisplayOrientation());
                roundRecorder.openCamera();
              } else {
                roundRecorder.setPreviewSizes(getCameraOutputWidth(), getCameraOutputHeight(), calculateDisplayOrientation());
                roundRecorder.switchToNewCamera();
              }
            } else {
              success = openCamera(surface);
              cameraOpened = success;
            }
          } catch (Throwable t) {
            Log.w(Log.TAG_CAMERA, "Cannot open camera", t);
            success = false;
          }
        }
      }
      if (!isActive || !success) {
        try { setVideoCapturing(false); } catch (Throwable ignored) { }
        try {
          closeCamera();
        } catch (Throwable t) {
          Log.w(Log.TAG_CAMERA, "Cannot close camera, but we ignore it.", t);
        }
        if (roundRecorder != null) {
          roundRecorder.onCameraClose();
        }
        cameraOpened = false;
        success = !isActive;
      }
      if (success) {
        this.isCameraActive = isActive;
      } else if (isActive) {
        manager.switchToLegacyApi(this);
      }
    }
  }

  // Round render

  private RoundVideoRecorder roundRecorder;

  public final void startRoundVideoCapture (String key, RoundVideoRecorder.Delegate delegate, String outputPath) {
    if (!checkCameraThread()) {
      sendMessage(ACTION_START_ROUND_CAPTURE, new Object[] {key, delegate, outputPath});
      return;
    }

    if (roundRecorder != null) {
      roundRecorder.startCapture(delegate, key, outputPath);
    } else {
      // Vibegram: the renderer is gone (camera failed or the backend is being replaced)
      Log.w(Log.TAG_ROUND, "startRoundVideoCapture without a renderer, key: %s", key);
      manager.onRoundCaptureNotStarted(this, key);
    }
  }

  public final void finishOrCancelRoundVideoCapture (String key, boolean saveResult) {
    if (!checkCameraThread()) {
      sendMessage(ACTION_FINISH_ROUND_CAPTURE, saveResult ? 1 : 0, 0, key);
      return;
    }

    if (roundRecorder != null) {
      roundRecorder.finishCapture(key, saveResult);
    } else {
      Log.w(Log.TAG_ROUND, "finishOrCancelRoundVideoCapture without a renderer, key: %s", key);
      manager.onRoundCaptureNotFinished(this, key);
    }
  }
}
