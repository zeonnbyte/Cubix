#ifndef CUBIX_VERSION
#define CUBIX_VERSION "unknown"
#endif
#include <jni.h>
#include <android/log.h>
#include <signal.h>
#include <stdio.h>
#include <string.h>
#include <stdint.h>
#include <ucontext.h>
#include <stdlib.h>
#include <elf.h>
#include "crashdiag.h"

// CUBIX_TEST_ARM hanya dipakai saat menguji kode ini di komputer biasa.
#if defined(__arm__) || defined(CUBIX_TEST_ARM)
#define CUBIX_ARM 1
#endif

#define TAG "Cubix"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, TAG, __VA_ARGS__)

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

// ---- Ringkasan modul: library Minecraft yang diekstrak ke .../mclibs/ ----
struct Module {
    char path[160];
    uintptr_t base;
    uintptr_t end;
};
static const int MAX_MODS = 32;
static Module g_mods[MAX_MODS];
static int g_nmods = 0;

static const char* base_name(const char* p) {
    const char* s = strrchr(p, '/');
    return s ? s + 1 : p;
}

// Satukan region /proc/self/maps per file. Region anonim yang menempel di ujung modul
// (biasanya .bss) ikut dihitung supaya register yang menunjuk ke .bss tetap dikenali.
static void build_modules() {
    g_nmods = 0;
    int cur = -1;
    for (int i = 0; i < g_nregions; i++) {
        const Region& r = g_regions[i];
        if (r.path[0] == '/' && strstr(r.path, "/mclibs/")) {
            int k = -1;
            for (int j = 0; j < g_nmods; j++) {
                if (strcmp(g_mods[j].path, r.path) == 0) { k = j; break; }
            }
            if (k < 0) {
                if (g_nmods >= MAX_MODS) { cur = -1; continue; }
                k = g_nmods++;
                strncpy(g_mods[k].path, r.path, sizeof(g_mods[k].path) - 1);
                g_mods[k].path[sizeof(g_mods[k].path) - 1] = 0;
                g_mods[k].base = r.lo;
                g_mods[k].end = r.hi;
            }
            if (r.lo < g_mods[k].base) g_mods[k].base = r.lo;
            if (r.hi > g_mods[k].end) g_mods[k].end = r.hi;
            cur = k;
        } else if (cur >= 0 && r.lo == g_mods[cur].end &&
                   (r.path[0] == 0 || strstr(r.path, ".bss"))) {
            g_mods[cur].end = r.hi;
            cur = -1;   // hanya satu region anonim (.bss) yang diserap
        } else {
            cur = -1;
        }
    }
}

static Module* find_module(uintptr_t v) {
    for (int i = 0; i < g_nmods; i++) {
        if (v >= g_mods[i].base && v < g_mods[i].end) return &g_mods[i];
    }
    return nullptr;
}

static bool readable_range(uintptr_t lo, size_t len) {
    Region* a = find_region(lo);
    return a && a->perms[0] == 'r' && find_region(lo + len - 1) == a;
}

static void hex_bytes(char* out, size_t cap, const unsigned char* b, int n) {
    size_t p = 0;
    for (int i = 0; i < n && p + 3 < cap; i++) p += snprintf(out + p, cap - p, "%02x", b[i]);
}

#if defined(CUBIX_ARM)
// Baca instruksi pemanggil tepat sebelum lr: dari sini kelihatan register mana yang dipakai
// untuk melompat ke alamat tidak valid.
static void describe_call_site(uintptr_t lr, const unsigned long R[16]) {
    bool thumb = (lr & 1) != 0;
    uintptr_t a = lr & ~(uintptr_t)1;
    if (!readable_range(a - 24, 32)) {
        LOGI("call site: memori di sekitar lr tidak terbaca");
        return;
    }
    char hex[80];
    hex_bytes(hex, sizeof(hex), (const unsigned char*)(a - 24), 32);
    LOGI("code@lr-24 (%s): %s", thumb ? "thumb" : "arm", hex);
    CallSite cs = decode_call_before((const unsigned char*)(a - 4), thumb);
    if (cs.kind == CALL_BLX_REG) {
        LOGI("call site: blx r%d, nilai r%d = %08lx", cs.reg, cs.reg, R[cs.reg]);
    } else if (cs.kind == CALL_BL_IMM) {
        LOGI("call site: bl/blx ke alamat tetap (bukan lompat lewat register)");
    } else {
        LOGI("call site: instruksi sebelum lr bukan blx/bl yang dikenali");
    }
}

