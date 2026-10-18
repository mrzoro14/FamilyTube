plugins {
    id("com.android.application")
}

android {
    namespace = "com.familytube.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.familytube.app"
        minSdk = 26
        targetSdk = 36
        versionCode = 6
        versionName = "1.5"

        // yt-dlp, Python and ffmpeg ship as native libraries; keep only phone CPUs.
        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a")
        }
    }

    buildTypes {
        release {
            // Family-only app installed by hand: sign with this PC's debug key so
            // every new build installs over the old one on the same phones.
            signingConfig = signingConfigs.getByName("debug")
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        // youtubedl-android runs its bundled binaries from the extracted lib folder.
        jniLibs {
            useLegacyPackaging = true
        }
    }
}

dependencies {
    val youtubedl = "0.18.1"
    implementation("io.github.junkfood02.youtubedl-android:library:$youtubedl")
    implementation("io.github.junkfood02.youtubedl-android:ffmpeg:$youtubedl")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.documentfile:documentfile:1.0.1")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("com.google.android.material:material:1.12.0")
}
