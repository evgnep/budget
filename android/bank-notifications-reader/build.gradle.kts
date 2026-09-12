plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "su.nepom.budget.banknotifications"
    compileSdk = 37
    buildToolsVersion = "36.0.0"

    defaultConfig {
        applicationId = "su.nepom.budget.banknotifications"
        minSdk = 26
        targetSdk = 37
        versionCode = 1
        versionName = rootProject.version.toString()
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    kotlin {
        jvmToolchain(libs.versions.jdk.get().toInt())
    }

    buildFeatures {
        compose = true
    }
}

dependencies {
    implementation(libs.android.core.ktx)
    implementation(libs.android.activity.compose)
    implementation(platform(libs.android.compose.bom))
    implementation(libs.android.compose.ui)
    implementation(libs.android.compose.ui.tooling.preview)
    implementation(libs.android.compose.material3)
    debugImplementation(libs.android.compose.ui.tooling)
}
