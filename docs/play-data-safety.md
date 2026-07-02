<!-- SPDX-License-Identifier: GPL-3.0-or-later -->
# Google Play Data safety form — mapping (issue #17)

Fill-in guide for Play Console → **App content → Data safety**. Every answer below is grounded
in this repository's code; re-verify against source if the data flows change. Companion docs:
[`PRIVACY.md`](../PRIVACY.md) (the policy to link in the form),
[`play-permissions.md`](play-permissions.md), [`play-compliance.md`](play-compliance.md).

## How Play defines "collection", and why LibreMail declares none

Play's definition ([Play Console Help — Provide information for Google Play's Data safety
section](https://support.google.com/googleplay/android-developer/answer/10787469)): *"Collect"
means transmitting data from your app off a user's device*, with exemptions that do **not** need
to be disclosed:

1. **On-device access/processing** — data "only processed locally on the user's device and not
   sent off device".
2. **End-to-end encryption** — data unreadable by anyone other than sender and recipient.
3. **Ephemeral processing** — data held in memory and "retained for no longer than necessary to
   service a specific request in real time".

LibreMail's data flows fall under exemptions 1 and 3:

- The developer **operates no servers and receives no user data**. There is no analytics,
  crash-reporting, or ad SDK in the dependency tree (see audit below), and the only
  developer-directed channel that exists in code — opt-in debug-report upload
  (`app/src/main/kotlin/org/libremail/reporting/ReportUploadWorker.kt`) — is dead in shipped
  builds because `DEBUG_REPORT_ENDPOINT` defaults to `""` (`app/build.gradle.kts`), which makes
  the upload worker fail without transmitting.
- All other traffic is the app doing its job as the user's mail agent against **servers the
  user chose** (their own IMAP/SMTP provider; Microsoft's OAuth/Graph endpoints for the Outlook
  account type). Each transfer services a specific user request in real time (sign-in, sync,
  send, server search); the app retains nothing off-device and the developer can never access
  any of it.

The same reasoning is the established practice of comparable open-source mail clients on Play
that declare no collection. If a Play reviewer pushes back, use the conservative alternative at
the bottom of this page — it is also truthful.

## Form answers

| Form question | Answer |
|---|---|
| Does your app collect or share any of the required user data types? | **No** |
| Is all of the user data collected by your app encrypted in transit? | Not asked when "No" above; for the record: **yes**, all connections are TLS (`ImapClient.kt`, `SmtpSender.kt` set `ssl.checkserveridentity=true`; `MailSecurity.NONE` is not offered in the UI — `ManualSetupScreen.kt:211`) |
| Do you provide a way for users to request that their data is deleted? | Not asked when "No" above; see account-deletion notes in [`play-compliance.md`](play-compliance.md) |
| Privacy policy URL | `https://github.com/JMR-dev/LibreMail/blob/main/PRIVACY.md` |

Result shown on the store listing: **"No data collected"** / **"No data shared with third
parties"**.

## Category-by-category evidence

Every Play data-safety category, the truthful answer, and where the code proves it:

| Play category | Collected? | Shared? | Evidence in code |
|---|---|---|---|
| Personal info → Name | No | No | Account display name stored in local Room DB only (`data/local/entity/AccountEntity` via `AccountRepositoryImpl.kt`); appears off-device only inside mail the user sends |
| Personal info → Email address | No | No | The user's own address is their mail login, sent only to their chosen provider to authenticate/send (`ImapClient.kt`, `SmtpSender.kt`, `GraphSender.kt`) — user-initiated, real-time, never to the developer |
| Personal info → User IDs | No | No | No developer-side accounts or IDs exist; OAuth tokens go only between the device and `login.microsoftonline.com` (`auth/OutlookAuthManager.kt`) |
| Financial info / Health / Location | No | No | No such APIs or permissions anywhere in the merged manifest (see [`play-permissions.md`](play-permissions.md)) |
| Messages → Emails | No | No | Mail syncs from the user's server *to* the device (`MailSyncer`), is cached locally (Room, optional SQLCipher — `di/DatabaseModule.kt`), and is transmitted only when the user sends a message to their own SMTP/Graph endpoint |
| Photos and videos / Audio files / Files and docs | No | No | Attachments are chosen via the system document picker (`ComposeScreen.kt` `OpenMultipleDocuments`, no storage permission), stored under `cacheDir` (`MailRepositoryImpl.kt:361`), and leave the device only inside mail the user sends |
| Calendar | No | No | No calendar API usage |
| Contacts | **No** | No | `contacts/ContactsRepository.kt` queries `ContactsContract` **on-device** for ≤8 autocomplete matches; results are held in memory for the compose screen. Nothing is uploaded — Play's on-device exemption applies |
| App activity (interactions, search history, installed apps) | No | No | No analytics SDK; server search sends the query string to the *user's own* IMAP server as an IMAP `SEARCH` command (user-initiated, ephemeral) |
| Web browsing | No | No | The reader WebView has JavaScript disabled and network loads blocked unless the user enables remote images (`ui/reader/HtmlBody.kt:62,98`) — and even then requests go to hosts referenced by the email, not to the developer |
| App info and performance (crash logs, diagnostics) | **No** | No | Crash/debug reports are written to local app storage only (`reporting/ReportStore.kt` → `filesDir/debug_reports`); upload requires an explicit user tap **and** a configured endpoint, and the endpoint is empty in this repo (`ReportSubmitter.isEnabled` → false) |
| Device or other IDs | No | No | No advertising ID (no `AD_ID` permission in the merged manifest), no device-ID reads; debug reports include only `Build.MANUFACTURER`/`MODEL`/OS version, and stay on device (`reporting/DiagnosticsCollector.kt`) |

### Dependency audit (no ads / analytics / tracking SDKs)

The complete runtime dependency list (`app/build.gradle.kts` + `gradle/libs.versions.toml`) is:
AndroidX (core, lifecycle, activity, navigation, webkit, Compose BOM, Room, DataStore,
WorkManager, Hilt-androidx), Dagger Hilt, kotlinx-coroutines, Eclipse Angus Mail (IMAP/SMTP),
AppAuth-Android (OAuth), and Zetetic SQLCipher. There is **no** Google Play Services, Firebase,
ad, analytics, or crash-reporting dependency, and the merged release manifest contains no
`com.google.android.gms.permission.AD_ID` permission (verified in
`app/build/intermediates/merged_manifests/release/processReleaseManifest/AndroidManifest.xml`).

## Security-practices section of the form

- **Encrypted in transit:** yes — TLS everywhere, hostname verification pinned on
  (`mail.<proto>.ssl.checkserveridentity=true` in `ImapClient.kt:480` / `SmtpSender.kt:50`).
- **Encryption at rest (optional extra credit, not a form field):** credentials are always
  encrypted with an Android Keystore key (`data/security/KeystoreCrypto.kt`,
  `CredentialStore.kt`); the mail cache can be SQLCipher-encrypted with a Keystore-sealed random
  key (`data/security/DatabaseKeyStore.kt`, opt-in, default off —
  `SettingsRepository.kt:44`).
- **Independent security review badge:** not requested (optional program).

## If anything changes, this form must change

| Future change | Data-safety impact |
|---|---|
| Configuring a real `DEBUG_REPORT_ENDPOINT` (issue #34) | Declare **App info and performance → Crash logs / Diagnostics**: collected, optional (user-initiated), not shared, encrypted in transit, user can delete (reports are deletable pre-submit) |
| Any opt-in telemetry from issues #10/#11 | Declare the specific types as collected + optional; backlog decision requires it stay strictly opt-in (F-Droid constraint) |
| Any new SDK with network access | Re-run this audit; SDKs count toward the form ("data transmitted by libraries/SDKs") |

## Conservative alternative declaration (only if Google rejects "no collection")

Declare the following, all with *Collected: yes · Optional: no · Shared: no · Processed
ephemerally: yes · Purpose: App functionality · Encrypted in transit: yes · Deletion: user can
delete data in-app (remove account)*:

- Personal info → Email address (account sign-in)
- Messages → Emails (sending/syncing the user's own mail with their provider)

Contacts, crash logs, and diagnostics remain **not collected** under any reading — they
demonstrably never leave the device in this codebase.
