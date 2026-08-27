package ru.arc.citizens

import net.citizensnpcs.api.CitizensAPI
import org.bukkit.Bukkit
import org.bukkit.World
import ru.arc.ARC
import ru.arc.util.Logging.info
import ru.arc.util.Logging.warn
import java.util.Locale

/**
 * Keeps only configured chunks containing persisted Citizens NPCs resident.
 *
 * Citizens represents nameplates and hologram lines as child NPC entities. On
 * Paper, unloading one parent chunk can therefore synchronously fan out from
 * `EntitiesUnloadEvent` into several NPC despawns and entity removals. A
 * bounded plugin ticket prevents that churn without enabling Citizens'
 * network-wide `always-keep-loaded` switch.
 *
 * All methods and async chunk callbacks execute on the Paper main thread. New
 * tickets are acquired before obsolete tickets are released so a moving NPC
 * never has an unprotected transition window.
 */
internal class NpcChunkTicketManager(
    private val config: () -> NpcChunkTicketConfig,
) {
    private val ownedTickets = linkedSetOf<NpcChunkKey>()
    private val borrowedTickets = linkedSetOf<NpcChunkKey>()
    private val pendingTickets = linkedMapOf<NpcChunkKey, Long>()

    private var desiredTickets: Set<NpcChunkKey> = emptySet()
    private var revision = 0L
    private var unavailableLogged = false
    private var lastSummary: Pair<Int, Int>? = null

    fun reconcile() {
        val current = config()
        if (!current.enabled || current.worlds.isEmpty()) {
            desiredTickets = emptySet()
            pendingTickets.clear()
            releaseObsoleteTickets()
            return
        }
        val citizens = Bukkit.getPluginManager().getPlugin("Citizens")
        if (citizens?.isEnabled != true) {
            if (!unavailableLogged) {
                warn("Citizens chunk tickets are enabled, but Citizens is unavailable")
                unavailableLogged = true
            }
            return
        }
        unavailableLogged = false

        val candidates =
            runCatching {
                CitizensAPI.getNPCRegistry().mapNotNull { npc ->
                    val location = npc.storedLocation ?: return@mapNotNull null
                    NpcChunkKey(location.world.name, location.blockX shr 4, location.blockZ shr 4)
                }
            }.getOrElse { failure ->
                warn("Failed to inspect Citizens NPC chunks", failure)
                return
            }
        val plan =
            NpcChunkTicketPlanner.plan(
                candidates = candidates,
                allowedWorlds = current.worlds,
                maxPinnedChunks = current.maxPinnedChunks,
            )
        desiredTickets = plan.selected
        revision++
        val currentRevision = revision

        borrowedTickets.retainAll(desiredTickets)
        val covered = ownedTickets + borrowedTickets + pendingTickets.keys
        (desiredTickets - covered).forEach { key -> acquireAsync(key, currentRevision) }
        releaseObsoleteTickets()

        val summary = desiredTickets.size to plan.rejectedCount
        if (summary != lastSummary) {
            info(
                "Citizens chunk ticket plan: pinned={}, capped={}, worlds={}",
                desiredTickets.size,
                plan.rejectedCount,
                current.worlds.sorted(),
            )
            if (plan.rejectedCount > 0) {
                warn(
                    "Citizens chunk ticket cap rejected {} chunk(s); raise max-pinned-chunks after reviewing the scope",
                    plan.rejectedCount,
                )
            }
            lastSummary = summary
        }
    }

    fun shutdown() {
        revision++
        desiredTickets = emptySet()
        pendingTickets.clear()
        releaseObsoleteTickets()
        borrowedTickets.clear()
        lastSummary = null
    }

    private fun acquireAsync(
        key: NpcChunkKey,
        requestedRevision: Long,
    ) {
        val world = world(key) ?: return
        pendingTickets[key] = requestedRevision
        world.getChunkAtAsync(key.x, key.z, false).whenComplete { chunk, failure ->
            if (pendingTickets[key] != requestedRevision) return@whenComplete
            pendingTickets.remove(key)
            if (failure != null) {
                warn("Failed to load Citizens NPC chunk {}:{},{}", key.world, key.x, key.z, failure)
                releaseObsoleteTickets()
                return@whenComplete
            }
            if (!ARC.instance.isEnabled || key !in desiredTickets) {
                releaseObsoleteTickets()
                return@whenComplete
            }
            runCatching { chunk.addPluginChunkTicket(ARC.instance) }
                .onSuccess { added ->
                    if (added) {
                        ownedTickets += key
                    } else {
                        borrowedTickets += key
                    }
                }.onFailure { ticketFailure ->
                    warn("Failed to pin Citizens NPC chunk {}:{},{}", key.world, key.x, key.z, ticketFailure)
                }
            releaseObsoleteTickets()
        }
    }

    private fun releaseObsoleteTickets() {
        if (pendingTickets.isNotEmpty()) return
        val obsolete = ownedTickets - desiredTickets
        obsolete.forEach { key ->
            val world = world(key)
            if (world == null) {
                warn("Cannot release Citizens NPC chunk ticket because world '{}' is unloaded", key.world)
                return@forEach
            }
            runCatching { world.removePluginChunkTicket(key.x, key.z, ARC.instance) }
                .onFailure { failure ->
                    warn("Failed to release Citizens NPC chunk {}:{},{}", key.world, key.x, key.z, failure)
                    return@forEach
                }
            ownedTickets.remove(key)
        }
    }

    private fun world(key: NpcChunkKey): World? =
        Bukkit.getWorld(key.world)
            ?: Bukkit.getWorlds().firstOrNull { it.name.lowercase(Locale.ROOT) == key.world.lowercase(Locale.ROOT) }
}
