// SPDX-License-Identifier: GPL-3.0-or-later
package org.libremail.di

import android.content.Context
import androidx.room.Room
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton
import org.libremail.data.local.LibreMailDatabase
import org.libremail.data.local.MIGRATION_1_2
import org.libremail.data.local.MIGRATION_2_3
import org.libremail.data.local.MIGRATION_3_4
import org.libremail.data.local.MIGRATION_4_5
import org.libremail.data.local.MIGRATION_5_6
import org.libremail.data.local.MIGRATION_6_7
import org.libremail.data.local.dao.AccountDao
import org.libremail.data.local.dao.AttachmentDao
import org.libremail.data.local.dao.CredentialDao
import org.libremail.data.local.dao.DraftDao
import org.libremail.data.local.dao.MessageDao
import org.libremail.data.local.dao.OutboxDao

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): LibreMailDatabase =
        Room.databaseBuilder(context, LibreMailDatabase::class.java, "libremail.db")
            .addMigrations(
                MIGRATION_1_2,
                MIGRATION_2_3,
                MIGRATION_3_4,
                MIGRATION_4_5,
                MIGRATION_5_6,
                MIGRATION_6_7,
            )
            // No destructive fallback: the migration chain is complete, and silently dropping the
            // accounts/credentials/mail tables would lose stored secrets. A missing migration should
            // fail loudly in testing instead.
            .build()

    @Provides
    fun provideMessageDao(database: LibreMailDatabase): MessageDao = database.messageDao()

    @Provides
    fun provideAccountDao(database: LibreMailDatabase): AccountDao = database.accountDao()

    @Provides
    fun provideCredentialDao(database: LibreMailDatabase): CredentialDao = database.credentialDao()

    @Provides
    fun provideAttachmentDao(database: LibreMailDatabase): AttachmentDao = database.attachmentDao()

    @Provides
    fun provideOutboxDao(database: LibreMailDatabase): OutboxDao = database.outboxDao()

    @Provides
    fun provideDraftDao(database: LibreMailDatabase): DraftDao = database.draftDao()
}
