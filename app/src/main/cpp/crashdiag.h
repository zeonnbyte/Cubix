#pragma once
// Fungsi murni (tidak menyentuh memori proses) untuk diagnosa crash. Dipisah dari launcher.cpp
// supaya bisa diuji di komputer biasa tanpa HP.
#include <stdint.h>
#include <stddef.h>

enum CallKind {
    CALL_UNKNOWN = 0,
    CALL_BLX_REG = 1,   // blx rN  (lompat lewat register)
    CALL_BL_IMM = 2     // bl/blx <alamat tetap>
};

struct CallSite {
    CallKind kind;
    int reg;       // nomor register untuk CALL_BLX_REG, selain itu -1
    bool thumb;
};

// `before` menunjuk 4 byte tepat sebelum alamat kembali (lr tanpa bit thumb).
// Thumb: yang dicek instruksi 16-bit terakhir (blx rN) atau 32-bit terakhir (bl/blx imm).
// ARM  : instruksi 32-bit terakhir.
static inline CallSite decode_call_before(const uint8_t* before, bool thumb) {
    CallSite cs;
    cs.kind = CALL_UNKNOWN;
    cs.reg = -1;
    cs.thumb = thumb;
    if (thumb) {
        uint16_t hw1 = (uint16_t)(before[0] | (before[1] << 8));
        uint16_t hw2 = (uint16_t)(before[2] | (before[3] << 8));
        if ((hw2 & 0xff87) == 0x4780) {                       // blx rm
            cs.kind = CALL_BLX_REG;
            cs.reg = (hw2 >> 3) & 0xf;
        } else if ((hw1 & 0xf800) == 0xf000 && (hw2 & 0xc000) == 0xc000) {   // bl / blx imm (Thumb-2)
            cs.kind = CALL_BL_IMM;
        }
    } else {
        uint32_t w = (uint32_t)before[0] | ((uint32_t)before[1] << 8) |
                     ((uint32_t)before[2] << 16) | ((uint32_t)before[3] << 24);
        if ((w & 0x0ffffff0u) == 0x012fff30u) {               // blx rm
            cs.kind = CALL_BLX_REG;
            cs.reg = (int)(w & 0xf);
        } else if ((w & 0x0f000000u) == 0x0b000000u) {        // bl imm
            cs.kind = CALL_BL_IMM;
        }
    }
    return cs;
}

// Nilai kecil yang masih muat di dalam ukuran library kemungkinan offset file yang belum
// ditambah alamat basis (relokasi tidak terjadi / basis terbaca 0).
static inline bool looks_like_unrelocated(uint32_t value, uint32_t module_size) {
    return value != 0 && value < module_size;
}
