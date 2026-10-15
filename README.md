# Cubix (kerangka, armeabi-v7a)

Kerangka launcher Minecraft Bedrock untuk Android 32-bit.

## Status fix_12
Minecraft dari Google Play dilindungi **PairIP**. `libmaesdk.so` bawaannya `NEEDED libpairipcore.so`
dan sebagian kodenya terenkripsi (diverifikasi dari entropi). Library itu tidak bisa dijalankan dari
paket lain, dan patch byte di offset tetap malah menimpa data terenkripsi. Karena itu:

- Patch byte `libmaesdk.so` dan crash handler "auto-recovery" **dihapus**.
- Library diekstrak apa adanya dari APK Minecraft.
- Rencana: ganti `libmaesdk`, `libgxcore`, `libPlayFabMultiplayer` dengan stub buatan sendiri
  (seperti Levi Launcher), dimuat duluan lewat `System.loadLibrary`.

## Cara pakai (tahap analisis)
1. Build dan install, buka app.
2. Tekan **1. Ekstrak & analisis library**. Laporan disalin ke clipboard dan disimpan ke `Download/cubix_report.txt`.
3. Tekan **2. Ekspor library kecil ke Download**.
4. Tekan **3. Ekspor libminecraftpe.so ke Download**.
5. Kirim `cubix_report.txt` dan file-file `.so` hasil ekspor.

Tombol "Jalankan Minecraft (Cubix)" masih ada untuk uji coba, tapi diperkirakan masih crash
sampai stub library selesai.

## Isi
- `LauncherActivity.kt` : UI sederhana, ekstrak/analisis/ekspor library, jalankan MainActivity.
- `McLoader.kt` : ekstrak `.so` armeabi-v7a dari APK Minecraft (termasuk split APK), analisis library
  (ukuran, ELF, sebutan `libpairipcore`, blok berentropi tinggi), dan ganti class loader dengan `DexClassLoader`.
- `cpp/launcher.cpp` : crash handler yang hanya mencatat (pc, lr, register, stack) lalu menyerahkan ke handler Android.
- ABI dikunci ke `armeabi-v7a` di `app/build.gradle.kts`.
