<!-- SPDX-License-Identifier: GPL-3.0-or-later -->
# LibreMail

A free and open-source email client for Android, built with Kotlin, Jetpack
Compose and Material 3 (Material You). LibreMail aims for a friendly default
experience with power-user features tucked under an **Advanced Settings** group.

> Status: **in development.** Material You shell; **account setup** (Gmail OAuth via
> AppAuth/PKCE and generic IMAP/SMTP, with a live connection test and Keystore-
> encrypted credentials); **IMAP receive** — background sync (WorkManager) into a local
> Room cache with pull-to-refresh; and **reading** — message bodies fetched on open and
> rendered in a hardened WebView (JavaScript off, remote images blocked by default),
> with mark-read, star, and delete. Composing and sending land next.

## Features (target MVP)

- Send and receive email with **Gmail** (OAuth 2.0) and **any IMAP/SMTP** provider.
- Material You dynamic theming, light/dark, edge-to-edge.
- Clean compose screen with phone/account contacts integration.
- Modern security: OAuth 2.0 Authorization Code + PKCE, no stored passwords for Gmail.

## Tech stack

| Area | Choice |
|---|---|
| Language / UI | Kotlin, Jetpack Compose, Material 3 |
| Min / target SDK | 33 (Android 13) / 37 (Android 17) |
| Build | Gradle 9.6, AGP 9.2, Kotlin 2.4.0 (KSP, no KAPT) |
| DI | Hilt |
| Local cache | Room (single source of truth) + DataStore |
| Async / sync | Coroutines + Flow, WorkManager |
| Email transport | Jakarta / Angus Mail (IMAP + SMTP, XOAUTH2) |
| OAuth | AppAuth-Android |

## Building

### Prerequisites

- **Android Studio** (latest) or the command-line Android SDK.
- **JDK 17–21** for the Gradle daemon. AGP 9.2 does not yet support JDK 25, so if
  your `JAVA_HOME` points at JDK 25, run Gradle with a 17–21 JDK (Android Studio's
  bundled JBR is fine; from the CLI, set `JAVA_HOME` to a JDK 21 install).
- **Android SDK Platform 37** (`platforms;android-37.0`) and **Build-Tools 37**.

```bash
# Install the required SDK packages (accept licenses when prompted):
sdkmanager "platforms;android-37.0" "build-tools;37.0.0"

# Build, test and lint the debug variant:
./gradlew :app:assembleDebug
./gradlew :app:testDebugUnitTest
./gradlew :app:lintDebug

# Install on a connected device / emulator:
./gradlew :app:installDebug
```

`local.properties` (git-ignored) must point `sdk.dir` at your Android SDK; Android
Studio creates it automatically.

## Gmail account setup (OAuth client)

Gmail IMAP/SMTP requires the restricted `https://mail.google.com/` scope. While the
app is unpublished you can use it in **Testing** mode with up to 100 test users and
no security assessment; a public Play Store release later requires a Google CASA
assessment for the restricted scope.

1. In the [Google Cloud Console](https://console.cloud.google.com/), create a
   project (e.g. *LibreMail*).
2. **APIs & Services → Library →** enable the **Gmail API**.
3. **OAuth consent screen:** user type *External*; add the scope
   `https://mail.google.com/`; under **Test users**, add your Google address.
   Leave the app in **Testing**.
4. **Credentials → Create credentials → OAuth client ID → Android.** Use package
   name `org.libremail.app` and your debug keystore SHA-1:
   ```bash
   keytool -list -v -keystore "$HOME/.android/debug.keystore" \
     -alias androiddebugkey -storepass android -keypass android
   ```
5. Copy `secrets.properties.example` to `secrets.properties` (git-ignored) and set
   `GMAIL_OAUTH_CLIENT_ID` to your client ID. The build injects it via `BuildConfig`.

## Architecture

Offline-first, unidirectional, layered:

```
ui/        Compose screens + ViewModels (MVVM), Navigation Compose, Material You theme
domain/    Models + repository interfaces
data/      Room (entities, DAOs, database) + repository implementation (source of truth)
di/        Hilt modules
```

The UI observes Room via `Flow`; later increments add a sync engine (Angus Mail
over IMAP/SMTP) that writes into Room, and an auth layer (AppAuth + an Android
Keystore-backed credential store).

## License

LibreMail is licensed under the **GNU General Public License v3.0** — see
[`LICENSE`](LICENSE). SPDX identifier: `GPL-3.0-or-later`.
