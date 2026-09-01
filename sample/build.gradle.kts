// SPDX-FileCopyrightText: 2026 BluffWorks LLC
// SPDX-License-Identifier: GPL-3.0-only

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "io.github.allenbw.chargelog.sample"
    compileSdk = 37

    defaultConfig {
        applicationId = "io.github.allenbw.chargelog.sample"
        minSdk = 31
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
    }

    buildFeatures {
        compose = true
        buildConfig = true // AGP 9 defaults this off; RecorderHost.appVersion reads VERSION_NAME
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(project(":recorder"))
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)
    implementation(libs.compose.ui.tooling.preview)
    debugImplementation(libs.compose.ui.tooling)
    implementation(libs.activity.compose)
    implementation(libs.lifecycle.runtime.compose)
    // Already on the classpath through :recorder's `api`, declared because the sample names
    // Dispatchers/withContext itself.
    implementation(libs.coroutines.android)
    // FileProvider only — the one androidx.core class the sample uses directly.
    implementation(libs.androidx.core)
}
