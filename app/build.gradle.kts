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
        // CI appends the commit, so the settings show which build is installed.
        // Only what a commit or tag is made of gets into the generated code.
        fun buildEnv(name: String) = System.getenv(name).orEmpty().filter { it.isLetterOrDigit() || it in "-._+" }
        val buildSha = buildEnv("ARA_BUILD_SHA")
        versionName = "2.0" + (buildSha.take(7).takeIf { it.isNotEmpty() }?.let { "+$it" } ?: "")
        // What the updater compares with the release channel (see update/UpdateLogic):
        // the commit, and the release tag the nightly workflow builds for. Both
        // are empty in a local build, which then matches no published build.
        buildConfigField("String", "BUILD_SHA", "\"$buildSha\"")
        buildConfigField("String", "BUILD_TAG", "\"${buildEnv("ARA_BUILD_TAG")}\"")

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
        // Debug builds share the test certificate too: each CI runner would
        // otherwise sign with a fresh debug key, and Android refuses to install
        // one branch APK over the previous one ("App not installed").
        debug {
            signingConfig = signingConfigs.getByName("nightly")
        }
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
    implementation(libs.health.connect.client)

    testImplementation(libs.junit)
    testImplementation(libs.kotlin.test.junit)
}
