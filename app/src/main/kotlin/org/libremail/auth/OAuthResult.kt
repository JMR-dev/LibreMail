// SPDX-License-Identifier: GPL-3.0-or-later
package org.libremail.auth

/** Result of a successful OAuth token exchange. */
data class OAuthResult(
    val email: String,
    val accessToken: String,
    /** Serialized [net.openid.appauth.AuthState], stored encrypted for later token refresh. */
    val authStateJson: String,
)

/** A freshly-refreshed access token plus the (possibly updated) AuthState to persist. */
data class FreshToken(
    val accessToken: String,
    val authStateJson: String,
)
