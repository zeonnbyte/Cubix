package com.cubix.launcher

import android.app.Activity
import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ApplicationInfo
import android.content.res.AssetManager
import android.os.Bundle
import dalvik.system.DexClassLoader
import java.io.File
import java.io.RandomAccessFile
import java.lang.reflect.Field
import java.util.zip.ZipFile
import kotlin.math.ln

/**
 * Menyiapkan proses Cubix supaya bisa menjalankan Minecraft di dalam prosesnya sendiri:
 * 1. Ekstrak semua .so armeabi-v7a dari APK Minecraft (termasuk split APK) TANPA diubah.
 * 2. Ganti class loader aplikasi dengan DexClassLoader yang membaca dex Minecraft (tanpa split APK non-dex).
 * 3. Daftarkan APK Minecraft sebagai sumber asset/resource.
 * 4. Analisis library (analyze) untuk mengetahui mana yang dilindungi PairIP.
 *
 * Catatan fix_12: libmaesdk dan libPlayFabMultiplayer diganti stub buatan Cubix (lihat STUBBED).
 * Catatan fix_11: patch byte libmaesdk.so DIHAPUS. Library itu dilindungi PairIP (kodenya
 * terenkripsi), jadi patch di offset tetap malah menimpa data terenkripsi.
 */
object McLoader {
    const val MC_PACKAGE = "com.mojang.minecraftpe"
    const val MC_MAIN = "com.mojang.minecraftpe.MainActivity"
    private const val ABI_DIR = "lib/armeabi-v7a/"

    // Ganti nilai ini kalau isi mclibs harus diekstrak ulang (mis. setelah logika ekstraksi berubah).
    private const val PATCH_VERSION = "cubix_v7_levi_order"

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

    fun libDir(ctx: Context): File = File(ctx.filesDir, "mclibs")

    /** Library Minecraft yang dilindungi PairIP; diganti stub buatan Cubix (ada di nativeLibraryDir). */
    private val STUBBED = setOf("libmaesdk.so", "libPlayFabMultiplayer.so")

