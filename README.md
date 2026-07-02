<!-- SPDX-License-Identifier: GPL-3.0-or-later -->
# LibreMail

A free and open-source email client for Android, built with Kotlin, Jetpack
Compose and Material 3 (Material You). LibreMail aims for a friendly default
experience with power-user features tucked under an **Advanced Settings** group.

> Status: **in development.** Material You shell; **onboarding** — a first-run flow from a
> welcome screen through a vendor picker (Outlook/Hotmail, Gmail, Yahoo, iCloud, or Other) and
> per-vendor setup to your first account's inbox; **account setup** — Outlook/Microsoft via
> OAuth 2.0 (AppAuth/PKCE), Gmail/Yahoo/iCloud via app password, and generic IMAP/SMTP, all
> with a live connection test and Keystore-encrypted credentials; **IMAP receive** — background
> sync (WorkManager) into a local Room cache with pull-to-refresh, backfilling your **entire**
> mail history (resumable) with an optional device-only retention cap; **reading** — message
> bodies fetched on open and rendered in a hardened WebView (JavaScript off, remote images
> blocked by default), with mark-read, star, and delete; **composing** — a rich-text HTML
> editor with a formatting toolbar and per-account signatures that sends
> `multipart/alternative` (HTML with a plaintext fallback) through a reliable background
> **outbox** (WorkManager-queued and retried, with a viewable outbox folder), plus
> device-contacts autocomplete, reply, and **drafts**; **on-device new-mail notifications** (no
> push service) with persisted settings; **instant push** via a foreground **IMAP IDLE**
> service; **attachments** — downloaded on demand and opened in a system viewer, and attach
> files when composing; **multiple accounts** — a unified inbox with per-account filtering;
> **search** across cached mail and the server (IMAP SEARCH); Outlook/Microsoft send via
> Microsoft **Graph** with an SMTP/XOAUTH2 fallback; an opt-in **app lock**
> (biometric/device-credential) that binds the encrypted cache key to your unlock; **mailto:**
> link handling with optional default-mail-app registration; and opt-in, F-Droid-safe **debug
> reporting** — local crash/error capture that you review (with a PII disclaimer) and submit
> only on an explicit action.

## Features (target MVP)

- Send and receive email with **Outlook/Microsoft** (OAuth 2.0), **Gmail, Yahoo and iCloud** (app password), and **any other IMAP/SMTP** provider.
- Guided first-run onboarding: welcome → vendor picker → per-vendor setup → your inbox.
- Material You dynamic theming, light/dark, edge-to-edge.
- Rich-text compose with a formatting toolbar, per-account signatures, and phone/account contacts integration.
- Offline-first: a local Room cache with full-history backfill and an optional device-only retention limit.
- Modern, opt-in security: OAuth 2.0 (Authorization Code + PKCE) for Outlook, Keystore-encrypted credentials, optional SQLCipher cache encryption, and a biometric/device-credential app lock.

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

## Accounts and onboarding

On first launch LibreMail runs a short onboarding flow: a welcome screen, a **vendor picker**
(Outlook/Hotmail, Gmail, Yahoo, iCloud, or **Other**), per-vendor setup, and an "add another
account?" prompt before it drops you on your first account's inbox. You can add more accounts
later from settings; a unified inbox merges them with per-account filtering.

LibreMail supports three kinds of account:

- **Outlook / Hotmail** — signs in with **OAuth 2.0** through Microsoft (AppAuth); no password
  is stored. See [Outlook / Microsoft account setup](#outlook--microsoft-account-setup-oauth-client)
  below.
- **Gmail, Yahoo and iCloud** — preconfigured IMAP/SMTP that authenticate with a provider
  **app password** (not your normal account password), preferring STARTTLS where the provider
  supports it. Onboarding links you to each vendor's app-password page. **Gmail requires
  2-Step Verification to be enabled** before Google will issue an app password.
- **Other** — a manual IMAP/SMTP form (host, port, security, and credentials) for any other
  provider.

App passwords and OAuth tokens are held in a credential store encrypted with the Android
Keystore, and every account runs a live connection test before it is saved.

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

## Privacy and data flow

LibreMail is offline-first: your mail lives in a local cache, and by default network traffic
goes only to your mail providers (IMAP/SMTP, plus Microsoft's OAuth and Graph endpoints for
Outlook). There is no analytics SDK and no always-on telemetry. The full privacy policy lives in
[`PRIVACY.md`](PRIVACY.md); Google Play compliance notes (data-safety mapping, permissions
justification) are under [`docs/`](docs/). Because Gmail uses an app password (no Google OAuth
scopes), no Google restricted-scope verification or CASA assessment applies; Outlook's OAuth
client is governed by Microsoft's Azure rules. The privacy-sensitive extras are all **opt-in**:

- **Cache encryption** — the Room cache can be encrypted at rest with **SQLCipher**. With the
  optional **app lock** (biometric or device credential) enabled, the cache key is bound to
  your authentication, so the database is only decrypted after you unlock the app.
- **Debug reporting** — **off by default.** When enabled, crashes and errors are captured
  **locally**; you review the full report — shown with a plain-language **PII disclaimer** —
  and it is sent only when you explicitly submit it, to a configurable (optional) endpoint.
  There is no hosted crash pipeline collecting reports in the background.
- **Android Backup** — **off by default.** When you turn it on, only safe app settings are
  backed up; the encrypted-database key, account credentials, and the mail cache are
  **excluded**. Because Android's backup transport can route data through Google, it stays
  disabled unless you opt in — the kind of optional behavior F-Droid lists as an anti-feature.

## Architecture

Offline-first, unidirectional, layered:

```
ui/        Compose screens + ViewModels (MVVM), Navigation Compose, Material You theme
domain/    Models + repository interfaces
data/      Room (entities, DAOs, database) + repository implementation (source of truth)
di/        Hilt modules
```

The UI observes Room via `Flow`; a sync engine (Angus Mail over IMAP/SMTP, plus Microsoft
Graph for Outlook send) writes into Room, and an auth layer (AppAuth for OAuth and an Android
Keystore-backed credential store for app passwords) handles sign-in.

## F-Droid

LibreMail is built to meet F-Droid's inclusion criteria: every dependency is
FOSS-licensed, there are no Google Play Services / Firebase / proprietary SDKs, the
build needs no `secrets.properties`, and there are **no anti-features to declare**
(the privacy-sensitive extras above are all opt-in). The full dependency license
audit, anti-feature review, and clean-room build verification live in
[`docs/fdroid-compliance.md`](docs/fdroid-compliance.md); the store listing is under
[`fastlane/metadata/android/`](fastlane/metadata/android/en-US), and
[`docs/fdroid/org.libremail.app.yml`](docs/fdroid/org.libremail.app.yml) is the
template for the eventual fdroiddata build recipe.

## License

LibreMail is licensed under the **GNU General Public License v3.0** — see
[`LICENSE`](LICENSE). SPDX identifier: `GPL-3.0-or-later`.
