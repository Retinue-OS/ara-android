import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

// Nightly builds are signed with a checked-in, non-secret test certificate
// (see keystore/README.md) so consecutive nightly APKs share a signature and
// can be installed over one another. This is not a production signing key.
val nightlyKeystoreProperties = Properties().apply {
    file("../keystore/nightly-test.properties").inputStream().use { load(it) }
}

android {
    namespace = "os.retinue.ara"
    // Android 15. A currently supported stable SDK; the Galaxy S25 ships One UI 7
    // on top of Android 15, so this matches the primary target device.
    compileSdk = 35

    defaultConfig {
        applicationId = "os.retinue.ara"
        // minSdk 31 (Android 12): covers the modern Samsung Galaxy line (S21 and
        // newer). Features of later levels (the Android 14 microphone foreground
        // service type, POST_NOTIFICATIONS) are guarded at runtime.
        minSdk = 31
        targetSdk = 35
        versionCode = 2
        versionName = "2.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        // No BuildConfig secrets are baked in: the Retinue base URL, the client
        // certificate and the wake-phrase models are all configured at runtime.

        // ONNX Runtime ships native libraries per ABI; phones and the emulator
        // only need these two, which keeps the APK a third of the size.
        ndk {
            abiFilters += listOf("arm64-v8a", "x86_64")
        }
    }

    signingConfigs {
        create("nightly") {
            storeFile = file("../keystore/${nightlyKeystoreProperties.getProperty("storeFile")}")
            storePassword = nightlyKeystoreProperties.getProperty("storePassword")
            keyAlias = nightlyKeystoreProperties.getProperty("keyAlias")
            keyPassword = nightlyKeystoreProperties.getProperty("keyPassword")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            signingConfig = signingConfigs.getByName("nightly")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }

    testOptions {
        unitTests {
            isReturnDefaultValues = true
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.datastore.preferences)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons.extended)
    debugImplementation(libs.compose.ui.tooling)

    implementation(libs.okhttp)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.androidx.webkit)
    implementation(libs.onnxruntime.android)

    testImplementation(libs.junit)
    testImplementation(libs.kotlin.test.junit)
}
