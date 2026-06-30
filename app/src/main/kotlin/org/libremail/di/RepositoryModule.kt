// SPDX-License-Identifier: GPL-3.0-or-later
package org.libremail.di

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton
import org.libremail.data.repository.AccountRepositoryImpl
import org.libremail.data.repository.MailRepositoryImpl
import org.libremail.data.sync.MailSyncer
import org.libremail.data.sync.Syncer
import org.libremail.domain.repository.AccountRepository
import org.libremail.domain.repository.MailRepository

@Module
@InstallIn(SingletonComponent::class)
abstract class RepositoryModule {

    @Binds
    @Singleton
    abstract fun bindMailRepository(impl: MailRepositoryImpl): MailRepository

    @Binds
    @Singleton
    abstract fun bindAccountRepository(impl: AccountRepositoryImpl): AccountRepository

    @Binds
    @Singleton
    abstract fun bindSyncer(impl: MailSyncer): Syncer
}
