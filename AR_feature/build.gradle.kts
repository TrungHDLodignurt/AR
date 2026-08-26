plugins {
    // AGP 9+ ships built-in Kotlin support, so no kotlin-android plugin here.
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "vn.apero.armeasure"
    compileSdk = 36
    resourcePrefix = "armeasure_"

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
    api(libs.androidx.material3)

    implementation(libs.sceneview.ar)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.core.ktx)

    testImplementation(libs.junit)
    // Unit tests run against android.jar's stub org.json (throws "not mocked"), not a real
    // parser — this pulls in the real implementation so ReferenceObjectJsonTest can run as
    // plain JUnit with no Robolectric, per this phase's requirement.
    testImplementation(libs.json)
}
