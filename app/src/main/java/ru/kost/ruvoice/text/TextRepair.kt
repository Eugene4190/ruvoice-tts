package ru.kost.ruvoice.text

/**
 * Conservative repair of OCR/PDF word splits inside ordinary Russian book text.
 *
 * Only joins a split when the resulting form is present in the bundled AOT morphology
 * table, or when the exact repair is covered by the small reviewed exception list.
 * Real hyphenated Russian words are delegated to RuLayer.keepHyphen().
 */
internal object TextRepair {
    private val splitWord = Regex(
        """(?<![\p{L}])(?<left>[А-Яа-яЁё]{2,})[ \t]*(?<sep>[-‐‑‒–—])[ \t]*(?<right>[А-Яа-яЁё]{2,})(?![\p{L}])"""
    )
    private val splitBySpace = Regex(
        """(?<![\p{L}])(?<left>[А-Яа-яЁё]{6,})[ \t]+(?<right>[а-яё]{2,5})(?![\p{L}])"""
    )

    // Confirmed from the user's book examples and manually reviewed OCR cases.
    private val confirmed = setOf(
        "техниками",
        "информации",
        "столько",
        "запланированного",
        "энергоинформационным"
    )

    private fun known(word: String): Boolean =
        confirmed.contains(word.lowercase().replace('ё', 'е')) ||
            ((Normalizer.morph?.tags(word) ?: 0) != 0)

    private fun preserveCase(source: String, joined: String): String = when {
        source.isNotEmpty() && source.all { !it.isLetter() || it.isUpperCase() } -> joined.uppercase()
        source.firstOrNull()?.isUpperCase() == true -> joined.replaceFirstChar { it.uppercase() }
        else -> joined
    }

    fun repair(source: String, layer: TextLayer): String {
        if (layer.code != "ru" || source.isEmpty()) return source

        var result = splitWord.replace(source) { m ->
            val left = m.groups["left"]!!.value
            val right = m.groups["right"]!!.value
            val joined = left + right
            val normalized = joined.lowercase().replace('ё', 'е')
            val exact = confirmed.contains(normalized)
            val compound = layer.keepHyphen(left.lowercase(), right.lowercase())
            val morphMatch = known(joined) &&
                (Normalizer.morph?.tags(left)?.let { it == 0 } ?: false ||
                 Normalizer.morph?.tags(right)?.let { it == 0 } ?: false)

            when {
                exact -> preserveCase(left + right, joined)
                compound -> m.value
                morphMatch -> preserveCase(left + right, joined)
                else -> m.value
            }
        }

        result = splitBySpace.replace(result) { m ->
            val left = m.groups["left"]!!.value
            val right = m.groups["right"]!!.value
            val joined = left + right
            val normalized = joined.lowercase().replace('ё', 'е')
            val exact = confirmed.contains(normalized)
            val morph = Normalizer.morph
            val morphMatch = morph != null && morph.tags(joined) != 0 &&
                (morph.tags(left) == 0 || morph.tags(right) == 0)
            if (exact || morphMatch) preserveCase(left + right, joined) else m.value
        }
        return result
    }
}
