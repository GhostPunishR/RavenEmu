package com.ravenemu.app.ui.player;

import android.hardware.Sensor;
import org.junit.Test;
import static org.junit.Assert.*;

/** The controller's readings, as the GL thread hands them to the native driver. Plain JVM. */
public final class ControllerMotionTest {
    @Test public void theLatestReadingsOfTheSensorsTheControllerHasAreRead() {
        ControllerMotion motion = new ControllerMotion();
        float[] acceleration = new float[3], turning = new float[3];
        assertEquals("No controller", 0, motion.read(acceleration, turning));
        motion.pretend(ControllerMotion.ACCELEROMETER | ControllerMotion.GYROSCOPE);
        motion.record(Sensor.TYPE_ACCELEROMETER, new float[]{1f, 2f, 3f});
        motion.record(Sensor.TYPE_ACCELEROMETER, new float[]{4f, 5f, 6f});
        motion.record(Sensor.TYPE_GYROSCOPE, new float[]{0.5f, -0.5f, 2f, 99f});
        motion.record(Sensor.TYPE_LIGHT, new float[]{7f, 7f, 7f});
        motion.record(Sensor.TYPE_GYROSCOPE, new float[]{1f});
        assertEquals(ControllerMotion.ACCELEROMETER | ControllerMotion.GYROSCOPE, motion.read(acceleration, turning));
        assertArrayEquals("The latest wins", new float[]{4f, 5f, 6f}, acceleration, 0f);
        assertArrayEquals("A short reading is ignored", new float[]{0.5f, -0.5f, 2f}, turning, 0f);
    }

    @Test public void aSensorTheControllerLacksIsNeverRead() {
        ControllerMotion motion = new ControllerMotion();
        float[] acceleration = new float[3], turning = new float[3];
        motion.pretend(ControllerMotion.ACCELEROMETER);
        motion.record(Sensor.TYPE_GYROSCOPE, new float[]{1f, 1f, 1f});
        assertEquals(ControllerMotion.ACCELEROMETER, motion.read(acceleration, turning));
        assertArrayEquals(new float[3], turning, 0f);
    }

    @Test public void stoppingForgetsTheControllerAndItsReadings() {
        ControllerMotion motion = new ControllerMotion();
        float[] acceleration = new float[3], turning = new float[3];
        motion.pretend(ControllerMotion.ACCELEROMETER);
        motion.record(Sensor.TYPE_ACCELEROMETER, new float[]{1f, 2f, 3f});
        motion.stop();
        assertEquals(0, motion.read(acceleration, turning));
        assertArrayEquals(new float[3], acceleration, 0f);
        // No controller, or the cartridge's sensors off: nothing registered.
        motion.use(null, true);
        assertEquals(0, motion.read(acceleration, turning));
    }
}
