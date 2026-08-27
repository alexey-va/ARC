package ru.arc.citizens

import java.util.Locale

internal data class NpcChunkKey(
    val world: String,
    val x: Int,
    val z: Int,
)

internal data class NpcChunkTicketPlan(
    val selected: Set<NpcChunkKey>,
    val rejectedCount: Int,
)

/** Pure, deterministic bound for the chunks kept resident by [NpcChunkTicketManager]. */
internal object NpcChunkTicketPlanner {
    fun plan(
        candidates: Iterable<NpcChunkKey>,
        allowedWorlds: Set<String>,
        maxPinnedChunks: Int,
    ): NpcChunkTicketPlan {
        require(maxPinnedChunks > 0) { "maxPinnedChunks must be positive" }
        val normalizedWorlds = allowedWorlds.map { it.lowercase(Locale.ROOT) }.toSet()
        val eligible =
            candidates
                .filter { it.world.lowercase(Locale.ROOT) in normalizedWorlds }
                .distinct()
                .sortedWith(compareBy<NpcChunkKey>({ it.world.lowercase(Locale.ROOT) }, { it.x }, { it.z }))
        val selected = eligible.take(maxPinnedChunks).toSet()
        return NpcChunkTicketPlan(
            selected = selected,
            rejectedCount = eligible.size - selected.size,
        )
    }
}
