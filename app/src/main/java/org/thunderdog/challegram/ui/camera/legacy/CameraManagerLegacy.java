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

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.SurfaceTexture;
import android.hardware.Camera;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.widget.Toast;

import androidx.annotation.AnyThread;
import androidx.annotation.Nullable;
import androidx.annotation.UiThread;

import org.thunderdog.challegram.Log;
import org.thunderdog.challegram.R;
import org.thunderdog.challegram.loader.ImageGalleryFile;
import org.thunderdog.challegram.player.RoundVideoRecorder;
import org.thunderdog.challegram.tool.UI;
import org.thunderdog.challegram.ui.camera.CameraDelegate;
import org.thunderdog.challegram.ui.camera.CameraError;
import org.thunderdog.challegram.ui.camera.CameraManagerTexture;
import org.thunderdog.challegram.ui.camera.CameraQrBridge;
import org.thunderdog.challegram.ui.camera.CameraTextureView;
import org.thunderdog.challegram.unsorted.Settings;

import java.util.concurrent.TimeUnit;

import me.vkryl.core.MathUtils;

public class CameraManagerLegacy extends CameraManagerTexture {

  private volatile CameraApi api;
  private CameraQrBridge cameraQrBridge;

  private boolean useRoundRender;

  private volatile int availableCameraCount = -1;

  public CameraManagerLegacy (Context context, CameraDelegate delegate) {
    super(context, delegate);
    api = new CameraApiLegacy(context, this);
    api.setDisplayOrientation(getDisplayRotation());

    if (delegate.useQrScanner() && cameraQrBridge == null) {
      cameraQrBridge = new CameraQrBridge(this);
    }
  }

  public void onPreviewFrame (byte[] data, Camera camera) {
    if (cameraQrBridge != null && api instanceof CameraApiLegacy && api.isCameraActive) {
      try {
        Camera.Size previewSize = camera.getParameters().getPreviewSize();
        cameraQrBridge.processImage(data, previewSize.width, previewSize.height, (CameraApiLegacy) api);
      } catch (Exception ignored) {
      }
    }
  }

  @Override
  protected void onDisplayRotationChanged (int rotation) {
    if (api != null)
      api.setDisplayOrientation(rotation);
  }

  @Override
  @UiThread
  public void switchCamera () {
    switchToNextCameraDevice();
  }

  @Override
  protected boolean getBitmap (Bitmap bitmap) {
    try {
      if (cameraView.isAvailable()) {
        cameraView.getBitmap(bitmap);
        return true;
      }
    } catch (Throwable t) {
      Log.e(Log.TAG_CAMERA, "Unable to take camera preview", t);
    }
    return false;
  }

  @Override
  @UiThread
  public void switchFlashMode () {
    api.toggleFlashMode();
  }

  private volatile boolean torchEnabled;

  /** Steady light for round videos (rear cameras with a torch). */
  public void setTorchEnabled (boolean enabled) {
    this.torchEnabled = enabled;
    api.setTorchEnabled(enabled);
  }

  // Vibegram: zoom ratio for round videos (see CameraApi)

  public interface ZoomRatioListener {
    /** Any thread. The open camera changed: new range and lens stops for the zoom buttons. */
    void onZoomCapabilitiesChanged (float minRatio, float maxRatio, float[] stops, float currentRatio);
    /** Any thread. The ratio has been applied to the camera. */
    default void onZoomRatioChanged (float ratio) { }
  }

  private volatile @Nullable ZoomRatioListener zoomRatioListener;

  public void setZoomRatioListener (@Nullable ZoomRatioListener listener) {
    this.zoomRatioListener = listener;
    if (listener != null) {
      listener.onZoomCapabilitiesChanged(api.getMinZoomRatio(), api.getMaxZoomRatio(), api.getZoomStops(), api.getZoomRatio());
    }
  }

  public void requestZoomRatio (float ratio) {
    api.requestZoomRatio(ratio);
  }

  public float getZoomRatio () {
    return api.getZoomRatio();
  }

  public float getMinZoomRatio () {
    return api.getMinZoomRatio();
  }

  public float getMaxZoomRatio () {
    return api.getMaxZoomRatio();
  }

  public float[] getZoomStops () {
    return api.getZoomStops();
  }

  public boolean isUsingCamera2 () {
    return api.isCamera2();
  }

