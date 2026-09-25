package ru.arc.itemlore

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.format.TextDecoration
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import java.text.Normalizer

/** Validation and lore construction are kept Bukkit-free so their rules are easy to test. */
internal object ItemLorePolicy {
    const val MAX_ROWS = 6
    const val MAX_CODE_POINTS_PER_ROW = 80

    private val plain = PlainTextComponentSerializer.plainText()
    private val defaultGap = Component.empty().decoration(TextDecoration.ITALIC, false)

    data class Draft(
        val lore: List<Component>?,
        val visibleRows: List<String>,
        val editDistance: Int,
    )

    sealed interface Result {
        data class Ready(val draft: Draft) : Result
        data class Rejected(val reason: Reason) : Result
    }

    enum class Reason { TOO_MANY_EXISTING_ROWS, EXISTING_ROW_TOO_LONG, TOO_MANY_FIELDS, ROW_TOO_LONG, MULTILINE_INPUT }

    fun originalRows(lore: List<Component>?): List<Component> = lore.orEmpty().let { rows ->
        if (rows.firstOrNull()?.let { plain.serialize(it).isEmpty() } == true) rows.drop(1) else rows
    }

    fun build(existingLore: List<Component>?, submitted: List<String>): Result {
        val originals = originalRows(existingLore)
        if (originals.size > MAX_ROWS) return Result.Rejected(Reason.TOO_MANY_EXISTING_ROWS)
        if (originals.any { codePoints(plain.serialize(it)) > MAX_CODE_POINTS_PER_ROW }) {
            return Result.Rejected(Reason.EXISTING_ROW_TOO_LONG)
        }
        if (submitted.size > MAX_ROWS) return Result.Rejected(Reason.TOO_MANY_FIELDS)
        if (submitted.any { codePoints(it) > MAX_CODE_POINTS_PER_ROW }) return Result.Rejected(Reason.ROW_TOO_LONG)
        if (submitted.any { value -> value.any { it == '\n' || it == '\r' || it.isISOControl() } }) {
            return Result.Rejected(Reason.MULTILINE_INPUT)
        }

        // An all-empty form is the explicit clear action, including when the
        // original body had decorative blank rows. Otherwise keep unchanged
        // empty rows inside the original body. Empty form slots
        // beyond its end are just unused capacity; blanking a non-empty original
        // row removes it and later rows compact naturally.
        val body = if (submitted.isNotEmpty() && submitted.all(String::isBlank)) emptyList() else submitted.mapIndexedNotNull { index, value ->
            val original = originals.getOrNull(index)
            if (original != null && normalizeVisible(plain.serialize(original)) == normalizeVisible(value)) original
            else if (value.isBlank()) null
            else Component.text(value, NamedTextColor.WHITE).decoration(TextDecoration.ITALIC, false)
        }
        val originalVisible = originals.map(plain::serialize)
        val newVisible = body.map(plain::serialize)
        val distance = levenshtein(
            canonical(originalVisible),
            canonical(newVisible),
        )
        val lore = when {
            distance == 0 -> existingLore
            body.isEmpty() -> null
            else -> listOf(existingLore?.firstOrNull()?.takeIf { row -> plain.serialize(row).isEmpty() } ?: defaultGap) + body
        }
        return Result.Ready(Draft(lore = lore, visibleRows = newVisible, editDistance = distance))
    }

    /** One default blank lore row is presentation spacing and is never part of a quote. */
    fun canonical(rows: List<String>): String = Normalizer.normalize(rows.joinToString("\n"), Normalizer.Form.NFC)

    private fun normalizeVisible(value: String): String = Normalizer.normalize(value, Normalizer.Form.NFC)

    fun levenshtein(left: String, right: String): Int {
        val a = left.codePoints().toArray()
        val b = right.codePoints().toArray()
        if (a.isEmpty()) return b.size
        if (b.isEmpty()) return a.size
        var previous = IntArray(b.size + 1) { it }
        var current = IntArray(b.size + 1)
        for (i in a.indices) {
            current[0] = i + 1
            for (j in b.indices) {
                val substitution = previous[j] + if (a[i] == b[j]) 0 else 1
                current[j + 1] = minOf(current[j] + 1, previous[j + 1] + 1, substitution)
            }
            val swap = previous
            previous = current
            current = swap
        }
        return previous[b.size]
    }

    fun quoteMinor(editDistance: Int, stackAmount: Int, rateMinorPerCharacter: Long): Long? {
        if (editDistance < 0 || stackAmount < 1 || rateMinorPerCharacter < 1L) return null
        return runCatching {
            Math.multiplyExact(Math.multiplyExact(editDistance.toLong(), rateMinorPerCharacter), stackAmount.toLong())
        }.getOrNull()
    }

    fun sameSnapshot(expected: ByteArray, actual: ByteArray?): Boolean = actual != null && expected.contentEquals(actual)

    private fun codePoints(value: String): Int = value.codePointCount(0, value.length)
}
