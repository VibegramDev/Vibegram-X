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
package org.thunderdog.challegram.ui.camera.legacy;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Rect;
import android.graphics.SurfaceTexture;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CameraMetadata;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.TotalCaptureResult;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Range;
import android.util.Size;
import android.util.SizeF;
import android.view.Surface;

import androidx.annotation.NonNull;
import androidx.annotation.RequiresApi;

import org.thunderdog.challegram.Log;
import org.thunderdog.challegram.U;
import org.thunderdog.challegram.loader.ImageGalleryFile;
import org.thunderdog.challegram.ui.camera.CameraFeatures;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import me.vkryl.core.lambda.RunnableData;

/**
 * Vibegram: Camera2 backend, used only for round videos.
 *
 * Mirrors {@link CameraApiLegacy}: {@link #prepareCamera()} opens the device,
 * {@link #openCamera(SurfaceTexture)} starts the repeating request into the texture of
 * {@link org.thunderdog.challegram.player.RoundVideoRecorder}. Camera2 is asynchronous, so the camera thread
 * waits for the callbacks (delivered on a separate thread) with a timeout. Any failure ends up in
 * {@link CameraManagerLegacy#switchToLegacyApi(CameraApi)}, which continues with Camera1.
 */
@RequiresApi(Build.VERSION_CODES.M)
public class CameraApi2 extends CameraApi {
  private static final long OPEN_TIMEOUT_MS = 2500;
  private static final long CONFIGURE_TIMEOUT_MS = 2500;
  private static final long FIRST_FRAME_TIMEOUT_MS = 2500;
  private static final long CLOSE_TIMEOUT_MS = 1000;
  /** Steps of the legacy index zoom (volume keys), mapped linearly onto 1..max ratio. */
  private static final int ZOOM_INDEX_STEPS = 60;

  private final CameraManager cameraManager;

  public CameraApi2 (Context context, CameraManagerLegacy manager) {
    super(context, manager);
    this.cameraManager = (CameraManager) context.getSystemService(Context.CAMERA_SERVICE);
    if (cameraManager == null) {
      throw new IllegalStateException("No CameraManager");
    }
  }

  @Override
  public boolean isCamera2 () {
    return true;
  }

  // === Support check ===

  private static volatile Boolean isSupported;

