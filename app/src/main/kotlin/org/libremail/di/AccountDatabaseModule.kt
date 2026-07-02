// SPDX-License-Identifier: GPL-3.0-or-later
package org.libremail.di

import android.content.Context
import androidx.room.Room
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.runBlocking
import org.libremail.data.local.AccountDatabase
import org.libremail.data.local.DatabaseFiles.ACCOUNTS_NAME
import org.libremail.data.local.DatabaseProvisioner
import org.libremail.data.local.DeferredOpenHelperFactory
import org.libremail.data.local.dao.AccountDao
import org.libremail.data.local.dao.AccountSettingsDao
import org.libremail.data.local.dao.CredentialDao
import org.libremail.data.local.dao.SignatureDao
import javax.inject.Singleton

/**
 * Hilt wiring for [AccountDatabase] — the non-auth-bound store for accounts, credentials, per-account
 * settings and signatures (issue #111). Kept separate from [DatabaseModule] so each database's
 * provides stay cohesive (and neither module grows past detekt's per-object function limit).
 */
@Module
@InstallIn(SingletonComponent::class)
object AccountDatabaseModule {

    /**
     * The plaintext account store. Its OPEN is gated on [DatabaseProvisioner.prepareCache] so the
     * one-time [org.libremail.data.local.AccountDataMigrator] (which populates this file on a dedicated
     * connection, then drops the moved tables from the cache) has finished before Room opens this file —
     * the migrate-before-open ordering the old construction-time dependency on `LibreMailDatabase`
     * enforced, now moved OFF the injection path (issue #93). This store always opens unkeyed, so it
     * ignores the returned cache open-mode and only awaits the shared sequence.
     */
    @Provides
    @Singleton
    fun provideAccountDatabase(
        @ApplicationContext context: Context,
        provisioner: DatabaseProvisioner,
    ): AccountDatabase = Room.databaseBuilder(context, AccountDatabase::class.java, ACCOUNTS_NAME)
        .openHelperFactory(
            DeferredOpenHelperFactory { configuration ->
                runBlocking { provisioner.prepareCache() }
                FrameworkSQLiteOpenHelperFactory().create(configuration)
            },
        )
        .build()

    @Provides
    fun provideAccountDao(database: AccountDatabase): AccountDao = database.accountDao()

    @Provides
    fun provideCredentialDao(database: AccountDatabase): CredentialDao = database.credentialDao()

    @Provides
    fun provideAccountSettingsDao(database: AccountDatabase): AccountSettingsDao = database.accountSettingsDao()

    @Provides
    fun provideSignatureDao(database: AccountDatabase): SignatureDao = database.signatureDao()
}
