package ru.arc.staffspells

import org.bukkit.Bukkit
import org.bukkit.GameMode
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.Sound
import org.bukkit.entity.Player
import org.bukkit.event.player.PlayerTeleportEvent
import org.bukkit.util.Vector
import ru.arc.core.LifecycleTaskScope
import java.util.UUID
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

internal enum class StaffMobilityMotion { BLINK, SLIDE, SURF, RUSH, VAULT, RECOIL, BALLISTIC, GLIDE, TETHER, BURST, HOP }

/** Bounded, damage-free release profile. The caller owns the shared cross-staff F cooldown. */
internal data class StaffMobilityProfile(
    val motion: StaffMobilityMotion,
    val durationTicks: Int,
    val range: Double,
    val forwardImpulse: Double,
    val verticalImpulse: Double,
    val steering: Double,
    val horizontalSpeedLimit: Double,
)

internal fun staffMobilityProfile(spell: StaffSpell): StaffMobilityProfile? = when (spell) {
    StaffSpell.MARK -> StaffMobilityProfile(StaffMobilityMotion.BLINK, 14, 12.0, 0.0, 0.0, 0.0, 0.0)
    StaffSpell.CHAIN -> StaffMobilityProfile(StaffMobilityMotion.BLINK, 12, 10.0, 0.0, 0.0, 0.0, 0.0)
    StaffSpell.REQUIEM -> StaffMobilityProfile(StaffMobilityMotion.BLINK, 14, 9.0, 0.0, 0.0, 0.0, 0.0)
    StaffSpell.NECROMANCER -> StaffMobilityProfile(StaffMobilityMotion.BLINK, 16, 11.0, 0.0, 0.0, 0.0, 0.0)
    StaffSpell.LUNAR -> StaffMobilityProfile(StaffMobilityMotion.BLINK, 10, 8.0, 0.0, 0.0, 0.0, 0.0)
    StaffSpell.FROST -> StaffMobilityProfile(StaffMobilityMotion.SLIDE, 20, 12.0, 1.35, 0.0, 0.32, 1.5)
    StaffSpell.TIDE -> StaffMobilityProfile(StaffMobilityMotion.SURF, 24, 14.0, 1.2, 0.3, 0.24, 1.4)
    StaffSpell.FAULT -> StaffMobilityProfile(StaffMobilityMotion.RUSH, 14, 10.0, 1.6, 0.16, 0.38, 1.65)
    StaffSpell.OSSUARY -> StaffMobilityProfile(StaffMobilityMotion.RUSH, 18, 10.0, 1.35, 0.22, 0.3, 1.45)
    StaffSpell.LANCE -> StaffMobilityProfile(StaffMobilityMotion.VAULT, 22, 10.0, 1.0, 0.82, 0.0, 1.2)
    StaffSpell.EMBER -> StaffMobilityProfile(StaffMobilityMotion.RECOIL, 20, 11.0, 1.3, 0.68, 0.0, 1.45)
    StaffSpell.GRAVITY -> StaffMobilityProfile(StaffMobilityMotion.BALLISTIC, 24, 13.0, 1.12, 0.94, 0.0, 1.35)
    StaffSpell.GALE -> StaffMobilityProfile(StaffMobilityMotion.GLIDE, 26, 14.0, 0.82, 0.58, 0.1, 1.25)
    StaffSpell.NOVA -> StaffMobilityProfile(StaffMobilityMotion.TETHER, 22, 14.0, 1.1, 0.0, 0.3, 1.35)
    StaffSpell.PRISM -> StaffMobilityProfile(StaffMobilityMotion.BURST, 18, 12.0, 1.15, 0.28, 0.3, 1.4)
    StaffSpell.ICICLE -> StaffMobilityProfile(StaffMobilityMotion.HOP, 24, 12.0, 1.0, 0.86, 0.0, 1.15)
    StaffSpell.INFERNO -> null
}