  // Vibegram: what the round video recorder needs from the camera stream.
  // Set by RecordAudioVideoController before the camera opens; the backend picks the smallest
  // preview whose short side is >= minSide and the fps range closest to fps (never above it),
  // then reports what it really got with onRoundCaptureConfigured().

  private volatile int roundCaptureMinSide, roundCaptureFps = 30;
  private volatile int roundActualPreviewWidth, roundActualPreviewHeight, roundActualMaxFps;

  public void setRoundCaptureRequest (int minSide, int fps) {
    this.roundCaptureMinSide = minSide;
    this.roundCaptureFps = fps;
  }

  public int getRoundCaptureMinSide () {
    return roundCaptureMinSide;
  }

  public int getRoundCaptureFps () {
    return roundCaptureFps;
  }

  /** Camera thread. The camera stream for a round video is configured: preview size and the max fps of the chosen range. */
  @AnyThread
  void onRoundCaptureConfigured (int previewWidth, int previewHeight, int maxFps) {
    this.roundActualPreviewWidth = previewWidth;
    this.roundActualPreviewHeight = previewHeight;
    this.roundActualMaxFps = maxFps;
  }

  /** 0 when unknown. */
  public int getRoundActualMaxFps () {
    return roundActualMaxFps;
  }

  public int getRoundActualPreviewWidth () {
    return roundActualPreviewWidth;
  }

  public int getRoundActualPreviewHeight () {
    return roundActualPreviewHeight;
  }

  @AnyThread
  void onZoomCapabilitiesChanged (float minRatio, float maxRatio, float[] stops, float currentRatio) {
    ZoomRatioListener listener = zoomRatioListener;
    if (listener != null) {
      listener.onZoomCapabilitiesChanged(minRatio, maxRatio, stops, currentRatio);
    }
  }

  @AnyThread
  void onZoomRatioChanged (float ratio) {
    ZoomRatioListener listener = zoomRatioListener;
    if (listener != null) {
      listener.onZoomRatioChanged(ratio);
    }
  }

  @Override
  @UiThread
  protected void onRequestZoom (float zoom) {
    api.requestZoom(MathUtils.clamp(zoom, getMinZoom(), getMaxZoom()));
  }

  @Override
  public float getCurrentZoom () {
    return api.getZoom();
  }

  @Override
  public float getMinZoomStep () {
    return 1;
  }

  @Override
  @UiThread
  public void onResetPreferences () {
    api.resetRequestedSettings();
  }

  // API

  public boolean useRoundRender () {
    synchronized (this) {
      return useRoundRender;
    }
  }

  public void setUseRoundRender (boolean useRoundRender) {
    boolean changed = false;
    synchronized (this) {
      if (this.useRoundRender != useRoundRender) {
        this.useRoundRender = useRoundRender;
        changed = true;
        if (!useRoundRender) {
          api.destroyRoundRenderer();
        }
      }
    }
    if (changed) {
      if (!useRoundRender) {
        // The next round video gives Camera2 another chance, unless the failure was remembered
        camera2DisabledForSession = false;
      }
      if (UI.inUiThread()) {
        checkApiBackend();
      } else {
        UI.post(this::checkApiBackend);
      }
    }
  }

  // Vibegram: backend selection. Camera2 only for round videos (Android 6+, not a LEGACY HAL,
  // allowed by the settings and not failed on this device); the main camera and QR scanning keep Camera1.

  private boolean apiSwitching, apiCheckPending, isDestroyed;
  private boolean camera2DisabledForSession;
  private static boolean camera2FailureToastShown;
  private int lastPreviewWidth, lastPreviewHeight;

  private boolean needCamera2 () {
    return Build.VERSION.SDK_INT >= Build.VERSION_CODES.M &&
      useRoundRender() &&
      cameraQrBridge == null &&
      !camera2DisabledForSession &&
      Settings.instance().useRoundCamera2() &&
      CameraApi2.isSupported(context);
  }

  private CameraApi createApi (boolean camera2) {
    if (camera2 && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
      try {
        return new CameraApi2(context, this);
      } catch (Throwable t) {
        Log.e(Log.TAG_CAMERA, "Cannot create Camera2 backend", t);
      }
    }
    return new CameraApiLegacy(context, this);
  }

