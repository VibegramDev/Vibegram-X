/*
 * This file is a part of Vibegram-X
 *
 * Hidden (spoiler) text particles. Behaviour and numbers follow the official Telegram for Android
 * (SpoilerEffect / SpoilerEffectBitmapFactory); the code is written for Telegram X's Text renderer.
 */
package org.thunderdog.challegram.util.text;

import android.animation.ValueAnimator;
import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.RectF;
import android.graphics.Shader;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.Process;
import android.os.SystemClock;

import androidx.annotation.Nullable;

import org.thunderdog.challegram.tool.Screen;
import org.thunderdog.challegram.unsorted.Settings;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Random;
import java.util.Set;
import java.util.WeakHashMap;

import me.vkryl.android.util.ViewProvider;

/**
 * Vibegram: particles of hidden (spoiler) text, behaving like the official Telegram app.
 *
 * Particles are not simulated per spoiler. One small square tile of moving dots is rendered on a
 * background thread (~30 fps) into an alpha-only bitmap, and every hidden line is just a rect filled
 * with that tile (repeat shader) tinted with the text colour. The tile is rendered only while some
 * hidden text was drawn during the previous frame, and only the part of the tile that is actually
 * shown (visible hidden rects mapped onto the tile) is moved and redrawn; when nothing hidden is on
 * screen (or the app is in background) nothing runs. With "Reduce motion" or system animations off the dots stand still.
 */
final class SpoilerParticles {
  private static final int FRAME_MS = 33;
  private static final int GRID = 10; // tile is GRID x GRID cells, each keeps its own particles
  private static final float[] LAYER_ALPHAS = {.3f, .6f, 1f};

  private static SpoilerParticles instance;

  static SpoilerParticles instance () {
    if (instance == null) {
      instance = new SpoilerParticles();
    }
    return instance;
  }

  // Tile, touched only by the worker after the first frame (and by the main thread before it)
  private final int size;
  private final int particleCount;
  private final float[] x, y, vx, vy, speed, age, life;
  private final byte[] layer;
  private final float[] cellLeft, cellTop, cellRight, cellBottom; // spawn area per cell
  private final float[] insetTop, insetBottom; // particles die when leaving them (vertically)
  private final byte[][] stamps = new byte[LAYER_ALPHAS.length][];
  private byte[] pixels;
  private ByteBuffer pixelBuffer;
  private final Random random = new Random();
  private final int[] cellOf;
  private final int step;
  private long lastUpdateTime;

  private final Bitmap[] buffers = new Bitmap[2];
  private final BitmapShader[] shaders = new BitmapShader[2];

  // Main thread
  private int currentBuffer;
  private boolean initialized;
  private final Paint drawPaint = new Paint();
  private int filterColor;
  private boolean frameScheduled;
  private final boolean[] dirtyCells = new boolean[GRID * GRID], lastCells = new boolean[GRID * GRID];
  private final Set<ViewProvider> pendingViews = Collections.newSetFromMap(new WeakHashMap<>());
  private final ArrayList<ViewProvider> invalidateList = new ArrayList<>();
  private final Handler mainHandler = new Handler(Looper.getMainLooper());
  private Handler workerHandler;

  private SpoilerParticles () {
    this.size = Screen.dp(100f);
    this.step = size / GRID;
    final int perCell = Math.max(1, (int) (60f * size / (float) Screen.dp(200f)));
    this.particleCount = GRID * GRID * perCell;
    x = new float[particleCount];
    y = new float[particleCount];
    vx = new float[particleCount];
    vy = new float[particleCount];
    speed = new float[particleCount];
    age = new float[particleCount];
    life = new float[particleCount];
    layer = new byte[particleCount];
    cellLeft = new float[particleCount];
    cellTop = new float[particleCount];
    cellRight = new float[particleCount];
    cellBottom = new float[particleCount];
    insetTop = new float[particleCount];
    insetBottom = new float[particleCount];
    cellOf = new int[particleCount];
    final float verticalPadding = Screen.dp(5f), rightPadding = Screen.dp(3f), inset = Screen.dp(2.5f);
    for (int i = 0; i < particleCount; i++) {
      int cell = i / perCell;
      cellOf[i] = cell;
      int col = cell % GRID, row = cell / GRID;
      cellLeft[i] = step * col;
      cellRight[i] = step * col + step + rightPadding;
      cellTop[i] = step * row - verticalPadding;
      cellBottom[i] = step * row + step + verticalPadding;
      insetTop[i] = cellTop[i] + inset;
      insetBottom[i] = cellBottom[i] - inset;
      spawn(i);
    }
    for (int i = 0; i < LAYER_ALPHAS.length; i++) {
      // Official app: 1.4dp dots for the faintest layer, 1.2dp for the others
      stamps[i] = buildStamp(Math.min(Screen.dp(i == 0 ? 1.4f : 1.2f), STAMP_SIZE - .5f), LAYER_ALPHAS[i]);
    }
  }

