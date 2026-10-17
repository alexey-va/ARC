package ru.arc.investigation

import org.bukkit.Bukkit
import org.bukkit.entity.Player
import org.bukkit.entity.LivingEntity
import ru.arc.hooks.HookRegistry
import ru.arc.hooks.packetevents.PacketEventsHook
import ru.arc.paper.api.ArcNativeCleanupService
import java.lang.ref.WeakReference
import java.util.UUID
import java.util.concurrent.CompletableFuture

/** Maintains viewer-only outlines for the next investigation targets. */
internal object InvestigationTargetGlow {
    private data class Target(val entity: WeakReference<LivingEntity>, val packets: PacketEventsHook)
    private var visibleTargets = mutableMapOf<UUID, Map<Int, Target>>()
    private var retirement = CompletableFuture.completedFuture<Void>(null)

    fun refresh(
        player: Player,
        record: InvestigationJournalRecord?,
        config: InvestigationConfig,
    ) {
        val desired =
            desiredInvestigationTargetRoles(record, player.world.name == config.world)
                .mapTo(linkedSetOf()) { role -> config.point(role).npcId }
        val previous = visibleTargets.remove(player.uniqueId).orEmpty()
        val next = linkedMapOf<Int, Target>()
        val citizens = HookRegistry.citizensHook
        val packets = HookRegistry.packetEventsHook
        for (id in desired) {
            val entity = citizens?.livingEntity(id) ?: continue
            if (packets == null) continue
            val old = previous[id]
            if (old != null && (old.entity.get() !== entity || old.packets !== packets)) restore(player, old)
            packets.setEntityGlowingFor(entity, player, true)
            next[id] = Target(WeakReference(entity), packets)
        }
        for ((id, target) in previous) if (id !in next) restore(player, target)
        if (next.isNotEmpty()) visibleTargets[player.uniqueId] = next
    }

    fun clear(player: Player) {
        visibleTargets.remove(player.uniqueId).orEmpty().values.forEach { restore(player, it) }
    }

    /** Detaches this generation in O(1); no later cursor reads mutable hook/config bindings. */
    fun clearAllAsync(): CompletableFuture<Void> {
        if (visibleTargets.isEmpty()) return retirement.copy()
        val server = Bukkit.getServer()
        val cleanup = server.servicesManager.load(ArcNativeCleanupService::class.java)
            ?: return CompletableFuture.failedFuture(IllegalStateException("ArcCore native cleanup service is unavailable"))
        val previous = visibleTargets
        visibleTargets = mutableMapOf()
        val hooks = linkedSetOf<PacketEventsHook>()
        var failure: Throwable? = null
        fun failed(error: Throwable) {
            if (failure == null) failure = error else if (failure !== error) failure!!.addSuppressed(error)
        }
        val steps = sequence {
            for ((playerId, targets) in previous) for (target in targets.values) {
                hooks.add(target.packets)
                try { server.getPlayer(playerId)?.takeIf(Player::isOnline)?.let { restore(it, target) } }
                catch (error: Throwable) { failed(error) }
                yield(Unit)
            }
            for (hook in hooks) {
                try {
                    val drain = hook.glowCleanupDrained()
                    while (!drain.isDone) yield(Unit)
                    drain.join() // Observed complete; never waits for native/packet work.
                } catch (error: Throwable) { failed(error) }
                yield(Unit)
            }
        }.iterator()
        val accepted = try {
            cleanup.submit("ARC:investigation-glow") {
                if (steps.hasNext()) { steps.next(); false }
                else { failure?.let { throw it }; true }
            }.toCompletableFuture()
        } catch (error: Throwable) { CompletableFuture.failedFuture(error) }
        retirement = CompletableFuture.allOf(retirement, accepted)
        return retirement.copy()
    }

    fun clearAll() { clearAllAsync() }

    private fun restore(player: Player, target: Target) {
        target.entity.get()?.let { target.packets.restoreEntityGlowingFor(it, player) }
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
