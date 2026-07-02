// SPDX-License-Identifier: GPL-3.0-or-later
package org.libremail.data

import org.junit.Test
import kotlin.test.assertEquals

class SnippetTest {

    @Test
    fun `html tags are stripped and block breaks collapse to single spaces`() {
        assertEquals("Hello world bye", Snippet.of("<div><p>Hello <b>world</b></p><p>bye</p></div>", isHtml = true))
    }

    @Test
    fun `html style and script content never leaks into the snippet`() {
        val snippet = Snippet.of(
            "<style>.body{color:#f00}</style><script>alert('x')</script><p>Visible</p>",
            isHtml = true,
        )
        assertEquals("Visible", snippet)
    }

    @Test
    fun `html entities are decoded including numeric references`() {
        assertEquals(
            "Tom & Jerry — \"friends\" don’t fight",
            Snippet.of("Tom &amp; Jerry &mdash; &quot;friends&quot; don&#8217;t fight", isHtml = true),
        )
    }

    @Test
    fun `html nbsp becomes a plain space and runs of whitespace collapse`() {
        assertEquals("a b c", Snippet.of("a&nbsp;b\n\t  c", isHtml = true))
    }

    @Test
    fun `html snippet is capped after markup is removed`() {
        val body = "<p>" + "x".repeat(500) + "</p>"
        val snippet = Snippet.of(body, isHtml = true)
        assertEquals("x".repeat(Snippet.MAX_LENGTH), snippet)
    }

    @Test
    fun `plain text keeps literal angle brackets`() {
        assertEquals(
            "From <ada@example.org>: 3 < 5 and x > y",
            Snippet.of("From <ada@example.org>: 3 < 5 and x > y", isHtml = false),
        )
    }

    @Test
    fun `plain text is not entity-decoded`() {
        assertEquals("Fish &amp; chips", Snippet.of("Fish &amp; chips", isHtml = false))
    }

    @Test
    fun `plain text collapses whitespace and trims`() {
        assertEquals("one two three", Snippet.of("  one\r\n two\t\tthree \n", isHtml = false))
    }

    @Test
    fun `plain text is capped at the max length`() {
        assertEquals("y".repeat(Snippet.MAX_LENGTH), Snippet.of("y".repeat(1_000), isHtml = false))
    }
}
