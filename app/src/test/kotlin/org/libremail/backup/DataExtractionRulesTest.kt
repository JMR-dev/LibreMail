// SPDX-License-Identifier: GPL-3.0-or-later
package org.libremail.backup

import org.junit.Test
import org.libremail.data.local.DatabaseFiles
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Validates the shipped Android Backup rule resources directly, so they can't silently drift from
 * [BackupPolicy] or from the acceptance criteria of issue #21: only the settings DataStore may be
 * eligible, and the Keystore-sealed cache key plus the credentials/mail database must be excluded.
 */
class DataExtractionRulesTest {

    private data class Rules(val includes: Set<String>, val excludes: Set<String>)

    private fun resource(name: String): File {
        // Gradle runs unit tests with the module dir (app/) as the working dir; fall back to the repo
        // root in case a runner starts elsewhere.
        val candidates = listOf(
            File("src/main/res/xml/$name"),
            File("app/src/main/res/xml/$name"),
        )
        return candidates.firstOrNull { it.exists() }
            ?: error("Could not locate $name; looked in ${candidates.map { it.absolutePath }}")
    }

    /** Collects the `domain:path` pairs of every <include>/<exclude> under the given section element. */
    private fun parseSection(file: File, sectionTag: String): Rules {
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file)
        val section = doc.getElementsByTagName(sectionTag).item(0) as Element
        fun collect(tag: String): Set<String> {
            val nodes = section.getElementsByTagName(tag)
            return (0 until nodes.length).map { i ->
                val e = nodes.item(i) as Element
                "${e.getAttribute("domain")}:${e.getAttribute("path")}"
            }.toSet()
        }
        return Rules(includes = collect("include"), excludes = collect("exclude"))
    }

    private val safeFile = "file:${BackupPolicy.SAFE_SETTINGS_FILE}"
    private val secretPaths: List<String> =
        BackupPolicy.EXCLUDED_FILE_PATHS.map { "file:$it" } +
            BackupPolicy.EXCLUDED_DATABASE_PATHS.map { "database:$it" }

    private fun assertSafe(rules: Rules) {
        // Strict allowlist: the settings DataStore is the ONLY thing eligible for backup/transfer.
        // Everything else — crucially the Keystore-sealed cache key and the credentials/mail
        // database — is excluded simply by not being listed.
        assertEquals(setOf(safeFile), rules.includes, "only the settings DataStore may be backed up")
        assertTrue(rules.excludes.isEmpty(), "rules are allowlist-only; no <exclude> entries expected")
        secretPaths.forEach { secret ->
            assertFalse(secret in rules.includes, "$secret must never be eligible for backup")
        }
    }

    @Test
    fun `data extraction rules (API 31+) back up only settings for cloud backup`() {
        assertSafe(parseSection(resource("data_extraction_rules.xml"), "cloud-backup"))
    }

    @Test
    fun `data extraction rules (API 31+) back up only settings for device transfer`() {
        assertSafe(parseSection(resource("data_extraction_rules.xml"), "device-transfer"))
    }

    @Test
    fun `full backup content (API 29-30) mirrors the same exclusions`() {
        assertSafe(parseSection(resource("backup_rules.xml"), "full-backup-content"))
    }

    @Test
    fun `both the cache and accounts databases are guarded against the backup allowlist`() {
        // The exclusion SoT is derived from DatabaseFiles, so both databases flow into secretPaths
        // and are asserted-absent from every include section by assertSafe above. Pin that here so
        // the accounts + credentials DB added in #111 can't quietly drop out of the guarded set.
        assertTrue(secretPaths.contains("database:${DatabaseFiles.NAME}"))
        assertTrue(secretPaths.contains("database:${DatabaseFiles.ACCOUNTS_NAME}"))
    }
}
