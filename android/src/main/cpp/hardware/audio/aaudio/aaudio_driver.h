/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Copyright (C) 2026 The RetroArch team
 * Copyright (C) 2026 GhostPunishR
 * Narrow PCM16 output adapter for the AAudio subset ported in aaudio_driver.c.
 * See that file for provenance and the complete original license notice.
 */
#ifndef RAVEN_RETROARCH_AAUDIO_H
#define RAVEN_RETROARCH_AAUDIO_H

#include <stdint.h>

/* Emulation owner only; callbacks consume the queue independently. Open starts
 * PCM16 stereo at the device rate. No calls from the audio callback itself. */
int raven_aaudio_open(int *sample_rate);
int raven_aaudio_running(void);
void raven_aaudio_push(int16_t left, int16_t right);
/* The device was disconnected (headphones, Bluetooth, USB): close and reopen. */
int raven_aaudio_lost(void);
/* Frames waiting in the software queue, and how many it may hold. */
void raven_aaudio_fill(uint32_t *queued, uint32_t *limit);
void raven_aaudio_set_running(int running);
/* Publish a consumer-owned purge without changing the stream's run state. */
void raven_aaudio_flush(void);
void raven_aaudio_close(void);

#endif
