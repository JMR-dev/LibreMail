<!-- SPDX-License-Identifier: GPL-3.0-or-later -->
# Permissions justification — merged manifest audit (issue #17)

Every permission in the **merged release manifest** (source of truth:
`app/build/intermediates/merged_manifests/release/processReleaseManifest/AndroidManifest.xml`
after `./gradlew :app:bundleRelease`; attribution from
`app/build/outputs/logs/manifest-merger-release-report.txt`), why it exists, where it is used,
and the text to paste into Play Console where a declaration is required.

## Complete merged-manifest permission list

| Permission | Declared by | Runtime prompt? | Purpose |
|---|---|---|---|
| `INTERNET` | app manifest | No | IMAP/SMTP/OAuth/Graph connections to the user's mail provider |
| `ACCESS_NETWORK_STATE` | app manifest | No | Connectivity checks so sync/WorkManager runs only when online |
| `READ_CONTACTS` | app manifest | **Yes** | On-device recipient autocomplete in the compose screen |
| `POST_NOTIFICATIONS` | app manifest | **Yes** (API 33+) | New-mail notifications + mandatory foreground-service status notification |
| `FOREGROUND_SERVICE` | app manifest | No | Prerequisite for running any foreground service (API 28+) |
| `FOREGROUND_SERVICE_DATA_SYNC` | app manifest | No | Type-specific permission for the IMAP IDLE push service (API 34+) |
| `WAKE_LOCK` | `androidx.work:work-runtime:2.11.2` | No | WorkManager keeps the CPU awake while a scheduled job (mail sync, outbox send) runs |
| `RECEIVE_BOOT_COMPLETED` | `androidx.work:work-runtime:2.11.2` | No | WorkManager reschedules pending jobs (periodic sync, queued outbox mail) after reboot |
| `org.libremail.app.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION` | `androidx.core:core:1.17.0` | No | Auto-generated app-signature permission guarding non-exported runtime receivers; not user-facing |

Nothing else. Notably **absent** (worth stating in any review exchange):

- **No `AD_ID`** — no ads or analytics SDKs at all.
- **No storage/media permissions** — attachments use the Storage Access Framework
  (`OpenMultipleDocuments` in `ui/compose/ComposeScreen.kt:88`) and a `FileProvider` for viewing
  (`AndroidManifest.xml:95`).
- **No `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`** — deliberately avoided because Play restricts
  it; the app deep-links to the system app-details screen instead
  (`push/BatteryOptimizationManager.kt`, comment cites this issue).
- No location, camera, microphone, SMS, call-log, accessibility, or `QUERY_ALL_PACKAGES`.

## `READ_CONTACTS` (Play "sensitive" permission — scrutinized, no declaration form)

- **Feature:** recipient autocomplete while composing. `contacts/ContactsRepository.kt` queries
  `ContactsContract.CommonDataKinds.Email` for at most 8 name/email matches of the typed text.
- **Data handling:** query and results are entirely **on-device** (results live in memory for
  the suggestion dropdown). Nothing from the contacts provider is stored, logged, or
  transmitted; an address reaches the network only if the user puts it on an email they send.
