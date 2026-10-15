package ru.arc.hooks.elitemobs

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.TextColor
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import org.bukkit.Location
import ru.arc.dungeons.api.CompassStrip
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.sqrt

internal data class CompassPoi(val bearing: Double, val distance: Double, val kind: DungeonCompassPointKind)

internal fun DungeonCompassPoint.project(origin: Location): CompassPoi? {
    if (origin.world?.uid != worldId) return null
    val dx = x - origin.x
    val dy = y - origin.y
    val dz = z - origin.z
    val distanceSquared = dx * dx + dy * dy + dz * dz
    if (!distanceSquared.isFinite() ||
        (kind != DungeonCompassPointKind.AVAILABLE_QUEST && distanceSquared > DUNGEON_COMPASS_RADIUS * DUNGEON_COMPASS_RADIUS) ||
        dx * dx + dz * dz < 0.000001) return null
    return CompassPoi((Math.toDegrees(atan2(-dx, dz)) + 360) % 360, sqrt(distanceSquared), kind)
}

/** Keeps EliteMobs target interpretation local while sharing ARC's strip projection and rendering. */
internal object QuestCompassRenderer {
    val WIDTH = CompassStrip.WIDTH

    private const val ACCENT = 0xff6b6b
    private const val TARGET = 0x9bd48d
    private const val GOLD = 0xffcf70
    private val accent = TextColor.color(ACCENT)
    private val scale = TextColor.color(0xe8dfd2)
    private val nativeSymbols = setOf('-', '⦿', '⬯', '☠', '⚔', '↑', '↓', '↕')

    fun render(yaw: Float, nativeTitle: String? = null, points: List<CompassPoi> = emptyList()): Component {
        val plain = PlainTextComponentSerializer.plainText()
            .serialize(LegacyComponentSerializer.legacySection().deserialize(nativeTitle ?: "-".repeat(63)))
        val hasNativeProjection = plain.length == 63 && plain.all { it in nativeSymbols }
        if (!hasNativeProjection && points.isEmpty()) return status(plain)

        val heading = if (yaw.isFinite()) yaw.toDouble() % 360.0 else 0.0
        val nativeMarkers = mutableListOf<CompassStrip.Marker>()
        val nativeCells = mutableListOf<Int>()
        if (hasNativeProjection) plain.forEachIndexed { index, symbol ->
            if (symbol == '-') return@forEachIndexed
            val bearing = heading + (index - 31) * 3.0
            val cell = CompassStrip.cellIndex(yaw, bearing)
            if (cell < 0) return@forEachIndexed
            nativeCells += cell
            nativeMarkers += CompassStrip.Marker(
                bearing,
                when (symbol) {
                    '⦿' -> "◇"
                    '⬯' -> "○"
                    else -> symbol.toString()
                },
                when (symbol) {
                    '☠', '⚔' -> ACCENT
                    '⬯', '↑', '↓', '↕' -> GOLD
                    else -> TARGET
                },
            )
        }

        val pointMarkers = mutableListOf<CompassStrip.Marker>()
        val occupied = mutableListOf<Int>()
        for (point in points.sortedWith(compareBy<CompassPoi> { it.kind.priority }.thenBy { it.distance })) {
            if (occupied.size >= 8) break
            if (!point.bearing.isFinite() || !point.distance.isFinite() || point.distance < 0.0 ||
                (point.kind != DungeonCompassPointKind.AVAILABLE_QUEST && point.distance > DUNGEON_COMPASS_RADIUS)) continue
            val cell = CompassStrip.cellIndex(yaw, point.bearing)
            if (cell < 0 || (nativeCells + occupied).any { abs(it - cell) < 3 }) continue
            occupied += cell
            pointMarkers += CompassStrip.Marker(point.bearing, point.kind.glyph(), point.kind.color())
        }

        // POIs render first so EliteMobs' tracked targets retain their collision precedence.
        return CompassStrip.render(yaw, pointMarkers + nativeMarkers)
    }

    private fun DungeonCompassPointKind.glyph(): String = when (this) {
        DungeonCompassPointKind.ELITE_BOSS -> "☠"
        DungeonCompassPointKind.ELITE_MOB, DungeonCompassPointKind.ARENA -> "⚔"
        DungeonCompassPointKind.CHEST -> "□"
        DungeonCompassPointKind.AVAILABLE_QUEST -> "!"
        DungeonCompassPointKind.GUILD_NPC -> "⚑"
        DungeonCompassPointKind.CLASS_TRAINER -> "▲"
        DungeonCompassPointKind.TRANSPORT -> "↔"
        DungeonCompassPointKind.SHOP -> "¤"
        DungeonCompassPointKind.REPAIR -> "+"
        DungeonCompassPointKind.SCRAP -> "×"
        DungeonCompassPointKind.ENCHANT -> "★"
        DungeonCompassPointKind.UNBIND -> "÷"
        DungeonCompassPointKind.SCROLL -> "≡"
        DungeonCompassPointKind.NPC_SERVICE -> "●"
    }

    private fun DungeonCompassPointKind.color(): Int = when (this) {
        DungeonCompassPointKind.ELITE_BOSS,
        DungeonCompassPointKind.ELITE_MOB,
        DungeonCompassPointKind.ARENA -> ACCENT
        DungeonCompassPointKind.CHEST,
        DungeonCompassPointKind.GUILD_NPC,
        DungeonCompassPointKind.TRANSPORT,
        DungeonCompassPointKind.SHOP,
        DungeonCompassPointKind.REPAIR,
        DungeonCompassPointKind.SCRAP,
        DungeonCompassPointKind.UNBIND,
        DungeonCompassPointKind.SCROLL -> GOLD
        DungeonCompassPointKind.ENCHANT -> TARGET
        DungeonCompassPointKind.NPC_SERVICE -> 0xe8dfd2
        DungeonCompassPointKind.AVAILABLE_QUEST,
        DungeonCompassPointKind.CLASS_TRAINER -> TARGET
    }

    private fun status(text: String): Component {
        val localized = when {
            text.isBlank() -> "Поиск цели…"
            text == "Waiting for the dungeon to start" -> "Ожидание старта данжа"
            text == "[EM] No quest destination found!" -> "Цель пока не найдена"
            text.startsWith("[EM] Go to world ") ->
                "Цель в мире: " + text.removePrefix("[EM] Go to world ").removeSuffix("!")
            else -> text.replace('\n', ' ').replace('\r', ' ')
        }
        val bounded = if (localized.length > 40) localized.take(39) + "…" else localized
        return Component.text("< ", accent).append(Component.text(bounded, scale))
            .append(Component.text(" >", accent))
    }
}
