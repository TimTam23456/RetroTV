plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.retrotv.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.retrotv.app"
        minSdk = 23
        // Kept at 29 on purpose: lets the app read games straight off USB drives
        // and the Download folder on TVs (sideloaded, so no Play Store rules apply).
        targetSdk = 29
        versionCode = 1
        versionName = "1.0"
    }

    signingConfigs {
        create("retrotv") {
            storeFile = file("retrotv.keystore")
            storePassword = "retrotv123"
            keyAlias = "retrotv"
            keyPassword = "retrotv123"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("retrotv")
        }
        debug {
            signingConfig = signingConfigs.getByName("retrotv")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    lint {
        checkReleaseBuilds = false
        abortOnError = false
    }
    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
    }
}

dependencies {
    implementation("com.github.Swordfish90:LibretroDroid:0.14.0")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("org.nanohttpd:nanohttpd:2.3.1")
}
