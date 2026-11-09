plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "nl.kpn.slim"
    compileSdk = 36

    defaultConfig {
        applicationId = "nl.kpn.slim"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.1"

        // Optional developer seed: an account + deviceId that is already registered. Normally empty; the app then
        // asks for a one-time sign-in and registers its own random deviceId.
        buildConfigField("String", "KPN_ACCOUNT", "\"${findProperty("kpn.account") ?: ""}\"")
        buildConfigField("String", "KPN_DEVICE_ID", "\"${findProperty("kpn.deviceId") ?: ""}\"")
    }

    buildFeatures { buildConfig = true }

    signingConfigs {
        // Sideload-only app: sign release with the local debug keystore.
        create("sideload") {
            storeFile = file("${System.getProperty("user.home")}/.android/debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName("sideload")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) }
}

dependencies {
    val media3 = "1.11.1"
    implementation("androidx.media3:media3-exoplayer:$media3")
    implementation("androidx.media3:media3-exoplayer-dash:$media3")
    implementation("androidx.media3:media3-ui:$media3")
    implementation("androidx.recyclerview:recyclerview:1.4.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.9.4")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
}
