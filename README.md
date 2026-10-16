# Cubix V0.1.29 (armeabi-v7a)

Cubix uses `GameActivity` with the compatibility classes in `:minecraft`.
Native libraries and assets are read from the installed Minecraft APK. Native
libraries are extracted unchanged; Cubix does not replace or patch PairIP
libraries.

For the core libraries, Cubix follows the order visible in Atlas Client 26.33's
public DEX decompilation: `MediaDecoders_Android`, `fmod`,
`HttpClient.Android` (when required by the Minecraft version), then
`libminecraftpe.so`. `libc++_shared.so` is loaded first to satisfy its native
dependencies. LeviLaunchroid's version gates remain in use for SDK and
`MallocRedirect` libraries.

A dedicated `DexClassLoader` adds `files/mclibs` to the native search path so
the Android linker can resolve dependencies such as `libpairipcore.so`. When
the installed game includes PairIP, Cubix loads that original library after
the core media/network dependencies and before `maesdk`/`libminecraftpe.so`.
PairIP and Minecraft's official integrity checks remain enabled. Compatibility
depends on the installed game version, package integrity, and device.

Atlas's public repository does not include its native `libloader` implementation
or packaged `.so` files. Its observed Java load sequence therefore does not
establish how that loader handles PairIP. Atlas also selects `arm64` or
`x86_64` libraries, while this Cubix build targets `armeabi-v7a`; only the
verified core load order is applied here.

This loads PairIP; it does not bypass or disable it.

Flarial Client (`flarialmc/dll-oss`) is a Windows 10/11 x64 injected DLL
client, not an Android launcher. Its public source has no PairIP or Android
Minecraft native-library loading implementation to port to Cubix.

The compatibility `MainActivity` JNI declarations must match the installed
`libminecraftpe.so` version.

V0.1.29: the native class loader now also includes the installed Minecraft's own
DEX files, so `libpairipcore.so` can find its `com.pairip.*` classes during
`JNI_OnLoad` (fixes the `RegisterNatives received NULL jclass` abort). Classes
and libraries are used unchanged.
