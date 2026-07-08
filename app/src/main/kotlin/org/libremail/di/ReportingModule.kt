// SPDX-License-Identifier: GPL-3.0-or-later
package org.libremail.di

import android.content.Context
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import org.libremail.BuildConfig
import org.libremail.data.security.KeystoreReportEncryption
import org.libremail.reporting.AppLog
import org.libremail.reporting.DebugReportEndpoint
import org.libremail.reporting.HybridReportPayloadEncryptor
import org.libremail.reporting.ReportPayloadEncryptor
import org.libremail.reporting.ReportStore
import java.io.File
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object ReportingModule {

    /**
     * The file-backed report store. [KeystoreReportEncryption] wires in the opt-in at-rest encryption
     * (issue #369): reports are sealed on disk when the `encryptCache` setting is on, plaintext when off.
     */
    @Provides
    @Singleton
    fun provideReportStore(
        @ApplicationContext context: Context,
        reportEncryption: KeystoreReportEncryption,
    ): ReportStore = ReportStore(
        directory = File(context.filesDir, "debug_reports"),
        encryption = reportEncryption,
    )

    /**
     * The debug-report ingest endpoint (empty by default — see [DebugReportEndpoint]). Provided as an
     * injectable value so [org.libremail.reporting.ReportUploadWorker] takes it via its constructor
     * instead of reading the `BuildConfig` static inline, which keeps its submit path testable.
     */
    @Provides
    @DebugReportEndpoint
    fun provideDebugReportEndpoint(): String = BuildConfig.DEBUG_REPORT_ENDPOINT

    /**
     * The seam that seals a report to the maintainer's public key before upload (issue #34). The key is
     * `BuildConfig.DEBUG_REPORT_PUBLIC_KEY` (empty by default), so a stock/F-Droid build resolves to
     * [ReportPayloadEncryptor.Disabled] and [org.libremail.reporting.ReportUploadWorker] fails closed —
     * it never transmits an unencrypted report. A key that is set but unparseable is logged (PII-free)
     * and likewise leaves the client disabled rather than silently sending in the clear.
     */
    @Provides
    @Singleton
    fun provideReportPayloadEncryptor(): ReportPayloadEncryptor {
        val publicKey = BuildConfig.DEBUG_REPORT_PUBLIC_KEY
        if (publicKey.isBlank()) return ReportPayloadEncryptor.Disabled
        return runCatching { HybridReportPayloadEncryptor(HybridReportPayloadEncryptor.parsePublicKey(publicKey)) }
            .getOrElse { e ->
                AppLog.w(TAG, "DEBUG_REPORT_PUBLIC_KEY is set but could not be parsed; upload stays disabled", e)
                ReportPayloadEncryptor.Disabled
            }
    }

    private const val TAG = "ReportingModule"
}