  /** UI thread. Replaces the backend when the wanted one differs; waits while the camera is open. */
  @UiThread
  private void checkApiBackend () {
    if (isDestroyed) {
      return;
    }
    if (apiSwitching) {
      apiCheckPending = true;
      return;
    }
    boolean needCamera2 = needCamera2();
    if (api.isCamera2() == needCamera2) {
      apiCheckPending = false;
      return;
    }
    if (__isCameraActive) {
      // Never swap under an open camera: done in checkCameraState() once it closes
      apiCheckPending = true;
      return;
    }
    apiCheckPending = false;
    replaceApi(needCamera2, false);
  }

  /**
   * UI thread. Closes the current backend completely (camera, round renderer, threads),
   * then creates the new one, gives it the surface, preview size, rotation and torch state
   * and reopens the camera if it was open. A round capture that has not produced a frame yet
   * is restarted on the new backend.
   */
  @UiThread
  private void replaceApi (boolean toCamera2, boolean isFallback) {
    final CameraApi oldApi = api;
    Log.i(Log.TAG_CAMERA, "Replacing camera backend, camera2: %b -> %b, fallback: %b", oldApi.isCamera2(), toCamera2, isFallback);
    final int facing = oldApi.getCurrentFacing();
    apiSwitching = true;
    checkCameraState(); // closes the preview of the old backend
    oldApi.release(() -> {
      apiSwitching = false;
      if (isDestroyed) {
        return;
      }
      if (isFallback && facing != CameraApi.FACING_UNKNOWN) {
        // Continue with the camera that was in use
        setPreferFrontFacingCamera(facing == CameraApi.FACING_FRONT);
      }
      CameraApi newApi = createApi(toCamera2 && needCamera2());
      newApi.setDisplayOrientation(getDisplayRotation());
      if (lastPreviewWidth > 0 && lastPreviewHeight > 0) {
        newApi.setPreviewSize(lastPreviewWidth, lastPreviewHeight);
      }
      if (torchEnabled) {
        newApi.setTorchEnabled(true);
      }
      api = newApi;
      if (destroyedCamera == oldApi) {
        destroyedCamera = null;
      }
      SurfaceTexture surfaceTexture = getSurfaceTexture();
      if (surfaceTexture != null) {
        newApi.onSurfaceTextureAvailable(surfaceTexture, getTextureWidth(), getTextureHeight());
      }
      checkCameraState();
      RoundCaptureRequest request = activeRoundCapture;
      if (isFallback && request != null && !request.started && !request.finished) {
        Log.i(Log.TAG_ROUND, "Restarting round capture on the new camera backend, key: %s", request.key);
        request.issuedTo = newApi;
        newApi.startRoundVideoCapture(request.key, request, request.outputPath);
      }
      if (apiCheckPending) {
        apiCheckPending = false;
        checkApiBackend();
      }
    });
  }

  @Override
  protected void onTakePhoto (int trimWidth, int trimHeight, int outRotation) {
    api.takePhoto(trimWidth, trimHeight, outRotation);
  }

  @Override
  public boolean onStartVideoCapture (int orientation) {
    Log.i(Log.TAG_CAMERA, "requestVideoCapture, orientation: %d", orientation);
    if (api.isVideoRecordSupported()) {
      api.startVideoCapture(orientation);
      return true;
    }
    return false;
  }

  @Override
  protected void onFinishOrCancelVideoCapture () {
    api.finishOrCancelVideoCapture();
  }

  /**
   * Async request to start round video capturin.
   * */
  public boolean requestRoundVideoCapture (String key, RoundVideoRecorder.Delegate delegate, String outputPath) {
    Log.i(Log.TAG_CAMERA, "requestRoundCapture, key: %s", key);
    if (api.isVideoRecordSupported()) {
      // Vibegram: remembered, so it survives a backend replacement (Camera2 -> Camera1 fallback)
      RoundCaptureRequest request = new RoundCaptureRequest(key, delegate, outputPath);
      activeRoundCapture = lastRoundCapture = request;
      if (!apiSwitching) {
        request.issuedTo = api;
        api.startRoundVideoCapture(key, request, outputPath);
      }
      return true;
    }
    return false;
  }

  // UI thread. active: requested and not finished or cancelled yet; last: kept for late "no renderer" reports
  private RoundCaptureRequest activeRoundCapture, lastRoundCapture;

