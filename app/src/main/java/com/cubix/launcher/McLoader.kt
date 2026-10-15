package com.cubix.launcher

import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ApplicationInfo
import dalvik.system.DexClassLoader
import java.io.File
import java.io.RandomAccessFile
import java.lang.reflect.Field
import java.util.zip.ZipFile

/**
 * Menyiapkan proses Cubix supaya bisa menjalankan Minecraft di dalam prosesnya sendiri:
 * 1. Ekstrak semua .so armeabi-v7a dari APK Minecraft (termasuk split APK).
 * 2. Patch perlindungan / crash pada libmaesdk.so (init_array & basic_string).
 * 3. Ganti class loader aplikasi dengan DexClassLoader yang membaca dex Minecraft (tanpa split APK non-dex).
 * 4. Daftarkan APK Minecraft sebagai sumber asset/resource.
 */
object McLoader {
    const val MC_PACKAGE = "com.mojang.minecraftpe"
    const val MC_MAIN = "com.mojang.minecraftpe.MainActivity"
    private const val ABI_DIR = "lib/armeabi-v7a/"
    private const val PATCH_VERSION = "cubix_v2"

    // Signature dan patch untuk libmaesdk.so init_array[0] (offset 0x20a480)
    // Asli: push {r7, lr}; mov r7, sp (80 b5 6f 46)
    // Patch: bx lr; nop (70 47 00 bf)
    private val INIT_SIG = byteArrayOf(0x80.toByte(), 0xb5.toByte(), 0x6f.toByte(), 0x46.toByte())
    private val INIT_PATCH = byteArrayOf(0x70.toByte(), 0x47.toByte(), 0x00.toByte(), 0xbf.toByte())

    // Signature dan patch untuk libmaesdk.so basic_string constructor (offset 0x1e8708)
    // Asli: 24 7d 82 c8 (ldrb r4, [r4, #0x14]; ldm r0!, {r1, r7})
    private val STR_SIG = byteArrayOf(0x24.toByte(), 0x7d.toByte(), 0x82.toByte(), 0xc8.toByte())
    // 70-byte Thumb safe basic_string: zero memory, handle null, safe SSO copy
    private val STR_PATCH = byteArrayOf(
        0x00, 0x22, 0x00, 0x29, 0xc0.toByte(), 0xe9.toByte(), 0x00, 0x22,
        0x82.toByte(), 0x60, 0x08, 0xbf.toByte(), 0x70, 0x47, 0x10.toByte(), 0xb5.toByte(),
        0x0a, 0x78, 0x4f, 0xf0.toByte(), 0x00, 0x0c, 0x6a, 0xb1.toByte(),
        0x00, 0xf1.toByte(), 0x01, 0x0e, 0x01, 0x31, 0x00, 0x24,
        0x63, 0x1c, 0x08, 0x2c, 0x0e, 0xf8.toByte(), 0x04, 0x20,
        0x05, 0xd8.toByte(), 0x0a, 0x5d, 0x1c, 0x46, 0x00, 0x2a,
        0xf6.toByte(), 0xd1.toByte(), 0x00, 0xe0.toByte(), 0x00, 0x23, 0x59, 0x00,
        0x01, 0x70, 0x18, 0x44, 0x80.toByte(), 0xf8.toByte(), 0x01, 0xc0.toByte(),
        0xbd.toByte(), 0xe8.toByte(), 0x10, 0x40, 0x70, 0x47
    )

    @Volatile
    private var prepared = false

    fun apkPaths(info: ApplicationInfo): List<String> {
        val list = mutableListOf(info.sourceDir)
        info.splitSourceDirs?.let { list.addAll(it) }
        return list
    }

    /**
     * Hanya kembalikan file APK yang benar-benar memuat file .dex di dalamnya.
     * Mencegah peringatan: "Failed to find entry 'classes.dex': Entry not found"
     * saat DexClassLoader mencoba memeriksa split APK non-kode (resource/ABI splits).
     */
    fun dexApkPaths(info: ApplicationInfo): List<String> {
        return apkPaths(info).filter { path ->
            try {
                ZipFile(path).use { zip ->
                    val entries = zip.entries()
                    var hasDex = false
                    while (entries.hasMoreElements()) {
                        val name = entries.nextElement().name
                        if (name.endsWith(".dex")) {
                            hasDex = true
                            break
                        }
                    }
                    hasDex
                }
            } catch (_: Throwable) {
                false
            }
        }.ifEmpty { listOf(info.sourceDir) }
    }

