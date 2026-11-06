#ifndef RAVEN_NATIVE_PREVIEW_H
#define RAVEN_NATIVE_PREVIEW_H

#include <stddef.h>
#include <stdint.h>

#define RAVEN_PREVIEW_EDGE 160u
#define RAVEN_PREVIEW_CAPACITY (2u + RAVEN_PREVIEW_EDGE * RAVEN_PREVIEW_EDGE)

/* GL owner only. Copy tightly packed, top-first RGBA into width, height,
 * Android ARGB pixels. Never upscale; return zero without touching output
 * when dimensions, source length or destination capacity are invalid. */
size_t raven_preview_argb(const uint8_t *rgba, size_t bytes, unsigned width,
                          unsigned height, uint32_t *output, size_t capacity);

#endif