  private void spawn (int i) {
    float rf = random.nextFloat();
    int attempts = 0;
    do {
      x[i] = cellLeft[i] + random.nextFloat() * (cellRight[i] - cellLeft[i]);
      y[i] = cellTop[i] + random.nextFloat() * (cellBottom[i] - cellTop[i]);
    } while ((y[i] < insetTop[i] || y[i] > insetBottom[i]) && ++attempts < 4);
    double angle = rf * Math.PI * 2.0 - Math.PI;
    vx[i] = (float) Math.cos(angle);
    vy[i] = (float) Math.sin(angle);
    speed[i] = 4f + rf * 6f;
    age[i] = 0f;
    life[i] = 1000 + random.nextInt(2000);
    layer[i] = (byte) random.nextInt(LAYER_ALPHAS.length);
  }

  private final boolean[] cellVisible = new boolean[GRID * GRID];

  /**
   * Moves particles by the time passed and draws the tile into {@code bitmap}.
   * Only cells shown somewhere ({@code region}, one flag per cell) are moved and drawn, as in the official app.
   * Dots are stamped straight into an alpha byte buffer instead of Canvas.drawPoints (software round points are slow).
   */
  private void render (Bitmap bitmap, boolean[] region) {
    final long now = SystemClock.uptimeMillis();
    final int dt = lastUpdateTime == 0 ? 0 : (int) Math.min(now - lastUpdateTime, FRAME_MS + 1);
    lastUpdateTime = now;
    final float move = dt / 500f;
    // Particles of a cell can leave it by 5dp vertically and 3dp to the right: take the neighbours too
    for (int cell = 0; cell < GRID * GRID; cell++) {
      int col = cell % GRID, row = cell / GRID;
      boolean visible = false;
      for (int dy = -1; dy <= 1 && !visible; dy++) {
        for (int dx = -1; dx <= 0 && !visible; dx++) {
          int r = (row + dy + GRID) % GRID, c = (col + dx + GRID) % GRID;
          visible = region[r * GRID + c];
        }
      }
      cellVisible[cell] = visible;
    }
    final int stride = bitmap.getRowBytes();
    if (pixels == null || pixels.length != stride * size) {
      pixels = new byte[stride * size];
      pixelBuffer = ByteBuffer.wrap(pixels);
    }
    final byte[] out = pixels;
    Arrays.fill(out, (byte) 0);
    for (int i = 0; i < particleCount; i++) {
      if (!cellVisible[cellOf[i]]) continue;
      age[i] += dt;
      if (age[i] >= life[i] || x[i] < cellLeft[i] || x[i] > cellRight[i] || y[i] < insetTop[i] || y[i] > insetBottom[i]) {
        spawn(i);
      } else {
        float d = speed[i] * move;
        x[i] += vx[i] * d;
        y[i] += vy[i] * d;
      }
      // Stamp a round dot, wrapping around the tile so that the repeat shader has no seams
      final byte[] stamp = stamps[layer[i]];
      final int cx = Math.round(x[i]) - STAMP_RADIUS, cy = Math.round(y[i]) - STAMP_RADIUS;
      for (int sy = 0, k = 0; sy < STAMP_SIZE; sy++) {
        final int py = wrap(cy + sy, size) * stride;
        for (int sx = 0; sx < STAMP_SIZE; sx++, k++) {
          final int value = stamp[k] & 0xff;
          if (value == 0) continue;
          final int index = py + wrap(cx + sx, size);
          if ((out[index] & 0xff) < value) {
            out[index] = (byte) value;
          }
        }
      }
    }
    pixelBuffer.rewind();
    bitmap.copyPixelsFromBuffer(pixelBuffer);
  }

  private static int wrap (int value, int mod) {
    int result = value % mod;
    return result < 0 ? result + mod : result;
  }

  private static int floorDiv (int value, int divisor) {
    int result = value / divisor;
    return (value % divisor != 0 && (value < 0) != (divisor < 0)) ? result - 1 : result;
  }

  private static final int STAMP_RADIUS = 2, STAMP_SIZE = STAMP_RADIUS * 2 + 1;

  /** Pixel coverage of a dot of {@code diameter} px centred in a 5x5 stamp, scaled by {@code alpha}. */
  private static byte[] buildStamp (float diameter, float alpha) {
    final byte[] stamp = new byte[STAMP_SIZE * STAMP_SIZE];
    final float r = diameter / 2f;
    final int samples = 4;
    for (int sy = 0; sy < STAMP_SIZE; sy++) {
      for (int sx = 0; sx < STAMP_SIZE; sx++) {
        int inside = 0;
        for (int j = 0; j < samples; j++) {
          for (int k = 0; k < samples; k++) {
            float px = sx - STAMP_RADIUS - .5f + (k + .5f) / samples;
            float py = sy - STAMP_RADIUS - .5f + (j + .5f) / samples;
            if (px * px + py * py <= r * r) inside++;
          }
        }
        stamp[sy * STAMP_SIZE + sx] = (byte) Math.round(255f * alpha * inside / (samples * samples));
      }
    }
    return stamp;
  }

