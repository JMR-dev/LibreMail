// SPDX-License-Identifier: GPL-3.0-or-later
package org.libremail.ui.compose

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import org.libremail.R
import org.libremail.richtext.BlockMarker
import org.libremail.richtext.RichLink
import org.libremail.richtext.RichSpan
import org.libremail.richtext.RichStyle
import org.libremail.richtext.RichTextContent
import org.libremail.richtext.RichTextEditing
import org.libremail.richtext.RichTextHtml

/** String-annotation tag the editor uses to carry a span's link target inside the [AnnotatedString]. */
private const val URL_TAG = "libremail:url"

/**
 * A rich-text body editor: a formatting toolbar (bold / italic / underline, bulleted + numbered
 * lists, block quote, and link) above a rounded [OutlinedTextField]. It converts its
 * [AnnotatedString] to the app's [RichTextContent] model and reports both the plaintext form and its
 * HTML — or null HTML when nothing is formatted, so an unformatted message stays plaintext-only and
 * feels exactly like the old editor.
 *
 * The field is a normal Compose text field, so TalkBack, text selection, and large system fonts all
 * work as usual; the toolbar buttons carry content descriptions and toggle state for accessibility.
 */
@Composable
fun RichTextBodyField(
    body: String,
    bodyHtml: String?,
    onBodyChange: (plain: String, html: String?) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
) {
    val linkColor = MaterialTheme.colorScheme.primary
    var value by remember { mutableStateOf(seedValue(body, bodyHtml, linkColor)) }
    // Tracks the (plain, html) we last pushed up, so an external change (draft load / signature swap)
    // re-seeds the field but our own emissions do not fight the user's cursor.
    var lastEmitted by remember { mutableStateOf(body to bodyHtml) }

    if (body to bodyHtml != lastEmitted) {
        value = seedValue(body, bodyHtml, linkColor)
        lastEmitted = body to bodyHtml
    }

    fun emit(newValue: TextFieldValue) {
        value = newValue
        val content = newValue.annotatedString.toRichContent()
        val html = if (content.hasFormatting()) RichTextHtml.toHtml(content) else null
        lastEmitted = content.text to html
        onBodyChange(content.text, html)
    }

    var showLinkDialog by remember { mutableStateOf(false) }

    Column(modifier) {
        FormattingToolbar(
            value = value,
            onToggleStyle = { style -> emit(applyStyle(value, style, linkColor)) },
            onToggleBlock = { marker -> emit(applyBlock(value, marker, linkColor)) },
            onLink = { showLinkDialog = true },
        )
        OutlinedTextField(
            value = value,
            onValueChange = ::emit,
            label = { Text(label) },
            shape = MaterialTheme.shapes.large,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text),
            modifier = Modifier.fillMaxWidth().weight(1f),
        )
    }

    if (showLinkDialog) {
        val hasSelection = value.selection.min < value.selection.max
        LinkDialog(
            enabled = hasSelection,
            onDismiss = { showLinkDialog = false },
            onConfirm = { url ->
                emit(applyLink(value, url, linkColor))
                showLinkDialog = false
            },
        )
    }
}

@Composable
private fun FormattingToolbar(
    value: TextFieldValue,
    onToggleStyle: (RichStyle) -> Unit,
    onToggleBlock: (BlockMarker) -> Unit,
    onLink: () -> Unit,
) {
    val content = value.annotatedString.toRichContent()
    val start = value.selection.min
    val end = value.selection.max
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        FormatButton(
            label = "B",
            description = stringResource(R.string.format_bold),
            active = RichTextEditing.isStyled(content, start, end, RichStyle.BOLD),
            fontWeight = FontWeight.Bold,
            onClick = { onToggleStyle(RichStyle.BOLD) },
        )
        FormatButton(
            label = "I",
            description = stringResource(R.string.format_italic),
            active = RichTextEditing.isStyled(content, start, end, RichStyle.ITALIC),
            fontStyle = FontStyle.Italic,
            onClick = { onToggleStyle(RichStyle.ITALIC) },
        )
        FormatButton(
            label = "U",
            description = stringResource(R.string.format_underline),
            active = RichTextEditing.isStyled(content, start, end, RichStyle.UNDERLINE),
            underline = true,
            onClick = { onToggleStyle(RichStyle.UNDERLINE) },
        )
        FormatButton(
            label = "•",
            description = stringResource(R.string.format_bullet_list),
            active = RichTextEditing.hasBlock(content, start, end, BlockMarker.BULLET),
            onClick = { onToggleBlock(BlockMarker.BULLET) },
        )
        FormatButton(
            label = "1.",
            description = stringResource(R.string.format_numbered_list),
            active = RichTextEditing.hasBlock(content, start, end, BlockMarker.ORDERED),
            onClick = { onToggleBlock(BlockMarker.ORDERED) },
        )
        FormatButton(
            label = "❝",
            description = stringResource(R.string.format_quote),
            active = RichTextEditing.hasBlock(content, start, end, BlockMarker.QUOTE),
            onClick = { onToggleBlock(BlockMarker.QUOTE) },
        )
        FormatButton(
            label = "🔗",
            description = stringResource(R.string.format_link),
            active = false,
            onClick = onLink,
        )
    }
}

