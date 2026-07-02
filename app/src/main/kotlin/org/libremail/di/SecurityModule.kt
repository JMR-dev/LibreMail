// SPDX-License-Identifier: GPL-3.0-or-later
package org.libremail.di

import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import org.libremail.data.security.AndroidAppLockManager
import org.libremail.data.security.AppLockGate
import org.libremail.data.security.AppLockManager
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class SecurityModule {

    @Binds
    @Singleton
    abstract fun bindAppLockManager(impl: AndroidAppLockManager): AppLockManager

    companion object {
        /**
         * The app-lock UI gate is application-scoped, NOT Activity/ViewModel-scoped, so its grace
         * window survives Activity recreation (e.g. Back finishing the task root on API 29/30, which
         * clears the Activity's ViewModelStore). See [AppLockGate] for why this must outlive the
         * ViewModel. A fresh process constructs a new instance that correctly starts LOCKED.
         */
        @Provides
        @Singleton
        fun provideAppLockGate(): AppLockGate = AppLockGate()
    }
}
