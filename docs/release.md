<!-- SPDX-License-Identifier: GPL-3.0-or-later -->

# Releasing LibreMail

`.github/workflows/release.yml` turns a version tag into store-ready artifacts and
publications. Every stage that needs credentials is **gated on CI secrets**: when a
secret is missing the stage skips with a log notice instead of failing, so the pipeline
already runs end-to-end before the store accounts (issues #16/#17/#18) exist. No key,
password or token is ever committed to the repo.

```
tag push (v*) ─► fast CI gate ─► build + sign ─┬─► GitHub release (changelog + artifacts + checksums)
   or manual     (assemble, unit  AAB + APK,   ├─► Google Play   [needs signing + Play secrets]
   dispatch      tests, lint,     SHA-256,     ├─► Galaxy Store  [manual for now — see below]
                 ktlint, detekt)  changelog    └─► S3 archive    [needs ARCHIVE_S3_* secrets]
```

F-Droid needs no stage at all: it builds (and signs) packages itself from the pushed tag
and the fastlane metadata in the repo (issue #18).

## Cutting a release

1. Bump `versionCode` (must strictly increase for Play/Galaxy) and `versionName` in
   `app/build.gradle.kts`, land that on `main` through a normal PR.
2. Tag the release commit and push the tag:

   ```bash
   git tag -a v0.2.0 -m "LibreMail 0.2.0"
   git push origin v0.2.0
   ```

3. The `Release` workflow runs automatically: fast CI gate → signed build → GitHub
   release with a Conventional-Commit changelog → store publication / archiving for every
   stage whose secrets are configured. The run's summary page shows a per-stage table.

The workflow warns (but does not fail) when the tag does not match `versionName`.

### Manual runs (`workflow_dispatch`)

The workflow can also be dispatched from the Actions tab:

| Input | Default | Meaning |
| --- | --- | --- |
| `tag` | *(empty)* | Existing tag to (re-)release — e.g. to retry a failed publication. Empty = rehearse against the branch head (build only; nothing can be published without a tag). |
| `dry_run` | `true` | Build, sign and checksum only; skip the GitHub release, store publication and archiving. |
| `play_track` | `internal` | Google Play track: `internal`, `alpha`, `beta` or `production`. |
| `play_rollout_fraction` | `0.10` | Staged-rollout user fraction for the `production` track (`0 < f < 1`), or `1.0` for a full rollout. Ignored on other tracks. |

A dispatch with no `tag` (or with `dry_run: true`) is the way to exercise the pipeline
today, with or without secrets.

## Required secrets

Configure under **Settings → Secrets and variables → Actions**. All are optional — each
missing group just disables its stage (with a `::notice::` in the build job's
"Plan release" step explaining what was skipped and why).

| Secret | Stage | Format / where to get it |
| --- | --- | --- |
| `RELEASE_KEYSTORE_BASE64` | Signing | Base64 of the release keystore file: `base64 -w0 release.keystore`. See [Release signing](#release-signing). |
| `RELEASE_KEYSTORE_PASSWORD` | Signing | Keystore password. Avoid backslashes (the value passes through a Java properties file). |
| `RELEASE_KEY_ALIAS` | Signing | Key alias inside the keystore. |
| `RELEASE_KEY_PASSWORD` | Signing | Password of that key. |
| `PLAY_SERVICE_ACCOUNT_JSON` | Google Play | Entire JSON key of a Google Cloud service account with release access in the Play Console. See [Google Play](#google-play). |
| `GALAXY_SERVICE_ACCOUNT_ID` | Galaxy Store *(reserved)* | Service-account ID from Samsung Seller Portal → Assistance → API Service. |
| `GALAXY_PRIVATE_KEY` | Galaxy Store *(reserved)* | PEM private key created with that service account. |
| `GALAXY_CONTENT_ID` | Galaxy Store *(reserved)* | The app's content ID in Seller Portal. |
| `ARCHIVE_S3_BUCKET` | S3 archive | Bucket name. |
| `ARCHIVE_S3_ACCESS_KEY_ID` | S3 archive | Access key with write access to the bucket. |
| `ARCHIVE_S3_SECRET_ACCESS_KEY` | S3 archive | Matching secret key. |
| `ARCHIVE_S3_ENDPOINT` | S3 archive *(optional)* | Endpoint URL for S3-compatible providers (MinIO, Backblaze B2, Cloudflare R2, …). Leave unset for AWS S3. |
| `ARCHIVE_S3_REGION` | S3 archive *(optional)* | Region; defaults to `us-east-1` (fine for most S3-compatibles). |

## Release signing

Generate the release keystore **once**, off-CI, and keep the original in a password
manager / offline backup — losing it means losing the ability to update the app on
stores that use it:

```bash
keytool -genkeypair -v -keystore release.keystore -alias libremail \
        -keyalg RSA -keysize 4096 -validity 10000
base64 -w0 release.keystore   # → RELEASE_KEYSTORE_BASE64
```

At build time the workflow decodes the keystore to the runner's temp dir and writes the
git-ignored `secrets.properties` that `app/build.gradle.kts` already reads
(`RELEASE_STORE_FILE` / `RELEASE_STORE_PASSWORD` / `RELEASE_KEY_ALIAS` /
`RELEASE_KEY_PASSWORD` — same keys as `secrets.properties.example`). Local builds are
unaffected: without the file, `assembleRelease`/`bundleRelease` fall back to the debug
key exactly as before.

**Without the four signing secrets** the workflow still runs, but artifacts are named
`LibreMail-<tag>-unsigned.{apk,aab}` (they carry only the throwaway debug-key fallback
signature — installable for testing, **not** publishable), the GitHub release is marked
as a pre-release with a warning, and Google Play publication is skipped.

If Play App Signing is enabled (recommended, and the default for new Play apps), this
keystore is the *upload key*; Google holds the actual app-signing key and an upload key
can be reset through Play support if lost. Galaxy Store and GitHub-release APKs are
signed directly with this key.

## Google Play

Prerequisites (issue #16): a Play developer account with the LibreMail listing created
and the **first AAB uploaded manually** through the Play Console (the API cannot create
the app or complete the initial listing/data-safety forms).

Service-account setup:

1. In Google Cloud, create a service account and a JSON key
   (`IAM & Admin → Service accounts → Keys → Add key → JSON`).
2. In the Play Console: `Users and permissions → Invite new users` → the service
   account's email → grant *Release to production* (or at least *Release apps to testing
   tracks*) for LibreMail.
3. Store the whole JSON file as the `PLAY_SERVICE_ACCOUNT_JSON` secret.

The publish stage uploads the AAB (plus the R8 `mapping.txt` and a generated
`whatsnew-en-US` release note) with
[`r0adkll/upload-google-play`](https://github.com/r0adkll/upload-google-play).

**Staged rollout:** tag pushes publish to the safe default track `internal`. To go wider,
dispatch the workflow with the same `tag`, `play_track: production` and a
`play_rollout_fraction` such as `0.10` — that creates an `inProgress` (staged) release
for 10 % of users. Increase the fraction / complete the rollout from the **Play Console →
Releases** afterwards; re-dispatching with the same `versionCode` cannot re-upload the
bundle.

**Manual fallback:** download the AAB from the GitHub release, verify it against
`SHA256SUMS.txt`, and upload it in the Play Console (`Production → Create new release`).

## Galaxy Store

Automated submission is **deliberately stubbed** for now: Samsung ships no maintained
GitHub Action, community Gradle plugins are effectively unmaintained, and the
[Content Publish API](https://developer.samsung.com/galaxy-store/galaxy-store-developer-api/content-publish-api/overview.html)
is mid-migration — `contentUpdate` stops accepting `binaryList` in **July 2026** in favor
of new add/modify/delete-binary endpoints. Wiring an API in the middle of that change,
with no seller account to test against (issue #17), would only produce untested code.
The `galaxy-store` job therefore prints the manual procedure (and notices when the
reserved `GALAXY_*` secrets are present) and always succeeds.

**Manual path per release:**

1. Download `LibreMail-<tag>.apk` and `SHA256SUMS.txt` from the GitHub release and verify:
   `sha256sum -c SHA256SUMS.txt`.
2. In [Samsung Seller Portal](https://seller.samsungapps.com), open the LibreMail entry,
   add the APK as a new binary, paste the release notes from the release page, submit for
   review.

**To automate later** (once #17 lands and the API migration settles): create a service
account under Seller Portal → *Assistance → API Service*, store the reserved `GALAXY_*`
secrets, and replace the stub with calls to the Content Publish API (JWT auth →
`createUploadSessionId` → `fileUpload` → add binary → `contentSubmit`), or adopt a
then-maintained action/plugin.

## F-Droid

There is intentionally **no push step**: F-Droid pulls from us. Its build servers check
out the pushed tag, build from source and sign the result themselves, driven by the
fastlane metadata in this repo and the recipe in fdroiddata (issue #18). Keeping the tag
= keeping the release; nothing else to do here. (This is also why opt-in-only telemetry
is a hard project constraint — see `README.md`.)

## Binary archive (S3)

When the `ARCHIVE_S3_*` secrets are set, every published release is copied to
S3-compatible storage using the AWS CLI (`--endpoint-url` supports MinIO, Backblaze B2,
Cloudflare R2, …):

```
s3://<ARCHIVE_S3_BUCKET>/releases/<tag>/
    LibreMail-<tag>.aab
    LibreMail-<tag>.apk
    LibreMail-<tag>-mapping.txt
    LibreMail-<tag>-src.zip          ← source at the released commit (GPL §6 convenience)
    LibreMail-<tag>-src.tar.gz
    CHANGELOG.md
    SHA256SUMS.txt                   ← SHA-256 for every LibreMail-* file above
```

Recommended bucket setup: enable **object versioning** and deny deletes (or add an
object-lock/retention rule) so prior binaries stay immutable; the per-release key prefix
keeps every version addressable either way.

**Manual fallback:** `aws s3 cp dist/ s3://<bucket>/releases/<tag>/ --recursive` with the
same layout, using artifacts downloaded from the GitHub release.

## What runs when

| Stage | Tag push | Dispatch (`dry_run`) | Dispatch (tag, `dry_run: false`) | Needs secrets |
| --- | --- | --- | --- | --- |
| Fast CI gate | ✔ | ✔ | ✔ | — |
| Build + checksum + workflow artifact | ✔ | ✔ | ✔ | — (signs when signing secrets exist) |
| GitHub release | ✔ | — | ✔ | — (repo `GITHUB_TOKEN`) |
| Google Play | ✔ | — | ✔ | signing + `PLAY_SERVICE_ACCOUNT_JSON` |
| Galaxy Store | stub (manual) | — | stub (manual) | *(reserved `GALAXY_*`)* |
| S3 archive | ✔ | — | ✔ | `ARCHIVE_S3_*` |
