package com.cubix.launcher

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.io.File
import java.io.RandomAccessFile
import java.util.zip.CRC32

class LauncherActivity : Activity() {

    private lateinit var logView: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try {
            System.loadLibrary("cubix")
        } catch (t: Throwable) {
            // crash handler native tidak aktif, game tetap bisa dijalankan
        }

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
        val btnExport = Button(this).apply {
            text = "Ekspor libmaesdk.so ke Download"
            setOnClickListener { exportMaesdk() }
        }
        logView = TextView(this).apply { setTextIsSelectable(true) }
        root.addView(btnRun)
        root.addView(btnPlain)
        root.addView(btnLog)
        root.addView(btnExport)
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
                    clean.subList(maxOf(0, idx - 170), minOf(clean.size, idx + 70)).joinToString("\n")
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

    /** Salin libmaesdk.so hasil ekstrak ke folder Download supaya bisa dikirim buat dibongkar. */
    private fun exportMaesdk() {
        logView.text = ""
        val src = File(filesDir, "mclibs/libmaesdk.so")
        if (!src.exists()) {
            log("libmaesdk.so belum diekstrak. Tekan \"Jalankan Minecraft\" sekali dulu, lalu coba lagi.")
            return
        }
        try {
            val crc = CRC32()
            src.inputStream().use { ins ->
                val buf = ByteArray(65536)
                while (true) {
                    val n = ins.read(buf)
                    if (n < 0) break
                    crc.update(buf, 0, n)
                }
            }
            log("libmaesdk.so: ${src.length()} byte, crc32=${java.lang.Long.toHexString(crc.value)}")

            // Bandingkan dengan byte yang tercatat di memori saat crash (code@e0e3b6ee, file off 0x1e86ee)
            RandomAccessFile(src, "r").use { raf ->
                val off = 0x1e86eeL
                if (raf.length() >= off + 64) {
                    raf.seek(off)
                    val b = ByteArray(64)
                    raf.readFully(b)
                    log("byte di file @0x1e86ee: " + b.joinToString("") { "%02x".format(it) })
                }
            }

            val name = "libmaesdk.so"
            if (Build.VERSION.SDK_INT >= 29) {
                val values = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, name)
                    put(MediaStore.Downloads.MIME_TYPE, "application/octet-stream")
                    put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                }
                val uri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                if (uri == null) {
                    log("Gagal bikin file di folder Download.")
                    return
                }
                contentResolver.openOutputStream(uri)?.use { out ->
                    src.inputStream().use { it.copyTo(out) }
                }
                log("Tersalin ke Download/$name")
            } else {
                val dst = File(getExternalFilesDir(null), name)
                src.copyTo(dst, overwrite = true)
                log("Tersalin ke ${dst.absolutePath}")
            }
        } catch (t: Throwable) {
            log("Gagal ekspor: ${t.javaClass.simpleName}: ${t.message}")
        }
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
            @Suppress("DEPRECATION")
            val pi = packageManager.getPackageInfo(McLoader.MC_PACKAGE, 0)
            @Suppress("DEPRECATION")
            log("Minecraft ${pi.versionName} (code ${pi.versionCode}), Application: ${info.className}")
        } catch (_: Throwable) {
        }

        try {
            val libs = McLoader.extractLibs(this, info)
            if (!libs.contains("libminecraftpe.so")) {
                log("libminecraftpe.so (armeabi-v7a) tidak ada di APK Minecraft.")
                log("Install Minecraft versi armeabi-v7a (32-bit).")
                return
            }
            log("Library diekstrak: ${libs.joinToString()}")
            if (libs.contains("libmaesdk.so")) {
                log("libmaesdk.so aktif (patch bypass init_array & safe string diterapkan).")
            }

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
