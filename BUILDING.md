<!--
SPDX-FileCopyrightText: 2026 BluffWorks LLC
SPDX-License-Identifier: GPL-3.0-only
-->

# Building the recorder

Reproducibility matters here; these versions are exact and match the ChargeLog app's own build
(the recorder is developed inside that app's repository and mirrored out to this one).

| Tool | Version |
|---|---|
| JDK | 17 (any vendor; CI uses Temurin 17) |
| Gradle | 9.5.0 (wrapper, sha-pinned — always use `./gradlew`) |
| Android Gradle Plugin | 9.3.0 (built-in Kotlin — `org.jetbrains.kotlin.android` is deliberately absent) |
| Kotlin / Compose compiler | 2.4.10 |
| compileSdk / minSdk | 37 / 31 |
| SDK build-tools | 36.0.0 |

## The Android SDK

Gradle has to be able to find an Android SDK, and a fresh clone does not carry one. Without it
the very first Gradle command fails with `SDK location not found`, which says nothing about how
to fix it. Two ways:

**Android Studio.** Open this directory as a project. Studio provisions the SDK and writes
`local.properties` for you. Nothing else to do.

**Command line.** Install the SDK, point `ANDROID_HOME` at it, and add the two packages this
build needs — the same ones CI installs (`.github/workflows/ci.yml`):

```
export ANDROID_HOME="$HOME/Library/Android/sdk"     # or wherever yours lives
sdkmanager "platforms;android-37.0" "build-tools;36.0.0"
```

Note `platforms;android-37.0` — the `sdkmanager` package name carries the `.0`, even though
`compileSdk = 37` does not. Instead of `ANDROID_HOME` you can write the path into an untracked
`local.properties` at this directory's root:

```
sdk.dir=/absolute/path/to/Android/sdk
```

`local.properties` is machine-specific and is not committed.

## Modules and commands

Modules: `:recorder` (Android library — capture, `data/` Room, `measure/` facts, the recording
service; no resources, no Compose) and `:sample` (a minimal Compose app that implements
`RecorderHost` and runs the recorder).

Build the sample: `./gradlew :sample:assembleDebug`
Library unit tests (no device): `./gradlew :recorder:testDebugUnitTest`
Device tests: `./gradlew :recorder:connectedDebugAndroidTest`

## Pulling a session log

For a bug report or a fixture contribution. Install the sample
(`./gradlew :sample:installDebug`), complete a charging session, then, from this directory:

```
./scripts/pull-logs.sh                 # writes to ./pulled-logs
./scripts/pull-logs.sh some/other/dir  # or somewhere else
```

It shells out to `adb shell run-as`, so it needs a debuggable build and a connected device. For
a different host app, pass its application id: `PKG=com.example.myapp ./scripts/pull-logs.sh`.

Before attaching one of those files to a public issue or PR, read
**"Read this before you attach a session log"** in `README.md` — a session log carries wall-clock
timestamps, a 1 Hz screen-state trace, and a per-install device id, and evidence contributed to
the fixture corpus is dedicated to the public domain irrevocably under CC0-1.0.

## Invariants

- **No resources.** `:recorder` carries no strings, drawables, layouts, or any other Android
  resource — a stray `R.` reference fails the build. This is a deliberate property of the
  library, not an oversight: it keeps the module a pure engine the host fully controls the
  presentation of.
- **No `INTERNET` permission**, anywhere in this tree. The recorder writes to local storage only
  and makes no network calls.
- **The library manifest requests only what the library itself needs.** `FOREGROUND_SERVICE`,
  `FOREGROUND_SERVICE_SPECIAL_USE`, `POST_NOTIFICATIONS`, `RECEIVE_BOOT_COMPLETED`, `WAKE_LOCK`
  — and nothing else. Anything a *host* wants (a battery-optimization exemption prompt, say) is
  declared by the host's own manifest, so embedding this library never adds a permission an
  integrator did not ask for.

### Override the `specialUse` justification

One thing the library manifest *does* hand you that you should change. `RecordingService` runs as
a `specialUse` foreground service, and Play requires such a service to carry a
`PROPERTY_SPECIAL_USE_FGS_SUBTYPE` property justifying it. The library ships one:

> Continuous battery charging-session measurement while the device is plugged in; sessions are
> user-visible recordings analyzed on-device.

That is ChargeLog's text, written for ChargeLog's own Play review. A library manifest merges into
every app that embeds it, so unless you replace it your app's Play submission carries a
declaration about *your* app in words somebody else wrote. It is roughly right for a recorder used
the way this one is; it is wrong the moment your app does something the sentence does not
describe, and it is not a claim you can defend in a review appeal.

Declare the service again in your own manifest and replace the value:

```xml
<manifest xmlns:android="http://schemas.android.com/apk/res/android"
          xmlns:tools="http://schemas.android.com/tools">
  <application>
    <service
        android:name="io.github.allenbw.chargelog.capture.RecordingService"
        android:foregroundServiceType="specialUse"
        tools:node="merge">
      <property
          android:name="android.app.PROPERTY_SPECIAL_USE_FGS_SUBTYPE"
          android:value="@string/fgs_justification"
          tools:replace="android:value" />
    </service>
  </application>
</manifest>
```

Check the result rather than trusting the merge: `./gradlew :yourapp:processDebugManifest` writes
the merged manifest to `build/intermediates/merged_manifest*/AndroidManifest.xml`, and the merger
report beside it says which manifest each attribute came from.
