#ifndef RAVEN_NATIVE_SENSORS_H
#define RAVEN_NATIVE_SENSORS_H

/* The phone's accelerometer, gyroscope and light sensor behind Libretro's
 * sensor interface, through the NDK (RavenEmu code). Emulation owner only:
 * the GL thread, or the lifecycle once it is suspended. The events queue on
 * the first caller's thread; a paused game switches the sensors off, for the
 * battery. */

/* A retro_sensor_action at `rate` Hz. Enabling answers 0 when the phone has
 * no such sensor; disabling always succeeds. */
int raven_sensors_set_state(unsigned action, unsigned rate);
/* Takes the events that arrived since; once per drawn frame. */
void raven_sensors_poll(void);
/* The latest RETRO_SENSOR_* reading, 0 when off: acceleration in m/s² along the screen
 * as shown (x right, y up), gyroscope in rad/s, illuminance in lux. */
float raven_sensors_read(unsigned id);
/* Display.getRotation(): 0 to 3 quarter turns. */
void raven_sensors_set_rotation(int rotation);
/* Paused: every sensor off. Running: back on as the core asked. */
void raven_sensors_set_active(int active);
/* The game closed: all off, nothing wanted any more. */
void raven_sensors_close(void);

#endif
