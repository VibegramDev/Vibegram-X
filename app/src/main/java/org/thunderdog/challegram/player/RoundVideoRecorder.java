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
 * File created on 14/10/2017
 */
package org.thunderdog.challegram.player;

import android.graphics.SurfaceTexture;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.media.MediaRecorder;
import android.opengl.EGL14;
import android.opengl.EGLExt;
import android.opengl.GLES11Ext;
import android.opengl.GLES20;
import android.opengl.GLES30;
import android.opengl.GLUtils;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.os.SystemClock;
import android.view.Surface;

import androidx.annotation.RequiresApi;

import org.thunderdog.challegram.Log;
import org.thunderdog.challegram.U;
import org.thunderdog.challegram.tool.UI;
import org.thunderdog.challegram.ui.camera.legacy.CameraApi;
import org.thunderdog.challegram.video.Mp4Output;
import org.thunderdog.challegram.video.old.Mp4Movie;
import org.thunderdog.challegram.video.old.Mp4OutputImpl;

import java.io.File;
import java.lang.ref.WeakReference;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.ArrayList;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;

import javax.microedition.khronos.egl.EGL10;
import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.egl.EGLContext;
import javax.microedition.khronos.egl.EGLDisplay;
import javax.microedition.khronos.egl.EGLSurface;
import javax.microedition.khronos.opengles.GL;

import me.vkryl.core.BitwiseUtils;

@RequiresApi(Build.VERSION_CODES.JELLY_BEAN_MR2)
public class RoundVideoRecorder {
  public interface Delegate {
    /**
     * Start progress timer at this point, if still required
     * */
    void onVideoRecordingStarted (String key, long startTimeMs);

    /**
     * Called when part of file has been generated
     * */
    void onVideoRecordProgress (String key, long readyBytesCount);

    /**
     * Called when video recording has been completely finished
     *
     * @param resultFileSize Result file size. Negative when recording has been cancelled or failed
     * */
    void onVideoRecordingFinished (String key, long resultFileSize, long resultDuration, TimeUnit resultDurationUnit);
  }

  private static class RoundHandler extends Handler {
    private final RoundVideoRecorder context;
    private final boolean isBackground;

    public RoundHandler (Looper looper, RoundVideoRecorder context, boolean isBackground) {
      super(looper);
      this.context = context;
      this.isBackground = isBackground;
    }

    @Override
    public void handleMessage (Message msg) {
      if (isBackground) {
        context.handleBackgroundMessage(msg);
      } else {
        context.handleCameraMessage(msg);
      }
    }
  }

  private final CameraApi context;

  private final Handler cameraHandler, backgroundHandler;

  private final SurfaceTexture mSurfaceTexture;
  private final int mSurfaceWidth, mSurfaceHeight;
  private int mPreviewWidth, mPreviewHeight;
  private int mOrientation;

  private Delegate mDelegate;
  private String workingKey;
  private String outputPath;
  private volatile boolean isCapturing; // Whether capture should be in progress
  private volatile boolean finishCapture; // Whether capture result should be saved
  // Vibegram: the camera was closed while recording (the stop closes it before the encoder stops).
  // Frames drawn after that come from the new, still empty texture or from the camera starting up again:
  // fed to the encoder they ended the video with a fade to green and black frames.
  private volatile boolean cameraDetachedFromEncoder;

  public RoundVideoRecorder (CameraApi context, SurfaceTexture surfaceTexture, int surfaceWidth, int surfaceHeight) {
    this.context = context;
    this.cameraHandler = new RoundHandler(context.getCameraHandler().getLooper(), this, false);
    this.backgroundHandler = new RoundHandler(context.getBackgroundHandler().getLooper(), this, true);
    this.mSurfaceTexture = surfaceTexture;
    this.mSurfaceWidth = surfaceWidth;
    this.mSurfaceHeight = surfaceHeight;
  }

  public void setPreviewSizes (int width, int height, int orientation) {
    if (!checkBackgroundThread()) {
      backgroundHandler.sendMessage(Message.obtain(backgroundHandler, BG_ACTION_SET_SIZES, width, height, orientation));
      return;
    }
    if (mPreviewWidth != 0 || mPreviewHeight != 0) {
      return;
    }
    this.mPreviewWidth = width;
    this.mPreviewHeight = height;
    this.mOrientation = orientation;

    updateSizes();
  }

  public void openCamera () {
    if (!checkBackgroundThread()) {
      backgroundHandler.sendMessage(Message.obtain(backgroundHandler, BG_ACTION_INIT));
      return;
    }
    cameraDetachedFromEncoder = false;
    prepareGL();
  }

  private volatile boolean isSwitchingToNewCamera;

  public boolean canSwitchToNewCamera () {
    return !isSwitchingToNewCamera && initied; // && eglSurface != null;
  }

  public void switchToNewCamera () {
    isSwitchingToNewCamera = true;
    // A new camera device is open (switch during recording): its frames go to the encoder again.
    cameraDetachedFromEncoder = false;
    if (!checkBackgroundThread()) {
      backgroundHandler.sendMessage(Message.obtain(backgroundHandler, BG_ACTION_REINIT));
      return;
    }
    doSwitchToNewCamera();
  }

  public void onCameraClose () {
    if (isCapturing()) {
      cameraDetachedFromEncoder = true;
    }
    if (!checkBackgroundThread()) {
      backgroundHandler.sendMessage(Message.obtain(backgroundHandler, BG_ACTION_CLOSE));
      return;
    }
    doReinitToNextCamera();
  }

  private boolean isDestroyed;

  public void destroy () {
    if (!checkBackgroundThread()) {
      backgroundHandler.sendMessage(Message.obtain(backgroundHandler, BG_ACTION_DESTROY));
      return;
    }
    if (!isDestroyed) {
      isDestroyed = true;
      doDestroy();
    }
  }

  private SurfaceTexture mCameraSurface;

  public SurfaceTexture getCameraTexture () {
    return mCameraSurface;
  }

  // Entry point

  public void startCapture (Delegate delegate, String key, String outputPath) {
    Log.i(Log.TAG_ROUND, "startCapture: %s, path: %s, isCapturing: %b", key, outputPath, isCapturing);
    if (!isCapturing) {
      this.cameraDetachedFromEncoder = false;
      this.isCapturing = true;
      this.mDelegate = delegate;
      this.workingKey = key;
      this.outputPath = outputPath;
      boolean success = false;
      try {
        success = startCaptureImpl();
      } catch (Throwable t) {
        Log.w(Log.TAG_ROUND, "Cannot start round video capture", t);
      }
      if (!success) {
        this.isCapturing = false;
      }
    }
  }

  public void finishCapture (String key, boolean saveResult) {
    Log.i(Log.TAG_ROUND, "finishCapture: %s, saveResult: %b, isCapturing: %b", key, saveResult, isCapturing);
    if (isCapturing) {
      this.finishCapture = saveResult;
      this.isCapturing = false;
      RoundRecordStats.finish();
      stopCaptureImpl();
    }
  }

  // LAYER

  private static final int CAM_ACTION_CREATE = 0;
  private static final int CAM_ACTION_START = 1;

  private void handleCameraMessage (Message msg) { // Camera thread
    switch (msg.what) {
      case CAM_ACTION_CREATE:
        createCamera((SurfaceTexture) msg.obj);
        break;
      case CAM_ACTION_START:
        doStartRecord();
        break;
    }
  }

  private static final int BG_ACTION_INIT = 0;
  private static final int BG_ACTION_REINIT = 1;
  private static final int BG_ACTION_DESTROY = 2;
  private static final int BG_ACTION_CLOSE = 3;
  private static final int BG_ACTION_RENDER = 4;
  private static final int BG_ACTION_SET_SIZES = 5;

  private void handleBackgroundMessage (Message msg) { // Background thread (GL)
    switch (msg.what) {
      case BG_ACTION_INIT:
        openCamera();
        break;
      case BG_ACTION_REINIT:
        switchToNewCamera();
        break;
      case BG_ACTION_DESTROY:
        destroy();
        break;
      case BG_ACTION_CLOSE:
        onCameraClose();
        break;
      case BG_ACTION_RENDER:
        doRender((Integer) msg.obj);
        break;
      case BG_ACTION_SET_SIZES:
        setPreviewSizes(msg.arg1, msg.arg2, (Integer) msg.obj);
        break;
    }
  }

  private static final int UI_ACTION_DISPATCH_VIDEO_RECORD_STARTED = 0;
  private static final int UI_ACTION_DISPATCH_VIDEO_RECORD_FINISHED = 1;
  private static final int UI_ACTION_DISPATCH_VIDEO_RECORD_PROGRESS = 2;

  private static boolean checkUiThread () {
    return Looper.myLooper() == Looper.getMainLooper();
  }

  private static class UiHandler extends Handler {
    private final RoundVideoRecorder context;

    public UiHandler (RoundVideoRecorder context) {
      super(Looper.getMainLooper());
      this.context = context;
    }

    @Override
    public void handleMessage (Message msg) {
      switch (msg.what) {
        case UI_ACTION_DISPATCH_VIDEO_RECORD_STARTED: {
          context.dispatchVideoRecordStarted((String) msg.obj, BitwiseUtils.mergeLong(msg.arg1, msg.arg2));
          break;
        }
        case UI_ACTION_DISPATCH_VIDEO_RECORD_FINISHED: {
          Object[] data = (Object[]) msg.obj;
          context.dispatchVideoRecordFinished((String) data[0], (long) data[1], (long) data[2], (TimeUnit) data[3]);
          data[0] = data[1] = data[2] = data[3] = null;
          break;
        }
        case UI_ACTION_DISPATCH_VIDEO_RECORD_PROGRESS: {
          context.dispatchVideoRecordProgress((String) msg.obj, BitwiseUtils.mergeLong(msg.arg1, msg.arg2));
          break;
        }
      }
    }
  }

  private final UiHandler uiHandler = new UiHandler(this);

  private void dispatchVideoRecordStarted (String key, long startTimeMs) {
    if (!checkUiThread()) {
      uiHandler.sendMessage(Message.obtain(uiHandler, UI_ACTION_DISPATCH_VIDEO_RECORD_STARTED, BitwiseUtils.splitLongToFirstInt(startTimeMs), BitwiseUtils.splitLongToSecondInt(startTimeMs), key));
      return;
    }
    if (mDelegate != null) {
      mDelegate.onVideoRecordingStarted(key, startTimeMs);
    }
  }

  private void dispatchVideoRecordFinished (String key, long resultFileSize, long resultDuration, TimeUnit resultDurationUnit) {
    if (!checkUiThread()) {
      uiHandler.sendMessage(Message.obtain(
        uiHandler,
        UI_ACTION_DISPATCH_VIDEO_RECORD_FINISHED,
        new Object[] {
          key, resultFileSize, resultDuration, resultDurationUnit
        }
      ));
      return;
    }
    if (mDelegate != null) {
      mDelegate.onVideoRecordingFinished(key, resultFileSize, resultDuration, resultDurationUnit);
    }
  }

  private void dispatchVideoRecordProgress (String key, long readyBytesCount) {
    if (!checkUiThread()) {
      uiHandler.sendMessage(Message.obtain(uiHandler, UI_ACTION_DISPATCH_VIDEO_RECORD_PROGRESS, BitwiseUtils.splitLongToFirstInt(readyBytesCount), BitwiseUtils.splitLongToSecondInt(readyBytesCount), key));
      return;
    }
    if (mDelegate != null) {
      mDelegate.onVideoRecordProgress(key, readyBytesCount);
    }
  }

  private boolean checkCameraThread () {
    return context.checkCameraThread();
  }

  private boolean checkBackgroundThread () {
    return context.checkBackgroundThread();
  }

  // Impl

  private File cameraFile;

