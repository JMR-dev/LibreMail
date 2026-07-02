// SPDX-License-Identifier: GPL-3.0-or-later
package org.libremail.di

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import org.libremail.data.security.AndroidAppLockManager
import org.libremail.data.security.AppLockManager
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class SecurityModule {

    @Binds
    @Singleton
    abstract fun bindAppLockManager(impl: AndroidAppLockManager): AppLockManager
}
