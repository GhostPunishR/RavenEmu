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
/* A game controller's motion sensors in place of the phone's, while it is
 * the one played with (Java reads them, Android 12 and later): `kinds` says
 * which it has, RAVEN_SENSORS_CONTROLLER_*; their readings, along the
 * controller (no screen rotation), stand for the phone's until it is fed
 * with no kind. The light stays the phone's. A sensor the phone lacks can
 * be enabled when the controller has it. Emulation owner, before a frame. */
#define RAVEN_SENSORS_CONTROLLER_ACCELEROMETER 1u
#define RAVEN_SENSORS_CONTROLLER_GYROSCOPE 2u
void raven_sensors_feed_controller(unsigned kinds, const float acceleration[3], const float turning[3]);

#endif
