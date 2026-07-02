// SPDX-License-Identifier: GPL-3.0-or-later
package org.libremail.domain.model

/** Per-account user preferences (signature, notification gating, retention). Defaults apply when unset. */
data class AccountSettings(
    val accountId: String,
    val signature: String = "",
    val signatureEnabled: Boolean = true,
    val notificationsEnabled: Boolean = true,
    /**
     * Device-only retention overrides (issue #13). `null` = inherit the global default; `0` = an
     * explicit "keep everything"; a positive value caps the newest-N messages ([retentionCount]) or
     * the last-N months ([retentionMonths]) kept locally. Never affects the server copy.
     */
    val retentionCount: Int? = null,
    val retentionMonths: Int? = null,
) {
    /**
     * The block to append to a compose body, or "" when disabled or blank. Uses the RFC 3676
     * signature delimiter ("-- " on its own line) so downstream clients recognize it as a signature.
     */
    fun signatureBlock(): String = if (signatureEnabled &&
        signature.isNotBlank()
    ) {
        "\n\n-- \n${signature.trimEnd()}"
    } else {
        ""
    }
}
