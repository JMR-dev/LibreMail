<!-- SPDX-License-Identifier: GPL-3.0-or-later -->
# F-Droid compliance

Audit for issue #16, performed 2026-07-01 against `main` (versionName 0.1.0 /
versionCode 1). **Verdict: LibreMail meets F-Droid's inclusion criteria with no
anti-features to declare.** Every runtime dependency is FOSS-licensed and
GPL-3.0-or-later-compatible, there are no Google Play Services / Firebase /
proprietary artifacts, no non-free Gradle plugins, and a clean-room build (no
`secrets.properties`, no proprietary keys) produces an installable APK.

Companion deliverables:

- `fastlane/metadata/android/en-US/` — the store listing F-Droid reads from this repo.
- `docs/fdroid/org.libremail.app.yml` — template + instructions for the build recipe
  that goes into [fdroiddata](https://gitlab.com/fdroid/fdroiddata).

## 1. Dependency license audit

### 1.1 Runtime dependencies (what ships in the APK)

Enumerated with `./gradlew :app:dependencies --configuration releaseRuntimeClasspath`.
Direct dependencies, with the resolved versions at audit time:

| Dependency | Version | License | Role |
|---|---|---|---|
| `androidx.core:core-ktx` | 1.17.0 | Apache-2.0 | AndroidX core |
| `androidx.lifecycle:lifecycle-runtime-ktx` / `-runtime-compose` / `-viewmodel-compose` | 2.9.4 | Apache-2.0 | Lifecycle/MVVM |
| `androidx.activity:activity-compose` | 1.12.4 | Apache-2.0 | Compose host activity |
| `androidx.navigation:navigation-compose` | 2.9.8 | Apache-2.0 | Navigation |
| `androidx.compose.*` (BOM 2026.06.00: ui, ui-graphics, material3, material-icons-core, …) | 1.11.3 / m3 1.4.0 | Apache-2.0 | UI toolkit |
| `androidx.webkit:webkit` | 1.12.1 | Apache-2.0 | Hardened WebView compat |
| `androidx.work:work-runtime-ktx` | 2.11.2 | Apache-2.0 | Background sync/outbox |
| `androidx.datastore:datastore-preferences` | 1.2.1 | Apache-2.0 | Settings store |
| `androidx.room:room-runtime` / `room-ktx` | 2.8.4 | Apache-2.0 | Local mail cache |
| `androidx.hilt:hilt-navigation-compose` / `hilt-work` | 1.3.0 | Apache-2.0 | Hilt integrations |
| `org.jetbrains.kotlin:kotlin-stdlib` | 2.4.0 | Apache-2.0 | Kotlin runtime |
| `org.jetbrains.kotlinx:kotlinx-coroutines-android` | 1.10.2 | Apache-2.0 | Coroutines |
| `com.google.dagger:hilt-android` (Dagger/Hilt) | 2.60 | Apache-2.0 | Dependency injection |
| `org.eclipse.angus:angus-mail` (+ `angus-activation`) | 2.0.5 / 2.0.3 | EPL-2.0 OR GPL-2.0 w/ Classpath-exception OR EDL-1.0 (BSD-3-Clause) | IMAP/SMTP transport |
| `net.openid:appauth` | 0.11.1 | Apache-2.0 | OAuth 2.0 + PKCE (Outlook) |
| `net.zetetic:sqlcipher-android` | 4.16.0 | BSD-3-Clause-style (SQLCipher Community Edition) | Opt-in cache encryption |

Transitive dependencies, grouped (full tree available from the Gradle command above):

| Group | License | Notes |
|---|---|---|
| `androidx.*` (~60 artifacts: appcompat, browser, collection, emoji2, fragment, savedstate, sqlite, startup, tracing, window, …) | Apache-2.0 | AndroidX |
| `org.jetbrains.*` (kotlin-stdlib, kotlinx-coroutines, kotlinx-serialization, annotations) | Apache-2.0 | JetBrains |
| `com.google.dagger:*` (dagger, hilt-core, dagger-lint-aar) | Apache-2.0 | via Hilt |
| `com.google.code.findbugs:jsr305` 3.0.2 | Apache-2.0 | annotations only |
| `com.google.guava:listenablefuture` 1.0 | Apache-2.0 | empty stub artifact (not Guava) |
| `com.squareup.okio:okio` 3.9.1 | Apache-2.0 | via DataStore |
| `jakarta.mail:jakarta.mail-api` 2.1.5 | EPL-2.0 OR GPL-2.0 w/ CPE OR EDL-1.0 | via Angus Mail |
| `jakarta.activation:jakarta.activation-api` 2.1.4 | EDL-1.0 (BSD-3-Clause) | via Angus Mail |
| `jakarta.inject:jakarta.inject-api` 2.0.1, `javax.inject:javax.inject` 1 | Apache-2.0 | DI annotations |
| `org.jspecify:jspecify` 1.0.0 | Apache-2.0 | nullness annotations |

**GPL compatibility.** Everything is Apache-2.0 or BSD-3-Clause except the
Jakarta/Angus mail stack, which is tri-licensed; LibreMail uses it under the
EDL-1.0 (BSD-3-Clause) / GPL-2.0-with-Classpath-exception options, both of which are
GPL-3.0-or-later-compatible. **No proprietary, source-unavailable, or
"free for open source use only" artifact appears anywhere in the tree.** In
particular there is **no** `com.google.android.gms:*` (Play Services), **no**
`com.google.firebase:*`, no Play Billing/Install Referrer, and no analytics or
crash-reporting SDK.

Native libraries in the release APK — all from the audited dependencies above:
`libsqlcipher.so` (SQLCipher), `libdatastore_shared_counter.so` (AndroidX DataStore),
`libandroidx.graphics.path.so` (AndroidX, via Compose).

### 1.2 Build-time dependencies (never ship in the APK)

Enumerated with `./gradlew buildEnvironment :app:buildEnvironment`:

| Plugin / tool | License |
|---|---|
| Android Gradle Plugin 9.2.x (`com.android.tools.*`) | Apache-2.0 |
| Kotlin Gradle plugin + Compose compiler 2.4.0 | Apache-2.0 |
| KSP 2.3.9 | Apache-2.0 |
| Hilt Gradle plugin 2.60 | Apache-2.0 |
| ktlint-gradle 14.2.0 (`org.jlleitschuh.gradle`) | MIT |
| detekt 2.0.0-alpha.5 (`dev.detekt`) | Apache-2.0 |

Their transitive tooling deps (protobuf, Tink, flatbuffers, bouncycastle,
juniversalchardet, jose4j, …) are Apache-2.0/MIT/MPL — all FOSS. **No non-free
Gradle plugin is used** (no Play Publisher, no Crashlytics/Google Services plugin,
no proprietary obfuscator; R8 ships with AGP and is Apache-2.0).

Artifacts resolve exclusively from open repositories: `google()` and
`mavenCentral()` (plus `gradlePluginPortal()` for the lint/format plugins).
`gradle-wrapper.jar` is the standard Gradle 9.6 wrapper (Apache-2.0), verifiable
against the official distribution.

### 1.3 APK payload hygiene

AGP by default embeds a *dependency info block* in the APK signing block: a list of
every dependency **encrypted with a Google Play public key**, readable only by
Google. That opaque blob is a known F-Droid blocker (it cannot be verified from
source and breaks reproducible-build verification), so this repo disables it in
`app/build.gradle.kts`:

```kotlin
dependenciesInfo {
    includeInApk = false
    includeInBundle = false
}
```

## 2. Anti-feature review

Reviewed against the [F-Droid anti-feature list](https://f-droid.org/docs/Anti-Features/),
based on the manifest and source at audit time. **Declared anti-features: none.**

| Anti-feature | Verdict | Reasoning |
|---|---|---|
| `Ads` | Clear | No advertising of any kind. |
| `Tracking` | Clear | No analytics/telemetry SDK; no identifiers are collected. Debug reporting is off by default, local-only, user-reviewed, and user-submitted (§2.1). |
| `NonFreeNet` | Clear | Generic IMAP/SMTP client, fully functional against free-software mail servers; the Microsoft integration is optional and user-chosen (§2.2). |
| `NonFreeAdd` | Clear | No add-ons; nothing is upsold. |
| `NonFreeDep` | Clear | Dependency audit in §1: every dependency is FOSS. |
| `NonFreeAssets` | Clear | All assets are first-party vector drawables carrying the repo's GPL SPDX headers; no bundled proprietary art, fonts, or blobs. |
| `NSFW` | Clear | N/A. |
| `UpstreamNonFree` | Clear | This repo is the upstream and is wholly GPL-3.0-or-later. |
| `KnownVuln` | Clear | No dependency with a known security vulnerability is pinned at audit time (all on current stable lines). |
| `ApplicationDebuggable` | Clear | Release builds are non-debuggable (AGP default) and R8-minified. |
| `TetheredNet` | Clear | No tethered/proprietary backend; the app talks to the user's own mail servers. |
| `NoSourceSince` | Clear | N/A — source is published. |

### 2.1 Debug reporting is not `Tracking`

The crash/debug-report pipeline (`org.libremail.reporting.*`) is designed to stay on
the right side of F-Droid's Tracking definition ("reports user activity ... without
consent"):

- **Off by default.** Nothing is captured until the user enables debug reporting.
- **Local capture only.** Reports (app/OS version, device model, stack trace, a
  non-PII settings summary, recent in-app log lines) are stored on-device.
  `DiagnosticsCollector` deliberately collects no account emails, server names,
  message content, or hardware/advertising identifiers.
- **User-initiated, reviewed submission.** A report leaves the device only when the
  user opens it, sees the full contents plus a PII disclaimer, and taps Submit
  (`ReportSubmitter` is the single egress seam).
- **No endpoint in F-Droid builds.** The ingest URL comes from
  `BuildConfig.DEBUG_REPORT_ENDPOINT`, default **empty** (settable only via the
  git-ignored `secrets.properties`). An F-Droid build therefore *cannot* transmit a
  report anywhere; the UI steers users to copy/save the report instead.

### 2.2 Outlook OAuth / Microsoft Graph is not `NonFreeNet`

`NonFreeNet` applies to apps that *promote or depend entirely on* a non-free network
service. LibreMail is a general-purpose email client: it works fully against any
IMAP/SMTP server, including self-hosted free-software stacks (Dovecot/Postfix, …),
and no Microsoft endpoint is ever contacted unless the user adds an
Outlook/Microsoft account. For transparency:

- Adding an Outlook account uses OAuth 2.0 + PKCE against
  `login.microsoftonline.com` and sends via Microsoft Graph
  (`graph.microsoft.com`), with SMTP/XOAUTH2 fallback — proprietary services, but
  the *user's own mailbox provider*, exactly like connecting to any other mail host.
- The build bundles a default Azure **public client id** (`OUTLOOK_OAUTH_CLIENT_ID`
  in `app/build.gradle.kts`). A public-client id is an identifier, not a secret or a
  key, and is overridable via `secrets.properties`. This mirrors what established
  F-Droid mail clients (K-9 Mail / Thunderbird) ship for Gmail/Outlook OAuth without
  a `NonFreeNet` flag.
- Gmail/Yahoo/iCloud accounts use plain app-password IMAP/SMTP — no proprietary
  SDK. Onboarding links to each vendor's app-password page open in the system
  browser only on an explicit tap.

Should F-Droid reviewers read the built-in Outlook convenience differently,
declaring `NonFreeNet` on the fdroiddata side is the documented fallback; nothing
in the app needs to change.

### 2.3 Android Backup (Google transport) is opt-in

`android:allowBackup="true"` is required at the manifest level, but
`LibreMailBackupAgent` enforces the runtime preference: **backup is off by
default**, and with it disabled the agent ships nothing. When the user opts in, the
allowlist in `res/xml/data_extraction_rules.xml` / `backup_rules.xml` backs up
*only* the settings DataStore — never credentials, the mail cache, or the
Keystore-sealed cache passphrase. Because Android Auto Backup can route through
Google's transport, the feature stays disabled unless explicitly chosen; F-Droid
has no anti-feature for opt-in platform backup.

### 2.4 Complete network surface

| Destination | When | Consent |
|---|---|---|
| User-configured IMAP/SMTP servers | Mail sync/send | Inherent (user adds the account) |
| `login.microsoftonline.com` | Outlook sign-in / token refresh | Only if an Outlook account is added |
| `graph.microsoft.com` (`sendMail`) | Outlook send | Only if an Outlook account is added |
| Vendor app-password help pages (Google/Yahoo/Apple) | Opened in the system browser | Explicit tap during setup |
| Remote images in HTML mail | Blocked by default | Per-user opt-in (tracking-pixel protection) |
| `DEBUG_REPORT_ENDPOINT` | Debug-report submission | Empty by default → impossible; otherwise explicit Submit tap |

No other endpoint exists in the code; there is no update checker, no push relay
(new-mail notifications are generated on-device; instant push is a direct IMAP IDLE
connection to the user's server), and no font/asset CDN.

## 3. Clean-room build verification

Verified 2026-07-01 on this branch, in a checkout containing **no
`secrets.properties`** (and no other proprietary keys — the file is optional by
design; `OUTLOOK_OAUTH_CLIENT_ID` has an in-tree default and
`DEBUG_REPORT_ENDPOINT` defaults to empty):

```
$ JAVA_HOME=<JDK 21> ./gradlew :app:assembleRelease
BUILD SUCCESSFUL
app/build/outputs/apk/release/app-release.apk   (~12.6 MiB)
```

The APK is installable: with no release keystore configured, release builds are
signed with the debug key (see `signingConfigs` in `app/build.gradle.kts`) — fine
for local testing and irrelevant to F-Droid, which builds from source and signs
with its own key. APK contents were inspected: single `classes.dex`, resources,
and the three native libraries listed in §1.1 — no bundled binaries of unknown
origin. `./gradlew :app:assembleDebug`, the unit tests, static analysis
(ktlint/detekt), and the emulator E2E suites run on every PR in CI, likewise
without any secrets configured.

## 4. Publishing checklist (for the maintainer)

1. Tag releases `v<versionName>` **and bump `versionCode`** in
   `app/build.gradle.kts` in the same commit. (At audit time tags `v0.1.0` and
   `v0.2.0` both point at versionCode 1 / versionName 0.1.0 — F-Droid's
   `UpdateCheckMode: Tags` needs the code to increase per release tag.)
2. Copy `docs/fdroid/org.libremail.app.yml` into a fork of fdroiddata as
   `metadata/org.libremail.app.yml`, run `fdroid lint org.libremail.app` and a test
   `fdroid build`, then open the merge request.
3. The store listing (title/short/full description, per-release changelogs) is read
   from `fastlane/metadata/android/en-US/` in this repo — add a
   `changelogs/<versionCode>.txt` for each release, and optionally
   `images/phoneScreenshots/`.
4. Keep this document current when dependencies or network behaviors change; if a
   future feature genuinely trips an anti-feature, declare it in the fdroiddata
   metadata rather than hiding it.
