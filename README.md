# Cubix (kerangka, armeabi-v7a)

Kerangka launcher Minecraft Bedrock untuk Android 32-bit.

## Cara pakai
1. Buka folder ini di Android Studio (otomatis bikin Gradle wrapper).
2. Install NDK + CMake 3.22.1 lewat SDK Manager.
3. Build dan install ke HP.
4. Buka app, tekan "Jalankan Minecraft (Cubix)". Log-nya muncul di layar.

## Isi
- `LauncherActivity.kt` : cek Minecraft terinstall, salin library, siapkan class loader, dan jalankan MainActivity.
- `McLoader.kt` :
  - Ekstrak library native armeabi-v7a dari APK Minecraft ke folder internal `files/mclibs`.
  - Filter split APK: hanya memasukkan file APK yang benar-benar memuat `.dex` ke `DexClassLoader` agar terbebas dari peringatan `classes.dex: Entry not found`.
  - Auto-patch `libmaesdk.so`: bypass crash `init_array[0]` telemetry dan menyediakan constructor `basic_string` yang aman dari null pointer dereference.
- `cpp/launcher.cpp` :
  - Crash handler native dengan auto-recovery jika terjadi fault di `libmaesdk.so`.
  - Global override untuk `_ZNSt6__ndk112basic_string...` agar teresolusi secara aman sebelum library lain memanggilnya.
- ABI dikunci ke `armeabi-v7a` di `app/build.gradle.kts`.

## Cara kerja jalankan Minecraft
- `McLoader.kt` mengekstrak `.so` armeabi-v7a dari APK Minecraft (termasuk split APK), mempatch proteksi/crash pada `libmaesdk.so`, mengganti class loader app dengan `DexClassLoader` dex Minecraft, dan mendaftarkan APK Minecraft sebagai asset.
- Manifest mendeklarasikan `com.mojang.minecraftpe.MainActivity` (NativeActivity, lib `minecraftpe`), kelasnya diambil dari dex Minecraft saat runtime.

## Catatan Perbaikan Terbaru
- **Pembersihan jniLibs**: Folder `jniLibs/arm64-v8a` yang salah arsitektur telah dihapus.
- **Pencegahan Crash libmaesdk.so**: Telah ditangani di level disk patch (di `McLoader`) dan di level runtime hook/override (di `launcher.cpp`).
- **DexClassLoader Clean**: Split APK non-dex disaring sehingga Dalvik tidak lagi memicu error pencarian `classes.dex`.
