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
import java.io.OutputStream

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
        val btnAnalyze = Button(this).apply {
            text = "1. Ekstrak & analisis library"
            setOnClickListener { analyzeLibs() }
        }
        val btnExportSmall = Button(this).apply {
            text = "2. Ekspor library kecil ke Download"
            setOnClickListener { exportSmallLibs() }
        }
        val btnExportMc = Button(this).apply {
            text = "3. Ekspor libminecraftpe.so ke Download"
            setOnClickListener { exportLibs(listOf("libminecraftpe.so")) }
        }
        val btnExportDex = Button(this).apply {
            text = "4. Ekspor dex MainActivity Minecraft ke Download"
            setOnClickListener { exportMcDex() }
        }
        val btnDiag = Button(this).apply {
            text = "5. Diagnosa kelas saoF dan Application Minecraft"
            setOnClickListener { diagnose() }
        }
        logView = TextView(this).apply { setTextIsSelectable(true) }
        root.addView(btnRun)
        root.addView(btnPlain)
        root.addView(btnLog)
        root.addView(btnAnalyze)
        root.addView(btnExportSmall)
        root.addView(btnExportMc)
        root.addView(btnExportDex)
        root.addView(btnDiag)
        root.addView(
            ScrollView(this).apply { addView(logView) },
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
            )
        )
        setContentView(root)
    }

    private fun log(msg: String) {
        logView.append(msg + "\n")
    }

    /** Aman dipanggil dari thread mana pun. */
    private fun logUi(msg: String) {
        runOnUiThread { logView.append(msg + "\n") }
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
                copyToClipboard("cubix log", out)
            }
        }.start()
    }

    private fun copyToClipboard(label: String, text: String) {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText(label, text))
    }

    /** Tulis ke folder Download lewat [writer]. Return teks lokasi, atau pesan gagal. */
    private fun saveToDownloads(name: String, mime: String, writer: (OutputStream) -> Unit): String {
        if (Build.VERSION.SDK_INT >= 29) {
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, name)
                put(MediaStore.Downloads.MIME_TYPE, mime)
                put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
            }
            val uri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: return "GAGAL bikin $name di Download"
            val out = contentResolver.openOutputStream(uri)
                ?: return "GAGAL buka tulisan $name di Download"
            out.use { writer(it) }
            return "Download/$name"
        }
        val dst = File(getExternalFilesDir(null), name)
        dst.outputStream().use { writer(it) }
        return dst.absolutePath
    }

    /** Ekstrak library Minecraft (kalau belum). Return daftar file .so, atau null kalau gagal. */
    private fun ensureExtracted(): List<String>? {
        val info = mcInfo()
        if (info == null) {
            logUi("Minecraft belum terinstall.")
            return null
        }
        return try {
            McLoader.extractLibs(this, info)
        } catch (t: Throwable) {
            logUi("Gagal ekstrak: ${t.javaClass.simpleName}: ${t.message}")
            null
        }
    }

    /** Tombol 1: ekstrak library tanpa menjalankan game, lalu buat laporan (tampil, clipboard, Download). */
    private fun analyzeLibs() {
        logView.text = "Mengekstrak dan menganalisis library, tunggu sebentar...\n"
        Thread {
            val report = StringBuilder()
            val cubixVer = try { packageManager.getPackageInfo(packageName, 0).versionName } catch (_: Throwable) { "?" }
            report.append("Cubix $cubixVer laporan library\n")
            report.append("ABI HP: ").append(Build.SUPPORTED_ABIS.joinToString()).append('\n')
            try {
                @Suppress("DEPRECATION")
                val pi = packageManager.getPackageInfo(McLoader.MC_PACKAGE, 0)
                @Suppress("DEPRECATION")
                report.append("Minecraft ${pi.versionName} (code ${pi.versionCode})\n")
            } catch (_: Throwable) {
            }
            val libs = ensureExtracted()
            if (libs == null) {
                return@Thread
            }
            report.append("Jumlah library: ${libs.size}\n\n")
            val dir = McLoader.libDir(this)
            for (name in libs.sorted()) {
                val f = File(dir, name)
                report.append(McLoader.analyze(f)).append('\n')
            }
            val text = report.toString()
            val where = try {
                saveToDownloads("cubix_report.txt", "text/plain") { it.write(text.toByteArray()) }
            } catch (t: Throwable) {
                "GAGAL simpan laporan: ${t.message}"
            }
            runOnUiThread {
                logView.text = "(laporan disalin ke clipboard, juga disimpan: $where)\n\n$text"
                copyToClipboard("cubix report", text)
            }
        }.start()
    }

    /** Tombol 2: salin library kecil (bukan libminecraftpe/libfmod/libc++_shared) ke Download. */
    private fun exportSmallLibs() {
        logView.text = ""
        Thread {
            val libs = ensureExtracted() ?: return@Thread
            val skip = setOf("libminecraftpe.so", "libfmod.so", "libc++_shared.so")
            val names = libs.filter { it !in skip }.sorted()
            exportLibsNow(names)
        }.start()
    }

    /** Dipakai tombol 3 (dan 2): ekspor library bernama [names] ke Download. */
    private fun exportLibs(names: List<String>) {
        logView.text = ""
        Thread {
            if (ensureExtracted() == null) return@Thread
            exportLibsNow(names)
        }.start()
    }

    private fun exportLibsNow(names: List<String>) {
        val dir = McLoader.libDir(this)
        if (names.isEmpty()) {
            logUi("Tidak ada library untuk diekspor.")
            return
        }
        for (name in names) {
            val src = File(dir, name)
            if (!src.exists()) {
                logUi("$name: tidak ada di hasil ekstraksi, dilewati")
                continue
            }
            try {
                val where = saveToDownloads(name, "application/octet-stream") { out ->
                    src.inputStream().use { it.copyTo(out) }
                }
                logUi("$name (${src.length()} byte) -> $where")
            } catch (t: Throwable) {
                logUi("$name: GAGAL ${t.javaClass.simpleName}: ${t.message}")
            }
        }
        logUi("Selesai.")
    }

    /** Tombol 4: salin file .dex Minecraft yang memuat MainActivity ke Download (untuk dianalisis). */
    private fun exportMcDex() {
        logView.text = ""
        Thread {
            try {
                val info = packageManager.getApplicationInfo(McLoader.MC_PACKAGE, 0)
                val needle = "Lcom/mojang/minecraftpe/MainActivity;"
                var count = 0
                for (apk in McLoader.apkPaths(info)) {
                    java.util.zip.ZipFile(apk).use { zip ->
                        val entries = zip.entries()
                        while (entries.hasMoreElements()) {
                            val e = entries.nextElement()
                            if (!e.name.endsWith(".dex")) continue
                            val bytes = zip.getInputStream(e).use { it.readBytes() }
                            if (!String(bytes, Charsets.ISO_8859_1).contains(needle)) continue
                            val name = "mc." + e.name.substringAfterLast('/')
                            val where = saveToDownloads(name, "application/octet-stream") { out -> out.write(bytes) }
                            logUi("$name (${bytes.size} byte) -> $where")
                            count++
                        }
                    }
                }
                if (count == 0) logUi("Tidak ada dex yang memuat MainActivity.") else logUi("Selesai.")
            } catch (t: Throwable) {
                logUi("GAGAL ${t.javaClass.simpleName}: ${t.message}")
            }
        }.start()
    }

    /** Tombol 5: baca isi kelas saoF dan info Application Minecraft lewat class loader Minecraft. */
    private fun diagnose() {
        logUi("Diagnosa PairIP dihapus. Pakai tombol 1 (analisis) dan Salin log crash.")
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
            if (libs.contains("libpairipcore.so")) {
                log("PERHATIAN: Minecraft ini memakai PairIP (libpairipcore.so). Library bawaan seperti libmaesdk.so kemungkinan terenkripsi dan akan crash. Pakai tombol 1-3 untuk analisis.")
            }

            McLoader.prepare(this, info)
            log("loadLibs: " + McLoader.loadLibs(this))
            log("Class loader siap, menjalankan Minecraft...")

            val intent = Intent(this, GameActivity::class.java)
            startActivity(intent)
        } catch (t: Throwable) {
            log("Gagal: ${t.javaClass.simpleName}: ${t.message}")
            t.cause?.let { log("Penyebab: ${it.javaClass.simpleName}: ${it.message}") }
        }
    }
}
