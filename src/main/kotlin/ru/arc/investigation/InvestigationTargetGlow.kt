package ru.arc.investigation

import org.bukkit.Bukkit
import org.bukkit.entity.Player
import ru.arc.hooks.HookRegistry
import java.util.UUID

/** Maintains viewer-only outlines for the next investigation targets. */
internal object InvestigationTargetGlow {
    private val visibleTargets = mutableMapOf<UUID, Set<Int>>()

    fun refresh(
        player: Player,
        record: InvestigationJournalRecord?,
        config: InvestigationConfig,
    ) {
        val desired =
            desiredInvestigationTargetRoles(record, player.world.name == config.world)
                .mapTo(linkedSetOf()) { role -> config.point(role).npcId }
        val previous = visibleTargets[player.uniqueId].orEmpty()
        (previous - desired).forEach { setGlow(player, it, false) }
        desired.forEach { setGlow(player, it, true) }
        if (desired.isEmpty()) visibleTargets.remove(player.uniqueId) else visibleTargets[player.uniqueId] = desired
    }

    fun clear(player: Player) {
        visibleTargets.remove(player.uniqueId).orEmpty().forEach { setGlow(player, it, false) }
    }

    fun clearAll() {
        visibleTargets.keys.toList().forEach { playerId ->
            Bukkit.getPlayer(playerId)?.let(::clear)
        }
        visibleTargets.clear()
    }

    private fun setGlow(
        player: Player,
        npcId: Int,
        glowing: Boolean,
    ) {
        val entity = HookRegistry.citizensHook?.livingEntity(npcId) ?: return
        HookRegistry.packetEventsHook?.setEntityGlowingFor(entity, player, glowing)
    }
}

internal fun desiredInvestigationTargetRoles(
    record: InvestigationJournalRecord?,
    inSceneWorld: Boolean,
): Set<String> {
    if (!inSceneWorld || record?.status != InvestigationStatus.ACTIVE) return emptySet()
    val targets =
        record.case.witnesses()
            .filterNot(record::hasClue)
            .mapTo(linkedSetOf(), InvestigationWitness::commandValue)
    if (record.clueCount() >= InvestigationService.MIN_CLUES) {
        targets += "foma"
    }
    return targets
}
