plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "com.vibecoderspace.noptrace.terminal"
    compileSdk = 37
    compileSdkMinor = 2
    ndkVersion = "28.2.13676358"

    defaultConfig {
        applicationId = "com.vibecoderspace.noptrace.terminal"
        minSdk = 26
        targetSdk = 37
        versionCode = 1
        versionName = "1.0.0"
        ndk {
            abiFilters += "arm64-v8a"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation(project(":terminal-view"))
    implementation(libs.androidx.annotation)
}
