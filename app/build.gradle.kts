plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.cubix.launcher"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.cubix.launcher"
        minSdk = 26
        targetSdk = 33
        versionCode = 21
        versionName = "0.1-fix21"

        // Khusus 32-bit ARM
        ndk {
            abiFilters += listOf("armeabi-v7a")
        }

        externalNativeBuild {
            cmake {
                cppFlags += "-std=c++17"
                arguments += listOf("-DANDROID_STL=none")
            }
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    // Keystore dibaca dari environment (GitHub Secrets). Kalau kosong, pakai debug key bawaan.
    val ksPath = System.getenv("KEYSTORE_PATH")

    signingConfigs {
        if (ksPath != null) {
            create("fixed") {
                storeFile = file(ksPath)
                storePassword = System.getenv("KEYSTORE_PASSWORD")
                keyAlias = System.getenv("KEY_ALIAS")
                keyPassword = System.getenv("KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        debug {
            if (ksPath != null) signingConfig = signingConfigs.getByName("fixed")
        }
        release {
            isMinifyEnabled = false
            if (ksPath != null) signingConfig = signingConfigs.getByName("fixed")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
}

dependencies {
    implementation(project(":minecraft"))
    implementation(libs.appcompat)
    implementation(libs.material)
}
