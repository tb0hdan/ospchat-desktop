package com.ospchat.desktop.ui

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.platform.Font
import androidx.compose.ui.text.withStyle

// Most Linux desktops (and headless CI hosts) ship no color emoji font, so
// Skia falls back to a monochrome contour glyph for every pictograph. Bundle
// Noto Color Emoji (SIL OFL 1.1) and route emoji-bearing text through it.
object EmojiFont {
    val family: FontFamily by lazy {
        FontFamily(Font(resource = "fonts/NotoColorEmoji.ttf"))
    }
}

// Wraps emoji-codepoint runs in a SpanStyle that targets [EmojiFont.family],
// leaving prose runs to inherit whatever style the caller already set. Skia
// per-glyph fallback would also work for many cases, but explicit spans keep
// rendering predictable across hosts whose system FontMgr is missing or odd.
fun emojiAware(text: String): AnnotatedString {
    if (text.isEmpty()) return AnnotatedString(text)
    val style = SpanStyle(fontFamily = EmojiFont.family)
    return buildAnnotatedString {
        var i = 0
        var runStart = -1
        while (i < text.length) {
            val cp = text.codePointAt(i)
            val width = Character.charCount(cp)
            if (isEmojiCodePoint(cp)) {
                if (runStart < 0) runStart = i
            } else {
                if (runStart >= 0) {
                    withStyle(style) { append(text.substring(runStart, i)) }
                    runStart = -1
                }
                append(text.substring(i, i + width))
            }
            i += width
        }
        if (runStart >= 0) {
            withStyle(style) { append(text.substring(runStart)) }
        }
    }
}

// Over-inclusive on purpose: false positives just route a non-emoji symbol
// through NotoColorEmoji, where Skia will then fall back anyway. False
// negatives would render an emoji in the monochrome system font — the bug
// we're fixing.
private fun isEmojiCodePoint(cp: Int): Boolean {
    if (cp == 0x200D || cp == 0xFE0E || cp == 0xFE0F || cp == 0x20E3) return true
    if (cp in 0xE0020..0xE007F) return true
    if (cp in 0x1F000..0x1FFFF) return true
    if (cp in 0x2600..0x27BF) return true
    if (cp in 0x2300..0x23FF) return true
    if (cp in 0x2B00..0x2BFF) return true
    return false
}
