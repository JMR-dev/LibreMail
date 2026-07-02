// SPDX-License-Identifier: GPL-3.0-or-later
package org.libremail.di

import android.content.Context
import androidx.room.Room
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import org.libremail.data.local.AccountDatabase
import org.libremail.data.local.DatabaseFiles.ACCOUNTS_NAME
import org.libremail.data.local.LibreMailDatabase
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
     * The plaintext account store. Depends on [LibreMailDatabase] purely for construction ordering:
     * building the cache runs the one-time [org.libremail.data.local.AccountDataMigrator] (which
     * populates this file on a dedicated connection) and then drops the moved tables, so by the time
     * Room opens this file the data is already present and no other connection is touching it.
     */
    @Provides
    @Singleton
    fun provideAccountDatabase(
        @ApplicationContext context: Context,
        @Suppress("UNUSED_PARAMETER") cacheDatabase: LibreMailDatabase,
    ): AccountDatabase = Room.databaseBuilder(context, AccountDatabase::class.java, ACCOUNTS_NAME).build()

    @Provides
    fun provideAccountDao(database: AccountDatabase): AccountDao = database.accountDao()

    @Provides
    fun provideCredentialDao(database: AccountDatabase): CredentialDao = database.credentialDao()

    @Provides
    fun provideAccountSettingsDao(database: AccountDatabase): AccountSettingsDao = database.accountSettingsDao()

    @Provides
    fun provideSignatureDao(database: AccountDatabase): SignatureDao = database.signatureDao()
}
