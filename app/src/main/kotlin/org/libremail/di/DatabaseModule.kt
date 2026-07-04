// SPDX-License-Identifier: GPL-3.0-or-later
package org.libremail.di

import android.content.Context
import androidx.room.Room
import androidx.room.migration.Migration
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.runBlocking
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory
import org.libremail.data.local.CacheOpenMode
import org.libremail.data.local.DatabaseFiles
import org.libremail.data.local.DatabaseProvisioner
import org.libremail.data.local.DeferredOpenHelperFactory
import org.libremail.data.local.LibreMailDatabase
import org.libremail.data.local.MIGRATION_10_11
import org.libremail.data.local.MIGRATION_11_12
import org.libremail.data.local.MIGRATION_12_13
import org.libremail.data.local.MIGRATION_13_14
import org.libremail.data.local.MIGRATION_14_15
import org.libremail.data.local.MIGRATION_15_16
import org.libremail.data.local.MIGRATION_16_17
import org.libremail.data.local.MIGRATION_17_18
import org.libremail.data.local.MIGRATION_18_19
import org.libremail.data.local.MIGRATION_1_2
import org.libremail.data.local.MIGRATION_2_3
import org.libremail.data.local.MIGRATION_3_4
import org.libremail.data.local.MIGRATION_4_5
import org.libremail.data.local.MIGRATION_5_6
import org.libremail.data.local.MIGRATION_6_7
import org.libremail.data.local.MIGRATION_7_8
import org.libremail.data.local.MIGRATION_8_9
import org.libremail.data.local.MIGRATION_9_10
import org.libremail.data.local.dao.AttachmentDao
import org.libremail.data.local.dao.BackfillProgressDao
import org.libremail.data.local.dao.DraftDao
import org.libremail.data.local.dao.FolderDao
import org.libremail.data.local.dao.MessageDao
import org.libremail.data.local.dao.OutboxDao
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    /**
     * Every migration the Room builder registers, in one named list so [provideDatabase] and the
     * "registered == declared" safety-net test (issue #312) read the same source. There is deliberately
     * no destructive fallback (see below), so a migration authored + schema-committed but forgotten here
     * passes every replay test yet crash-loops ALL upgrading users at first DB open; `MigrationTest`
     * asserts this list equals the reflectively-discovered set of every `Migration` val in Migrations.kt.
     */
    val ALL_MIGRATIONS: Array<Migration> = arrayOf(
        MIGRATION_1_2,
        MIGRATION_2_3,
        MIGRATION_3_4,
        MIGRATION_4_5,
        MIGRATION_5_6,
        MIGRATION_6_7,
        MIGRATION_7_8,
        MIGRATION_8_9,
        MIGRATION_9_10,
        MIGRATION_10_11,
        MIGRATION_11_12,
        MIGRATION_12_13,
        MIGRATION_13_14,
        MIGRATION_14_15,
        MIGRATION_15_16,
        MIGRATION_16_17,
        MIGRATION_17_18,
        MIGRATION_18_19,
    )

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context, provisioner: DatabaseProvisioner): LibreMailDatabase =
        Room.databaseBuilder(context, LibreMailDatabase::class.java, DB_NAME)
            .addMigrations(*ALL_MIGRATIONS)
            // No destructive fallback: the migration chain is complete, and silently dropping the
            // mail/message tables would lose cached data. A missing migration should fail loudly in
            // testing instead.
            //
            // All blocking startup work — the issue-#111 AccountDataMigrator, the encrypted-cache
            // conversion, and the Keystore passphrase resolution — is deferred OFF this injection path
            // (issue #93). The factory below runs DatabaseProvisioner.prepareCache() lazily, when Room
            // first OPENS the cache on its background query executor, never on the (possibly main)
            // thread that injects this singleton. prepareCache() still performs that sequence before the
            // file opens and in the same order, so the migrate-before-open guarantee and the encryption
            // gate are unchanged — only where/when they run moved.
            .openHelperFactory(
                DeferredOpenHelperFactory { configuration ->
                    val realFactory = when (val mode = runBlocking { provisioner.prepareCache() }) {
                        is CacheOpenMode.Encrypted ->
                            SupportOpenHelperFactory(mode.passphrase.toByteArray(Charsets.US_ASCII), null, false)

                        CacheOpenMode.Plaintext -> FrameworkSQLiteOpenHelperFactory()
                    }
                    realFactory.create(configuration)
                },
            )
            .build()

    @Provides
    fun provideMessageDao(database: LibreMailDatabase): MessageDao = database.messageDao()

    @Provides
    fun provideAttachmentDao(database: LibreMailDatabase): AttachmentDao = database.attachmentDao()

    @Provides
    fun provideOutboxDao(database: LibreMailDatabase): OutboxDao = database.outboxDao()

    @Provides
    fun provideDraftDao(database: LibreMailDatabase): DraftDao = database.draftDao()

    @Provides
    fun provideFolderDao(database: LibreMailDatabase): FolderDao = database.folderDao()

    @Provides
    fun provideBackfillProgressDao(database: LibreMailDatabase): BackfillProgressDao = database.backfillProgressDao()

    private const val DB_NAME = DatabaseFiles.NAME
}
