/*
 * This file is a part of Vibegram X, a fork of Telegram X
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <https://www.gnu.org/licenses/>.
 */
package org.thunderdog.challegram.webapp;

import android.content.Context;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Build;
import android.os.SystemClock;

import androidx.annotation.Nullable;

import org.thunderdog.challegram.Log;
import org.thunderdog.challegram.tool.UI;
import org.thunderdog.challegram.unsorted.AppContext;

/**
 * Accelerometer, gyroscope and device orientation of {@code web_app_start_*} /
 * {@code web_app_stop_*}.
 *
 * <p>All methods are called on the UI thread; sensor events arrive there too (listeners are
 * registered without a handler).
 */
public final class WebAppSensors {
  public static final int TYPE_ACCELEROMETER = 0, TYPE_GYROSCOPE = 1, TYPE_DEVICE_ORIENTATION = 2;

  public interface Listener {
    void onSensorChanged (int type, String eventName, String json);
  }

  private static final int MIN_REFRESH_RATE_MS = 20, MAX_REFRESH_RATE_MS = 1000;

  private final Listener listener;
  private final @Nullable SensorManager sensorManager;
  private final Channel[] channels;
  private final StringBuilder jsonBuilder = new StringBuilder(96);
  private boolean isPaused, isReleased;

  public WebAppSensors (Listener listener) {
    this.listener = listener;
    SensorManager sensorManager = null;
    try {
      sensorManager = (SensorManager) AppContext.get().getSystemService(Context.SENSOR_SERVICE);
    } catch (Throwable t) {
      Log.e("SensorManager is unavailable", t);
    }
    this.sensorManager = sensorManager;
    this.channels = new Channel[] {
      new Channel(TYPE_ACCELEROMETER, "accelerometer_changed"),
      new Channel(TYPE_GYROSCOPE, "gyroscope_changed"),
      new Channel(TYPE_DEVICE_ORIENTATION, "device_orientation_changed")
    };
  }

  /** @return false when the sensor is missing, which the caller reports as {@code UNSUPPORTED}. */
  public boolean start (int type, int refreshRateMs, boolean needAbsolute) {
    if (isReleased || sensorManager == null || type < 0 || type >= channels.length) {
      return false;
    }
    Channel channel = channels[type];
    int refreshRate = Math.max(MIN_REFRESH_RATE_MS, Math.min(MAX_REFRESH_RATE_MS, refreshRateMs));
    if (channel.isRunning) {
      if (type != TYPE_DEVICE_ORIENTATION || channel.isAbsolute == needAbsolute) {
        channel.refreshRateMs = refreshRate;
        return true;
      }
      channel.stop();
    }
    if (!channel.setup(needAbsolute)) {
      return false;
    }
    channel.refreshRateMs = refreshRate;
    channel.isRunning = true;
    if (!isPaused && !channel.register()) {
      channel.stop();
      return false;
    }
    return true;
  }

  /** Stopping a sensor that does not run succeeds, as the documentation says. */
  public boolean stop (int type) {
    if (type >= 0 && type < channels.length) {
      channels[type].stop();
    }
    return true;
  }

  public void pause () {
    if (isPaused) {
      return;
    }
    isPaused = true;
    for (Channel channel : channels) {
      channel.unregister();
    }
  }

  public void resume () {
    if (!isPaused || isReleased) {
      return;
    }
    isPaused = false;
    for (Channel channel : channels) {
      if (channel.isRunning) {
        channel.register();
      }
    }
  }

  public void release () {
    isReleased = true;
    for (Channel channel : channels) {
      channel.stop();
    }
  }

  private static int samplingPeriodFor (int refreshRateMs) {
    if (refreshRateMs >= 160) {
      return SensorManager.SENSOR_DELAY_NORMAL;
    } else if (refreshRateMs >= 60) {
      return SensorManager.SENSOR_DELAY_UI;
    } else {
      return SensorManager.SENSOR_DELAY_GAME;
    }
  }

  private static void appendNumber (StringBuilder builder, float value) {
    // NaN and infinities are not valid JSON numbers.
    if (Float.isNaN(value) || Float.isInfinite(value)) {
      builder.append('0');
    } else {
      builder.append(value);
    }
  }

  /** One sensor kind: its registration, latest values and the single throttled post. */
  private final class Channel implements SensorEventListener, Runnable {
    private final int type;
    private final String eventName;
    private final float[] values = new float[3];

    private @Nullable Sensor primarySensor, secondarySensor;
    private boolean isRunning, isRegistered, isPostPending, hasValues;
    private boolean isAbsolute;
    private int refreshRateMs = MAX_REFRESH_RATE_MS;
    private long lastPostTime;

    // Device orientation only.
    private @Nullable float[] gravity, geomagnetic, rotationMatrix, orientation, rotationVector;
    private boolean hasGravity, hasGeomagnetic;

    Channel (int type, String eventName) {
      this.type = type;
      this.eventName = eventName;
    }