  /** Wraps the delegate to know whether the recorder has already started writing the file. */
  private final class RoundCaptureRequest implements RoundVideoRecorder.Delegate {
    final String key, outputPath;
    final RoundVideoRecorder.Delegate delegate;
    boolean started, finished;
    CameraApi issuedTo;

    RoundCaptureRequest (String key, RoundVideoRecorder.Delegate delegate, String outputPath) {
      this.key = key;
      this.delegate = delegate;
      this.outputPath = outputPath;
    }

    @Override
    public void onVideoRecordingStarted (String key, long startTimeMs) {
      started = true;
      delegate.onVideoRecordingStarted(key, startTimeMs);
    }

    @Override
    public void onVideoRecordProgress (String key, long readyBytesCount) {
      delegate.onVideoRecordProgress(key, readyBytesCount);
    }

    @Override
    public void onVideoRecordingFinished (String key, long resultFileSize, long resultDuration, TimeUnit resultDurationUnit) {
      if (activeRoundCapture == this) {
        activeRoundCapture = null;
      }
      if (!finished) {
        finished = true;
        delegate.onVideoRecordingFinished(key, resultFileSize, resultDuration, resultDurationUnit);
      }
    }

    void dispatchNothingRecorded () {
      onVideoRecordingFinished(key, -1, -1, TimeUnit.MILLISECONDS);
    }
  }

  /** Any thread. {@code fromApi} had no renderer for a capture start request. */
  void onRoundCaptureNotStarted (CameraApi fromApi, String key) {
    UI.post(() -> {
      RoundCaptureRequest request = activeRoundCapture;
      if (request == null || !request.key.equals(key) || request.started || apiSwitching) {
        // While switching, the new backend gets the request once it's ready
        return;
      }
      if (fromApi != api) {
        if (request.issuedTo != api) {
          request.issuedTo = api;
          api.startRoundVideoCapture(request.key, request, request.outputPath);
        }
      } else {
        request.dispatchNothingRecorded();
      }
    });
  }

  /** Any thread. {@code fromApi} had no renderer for a capture finish request. */
  void onRoundCaptureNotFinished (CameraApi fromApi, String key) {
    UI.post(() -> {
      RoundCaptureRequest request = lastRoundCapture;
      if (request != null && request.key.equals(key) && !request.started) {
        request.dispatchNothingRecorded();
      }
    });
  }

  /**
   * Async request to stop round video capture.
   *
   * Calling this method after {@link #requestRoundVideoCapture} returned {@code false}
   * is undefined behavior
   * */
  public void finishOrCancelRoundVideoCapture (String key, boolean saveResult) {
    RoundCaptureRequest request = activeRoundCapture;
    boolean isActive = request != null && request.key.equals(key);
    if (isActive) {
      // Never restarted on another backend after this point
      activeRoundCapture = null;
    }
    if (apiSwitching && isActive) {
      if (!request.started) {
        // Nothing has been written yet: report it like the recorder would
        UI.post(request::dispatchNothingRecorded);
      }
      // Otherwise the destroyed recorder reports the result itself
      return;
    }
    api.finishOrCancelRoundVideoCapture(key, saveResult);
  }

  @UiThread
  public void switchToNextCameraDevice () {
    if (availableCameraCount != -1) {
      api.switchToNextCameraDevice();
    }
  }

  private boolean isCameraOpen, isCameraPaused;
  private CameraApi destroyedCamera;

  public void openCamera () {
    isCameraOpen = true;
    checkCameraState();
  }

  public void closeCamera () {
    isCameraOpen = false;
    checkCameraState();
  }

  public void pauseCamera () {
    isCameraPaused = true;
    checkCameraState();
  }

  public void resumeCamera () {
    isCameraPaused = false;
    checkCameraState();
  }

  public void destroy () {
    if (cameraQrBridge != null) {
      cameraQrBridge.destroy();
      cameraQrBridge = null;
    }

    isDestroyed = true;
    destroyedCamera = api;
    checkCameraState();
    // Vibegram: also quits the camera threads, they used to leak with every manager
    api.release(null);
  }

  // Internal

  private volatile boolean __isCameraActive;

  @Override
  public boolean isCameraActive () {
    return __isCameraActive;
  }

