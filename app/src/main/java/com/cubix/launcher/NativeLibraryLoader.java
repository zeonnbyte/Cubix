package com.cubix.launcher;

public final class NativeLibraryLoader {
    private NativeLibraryLoader() {
    }

    public static void load(String name) {
        System.loadLibrary(name);
    }
}
