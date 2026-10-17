package ru.arc.staffspells

import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.Particle
import org.bukkit.Sound
import org.bukkit.World
import org.bukkit.attribute.Attribute
import org.bukkit.entity.Arrow
import org.bukkit.entity.Entity
import org.bukkit.entity.LivingEntity
import org.bukkit.entity.Player
import org.bukkit.entity.AbstractSkeleton
import org.bukkit.entity.Projectile
import org.bukkit.entity.Skeleton
import org.bukkit.entity.WitherSkeleton
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.HandlerList
import org.bukkit.event.Listener
import org.bukkit.event.entity.EntityDamageByEntityEvent
import org.bukkit.event.entity.EntityDeathEvent
import org.bukkit.inventory.ItemStack
import org.bukkit.util.BoundingBox
import org.bukkit.util.Vector
import ru.arc.core.LifecycleTaskScope
import java.util.UUID
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin

internal enum class StaffSummonCastResult { CAST, NO_GROUND, CAPACITY }

internal const val STAFF_SUMMON_ARMY_LIMIT = 10

/** Owns short-lived script-controlled undead actors; every hit stays in the staff damage gate. */
internal class StaffSpellSummons(
    private val settings: StaffSpellSettings,
    private val damage: StaffSpellDamage,
    private val visuals: StaffSpellVisuals,
    private val targeting: StaffSpellTargeting,
    private val actorFactory: ((World, Location, Boolean) -> AbstractSkeleton)? = null,
) : AutoCloseable, Listener {
    private data class Actor(
        val mob: AbstractSkeleton,
        val commander: Boolean,
        var summonTicks: Int = 0,
        var windupTarget: UUID? = null,
        var windupTicks: Int = 0,
        var nextAttackTick: Int = 0,
        var attacksMade: Int = 0,
    )

    private data class ArrowFlight(
        val arrow: Arrow,
        val cast: StaffSpellCast,
        val worldId: UUID,
        val direction: Vector,
        var position: Location,
        var remaining: Double,
        val volley: Volley? = null,
        val powerMultiplier: Double = 1.0,
        var elapsedTicks: Int = 0,
    )

    /** Shared by the existing army's one-shot order and its projectiles; never grants fresh ammunition. */
    private data class Volley(
        val cast: StaffSpellCast,
        val worldId: UUID,
        val targetId: UUID,
        val actorIds: Set<UUID>,
        val leadingArcherId: UUID?,
        val releaseTick: Long,
        val presentationActive: () -> Boolean,
        val hitsByVictim: MutableMap<UUID, Int> = mutableMapOf(),
        val pvpDamageByVictim: MutableMap<UUID, Double> = mutableMapOf(),
        var successfulHits: Int = 0,
    )

    private data class Session(
        val casterId: UUID,
        val worldId: UUID,
        val cast: StaffSpellCast,
        val tuning: StaffSpellTuning,
        val commander: Boolean,
        val bornAtTick: Long,
        val actors: MutableList<Actor> = mutableListOf(),
        val flights: MutableList<ArrowFlight> = mutableListOf(),
        var focusTargetId: UUID? = null,
        var rallyPoint: Location? = null,
        var ageTicks: Int = 0,
        var successfulHits: Int = 0,
    )

    private val tasks = LifecycleTaskScope()
    private val sessions = mutableListOf<Session>()
    private val volleys = mutableMapOf<UUID, Volley>()
    private var currentTick = 0L
    private var started = false
    private var closed = false
    private val targetSearchRadius = minOf(settings.range, MAX_TARGET_SEARCH_RADIUS)

    fun countFor(casterId: UUID): Int = activeActorCount(casterId)

    /** Returns the live owner cast only for a summon this controller currently owns. */
    fun castFor(target: LivingEntity): StaffSpellCast? {
        val session = trackedSession(target) ?: return null
        val owner = Bukkit.getPlayer(session.casterId) ?: return null
        return session.cast.takeIf {
            target.isValid && !target.isDead && !target.isInvulnerable && loaded(target.location) &&
                target.world.uid == session.worldId && owner.world.uid == session.worldId && ready(owner, session.cast)
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onTrackedActorDamage(event: EntityDamageByEntityEvent) {
        val target = event.entity as? LivingEntity ?: return
        val session = trackedSession(target) ?: return
        val authorization = session.cast.duelAuthorization ?: return
        val access = session.cast.duelAccess
        val owner = Bukkit.getPlayer(session.casterId)
        val attacker = event.damager.playerAttacker()
        if (access == null || owner == null || !ready(owner, session.cast) ||
            owner.world.uid != session.worldId || attacker == null ||
            !access.isCurrent(authorization, owner) || !access.canHit(authorization, owner, attacker) ||
            !access.isCurrent(authorization, owner)) {
            event.isCancelled = true
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    fun onTrackedActorDeath(event: EntityDeathEvent) {
        if (trackedSession(event.entity) == null) return
        event.drops.clear()
        event.droppedExp = 0
    }

    fun start() {
        check(!closed) { "Summon controller is closed" }
        if (started) return
        started = true
        tasks.runTimer(1, 1, ::tick)
    }

    fun cast(player: Player, cast: StaffSpellCast, tuning: StaffSpellTuning, input: StaffCastInput, charge: Double): Boolean {
        return castResult(player, cast, tuning, input, charge) == StaffSummonCastResult.CAST
    }

    fun castResult(
        player: Player,
        cast: StaffSpellCast,
        tuning: StaffSpellTuning,
        input: StaffCastInput,
        @Suppress("UNUSED_PARAMETER")
        charge: Double,
    ): StaffSummonCastResult {
        if (closed || !started || input.quick || cast.casterId != player.uniqueId || !ready(player, cast))
            return StaffSummonCastResult.NO_GROUND
        val commander = input.secondary
        val requested = if (commander) 1 else 2
        if (activeActorCount(player.uniqueId) + requested > STAFF_SUMMON_ARMY_LIMIT)
            return StaffSummonCastResult.CAPACITY
        val world = player.world
        val spawnPoints = formation(player).asSequence()
            .mapNotNull { safeSpawnLocation(it, commander) }
            .take(requested)
            .toList()
        if (spawnPoints.size < requested) return StaffSummonCastResult.NO_GROUND

        // Keep the global actor ceiling; its existing oldest-squad eviction policy remains in force.
        while (activeActorCount() + spawnPoints.size > MAX_ACTORS) {
            val oldest = sessions.firstOrNull() ?: break
            remove(oldest)
        }

        val session = Session(player.uniqueId, world.uid, cast, tuning, commander, currentTick)
        spawnPoints.forEach { point ->
            spawnActor(world, point, commander)?.let { mob ->
                session.actors += Actor(mob, commander)
            }
        }
        if (session.actors.size < requested) {
            remove(session)
            return StaffSummonCastResult.NO_GROUND
        }
        sessions += session
        return StaffSummonCastResult.CAST
    }

    /** Retargets the caster's already active squad without changing any actor timers or creating projectiles. */
    fun commandFocus(player: Player, target: LivingEntity, cast: StaffSpellCast): Boolean {
        if (closed || !started || cast.casterId != player.uniqueId || !ready(player, cast) ||
            target.uniqueId == player.uniqueId || target.world.uid != player.world.uid ||
            !target.isValid || target.isDead || target.isInvulnerable || !loaded(target.location) ||
            !damage.eligible(player, target, cast) || !visible(player.eyeLocation, target)) return false
        val session = activeCommandSession(player) ?: return false
        session.focusTargetId = target.uniqueId
        session.rallyPoint = null
        return true
    }

    /** Prepares exactly one existing attack per surviving actor, with one designated leading arrow. */
    fun commandVolley(player: Player, target: LivingEntity, cast: StaffSpellCast,
        presentationActive: () -> Boolean = { true }): Boolean {
        if (closed || !started || cast.casterId != player.uniqueId || !ready(player, cast) ||
            !validCommandTarget(player, target, cast) || !presentationActive()) return false
        val army = sessions.filter { session ->
            session.casterId == player.uniqueId && session.worldId == player.world.uid && ready(player, session.cast)
        }
        val actors = army.flatMap { it.actors }.filter {
            it.mob.isValid && !it.mob.isDead && it.mob.world === player.world && loaded(it.mob.location)
        }
        if (actors.isEmpty()) return false
        army.forEach { session ->
            session.focusTargetId = target.uniqueId
            session.rallyPoint = null
        }
        volleys[player.uniqueId] = Volley(cast, player.world.uid, target.uniqueId,
            actors.mapTo(mutableSetOf()) { it.mob.uniqueId }, actors.firstOrNull { !it.commander }?.mob?.uniqueId,
            currentTick + VOLLEY_WINDUP_TICKS, presentationActive)
        actors.forEach(::resetWindup)
        return true
    }

    private fun validCommandTarget(player: Player, target: LivingEntity, cast: StaffSpellCast): Boolean =
        target.uniqueId != player.uniqueId && !isCasterActor(player.uniqueId, target) &&
            target.isValid && !target.isDead && !target.isInvulnerable && target.world === player.world &&
            loaded(target.location) && player.eyeLocation.distanceSquared(center(target)) <=
            FOCUS_COMMAND_RANGE * FOCUS_COMMAND_RANGE && visible(player.eyeLocation, target) &&
            damage.eligible(player, target, cast)

    private fun currentVolleyTarget(owner: Player, volley: Volley): LivingEntity? {
        if (volleys[owner.uniqueId] !== volley || owner.world.uid != volley.worldId || !ready(owner, volley.cast)) return null
        if (currentTick <= volley.releaseTick && !volley.presentationActive()) return null
        return (Bukkit.getEntity(volley.targetId) as? LivingEntity)?.takeIf {
            validCommandTarget(owner, it, volley.cast)
        }
    }

    /** Rallying only changes the squad's idle destination; attack windups and cooldown ticks remain intact. */
    fun commandRally(player: Player, point: Location, cast: StaffSpellCast): Boolean {
        if (closed || !started || cast.casterId != player.uniqueId || !ready(player, cast) ||
            point.world !== player.world || !loaded(point) || player.location.distanceSquared(point) >
            RALLY_COMMAND_RANGE * RALLY_COMMAND_RANGE || !visible(player.eyeLocation, point.clone().add(0.0, .8, 0.0))) return false
        val safePoint = resolveSupportedLocation(point, width = .6, height = 2.0,
            maxRise = MAX_SPAWN_STEP_UP, maxDrop = MAX_SPAWN_STEP_DOWN) ?: return false
        val session = activeCommandSession(player) ?: return false
        session.focusTargetId = null
        session.rallyPoint = safePoint
        return true
    }

    private fun activeCommandSession(player: Player): Session? = sessions.asReversed().firstOrNull { session ->
        session.casterId == player.uniqueId && session.worldId == player.world.uid &&
            session.actors.any { it.mob.isValid && !it.mob.isDead }
    }

    fun cancel(casterId: UUID) {
        volleys.remove(casterId)
        sessions.filter { it.casterId == casterId }.forEach(::remove)
    }

    override fun close() {
        if (closed) return
        closed = true
        try {
            tasks.close()
        } finally {
            volleys.clear()
            sessions.toList().forEach(::remove)
            HandlerList.unregisterAll(this)
        }
    }

    private fun tick() {
        currentTick++
        volleys.entries.removeIf { (ownerId, volley) ->
            val owner = Bukkit.getPlayer(ownerId)
            owner == null || currentTick > volley.releaseTick + VOLLEY_MAX_FLIGHT_TICKS ||
                currentVolleyTarget(owner, volley) == null
        }
        sessions.toList().forEach sessionLoop@{ session ->
            val player = Bukkit.getPlayer(session.casterId)
            if (player == null || !ready(player, session.cast) || player.world.uid != session.worldId) {
                remove(session)
                return@sessionLoop
            }
            session.ageTicks++
            if (currentTick - session.bornAtTick >= LIFETIME_TICKS) {
                remove(session)
                return@sessionLoop
            }

            session.actors.toList().forEach actorLoop@{ actor ->
                if (sessions.none { it === session }) return@actorLoop
                if (!actor.mob.isValid || actor.mob.isDead || actor.mob.world.uid != session.worldId ||
                    !loaded(actor.mob.location)) {
                    actor.mob.remove()
                    session.actors.remove(actor)
                    return@actorLoop
                }
                enforceActorSafety(actor.mob, actor.commander)
                if (actor.summonTicks < SUMMON_CUE_TICKS) {
                    summonFrame(session, actor, actor.summonTicks)
                    actor.summonTicks++
                    return@actorLoop
                }
                updateActor(session, actor, player)
            }
            tickFlights(session, player)
            if (session.actors.isEmpty()) remove(session)
        }
    }

    private fun updateActor(session: Session, actor: Actor, owner: Player) {
        val mob = actor.mob
        val volley = volleys[session.casterId]?.takeIf {
            currentTick <= it.releaseTick && mob.uniqueId in it.actorIds
        }
        if (volley != null) {
            updateVolleyActor(session, actor, owner, volley)
            return
        }
        val target = selectTarget(owner, session, actor)
        if (target == null) {
            val rally = session.rallyPoint
            if (rally == null) resetWindup(actor)
            moveToward(mob, rally ?: followPoint(owner, session.actors.indexOf(actor)))
            return
        }

        val targetCenter = center(target)
        val distance = center(mob).distanceSquared(targetCenter)
        if (actor.commander && distance > COMMANDER_REACH * COMMANDER_REACH) {
            resetWindup(actor)
            moveToward(mob, approachPoint(mob.location, target.location))
            return
        }
        if (!actor.commander) {
            val horizontalDistance = mob.location.toVector().subtract(target.location.toVector()).apply { y = 0.0 }.length()
            when {
                horizontalDistance > ARCHER_MAX_STANDOFF -> {
                    resetWindup(actor)
                    moveToward(mob, approachPoint(mob.location, target.location, ARCHER_PREFERRED_STANDOFF))
                    return
                }
                horizontalDistance < ARCHER_MIN_STANDOFF -> {
                    resetWindup(actor)
                    moveToward(mob, approachPoint(mob.location, target.location, ARCHER_MIN_STANDOFF))
                    return
                }
            }
        }
        mob.velocity = Vector(0.0, mob.velocity.y, 0.0)
        if (session.successfulHits >= MAX_SESSION_HITS ||
            actor.attacksMade >= (if (actor.commander) COMMANDER_SWINGS else SOLDIER_SHOTS) ||
            session.ageTicks < actor.nextAttackTick) {
            resetWindup(actor)
            return
        }

        if (actor.windupTarget != target.uniqueId) {
            actor.windupTarget = target.uniqueId
            actor.windupTicks = 0
            windupCue(session, mob, starting = true)
        }
        actor.windupTicks++
        if (actor.windupTicks % 2 == 0) windupCue(session, mob, starting = false)
        if (actor.windupTicks < WINDUP_TICKS) return

        mob.swingMainHand()
        val attacked = if (actor.commander) {
            attack(session, actor, owner, target).also { if (it > 0) actor.attacksMade++ } > 0
        } else {
            shootArrow(session, actor, owner, target).also { if (it) actor.attacksMade++ }
        }
        actor.windupTarget = null
        actor.windupTicks = 0
        actor.nextAttackTick = session.ageTicks + ATTACK_COOLDOWN_TICKS
        if (!attacked) resetWindup(actor)
    }

    private fun updateVolleyActor(session: Session, actor: Actor, owner: Player, volley: Volley) {
        val target = currentVolleyTarget(owner, volley) ?: return
        val mob = actor.mob
        resetWindup(actor)
        mob.velocity = Vector(0.0, mob.velocity.y, 0.0)
        val direction = center(target).subtract(mob.eyeLocation).toVector()
        mob.setRotation(Math.toDegrees(atan2(-direction.x, direction.z)).toFloat(), 0.0f)
        if (currentTick < volley.releaseTick) {
            if (currentTick % 2L == 0L) windupCue(session, mob, starting = false)
            return
        }
        // A Q advances one remaining ordinary attack, then restores its ordinary cooldown.
        actor.nextAttackTick = session.ageTicks + ATTACK_COOLDOWN_TICKS
        if (session.successfulHits >= MAX_SESSION_HITS || actor.attacksMade >=
            (if (actor.commander) COMMANDER_SWINGS else SOLDIER_SHOTS) ||
            !validFocusTarget(owner, mob, target, session)) return
        val attacked = if (actor.commander) attack(session, actor, owner, target, volley) > 0
            else shootArrow(session, actor, owner, target, volley,
                if (mob.uniqueId == volley.leadingArcherId) VOLLEY_LEADING_POWER else 1.0)
        if (attacked) {
            actor.attacksMade++
            mob.swingMainHand()
        }
    }

    private fun selectTarget(owner: Player, session: Session, actor: Actor): LivingEntity? {
        val commanded = session.focusTargetId?.let { Bukkit.getEntity(it) as? LivingEntity }
        if (commanded != null && validFocusTarget(owner, actor.mob, commanded, session)) return commanded
        return targeting.nearby(owner, actor.mob.location, targetSearchRadius, session.cast)
            .asSequence()
            .filterNot { isCasterActor(session.casterId, it) }
            .filter { it.world.uid == session.worldId && loaded(it.location) }
            .filter { !it.isInvulnerable }
            .sortedBy { center(it).distanceSquared(center(actor.mob)) }
            .firstOrNull { visible(actor.mob.eyeLocation, it) }
    }

    private fun validFocusTarget(
        owner: Player,
        mob: AbstractSkeleton,
        target: LivingEntity,
        session: Session,
    ): Boolean = target !== mob && !isCasterActor(session.casterId, target) && target.isValid && !target.isDead &&
        !target.isInvulnerable && target.world.uid == session.worldId && mob.world.uid == session.worldId && loaded(target.location) &&
        center(target).distanceSquared(center(mob)) <= FOCUS_COMMAND_RANGE * FOCUS_COMMAND_RANGE &&
        visible(mob.eyeLocation, target) && damage.isCurrent(owner, session.cast) &&
        damage.eligible(owner, target, session.cast)

    private fun attack(session: Session, actor: Actor, owner: Player, target: org.bukkit.entity.LivingEntity,
        volley: Volley? = null): Int {
        if (!ready(owner, session.cast) || !validTarget(owner, actor.mob, target, session)) return 0
        val candidates = if (actor.commander) {
            targeting.nearby(owner, actor.mob.location, COMMANDER_CLEAVE_REACH, session.cast)
                .asSequence()
                .filter { center(it).distanceSquared(center(actor.mob)) <= COMMANDER_CLEAVE_REACH * COMMANDER_CLEAVE_REACH }
                .filter { validTarget(owner, actor.mob, it, session) }
                .sortedBy { center(it).distanceSquared(center(target)) }
                .take(MAX_CLEAVE_TARGETS)
                .toList()
        } else {
            listOf(target)
        }

        var applied = 0
        for (victim in candidates) {
            if (session.successfulHits >= MAX_SESSION_HITS || !ready(owner, session.cast)) break
            if (!validTarget(owner, actor.mob, victim, session)) continue
            val powerScale = if (actor.commander) COMMANDER_POWER_SCALE else SOLDIER_POWER_SCALE
            if (hit(session, owner, victim, session.cast, powerScale, volley = volley)) {
                applied++
            }
        }
        if (applied > 0) impactCue(session, actor.mob, actor.commander)
        return applied
    }

    private fun hit(session: Session, owner: Player, victim: LivingEntity, cast: StaffSpellCast,
        powerScale: Double, multiplier: Double = 1.0, volley: Volley? = null): Boolean {
        var power = session.tuning.power * powerScale * multiplier
        var vanillaDamage = session.tuning.vanillaDamage * VANILLA_DAMAGE_SCALE * multiplier
        var pvpDamage = 0.0
        if (volley != null) {
            if (currentVolleyTarget(owner, volley) == null || volley.successfulHits >= VOLLEY_MAX_HITS ||
                volley.hitsByVictim.getOrDefault(victim.uniqueId, 0) >= VOLLEY_MAX_HITS_PER_VICTIM) return false
            if (victim is Player) {
                val duelMultiplier = cast.duelAuthorization?.context?.damageMultiplier() ?: return false
                val remaining = VOLLEY_PVP_DAMAGE_LIMIT - volley.pvpDamageByVictim.getOrDefault(victim.uniqueId, 0.0)
                if (remaining <= 0.0) return false
                pvpDamage = minOf(vanillaDamage * duelMultiplier, remaining)
                val clamp = pvpDamage / (vanillaDamage * duelMultiplier)
                vanillaDamage *= clamp
                power *= clamp
            }
        }
        if (!damage.hit(owner, victim, cast, power, vanillaDamage, magicStrike = null)) return false
        session.successfulHits++
        if (volley != null) {
            volley.successfulHits++
            volley.hitsByVictim.merge(victim.uniqueId, 1, Int::plus)
            if (pvpDamage > 0.0) volley.pvpDamageByVictim.merge(victim.uniqueId, pvpDamage, Double::plus)
        }
        return true
    }

    private fun validTarget(owner: Player, mob: AbstractSkeleton, target: org.bukkit.entity.LivingEntity, session: Session): Boolean =
        target !== mob && !isCasterActor(session.casterId, target) && target.isValid && !target.isDead && !target.isInvulnerable &&
            target.world.uid == session.worldId && loaded(target.location) &&
            center(target).distanceSquared(center(mob)) <=
            (if (session.commander) COMMANDER_CLEAVE_REACH else targetSearchRadius) *
            (if (session.commander) COMMANDER_CLEAVE_REACH else targetSearchRadius) &&
            visible(mob.eyeLocation, target) && damage.isCurrent(owner, session.cast) &&
            damage.eligible(owner, target, session.cast)

    private fun moveToward(mob: AbstractSkeleton, destination: Location) {
        if (mob.world !== destination.world || !loaded(mob.location)) return
        val current = mob.location
        val dx = destination.x - current.x
        val dz = destination.z - current.z
        val length = kotlin.math.sqrt(dx * dx + dz * dz)
        if (!length.isFinite() || length < 0.12) {
            mob.velocity = Vector(0.0, mob.velocity.y, 0.0)
            return
        }
        val step = minOf(WALK_SPEED, length)
        val vx = dx / length * step
        val vz = dz / length * step
        val next = current.clone().add(vx, 0.0, vz)
        val supportedNext = resolveSupportedLocation(
            next,
            width = mob.width,
            height = mob.height,
            maxRise = MAX_MOVEMENT_STEP,
            maxDrop = MAX_MOVEMENT_STEP,
        )
        if (supportedNext == null) {
            mob.velocity = Vector(0.0, mob.velocity.y, 0.0)
            return
        }
        mob.velocity = Vector(vx, mob.velocity.y.coerceIn(-MAX_VERTICAL_SPEED, MAX_VERTICAL_SPEED), vz)
        mob.setRotation(Math.toDegrees(atan2(-dx, dz)).toFloat(), 0.0f)
    }

    private fun approachPoint(mob: Location, target: Location, standOff: Double = 1.45): Location {
        val away = mob.toVector().subtract(target.toVector()).apply { y = 0.0 }
        if (away.lengthSquared() < 0.01) away.z = -1.0
        return target.clone().add(away.normalize().multiply(standOff))
    }

    private fun followPoint(owner: Player, index: Int): Location {
        val forward = owner.eyeLocation.direction.clone().apply { y = 0.0 }
        if (forward.lengthSquared() < 0.01) forward.z = 1.0
        forward.normalize()
        val right = Vector(forward.z, 0.0, -forward.x)
        val slot = FOLLOW_SLOTS[index.coerceIn(FOLLOW_SLOTS.indices)]
        return owner.location.clone().subtract(forward.multiply(slot.second)).add(right.multiply(slot.first))
    }

    private fun formation(player: Player): List<Location> {
        val forward = player.eyeLocation.direction.clone().apply { y = 0.0 }
        if (forward.lengthSquared() < 0.01) forward.z = 1.0
        forward.normalize()
        val right = Vector(forward.z, 0.0, -forward.x)
        return SPAWN_SLOTS.map { (side, front) ->
            player.location.clone().add(right.clone().multiply(side)).add(forward.clone().multiply(front))
        }
    }

    private fun safeSpawnLocation(at: Location, commander: Boolean): Location? =
        resolveSupportedLocation(
            at,
            width = 0.6,
            height = if (commander) 2.4 else 2.0,
            maxRise = MAX_SPAWN_STEP_UP,
            maxDrop = MAX_SPAWN_GROUND_DROP,
        )

    private fun resolveSupportedLocation(
        at: Location,
        width: Double,
        height: Double,
        maxRise: Double,
        maxDrop: Double,
    ): Location? {
        val world = at.world ?: return null
        if (!loaded(at)) return null
        val radius = (width / 2.0 - BODY_EDGE_INSET).coerceAtLeast(MIN_SUPPORT_RADIUS)
        val supportedY = supportSurfaceAt(world, at.x, at.z, at.y, maxRise, maxDrop) ?: return null
        val resolved = at.clone().apply { y = supportedY }
        if (!hasFootprintSupport(world, resolved, radius)) return null
        if (!bodyClear(resolved, width, height)) return null
        return resolved
    }

    /** Finds the nearest collision top beneath a point without loading any chunks. */
    private fun supportSurfaceAt(
        world: World,
        x: Double,
        z: Double,
        preferredY: Double,
        maxRise: Double,
        maxDrop: Double,
    ): Double? {
        // Include a block whose top is exactly at the lowest allowed foot plane.
        val minY = (floor(preferredY - maxDrop).toInt() - 1).coerceAtLeast(world.minHeight)
        val maxY = floor(preferredY + maxRise).toInt().coerceAtMost(world.maxHeight - 1)
        if (minY > maxY) return null
        val surfaces = mutableListOf<Double>()
        for (blockY in maxY downTo minY) {
            if (!world.isChunkLoaded(floor(x).toInt() shr 4, floor(z).toInt() shr 4)) return null
            val block = world.getBlockAt(floor(x).toInt(), blockY, floor(z).toInt())
            if (!block.isSolid || block.isLiquid) continue
            for (shape in block.collisionShape.boundingBoxes) {
                if (x < block.x + shape.minX + SUPPORT_EDGE_INSET ||
                    x > block.x + shape.maxX - SUPPORT_EDGE_INSET ||
                    z < block.z + shape.minZ + SUPPORT_EDGE_INSET ||
                    z > block.z + shape.maxZ - SUPPORT_EDGE_INSET) continue
                val top = block.y + shape.maxY
                if (top >= preferredY - maxDrop && top <= preferredY + maxRise) surfaces += top
            }
        }
        return surfaces.minWithOrNull(compareBy<Double> { kotlin.math.abs(it - preferredY) }.thenByDescending { it })
    }

    /** Rejects narrow posts and deep gaps while allowing a half-block transition under a mob. */
    private fun hasFootprintSupport(world: World, at: Location, radius: Double): Boolean {
        val supportedCorners = listOf(-radius to -radius, -radius to radius, radius to -radius, radius to radius)
            .count { (x, z) ->
                supportSurfaceAt(
                    world, at.x + x, at.z + z, at.y,
                    maxRise = FOOT_SUPPORT_TOLERANCE,
                    maxDrop = FOOTPRINT_SUPPORT_DROP,
                ) != null
            }
        return supportedCorners >= MIN_SUPPORTED_CORNERS
    }

    private fun bodyClear(at: Location, width: Double, height: Double): Boolean {
        val world = at.world ?: return false
        if (!loaded(at)) return false
        val radius = (width / 2.0 - BODY_EDGE_INSET).coerceAtLeast(MIN_SUPPORT_RADIUS)
        val body = BoundingBox(
            at.x - radius,
            at.y + BODY_FEET_INSET,
            at.z - radius,
            at.x + radius,
            at.y + height - BODY_HEAD_INSET,
            at.z + radius,
        )
        val minX = floor(body.minX).toInt()
        val maxX = floor(body.maxX - BOUNDS_EDGE_EPSILON).toInt()
        val minY = floor(body.minY).toInt()
        val maxY = floor(body.maxY - BOUNDS_EDGE_EPSILON).toInt()
        val minZ = floor(body.minZ).toInt()
        val maxZ = floor(body.maxZ - BOUNDS_EDGE_EPSILON).toInt()
        if (minY < world.minHeight || maxY >= world.maxHeight) return false

        for (x in minX..maxX) for (y in minY..maxY) for (z in minZ..maxZ) {
            if (!world.isChunkLoaded(x shr 4, z shr 4)) return false
            val block = world.getBlockAt(x, y, z)
            if (block.isLiquid) return false
            val localBody = body.clone().shift(-x.toDouble(), -y.toDouble(), -z.toDouble())
            if (block.collisionShape.overlaps(localBody)) return false
        }
        return true
    }

    private fun spawnActor(world: World, at: Location, commander: Boolean): AbstractSkeleton? {
        if (!bodyClear(at, width = 0.6, height = if (commander) 2.4 else 2.0)) return null
        val mob = actorFactory?.invoke(world, at, commander) ?: if (commander) {
            world.spawn(at, WitherSkeleton::class.java)
        } else {
            world.spawn(at, Skeleton::class.java)
        }
        configure(mob, commander)
        return mob.takeIf { it.isValid && !it.isDead }
    }

    private fun configure(mob: AbstractSkeleton, commander: Boolean) {
        mob.isSilent = true
        mob.isPersistent = false
        mob.isInvulnerable = commander
        if (!commander) {
            mob.getAttribute(Attribute.MAX_HEALTH)?.baseValue = SOLDIER_HEALTH
            mob.health = SOLDIER_HEALTH
        }
        mob.setCanPickupItems(false)
        mob.setShouldBurnInDay(false)
        mob.setCollidable(false)
        mob.setNoPhysics(false)
        mob.setGravity(true)
        mob.setTarget(null)
        mob.equipment?.let { gear ->
            gear.clear()
            if (commander) {
                gear.setHelmet(ItemStack(Material.CHAINMAIL_HELMET))
                gear.setChestplate(ItemStack(Material.CHAINMAIL_CHESTPLATE))
            } else {
                gear.setItemInMainHand(ItemStack(Material.BOW))
            }
            gear.setItemInMainHandDropChance(0.0f)
            gear.setItemInOffHandDropChance(0.0f)
            gear.setHelmetDropChance(0.0f)
            gear.setChestplateDropChance(0.0f)
            gear.setLeggingsDropChance(0.0f)
            gear.setBootsDropChance(0.0f)
        }

        // Clearing equipment can cause AbstractSkeleton to reinstall weapon goals.
        // Remove both regular and target goals after all equipment changes.
        Bukkit.getMobGoals().removeAllGoals(mob)
        mob.pathfinder.stopPathfinding()
        mob.setTarget(null)
        mob.setAI(true)
        mob.setAware(true)
    }

    private fun enforceActorSafety(mob: AbstractSkeleton, commander: Boolean) {
        if (!mob.hasAI()) mob.setAI(true)
        if (!mob.isAware) mob.setAware(true)
        if (mob.isInvulnerable != commander) mob.isInvulnerable = commander
        if (mob.isPersistent) mob.isPersistent = false
        if (mob.canPickupItems) mob.setCanPickupItems(false)
        if (mob.shouldBurnInDay()) mob.setShouldBurnInDay(false)
        if (mob.isCollidable) mob.setCollidable(false)
        mob.setTarget(null)
    }

    private fun summonFrame(session: Session, actor: Actor, ageTicks: Int) {
        val samples = staffSummonFrameSamples(ageTicks, actor.commander)
        if (samples.isEmpty()) return
        val origin = actor.mob.location.clone()
        visuals.withOwner(session.casterId) {
            visuals.particles(origin, samples, clearance = SUMMON_CAMERA_CLEARANCE)
            if (ageTicks == 0) visuals.cue(origin, if (actor.commander) Sound.ENTITY_WITHER_SKELETON_AMBIENT
                else Sound.BLOCK_SOUL_SAND_BREAK, 0.38f, if (actor.commander) 0.72f else 0.9f)
        }
    }

    private fun windupCue(session: Session, mob: AbstractSkeleton, starting: Boolean) {
        val anchor = mob.eyeLocation
        val samples = listOf(
            StaffParticleSample(Particle.SOUL, Vector(0.0, -0.25, 0.0), speed = 0.01),
            StaffParticleSample(Particle.DUST, Vector(0.0, -0.35, 0.0),
                data = staffDust(if (session.commander) COMMANDER_COLOR else SOLDIER_COLOR, 1.0f)),
        )
        visuals.withOwner(session.casterId) {
            visuals.particles(anchor, samples, clearance = 0.0)
            if (starting) visuals.cue(anchor, Sound.ENTITY_SKELETON_AMBIENT, 0.22f,
                if (session.commander) 0.65f else 1.1f)
        }
    }

    private fun impactCue(session: Session, mob: AbstractSkeleton, commander: Boolean) {
        val at = mob.location.clone().add(0.0, 0.9, 0.0)
        visuals.withOwner(session.casterId) {
            visuals.particles(at, listOf(
                StaffParticleSample(Particle.SOUL, Vector(), count = if (commander) 4 else 2, spread = Vector(.18, .18, .18), speed = .02),
                StaffParticleSample(Particle.DUST, Vector(), count = if (commander) 5 else 3,
                    spread = Vector(.24, .18, .24), speed = .01,
                    data = staffDust(if (commander) COMMANDER_COLOR else SOLDIER_COLOR, 1.15f)),
            ), clearance = 0.0)
            visuals.cue(at, if (commander) Sound.ENTITY_WITHER_SKELETON_AMBIENT else Sound.ENTITY_SKELETON_HURT,
                0.3f, if (commander) 0.8f else 0.92f)
        }
    }

    private fun resetWindup(actor: Actor) {
        actor.windupTarget = null
        actor.windupTicks = 0
    }

    private fun shootArrow(session: Session, actor: Actor, owner: Player, target: LivingEntity,
        volley: Volley? = null, powerMultiplier: Double = 1.0): Boolean {
        if (session.successfulHits >= MAX_SESSION_HITS || activeFlightCount() >= MAX_PROJECTILES ||
            !ready(owner, session.cast) ||
            !(if (volley == null) validTarget(owner, actor.mob, target, session)
                else validFocusTarget(owner, actor.mob, target, session) && currentVolleyTarget(owner, volley) != null)) return false
        val origin = actor.mob.eyeLocation
        val aim = center(target).toVector().subtract(origin.toVector())
        val distance = aim.length()
        if (!distance.isFinite() || distance <= MIN_ARROW_DISTANCE ||
            distance > (if (volley == null) MAX_ARROW_RANGE else FOCUS_COMMAND_RANGE)) return false
        val direction = aim.multiply(1.0 / distance)
        val arrow = actor.mob.world.spawnArrow(origin, direction, 0.0f, 0.0f)
        arrow.shooter = actor.mob
        arrow.damage = 0.0
        arrow.knockbackStrength = 0
        arrow.isCritical = false
        arrow.setGravity(false)
        arrow.isPersistent = false
        arrow.isSilent = true
        arrow.isInvulnerable = true
        arrow.pickupStatus = org.bukkit.entity.AbstractArrow.PickupStatus.DISALLOWED
        arrow.velocity = Vector()
        orientArrow(arrow, direction)
        if (!arrow.isValid || arrow.isDead) {
            arrow.remove()
            return false
        }
        session.flights += ArrowFlight(arrow, session.cast, session.worldId, direction, origin.clone(), distance,
            volley, powerMultiplier)
        visuals.withOwner(session.casterId) {
            visuals.cue(origin, Sound.ENTITY_SKELETON_SHOOT, 0.3f, 1.0f)
        }
        return true
    }

    private fun tickFlights(session: Session, owner: Player) {
        session.flights.toList().forEach flightLoop@{ flight ->
            if (flight !in session.flights) return@flightLoop
            val arrow = flight.arrow
            if (session.successfulHits >= MAX_SESSION_HITS || !arrow.isValid || arrow.isDead ||
                arrow.world.uid != flight.worldId ||
                flight.worldId != session.worldId || !ready(owner, flight.cast) || owner.world.uid != session.worldId ||
                flight.volley?.let { currentVolleyTarget(owner, it) == null } == true) {
                retireFlight(session, flight)
                return@flightLoop
            }
            flight.elapsedTicks++
            if (flight.elapsedTicks > (if (flight.volley == null) MAX_ARROW_FLIGHT_TICKS else VOLLEY_MAX_FLIGHT_TICKS) ||
                !loaded(flight.position)) {
                retireFlight(session, flight)
                return@flightLoop
            }
            val step = minOf(ARROW_STEP_DISTANCE, flight.remaining)
            if (!step.isFinite() || step <= MIN_ARROW_DISTANCE) {
                retireFlight(session, flight)
                return@flightLoop
            }
            val from = flight.position
            val end = targeting.rayEnd(from, flight.direction, step)
            val travelled = from.distance(end)
            if (!travelled.isFinite() || travelled <= MIN_ARROW_DISTANCE) {
                arrowImpactCue(session, from, hit = false)
                retireFlight(session, flight)
                return@flightLoop
            }

            val victim = targeting.lineTargets(owner, from, end, ARROW_SWEEP_WIDTH, flight.cast)
                .firstOrNull { !isCasterActor(session.casterId, it) }
            if (victim != null) {
                val hitPosition = victim.boundingBox.expand(ARROW_SWEEP_WIDTH)
                    .rayTrace(from.toVector(), flight.direction, travelled)?.hitPosition
                    ?.toLocation(from.world) ?: center(victim)
                arrow.teleport(hitPosition)
                val currentTarget = ready(owner, flight.cast) && victim.isValid && !victim.isDead &&
                    !isCasterActor(session.casterId, victim) &&
                    !victim.isInvulnerable && victim.world.uid == flight.worldId && loaded(victim.location) &&
                    visible(from, victim) && damage.eligible(owner, victim, flight.cast)
                val applied = currentTarget && hit(session, owner, victim, flight.cast,
                    SOLDIER_POWER_SCALE, flight.powerMultiplier, flight.volley)
                arrowImpactCue(session, hitPosition, hit = applied)
                retireFlight(session, flight)
                return@flightLoop
            }

            val clippedByBlock = travelled < step - BLOCK_CLIP_EPSILON
            arrow.teleport(end)
            if (clippedByBlock) {
                arrowImpactCue(session, end, hit = false)
                retireFlight(session, flight)
                return@flightLoop
            }

            flight.position = end
            flight.remaining = (flight.remaining - travelled).coerceAtLeast(0.0)
            orientArrow(arrow, flight.direction)
            if (flight.elapsedTicks % ARROW_TRAIL_INTERVAL_TICKS == 0) {
                visuals.withOwner(session.casterId) {
                    visuals.particles(end, buildList {
                        add(StaffParticleSample(Particle.CRIT, Vector(), count = 1))
                        if (flight.powerMultiplier > 1.0) add(StaffParticleSample(Particle.SOUL_FIRE_FLAME, Vector(), count = 1))
                    }, clearance = 0.0)
                }
            }
            if (flight.remaining <= MIN_ARROW_DISTANCE) retireFlight(session, flight)
        }
    }

    private fun arrowImpactCue(session: Session, at: Location, hit: Boolean) {
        visuals.withOwner(session.casterId) {
            visuals.particles(at, listOf(
                StaffParticleSample(Particle.CRIT, Vector(), count = if (hit) 4 else 2, spread = Vector(.1, .1, .1), speed = .01),
                StaffParticleSample(Particle.SOUL, Vector(0.0, 0.08, 0.0), count = 2, spread = Vector(.08, .08, .08), speed = .01),
            ), clearance = 0.0)
            visuals.cue(at, if (hit) Sound.ENTITY_ARROW_HIT else Sound.BLOCK_STONE_HIT,
                0.24f, if (hit) 1.0f else 0.9f)
        }
    }

    private fun orientArrow(arrow: Arrow, direction: Vector) {
        val horizontal = kotlin.math.sqrt(direction.x * direction.x + direction.z * direction.z)
        val yaw = Math.toDegrees(atan2(-direction.x, direction.z)).toFloat()
        val pitch = Math.toDegrees(-atan2(direction.y, horizontal)).toFloat()
        arrow.setRotation(yaw, pitch)
    }

    private fun activeFlightCount() = sessions.sumOf { session ->
        session.flights.count { it.arrow.isValid }
    }

    private fun retireFlight(session: Session, flight: ArrowFlight) {
        session.flights.remove(flight)
        flight.arrow.remove()
    }

    private fun trackedSession(target: LivingEntity): Session? = sessions.firstNotNullOfOrNull { session ->
        if (session.worldId != target.world.uid) return@firstNotNullOfOrNull null
        session.takeIf { current -> current.actors.any { it.mob.uniqueId == target.uniqueId } }
    }

    private fun isCasterActor(casterId: UUID, target: LivingEntity) =
        sessions.any { session ->
            session.casterId == casterId && session.actors.any { it.mob.uniqueId == target.uniqueId }
        }

    private fun ready(player: Player, cast: StaffSpellCast) =
        player.isOnline && player.isValid && !player.isDead && damage.isCurrent(player, cast)

    private fun loaded(at: Location): Boolean {
        val world = at.world ?: return false
        return at.x.isFinite() && at.y.isFinite() && at.z.isFinite() &&
            world.isChunkLoaded(at.blockX shr 4, at.blockZ shr 4)
    }

    private fun activeActorCount(casterId: UUID? = null) = sessions.asSequence()
        .filter { casterId == null || it.casterId == casterId }
        .sumOf { session -> session.actors.count { it.mob.isValid && !it.mob.isDead } }

    private fun remove(session: Session) {
        sessions.removeAll { it === session }
        session.flights.toList().forEach { retireFlight(session, it) }
        session.actors.forEach { it.mob.remove() }
        session.actors.clear()
    }

    private companion object {
        const val RALLY_COMMAND_RANGE = 32.0
        const val FOCUS_COMMAND_RANGE = 32.0
        const val MAX_ACTORS = 32
        const val LIFETIME_TICKS = 900L
        const val WINDUP_TICKS = 10
        const val VOLLEY_WINDUP_TICKS = 12L
        const val VOLLEY_MAX_FLIGHT_TICKS = 64
        const val VOLLEY_LEADING_POWER = 2.5
        const val VOLLEY_MAX_HITS = 8
        const val VOLLEY_MAX_HITS_PER_VICTIM = 4
        const val VOLLEY_PVP_DAMAGE_LIMIT = 8.0
        const val ATTACK_COOLDOWN_TICKS = 60
        const val SOLDIER_SHOTS = 16
        const val COMMANDER_SWINGS = 16
        const val MAX_SESSION_HITS = 48
        const val MAX_PROJECTILES = 16
        const val MAX_CLEAVE_TARGETS = 3
        const val MAX_TARGET_SEARCH_RADIUS = 12.0
        const val ARCHER_MIN_STANDOFF = 2.0
        const val ARCHER_PREFERRED_STANDOFF = 3.25
        const val ARCHER_MAX_STANDOFF = 5.0
        const val COMMANDER_REACH = 2.8
        const val COMMANDER_CLEAVE_REACH = 2.8
        const val SOLDIER_HEALTH = 4.0
        const val SOLDIER_POWER_SCALE = 0.5
        const val COMMANDER_POWER_SCALE = 0.75
        const val VANILLA_DAMAGE_SCALE = 0.2
        const val ARROW_STEP_DISTANCE = 0.5
        const val MAX_ARROW_RANGE = 8.0
        const val MAX_ARROW_FLIGHT_TICKS = 16
        const val ARROW_SWEEP_WIDTH = 0.2
        const val ARROW_TRAIL_INTERVAL_TICKS = 2
        const val MIN_ARROW_DISTANCE = 0.03
        const val BLOCK_CLIP_EPSILON = 0.02
        const val WALK_SPEED = 0.23
        const val SUMMON_CUE_TICKS = 10
        const val SUMMON_CAMERA_CLEARANCE = 1.0
        const val BODY_EDGE_INSET = 0.03
        const val BODY_FEET_INSET = 0.02
        const val BODY_HEAD_INSET = 0.02
        const val BOUNDS_EDGE_EPSILON = 0.0001
        const val SUPPORT_EDGE_INSET = 0.015
        const val MIN_SUPPORT_RADIUS = 0.1
        const val MAX_SPAWN_STEP_UP = 0.55
        const val MAX_SPAWN_STEP_DOWN = 0.75
        const val MAX_SPAWN_GROUND_DROP = 64.0
        const val MAX_MOVEMENT_STEP = 0.55
        const val MAX_VERTICAL_SPEED = 0.35
        const val FOOT_SUPPORT_TOLERANCE = 0.025
        const val FOOTPRINT_SUPPORT_DROP = 0.55
        const val MIN_SUPPORTED_CORNERS = 2
        const val SOLDIER_COLOR = 0x9B72C4
        const val COMMANDER_COLOR = 0xC7A6E8
        val SPAWN_SLOTS = listOf(
            0.9 to 0.65,
            -0.9 to 0.65,
            1.1 to -0.95,
            -1.1 to -0.95,
        )
        val FOLLOW_SLOTS = listOf(0.9 to 1.9, -0.9 to 1.9, 1.45 to 2.6, -1.45 to 2.6)
    }
}

private fun Entity.playerAttacker(): Player? = when (this) {
    is Player -> this
    is Projectile -> shooter as? Player
    else -> null
}

/** Five compact native-particle stages; kept pure so bounds and commander distinction stay testable. */
internal fun staffSummonFrameSamples(ageTicks: Int, commander: Boolean): List<StaffParticleSample> {
    if (ageTicks !in 0 until 10 || ageTicks % 2 != 0) return emptyList()
    val stage = ageTicks / 2
    val color = if (commander) 0xC7A6E8 else 0x9B72C4
    val points = if (commander) 8 else 6
    val radius = if (commander) 0.36 + stage * 0.11 else 0.27 + stage * 0.10
    return buildList {
        repeat(points) { index ->
            val angle = index * (Math.PI * 2.0 / points) + stage * 0.28
            add(StaffParticleSample(
                Particle.DUST,
                Vector(cos(angle) * radius, 0.07 + stage * 0.015, sin(angle) * radius),
                data = staffDust(color, if (commander) 1.35f else 1.15f),
            ))
        }
        repeat(if (commander) 3 else 2) { index ->
            add(StaffParticleSample(
                if (commander && index == 0) Particle.SOUL else Particle.SOUL_FIRE_FLAME,
                Vector((index - 1) * 0.08, 0.2 + stage * 0.16 + index * 0.08, 0.0),
                speed = 0.008,
            ))
        }
        if (stage >= 2) {
            add(StaffParticleSample(Particle.SMOKE, Vector(0.0, stage * 0.11, 0.0), speed = 0.004))
        }
    }
}
