<!-- SPDX-License-Identifier: GPL-3.0-or-later -->
# LibreMail Privacy Policy

**Effective date: 2026-07-01** · Applies to the LibreMail Android app (`org.libremail.app`).

LibreMail is a free and open-source (GPL-3.0-or-later) email client. This policy describes what
the app does with your data. Because the source code is public, every statement here can be
verified against the code at <https://github.com/JMR-dev/LibreMail>.

## Summary

- **We run no servers and receive no data from you.** The LibreMail project has no backend: the
  app talks only to the email provider(s) *you* configure (e.g. your Gmail, Outlook, Yahoo,
  iCloud, or self-hosted IMAP/SMTP server) and, for Outlook accounts, to Microsoft's sign-in and
  Graph endpoints.
- **Your mail stays on your device.** Messages are cached locally so the app works offline; the
  cache can optionally be encrypted at rest.
- **No ads, no analytics, no tracking.** The app contains no advertising, analytics, or tracking
  SDK of any kind, and no Google Play Services or Firebase dependency.
- **Nothing is sent to the developers** — including crash reports, which are strictly opt-in,
  stored locally, shown to you for review, and (in this build) cannot be uploaded at all because
  no ingest endpoint is configured.

## What the app stores on your device

All of the following lives in the app's private storage on your device only:

- **Account settings** — your email address, display name, and server host/port/security
  settings for each account you add.
- **Credentials** — your per-account app password or OAuth tokens, encrypted with a hardware-
  backed key in the Android Keystore before being written to storage.
- **Mail cache** — headers, message bodies, and folder state, in a local database so your mail is
  available offline. You can optionally enable **cache encryption** (SQLCipher) in Settings; the
  database key is random, never leaves the device, and is itself sealed by the Android Keystore.
- **Attachments** you download or attach, in the app's cache directory (Android may clear this
  automatically to reclaim space).
- **Preferences** — theme, notification, sync, and privacy toggles.
- **Debug reports** — only if a crash occurs or you ask the app to capture one; see
  [Diagnostics](#diagnostics-and-debug-reports).

Uninstalling the app, or clearing its storage in Android settings, deletes all of the above.

## What leaves your device

The app makes network connections **only** to servers that operate your email service:

- **Your mail servers** — the IMAP and SMTP hosts of each account you configure (for the built-in
  presets: `imap/smtp.gmail.com`, `imap/smtp.mail.yahoo.com`, `imap/smtp.mail.me.com`;
  `outlook.office.com` for Outlook). This traffic is your email itself: signing in, downloading
  your mail, sending the messages you write, and — when you use server search — your search
  query. That is the app doing its job as your email client; none of it goes to us.
- **Microsoft identity platform and Graph** (`login.microsoftonline.com`,
  `graph.microsoft.com`) — only for Outlook/Hotmail accounts, to sign you in with OAuth 2.0 and
  to send mail via Microsoft's API.
- **Remote images in emails** — blocked by default. If you enable "load remote images", the
  message viewer will fetch images from the servers referenced by the email (which can reveal
  your IP address to the sender), so it stays off unless you turn it on.

Every mail connection uses TLS (SSL/TLS or STARTTLS) with server-certificate hostname
verification; the account-setup UI does not offer an unencrypted option.

The app never transmits your data to the LibreMail project or any third party of ours. There is
no telemetry, no "phone home", and no ad or analytics traffic.

## Contacts (`READ_CONTACTS` permission)

When composing a message, LibreMail can suggest recipients from your device contacts. The app
asks for the contacts permission the first time you open the compose screen:

- Contact lookups run **entirely on the device** and return at most a handful of name/email
  matches for what you typed. Your contact list is never uploaded, copied, or synced anywhere.
- The only way a contact detail leaves the device is when *you* put an address in an email you
  send — it then appears in that email, like in any mail client.
- The permission is optional: if you deny it, autocomplete is silently disabled and everything
  else keeps working.

## Notifications (`POST_NOTIFICATIONS` permission)

Used to show new-mail notifications (per-account, with sender/subject hidden on a locked screen)
and the persistent low-priority status notification Android requires while the optional
instant-push connection is active. New-mail notifications are generated **on the device** from
your synced mail — there is no push server and no cloud messaging service involved. You can
decline the permission or disable notifications per account in system settings.

## Instant push (foreground service)

For instant mail delivery the app can hold an open IMAP IDLE connection to your mail server in a
foreground service (shown as a persistent notification). This connects only to your own mail
server, and can be turned off in Settings ("push mail"), which falls back to periodic background
sync.

## Diagnostics and debug reports

LibreMail has **no automatic crash or usage reporting**. What exists instead:

- If the app crashes, or you use "Report a problem", a report is saved **locally** on your
  device. It contains the app version, Android version, device make/model, a stack trace (for
  crashes), a short summary of non-identifying settings, and recent internal log lines — by
  design no account addresses, server names, or message content fields are collected.
- You can view the full report text (with a plain-language notice to check it for anything
  personal), copy it, share it yourself, or delete it. It is transmitted **only** if you
  explicitly tap Submit — never in the background.
- In the builds produced from this repository **no upload endpoint is configured**, so even an
  explicit Submit cannot send anything; the report simply stays on your device. If a future
  release adds an endpoint, submission will remain strictly opt-in and user-initiated, and this
  policy will be updated.

## Android Backup

Android's cloud backup is **off by default** for LibreMail. If you enable "Include settings in
Android Backup" in Settings, only your app preferences are backed up through your device's
Android Backup transport (typically Google's). Your credentials, the mail cache, and the cache
encryption key are always excluded from backups.

## Data deletion

- **Remove an account** (in the app's account settings) — deletes that account's stored
  credentials, its cached messages, folders, and per-account settings from the local database,
  and its notification channels. Copies of downloaded attachments in the app's cache directory
  are cleared by Android's normal cache management, or immediately via "Clear cache" in system
  settings.
- **Uninstall the app / clear storage** — removes all locally stored app data.
- **Your mailbox is unaffected**: mail lives with your email provider; deleting data in
  LibreMail does not delete mail from the server unless you explicitly delete messages in the
  app. We hold no copy of your data, so there is nothing for us to delete on any server.

## Children

LibreMail is a general-audience utility that requires an existing email account. It is not
directed at children, and — as described above — it collects no data from any user.

## Changes and contact

Changes to this policy are made in the public repository with full version history. Questions or
concerns: open an issue at <https://github.com/JMR-dev/LibreMail/issues>.