    /**
     * Patch perlindungan / telemetry crash pada libmaesdk.so:
     * 1. Bypass init_array[0] (0x20a480) agar tidak mengeksekusi setup string telemetry yang crash.
     * 2. Replace basic_string constructor (0x1e8708) dengan implementasi aman tanpa crash null deref.
     */
    fun patchMaesdk(file: File): Boolean {
        if (!file.exists() || !file.canWrite()) return false
        try {
            RandomAccessFile(file, "rw").use { raf ->
                val len = raf.length()

                // Patch 1: init_array[0] di offset 0x20a480
                val offInit = 0x20a480L
                if (len >= offInit + INIT_SIG.size) {
                    raf.seek(offInit)
                    val buf = ByteArray(INIT_SIG.size)
                    raf.readFully(buf)
                    if (buf.contentEquals(INIT_SIG)) {
                        raf.seek(offInit)
                        raf.write(INIT_PATCH)
                    }
                }

                // Patch 2: basic_string di offset 0x1e8708
                val offStr = 0x1e8708L
                if (len >= offStr + STR_SIG.size) {
                    raf.seek(offStr)
                    val buf = ByteArray(STR_SIG.size)
                    raf.readFully(buf)
                    if (buf.contentEquals(STR_SIG)) {
                        raf.seek(offStr)
                        raf.write(STR_PATCH)
                        // Pad sisa 84 bytes dengan NOP (0x00, 0xbf)
                        val remaining = 84 - STR_PATCH.size
                        if (remaining > 0) {
                            val nops = ByteArray(remaining) { if (it % 2 == 0) 0x00 else 0xbf.toByte() }
                            raf.write(nops)
                        }
                    }
                }
            }
            return true
        } catch (_: Throwable) {
            return false
        }
    }

    /** Ekstrak library native dari APK, return daftar nama file yang berhasil diekstrak. */
    fun extractLibs(ctx: Context, info: ApplicationInfo): List<String> {
        val dest = File(ctx.filesDir, "mclibs")
        val stamp = File(dest, "stamp.txt")
        val key = "${ctx.packageManager.getPackageInfo(MC_PACKAGE, 0).lastUpdateTime}:${info.sourceDir}:$PATCH_VERSION"
        if (stamp.exists() && stamp.readText() == key && File(dest, "libminecraftpe.so").exists()) {
            return dest.list()?.filter { it.endsWith(".so") } ?: emptyList()
        }
        dest.deleteRecursively()
        dest.mkdirs()
        val names = mutableListOf<String>()
        for (apk in apkPaths(info)) {
            ZipFile(apk).use { zip ->
                val entries = zip.entries()
                while (entries.hasMoreElements()) {
                    val e = entries.nextElement()
                    if (e.isDirectory || !e.name.startsWith(ABI_DIR) || !e.name.endsWith(".so")) continue
                    val out = File(dest, e.name.substringAfterLast('/'))
                    zip.getInputStream(e).use { input ->
                        out.outputStream().use { output -> input.copyTo(output) }
                    }
                    out.setReadable(true, false)
                    out.setExecutable(true, false)
                    names.add(out.name)
                }
            }
        }

        // Terapkan patch pada libmaesdk.so hasil ekstrak
        val maesdk = File(dest, "libmaesdk.so")
        if (maesdk.exists()) {
            patchMaesdk(maesdk)
        }

        stamp.writeText(key)
        return names
    }

    private fun findField(obj: Any, name: String): Field {
        var c: Class<*>? = obj.javaClass
        while (c != null) {
            try {
                return c.getDeclaredField(name).apply { isAccessible = true }
            } catch (_: NoSuchFieldException) {
            }
            c = c.superclass
        }
        throw NoSuchFieldException(name)
    }

    /** Harus dipanggil sebelum MainActivity Minecraft dijalankan. */
    fun prepare(ctx: Context, info: ApplicationInfo) {
        if (prepared) return
        val app = ctx.applicationContext
        val allApkPaths = apkPaths(info)
        val dexPaths = dexApkPaths(info)
        val libDir = File(ctx.filesDir, "mclibs")

        // Asset dan resource Minecraft ikut terbaca oleh activity baru
        val ai = app.applicationInfo
        val old = ai.sharedLibraryFiles?.toList() ?: emptyList()
        ai.sharedLibraryFiles = (old + allApkPaths).distinct().toTypedArray()

        // Ganti class loader aplikasi hanya dengan APK yang berisi .dex
        val base = (app as ContextWrapper).baseContext
        val packageInfo = findField(base, "mPackageInfo").get(base)
            ?: throw IllegalStateException("mPackageInfo null")
        val clField = findField(packageInfo, "mClassLoader")
        val parent = clField.get(packageInfo) as ClassLoader
        val optDir = File(ctx.codeCacheDir, "mcdex").apply { mkdirs() }
        val loader = DexClassLoader(
            dexPaths.joinToString(File.pathSeparator),
            optDir.absolutePath,
            libDir.absolutePath,
            parent
        )
        clField.set(packageInfo, loader)
        prepared = true
    }
}
