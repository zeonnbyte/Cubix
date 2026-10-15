# Cubix (kerangka, armeabi-v7a)

Kerangka launcher Minecraft Bedrock untuk Android 32-bit.

## Cara pakai
1. Buka folder ini di Android Studio (otomatis bikin Gradle wrapper).
2. Install NDK + CMake 3.22.1 lewat SDK Manager.
3. Build dan install ke HP.
4. Buka app, tekan "Cek & Load Minecraft". Log-nya muncul di layar.

## Isi
- `LauncherActivity.kt` : cek Minecraft terinstall, salin dan load `libc++_shared.so`, `libfmod.so`, `libminecraftpe.so`.
- `cpp/launcher.cpp` : titik masuk native untuk hook/mod nanti.
- ABI dikunci ke `armeabi-v7a` di `app/build.gradle.kts`.

## Cara kerja jalankan Minecraft
- `McLoader.kt` ekstrak `.so` armeabi-v7a dari APK Minecraft (termasuk split APK), ganti class loader app dengan DexClassLoader dex Minecraft, dan daftarkan APK Minecraft sebagai asset.
- Manifest mendeklarasikan `com.mojang.minecraftpe.MainActivity` (NativeActivity, lib `minecraftpe`), kelasnya diambil dari dex Minecraft saat runtime.

## Belum ada
- Hook/mod. Offset di `libminecraftpe.so` beda tiap versi.

## Catatan
Kerangka ini belum dikompilasi atau dites di sini. Kalau ada error build atau gagal load, kirim lognya.
