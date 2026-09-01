// SPDX-FileCopyrightText: 2026 BluffWorks LLC
// SPDX-License-Identifier: GPL-3.0-only

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.room3)
}

android {
    // Distinct from the app's namespace: AGP enforces unique namespaces across modules. Kotlin
    // packages stay io.github.allenbw.chargelog.{capture,data,measure}; only the R/BuildConfig
    // namespace differs, and this module has neither (no resources, no BuildConfig).
    namespace = "io.github.allenbw.chargelog.recorder"
    compileSdk = 37

    defaultConfig {
        minSdk = 31
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        consumerProguardFiles("consumer-rules.pro")
    }

    // The recorder carries no strings, drawables, or any other resource — a stray R. reference
    // fails the build. This is a property the public tree advertises; keep it.
    androidResources { enable = false }

    // Real-session NDJSON fixtures and their loader, shared with the app's tests.
    testFixtures { enable = true }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    jvmToolchain(17)
}

room3 {
    schemaDirectory("$projectDir/schemas")
}

dependencies {
    // `api`: the app names Room entities, DAO flows and RawLine in its own signatures.
    api(libs.room3.runtime)
    ksp(libs.room3.compiler)
    implementation(libs.sqlite.framework)
    api(libs.coroutines.android)
    api(libs.serialization.json)

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.core)
}
