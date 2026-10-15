#include <jni.h>
#include <android/log.h>
#include <signal.h>
#include <stdio.h>
#include <string.h>
#include <stdint.h>
#include <ucontext.h>
#include <stdlib.h>
#include <elf.h>

#define TAG "Cubix"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)

// Crash handler: dijalankan di dalam proses Cubix, jadi bisa membaca memorinya sendiri.
// Mencatat register, pemanggil di stack, dan byte kode di sekitar pc, lalu menyerahkan
// ke handler bawaan Android supaya tombstone normal tetap dibuat.

struct Region {
    uintptr_t lo;
    uintptr_t hi;
    uintptr_t off;
    char perms[5];
    char path[160];
};

static const int MAX_REGIONS = 8192;
static Region g_regions[MAX_REGIONS];
static int g_nregions = 0;

static const int SIGS[] = {SIGSEGV, SIGBUS, SIGILL};
static const int NSIGS = 3;
static struct sigaction g_old[3];

static void load_maps() {
    FILE* f = fopen("/proc/self/maps", "r");
    if (!f) return;
    char line[512];
    g_nregions = 0;
    while (fgets(line, sizeof(line), f) && g_nregions < MAX_REGIONS) {
        unsigned long lo = 0, hi = 0, off = 0, ino = 0;
        char perms[8] = {0};
        char dev[16] = {0};
        char path[160] = {0};
        int n = sscanf(line, "%lx-%lx %7s %lx %15s %lu %159[^\n]", &lo, &hi, perms, &off, dev, &ino, path);
        if (n < 6) continue;
        Region& r = g_regions[g_nregions++];
        r.lo = lo;
        r.hi = hi;
        r.off = off;
        strncpy(r.perms, perms, 4);
        r.perms[4] = 0;
        strncpy(r.path, n >= 7 ? path : "", sizeof(r.path) - 1);
        r.path[sizeof(r.path) - 1] = 0;
    }
    fclose(f);
}

static Region* find_region(uintptr_t v) {
    for (int i = 0; i < g_nregions; i++) {
        if (v >= g_regions[i].lo && v < g_regions[i].hi) return &g_regions[i];
    }
    return nullptr;
}

static void describe(const char* name, uintptr_t v) {
    Region* r = find_region(v);
    if (r && r->path[0]) {
        LOGI("%s = %08lx  %s+0x%lx", name, (unsigned long)v, r->path,
             (unsigned long)(v - r->lo + r->off));
    } else {
        LOGI("%s = %08lx", name, (unsigned long)v);
    }
}


// Cari simbol ekspor terdekat (dari .dynsym di file .so) untuk offset tertentu.
static void nearest_symbol(const char* path, uint32_t off, const char* label) {
    FILE* f = fopen(path, "rb");
    if (!f) return;
    fseek(f, 0, SEEK_END);
    long size = ftell(f);
    fseek(f, 0, SEEK_SET);
    if (size <= 0 || size > 64 * 1024 * 1024) { fclose(f); return; }
    unsigned char* buf = (unsigned char*)malloc(size);
    if (!buf) { fclose(f); return; }
    size_t rd = fread(buf, 1, size, f);
    fclose(f);
    if ((long)rd != size) { free(buf); return; }
    Elf32_Ehdr* eh = (Elf32_Ehdr*)buf;
    if (memcmp(eh->e_ident, ELFMAG, SELFMAG) != 0 || eh->e_shoff == 0 ||
        eh->e_shoff + (uint32_t)eh->e_shnum * sizeof(Elf32_Shdr) > (uint32_t)size) {
        LOGI("%s: ELF tanpa section header, simbol tidak bisa dibaca", label);
        free(buf);
        return;
    }
    Elf32_Shdr* sh = (Elf32_Shdr*)(buf + eh->e_shoff);
    const char* best = nullptr;
    uint32_t bestVal = 0, bestSize = 0;
    uint32_t jniOnLoad = 0;
    for (int i = 0; i < eh->e_shnum; i++) {
        if (sh[i].sh_type != SHT_DYNSYM) continue;
        Elf32_Shdr& str = sh[sh[i].sh_link];
        const char* strtab = (const char*)(buf + str.sh_offset);
        Elf32_Sym* syms = (Elf32_Sym*)(buf + sh[i].sh_offset);
        int n = sh[i].sh_size / sizeof(Elf32_Sym);
        for (int j = 0; j < n; j++) {
            if (syms[j].st_shndx == 0 || ELF32_ST_TYPE(syms[j].st_info) != STT_FUNC) continue;
            uint32_t v = syms[j].st_value & ~1u;
            const char* nm = strtab + syms[j].st_name;
            if (strcmp(nm, "JNI_OnLoad") == 0) jniOnLoad = v;
            if (v <= off && v >= bestVal) {
                best = nm;
                bestVal = v;
                bestSize = syms[j].st_size;
            }
        }
    }
    if (jniOnLoad) LOGI("%s: JNI_OnLoad ada di offset 0x%x", label, jniOnLoad);
    if (best) {
        LOGI("%s: 0x%x dekat simbol %s+0x%x (ukuran simbol 0x%x)%s", label, off, best,
             off - bestVal, bestSize, (off - bestVal < bestSize) ? " [DI DALAM simbol]" : "");
    } else {
        LOGI("%s: 0x%x tidak ada simbol ekspor sebelumnya", label, off);
    }
    free(buf);
}

