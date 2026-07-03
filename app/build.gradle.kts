// SPDX-License-Identifier: GPL-3.0-or-later
import org.gradle.testing.jacoco.tasks.JacocoReport
import java.util.Properties

plugins {
    // Applied by id; the plugins themselves come from the root buildscript classpath.
    // We deliberately do NOT apply org.jetbrains.kotlin.android — AGP's built-in Kotlin
    // handles Kotlin compilation (using KGP 2.4.0 from the buildscript classpath).
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
    id("com.google.dagger.hilt.android")
    // JaCoCo (Gradle built-in) — unit-test code-coverage reporting (issue #192). The base `jacoco`
    // plugin auto-instruments the JVM `testDebugUnitTest` task; the jacocoTestReport task below turns
    // its exec data into XML + HTML. AGP-9-safe: it does NOT apply org.jetbrains.kotlin.android (which
    // ClassCastExceptions against AGP 9's built-in-Kotlin DSL — see CLAUDE.md) and touches no variant DSL.
    jacoco
    // Lint/format — resolved from the Gradle Plugin Portal (not the buildscript classpath).
    alias(libs.plugins.ktlint)
    alias(libs.plugins.detekt)
}

// Read optional build secrets (Outlook client id, release signing) from secrets.properties
// (git-ignored). Absent values fall back to the defaults below.
val secretsFile = rootProject.file("secrets.properties")
val secrets = Properties().apply {
    if (secretsFile.exists()) secretsFile.inputStream().use { load(it) }
}

// Microsoft (Outlook) OAuth public client id — a GUID, not a secret. Overridable via
// secrets.properties; defaults to the app's registered client id.
val outlookOAuthClientId: String = secrets.getProperty(
    "OUTLOOK_OAUTH_CLIENT_ID",
    "04e4aa5e-ed1f-47f9-b567-b99a0b29b3df",
)

// Custom URI scheme AppAuth uses to capture the Outlook OAuth redirect. Must match the scheme of
// OUTLOOK_OAUTH_REDIRECT_URI and the redirect URI registered in the Azure app registration.
val outlookRedirectScheme = "org.libremail.outlook"

// Debug-report ingest endpoint (issue #34, out of scope for this repo). Empty by default: the debug
// reporting client is strictly opt-in and never sends anything unless the user taps Submit AND an
// endpoint is configured here (overridable via git-ignored secrets.properties).
val debugReportEndpoint: String = secrets.getProperty("DEBUG_REPORT_ENDPOINT", "")

// Optional release signing, configured via git-ignored secrets.properties. When absent, release
// builds fall back to the debug key (installable for testing, but not publishable).
val releaseStoreFile: String? = secrets.getProperty("RELEASE_STORE_FILE")

