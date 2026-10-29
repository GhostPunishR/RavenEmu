#include "native_menu.h"
#include <GLES2/gl2.h>
#include <stddef.h>
#include <stdint.h>

/* First native C menu: UI labels and selection are drawn by GLES2, not
 * Compose or Java widgets. Platform services (the Android file picker and
 * accessible library list) remain handled by the Java Activity. */
static int visible;
static int selection;
static const char *const items[] = {
    "REPRENDRE", "BIBLIOTHEQUE", "SAUVER ETAT", "CHARGER ETAT"
};
#define ITEM_COUNT ((int)(sizeof(items) / sizeof(items[0])))

int raven_menu_input(int command) {
    if (command == 0) {
        visible = !visible;
        if (visible) selection = 0;
        return visible ? 0 : 1;
    }
    if (command == 4) { visible = 0; return 1; }
    if (!visible) return 0;
    if (command == 1) { selection = (selection + ITEM_COUNT - 1) % ITEM_COUNT; return 0; }
    if (command == 2) { selection = (selection + 1) % ITEM_COUNT; return 0; }
    if (command == 3) { visible = 0; return selection + 1; }
    return 0;
}
int raven_menu_visible(void) { return visible; }

static const uint8_t font[26][7] = {
    {14,17,17,31,17,17,17}, /* A */
    {30,17,17,30,17,17,30}, /* B */
    {14,17,16,16,16,17,14}, /* C */
    {30,17,17,17,17,17,30}, /* D */
    {31,16,16,30,16,16,31}, /* E */
    {31,16,16,30,16,16,16}, /* F */
    {14,17,16,23,17,17,14}, /* G */
    {17,17,17,31,17,17,17}, /* H */
    {31,4,4,4,4,4,31}, /* I */
    {7,2,2,2,2,18,12}, /* J */
    {17,18,20,24,20,18,17}, /* K */
    {16,16,16,16,16,16,31}, /* L */
    {17,27,21,21,17,17,17}, /* M */
    {17,25,21,19,17,17,17}, /* N */
    {14,17,17,17,17,17,14}, /* O */
    {30,17,17,30,16,16,16}, /* P */
    {14,17,17,17,21,18,13}, /* Q */
    {30,17,17,30,20,18,17}, /* R */
    {15,16,16,14,1,1,30}, /* S */
    {31,4,4,4,4,4,4}, /* T */
    {17,17,17,17,17,17,14}, /* U */
    {17,17,17,17,17,10,4}, /* V */
    {17,17,17,21,21,21,10}, /* W */
    {17,17,10,4,10,17,17}, /* X */
    {17,17,10,4,4,4,4}, /* Y */
    {31,1,2,4,8,16,31} /* Z */
};

static void rectangle(int x, int y, int width, int height, int sw, int sh,
                      float r, float g, float b) {
    if (width <= 0 || height <= 0) return;
    if (x < 0) { width += x; x = 0; }
    if (y < 0) { height += y; y = 0; }
    if (x >= sw || y >= sh) return;
    if (width > sw - x) width = sw - x;
    if (height > sh - y) height = sh - y;
    if (width <= 0 || height <= 0) return;
    glScissor(x, y, width, height);
    glClearColor(r, g, b, 1.f);
    glClear(GL_COLOR_BUFFER_BIT);
}

static int measure(const char *text, int scale) {
    int count = 0;
    while (text && *text++) ++count;
    return count * scale * 6;
}

static void write_text(const char *text, int x, int y, int sw, int sh, int scale,
                       float r, float g, float b) {
    for (; text && *text; ++text, x += scale * 6) {
        if (*text < 'A' || *text > 'Z') continue;
        const uint8_t *glyph = font[(int)(*text - 'A')];
        for (int row = 0; row < 7; ++row) {
            for (int column = 0; column < 5; ++column) {
                if (glyph[row] & (1u << (4 - column)))
                    rectangle(x + column * scale, y + (6 - row) * scale,
                              scale, scale, sw, sh, r, g, b);
            }
        }
    }
}

void raven_menu_draw(int sw, int sh) {
    if (!visible || sw < 100 || sh < 100) return;
    int scale = sw / 155;
    if (sh / 110 < scale) scale = sh / 110;
    if (scale < 1) scale = 1;
    if (scale > 8) scale = 8;

    const int panel_width = sw - 24 * scale > 90 * scale ? 120 * scale : sw - 16;
    const int panel_height = 72 * scale;
    const int x = (sw - panel_width) / 2;
    const int y = (sh - panel_height) / 2;
    const int padding = 7 * scale;

    glEnable(GL_SCISSOR_TEST);
    rectangle(x - scale, y - scale, panel_width + 2 * scale,
              panel_height + 2 * scale, sw, sh, .45f, .68f, .95f);
    rectangle(x, y, panel_width, panel_height, sw, sh, .07f, .09f, .16f);
    write_text("RAVENEMU", x + padding, y + panel_height - 13 * scale,
               sw, sh, scale, .92f, .94f, 1.f);
    for (int i = 0; i < ITEM_COUNT; ++i) {
        int baseline = y + panel_height - (27 + 11 * i) * scale;
        if (selection == i)
            rectangle(x + 3 * scale, baseline - 2 * scale,
                      panel_width - 6 * scale, 10 * scale,
                      sw, sh, .16f, .31f, .49f);
        write_text(items[i], x + padding, baseline, sw, sh, scale,
                   1.f, 1.f, 1.f);
    }
    glDisable(GL_SCISSOR_TEST);
}
