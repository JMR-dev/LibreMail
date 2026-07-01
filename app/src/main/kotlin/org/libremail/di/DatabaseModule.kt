// SPDX-License-Identifier: GPL-3.0-or-later
package org.libremail.di

import android.content.Context
import androidx.room.Room
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory
import org.libremail.data.local.DatabaseEncryption
import org.libremail.data.local.LibreMailDatabase
import org.libremail.data.local.MIGRATION_1_2
import org.libremail.data.local.MIGRATION_2_3
import org.libremail.data.local.MIGRATION_3_4
import org.libremail.data.local.MIGRATION_4_5
import org.libremail.data.local.MIGRATION_5_6
import org.libremail.data.local.MIGRATION_6_7
import org.libremail.data.local.MIGRATION_7_8
import org.libremail.data.local.MIGRATION_8_9
import org.libremail.data.local.MIGRATION_9_10
import org.libremail.data.local.dao.AccountDao
import org.libremail.data.local.dao.AccountSettingsDao
import org.libremail.data.local.dao.AttachmentDao
import org.libremail.data.local.dao.CredentialDao
import org.libremail.data.local.dao.DraftDao
import org.libremail.data.local.dao.FolderDao
import org.libremail.data.local.dao.MessageDao
import org.libremail.data.local.dao.OutboxDao
import org.libremail.data.security.DatabaseKeyStore
import org.libremail.data.settings.SettingsRepository
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    @Provides
    @Singleton
    fun provideDatabase(
        @ApplicationContext context: Context,
        keyStore: DatabaseKeyStore,
        settingsRepository: SettingsRepository,
    ): LibreMailDatabase {
        val builder = Room.databaseBuilder(context, LibreMailDatabase::class.java, DB_NAME)
            .addMigrations(
                MIGRATION_1_2,
                MIGRATION_2_3,
                MIGRATION_3_4,
                MIGRATION_4_5,
                MIGRATION_5_6,
                MIGRATION_6_7,
                MIGRATION_7_8,
                MIGRATION_8_9,
                MIGRATION_9_10,
            )
        // No destructive fallback: the migration chain is complete, and silently dropping the
        // accounts/credentials/mail tables would lose stored secrets. A missing migration should
        // fail loudly in testing instead.

        // Opt-in at-rest encryption of the local cache (off by default). The conversion runs here —
        // before the database is opened — so it never races an open connection; toggling the setting
        // therefore takes effect on the next app start. The passphrase is sealed by the Keystore.
        val dbFile = context.getDatabasePath(DB_NAME)
        if (runBlocking { settingsRepository.settings.first().encryptCache }) {
            val passphrase = runBlocking { keyStore.passphrase() }
            DatabaseEncryption.ensureEncrypted(dbFile, passphrase)
            builder.openHelperFactory(
                SupportOpenHelperFactory(passphrase.toByteArray(Charsets.US_ASCII), null, false),
            )
        } else if (DatabaseEncryption.isEncrypted(dbFile)) {
            // Encryption was turned back off — decrypt so the default (unkeyed) open succeeds.
            DatabaseEncryption.ensurePlaintext(dbFile, runBlocking { keyStore.passphrase() })
        }
        return builder.build()
    }

    @Provides
    fun provideMessageDao(database: LibreMailDatabase): MessageDao = database.messageDao()

    @Provides
    fun provideAccountDao(database: LibreMailDatabase): AccountDao = database.accountDao()

    @Provides
    fun provideAccountSettingsDao(database: LibreMailDatabase): AccountSettingsDao = database.accountSettingsDao()

    @Provides
    fun provideCredentialDao(database: LibreMailDatabase): CredentialDao = database.credentialDao()

    @Provides
    fun provideAttachmentDao(database: LibreMailDatabase): AttachmentDao = database.attachmentDao()

    @Provides
    fun provideOutboxDao(database: LibreMailDatabase): OutboxDao = database.outboxDao()

    @Provides
    fun provideDraftDao(database: LibreMailDatabase): DraftDao = database.draftDao()

    @Provides
    fun provideFolderDao(database: LibreMailDatabase): FolderDao = database.folderDao()

    private const val DB_NAME = "libremail.db"
}
