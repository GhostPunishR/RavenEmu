#include "native_preview.h"
#include <stdio.h>
#include <string.h>

#define CHECK(c) do { if (!(c)) { fprintf(stderr, "%s:%d: %s\n", __FILE__, __LINE__, #c); return 1; } } while (0)

int main(void) {
    uint32_t result[RAVEN_PREVIEW_CAPACITY];
    const uint8_t pixels[] = {255,0,0,255, 0,255,0,255, 0,0,255,255, 1,2,3,128};
    CHECK(raven_preview_argb(pixels, sizeof(pixels), 2, 2, result, RAVEN_PREVIEW_CAPACITY) == 6);
    CHECK(result[0] == 2 && result[1] == 2);
    CHECK(result[2] == UINT32_C(0xffff0000) && result[3] == UINT32_C(0xff00ff00));
    CHECK(result[4] == UINT32_C(0xff0000ff) && result[5] == UINT32_C(0x80010203));
    memset(result, 0x55, sizeof(result));
    CHECK(!raven_preview_argb(pixels, 15, 2, 2, result, RAVEN_PREVIEW_CAPACITY));
    CHECK(!raven_preview_argb(pixels, 16, 2, 2, result, 5));
    CHECK(!raven_preview_argb(pixels, 16, 0, 2, result, RAVEN_PREVIEW_CAPACITY));
    CHECK(!raven_preview_argb(pixels, 16, 2049, 2, result, RAVEN_PREVIEW_CAPACITY));
    CHECK(!raven_preview_argb(NULL, 16, 2, 2, result, RAVEN_PREVIEW_CAPACITY));
    CHECK(!raven_preview_argb(pixels, 16, 2, 2, NULL, RAVEN_PREVIEW_CAPACITY));
    CHECK(result[0] == UINT32_C(0x55555555));
    uint8_t gba[240 * 160 * 4];
    memset(gba, 255, sizeof(gba));
    gba[0] = 0; gba[1] = 0; gba[2] = 0;
    CHECK(raven_preview_argb(gba, sizeof(gba), 240, 160, result, RAVEN_PREVIEW_CAPACITY) == 2 + 160 * 106);
    CHECK(result[0] == 160 && result[1] == 106 && result[2] == UINT32_C(0xff000000));
    CHECK(result[3] == UINT32_C(0xffffffff));
    uint8_t portrait[200 * 90 * 4] = {0};
    CHECK(raven_preview_argb(portrait, sizeof(portrait), 90, 200, result, RAVEN_PREVIEW_CAPACITY) == 2 + 72 * 160);
    CHECK(result[0] == 72 && result[1] == 160);
    uint8_t strip[2048 * 4] = {0};
    CHECK(raven_preview_argb(strip, sizeof(strip), 2048, 1, result, RAVEN_PREVIEW_CAPACITY) == 162);
    CHECK(result[0] == 160 && result[1] == 1);
    puts("Native preview: colors, orientation, bounds and aspect ratios passed");
    return 0;
}
