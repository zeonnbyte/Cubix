package com.cubix.launcher

import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ApplicationInfo
import dalvik.system.DexClassLoader
import java.io.File
import java.lang.reflect.Field
import java.util.zip.ZipFile

/**
 * Menyiapkan proses Cubix supaya bisa menjalankan Minecraft di dalam prosesnya sendiri:
 * 1. Ekstrak semua .so armeabi-v7a dari APK Minecraft (termasuk split APK).
 * 2. Ganti class loader aplikasi dengan DexClassLoader yang membaca dex Minecraft.
 * 3. Daftarkan APK Minecraft sebagai sumber asset/resource.
 */
object McLoader {
    const val MC_PACKAGE = "com.mojang.minecraftpe"
    const val MC_MAIN = "com.mojang.minecraftpe.MainActivity"
    private const val ABI_DIR = "lib/armeabi-v7a/"

    @Volatile
    private var prepared = false

    fun apkPaths(info: ApplicationInfo): List<String> {
        val list = mutableListOf(info.sourceDir)
        info.splitSourceDirs?.let { list.addAll(it) }
        return list
    }

    /** Ekstrak library native dari APK, return daftar nama file yang berhasil diekstrak. */
    fun extractLibs(ctx: Context, info: ApplicationInfo): List<String> {
        val dest = File(ctx.filesDir, "mclibs")
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
                    out.setReadOnly()
                    names.add(out.name)
                }
            }
        }
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
        val paths = apkPaths(info)
        val libDir = File(ctx.filesDir, "mclibs")

        // Asset dan resource Minecraft ikut terbaca oleh activity baru
        val ai = app.applicationInfo
        val old = ai.sharedLibraryFiles?.toList() ?: emptyList()
        ai.sharedLibraryFiles = (old + paths).distinct().toTypedArray()

        // Ganti class loader aplikasi
        val base = (app as ContextWrapper).baseContext
        val packageInfo = findField(base, "mPackageInfo").get(base)
            ?: throw IllegalStateException("mPackageInfo null")
        val clField = findField(packageInfo, "mClassLoader")
        val parent = clField.get(packageInfo) as ClassLoader
        val optDir = File(ctx.codeCacheDir, "mcdex").apply { mkdirs() }
        val loader = DexClassLoader(
            paths.joinToString(File.pathSeparator),
            optDir.absolutePath,
            libDir.absolutePath,
            parent
        )
        clField.set(packageInfo, loader)
        prepared = true
    }
}
