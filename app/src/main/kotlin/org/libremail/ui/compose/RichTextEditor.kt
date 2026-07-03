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
import androidx.compose.ui.text.ParagraphStyle
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.libremail.R
import org.libremail.richtext.BlockMarker
import org.libremail.richtext.RichAlign
import org.libremail.richtext.RichAlignment
import org.libremail.richtext.RichBaseStyle
import org.libremail.richtext.RichImage
import org.libremail.richtext.RichLink
import org.libremail.richtext.RichSpan
import org.libremail.richtext.RichStyle
import org.libremail.richtext.RichTextContent
import org.libremail.richtext.RichTextEditing
import org.libremail.richtext.RichTextHtml
import org.libremail.ui.compose.format.ColorSwatch
import org.libremail.ui.compose.format.ColorSwatchRow
import org.libremail.ui.compose.format.FontSizePicker

/** String-annotation tag the editor uses to carry a span's link target inside the [AnnotatedString]. */
private const val URL_TAG = "libremail:url"

/**
 * String-annotation tag carrying a parameterized style's identity (see [encodeStyle]), so e.g. a
 * [RichStyle.FontColor] span can never be confused with the link-color paint or any other span that
 * happens to share its visual [SpanStyle].
 */
internal const val STYLE_TAG = "libremail:style"

/** String-annotation tag carrying an inline image's content id and display name over its token. */
internal const val IMAGE_TAG = "libremail:image"

/**
 * A rich-text body editor: a formatting toolbar (bold / italic / underline / strikethrough, font
 * size, font color and highlight, bulleted + numbered lists, block quote, and link) above a rounded
 * [OutlinedTextField]. It converts its [AnnotatedString] to the app's [RichTextContent] model and
 * reports both the plaintext form and its HTML — or null HTML when nothing is formatted, so an
 * unformatted message stays plaintext-only and feels exactly like the old editor.
 *
 * The field is a normal Compose text field, so TalkBack, text selection, and large system fonts all
 * work as usual; each toolbar button exposes its accessible action label via `onClickLabel` on its
 * [Modifier.clickable] (not a `contentDescription`), and still carries toggle state for accessibility.
 * The font-color and highlight buttons open a [ColorPickerDialog] built on the shared
 * [ColorSwatchRow], whose individual swatches carry their own `contentDescription` instead; the font
 * size button opens the self-contained [FontSizePicker] dropdown.
 *
 * [resolveFont] maps a CSS font-family stack to a Compose [FontFamily] for display; the default
 * resolves nothing, leaving the system font (the model still round-trips the CSS value untouched).
 */
