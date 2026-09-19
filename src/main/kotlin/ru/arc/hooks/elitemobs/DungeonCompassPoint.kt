package ru.arc.hooks.elitemobs

import java.util.UUID

internal const val DUNGEON_COMPASS_RADIUS = 64.0

internal enum class DungeonCompassPointKind { CHEST, AVAILABLE_QUEST }

/** Immutable nearby destination; never retains a live world or entity across refreshes. */
internal data class DungeonCompassPoint(
    val worldId: UUID,
    val x: Double,
    val y: Double,
    val z: Double,
    val kind: DungeonCompassPointKind,
)
