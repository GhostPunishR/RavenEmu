#ifndef RAVEN_STATE_PROGRESS_H
#define RAVEN_STATE_PROGRESS_H

#include <stddef.h>
#include <stdint.h>

/* A save state as the app keeps it: the core's state, then the progress of
 * the game's achievements (rcheevos' runtime) and a 12-byte footer, the
 * progress' length (little-endian) then the magic "RAVENRA1". A state saved
 * without achievements, or before they were kept, has no footer and loads
 * as before. RavenEmu code (AGPLv3); no I/O, no allocation. */
#define RAVEN_STATE_PROGRESS_FOOTER 12u

/* The bytes a state takes with this much progress (none: no footer). */
size_t raven_state_progress_total(size_t core, size_t progress);
/* After the core's state and the progress are in `state`: the footer. */
void raven_state_progress_seal(uint8_t *state, size_t core, size_t progress);
/* The core's share of a state and its progress. Returns 1 when the state
 * has a footer (progress may then be empty), 0 otherwise: all of it is the
 * core's, and *progress is NULL. */
int raven_state_progress_split(const uint8_t *state, size_t size, size_t *core, const uint8_t **progress,
                               size_t *progress_size);

#endif