- **Request flow:** a dedicated, skippable **onboarding step** (`ui/onboarding/ContactsAccessScreen.kt`,
  route `ONBOARDING_CONTACTS`) requests it **once**, showing an in-context rationale up front —
  contacts are used only for on-device autocomplete and never uploaded (#127, #128). The compose
  screen no longer prompts; it only reads the current grant. If declined, recipient autocomplete can
  be enabled later from **Settings → Contacts → Recipient autocomplete** (`ui/settings/SettingsScreen.kt`),
  which re-requests in-app when possible or deep-links to the app's system settings when the
  permission is permanently denied (#129). Denial is handled gracefully throughout —
  `ContactsRepository.search` returns empty and composing works normally (manual address entry).
- **Play-Console justification text (if asked in review):**
  > LibreMail is an email client. READ_CONTACTS powers recipient autocomplete on the compose
  > screen only: the app queries the on-device contacts provider for names/email addresses
  > matching what the user typed and shows up to 8 suggestions. Contact data is processed
  > entirely on the device — it is never uploaded, stored outside the suggestion list, or shared.
  > The permission is requested once, in context, from a skippable onboarding step that explains
  > the on-device autocomplete use before asking (and can be enabled later from Settings); the
  > feature degrades gracefully if denied.

## `POST_NOTIFICATIONS`

- **Features:** (1) per-account new-mail notifications, generated on-device from synced mail —
  `notifications/MailNotifier.kt` (no push/cloud-messaging service; lock-screen content
  redacted via `VISIBILITY_PRIVATE`); (2) the persistent low-importance status notification
  Android requires while the IMAP IDLE foreground service runs (`push/IdleService.kt:120`).
- **Request flow:** once, when the onboarding welcome screen appears, API 33+ only
  (`ui/onboarding/OnboardingWelcomeScreen.kt` `NotificationPermissionEffect`, scoped to that
  screen's composition so the system dialog shows onboarding context instead of racing the
  cold-start/splash transition — #151). If denied, `MailNotifier.notifyNewMail` no-ops (permission
  re-checked before every post, `MailNotifier.kt:134`); mail sync itself is unaffected.
- **Play-Console justification text (if asked):**
  > Notifies the user of newly received email (per-account channels, generated on the device
  > from the user's own mailbox — no push service) and shows the persistent status notification
  > Android requires for the optional foreground IMAP IDLE connection. Requested once, when the
  > onboarding welcome screen appears; all app functions except notifications work if declined.

## `FOREGROUND_SERVICE_DATA_SYNC` (requires the Play Console FGS declaration)

Play Console → App content → **Foreground service permissions** asks for the type's use case
and a demo video. Facts to declare, all verifiable in `push/IdleService.kt`:

- **What runs:** one foreground service (`.push.IdleService`, manifest
  `foregroundServiceType="dataSync"`, `AndroidManifest.xml:89`) holding a long-lived IMAP IDLE
  (RFC 2177) connection per configured account so the user's own mail server can push new mail
  instantly. On server activity it triggers a normal sync into the local cache and a new-mail
  notification.
- **Why a foreground service:** IMAP IDLE requires a continuously open TCP connection that
  survives while the app is backgrounded; it cannot be modeled as deferrable work. WorkManager
  **is** used for everything deferrable (periodic sync, outbox sending) — the service exists
  only for the always-connected push case. There is no push-notification alternative (FCM)
  because plain IMAP servers cannot address one, and the app deliberately uses no Google cloud
  services.
- **User control / lifecycle:** starts only when at least one account exists **and** the "push
  mail" setting is enabled (`LibreMailApplication.kt:70`); the setting is a visible toggle in
  Settings (`ui/settings/SettingsScreen.kt:147`); the service stops reactively when the toggle
  turns off or the last account is removed, and shows a persistent low-importance status
  notification while running (`IdleService.startAsForeground`).
- **Declaration text to paste:**
  > LibreMail is an email client. The dataSync foreground service maintains a long-lived IMAP
  > IDLE (RFC 2177) connection to the user's own mail server so new mail arrives instantly.
  > IMAP has no out-of-band push channel (such as FCM), so real-time delivery requires keeping
  > this user-visible connection open; all deferrable transfers (periodic sync, sending queued
  > mail) already use WorkManager instead. The service runs only while the user has an account
  > configured and the "push mail" setting enabled, displays a persistent status notification,
  > and stops immediately when the user disables the setting or removes their last account.
- **Demo video (human step):** screen-record: Settings → toggle "push mail" on → the status
  notification appears → send the account a mail from elsewhere → the new-mail notification
  arrives with the app backgrounded → toggle off → status notification disappears.
- **Note:** the app targets SDK 37, so the API-34 requirement to declare a type for every FGS
  is in force; `FOREGROUND_SERVICE` plus the typed permission are both declared and the service
  calls `ServiceCompat.startForeground(..., FOREGROUND_SERVICE_TYPE_DATA_SYNC)`
  (`IdleService.kt:136`).

## Library-injected permissions (`WAKE_LOCK`, `RECEIVE_BOOT_COMPLETED`)

Injected by `androidx.work:work-runtime:2.11.2` for its own machinery: holding a partial wake
lock while an enqueued job executes, and re-registering scheduled jobs after a reboot. LibreMail
uses WorkManager for periodic mail sync (`data/sync/` workers), reliable outbox sending
(`SendWorker.kt`), and the opt-in debug-report upload (`ReportUploadWorker.kt`, dormant —
endpoint unconfigured). Neither permission needs a Play declaration; keep this attribution handy
for review questions.
