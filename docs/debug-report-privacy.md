<!-- SPDX-License-Identifier: GPL-3.0-or-later -->
# Debug report privacy posture

Data flow and privacy design for LibreMail's opt-in debug reporting, covering the **client (app) side**
that lives in this repository. It is referenced by the F-Droid audit (#16) and the README (#20), and is
the client half of the ingest pipeline epic (#11); the ingest server (#34) and the weekly publish job
(#35) are **separate infrastructure**, not part of this repo.

> TL;DR: nothing is ever sent unless the user taps **Submit** on a report they have read in full, and
> even then the payload is **anonymized** and **encrypted to the maintainer's public key on the device**
> before it leaves. A stock build (and every F-Droid build) is configured to send **nothing at all**.

## 1. What a report contains

A `DebugReport` is assembled by `DiagnosticsCollector` from **coarse, non-identifying** fields only:

- App version name/code; Android release + SDK int; device manufacturer + model.
- A small settings summary (feature toggles and enum names — e.g. `dynamicColor=true`, `fetchPolicy=ALL`).
- A **bucketed** account summary: one `"<provider> (<authType>)"` entry per account (e.g. `Gmail
  (PASSWORD_IMAP)`) — never an email address, username, or server hostname (custom hosts collapse to
  `Other`).
- For crashes, a stack trace **scrubbed at capture time** by `StackTraceScrubber`: exception class names
  and stack frames are kept; the free-text exception *message* (the only place a host or username
  appears) is dropped, and any residual `user@host` / `host:port` token is redacted.
- The recent in-app log buffer (`RingLogBuffer`), which is governed by the `AppLog` **"no PII" contract**
  (a detekt guard forbids raw `android.util.Log`; throwables are auto-scrubbed).
- Two user-supplied fields: a free-text **comment**, and an optional **reply-to email** the user
  typed when submitting (issue #159).

Message bodies, headers, attachments, credentials, and tokens are **never** collected.

## 2. Opt-in gating (strictly user-initiated)

Reporting is **off by default and cannot phone home** without deliberate action:

- A report is only transmitted when the user taps **Submit** on the review screen after reading the full
  payload verbatim (issue #33). There is no background or automatic upload.
- The transmit path additionally requires build configuration that a stock/F-Droid build does **not**
  ship: both `DEBUG_REPORT_ENDPOINT` and `DEBUG_REPORT_PUBLIC_KEY` (below) must be set. With either
  empty, `ReportSubmitter.isEnabled` is false / the worker fails closed, and the UI steers the user to
  **Copy** or **Save to file** instead.

## 3. Anonymization pass (`ReportAnonymizer`)

Immediately before upload, a best-effort redaction pass runs as defence-in-depth over the two surfaces
that can still carry PII the earlier stages never saw:

- **User comment** (free text) and **log lines** are scanned and redacted for: email addresses,
  `host:port` tokens, bare IPv4 addresses, JWT-shaped tokens, `Bearer` authorization values, and
  `key=value` credentials (`password=…`, `access_token=…`, …). The stack trace is re-run through
  `StackTraceScrubber`.
- Redaction is intentionally **conservative-but-lossy** (the ticket's "best-effort" bar): it prefers to
  over-redact a false positive rather than leak a real secret. If any PII *shape* survives, the worker
  logs a PII-free warning (it never blocks the user's submission).
- **Exception — the reply-to email is retained by design.** `userEmail` is consented, purposeful data
  the user chose to give so the maintainer can follow up (issue #159); it is the one identifier that
  intentionally leaves the device, and only when the user fills it in. The ingest server (#34) is where
  reply-to is separated from the report body if desired.

## 4. Encryption scheme and key custody

The report is **end-to-end encrypted on the device** before upload (`ReportPayloadEncryptor`), so the
ingest Worker and the R2 bucket only ever see opaque ciphertext — "not readable without the documented
key" holds against the transport and storage tiers, not just at rest.

**Scheme — hybrid (envelope) encryption, JCA only (no third-party or Google/GMS crypto):**

1. Generate a fresh random **AES-256-GCM** content key + 96-bit IV; encrypt the report payload with it.
2. Wrap the content key with the recipient's RSA public key using **RSA-OAEP (SHA-256, MGF1-SHA-256)**.
3. Emit a self-describing JSON envelope — the exact wire body of the POST:

   ```json
   { "v": 1, "alg": "RSA-OAEP-SHA256+A256GCM",
     "ek": "<base64 RSA-OAEP-wrapped AES key>",
     "iv": "<base64 12-byte GCM IV>",
     "ct": "<base64 AES-GCM ciphertext||tag>" }
   ```

**Key custody:**

- The **public** key ships in the build via `BuildConfig.DEBUG_REPORT_PUBLIC_KEY` (a single-line Base64
  X.509/SPKI RSA key). A public key is not a secret, so nothing sensitive is embedded in the APK — this
  is F-Droid-safe.
- The matching **private** key is held by the maintainer, **off-device** (e.g. Cloudflare Secret Manager
  per #11), and never appears in this repository or the app. Only that private key can decrypt a report.
- **Fail-closed:** if no public key is configured, or if sealing throws, `ReportUploadWorker` returns a
  failure and sends nothing — it never transmits an unencrypted report. This is distinct from the
  on-device at-rest `ReportEncryption` (#369), whose symmetric Android-Keystore key can never leave the
  phone and so could never decrypt a report a remote reviewer must read; that is why the upload path is
  asymmetric.

## 5. Why R2 credentials are NOT in the app

Uploading to Cloudflare R2 (S3-compatible) requires write credentials. Embedding those in a FOSS,
publicly-built APK would expose them to every user and violate both F-Droid's rules and #11/#34's
"secrets stored in Cloudflare, never in the app". So the app does **not** hold R2/S3 credentials or sign
requests: it performs a single authenticated-by-nothing **HTTPS POST of the encrypted envelope** to the
ingest Worker's endpoint, and the Worker (server-side infra, #34) holds the R2 credentials and writes
the object. The client's only configuration is a URL and a public key.

## 6. Configuration summary

| Build config (`secrets.properties`, git-ignored) | Default | Effect when empty |
|---|---|---|
| `DEBUG_REPORT_ENDPOINT` | empty | No submit path; UI offers Copy/Save only. |
| `DEBUG_REPORT_PUBLIC_KEY` | empty | Worker fails closed; nothing is ever uploaded. |

See `secrets.properties.example` for how to generate the key pair and populate these.