/** Caps horizontal displacement on the next movement step to the profile's release-centered range. */
internal fun staffMobilityBoundVelocity(anchor: Location, current: Location, velocity: Vector,
    maximumRange: Double): Vector? {
    if (anchor.world == null || current.world != anchor.world || !maximumRange.isFinite() || maximumRange <= 0.0 ||
        !listOf(anchor.x, anchor.z, current.x, current.z, velocity.x, velocity.y, velocity.z).all(Double::isFinite)) return null
    val offset = current.toVector().subtract(anchor.toVector()).setY(0.0)
    val horizontal = velocity.clone().setY(0.0)
    val distance = offset.length()
    if (!distance.isFinite() || distance >= maximumRange - MOBILITY_RANGE_EPSILON) return null
    val next = offset.clone().add(horizontal)
    if (next.length() <= maximumRange) return velocity.clone()

    val a = horizontal.lengthSquared()
    if (!a.isFinite() || a <= MOBILITY_RANGE_EPSILON) return velocity.clone()
    val b = 2.0 * offset.dot(horizontal)
    val c = offset.lengthSquared() - maximumRange * maximumRange
    val discriminant = b * b - 4.0 * a * c
    if (!discriminant.isFinite() || discriminant < 0.0) return null
    val fraction = ((-b + kotlin.math.sqrt(discriminant)) / (2.0 * a)).coerceIn(0.0, 1.0)
    return velocity.clone().setX(horizontal.x * fraction).setZ(horizontal.z * fraction)
}

private const val MOBILITY_RANGE_EPSILON = 1.0e-6

