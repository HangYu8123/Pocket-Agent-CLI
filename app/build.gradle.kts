plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.pocketagent"
    compileSdk = 35
    ndkVersion = "27.2.12479018"

    defaultConfig {
        applicationId = "com.pocketagent"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
        // ABIs are selected by the splits block below.
        externalNativeBuild {
            cmake {
                arguments += listOf("-DANDROID_STL=c++_static", "-DANDROID_SUPPORT_FLEXIBLE_PAGE_SIZES=ON")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // Debug-signed so the APK is directly installable while developing.
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    // One APK per ABI keeps each install small (phones only need arm64-v8a).
    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a", "x86_64")
            isUniversalApk = false
        }
    }

    packaging {
        // Extract native libs to the app's nativeLibraryDir so proot can be exec()'d
        // from an executable location (required on Android 10+ with targetSdk >= 29).
        jniLibs {
            useLegacyPackaging = true
            // These are executables/prebuilt libs from Termux; never strip or touch them.
            keepDebugSymbols += listOf("**/libproot*.so", "**/libtalloc.so", "**/libandroid-shmem.so", "**/libtermux.so")
            // Our 16 KB-aligned rebuild of libtermux.so (tools/build_termux_jni.sh) wins over the AAR copy.
            pickFirsts += "**/libtermux.so"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { viewBinding = true; buildConfig = true }
}

dependencies {
    implementation("com.github.termux.termux-app:terminal-view:v0.118.3")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.activity:activity-ktx:1.9.2")
    implementation("androidx.preference:preference-ktx:1.2.1")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.6")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("org.apache.commons:commons-compress:1.27.1")
}
