package com.ravenemu.app.settings;


import android.content.Context;
import java.util.Map;

/** The beta's Settings → Interface, cartridge part: the rumble motor on the
 * phone's vibrator, the phone's sensors for the cartridge's, and Boktai's sun.
 * Stored with the playback preferences of the native preview; no beta data is
 * read. */
public final class CartridgeSettings {
    public static final int SOLAR_MIN = 0, SOLAR_MAX = 100, SOLAR_STEP = 10;

    public static final CartridgeSettings DEFAULTS = new CartridgeSettings(true, true, true, 50);

    /** Drill Dozer, WarioWare: Twisted!, the Game Boy rumble cartridges. */
    public final boolean rumble;
    /** The phone's gyroscope, tilt and light stand in for the cartridge's. */
    public final boolean sensors;
    /** Boktai's sun from the phone's light sensor, with the sensors on. */
    public final boolean solarAuto;
    /** Otherwise, the share of full sunlight, by tens of percent. */
    public final int solarLevel;

    public CartridgeSettings(boolean rumble, boolean sensors, boolean solarAuto, int solarLevel) {
        this.rumble = rumble;
        this.sensors = sensors;
        this.solarAuto = solarAuto;
        int level = Math.max(SOLAR_MIN, Math.min(SOLAR_MAX, solarLevel));
        this.solarLevel = (level + SOLAR_STEP / 2) / SOLAR_STEP * SOLAR_STEP;
    }

    public CartridgeSettings withRumble(boolean value) {
        return new CartridgeSettings(value, sensors, solarAuto, solarLevel);
    }
    public CartridgeSettings withSensors(boolean value) {
        return new CartridgeSettings(rumble, value, solarAuto, solarLevel);
    }
    public CartridgeSettings withSolarAuto(boolean value) {
        return new CartridgeSettings(rumble, sensors, value, solarLevel);
    }
    public CartridgeSettings withSolarLevel(int value) {
        return new CartridgeSettings(rumble, sensors, solarAuto, value);
    }

    /** The light sensor sets the sun; the level only applies otherwise. */
    public boolean solarFromSensor() { return sensors && solarAuto; }

    public static CartridgeSettings load(Context context) {
        Map<String, ?> values = context.getSharedPreferences(PlaybackSettings.PREFERENCES, Context.MODE_PRIVATE)
                .getAll();
        Object rumble = values.get("cartridge.rumble");
        Object sensors = values.get("cartridge.sensors");
        Object solarAuto = values.get("cartridge.solar_auto");
        Object solarLevel = values.get("cartridge.solar_level");
        return new CartridgeSettings(rumble instanceof Boolean ? (Boolean)rumble : DEFAULTS.rumble,
                sensors instanceof Boolean ? (Boolean)sensors : DEFAULTS.sensors,
                solarAuto instanceof Boolean ? (Boolean)solarAuto : DEFAULTS.solarAuto,
                solarLevel instanceof Integer ? (Integer)solarLevel : DEFAULTS.solarLevel);
    }

    public void save(Context context) {
        context.getSharedPreferences(PlaybackSettings.PREFERENCES, Context.MODE_PRIVATE).edit()
                .putBoolean("cartridge.rumble", rumble).putBoolean("cartridge.sensors", sensors)
                .putBoolean("cartridge.solar_auto", solarAuto).putInt("cartridge.solar_level", solarLevel)
                .apply();
    }
}
