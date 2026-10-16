package com.ravenemu.android

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.hardware.display.DisplayManager
import android.view.Display
import com.ravenemu.shared.Settings
import kotlin.math.PI
import kotlin.math.log10
import kotlin.math.roundToInt

/**
 * The phone's sensors in place of the cartridge's: gyroscope for WarioWare:
 * Twisted!, accelerometer for the tilt cartridges (Yoshi Topsy-Turvy, Koro
 * Koro Puzzle, GB MBC7 such as Kirby Tilt 'n' Tumble) and light for Boktai.
 *
 * [start] registers only the sensors the loaded cartridge has. Their callbacks
 * keep the latest reading; the emulation thread hands it to the core before
 * each frame ([applyTo], under the session's native lock). [stop] unregisters
 * and returns rotation and tilt to rest, so a resumed game starts level.
 */
internal class CartridgeSensors(context: Context) : SensorEventListener {
    private val manager: SensorManager? = context.getSystemService(SensorManager::class.java)
    private val display: Display? =
        context.getSystemService(DisplayManager::class.java)?.getDisplay(Display.DEFAULT_DISPLAY)
    @Volatile private var kinds = 0
    @Volatile private var light = CartridgeSensorMath.manualLight(Settings().solarLightLevel)
    @Volatile private var rotation = 0
    @Volatile private var tiltX = 0
    @Volatile private var tiltY = 0

    /** Feeds the [cartridge] sensors (NativeCore.SENSOR_* flags) as [settings] ask. */
    fun start(cartridge: Int, settings: Settings) {
        stop()
        kinds = cartridge
        // Boktai's sun before the first reading, or without a light sensor.
        light = CartridgeSensorMath.manualLight(settings.solarLightLevel)
        val sensors = manager ?: return
        if (!settings.cartridgeSensors) return
        if (cartridge and NativeCore.SENSOR_SOLAR != 0 && settings.solarLightAuto) {
            sensors.getDefaultSensor(Sensor.TYPE_LIGHT)?.let {
                sensors.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL)
            }
        }
        if (cartridge and NativeCore.SENSOR_ROTATION != 0) {
            sensors.getDefaultSensor(Sensor.TYPE_GYROSCOPE)?.let {
                sensors.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME)
            }
        }
        // The raw accelerometer, not the filtered gravity: the cartridges
        // measure motion too, and Kirby Tilt 'n' Tumble jumps on a flick.
        if (cartridge and NativeCore.SENSOR_TILT != 0) {
            sensors.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)?.let {
                sensors.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME)
            }
        }
    }

    fun stop() {
        manager?.unregisterListener(this)
        rotation = 0
        tiltX = 0
        tiltY = 0
    }

    /** Hands the latest readings to [core]; call on the session's native lock. */
    fun applyTo(core: NativeCore) {
        val present = kinds
        if (present and NativeCore.SENSOR_SOLAR != 0) core.setSolarLight(light)
        if (present and NativeCore.SENSOR_ROTATION != 0) core.setRotation(rotation)
        if (present and NativeCore.SENSOR_TILT != 0) core.setTilt(tiltX, tiltY)
    }

    override fun onSensorChanged(event: SensorEvent) {
        val values = event.values
        when (event.sensor.type) {
            Sensor.TYPE_LIGHT -> light = CartridgeSensorMath.lightLevel(values[0])
            Sensor.TYPE_GYROSCOPE -> rotation = CartridgeSensorMath.rotationSpeed(values[2])
            Sensor.TYPE_ACCELEROMETER -> {
                val turn = display?.rotation ?: 0
                tiltX = CartridgeSensorMath.tiltX(values[0], values[1], turn)
                tiltY = CartridgeSensorMath.tiltY(values[0], values[1], turn)
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) = Unit
}

/** Android sensor readings in the units of RavenCore's C API. */
internal object CartridgeSensorMath {
    /** A bright room: Boktai's sensor gets nothing from it. */
    private const val ROOM_LUX = 1_000.0
    /** Direct sunlight. */
    private const val SUN_LUX = 100_000.0
    /** Half a turn per second gives the gyroscope's full scale. */
    private const val FULL_ROTATION = PI.toFloat()

    /** Manual sun, [percent] of full sunlight, to 0..255. */
    fun manualLight(percent: Int): Int = (percent.coerceIn(0, 100) * 255 + 50) / 100

    /**
     * Lux to 0..255 on a log scale, from a bright room (0) to direct sun
     * (255): an overcast sky around 10 000 lx gives half. Like the real
     * cartridge, Boktai wants daylight.
     */
    fun lightLevel(lux: Float): Int {
        if (!(lux > ROOM_LUX)) return 0
        val share = (log10(lux.toDouble()) - log10(ROOM_LUX)) / (log10(SUN_LUX) - log10(ROOM_LUX))
        return (share * 255).roundToInt().coerceIn(0, 255)
    }

    /**
     * Gyroscope z in rad/s to -32768..32767. Android counts it positive
     * counterclockwise as seen from the screen; the core, clockwise.
     */
    fun rotationSpeed(radiansPerSecond: Float): Int = scaled(-radiansPerSecond / FULL_ROTATION)

    /**
     * Accelerometer x, y in m/s², with [displayRotation] a Surface.ROTATION_*
     * value, to the core's tilt (-1 g..1 g as -32768..32767). The axes first
     * follow the screen as displayed; then the sign flips, since a side going
     * down reads negative on Android while the core counts it positive.
     */
    fun tiltX(x: Float, y: Float, displayRotation: Int): Int =
        scaled(-screenX(x, y, displayRotation) / SensorManager.STANDARD_GRAVITY)

    fun tiltY(x: Float, y: Float, displayRotation: Int): Int =
        scaled(-screenY(x, y, displayRotation) / SensorManager.STANDARD_GRAVITY)

    private fun screenX(x: Float, y: Float, rotation: Int): Float = when (rotation) {
        1 -> -y
        2 -> -x
        3 -> y
        else -> x
    }

    private fun screenY(x: Float, y: Float, rotation: Int): Float = when (rotation) {
        1 -> x
        2 -> -y
        3 -> -x
        else -> y
    }

    private fun scaled(unit: Float): Int =
        if (unit.isNaN()) 0 else (unit * 32768f).roundToInt().coerceIn(-32768, 32767)
}
