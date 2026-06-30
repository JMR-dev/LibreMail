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
> with mark-read, star, and delete; and **composing** — a compose screen with device-
> contacts autocomplete that sends via a reliable background **outbox** (WorkManager-queued
> and retried, with a viewable outbox folder), plus reply and **drafts** saved for
> later; **on-device new-mail
> notifications** (no push service) with persisted settings; **instant push** via a
> foreground **IMAP IDLE** service; **attachments** — downloaded on demand and opened in a
> system viewer, and attach files when composing; **multiple accounts** — a unified inbox
> with per-account filtering; and
> **search** across cached mail and the server (IMAP SEARCH); and **Outlook/Microsoft**
> accounts (OAuth 2.0 sign-in, IMAP receive + Microsoft Graph send, SMTP/XOAUTH2 fallback).

## Features (target MVP)

- Send and receive email with **Gmail** and **Outlook/Microsoft** (OAuth 2.0) and **any IMAP/SMTP** provider.
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

## Outlook / Microsoft account setup (OAuth client)

Outlook uses the Microsoft identity platform with OAuth 2.0 + PKCE (no client secret). Send
goes through Microsoft **Graph** (`sendMail`, their preferred API) with SMTP/XOAUTH2 as a
fallback; receive is **IMAP**. Graph and Exchange Online are separate resources, so one
consent grants every scope and per-resource access tokens are minted from the one refresh
token. A working client ID ships with the build; to use your own Azure app registration:

1. [Azure portal](https://portal.azure.com/) → **App registrations → New registration.**
   Supported account types: *Accounts in any organizational directory and personal Microsoft
   accounts*.
2. **Authentication → Add a platform → Mobile and desktop applications**; add the redirect
   URI `org.libremail.outlook://oauth2redirect` and enable **Allow public client flows**.
3. **API permissions** (delegated): **Microsoft Graph → `Mail.Send`** (primary send), plus
   **Office 365 Exchange Online → `IMAP.AccessAsUser.All` and `SMTP.Send`** (receive + SMTP
   fallback). `openid`/`email`/`offline_access` come from OIDC.
4. Copy the **Application (client) ID** into `secrets.properties` as
   `OUTLOOK_OAUTH_CLIENT_ID` (it overrides the built-in default).

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