  private boolean startCaptureImpl () throws Throwable {
    cameraFile = new File(outputPath);
    /*if (!cameraFile.exists() && !cameraFile.createNewFile()) {
      return false;
    }*/

    doStartRecord();

    return true;
  }

  private void stopCaptureImpl () {
    destroy();
  }


  // Copypasting

  private void requestRender() {
    if (!checkBackgroundThread()) {
      backgroundHandler.sendMessage(Message.obtain(backgroundHandler, BG_ACTION_RENDER, cameraId));
    } else {
      doRender(cameraId);
    }
  }

  private volatile boolean cameraReady;

  private FloatBuffer vertexBuffer;
  private FloatBuffer textureBuffer;

  private static final String VERTEX_SHADER =
    "uniform mat4 uMVPMatrix;\n" +
      "uniform mat4 uSTMatrix;\n" +
      "attribute vec4 aPosition;\n" +
      "attribute vec4 aTextureCoord;\n" +
      "varying vec2 vTextureCoord;\n" +
      "void main() {\n" +
      "   gl_Position = uMVPMatrix * aPosition;\n" +
      "   vTextureCoord = (uSTMatrix * aTextureCoord).xy;\n" +
      "}\n";

  private static final String FRAGMENT_SHADER =
    "#extension GL_OES_EGL_image_external : require\n" +
      "precision highp float;\n" +
      "varying vec2 vTextureCoord;\n" +
      "uniform float scaleX;\n" +
      "uniform float scaleY;\n" +
      "uniform float alpha;\n" +
      "uniform float zoom;\n" +
      "uniform samplerExternalOES sTexture;\n" +
      "void main() {\n" +
      "   vec2 coord = vec2((vTextureCoord.x - 0.5) * scaleX, (vTextureCoord.y - 0.5) * scaleY);\n" +
      "   float coef = ceil(clamp(0.2601 - dot(coord, coord), 0.0, 1.0));\n" +
      "   vec2 tc = 0.5 + (vTextureCoord - 0.5) / zoom;\n" +
      "   vec3 color = texture2D(sTexture, tc).rgb * coef + (1.0 - step(0.001, coef));\n" +
      "   gl_FragColor = vec4(color * alpha, alpha);\n" +
      "}\n";

  // Vibegram: same as FRAGMENT_SHADER, but for a camera frame much larger than the output
  // (e.g. 1080 -> 640): a single sample per output pixel skips camera pixels and fine details shimmer.
  // 3x3 bilinear taps with tent weights (1-2-1) average the whole footprint of the output pixel.
  private static final String FRAGMENT_SHADER_DOWNSCALE =
    "#extension GL_OES_EGL_image_external : require\n" +
      "precision highp float;\n" +
      "varying vec2 vTextureCoord;\n" +
      "uniform float scaleX;\n" +
      "uniform float scaleY;\n" +
      "uniform float alpha;\n" +
      "uniform vec2 texelOffset;\n" +
      "uniform float zoom;\n" +
      "uniform samplerExternalOES sTexture;\n" +
      "void main() {\n" +
      "   vec2 coord = vec2((vTextureCoord.x - 0.5) * scaleX, (vTextureCoord.y - 0.5) * scaleY);\n" +
      "   float coef = ceil(clamp(0.2601 - dot(coord, coord), 0.0, 1.0));\n" +
      "   vec2 tc = 0.5 + (vTextureCoord - 0.5) / zoom;\n" +
      "   vec2 dx = vec2(texelOffset.x / zoom, 0.0);\n" +
      "   vec2 dy = vec2(0.0, texelOffset.y / zoom);\n" +
      "   vec3 sum = texture2D(sTexture, tc).rgb * 4.0;\n" +
      "   sum += (texture2D(sTexture, tc - dx).rgb + texture2D(sTexture, tc + dx).rgb +\n" +
      "           texture2D(sTexture, tc - dy).rgb + texture2D(sTexture, tc + dy).rgb) * 2.0;\n" +
      "   sum += texture2D(sTexture, tc - dx - dy).rgb + texture2D(sTexture, tc + dx - dy).rgb +\n" +
      "          texture2D(sTexture, tc - dx + dy).rgb + texture2D(sTexture, tc + dx + dy).rgb;\n" +
      "   vec3 color = (sum / 16.0) * coef + (1.0 - step(0.001, coef));\n" +
      "   gl_FragColor = vec4(color * alpha, alpha);\n" +
      "}\n";

  // Vibegram: the blur of a lens switch in the video itself (the screen blurs it in the UI): the last frame of the
  // previous lens blurs while the camera reopens, then the new lens fades in from blurred to sharp.
  // A disk of 24 taps (Vogel spiral), "radius" in texture coordinates, 2D copies of the frames only.
  private static final String FRAGMENT_SHADER_BLUR =
    "precision highp float;\n" +
      "varying vec2 vTextureCoord;\n" +
      "uniform float scaleX;\n" +
      "uniform float scaleY;\n" +
      "uniform float alpha;\n" +
      "uniform float zoom;\n" +
      "uniform vec2 radius;\n" +
      "uniform sampler2D sTexture;\n" +
      "void main() {\n" +
      "   vec2 coord = vec2((vTextureCoord.x - 0.5) * scaleX, (vTextureCoord.y - 0.5) * scaleY);\n" +
      "   float coef = ceil(clamp(0.2601 - dot(coord, coord), 0.0, 1.0));\n" +
      "   vec2 tc = 0.5 + (vTextureCoord - 0.5) / zoom;\n" +
      "   vec2 r = radius / zoom;\n" +
      "   vec3 sum = vec3(0.0);\n" +
      "   for (int i = 0; i < 24; i++) {\n" +
      "     float f = float(i);\n" +
      "     float d = sqrt((f + 0.5) / 24.0);\n" +
      "     float a = f * 2.39996323;\n" +
      "     sum += texture2D(sTexture, tc + vec2(cos(a), sin(a)) * d * r).rgb;\n" +
      "   }\n" +
      "   vec3 color = (sum / 24.0) * coef + (1.0 - step(0.001, coef));\n" +
      // Not premultiplied: blending is SRC_ALPHA, ONE_MINUS_SRC_ALPHA (premultiplied, the white outside the circle grays mid-fade)
      "   gl_FragColor = vec4(color, alpha);\n" +
      "}\n";

  /** Blur radius of a lens switch, part of the video side. */
  private static final float SWITCH_BLUR_RADIUS = 0.04f;
  /** The last frame of the previous camera blurs in this time. */
  private static final long SWITCH_BLUR_IN_NS = 150_000_000L;
  /** The new camera fades in from blurred to sharp in this time. */
  private static final float SWITCH_FADE_NS = 250_000_000f;

  // Vibegram: "zoom" in the shaders is the software zoom past the camera maximum (CameraApi.getSoftwareZoom()):
  // the frame is sampled closer to its center, the round mask stays where it is.

  /** Camera pixels per output pixel above which {@link #FRAGMENT_SHADER_DOWNSCALE} is used. */
  private static final float DOWNSCALE_RATIO_THRESHOLD = 1.3f;

  private static String toTexture2D (String shader) {
    return shader.replace("#extension GL_OES_EGL_image_external : require\n", "").replace("samplerExternalOES", "sampler2D");
  }

  private static final String FRAGMENT_SCREEN_SHADER =
    "#extension GL_OES_EGL_image_external : require\n" +
      "precision lowp float;\n" +
      "varying vec2 vTextureCoord;\n" +
      "uniform float zoom;\n" +
      "uniform samplerExternalOES sTexture;\n" +
      "void main() {\n" +
      "   gl_FragColor = texture2D(sTexture, 0.5 + (vTextureCoord - 0.5) / zoom);\n" +
      "}\n";

  private int[] position = new int[2];
  private int[] cameraTexture = new int[1];
  private int[] oldCameraTexture = new int[1];
  /** Software zoom of the last encoded frame, and of the previous camera's last frame during a switch. */
  private volatile float lastEncodedZoom = 1f, oldCameraZoom = 1f;
  private float cameraTextureAlpha = 1.0f;

  private float scaleX, scaleY;
  private boolean initied;

  private void prepareGL () {
    updateSizes();
    initied = initGL();
  }

  private void updateSizes () {
    int width, height;

    width = mPreviewWidth;
    height = mPreviewHeight;

    int minSide = Math.min(width, height);
    float scale = mSurfaceWidth / (float) minSide;

    width *= scale;
    height *= scale;
    if (width > height) {
      scaleX = 1.0f;
      scaleY = (float) width / (float) mSurfaceHeight;
    } else {
      scaleX = (float) height / (float) mSurfaceWidth;
      scaleY = 1.0f;
    }
    if (!U.isRotated(mOrientation)) {
      float temp = scaleX;
      scaleX = scaleY;
      scaleY = temp;
    }
    rotationAngle = mOrientation;
    surfaceTexture = mSurfaceTexture;

    android.opengl.Matrix.setIdentityM(mMVPMatrix, 0);
    if (rotationAngle != 0) {
      android.opengl.Matrix.rotateM(mMVPMatrix, 0, rotationAngle, 0, 0, 1);
    }
  }

