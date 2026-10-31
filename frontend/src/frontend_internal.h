#ifndef RAVENEMU_FRONTEND_INTERNAL_H
#define RAVENEMU_FRONTEND_INTERNAL_H

/* Between the Libretro host and the frontend's other modules (achievements),
 * not part of the application's API: these hand out Libretro structures. */
#include <ravenemu/frontend.h>
#include <libretro.h>

/* The memory map the loaded game's core declared (RETRO_ENVIRONMENT_SET_MEMORY_MAPS),
 * or NULL without one. Its descriptors point into the core: valid until unload. */
const struct retro_memory_map *raven_frontend_memory_map(const raven_frontend *host);
/* retro_get_memory_data and its size for the loaded game; NULL without it. */
void *raven_frontend_core_memory(const raven_frontend *host, unsigned id, size_t *size);

#endif
