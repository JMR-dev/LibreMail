// SPDX-License-Identifier: GPL-3.0-or-later
package org.libremail.di

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import org.libremail.power.AndroidBatteryStatusProvider
import org.libremail.power.BatteryStatusProvider
import javax.inject.Singleton

/** Bindings for device power/battery state sources. */
@Module
@InstallIn(SingletonComponent::class)
abstract class PowerModule {

    @Binds
    @Singleton
    abstract fun bindBatteryStatusProvider(impl: AndroidBatteryStatusProvider): BatteryStatusProvider
}