@Composable
fun RichTextBodyField(
    body: String,
    bodyHtml: String?,
    onBodyChange: (plain: String, html: String?) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    resolveFont: (String) -> FontFamily? = { null },
) {
    val linkColor = MaterialTheme.colorScheme.primary
    val seed = remember { seedContent(body, bodyHtml) }
    var value by remember { mutableStateOf(seed.toSeededValue(linkColor, resolveFont)) }
    // The message-wide base style is position-independent, so it lives outside the AnnotatedString
    // (which only carries positioned spans) and is recombined with the field's content on emit.
    var baseStyle by remember { mutableStateOf(seed.baseStyle) }
    // Tracks the (plain, html) we last pushed up, so an external change (draft load / signature swap)
    // re-seeds the field but our own emissions do not fight the user's cursor.
    var lastEmitted by remember { mutableStateOf(body to bodyHtml) }

    if (body to bodyHtml != lastEmitted) {
        val content = seedContent(body, bodyHtml)
        value = content.toSeededValue(linkColor, resolveFont)
        baseStyle = content.baseStyle
        lastEmitted = body to bodyHtml
    }

    fun emit(newValue: TextFieldValue) {
        value = newValue
        val content = newValue.annotatedString.toRichContent(baseStyle)
        val html = if (content.hasFormatting()) RichTextHtml.toHtml(content) else null
        lastEmitted = content.text to html
        onBodyChange(content.text, html)
    }

    var showLinkDialog by remember { mutableStateOf(false) }
    var showFontColorPicker by remember { mutableStateOf(false) }
    var showHighlightPicker by remember { mutableStateOf(false) }

    Column(modifier) {
        FormattingToolbar(
            value = value,
            onToggleStyle = { style -> emit(applyStyle(value, style, linkColor, resolveFont)) },
            onToggleBlock = { marker -> emit(applyBlock(value, marker, linkColor, resolveFont)) },
            onLink = { showLinkDialog = true },
            onFontColor = { showFontColorPicker = true },
            onHighlight = { showHighlightPicker = true },
            onFontSize = { pt ->
                emit(
                    if (pt != null) {
                        applyStyle(value, RichStyle.FontSize(pt), linkColor, resolveFont)
                    } else {
                        clearStyle(value, RichStyle.FontSize::class.java, linkColor, resolveFont)
                    },
                )
            },
        )
        OutlinedTextField(
            value = value,
            onValueChange = ::emit,
            label = { Text(label) },
            shape = MaterialTheme.shapes.large,
            textStyle = applyBaseStyle(LocalTextStyle.current, baseStyle, resolveFont),
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
                emit(applyLink(value, url, linkColor, resolveFont))
                showLinkDialog = false
            },
        )
    }

    if (showFontColorPicker) {
        val current = RichTextEditing.styleAt(
            value.annotatedString.toRichContent(),
            value.selection.min,
            value.selection.max,
            RichStyle.FontColor::class.java,
        )
        ColorPickerDialog(
            title = stringResource(R.string.format_color),
            swatches = fontColorSwatches(),
            selectedArgb = current?.argb,
            onDismiss = { showFontColorPicker = false },
            onSelect = { argb ->
                emit(
                    if (argb != null) {
                        applyStyle(value, RichStyle.FontColor(argb), linkColor, resolveFont)
                    } else {
                        clearStyle(value, RichStyle.FontColor::class.java, linkColor, resolveFont)
                    },
                )
                showFontColorPicker = false
            },
        )
    }

    if (showHighlightPicker) {
        val current = RichTextEditing.styleAt(
            value.annotatedString.toRichContent(),
            value.selection.min,
            value.selection.max,
            RichStyle.Highlight::class.java,
        )
        ColorPickerDialog(
            title = stringResource(R.string.format_highlight),
            swatches = highlightSwatches(),
            selectedArgb = current?.argb,
            onDismiss = { showHighlightPicker = false },
            onSelect = { argb ->
                emit(
                    if (argb != null) {
                        applyStyle(value, RichStyle.Highlight(argb), linkColor, resolveFont)
                    } else {
                        clearStyle(value, RichStyle.Highlight::class.java, linkColor, resolveFont)
                    },
                )
                showHighlightPicker = false
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
    onFontColor: () -> Unit,
    onHighlight: () -> Unit,
    onFontSize: (Int?) -> Unit,
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
            active = RichTextEditing.isStyled(content, start, end, RichStyle.Bold),
            fontWeight = FontWeight.Bold,
            onClick = { onToggleStyle(RichStyle.Bold) },
        )
        FormatButton(
            label = "I",
            description = stringResource(R.string.format_italic),
            active = RichTextEditing.isStyled(content, start, end, RichStyle.Italic),
            fontStyle = FontStyle.Italic,
            onClick = { onToggleStyle(RichStyle.Italic) },
        )
        FormatButton(
            label = "U",
            description = stringResource(R.string.format_underline),
            active = RichTextEditing.isStyled(content, start, end, RichStyle.Underline),
            underline = true,
            onClick = { onToggleStyle(RichStyle.Underline) },
        )
        FormatButton(
            label = "S",
            description = stringResource(R.string.format_strikethrough),
            active = RichTextEditing.isStyled(content, start, end, RichStyle.Strikethrough),
            strikethrough = true,
            onClick = { onToggleStyle(RichStyle.Strikethrough) },
        )
        val fontColorArgb = RichTextEditing.styleAt(content, start, end, RichStyle.FontColor::class.java)?.argb
        FormatButton(
            label = "A",
            description = stringResource(R.string.format_color),
            active = fontColorArgb != null,
            tint = fontColorArgb?.let { Color(it) },
            onClick = onFontColor,
        )
        val highlightArgb = RichTextEditing.styleAt(content, start, end, RichStyle.Highlight::class.java)?.argb
        FormatButton(
            label = "H",
            description = stringResource(R.string.format_highlight),
            active = false,
            swatchColor = highlightArgb?.let { Color(it) },
            onClick = onHighlight,
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
        // The font-size dropdown trails every glyph button on purpose. The toolbar overflows the
        // screen width and scrolls horizontally, and the compose E2E taps the "•" bullet button
        // *without* scrolling first (see ComposeScreenTest.formattingToolbar_bulletButtonMarksTheLine...),
        // so its click lands on the button's on-screen center. Any control inserted *before* the block
        // buttons shifts them right and can push the bullet past the viewport, making that tap miss —
        // so this wider control is appended last, leaving every pre-existing button in its tested spot.
        val fontSizePt = RichTextEditing.styleAt(content, start, end, RichStyle.FontSize::class.java)?.pt
        FontSizePicker(selectedPt = fontSizePt, onSelect = onFontSize)
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
    strikethrough: Boolean = false,
    tint: Color? = null,
    swatchColor: Color? = null,
) {
    val colors = MaterialTheme.colorScheme
    val background = swatchColor ?: if (active) colors.secondaryContainer else Color.Transparent
    val textColor = tint ?: if (active) colors.onSecondaryContainer else colors.onSurfaceVariant
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
                textDecoration = when {
                    underline -> TextDecoration.Underline
                    strikethrough -> TextDecoration.LineThrough
                    else -> null
                },
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

/**
 * A color-swatch picker shared by the font-color and highlight toolbar buttons. [title] names which
 * one; [swatches] is the fixed palette; [selectedArgb] rings the swatch (if any) uniformly applied
 * over the current selection. Tapping any swatch - or the built-in "no color" entry, which
 * [ColorSwatchRow] reports as a null argument - calls [onSelect]; the caller applies the change and
 * dismisses, mirroring how [LinkDialog]'s `onConfirm` is wired.
 */
@Composable
private fun ColorPickerDialog(
    title: String,
    swatches: List<ColorSwatch>,
    selectedArgb: Int?,
    onDismiss: () -> Unit,
    onSelect: (Int?) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            ColorSwatchRow(
                swatches = swatches,
                selectedArgb = selectedArgb,
                onSelect = onSelect,
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

/** ~8 fixed font colors for the font-color picker; "no color" is [ColorSwatchRow]'s own leading entry. */
@Composable
private fun fontColorSwatches(): List<ColorSwatch> = listOf(
    ColorSwatch(0xFF000000.toInt(), stringResource(R.string.format_color_black)),
    ColorSwatch(0xFF616161.toInt(), stringResource(R.string.format_color_gray)),
    ColorSwatch(0xFFD32F2F.toInt(), stringResource(R.string.format_color_red)),
    ColorSwatch(0xFFEF6C00.toInt(), stringResource(R.string.format_color_orange)),
    ColorSwatch(0xFFF9A825.toInt(), stringResource(R.string.format_color_yellow)),
    ColorSwatch(0xFF2E7D32.toInt(), stringResource(R.string.format_color_green)),
    ColorSwatch(0xFF1565C0.toInt(), stringResource(R.string.format_color_blue)),
    ColorSwatch(0xFF6A1B9A.toInt(), stringResource(R.string.format_color_purple)),
)

/** Typical marker colors for the highlight picker; "none" is [ColorSwatchRow]'s own leading entry. */
@Composable
private fun highlightSwatches(): List<ColorSwatch> = listOf(
    ColorSwatch(0xFFFFF59D.toInt(), stringResource(R.string.format_highlight_yellow)),
    ColorSwatch(0xFFA5D6A7.toInt(), stringResource(R.string.format_highlight_green)),
    ColorSwatch(0xFF80DEEA.toInt(), stringResource(R.string.format_highlight_cyan)),
    ColorSwatch(0xFFF48FB1.toInt(), stringResource(R.string.format_highlight_pink)),
)

// --- editor-op plumbing (TextFieldValue <-> RichTextContent) ---

/** Toggles [style] over the selection and rebuilds the field value (one-liner for toolbar wiring). */
internal fun applyStyle(
    value: TextFieldValue,
    style: RichStyle,
    linkColor: Color,
    resolveFont: (String) -> FontFamily? = { null },
): TextFieldValue {
    val updated = RichTextEditing.toggleStyle(
        value.annotatedString.toRichContent(),
        value.selection.min,
        value.selection.max,
        style,
    )
    return TextFieldValue(updated.toAnnotatedString(linkColor, resolveFont), value.selection)
}

internal fun applyBlock(
    value: TextFieldValue,
    marker: BlockMarker,
    linkColor: Color,
    resolveFont: (String) -> FontFamily?,
): TextFieldValue {
    val result = RichTextEditing.toggleBlock(
        value.annotatedString.toRichContent(),
        value.selection.min,
        value.selection.max,
        marker,
    )
    return TextFieldValue(
        result.content.toAnnotatedString(linkColor, resolveFont),
        TextRange(result.selectionStart, result.selectionEnd),
    )
}

internal fun applyLink(
    value: TextFieldValue,
    url: String,
    linkColor: Color,
    resolveFont: (String) -> FontFamily?,
): TextFieldValue {
    val updated = RichTextEditing.applyLink(
        value.annotatedString.toRichContent(),
        value.selection.min,
        value.selection.max,
        url,
    )
    return TextFieldValue(updated.toAnnotatedString(linkColor, resolveFont), value.selection)
}

/**
 * Clears every span of style kind [kind] over the selection - the color pickers' "no color"/"none"
 * entry, which must remove the style outright regardless of its value. [applyStyle] cannot do this:
 * it delegates to [RichTextEditing.toggleStyle], which only clears when the selection is already
 * uniformly one exact value, and otherwise applies that value instead of removing it.
 */
internal fun <T : RichStyle> clearStyle(
    value: TextFieldValue,
    kind: Class<T>,
    linkColor: Color,
    resolveFont: (String) -> FontFamily? = { null },
): TextFieldValue {
    val start = value.selection.min
    val end = value.selection.max
    if (start >= end) return value
    val content = value.annotatedString.toRichContent()
    val kept = content.spans.flatMap { span ->
        when {
            !kind.isInstance(span.style) || span.end <= start || span.start >= end -> listOf(span)
            else -> buildList {
                if (span.start < start) add(span.copy(end = start))
                if (span.end > end) add(span.copy(start = end))
            }
        }
    }
    val updated = content.copy(spans = kept.sortedBy { it.start })
    return TextFieldValue(updated.toAnnotatedString(linkColor, resolveFont), value.selection)
}

private fun seedContent(body: String, bodyHtml: String?): RichTextContent =
    if (bodyHtml != null) RichTextHtml.fromHtml(bodyHtml) else RichTextContent(body)

private fun RichTextContent.toSeededValue(linkColor: Color, resolveFont: (String) -> FontFamily?): TextFieldValue {
    val annotated = toAnnotatedString(linkColor, resolveFont)
    return TextFieldValue(annotated, TextRange(annotated.length))
}

/** Applies the message-wide base font family/size on top of the field's ambient text style. */
internal fun applyBaseStyle(
    textStyle: TextStyle,
    base: RichBaseStyle?,
    resolveFont: (String) -> FontFamily?,
): TextStyle {
    if (base == null) return textStyle
    return textStyle.copy(
        fontFamily = base.fontCss?.let(resolveFont) ?: textStyle.fontFamily,
        fontSize = base.fontSizePt?.let { it.sp } ?: textStyle.fontSize,
    )
}

/** Maps the app rich-text model onto a Compose [AnnotatedString] for display/editing. */
internal fun RichTextContent.toAnnotatedString(
    linkColor: Color,
    resolveFont: (String) -> FontFamily? = { null },
): AnnotatedString = buildAnnotatedString {
    append(text)
    spans.forEach { span ->
        addStyle(spanStyleFor(span.style, resolveFont), span.start, span.end)
        encodeStyle(span.style)?.let { addStringAnnotation(STYLE_TAG, it, span.start, span.end) }
    }
    links.forEach { link ->
        addStyle(SpanStyle(color = linkColor), link.start, link.end)
        addStringAnnotation(URL_TAG, link.url, link.start, link.end)
    }
    images.forEach { image ->
        addStringAnnotation(IMAGE_TAG, encodeImage(image), image.start, image.end)
    }
    disjoint(alignments).forEach { alignment ->
        addStyle(ParagraphStyle(textAlign = alignment.align.toTextAlign()), alignment.start, alignment.end)
    }
}

/**
 * Maps a Compose [AnnotatedString] back to the app model. Simple styles are recovered from their
 * single-attribute [SpanStyle]s; parameterized styles and images from their string annotations
 * (their visual paint is deliberately ignored, so the link color can never masquerade as a
 * [RichStyle.FontColor]). [baseStyle] is position-independent and rides alongside unchanged.
 */
internal fun AnnotatedString.toRichContent(baseStyle: RichBaseStyle? = null): RichTextContent {
    val simple = spanStyles.mapNotNull { range ->
        simpleStyleOf(range.item)?.let { RichSpan(range.start, range.end, it) }
    }
    val parameterized = getStringAnnotations(STYLE_TAG, 0, length).mapNotNull { range ->
        decodeStyle(range.item)?.let { RichSpan(range.start, range.end, it) }
    }
    val links = getStringAnnotations(URL_TAG, 0, length).map { RichLink(it.start, it.end, it.item) }
    val images = getStringAnnotations(IMAGE_TAG, 0, length).mapNotNull(::decodeImage)
    val alignments = paragraphStyles.mapNotNull { range ->
        range.item.textAlign.toRichAlign()?.let { RichAlignment(range.start, range.end, it) }
    }
    return RichTextContent(
        text = text,
        spans = (simple + parameterized).sortedBy { it.start },
        links = links,
        alignments = alignments,
        images = images,
        baseStyle = baseStyle,
    )
}

/** The visual paint for [style]; identity is carried separately (see [STYLE_TAG]). */
private fun spanStyleFor(style: RichStyle, resolveFont: (String) -> FontFamily?): SpanStyle = when (style) {
    RichStyle.Bold -> SpanStyle(fontWeight = FontWeight.Bold)
    RichStyle.Italic -> SpanStyle(fontStyle = FontStyle.Italic)
    RichStyle.Underline -> SpanStyle(textDecoration = TextDecoration.Underline)
    RichStyle.Strikethrough -> SpanStyle(textDecoration = TextDecoration.LineThrough)
    is RichStyle.FontFamily -> SpanStyle(fontFamily = resolveFont(style.css))
    is RichStyle.FontSize -> SpanStyle(fontSize = style.pt.sp)
    is RichStyle.FontColor -> SpanStyle(color = Color(style.argb))
    is RichStyle.Highlight -> SpanStyle(background = Color(style.argb))
}

private fun simpleStyleOf(span: SpanStyle): RichStyle? = when {
    span.fontWeight == FontWeight.Bold -> RichStyle.Bold
    span.fontStyle == FontStyle.Italic -> RichStyle.Italic
    span.textDecoration == TextDecoration.Underline -> RichStyle.Underline
    span.textDecoration == TextDecoration.LineThrough -> RichStyle.Strikethrough
    else -> null // parameterized styles and the link paint are carried by string annotations instead
}

private const val STYLE_FAMILY = "family"
private const val STYLE_SIZE = "size"
private const val STYLE_COLOR = "color"
private const val STYLE_HIGHLIGHT = "highlight"

/** Compact identity payload for parameterized styles; simple styles need none. */
private fun encodeStyle(style: RichStyle): String? = when (style) {
    RichStyle.Bold, RichStyle.Italic, RichStyle.Underline, RichStyle.Strikethrough -> null
    is RichStyle.FontFamily -> "$STYLE_FAMILY:${style.css}"
    is RichStyle.FontSize -> "$STYLE_SIZE:${style.pt}"
    is RichStyle.FontColor -> "$STYLE_COLOR:${style.argb}"
    is RichStyle.Highlight -> "$STYLE_HIGHLIGHT:${style.argb}"
}

private fun decodeStyle(payload: String): RichStyle? {
    val value = payload.substringAfter(':', "")
    return when (payload.substringBefore(':')) {
        STYLE_FAMILY -> RichStyle.FontFamily(value)
        STYLE_SIZE -> value.toIntOrNull()?.let { RichStyle.FontSize(it) }
        STYLE_COLOR -> value.toIntOrNull()?.let { RichStyle.FontColor(it) }
        STYLE_HIGHLIGHT -> value.toIntOrNull()?.let { RichStyle.Highlight(it) }
        else -> null
    }
}

private const val IMAGE_SEPARATOR = '\n'

private fun encodeImage(image: RichImage): String = image.contentId + IMAGE_SEPARATOR + image.name

private fun decodeImage(range: AnnotatedString.Range<String>): RichImage? {
    val sep = range.item.indexOf(IMAGE_SEPARATOR)
    if (sep < 0) return null
    return RichImage(range.start, range.end, range.item.take(sep), range.item.substring(sep + 1))
}

private fun RichAlign.toTextAlign(): TextAlign = when (this) {
    RichAlign.START -> TextAlign.Start
    RichAlign.CENTER -> TextAlign.Center
    RichAlign.END -> TextAlign.End
}

private fun TextAlign.toRichAlign(): RichAlign? = when (this) {
    TextAlign.Start, TextAlign.Left -> RichAlign.START
    TextAlign.Center -> RichAlign.CENTER
    TextAlign.End, TextAlign.Right -> RichAlign.END
    else -> null
}

/** Paragraph styles must not overlap inside an [AnnotatedString]; sorts and drops any that would. */
private fun disjoint(alignments: List<RichAlignment>): List<RichAlignment> {
    val result = ArrayList<RichAlignment>(alignments.size)
    for (alignment in alignments.sortedBy { it.start }) {
        if (alignment.start < alignment.end && (result.isEmpty() || alignment.start >= result.last().end)) {
            result.add(alignment)
        }
    }
    return result
}
