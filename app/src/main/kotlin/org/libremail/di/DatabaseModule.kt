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
import org.libremail.data.local.dao.AccountDao
import org.libremail.data.local.dao.MessageDao

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): LibreMailDatabase =
        Room.databaseBuilder(context, LibreMailDatabase::class.java, "libremail.db").build()

    @Provides
    fun provideMessageDao(database: LibreMailDatabase): MessageDao = database.messageDao()

    @Provides
    fun provideAccountDao(database: LibreMailDatabase): AccountDao = database.accountDao()
}