    boolean setup (boolean needAbsolute) {
      SensorManager manager = sensorManager;
      if (manager == null) {
        return false;
      }
      primarySensor = secondarySensor = null;
      isAbsolute = false;
      switch (type) {
        case TYPE_ACCELEROMETER:
          primarySensor = manager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
          break;
        case TYPE_GYROSCOPE:
          primarySensor = manager.getDefaultSensor(Sensor.TYPE_GYROSCOPE);
          break;
        case TYPE_DEVICE_ORIENTATION: {
          if (needAbsolute) {
            primarySensor = manager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
            secondarySensor = manager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD);
            if (secondarySensor == null) {
              primarySensor = null;
            }
            isAbsolute = true;
          } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR2) {
            primarySensor = manager.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR);
          }
          if (primarySensor != null && rotationMatrix == null) {
            gravity = new float[3];
            geomagnetic = new float[3];
            rotationMatrix = new float[9];
            orientation = new float[3];
            rotationVector = new float[4];
          }
          break;
        }
      }
      return primarySensor != null;
    }

    boolean register () {
      SensorManager manager = sensorManager;
      if (isRegistered || manager == null || primarySensor == null) {
        return isRegistered;
      }
      int samplingPeriod = samplingPeriodFor(refreshRateMs);
      boolean registered = manager.registerListener(this, primarySensor, samplingPeriod);
      if (registered && secondarySensor != null) {
        registered = manager.registerListener(this, secondarySensor, samplingPeriod);
        if (!registered) {
          manager.unregisterListener(this);
        }
      }
      isRegistered = registered;
      return registered;
    }

    void unregister () {
      if (isRegistered && sensorManager != null) {
        sensorManager.unregisterListener(this);
      }
      isRegistered = false;
      cancelPost();
      hasValues = hasGravity = hasGeomagnetic = false;
    }

    void stop () {
      unregister();
      isRunning = false;
    }

    private void cancelPost () {
      if (isPostPending) {
        UI.cancel(this);
        isPostPending = false;
      }
    }

    @Override
    public void onSensorChanged (SensorEvent event) {
      if (!isRegistered) {
        return;
      }
      float[] eventValues = event.values;
      if (eventValues == null || eventValues.length < 3) {
        return;
      }
      switch (type) {
        case TYPE_ACCELEROMETER: {
          // Negated to match the sign convention of the iOS client.
          values[0] = -eventValues[0];
          values[1] = -eventValues[1];
          values[2] = -eventValues[2];
          break;
        }
        case TYPE_GYROSCOPE: {
          values[0] = eventValues[0];
          values[1] = eventValues[1];
          values[2] = eventValues[2];
          break;
        }
        case TYPE_DEVICE_ORIENTATION: {
          if (!updateOrientation(event.sensor, eventValues)) {
            return;
          }
          break;
        }
        default:
          return;
      }
      hasValues = true;
      schedulePost();
    }

    private boolean updateOrientation (Sensor sensor, float[] eventValues) {
      float[] rotationMatrix = this.rotationMatrix;
      float[] orientation = this.orientation;
      if (rotationMatrix == null || orientation == null) {
        return false;
      }
      if (isAbsolute) {
        float[] target;
        int sensorType = sensor.getType();
        if (sensorType == Sensor.TYPE_ACCELEROMETER) {
          target = gravity;
          hasGravity = true;
        } else if (sensorType == Sensor.TYPE_MAGNETIC_FIELD) {
          target = geomagnetic;
          hasGeomagnetic = true;
        } else {
          return false;
        }
        if (target == null) {
          return false;
        }
        System.arraycopy(eventValues, 0, target, 0, 3);
        if (!hasGravity || !hasGeomagnetic) {
          return false;
        }
        if (!SensorManager.getRotationMatrix(rotationMatrix, null, gravity, geomagnetic)) {
          return false;
        }
      } else {
        float[] vector = eventValues;
        // Some Samsung devices deliver 5 values, which getRotationMatrixFromVector rejects.
        if (eventValues.length > 4 && rotationVector != null) {
          System.arraycopy(eventValues, 0, rotationVector, 0, 4);
          vector = rotationVector;
        }
        try {
          SensorManager.getRotationMatrixFromVector(rotationMatrix, vector);
        } catch (IllegalArgumentException ignored) {
          return false;
        }
      }
      SensorManager.getOrientation(rotationMatrix, orientation);
      values[0] = -orientation[0];
      values[1] = -orientation[1];
      values[2] = orientation[2];
      return true;
    }

    private void schedulePost () {
      if (isPostPending) {
        return;
      }
      long elapsed = SystemClock.uptimeMillis() - lastPostTime;
      if (elapsed >= refreshRateMs) {
        dispatch();
      } else {
        isPostPending = true;
        UI.post(this, refreshRateMs - elapsed);
      }
    }

    @Override
    public void run () {
      isPostPending = false;
      dispatch();
    }

    private void dispatch () {
      if (!isRegistered || !hasValues) {
        return;
      }
      lastPostTime = SystemClock.uptimeMillis();
      StringBuilder json = jsonBuilder;
      json.setLength(0);
      if (type == TYPE_DEVICE_ORIENTATION) {
        json.append("{\"absolute\":").append(isAbsolute);
        json.append(",\"alpha\":");
        appendNumber(json, values[0]);
        json.append(",\"beta\":");
        appendNumber(json, values[1]);
        json.append(",\"gamma\":");
        appendNumber(json, values[2]);
      } else {
        json.append("{\"x\":");
        appendNumber(json, values[0]);
        json.append(",\"y\":");
        appendNumber(json, values[1]);
        json.append(",\"z\":");
        appendNumber(json, values[2]);
      }
      json.append('}');
      listener.onSensorChanged(type, eventName, json.toString());
    }

    @Override
    public void onAccuracyChanged (Sensor sensor, int accuracy) { }
  }
}