// Apakah nilai kecil (pc/register) kelihatan seperti offset yang belum ditambah basis modul?
// Kalau literal itu ada di data modul, berarti relokasi tidak sampai ke slot itu; kalau tidak
// ada, nilainya dihitung saat runtime (mis. basis=0 + offset).
static void check_unrelocated(const char* name, uintptr_t v, Module* m) {
    if (!m) return;
    if (!looks_like_unrelocated((uint32_t)v, (uint32_t)(m->end - m->base))) return;
    LOGI("%s=%08lx cocok dengan offset di %s; basis+nilai=%08lx (di dalam modul)", name,
         (unsigned long)v, base_name(m->path), (unsigned long)(m->base + v));
    int hits = 0;
    for (int i = 0; i < g_nregions && hits < 8; i++) {
        Region& r = g_regions[i];
        if (r.perms[0] != 'r' || r.perms[2] == 'x' || r.lo < m->base || r.hi > m->end) continue;
        const uint32_t* w = (const uint32_t*)r.lo;
        size_t n = (r.hi - r.lo) / 4;
        for (size_t k = 0; k < n && hits < 8; k++) {
            if (w[k] == (uint32_t)v) {
                LOGI("  literal %08lx ada di %s+0x%lx (region %s)", (unsigned long)v,
                     base_name(m->path), (unsigned long)(r.lo + k * 4 - m->base), r.perms);
                hits++;
            }
        }
    }
    if (hits == 0) LOGI("  literal %08lx tidak ditemukan di memori modul (nilai dihitung saat runtime)",
                        (unsigned long)v);
}

// Isi memori di sekitar register yang menunjuk ke modul pemanggil (untuk melihat slot data).
static void dump_reg_memory(const unsigned long R[16], Module* m) {
    if (!m) return;
    int shown = 0;
    for (int i = 0; i < 13 && shown < 8; i++) {
        uintptr_t v = R[i];
        if (v < m->base + 16 || v >= m->end) continue;
        uintptr_t s = (v & ~(uintptr_t)3) - 8;
        if (!readable_range(s, 24)) continue;
        const uint32_t* w = (const uint32_t*)s;
        LOGI("mem[r%d-8]: %08x %08x %08x %08x %08x %08x  (r%d=%s+0x%lx)", i, w[0], w[1], w[2],
             w[3], w[4], w[5], i, base_name(m->path), (unsigned long)(v - m->base));
        shown++;
    }
}

// Salin string C dari memori proses dengan aman (cek region di setiap byte).
static void safe_cstr(uintptr_t a, char* out, size_t cap) {
    size_t i = 0;
    for (; i + 1 < cap; i++) {
        if (!readable_range(a + i, 1)) break;
        char c = *(const char*)(a + i);
        if (c == 0) break;
        out[i] = c;
    }
    out[i] = 0;
}

// Cek register yang menunjuk ke slot memori berisi nilai yang sama dengan pc. Pola PLT ARM
// "ldr pc, [ip, #n]!" membuat ip = alamat slot GOT; kalau slot itu masih berisi alamat awal
// (link-time) berarti relokasi JUMP_SLOT belum/tidak mengisinya.
static uint32_t check_slot_registers(const unsigned long R[16], uintptr_t pc) {
    uint32_t first = 0;
    for (int i = 0; i < 13; i++) {
        uintptr_t v = R[i];
        if ((v & 3) != 0 || !readable_range(v, 4)) continue;
        if (*(const uint32_t*)v != (uint32_t)pc) continue;
        Module* mm = find_module(v);
        if (mm) {
            LOGI("[r%d] = %08lx isinya sama dengan pc: slot di %s+0x%lx (GOT belum terisi?)", i,
                 (unsigned long)v, base_name(mm->path), (unsigned long)(v - mm->base));
            if (!first) first = (uint32_t)(v - mm->base);
        } else {
            LOGI("[r%d] = %08lx isinya sama dengan pc", i, (unsigned long)v);
        }
    }
    return first;
}

