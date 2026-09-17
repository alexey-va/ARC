package ru.arc.hooks.citizens

/** Authored values copied from Citizens before its native hologram is removed. */
internal data class NpcHologramSource(
    val name: String?,
    val lines: List<String>,
    val lineHeight: Double,
    val viewRange: Int,
)

/**
 * Bukkit-independent state for one NPC display stack.
 *
 * Text parsing is intentionally owned by the renderer and happens only after
 * [apply] returns true. The movement task only calls [tick] and reads the
 * already materialized line list.
 */
internal class NpcHologramState {
    private var source: NpcHologramSource? = null
    private var bubble: Bubble? = null

    private data class Bubble(
        val lines: List<String>,
        var remainingTicks: Int,
    )

    fun apply(next: NpcHologramSource): Boolean {
        if (source == next) return false
        source = next.copy(lines = next.lines.toList())
        return true
    }

    fun source(): NpcHologramSource? = source

    fun showBubble(lines: List<String>, ttlTicks: Int) {
        bubble = Bubble(lines.filter(String::isNotEmpty), ttlTicks.coerceAtLeast(1))
    }

    /** Returns true when an expired bubble changed the visible content. */
    fun tick(elapsedTicks: Int = 1): Boolean {
        val current = bubble ?: return false
        current.remainingTicks -= elapsedTicks.coerceAtLeast(1)
        if (current.remainingTicks > 0) return false
        bubble = null
        return true
    }

    fun visibleLines(): List<String> = bubble?.lines ?: source?.lines.orEmpty()

    fun bodyText(): String = visibleLines().joinToString("\n")

    fun bubbleActive(): Boolean = bubble != null
}

private val legacyFormattingCode = Regex("(?i)[§&][0-9A-FK-ORX]")

private fun plainLegacyText(value: String): String =
    legacyFormattingCode.replace(value, "").trim()

/**
 * Older Citizens entries often repeated the NPC name as their final authored
 * line while NAMEPLATE_VISIBLE was false. ARC renders that name separately.
 * Only the final line is considered, and a role-prefixed NPC name may match
 * its last token when that token is meaningful (three or more characters).
 */
internal fun normalizeNpcHologramLines(lines: List<String>, npcName: String): List<String> {
    return resolveNpcHologramPresentation(lines, npcName).lines
}

internal data class NpcHologramPresentation(
    val name: String,
    val lines: List<String>,
    val nameFromAuthoredLine: Boolean,
)

/** Keeps a multiline body above the name instead of centring it through it. */
internal fun npcHologramBodyOffset(bodyGap: Double, lineHeight: Double, lineCount: Int): Double {
    val safeLineHeight = lineHeight.coerceIn(0.05, 2.0)
    return bodyGap.coerceAtLeast(safeLineHeight) + (lineCount.coerceAtLeast(1) - 1) * safeLineHeight * 0.5
}

/** Converts Citizens' block tracking range to the TextDisplay 64-block multiplier. */
internal fun npcHologramViewRange(citizensRange: Int, fallbackMultiplier: Float): Float =
    citizensRange.takeIf { it > 0 }
        ?.let { (it / 64.0f).coerceIn(0.05f, 4.0f) }
        ?: fallbackMultiplier

/** Separates the styled final name line from the permanent multiline body. */
internal fun resolveNpcHologramPresentation(lines: List<String>, npcName: String): NpcHologramPresentation {
    val last = lines.lastOrNull() ?: return NpcHologramPresentation(npcName, lines, false)
    val plainLine = plainLegacyText(last)
    if (plainLine.isEmpty()) return NpcHologramPresentation(npcName, lines, false)

    val plainName = plainLegacyText(npcName)
    val nameTail = plainName.substringAfterLast(' ', "").takeIf { plainName.contains(' ') && it.length >= 3 }
    if (plainLine == plainName || plainLine == nameTail) return NpcHologramPresentation(last, lines.dropLast(1), true)
    return NpcHologramPresentation(npcName, lines, false)
}
