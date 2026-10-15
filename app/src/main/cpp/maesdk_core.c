/* Pendukung stub libmaesdk: semua fungsi C++ yang tidak dikenali mengembalikan pointer ke
 * objek "kosong" yang vtable-nya berisi fungsi no-op, supaya pemanggil tidak crash. */
static char g_obj[4096] __attribute__((aligned(16)));
static void *g_vt[512];

__attribute__((visibility("hidden"))) void *maes_noop(void) { return g_obj; }

__attribute__((constructor)) static void maes_init(void) {
    for (int i = 0; i < 512; i++) g_vt[i] = (void *)maes_noop;
    ((void **)g_obj)[0] = (void *)g_vt;
}
