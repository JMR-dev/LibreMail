// SPDX-License-Identifier: GPL-3.0-or-later
package org.libremail.data.sync

import androidx.work.ListenableWorker.Result
import org.libremail.data.local.isCacheEncryptionUnavailable
import org.libremail.reporting.AppLog

/**
 * Runs [block] and, if opening the Room cache fails because SQLCipher's native library is unavailable on
 * this device (issue #359 — a `CacheEncryptionUnavailableException` the provisioner raised, or defensively
 * a bare `LinkageError` at `nativeOpen`), logs a PII-free breadcrumb under [tag] and returns
 * [Result.retry] rather than letting the failure crash the worker. WorkManager workers inject the cache
 * directly, with no UI gate; a later launch may load the library and recover, so a soft retry — not a hard
 * failure — is correct. Every other throwable (including `CancellationException`) propagates unchanged.
 *
 * `inline` so the (suspending) [block] runs in the worker's own coroutine context, mirroring `runCatching`.
 */
internal inline fun retryIfEncryptedCacheUnavailable(tag: String, block: () -> Result): Result = try {
    block()
} catch (failure: Throwable) {
    if (!failure.isCacheEncryptionUnavailable()) throw failure
    AppLog.w(tag, "deferred: encrypted cache unavailable (SQLCipher native library)", failure)
    Result.retry()
}