  private static int loadShader (int type, String shaderCode) {
    int shader = GLES20.glCreateShader(type);
    GLES20.glShaderSource(shader, shaderCode);
    GLES20.glCompileShader(shader);
    int[] compileStatus = new int[1];
    GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, compileStatus, 0);
    if (compileStatus[0] == 0) {
      Log.e(Log.TAG_ROUND, GLES20.glGetShaderInfoLog(shader));
      GLES20.glDeleteShader(shader);
      shader = 0;
    }
    return shader;
  }

  private static final int EGL_CONTEXT_CLIENT_VERSION = 0x3098;
  private static final int EGL_OPENGL_ES2_BIT = 4;
  private SurfaceTexture surfaceTexture;
  private EGL10 egl10;
  private EGLDisplay eglDisplay;
  private EGLConfig eglConfig;
  private EGLContext eglContext;
  private EGLSurface eglSurface;
  private GL gl;

  private SurfaceTexture cameraSurface;

  private int drawProgram;
  private int vertexMatrixHandle;
  private int textureMatrixHandle;
  private int zoomHandle = -1;
  private int positionHandle;
  private int textureHandle;

  private int rotationAngle;

  private Integer cameraId = 0;

  private VideoRecorder videoEncoder;

  private float[] mMVPMatrix = new float[16];
  private float[] mSTMatrix = new float[16];
  private float[] moldSTMatrix = new float[16];

  private boolean initGL () {
    Log.i(Log.TAG_ROUND, "start init gl");
    resetFrameCopies();
    // Camera2 fills it in when its session starts; Camera1 leaves it empty.
    RoundRecordStats.setCameraConfig(null, false, null, null, null, RoundRecordStats.STABILIZATION_OFF, false, null);
    cameraGles3 = false;
    egl10 = (EGL10) EGLContext.getEGL();

    eglDisplay = egl10.eglGetDisplay(EGL10.EGL_DEFAULT_DISPLAY);
    if (eglDisplay == EGL10.EGL_NO_DISPLAY) {
      Log.e(Log.TAG_ROUND, "eglGetDisplay failed " + GLUtils.getEGLErrorString(egl10.eglGetError()));
      finish();
      return false;
    }

    int[] version = new int[2];
    if (!egl10.eglInitialize(eglDisplay, version)) {
      Log.e(Log.TAG_ROUND, "eglInitialize failed " + GLUtils.getEGLErrorString(egl10.eglGetError()));
      finish();
      return false;
    }

    int[] configsCount = new int[1];
    EGLConfig[] configs = new EGLConfig[1];
    int[] configSpec = new int[] {
      EGL10.EGL_RENDERABLE_TYPE, EGL_OPENGL_ES2_BIT,
      EGL10.EGL_RED_SIZE, 8,
      EGL10.EGL_GREEN_SIZE, 8,
      EGL10.EGL_BLUE_SIZE, 8,
      EGL10.EGL_ALPHA_SIZE, 0,
      EGL10.EGL_DEPTH_SIZE, 0,
      EGL10.EGL_STENCIL_SIZE, 0,
      EGL10.EGL_NONE
    };
    if (!egl10.eglChooseConfig(eglDisplay, configSpec, configs, 1, configsCount)) {
      Log.e(Log.TAG_ROUND, "eglChooseConfig failed " + GLUtils.getEGLErrorString(egl10.eglGetError()));
      finish();
      return false;
    } else if (configsCount[0] > 0) {
      eglConfig = configs[0];
    } else {
      Log.e(Log.TAG_ROUND, "eglConfig not initialized");
      finish();
      return false;
    }

    int[] attrib_list = { EGL_CONTEXT_CLIENT_VERSION, 2, EGL10.EGL_NONE };
    eglContext = egl10.eglCreateContext(eglDisplay, eglConfig, EGL10.EGL_NO_CONTEXT, attrib_list);
    if (eglContext == null) {
      Log.e(Log.TAG_ROUND, "eglCreateContext failed " + GLUtils.getEGLErrorString(egl10.eglGetError()));
      finish();
      return false;
    }

    if (surfaceTexture instanceof SurfaceTexture) {
      eglSurface = egl10.eglCreateWindowSurface(eglDisplay, eglConfig, surfaceTexture, null);
    } else {
      finish();
      return false;
    }

    if (eglSurface == null || eglSurface == EGL10.EGL_NO_SURFACE) {
      Log.e(Log.TAG_ROUND, "createWindowSurface failed " + GLUtils.getEGLErrorString(egl10.eglGetError()));
      finish();
      return false;
    }
    if (!egl10.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)) {
      Log.e(Log.TAG_ROUND, "eglMakeCurrent failed " + GLUtils.getEGLErrorString(egl10.eglGetError()));
      finish();
      return false;
    }
    gl = eglContext.getGL();
    cameraGles3 = isGles3();

    float tX = 1.0f / scaleX / 2.0f;
    float tY = 1.0f / scaleY / 2.0f;
    float[] verticesData = {
      -1.0f, -1.0f, 0,
      1.0f, -1.0f, 0,
      -1.0f, 1.0f, 0,
      1.0f, 1.0f, 0
    };
    float[] texData = {
      0.5f - tX, 0.5f - tY,
      0.5f + tX, 0.5f - tY,
      0.5f - tX, 0.5f + tY,
      0.5f + tX, 0.5f + tY
    };

    videoEncoder = new VideoRecorder();

    vertexBuffer = ByteBuffer.allocateDirect(verticesData.length * 4).order(ByteOrder.nativeOrder()).asFloatBuffer();
    vertexBuffer.put(verticesData).position(0);

    textureBuffer = ByteBuffer.allocateDirect(texData.length * 4).order(ByteOrder.nativeOrder()).asFloatBuffer();
    textureBuffer.put(texData).position(0);

    android.opengl.Matrix.setIdentityM(mSTMatrix, 0);

    int vertexShader = loadShader(GLES20.GL_VERTEX_SHADER, VERTEX_SHADER);
    int fragmentShader = loadShader(GLES20.GL_FRAGMENT_SHADER, FRAGMENT_SCREEN_SHADER);
    if (vertexShader != 0 && fragmentShader != 0) {
      drawProgram = GLES20.glCreateProgram();
      GLES20.glAttachShader(drawProgram, vertexShader);
      GLES20.glAttachShader(drawProgram, fragmentShader);
      GLES20.glLinkProgram(drawProgram);
      int[] linkStatus = new int[1];
      GLES20.glGetProgramiv(drawProgram, GLES20.GL_LINK_STATUS, linkStatus, 0);
      if (linkStatus[0] == 0) {
        Log.e(Log.TAG_ROUND, "failed link shader");
        GLES20.glDeleteProgram(drawProgram);
        drawProgram = 0;
      } else {
        positionHandle = GLES20.glGetAttribLocation(drawProgram, "aPosition");
        textureHandle = GLES20.glGetAttribLocation(drawProgram, "aTextureCoord");
        vertexMatrixHandle = GLES20.glGetUniformLocation(drawProgram, "uMVPMatrix");
        textureMatrixHandle = GLES20.glGetUniformLocation(drawProgram, "uSTMatrix");
        zoomHandle = GLES20.glGetUniformLocation(drawProgram, "zoom");
      }
    } else {
      Log.e(Log.TAG_ROUND, "failed creating shader");
      finish();
      return false;
    }

    GLES20.glGenTextures(1, cameraTexture, 0);
    GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, cameraTexture[0]);
    GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
    GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
    GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
    GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);

    android.opengl.Matrix.setIdentityM(mMVPMatrix, 0);

    cameraSurface = new SurfaceTexture(cameraTexture[0]);
    cameraSurface.setOnFrameAvailableListener(surfaceTexture -> requestRender());
    createCamera(cameraSurface);
    Log.i(Log.TAG_ROUND, "gl initied");

    return true;
  }

  public void finish() {
    if (eglSurface != null) {
      egl10.eglMakeCurrent(eglDisplay, EGL10.EGL_NO_SURFACE, EGL10.EGL_NO_SURFACE, EGL10.EGL_NO_CONTEXT);
      egl10.eglDestroySurface(eglDisplay, eglSurface);
      eglSurface = null;
    }
    if (eglContext != null) {
      egl10.eglDestroyContext(eglDisplay, eglContext);
      eglContext = null;
    }
    if (eglDisplay != null) {
      egl10.eglTerminate(eglDisplay);
      eglDisplay = null;
    }
  }

  private void doSwitchToNewCamera () {
    context.openCameraIfStillNeeded(this);
  }

  private void doRender (Integer cameraId) {
    onDraw(cameraId);
  }

  private void createCamera(final SurfaceTexture surfaceTexture) {
    if (!checkCameraThread()) {
      cameraHandler.sendMessage(Message.obtain(cameraHandler, CAM_ACTION_CREATE, surfaceTexture));
      return;
    }

    surfaceTexture.setDefaultBufferSize(mPreviewWidth, mPreviewHeight);
    mCameraSurface = surfaceTexture;
    context.openCameraIfStillNeeded(this);

   /* AndroidUtilities.runOnUIThread(new Runnable() {
      @Override
      public void run() {
        if (cameraThread == null) {
          return;
        }
        FileLog.i("create camera session");



        cameraSession = new CameraSession(selectedCamera, previewSize, pictureSize, ImageFormat.JPEG);
        cameraThread.setCurrentSession(cameraSession);
        CameraController.getInstance().openRound(cameraSession, surfaceTexture, new Runnable() {
          @Override
          public void run() {
            if (cameraSession != null) {
              FileLog.i("camera initied");
              cameraSession.setInitied();
            }
          }
        }, new Runnable() {
          @Override
          public void run() {
            cameraThread.setCurrentSession(cameraSession);
          }
        });
      }
    });*/
  }

  private void doReinitToNextCamera () {
    if (!initied || eglSurface == null) {
      return;
    }

    if (!egl10.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)) {
      Log.e(Log.TAG_ROUND, "eglMakeCurrent failed " + GLUtils.getEGLErrorString(egl10.eglGetError()));
      return;
    }

    if (cameraSurface != null) {
      cameraSurface.getTransformMatrix(moldSTMatrix);
      cameraSurface.setOnFrameAvailableListener(null);
      // Vibegram: the encoder fades from the last frame of this camera: a 2D copy of it, like all its frames.
      int width = copyWidth(), height = copyHeight();
      int oldCopy = width > 0 && height > 0 ? createFrameTexture(width, height) : 0;
      if (oldCopy != 0 && !copyToTexture(cameraTexture[0], oldCopy, width, height, true)) {
        GLES20.glDeleteTextures(1, new int[] {oldCopy}, 0);
        oldCopy = 0;
      }
      cameraSurface.release();
      GLES20.glDeleteTextures(1, cameraTexture, 0);
      // The previous frame keeps its own zoom: the new lens may need another software crop
      oldCameraZoom = lastEncodedZoom;
      oldCameraTexture[0] = oldCopy;
      cameraTextureAlpha = 0.0f;
      cameraTexture[0] = 0;
      VideoRecorder encoder = videoEncoder;
      if (oldCopy != 0 && encoder != null) {
        // The camera is closed until the new one gives frames: the video goes on with the blurring previous frame
        encoder.startTransitionFrames(cameraId + 1);
      }
    }
    cameraId++;
    cameraReady = false;

    GLES20.glGenTextures(1, cameraTexture, 0);
    GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, cameraTexture[0]);
    GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
    GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
    GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
    GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);

    cameraSurface = new SurfaceTexture(cameraTexture[0]);
    cameraSurface.setOnFrameAvailableListener(surfaceTexture -> requestRender());
    createCamera(cameraSurface);
  }

  private void doDestroy () {
    finish();
    if (recording) {
      videoEncoder.stopRecording(finishCapture ? 1 : 0);
    }
  }

  public boolean isCapturing () {
    return isCapturing || finishCapture || recording;
  }

  private void onDraw(Integer cameraId) {
    if (!initied || eglSurface == null) {
      return;
    }

    if (!eglContext.equals(egl10.eglGetCurrentContext()) || !eglSurface.equals(egl10.eglGetCurrentSurface(EGL10.EGL_DRAW))) {
      if (!egl10.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)) {
        Log.e(Log.TAG_ROUND, "eglMakeCurrent failed " + GLUtils.getEGLErrorString(egl10.eglGetError()));
        return;
      }
    }
    cameraSurface.updateTexImage();
    cameraSurface.getTransformMatrix(mSTMatrix);
    if (RoundRecordStats.active) {
      RoundRecordStats.renders.incrementAndGet();
    }

    if (isCapturing()) {
      if (!recording) {
        // Vibegram: settings (0 = max) capped by the encoder and by what the camera really delivers.
        RoundVideoQuality quality = RoundVideoQuality.forEncoder(mPreviewWidth, mPreviewHeight);
        videoEncoder.startRecording(cameraFile, quality, EGL14.eglGetCurrentContext());
        recordStartTime = SystemClock.uptimeMillis();
        recording = true;
        RoundRecordStats.start();
        int orientation = rotationAngle;
        if (orientation == 90 || orientation == 270) {
          float temp = scaleX;
          scaleX = scaleY;
          scaleY = temp;
        }
        dispatchVideoRecordStarted(workingKey, recordStartTime);
      }

      // Vibegram: only frames of an open camera during the capture itself (see cameraDetachedFromEncoder).
      if (isCapturing && !cameraDetachedFromEncoder) {
        // The encoder draws its own copy of the frame: the camera texture is free for the next one at once.
        int slot = videoEncoder.acquireFrameSlot(ENCODER_WAIT_MS);
        if (slot != -1 && copyCameraFrame(slot)) {
          videoEncoder.frameAvailable(cameraSurface, cameraId);
        } else if (RoundRecordStats.active) {
          RoundRecordStats.dropped.incrementAndGet();
        }
      }
    }

    GLES20.glUseProgram(drawProgram);
    GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
    GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, cameraTexture[0]);

    GLES20.glVertexAttribPointer(positionHandle, 3, GLES20.GL_FLOAT, false, 12, vertexBuffer);
    GLES20.glEnableVertexAttribArray(positionHandle);

    GLES20.glVertexAttribPointer(textureHandle, 2, GLES20.GL_FLOAT, false, 8, textureBuffer);
    GLES20.glEnableVertexAttribArray(textureHandle);

    GLES20.glUniformMatrix4fv(textureMatrixHandle, 1, false, mSTMatrix, 0);
    GLES20.glUniformMatrix4fv(vertexMatrixHandle, 1, false, mMVPMatrix, 0);
    if (zoomHandle != -1) {
      GLES20.glUniform1f(zoomHandle, context.getSoftwareZoom());
    }

    GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);

    GLES20.glDisableVertexAttribArray(positionHandle);
    GLES20.glDisableVertexAttribArray(textureHandle);
    GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, 0);
    GLES20.glUseProgram(0);

    egl10.eglSwapBuffers(eglDisplay, eglSurface);
  }

  private long recordStartTime;

  // Vibegram: frames for the encoder. It drew straight from the camera texture, on its own thread: the camera
  // thread had to keep the texture until the encoder drew it (or the frame was lost), and the two threads took
  // turns instead of working in parallel. At 60 fps that lost 7-23 of 58 frames a second. Now the camera thread
  // copies each frame into one of a few textures (a cheap GPU copy) and goes on, the encoder draws the copies.

  /** Frames the encoder may lag behind the camera before frames are dropped. */
  private static final int FRAME_RING_SIZE = 8;
  /** When the encoder is that far behind: wait a bit for it, not long enough to freeze the preview. */
  private static final long ENCODER_WAIT_MS = 12;
  private static final float[] IDENTITY_MATRIX = new float[16];
  static {
    android.opengl.Matrix.setIdentityM(IDENTITY_MATRIX, 0);
  }
  private static final String FRAGMENT_COPY_SHADER =
    "#extension GL_OES_EGL_image_external : require\n" +
      "precision highp float;\n" +
      "varying vec2 vTextureCoord;\n" +
      "uniform samplerExternalOES sTexture;\n" +
      "void main() {\n" +
      "   gl_FragColor = texture2D(sTexture, vTextureCoord);\n" +
      "}\n";

  private final int[] frameRing = new int[FRAME_RING_SIZE];
  /** Transform of the camera texture for each copy: the encoder applies it like it did to the camera texture. */
  private final float[][] frameRingMatrix = new float[FRAME_RING_SIZE][16];
  // GPU fences (OpenGL ES 3) instead of glFinish(), which made each thread wait for the whole GPU queue:
  // copyFence[slot]: the copy is written, the encoder GPU waits for it (glWaitSync, no CPU wait);
  // readFence[slot]: the encoder has read the copy, the camera waits for it before writing that slot again.
  private final long[] copyFence = new long[FRAME_RING_SIZE];
  private final long[] readFence = new long[FRAME_RING_SIZE];
  private volatile boolean cameraGles3;

  private static boolean isGles3 () {
    String version = GLES20.glGetString(GLES20.GL_VERSION);
    return version != null && version.startsWith("OpenGL ES 3");
  }

  private boolean useFences () {
    return cameraGles3 && videoEncoder != null && videoEncoder.encoderGles3;
  }
  private int frameRingWidth, frameRingHeight;
  private final int[] copyFramebuffer = new int[1];
  private int copyProgram;
  private int copyPositionHandle, copyTextureHandle, copyVertexMatrixHandle, copyTextureMatrixHandle;
  private FloatBuffer fullTextureBuffer;
  private final int[] savedViewport = new int[4];

  private void resetFrameCopies () {
    // The objects belonged to a destroyed context.
    java.util.Arrays.fill(frameRing, 0);
    java.util.Arrays.fill(copyFence, 0);
    java.util.Arrays.fill(readFence, 0);
    frameRingWidth = frameRingHeight = 0;
    copyFramebuffer[0] = 0;
    copyProgram = 0;
  }

  /** Size of the copies: the camera buffer, landscape like all camera output sizes. */
  private int copyWidth () {
    return Math.max(mPreviewWidth, mPreviewHeight);
  }

  private int copyHeight () {
    return Math.min(mPreviewWidth, mPreviewHeight);
  }

  private boolean ensureCopyProgram () {
    if (copyProgram != 0) {
      return true;
    }
    int vertexShader = loadShader(GLES20.GL_VERTEX_SHADER, VERTEX_SHADER);
    int fragmentShader = loadShader(GLES20.GL_FRAGMENT_SHADER, FRAGMENT_COPY_SHADER);
    if (vertexShader == 0 || fragmentShader == 0) {
      return false;
    }
    int program = GLES20.glCreateProgram();
    GLES20.glAttachShader(program, vertexShader);
    GLES20.glAttachShader(program, fragmentShader);
    GLES20.glLinkProgram(program);
    int[] linkStatus = new int[1];
    GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, linkStatus, 0);
    if (linkStatus[0] == 0) {
      Log.e(Log.TAG_ROUND, "Copy program link failed");
      GLES20.glDeleteProgram(program);
      return false;
    }
    copyPositionHandle = GLES20.glGetAttribLocation(program, "aPosition");
    copyTextureHandle = GLES20.glGetAttribLocation(program, "aTextureCoord");
    copyVertexMatrixHandle = GLES20.glGetUniformLocation(program, "uMVPMatrix");
    copyTextureMatrixHandle = GLES20.glGetUniformLocation(program, "uSTMatrix");
    float[] texData = {0f, 0f, 1f, 0f, 0f, 1f, 1f, 1f};
    fullTextureBuffer = ByteBuffer.allocateDirect(texData.length * 4).order(ByteOrder.nativeOrder()).asFloatBuffer();
    fullTextureBuffer.put(texData).position(0);
    GLES20.glGenFramebuffers(1, copyFramebuffer, 0);
    copyProgram = program;
    return true;
  }

  private static int createFrameTexture (int width, int height) {
    int[] texture = new int[1];
    GLES20.glGenTextures(1, texture, 0);
    GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texture[0]);
    GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
    GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
    GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
    GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
    GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, width, height, 0, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null);
    GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0);
    return texture[0];
  }

  /** Camera thread: copies the current camera frame into the slot of the ring. */
  private boolean copyCameraFrame (int slot) {
    int width = copyWidth(), height = copyHeight();
    if (width <= 0 || height <= 0) {
      return false;
    }
    if (frameRingWidth != width || frameRingHeight != height) {
      for (int i = 0; i < FRAME_RING_SIZE; i++) {
        if (frameRing[i] != 0) {
          GLES20.glDeleteTextures(1, frameRing, i);
        }
        frameRing[i] = createFrameTexture(width, height);
      }
      frameRingWidth = width;
      frameRingHeight = height;
    }
    System.arraycopy(mSTMatrix, 0, frameRingMatrix[slot], 0, 16);
    boolean fences = useFences();
    long fence;
    synchronized (videoEncoder.frameLock) {
      fence = readFence[slot];
      readFence[slot] = 0;
    }
    if (fence != 0) {
      // The encoder issued its drawing of this slot: wait until the GPU has read it (usually done already).
      GLES30.glClientWaitSync(fence, 0, 50_000_000L);
      GLES30.glDeleteSync(fence);
    }
    if (!copyToTexture(cameraTexture[0], frameRing[slot], width, height, !fences)) {
      return false;
    }
    if (copyFence[slot] != 0) {
      // Left by a frame the encoder skipped without drawing (the first 200 ms).
      GLES30.glDeleteSync(copyFence[slot]);
      copyFence[slot] = 0;
    }
    if (fences) {
      copyFence[slot] = GLES30.glFenceSync(GLES30.GL_SYNC_GPU_COMMANDS_COMPLETE, 0);
      // The fence is waited for from the encoder context: it has to reach the GPU.
      GLES20.glFlush();
    }
    return true;
  }

  /**
   * Copies the camera buffer as it is (not through its transform) into a 2D texture: sampled with the camera
   * transform, the copy gives what the camera texture gave, round mask and zoom included.
   */
  private boolean copyToTexture (int cameraTextureId, int target, int width, int height, boolean finish) {
    if (!ensureCopyProgram()) {
      return false;
    }
    GLES20.glGetIntegerv(GLES20.GL_VIEWPORT, savedViewport, 0);
    GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, copyFramebuffer[0]);
    GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0, GLES20.GL_TEXTURE_2D, target, 0);
    boolean complete = GLES20.glCheckFramebufferStatus(GLES20.GL_FRAMEBUFFER) == GLES20.GL_FRAMEBUFFER_COMPLETE;
    if (complete) {
      GLES20.glViewport(0, 0, width, height);
      GLES20.glUseProgram(copyProgram);
      GLES20.glVertexAttribPointer(copyPositionHandle, 3, GLES20.GL_FLOAT, false, 12, vertexBuffer);
      GLES20.glEnableVertexAttribArray(copyPositionHandle);
      GLES20.glVertexAttribPointer(copyTextureHandle, 2, GLES20.GL_FLOAT, false, 8, fullTextureBuffer);
      GLES20.glEnableVertexAttribArray(copyTextureHandle);
      GLES20.glUniformMatrix4fv(copyVertexMatrixHandle, 1, false, IDENTITY_MATRIX, 0);
      GLES20.glUniformMatrix4fv(copyTextureMatrixHandle, 1, false, IDENTITY_MATRIX, 0);
      GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
      GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, cameraTextureId);
      GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
      GLES20.glDisableVertexAttribArray(copyPositionHandle);
      GLES20.glDisableVertexAttribArray(copyTextureHandle);
      GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, 0);
      GLES20.glUseProgram(0);
    } else {
      Log.e(Log.TAG_ROUND, "Frame copy framebuffer incomplete");
    }
    GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0);
    GLES20.glViewport(savedViewport[0], savedViewport[1], savedViewport[2], savedViewport[3]);
    if (finish) {
      // The encoder reads the copy from its own context: it has to be complete before the frame is queued.
      GLES20.glFinish();
    }
    return complete;
  }
  private boolean recording;

  private void doStartRecord () {
    if (!checkCameraThread()) {
      cameraHandler.sendMessage(Message.obtain(cameraHandler, CAM_ACTION_START));
      return;
    }
    // Nothing?
  }

  private static final int MSG_START_RECORDING = 0;
  private static final int MSG_STOP_RECORDING = 1;
  private static final int MSG_VIDEOFRAME_AVAILABLE = 2;
  private static final int MSG_AUDIOFRAME_AVAILABLE = 3;
  private static final int MSG_TRANSITION_FRAME = 4;

  private static class EncoderHandler extends Handler {
    private WeakReference<VideoRecorder> mWeakEncoder;

    public EncoderHandler(VideoRecorder encoder) {
      super(Looper.myLooper());
      mWeakEncoder = new WeakReference<>(encoder);
    }

    @Override
    public void handleMessage(Message inputMessage) {
      int what = inputMessage.what;

      VideoRecorder encoder = mWeakEncoder.get();
      if (encoder == null) {
        return;
      }

      switch (what) {
        case MSG_START_RECORDING: {
          try {
            Log.i(Log.TAG_ROUND, "start encoder");
            encoder.prepareEncoder();
          } catch (Exception e) {
            Log.e(Log.TAG_ROUND, "Error", e);
            encoder.handleStopRecording(0);
            Looper.myLooper().quit();
          }
          break;
        }
        case MSG_STOP_RECORDING: {
          Log.i(Log.TAG_ROUND, "stop encoder");
          encoder.handleStopRecording(inputMessage.arg1);
          break;
        }
        case MSG_VIDEOFRAME_AVAILABLE: {
          long timestamp = (((long) inputMessage.arg1) << 32) | (((long) inputMessage.arg2) & 0xffffffffL);
          Integer cameraId = (Integer) inputMessage.obj;
          try {
            encoder.handleVideoFrameAvailable(timestamp, cameraId);
          } finally {
            encoder.onFrameConsumed();
          }
          break;
        }
        case MSG_AUDIOFRAME_AVAILABLE: {
          encoder.handleAudioFrameAvailable((AudioBufferInfo) inputMessage.obj);
          break;
        }
        case MSG_TRANSITION_FRAME: {
          encoder.handleTransitionFrame(inputMessage.arg1);
          break;
        }
      }
    }

    public void exit() {
      Looper.myLooper().quit();
    }
  }

  private static class AudioBufferInfo {
    byte[] buffer = new byte[2048 * 10];
    long[] offset = new long[10];
    int[] read = new int[10];
    int results;
    int lastWroteBuffer;
    boolean last;
  }

  private class VideoRecorder implements Runnable {

    private static final String VIDEO_MIME_TYPE = "video/avc";
    private static final String AUDIO_MIME_TYPE = "audio/mp4a-latm";
    private static final int IFRAME_INTERVAL = 1;

    private File videoFile;
    private RoundVideoQuality quality;
    private int videoWidth;
    private int videoHeight;
    private boolean videoConvertFirstWrite = true;
    private boolean blendEnabled;

    private Surface surface;
    private android.opengl.EGLDisplay eglDisplay = EGL14.EGL_NO_DISPLAY;
    private android.opengl.EGLContext eglContext = EGL14.EGL_NO_CONTEXT;
    private android.opengl.EGLContext sharedEglContext;
    private android.opengl.EGLConfig eglConfig;
    private android.opengl.EGLSurface eglSurface = EGL14.EGL_NO_SURFACE;

    private MediaCodec videoEncoder;
    private MediaCodec audioEncoder;

    private MediaCodec.BufferInfo videoBufferInfo;
    private MediaCodec.BufferInfo audioBufferInfo;
    private Mp4Output mediaMuxer;
    private ArrayList<AudioBufferInfo> buffersToWrite = new ArrayList<>();
    private int videoTrackIndex = -5;
    private int audioTrackIndex = -5;

    private long lastCommitedFrameTime;
    private long audioStartTime = -1;

    private long currentTimestamp = 0;
    private long lastTimestamp = -1;
    private volatile long lastQueuedTimestamp;

    // Vibegram: frames reach the encoder through a ring of copies (see copyCameraFrame): the camera thread
    // copies frame N into slot N % FRAME_RING_SIZE, the encoder thread draws the slots in the same order.
    final Object frameLock = new Object();
    volatile boolean encoderGles3;
    private int queuedFrames, consumedFrames;

    void onFrameConsumed () {
      synchronized (frameLock) {
        consumedFrames++;
        frameLock.notifyAll();
      }
    }

    /** Encoder thread: the slot of the frame being handled. */
    private int currentFrameSlot () {
      synchronized (frameLock) {
        return consumedFrames % FRAME_RING_SIZE;
      }
    }

    /**
     * Camera thread: the slot for the next frame, waiting at most timeoutMs while all slots wait for the encoder;
     * -1 when it is still behind (the frame is dropped, the preview goes on).
     */
    int acquireFrameSlot (long timeoutMs) {
      synchronized (frameLock) {
        if (queuedFrames - consumedFrames < FRAME_RING_SIZE) {
          return queuedFrames % FRAME_RING_SIZE;
        }
        final long waitStart = System.nanoTime();
        long deadline = SystemClock.uptimeMillis() + timeoutMs;
        while (queuedFrames - consumedFrames >= FRAME_RING_SIZE) {
          long left = deadline - SystemClock.uptimeMillis();
          if (left <= 0) {
            break;
          }
          try {
            frameLock.wait(left);
          } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            break;
          }
        }
        boolean full = queuedFrames - consumedFrames >= FRAME_RING_SIZE;
        RoundRecordStats.onWait(System.nanoTime() - waitStart, full);
        return full ? -1 : queuedFrames % FRAME_RING_SIZE;
      }
    }

    private volatile EncoderHandler handler;

    private final Object sync = new Object();
    private boolean ready;
    private volatile boolean running;
    private volatile int sendWhenDone;
    private long skippedTime;
    private boolean skippedFirst;

    private int drawProgram;
    private int vertexMatrixHandle;
    private int textureMatrixHandle;
    private int positionHandle;
    private int textureHandle;
    private int scaleXHandle;
    private int scaleYHandle;
    private int alphaHandle;
    private int texelOffsetHandle = -1;
    private int encoderZoomHandle = -1;
    private int blurProgram;
    private int blurPositionHandle, blurTextureHandle, blurScaleXHandle, blurScaleYHandle, blurAlphaHandle, blurZoomHandle,
      blurRadiusHandle, blurVertexMatrixHandle, blurTextureMatrixHandle;

    private Integer lastCameraId = 0;

    private AudioRecord audioRecorder;

    private ArrayBlockingQueue<AudioBufferInfo> buffers = new ArrayBlockingQueue<>(10);

    private Runnable recorderRunnable = new Runnable() {

      @Override
      public void run() {
        long audioPresentationTimeNs;
        int readResult;
        boolean done = false;
        while (!done) {
          if (!running && audioRecorder.getRecordingState() != AudioRecord.RECORDSTATE_STOPPED) {
            try {
              audioRecorder.stop();
            } catch (Exception e) {
              done = true;
            }
            if (sendWhenDone == 0) {
              break;
            }
          }
          AudioBufferInfo buffer;
          if (buffers.isEmpty()) {
            buffer = new AudioBufferInfo();
          } else {
            buffer = buffers.poll();
          }
          buffer.lastWroteBuffer = 0;
          buffer.results = 10;
          for (int a = 0; a < 10; a++) {
            audioPresentationTimeNs = System.nanoTime();
            readResult = audioRecorder.read(buffer.buffer, a * 2048, 2048);
            if (readResult <= 0) {
              buffer.results = a;
              if (!running) {
                buffer.last = true;
              }
              break;
            }
            buffer.offset[a] = audioPresentationTimeNs;
            buffer.read[a] = readResult;
          }
          if (buffer.results >= 0 || buffer.last) {
            if (!running && buffer.results < 10) {
              done = true;
            }
            handler.sendMessage(handler.obtainMessage(MSG_AUDIOFRAME_AVAILABLE, buffer));
          } else {
            if (!running) {
              done = true;
            } else {
              try {
                buffers.put(buffer);
              } catch (Exception ignore) {

              }
            }
          }
        }
        try {
          audioRecorder.release();
        } catch (Exception e) {
          Log.e(Log.TAG_ROUND, "Error", e);
        }
        handler.sendMessage(handler.obtainMessage(MSG_STOP_RECORDING, sendWhenDone, 0));
      }
    };

    public void startRecording(File outputFile, RoundVideoQuality quality, android.opengl.EGLContext sharedContext) {
      videoFile = outputFile;
      this.quality = quality;
      videoWidth = quality.side;
      videoHeight = quality.side;
      sharedEglContext = sharedContext;
      synchronized (frameLock) {
        queuedFrames = consumedFrames = 0;
      }

      synchronized (sync) {
        if (running) {
          return;
        }
        running = true;
        new Thread(this, "TextureMovieEncoder").start();
        while (!ready) {
          try {
            sync.wait();
          } catch (InterruptedException ie) {
            // ignore
          }
        }
      }
      handler.sendMessage(handler.obtainMessage(MSG_START_RECORDING));
    }

    public void stopRecording(int send) {
      handler.sendMessage(handler.obtainMessage(MSG_STOP_RECORDING, send, 0));
    }

    public void frameAvailable(SurfaceTexture st, Integer cameraId) {
      synchronized (sync) {
        if (!ready) {
          return;
        }
      }

      long timestamp = st.getTimestamp();
      // Vibegram: a render without a new camera frame (several frame callbacks, one updateTexImage) repeats
      // the timestamp: such a frame went to the encoder as a duplicate with a zero duration.
      if (timestamp == 0 || timestamp == lastQueuedTimestamp) {
        return;
      }
      lastQueuedTimestamp = timestamp;
      synchronized (frameLock) {
        queuedFrames++;
      }
      if (RoundRecordStats.active) {
        RoundRecordStats.queued.incrementAndGet();
      }

      handler.sendMessage(handler.obtainMessage(MSG_VIDEOFRAME_AVAILABLE, (int) (timestamp >> 32), (int) timestamp, cameraId));
    }

    @Override
    public void run() {
      Looper.prepare();
      synchronized (sync) {
        handler = new EncoderHandler(this);
        ready = true;
        sync.notify();
      }
      Looper.loop();

      synchronized (sync) {
        ready = false;
      }
    }

    private void handleAudioFrameAvailable(AudioBufferInfo input) {
      if (audioStartTime == -1) {
        audioStartTime = input.offset[0];
      }
      buffersToWrite.add(input);
      if (buffersToWrite.size() > 1) {
        input = buffersToWrite.get(0);
      }
      try {
        drainEncoder(false);
      } catch (Exception e) {
        Log.e(Log.TAG_ROUND, "Error", e);
      }
      try {
        boolean isLast = false;
        while (input != null) {
          int inputBufferIndex = audioEncoder.dequeueInputBuffer(0);
          if (inputBufferIndex >= 0) {
            ByteBuffer inputBuffer;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
              inputBuffer = audioEncoder.getInputBuffer(inputBufferIndex);
            } else {
              ByteBuffer[] inputBuffers = audioEncoder.getInputBuffers();
              inputBuffer = inputBuffers[inputBufferIndex];
              inputBuffer.clear();
            }
            long startWriteTime = input.offset[input.lastWroteBuffer];
            for (int a = input.lastWroteBuffer; a <= input.results; a++) {
              if (a < input.results) {
                if (inputBuffer.remaining() < input.read[a]) {
                  input.lastWroteBuffer = a;
                  input = null;
                  break;
                }
                inputBuffer.put(input.buffer, a * 2048, input.read[a]);
              }
              if (a >= input.results - 1) {
                buffersToWrite.remove(input);
                if (running) {
                  buffers.put(input);
                }
                if (!buffersToWrite.isEmpty()) {
                  input = buffersToWrite.get(0);
                } else {
                  isLast = input.last;
                  input = null;
                  break;
                }
              }
            }
            audioEncoder.queueInputBuffer(inputBufferIndex, 0, inputBuffer.position(), startWriteTime == 0 ? 0 : (startWriteTime - audioStartTime) / 1000, isLast ? MediaCodec.BUFFER_FLAG_END_OF_STREAM : 0);
          }
        }
      } catch (Throwable e) {
        Log.e(Log.TAG_ROUND, "Error", e);
      }
    }

    private void handleVideoFrameAvailable(long timestampNanos, Integer cameraId) {
      // Vibegram: frames are drawn from the shared camera texture when this thread gets to them, not when they
      // arrived. Queued frames handled after the camera was closed drew its replacement, an empty texture
      // fading in: the green and black end of the video (seen at 60 fps, where this thread falls behind).
      if (cameraDetachedFromEncoder) {
        return;
      }
      final long handleStart = System.nanoTime();
      if (RoundRecordStats.active) {
        RoundRecordStats.handled.incrementAndGet();
      }
      try {
        drainEncoder(false);
      } catch (Exception e) {
        Log.e(Log.TAG_ROUND, "Error", e);
      }
      long dt, alphaDt;
      if (!lastCameraId.equals(cameraId)) {
        lastTimestamp = -1;
        lastCameraId = cameraId;
      }
      if (lastTimestamp == -1) {
        lastTimestamp = timestampNanos;
        if (currentTimestamp != 0) {
          dt = (SystemClock.uptimeMillis() - lastCommitedFrameTime) * 1000000;
          alphaDt = 0;
        } else {
          alphaDt = dt = 0;
        }
      } else {
        alphaDt = dt = (timestampNanos - lastTimestamp);
        lastTimestamp = timestampNanos;
      }
      lastCommitedFrameTime = SystemClock.uptimeMillis();
      if (!skippedFirst) {
        skippedTime += dt;
        if (skippedTime < 200000000) {
          return;
        }
        skippedFirst = true;
      }
      currentTimestamp += dt;

      // Vibegram: each queued frame has its own copy (see copyCameraFrame), drawn with its own time.
      int frameSlot = currentFrameSlot();
      int frameTexture = frameRing[frameSlot];
      long frameCopyFence = copyFence[frameSlot];
      copyFence[frameSlot] = 0;
      if (frameCopyFence != 0) {
        // The GPU of this context waits for the copy; this thread goes on.
        GLES30.glWaitSync(frameCopyFence, 0, GLES30.GL_TIMEOUT_IGNORED);
        GLES30.glDeleteSync(frameCopyFence);
      }

      if (oldCameraTexture[0] != 0 && blurProgram != 0) {
        drawTransitionFrame(frameSlot, frameTexture);
        RoundRecordStats.onDrawn(0, System.nanoTime() - handleStart);
        advanceCameraTransition(alphaDt);
        return;
      }
      transitionStart = -1;
      lastEncodedZoom = context.getSoftwareZoom();

      GLES20.glUseProgram(drawProgram);
      GLES20.glVertexAttribPointer(positionHandle, 3, GLES20.GL_FLOAT, false, 12, vertexBuffer);
      GLES20.glEnableVertexAttribArray(positionHandle);
      GLES20.glVertexAttribPointer(textureHandle, 2, GLES20.GL_FLOAT, false, 8, textureBuffer);
      GLES20.glEnableVertexAttribArray(textureHandle);
      GLES20.glUniform1f(scaleXHandle, scaleX);
      GLES20.glUniform1f(scaleYHandle, scaleY);
      if (texelOffsetHandle != -1) {
        GLES20.glUniform2f(texelOffsetHandle, texelOffsetX, texelOffsetY);
      }
      if (encoderZoomHandle != -1) {
        GLES20.glUniform1f(encoderZoomHandle, context.getSoftwareZoom());
      }
      GLES20.glUniformMatrix4fv(vertexMatrixHandle, 1, false, mMVPMatrix, 0);

      GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
      if (oldCameraTexture[0] != 0) {
        if (!blendEnabled) {
          GLES20.glEnable(GLES20.GL_BLEND);
          blendEnabled = true;
        }
        GLES20.glUniformMatrix4fv(textureMatrixHandle, 1, false, moldSTMatrix, 0);
        GLES20.glUniform1f(alphaHandle, 1.0f);
        if (encoderZoomHandle != -1) {
          GLES20.glUniform1f(encoderZoomHandle, oldCameraZoom);
        }
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, oldCameraTexture[0]);
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
        if (encoderZoomHandle != -1) {
          GLES20.glUniform1f(encoderZoomHandle, context.getSoftwareZoom());
        }
      }
      GLES20.glUniformMatrix4fv(textureMatrixHandle, 1, false, frameRingMatrix[frameSlot], 0);
      GLES20.glUniform1f(alphaHandle, cameraTextureAlpha);
      GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, frameTexture);
      GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);

      GLES20.glDisableVertexAttribArray(positionHandle);
      GLES20.glDisableVertexAttribArray(textureHandle);
      GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0);
      GLES20.glUseProgram(0);

      // Log.i(Log.TAG_ROUND, "frame time = " + currentTimestamp);
      EGLExt.eglPresentationTimeANDROID(eglDisplay, eglSurface, currentTimestamp);
      if (encoderGles3 && cameraGles3) {
        // The camera waits for this before reusing the slot (see copyCameraFrame).
        long fence = GLES30.glFenceSync(GLES30.GL_SYNC_GPU_COMMANDS_COMPLETE, 0);
        synchronized (frameLock) {
          readFence[frameSlot] = fence;
        }
      }
      final long swapStart = System.nanoTime();
      EGL14.eglSwapBuffers(eglDisplay, eglSurface);
      if (!(encoderGles3 && cameraGles3)) {
        // The swap only queues the drawing: let it read the copy before the camera thread reuses its slot.
        GLES20.glFinish();
      }
      long drawnTime = System.nanoTime();
      RoundRecordStats.onDrawn(drawnTime - swapStart, drawnTime - handleStart);

      advanceCameraTransition(alphaDt);
    }

    // Lens switch in the video: the previous frame blurs (also while the camera reopens, see handleTransitionFrame),
    // stays until the new lens has settled its exposure (CameraApi.isCameraSettled), then the new lens fades in
    // from blurred to sharp. Without the blur program: the original 200 ms crossfade.

    /** currentTimestamp when the previous frame started to blur, -1 out of a switch. */
    private long transitionStart = -1;

    private float transitionBlurIn () {
      if (transitionStart == -1) {
        transitionStart = currentTimestamp;
      }
      return Math.min(1f, (float) (currentTimestamp - transitionStart) / (float) SWITCH_BLUR_IN_NS);
    }

    void startTransitionFrames (int newCameraId) {
      Handler handler = this.handler;
      if (handler != null && running) {
        handler.removeMessages(MSG_TRANSITION_FRAME);
        handler.sendMessage(handler.obtainMessage(MSG_TRANSITION_FRAME, newCameraId, 0));
      }
    }

    /** Frames of the blurring previous frame while no camera gives frames (~60 a second, real time). */
    private void handleTransitionFrame (int newCameraId) {
      if (!running || blurProgram == 0 || oldCameraTexture[0] == 0 || lastCameraId == newCameraId || currentTimestamp == 0 || !skippedFirst) {
        return;
      }
      long now = SystemClock.uptimeMillis();
      // The camera may have stopped a few frames before the switch: the gap is filled with 60 fps frames too
      // (a single long frame is a visible freeze in the video)
      for (int i = 0; i < 12 && now - lastCommitedFrameTime >= 15; i++) {
        try {
          drainEncoder(false);
        } catch (Exception e) {
          Log.e(Log.TAG_ROUND, "Error", e);
        }
        long step = now - lastCommitedFrameTime >= 33 ? 16 : now - lastCommitedFrameTime;
        currentTimestamp += step * 1000000L;
        lastCommitedFrameTime += step;
        drawTransitionFrame(-1, 0);
      }
      if (now - lastCommitedFrameTime >= 15) {
        currentTimestamp += (now - lastCommitedFrameTime) * 1000000L;
        lastCommitedFrameTime = now;
      }
      handler.sendMessageDelayed(handler.obtainMessage(MSG_TRANSITION_FRAME, newCameraId, 0), 16);
    }

    private void setupBlurDraw (float alpha, float blur, float zoom) {
      GLES20.glUseProgram(blurProgram);
      GLES20.glVertexAttribPointer(blurPositionHandle, 3, GLES20.GL_FLOAT, false, 12, vertexBuffer);
      GLES20.glEnableVertexAttribArray(blurPositionHandle);
      GLES20.glVertexAttribPointer(blurTextureHandle, 2, GLES20.GL_FLOAT, false, 8, textureBuffer);
      GLES20.glEnableVertexAttribArray(blurTextureHandle);
      GLES20.glUniform1f(blurScaleXHandle, scaleX);
      GLES20.glUniform1f(blurScaleYHandle, scaleY);
      GLES20.glUniform1f(blurAlphaHandle, alpha);
      GLES20.glUniform1f(blurZoomHandle, zoom);
      int side = Math.min(mPreviewWidth, mPreviewHeight);
      float rx = mPreviewWidth > 0 ? SWITCH_BLUR_RADIUS * blur * side / mPreviewWidth : 0f;
      float ry = mPreviewHeight > 0 ? SWITCH_BLUR_RADIUS * blur * side / mPreviewHeight : 0f;
      GLES20.glUniform2f(blurRadiusHandle, rx, ry);
      GLES20.glUniformMatrix4fv(blurVertexMatrixHandle, 1, false, mMVPMatrix, 0);
    }

    /** The previous frame blurred, and over it the new frame (frameSlot != -1) fading in from blurred to sharp. */
    private void drawTransitionFrame (int frameSlot, int frameTexture) {
      float blurIn = transitionBlurIn();
      // Smooth start and end
      float oldBlur = blurIn * blurIn * (3f - 2f * blurIn);
      GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
      if (!blendEnabled) {
        GLES20.glEnable(GLES20.GL_BLEND);
        blendEnabled = true;
      }
      setupBlurDraw(1f, oldBlur, oldCameraZoom);
      GLES20.glUniformMatrix4fv(blurTextureMatrixHandle, 1, false, moldSTMatrix, 0);
      GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, oldCameraTexture[0]);
      GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
      if (frameSlot != -1 && cameraTextureAlpha > 0f) {
        float fade = cameraTextureAlpha * cameraTextureAlpha * (3f - 2f * cameraTextureAlpha);
        float zoom = context.getSoftwareZoom();
        lastEncodedZoom = zoom;
        setupBlurDraw(fade, oldBlur * (1f - fade), zoom);
        GLES20.glUniformMatrix4fv(blurTextureMatrixHandle, 1, false, frameRingMatrix[frameSlot], 0);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, frameTexture);
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
      }
      GLES20.glDisableVertexAttribArray(blurPositionHandle);
      GLES20.glDisableVertexAttribArray(blurTextureHandle);
      GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0);
      GLES20.glUseProgram(0);

      EGLExt.eglPresentationTimeANDROID(eglDisplay, eglSurface, currentTimestamp);
      if (frameSlot != -1 && encoderGles3 && cameraGles3) {
        long fence = GLES30.glFenceSync(GLES30.GL_SYNC_GPU_COMMANDS_COMPLETE, 0);
        synchronized (frameLock) {
          readFence[frameSlot] = fence;
        }
      }
      EGL14.eglSwapBuffers(eglDisplay, eglSurface);
      if (!(encoderGles3 && cameraGles3)) {
        GLES20.glFinish();
      }
    }

    private void advanceCameraTransition (long alphaDt) {
      if (oldCameraTexture[0] != 0 && blurProgram != 0 && (!context.isCameraSettled() || transitionBlurIn() < 1f)) {
        // The new lens is still settling its exposure, or the previous frame is still blurring: it stays
        return;
      }
      if (oldCameraTexture[0] != 0 && cameraTextureAlpha < 1.0f) {
        cameraTextureAlpha += alphaDt / (blurProgram != 0 ? SWITCH_FADE_NS : 200000000.0f);
        if (cameraTextureAlpha > 1) {
          GLES20.glDisable(GLES20.GL_BLEND);
          blendEnabled = false;
          cameraTextureAlpha = 1;
          isSwitchingToNewCamera = false;
          GLES20.glDeleteTextures(1, oldCameraTexture, 0);
          oldCameraTexture[0] = 0;
          if (!cameraReady) {
            cameraReady = true;
          }
        }
      } else if (!cameraReady) {
        cameraReady = true;
      }
    }

    private void handleStopRecording(final int send) {
      if (running) {
        sendWhenDone = send;
        running = false;
        return;
      }
      try {
        drainEncoder(true);
      } catch (Exception e) {
        Log.e(Log.TAG_ROUND, "Error", e);
      }
      if (videoEncoder != null) {
        try {
          videoEncoder.stop();
          videoEncoder.release();
          videoEncoder = null;
        } catch (Exception e) {
          Log.e(Log.TAG_ROUND, "Error", e);
        }
      }
      if (audioEncoder != null) {
        try {
          audioEncoder.stop();
          audioEncoder.release();
          audioEncoder = null;
        } catch (Exception e) {
          Log.e(Log.TAG_ROUND, "Error", e);
        }
      }
      if (mediaMuxer != null) {
        try {
          mediaMuxer.finishMovie();
        } catch (Exception e) {
          Log.e(Log.TAG_ROUND, "Error", e);
        }
      }
      if (send != 0) {
        UI.post(() -> didWriteData(videoFile, true));
      } else {
        // FileLoader.getInstance().cancelUploadFile(videoFile.getAbsolutePath(), false);
        videoFile.delete();
        dispatchVideoRecordFinished(workingKey, -1, -1, null);
      }
      EGL14.eglDestroySurface(eglDisplay, eglSurface);
      eglSurface = EGL14.EGL_NO_SURFACE;
      if (surface != null) {
        surface.release();
        surface = null;
      }
      if (eglDisplay != EGL14.EGL_NO_DISPLAY) {
        EGL14.eglMakeCurrent(eglDisplay, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT);
        EGL14.eglDestroyContext(eglDisplay, eglContext);
        EGL14.eglReleaseThread();
        EGL14.eglTerminate(eglDisplay);
      }
      eglDisplay = EGL14.EGL_NO_DISPLAY;
      eglContext = EGL14.EGL_NO_CONTEXT;
      eglConfig = null;
      handler.exit();
    }

    private void prepareEncoder() {
      try {
        for (int a = 0; a < 3; a++) {
          buffers.add(new AudioBufferInfo());
        }
        AudioRecord audioRecorderFinal = null;
        int[] sampleRates = audioSampleRates(quality.audioSampleRate);
        int sampleRate = sampleRates[0];
        for (int newSampleRate : sampleRates) {
          sampleRate = newSampleRate;
          int recordBufferSize = AudioRecord.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
          if (recordBufferSize <= 0) {
            recordBufferSize = 3584;
          }
          int bufferSize = 2048 * 24;
          if (bufferSize < recordBufferSize) {
            bufferSize = ((recordBufferSize / 2048) + 1) * 2048 * 2;
          }
          try {
            audioRecorderFinal = new AudioRecord(MediaRecorder.AudioSource.MIC, sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bufferSize);
            audioRecorderFinal.startRecording();
            audioRecorder = audioRecorderFinal;
            break;
          } catch (Throwable t) {
            if (audioRecorderFinal != null) {
              try { audioRecorderFinal.stop(); } catch (Throwable ignored) { }
              try { audioRecorderFinal.release(); } catch (Throwable ignored) { }
            }
            audioRecorderFinal = null;
            if (sampleRate == sampleRates[sampleRates.length - 1]) {
              throw t;
            }
          }
        }
        if (audioRecorder == null) {
          throw new NullPointerException();
        }
        new Thread(recorderRunnable).start();

        audioBufferInfo = new MediaCodec.BufferInfo();
        videoBufferInfo = new MediaCodec.BufferInfo();

        startAudioEncoder(sampleRate);

        // Vibegram: if the encoder refuses the chosen quality, step down instead of losing the recording.
        RoundVideoQuality videoQuality = quality;
        while (true) {
          try {
            startVideoEncoder(videoQuality);
            break;
          } catch (Exception e) {
            RoundVideoQuality lower = videoQuality.nextLower();
            Log.w(Log.TAG_ROUND, "Video encoder failed with %s, trying %s", e, videoQuality, lower);
            if (lower == null) {
              throw e;
            }
            videoQuality = lower;
          }
        }
        quality = videoQuality;
        videoWidth = videoQuality.side;
        videoHeight = videoQuality.side;
        RoundVideoQuality.onEncoderStarted(workingKey, videoQuality);

        Mp4Movie movie = new Mp4Movie();
        movie.setCacheFile(videoFile);
        movie.setRotation(0);
        movie.setSize(videoWidth, videoHeight);
        mediaMuxer = new Mp4OutputImpl().createMovie(movie);

        doStartRecord();
      } catch (Exception ioe) {
        throw new RuntimeException(ioe);
      }

      if (eglDisplay != EGL14.EGL_NO_DISPLAY) {
        throw new RuntimeException("EGL already set up");
      }

      eglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY);
      if (eglDisplay == EGL14.EGL_NO_DISPLAY) {
        throw new RuntimeException("unable to get EGL14 display");
      }
      int[] version = new int[2];
      if (!EGL14.eglInitialize(eglDisplay, version, 0, version, 1)) {
        eglDisplay = null;
        throw new RuntimeException("unable to initialize EGL14");
      }

      if (eglContext == EGL14.EGL_NO_CONTEXT) {
        int renderableType = EGL14.EGL_OPENGL_ES2_BIT;

        int[] attribList = {
          EGL14.EGL_RED_SIZE, 8,
          EGL14.EGL_GREEN_SIZE, 8,
          EGL14.EGL_BLUE_SIZE, 8,
          EGL14.EGL_ALPHA_SIZE, 8,
          EGL14.EGL_RENDERABLE_TYPE, renderableType,
          0x3142, 1,
          EGL14.EGL_NONE
        };
        android.opengl.EGLConfig[] configs = new android.opengl.EGLConfig[1];
        int[] numConfigs = new int[1];
        if (!EGL14.eglChooseConfig(eglDisplay, attribList, 0, configs, 0, configs.length, numConfigs, 0)) {
          throw new RuntimeException("Unable to find a suitable EGLConfig");
        }

        int[] attrib2_list = {
          EGL14.EGL_CONTEXT_CLIENT_VERSION, 2,
          EGL14.EGL_NONE
        };
        eglContext = EGL14.eglCreateContext(eglDisplay, configs[0], sharedEglContext, attrib2_list, 0);
        eglConfig = configs[0];
      }

      int[] values = new int[1];
      EGL14.eglQueryContext(eglDisplay, eglContext, EGL14.EGL_CONTEXT_CLIENT_VERSION, values, 0);

      if (eglSurface != EGL14.EGL_NO_SURFACE) {
        throw new IllegalStateException("surface already created");
      }

      int[] surfaceAttribs = {
        EGL14.EGL_NONE
      };
      eglSurface = EGL14.eglCreateWindowSurface(eglDisplay, eglConfig, surface, surfaceAttribs, 0);
      if (eglSurface == null) {
        throw new RuntimeException("surface was null");
      }

      if (!EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)) {
        Log.e(Log.TAG_ROUND, "eglMakeCurrent failed " + GLUtils.getEGLErrorString(EGL14.eglGetError()));
        throw new RuntimeException("eglMakeCurrent failed");
      }
      GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA);
      GLES20.glViewport(0, 0, videoWidth, videoHeight);
      encoderGles3 = isGles3();
      Log.i(Log.TAG_ROUND, "Encoder GL: %s, fences: %b", GLES20.glGetString(GLES20.GL_VERSION), encoderGles3 && cameraGles3);
      RoundRecordStats.setEncoderSync(encoderGles3 && cameraGles3);

      // Camera pixels per output pixel: the square crop of the camera frame vs the output side.
      int cameraSide = Math.min(mPreviewWidth, mPreviewHeight);
      float ratio = cameraSide > 0 ? (float) cameraSide / (float) videoWidth : 1f;
      boolean downscale = ratio > DOWNSCALE_RATIO_THRESHOLD && mPreviewWidth > 0 && mPreviewHeight > 0;
      if (downscale) {
        // Taps a third of the footprint apart: with bilinear filtering they cover all camera pixels of an output pixel.
        float step = ratio / 3f;
        texelOffsetX = step / (float) mPreviewWidth;
        texelOffsetY = step / (float) mPreviewHeight;
      }
      Log.i(Log.TAG_ROUND, "Encoder draw: camera %dx%d -> %d, ratio %.2f, downscale: %b", mPreviewWidth, mPreviewHeight, videoWidth, ratio, downscale);

      int vertexShader = loadShader(GLES20.GL_VERTEX_SHADER, VERTEX_SHADER);
      // The encoder draws 2D copies of the camera frames (see copyCameraFrame), not the camera texture.
      int fragmentShader = loadShader(GLES20.GL_FRAGMENT_SHADER, toTexture2D(downscale ? FRAGMENT_SHADER_DOWNSCALE : FRAGMENT_SHADER));
      if (downscale && fragmentShader == 0) {
        // Some old GPUs may refuse the bigger shader: fall back to the single sample one.
        fragmentShader = loadShader(GLES20.GL_FRAGMENT_SHADER, toTexture2D(FRAGMENT_SHADER));
      }
      if (vertexShader != 0 && fragmentShader != 0) {
        drawProgram = GLES20.glCreateProgram();
        GLES20.glAttachShader(drawProgram, vertexShader);
        GLES20.glAttachShader(drawProgram, fragmentShader);
        GLES20.glLinkProgram(drawProgram);
        int[] linkStatus = new int[1];
        GLES20.glGetProgramiv(drawProgram, GLES20.GL_LINK_STATUS, linkStatus, 0);
        if (linkStatus[0] == 0) {
          GLES20.glDeleteProgram(drawProgram);
          drawProgram = 0;
        } else {
          positionHandle = GLES20.glGetAttribLocation(drawProgram, "aPosition");
          textureHandle = GLES20.glGetAttribLocation(drawProgram, "aTextureCoord");
          scaleXHandle = GLES20.glGetUniformLocation(drawProgram, "scaleX");
          scaleYHandle = GLES20.glGetUniformLocation(drawProgram, "scaleY");
          alphaHandle = GLES20.glGetUniformLocation(drawProgram, "alpha");
          texelOffsetHandle = GLES20.glGetUniformLocation(drawProgram, "texelOffset");
          encoderZoomHandle = GLES20.glGetUniformLocation(drawProgram, "zoom");
          vertexMatrixHandle = GLES20.glGetUniformLocation(drawProgram, "uMVPMatrix");
          textureMatrixHandle = GLES20.glGetUniformLocation(drawProgram, "uSTMatrix");
        }
      }
      int blurVertexShader = loadShader(GLES20.GL_VERTEX_SHADER, VERTEX_SHADER);
      int blurFragmentShader = loadShader(GLES20.GL_FRAGMENT_SHADER, FRAGMENT_SHADER_BLUR);
      if (blurVertexShader != 0 && blurFragmentShader != 0) {
        int program = GLES20.glCreateProgram();
        GLES20.glAttachShader(program, blurVertexShader);
        GLES20.glAttachShader(program, blurFragmentShader);
        GLES20.glLinkProgram(program);
        int[] linkStatus = new int[1];
        GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, linkStatus, 0);
        if (linkStatus[0] == 0) {
          // No blur on this GPU: lens switches crossfade as before
          GLES20.glDeleteProgram(program);
        } else {
          blurProgram = program;
          blurPositionHandle = GLES20.glGetAttribLocation(program, "aPosition");
          blurTextureHandle = GLES20.glGetAttribLocation(program, "aTextureCoord");
          blurScaleXHandle = GLES20.glGetUniformLocation(program, "scaleX");
          blurScaleYHandle = GLES20.glGetUniformLocation(program, "scaleY");
          blurAlphaHandle = GLES20.glGetUniformLocation(program, "alpha");
          blurZoomHandle = GLES20.glGetUniformLocation(program, "zoom");
          blurRadiusHandle = GLES20.glGetUniformLocation(program, "radius");
          blurVertexMatrixHandle = GLES20.glGetUniformLocation(program, "uMVPMatrix");
          blurTextureMatrixHandle = GLES20.glGetUniformLocation(program, "uSTMatrix");
        }
      }
    }

    private float texelOffsetX, texelOffsetY;

    private void startAudioEncoder (int sampleRate) throws Exception {
      // AAC-LC mono. Lower sample rates cannot carry high bitrates, so cap them.
      int maxBitrate = sampleRate >= 44100 ? quality.audioBitrate : sampleRate >= 22050 ? 64000 : 32000;
      Exception lastError = null;
      for (int bitrate : new int[] {128000, 96000, 64000, 32000}) {
        if (bitrate > maxBitrate) {
          continue;
        }
        MediaFormat audioFormat = new MediaFormat();
        audioFormat.setString(MediaFormat.KEY_MIME, AUDIO_MIME_TYPE);
        audioFormat.setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC);
        audioFormat.setInteger(MediaFormat.KEY_SAMPLE_RATE, sampleRate);
        audioFormat.setInteger(MediaFormat.KEY_CHANNEL_COUNT, 1);
        audioFormat.setInteger(MediaFormat.KEY_BIT_RATE, bitrate);
        audioFormat.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 2048 * 10);
        MediaCodec codec = MediaCodec.createEncoderByType(AUDIO_MIME_TYPE);
        try {
          codec.configure(audioFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
          codec.start();
          audioEncoder = codec;
          Log.i(Log.TAG_ROUND, "Audio encoder: AAC-LC mono %d Hz, %d bit/s", sampleRate, bitrate);
          return;
        } catch (Exception e) {
          lastError = e;
          Log.w(Log.TAG_ROUND, "Audio encoder refused %d Hz %d bit/s", e, sampleRate, bitrate);
          try { codec.release(); } catch (Throwable ignored) { }
        }
      }
      throw lastError != null ? lastError : new IllegalStateException("No audio bitrate");
    }

    private MediaCodec createVideoCodec () throws Exception {
      String name = RoundVideoQuality.getVideoEncoderName();
      if (name != null) {
        try {
          // The encoder the capability checks were made against.
          return MediaCodec.createByCodecName(name);
        } catch (Exception e) {
          Log.w(Log.TAG_ROUND, "Cannot create %s", e, name);
        }
      }
      return MediaCodec.createEncoderByType(VIDEO_MIME_TYPE);
    }

    /** @param tuned with H.264 High profile and VBR when supported; null when there is nothing to tune */
    private MediaFormat createVideoFormat (RoundVideoQuality q, boolean tuned) {
      MediaFormat format = MediaFormat.createVideoFormat(VIDEO_MIME_TYPE, q.side, q.side);
      format.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
      format.setInteger(MediaFormat.KEY_BIT_RATE, q.getVideoBitrateBps());
      format.setInteger(MediaFormat.KEY_FRAME_RATE, q.fps);
      format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, IFRAME_INTERVAL);
      if (tuned) {
        boolean changed = false;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
          int level = RoundVideoQuality.getAvcLevel(q.side, q.fps);
          if (RoundVideoQuality.supportsHighProfile(level)) {
            format.setInteger(MediaFormat.KEY_PROFILE, MediaCodecInfo.CodecProfileLevel.AVCProfileHigh);
            format.setInteger(MediaFormat.KEY_LEVEL, level);
            changed = true;
          }
        }
        // A bitrate that fits a full minute into Telegram's 10 MB: constant, so it fits at any motion
        // (with VBR some encoders, Qualcomm, do not go below ~2 Mbit/s at 60 fps however low the target is).
        // A higher one: variable, quiet scenes stay small and are more likely to be sent without re-encoding.
        boolean preferCbr = q.fitsFullLength();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP && preferCbr && RoundVideoQuality.supportsCbr()) {
          format.setInteger(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR);
          changed = true;
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP && RoundVideoQuality.supportsVbr()) {
          format.setInteger(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR);
          changed = true;
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP && RoundVideoQuality.supportsCbr()) {
          format.setInteger(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR);
          changed = true;
        }
        if (!changed) {
          return null;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
          // High profile may turn B-frames on; keep frames in presentation order like before (merging, live upload).
          format.setInteger(MediaFormat.KEY_MAX_B_FRAMES, 0);
        }
      }
      return format;
    }

    /** CBR, VBR or null when the encoder chose. */
    @androidx.annotation.Nullable
    private static String getBitrateModeName (MediaFormat format) {
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP && format.containsKey(MediaFormat.KEY_BITRATE_MODE)) {
        int bitrateMode = format.getInteger(MediaFormat.KEY_BITRATE_MODE);
        return bitrateMode == MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR ? "CBR" :
          bitrateMode == MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR ? "VBR" : null;
      }
      return null;
    }

    /** Configures and starts the H.264 encoder; tries the tuned config first and the plain one if it is refused. */
    private void startVideoEncoder (RoundVideoQuality q) throws Exception {
      Exception lastError = null;
      for (boolean tuned : new boolean[] {true, false}) {
        MediaFormat format = createVideoFormat(q, tuned);
        if (format == null) {
          continue;
        }
        MediaCodec codec = createVideoCodec();
        Surface inputSurface = null;
        try {
          codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
          inputSurface = codec.createInputSurface();
          codec.start();
          videoEncoder = codec;
          surface = inputSurface;
          Log.i(Log.TAG_ROUND, "Video encoder: %s, format: %s", q, format);
          RoundRecordStats.setEncoderConfig(codec.getName(), q.side, q.fps, q.getVideoBitrateBps() / 1000, getBitrateModeName(format));
          return;
        } catch (Exception e) {
          lastError = e;
          Log.w(Log.TAG_ROUND, "Video encoder refused %s (tuned: %b)", e, q, tuned);
          if (inputSurface != null) {
            try { inputSurface.release(); } catch (Throwable ignored) { }
          }
          try { codec.release(); } catch (Throwable ignored) { }
        }
      }
      throw lastError != null ? lastError : new IllegalStateException("No video format");
    }

    public Surface getInputSurface() {
      return surface;
    }

    private void didWriteData(File file, boolean last) {
      if (videoConvertFirstWrite && !last) {
        videoConvertFirstWrite = false;
      } else if (last) {
        dispatchVideoRecordFinished(workingKey, file.length(), SystemClock.uptimeMillis() - recordStartTime, TimeUnit.MILLISECONDS);
      } else {
        dispatchVideoRecordProgress(workingKey, file.length());
      }
    }

    private boolean videoFinished;
    private long videoFinishedPresentationTimeUs;


    public void drainEncoder(boolean endOfStream) throws Exception {
      final long drainStart = System.nanoTime();
      try {
        drainEncoderImpl(endOfStream);
      } finally {
        RoundRecordStats.onDrain(System.nanoTime() - drainStart);
      }
    }

    private void drainEncoderImpl(boolean endOfStream) throws Exception {
      if (endOfStream) {
        videoEncoder.signalEndOfInputStream();
      }

      ByteBuffer[] encoderOutputBuffers = videoEncoder.getOutputBuffers();
      while (true) {
        // Vibegram: wait for output only at the end. Called for every video and audio frame, a 10 ms wait each
        // time nothing was ready took 10 ms of each 16.7 ms frame at 60 fps from this thread: frames were dropped.
        int encoderStatus = videoEncoder.dequeueOutputBuffer(videoBufferInfo, endOfStream ? 10000 : 0);
        if (encoderStatus == MediaCodec.INFO_TRY_AGAIN_LATER) {
          if (!endOfStream) {
            break;
          }
        } else if (encoderStatus == MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED) {
          if (Build.VERSION.SDK_INT < 21) {
            encoderOutputBuffers = videoEncoder.getOutputBuffers();
          }
        } else if (encoderStatus == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
          MediaFormat newFormat = videoEncoder.getOutputFormat();
          if (videoTrackIndex == -5) {
            videoTrackIndex = mediaMuxer.addTrack(newFormat, false);
          }
        } else if (encoderStatus >= 0) {
          ByteBuffer encodedData;
          if (Build.VERSION.SDK_INT < 21) {
            encodedData = encoderOutputBuffers[encoderStatus];
          } else {
            encodedData = videoEncoder.getOutputBuffer(encoderStatus);
          }
          if (encodedData == null) {
            throw new RuntimeException("encoderOutputBuffer " + encoderStatus + " was null");
          }
          if (videoBufferInfo.size > 1) {
            if ((videoBufferInfo.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0) {
              if (mediaMuxer.writeSampleData(videoTrackIndex, encodedData, videoBufferInfo, true)) {
                didWriteData(videoFile, false);
              }
            } else if (videoTrackIndex == -5) {
              byte[] csd = new byte[videoBufferInfo.size];
              encodedData.limit(videoBufferInfo.offset + videoBufferInfo.size);
              encodedData.position(videoBufferInfo.offset);
              encodedData.get(csd);
              ByteBuffer sps = null;
              ByteBuffer pps = null;
              for (int a = videoBufferInfo.size - 1; a >= 0; a--) {
                if (a > 3) {
                  if (csd[a] == 1 && csd[a - 1] == 0 && csd[a - 2] == 0 && csd[a - 3] == 0) {
                    sps = ByteBuffer.allocate(a - 3);
                    pps = ByteBuffer.allocate(videoBufferInfo.size - (a - 3));
                    sps.put(csd, 0, a - 3).position(0);
                    pps.put(csd, a - 3, videoBufferInfo.size - (a - 3)).position(0);
                    break;
                  }
                } else {
                  break;
                }
              }

              MediaFormat newFormat = MediaFormat.createVideoFormat("video/avc", videoWidth, videoHeight);
              if (sps != null && pps != null) {
                newFormat.setByteBuffer("csd-0", sps);
                newFormat.setByteBuffer("csd-1", pps);
              }
              videoTrackIndex = mediaMuxer.addTrack(newFormat, false);
            }
          }
          videoEncoder.releaseOutputBuffer(encoderStatus, false);
          if ((videoBufferInfo.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
            videoFinished = true;
            videoFinishedPresentationTimeUs = videoBufferInfo.presentationTimeUs;
            break;
          }
        }
      }

      encoderOutputBuffers = audioEncoder.getOutputBuffers();
      boolean encoderOutputAvailable = true;
      while (true) {
        int encoderStatus = audioEncoder.dequeueOutputBuffer(audioBufferInfo, 0);
        if (encoderStatus == MediaCodec.INFO_TRY_AGAIN_LATER) {
          if (!endOfStream || !running && sendWhenDone == 0) {
            break;
          }
        } else if (encoderStatus == MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED) {
          if (Build.VERSION.SDK_INT < 21) {
            encoderOutputBuffers = audioEncoder.getOutputBuffers();
          }
        } else if (encoderStatus == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
          MediaFormat newFormat = audioEncoder.getOutputFormat();
          if (audioTrackIndex == -5) {
            audioTrackIndex = mediaMuxer.addTrack(newFormat, true);
          }
        } else if (encoderStatus >= 0) {
          ByteBuffer encodedData;
          if (Build.VERSION.SDK_INT < 21) {
            encodedData = encoderOutputBuffers[encoderStatus];
          } else {
            encodedData = audioEncoder.getOutputBuffer(encoderStatus);
          }
          if (encodedData == null) {
            throw new RuntimeException("encoderOutputBuffer " + encoderStatus + " was null");
          }
          if ((audioBufferInfo.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
            audioBufferInfo.size = 0;
          }
          if (audioBufferInfo.size != 0 && isCapturing) {
            if (mediaMuxer.writeSampleData(audioTrackIndex, encodedData, audioBufferInfo, false)) {
              didWriteData(videoFile, false);
            }
          }
          audioEncoder.releaseOutputBuffer(encoderStatus, false);
          if ((audioBufferInfo.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
            break;
          }
        }
      }
    }

    @Override
    protected void finalize() throws Throwable {
      try {
        if (eglDisplay != EGL14.EGL_NO_DISPLAY) {
          EGL14.eglMakeCurrent(eglDisplay, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT);
          EGL14.eglDestroyContext(eglDisplay, eglContext);
          EGL14.eglReleaseThread();
          EGL14.eglTerminate(eglDisplay);
          eglDisplay = EGL14.EGL_NO_DISPLAY;
          eglContext = EGL14.EGL_NO_CONTEXT;
          eglConfig = null;
        }
      } finally {
        super.finalize();
      }
    }
  }

  private static final int[] SAMPLE_RATES = {48000, 44100, 22050, 11025, 8000};

  /** AudioRecord sample rates to try: the preferred one first, then the usual fallbacks below it. */
  private static int[] audioSampleRates (int preferred) {
    int count = 0;
    for (int rate : SAMPLE_RATES) {
      if (rate <= preferred) count++;
    }
    if (count == 0) {
      return SAMPLE_RATES;
    }
    int[] result = new int[count];
    int index = 0;
    for (int rate : SAMPLE_RATES) {
      if (rate <= preferred) result[index++] = rate;
    }
    return result;
  }
}
