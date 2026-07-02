<!-- SPDX-License-Identifier: GPL-3.0-or-later -->
# Google Play technical compliance & console checklist (issue #17)

Verified 2026-07-01 against this repository (commit on `main` at time of writing). Companion
docs: [`PRIVACY.md`](../PRIVACY.md), [`play-data-safety.md`](play-data-safety.md),
[`play-permissions.md`](play-permissions.md).

## 1. Target API level — PASS

| Fact | Value | Source |
|---|---|---|
| `targetSdk` | **37** | `app/build.gradle.kts:52` |
| `compileSdk` | 37 | `app/build.gradle.kts:46` |
| `minSdk` | 29 (Android 10) | `app/build.gradle.kts:51` |
| Play requirement (new apps & updates, phones/tablets) | target API **35** (Android 15)+ since 2025-08-31 | [Play target-API policy](https://support.google.com/googleplay/android-developer/answer/11926878) |

Target 37 exceeds the requirement with two versions of headroom; no action needed. When Google
announces the 2026 deadline (expected: API 36 for the Aug 2026 window), 37 still passes.

## 2. 16 KB page-size support — PASS (verified empirically)

Play requires new apps and updates targeting Android 15+ to support 16 KB memory page sizes on
64-bit devices since 2025-11-01 ([Android developers blog](https://android-developers.googleblog.com/2025/05/prepare-play-apps-for-devices-with-16kb-page-size.html)).
Compliance = every `PT_LOAD` segment of every packaged 64-bit `.so` aligned to ≥ 0x4000 (16384).

The release AAB packages exactly three native libraries. All were extracted from
`app-release.aab` and their ELF program headers checked (same check as AOSP's
`check_elf_alignment.sh`); **every one reports `p_align = 0x4000` on every ABI**:

| Library | From dependency | arm64-v8a | x86_64 | armeabi-v7a / x86 (32-bit, not gated) |
|---|---|---|---|---|
| `libsqlcipher.so` | `net.zetetic:sqlcipher-android:4.16.0` | 0x4000 OK | 0x4000 OK | 0x4000 OK |
| `libandroidx.graphics.path.so` | Compose (BOM `2026.06.00`) | 0x4000 OK | 0x4000 OK | 0x4000 OK |
| `libdatastore_shared_counter.so` | `androidx.datastore:1.2.1` | 0x4000 OK | 0x4000 OK | 0x4000 OK |

SQLCipher — the dependency called out in issue #17 — has shipped 16 KB-aligned binaries since
well before 4.16.0, and the pinned version is confirmed aligned above. AGP 9.2 also emits 16
KB-zip-aligned uncompressed libraries by default (AGP ≥ 8.5.1 behavior), and Play regenerates
delivery APKs from the AAB anyway. Re-verify after any bump of `sqlcipher`, `datastore`, or
`composeBom` in `gradle/libs.versions.toml`: Play Console → **App bundle explorer** shows a
16 KB compliance verdict per upload.

## 3. App Bundle (AAB) — PASS, signing is a human step

- `./gradlew :app:bundleRelease` succeeds and produces
  `app/build/outputs/bundle/release/app-release.aab` (~10.4 MB, R8-minified). Verified
  2026-07-01 with JDK 21.
- **Signing:** without `secrets.properties` the release build intentionally falls back to the
  **debug** keystore (`app/build.gradle.kts:86` — installable locally, not publishable). For
  Play the maintainer must create an upload keystore, set `RELEASE_STORE_FILE` /
  `RELEASE_STORE_PASSWORD` / `RELEASE_KEY_ALIAS` / `RELEASE_KEY_PASSWORD` in
  `secrets.properties`, rebuild, and enroll in **Play App Signing** on first upload (Play holds
  the app signing key; the local key becomes the upload key).
- `versionCode 1` / `versionName "0.1.0"` (`app/build.gradle.kts:53`) — bump per release.

## 4. OAuth / CASA — no Google verification applies

Verified in source, 2026-07-01:

- **Gmail onboarding uses an app password over IMAP/SMTP, not OAuth.** The Gmail preset
  (`domain/model/MailProvider.kt:37`) is plain `imap.gmail.com:993` / `smtp.gmail.com:587`
  authenticating with a user-created app password; the only Google URL in the app is the
  `myaccount.google.com/apppasswords` help link opened in the browser. There is **no Google
  OAuth client, no Google sign-in flow, and no Gmail API scope anywhere in the code** — so the
  Google restricted-scope verification and **CASA security assessment do not apply** to
  LibreMail. (This is deliberate — issue #9; do not "fix" Gmail back to OAuth.)
- **Outlook OAuth is Microsoft-side only.** `auth/OutlookAuthManager.kt` uses AppAuth (PKCE,
  public client) against `login.microsoftonline.com` with Microsoft Graph
  (`Mail.Send`) and Exchange Online (`IMAP.AccessAsUser.All`, `SMTP.Send`) scopes. Verification
  of that client is governed by **Microsoft's** app-registration/publisher rules in Azure —
  nothing on the Google side. Google Play itself imposes no OAuth review; only the data-safety
  and permissions declarations above cover it.
- README follow-up: tracked as issue **#20** (a fuller README pass); `README.md`'s privacy
  section now links `PRIVACY.md`.

## 5. Console checklist (human steps, in order)

Everything below happens in Play Console and cannot be done from the repo. Drafted answers are
ready to paste.

1. **Developer account** — one-time registration + identity verification.
2. **Create app** — name *LibreMail*, default language, **App** (not game), **Free**.
   Free-to-paid can never be toggled later; LibreMail is GPL and free.
3. **Store listing** (assets required):
   - App icon **512×512 PNG** (≤1 MB); feature graphic **1024×500**; **2–8 phone screenshots**
     (16:9 or 9:16, 320–3840 px; onboarding, inbox, reader, compose, settings are good
     candidates); optional 7"/10" tablet screenshots.
   - Short description (≤80 chars), draft:
     > Open-source email for Outlook, Gmail, Yahoo, iCloud and any IMAP provider.
   - Full description (≤4000 chars), draft:
     > LibreMail is a free and open-source (GPL-3.0) email client with a friendly Material You
     > design. Add Outlook/Hotmail (OAuth sign-in), Gmail, Yahoo, iCloud (app password), or any
     > IMAP/SMTP provider; read, search, and manage your mail offline-first; compose with rich
     > text, signatures, attachments, and contact autocomplete; get instant new-mail
     > notifications via IMAP IDLE push — no tracking, no ads, no analytics, and your mail
     > never touches our servers because we don't have any. Optional extras: encrypted local
     > cache (SQLCipher), unified inbox for multiple accounts, and full mail-history backfill
     > with a retention cap.
   - Category **Communication**; contact email (maintainer's); privacy policy URL
     `https://github.com/JMR-dev/LibreMail/blob/main/PRIVACY.md`.
4. **App content declarations:**
   - **Privacy policy** — URL above.
   - **Ads** — *No, my app does not contain ads* (no ad SDK; see dependency audit in
     [`play-data-safety.md`](play-data-safety.md)).
   - **App access** — reviewers need a mail account to exercise the app. Provide either
     "All functionality is available without special access" plus a note that any IMAP account
     works, or (safer) supply a disposable test account (e.g. a throwaway IMAP mailbox) under
     *Special access instructions*. Do **not** hand over a personal account.
   - **Content rating (IARC questionnaire)** — draft answers: email/communication app; category
     **Utility / Communication**; violence/sex/language/drugs/gambling: **No** to all;
     user interaction: **Yes** (users exchange email — expect an "Interactive elements: Users
     Interact" notice); shares user-provided location: **No**; digital purchases: **No**.
     Expected rating: **Everyone / PEGI 3** with the Users-Interact disclosure.
   - **Target audience** — **13 and over** (requires an email account; not directed at
     children — do not select under-13, which triggers Families policy).
   - **News app** — No. **COVID-19 app** — No. **Government app** — No.
   - **Financial features** — None. **Health apps** — Not a health app.
   - **Data safety** — answers and evidence in [`play-data-safety.md`](play-data-safety.md).
   - **Foreground service permissions** (`FOREGROUND_SERVICE_DATA_SYNC`) — declaration text and
     demo-video script in [`play-permissions.md`](play-permissions.md).
   - **Account deletion** — Play's deletion-URL requirement applies to apps that let users
     *create an account with the developer*. LibreMail creates no such accounts (users connect
     their own third-party mailboxes), so answer the "App access/account creation" question
     with **no account creation** and the deletion section does not apply. In-app truth, if a
     free-text answer is wanted:
     > LibreMail has no user accounts of its own and stores data only on the device. Removing
     > an account inside the app deletes its saved credentials and its locally cached
     > messages, folders, and settings (`AccountRepositoryImpl.deleteAccount`); uninstalling
     > the app removes all app data. The user's mailbox at their email provider is unaffected.
5. **Upload** the properly signed release AAB to **Internal testing** first; check **App bundle
   explorer** (16 KB verdict) and the **pre-launch report** (automated crawl on real devices;
   supply the test-account credentials so it can get past onboarding).
6. **Countries/regions**, pricing (Free), then promote Internal → Closed/Open testing →
   Production. Note: new personal developer accounts must run a closed test (12 testers /
   14 days) before production access.

## 6. Findings for the maintainer (repo-side, discovered during verification)

1. **"Push mail" is on by default, not opt-in.** `SettingsRepository.kt:41` defaults
   `pushIdle = true`, so the dataSync foreground service starts as soon as the first account is
   added. The manifest comment (`AndroidManifest.xml:88` — "opt-in via Advanced Settings") and
   README wording say opt-in. Either flip the default to `false` or fix the comments; the Play
   FGS declaration drafted here describes the **actual** behavior (default-on, user-visible
   toggle, persistent notification), which is acceptable to declare but must stay truthful.
2. **README tech-stack table says min SDK 33**; the build uses `minSdk 29`
   (`app/build.gradle.kts:51`). Fix with the #20 README pass.
3. **README advertises an "app lock" (biometric/device-credential)** that does not exist in the
   code yet (no biometric API usage anywhere in `app/src/main`). `PRIVACY.md` deliberately does
   not claim it; remove or de-scope the README claim until implemented (#20), and update
   `PRIVACY.md` when it ships.
4. **Release signing falls back to the debug key** without `secrets.properties` — fine for CI,
   but the Play upload must be built with the real upload keystore (section 3).
