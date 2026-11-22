package ru.kost.ruvoice.text

/**
 * Conservative repair of OCR/PDF word splits inside ordinary Russian book text.
 *
 * Only joins a split when the resulting form is present in the bundled AOT morphology
 * table, or when the exact repair is covered by the small reviewed exception list.
 * Real hyphenated Russian words are delegated to RuLayer.keepHyphen().
 *
 * The mapped form keeps every output character tied to its original input position,
 * so Android TTS word highlighting still points to the full source spelling.
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

    private fun identity(source: String): SpanText.Mapped =
        SpanText.Mapped(source, IntArray(source.length + 1) { it })

    /** Apply one split pattern, composing its output-to-source offsets with the incoming map. */
    private fun joinMatches(
        input: SpanText.Mapped,
        regex: Regex,
        shouldJoin: (left: String, right: String) -> Boolean
    ): SpanText.Mapped {
        val source = input.text
        val out = StringBuilder(source.length)
        val offsets = ArrayList<Int>(source.length + 1)

        fun keep(from: Int, until: Int) {
            for (i in from until until) {
                out.append(source[i])
                offsets += input.orig(i)
            }
        }

        var at = 0
        for (match in regex.findAll(source)) {
            keep(at, match.range.first)
            val leftGroup = match.groups["left"]!!
            val rightGroup = match.groups["right"]!!
            val left = leftGroup.value
            val right = rightGroup.value
            if (shouldJoin(left, right)) {
                val joined = preserveCase(left + right, left + right)
                out.append(joined)
                for (i in leftGroup.range) offsets += input.orig(i)
                for (i in rightGroup.range) offsets += input.orig(i)
            } else {
                keep(match.range.first, match.range.last + 1)
            }
            at = match.range.last + 1
        }
        keep(at, source.length)
        offsets += input.orig(source.length)
        return SpanText.Mapped(out.toString(), offsets.toIntArray())
    }

    fun mapped(source: String, layer: TextLayer): SpanText.Mapped {
        if (layer.code != "ru" || source.isEmpty()) return identity(source)

        var result = joinMatches(identity(source), splitWord) { left, right ->
            val normalized = (left + right).lowercase().replace('ё', 'е')
            val exact = confirmed.contains(normalized)
            val compound = layer.keepHyphen(left.lowercase(), right.lowercase())
            val morphMatch = known(left + right) &&
                (Normalizer.morph?.tags(left)?.let { it == 0 } ?: false ||
                 Normalizer.morph?.tags(right)?.let { it == 0 } ?: false)
            exact || (!compound && morphMatch)
        }

        result = joinMatches(result, splitBySpace) { left, right ->
            val normalized = (left + right).lowercase().replace('ё', 'е')
            val exact = confirmed.contains(normalized)
            val morph = Normalizer.morph
            val morphMatch = morph != null && morph.tags(left + right) != 0 &&
                (morph.tags(left) == 0 || morph.tags(right) == 0)
            exact || morphMatch
        }
        return result
    }

    fun repair(source: String, layer: TextLayer): String = mapped(source, layer).text
}