/** One short, collision-respecting mobility release per owner; no fall-damage suppression or block mutation. */
internal class StaffSpellMobility(
    @Suppress("UNUSED_PARAMETER") settings: StaffSpellSettings,
    private val damage: StaffSpellDamage,
    private val effects: StaffSpellDisplayEffects,
    private val visuals: StaffSpellVisuals,
    private val targeting: StaffSpellTargeting,
) : AutoCloseable {
    private data class Motion(
        val spell: StaffSpell,
        val cast: StaffSpellCast,
        val profile: StaffMobilityProfile,
        val worldId: UUID,
        val anchor: Location,
        val direction: Vector,
        val carryDirection: Vector?,
        val sceneId: UUID?,
        val departureSceneId: UUID? = null,
        var ageTicks: Int = 0,
    )

    private val tasks = LifecycleTaskScope()
    private val active = linkedMapOf<UUID, Motion>()
    private var started = false
    private var closed = false

    fun start() {
        if (closed || started) return
        started = true
        tasks.runTimer(1, 1) { tick() }
    }

    @Suppress("UNUSED_PARAMETER")
    fun cast(player: Player, spell: StaffSpell, cast: StaffSpellCast, tuning: StaffSpellTuning): Boolean {
        if (!Bukkit.isPrimaryThread() || closed || !started || !ready(player) ||
            !damage.isCurrent(player, cast) || player.uniqueId in active) return false
        val profile = staffMobilityProfile(spell) ?: return false
        if (!inputAllowed(player, cast)) return false

        val origin = player.location.clone()
        val eye = player.eyeLocation.clone()
        val aim = targeting.assistedDirection(player, spell, cast, eye.direction, eye, profile.range)
        val forward = horizontal(aim, origin.yaw) ?: return false
        val direction = when (spell) {
            StaffSpell.NOVA -> aimedTether(player, eye, aim, profile.range) ?: return false
            else -> forward
        }

        val anchor: Location
        var carryDirection: Vector? = null
        if (profile.motion == StaffMobilityMotion.BLINK) {
            val destination = safeBlinkDestination(player, direction, profile.range) ?: return false
            if (!mobilityAllowed(player, cast, destination)) return false
            val authorization = cast.duelAuthorization
            val teleported = if (authorization != null) {
                cast.duelAccess?.teleportMobility(player, authorization, destination) == true
            } else {
                // Destination and its full player-sized clearance were checked in loaded chunks above.
                player.teleport(destination, PlayerTeleportEvent.TeleportCause.PLUGIN)
            }
            if (!teleported) return false
            anchor = player.location.clone()
            releaseCue(player.uniqueId, spell, origin)
            releaseCue(player.uniqueId, spell, anchor, arrival = true)
        } else {
            val impulse = initialVelocity(spell, profile, forward, direction, player) ?: return false
            val before = player.velocity.clone()
            val merged = boundedVelocity(before, impulse, profile.horizontalSpeedLimit) ?: return false
            val predicted = merged.clone()
            if (!clearBodyPath(player, predicted)) return false
            val destination = player.location.clone().add(predicted)
            if (!mobilityAllowed(player, cast, destination)) return false
            player.velocity = merged
            val addedCarry = merged.clone().subtract(before).setY(0.0)
            if (addedCarry.lengthSquared().isFinite() && addedCarry.lengthSquared() > MOBILITY_CARRY_EPSILON) {
                carryDirection = addedCarry.normalize()
            }
            anchor = player.location.clone()
            releaseCue(player.uniqueId, spell, origin)
        }

        val departure = effects.custom(player.uniqueId, spell, origin, STAFF_MOBILITY_AFTERIMAGE_TICKS) { age ->
            staffMobilityAfterimageParts(spell, age, direction)
        }
        val motion = Motion(spell, cast, profile, player.world.uid, anchor, direction.clone(), carryDirection, null,
            departureSceneId = departure)
        val scene = effects.custom(player.uniqueId, spell, anchor, profile.durationTicks,
            ownerAttachedMotion = profile.motion != StaffMobilityMotion.BLINK) { age ->
            staffMobilityDisplayParts(spell, age, profile.durationTicks, direction)
        }
        active[player.uniqueId] = motion.copy(sceneId = scene)
        return true
    }

    fun cancel(playerId: UUID) {
        active.remove(playerId)?.let {
            effects.remove(it.sceneId)
            effects.remove(it.departureSceneId)
        }
    }

    override fun close() {
        if (closed) return
        closed = true
        active.keys.toList().forEach(::cancel)
        tasks.close()
    }

    private fun tick() {
        if (closed || active.isEmpty()) return
        active.toMap().forEach { (playerId, motion) ->
            if (active[playerId] !== motion) return@forEach
            val player = Bukkit.getPlayer(playerId)
            if (player == null) {
                cancel(playerId)
                return@forEach
            }
            if (!current(player, motion)) {
                if (motion.profile.motion != StaffMobilityMotion.BLINK &&
                    player.isOnline && player.isValid && !player.isDead) stopHorizontalMotion(player)
                cancel(playerId)
                return@forEach
            }
            if (motion.profile.motion != StaffMobilityMotion.BLINK) {
                val bounded = staffMobilityBoundVelocity(motion.anchor, player.location, player.velocity,
                    motion.profile.range)
                if (bounded == null) {
                    stopHorizontalMotion(player)
                    finish(player, motion)
                    return@forEach
                }
                player.velocity = bounded
            }
            val currentStep = player.location.clone().add(player.velocity)
            if (!mobilityAllowed(player, motion.cast, currentStep)) {
                if (motion.profile.motion != StaffMobilityMotion.BLINK && motion.cast.duelAuthorization != null) {
                    stopHorizontalMotion(player)
                }
                cancel(playerId)
                return@forEach
            }

            if (motion.profile.motion != StaffMobilityMotion.BLINK) {
                val steered = steeringVelocity(player, motion)
                if (steered != null) {
                    val bounded = staffMobilityBoundVelocity(motion.anchor, player.location, steered,
                        motion.profile.range)
                    if (bounded == null) {
                        stopHorizontalMotion(player)
                        finish(player, motion)
                        return@forEach
                    }
                    val predicted = bounded.clone()
                    if (!clearBodyPath(player, predicted)) {
                        stopHorizontalMotion(player)
                        finish(player, motion)
                        return@forEach
                    }
                    val destination = player.location.clone().add(predicted)
                    if (!mobilityAllowed(player, motion.cast, destination)) {
                        if (motion.cast.duelAuthorization != null) stopHorizontalMotion(player)
                        cancel(playerId)
                        return@forEach
                    }
                    player.velocity = bounded
                }
            }

            motion.ageTicks++
            val anchor = if (motion.profile.motion == StaffMobilityMotion.BLINK) motion.anchor else player.location
            if (motion.ageTicks % 2 == 0) effects.updateCustom(motion.sceneId, anchor,
                staffMobilityDisplayParts(motion.spell, motion.ageTicks, motion.profile.durationTicks, motion.direction))
            if (motion.ageTicks % 4 == 0) releaseParticles(motion.spell, anchor)
            if (motion.ageTicks == motion.profile.durationTicks / 2) {
                val travelSound = when (motion.spell) {
                    StaffSpell.GALE -> Sound.ENTITY_BREEZE_SLIDE
                    StaffSpell.TIDE -> Sound.ENTITY_PLAYER_SWIM
                    StaffSpell.NOVA -> Sound.BLOCK_AZALEA_LEAVES_BREAK
                    StaffSpell.OSSUARY -> Sound.BLOCK_BONE_BLOCK_HIT
                    else -> null
                }
                if (travelSound != null) visuals.withOwner(playerId) {
                    visuals.cue(anchor, travelSound, 0.34f, 1.15f)
                }
            }
            if (motion.ageTicks >= motion.profile.durationTicks) finish(player, motion)
        }
    }

    private fun finish(player: Player, motion: Motion) {
        if (!active.remove(player.uniqueId, motion)) return
        if (motion.profile.motion != StaffMobilityMotion.BLINK && player.isOnline && player.isValid && !player.isDead &&
            player.world.uid == motion.worldId) {
            stopOwnedHorizontalCarry(player, motion)
        }
        effects.remove(motion.sceneId)
        effects.remove(motion.departureSceneId)
        val at = player.location.takeIf { player.world.uid == motion.worldId } ?: motion.anchor
        visuals.withOwner(player.uniqueId) {
            releaseParticles(motion.spell, at, impulse = true)
            if (motion.profile.motion != StaffMobilityMotion.BLINK)
                visuals.cue(at, endSound(motion.spell), 0.58f, endPitch(motion.spell))
        }
        effects.custom(player.uniqueId, motion.spell, at, STAFF_MOBILITY_AFTERIMAGE_TICKS) { age ->
            staffMobilityAfterimageParts(motion.spell, age, motion.direction)
        }
    }

    private fun current(player: Player, motion: Motion): Boolean = ready(player) &&
        player.world.uid == motion.worldId && inputAllowed(player, motion.cast) &&
        damage.isCurrent(player, motion.cast)

    private fun stopHorizontalMotion(player: Player) {
        val velocity = player.velocity.clone()
        velocity.x = 0.0
        velocity.z = 0.0
        player.velocity = velocity
    }

    /** Release only the component this impulse added; preserve fall and unrelated sideways motion. */
    private fun stopOwnedHorizontalCarry(player: Player, motion: Motion) {
        val carryDirection = motion.carryDirection ?: return
        val velocity = player.velocity.clone()
        val horizontal = velocity.clone().setY(0.0)
        val speed = horizontal.length()
        if (!speed.isFinite() || speed > motion.profile.horizontalSpeedLimit * MAX_FINISH_CARRY_SPEED_FACTOR) return
        val carrySpeed = horizontal.dot(carryDirection)
        if (!carrySpeed.isFinite() || carrySpeed <= MIN_FINISH_CARRY_SPEED) return
        val remaining = horizontal.subtract(carryDirection.clone().multiply(carrySpeed))
        velocity.x = remaining.x
        velocity.z = remaining.z
        player.velocity = velocity
    }

    private fun inputAllowed(player: Player, cast: StaffSpellCast): Boolean {
        val authorization = cast.duelAuthorization ?: return true
        val access = cast.duelAccess ?: return false
        return access.inputAllowed(player)
    }

    private fun mobilityAllowed(player: Player, cast: StaffSpellCast, destination: Location): Boolean {
        val authorization = cast.duelAuthorization ?: return true
        val access = cast.duelAccess ?: return false
        return access.mobilityAllowed(player, authorization, destination)
    }

    private fun safeBlinkDestination(player: Player, direction: Vector, range: Double): Location? {
        val origin = player.location
        val world = player.world
        var distance = min(range, MAX_BLINK_RANGE)
        while (distance >= MIN_BLINK_DISTANCE) {
            val candidate = origin.clone().add(direction.clone().multiply(distance))
            val probe = candidate.clone().add(0.0, 2.1, 0.0)
            val floor = targeting.groundBelow(probe, 6.5)
            if (floor != null && floor.world == world && floor.x.isFinite() && floor.y.isFinite() && floor.z.isFinite() &&
                kotlin.math.abs(floor.y - origin.y) <= MAX_BLINK_VERTICAL_CHANGE) {
                floor.yaw = origin.yaw
                floor.pitch = origin.pitch
                if (safeStanding(player, floor)) return floor
            }
            distance -= BLINK_CANDIDATE_STEP
        }
        return null
    }

    private fun safeStanding(player: Player, feet: Location): Boolean {
        val world = feet.world ?: return false
        if (feet.y < world.minHeight || feet.y + PLAYER_HEIGHT >= world.maxHeight ||
            !world.worldBorder.isInside(feet)) return false
        val minX = floor(feet.x - PLAYER_HALF_WIDTH).toInt()
        val maxX = floor(feet.x + PLAYER_HALF_WIDTH).toInt()
        val minZ = floor(feet.z - PLAYER_HALF_WIDTH).toInt()
        val maxZ = floor(feet.z + PLAYER_HALF_WIDTH).toInt()
        val minY = floor(feet.y).toInt()
        val maxY = floor(feet.y + PLAYER_HEIGHT - 0.04).toInt()
        for (x in minX..maxX) for (z in minZ..maxZ) {
            if (!world.isChunkLoaded(x shr 4, z shr 4)) return false
            for (y in minY..maxY) {
                val block = world.getBlockAt(x, y, z)
                if (!block.isPassable || block.isLiquid || block.type in UNSAFE_LANDING_BLOCKS) return false
            }
        }
        val support = world.getBlockAt(floor(feet.x).toInt(), floor(feet.y - 0.08).toInt(), floor(feet.z).toInt())
        return support.type.isSolid && !support.isPassable && !support.isLiquid && support.type !in UNSAFE_LANDING_BLOCKS
    }

    /** Swept, multi-height body rays reject walls and unloaded chunks before an impulse is applied. */
    private fun clearBodyPath(player: Player, displacement: Vector): Boolean {
        val distance = displacement.length()
        if (!distance.isFinite() || distance > MAX_BODY_SWEEP ||
            !listOf(displacement.x, displacement.y, displacement.z).all(Double::isFinite)) return false
        if (distance < MIN_BODY_SWEEP) return true
        val location = player.location
        for (height in BODY_RAY_HEIGHTS) for (x in BODY_RAY_OFFSETS) for (z in BODY_RAY_OFFSETS) {
            val from = location.clone().add(x, height, z)
            val hit = staffBlockHit(from, displacement, distance, includeMagicBarriers = false)
            if (!hit.pathLoaded || hit.hit != null) return false
        }
        return true
    }

    private fun aimedTether(player: Player, eye: Location, aim: Vector, range: Double): Vector? {
        val ray = staffBlockHit(eye, aim, range)
        val hit = ray.hit ?: return null
        if (!ray.pathLoaded || hit.hitPosition == null) return null
        val toSurface = hit.hitPosition.toLocation(player.world).toVector().subtract(player.location.toVector())
        if (!toSurface.lengthSquared().isFinite() || toSurface.lengthSquared() < 1.0) return null
        // The checked ray itself establishes a clear line to its first solid surface.
        return toSurface.normalize()
    }

    private fun initialVelocity(
        spell: StaffSpell,
        profile: StaffMobilityProfile,
        forward: Vector,
        aimedDirection: Vector,
        player: Player,
    ): Vector? {
        val horizontalImpulse = when (spell) {
            StaffSpell.LANCE, StaffSpell.EMBER -> forward.clone().multiply(-profile.forwardImpulse)
            else -> forward.clone().multiply(profile.forwardImpulse)
        }
        val vertical = when (spell) {
            StaffSpell.TIDE -> if (player.isInWater) 0.12 else profile.verticalImpulse
            StaffSpell.NOVA -> (aimedDirection.y * 0.42).coerceIn(-0.18, 0.3)
            else -> profile.verticalImpulse
        }
        if (spell == StaffSpell.NOVA) {
            val horizontal = aimedDirection.clone().setY(0.0)
            if (horizontal.lengthSquared() < 1.0e-9) return null
            return horizontal.normalize().multiply(profile.forwardImpulse).setY(vertical)
        }
        return horizontalImpulse.setY(vertical)
    }

    /** Capped acceleration sustains ground travel against friction without replacing foreign velocity. */
    private fun steeringVelocity(player: Player, motion: Motion): Vector? {
        val acceleration = motion.profile.steering
        if (acceleration <= 0.0) return null
        val existing = player.velocity
        val horizontal = existing.clone().setY(0.0)
        val speed = horizontal.length()
        if (!speed.isFinite() || speed >= motion.profile.horizontalSpeedLimit) return null
        val heading = if (motion.spell == StaffSpell.GALE) {
            horizontal(player.eyeLocation.direction, player.location.yaw) ?: return null
        } else horizontal(motion.direction, player.location.yaw) ?: return null
        val adjusted = existing.clone().add(heading.multiply(min(acceleration,
            motion.profile.horizontalSpeedLimit - speed)))
        val adjustedHorizontal = adjusted.clone().setY(0.0)
        if (adjustedHorizontal.length() > motion.profile.horizontalSpeedLimit) {
            adjustedHorizontal.normalize().multiply(motion.profile.horizontalSpeedLimit)
            adjusted.x = adjustedHorizontal.x
            adjusted.z = adjustedHorizontal.z
        }
        return adjusted
    }

    private fun boundedVelocity(before: Vector, impulse: Vector, horizontalLimit: Double): Vector? {
        if (!listOf(before.x, before.y, before.z, impulse.x, impulse.y, impulse.z).all(Double::isFinite)) return null
        val currentHorizontal = before.clone().setY(0.0).length()
        if (currentHorizontal > horizontalLimit * 1.5) return null
        val combined = before.clone().add(impulse)
        val horizontal = combined.clone().setY(0.0)
        if (horizontal.length() > horizontalLimit) {
            horizontal.normalize().multiply(horizontalLimit)
            combined.x = horizontal.x
            combined.z = horizontal.z
        }
        return combined
    }

    private fun horizontal(direction: Vector, yaw: Float): Vector? {
        val flat = direction.clone().setY(0.0)
        if (!flat.lengthSquared().isFinite()) return null
        if (flat.lengthSquared() < 1.0e-9) {
            val radians = Math.toRadians(yaw.toDouble())
            return Vector(-sin(radians), 0.0, kotlin.math.cos(radians))
        }
        return flat.normalize()
    }

    private fun releaseCue(owner: UUID, spell: StaffSpell, at: Location, arrival: Boolean = false) {
        visuals.withOwner(owner) {
            if (arrival) visuals.cue(at, endSound(spell), 0.62f, endPitch(spell))
            else {
                visuals.cue(at, releaseSound(spell), 0.78f, releasePitch(spell))
                when (spell) {
                    StaffSpell.FAULT -> visuals.cue(at, Sound.ENTITY_IRON_GOLEM_ATTACK, 0.24f, 0.7f)
                    StaffSpell.OSSUARY -> visuals.cue(at, Sound.BLOCK_BONE_BLOCK_BREAK, 0.28f, 1.22f)
                    StaffSpell.TIDE -> visuals.cue(at, Sound.BLOCK_BUBBLE_COLUMN_UPWARDS_AMBIENT, 0.28f, 0.88f)
                    StaffSpell.NOVA -> visuals.cue(at, Sound.BLOCK_AZALEA_LEAVES_BREAK, 0.28f, 1.32f)
                    StaffSpell.GRAVITY -> visuals.cue(at, Sound.BLOCK_AMETHYST_BLOCK_RESONATE, 0.22f, 0.58f)
                    else -> Unit
                }
            }
            releaseParticles(spell, at, impulse = true)
        }
    }

    private fun releaseParticles(spell: StaffSpell, at: Location, impulse: Boolean = false) {
        visuals.particles(at, staffMobilityParticleBurst(spell, impulse), clearance = 1.1)
    }

    private fun ready(player: Player): Boolean = player.isOnline && player.isValid && !player.isDead &&
        player.gameMode != GameMode.SPECTATOR

    private fun releaseSound(spell: StaffSpell) = when (spell) {
        StaffSpell.MARK -> Sound.ENTITY_ENDERMAN_TELEPORT
        StaffSpell.LUNAR -> Sound.ENTITY_PHANTOM_FLAP
        StaffSpell.CHAIN -> Sound.BLOCK_RESPAWN_ANCHOR_CHARGE
        StaffSpell.NOVA -> Sound.BLOCK_ROOTED_DIRT_BREAK
        StaffSpell.REQUIEM -> Sound.BLOCK_SOUL_SAND_BREAK
        StaffSpell.NECROMANCER -> Sound.ENTITY_WITHER_SKELETON_AMBIENT
        StaffSpell.OSSUARY -> Sound.BLOCK_BONE_BLOCK_PLACE
        StaffSpell.FROST, StaffSpell.ICICLE -> Sound.BLOCK_GLASS_BREAK
        StaffSpell.TIDE -> Sound.ENTITY_PLAYER_SPLASH
        StaffSpell.FAULT -> Sound.BLOCK_DEEPSLATE_BREAK
        StaffSpell.LANCE -> Sound.ITEM_TRIDENT_THROW
        StaffSpell.EMBER -> Sound.ENTITY_BLAZE_SHOOT
        StaffSpell.GRAVITY -> Sound.BLOCK_RESPAWN_ANCHOR_CHARGE
        StaffSpell.GALE -> Sound.ENTITY_BREEZE_WIND_BURST
        StaffSpell.PRISM -> Sound.BLOCK_AMETHYST_BLOCK_CHIME
        StaffSpell.INFERNO -> Sound.BLOCK_FIRE_AMBIENT
    }

    private fun endSound(spell: StaffSpell) = when (spell) {
        StaffSpell.MARK, StaffSpell.LUNAR -> Sound.BLOCK_AMETHYST_BLOCK_CHIME
        StaffSpell.CHAIN -> Sound.BLOCK_RESPAWN_ANCHOR_DEPLETE
        StaffSpell.NOVA -> Sound.BLOCK_AZALEA_LEAVES_BREAK
        StaffSpell.REQUIEM -> Sound.BLOCK_BELL_RESONATE
        StaffSpell.NECROMANCER -> Sound.BLOCK_SOUL_SAND_BREAK
        StaffSpell.FROST, StaffSpell.ICICLE -> Sound.BLOCK_AMETHYST_CLUSTER_HIT
        StaffSpell.TIDE -> Sound.ENTITY_PLAYER_SPLASH
        StaffSpell.FAULT -> Sound.BLOCK_DEEPSLATE_BREAK
        StaffSpell.OSSUARY -> Sound.BLOCK_BONE_BLOCK_BREAK
        StaffSpell.LANCE -> Sound.ENTITY_PLAYER_ATTACK_SWEEP
        StaffSpell.EMBER -> Sound.BLOCK_FIRE_EXTINGUISH
        StaffSpell.GRAVITY -> Sound.BLOCK_BEACON_DEACTIVATE
        StaffSpell.GALE -> Sound.ENTITY_BREEZE_SLIDE
        StaffSpell.PRISM -> Sound.BLOCK_AMETHYST_BLOCK_CHIME
        StaffSpell.INFERNO -> Sound.BLOCK_FIRE_AMBIENT
    }

    private fun releasePitch(spell: StaffSpell) = when (spell) {
        StaffSpell.MARK, StaffSpell.LUNAR -> 1.2f
        StaffSpell.CHAIN, StaffSpell.NOVA -> 1.55f
        StaffSpell.REQUIEM, StaffSpell.NECROMANCER -> 0.75f
        StaffSpell.FROST, StaffSpell.ICICLE -> 0.88f
        StaffSpell.TIDE -> 1.15f
        StaffSpell.FAULT, StaffSpell.OSSUARY -> 0.72f
        StaffSpell.LANCE -> 1.3f
        StaffSpell.EMBER -> 0.95f
        StaffSpell.GRAVITY -> 0.7f
        StaffSpell.GALE -> 1.4f
        StaffSpell.PRISM -> 1.6f
        StaffSpell.INFERNO -> 0.9f
    }

    private fun endPitch(spell: StaffSpell) = (releasePitch(spell) + 0.18f).coerceIn(0.55f, 1.9f)

    private companion object {
        const val PLAYER_HALF_WIDTH = 0.3
        const val PLAYER_HEIGHT = 1.8
        const val MIN_BLINK_DISTANCE = 2.0
        const val MAX_BLINK_RANGE = 12.0
        const val BLINK_CANDIDATE_STEP = 0.5
        const val MOBILITY_CARRY_EPSILON = 1.0e-6
        const val MAX_FINISH_CARRY_SPEED_FACTOR = 1.5
        const val MIN_FINISH_CARRY_SPEED = 0.02
        const val MAX_BLINK_VERTICAL_CHANGE = 3.0
        const val MAX_BODY_SWEEP = 2.6
        const val MIN_BODY_SWEEP = 0.025
        val BODY_RAY_OFFSETS = listOf(-0.24, 0.24)
        val BODY_RAY_HEIGHTS = listOf(0.2, 0.9, 1.58)
        val UNSAFE_LANDING_BLOCKS = setOf(Material.CACTUS, Material.CAMPFIRE, Material.SOUL_CAMPFIRE,
            Material.FIRE, Material.SOUL_FIRE, Material.MAGMA_BLOCK, Material.POWDER_SNOW,
            Material.SWEET_BERRY_BUSH, Material.WITHER_ROSE, Material.NETHER_PORTAL, Material.END_PORTAL)
    }
}
