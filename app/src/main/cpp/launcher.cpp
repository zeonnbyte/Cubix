#include <jni.h>
#include <android/log.h>

#define TAG "Cubix"

// Titik masuk native. Hook/mod nantinya dipasang di sini setelah
// libminecraftpe.so ter-load. Offset fungsi beda tiap versi, jadi
// siapkan signature scan per versi Minecraft.
JNIEXPORT jint JNI_OnLoad(JavaVM*, void*) {
    __android_log_print(ANDROID_LOG_INFO, TAG, "libcubix loaded (armeabi-v7a)");
    return JNI_VERSION_1_6;
}