  /**
   * Whether Camera2 is worth using: Android 6+ and both cameras that would be used for round videos
   * report a hardware level above LEGACY (LEGACY is a Camera1 HAL behind a Camera2 wrapper).
   */
  public static boolean isSupported (Context context) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
      return false;
    }
    Boolean result = isSupported;
    if (result == null) {
      boolean supported = false;
      try {
        CameraManager cameraManager = (CameraManager) context.getSystemService(Context.CAMERA_SERVICE);
        if (cameraManager != null) {
          CameraIds ids = findCameraIds(cameraManager);
          supported = ids.count() > 0;
          for (String id : ids.asList(false)) {
            Integer level = cameraManager.getCameraCharacteristics(id).get(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL);
            if (level == null || level == CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_LEGACY) {
              supported = false;
              break;
            }
          }
          Log.i(Log.TAG_CAMERA, "Camera2 for round videos: %b, back: %s, front: %s", supported, ids.backId, ids.frontId);
        }
      } catch (Throwable t) {
        Log.w(Log.TAG_CAMERA, "Cannot check Camera2 support", t);
        supported = false;
      }
      isSupported = result = supported;
    }
    return result;
  }

  private static final class CameraIds {
    String backId, frontId;

    int count () {
      return (backId != null ? 1 : 0) + (frontId != null ? 1 : 0);
    }

    List<String> asList (boolean preferFront) {
      List<String> list = new ArrayList<>(2);
      String first = preferFront ? frontId : backId;
      String second = preferFront ? backId : frontId;
      if (first != null) {
        list.add(first);
      }
      if (second != null) {
        list.add(second);
      }
      return list;
    }
  }

  /**
   * The first front camera and the back camera: a logical multi-camera when there is one
   * (it switches between the ultra-wide, main and telephoto lenses by zoom ratio), else the first back camera.
   * Depth-only cameras are skipped.
   */
  private static CameraIds findCameraIds (CameraManager cameraManager) throws CameraAccessException {
    CameraIds ids = new CameraIds();
    String logicalBackId = null;
    for (String id : cameraManager.getCameraIdList()) {
      CameraCharacteristics c;
      try {
        c = cameraManager.getCameraCharacteristics(id);
      } catch (Throwable t) {
        Log.w(Log.TAG_CAMERA, "Cannot get characteristics of camera %s", t, id);
        continue;
      }
      int[] capabilities = c.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES);
      if (!contains(capabilities, CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_BACKWARD_COMPATIBLE)) {
        continue;
      }
      Integer facing = c.get(CameraCharacteristics.LENS_FACING);
      if (facing == null) {
        continue;
      }
      if (facing == CameraMetadata.LENS_FACING_FRONT) {
        if (ids.frontId == null) {
          ids.frontId = id;
        }
      } else if (facing == CameraMetadata.LENS_FACING_BACK) {
        if (ids.backId == null) {
          ids.backId = id;
        }
        if (logicalBackId == null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P &&
          contains(capabilities, CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_LOGICAL_MULTI_CAMERA)) {
          logicalBackId = id;
        }
      }
    }
    if (logicalBackId != null) {
      ids.backId = logicalBackId;
    }
    return ids;
  }

  private static boolean contains (int[] array, int value) {
    if (array != null) {
      for (int item : array) {
        if (item == value) {
          return true;
        }
      }
    }
    return false;
  }

  // === Failures ===

  private volatile boolean rememberFailure;
  private boolean failureReported;

  /** Whether the last failure is a device problem (remembered in settings) rather than a busy or forbidden camera. */
  public boolean shouldRememberFailure () {
    return rememberFailure;
  }

  private static boolean isDeviceFailure (Throwable t) {
    if (t instanceof SecurityException) {
      return false;
    }
    if (t instanceof CameraAccessException) {
      switch (((CameraAccessException) t).getReason()) {
        case CameraAccessException.CAMERA_IN_USE:
        case CameraAccessException.MAX_CAMERAS_IN_USE:
        case CameraAccessException.CAMERA_DISABLED:
        case CameraAccessException.CAMERA_DISCONNECTED:
          return false;
      }
    }
    if (t instanceof Camera2Failure) {
      return ((Camera2Failure) t).isDeviceFailure;
    }
    return true;
  }

  private static final class Camera2Failure extends Exception {
    final boolean isDeviceFailure;

    Camera2Failure (String message, boolean isDeviceFailure) {
      super(message);
      this.isDeviceFailure = isDeviceFailure;
    }
  }

  /** Camera thread. Gives up on Camera2 for this round video: the manager replaces this backend with Camera1. */
  private void reportFailure (String reason, boolean isDeviceFailure) {
    if (failureReported) {
      return;
    }
    failureReported = true;
    rememberFailure = isDeviceFailure;
    Log.e(Log.TAG_CAMERA, "Camera2 failed: %s, device failure: %b", reason, isDeviceFailure);
    manager.switchToLegacyApi(this);
  }

  // === Callback thread ===

  private HandlerThread callbackThread;
  private Handler callbackHandler;

  private Handler getCallbackHandler () {
    if (callbackHandler == null) {
      callbackThread = new HandlerThread("Camera2Callbacks");
      callbackThread.start();
      callbackHandler = new Handler(callbackThread.getLooper());
    }
    return callbackHandler;
  }

  @Override
  protected void onRelease () throws Throwable {
    if (callbackThread != null) {
      callbackThread.quitSafely();
      callbackThread = null;
      callbackHandler = null;
    }
  }

  // === Camera state ===

  private CameraIds cameraIds;
  private String cameraId;
  private CameraCharacteristics characteristics;
  private boolean isFrontFacing;
  private int sensorOrientation;
  private DeviceCallback deviceCallback;
  private CameraDevice device;
  private CameraCaptureSession session;
  private Surface outputSurface;
  private CaptureRequest.Builder requestBuilder;
  private int sessionGeneration;
  private volatile boolean firstFrameReceived;

  private Size previewSize;
  private Size lockedRoundSize; // the size of the first camera of the round renderer
  private Range<Integer> fpsRange;
  private int stabilizationMode = -1, opticalStabilizationMode = -1;
  private boolean hasFlash;
  private boolean useZoomRatioControl;
  private Rect activeArraySize;

  private static final class DeviceCallback extends CameraDevice.StateCallback {
    final CountDownLatch openLatch = new CountDownLatch(1);
    final CountDownLatch closeLatch = new CountDownLatch(1);
    final CameraApi2 api;
    CameraDevice openedDevice;
    int error = -1;
    boolean disconnected, abandoned, isOpened;

    DeviceCallback (CameraApi2 api) {
      this.api = api;
    }

    @Override
    public void onOpened (@NonNull CameraDevice camera) {
      boolean close;
      synchronized (this) {
        close = abandoned;
        if (!close) {
          openedDevice = camera;
          isOpened = true;
        }
      }
      if (close) {
        // Opened after the timeout, nobody waits for it anymore
        camera.close();
      }
      openLatch.countDown();
    }

    @Override
    public void onDisconnected (@NonNull CameraDevice camera) {
      boolean wasOpened;
      synchronized (this) {
        disconnected = true;
        wasOpened = isOpened;
      }
      camera.close();
      openLatch.countDown();
      if (wasOpened) {
        api.post(() -> api.onDeviceLost(this, "disconnected", false), 0);
      }
    }

    @Override
    public void onError (@NonNull CameraDevice camera, int error) {
      boolean wasOpened;
      synchronized (this) {
        this.error = error;
        wasOpened = isOpened;
      }
      camera.close();
      openLatch.countDown();
      if (wasOpened) {
        boolean isDeviceFailure = error == ERROR_CAMERA_DEVICE || error == ERROR_CAMERA_SERVICE;
        api.post(() -> api.onDeviceLost(this, "error " + error, isDeviceFailure), 0);
      }
    }

    @Override
    public void onClosed (@NonNull CameraDevice camera) {
      closeLatch.countDown();
    }
  }

  /** Camera thread. The open device has been disconnected or failed. */
  private void onDeviceLost (DeviceCallback callback, String reason, boolean isDeviceFailure) {
    if (callback != deviceCallback || !isCameraActive) {
      return;
    }
    // A failure before the first frame is a broken Camera2 implementation, after it a busy or overheated camera
    reportFailure("device " + reason, isDeviceFailure && !firstFrameReceived);
  }

  // === Opening ===

  @SuppressLint("MissingPermission")
  @Override
  protected boolean prepareCamera () throws Throwable {
    try {
      return prepareCameraImpl();
    } catch (Throwable t) {
      rememberFailure = isDeviceFailure(t);
      throw t;
    }
  }

  @SuppressLint("MissingPermission")
  private boolean prepareCameraImpl () throws Throwable {
    if (cameraIds == null) {
      cameraIds = findCameraIds(cameraManager);
    }
    setNumberOfCameras(cameraIds.count());
    if (mNumberOfCameras == 0) {
      // Camera1 decides whether it's really "no camera" and shows the error
      throw new Camera2Failure("no usable cameras", false);
    }
    List<String> ids = cameraIds.asList(manager.preferFrontFacingCamera());
    String id = ids.get(getRequestedCameraIndex());

    CameraCharacteristics c = cameraManager.getCameraCharacteristics(id);
    Integer facing = c.get(CameraCharacteristics.LENS_FACING);
    Integer orientation = c.get(CameraCharacteristics.SENSOR_ORIENTATION);

    this.cameraId = id;
    this.characteristics = c;
    this.isFrontFacing = facing != null && facing == CameraMetadata.LENS_FACING_FRONT;
    this.sensorOrientation = orientation != null ? orientation : 90;

    setupStreamConfiguration(c);

    DeviceCallback callback = new DeviceCallback(this);
    this.deviceCallback = callback;
    cameraManager.openCamera(id, callback, getCallbackHandler());
    boolean signalled = callback.openLatch.await(OPEN_TIMEOUT_MS, TimeUnit.MILLISECONDS);
    CameraDevice device;
    synchronized (callback) {
      device = callback.openedDevice;
      if (!signalled || device == null) {
        callback.abandoned = true;
      }
    }
    if (!signalled) {
      throw new Camera2Failure("open timeout, camera " + id, true);
    }
    if (device == null || callback.disconnected || callback.error != -1) {
      if (device != null) {
        device.close();
      }
      // ERROR_CAMERA_IN_USE / MAX_CAMERAS_IN_USE / DISABLED are not about Camera2 itself
      boolean isDeviceFailure = callback.error == CameraDevice.StateCallback.ERROR_CAMERA_DEVICE || callback.error == CameraDevice.StateCallback.ERROR_CAMERA_SERVICE;
      throw new Camera2Failure("open failed, camera " + id + ", error: " + callback.error + ", disconnected: " + callback.disconnected, isDeviceFailure);
    }
    this.device = device;
    this.mCurrentFacing = isFrontFacing ? FACING_FRONT : FACING_BACK;

    setupFeatures(c);
    setupZoom(c);

    int width = previewSize.getWidth(), height = previewSize.getHeight();
    if (U.isRotated(calculateDisplayOrientation())) {
      manager.setAspectRatio(height, width);
    } else {
      manager.setAspectRatio(width, height);
    }
    Log.i(Log.TAG_CAMERA, "Camera2 opened camera %s, front: %b, preview: %dx%d, fps: %s, stabilization: %d", id, isFrontFacing, width, height, fpsRange, stabilizationMode);
    return true;
  }

  private void setupStreamConfiguration (CameraCharacteristics c) throws Camera2Failure {
    StreamConfigurationMap map = c.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
    Size[] outputSizes = map != null ? map.getOutputSizes(SurfaceTexture.class) : null;
    if (outputSizes == null || outputSizes.length == 0) {
      throw new Camera2Failure("no SurfaceTexture output sizes", true);
    }
    List<int[]> sizes = new ArrayList<>(outputSizes.length);
    for (Size size : outputSizes) {
      sizes.add(new int[] {size.getWidth(), size.getHeight()});
    }

    List<int[]> ranges = new ArrayList<>();
    Range<Integer>[] availableRanges = c.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES);
    if (availableRanges != null) {
      for (Range<Integer> range : availableRanges) {
        ranges.add(new int[] {range.getLower(), range.getUpper()});
      }
    }

    final boolean isRound = manager.useRoundRender();
    final int minSide = isRound ? manager.getRoundCaptureMinSide() : 0;
    int fps = isRound && minSide > 0 ? manager.getRoundCaptureFps() : 30;
    int[] range = chooseFpsRange(ranges, fps, 1);

    int[] chosen = null;
    if (isRound && lockedRoundSize != null && hasRoundRecorder()) {
      // The round renderer keeps the size of the first camera: use it for the other one too, when possible
      for (int[] size : sizes) {
        if (size[0] == lockedRoundSize.getWidth() && size[1] == lockedRoundSize.getHeight()) {
          chosen = size;
          break;
        }
      }
    }
    if (chosen == null && minSide > 0) {
      chosen = chooseRoundPreviewSize(sizes, minSide);
      if (chosen != null && range != null && range[1] > 30 && !supportsFps(map, chosen, range[1])) {
        // Vibegram: the chosen size can't stream at 60 fps, look for one that can
        List<int[]> fastSizes = new ArrayList<>();
        for (int[] size : sizes) {
          if (supportsFps(map, size, range[1])) {
            fastSizes.add(size);
          }
        }
        int[] fast = chooseRoundPreviewSize(fastSizes, minSide);
        if (fast != null && Math.min(fast[0], fast[1]) >= minSide) {
          chosen = fast;
        } else {
          range = chooseFpsRange(ranges, 30, 1);
        }
      }
    }
    if (chosen == null) {
      chosen = chooseLegacyPreviewSize(sizes);
    }
    this.previewSize = new Size(chosen[0], chosen[1]);
    this.fpsRange = range != null ? new Range<>(range[0], range[1]) : null;
    if (isRound && !hasRoundRecorder()) {
      lockedRoundSize = previewSize;
    }
    if (isRound) {
      manager.onRoundCaptureConfigured(chosen[0], chosen[1], range != null ? range[1] : 30);
    }
  }

  private static boolean supportsFps (StreamConfigurationMap map, int[] size, int fps) {
    try {
      long minFrameDuration = map.getOutputMinFrameDuration(SurfaceTexture.class, new Size(size[0], size[1]));
      return minFrameDuration <= 0 || minFrameDuration <= 1_000_000_000L / fps + 100_000L;
    } catch (Throwable t) {
      return false;
    }
  }

  /** The pre-Vibegram logic of {@link CameraApiLegacy}: the closest by area to maxResolution² or to the view size. */
  private int[] chooseLegacyPreviewSize (List<int[]> sizes) {
    final int maxResolution = manager.getMaxResolution();
    long desiredSquare;
    float desiredRatio;
    if (maxResolution != 0) {
      desiredSquare = (long) maxResolution * maxResolution;
      desiredRatio = 16f / 9f;
    } else if (mPreviewWidth > 0 && mPreviewHeight > 0) {
      desiredSquare = (long) mPreviewWidth * mPreviewHeight;
      desiredRatio = (float) Math.max(mPreviewWidth, mPreviewHeight) / (float) Math.min(mPreviewWidth, mPreviewHeight);
    } else {
      desiredSquare = 1280L * 720L;
      desiredRatio = 16f / 9f;
    }
    List<int[]> sorted = new ArrayList<>(sizes);
    final long square = desiredSquare;
    final float ratio = desiredRatio;
    Collections.sort(sorted, (a, b) -> comparePreviewSizes(a[0], a[1], b[0], b[1], square, ratio));
    return sorted.get(0);
  }

  private void setupFeatures (CameraCharacteristics c) {
    CameraFeatures features = new CameraFeatures(true);
    if (isFrontFacing) {
      features.add(CameraFeatures.FEATURE_FACING_FRONT);
    }
    Boolean flash = c.get(CameraCharacteristics.FLASH_INFO_AVAILABLE);
    hasFlash = flash != null && flash;
    features.add(CameraFeatures.FEATURE_FLASH_OFF);
    if (hasFlash) {
      features.add(CameraFeatures.FEATURE_FLASH_TORCH);
    }
    int[] afModes = c.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES);
    if (contains(afModes, CameraMetadata.CONTROL_AF_MODE_AUTO)) {
      features.add(CameraFeatures.FEATURE_FOCUS_AUTO);
    }
    if (contains(afModes, CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_PICTURE)) {
      features.add(CameraFeatures.FEATURE_FOCUS_CONTINUOUS_PICTURE);
    }
    if (contains(afModes, CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_VIDEO)) {
      features.add(CameraFeatures.FEATURE_FOCUS_CONTINUOUS_VIDEO);
    }

    stabilizationMode = -1;
    int[] stabilizationModes = c.get(CameraCharacteristics.CONTROL_AVAILABLE_VIDEO_STABILIZATION_MODES);
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && contains(stabilizationModes, CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_PREVIEW_STABILIZATION)) {
      // Stabilizes the stream the recorder draws, unlike ON which may only stabilize recording surfaces
      stabilizationMode = CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_PREVIEW_STABILIZATION;
    } else if (contains(stabilizationModes, CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_ON)) {
      stabilizationMode = CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_ON;
    }
    int[] opticalModes = c.get(CameraCharacteristics.LENS_INFO_AVAILABLE_OPTICAL_STABILIZATION);
    opticalStabilizationMode = contains(opticalModes, CameraMetadata.LENS_OPTICAL_STABILIZATION_MODE_ON) ? CameraMetadata.LENS_OPTICAL_STABILIZATION_MODE_ON : -1;

    setCameraDeviceFeatures(features);
  }

  // === Zoom ===

  /** Ratio of the longest physical lens of the open back camera, 1 without a telephoto lens (or when unknown). */
  private float longestLensRatio = 1f;

  private void setupZoom (CameraCharacteristics c) {
    float minRatio = 1f, maxRatio = 1f;
    useZoomRatioControl = false;
    activeArraySize = c.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE);
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
      Range<Float> range = c.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE);
      if (range != null && range.getUpper() > range.getLower()) {
        minRatio = range.getLower();
        maxRatio = range.getUpper();
        useZoomRatioControl = true;
      }
    }
    if (!useZoomRatioControl) {
      Float maxDigitalZoom = c.get(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM);
      maxRatio = maxDigitalZoom != null && activeArraySize != null ? Math.max(1f, maxDigitalZoom) : 1f;
    }
    minRatio = Math.min(minRatio, 1f);

    if (maxRatio > 1f && mFeatures != null) {
      mFeatures.add(CameraFeatures.FEATURE_ZOOM);
      mFeatures.setMaxZoom(ZOOM_INDEX_STEPS);
    }

    float[] stops = isFrontFacing ? computeFrontStops(minRatio, maxRatio) : computeBackStops(c, minRatio, maxRatio);
    // Past the camera maximum the round recorder crops frames: back cameras only, a selfie needs no 30×
    setZoomCapabilities(minRatio, maxRatio, stops, isFrontFacing ? 0f : longestLensRatio);
    syncZoomIndex(0f);
  }

  private static float[] computeFrontStops (float minRatio, float maxRatio) {
    List<Float> stops = new ArrayList<>();
    stops.add(1f);
    if (maxRatio >= 2f) {
      stops.add(2f);
    }
    // Digital steps, shown after 2×
    if (maxRatio >= 4f) {
      stops.add(4f);
    }
    if (maxRatio >= 10f) {
      stops.add(10f);
    }
    return finishStops(stops, minRatio, maxRatio);
  }

  /** Ultra-wide (min ratio below 1), 1, 2 and the native factors of the telephoto lenses. */
  private float[] computeBackStops (CameraCharacteristics c, float minRatio, float maxRatio) {
    longestLensRatio = 1f;
    List<Float> stops = new ArrayList<>();
    if (minRatio < 0.99f) {
      stops.add(Math.round(minRatio * 10f) / 10f);
    }
    stops.add(1f);
    if (maxRatio >= 2f) {
      stops.add(2f);
    }
    boolean hasPhysicalInfo = false;
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
      try {
        hasPhysicalInfo = addTelephotoStops(c, stops, maxRatio);
      } catch (Throwable t) {
        Log.w(Log.TAG_CAMERA, "Cannot read physical cameras", t);
      }
    }
    // Digital steps like camera apps (4, 10), unless a telephoto lens is already close to them.
    // Some phones expose only the ultra-wide and the main lens to other apps, so these are always offered.
    final float[] digitalSteps = {4f, 10f};
    for (float step : digitalSteps) {
      if (step > maxRatio + 0.01f) {
        break;
      }
      boolean nearLens = false;
      for (float stop : stops) {
        if (stop > 2.05f && Math.abs(stop - step) / step < 0.3f) {
          nearLens = true;
          break;
        }
      }
      if (!nearLens) {
        stops.add(step);
      }
    }
    Log.i(Log.TAG_CAMERA, "Camera2 zoom range %.2f..%.2f, physical lenses: %b, longest: %.1f, stops: %s", minRatio, maxRatio, hasPhysicalInfo, longestLensRatio, stops);
    return finishStops(stops, minRatio, maxRatio);
  }

  @RequiresApi(Build.VERSION_CODES.P)
  private boolean addTelephotoStops (CameraCharacteristics logical, List<Float> stops, float maxRatio) {
    Set<String> physicalIds = logical.getPhysicalCameraIds();
    if (physicalIds == null || physicalIds.size() < 2) {
      return false;
    }
    float logicalFocal = equivalentFocalLength(logical);
    List<Float> focals = new ArrayList<>();
    for (String physicalId : physicalIds) {
      try {
        float focal = equivalentFocalLength(cameraManager.getCameraCharacteristics(physicalId));
        if (focal > 0f) {
          focals.add(focal);
        }
      } catch (Throwable t) {
        Log.w(Log.TAG_CAMERA, "Cannot read physical camera %s", t, physicalId);
      }
    }
    if (focals.size() < 2) {
      return false;
    }
    // The main lens is the 1x reference: the physical lens closest to the logical camera itself
    float mainFocal = 0f;
    if (logicalFocal > 0f) {
      float bestDiff = Float.MAX_VALUE;
      for (float focal : focals) {
        float diff = Math.abs(focal - logicalFocal);
        if (diff < bestDiff) {
          bestDiff = diff;
          mainFocal = focal;
        }
      }
    } else {
      // Unknown: the second shortest one when there is an ultra-wide, else the shortest
      Collections.sort(focals);
      mainFocal = focals.get(0);
    }
    if (mainFocal <= 0f) {
      return false;
    }
    Log.i(Log.TAG_CAMERA, "Camera2 lenses (35mm equivalent): %s, main: %.1f", focals, mainFocal);
    for (float focal : focals) {
      float factor = Math.round(focal / mainFocal * 2f) / 2f;
      longestLensRatio = Math.max(longestLensRatio, factor);
      if (factor > 2f && factor <= maxRatio + 0.01f) {
        stops.add(factor);
      }
    }
    return true;
  }

  /** 35 mm equivalent focal length: focal * 43.27 / sensor diagonal (mm), 0 when unknown. */
  private static float equivalentFocalLength (CameraCharacteristics c) {
    float[] focalLengths = c.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS);
    SizeF physicalSize = c.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE);
    if (focalLengths == null || focalLengths.length == 0 || physicalSize == null) {
      return 0f;
    }
    double diagonal = Math.hypot(physicalSize.getWidth(), physicalSize.getHeight());
    if (diagonal <= 0) {
      return 0f;
    }
    return (float) (focalLengths[0] * 43.27 / diagonal);
  }

  private static float[] finishStops (List<Float> stops, float minRatio, float maxRatio) {
    List<Float> result = new ArrayList<>(stops.size());
    for (float stop : stops) {
      float clamped = Math.max(minRatio, Math.min(maxRatio, stop));
      boolean duplicate = false;
      for (float existing : result) {
        if (Math.abs(existing - clamped) < 0.05f) {
          duplicate = true;
          break;
        }
      }
      if (!duplicate) {
        result.add(clamped);
      }
    }
    Collections.sort(result);
    float[] array = new float[result.size()];
    for (int i = 0; i < array.length; i++) {
      array[i] = result.get(i);
    }
    return array;
  }

  private float zoomIndexToRatio (float index) {
    int steps = mFeatures != null ? mFeatures.getMaxZoom() : 0;
    if (steps <= 0) {
      return 1f;
    }
    float fraction = Math.max(0f, Math.min(1f, index / steps));
    return 1f + (mHardwareMaxZoomRatio - 1f) * fraction;
  }

  private float zoomRatioToIndex (float ratio) {
    int steps = mFeatures != null ? mFeatures.getMaxZoom() : 0;
    if (steps <= 0 || mHardwareMaxZoomRatio <= 1f || ratio <= 1f) {
      return 0f;
    }
    ratio = Math.min(ratio, mHardwareMaxZoomRatio);
    return Math.round((ratio - 1f) / (mHardwareMaxZoomRatio - 1f) * steps);
  }

  @Override
  protected void onZoomChanged (float zoom) {
    float ratio = zoomIndexToRatio(zoom);
    if (device == null) {
      return;
    }
    try {
      applyZoom(ratio);
      updateRepeatingRequest();
    } catch (Throwable t) {
      Log.w(Log.TAG_CAMERA, "Cannot change Camera2 zoom", t);
      return;
    }
    resetSoftwareZoom();
    if (mZoomRatio != ratio) {
      mZoomRatio = ratio;
      manager.onZoomRatioChanged(ratio);
    }
  }

  @Override
  protected void onZoomRatioChanged (float ratio) throws Throwable {
    if (device == null) {
      return;
    }
    applyZoom(ratio);
    updateRepeatingRequest();
    syncZoomIndex(zoomRatioToIndex(ratio));
  }

  private void applyZoom (float ratio) {
    CaptureRequest.Builder builder = requestBuilder;
    if (builder == null) {
      return;
    }
    if (useZoomRatioControl && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
      builder.set(CaptureRequest.CONTROL_ZOOM_RATIO, ratio);
    } else if (activeArraySize != null) {
      float zoom = Math.max(1f, ratio);
      int width = Math.round(activeArraySize.width() / zoom);
      int height = Math.round(activeArraySize.height() / zoom);
      int left = activeArraySize.left + (activeArraySize.width() - width) / 2;
      int top = activeArraySize.top + (activeArraySize.height() - height) / 2;
      builder.set(CaptureRequest.SCALER_CROP_REGION, new Rect(left, top, left + width, top + height));
    }
  }

  // === Session ===

  @Override
  protected boolean openCamera (SurfaceTexture texture) throws Throwable {
    try {
      return openCameraImpl(texture);
    } catch (Throwable t) {
      rememberFailure = isDeviceFailure(t);
      throw t;
    }
  }

  private static final class SessionCallback extends CameraCaptureSession.StateCallback {
    final CountDownLatch latch = new CountDownLatch(1);
    CameraCaptureSession configuredSession;
    boolean failed, abandoned;

    @Override
    public void onConfigured (@NonNull CameraCaptureSession session) {
      boolean close;
      synchronized (this) {
        close = abandoned;
        if (!close) {
          configuredSession = session;
        }
      }
      if (close) {
        session.close();
      }
      latch.countDown();
    }

    @Override
    public void onConfigureFailed (@NonNull CameraCaptureSession session) {
      synchronized (this) {
        failed = true;
      }
      latch.countDown();
    }
  }

  private boolean openCameraImpl (SurfaceTexture texture) throws Throwable {
    final CameraDevice device = this.device;
    if (device == null || texture == null) {
      throw new IllegalStateException("device: " + device + ", texture: " + texture);
    }
    closeSession();
    texture.setDefaultBufferSize(previewSize.getWidth(), previewSize.getHeight());
    Surface surface = new Surface(texture);
    this.outputSurface = surface;

    SessionCallback callback = new SessionCallback();
    //noinspection deprecation: the List<Surface> variant is the only one on API 23-27
    device.createCaptureSession(Collections.singletonList(surface), callback, getCallbackHandler());
    boolean signalled = callback.latch.await(CONFIGURE_TIMEOUT_MS, TimeUnit.MILLISECONDS);
    CameraCaptureSession session;
    synchronized (callback) {
      session = callback.configuredSession;
      if (!signalled) {
        callback.abandoned = true;
      }
    }
    if (!signalled) {
      throw new Camera2Failure("configure timeout, camera " + cameraId, true);
    }
    if (session == null || callback.failed) {
      throw new Camera2Failure("configure failed, camera " + cameraId + ", size: " + previewSize, true);
    }
    this.session = session;

    final boolean isRound = manager.useRoundRender();
    CaptureRequest.Builder builder = device.createCaptureRequest(isRound ? CameraDevice.TEMPLATE_RECORD : CameraDevice.TEMPLATE_PREVIEW);
    builder.addTarget(surface);
    builder.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO);
    if (mFeatures != null) {
      if (isRound && mFeatures.has(CameraFeatures.FEATURE_FOCUS_CONTINUOUS_VIDEO)) {
        builder.set(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_VIDEO);
      } else if (mFeatures.has(CameraFeatures.FEATURE_FOCUS_CONTINUOUS_PICTURE)) {
        builder.set(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_PICTURE);
      } else if (mFeatures.has(CameraFeatures.FEATURE_FOCUS_CONTINUOUS_VIDEO)) {
        builder.set(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_VIDEO);
      }
    }
    if (fpsRange != null) {
      builder.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, fpsRange);
    }
    if (stabilizationMode != -1) {
      builder.set(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE, stabilizationMode);
    }
    if (opticalStabilizationMode != -1) {
      builder.set(CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE, opticalStabilizationMode);
    }
    this.requestBuilder = builder;
    applyTorch(mTorchRequested);
    applyZoom(mZoomRatio);

    firstFrameReceived = false;
    final int generation = ++sessionGeneration;
    session.setRepeatingRequest(builder.build(), captureCallback, getCallbackHandler());

    // Vibegram: some HALs configure fine and then never deliver a frame
    post(() -> {
      if (generation == sessionGeneration && this.session != null && isCameraActive && !firstFrameReceived) {
        reportFailure("no frames in " + FIRST_FRAME_TIMEOUT_MS + "ms, camera " + cameraId, true);
      }
    }, (int) FIRST_FRAME_TIMEOUT_MS);
    return true;
  }

  private final CameraCaptureSession.CaptureCallback captureCallback = new CameraCaptureSession.CaptureCallback() {
    @Override
    public void onCaptureCompleted (@NonNull CameraCaptureSession session, @NonNull CaptureRequest request, @NonNull TotalCaptureResult result) {
      if (!firstFrameReceived) {
        firstFrameReceived = true;
        Log.i(Log.TAG_CAMERA, "Camera2 first frame, camera %s", cameraId);
        manager.onRenderedFirstFrame();
      }
    }
  };

  private void updateRepeatingRequest () throws CameraAccessException {
    if (session != null && requestBuilder != null) {
      session.setRepeatingRequest(requestBuilder.build(), captureCallback, getCallbackHandler());
    }
  }

  private void applyTorch (boolean enabled) {
    CaptureRequest.Builder builder = requestBuilder;
    if (builder == null) {
      return;
    }
    builder.set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_ON);
    builder.set(CaptureRequest.FLASH_MODE, enabled && hasFlash ? CameraMetadata.FLASH_MODE_TORCH : CameraMetadata.FLASH_MODE_OFF);
  }

  @Override
  protected void onTorchChange (boolean enabled) throws Throwable {
    if (requestBuilder != null && hasFlash) {
      applyTorch(enabled);
      updateRepeatingRequest();
    }
  }

  @Override
  protected void onFlashModeChange (int newFlashMode) throws Throwable {
    // Photo flash modes don't apply to round videos: only the torch (and the fake front flash drawn by the UI)
  }

  private void closeSession () {
    sessionGeneration++;
    if (session != null) {
      try {
        session.close();
      } catch (Throwable t) {
        Log.w(Log.TAG_CAMERA, "Cannot close Camera2 session", t);
      }
      session = null;
    }
    requestBuilder = null;
    if (outputSurface != null) {
      outputSurface.release();
      outputSurface = null;
    }
  }

  @Override
  protected void closeCamera () throws Throwable {
    manager.resetRenderState(false);
    closeSession();
    firstFrameReceived = false;
    DeviceCallback callback = this.deviceCallback;
    CameraDevice device = this.device;
    this.deviceCallback = null;
    this.device = null;
    if (device != null) {
      try {
        device.close();
      } catch (Throwable t) {
        Log.w(Log.TAG_CAMERA, "Cannot close Camera2 device", t);
      }
      // Some HALs refuse to open the other camera until this one is really closed
      if (callback != null && !callback.closeLatch.await(CLOSE_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
        Log.w(Log.TAG_CAMERA, "Camera2 device %s did not report closing in time", cameraId);
      }
    }
    if (characteristics != null) {
      characteristics = null;
      setCameraDeviceFeatures(null);
      setZoomCapabilities(1f, 1f, new float[] {1f});
    }
  }

  // === Camera switching ===

  @Override
  protected void onNextCameraSourceRequested () {
    final boolean isActive = isCameraActive;
    if (mNumberOfCameras > 1 && cameraIds != null) {
      resetFlashMode();
      resetZoom();
      manager.resetRenderState(true);
      int nextCameraIndex = getNextCameraIndex();
      List<String> ids = cameraIds.asList(manager.preferFrontFacingCamera());
      boolean toFrontFace = ids.get(nextCameraIndex).equals(cameraIds.frontId);
      boolean forward = nextCameraIndex >= getRequestedCameraIndex();
      manager.onCameraSourceChange(false, forward, toFrontFace);
      if (isActive) {
        setCameraActive(false);
      }
      setRequestedCameraIndex(nextCameraIndex);
      if (isActive) {
        setCameraActive(true);
      }
      manager.onCameraSourceChange(true, forward, toFrontFace);
    }
  }

  @Override
  protected void onResetRequestedSettings () {
    cameraIds = null;
    lockedRoundSize = null;
  }

  // === Orientation & sizes ===

  @Override
  protected int calculateDisplayOrientation () {
    // Same formula as Camera1 (CameraApiLegacy), so RoundVideoRecorder draws both the same way
    int degrees = this.mDisplayOrientation;
    int result;
    if (isFrontFacing) {
      result = (sensorOrientation + degrees) % 360;
      result = (360 - result) % 360;
    } else {
      result = (sensorOrientation - degrees + 360) % 360;
    }
    return result;
  }

  @Override
  protected int getSensorOrientation () {
    return sensorOrientation;
  }

  @Override
  protected int getCameraOutputWidth () {
    return previewSize != null ? previewSize.getWidth() : 0;
  }

  @Override
  protected int getCameraOutputHeight () {
    return previewSize != null ? previewSize.getHeight() : 0;
  }

  @Override
  protected void onPreviewSizeChanged (int newWidth, int newHeight) {
    // The size is chosen in prepareCamera(): CameraApi restarts the camera when it's not busy
  }

  @Override
  protected void onDisplayOrientationChanged () {
    // Camera2 rotates SurfaceTexture output by itself, nothing to reconfigure
  }

  @Override
  protected void onForcedOrientationChange () { }

  // === Photo & regular video: not used, Camera2 serves only round videos ===

  @Override
  protected void onTakePhoto (int trimWidth, int trimHeight, int orientation) {
    manager.onTakeMediaError(false);
  }

  @Override
  public boolean isVideoRecordSupported () {
    // Round videos are recorded by RoundVideoRecorder from the preview stream
    return true;
  }

  @Override
  protected void onStartVideoCapture () throws Throwable {
    throw new UnsupportedOperationException("Camera2 backend records round videos only");
  }

  @Override
  protected void onFinishVideoCapture (boolean saveFile, RunnableData<ImageGalleryFile> callback) { }
}
