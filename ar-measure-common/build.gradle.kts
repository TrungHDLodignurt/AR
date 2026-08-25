plugins {
    // AGP 9+ ships built-in Kotlin support, so no kotlin-android plugin here.
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "vn.apero.armeasure.common"
    compileSdk = 36

    defaultConfig {
        minSdk = 24
    }

    lint {
        targetSdk = 36
    }

    testOptions {
        targetSdk = 36
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }
}

dependencies {
    api(platform(libs.androidx.compose.bom))
    api(libs.androidx.ui)
    api(libs.androidx.ui.graphics)

    testImplementation(libs.junit)
}