static void handler(int sig, siginfo_t* si, void* ctx) {
    load_maps();
    LOGI("==== Cubix crash handler: sig=%d fault_addr=%p ====", sig, si ? si->si_addr : nullptr);
#if defined(__arm__)
    ucontext_t* uc = (ucontext_t*)ctx;
    uintptr_t pc = uc->uc_mcontext.arm_pc;
    uintptr_t lr = uc->uc_mcontext.arm_lr;
    uintptr_t sp = uc->uc_mcontext.arm_sp;
    describe("pc", pc);
    describe("lr", lr);
    {
        Region* r1 = find_region(pc);
        if (r1 && r1->path[0]) nearest_symbol(r1->path, (uint32_t)(pc - r1->lo + r1->off), "pc");
        Region* r2 = find_region(lr);
        if (r2 && r2->path[0]) nearest_symbol(r2->path, (uint32_t)(lr - r2->lo + r2->off), "lr");
    }
    LOGI("r0=%08lx r1=%08lx r2=%08lx r3=%08lx r4=%08lx r5=%08lx",
         uc->uc_mcontext.arm_r0, uc->uc_mcontext.arm_r1, uc->uc_mcontext.arm_r2,
         uc->uc_mcontext.arm_r3, uc->uc_mcontext.arm_r4, uc->uc_mcontext.arm_r5);
    LOGI("r6=%08lx r7=%08lx r8=%08lx r9=%08lx r10=%08lx fp=%08lx sp=%08lx",
         uc->uc_mcontext.arm_r6, uc->uc_mcontext.arm_r7, uc->uc_mcontext.arm_r8,
         uc->uc_mcontext.arm_r9, uc->uc_mcontext.arm_r10, uc->uc_mcontext.arm_fp,
         (unsigned long)sp);

    // Byte kode di sekitar pc (untuk dibongkar belakangan)
    uintptr_t base = (pc & ~(uintptr_t)1) - 32;
    Region* pr = find_region(pc);
    if (pr && pr->perms[0] == 'r' && find_region(base) == pr && find_region(base + 64) == pr) {
        char hex[200];
        int p = 0;
        const unsigned char* b = (const unsigned char*)base;
        for (int i = 0; i < 64; i++) p += snprintf(hex + p, sizeof(hex) - p, "%02x", b[i]);
        LOGI("code@%08lx (file off 0x%lx): %s", (unsigned long)base,
             (unsigned long)(base - pr->lo + pr->off), hex);
    }

    // Pemindaian stack: alamat yang menunjuk ke kode di dalam library
    uintptr_t* s = (uintptr_t*)sp;
    int hits = 0;
    for (int i = 0; i < 4096 && hits < 40; i++) {
        Region* sr = find_region((uintptr_t)&s[i]);
        if (!sr || sr->perms[0] != 'r') break;
        uintptr_t v = s[i];
        Region* r = find_region(v);
        if (r && r->perms[2] == 'x' && r->path[0]) {
            char name[24];
            snprintf(name, sizeof(name), "stack+%d", i);
            describe(name, v);
            hits++;
        }
    }
#endif
    for (int i = 0; i < g_nregions; i++) {
        if (strstr(g_regions[i].path, "libmaesdk") || strstr(g_regions[i].path, "libminecraftpe")) {
            LOGI("map %08lx-%08lx %s off=%lx %s", (unsigned long)g_regions[i].lo,
                 (unsigned long)g_regions[i].hi, g_regions[i].perms,
                 (unsigned long)g_regions[i].off, g_regions[i].path);
        }
    }

    // Kembalikan handler asli dan biarkan instruksi yang crash dieksekusi ulang
    for (int i = 0; i < NSIGS; i++) {
        if (SIGS[i] == sig) sigaction(sig, &g_old[i], nullptr);
    }
}

static void install_handler() {
    struct sigaction sa;
    memset(&sa, 0, sizeof(sa));
    sa.sa_sigaction = handler;
    sa.sa_flags = SA_SIGINFO;
    sigemptyset(&sa.sa_mask);
    for (int i = 0; i < NSIGS; i++) sigaction(SIGS[i], &sa, &g_old[i]);
}

// Titik masuk native. Hook/mod nantinya dipasang di sini setelah
// libminecraftpe.so ter-load.
extern "C" JNIEXPORT jint JNI_OnLoad(JavaVM*, void*) {
    install_handler();
    LOGI("libcubix loaded (armeabi-v7a), crash handler terpasang");
    return JNI_VERSION_1_6;
}
