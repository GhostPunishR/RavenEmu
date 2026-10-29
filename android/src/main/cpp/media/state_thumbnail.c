#include "state_thumbnail.h"

size_t raven_preview_argb(const uint8_t *rgba, size_t bytes, unsigned width,
                          unsigned height, uint32_t *output, size_t capacity) {
    if (!rgba || !output || !width || !height || width > 2048u || height > 2048u ||
            bytes < (size_t)width * height * 4u) return 0;
    unsigned w = width, h = height;
    if (w > RAVEN_PREVIEW_EDGE || h > RAVEN_PREVIEW_EDGE) {
        if (w >= h) {
            h = height * RAVEN_PREVIEW_EDGE / width;
            w = RAVEN_PREVIEW_EDGE;
        } else {
            w = width * RAVEN_PREVIEW_EDGE / height;
            h = RAVEN_PREVIEW_EDGE;
        }
        if (!w) w = 1;
        if (!h) h = 1;
    }
    size_t count = 2u + (size_t)w * h;
    if (capacity < count) return 0;
    output[0] = w;
    output[1] = h;
    for (unsigned y = 0; y < h; ++y) {
        unsigned source_y = y * height / h;
        for (unsigned x = 0; x < w; ++x) {
            unsigned source_x = x * width / w;
            const uint8_t *p = rgba + ((size_t)source_y * width + source_x) * 4u;
            output[2u + (size_t)y * w + x] = ((uint32_t)p[3] << 24u) |
                ((uint32_t)p[0] << 16u) | ((uint32_t)p[1] << 8u) | p[2];
        }
    }
    return count;
}
