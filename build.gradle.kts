// Top-level build file. Plugins are declared here (apply=false) and applied in
// the module build scripts. Keeps versions in one place via the version catalog.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
}
