/* The achievements' progress after a save state's bytes (RavenEmu code, AGPLv3). */
#include "state_progress.h"

#include <string.h>

static const uint8_t MAGIC[8] = {'R', 'A', 'V', 'E', 'N', 'R', 'A', '1'};

size_t raven_state_progress_total(size_t core, size_t progress) {
    return progress ? core + progress + RAVEN_STATE_PROGRESS_FOOTER : core;
}

void raven_state_progress_seal(uint8_t *state, size_t core, size_t progress) {
    if (!state || !progress) return;
    uint8_t *footer = state + core + progress;
    const uint32_t length = (uint32_t)progress;
    for (int i = 0; i < 4; ++i) footer[i] = (uint8_t)(length >> (8 * i));
    memcpy(footer + 4, MAGIC, sizeof(MAGIC));
}

int raven_state_progress_split(const uint8_t *state, size_t size, size_t *core, const uint8_t **progress,
                               size_t *progress_size) {
    *core = size;
    *progress = NULL;
    *progress_size = 0;
    if (!state || size < RAVEN_STATE_PROGRESS_FOOTER) return 0;
    const uint8_t *footer = state + size - RAVEN_STATE_PROGRESS_FOOTER;
    if (memcmp(footer + 4, MAGIC, sizeof(MAGIC)) != 0) return 0;
    uint32_t length = 0;
    for (int i = 0; i < 4; ++i) length |= (uint32_t)footer[i] << (8 * i);
    /* The core keeps at least a byte: a footer alone is no state. */
    if ((size_t)length >= size - RAVEN_STATE_PROGRESS_FOOTER) return 0;
    *core = size - RAVEN_STATE_PROGRESS_FOOTER - length;
    *progress = length ? state + *core : NULL;
    *progress_size = length;
    return 1;
}