android {
    namespace = "org.libremail"
    compileSdk = 37

    defaultConfig {
        applicationId = "org.libremail.app"
        // Supports a rolling ~7-year window of Android versions (API 29 / Android 10, 2019 → latest).
        minSdk = 29
        targetSdk = 37
        versionCode = 1
        versionName = "0.1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        buildConfigField("String", "OUTLOOK_OAUTH_CLIENT_ID", "\"$outlookOAuthClientId\"")
        buildConfigField("String", "OUTLOOK_OAUTH_REDIRECT_URI", "\"$outlookRedirectScheme://oauth2redirect\"")
        buildConfigField("String", "DEBUG_REPORT_ENDPOINT", "\"$debugReportEndpoint\"")
        // AppAuth's bundled manifest requires this placeholder; it registers the redirect scheme on
        // RedirectUriReceiverActivity so the Outlook sign-in redirect returns to the app.
        manifestPlaceholders["appAuthRedirectScheme"] = outlookRedirectScheme
    }

    signingConfigs {
        if (releaseStoreFile != null) {
            create("release") {
                storeFile = file(releaseStoreFile)
                storePassword = secrets.getProperty("RELEASE_STORE_PASSWORD")
                keyAlias = secrets.getProperty("RELEASE_KEY_ALIAS")
                keyPassword = secrets.getProperty("RELEASE_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            // Use a dedicated release keystore when configured in secrets.properties; otherwise fall
            // back to the debug key so the build is still installable for local testing.
            signingConfig = if (releaseStoreFile != null) {
                signingConfigs.getByName("release")
            } else {
                signingConfigs.getByName("debug")
            }
            // Ship ARM only — arm64-v8a (64-bit) + armeabi-v7a (32-bit); x86/x86_64 are
            // intentionally dropped. Scoped to this build type ONLY — do NOT move to defaultConfig
            // or the debug type: CI's E2E matrix runs the debug build on x86_64 emulators
            // (.github/workflows/ci.yml) and needs the x86_64 native libs (incl. libsqlcipher.so).
            // See docs/play-compliance.md and docs/fdroid-compliance.md for the coverage tradeoff.
            ndk {
                abiFilters += listOf("arm64-v8a", "armeabi-v7a")
            }
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    // Ship the exported Room schemas as androidTest assets so MigrationTestHelper can load them.
    sourceSets.getByName("androidTest").assets.srcDir("$projectDir/schemas")

    // F-Droid compliance (issue #16): by default AGP embeds a "dependency info block" in the APK
    // signing block — a list of every dependency, encrypted so that ONLY Google Play can read it.
    // F-Droid's inclusion policy treats that opaque, Google-only blob as a blocker (it cannot be
    // verified from source and breaks reproducible builds), so keep it out of APKs and bundles.
    // See docs/fdroid-compliance.md.
    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }

    // Ship the exported Room schemas as androidTest assets so MigrationTestHelper can load them.
    sourceSets.getByName("androidTest").assets.srcDir("$projectDir/schemas")

    packaging {
        resources {
            // Angus Mail / Jakarta Activation (added later) ship duplicate META-INF entries.
            excludes += setOf(
                "/META-INF/{AL2.0,LGPL2.1}",
                "/META-INF/DEPENDENCIES",
                "/META-INF/LICENSE*",
                "/META-INF/NOTICE*",
                "/META-INF/INDEX.LIST",
            )
        }
    }

    testOptions {
        // Gradle Managed Devices define the per-API E2E matrix as config-as-code: one virtual
        // device per supported Android version (a rolling ~7-year window, API 29 → latest stable).
        // Run the whole matrix with `./gradlew e2eGroupDebugAndroidTest`, or one level with e.g.
        // `./gradlew api29DebugAndroidTest`. Gradle provisions/boots/tears down the emulators and
        // downloads the system images on first use. Keep this list in lockstep with the CI matrix in
        // .github/workflows/ci.yml; when a new Android ships, add it and drop the oldest level that
        // has fallen outside ~7 years.
        //
        // API 37 (preview) is intentionally NOT listed here (re-confirmed 2026-07, see PR that added
        // API 35/37 to preflight): its only published system image is the nonstandard
        // "android-37.0" / google_apis_ps16k pairing that the e2e-preview job in
        // .github/workflows/ci.yml installs directly via sdkmanager. ManagedVirtualDevice only knows
        // how to build an "android-<apiLevel:Int>" package id (e.g. `apiLevel = 37` → "android-37")
        // or an "android-<apiPreview:codename>" one — neither produces "android-37.0" — so there is
        // no DSL path to this image today, the same root cause documented on e2e-preview for why
        // reactivecircus/android-emulator-runner can't provision it either. issue #124's perf doc
        // (docs/perf/issue-124-unified-inbox-paging.md) independently corroborates this: its API 37
        // cross-check used a physical Pixel, not an emulator/AVD. Locally, preflight covers API 37
        // by hand-provisioning it with .claude/skills/preflight/api37_e2e.py, which mirrors the
        // e2e-preview job (same image + emulator flags, except it renders on the host GPU via
        // `-gpu auto-no-window` locally instead of CI's headless `-gpu swiftshader_indirect`). Once
        // a managed-device-compatible image is published, add `api37` here (and to the CI matrix),
        // delete that script, and drop the e2e-preview job.
        managedDevices {
            localDevices {
                listOf(29, 30, 31, 32, 33, 34, 35, 36).forEach { api ->
                    create("api$api") {
                        device = "Pixel 2"
                        apiLevel = api
                        systemImageSource = "google_apis"
                    }
                }
            }
            groups {
                create("e2e") {
                    targetDevices.addAll(localDevices)
                }
            }
        }
    }
}

// Export Room schemas so the instrumented MigrationTest can replay and validate each migration.
ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

detekt {
    // Merge the project overrides in config/detekt onto detekt's bundled defaults.
    buildUponDefaultConfig = true
    config.setFrom(rootProject.file("config/detekt/detekt.yml"))
}

// Pin a modern JaCoCo (version catalog) so the coverage agent understands Kotlin 2.4.0 bytecode
// on JDK 21.
jacoco {
    toolVersion = libs.versions.jacoco.get()
}

// Unit-test coverage report (issue #192). Reads the exec data the base `jacoco` plugin records for
// the JVM `testDebugUnitTest` task, mapped against the debug variant's compiled Kotlin classes and
// the hand-written main sources. Produces machine-readable XML + human-readable HTML under
// build/reports/jacoco/jacocoTestReport/. Instrumented/E2E coverage is out of scope (issue #192).
tasks.register<JacocoReport>("jacocoTestReport") {
    // Ensure the unit tests (and thus their coverage exec data) have run first.
    dependsOn("testDebugUnitTest")
    group = "verification"
    description = "Generates JaCoCo XML + HTML coverage for the debug JVM unit tests."

    reports {
        xml.required.set(true)
        html.required.set(true)
    }

    // Strip generated code from the denominator so the % reflects hand-written Kotlin. Verified
    // against an actual compileDebugKotlin output tree: Room's KSP-generated `_Impl` DAOs/database
    // and the Compose compiler's per-file ComposableSingletons holders are the only generated code
    // that actually lands in classDirectories below (Room's KSP output is added as an extra Kotlin
    // source root on the *same* compile task, so it comes out the same door as hand-written code).
    // Hilt/Dagger's generated Java (Hilt_*, Dagger*_HiltComponents*, *_GeneratedInjector, *_Factory,
    // *_MembersInjector, hilt_aggregated_deps) and AGP's BuildConfig/R/Manifest are compiled by a
    // separate javac task (hiltJavaCompileDebug / compileDebugJavaWithJavac) into a directory this
    // report never reads, so those patterns are conventional belt-and-suspenders in case that ever
    // changes. DataBinding isn't enabled in this module (no buildFeatures.dataBinding/viewBinding),
    // so there's nothing generated for it to exclude; if it's turned on later, add "**/BR.class",
    // "**/DataBinderMapperImpl*.class" and "**/*Binding.class".
    //
    // Deliberately NOT excluded: Kotlin's own `$$inlined$` synthetic classes (e.g. for
    // `Flow.map { ... }` in the repositories) — those hold real hand-written transform logic, not
    // generated boilerplate, so stripping them would silently shrink the measured surface.
    val generated = listOf(
        "**/R.class",
        "**/R\$*.class",
        "**/BuildConfig.*",
        "**/Manifest*.*",
        "**/Hilt_*.class",
        "**/Dagger*.class",
        "**/*_Hilt*",
        "**/*_GeneratedInjector.class",
        "**/hilt_aggregated_deps/**",
        "**/dagger/**",
        "**/*_Factory*",
        "**/*_MembersInjector*",
        "**/*_Provide*",
        "**/*_Impl*",
        "**/ComposableSingletons*",
    )

    // Classes = the debug variant's compiled Kotlin (AGP 9 built-in Kotlin output). All hand-written
    // code here is Kotlin, so the javac output (purely Hilt/Dagger/BuildConfig generated) is omitted.
    val debugKotlinClasses = layout.buildDirectory.dir(
        "intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes",
    )
    classDirectories.setFrom(
        fileTree(debugKotlinClasses) { exclude(generated) },
    )

    // Sources = hand-written main Kotlin.
    sourceDirectories.setFrom(files("src/main/kotlin"))

    // Exec data written by the instrumented testDebugUnitTest task. Accept the base `jacoco` plugin's
    // default location and AGP's enableUnitTestCoverage location so the wiring is robust either way.
    executionData.setFrom(
        fileTree(layout.buildDirectory) {
            include(
                "jacoco/testDebugUnitTest.exec",
                "outputs/unit_test_code_coverage/debugUnitTest/testDebugUnitTest.exec",
            )
        },
    )
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.webkit)
    implementation(libs.androidx.biometric)
    implementation(libs.kotlinx.coroutines.android)

    // Email transport (IMAP/SMTP) + OAuth
    implementation(libs.angus.mail)
    implementation(libs.appauth)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.core)
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)

    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    compileOnly(libs.error.prone.annotations)
    implementation(libs.androidx.hilt.navigation.compose)
    implementation(libs.androidx.hilt.work)
    ksp(libs.androidx.hilt.compiler)

    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.androidx.datastore.preferences)

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    implementation(libs.androidx.room.paging)
    ksp(libs.androidx.room.compiler)
    implementation(libs.sqlcipher.android)

    // Paging 3 — the unified inbox list is paged so its cost scales with the screen (issue #124).
    implementation(libs.androidx.paging.runtime)
    implementation(libs.androidx.paging.compose)

    // Raise kotlinx-serialization to the version Room's schema-bundle serializers were compiled
    // against (see libs.versions.toml). AGP 9 consistent resolution shares it with the androidTest
    // classpath so MigrationTestHelper can parse the exported schema JSON.
    implementation(platform(libs.kotlinx.serialization.bom))

    testImplementation(libs.junit)
    testImplementation(libs.kotlin.test)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.turbine)
    testImplementation(libs.mockk)
    testImplementation(libs.greenmail)
    // asSnapshot() drives a PagingData flow to a concrete list in JVM unit tests (issue #124).
    testImplementation(libs.androidx.paging.testing)
    // The real org.json for unit tests (android.jar ships a stubbed, no-op version).
    testImplementation("org.json:json:20231013")

    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.espresso.intents)
    androidTestImplementation(libs.androidx.test.rules)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.room.testing)
    // Instrumented DatabaseProvisioner test: fakes the security/settings collaborators and spies the
    // DatabaseEncryption object to regression-guard the SQLCipher native-lib load before a keyed open.
    androidTestImplementation(libs.mockk.android)
}
