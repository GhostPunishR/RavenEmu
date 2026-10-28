#include <ravenemu/frontend.h>
#include <stdio.h>
#include <string.h>

/* Validate the real RavenCore Libretro adapters can be loaded by the new C
 * frontend on Linux. No game or BIOS is required for this contract test. */
int main(int argc, char **argv) {
    if (argc != 4) {
        fprintf(stderr, "Expected paths to GB, GBC and GBA Libretro cores\n");
        return 1;
    }
    raven_frontend *host = raven_frontend_create();
    if (!host) return 2;
    for (int i = 1; i < argc; ++i) {
        if (!raven_frontend_open_core(host, argv[i], ".", ".")) {
            fprintf(stderr, "%s: %s\n", argv[i], raven_frontend_last_error(host));
            raven_frontend_destroy(host);
            return 3;
        }
        raven_core_info info;
        if (!raven_frontend_get_info(host, &info) || !info.name ||
            !info.version || !info.extensions || !*info.name ||
            !*info.extensions) {
            fprintf(stderr, "%s: missing Libretro metadata\n", argv[i]);
            raven_frontend_destroy(host);
            return 4;
        }
        printf("%s: %s (%s)\n", argv[i], info.name, info.version);
        raven_frontend_close_core(host);
    }
    raven_frontend_destroy(host);
    return 0;
}
