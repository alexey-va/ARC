package ru.arc.itemlore

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.format.TextDecoration
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer
import java.text.Normalizer

/** Validation and lore construction are kept Bukkit-free so their rules are easy to test. */
internal object ItemLorePolicy {
    // Bounds client form size and quadratic quote work; fields are added on demand.
    const val MAX_ROWS = 32
    const val MAX_CODE_POINTS_PER_ROW = 80
    const val MAX_INPUT_LENGTH = 1024

    private val plain = PlainTextComponentSerializer.plainText()
    private val legacy = LegacyComponentSerializer.builder().character('&').hexColors().build()
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

    /** White is the implicit editor color, so its leading code is never charged. */
    fun editableText(row: Component): String = legacy.serialize(
        row.replaceText { it.matchLiteral("&").replacement("&&") },
    ).removePrefix("&f")

    fun rowFits(row: Component): Boolean = codePoints(plain.serialize(row)) <= MAX_CODE_POINTS_PER_ROW &&
        editableText(row).length <= MAX_INPUT_LENGTH

    // Raw controls are rejected before parsing, so NUL can safely protect &&.
    private fun parse(value: String): Component = legacy.deserialize(value.replace("&&", "\u0000"))
        .replaceText { it.matchLiteral("\u0000").replacement("&") }
        .colorIfAbsent(NamedTextColor.WHITE)
        .decorationIfAbsent(TextDecoration.ITALIC, TextDecoration.State.FALSE)

    fun build(existingLore: List<Component>?, submitted: List<String>): Result {
        val originals = originalRows(existingLore)
        if (originals.size > MAX_ROWS) return Result.Rejected(Reason.TOO_MANY_EXISTING_ROWS)
        if (originals.any { !rowFits(it) }) return Result.Rejected(Reason.EXISTING_ROW_TOO_LONG)
        if (submitted.size > MAX_ROWS) return Result.Rejected(Reason.TOO_MANY_FIELDS)
        if (submitted.any { it.length > MAX_INPUT_LENGTH }) return Result.Rejected(Reason.ROW_TOO_LONG)
        if (submitted.any { value -> value.any { it == '\n' || it == '\r' || it.isISOControl() } }) {
            return Result.Rejected(Reason.MULTILINE_INPUT)
        }
        val parsed = submitted.map(::parse)
        if (parsed.any { !rowFits(it) }) return Result.Rejected(Reason.ROW_TOO_LONG)

        // Preserve the exact original component (including metadata) when its
        // editable text and formatting did not change. Blank fields remove rows;
        // an all-empty form also clears decorative blank rows.
        val body = if (parsed.all { plain.serialize(it).isBlank() }) emptyList() else parsed.mapIndexedNotNull { index, row ->
            val original = originals.getOrNull(index)
            when {
                original != null && normalizeVisible(editableText(original)) == normalizeVisible(editableText(row)) -> original
                plain.serialize(row).isBlank() -> null
                else -> row
            }
        }
        // Canonical legacy encoding includes color/style changes, and collapses
        // redundant codes and equivalent hex/legacy spellings before pricing.
        val distance = levenshtein(canonical(originals.map(::editableText)), canonical(body.map(::editableText)))
        val lore = when {
            distance == 0 -> existingLore
            body.isEmpty() -> null
            else -> listOf(existingLore?.firstOrNull()?.takeIf { row -> plain.serialize(row).isEmpty() } ?: defaultGap) + body
        }
        return Result.Ready(Draft(lore = lore, visibleRows = body.map(plain::serialize), editDistance = distance))
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
