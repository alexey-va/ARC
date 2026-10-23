package ru.arc.hooks.elitemobs

import com.magmaguy.elitemobs.api.EliteMobDamagedEvent
import com.magmaguy.elitemobs.api.EliteMobDeathEvent
import com.magmaguy.elitemobs.api.EliteMobRemoveEvent
import com.magmaguy.elitemobs.api.EliteMobSpawnEvent
import com.magmaguy.elitemobs.api.ElitePhaseSwitchEvent
import com.magmaguy.elitemobs.api.internal.RemovalReason
import com.magmaguy.elitemobs.entitytracker.EntityTracker
import com.magmaguy.elitemobs.mobconstructor.custombosses.CustomBossEntity
import com.magmaguy.elitemobs.mobconstructor.custombosses.RegionalBossEntity
import org.bukkit.Bukkit
import org.bukkit.GameMode
import org.bukkit.World
import org.bukkit.entity.AreaEffectCloud
import org.bukkit.entity.Entity
import org.bukkit.entity.EvokerFangs
import org.bukkit.entity.Player
import org.bukkit.entity.Projectile
import org.bukkit.entity.TNTPrimed
import org.bukkit.entity.Tameable
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.entity.EntityDamageByEntityEvent
import org.bukkit.event.entity.EntityDamageEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.event.world.ChunkUnloadEvent
import ru.arc.core.BudgetedTaskQueue
import ru.arc.core.LifecycleTaskScope
import java.util.IdentityHashMap
import java.util.UUID
import java.util.logging.Level
import java.util.logging.Logger

internal data class PrimisDamageOwner(val id: UUID, val player: Player?)

/** Preserve an offline pet owner's identity: an absent known player is outside, not environmental damage. */
internal fun primisDamageOwner(event: EntityDamageEvent): PrimisDamageOwner? {
    fun resolve(source: Entity?, depth: Int = 0): PrimisDamageOwner? {
        if (source == null || depth >= 4) return null
        return when (source) {
            is Player -> PrimisDamageOwner(source.uniqueId, source)
            is Projectile -> resolve(source.shooter as? Entity, depth + 1)
            is Tameable -> source.owner?.let { PrimisDamageOwner(it.uniqueId, Bukkit.getPlayer(it.uniqueId)) }
            is AreaEffectCloud -> resolve(source.source as? Entity, depth + 1)
            is EvokerFangs -> resolve(source.owner, depth + 1)
            is TNTPrimed -> resolve(source.source, depth + 1)
            else -> null
        }
    }
    return resolve(event.damageSource.causingEntity) ?: resolve(event.damageSource.directEntity)
        ?: resolve((event as? EntityDamageByEntityEvent)?.damager)
}

/**
 * Server-thread owner for the three authored regional encounters. Native EliteMobs owns blocks,
 * phase materialization, health and rewards. We admit damage, veto unsafe materialization, and
 * acknowledge a native abort only after its first body and budgeted passage readback are ready.
 */
