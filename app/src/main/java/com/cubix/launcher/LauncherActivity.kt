package com.cubix.launcher

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

class LauncherActivity : Activity() {

    private lateinit var logView: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 48, 48, 48)
        }
        val btnRun = Button(this).apply {
            text = "Jalankan Minecraft (Cubix)"
            setOnClickListener { runMinecraft() }
        }
        val btnPlain = Button(this).apply {
            text = "Buka Minecraft biasa"
            setOnClickListener { openPlain() }
        }
        val btnLog = Button(this).apply {
            text = "Salin log crash"
            setOnClickListener { showCrashLog() }
        }
        logView = TextView(this).apply { setTextIsSelectable(true) }
        root.addView(btnRun)
        root.addView(btnPlain)
        root.addView(btnLog)
        root.addView(ScrollView(this).apply { addView(logView) })
        setContentView(root)
    }

    private fun log(msg: String) {
        logView.append(msg + "\n")
    }

    private fun mcInfo(): ApplicationInfo? = try {
        packageManager.getApplicationInfo(McLoader.MC_PACKAGE, 0)
    } catch (e: PackageManager.NameNotFoundException) {
        null
    }

    private fun showCrashLog() {
        logView.text = "Membaca log..."
        Thread {
            val text = try {
                val p = ProcessBuilder("logcat", "-d", "-t", "2000", "-v", "time")
                    .redirectErrorStream(true).start()
                val lines = p.inputStream.bufferedReader().readLines()
                val noise = Regex("Insets|GraphicsEnvironment|OpenGLRenderer|Monitor::Inflate|Compiler allocated|hiddenapi")
                val clean = lines.filterNot { noise.containsMatchIn(it) }
                val idx = clean.indexOfLast { it.contains("Fatal signal") || it.contains("FATAL EXCEPTION") }
                if (idx < 0) {
                    clean.takeLast(80).joinToString("\n")
                } else {
                    clean.subList(maxOf(0, idx - 25), minOf(clean.size, idx + 90)).joinToString("\n")
                }
            } catch (t: Throwable) {
                "Gagal baca log: ${t.message}"
            }
            runOnUiThread {
                val out = if (text.isBlank()) "Log kosong" else text
                logView.text = "(sudah disalin ke clipboard)\n" + out
                val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("cubix log", out))
            }
        }.start()
    }

    private fun openPlain() {
        val i = packageManager.getLaunchIntentForPackage(McLoader.MC_PACKAGE)
        if (i == null) {
            logView.text = ""
            log("Minecraft belum terinstall.")
        } else {
            startActivity(i)
        }
    }

    private fun runMinecraft() {
        logView.text = ""
        log("ABI HP: ${Build.SUPPORTED_ABIS.joinToString()}")

        val info = mcInfo()
        if (info == null) {
            log("Minecraft belum terinstall.")
            return
        }
        log("APK Minecraft: ${McLoader.apkPaths(info).size} file")

        try {
            val libs = McLoader.extractLibs(this, info)
            if (!libs.contains("libminecraftpe.so")) {
                log("libminecraftpe.so (armeabi-v7a) tidak ada di APK Minecraft.")
                log("Install Minecraft versi armeabi-v7a (32-bit).")
                return
            }
            log("Library diekstrak: ${libs.joinToString()}")

            McLoader.prepare(this, info)
            log("Class loader siap, menjalankan Minecraft...")

            val intent = Intent().setClassName(packageName, McLoader.MC_MAIN)
            startActivity(intent)
        } catch (t: Throwable) {
            log("Gagal: ${t.javaClass.simpleName}: ${t.message}")
            t.cause?.let { log("Penyebab: ${it.javaClass.simpleName}: ${it.message}") }
        }
    }
}
