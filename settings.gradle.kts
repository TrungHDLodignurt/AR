pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "ar-tape-measure"
include(":app")
include(":ar-measure-common")
include(":ar-measure-ar")
include(":ar-measure-photo")
