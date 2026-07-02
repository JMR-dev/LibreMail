// SPDX-License-Identifier: GPL-3.0-or-later
package org.libremail.power

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Reads battery state from [BatteryManager] (one-shot) and from the sticky
 * [Intent.ACTION_BATTERY_CHANGED] broadcast (stream). Needs no permission. Anything unreadable
 * degrades to [BatteryStatus.ASSUME_OK], so battery gating only ever engages on a definitively low
 * battery, never on missing data.
 */
@Singleton
class AndroidBatteryStatusProvider @Inject constructor(@ApplicationContext private val context: Context) :
    BatteryStatusProvider {

    override fun current(): BatteryStatus {
        val manager = context.getSystemService(BatteryManager::class.java) ?: return BatteryStatus.ASSUME_OK
        val percent = manager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        return BatteryStatus(
            percent = if (percent in 0..PERCENT_SCALE) percent else BatteryStatus.ASSUME_OK.percent,
            isCharging = manager.isCharging,
        )
    }

    /**
     * [Intent.ACTION_BATTERY_CHANGED] can only be received by a runtime-registered receiver, so the
     * registration lives for exactly as long as the flow is collected. The broadcast is sticky:
     * registration returns the latest snapshot, which is emitted immediately. RECEIVER_NOT_EXPORTED
     * is correct for a system broadcast — the system is always allowed to deliver to it.
     */
    override fun status(): Flow<BatteryStatus> = callbackFlow {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                trySend(intent.toBatteryStatus())
            }
        }
        val sticky = ContextCompat.registerReceiver(
            context,
            receiver,
            IntentFilter(Intent.ACTION_BATTERY_CHANGED),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        trySend(sticky?.toBatteryStatus() ?: current())
        awaitClose { context.unregisterReceiver(receiver) }
    }.distinctUntilChanged()

    private fun Intent.toBatteryStatus(): BatteryStatus {
        val level = getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        val status = getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        val charging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
        return BatteryStatus(
            percent = if (level >= 0 && scale > 0) level * PERCENT_SCALE / scale else BatteryStatus.ASSUME_OK.percent,
            isCharging = charging,
        )
    }

    private companion object {
        const val PERCENT_SCALE = 100
    }
}