internal class PrimisEncounterGuard(
    private val nativeWork: BudgetedTaskQueue,
    private val logger: Logger,
    private val notify: (Player, PrimisAdmissionDecision) -> Unit,
    private val prepared: List<PrimisPreparedEncounter> = preparePrimisEncounters(),
    private val tasks: LifecycleTaskScope = LifecycleTaskScope(),
) : Listener, AutoCloseable {
    private class Encounter(val boss: RegionalBossEntity, val definition: PrimisPreparedEncounter) {
        val policy = PrimisEncounterPolicy()
        val chunks = (definition.passages.map { (it.x shr 4) to (it.z shr 4) } +
            ((kotlin.math.floor(definition.profile.spawnCoordinates[0]).toInt() shr 4) to
                (kotlin.math.floor(definition.profile.spawnCoordinates[2]).toInt() shr 4))).toSet()
        var baselineReady = false
        var verification: Any? = null
        var resetOwned = false
        var resetInvoked = false
        var resetQueued = false
        var resetAttempts = 0
        var nextResetTick = 0L
        var warned = false
    }
    private val encounters = IdentityHashMap<RegionalBossEntity, Encounter>()
    private val intents = IdentityHashMap<EntityDamageEvent, Encounter>()
    private val notices = mutableMapOf<UUID, Long>()
    private var tick = 0L
    private var started = false
    private var closed = false

    fun start() {
        if (started || closed) return
        started = true
        prepared.filterNot { it.ready }.forEach {
            logger.warning("PRIMIS_ENCOUNTER profile=${it.profile.baseFile} unavailable=${it.failure}")
        }
        // Only these three native regional registries are inspected, once, without a global entity walk.
        val existing = prepared.mapNotNull { it.base }.flatMap { RegionalBossEntity.getRegionalBossEntities(it).orEmpty() }.iterator()
        nativeWork.submit({ !closed }) {
            if (!existing.hasNext()) true
            else {
                val boss = existing.next()
                if (alive(boss)) encounter(boss, adopt = true)?.let { entry ->
                    if (entry.policy.state == PrimisEncounterState.RESETTING) requestReset(entry)
                    else if (firstPhase(boss)) verifyBaseline(entry)
                }
                !existing.hasNext()
            }
        }
        tasks.runTimer(10, 10) { tick += 10; poll() }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    fun onBukkitDamage(event: EntityDamageEvent) {
        val boss = EntityTracker.getEliteMobEntity(event.entity) as? RegionalBossEntity ?: return
        if (!allowDamage(boss, event, recordIntent = true)) event.isCancelled = true
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    fun onEliteDamage(event: EliteMobDamagedEvent) {
        val boss = event.eliteEntity as? RegionalBossEntity ?: return
        if (!allowDamage(boss, event.entityDamageEvent)) event.isCancelled = true
    }

    private fun allowDamage(boss: RegionalBossEntity, event: EntityDamageEvent, recordIntent: Boolean = false): Boolean {
        val entry = encounter(boss) ?: return true
        val owner = primisDamageOwner(event)
        val inside = hasParticipant(entry)
        val origin = when {
            event.cause in setOf(EntityDamageEvent.DamageCause.FIRE_TICK, EntityDamageEvent.DamageCause.POISON,
                EntityDamageEvent.DamageCause.WITHER) -> PrimisDamageOrigin.ONGOING_EFFECT
            owner == null -> PrimisDamageOrigin.UNATTRIBUTED
            owner.player?.let { eligible(it) && inside(entry, it) } == true -> PrimisDamageOrigin.INSIDE_PLAYER
            else -> PrimisDamageOrigin.OUTSIDE_PLAYER
        }
        val decision = entry.policy.damage(origin,
            entry.definition.ready && current(entry) && chunksLoaded(entry) &&
                (entry.policy.state == PrimisEncounterState.ADMITTED || entry.baselineReady),
            passagesClear(entry), inside, tick)
        if (decision == PrimisAdmissionDecision.ALLOW) {
            if (recordIntent && entry.policy.state == PrimisEncounterState.PROVISIONAL) intents[event] = entry
            return true
        }
        owner?.player?.takeIf { it.isOnline }?.let { player ->
            if (tick >= (notices[player.uniqueId] ?: Long.MIN_VALUE)) {
                notices[player.uniqueId] = tick + 40
                notify(player, decision)
            }
        }
        return false
    }

    @EventHandler(priority = EventPriority.MONITOR)
    fun onDamageOutcome(event: EntityDamageEvent) {
        val entry = intents.remove(event) ?: return
        if (!event.isCancelled && event.finalDamage > 0.0 && current(entry) && hasParticipant(entry)) entry.policy.confirmAdmission()
    }

    /** NORMAL TransitiveBossBlock skips cancelled spawn events, including deferred phase materialization. */
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    fun onSpawn(event: EliteMobSpawnEvent) {
        val boss = event.eliteMobEntity as? RegionalBossEntity ?: return
        val entry = encounter(boss) ?: return
        entry.baselineReady = false; entry.verification = null
        if (firstPhase(boss)) return
        if (!entry.definition.ready || entry.policy.state !in setOf(PrimisEncounterState.PROVISIONAL, PrimisEncounterState.ADMITTED) ||
            !hasParticipant(entry) || !chunksLoaded(entry) || !passagesClear(entry)) {
            event.isCancelled = true
            entry.resetOwned = true
            requestReset(entry)
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    fun onSpawnOutcome(event: EliteMobSpawnEvent) {
        val entry = encounters[event.eliteMobEntity] ?: return
        if (event.isCancelled && (entry.policy.state == PrimisEncounterState.PROVISIONAL || entry.resetOwned)) {
            entry.resetOwned = true
            requestReset(entry)
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    fun onPhaseSwitch(event: ElitePhaseSwitchEvent) {
        val entry = encounters[event.customBossEntity] ?: return
        if (firstPhase(entry.boss)) verifyBaseline(entry)
        else entry.policy.confirmAdmission()
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onDeath(event: EliteMobDeathEvent) {
        (event.eliteEntity as? RegionalBossEntity)?.let { encounters.remove(it) }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    fun onRemove(event: EliteMobRemoveEvent) {
        val entry = encounters[event.eliteMobEntity] ?: return
        when (event.removalReason) {
            RemovalReason.DEATH, RemovalReason.KILL_COMMAND -> encounters.remove(entry.boss)
            RemovalReason.PHASE_BOSS_PHASE_END, RemovalReason.PHASE_BOSS_RESET -> Unit
            else -> {
                entry.policy.beginReset()
                entry.baselineReady = false; entry.verification = null
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onChunkUnload(event: ChunkUnloadEvent) {
        encounters.values.filter { it.definition.profile.world == event.world.name &&
            (event.chunk.x to event.chunk.z) in it.chunks }.forEach { it.baselineReady = false; it.verification = null }
    }

    @EventHandler
    fun onQuit(event: PlayerQuitEvent) { notices.remove(event.player.uniqueId) }

    private fun encounter(boss: RegionalBossEntity, adopt: Boolean = false): Encounter? {
        if (closed) return null
        encounters[boss]?.let { return it }
        val baseFile = boss.phaseBossEntity?.phase1Config?.filename ?: boss.customBossesConfigFields.filename
        val definition = prepared.firstOrNull { it.profile.baseFile == baseFile &&
            it.profile.world == boss.worldName && it.profile.matchesSpawn(boss.configurationLocationString) } ?: return null
        return Encounter(boss, definition).also { entry ->
            encounters[boss] = entry
            if (adopt) entry.policy.adopt(!firstPhase(boss), hasParticipant(entry))
            if (firstPhase(boss)) verifyBaseline(entry)
        }
    }

    private fun current(entry: Encounter): Boolean = !closed && encounters[entry.boss] === entry &&
        entry.definition.base?.let { RegionalBossEntity.getRegionalBossEntities(it).orEmpty().any { boss -> boss === entry.boss } } == true &&
        entry.definition.profile.matchesSpawn(entry.boss.configurationLocationString)

    private fun world(entry: Encounter): World? = Bukkit.getWorld(entry.definition.profile.world)
    private fun firstPhase(boss: CustomBossEntity) = boss.phaseBossEntity?.isInFirstPhase == true
    private fun alive(boss: CustomBossEntity) = boss.livingEntity?.let { it.isValid && !it.isDead && boss.health > 0.0 } == true
    private fun eligible(player: Player) = player.isOnline && !player.isDead && player.health > 0.0 &&
        player.gameMode in setOf(GameMode.SURVIVAL, GameMode.ADVENTURE)
    private fun inside(entry: Encounter, player: Player): Boolean = player.location.let { at ->
        at.world.name == entry.definition.profile.world && entry.definition.profile.room.contains(at.x, at.y, at.z)
    }
    private fun hasParticipant(entry: Encounter) = world(entry)?.players?.any { eligible(it) && inside(entry, it) } == true
    private fun chunksLoaded(entry: Encounter) = world(entry)?.let { world -> entry.chunks.all { (x, z) -> world.isChunkLoaded(x, z) } } == true
    private fun passagesClear(entry: Encounter): Boolean = world(entry)?.players?.none { player ->
        val box = player.boundingBox
        entry.definition.passages.any { it.box.overlaps(box) }
    } == true

    private fun poll() {
        for (entry in encounters.values.toList()) {
            if (!current(entry)) { encounters.remove(entry.boss); continue }
            val boss = entry.boss
            if (boss.health <= 0.0 || boss.isRespawning || boss.isRemoved) { encounters.remove(boss); continue }
            if (!alive(boss) && !entry.resetOwned) continue
            if (entry.policy.observe(tick, hasParticipant(entry))) {
                if (nativeFirstReady(entry)) verifyBaseline(entry)
                else if (!entry.resetInvoked || firstPhase(boss)) requestReset(entry)
            } else if (firstPhase(boss)) verifyBaseline(entry)
        }
        notices.entries.removeIf { tick - it.value > 200 }
    }

    private fun requestReset(entry: Encounter) {
        entry.policy.beginReset(); entry.baselineReady = false; entry.verification = null
        if (!entry.definition.ready || entry.resetQueued || entry.resetAttempts >= 3 || tick < entry.nextResetTick) return
        entry.resetQueued = true
        tasks.runLater(1) {
            entry.resetQueued = false
            if (!current(entry) || !chunksLoaded(entry)) return@runLater
            val boss = entry.boss
            if (boss.health <= 0.0 || boss.isRespawning || boss.isRemoved || (!alive(boss) && !entry.resetOwned)) return@runLater
            entry.resetOwned = true; entry.resetAttempts++; entry.nextResetTick = tick + 20
            runCatching {
                if (entry.resetInvoked && firstPhase(boss) && !alive(boss)) {
                    boss.phaseBossEntity.resetToFirstPhase()
                    boss.spawn(true)
                } else boss.fullHeal()
                entry.resetInvoked = true
                if (alive(boss) && firstPhase(boss)) { boss.fullHeal(); verifyBaseline(entry) }
            }.onFailure {
                if (!entry.warned) {
                    entry.warned = true
                    logger.log(Level.WARNING, "PRIMIS_ENCOUNTER profile=${entry.definition.profile.baseFile} reset failed", it)
                }
            }
            if (entry.resetAttempts >= 3 && !nativeFirstReady(entry)) {
                logger.warning("PRIMIS_ENCOUNTER profile=${entry.definition.profile.baseFile} reset remains unavailable after three attempts")
            }
        }
    }

    private fun nativeFirstReady(entry: Encounter) = entry.resetInvoked && alive(entry.boss) && firstPhase(entry.boss) &&
        entry.boss.maxHealth > 0.0 && entry.boss.health >= entry.boss.maxHealth

    private fun verifyBaseline(entry: Encounter) {
        if (!entry.definition.ready || entry.baselineReady || entry.verification != null || !firstPhase(entry.boss)) return
        val operation = Any(); entry.verification = operation
        var index = 0; var matches = true
        nativeWork.submit({ current(entry) && entry.verification === operation }) {
            val world = world(entry)
            val cell = entry.definition.passages[index++]
            matches = world != null && world.isChunkLoaded(cell.x shr 4, cell.z shr 4) &&
                world.getBlockAt(cell.x, cell.y, cell.z).blockData.asString == cell.baseline.asString
            !matches || index == entry.definition.passages.size
        }.whenComplete { _, failure ->
            if (!current(entry) || entry.verification !== operation) return@whenComplete
            entry.verification = null
            entry.baselineReady = failure == null && matches && chunksLoaded(entry)
            if (!entry.baselineReady && !entry.warned) {
                entry.warned = true
                logger.warning("PRIMIS_ENCOUNTER profile=${entry.definition.profile.baseFile} passage baseline is not ready; damage remains blocked")
            }
            if (entry.policy.acknowledgeReset(nativeFirstReady(entry), entry.baselineReady)) {
                entry.resetOwned = false; entry.resetInvoked = false; entry.resetAttempts = 0; entry.warned = false
            }
        }
    }

    override fun close() {
        if (closed) return
        tasks.close()
        // A cancelled phase body is owned recovery, not an ordinary absent or defeated boss.
        // Complete that narrow handoff before this generation loses its next-tick reset callback.
        for (entry in encounters.values.toList()) {
            if (!entry.resetOwned || entry.policy.state != PrimisEncounterState.RESETTING) continue
            val boss = entry.boss
            for (attempt in 1..3) {
                if (!current(entry) || !chunksLoaded(entry) || boss.health <= 0.0 || boss.isRespawning || boss.isRemoved) break
                val failure = runCatching {
                    if (firstPhase(boss) && !alive(boss)) {
                        boss.phaseBossEntity.resetToFirstPhase()
                        boss.spawn(true)
                    } else boss.fullHeal()
                    if (alive(boss) && firstPhase(boss)) boss.fullHeal()
                }.exceptionOrNull()
                if (failure == null && alive(boss) && firstPhase(boss)) break
                if (attempt == 3) logger.log(Level.WARNING,
                    "PRIMIS_ENCOUNTER profile=${entry.definition.profile.baseFile} terminal reset remains unavailable", failure)
            }
        }
        closed = true; encounters.clear(); intents.clear(); notices.clear()
    }
}
