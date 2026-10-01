package ru.arc.hooks.elitemobs

import java.util.UUID

internal const val DUNGEON_COMPASS_RADIUS = 64.0

internal enum class DungeonCompassPointKind(val priority: Int) {
    ELITE_BOSS(0),
    AVAILABLE_QUEST(1),
    GUILD_NPC(2),
    CHEST(3),
    CLASS_TRAINER(4),
    ARENA(4),
    TRANSPORT(4),
    SHOP(4),
    REPAIR(5),
    SCRAP(5),
    ENCHANT(5),
    UNBIND(5),
    SCROLL(5),
    QUEST_UNAVAILABLE(6),
    NPC_SERVICE(6),
    ELITE_MOB(7),
}

/** Immutable nearby destination; never retains a live world or entity across refreshes. */
internal data class DungeonCompassPoint(
    val worldId: UUID,
    val x: Double,
    val y: Double,
    val z: Double,
    val kind: DungeonCompassPointKind,
)
