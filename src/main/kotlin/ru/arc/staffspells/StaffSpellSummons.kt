package ru.arc.staffspells

import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.Particle
import org.bukkit.Sound
import org.bukkit.World
import org.bukkit.entity.Player
import org.bukkit.entity.AbstractSkeleton
import org.bukkit.entity.Skeleton
import org.bukkit.entity.WitherSkeleton
import org.bukkit.inventory.ItemStack
import org.bukkit.util.BoundingBox
import org.bukkit.util.Vector
import ru.arc.core.LifecycleTaskScope
import java.util.UUID
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin

/** Owns short-lived, non-AI undead actors; every hit remains inside the staff damage gate. */
internal class StaffSpellSummons(
    private val settings: StaffSpellSettings,
    private val damage: StaffSpellDamage,
    private val visuals: StaffSpellVisuals,
    private val targeting: StaffSpellTargeting,
    private val actorFactory: ((World, Location, Boolean) -> AbstractSkeleton)? = null,
) : AutoCloseable {
    private data class Actor(
        val mob: AbstractSkeleton,
        val commander: Boolean,
        var summonTicks: Int = 0,
        var windupTarget: UUID? = null,
        var windupTicks: Int = 0,
        var nextAttackTick: Int = 0,
        var successfulAttacks: Int = 0,
    )

    private data class Session(
        val casterId: UUID,
        val worldId: UUID,
        val cast: StaffSpellCast,
        val tuning: StaffSpellTuning,
        val commander: Boolean,
        val bornAtTick: Long,
        val actors: MutableList<Actor> = mutableListOf(),
        var ageTicks: Int = 0,
        var successfulHits: Int = 0,
    )

    private val tasks = LifecycleTaskScope()
    private val sessions = linkedMapOf<UUID, Session>()
    private var currentTick = 0L
    private var started = false
    private var closed = false
    private val targetSearchRadius = minOf(settings.range, MAX_TARGET_SEARCH_RADIUS)

    fun start() {
        check(!closed) { "Summon controller is closed" }
        if (started) return
        started = true
        tasks.runTimer(1, 1, ::tick)
    }

    fun cast(player: Player, cast: StaffSpellCast, tuning: StaffSpellTuning, input: StaffCastInput, charge: Double) {
        if (closed || !started || input.quick || cast.casterId != player.uniqueId || !ready(player, cast)) return
        val commander = input.secondary
        val progress = charge.takeIf(Double::isFinite)?.coerceIn(0.0, 1.0) ?: 0.0
        val requested = if (commander) 1 else (2 + (progress * 2.0).toInt()).coerceIn(2, 4)
        val world = player.world
        val spawnPoints = formation(player).asSequence()
            .mapNotNull { safeSpawnLocation(it, commander) }
            .take(requested)
            .toList()
        if (spawnPoints.size < if (commander) 1 else 2) return

        cancel(player.uniqueId)
        while (activeActorCount() + spawnPoints.size > MAX_ACTORS) {
            val oldest = sessions.values.firstOrNull() ?: break
            remove(oldest)
        }

        val session = Session(player.uniqueId, world.uid, cast, tuning, commander, currentTick)
        spawnPoints.forEach { point ->
            spawnActor(world, point, commander)?.let { mob ->
                session.actors += Actor(mob, commander)
            }
        }
        if (session.actors.isEmpty()) return
        sessions[player.uniqueId] = session
    }

    fun cancel(casterId: UUID) {
        sessions[casterId]?.let(::remove)
    }

    override fun close() {
        if (closed) return
        closed = true
        try {
            tasks.close()
        } finally {
            sessions.values.toList().forEach(::remove)
        }
    }

    private fun tick() {
        currentTick++
        sessions.values.toList().forEach sessionLoop@{ session ->
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
                if (sessions[session.casterId] !== session) return@actorLoop
                if (!actor.mob.isValid || actor.mob.isDead || !loaded(actor.mob.location)) {
                    actor.mob.remove()
                    session.actors.remove(actor)
                    return@actorLoop
                }
                enforceActorSafety(actor.mob)
                if (actor.summonTicks < SUMMON_CUE_TICKS) {
                    summonFrame(session, actor, actor.summonTicks)
                    actor.summonTicks++
                    return@actorLoop
                }
                updateActor(session, actor, player)
            }
            if (session.actors.isEmpty()) remove(session)
        }
    }

    private fun updateActor(session: Session, actor: Actor, owner: Player) {
        val mob = actor.mob
        val target = selectTarget(owner, session, actor)
        if (target == null) {
            resetWindup(actor)
            moveToward(mob, followPoint(owner, session.actors.indexOf(actor)))
            return
        }

        val targetCenter = center(target)
        val reach = if (actor.commander) COMMANDER_REACH else SOLDIER_REACH
        if (center(mob).distanceSquared(targetCenter) > reach * reach) {
            resetWindup(actor)
            moveToward(mob, approachPoint(mob.location, target.location))
            return
        }
        if (session.successfulHits >= MAX_SESSION_HITS ||
            actor.successfulAttacks >= (if (actor.commander) COMMANDER_SWINGS else SOLDIER_HITS) ||
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
        val hits = attack(session, actor, owner, target)
        actor.windupTarget = null
        actor.windupTicks = 0
        actor.nextAttackTick = session.ageTicks + ATTACK_COOLDOWN_TICKS
        if (hits > 0) actor.successfulAttacks++
    }

    private fun selectTarget(owner: Player, session: Session, actor: Actor) =
        targeting.nearby(owner, actor.mob.location, targetSearchRadius, session.cast)
            .asSequence()
            .filter { it.world.uid == session.worldId && loaded(it.location) }
            .filter { !it.isInvulnerable }
            .filter { visible(actor.mob.eyeLocation, center(it)) }
            .sortedBy { center(it).distanceSquared(center(actor.mob)) }
            .firstOrNull()

    private fun attack(session: Session, actor: Actor, owner: Player, target: org.bukkit.entity.LivingEntity): Int {
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
            if (damage.hit(
                    owner,
                    victim,
                    session.cast,
                    session.tuning.power * powerScale,
                    session.tuning.vanillaDamage * VANILLA_DAMAGE_SCALE,
                )) {
                session.successfulHits++
                applied++
            }
        }
        if (applied > 0) impactCue(session, actor.mob, actor.commander)
        return applied
    }

    private fun validTarget(owner: Player, mob: AbstractSkeleton, target: org.bukkit.entity.LivingEntity, session: Session): Boolean =
        target !== mob && target.isValid && !target.isDead && !target.isInvulnerable &&
            target.world.uid == session.worldId && loaded(target.location) &&
            center(target).distanceSquared(center(mob)) <=
            (if (session.commander) COMMANDER_CLEAVE_REACH else SOLDIER_REACH) *
            (if (session.commander) COMMANDER_CLEAVE_REACH else SOLDIER_REACH) &&
            visible(mob.eyeLocation, center(target)) && damage.isCurrent(owner, session.cast) &&
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
        val vx = dx / length * WALK_SPEED
        val vz = dz / length * WALK_SPEED
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

    private fun approachPoint(mob: Location, target: Location): Location {
        val away = mob.toVector().subtract(target.toVector()).apply { y = 0.0 }
        if (away.lengthSquared() < 0.01) away.z = -1.0
        return target.clone().add(away.normalize().multiply(1.45))
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
            maxDrop = MAX_SPAWN_STEP_DOWN,
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
        val minY = floor(preferredY - maxDrop).toInt().coerceAtLeast(world.minHeight)
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
        mob.setAI(false)
        mob.setAware(false)
        mob.isSilent = true
        mob.isPersistent = false
        mob.isInvulnerable = true
        mob.setCanPickupItems(false)
        mob.setShouldBurnInDay(false)
        mob.setCollidable(false)
        mob.setNoPhysics(false)
        mob.setGravity(true)
        mob.setTarget(null)
        val gear = mob.equipment ?: return
        gear.clear()
        if (commander) {
            gear.setHelmet(ItemStack(Material.CHAINMAIL_HELMET))
            gear.setChestplate(ItemStack(Material.CHAINMAIL_CHESTPLATE))
        }
        gear.setItemInMainHandDropChance(0.0f)
        gear.setItemInOffHandDropChance(0.0f)
        gear.setHelmetDropChance(0.0f)
        gear.setChestplateDropChance(0.0f)
        gear.setLeggingsDropChance(0.0f)
        gear.setBootsDropChance(0.0f)
    }

    private fun enforceActorSafety(mob: AbstractSkeleton) {
        if (mob.hasAI()) mob.setAI(false)
        if (mob.isAware) mob.setAware(false)
        if (!mob.isInvulnerable) mob.isInvulnerable = true
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

    private fun ready(player: Player, cast: StaffSpellCast) =
        player.isOnline && player.isValid && !player.isDead && damage.isCurrent(player, cast)

    private fun loaded(at: Location): Boolean {
        val world = at.world ?: return false
        return at.x.isFinite() && at.y.isFinite() && at.z.isFinite() &&
            world.isChunkLoaded(at.blockX shr 4, at.blockZ shr 4)
    }

    private fun activeActorCount() = sessions.values.sumOf { it.actors.count { actor -> actor.mob.isValid } }

    private fun remove(session: Session) {
        if (sessions[session.casterId] === session) sessions.remove(session.casterId)
        session.actors.forEach { it.mob.remove() }
        session.actors.clear()
    }

    private companion object {
        const val MAX_ACTORS = 16
        const val LIFETIME_TICKS = 200L
        const val WINDUP_TICKS = 10
        const val ATTACK_COOLDOWN_TICKS = 20
        const val SOLDIER_HITS = 3
        const val COMMANDER_SWINGS = 2
        const val MAX_SESSION_HITS = 6
        const val MAX_CLEAVE_TARGETS = 3
        const val MAX_TARGET_SEARCH_RADIUS = 12.0
        const val SOLDIER_REACH = 2.25
        const val COMMANDER_REACH = 2.8
        const val COMMANDER_CLEAVE_REACH = 2.8
        const val SOLDIER_POWER_SCALE = 0.5
        const val COMMANDER_POWER_SCALE = 0.75
        const val VANILLA_DAMAGE_SCALE = 0.2
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