// Kalau pc adalah offset di modul pemanggil, lihat byte di (basis + pc). Awal PLT ARM
// (push {lr}; ldr lr,[pc,#4]; ...) berbentuk 04e02de5 04e09fe5.
static void peek_as_offset(uintptr_t pc, Module* m) {
    if (!m || !looks_like_unrelocated((uint32_t)pc, (uint32_t)(m->end - m->base))) return;
    uintptr_t a = m->base + pc;
    if (!readable_range(a, 16)) {
        LOGI("byte di basis+pc (%08lx) tidak terbaca", (unsigned long)a);
        return;
    }
    char hex[40];
    hex_bytes(hex, sizeof(hex), (const unsigned char*)a, 16);
    const unsigned char* b = (const unsigned char*)a;
    bool plt0 = b[0] == 0x04 && b[1] == 0xe0 && b[2] == 0x2d && b[3] == 0xe5;
    LOGI("byte di basis+pc (%s+0x%lx): %s%s", base_name(m->path), (unsigned long)pc, hex,
         plt0 ? "  <- awal PLT (stub lazy binding)" : "");
}

// Baca tabel dinamis modul dari memori: dependensi, relokasi, dan daftar init_array.
// Ini menunjukkan apakah relokasi PLT (JMPREL) ada, dan konstruktor mana yang dijalankan.
static void dump_dynamic(Module* m, uint32_t slotv) {
    if (!m) return;
    const char* nm = base_name(m->path);
    uintptr_t base = m->base;
    if (!readable_range(base, sizeof(Elf32_Ehdr))) return;
    const Elf32_Ehdr* eh = (const Elf32_Ehdr*)base;
    if (memcmp(eh->e_ident, ELFMAG, SELFMAG) != 0 || eh->e_ident[EI_CLASS] != ELFCLASS32) {
        LOGI("dyn %s: header ELF32 tidak terbaca", nm);
        return;
    }
    if (!readable_range(base + eh->e_phoff, (size_t)eh->e_phnum * sizeof(Elf32_Phdr))) return;
    const Elf32_Phdr* ph = (const Elf32_Phdr*)(base + eh->e_phoff);
    uint32_t dynv = 0, dynsz = 0;
    for (int i = 0; i < eh->e_phnum; i++) {
        if (ph[i].p_type == PT_DYNAMIC) { dynv = ph[i].p_vaddr; dynsz = ph[i].p_memsz; }
    }
    if (!dynv || !readable_range(base + dynv, dynsz)) {
        LOGI("dyn %s: PT_DYNAMIC tidak terbaca", nm);
        return;
    }
    const Elf32_Dyn* d = (const Elf32_Dyn*)(base + dynv);
    uint32_t nd = dynsz / sizeof(Elf32_Dyn);

    uint32_t strtab = 0, symtab = 0, init = 0, initArr = 0, initArrSz = 0, pltgot = 0;
    uint32_t jmprel = 0, pltrelsz = 0, rel = 0, relsz = 0, arel = 0, arelsz = 0;
    uint32_t relr = 0, relrsz = 0, arelr = 0, arelrsz = 0, flags = 0, flags1 = 0;
    uint32_t needed[24];
    int nneeded = 0;
    for (uint32_t i = 0; i < nd && d[i].d_tag != 0; i++) {
        uint32_t v = d[i].d_un.d_val;
        switch ((uint32_t)d[i].d_tag) {
            case 1: if (nneeded < 24) needed[nneeded++] = v; break;      // DT_NEEDED
            case 2: pltrelsz = v; break;                                  // DT_PLTRELSZ
            case 3: pltgot = v; break;                                    // DT_PLTGOT
            case 5: strtab = v; break;                                    // DT_STRTAB
            case 6: symtab = v; break;                                    // DT_SYMTAB
            case 12: init = v; break;                                     // DT_INIT
            case 17: rel = v; break;                                      // DT_REL
            case 18: relsz = v; break;                                    // DT_RELSZ
            case 23: jmprel = v; break;                                   // DT_JMPREL
            case 25: initArr = v; break;                                  // DT_INIT_ARRAY
            case 27: initArrSz = v; break;                                // DT_INIT_ARRAYSZ
            case 30: flags = v; break;                                    // DT_FLAGS
            case 35: relrsz = v; break;                                   // DT_RELRSZ
            case 36: relr = v; break;                                     // DT_RELR
            case 0x6000000f: arel = v; break;                             // DT_ANDROID_REL
            case 0x60000010: arelsz = v; break;                           // DT_ANDROID_RELSZ
            case 0x6fffe000: arelr = v; break;                            // DT_ANDROID_RELR
            case 0x6fffe001: arelrsz = v; break;                          // DT_ANDROID_RELRSZ
            case 0x6ffffffb: flags1 = v; break;                           // DT_FLAGS_1
            default: break;
        }
    }
    LOGI("dyn %s: JMPREL=%x PLTRELSZ=%x PLTGOT=%x REL=%x/%x ANDROID_REL=%x/%x RELR=%x/%x "
         "ANDROID_RELR=%x/%x FLAGS=%x FLAGS_1=%x", nm, jmprel, pltrelsz, pltgot, rel, relsz,
         arel, arelsz, relr, relrsz, arelr, arelrsz, flags, flags1);
    if (strtab) {
        uintptr_t st = (strtab >= base) ? (uintptr_t)strtab : base + strtab;
        char line[200];
        size_t p = 0;
        for (int i = 0; i < nneeded && p + 40 < sizeof(line); i++) {
            char name[48];
            safe_cstr(st + needed[i], name, sizeof(name));
            p += snprintf(line + p, sizeof(line) - p, "%s%s", i ? " " : "", name);
        }
        LOGI("dyn %s: NEEDED: %s", nm, nneeded ? line : "(kosong)");
    }
    if (slotv) {
        // Cari relokasi untuk slot GOT yang belum terisi: simbol apa yang seharusnya diisi di situ.
        const uint32_t tabs[2][2] = {{jmprel, pltrelsz}, {rel, relsz}};
        const char* tabname[2] = {"JMPREL", "REL"};
        bool found = false;
        for (int t = 0; t < 2 && !found; t++) {
            uint32_t off = tabs[t][0], sz = tabs[t][1];
            if (!off || !sz || !readable_range(base + off, sz)) continue;
            const Elf32_Rel* rr = (const Elf32_Rel*)(base + off);
            for (uint32_t i = 0; i < sz / sizeof(Elf32_Rel); i++) {
                if (rr[i].r_offset != slotv) continue;
                found = true;
                uint32_t sym = ELF32_R_SYM(rr[i].r_info), type = ELF32_R_TYPE(rr[i].r_info);
                char name[80] = "?";
                if (symtab && strtab) {
                    uintptr_t sa = base + symtab + (uintptr_t)sym * sizeof(Elf32_Sym);
                    uintptr_t st = (strtab >= base) ? (uintptr_t)strtab : base + strtab;
                    if (readable_range(sa, sizeof(Elf32_Sym))) {
                        safe_cstr(st + ((const Elf32_Sym*)sa)->st_name, name, sizeof(name));
                    }
                }
                LOGI("dyn %s: slot +0x%x ada di %s[%u], tipe=%u, simbol=%s", nm, slotv,
                     tabname[t], i, type, name);
                break;
            }
        }
        if (!found) {
            LOGI("dyn %s: slot +0x%x TIDAK ada di JMPREL/REL%s", nm, slotv,
                 (arel || arelr || relr) ? " (ada relokasi terkemas, tidak diperiksa)" : "");
        }
    }
    if (init) LOGI("dyn %s: DT_INIT=%x", nm, init);
    if (initArr && initArrSz && readable_range(base + initArr, initArrSz)) {
        const uint32_t* ia = (const uint32_t*)(base + initArr);
        uint32_t n = initArrSz / 4;
        for (uint32_t i = 0; i < n && i < 16; i++) {
            uint32_t v = ia[i];
            uint32_t b32 = (uint32_t)base;
            if (v >= b32 && v < b32 + (uint32_t)(m->end - m->base)) {
                LOGI("dyn %s: init_array[%u] = %08x (basis+0x%x)", nm, i, v, v - b32);
            } else {
                LOGI("dyn %s: init_array[%u] = %08x (di luar modul / belum direlokasi)", nm, i, v);
            }
        }
        if (n > 16) LOGI("dyn %s: init_array berisi %u entri (16 pertama ditampilkan)", nm, n);
    }
}
#endif

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

