package com.ravenemu.app.ui.player;

import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Build;
import android.view.InputDevice;

/** The accelerometer and gyroscope of the controller played with, for the
 * cartridge's sensors in place of the phone's (Android 12 and later: the
 * beta read the phone's only). The UI thread chooses the controller and
 * receives its events; the GL thread reads the latest values before each
 * frame and hands them to the native sensor driver. */
final class ControllerMotion implements SensorEventListener {
    /** As RAVEN_SENSORS_CONTROLLER_* in sensor_driver.h. */
    static final int ACCELEROMETER = 1, GYROSCOPE = 2;

    private SensorManager manager;
    private int kinds;
    private final float[] acceleration = new float[3], turning = new float[3];

    /** The controller now played with, or null for the touch screen; nothing
     * either when the cartridge's sensors are off in the settings. */
    synchronized void use(InputDevice device, boolean wanted) {
        stop();
        if (device == null || !wanted) return;
        if (Build.VERSION.SDK_INT < 31) return;
        SensorManager sensors = device.getSensorManager();
        if (sensors == null) return;
        int found = 0;
        Sensor accelerometer = sensors.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
        if (accelerometer != null && sensors.registerListener(this, accelerometer, SensorManager.SENSOR_DELAY_GAME))
            found |= ACCELEROMETER;
        Sensor gyroscope = sensors.getDefaultSensor(Sensor.TYPE_GYROSCOPE);
        if (gyroscope != null && sensors.registerListener(this, gyroscope, SensorManager.SENSOR_DELAY_GAME))
            found |= GYROSCOPE;
        if (found == 0) return;
        manager = sensors;
        kinds = found;
    }

    /** Paused, closed, or back to the touch screen: no listener left. */
    synchronized void stop() {
        if (manager != null) manager.unregisterListener(this);
        manager = null;
        kinds = 0;
        java.util.Arrays.fill(acceleration, 0f);
        java.util.Arrays.fill(turning, 0f);
    }

    @Override public void onSensorChanged(SensorEvent event) {
        if (event != null && event.sensor != null) record(event.sensor.getType(), event.values);
    }

    @Override public void onAccuracyChanged(Sensor sensor, int accuracy) {}

    /** A reading of the controller's sensor of this type. */
    synchronized void record(int type, float[] values) {
        if (values == null || values.length < 3) return;
        float[] target = type == Sensor.TYPE_ACCELEROMETER && (kinds & ACCELEROMETER) != 0 ? acceleration
                : type == Sensor.TYPE_GYROSCOPE && (kinds & GYROSCOPE) != 0 ? turning : null;
        if (target != null) System.arraycopy(values, 0, target, 0, 3);
    }

    /** The sensors the controller has, its latest readings copied out. */
    synchronized int read(float[] accelerationOut, float[] turningOut) {
        System.arraycopy(acceleration, 0, accelerationOut, 0, 3);
        System.arraycopy(turning, 0, turningOut, 0, 3);
        return kinds;
    }

    /** Tests: as if a controller with these sensors were registered. */
    synchronized void pretend(int sensorKinds) { kinds = sensorKinds; }
}