@Composable
private fun FormatButton(
    label: String,
    description: String,
    active: Boolean,
    onClick: () -> Unit,
    fontWeight: FontWeight? = null,
    fontStyle: FontStyle? = null,
    underline: Boolean = false,
) {
    val colors = MaterialTheme.colorScheme
    val background = if (active) colors.secondaryContainer else Color.Transparent
    val textColor = if (active) colors.onSecondaryContainer else colors.onSurfaceVariant
    Box(
        modifier = Modifier
            .clip(MaterialTheme.shapes.small)
            .background(background)
            .clickable(onClick = onClick, role = Role.Button, onClickLabel = description)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            color = textColor,
            style = LocalTextStyle.current.copy(
                fontWeight = fontWeight,
                fontStyle = fontStyle,
                textDecoration = if (underline) TextDecoration.Underline else null,
            ),
        )
    }
}

@Composable
private fun LinkDialog(enabled: Boolean, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var url by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.format_link_title)) },
        text = {
            if (enabled) {
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it },
                    singleLine = true,
                    label = { Text(stringResource(R.string.format_link_url)) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    modifier = Modifier.fillMaxWidth(),
                )
            } else {
                Text(stringResource(R.string.format_link_needs_selection))
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(url.trim()) }, enabled = enabled && url.isNotBlank()) {
                Text(stringResource(R.string.format_link_apply))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

// --- editor-op plumbing (TextFieldValue <-> RichTextContent) ---

private fun applyStyle(value: TextFieldValue, style: RichStyle, linkColor: Color): TextFieldValue {
    val updated = RichTextEditing.toggleStyle(
        value.annotatedString.toRichContent(),
        value.selection.min,
        value.selection.max,
        style,
    )
    return TextFieldValue(updated.toAnnotatedString(linkColor), value.selection)
}

private fun applyBlock(value: TextFieldValue, marker: BlockMarker, linkColor: Color): TextFieldValue {
    val result = RichTextEditing.toggleBlock(
        value.annotatedString.toRichContent(),
        value.selection.min,
        value.selection.max,
        marker,
    )
    return TextFieldValue(
        result.content.toAnnotatedString(linkColor),
        TextRange(result.selectionStart, result.selectionEnd),
    )
}

private fun applyLink(value: TextFieldValue, url: String, linkColor: Color): TextFieldValue {
    val updated = RichTextEditing.applyLink(
        value.annotatedString.toRichContent(),
        value.selection.min,
        value.selection.max,
        url,
    )
    return TextFieldValue(updated.toAnnotatedString(linkColor), value.selection)
}

private fun seedValue(body: String, bodyHtml: String?, linkColor: Color): TextFieldValue {
    val content = if (bodyHtml != null) RichTextHtml.fromHtml(bodyHtml) else RichTextContent(body)
    val annotated = content.toAnnotatedString(linkColor)
    return TextFieldValue(annotated, TextRange(annotated.length))
}

/** Maps the app rich-text model onto a Compose [AnnotatedString] for display/editing. */
internal fun RichTextContent.toAnnotatedString(linkColor: Color): AnnotatedString = buildAnnotatedString {
    append(text)
    spans.forEach { span -> addStyle(spanStyleFor(span.style), span.start, span.end) }
    links.forEach { link ->
        addStyle(SpanStyle(color = linkColor), link.start, link.end)
        addStringAnnotation(URL_TAG, link.url, link.start, link.end)
    }
}

/** Maps a Compose [AnnotatedString] back to the app model, reading single-attribute span styles. */
internal fun AnnotatedString.toRichContent(): RichTextContent {
    val richSpans = spanStyles.mapNotNull { range ->
        styleOf(range.item)?.let { RichSpan(range.start, range.end, it) }
    }
    val links = getStringAnnotations(URL_TAG, 0, length).map { RichLink(it.start, it.end, it.item) }
    return RichTextContent(text, richSpans, links)
}

private fun spanStyleFor(style: RichStyle): SpanStyle = when (style) {
    RichStyle.BOLD -> SpanStyle(fontWeight = FontWeight.Bold)
    RichStyle.ITALIC -> SpanStyle(fontStyle = FontStyle.Italic)
    RichStyle.UNDERLINE -> SpanStyle(textDecoration = TextDecoration.Underline)
}

private fun styleOf(span: SpanStyle): RichStyle? = when {
    span.fontWeight == FontWeight.Bold -> RichStyle.BOLD
    span.fontStyle == FontStyle.Italic -> RichStyle.ITALIC
    span.textDecoration == TextDecoration.Underline -> RichStyle.UNDERLINE
    else -> null // e.g. the link color span, which is carried by the URL annotation instead
}
