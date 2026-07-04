// SPDX-License-Identifier: GPL-3.0-or-later
package org.libremail.reporting

/**
 * Removes personally-identifiable information from a throwable's stack trace before it is stored in
 * or submitted as a [DebugReport]. LibreMail reports are a hard PII-free surface, but mail/network
 * exceptions (Jakarta/Angus Mail, `java.net`) embed PII in their *message* text — a server hostname
 * and, for auth failures, the account username/email — e.g.
 *
 * ```
 * java.net.ConnectException: Failed to connect to imap.example.com/93.184.216.34:993
 * ```
 *
 * A regex alone can't safely scrub this: a hostname like `imap.example.com` is indistinguishable
 * from a dotted class name (`java.net.ConnectException`) or a frame's file (`Socket.java`). So the
 * scrubber instead keeps the parts of a trace that carry no PII yet make a report useful — the
 * exception *class* names and every stack *frame* (`at pkg.Class.method(File.kt:42)`) — and drops
 * the free-text message from each exception header line, which is the only place a hostname or
 * username appears. As defense-in-depth it then redacts any residual e-mail address or `host:port`
 * token left on a wrapped/continuation message line. Frame lines are never touched, so a frame's
 * `File.kt:42` is never mistaken for a `host:port`.
 */
object StackTraceScrubber {

    private const val REDACTED = "[redacted]"

    /** Exception header prefixes that precede the class name and must be preserved. */
    private val KNOWN_PREFIXES = listOf("Caused by: ", "Suppressed: ")

    /** `user@host.tld` anywhere in the text. */
    private val EMAIL = Regex("""[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}""")

    /**
     * A dotted host name or IPv4 address followed by `:port`, including the `InetSocketAddress`
     * "host/1.2.3.4:port" rendering. Applied only to non-frame lines, so it can never match a stack
     * frame's `File.kt:42`.
     */
    private val HOST_PORT = Regex("""[A-Za-z0-9.-]+(?:/[0-9.]+)?:\d{2,5}""")

    /** Scrubs [stackTrace] (the output of [Throwable.stackTraceToString]) of PII. */
    fun scrub(stackTrace: String): String =
        stackTrace.lineSequence().joinToString(separator = "\n", transform = ::scrubLine)

    private fun scrubLine(line: String): String {
        val trimmed = line.trimStart()
        // Stack frames and the "... N more" elision carry only class/method/file/line — never PII.
        if (trimmed.startsWith("at ") || trimmed.startsWith("... ")) return line
        // Exception header line ("Type: message", possibly behind a "Caused by: "/"Suppressed: "
        // prefix): drop the free-text message, then redact anything PII-shaped that remains.
        return redact(dropMessage(line))
    }

    /** Keeps a header's class name(s) and any `Caused by:`/`Suppressed:` prefix, dropping the message. */
    private fun dropMessage(line: String): String {
        val indent = line.takeWhile(Char::isWhitespace)
        val body = line.substring(indent.length)
        val prefix = KNOWN_PREFIXES.firstOrNull(body::startsWith).orEmpty()
        // Throwable renders headers as "<class>" or "<class>: <message>"; keep up to the first ": ".
        val classOnly = body.substring(prefix.length).substringBefore(": ")
        return indent + prefix + classOnly
    }

    private fun redact(line: String): String = line.replace(EMAIL, REDACTED).replace(HOST_PORT, REDACTED)
}