#if defined(CUBIX_ARM)
    ucontext_t* uc = (ucontext_t*)ctx;
    uintptr_t pc = uc->uc_mcontext.arm_pc;
    uintptr_t lr = uc->uc_mcontext.arm_lr;
    uintptr_t sp = uc->uc_mcontext.arm_sp;
    Region* pr = find_region(pc);
    // fix_11: tidak ada lagi "pemulihan otomatis". Crash dicatat apa adanya lalu diserahkan
    // ke handler bawaan Android supaya penyebab aslinya kelihatan.
#endif

    LOGI("==== Cubix crash handler: sig=%d fault_addr=%p ====", sig, si ? si->si_addr : nullptr);
#if defined(CUBIX_ARM)
    describe("pc", pc);
    describe("lr", lr);
    {
        Region* r1 = find_region(pc);
        if (r1 && r1->path[0]) nearest_symbol(r1->path, (uint32_t)(pc - r1->lo + r1->off), "pc");
        Region* r2 = find_region(lr);
        if (r2 && r2->path[0]) nearest_symbol(r2->path, (uint32_t)(lr - r2->lo + r2->off), "lr");
    }
    build_modules();
    unsigned long R[16] = {
        uc->uc_mcontext.arm_r0, uc->uc_mcontext.arm_r1, uc->uc_mcontext.arm_r2,
        uc->uc_mcontext.arm_r3, uc->uc_mcontext.arm_r4, uc->uc_mcontext.arm_r5,
        uc->uc_mcontext.arm_r6, uc->uc_mcontext.arm_r7, uc->uc_mcontext.arm_r8,
        uc->uc_mcontext.arm_r9, uc->uc_mcontext.arm_r10, uc->uc_mcontext.arm_fp,
        uc->uc_mcontext.arm_ip, uc->uc_mcontext.arm_sp, uc->uc_mcontext.arm_lr,
        uc->uc_mcontext.arm_pc};
    LOGI("r0=%08lx r1=%08lx r2=%08lx r3=%08lx r4=%08lx r5=%08lx",
         uc->uc_mcontext.arm_r0, uc->uc_mcontext.arm_r1, uc->uc_mcontext.arm_r2,
         uc->uc_mcontext.arm_r3, uc->uc_mcontext.arm_r4, uc->uc_mcontext.arm_r5);
    LOGI("r6=%08lx r7=%08lx r8=%08lx r9=%08lx r10=%08lx fp=%08lx sp=%08lx",
         uc->uc_mcontext.arm_r6, uc->uc_mcontext.arm_r7, uc->uc_mcontext.arm_r8,
         uc->uc_mcontext.arm_r9, uc->uc_mcontext.arm_r10, uc->uc_mcontext.arm_fp,
         (unsigned long)sp);

    {
        // Modul pemanggil (yang berisi lr) dan analisis lompatan ke alamat tidak valid
        Module* caller = find_module(lr);
        if (caller) LOGI("modul pemanggil: %s basis=%08lx", base_name(caller->path),
                         (unsigned long)caller->base);
        describe_call_site(lr, R);
        if (!find_region(pc)) {
            LOGI("pc tidak berada di memori terpetakan");
            check_unrelocated("pc", pc, caller);
            peek_as_offset(pc, caller);
        }
        uint32_t slotv = check_slot_registers(R, pc);
        dump_dynamic(caller, slotv);
        for (int i = 0; i < 13; i++) {
            char nm[8];
            snprintf(nm, sizeof(nm), "r%d", i);
            if (R[i] == pc) check_unrelocated(nm, R[i], caller);
        }
        dump_reg_memory(R, caller);
    }

    // Byte kode di sekitar pc (untuk dibongkar belakangan)
    uintptr_t base = (pc & ~(uintptr_t)1) - 32;
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
    build_modules();
    for (int i = 0; i < g_nmods; i++) {
        LOGI("modul %08lx-%08lx %s", (unsigned long)g_mods[i].base, (unsigned long)g_mods[i].end,
             base_name(g_mods[i].path));
    }
    {
        // Segmen lengkap modul pemanggil (perms + offset file) untuk memetakan offset ke alamat
        Module* caller = nullptr;
#if defined(CUBIX_ARM)
        caller = find_module(lr);
#endif
        for (int i = 0; i < g_nregions; i++) {
            bool mine = caller && g_regions[i].lo >= caller->base && g_regions[i].hi <= caller->end;
            if (mine || strstr(g_regions[i].path, "libmaesdk") ||
                strstr(g_regions[i].path, "libminecraftpe")) {
                LOGI("map %08lx-%08lx %s off=%lx %s", (unsigned long)g_regions[i].lo,
                     (unsigned long)g_regions[i].hi, g_regions[i].perms,
                     (unsigned long)g_regions[i].off, g_regions[i].path);
            }
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
    LOGI("libcubix loaded (armeabi-v7a), crash handler terpasang (%s)", CUBIX_VERSION);
    return JNI_VERSION_1_6;
}
