package ru.arc.onboarding

import net.kyori.adventure.text.Component
import ru.arc.config.Config
import java.util.Locale

internal data class OptionalQuestArea(
    val world: String,
    val minX: Double,
    val minY: Double,
    val minZ: Double,
    val maxX: Double,
    val maxY: Double,
    val maxZ: Double,
) {
    init {
        require(world.isNotBlank() && world.length <= 128 && world.none(Char::isISOControl))
        require(listOf(minX, minY, minZ, maxX, maxY, maxZ).all(Double::isFinite))
        require(minX <= maxX && minY <= maxY && minZ <= maxZ)
    }

    fun contains(worldName: String, x: Double, y: Double, z: Double): Boolean =
        worldName.trim().lowercase(Locale.ROOT) == world && x in minX..maxX && y in minY..maxY && z in minZ..maxZ
}

internal class OptionalQuestConfig(private val source: Config) {
    val enabled = source.bool("optional-quests.enabled", false)
    val npcIds = OptionalQuestKind.entries.associateWith { source.int("optional-quests.${it.id}.npc-id", -1) }
    val areas: Map<OptionalQuestStep, OptionalQuestArea> = LOCATION_STEPS.mapNotNull { step ->
        val path = "optional-quests.arrivals.${step.name.lowercase(Locale.ROOT).replace('_', '-')}"
        source.stringOrNull("$path.world")?.let { world ->
            step to OptionalQuestArea(world.trim().lowercase(Locale.ROOT),
                source.double("$path.min-x", -30_000_000.0), source.double("$path.min-y", -2048.0), source.double("$path.min-z", -30_000_000.0),
                source.double("$path.max-x", 30_000_000.0), source.double("$path.max-y", 2048.0), source.double("$path.max-z", 30_000_000.0))
        }
    }.toMap()

    fun text(key: String): Component = source.component("optional-quests.text.$key", OptionalQuestDialog.DEFAULT_TEXT.getValue(key))

    fun validate() {
        require(npcIds.values.all { it >= -1 }) { "Optional quest NPC ids must be -1 or non-negative" }
        require(npcIds.values.filter { it >= 0 }.distinct().size == npcIds.values.count { it >= 0 }) { "Optional quest NPC ids must be distinct" }
        OptionalQuestDialog.DEFAULT_TEXT.keys.forEach(::text)
        if (enabled) require(areas.keys.containsAll(LOCATION_STEPS)) { "Optional quests require every arrival area" }
    }

    companion object {
        internal val LOCATION_STEPS = setOf(OptionalQuestStep.GUILD_VISITED, OptionalQuestStep.PRIMIS_VISITED,
            OptionalQuestStep.CASINO_GROUND_VISITED, OptionalQuestStep.CASINO_MIDDLE_VISITED, OptionalQuestStep.CASINO_UPPER_VISITED,
            OptionalQuestStep.FARM_VISITED)
    }
}
