// SPDX-License-Identifier: GPL-3.0-or-later
package org.libremail.mail

import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * A tiny localhost TCP proxy that forwards a **cleartext** IMAP session to a real backend (GreenMail)
 * while COUNTING what crosses it, so tests can measure the folder-open round-trip *structure*
 * deterministically without a real network (issue #125):
 *
 *  - [connectionCount] — how many separate TCP connections the client established. On a real network
 *    each new connection is a full CONNECT + TLS handshake + LOGIN/AUTH handshake group (several
 *    RTTs). [ImapClient] opens one [jakarta.mail.Store] — and therefore one connection — per
 *    operation today, so this equals the number of operations. Connection reuse / pooling would make
 *    it diverge (many operations, few connections); that divergence is exactly what a future fix
 *    should produce and what these tests are wired to detect.
 *  - [commandCount] — how many times each IMAP command word (LOGIN, EXAMINE, SELECT, FETCH, LOGOUT…)
 *    the client issued, parsed from the cleartext client → server stream.
 *
 * Point [ImapClient] at [port] instead of the backend's port. Cleartext only (MailSecurity.NONE):
 * command parsing needs to see the bytes. Connection counting alone would work through TLS too, but
 * the LibreMail unit tests already exercise the plaintext path, matching the existing GreenMail tests.
 */
class CountingImapProxy(private val backendHost: String, private val backendPort: Int) : AutoCloseable {

    private val server = ServerSocket(0, BACKLOG, InetAddress.getByName("127.0.0.1"))
    private val connections = AtomicInteger(0)
    private val commands = ConcurrentHashMap<String, AtomicInteger>()

    /** Client → server pump threads, tracked so tests can wait for the parsed command stream to settle. */
    private val clientPumps = Collections.synchronizedList(mutableListOf<Thread>())

    /** Accepted client-side sockets, so a test can force-drop them to simulate a server/NAT disconnect. */
    private val acceptedSockets = Collections.synchronizedList(mutableListOf<Socket>())

    @Volatile private var running = true

    /** The local port to point [ImapClient] at; it forwards to the backend. */
    val port: Int get() = server.localPort

    /** Total TCP connections the client has opened through the proxy. */
    val connectionCount: Int get() = connections.get()

    /** How many times the client issued [command] (case-insensitive), e.g. "LOGIN", "EXAMINE". */
    fun commandCount(command: String): Int = commands[command.uppercase()]?.get() ?: 0

    /** Authentication round-trips: the `LOGIN` command plus any SASL `AUTHENTICATE` (e.g. XOAUTH2). */
    fun authCommandCount(): Int = commandCount("LOGIN") + commandCount("AUTHENTICATE")

    init {
        Thread({ acceptLoop() }, "imap-proxy-accept").apply { isDaemon = true }.start()
    }

    /**
     * Joins the client → server pump threads so every command line sent before each connection closed
     * has been parsed. [ImapClient] closes its store (and thus the socket) when an operation finishes,
     * which ends the corresponding pump; call this before asserting on [commandCount]. [connectionCount]
     * needs no settling — it is incremented synchronously as each connection is accepted.
     */
    fun awaitClientStreamsSettled(timeoutMs: Long = SETTLE_TIMEOUT_MS) {
        val deadline = System.currentTimeMillis() + timeoutMs
        val snapshot = synchronized(clientPumps) { clientPumps.toList() }
        for (thread in snapshot) {
            val remaining = deadline - System.currentTimeMillis()
            if (remaining > 0) thread.join(remaining)
        }
    }

    /**
     * Force-closes every currently-accepted client socket, simulating a server idle-timeout / NAT
     * rebind / network drop of the kept-alive reused connection. The client's next use of that socket
     * then fails with an I/O error, which the connection-reuse cache should transparently reconnect
     * from. [connectionCount] keeps counting, so a subsequent reconnect makes it rise.
     */
    fun dropAcceptedConnections() {
        val snapshot = synchronized(acceptedSockets) { acceptedSockets.toList().also { acceptedSockets.clear() } }
        snapshot.forEach { runCatching { it.close() } }
    }

    override fun close() {
        running = false
        runCatching { server.close() }
    }

    private fun acceptLoop() {
        while (running) {
            val client = try {
                server.accept()
            } catch (_: IOException) {
                return // server socket closed by close()
            }
            connections.incrementAndGet()
            val backend = try {
                Socket(backendHost, backendPort)
            } catch (_: IOException) {
                runCatching { client.close() }
                continue
            }
            acceptedSockets.add(client)
            val upstream = Thread({ pumpCountingCommands(client, backend) }, "imap-proxy-up").apply { isDaemon = true }
            val downstream = Thread({ pump(backend, client) }, "imap-proxy-down").apply { isDaemon = true }
            clientPumps.add(upstream)
            upstream.start()
            downstream.start()
        }
    }

    /** Forwards client → server bytes verbatim while parsing each CRLF-terminated line as a command. */
    private fun pumpCountingCommands(from: Socket, to: Socket) {
        val buffer = ByteArray(BUFFER_SIZE)
        val line = StringBuilder()
        try {
            val input = from.getInputStream()
            val output = to.getOutputStream()
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                output.write(buffer, 0, read)
                output.flush()
                for (i in 0 until read) {
                    when (val ch = buffer[i].toInt().toChar()) {
                        '\n' -> {
                            recordCommand(line.toString())
                            line.setLength(0)
                        }
                        '\r' -> Unit
                        else -> line.append(ch)
                    }
                }
            }
        } catch (_: IOException) {
            // Peer closed; fall through to socket cleanup.
        } finally {
            runCatching { from.close() }
            runCatching { to.close() }
        }
    }

    /** Forwards server → client bytes verbatim (no parsing needed for this direction). */
    private fun pump(from: Socket, to: Socket) {
        val buffer = ByteArray(BUFFER_SIZE)
        try {
            val input = from.getInputStream()
            val output = to.getOutputStream()
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                output.write(buffer, 0, read)
                output.flush()
            }
        } catch (_: IOException) {
            // Peer closed; fall through to socket cleanup.
        } finally {
            runCatching { from.close() }
            runCatching { to.close() }
        }
    }

    /** Records the command word of an IMAP line shaped `<tag> <COMMAND> [args]`. */
    private fun recordCommand(rawLine: String) {
        val parts = rawLine.trim().split(' ', limit = 3)
        if (parts.size < 2) return
        val command = parts[1].uppercase()
        commands.computeIfAbsent(command) { AtomicInteger(0) }.incrementAndGet()
    }

    private companion object {
        const val BACKLOG = 50
        const val BUFFER_SIZE = 8192
        const val SETTLE_TIMEOUT_MS = 2_000L
    }
}
