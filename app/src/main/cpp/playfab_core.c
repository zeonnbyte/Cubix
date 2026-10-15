/* Sumber C kecil supaya CMake memakai linker C untuk stub PlayFab (target yang hanya berisi assembly
 * sering gagal menghasilkan file .so). */
__attribute__((visibility("hidden"))) int playfab_stub_marker(void) { return 0; }
