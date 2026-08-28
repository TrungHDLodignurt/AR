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
    // ViewModels are obtained with androidx's own viewModel() plus an explicit factory, deliberately
    // not koinViewModel(): this module ships no DI so it drops into a host with or without Koin, and
    // README section 13's R8 story rests on the dependency list staying reflection-free.
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.viewmodel.savedstate)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.core.ktx)
    // @Preview annotations only; the renderer itself is the IDE's, so no ui-tooling at runtime.
    implementation(libs.androidx.ui.tooling.preview)
    // Primary auto-fit detector. Unbundled: the model arrives via Play Services, so this costs no
    // APK size, but it also means a device without Play Services gets the Canny+Hough fallback.
    implementation(libs.mlkit.subject.segmentation)

    testImplementation(libs.junit)
    // Unit tests run against android.jar's stub org.json (throws "not mocked"), not a real
    // parser — this pulls in the real implementation so ReferenceObjectJsonTest can run as
    // plain JUnit with no Robolectric, per this phase's requirement.
    testImplementation(libs.json)
    // Dispatchers.setMain, so MviViewModelTest can construct a ViewModel without a device.
    testImplementation(libs.kotlinx.coroutines.test)
}
