package com.ospchat.desktop.ui

/**
 * Loader for the Android-bundled emoji set vendored under
 * `resources/emoji/emoji_category_*.csv` (Apache 2.0, ex-`androidx.emoji2-emojipicker`).
 *
 * The CSV format is one row per emoji; rows may contain comma-separated
 * variants (skin-tone × gender combinations on the People categories). We
 * expose the base glyph and the full variant list — the picker currently
 * renders just the base, but variants are kept so a long-press popup can be
 * added without re-reading the CSV.
 */
data class Emoji(
    val base: String,
    val variants: List<String>,
)

data class EmojiCategory(
    val displayName: String,
    val emojis: List<Emoji>,
)

object EmojiCatalog {
    /**
     * Order mirrors the Android `EmojiPickerView` left-to-right tab layout.
     * Each entry is `(displayName, resourcePath)`.
     */
    private val sources: List<Pair<String, String>> =
        listOf(
            "Smileys" to "emoji/emoji_category_emotions.csv",
            "People" to "emoji/emoji_category_people.csv",
            "Diverse" to "emoji/emoji_category_people_gender_inclusive.csv",
            "Animals" to "emoji/emoji_category_animals_nature.csv",
            "Food" to "emoji/emoji_category_food_drink.csv",
            "Activities" to "emoji/emoji_category_activity.csv",
            "Travel" to "emoji/emoji_category_travel_places.csv",
            "Objects" to "emoji/emoji_category_objects.csv",
            "Symbols" to "emoji/emoji_category_symbols.csv",
            "Flags" to "emoji/emoji_category_flags.csv",
        )

    val categories: List<EmojiCategory> by lazy { sources.map(::loadCategory) }

    private fun loadCategory(spec: Pair<String, String>): EmojiCategory {
        val (name, path) = spec
        val emojis =
            requireNotNull(EmojiCatalog::class.java.classLoader.getResourceAsStream(path)) {
                "Missing emoji resource: $path"
            }.bufferedReader(Charsets.UTF_8).useLines { lines ->
                lines
                    .map(String::trim)
                    .filter(String::isNotEmpty)
                    .map { row ->
                        val parts = row.split(',').map(String::trim).filter(String::isNotEmpty)
                        Emoji(base = parts.first(), variants = parts.drop(1))
                    }.toList()
            }
        return EmojiCategory(displayName = name, emojis = emojis)
    }
}
