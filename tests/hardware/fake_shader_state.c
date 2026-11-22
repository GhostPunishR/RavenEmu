/* The renderer's unit test runs without presets: the stock program only. */
#include "shader_state.h"

void raven_shaders_context_lost(void) {}
void raven_shaders_reset(void) {}
int raven_shaders_draw(GLuint input, const uint8_t *rgba, int fresh, unsigned width, unsigned height,
                       int vx, int vy, int vw, int vh) {
    (void)input; (void)rgba; (void)fresh; (void)width; (void)height; (void)vx; (void)vy; (void)vw; (void)vh;
    return 0;
}
