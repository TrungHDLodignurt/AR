plugins {
    // AGP 9+ ships built-in Kotlin support, so no kotlin-android plugin here.
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.compose) apply false
}
