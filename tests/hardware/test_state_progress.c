/* The achievements' progress after a save state, and states without it. */
#include "state_progress.h"

#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#define CHECK(c) do { if (!(c)) { fprintf(stderr, "%s:%d: %s\n", __FILE__, __LINE__, #c); exit(1); } } while (0)

int main(void) {
    uint8_t state[64];
    const uint8_t core[5] = {1, 2, 3, 4, 5}, progress[3] = {9, 8, 7};
    size_t core_size, progress_size;
    const uint8_t *found;

    /* With progress: sealed, then split back. */
    CHECK(raven_state_progress_total(5, 3) == 5 + 3 + RAVEN_STATE_PROGRESS_FOOTER);
    memcpy(state, core, 5);
    memcpy(state + 5, progress, 3);
    raven_state_progress_seal(state, 5, 3);
    CHECK(memcmp(state + 12, "RAVENRA1", 8) == 0 && state[8] == 3 && state[9] == 0);
    CHECK(raven_state_progress_split(state, 20, &core_size, &found, &progress_size));
    CHECK(core_size == 5 && progress_size == 3 && found == state + 5 && memcmp(found, progress, 3) == 0);

    /* Without achievements: the core's state alone, unchanged. */
    CHECK(raven_state_progress_total(5, 0) == 5);
    CHECK(!raven_state_progress_split(core, 5, &core_size, &found, &progress_size));
    CHECK(core_size == 5 && found == NULL && progress_size == 0);

    /* A state from before, or one whose end only looks like a footer. */
    uint8_t old[40];
    memset(old, 0xAB, sizeof(old));
    CHECK(!raven_state_progress_split(old, sizeof(old), &core_size, &found, &progress_size) && core_size == 40);
    memcpy(state + 12, "RAVENRA2", 8);
    CHECK(!raven_state_progress_split(state, 20, &core_size, &found, &progress_size) && core_size == 20);
    /* A length past the state: no footer, all of it is the core's. */
    memcpy(state + 12, "RAVENRA1", 8);
    state[8] = 200;
    CHECK(!raven_state_progress_split(state, 20, &core_size, &found, &progress_size) && core_size == 20);
    /* An empty progress keeps its footer; a footer alone is no state. */
    memcpy(state, core, 5);
    raven_state_progress_seal(state, 5, 0);
    uint8_t empty[5 + RAVEN_STATE_PROGRESS_FOOTER];
    memcpy(empty, core, 5);
    memset(empty + 5, 0, 4);
    memcpy(empty + 9, "RAVENRA1", 8);
    CHECK(raven_state_progress_split(empty, sizeof(empty), &core_size, &found, &progress_size));
    CHECK(core_size == 5 && found == NULL && progress_size == 0);
    CHECK(!raven_state_progress_split(empty + 5, RAVEN_STATE_PROGRESS_FOOTER, &core_size, &found, &progress_size));
    CHECK(!raven_state_progress_split(NULL, 0, &core_size, &found, &progress_size));
    puts("State progress: 6 cases passed");
    return 0;
}
