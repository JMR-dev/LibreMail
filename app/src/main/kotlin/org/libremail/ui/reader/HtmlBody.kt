// SPDX-License-Identifier: GPL-3.0-or-later
package org.libremail.ui.reader

import android.annotation.SuppressLint
import android.content.Intent
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewFeature
import org.libremail.domain.model.InlineImage
import java.io.ByteArrayInputStream

/**
 * Renders an HTML email body in a hardened WebView: JavaScript and file/content access are
 * disabled, links open in the system browser, and remote content is blocked until the user
 * opts in (tracking-pixel protection).
 *
 * Inline images the email carries itself (`<img src="cid:...">`, resolved via [inlineImages]) are
 * always served — they are embedded content, not a remote fetch, so they render even while remote
 * images are blocked.
 *
 * The email is wrapped with an explicit background/text/link color drawn from the active Material
 * theme so it is always readable — in dark mode the previous transparent WebView showed the
 * near-black app surface through emails whose own CSS left the text at the browser default of
 * black, rendering them black-on-black. Where the platform supports it, algorithmic darkening is
 * enabled as a backstop for emails that hardcode their own foreground colors.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun HtmlBody(
    html: String,
    loadRemoteImages: Boolean,
    inlineImages: Map<String, InlineImage>,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val colorScheme = MaterialTheme.colorScheme
    val surface = colorScheme.surface
    val onSurface = colorScheme.onSurface
    val primary = colorScheme.primary
    val isDark = surface.luminance() < 0.5f
    val surfaceArgb = surface.toArgb()
    val document = remember(html, surface, onSurface, primary) {
        wrapHtml(
            body = html,
            backgroundHex = surface.toCssHex(),
            textHex = onSurface.toCssHex(),
            linkHex = primary.toCssHex(),
            dark = isDark,
        )
    }
    // A mutable holder the WebViewClient reads on the (background) interception thread, kept current
    // by the update block so inline images that arrive after the first composition are resolvable.
    val imageHolder = remember { InlineImageHolder() }
    imageHolder.images = inlineImages
    // Tracks the content actually loaded so recompositions (star/attachment state changes) don't
    // reload the page and throw away the user's scroll position. Keyed on the fully wrapped
    // document so a theme (light/dark) change still re-renders with the new colors, and on the set
    // of available cid: keys so the page reloads once when inline images finish resolving.
    val lastLoaded = remember { mutableStateOf<Triple<String, Boolean, Set<String>>?>(null) }
    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            WebView(ctx).apply {
                with(settings) {
                    javaScriptEnabled = false
                    allowFileAccess = false
                    allowContentAccess = false
                    domStorageEnabled = false
                    cacheMode = WebSettings.LOAD_NO_CACHE
                    setSupportZoom(true)
                    builtInZoomControls = true
                    displayZoomControls = false
                }
                setBackgroundColor(surfaceArgb)
                applyAlgorithmicDarkening(isDark)
                isVerticalScrollBarEnabled = true
                webViewClient = object : WebViewClient() {
                    override fun shouldInterceptRequest(
                        view: WebView?,
                        request: WebResourceRequest?,
                    ): WebResourceResponse? {
                        // Serve inline images the email embedded itself (cid:) from the message's own
                        // parts; everything else falls through to normal (remote-blockable) loading.
                        val image = request?.url?.toString()?.let { resolveInlineImage(it, imageHolder.images) }
                            ?: return null
                        return WebResourceResponse(image.mimeType, null, ByteArrayInputStream(image.bytes))
                    }

                    override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                        val url = request?.url ?: return false
                        // Only open ordinary web/mail links, and only on an actual user tap — never
                        // auto-launch arbitrary intent:/market:/custom-scheme URIs (e.g. via a
                        // meta-refresh in a malicious email) or navigate inside the WebView.
                        val scheme = url.scheme?.lowercase()
                        if (scheme != "http" && scheme != "https" && scheme != "mailto") return true
                        if (!request.hasGesture()) return true
                        runCatching {
                            context.startActivity(
                                Intent(Intent.ACTION_VIEW, url).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                            )
                        }
                        return true
                    }
                }
            }
        },
        update = { webView ->
            // Re-apply theme-dependent state so toggling light/dark while the reader is open updates
            // the chrome behind the (padding of the) page as well as the content.
            webView.setBackgroundColor(surfaceArgb)
            webView.applyAlgorithmicDarkening(isDark)
            webView.settings.blockNetworkLoads = !loadRemoteImages
            val key = Triple(document, loadRemoteImages, inlineImages.keys.toSet())
            if (lastLoaded.value != key) {
                lastLoaded.value = key
                webView.loadDataWithBaseURL(null, document, "text/html", "UTF-8", null)
            }
        },
    )
}

/** Mutable, thread-visible reference to the current inline images (read from the interception thread). */
private class InlineImageHolder {
    @Volatile
    var images: Map<String, InlineImage> = emptyMap()
}

/**
 * Extracts and normalizes the `Content-ID` from a `cid:` URL (surrounding angle brackets stripped),
 * or null when [url] is not a `cid:` reference. Kept separate from Android types so it is unit-testable.
 */
internal fun cidKey(url: String): String? {
    if (!url.startsWith("cid:", ignoreCase = true)) return null
    return url.substring(CID_PREFIX_LENGTH).trim().trim('<', '>').trim().takeUnless { it.isBlank() }
}

/** Resolves a `cid:` [url] to its [InlineImage] among [images] (keyed by normalized Content-ID), or null. */
internal fun resolveInlineImage(url: String, images: Map<String, InlineImage>): InlineImage? =
    cidKey(url)?.let { images[it] }

private const val CID_PREFIX_LENGTH = 4

/**
 * Lets the WebView algorithmically darken email content that does not declare its own dark support,
 * but only in dark mode and only where the installed WebView supports the feature. This is a
 * best-effort backstop; readability is already guaranteed by the explicit colors in [wrapHtml].
 */
private fun WebView.applyAlgorithmicDarkening(dark: Boolean) {
    if (WebViewFeature.isFeatureSupported(WebViewFeature.ALGORITHMIC_DARKENING)) {
        WebSettingsCompat.setAlgorithmicDarkeningAllowed(settings, dark)
    }
}

private const val RGB_MASK = 0xFFFFFF

/** The color as a CSS `#RRGGBB` string (alpha dropped — email backgrounds/text are opaque). */
internal fun Color.toCssHex(): String = "#%06X".format(toArgb() and RGB_MASK)

internal fun wrapHtml(body: String, backgroundHex: String, textHex: String, linkHex: String, dark: Boolean): String {
    val scheme = if (dark) "dark" else "light"
    return """
    <html>
    <head>
      <meta name="viewport" content="width=device-width, initial-scale=1">
      <meta http-equiv="Content-Security-Policy" content="default-src 'none'; img-src http: https: data: cid:; style-src 'unsafe-inline'; font-src data:">
      <style>
        :root { color-scheme: $scheme; }
        html, body { background-color: $backgroundHex; color: $textHex; }
        body { font-family: sans-serif; line-height: 1.5; padding: 16px; word-wrap: break-word; }
        img { max-width: 100%; height: auto; }
        a { color: $linkHex; }
      </style>
    </head>
    <body>$body</body>
    </html>
    """.trimIndent()
}