  public void checkCameraState () {
    boolean isCameraActive = isCameraOpen && !isCameraPaused && api != destroyedCamera && !apiSwitching && !isDestroyed;
    if (__isCameraActive != isCameraActive) {
      boolean success;
      boolean needSwitchToLegacyApi = false;
      if (isCameraActive) {
        int state = openCameraImpl();
        success = state == STATE_OK;
        needSwitchToLegacyApi = state == STATE_SWITCH_TO_LEGACY_API;
      } else {
        success = closeCameraImpl();
      }
      if (success) {
        __isCameraActive = isCameraActive;
        Log.i(Log.TAG_CAMERA, "isCameraActive -> %b", isCameraActive);
      } else {
        Log.i(Log.TAG_CAMERA, "isCameraActive -> %b failed", isCameraActive);
        if (needSwitchToLegacyApi) {
          switchToLegacyApi();
        }
      }
      if (!__isCameraActive && apiCheckPending && !apiSwitching && UI.inUiThread()) {
        checkApiBackend();
      }
    }
  }

  private static final int STATE_OK = 0;
  private static final int STATE_FAILED = 1;
  private static final int STATE_SWITCH_TO_LEGACY_API = 2;

  private int openCameraImpl () {
    int state = STATE_FAILED;
    try {
      if (api.openPreview()) {
        state = STATE_OK;
      }
    } catch (Throwable t) {
      Log.w(Log.TAG_CAMERA, "Cannot open preview", t);
      if (api.isCamera2()) {
        state = STATE_SWITCH_TO_LEGACY_API;
      } else {
        showFatalError(t);
      }
    }
    return state;
  }

  private boolean closeCameraImpl () {
    try {
      return api.closePreview();
    } catch (Throwable t) {
      Log.w(Log.TAG_CAMERA, "Cannot close preview", t);
    }
    return false;
  }

  // Semi-internal API

  public int getCurrentCameraRotation () {
    return api.calculateDisplayOrientation();
  }

  public int getCurrentCameraSensorOrientation () {
    return api.getSensorOrientation();
  }

  public void showFatalError (String error) {
    delegate.displayFatalErrorMessage(error);
  }

  public void showFatalError (Throwable error) {
    delegate.displayFatalErrorMessage(Log.toString(error));
  }

  public void resolveExpectedError (@CameraError.Code int code) {
    delegate.resolveExpectedError(code);
  }

  public void setAvailableCameraCount (int cameraCount) {
    synchronized (this) {
      if (this.availableCameraCount != cameraCount) {
        this.availableCameraCount = cameraCount;
        delegate.onAvailableCamerasCountChanged(cameraCount);
      }
    }
  }

  public int getAvailableCameraCount () {
    synchronized (this) {
      return availableCameraCount;
    }
  }

  // UI handler

  private void sendMessage (int what) {
    handler.sendMessage(Message.obtain(handler, what));
  }

  private void sendMessage (int what, Object obj) {
    handler.sendMessage(Message.obtain(handler, what, obj));
  }

  private void sendMessage (int what, int arg1, int arg2, Object obj) {
    handler.sendMessage(Message.obtain(handler, what, arg1, arg2, obj));
  }

  private final MainHandler handler = new MainHandler(this);
  private static final int ACTION_SWITCH_TO_LEGACY_API = 0;
  private static final int ACTION_DISPATCH_MEDIA_RESULT = 1;

  private void processMessage (Message msg) {
    switch (msg.what) {
      case ACTION_SWITCH_TO_LEGACY_API:
        if (msg.obj != null) {
          switchToLegacyApi((CameraApi) msg.obj);
        } else {
          switchToLegacyApi();
        }
        break;
      case ACTION_DISPATCH_MEDIA_RESULT:
        if (msg.obj != null) {
          onTakeMediaResult((ImageGalleryFile) msg.obj, msg.arg1 == 1);
        } else {
          onTakeMediaError(msg.arg1 == 1);
        }
        break;
    }
  }

  private static class MainHandler extends Handler {
    private final CameraManagerLegacy manager;

    public MainHandler (CameraManagerLegacy manager) {

      super(Looper.getMainLooper());
      this.manager = manager;
    }

    @Override
    public void handleMessage (Message msg) {
      manager.processMessage(msg);
    }
  }

  // Texture listeners

