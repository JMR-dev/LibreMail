// SPDX-License-Identifier: GPL-3.0-or-later
import java.util.Properties

plugins {
    // Applied by id; the plugins themselves come from the root buildscript classpath.
    // We deliberately do NOT apply org.jetbrains.kotlin.android — AGP's built-in Kotlin
    // handles Kotlin compilation (using KGP 2.4.0 from the buildscript classpath).
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
    id("com.google.dagger.hilt.android")
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
        // has fallen outside ~7 years. API 37 (preview) is exercised on the dev emulator until a
        // stable managed-device image is published, so it is intentionally not listed here.
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

// Export Room schemas so migrations can be validated by instrumented MigrationTestHelper tests.
ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

detekt {
    // Merge the project overrides in config/detekt onto detekt's bundled defaults.
    buildUponDefaultConfig = true
    config.setFrom(rootProject.file("config/detekt/detekt.yml"))
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.webkit)
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
    ksp(libs.androidx.room.compiler)
    implementation(libs.sqlcipher.android)

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
    // The real org.json for unit tests (android.jar ships a stubbed, no-op version).
    testImplementation("org.json:json:20231013")

    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.espresso.intents)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.room.testing)
}
