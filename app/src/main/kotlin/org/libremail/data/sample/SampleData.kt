// SPDX-License-Identifier: GPL-3.0-or-later
package org.libremail.data.sample

import org.libremail.domain.model.Message

/**
 * Placeholder content shown until real IMAP sync lands (next increment), so the
 * mailbox and reader are populated when the local cache is still empty.
 */
object SampleData {
    private const val MINUTE = 60_000L
    private const val HOUR = 60 * MINUTE
    private val now = System.currentTimeMillis()

    val messages: List<Message> = listOf(
        Message(
            id = "sample-1",
            accountId = "sample",
            sender = "LibreMail",
            senderEmail = "hello@libremail.org",
            subject = "Welcome to LibreMail",
            snippet = "Thanks for trying LibreMail — a free, open-source email client.",
            body = "Thanks for trying LibreMail!\n\n" +
                "This is placeholder content. Account sign-in, IMAP sync and sending " +
                "arrive in upcoming increments. The screen you are looking at is wired " +
                "to the same ViewModel → Repository → Room pipeline the real data will use.",
            timestampMillis = now - 5 * MINUTE,
            isRead = false,
            isStarred = true,
        ),
        Message(
            id = "sample-2",
            accountId = "sample",
            sender = "Material You",
            senderEmail = "design@android.example",
            subject = "Your theme follows the wallpaper",
            snippet = "On Android 12+ the colors you see are derived from your wallpaper.",
            body = "LibreMail uses Material 3 dynamic color. On Android 12 and newer, the " +
                "accent colors are generated from your wallpaper. On older versions it falls " +
                "back to the LibreMail brand palette.",
            timestampMillis = now - 3 * HOUR,
            isRead = false,
            isStarred = false,
        ),
        Message(
            id = "sample-3",
            accountId = "sample",
            sender = "Privacy",
            senderEmail = "privacy@libremail.org",
            subject = "Remote images are blocked by default",
            snippet = "We block remote content to protect you from tracking pixels.",
            body = "By default, remote images in HTML email are not loaded, which prevents " +
                "senders from tracking when you open a message. You can change this under " +
                "Settings → Advanced Settings.",
            timestampMillis = now - 26 * HOUR,
            isRead = true,
            isStarred = false,
        ),
    )

    fun byId(id: String): Message? = messages.firstOrNull { it.id == id }
}