  @Override
  public void onTextureAvailable (SurfaceTexture surface, int width, int height) {
    api.onSurfaceTextureAvailable(surface, width, height);
  }

  @Override
  public void onTextureSizeChanged (SurfaceTexture surface, int width, int height) {
    api.onSurfaceTextureSizeChanged(surface, width, height);
  }

  @Override
  public void onTextureDestroyed (SurfaceTexture surface) {
    api.onSurfaceTextureDestroyed(surface);
  }

  private boolean renderedFirstFrame;

  @Override
  public void setPreviewSize (int viewWidth, int viewHeight) {
    this.lastPreviewWidth = viewWidth;
    this.lastPreviewHeight = viewHeight;
    api.setPreviewSize(viewWidth, viewHeight);
  }

  // Called by Api

  @AnyThread
  void onPerformSuccessHint (boolean isVideo) {
    delegate.onPerformSuccessHint(isVideo);
  }

  @AnyThread
  public void onCameraSourceChange (boolean completed, boolean isForward, boolean toFrontFace) {
    delegate.onCameraSourceChange(completed, isForward, toFrontFace);
  }

  @AnyThread
  public void onFlashModeChanged (int mode) {
    delegate.onFlashModeChanged(mode);
  }

  @Override
  public float getMaxZoom () {
    return api.getMaxZoom();
  }

  @Override
  public float getMinZoom () {
    return 0f;
  }

  /**
   * Called when first camera frame has been rendered.
   *
   * Good place to hide fake overlays.
   * */
  @AnyThread
  public void onRenderedFirstFrame () {
    if (!renderedFirstFrame && __isCameraActive) {
      Log.i(Log.TAG_CAMERA, "onRenderedFirstFrame");
      renderedFirstFrame = true;
      delegate.onRenderedFirstFrame();
    }
  }

  /**
   * Called when {@link CameraTextureView} has been abandoned by {@link CameraApi}
   *
   * Show fake overlays at this point.
   * */
  @AnyThread
  public void resetRenderState (boolean needBlurPreview) {
    if (renderedFirstFrame) {
      Log.i(Log.TAG_CAMERA, "resetRenderState");
      renderedFirstFrame = false;
      delegate.onResetRenderState(needBlurPreview, null);
    }
  }

  /**
   * Called when camera preview size became known.
   * */
  @AnyThread
  public void setAspectRatio (int width, int height) {
    UI.post(() -> cameraView.setAspectRatio(width, height));
  }

  /**
   * Called when Camera2 API has failed due to unknown error.
   *
   * Seamlessly switch to CameraApiLegacy & remember that until SDK_INT is changed,
   * (obviously meaning that Android has been updated and camera2 API might has become available)
   * */
  public void switchToLegacyApi () {
    switchToLegacyApiImpl(null);
  }

  /** Same as {@link #switchToLegacyApi()}, ignored when {@code fromApi} is no longer the current backend. */
  public void switchToLegacyApi (CameraApi fromApi) {
    switchToLegacyApiImpl(fromApi);
  }

  private void switchToLegacyApiImpl (CameraApi fromApi) {
    if (!UI.inUiThread()) {
      sendMessage(ACTION_SWITCH_TO_LEGACY_API, fromApi);
      return;
    }

    if (isDestroyed || (fromApi != null && fromApi != api)) {
      return;
    }

    if (!api.isCamera2()) {
      Log.e(Log.TAG_CAMERA, "Trying to switch to legacy API, when already using legacy API");
      return;
    }

    // Camera1 for the rest of this round video, whatever the reason
    camera2DisabledForSession = true;
    if (apiSwitching) {
      // The pending replacement re-checks needCamera2() and picks Camera1
      return;
    }

    Log.w(Log.TAG_CAMERA, "Switching to legacy API.");
    CameraApi failedApi = api;
    if (failedApi instanceof CameraApi2 && ((CameraApi2) failedApi).shouldRememberFailure()) {
      // Vibegram: remembered until the system updates or the user picks Camera2 again
      Settings.instance().setRoundCamera2Failed();
      if (!camera2FailureToastShown) {
        camera2FailureToastShown = true;
        UI.showToast(R.string.vg_RoundCamera2Failed, Toast.LENGTH_LONG);
      }
    }
    replaceApi(false, true);
  }
}
