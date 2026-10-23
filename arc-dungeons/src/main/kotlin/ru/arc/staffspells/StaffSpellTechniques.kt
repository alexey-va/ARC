package ru.arc.staffspells

import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.Particle
import org.bukkit.Sound
import org.bukkit.entity.LivingEntity
import org.bukkit.entity.Player
import org.bukkit.potion.PotionEffect
import org.bukkit.potion.PotionEffectType
import org.bukkit.util.Vector
import org.joml.Vector3f
import ru.arc.core.LifecycleTaskScope
import java.util.UUID
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/** Q-owned techniques. Each sequence owns a bounded scene and rechecks its cast at each impact. */
internal class StaffSpellTechniques(
    private val settings: StaffSpellSettings,
    private val damage: StaffSpellDamage,
    private val effects: StaffSpellDisplayEffects,
    private val visuals: StaffSpellVisuals,
    private val targeting: StaffSpellTargeting,
    private val summons: StaffSpellSummons,
    private val rootTarget: (Player, LivingEntity, StaffSpellCast, Int) -> Boolean,
    private val rootActive: (UUID, UUID) -> Boolean,
    private val clearRoots: (UUID) -> Unit,
) : AutoCloseable {
    private enum class LunarPhase { OUTBOUND, TURN, RETURN }

    private data class LunarFlight(
        val casterId: UUID,
        val cast: StaffSpellCast,
        val tuning: StaffSpellTuning,
        val worldId: UUID,
        val origin: Location,
        val forward: Vector,
        var position: Location,
        var sceneId: UUID?,
        var ageTicks: Int = 0,
        var phase: LunarPhase = LunarPhase.OUTBOUND,
        var turnTicks: Int = 0,
        var yaw: Float = 0f,
        var travelled: Double = 0.0,
        val attemptedTargets: MutableSet<UUID> = mutableSetOf(),
    )

    private data class ChainOrb(
        val casterId: UUID,
        val cast: StaffSpellCast,
        val tuning: StaffSpellTuning,
        val worldId: UUID,
        var position: Location,
        var direction: Vector,
        var sceneId: UUID?,
        var ageTicks: Int = 0,
        var travelled: Double = 0.0,
        var burstAge: Int? = null,
    )

    private data class GaleHurricane(
        val casterId: UUID,
        val cast: StaffSpellCast,
        val tuning: StaffSpellTuning,
        val worldId: UUID,
        val origin: Location,
        val sceneId: UUID,
        var ageTicks: Int = 0,
        val targetHits: MutableMap<UUID, Int> = linkedMapOf(),
    )

    private data class IcicleBurst(
        val casterId: UUID,
        val cast: StaffSpellCast,
        val tuning: StaffSpellTuning,
        val worldId: UUID,
        val origin: Location,
        val sceneId: UUID,
        var ageTicks: Int = 0,
    )

    private data class PrismWard(
        val casterId: UUID,
        val cast: StaffSpellCast,
        val tuning: StaffSpellTuning,
        val worldId: UUID,
        val origin: Location,
        val yaw: Float,
        var sceneId: UUID?,
        var ageTicks: Int = 0,
        val alreadyHit: MutableSet<UUID> = mutableSetOf(),
        val crossing: MutableSet<UUID> = mutableSetOf(),
        var lastCutTick: Int? = null,
    )

    private data class NecromancerOrder(
        val casterId: UUID,
        val cast: StaffSpellCast,
        val anchor: Location,
        val target: LivingEntity?,
        val sceneId: UUID,
        var ageTicks: Int = 0,
    )

    private data class GravityHold(
        val casterId: UUID,
        val cast: StaffSpellCast,
        val tuning: StaffSpellTuning,
        val worldId: UUID,
        val target: LivingEntity?,
        val anchor: Location,
        val originalGravity: Boolean,
        var sceneId: UUID?,
        var ageTicks: Int = 0,
    )

    private data class LancePin(
        val casterId: UUID,
        val cast: StaffSpellCast,
        val worldId: UUID,
        val target: LivingEntity?,
        val pin: Boolean,
        var sceneId: UUID?,
        var ageTicks: Int = 0,
    )

    private enum class EmberStage { RECORDING, IGNITING }
    private data class EmberTrail(
        val casterId: UUID,
        val cast: StaffSpellCast,
        val tuning: StaffSpellTuning,
        val worldId: UUID,
        val origin: Location,
        val points: MutableList<Location>,
        var sceneId: UUID?,
        var stage: EmberStage = EmberStage.RECORDING,
        var ageTicks: Int = 0,
        var lastPointTick: Int = 0,
        var igniteIndex: Int = 0,
        val alreadyHit: MutableSet<UUID> = mutableSetOf(),
    )

    private val tasks = LifecycleTaskScope()
    private val lunarFlights = linkedMapOf<UUID, LunarFlight>()
    private val chainOrbs = linkedMapOf<UUID, ChainOrb>()
    private val galeHurricanes = linkedMapOf<UUID, GaleHurricane>()
    private val icicleBursts = linkedMapOf<UUID, IcicleBurst>()
    private val prismWards = linkedMapOf<UUID, PrismWard>()
    private val necromancerOrders = linkedMapOf<UUID, NecromancerOrder>()
    private val gravityHolds = linkedMapOf<UUID, GravityHold>()
    /** Holds in-flight reservations too, so a synchronous damage callback cannot start a second hold. */
    private val gravityTargetOwners = mutableMapOf<UUID, UUID>()
    private val lancePins = linkedMapOf<UUID, LancePin>()
    private val emberTrails = linkedMapOf<UUID, EmberTrail>()
    private var tick = 0L
    private var started = false
    private var closed = false

    fun start() {
        check(!closed) { "Staff Q techniques are closed" }
        if (started) return
        started = true
        tasks.runTimer(1, 1, ::tickAll)
    }

    /** Returns true only when this controller admitted the Q activation. */
    fun cast(player: Player, spell: StaffSpell, cast: StaffSpellCast, tuning: StaffSpellTuning): Boolean {
        if (closed || !started || !ready(player, cast)) return false
        return when (spell) {
            StaffSpell.CHAIN -> castChain(player, cast, tuning)
            StaffSpell.GALE -> castGaleHurricane(player, cast, tuning)
            StaffSpell.ICICLE -> castIcicleBurst(player, cast, tuning)
            StaffSpell.LUNAR -> castLunar(player, cast, tuning)
            StaffSpell.PRISM -> castPrismWard(player, cast, tuning)
            StaffSpell.NECROMANCER -> commandNecromancer(player, cast)
            StaffSpell.GRAVITY -> castGravity(player, cast, tuning)
            StaffSpell.LANCE -> castLance(player, cast, tuning)
            StaffSpell.EMBER -> castEmberTrail(player, cast, tuning)
            else -> false
        }
    }

    private fun castLunar(player: Player, cast: StaffSpellCast, tuning: StaffSpellTuning): Boolean {
        if (player.uniqueId in lunarFlights || !admitNewScenes(1)) return false
        val eye = player.eyeLocation
        val direction = eye.direction.clone().apply { y = 0.0 }
        if (!direction.lengthSquared().isFinite() || direction.lengthSquared() < MIN_DIRECTION_SQUARED) return false
        direction.normalize()
        val origin = eye.clone().add(direction.clone().multiply(LUNAR_LAUNCH_OFFSET))
            .subtract(0.0, LUNAR_BELOW_EYE, 0.0)
        val yaw = atan2(-direction.x, direction.z).toFloat()
        val flight = LunarFlight(player.uniqueId, cast, tuning, player.world.uid, origin.clone(), direction,
            origin.clone(), null, yaw = yaw)
        flight.sceneId = effects.custom(player.uniqueId, StaffSpell.LUNAR, origin, STAFF_LUNAR_TOTAL_TICKS) { age ->
            staffLunarBoomerangParts(yaw, age)
        } ?: return false
        lunarFlights[player.uniqueId] = flight
        visuals.withOwner(player.uniqueId) {
            visuals.cue(origin, Sound.ENTITY_PLAYER_ATTACK_SWEEP, 0.72f, 0.66f)
            visuals.cue(origin, Sound.BLOCK_AMETHYST_BLOCK_CHIME, 0.28f, 1.28f)
            lunarParticles(origin, apex = false)
        }
        return true
    }

    private fun castChain(player: Player, cast: StaffSpellCast, tuning: StaffSpellTuning): Boolean {
        chainOrbs[player.uniqueId]?.let { orb ->
            if (orb.burstAge != null) return false
            detonateChain(player, orb)
            return true
        }
        if (!admitNewScenes(1)) return false
        val eye = player.eyeLocation
        val direction = targeting.assistedDirection(player, StaffSpell.CHAIN, cast,
            direction = eye.direction, from = eye, range = settings.range)
        if (!safeDirection(direction)) return false
        val origin = targeting.rayEnd(eye, direction, CHAIN_LAUNCH_OFFSET)
        if (!isLoaded(origin)) return false
        val yaw = atan2(-direction.x, direction.z).toFloat()
        val orb = ChainOrb(player.uniqueId, cast, tuning, player.world.uid, origin.clone(), direction, null)
        orb.sceneId = effects.custom(player.uniqueId, StaffSpell.CHAIN, origin,
            CHAIN_MAX_TICKS + STAFF_CHAIN_BURST_TICKS) { age ->
            staffChainTechniqueParts(yaw, age / 2)
        } ?: return false
        chainOrbs[player.uniqueId] = orb
        visuals.withOwner(player.uniqueId) {
            visuals.cue(origin, Sound.BLOCK_RESPAWN_ANCHOR_CHARGE, .62f, 1.35f)
            visuals.particles(origin, chainTrailSamples(), clearance = 0.0)
        }
        if (eye.distance(origin) + BLOCK_CLIP_EPSILON < CHAIN_LAUNCH_OFFSET) detonateChain(player, orb)
        return true
    }

    private fun detonateChain(player: Player, orb: ChainOrb) {
        if (chainOrbs[orb.casterId] !== orb || !ready(player, orb.cast) || player.world.uid != orb.worldId) {
            removeChain(orb)
            return
        }
        if (orb.burstAge != null) return
        // Enter the terminal phase before invoking damage: a synchronous hit callback can re-enter Q.
        orb.burstAge = 0
        val impact = orb.position.clone()
        effects.updateCustom(orb.sceneId, impact, staffChainBurstParts(0))
        visuals.withOwner(orb.casterId) {
            visuals.cue(impact, Sound.ENTITY_LIGHTNING_BOLT_IMPACT, .85f, 1.35f)
            visuals.cue(impact, Sound.BLOCK_RESPAWN_ANCHOR_DEPLETE, .32f, 1.65f)
            visuals.particles(impact, staffChainBurstParticles(), clearance = 0.0)
        }
        val targets = targeting.nearby(player, impact, STAFF_CHAIN_DETONATION_RADIUS, orb.cast)
            .filter { staffVisibleTargetPoint(impact, it, player.eyeLocation) != null }
            .sortedBy { center(it).distanceSquared(impact) }
            .take(settings.maxAreaTargets.coerceIn(1, MAX_CHAIN_TARGETS))
        for (target in targets) {
            if (chainOrbs[orb.casterId] !== orb || !damage.isCurrent(player, orb.cast)) break
            if (!validTechniqueTarget(player, target, orb.cast, orb.worldId) ||
                staffVisibleTargetPoint(impact, target, player.eyeLocation) == null) continue
            damage.hit(player, target, orb.cast, orb.tuning.power, orb.tuning.vanillaDamage)
        }
    }

    private fun tickChainOrbs() {
        chainOrbs.values.toList().forEach { orb ->
            if (chainOrbs[orb.casterId] !== orb) return@forEach
            val player = Bukkit.getPlayer(orb.casterId)
            if (player == null || !ready(player, orb.cast) || player.world.uid != orb.worldId ||
                !isLoaded(orb.position) || !effects.isActive(orb.sceneId)) {
                removeChain(orb)
                return@forEach
            }
            orb.ageTicks++
            orb.burstAge?.let { age ->
                orb.burstAge = age + 1
                if (age + 1 >= STAFF_CHAIN_BURST_TICKS) removeChain(orb)
                else effects.updateCustom(orb.sceneId, orb.position, staffChainBurstParts(age + 1))
                return@forEach
            }
            val look = player.eyeLocation.direction
            if (safeDirection(look)) orb.direction = rotateToward(orb.direction, look, CHAIN_TURN_RADIANS)
            val step = min(CHAIN_SPEED, (CHAIN_MAX_RANGE - orb.travelled).coerceAtLeast(0.0))
            val next = orb.position.clone().add(orb.direction.clone().multiply(step))
            val reached = step > MIN_MOVE_DISTANCE && moveChain(orb, player, next)
            if (chainOrbs[orb.casterId] !== orb || orb.burstAge != null) return@forEach
            if (!reached || orb.travelled + MIN_MOVE_DISTANCE >= CHAIN_MAX_RANGE || orb.ageTicks >= CHAIN_MAX_TICKS) {
                detonateChain(player, orb)
                return@forEach
            }
            effects.updateCustom(orb.sceneId, orb.position, staffChainTechniqueParts(
                atan2(-orb.direction.x, orb.direction.z).toFloat(), orb.ageTicks / 2))
            if (orb.ageTicks % 2 == 0) visuals.withOwner(orb.casterId) {
                visuals.particles(orb.position, chainTrailSamples(), clearance = 0.0)
            }
            if (orb.ageTicks == 12) visuals.withOwner(orb.casterId) {
                visuals.cue(orb.position, Sound.BLOCK_RESPAWN_ANCHOR_CHARGE, .24f, 1.85f)
            }
        }
    }

    private fun moveChain(orb: ChainOrb, player: Player, desired: Location): Boolean {
        val delta = desired.toVector().subtract(orb.position.toVector())
        val distance = delta.length()
        if (!distance.isFinite() || distance < MIN_MOVE_DISTANCE) return false
        val clipped = targeting.rayEnd(orb.position, delta.multiply(1.0 / distance), distance)
        val moved = orb.position.distance(clipped)
        if (!moved.isFinite()) return false
        val proximity = chainProximityPoint(orb, player, orb.position, clipped)
        if (proximity != null) {
            orb.travelled += orb.position.distance(proximity)
            orb.position = proximity
            detonateChain(player, orb)
            return false
        }
        if (moved < MIN_MOVE_DISTANCE) return false
        orb.position = clipped
        orb.travelled += moved
        return moved + BLOCK_CLIP_EPSILON >= distance
    }

    /** A swept proximity fuse; the chosen point always remains on the already-clipped flight segment. */
    private fun chainProximityPoint(orb: ChainOrb, player: Player, from: Location, to: Location): Location? {
        val delta = to.toVector().subtract(from.toVector())
        val lengthSquared = delta.lengthSquared()
        val midpoint = from.clone().add(delta.clone().multiply(.5))
        return targeting.nearby(player, midpoint, delta.length() * .5 + CHAIN_PROXIMITY_RADIUS + 1.0, orb.cast)
            .sortedBy { center(it).distanceSquared(from) }.take(MAX_CHAIN_PROXIMITY_CANDIDATES)
            .firstNotNullOfOrNull { target ->
                if (!validTechniqueTarget(player, target, orb.cast, orb.worldId)) return@firstNotNullOfOrNull null
                val along = if (lengthSquared < MIN_DIRECTION_SQUARED) 0.0 else
                    (center(target).toVector().subtract(from.toVector()).dot(delta) / lengthSquared).coerceIn(0.0, 1.0)
                val point = from.clone().add(delta.clone().multiply(along))
                val body = staffVisibleTargetPoint(point, target, player.eyeLocation) ?: return@firstNotNullOfOrNull null
                point.takeIf { body.distanceSquared(it) <= CHAIN_PROXIMITY_RADIUS * CHAIN_PROXIMITY_RADIUS }
            }
    }

    private fun removeChain(orb: ChainOrb) {
        chainOrbs.remove(orb.casterId, orb)
        effects.remove(orb.sceneId)
    }

    private fun chainTrailSamples() = listOf(
        StaffParticleSample(Particle.ELECTRIC_SPARK, Vector(), count = 5, spread = Vector(.13, .13, .13), speed = .02),
        StaffParticleSample(Particle.DUST_COLOR_TRANSITION, Vector(), count = 7, spread = Vector(.24, .22, .24),
            speed = .008, data = staffFade(0xA8E8FF, 0x8357FF, 1.25f)),
    )

    private fun castGaleHurricane(player: Player, cast: StaffSpellCast, tuning: StaffSpellTuning): Boolean {
        if (player.uniqueId in galeHurricanes || !admitNewScenes(1)) return false
        // Wind is anchored to the cast point, including mid-air; it neither tracks the caster nor needs a floor.
        val origin = player.location.clone()
        if (!isLoaded(origin)) return false
        val scene = effects.custom(player.uniqueId, StaffSpell.GALE, origin,
            STAFF_GALE_HURRICANE_TICKS, ::staffGaleHurricaneParts) ?: return false
        galeHurricanes[player.uniqueId] = GaleHurricane(player.uniqueId, cast, tuning, player.world.uid, origin, scene)
        visuals.withOwner(player.uniqueId) {
            visuals.cue(origin, Sound.ENTITY_BREEZE_CHARGE, 1.1f, .55f)
            visuals.cue(origin, Sound.ENTITY_BREEZE_WIND_BURST, 1.15f, .62f)
            visuals.particles(origin, staffGaleHurricaneParticles(0), clearance = 2.0)
        }
        return true
    }

    private fun tickGaleHurricanes() {
        galeHurricanes.values.toList().forEach { hurricane ->
            if (galeHurricanes[hurricane.casterId] !== hurricane) return@forEach
            val player = Bukkit.getPlayer(hurricane.casterId)
            if (player == null || !ready(player, hurricane.cast) || player.world.uid != hurricane.worldId ||
                !isLoaded(hurricane.origin) || !effects.isActive(hurricane.sceneId)) {
                removeGaleHurricane(hurricane)
                return@forEach
            }
            hurricane.ageTicks++
            val age = hurricane.ageTicks
            if (age >= STAFF_GALE_HURRICANE_TICKS) {
                removeGaleHurricane(hurricane)
                return@forEach
            }
            if (age % 2 == 0) visuals.withOwner(hurricane.casterId) {
                visuals.particles(hurricane.origin, staffGaleHurricaneParticles(age), clearance = 2.0)
            }
            if (age == STAFF_GALE_HURRICANE_BUILD_TICKS || age == 42 || age == 72) visuals.withOwner(hurricane.casterId) {
                visuals.cue(hurricane.origin.clone().add(0.0, 2.0, 0.0), Sound.ENTITY_BREEZE_IDLE_AIR,
                    1.15f, if (age == STAFF_GALE_HURRICANE_BUILD_TICKS) .55f else .72f)
                visuals.cue(hurricane.origin, Sound.ENTITY_BREEZE_WHIRL, .86f, .62f + age * .002f)
            }
            if (age >= STAFF_GALE_HURRICANE_BUILD_TICKS &&
                age < STAFF_GALE_HURRICANE_BUILD_TICKS + STAFF_GALE_HURRICANE_ACTIVE_TICKS &&
                (age - STAFF_GALE_HURRICANE_BUILD_TICKS) % STAFF_GALE_HURRICANE_PULSE_TICKS == 0) {
                pulseGaleHurricane(player, hurricane)
            }
        }
    }

    private fun pulseGaleHurricane(player: Player, hurricane: GaleHurricane) {
        val origin = hurricane.origin
        val source = origin.clone().add(0.0, .9, 0.0)
        val query = origin.clone().add(0.0, STAFF_GALE_HURRICANE_HEIGHT * .5, 0.0)
        val limit = settings.maxAreaTargets.coerceIn(1, GALE_HURRICANE_TARGET_LIMIT)
        val targets = targeting.nearby(player, query, GALE_HURRICANE_QUERY_RADIUS, hurricane.cast)
            .filter { inGaleHurricane(origin, it) }
            .sortedBy { center(it).distanceSquared(source) }
            .take(GALE_HURRICANE_CANDIDATE_LIMIT)
        for (target in targets) {
            if (galeHurricanes[hurricane.casterId] !== hurricane || !ready(player, hurricane.cast)) break
            val previousHits = hurricane.targetHits[target.uniqueId]
            if (previousHits == null && hurricane.targetHits.size >= limit ||
                previousHits != null && previousHits >= STAFF_GALE_HURRICANE_MAX_TARGET_HITS ||
                !validTechniqueTarget(player, target, hurricane.cast, hurricane.worldId) ||
                !inGaleHurricane(origin, target) ||
                staffVisibleTargetPoint(source, target, player.eyeLocation) == null) continue
            // Reserve before callbacks: at most eight targets and eight attempts per target for this entire cast.
            hurricane.targetHits[target.uniqueId] = (previousHits ?: 0) + 1
            if (!damage.hit(player, target, hurricane.cast,
                    hurricane.tuning.power * STAFF_GALE_HURRICANE_PULSE_SCALE,
                    hurricane.tuning.vanillaDamage * STAFF_GALE_HURRICANE_PULSE_SCALE)) continue
            if (galeHurricanes[hurricane.casterId] !== hurricane ||
                !validTechniqueTarget(player, target, hurricane.cast, hurricane.worldId) ||
                !inGaleHurricane(origin, target) ||
                staffVisibleTargetPoint(source, target, player.eyeLocation) == null) continue
            val inward = origin.toVector().subtract(target.location.toVector()).apply { y = 0.0 }
            if (inward.lengthSquared() > MIN_DIRECTION_SQUARED) inward.normalize()
            val tangent = Vector(-inward.z, .12, inward.x).multiply(.26)
            target.velocity = target.velocity.multiply(.45).add(inward.multiply(.18)).add(tangent).apply {
                y = y.coerceIn(.08, .24)
                val horizontal = kotlin.math.hypot(x, z)
                if (horizontal > .65) { x *= .65 / horizontal; z *= .65 / horizontal }
            }
        }
    }

    private fun inGaleHurricane(origin: Location, target: LivingEntity): Boolean {
        if (target.world != origin.world) return false
        val offset = center(target).subtract(origin)
        return offset.x * offset.x + offset.z * offset.z <= STAFF_GALE_HURRICANE_RADIUS * STAFF_GALE_HURRICANE_RADIUS &&
            offset.y in -.5..STAFF_GALE_HURRICANE_HEIGHT
    }

    private fun removeGaleHurricane(hurricane: GaleHurricane) {
        galeHurricanes.remove(hurricane.casterId, hurricane)
        effects.remove(hurricane.sceneId)
    }

    private fun castIcicleBurst(player: Player, cast: StaffSpellCast, tuning: StaffSpellTuning): Boolean {
        if (player.uniqueId in icicleBursts || !admitNewScenes(1)) return false
        val origin = player.location.clone().add(0.0, .04, 0.0)
        if (!isLoaded(origin)) return false
        val scene = effects.custom(player.uniqueId, StaffSpell.ICICLE, origin,
            STAFF_ICICLE_RADIAL_TICKS, ::staffIcicleRadialParts) ?: return false
        icicleBursts[player.uniqueId] = IcicleBurst(player.uniqueId, cast, tuning, player.world.uid, origin, scene)
        visuals.withOwner(player.uniqueId) {
            visuals.cue(origin, Sound.BLOCK_GLASS_PLACE, .68f, .75f)
            visuals.particles(origin, icicleBurstSamples(1.4), clearance = .6)
        }
        return true
    }

    private fun tickIcicleBursts() {
        icicleBursts.values.toList().forEach { burst ->
            val player = Bukkit.getPlayer(burst.casterId)
            if (player == null || !ready(player, burst.cast) || player.world.uid != burst.worldId ||
                !isLoaded(burst.origin) || !effects.isActive(burst.sceneId)) {
                removeIcicleBurst(burst)
                return@forEach
            }
            burst.ageTicks++
            if (burst.ageTicks == STAFF_ICICLE_RADIAL_CONTACT_TICK) {
                visuals.withOwner(burst.casterId) {
                    visuals.cue(burst.origin, Sound.BLOCK_GLASS_BREAK, .86f, 1.12f)
                    visuals.cue(burst.origin, Sound.BLOCK_AMETHYST_BLOCK_CHIME, .34f, 1.65f)
                    visuals.particles(burst.origin, icicleBurstSamples(STAFF_ICICLE_RADIAL_RADIUS), clearance = .6)
                }
                val targets = targeting.nearby(player, burst.origin, STAFF_ICICLE_RADIAL_RADIUS + 2.0, burst.cast)
                    .filter { target ->
                        val offset = center(target).subtract(burst.origin)
                        offset.x * offset.x + offset.z * offset.z <= STAFF_ICICLE_RADIAL_RADIUS * STAFF_ICICLE_RADIAL_RADIUS &&
                            kotlin.math.abs(offset.y) <= 2.5 &&
                            visible(burst.origin.clone().add(0.0, .65, 0.0), target)
                    }.sortedBy { center(it).distanceSquared(burst.origin) }
                    .take(settings.maxAreaTargets.coerceIn(1, 8))
                for (target in targets) {
                    if (icicleBursts[burst.casterId] !== burst || !ready(player, burst.cast)) break
                    if (!validTechniqueTarget(player, target, burst.cast, burst.worldId) ||
                        !visible(burst.origin.clone().add(0.0, .65, 0.0), target)) continue
                    if (damage.hit(player, target, burst.cast, burst.tuning.power * .72, burst.tuning.vanillaDamage * .72) &&
                        icicleBursts[burst.casterId] === burst &&
                        validTechniqueTarget(player, target, burst.cast, burst.worldId)) {
                        rootTarget(player, target, burst.cast, ICICLE_BURST_ROOT_TICKS)
                    }
                }
            }
            if (burst.ageTicks >= STAFF_ICICLE_RADIAL_TICKS) removeIcicleBurst(burst)
        }
    }

    private fun removeIcicleBurst(burst: IcicleBurst) {
        icicleBursts.remove(burst.casterId, burst)
        effects.remove(burst.sceneId)
    }

    private fun icicleBurstSamples(radius: Double) = List(24) { index ->
        val angle = (index / 2) * Math.PI / 6.0
        StaffParticleSample(if (index % 2 == 0) Particle.SNOWFLAKE else Particle.END_ROD,
            Vector(cos(angle) * radius, .28 + index % 2 * .26, sin(angle) * radius),
            count = if (index % 2 == 0) 2 else 1, spread = Vector(.1, .09, .1), speed = .035)
    }

    private fun castPrismWard(player: Player, cast: StaffSpellCast, tuning: StaffSpellTuning): Boolean {
        if (player.uniqueId in prismWards || !admitNewScenes(1)) return false
        val eye = player.eyeLocation
        val direction = eye.direction.clone()
        if (!safeDirection(direction)) return false
        val reach = min(settings.range, PRISM_WARD_RANGE)
        val aim = targeting.rayEnd(eye, direction, reach)
        val ground = targeting.groundBelow(aim.clone().add(0.0, PRISM_GROUND_RAY_HEIGHT, 0.0), PRISM_GROUND_RAY_RANGE)
            ?: return false
        if (ground.world !== player.world || !visible(eye, ground.clone().add(0.0, .8, 0.0))) return false
        val horizontal = direction.clone().apply { y = 0.0 }
        if (!safeDirection(horizontal)) return false
        horizontal.normalize()
        val yaw = atan2(-horizontal.x, horizontal.z).toFloat()
        val ward = PrismWard(player.uniqueId, cast, tuning, player.world.uid, ground, yaw, null)
        ward.sceneId = effects.custom(player.uniqueId, StaffSpell.PRISM, ground, PRISM_WARD_TICKS) { age ->
            staffPrismTripwireParts(yaw, age / 2)
        } ?: return false
        prismWards[player.uniqueId] = ward
        visuals.withOwner(player.uniqueId) {
            visuals.cue(ground, Sound.BLOCK_AMETHYST_BLOCK_RESONATE, .58f, .82f)
            visuals.particles(ground, prismWardSamples(yaw), clearance = .6)
        }
        return true
    }

    private fun tickPrismWards() {
        prismWards.values.toList().forEach { ward ->
            if (prismWards[ward.casterId] !== ward) return@forEach
            val player = Bukkit.getPlayer(ward.casterId)
            if (player == null || !ready(player, ward.cast) || player.world.uid != ward.worldId ||
                ward.origin.world?.uid != ward.worldId || !effects.isActive(ward.sceneId)) {
                removePrism(ward)
                return@forEach
            }
            ward.ageTicks++
            effects.updateCustom(ward.sceneId, ward.origin,
                staffPrismTripwireParts(ward.yaw, ward.ageTicks / 2, ward.lastCutTick?.let { ward.ageTicks - it }))
            val present = mutableSetOf<UUID>()
            targeting.nearby(player, ward.origin, PRISM_DETECTION_RADIUS, ward.cast).forEach targetLoop@{ target ->
                if (prismWards[ward.casterId] !== ward || !damage.isCurrent(player, ward.cast)) return@targetLoop
                if (!target.isValid || target.isDead || target.isInvulnerable || target.world.uid != ward.worldId ||
                    !damage.eligible(player, target, ward.cast) || !visible(player.eyeLocation, target) ||
                    !visible(ward.origin.clone().add(0.0, .35, 0.0), target) ||
                    kotlin.math.abs(center(target).y - ward.origin.y) > PRISM_VERTICAL_TOLERANCE ||
                    !staffPrismTripwireContains(center(target), ward.origin, ward.yaw)) return@targetLoop
                present += target.uniqueId
                if (target.uniqueId !in ward.crossing && target.uniqueId !in ward.alreadyHit) {
                    ward.alreadyHit += target.uniqueId
                    if (damage.hit(player, target, ward.cast, ward.tuning.power, ward.tuning.vanillaDamage)) {
                        if (prismWards[ward.casterId] !== ward ||
                            !validTechniqueTarget(player, target, ward.cast, ward.worldId)) return@targetLoop
                        target.addPotionEffect(PotionEffect(PotionEffectType.SLOWNESS, PRISM_WARD_SLOW_TICKS,
                            1, false, true))
                        ward.lastCutTick = ward.ageTicks
                        visuals.withOwner(ward.casterId) {
                            visuals.cue(center(target), Sound.BLOCK_AMETHYST_BLOCK_HIT, .46f, 1.55f)
                            visuals.cue(center(target), Sound.ENTITY_PLAYER_ATTACK_SWEEP, .32f, 1.65f)
                            visuals.particles(center(target), prismTripSamples(), clearance = 0.0)
                        }
                    }
                }
            }
            ward.crossing.retainAll(present)
            ward.crossing.addAll(present)
            if (ward.ageTicks % 6 == 0) visuals.withOwner(ward.casterId) {
                visuals.particles(ward.origin, prismWardSamples(ward.yaw), clearance = .6)
            }
            if (ward.ageTicks >= PRISM_WARD_TICKS) {
                visuals.withOwner(ward.casterId) {
                    visuals.cue(ward.origin, Sound.BLOCK_AMETHYST_CLUSTER_BREAK, .3f, 1.45f)
                }
                removePrism(ward)
            }
        }
    }

    private fun removePrism(ward: PrismWard) {
        prismWards.remove(ward.casterId, ward)
        effects.remove(ward.sceneId)
    }

    private fun prismWardSamples(yaw: Float) = buildList {
        val vertices = listOf(Vector(-2.05, 0.16, -1.55), Vector(2.05, 0.16, -1.55), Vector(0.0, .16, 2.25))
        repeat(3) { edge ->
            val from = vertices[edge]
            val to = vertices[(edge + 1) % vertices.size]
            repeat(4) { step ->
                val point = from.clone().add(to.clone().subtract(from).multiply((step + .5) / 4.0))
                point.rotateAroundY(yaw.toDouble())
                add(StaffParticleSample(if (step % 2 == 0) Particle.END_ROD else Particle.DUST_COLOR_TRANSITION,
                    point, count = if (step % 2 == 0) 1 else 2,
                    spread = Vector(.035, .035, .035), speed = .006,
                    data = if (step % 2 == 0) null else staffFade(0xE9D8FF, 0x7DE9EF, 1.0f)))
            }
        }
    }

    private fun prismTripSamples() = listOf(
        StaffParticleSample(Particle.END_ROD, Vector(), count = 7, spread = Vector(.22, .18, .22), speed = .045),
        StaffParticleSample(Particle.DUST_COLOR_TRANSITION, Vector(), count = 10,
            spread = Vector(.32, .22, .32), speed = .02, data = staffFade(0xF4DFFF, 0x6EE9FF, 1.15f)),
    )

    private fun commandNecromancer(player: Player, cast: StaffSpellCast): Boolean {
        if (player.uniqueId in necromancerOrders || !admitNewScenes(1)) return false
        val eye = player.eyeLocation
        val target = targeting.aimed(player, min(settings.range, NECROMANCER_COMMAND_RANGE), settings.aimDegrees, cast)
            .firstOrNull()
        if (target != null) {
            if (!visible(eye, target) || !damage.isCurrent(player, cast) ||
                !damage.eligible(player, target, cast)) return false
            val scene = effects.custom(player.uniqueId, StaffSpell.NECROMANCER, target.location, 32,
                ::staffNecromancerCommandParts) ?: return false
            val order = NecromancerOrder(player.uniqueId, cast, target.location.clone(), target, scene)
            necromancerOrders[player.uniqueId] = order
            summons.commandVolley(player, target, cast) {
                necromancerOrders[player.uniqueId] === order && effects.isActive(scene)
            }
            visuals.withOwner(player.uniqueId) {
                visuals.cue(center(target), Sound.ENTITY_WITHER_SKELETON_AMBIENT, .42f, .72f)
                visuals.cue(center(target), Sound.BLOCK_BONE_BLOCK_PLACE, .5f, .65f)
                visuals.particles(center(target), necromancerFocusSamples(), clearance = 0.0)
            }
            return true
        }

        val aim = targeting.rayEnd(eye, eye.direction, min(settings.range, NECROMANCER_COMMAND_RANGE))
        val rally = targeting.groundBelow(aim.clone().add(0.0, 2.0, 0.0), NECROMANCER_RALLY_GROUND_RANGE)
            ?: emptyAimPoint(player)?.subtract(0.0, .8, 0.0) ?: return false
        if (rally.world !== player.world || !visible(eye, rally.clone().add(0.0, .8, 0.0)) ||
            !damage.isCurrent(player, cast)) return false
        val scene = effects.custom(player.uniqueId, StaffSpell.NECROMANCER, rally, 32, ::staffNecromancerCommandParts)
            ?: return false
        necromancerOrders[player.uniqueId] = NecromancerOrder(player.uniqueId, cast, rally.clone(), null, scene)
        summons.commandRally(player, rally, cast)
        visuals.withOwner(player.uniqueId) {
            visuals.cue(rally, Sound.BLOCK_SOUL_SAND_STEP, .46f, 1.28f)
            visuals.particles(rally, necromancerRallySamples(), clearance = .45)
        }
        return true
    }

    private fun tickNecromancerOrders() {
        necromancerOrders.values.toList().forEach { order ->
            val player = Bukkit.getPlayer(order.casterId)
            if (player == null || !ready(player, order.cast) || player.world !== order.anchor.world ||
                !effects.isActive(order.sceneId) || order.target?.let {
                    !validTechniqueTarget(player, it, order.cast, player.world.uid)
                } == true) {
                removeNecromancerOrder(order)
                return@forEach
            }
            order.ageTicks++
            val at = order.target?.location ?: order.anchor
            effects.updateCustom(order.sceneId, at, staffNecromancerCommandParts(order.ageTicks))
            if (order.ageTicks == 12) visuals.withOwner(order.casterId) {
                visuals.cue(at, Sound.ENTITY_WITHER_SHOOT, .44f, .72f)
                visuals.cue(at, Sound.BLOCK_BONE_BLOCK_BREAK, .55f, .7f)
                visuals.particles(at.clone().add(0.0, 1.5, 0.0), necromancerFocusSamples(), clearance = 0.0)
            }
            if (order.ageTicks >= 32) removeNecromancerOrder(order)
        }
    }

    private fun removeNecromancerOrder(order: NecromancerOrder) {
        necromancerOrders.remove(order.casterId, order)
        effects.remove(order.sceneId)
    }

    private fun necromancerFocusSamples() = listOf(
        StaffParticleSample(Particle.SOUL, Vector(), count = 5, spread = Vector(.18, .24, .18), speed = .02),
        StaffParticleSample(Particle.DUST_COLOR_TRANSITION, Vector(0.0, .18, 0.0), count = 8,
            spread = Vector(.28, .34, .28), speed = .008, data = staffFade(0xB895D4, 0x6FEBD0, 1.1f)),
    )

    private fun necromancerRallySamples() = listOf(
        StaffParticleSample(Particle.SOUL, Vector(.0, .1, .0), count = 7, spread = Vector(.5, .12, .5), speed = .008),
        StaffParticleSample(Particle.DUST_COLOR_TRANSITION, Vector(), count = 10, spread = Vector(.65, .12, .65),
            speed = .004, data = staffFade(0x8C6CB3, 0x9DECD5, .95f)),
    )

    private fun castGravity(player: Player, cast: StaffSpellCast, tuning: StaffSpellTuning): Boolean {
        gravityHolds[player.uniqueId]?.let { hold ->
            if (!validGravityHold(player, hold)) {
                releaseGravity(hold)
                return false
            }
            throwGravity(player, hold)
            return true
        }
        if (!admitNewScenes(1)) return false
        val target = targeting.aimed(player, min(settings.range, GRAVITY_Q_RANGE), settings.aimDegrees, cast)
            .firstOrNull()
        if (target == null) {
            val at = emptyAimPoint(player) ?: return false
            val sceneId = effects.custom(player.uniqueId, StaffSpell.GRAVITY, at, GRAVITY_HOLD_TICKS) { age ->
                staffGravityPinParts(age, thrown = false, yaw = 0f)
            } ?: return false
            gravityHolds[player.uniqueId] = GravityHold(player.uniqueId, cast, tuning, player.world.uid,
                null, at, true, sceneId)
            gravityHoldFeedback(player.uniqueId, at)
            return true
        }
        if (!validTechniqueTarget(player, target, cast, player.world.uid) || !reserveGravityTarget(player, target))
            return false
        var admittedHold = false
        try {
            if (!damage.hit(player, target, cast, tuning.power, tuning.vanillaDamage)) return false
            // Damage hooks can invalidate the round, target, or line of sight synchronously.
            if (!validTechniqueTarget(player, target, cast, player.world.uid)) return true
            val gravity = target.hasGravity()
            val at = center(target)
            val sceneId = effects.custom(player.uniqueId, StaffSpell.GRAVITY, at, GRAVITY_HOLD_TICKS) { age ->
                staffGravityPinParts(age, thrown = false, yaw = 0f)
            } ?: return true
            if (!effects.isActive(sceneId) || !validTechniqueTarget(player, target, cast, player.world.uid)) {
                effects.remove(sceneId)
                return true
            }
            if (!rootTarget(player, target, cast, GRAVITY_HOLD_TICKS)) {
                effects.remove(sceneId)
                return true
            }
            if (!effects.isActive(sceneId) || !validTechniqueTarget(player, target, cast, player.world.uid)) {
                clearRoots(cast.attackId)
                effects.remove(sceneId)
                return true
            }
            val hold = GravityHold(player.uniqueId, cast, tuning, player.world.uid, target, at.clone(), gravity, sceneId)
            target.setGravity(false)
            target.velocity = Vector()
            gravityHolds[player.uniqueId] = hold
            admittedHold = true
            gravityHoldFeedback(player.uniqueId, at)
            return true
        } finally {
            if (!admittedHold) gravityTargetOwners.remove(target.uniqueId, player.uniqueId)
        }
    }

    private fun reserveGravityTarget(player: Player, target: LivingEntity): Boolean {
        val targetId = target.uniqueId
        val ownerId = gravityTargetOwners[targetId]
        if (ownerId == null) {
            gravityTargetOwners[targetId] = player.uniqueId
            return true
        }
        if (ownerId == player.uniqueId) return false

        val existingHold = gravityHolds[ownerId]
        if (existingHold == null) return false // A reentrant cast met the earlier cast's opening damage.
        val existingOwner = Bukkit.getPlayer(ownerId)
        if (existingOwner != null && validGravityHold(existingOwner, existingHold)) return false
        releaseGravity(existingHold)
        if (targetId in gravityTargetOwners) return false
        gravityTargetOwners[targetId] = player.uniqueId
        return true
    }

    private fun tickGravityHolds() {
        gravityHolds.values.toList().forEach { hold ->
            if (gravityHolds[hold.casterId] !== hold) return@forEach
            val player = Bukkit.getPlayer(hold.casterId)
            if (player == null || !validGravityHold(player, hold)) {
                releaseGravity(hold)
                return@forEach
            }
            hold.ageTicks++
            hold.target?.let { target -> target.setGravity(false); target.velocity = Vector() }
            val at = hold.target?.let(::center) ?: hold.anchor
            effects.updateCustom(hold.sceneId, at, staffGravityPinParts(hold.ageTicks, thrown = false, yaw = 0f))
            if (hold.ageTicks % 3 == 0) visuals.withOwner(hold.casterId) {
                visuals.particles(at, gravityHoldSamples(), clearance = 0.0)
            }
            if (hold.ageTicks >= GRAVITY_HOLD_TICKS) {
                visuals.withOwner(hold.casterId) {
                    visuals.cue(at, Sound.BLOCK_BEACON_DEACTIVATE, .34f, .72f)
                }
                releaseGravity(hold)
            }
        }
    }

    private fun validGravityHold(player: Player, hold: GravityHold): Boolean {
        val target = hold.target
        return gravityHolds[hold.casterId] === hold && effects.isActive(hold.sceneId) && ready(player, hold.cast) &&
            player.world.uid == hold.worldId && (if (target == null)
                isLoaded(hold.anchor) && visible(player.eyeLocation, hold.anchor)
            else validTechniqueTarget(player, target, hold.cast, hold.worldId) &&
                rootActive(target.uniqueId, hold.cast.attackId))
    }

    private fun throwGravity(player: Player, hold: GravityHold) {
        val eye = player.eyeLocation
        val look = eye.direction
        val aim = targeting.rayEnd(eye, look, min(settings.range, GRAVITY_THROW_RANGE))
        val from = hold.target?.let(::center) ?: hold.anchor
        var direction = aim.toVector().subtract(from.toVector())
        if (!safeDirection(direction)) {
            releaseGravity(hold)
            return
        }
        val clipped = targeting.rayEnd(from, direction, GRAVITY_THROW_RANGE)
        direction = clipped.toVector().subtract(from.toVector())
        if (!safeDirection(direction)) {
            releaseGravity(hold)
            return
        }
        direction.normalize()
        releaseGravity(hold)
        if (hold.target != null) {
            hold.target.velocity = direction.clone().multiply(GRAVITY_THROW_SPEED).add(Vector(0.0, GRAVITY_THROW_LIFT, 0.0))
        }
        run {
            val motion = direction.clone().multiply(min(5.0, from.distance(clipped)))
            val yaw = atan2(-direction.x, direction.z).toFloat()
            effects.custom(player.uniqueId, StaffSpell.GRAVITY, from, 12) { age ->
                val progress = (age / 8f).coerceIn(0f, 1f)
                staffGravityPinParts(age, thrown = true, yaw = yaw).map { part ->
                    part.copy(center = Vector3f(part.center).add((motion.x * progress).toFloat(),
                        (motion.y * progress).toFloat(), (motion.z * progress).toFloat()),
                        scale = Vector3f(part.scale).mul((1f - age / 12f).coerceAtLeast(.01f)))
                }
            }
        }
        visuals.withOwner(hold.casterId) {
            visuals.cue(from, Sound.ENTITY_WARDEN_SONIC_BOOM, .48f, 1.25f)
            visuals.particles(from, gravityThrowSamples(), clearance = 0.0)
        }
    }

    private fun releaseGravity(hold: GravityHold) {
        gravityHolds.remove(hold.casterId, hold)
        hold.target?.let { gravityTargetOwners.remove(it.uniqueId, hold.casterId) }
        clearRoots(hold.cast.attackId)
        effects.remove(hold.sceneId)
        hold.target?.takeIf { it.isValid && !it.isDead }?.setGravity(hold.originalGravity)
    }

    private fun gravityHoldFeedback(ownerId: UUID, at: Location) = visuals.withOwner(ownerId) {
        visuals.cue(at, Sound.BLOCK_RESPAWN_ANCHOR_CHARGE, .56f, .74f)
        visuals.cue(at, Sound.BLOCK_AMETHYST_BLOCK_RESONATE, .24f, .58f)
        visuals.particles(at, gravityHoldSamples(), clearance = 0.0)
    }

    private fun gravityHoldSamples() = listOf(
        StaffParticleSample(Particle.REVERSE_PORTAL, Vector(), count = 5, spread = Vector(.16, .2, .16), speed = .025),
        StaffParticleSample(Particle.DUST_COLOR_TRANSITION, Vector(), count = 9, spread = Vector(.32, .36, .32),
            speed = .009, data = staffFade(0x8E75BA, 0xE0F5FF, 1.15f)),
    )

    private fun gravityThrowSamples() = listOf(
        StaffParticleSample(Particle.SONIC_BOOM, Vector(), count = 1, speed = .0),
        StaffParticleSample(Particle.REVERSE_PORTAL, Vector(), count = 10, spread = Vector(.36, .32, .36), speed = .035),
        StaffParticleSample(Particle.DUST_COLOR_TRANSITION, Vector(), count = 10,
            spread = Vector(.42, .34, .42), speed = .025, data = staffFade(0xE2E9FF, 0x885AFF, 1.25f)),
    )

    private fun castLance(player: Player, cast: StaffSpellCast, tuning: StaffSpellTuning): Boolean {
        if (player.uniqueId in lancePins || !admitNewScenes(1)) return false
        val eye = player.eyeLocation
        val range = min(settings.range, LANCE_Q_RANGE)
        val end = targeting.rayEnd(eye, eye.direction, range)
        val target = targeting.lineTargets(player, eye, end, LANCE_Q_WIDTH, cast).firstOrNull()
        if (target == null) {
            val at = emptyAimPoint(player, 8.0) ?: return false
            val direction = at.toVector().subtract(eye.toVector()).normalize()
            val yaw = atan2(-direction.x, direction.z).toFloat()
            val scene = effects.custom(player.uniqueId, StaffSpell.LANCE, at, LANCE_OPEN_TICKS) { age ->
                staffLancePinParts(1.8, yaw, held = false, ageTicks = age).map { part ->
                    part.copy(scale = Vector3f(part.scale).mul((1f - age.toFloat() / LANCE_OPEN_TICKS).coerceAtLeast(.02f)))
                }
            } ?: return false
            lancePins[player.uniqueId] = LancePin(player.uniqueId, cast, player.world.uid, null, false, scene)
            visuals.withOwner(player.uniqueId) {
                visuals.cue(at, Sound.ITEM_TRIDENT_THROW, .7f, 1.35f)
                visuals.particles(at, lanceImpactSamples(false), clearance = .2)
            }
            return true
        }
        if (!validTechniqueTarget(player, target, cast, player.world.uid)) return false
        val at = center(target)
        val direction = at.toVector().subtract(eye.toVector())
        if (!safeDirection(direction)) return false
        direction.normalize()
        val behind = at.clone().add(direction.clone().multiply(LANCE_WALL_START_OFFSET))
        val wall = staffBlockHit(behind, direction, LANCE_WALL_REACH)
        val wallPoint = wall.hit?.hitBlock?.let { block -> wall.hit?.hitPosition?.toLocation(block.world) }
        val wallDistance = wallPoint?.let { behind.distance(it) }
        val pinned = wall.pathLoaded && wallDistance != null && wallDistance in LANCE_MIN_WALL_DISTANCE..LANCE_MAX_WALL_DISTANCE &&
            visible(eye, at)
        val scale = if (pinned) 1.0 else LANCE_OPEN_DAMAGE_SCALE
        if (!damage.hit(player, target, cast, tuning.power * scale, tuning.vanillaDamage * scale)) return false
        // The damage hook is allowed to end a round or move the victim behind cover.
        if (!validTechniqueTarget(player, target, cast, player.world.uid)) return true

        val holdTicks = if (pinned) LANCE_PIN_TICKS else LANCE_OPEN_TICKS
        val yaw = atan2(-direction.x, direction.z).toFloat()
        val length = if (pinned) (LANCE_WALL_START_OFFSET + wallDistance!!).coerceIn(.8, 4.0) else 1.3
        val pin = LancePin(player.uniqueId, cast, player.world.uid, target, pinned, null)
        val sceneId = effects.custom(player.uniqueId, StaffSpell.LANCE, at, holdTicks) { age ->
            staffLancePinParts(length, yaw, pinned, ageTicks = age).map { part ->
                if (!pinned) part.copy(scale = Vector3f(part.scale).mul((1.0 - age.toDouble() / holdTicks).toFloat()
                    .coerceAtLeast(.08f))) else part
            }
        } ?: return true
        if (!effects.isActive(sceneId) || !validTechniqueTarget(player, target, cast, player.world.uid)) {
            effects.remove(sceneId)
            return true
        }
        pin.sceneId = sceneId
        lancePins[player.uniqueId] = pin
        if (pinned) {
            target.velocity = direction.clone().multiply(LANCE_WALL_IMPACT_SPEED)
            target.addPotionEffect(org.bukkit.potion.PotionEffect(
                org.bukkit.potion.PotionEffectType.SLOWNESS, LANCE_PIN_TICKS, LANCE_PIN_AMPLIFIER,
                false, false, true), true)
        } else {
            target.velocity = direction.clone().multiply(LANCE_OPEN_KNOCKBACK).add(Vector(0.0, .12, 0.0))
        }
        visuals.withOwner(player.uniqueId) {
            visuals.cue(at, if (pinned) Sound.ITEM_TRIDENT_HIT_GROUND else Sound.ITEM_TRIDENT_HIT,
                .62f, if (pinned) .72f else 1.08f)
            if (pinned) visuals.cue(at, Sound.BLOCK_AMETHYST_BLOCK_HIT, .28f, 1.35f)
            visuals.particles(at, lanceImpactSamples(pinned), clearance = 0.0)
        }
        return true
    }

    private fun tickLancePins() {
        lancePins.values.toList().forEach { pin ->
            if (lancePins[pin.casterId] !== pin) return@forEach
            val player = Bukkit.getPlayer(pin.casterId)
            val target = pin.target
            if (player == null || !ready(player, pin.cast) || player.world.uid != pin.worldId ||
                target != null && (!target.isValid || target.isDead || target.world.uid != pin.worldId ||
                !damage.eligible(player, target, pin.cast) || pin.pin && !visible(player.eyeLocation, target))) {
                removeLancePin(pin)
                return@forEach
            }
            pin.ageTicks++
            if (pin.ageTicks >= if (pin.pin) LANCE_PIN_TICKS else LANCE_OPEN_TICKS) {
                if (pin.pin && target != null) visuals.withOwner(pin.casterId) {
                    visuals.cue(center(target), Sound.BLOCK_AMETHYST_CLUSTER_BREAK, .26f, 1.5f)
                }
                removeLancePin(pin)
            }
        }
    }

    private fun removeLancePin(pin: LancePin) {
        lancePins.remove(pin.casterId, pin)
        effects.remove(pin.sceneId)
    }

    private fun lanceImpactSamples(pinned: Boolean) = listOf(
        StaffParticleSample(Particle.CRIT, Vector(), count = if (pinned) 16 else 8,
            spread = Vector(if (pinned) .5 else .28, .28, .28), speed = .08),
        StaffParticleSample(if (pinned) Particle.END_ROD else Particle.CRIT, Vector(0.0, .14, 0.0),
            count = if (pinned) 7 else 3, spread = Vector(.24, .24, .24), speed = .035),
    )

    private fun castEmberTrail(player: Player, cast: StaffSpellCast, tuning: StaffSpellTuning): Boolean {
        emberTrails[player.uniqueId]?.let { trail ->
            if (trail.stage != EmberStage.RECORDING || !ready(player, trail.cast) || trail.worldId != player.world.uid)
                return false
            trail.stage = EmberStage.IGNITING
            trail.ageTicks = 0
            trail.igniteIndex = 0
            visuals.withOwner(player.uniqueId) {
                visuals.particles(trail.points.last(), emberIgniteSamples(), clearance = .2)
            }
            return true
        }
        if (!admitNewScenes(1)) return false
        val ground = targeting.groundBelow(player.location.clone().add(0.0, 1.0, 0.0), EMBER_GROUND_SEARCH)
            ?: return false
        if (ground.world !== player.world || !visible(player.eyeLocation, ground.clone().add(0.0, .15, 0.0))) return false
        val trail = EmberTrail(player.uniqueId, cast, tuning, player.world.uid, ground.clone(), mutableListOf(ground.clone()), null)
        trail.sceneId = effects.custom(player.uniqueId, StaffSpell.EMBER, ground, EMBER_SCENE_LIFETIME_TICKS) { _ ->
            staffEmberTrailParts(trail.points.map(::locationVector), locationVector(trail.origin), -1)
        } ?: return false
        emberTrails[player.uniqueId] = trail
        visuals.withOwner(player.uniqueId) {
            visuals.cue(ground, Sound.BLOCK_CAMPFIRE_CRACKLE, .42f, .78f)
            visuals.particles(ground, emberStartSamples(), clearance = .2)
        }
        return true
    }

    private fun tickEmberTrails() {
        emberTrails.values.toList().forEach { trail ->
            if (emberTrails[trail.casterId] !== trail) return@forEach
            val player = Bukkit.getPlayer(trail.casterId)
            if (player == null || !ready(player, trail.cast) || player.world.uid != trail.worldId ||
                !effects.isActive(trail.sceneId)) {
                removeEmber(trail)
                return@forEach
            }
            trail.ageTicks++
            when (trail.stage) {
                EmberStage.RECORDING -> recordEmberStep(player, trail)
                EmberStage.IGNITING -> igniteEmberStep(player, trail)
            }
            if (emberTrails[trail.casterId] !== trail) return@forEach
            val origin = locationVector(trail.origin)
            val parts = staffEmberTrailParts(trail.points.map(::locationVector), origin, trail.igniteIndex - 1, trail.ageTicks)
            effects.updateCustom(trail.sceneId, trail.origin, parts)
            if (trail.ageTicks >= EMBER_TRAIL_LIFETIME_TICKS && trail.stage == EmberStage.RECORDING) {
                trail.stage = EmberStage.IGNITING
                trail.ageTicks = 0
                trail.igniteIndex = 0
            }
        }
    }

    private fun recordEmberStep(player: Player, trail: EmberTrail) {
        val last = trail.points.last()
        val floor = targeting.groundBelow(player.location.clone().add(0.0, 1.0, 0.0), EMBER_GROUND_SEARCH)
        if (floor != null && floor.world === player.world && visible(player.eyeLocation, floor.clone().add(0.0, .15, 0.0))) {
            val totalLength = trail.points.zipWithNext().sumOf { (a, b) -> a.distance(b) }
            val gap = floor.distance(last)
            if (gap >= EMBER_MIN_POINT_GAP && totalLength + gap <= EMBER_TRAIL_MAX_LENGTH &&
                trail.points.size < STAFF_EMBER_TRAIL_MAX_POINTS) {
                trail.points += floor.clone()
                trail.lastPointTick = trail.ageTicks
                visuals.withOwner(trail.casterId) {
                    visuals.particles(floor, emberTrailSamples(), clearance = .2)
                }
            } else if (totalLength + gap > EMBER_TRAIL_MAX_LENGTH || trail.points.size >= STAFF_EMBER_TRAIL_MAX_POINTS) {
                trail.stage = EmberStage.IGNITING
                trail.ageTicks = 0
                trail.igniteIndex = 0
            }
        }
        val lastLocation = trail.points.last()
        if (player.world.uid != trail.worldId || !player.location.x.isFinite() ||
            !player.location.y.isFinite() || !player.location.z.isFinite()) {
            removeEmber(trail)
            return
        }
        if (lastLocation.distance(player.location) > EMBER_TELEPORT_CUTOFF) {
            // A teleport is a lifecycle seam, not a new path segment; ignite what was recorded.
            trail.stage = EmberStage.IGNITING
            trail.ageTicks = 0
            trail.igniteIndex = 0
        }
        val lit = (trail.points.size - 1).coerceAtLeast(-1)
        effects.updateCustom(trail.sceneId, trail.origin,
            staffEmberTrailParts(trail.points.map(::locationVector), locationVector(trail.origin), lit, trail.ageTicks))
        if (trail.ageTicks % 6 == 0) visuals.withOwner(trail.casterId) {
            visuals.particles(lastLocation, emberTrailSamples(), clearance = .2)
        }
    }

    private fun igniteEmberStep(player: Player, trail: EmberTrail) {
        if (trail.igniteIndex >= trail.points.size) {
            if (trail.ageTicks < trail.points.size * EMBER_IGNITE_INTERVAL_TICKS + EMBER_AFTERGLOW_TICKS) return
            visuals.withOwner(trail.casterId) {
                visuals.cue(trail.points.last(), Sound.BLOCK_FIRE_EXTINGUISH, .28f, 1.3f)
            }
            removeEmber(trail)
            return
        }
        if (trail.ageTicks % EMBER_IGNITE_INTERVAL_TICKS != 0) return
        val point = trail.points[trail.igniteIndex]
        if (point.world !== player.world || !visible(player.eyeLocation, point.clone().add(0.0, .2, 0.0))) {
            trail.igniteIndex++
            return
        }
        visuals.withOwner(trail.casterId) {
            // A short ignition every third node avoids stacking a long ambient clip every two ticks.
            if (trail.igniteIndex % 3 == 0 || trail.igniteIndex == trail.points.lastIndex)
                visuals.cue(point, Sound.ITEM_FIRECHARGE_USE, if (trail.igniteIndex == 0) .52f else .3f,
                    .82f + trail.igniteIndex * .04f)
            visuals.particles(point, emberIgniteSamples(), clearance = .15)
        }
        for (target in targeting.nearby(player, point, EMBER_IGNITE_RADIUS, trail.cast)
            .sortedBy { center(it).distanceSquared(point) }) {
            if (emberTrails[trail.casterId] !== trail || !damage.isCurrent(player, trail.cast)) return
            if (target.uniqueId in trail.alreadyHit || !validTechniqueTarget(player, target, trail.cast, trail.worldId) ||
                !visible(point.clone().add(0.0, .25, 0.0), target)) continue
            trail.alreadyHit += target.uniqueId
            if (damage.hit(player, target, trail.cast,
                    trail.tuning.power * EMBER_NODE_DAMAGE_SCALE,
                    trail.tuning.vanillaDamage * EMBER_NODE_DAMAGE_SCALE)) {
                visuals.withOwner(trail.casterId) {
                    visuals.particles(center(target), emberImpactSamples(), clearance = .0)
                }
            }
        }
        trail.igniteIndex++
    }

    private fun removeEmber(trail: EmberTrail) {
        emberTrails.remove(trail.casterId, trail)
        effects.remove(trail.sceneId)
    }

    private fun emberStartSamples() = listOf(
        StaffParticleSample(Particle.FLAME, Vector(), count = 5, spread = Vector(.16, .1, .16), speed = .018),
        StaffParticleSample(Particle.DUST_COLOR_TRANSITION, Vector(0.0, .12, 0.0), count = 7,
            spread = Vector(.25, .12, .25), speed = .006, data = staffFade(0xFF9B37, 0xD12F19, 1.1f)),
    )

    private fun emberTrailSamples() = listOf(
        StaffParticleSample(Particle.SOUL_FIRE_FLAME, Vector(0.0, .14, 0.0), count = 3,
            spread = Vector(.14, .12, .14), speed = .008),
        StaffParticleSample(Particle.DUST_COLOR_TRANSITION, Vector(0.0, .08, 0.0), count = 5,
            spread = Vector(.24, .1, .24), speed = .003, data = staffFade(0xFFB15C, 0xE1451F, 1.0f)),
    )

    private fun emberIgniteSamples() = listOf(
        StaffParticleSample(Particle.FLAME, Vector(0.0, .22, 0.0), count = 12, spread = Vector(.38, .34, .38), speed = .07),
        StaffParticleSample(Particle.SOUL_FIRE_FLAME, Vector(0.0, .28, 0.0), count = 8,
            spread = Vector(.3, .3, .3), speed = .045),
        StaffParticleSample(Particle.DUST_COLOR_TRANSITION, Vector(0.0, .15, 0.0), count = 10,
            spread = Vector(.42, .3, .42), speed = .025, data = staffFade(0xFFE375, 0xE33F18, 1.25f)),
    )

    private fun emberImpactSamples() = listOf(
        StaffParticleSample(Particle.FLAME, Vector(.0, .25, .0), count = 7, spread = Vector(.25, .25, .25), speed = .06),
        StaffParticleSample(Particle.SOUL_FIRE_FLAME, Vector(.0, .2, .0), count = 4, spread = Vector(.18, .18, .18), speed = .03),
    )

    fun canRecast(casterId: UUID, spell: StaffSpell? = null): Boolean = when (spell) {
        StaffSpell.CHAIN -> chainOrbs[casterId]?.let { it.burstAge == null } == true
        StaffSpell.EMBER -> emberTrails[casterId]?.stage == EmberStage.RECORDING
        StaffSpell.GRAVITY -> casterId in gravityHolds
        null -> chainOrbs[casterId]?.let { it.burstAge == null } == true || emberTrails[casterId]?.stage == EmberStage.RECORDING ||
            casterId in gravityHolds
        else -> false
    }

    fun continuationLabel(casterId: UUID, spell: StaffSpell? = null): String? = when (spell) {
        StaffSpell.CHAIN -> "Detonate Chain orb".takeIf { canRecast(casterId, StaffSpell.CHAIN) }
        StaffSpell.GALE -> "Ураган активен".takeIf { casterId in galeHurricanes }
        StaffSpell.EMBER -> "Ignite Ember trail".takeIf { emberTrails[casterId]?.stage == EmberStage.RECORDING }
        StaffSpell.GRAVITY -> "Throw suspended target".takeIf { casterId in gravityHolds }
        StaffSpell.LUNAR -> lunarFlights[casterId]?.let { flight ->
            if (flight.phase == LunarPhase.RETURN) "Returning crescent" else "Crescent in flight"
        }
        null -> when {
            canRecast(casterId, StaffSpell.CHAIN) -> "Detonate Chain orb"
            casterId in galeHurricanes -> "Ураган активен"
            emberTrails[casterId]?.stage == EmberStage.RECORDING -> "Ignite Ember trail"
            casterId in gravityHolds -> "Throw suspended target"
            lunarFlights[casterId] != null -> if (lunarFlights.getValue(casterId).phase == LunarPhase.RETURN)
                "Returning crescent" else "Crescent in flight"
            else -> null
        }
        else -> null
    }

    fun cancel(casterId: UUID) {
        lunarFlights.remove(casterId)?.let(::removeLunar)
        chainOrbs.remove(casterId)?.let(::removeChain)
        galeHurricanes.remove(casterId)?.let(::removeGaleHurricane)
        icicleBursts.remove(casterId)?.let(::removeIcicleBurst)
        prismWards.remove(casterId)?.let(::removePrism)
        necromancerOrders.remove(casterId)?.let(::removeNecromancerOrder)
        gravityHolds.remove(casterId)?.let(::releaseGravity)
        lancePins.remove(casterId)?.let(::removeLancePin)
        emberTrails.remove(casterId)?.let(::removeEmber)
    }

    override fun close() {
        if (closed) return
        closed = true
        tasks.close()
        chainOrbs.values.toList().forEach(::removeChain)
        galeHurricanes.values.toList().forEach(::removeGaleHurricane)
        lunarFlights.values.toList().forEach(::removeLunar)
        icicleBursts.values.toList().forEach(::removeIcicleBurst)
        prismWards.values.toList().forEach(::removePrism)
        necromancerOrders.values.toList().forEach(::removeNecromancerOrder)
        gravityHolds.values.toList().forEach(::releaseGravity)
        lancePins.values.toList().forEach(::removeLancePin)
        emberTrails.values.toList().forEach(::removeEmber)
        lunarFlights.clear()
        chainOrbs.clear()
        galeHurricanes.clear()
        icicleBursts.clear()
        prismWards.clear()
        necromancerOrders.clear()
        gravityHolds.clear()
        gravityTargetOwners.clear()
        lancePins.clear()
        emberTrails.clear()
    }

    private fun tickAll() {
        tick++
        tickChainOrbs()
        tickGaleHurricanes()
        tickIcicleBursts()
        tickPrismWards()
        tickNecromancerOrders()
        tickGravityHolds()
        tickLancePins()
        tickEmberTrails()
        tickLunar()
    }

    private fun tickLunar() {
        lunarFlights.values.toList().forEach { flight ->
            if (lunarFlights[flight.casterId] !== flight) return@forEach
            val player = Bukkit.getPlayer(flight.casterId)
            if (player == null || !ready(player, flight.cast) || player.world.uid != flight.worldId ||
                flight.position.world?.uid != flight.worldId) {
                removeLunar(flight)
                return@forEach
            }
            flight.ageTicks++
            when (flight.phase) {
                LunarPhase.OUTBOUND -> stepLunarOutbound(flight, player)
                LunarPhase.TURN -> stepLunarTurn(flight)
                LunarPhase.RETURN -> stepLunarReturn(flight, player)
            }
            if (lunarFlights[flight.casterId] !== flight) return@forEach
            updateLunarScene(flight)
            if (flight.ageTicks % 2 == 0) visuals.withOwner(flight.casterId) {
                visuals.particles(flight.position, lunarTrailSamples(flight.phase), clearance = 0.0)
            }
            if (flight.ageTicks >= STAFF_LUNAR_TOTAL_TICKS) {
                visuals.withOwner(flight.casterId) {
                    visuals.cue(flight.position, Sound.BLOCK_AMETHYST_CLUSTER_HIT, .28f, .72f)
                }
                removeLunar(flight)
            }
        }
    }

    private fun stepLunarOutbound(flight: LunarFlight, player: Player) {
        val progress = (flight.ageTicks.toDouble() / STAFF_LUNAR_OUTBOUND_TICKS).coerceIn(0.0, 1.0)
        val side = Vector(flight.forward.z, 0.0, -flight.forward.x)
        val desired = flight.origin.clone()
            .add(flight.forward.clone().multiply(STAFF_LUNAR_OUTBOUND_BLOCKS * progress))
            .add(side.multiply(0.42 * sin(Math.PI * progress)))
        val reached = moveLunar(flight, player, desired)
        if (!reached || flight.ageTicks >= STAFF_LUNAR_OUTBOUND_TICKS) {
            flight.phase = LunarPhase.TURN
            flight.turnTicks = 0
            visuals.withOwner(flight.casterId) {
                visuals.cue(flight.position, Sound.BLOCK_AMETHYST_BLOCK_CHIME, 0.62f, 0.78f)
                lunarParticles(flight.position, apex = true)
            }
        }
    }

    private fun stepLunarTurn(flight: LunarFlight) {
        flight.turnTicks++
        val p = (flight.turnTicks.toFloat() / STAFF_LUNAR_TURN_TICKS).coerceIn(0f, 1f)
        val oppositeYaw = atan2(flight.forward.x, -flight.forward.z).toFloat()
        flight.yaw = flight.yaw + wrapAngle(oppositeYaw - flight.yaw) * smoothTechnique(p)
        if (flight.turnTicks >= STAFF_LUNAR_TURN_TICKS) flight.phase = LunarPhase.RETURN
    }

    private fun stepLunarReturn(flight: LunarFlight, player: Player) {
        val facing = player.eyeLocation.direction.clone().apply { y = 0.0 }
        if (facing.lengthSquared() < MIN_DIRECTION_SQUARED) {
            facing.x = -flight.forward.x
            facing.y = -flight.forward.y
            facing.z = -flight.forward.z
        }
        else facing.normalize()
        val destination = player.location.clone().add(facing.multiply(LUNAR_RETURN_OFFSET))
            .add(0.0, LUNAR_RETURN_HEIGHT, 0.0)
        val distance = flight.position.distance(destination)
        val remainingTicks = (STAFF_LUNAR_TOTAL_TICKS - flight.ageTicks).coerceAtLeast(1)
        val budget = (STAFF_LUNAR_MAX_TRAVEL_BLOCKS - flight.travelled).coerceAtLeast(0.0)
        if (!distance.isFinite() || distance > MAX_RETURN_SEPARATION || budget < 0.05) {
            removeLunar(flight)
            return
        }
        if (distance <= RETURN_ARRIVAL_DISTANCE) {
            visuals.withOwner(flight.casterId) {
                visuals.cue(flight.position, Sound.BLOCK_AMETHYST_BLOCK_CHIME, 0.42f, 1.4f)
            }
            removeLunar(flight)
            return
        }
        val step = min(distance / remainingTicks, min(LUNAR_RETURN_MAX_STEP, budget))
        val next = flight.position.clone().add(destination.toVector().subtract(flight.position.toVector())
            .normalize().multiply(step))
        moveLunar(flight, player, next)
        val direction = destination.toVector().subtract(flight.position.toVector())
        if (direction.lengthSquared() > MIN_DIRECTION_SQUARED) {
            direction.normalize()
            flight.yaw = atan2(-direction.x, direction.z).toFloat()
        }
        if (flight.position.distanceSquared(destination) <= RETURN_ARRIVAL_DISTANCE * RETURN_ARRIVAL_DISTANCE) {
            visuals.withOwner(flight.casterId) {
                visuals.cue(flight.position, Sound.BLOCK_AMETHYST_BLOCK_CHIME, 0.42f, 1.4f)
            }
            removeLunar(flight)
        }
    }

    /** Moves and sweeps one segment; the block ray stops the crescent before a wall. */
    private fun moveLunar(flight: LunarFlight, player: Player, desired: Location): Boolean {
        if (desired.world != flight.position.world) return false
        val delta = desired.toVector().subtract(flight.position.toVector())
        val distance = delta.length()
        if (!distance.isFinite() || distance < MIN_MOVE_DISTANCE) return true
        val clipped = targeting.rayEnd(flight.position, delta.multiply(1.0 / distance), distance)
        val moved = flight.position.distance(clipped)
        if (!moved.isFinite() || moved < MIN_MOVE_DISTANCE) return false
        sweepLunar(flight, player, flight.position, clipped)
        if (lunarFlights[flight.casterId] !== flight) return false
        flight.travelled += moved
        flight.position = clipped
        return moved + BLOCK_CLIP_EPSILON >= distance
    }

    private fun sweepLunar(flight: LunarFlight, player: Player, from: Location, to: Location) {
        for (target in targeting.lineTargets(player, from, to, LUNAR_SWEEP_WIDTH, flight.cast)) {
            if (lunarFlights[flight.casterId] !== flight || !damage.isCurrent(player, flight.cast)) return
            if (!flight.attemptedTargets.add(target.uniqueId)) continue
            if (!target.isValid || target.isDead || target.isInvulnerable || target.world.uid != flight.worldId ||
                !visible(player.eyeLocation, target) || !visible(from, target) ||
                !damage.eligible(player, target, flight.cast)) continue
            if (damage.hit(player, target, flight.cast, flight.tuning.power, flight.tuning.vanillaDamage)) {
                visuals.withOwner(flight.casterId) {
                    visuals.cue(center(target), Sound.ENTITY_PLAYER_ATTACK_KNOCKBACK, 0.48f, 1.38f)
                    visuals.particles(center(target), lunarHitSamples(), clearance = 0.0)
                }
            }
        }
    }

    private fun updateLunarScene(flight: LunarFlight) {
        val parts = staffLunarBoomerangParts(flight.yaw, flight.ageTicks)
        effects.updateCustom(flight.sceneId, flight.position, parts)
    }

    private fun removeLunar(flight: LunarFlight) {
        lunarFlights.remove(flight.casterId, flight)
        effects.remove(flight.sceneId)
    }

    private fun ready(player: Player, cast: StaffSpellCast): Boolean =
        player.isOnline && player.isValid && !player.isDead && player.gameMode != org.bukkit.GameMode.SPECTATOR &&
            damage.isCurrent(player, cast)

    private fun activeSessionCount(): Int = lunarFlights.size + chainOrbs.size + icicleBursts.size +
        prismWards.size + gravityHolds.size + lancePins.size + emberTrails.size + galeHurricanes.size + necromancerOrders.size

    private fun activeDisplaySceneCount(): Int = lunarFlights.size + chainOrbs.size + icicleBursts.size + prismWards.size +
        gravityHolds.size + lancePins.size + emberTrails.size + galeHurricanes.size + necromancerOrders.size

    private fun admitNewScenes(requested: Int): Boolean = requested > 0 &&
        activeSessionCount() < MAX_TECHNIQUE_SESSIONS && activeDisplaySceneCount() + requested <= MAX_TECHNIQUE_SCENES

    private fun isLoaded(at: Location): Boolean {
        val world = at.world ?: return false
        if (!at.x.isFinite() || !at.y.isFinite() || !at.z.isFinite() ||
            kotlin.math.abs(at.x) > MAX_WORLD_COORDINATE || kotlin.math.abs(at.z) > MAX_WORLD_COORDINATE) return false
        return world.isChunkLoaded(at.blockX shr 4, at.blockZ shr 4)
    }

    private fun emptyAimPoint(player: Player, range: Double = 6.0): Location? {
        val eye = player.eyeLocation
        if (!safeDirection(eye.direction)) return null
        val at = targeting.rayEnd(eye, eye.direction, min(settings.range, range))
        if (at.world != player.world || eye.distanceSquared(at) < 1.2 * 1.2) return null
        at.subtract(eye.direction.clone().multiply(.08))
        return at.takeIf { isLoaded(it) && visible(eye, it) }
    }

    private fun safeDirection(direction: Vector): Boolean = direction.x.isFinite() && direction.y.isFinite() &&
        direction.z.isFinite() && direction.lengthSquared().isFinite() && direction.lengthSquared() >= MIN_DIRECTION_SQUARED

    private fun rotateToward(from: Vector, toward: Vector, maxRadians: Double): Vector {
        if (!safeDirection(from) || !safeDirection(toward)) return from.clone()
        val current = from.clone().normalize()
        val desired = toward.clone().normalize()
        val dot = current.dot(desired).coerceIn(-1.0, 1.0)
        val angle = kotlin.math.acos(dot)
        if (!angle.isFinite() || angle <= maxRadians) return desired
        var axis = current.clone().crossProduct(desired)
        if (axis.lengthSquared() < MIN_DIRECTION_SQUARED) axis = current.clone().crossProduct(Vector(0.0, 1.0, 0.0))
        if (axis.lengthSquared() < MIN_DIRECTION_SQUARED) axis = current.clone().crossProduct(Vector(1.0, 0.0, 0.0))
        if (axis.lengthSquared() < MIN_DIRECTION_SQUARED) return current
        return current.rotateAroundAxis(axis.normalize(), maxRadians.coerceIn(0.0, Math.PI)).normalize()
    }

    private fun validTechniqueTarget(player: Player, target: LivingEntity, cast: StaffSpellCast, worldId: UUID): Boolean =
        ready(player, cast) && player.world.uid == worldId && target.isValid && !target.isDead &&
            !target.isInvulnerable && target.world.uid == worldId && isLoaded(target.location) &&
            damage.eligible(player, target, cast) && visible(player.eyeLocation, target)

    private fun locationVector(location: Location) = Vector3f(location.x.toFloat(), location.y.toFloat(), location.z.toFloat())

    private fun lunarParticles(at: Location, apex: Boolean) {
        visuals.particles(at, if (apex) lunarHitSamples() else lunarTrailSamples(LunarPhase.OUTBOUND), clearance = 0.0)
    }

    private fun lunarTrailSamples(phase: LunarPhase) = listOf(
        StaffParticleSample(Particle.END_ROD, Vector(0.0, 0.0, 0.0), count = 3,
            spread = Vector(0.18, 0.18, 0.18), speed = 0.015),
        StaffParticleSample(Particle.DUST_COLOR_TRANSITION, Vector(0.0, 0.06, 0.0), count = 5,
            spread = Vector(0.28, 0.24, 0.28), speed = 0.004,
            data = staffFade(0xD5B7FF, if (phase == LunarPhase.RETURN) 0x75E9DF else 0x9C63E8, 1.25f)),
    )

    private fun lunarHitSamples() = listOf(
        StaffParticleSample(Particle.END_ROD, Vector(), count = 5, spread = Vector(.20, .20, .20), speed = .025),
        StaffParticleSample(Particle.DUST_COLOR_TRANSITION, Vector(), count = 8, spread = Vector(.28, .24, .28),
            speed = .012, data = staffFade(0xEAD9FF, 0x71EEE3, 1.2f)),
    )

    private fun wrapAngle(value: Float): Float {
        var angle = value
        val pi = Math.PI.toFloat()
        while (angle > pi) angle -= (Math.PI * 2.0).toFloat()
        while (angle < -pi) angle += (Math.PI * 2.0).toFloat()
        return angle
    }

    private fun smoothTechnique(value: Float) = value * value * (3f - 2f * value)

    private companion object {
        const val MAX_TECHNIQUE_SESSIONS = 24
        const val MAX_TECHNIQUE_SCENES = 24
        const val MIN_DIRECTION_SQUARED = 1.0e-8
        const val MIN_MOVE_DISTANCE = 0.03
        const val BLOCK_CLIP_EPSILON = 0.025
        const val MAX_WORLD_COORDINATE = 29_999_984.0

        const val CHAIN_LAUNCH_OFFSET = .75
        const val CHAIN_MAX_TICKS = 30
        const val CHAIN_MAX_RANGE = 28.0
        const val CHAIN_SPEED = 1.05
        const val CHAIN_TURN_RADIANS = .16
        const val CHAIN_PROXIMITY_RADIUS = 2.25
        const val MAX_CHAIN_PROXIMITY_CANDIDATES = 32
        const val MAX_CHAIN_TARGETS = 8

        const val GALE_HURRICANE_TARGET_LIMIT = 8
        const val GALE_HURRICANE_CANDIDATE_LIMIT = 32
        const val GALE_HURRICANE_QUERY_RADIUS = 10.5

        const val ICICLE_BURST_ROOT_TICKS = 24

        const val PRISM_WARD_RANGE = 14.0
        const val PRISM_GROUND_RAY_HEIGHT = 2.5
        const val PRISM_GROUND_RAY_RANGE = 5.5
        const val PRISM_WARD_TICKS = 60
        const val PRISM_WARD_SLOW_TICKS = 40
        const val PRISM_DETECTION_RADIUS = 3.5
        const val PRISM_VERTICAL_TOLERANCE = 1.4

        const val NECROMANCER_COMMAND_RANGE = 32.0
        const val NECROMANCER_RALLY_GROUND_RANGE = 6.0

        const val GRAVITY_Q_RANGE = 20.0
        const val GRAVITY_HOLD_TICKS = 28
        const val GRAVITY_THROW_RANGE = 24.0
        const val GRAVITY_THROW_SPEED = 1.35
        const val GRAVITY_THROW_LIFT = .48

        const val LANCE_Q_RANGE = 18.0
        const val LANCE_Q_WIDTH = .22
        const val LANCE_WALL_START_OFFSET = .3
        const val LANCE_WALL_REACH = 2.5
        const val LANCE_MIN_WALL_DISTANCE = .15
        const val LANCE_MAX_WALL_DISTANCE = 1.85
        const val LANCE_PIN_TICKS = 14
        const val LANCE_OPEN_TICKS = 8
        const val LANCE_OPEN_DAMAGE_SCALE = .55
        const val LANCE_WALL_IMPACT_SPEED = .28
        const val LANCE_OPEN_KNOCKBACK = .38
        const val LANCE_PIN_AMPLIFIER = 3

        const val EMBER_TRAIL_LIFETIME_TICKS = 40
        const val EMBER_AFTERGLOW_TICKS = 6
        const val EMBER_GROUND_SEARCH = 6.0
        const val EMBER_MIN_POINT_GAP = .85
        const val EMBER_TRAIL_MAX_LENGTH = 16.0
        const val EMBER_TELEPORT_CUTOFF = 7.0
        const val EMBER_IGNITE_INTERVAL_TICKS = 2
        const val EMBER_SCENE_LIFETIME_TICKS = EMBER_TRAIL_LIFETIME_TICKS +
            STAFF_EMBER_TRAIL_MAX_POINTS * EMBER_IGNITE_INTERVAL_TICKS + EMBER_AFTERGLOW_TICKS + 2
        const val EMBER_IGNITE_RADIUS = 1.65
        const val EMBER_NODE_DAMAGE_SCALE = .34

        const val LUNAR_LAUNCH_OFFSET = 1.1
        const val LUNAR_BELOW_EYE = 0.38
        const val LUNAR_RETURN_OFFSET = 1.05
        const val LUNAR_RETURN_HEIGHT = 0.95
        const val LUNAR_RETURN_MAX_STEP = 1.55
        const val RETURN_ARRIVAL_DISTANCE = 0.8
        const val MAX_RETURN_SEPARATION = 24.0
        const val LUNAR_SWEEP_WIDTH = 0.62
    }
}
