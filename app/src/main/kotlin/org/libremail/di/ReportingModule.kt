// SPDX-License-Identifier: GPL-3.0-or-later
package org.libremail.di

import android.content.Context
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
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
}
