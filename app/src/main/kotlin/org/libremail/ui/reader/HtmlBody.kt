// SPDX-License-Identifier: GPL-3.0-or-later
package org.libremail.ui.reader

import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Color
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView

/**
 * Renders an HTML email body in a hardened WebView: JavaScript and file/content access are
 * disabled, links open in the system browser, and remote content is blocked until the user
 * opts in (tracking-pixel protection).
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun HtmlBody(
    html: String,
    loadRemoteImages: Boolean,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
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
                setBackgroundColor(Color.TRANSPARENT)
                isVerticalScrollBarEnabled = true
                webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                        val url = request?.url ?: return false
                        runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, url)) }
                        return true
                    }
                }
            }
        },
        update = { webView ->
            webView.settings.blockNetworkLoads = !loadRemoteImages
            webView.loadDataWithBaseURL(null, wrapHtml(html), "text/html", "UTF-8", null)
        },
    )
}

private fun wrapHtml(body: String): String =
    """
    <html>
    <head>
      <meta name="viewport" content="width=device-width, initial-scale=1">
      <style>
        body { font-family: sans-serif; line-height: 1.5; padding: 16px; word-wrap: break-word; }
        img { max-width: 100%; height: auto; }
        a { color: #0B57D0; }
      </style>
    </head>
    <body>$body</body>
    </html>
    """.trimIndent()
