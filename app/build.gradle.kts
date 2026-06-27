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
}

// Read the Gmail OAuth client id from secrets.properties (git-ignored). Empty when absent.
val secretsFile = rootProject.file("secrets.properties")
val secrets = Properties().apply {
    if (secretsFile.exists()) secretsFile.inputStream().use { load(it) }
}
val gmailOAuthClientId: String = secrets.getProperty("GMAIL_OAUTH_CLIENT_ID", "")

// For a Google installed-app OAuth client, AppAuth's redirect is the reversed client
// id as a custom URI scheme. Fall back to a placeholder so the manifest stays valid
// until a real client id is set in secrets.properties.
val gmailRedirectScheme: String = if (gmailOAuthClientId.endsWith(".apps.googleusercontent.com")) {
    "com.googleusercontent.apps." + gmailOAuthClientId.removeSuffix(".apps.googleusercontent.com")
} else {
    "org.libremail.oauth"
}

android {
    namespace = "org.libremail"
    compileSdk = 37

    defaultConfig {
        applicationId = "org.libremail.app"
        minSdk = 33
        targetSdk = 37
        versionCode = 1
        versionName = "0.1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        buildConfigField("String", "GMAIL_OAUTH_CLIENT_ID", "\"$gmailOAuthClientId\"")
        buildConfigField("String", "GMAIL_OAUTH_REDIRECT_URI", "\"$gmailRedirectScheme:/oauth2redirect\"")
        // AppAuth captures the OAuth redirect via this custom scheme.
        manifestPlaceholders["appAuthRedirectScheme"] = gmailRedirectScheme
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            // Sign release builds with the debug key so they're installable for testing.
            // A public release would configure a dedicated upload/release keystore here.
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
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
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.navigation.compose)
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

    testImplementation(libs.junit)
    testImplementation(libs.kotlin.test)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.turbine)
    testImplementation(libs.mockk)
    testImplementation(libs.greenmail)

    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
}
