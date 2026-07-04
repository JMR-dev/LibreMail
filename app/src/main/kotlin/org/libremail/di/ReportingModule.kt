// SPDX-License-Identifier: GPL-3.0-or-later
package org.libremail.di

import android.content.Context
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import org.libremail.BuildConfig
import org.libremail.reporting.DebugReportEndpoint
import org.libremail.reporting.ReportStore
import java.io.File
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object ReportingModule {

    @Provides
    @Singleton
    fun provideReportStore(@ApplicationContext context: Context): ReportStore =
        ReportStore(File(context.filesDir, "debug_reports"))

    /**
     * The debug-report ingest endpoint (empty by default — see [DebugReportEndpoint]). Provided as an
     * injectable value so [org.libremail.reporting.ReportUploadWorker] takes it via its constructor
     * instead of reading the `BuildConfig` static inline, which keeps its submit path testable.
     */
    @Provides
    @DebugReportEndpoint
    fun provideDebugReportEndpoint(): String = BuildConfig.DEBUG_REPORT_ENDPOINT
}
