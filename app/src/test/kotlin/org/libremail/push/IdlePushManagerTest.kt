// SPDX-License-Identifier: GPL-3.0-or-later
package org.libremail.push

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkConstructor
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import io.mockk.verify
import org.junit.After
import org.junit.Test

class IdlePushManagerTest {

    private val context = mockk<Context>(relaxed = true)
    private val manager = IdlePushManager(context)

    @After
    fun tearDown() = unmockkAll()

    @Test
    fun `start launches the idle foreground service`() {
        mockkConstructor(Intent::class)
        mockkStatic(ContextCompat::class)
        every { ContextCompat.startForegroundService(any(), any()) } just Runs

        manager.start()

        verify { ContextCompat.startForegroundService(context, any()) }
    }

    @Test
    fun `start swallows a background-start rejection so periodic sync still covers mail`() {
        mockkConstructor(Intent::class)
        mockkStatic(ContextCompat::class)
        every { ContextCompat.startForegroundService(any(), any()) } throws
            IllegalStateException("cannot start FGS from background")

        // No exception escapes: the runCatching keeps a rejected foreground start from crashing the app.
        manager.start()

        verify { ContextCompat.startForegroundService(context, any()) }
    }

    @Test
    fun `stop tears down the idle service`() {
        mockkConstructor(Intent::class)

        manager.stop()

        verify { context.stopService(any()) }
    }
}
