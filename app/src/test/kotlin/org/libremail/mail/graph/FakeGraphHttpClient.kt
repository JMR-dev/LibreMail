// SPDX-License-Identifier: GPL-3.0-or-later
package org.libremail.mail.graph

/**
 * A no-network [GraphHttpClient] for unit tests: it records every [GraphRequest] it is asked to execute
 * and returns whatever the supplied [responder] produces for that call (the request plus its 0-based
 * call index), so a test can script a 429-then-200 sequence, echo a `$batch` body, or throw a
 * [GraphTransportException] — all without opening a socket.
 */
class FakeGraphHttpClient(private val responder: (request: GraphRequest, callIndex: Int) -> GraphResponse) :
    GraphHttpClient() {

    /** Every request passed to [execute], in call order — the assertion surface for call count / headers. */
    val requests = mutableListOf<GraphRequest>()

    val callCount: Int get() = requests.size

    override suspend fun execute(request: GraphRequest): GraphResponse {
        val index = requests.size
        requests += request
        return responder(request, index)
    }

    companion object {
        /** Replays [responses] in order; the last one repeats if execute is called more times than supplied. */
        fun sequence(vararg responses: GraphResponse): FakeGraphHttpClient =
            FakeGraphHttpClient { _, index -> responses[minOf(index, responses.size - 1)] }

        /** Always answers with the same [response]. */
        fun always(response: GraphResponse): FakeGraphHttpClient = FakeGraphHttpClient { _, _ -> response }
    }
}