  private void ensureInitialized () {
    if (initialized) return;
    initialized = true;
    for (int i = 0; i < 2; i++) {
      buffers[i] = Bitmap.createBitmap(size, size, Bitmap.Config.ALPHA_8);
      shaders[i] = new BitmapShader(buffers[i], Shader.TileMode.REPEAT, Shader.TileMode.REPEAT);
    }
    boolean[] all = new boolean[GRID * GRID];
    java.util.Arrays.fill(all, true);
    render(buffers[0], all);
    currentBuffer = 0;
    drawPaint.setShader(shaders[0]);
  }

  private static boolean isAnimationAllowed () {
    if (Settings.instance().needReduceMotion()) {
      return false;
    }
    return Build.VERSION.SDK_INT < Build.VERSION_CODES.O || ValueAnimator.areAnimatorsEnabled();
  }

  /**
   * Fills {@code rect} with particles of {@code color}.
   * {@code viewProvider} is invalidated when the next tile frame is ready (only if it draws again).
   */
  void draw (Canvas c, RectF rect, int color, float alpha, @Nullable ViewProvider viewProvider) {
    if (alpha <= 0f || rect.isEmpty()) {
      return;
    }
    ensureInitialized();
    if (filterColor != color || drawPaint.getColorFilter() == null) {
      filterColor = color;
      drawPaint.setColorFilter(new PorterDuffColorFilter(color | 0xff000000, PorterDuff.Mode.SRC_IN));
    }
    drawPaint.setAlpha((int) (Color.alpha(color) * Math.min(1f, alpha)));
    c.drawRect(rect, drawPaint);
    if (viewProvider != null && isAnimationAllowed()) {
      pendingViews.add(viewProvider);
      addDirtyRect(rect);
      scheduleFrame();
    }
  }

  /** Marks tile cells under a drawn rect (canvas coordinates, the shader repeats from 0,0). */
  private void addDirtyRect (RectF rect) {
    int col0 = floorDiv((int) Math.floor(rect.left), step);
    int col1 = floorDiv((int) Math.ceil(rect.right), step);
    int row0 = floorDiv((int) Math.floor(rect.top), step);
    int row1 = floorDiv((int) Math.ceil(rect.bottom), step);
    if (col1 - col0 >= GRID) { col0 = 0; col1 = GRID - 1; }
    if (row1 - row0 >= GRID) { row0 = 0; row1 = GRID - 1; }
    for (int row = row0; row <= row1; row++) {
      int r = wrap(row, GRID);
      for (int col = col0; col <= col1; col++) {
        dirtyCells[r * GRID + wrap(col, GRID)] = true;
      }
    }
  }

  private void scheduleFrame () {
    if (frameScheduled) return;
    frameScheduled = true;
    if (workerHandler == null) {
      HandlerThread thread = new HandlerThread("VgSpoilerParticles", Process.THREAD_PRIORITY_BACKGROUND);
      thread.start();
      workerHandler = new Handler(thread.getLooper());
    }
    mainHandler.postDelayed(startFrame, FRAME_MS);
  }

  // Written by the main thread before the worker is posted, read by the worker; the next write happens
  // only after the worker's frame was delivered back, so no locking is needed.
  private final boolean[] workerCells = new boolean[GRID * GRID];
  private int workerBuffer;

  private final Runnable startFrame = () -> {
    // Region of this frame plus the previous one: a view drawn between the snapshot and the swap keeps its dots
    for (int i = 0; i < GRID * GRID; i++) {
      workerCells[i] = dirtyCells[i] || lastCells[i];
      lastCells[i] = dirtyCells[i];
      dirtyCells[i] = false;
    }
    workerBuffer = 1 - currentBuffer;
    workerHandler.post(this::renderFrame);
  };

  private void renderFrame () {
    render(buffers[workerBuffer], workerCells);
    mainHandler.post(onFrameReady);
  }

  private final Runnable onFrameReady = this::onFrameReady;

  private void onFrameReady () {
    currentBuffer = workerBuffer;
    drawPaint.setShader(shaders[currentBuffer]);
    frameScheduled = false;
    // Views re-register when they draw hidden text again, so off-screen ones stop the loop
    invalidateList.addAll(pendingViews);
    pendingViews.clear();
    for (ViewProvider viewProvider : invalidateList) {
      viewProvider.invalidate();
    }
    invalidateList.clear();
  }
}
