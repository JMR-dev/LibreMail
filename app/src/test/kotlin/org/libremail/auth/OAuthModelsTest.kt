// SPDX-License-Identifier: GPL-3.0-or-later
package org.libremail.auth

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull

/** Value-semantics cover for the two OAuth result carriers. */
class OAuthModelsTest {

    @Test
    fun `OAuthResult exposes its fields and value semantics`() {
        val result = OAuthResult(email = "me@example.com", accessToken = "at", authStateJson = "{json}")

        assertEquals("me@example.com", result.email)
        assertEquals("at", result.accessToken)
        assertEquals("{json}", result.authStateJson)
        assertEquals(result, result.copy())
        assertNotEquals(result, result.copy(accessToken = "other"))
    }

    @Test
    fun `FreshToken defaults its expiry to null and supports copy`() {
        val fresh = FreshToken(accessToken = "at", authStateJson = "{json}")

        assertNull(fresh.accessTokenExpiry)
        assertEquals(4_200L, fresh.copy(accessTokenExpiry = 4_200L).accessTokenExpiry)
        assertEquals("at", fresh.accessToken)
        assertEquals(fresh, FreshToken("at", "{json}"))
    }
}
