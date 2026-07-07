// SPDX-License-Identifier: GPL-3.0-or-later
import org.gradle.testing.jacoco.tasks.JacocoCoverageVerification
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
        // IMAP connection reuse (issue #357 Part 2, wiring the #125 spike): keep one authenticated
        // IMAP connection warm per account instead of paying a cold CONNECT+TLS+LOGIN on every
        // operation — the fix for Gmail throttling LibreMail's connect-per-operation traffic. ON by
        // default; this is the safety switch: flip to "false" here (a build-config change, no Kotlin
        // edit) to fall back to connect-per-operation if a server misbehaves with a kept-alive socket.
        buildConfigField("Boolean", "IMAP_CONNECTION_REUSE", "true")
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
        // Robolectric-backed Compose UI unit tests (issue #373) need the merged Android resources
        // (drawables, strings, the compiled resource table) on the JVM unit-test classpath so
        // `stringResource(...)` and Material3 theming resolve without an emulator. Off by default in
        // AGP; JVM tests that don't touch resources are unaffected.
        unitTests.isIncludeAndroidResources = true

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

// Unit-test coverage (issue #192). Two tasks share ONE scoping so they can never measure different
// surfaces: `jacocoTestReport` (XML+HTML under build/reports/jacoco/jacocoTestReport/) and
// `jacocoTestCoverageVerification` (the no-regression gate, further down). Both read the exec data
// the base `jacoco` plugin records for the JVM `testDebugUnitTest` task, mapped against the debug
// variant's compiled Kotlin classes and the hand-written main sources. Instrumented/E2E coverage is
// out of scope (issue #192).

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
val jacocoGeneratedExcludes = listOf(
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

// Scope the denominator to the JVM-testable surface (issues #290/#292, following the Phase-2 coverage
// audit): unlike `jacocoGeneratedExcludes` above, none of this is generated code — it is hand-written
// but structurally unreachable from a JVM unit test, so counting it against the metric just measures
// how much Compose/framework glue exists rather than how well the logic is tested. Four buckets:
//  1. Compose screen/component render code. Historically only exercisable via an emulator, so it was
//     excluded here. Issue #373 changes that: Robolectric runs the Android framework on the JVM, so a
//     `createComposeRule()` test in the `test` source set now gives these files real JVM coverage
//     without an emulator. This bucket therefore SHRINKS one screen at a time — each glob is deleted
//     in the same PR that adds that screen's Robolectric JVM Compose test. AddAnotherAccountScreen was
//     the first (see AddAnotherAccountScreenJvmTest) and has been removed below; the rest are tracked
//     as per-area conversion tickets under #373. The coverage-floor re-ratchet is deferred until the
//     whole conversion is done and stable (#373) — do NOT raise it in a conversion PR.
//  2. Android framework entry points the OS instantiates directly (Activity/Service/Application/
//     BackupAgent) rather than the app's own code constructing them.
//  3. Hilt DI modules — `@Provides`/`@Binds` one-liners with no branching logic.
//  4. The `src/debug` cold-open probe (issue #221), a `ContentProvider` that only runs in a forked
//     instrumented process (see its kdoc) and is never packaged in a release build anyway.
//
// KEPT IN SCOPE — this corrects #292, which excluded `**/*Worker*`: the six WorkManager workers
// (SyncWorker, BackfillWorker, PruneWorker, SendWorker, ReportPurgeWorker, ReportUploadWorker) are
// all directly unit-tested today (construct-the-worker-and-call-doWork(), e.g. SyncWorkerTest,
// SendWorkerTest), so they carry real tested logic and belong in BOTH the numerator and denominator.
// Only their Hilt wiring (WorkManagerModule) is excluded, and that falls under `**/di/**` below — so
// there is intentionally no `**/*Worker*` glob in the list.
//
// Also deliberately NOT excluded, even though each sits in a package/pattern above and renders UI:
// files that carry plain, unit-tested logic alongside their `@Composable` functions. JaCoCo has no
// finer granularity than a class file, and Kotlin compiles every top-level function in a .kt file —
// `@Composable` or not — into the SAME facade class (`<File>Kt.class`); excluding that class would
// silently zero out the tested function's coverage too, not just the render code's. Confirmed
// against these files' own dedicated tests before leaving them out of the list below:
//   - ui/compose/RichTextEditor.kt (RichTextEditorTest) — the AnnotatedString<->RichTextContent
//     editor-op functions (applyStyle/applyBlock/applyLink/toRichContent/toAnnotatedString/...).
//   - ui/settings/AccountReorderList.kt (AccountReorderListTest) — commitDrag's reorder maths.
//   - ui/reader/HtmlBody.kt (HtmlBodyTest, InlineImageResolverTest) — cidKey/resolveInlineImage/
//     wrapHtml/toCssHex.
//   - ui/reporting/ReportReviewScreen.kt (ReportReviewClipboardTest) — copyReportPayloadToClipboard.
// (ui/compose/format/FontRegistry.kt and ui/mailbox/FolderLabels.kt are plain logic files with no
// `@Composable` at all — never at risk — but sit right next to excluded files below.) For the same
// reason this list names each Screen/component file individually rather than a package-wide
// "**/ui/**": a blanket pattern can't carve the four files above back out, and would also reach
// every `*ViewModel*`.
val jacocoNonJvmTestableSurface = listOf(
    // --- Compose UI render code: one glob per screen/component file (see the exceptions above) ---
    "**/LibreMailApp*",
    "**/AccountPickerScreen*",
    "**/AppPasswordSetupScreen*",
    "**/ManualSetupScreen*",
    "**/ComposeScreen*",
    "**/ColorSwatch*",
    "**/FontPicker*",
    "**/FontSizePicker*",
    "**/ParagraphAlignmentControl*",
    "**/DraftsScreen*",
    "**/LockScreen*",
    "**/AppLockGateHost*",
    "**/FolderDrawer*",
    "**/MailboxScreen*",
    // AddAnotherAccountScreen converted to a Robolectric JVM Compose test (#373) — now JVM-covered.
    "**/BatteryOptimizationScreen*",
    "**/ContactsAccessScreen*",
    "**/LicenseScreen*",
    "**/OnboardingWelcomeScreen*",
    "**/OutboxScreen*",
    "**/ReaderScreen*",
    "**/ProblemReportsScreen*",
    "**/AccountSettingsScreen*",
    "**/SettingsScreen*",
    "**/SettingsComponents*",
    "**/SignatureEditScreen*",
    "**/SignaturesScreen*",
    // CacheEncryptionGate.kt (issue #359/#367 fail-closed encryption gate) is pure render: the gate
    // composable, its blank cover, the error screen, and the ephemeral report-review screen — no plain
    // top-level logic. Spelled out to "...GateKt*" (the file's compiled facade class), NOT the bare
    // "**/CacheEncryptionGate*" this list otherwise uses, because unlike every Screen/ViewModel pair
    // above, CacheEncryptionGateViewModel's name literally starts with "CacheEncryptionGate" — a bare
    // wildcard would also swallow the (94%-covered, dedicated-tested) ViewModel and its sealed
    // CacheEncryptionGateState. CacheEncryptionGateViewModel and CacheEncryptionUnavailableException
    // stay in scope (both have JVM tests: CacheEncryptionGateViewModelTest, DatabaseProvisionerTest).
    "**/CacheEncryptionGateKt*",
    // --- Android framework entry points (OS-instantiated). NB: Workers are intentionally NOT here
    // --- (they are unit-tested — see the KEPT IN SCOPE note above).
    "**/*Activity*",
    "**/*Service*",
    "**/LibreMailApplication*",
    "**/*BackupAgent*",
    // --- Hilt DI wiring (includes WorkManagerModule) ---
    "**/di/**",
    // --- src/debug cold-open probe (issue #221) ---
    "**/data/local/coldopen/**",
    // --- src/debug fetch-gate receiver (issue #393): a BroadcastReceiver that only runs on-device
    // --- (adb-driven), covered by an instrumented test, never packaged into a release build. Its
    // --- pure collaborators DebugFetchGate/FetchScope stay IN scope (unit-tested by DebugFetchGateTest).
    "**/debug/FetchGateReceiver*",
)

// Classes = the debug variant's compiled Kotlin (AGP 9 built-in Kotlin output), with the generated
// code and the non-JVM-testable surface above stripped out. All hand-written code here is Kotlin, so
// the javac output (purely Hilt/Dagger/BuildConfig generated) is omitted. Hoisted to a shared val so
// the report and the verification gate always run against the identical denominator.
val jacocoDebugKotlinClasses = layout.buildDirectory.dir(
    "intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes",
)
val jacocoClassDirectories = fileTree(jacocoDebugKotlinClasses) {
    exclude(jacocoGeneratedExcludes + jacocoNonJvmTestableSurface)
}

// Sources = hand-written main Kotlin.
val jacocoSourceDirectories = files("src/main/kotlin")

// Exec data written by the instrumented testDebugUnitTest task. Accept the base `jacoco` plugin's
// default location and AGP's enableUnitTestCoverage location so the wiring is robust either way.
val jacocoExecutionData = fileTree(layout.buildDirectory) {
    include(
        "jacoco/testDebugUnitTest.exec",
        "outputs/unit_test_code_coverage/debugUnitTest/testDebugUnitTest.exec",
    )
}

tasks.register<JacocoReport>("jacocoTestReport") {
    // Ensure the unit tests (and thus their coverage exec data) have run first.
    dependsOn("testDebugUnitTest")
    group = "verification"
    description = "Generates JaCoCo XML + HTML coverage for the debug JVM unit tests."

    reports {
        xml.required.set(true)
        html.required.set(true)
    }

    classDirectories.setFrom(jacocoClassDirectories)
    sourceDirectories.setFrom(jacocoSourceDirectories)
    executionData.setFrom(jacocoExecutionData)
}

// No-regression coverage gate (closes #251; scoping from #290/#292). Fails `check` / CI when the
// overall LINE coverage of the scoped surface above drops below `jacocoLineCoverageFloor`. This is a
// FLOOR, not an absolute 95% target — the maintainer chose a ratchet over a fixed goal. The floor is
// set a hair (~0.5–1%) below the measured baseline so ordinary run-to-run noise doesn't red-flag it,
// while a real regression still fails the build. Manual ratchet FOR NOW: when coverage rises
// materially, bump this number up in the SAME PR so the floor tracks reality (there is no auto-ratchet
// yet). Baseline measured when this floor was set: 80.21% line (4838/6032), floor 0.79 (~1.2% headroom).
val jacocoLineCoverageFloor = "0.79"
tasks.register<JacocoCoverageVerification>("jacocoTestCoverageVerification") {
    // Same inputs as jacocoTestReport (shared vals above) so the gate enforces exactly what the
    // report shows. Depend on the unit tests so the exec data exists before verifying.
    dependsOn("testDebugUnitTest")
    group = "verification"
    description = "Fails the build if scoped JVM unit-test LINE coverage regresses below the floor."

    classDirectories.setFrom(jacocoClassDirectories)
    sourceDirectories.setFrom(jacocoSourceDirectories)
    executionData.setFrom(jacocoExecutionData)

    violationRules {
        rule {
            element = "BUNDLE"
            limit {
                counter = "LINE"
                value = "COVEREDRATIO"
                minimum = jacocoLineCoverageFloor.toBigDecimal()
            }
        }
    }
}

// Make the aggregate `check` lifecycle task enforce the no-regression floor locally too, so a
// coverage regression is caught by `./gradlew check` and not only in CI.
tasks.named("check") {
    dependsOn("jacocoTestCoverageVerification")
}

// --- Robolectric android-all offline resolution (issue #373) ------------------------------------
// Robolectric runs the real Android framework on the JVM from a large `android-all-instrumented`
// jar. By default it resolves that jar LAZILY AT TEST TIME by downloading it from Maven Central
// (org.robolectric.internal.dependency.MavenDependencyResolver -> MavenArtifactFetcher). That
// runtime download is unreliable on CI runners and failed the JVM Compose PoC in CI with
// `java.lang.AssertionError at MavenArtifactFetcher ... Caused by: java.io.IOException` ("Failed to
// fetch maven artifact"). Fix: resolve the jar through Gradle instead — reliable, cached, and
// persisted by the CI Gradle cache, using the same repositories as every other dependency — then
// hand it to Robolectric in OFFLINE mode so it never touches the network at test time.
//
// A DEDICATED resolvable configuration (deliberately NOT testImplementation/testRuntimeOnly) keeps
// the ~200 MB instrumented framework jar OFF the JVM unit-test classpath: it must be loaded only by
// Robolectric's sandbox classloader, never flattened onto the app's test classpath where it would
// collide with the stub `android.jar`. `syncRobolectricAndroidAll` stages the resolved jar under
// its Maven filename (android-all-instrumented-<version>.jar) — exactly what Robolectric's
// LocalDependencyResolver looks up as <artifactId>-<version>.jar — and the two system properties
// below switch Robolectric onto that offline directory (see LegacyDependencyResolver). Every
// Robolectric test pins @Config(sdk = 36) (app/src/test/resources/robolectric.properties), so the
// single sdk=36 jar covers them all; a test on a different SDK must add that android-all version to
// this configuration too. The offline properties are inert for non-Robolectric JVM tests.
val robolectricAndroidAll: Configuration = configurations.create("robolectricAndroidAll") {
    isCanBeConsumed = false
    isCanBeResolved = true
}

val robolectricDepsDir = layout.buildDirectory.dir("robolectric-android-all")

val syncRobolectricAndroidAll = tasks.register<Sync>("syncRobolectricAndroidAll") {
    description = "Stages Robolectric's android-all-instrumented jar for offline resolution (issue #373)."
    from(robolectricAndroidAll)
    into(robolectricDepsDir)
}

tasks.withType<Test>().configureEach {
    dependsOn(syncRobolectricAndroidAll)
    systemProperty("robolectric.offline", "true")
    systemProperty("robolectric.dependency.dir", robolectricDepsDir.get().asFile.absolutePath)
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

    // Robolectric-backed JVM Compose UI tests (issue #373): Robolectric runs the Android framework
    // on the JVM so `createComposeRule()` can drive composables without an emulator, bringing screen
    // render code into the JaCoCo JVM-testable surface. The Compose test artifacts come from the same
    // BOM as the app (aligned versions) and reuse the ui-test-junit4 / ui-test-manifest aliases the
    // androidTest source set already declares — here in `test` (JVM), not `androidTest`. Robolectric
    // sources Android's real org.json from its sandbox, so it does not clash with the stub-replacing
    // org.json above (that is for the plain, non-Robolectric JVM tests).
    testImplementation(libs.robolectric)
    // The android-all-instrumented framework jar Robolectric loads into its sandbox — resolved via
    // Gradle and staged for offline use by syncRobolectricAndroidAll above so no flaky test-time
    // download happens in CI (issue #373). On its own dedicated configuration, NOT the test
    // classpath — see that block for why. The artifact has no transitive dependencies (verified from
    // its POM), so it resolves to exactly the one staged jar.
    "robolectricAndroidAll"(libs.robolectric.android.all.instrumented)
    testImplementation(platform(libs.androidx.compose.bom))
    testImplementation(libs.androidx.compose.ui.test.junit4)
    testImplementation(libs.androidx.compose.ui.test.manifest)

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
    // TestListenableWorkerBuilder for the instrumented PruneWorker/BackfillWorker cache-lock-deferral
    // test (issue #226): builds a CoroutineWorker with its real (non-Hilt) constructor args on-device.
    androidTestImplementation(libs.androidx.work.testing)
}
