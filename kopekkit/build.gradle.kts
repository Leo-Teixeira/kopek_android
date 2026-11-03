// KopekKit : le SDK. Une bibliothèque Android (pas une appli) sans UI,
// donc ni plugin Compose ni applicationId.
plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "com.lteixeira.kopekkit"
    compileSdk {
        version = release(36) {
            minorApiLevel = 1
        }
    }

    defaultConfig {
        minSdk = 24
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    testImplementation(libs.junit)
}
