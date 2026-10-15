package com.cubix.launcher

import android.app.Activity
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import java.io.File

/**
 * Kerangka dasar launcher: cek Minecraft terinstall, cek arsitektur (harus armeabi-v7a),
 * salin library native-nya, lalu load ke proses launcher.
 *
 * Ini baru tahap "loader check". Menjalankan game penuh (class loader MainActivity
 * milik Minecraft) adalah langkah berikutnya.
 */
class LauncherActivity : Activity() {

    companion object {
        const val MC_PACKAGE = "com.mojang.minecraftpe"

        // Urutan load penting: dependensi dulu, game terakhir
        val MC_LIBS = listOf("libc++_shared.so", "libfmod.so", "libminecraftpe.so")
    }

    private lateinit var logView: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 48, 48, 48)
        }
        logView = TextView(this)
        val btn = Button(this).apply {
            text = "Cek & Load Minecraft"
            setOnClickListener { checkAndLoad() }
        }
        root.addView(btn)
        root.addView(logView)
        setContentView(root)
    }

    private fun log(msg: String) {
        logView.append(msg + "\n")
    }

    private fun checkAndLoad() {
        logView.text = ""
        log("ABI HP: ${Build.SUPPORTED_ABIS.joinToString()}")

        val info = try {
            packageManager.getApplicationInfo(MC_PACKAGE, 0)
        } catch (e: PackageManager.NameNotFoundException) {
            log("Minecraft belum terinstall.")
            return
        }

        val libDir = File(info.nativeLibraryDir)
        log("Lib dir Minecraft: ${libDir.absolutePath}")

        val missing = MC_LIBS.filter { !File(libDir, it).exists() }
        if (missing.isNotEmpty()) {
            log("Library tidak ketemu: $missing")
            log("Kemungkinan Minecraft ini bukan build armeabi-v7a, atau libnya masih di dalam APK.")
            return
        }

        // Salin ke folder milik launcher supaya boleh di-load
        val dest = File(filesDir, "mclibs").apply { mkdirs() }
        for (name in MC_LIBS) {
            File(libDir, name).copyTo(File(dest, name), overwrite = true)
        }

        try {
            for (name in MC_LIBS) {
                System.load(File(dest, name).absolutePath)
                log("OK load: $name")
            }
        } catch (t: Throwable) {
            log("Gagal load: ${t.message}")
        }
    }
}
