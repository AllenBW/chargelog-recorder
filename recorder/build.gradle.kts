// SPDX-FileCopyrightText: 2026 BluffWorks LLC
// SPDX-License-Identifier: GPL-3.0-only

import java.time.Duration

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

    // Lint's separate analysis of the unit-test, androidTest and testFixtures source sets was
    // ~47 s of a consumer's lint run for sources it barely reports on. Main sources are still
    // analyzed in full.
    lint {
        ignoreTestSources = true
        ignoreTestFixturesSources = true
        // minSdk 31 admits Android 12 and 13, where an unguarded newer call is a NoSuchMethodError
        // in the service on every plug-in. These three fail the build however a default moves.
        fatal += setOf("NewApi", "MissingPermission", "UnspecifiedRegisterReceiverFlag")
    }
}

kotlin {
    jvmToolchain(17)
}

room3 {
    schemaDirectory("$projectDir/schemas")
}

// Room exports a schema only when it differs from the committed one and never deletes an export,
// so a reverted entity change left its export behind, the room3 plugin copied it back over the
// restored schemas/<version>.json on every build, and the build cache kept it past a clean.
// Emptying the export before KSP runs makes every export this run's own. docs/ndjson-format.md,
// "Writing the projection".
tasks.named { it.startsWith("ksp") }.configureEach {
    val export = layout.buildDirectory.dir("intermediates/room/schemas/$name")
    doFirst { export.get().asFile.listFiles()?.forEach { it.deleteRecursively() } }
}

// ChargeLogDbSchemaPinTest reads the committed schemas, which no compile task hashes for the tests:
// without this, a hand-edited schemas/6.json alone left the tests UP-TO-DATE and the pin unrun.
tasks.withType<Test>().configureEach {
    inputs.dir("schemas").withPathSensitivity(PathSensitivity.RELATIVE).withPropertyName("roomSchemas")
}

// A test that hangs fails its task after 20 minutes instead of holding the build open. The whole
// suite runs in seconds; the margin is for a loaded machine, not for any test that needs it.
tasks.withType<Test>().configureEach {
    timeout.set(Duration.ofMinutes(20))
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