    /** Ekstrak library native dari APK apa adanya, return daftar nama file yang berhasil diekstrak. */
    fun extractLibs(ctx: Context, info: ApplicationInfo): List<String> {
        val dest = libDir(ctx)
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
                    val base = e.name.substringAfterLast('/')
                    if (base in STUBBED) continue
                    val out = File(dest, base)
                    zip.getInputStream(e).use { input ->
                        out.outputStream().use { output -> input.copyTo(output) }
                    }
                    out.setReadable(true, false)
                    out.setExecutable(true, false)
                    names.add(out.name)
                }
            }
        }
        // Pasang stub pengganti library yang dilindungi PairIP
        for (stub in STUBBED) {
            val src = File(ctx.applicationInfo.nativeLibraryDir, stub)
            if (!src.exists()) continue
            val out = File(dest, stub)
            src.inputStream().use { i -> out.outputStream().use { o -> i.copyTo(o) } }
            out.setReadable(true, false)
            out.setExecutable(true, false)
            names.add(stub)
        }
        stamp.writeText(key)
        return names
    }

    // ------------------------------------------------------------------
    // Analisis library: ukuran, jenis ELF, apakah menyebut libpairipcore, dan blok "terenkripsi".
    // ------------------------------------------------------------------

    private const val PAGE = 4096
    private const val HIGH_ENTROPY = 7.5

    /** True kalau file memuat urutan byte [pat] di mana pun (dibaca streaming, hemat memori). */
    private fun containsBytes(file: File, pat: ByteArray): Boolean {
        val chunk = 65536
        val buf = ByteArray(chunk + pat.size)
        file.inputStream().use { ins ->
            var carry = 0
            while (true) {
                val n = ins.read(buf, carry, chunk)
                if (n < 0) return false
                val total = carry + n
                var i = 0
                val last = total - pat.size
                while (i <= last) {
                    if (buf[i] == pat[0]) {
                        var j = 1
                        while (j < pat.size && buf[i + j] == pat[j]) j++
                        if (j == pat.size) return true
                    }
                    i++
                }
                carry = minOf(pat.size - 1, total)
                System.arraycopy(buf, total - carry, buf, 0, carry)
            }
        }
    }

    /** Ringkasan satu library dalam bentuk teks (beberapa baris). */
    fun analyze(file: File): String {
        val size = file.length()
        val sb = StringBuilder()
        sb.append(file.name).append('\n')
        sb.append("  ukuran      : ").append(size).append(" byte\n")

        // Header ELF
        try {
            RandomAccessFile(file, "r").use { raf ->
                val h = ByteArray(20)
                raf.readFully(h)
                val isElf = h[0] == 0x7f.toByte() && h[1] == 'E'.code.toByte() &&
                    h[2] == 'L'.code.toByte() && h[3] == 'F'.code.toByte()
                if (isElf) {
                    val cls = if (h[4].toInt() == 1) "ELF32" else "ELF64"
                    val machine = (h[18].toInt() and 0xff) or ((h[19].toInt() and 0xff) shl 8)
                    val mach = when (machine) {
                        0x28 -> "ARM"
                        0xb7 -> "AArch64"
                        else -> "mesin=0x" + machine.toString(16)
                    }
                    sb.append("  format      : $cls $mach\n")
                } else {
                    sb.append("  format      : BUKAN ELF\n")
                }
            }
        } catch (t: Throwable) {
            sb.append("  format      : gagal baca (${t.message})\n")
        }

        // Menyebut libpairipcore? (tanda dilindungi PairIP, atau library pelindung itu sendiri)
        val pairip = try {
            containsBytes(file, "libpairipcore.so".toByteArray(Charsets.US_ASCII))
        } catch (t: Throwable) {
            null
        }
        sb.append("  libpairipcore: ").append(
            when (pairip) {
                true -> "YA (menyebut libpairipcore.so)"
                false -> "tidak"
                null -> "gagal cek"
            }
        ).append('\n')

        // Blok berentropi tinggi (kemungkinan kode terenkripsi) per halaman 4 KB
        var highPages = 0
        var totalPages = 0
        val runs = mutableListOf<String>()
        var runStart = -1L
        try {
            RandomAccessFile(file, "r").use { raf ->
                val buf = ByteArray(PAGE)
                val cnt = IntArray(256)
                var off = 0L
                while (off < size) {
                    val n = minOf(PAGE.toLong(), size - off).toInt()
                    raf.seek(off)
                    raf.readFully(buf, 0, n)
                    totalPages++
                    var high = false
                    if (n >= 1024) {
                        java.util.Arrays.fill(cnt, 0)
                        for (i in 0 until n) cnt[buf[i].toInt() and 0xff]++
                        var e = 0.0
                        for (c in cnt) {
                            if (c == 0) continue
                            val p = c.toDouble() / n
                            e -= p * (ln(p) / ln(2.0))
                        }
                        high = e > HIGH_ENTROPY
                    }
                    if (high) {
                        highPages++
                        if (runStart < 0) runStart = off
                    } else if (runStart >= 0) {
                        if (runs.size < 8) runs.add("0x" + runStart.toString(16) + "-0x" + off.toString(16))
                        runStart = -1L
                    }
                    off += n
                }
                if (runStart >= 0 && runs.size < 8) {
                    runs.add("0x" + runStart.toString(16) + "-0x" + size.toString(16))
                }
            }
            sb.append("  entropi tinggi: $highPages dari $totalPages halaman")
            if (runs.isNotEmpty()) sb.append("  blok: ").append(runs.joinToString(", "))
            sb.append('\n')
        } catch (t: Throwable) {
            sb.append("  entropi     : gagal hitung (${t.message})\n")
        }
        return sb.toString()
    }

    /** AssetManager yang membaca APK Minecraft (asset game), dipakai GameActivity dan CubixApp. */
    @Volatile
    var gameAssets: AssetManager? = null
        private set

    /** Siapkan asset Minecraft. MainActivity sekarang kelas buatan sendiri (modul :minecraft), tanpa PairIP. */
    fun prepare(ctx: Context, info: ApplicationInfo) {
        val am = AssetManager::class.java.getDeclaredConstructor().newInstance()
        val add = AssetManager::class.java.getMethod("addAssetPath", String::class.java)
        for (p in apkPaths(info)) add.invoke(am, p)
        gameAssets = am
    }

    private fun addNativePath(ctx: Context, dir: String) {
        val cl = ctx.classLoader as dalvik.system.BaseDexClassLoader
        val m = dalvik.system.BaseDexClassLoader::class.java
            .getDeclaredMethod("addNativePath", java.util.Collection::class.java)
        m.isAccessible = true
        m.invoke(cl, listOf(dir))
    }

    /**
     * Muat library hasil ekstrak dengan URUTAN TETAP seperti Levi (GamePackageManager /
     * MinecraftRuntimePreparer), bukan semua .so sekaligus. Library lain (mis. libpairipcore,
     * libgxcore) sengaja tidak dimuat di awal: memuat libpairipcore bisa memicu pemeriksaan
     * PairIP dan mematikan proses, dan gxcore di Levi baru dimuat SETELAH minecraftpe.
     * Melempar exception kalau libminecraftpe gagal dimuat. Return: laporan teks.
     */
    fun loadLibs(ctx: Context): String {
        val dir = libDir(ctx)
        val sb = StringBuilder()
        addNativePath(ctx, dir.absolutePath)

        fun loadOne(name: String): Boolean {
            val f = File(dir, "lib$name.so")
            if (!f.exists() || f.length() == 0L) {
                sb.append("lewati ").append(f.name).append(" (tidak ada)\n")
                return false
            }
            return try {
                f.setReadOnly()
                System.load(f.absolutePath)
                sb.append("dimuat ").append(f.name).append('\n')
                true
            } catch (t: UnsatisfiedLinkError) {
                sb.append("gagal muat ").append(f.name).append(": ").append(t.message).append('\n')
                false
            }
        }

        // Urutan sama dengan Levi: c++_shared, HttpClient, stub SDK, fmod, decoder, MallocRedirect.
        for (name in listOf(
            "c++_shared", "HttpClient.Android", "PlayFabMultiplayer", "maesdk",
            "fmod", "MediaDecoders_Android", "MallocRedirect"
        )) loadOne(name)

        File(dir, "libminecraftpe.so").setReadOnly()
        System.loadLibrary("minecraftpe")
        sb.append("libminecraftpe dimuat\n")

        // gxcore dimuat setelah minecraftpe (urutan Levi); kegagalan dicatat, tidak fatal.
        loadOne("gxcore")
        return sb.toString()
    }

    /** Isi extra Firebase dari resource string Minecraft (seperti yang dilakukan Levi). */
    fun firebaseExtras(ctx: Context): Map<String, String> {
        val res = try { ctx.packageManager.getResourcesForApplication(MC_PACKAGE) } catch (_: Throwable) { return emptyMap() }
        fun str(name: String, def: String): String {
            val id = res.getIdentifier(name, "string", MC_PACKAGE)
            return if (id != 0) res.getString(id) else def
        }
        return mapOf(
            "MINECRAFT_FIREBASE_APP_ID" to str("google_app_id", "1:486187589451:android:b2331110821fe2304bd2ce"),
            "MINECRAFT_FIREBASE_API_KEY" to str("google_api_key", str("google_crash_reporting_api_key", "")),
            "MINECRAFT_FIREBASE_PROJECT_ID" to str("project_id", "minecraft-bedrock-57580"),
            "MINECRAFT_FIREBASE_SENDER_ID" to str("gcm_defaultSenderId", "486187589451")
        )
    }
}
