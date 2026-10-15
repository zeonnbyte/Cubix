# Cubix (armeabi-v7a) - fix19

Mulai fix19, Cubix TIDAK lagi memakai MainActivity asli Minecraft (dilindungi PairIP).
Modul `:minecraft` (disalin dari LeviLaunchroid, Apache-2.0, lihat LICENSE-LeviLaunchroid)
berisi `com.mojang.minecraftpe.MainActivity` yang bersih. `GameActivity` mewarisinya.
Dari Minecraft terinstal hanya dipakai: `.so` armeabi-v7a (diekstrak ke filesDir/mclibs) dan asset APK.
Library PairIP (libmaesdk, libPlayFabMultiplayer) tetap diganti stub Cubix.

Catatan: versi MainActivity (JNI) harus cocok dengan versi libminecraftpe.so yang terinstal.
