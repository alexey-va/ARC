package ru.arc.staffspells

import org.bukkit.GameMode
import org.bukkit.Location
import org.bukkit.Particle
import org.bukkit.Sound
import org.bukkit.block.data.BlockData
import org.bukkit.entity.LivingEntity
import org.bukkit.entity.Player
import org.bukkit.event.entity.EntityDamageEvent
import org.bukkit.util.Vector
import ru.arc.core.LifecycleTaskScope
import java.util.UUID
import kotlin.math.min

/** Owns the two held staff channels and bounded, swept Gravity throws. All methods run on Paper's main thread. */
internal class StaffSpellChannels(
    private val settings: StaffSpellSettings,
    private val damage: StaffSpellDamage,
    private val effects: StaffSpellDisplayEffects,
    private val visuals: StaffSpellVisuals,
    private val targeting: StaffSpellTargeting,
) : AutoCloseable {
    private data class BlockSelection(
        val point: Location,
        val data: BlockData,
        val blockX: Int,
        val blockY: Int,
        val blockZ: Int,
    )

    private data class Channel(
        val casterId: UUID,
        val spell: StaffSpell,
        val cast: StaffSpellCast,
        val tuning: StaffSpellTuning,
        val secondary: Boolean,
        val worldId: UUID,
        var ageTicks: Int = 0,
        var lastDamageTick: Int = 0,
        var sceneId: UUID? = null,
        var position: Location? = null,
        var selectedBlockKey: Triple<Int, Int, Int>? = null,
    )

    private data class Flight(
        val casterId: UUID,
        val cast: StaffSpellCast,
        val tuning: StaffSpellTuning,
        val worldId: UUID,
        val sceneId: UUID?,
        var position: Location,
        val direction: Vector,
        var remaining: Double,
        var speed: Double,
        var sceneAgeTicks: Int,
        var elapsedTicks: Int = 0,
        var impacted: Boolean = false,
        var cancelled: Boolean = false,
    )

    private data class PulseTarget(
        val entity: LivingEntity,
        val startY: Double,
        var lifted: Boolean = false,
        var slammed: Boolean = false,
    )

    private data class PulseFallProtection(
        val entity: LivingEntity,
        val worldId: UUID,
        val expiresAtTick: Long,
    )

    private data class GravityPulse(
        val casterId: UUID,
        val cast: StaffSpellCast,
        val tuning: StaffSpellTuning,
        val worldId: UUID,
        val origin: Location,
        val sceneId: UUID,
        val targets: MutableList<PulseTarget>,
        var ageTicks: Int = 0,
    )

    private val tasks = LifecycleTaskScope()
    private val channels = linkedMapOf<UUID, Channel>()
    private val flights = mutableListOf<Flight>()
    private val gravityPulses = mutableListOf<GravityPulse>()
    private val pulseFallProtections = linkedMapOf<UUID, PulseFallProtection>()
    private val scenesByCaster = mutableMapOf<UUID, MutableSet<UUID>>()
    private var pulseProtectionTick = 0L
    private var started = false
    private var closed = false

    fun start() {
        check(!closed) { "Staff channels are closed" }
        if (started) return
        started = true
        tasks.runTimer(CHANNEL_STEP_TICKS.toLong(), CHANNEL_STEP_TICKS.toLong(), ::tickFlights)
    }

    /** Starts an Inferno stream or selects a solid block for Gravity without editing world state. */
    fun begin(player: Player, spell: StaffSpell, cast: StaffSpellCast, tuning: StaffSpellTuning,
        secondary: Boolean): Boolean {
        if (closed || !started || spell !in CHANNEL_SPELLS || !ready(player, cast) ||
            player.uniqueId in channels || channels.size >= MAX_CHANNELS) return false

        val channel = Channel(player.uniqueId, spell, cast, tuning, secondary, player.world.uid)
        if (spell == StaffSpell.GRAVITY) {
            val selection = selectBlock(player) ?: return false
            val eye = player.eyeLocation
            val direction = safeDirection(eye.direction) ?: return false
            val eyeToSelection = selection.point.distance(eye)
            val holdDistance = minOf(GRAVITY_HOLD_DISTANCE, eyeToSelection - GRAVITY_BLOCK_GAP)
            if (!holdDistance.isFinite() || holdDistance < GRAVITY_MIN_EYE_DISTANCE) return false
            val position = targeting.rayEnd(eye, direction, holdDistance)
            if (position.distance(eye) < GRAVITY_MIN_EYE_DISTANCE) return false
            val scene = effects.play(player.uniqueId, spell, position, position.clone().add(direction),
                radius = GRAVITY_RADIUS, durationTicks = GRAVITY_MAX_HOLD_TICKS + GRAVITY_FLIGHT_TICKS,
                secondary = secondary, input = StaffCastInput.of(secondary, false), controllerDriven = true)
                ?: return false
            channel.sceneId = scene
            channel.position = position
            channel.selectedBlockKey = Triple(selection.blockX, selection.blockY, selection.blockZ)
            emitSelection(channel, selection)
            visuals.withOwner(player.uniqueId) {
                visuals.cue(position, Sound.BLOCK_STONE_PLACE, volume = 0.32f, pitch = 0.72f)
                visuals.cue(position, Sound.BLOCK_AMETHYST_BLOCK_RESONATE, volume = 0.24f, pitch = 0.58f)
            }
            trackScene(player.uniqueId, scene)
        } else {
            val eye = player.eyeLocation
            val direction = safeDirection(eye.direction) ?: return false
            visuals.withOwner(player.uniqueId) {
                visuals.cue(eye, Sound.ITEM_FIRECHARGE_USE, volume = 0.3f, pitch = 1.2f)
            }
        }
        channels[player.uniqueId] = channel
        if (spell == StaffSpell.INFERNO) {
            updateInferno(player, channel, 0)
            if (player.uniqueId !in channels) return false
        }
        return true
    }

    /** Returns false at the hold limit or when the captured caster/world is no longer valid. */
    fun update(player: Player, ticksHeld: Int): Boolean {
        val channel = channels[player.uniqueId] ?: return false
        if (!current(player, channel.cast, channel.worldId) || ticksHeld < channel.ageTicks) {
            cancel(player.uniqueId)
            return false
        }
        val limit = if (channel.spell == StaffSpell.INFERNO) INFERNO_MAX_HOLD_TICKS else GRAVITY_MAX_HOLD_TICKS
        if (ticksHeld > limit) {
            cancel(player.uniqueId)
            return false
        }
        channel.ageTicks = ticksHeld
        if (channel.spell == StaffSpell.INFERNO) updateInferno(player, channel, ticksHeld)
        else updateGravity(player, channel, ticksHeld)
        if (ticksHeld >= limit) {
            cancel(player.uniqueId)
            return false
        }
        return player.uniqueId in channels
    }

    /** Releases Gravity into one accelerated throw. Inferno simply stops its stream. */
    fun release(player: Player) {
        val channel = channels.remove(player.uniqueId) ?: return
        if (!current(player, channel.cast, channel.worldId)) {
            removeScene(player.uniqueId, channel.sceneId)
            return
        }
        if (channel.spell != StaffSpell.GRAVITY) return
        if (flights.size >= MAX_FLIGHTS) {
            removeScene(player.uniqueId, channel.sceneId)
            return
        }
        val from = channel.position ?: run { removeScene(player.uniqueId, channel.sceneId); return }
        val eye = player.eyeLocation
        val aimDirection = safeDirection(eye.direction)
            ?: run { removeScene(player.uniqueId, channel.sceneId); return }
        val look = safeDirection(targeting.assistedDirection(player, StaffSpell.GRAVITY, channel.cast,
            direction = aimDirection, from = from, range = GRAVITY_TARGET_RANGE))
            ?: run { removeScene(player.uniqueId, channel.sceneId); return }
        val aim = targeting.rayEnd(eye, look, GRAVITY_TARGET_RANGE)
        val toward = aim.toVector().subtract(from.toVector())
        val aimDistance = toward.length().takeIf(Double::isFinite) ?: 0.0
        val wanted = aimDistance.coerceIn(0.5, GRAVITY_THROW_RANGE)
        val direction = if (aimDistance >= 0.5) toward.multiply(1.0 / aimDistance) else look
        val end = targeting.rayEnd(from, direction, wanted)
        val remaining = from.distance(end)
        val scene = channel.sceneId
        if (scene == null) return
        effects.moveProjectile(scene, from, direction)
        effects.synchronize(scene, channel.ageTicks)
        flights += Flight(player.uniqueId, channel.cast, channel.tuning, channel.worldId, scene,
            from.clone(), direction, remaining, INITIAL_THROW_SPEED, channel.ageTicks)
        visuals.withOwner(player.uniqueId) {
            visuals.cue(from, Sound.BLOCK_STONE_HIT, volume = 0.42f, pitch = 0.68f)
            visuals.cue(from, Sound.ENTITY_BREEZE_SHOOT, volume = 0.32f, pitch = 0.72f)
        }
    }

    /** Starts the distinct caster-centered Gravity secondary attack; the primary hold remains untouched. */
    fun gravityPulse(player: Player, cast: StaffSpellCast, tuning: StaffSpellTuning): Boolean {
        if (closed || !started || !ready(player, cast) || player.uniqueId in channels ||
            gravityPulses.any { it.casterId == player.uniqueId } || gravityPulses.size >= MAX_GRAVITY_PULSES) return false

        val origin = player.location.clone()
        val targets = targeting.nearby(player, origin, GRAVITY_PULSE_RADIUS, cast)
            .filter { center(it).distanceSquared(origin) <= GRAVITY_PULSE_RADIUS * GRAVITY_PULSE_RADIUS }
            .filter { visible(player.eyeLocation, center(it)) }
            .sortedBy { center(it).distanceSquared(origin) }
            .take(settings.maxAreaTargets.coerceIn(1, MAX_PULSE_TARGETS))
            .map { PulseTarget(it, it.location.y) }
            .toMutableList()
        tickPulseFallProtections()
        val newProtections = targets.map { it.entity.uniqueId }.distinct().count { it !in pulseFallProtections }
        if (pulseFallProtections.size + newProtections > MAX_PULSE_FALL_PROTECTIONS) return false
        val scene = effects.play(player.uniqueId, StaffSpell.GRAVITY, origin, origin,
            radius = GRAVITY_PULSE_RADIUS, durationTicks = GRAVITY_PULSE_TICKS,
            secondary = true, input = StaffCastInput.of(true, false), controllerDriven = true)
            ?: return false
        val pulse = GravityPulse(player.uniqueId, cast, tuning, player.world.uid, origin, scene, targets)
        gravityPulses += pulse
        trackScene(player.uniqueId, scene)

        for (target in targets) {
            target.entity.fallDistance = 0f
            target.entity.velocity = target.entity.velocity.clone().apply {
                y = maxOf(y, GRAVITY_PULSE_LIFT_VELOCITY)
            }
            pulseFallProtections[target.entity.uniqueId] = PulseFallProtection(
                target.entity,
                target.entity.world.uid,
                pulseProtectionTick + MAX_PULSE_FALL_PROTECTION_TICKS,
            )
        }
        visuals.withOwner(player.uniqueId) {
            visuals.particles(origin, staffGravityPulseFrame(0, GRAVITY_PULSE_RADIUS))
            visuals.cue(origin, Sound.BLOCK_STONE_PLACE, volume = 0.38f, pitch = 0.58f)
            visuals.cue(origin, Sound.BLOCK_RESPAWN_ANCHOR_CHARGE, volume = 0.36f, pitch = 0.62f)
        }
        return true
    }

    /** Cancels only the first FALL event for a pulse-launched target, including after its pulse is cancelled. */
    fun suppressPulseFallDamage(event: EntityDamageEvent): Boolean {
        if (closed || event.cause != EntityDamageEvent.DamageCause.FALL) return false
        val entity = event.entity as? LivingEntity ?: return false
        val protection = pulseFallProtections[entity.uniqueId] ?: return false
        if (protection.expiresAtTick <= pulseProtectionTick || !entity.isValid || entity.isDead ||
            entity.world.uid != protection.worldId) {
            pulseFallProtections.remove(entity.uniqueId)
            return false
        }

        event.isCancelled = true
        entity.fallDistance = 0f
        pulseFallProtections.remove(entity.uniqueId)
        return true
    }

    /** Cancellation discards active casts and in-flight throws; it never creates a release shot. */
    fun cancel(casterId: UUID) {
        channels.remove(casterId)
        flights.filter { it.casterId == casterId }.forEach { it.cancelled = true }
        flights.removeAll { it.casterId == casterId }
        gravityPulses.removeAll { it.casterId == casterId }
        scenesByCaster.remove(casterId)?.toList()?.forEach { scene ->
            effects.remove(scene)
        }
    }

    override fun close() {
        if (closed) return
        closed = true
        tasks.close()
        channels.clear()
        flights.forEach { it.cancelled = true }
        flights.clear()
        gravityPulses.clear()
        pulseFallProtections.clear()
        scenesByCaster.values.flatten().distinct().forEach(effects::remove)
        scenesByCaster.clear()
    }

    private fun updateInferno(player: Player, channel: Channel, ticksHeld: Int) {
        val eye = player.eyeLocation
        val direction = safeDirection(eye.direction) ?: run { cancel(player.uniqueId); return }
        val muzzle = infernoMuzzle(eye, direction)
        val visibleEnd = targeting.rayEnd(muzzle, direction, infernoReach())
        val visibleReach = muzzle.distance(visibleEnd)
        if (ticksHeld % CHANNEL_STEP_TICKS != 0) return

        val samples = staffInfernoFrame(ticksHeld, visibleReach).map { sample ->
            sample.copy(offset = orient(sample.offset, direction))
        }
        visuals.withOwner(player.uniqueId) { visuals.particles(muzzle, samples, clearance = PARTICLE_CLEARANCE) }
        if (ticksHeld > 0 && (ticksHeld % SOUND_INTERVAL_TICKS == 0)) {
            visuals.withOwner(player.uniqueId) {
                visuals.cue(muzzle, Sound.BLOCK_FIRE_AMBIENT, volume = 0.16f, pitch = 1.35f)
            }
        }
        if (ticksHeld - channel.lastDamageTick >= INFERNO_DAMAGE_INTERVAL || ticksHeld == INFERNO_MAX_HOLD_TICKS) {
            val elapsed = (ticksHeld - channel.lastDamageTick).coerceIn(0, INFERNO_DAMAGE_INTERVAL)
            channel.lastDamageTick = ticksHeld
            if (elapsed > 0) infernoPulse(player, channel, eye, direction, elapsed.toDouble() / INFERNO_MAX_HOLD_TICKS)
        }
    }

    private fun infernoPulse(player: Player, channel: Channel, eye: Location, direction: Vector, fraction: Double) {
        if (!current(player, channel.cast, channel.worldId)) return
        val muzzle = infernoMuzzle(eye, direction)
        val end = targeting.rayEnd(muzzle, direction, infernoReach())
        if (muzzle.distanceSquared(end) < 0.01) return
        val victim = targeting.lineTargets(player, muzzle, end, INFERNO_SWEEP_WIDTH, channel.cast).firstOrNull()
            ?: return
        if (!current(player, channel.cast, channel.worldId) || !visible(eye, center(victim))) return
        damage.hit(player, victim, channel.cast,
            channel.tuning.power * fraction, channel.tuning.vanillaDamage * fraction)
    }

    private fun updateGravity(player: Player, channel: Channel, ticksHeld: Int) {
        val eye = player.eyeLocation
        val direction = safeDirection(eye.direction) ?: run { cancel(player.uniqueId); return }
        val selection = selectBlock(player)
        val aim = selection?.point?.clone() ?: targeting.rayEnd(eye, direction, GRAVITY_TARGET_RANGE)
        val aimDistance = eye.distance(aim)
        val holdDistance = minOf(GRAVITY_HOLD_DISTANCE,
            (aimDistance - GRAVITY_BLOCK_GAP).coerceAtLeast(GRAVITY_MIN_EYE_DISTANCE))
        val desired = targeting.rayEnd(eye, direction, holdDistance)
        if (!desired.distance(eye).isFinite() || desired.distance(eye) < GRAVITY_MIN_EYE_DISTANCE) {
            cancel(player.uniqueId)
            return
        }
        val previous = channel.position ?: desired
        val delta = desired.toVector().subtract(previous.toVector())
        val moved = if (delta.lengthSquared() < 0.0001) previous.clone() else {
            val distance = delta.length()
            targeting.rayEnd(previous, delta.multiply(1.0 / distance), distance)
        }
        if (moved.distance(eye) < GRAVITY_MIN_EYE_DISTANCE) {
            cancel(player.uniqueId)
            return
        }
        channel.position = moved
        selection?.let { selected ->
            val key = Triple(selected.blockX, selected.blockY, selected.blockZ)
            if (key != channel.selectedBlockKey) {
                channel.selectedBlockKey = key
                emitSelection(channel, selected)
            }
        }
        if (ticksHeld % CHANNEL_STEP_TICKS == 0) {
            channel.sceneId?.let { scene ->
                effects.moveProjectile(scene, moved, direction)
                effects.synchronize(scene, ticksHeld)
            }
            if (ticksHeld > 0 && ticksHeld % (SOUND_INTERVAL_TICKS * 2) == 0) {
                visuals.withOwner(player.uniqueId) {
                    visuals.cue(moved, Sound.BLOCK_AMETHYST_BLOCK_RESONATE, volume = 0.18f, pitch = 0.68f)
                }
            }
        }
    }

    private fun selectBlock(player: Player): BlockSelection? {
        val eye = player.eyeLocation
        val direction = safeDirection(eye.direction) ?: return null
        val ray = staffBlockHit(eye, direction, GRAVITY_TARGET_RANGE)
        if (!ray.pathLoaded) return null
        val hit = ray.hit ?: return null
        val block = hit.hitBlock ?: return null
        if (!block.type.isSolid || block.isPassable) return null
        return BlockSelection(hit.hitPosition.toLocation(eye.world), block.blockData.clone(),
            block.x, block.y, block.z)
    }

    private fun emitSelection(channel: Channel, selection: BlockSelection) {
        visuals.withOwner(channel.casterId) {
            visuals.particles(selection.point, staffGravitySelectionDebris(selection.data), clearance = 0.0)
            visuals.cue(selection.point, Sound.BLOCK_STONE_HIT, volume = 0.18f, pitch = 0.86f)
        }
    }

    private fun tickFlights() {
        pulseProtectionTick += CHANNEL_STEP_TICKS
        tickPulseFallProtections()
        flights.toList().forEach { flight ->
            if (flight !in flights || flight.cancelled || flight.impacted) return@forEach
            val player = org.bukkit.Bukkit.getPlayer(flight.casterId)
            if (player == null || !current(player, flight.cast, flight.worldId)) {
                cancel(flight.casterId)
                return@forEach
            }
            flight.elapsedTicks += CHANNEL_STEP_TICKS
            flight.sceneAgeTicks += CHANNEL_STEP_TICKS
            flight.speed = (flight.speed + THROW_ACCELERATION * CHANNEL_STEP_TICKS).coerceAtMost(MAX_THROW_SPEED)
            val step = min(flight.speed, flight.remaining)
            if (!step.isFinite() || step <= MIN_FLIGHT_STEP) {
                impact(flight, player, flight.position)
                return@forEach
            }
            val from = flight.position.clone()
            val blockEnd = targeting.rayEnd(from, flight.direction, step)
            val victim = targeting.lineTargets(player, from, blockEnd, GRAVITY_SWEEP_WIDTH, flight.cast).firstOrNull()
            val actualEnd = victim?.let { target ->
                target.boundingBox.expand(GRAVITY_SWEEP_WIDTH).rayTrace(from.toVector(), flight.direction,
                    from.distance(blockEnd))?.hitPosition?.toLocation(from.world)
            } ?: blockEnd
            flight.position = actualEnd.clone()
            flight.remaining = (flight.remaining - from.distance(actualEnd)).coerceAtLeast(0.0)
            flight.sceneId?.let { scene ->
                effects.moveProjectile(scene, actualEnd, flight.direction)
                effects.synchronize(scene, flight.sceneAgeTicks)
            }
            val hitBlock = from.distanceSquared(blockEnd) < step * step - BLOCK_CLIP_EPSILON
            if (victim != null || hitBlock || flight.remaining <= MIN_FLIGHT_STEP ||
                flight.elapsedTicks >= GRAVITY_FLIGHT_TICKS) {
                impact(flight, player, actualEnd)
            }
        }
        tickGravityPulses()
    }

    private fun tickPulseFallProtections() {
        pulseFallProtections.entries.toList().forEach { (entityId, protection) ->
            val entity = protection.entity
            if (protection.expiresAtTick <= pulseProtectionTick || !entity.isValid || entity.isDead ||
                entity.world.uid != protection.worldId) {
                pulseFallProtections.remove(entityId)
                return@forEach
            }
            if (entity.isOnGround) pulseFallProtections.remove(entityId)
        }
    }

    private fun tickGravityPulses() {
        gravityPulses.toList().forEach { pulse ->
            if (pulse !in gravityPulses) return@forEach
            val player = org.bukkit.Bukkit.getPlayer(pulse.casterId)
            if (player == null || !current(player, pulse.cast, pulse.worldId)) {
                cancel(pulse.casterId)
                return@forEach
            }

            pulse.ageTicks += CHANNEL_STEP_TICKS
            effects.synchronize(pulse.sceneId, pulse.ageTicks)
            visuals.withOwner(pulse.casterId) {
                visuals.particles(pulse.origin, staffGravityPulseFrame(pulse.ageTicks, GRAVITY_PULSE_RADIUS))
                if (pulse.ageTicks == GRAVITY_PULSE_LIFT_TICKS) {
                    visuals.groundDepression(pulse.origin, GRAVITY_PULSE_RADIUS)
                    visuals.cue(pulse.origin, Sound.ENTITY_GENERIC_EXPLODE, volume = 0.85f, pitch = 0.55f)
                    visuals.cue(pulse.origin, Sound.BLOCK_DEEPSLATE_BREAK, volume = 0.9f, pitch = 0.65f)
                }
            }

            pulse.targets.toList().forEach { candidate ->
                val target = candidate.entity
                if (!target.isValid || target.isDead || target.world.uid != pulse.worldId ||
                    !damage.eligible(player, target, pulse.cast)) {
                    pulse.targets.remove(candidate)
                    return@forEach
                }
                // The spell owns the fall; keep vanilla fall damage from stacking on the landing hit.
                target.fallDistance = 0f
                if (!candidate.lifted && target.location.y - candidate.startY >= GRAVITY_PULSE_MIN_LIFT)
                    candidate.lifted = true
                if (candidate.lifted && !candidate.slammed && pulse.ageTicks >= GRAVITY_PULSE_LIFT_TICKS) {
                    candidate.slammed = true
                    target.velocity = target.velocity.clone().apply { y = minOf(y, -GRAVITY_PULSE_SLAM_VELOCITY) }
                }
                if (candidate.slammed) {
                    val contact = pulseGroundContact(target) ?: return@forEach
                    val withinRadius = center(target).distanceSquared(pulse.origin) <=
                        (GRAVITY_PULSE_RADIUS + 0.35) * (GRAVITY_PULSE_RADIUS + 0.35)
                    if (withinRadius && current(player, pulse.cast, pulse.worldId) &&
                        damage.eligible(player, target, pulse.cast) &&
                        visible(player.eyeLocation, center(target)) && visible(contact, center(target))) {
                        val hit = damage.hit(player, target, pulse.cast,
                            pulse.tuning.power, pulse.tuning.vanillaDamage)
                        target.fallDistance = 0f
                        if (hit) visuals.withOwner(pulse.casterId) {
                            visuals.particles(contact, staffGravityPulseContactFrame(), clearance = 0.0)
                            visuals.cue(contact, Sound.BLOCK_STONE_BREAK, volume = 0.48f, pitch = 0.62f)
                        }
                    }
                    pulse.targets.remove(candidate)
                }
            }

            if (pulse.ageTicks >= GRAVITY_PULSE_TICKS) {
                pulse.targets.forEach { it.entity.fallDistance = 0f }
                gravityPulses.remove(pulse)
                removeScene(pulse.casterId, pulse.sceneId)
            }
        }
    }

    private fun pulseGroundContact(target: LivingEntity): Location? {
        if (target.velocity.y > GRAVITY_PULSE_CONTACT_MAX_UPWARD_SPEED) return null
        val feet = target.location
        val ground = targeting.groundBelow(feet.clone().add(0.0, GRAVITY_PULSE_GROUND_PROBE_OFFSET, 0.0),
            GRAVITY_PULSE_GROUND_PROBE_RANGE) ?: return null
        return ground.takeIf { (feet.y - it.y) in -GRAVITY_PULSE_GROUND_CONTACT_EPSILON..GRAVITY_PULSE_GROUND_CONTACT_EPSILON }
    }

    private fun impact(flight: Flight, player: Player, at: Location) {
        if (flight !in flights || flight.cancelled || flight.impacted) return
        flight.impacted = true
        val radius = GRAVITY_BLAST_RADIUS
        flight.sceneId?.let { scene ->
            effects.moveProjectile(scene, at, flight.direction)
            effects.synchronize(scene, flight.sceneAgeTicks)
            effects.impact(scene, at, radius, GRAVITY_IMPACT_TICKS)
        }
        visuals.withOwner(flight.casterId) {
            visuals.sceneryBurst(at, radius)
            visuals.particles(at, staffGravityImpactFrame(), clearance = 0.0)
            visuals.cue(at, Sound.BLOCK_DEEPSLATE_BREAK, volume = 0.72f, pitch = 0.64f)
            visuals.cue(at, Sound.BLOCK_AMETHYST_BLOCK_RESONATE, volume = 0.34f, pitch = 0.58f)
        }
        val targets = targeting.nearby(player, at, radius, flight.cast)
            .filter { center(it).distanceSquared(at) <= radius * radius }
            .filter { visible(at, center(it)) && visible(player.eyeLocation, center(it)) }
            .sortedBy { center(it).distanceSquared(at) }
            .take(settings.maxAreaTargets.coerceIn(1, 16))
        for (target in targets) {
            if (flight.cancelled || !current(player, flight.cast, flight.worldId) ||
                !damage.eligible(player, target, flight.cast) || !visible(at, center(target)) ||
                !visible(player.eyeLocation, center(target))) break
            damage.hit(player, target, flight.cast, flight.tuning.power, flight.tuning.vanillaDamage)
        }
        flights.remove(flight)
        if (!flight.cancelled) retireScene(flight.casterId, flight.sceneId, (GRAVITY_IMPACT_TICKS + 2).toLong())
    }

    private fun retireScene(casterId: UUID, sceneId: UUID?, afterTicks: Long) {
        val scene = sceneId ?: return
        tasks.runLater(afterTicks) {
            scenesByCaster[casterId]?.remove(scene)
            if (scenesByCaster[casterId].isNullOrEmpty()) scenesByCaster.remove(casterId)
        }
    }

    private fun trackScene(casterId: UUID, sceneId: UUID) {
        scenesByCaster.getOrPut(casterId, ::mutableSetOf).add(sceneId)
    }

    private fun removeScene(casterId: UUID, sceneId: UUID?) {
        val scene = sceneId ?: return
        scenesByCaster[casterId]?.remove(scene)
        if (scenesByCaster[casterId].isNullOrEmpty()) scenesByCaster.remove(casterId)
        effects.remove(scene)
    }

    private fun infernoReach() = settings.range.takeIf { it.isFinite() && it > 0.0 }
        ?.coerceAtMost(INFERNO_MAX_REACH) ?: INFERNO_DEFAULT_REACH

    private fun infernoMuzzle(eye: Location, direction: Vector) =
        eye.clone().add(direction.clone().multiply(0.48)).add(0.0, -0.28, 0.0)

    private fun current(player: Player, cast: StaffSpellCast, worldId: UUID) =
        ready(player, cast) && player.world.uid == worldId

    private fun ready(player: Player, cast: StaffSpellCast) =
        cast.casterId == player.uniqueId && player.isOnline && player.isValid && !player.isDead &&
            player.gameMode != GameMode.SPECTATOR && damage.isCurrent(player, cast)

    private fun safeDirection(direction: Vector): Vector? = direction.clone()
        .takeIf { it.x.isFinite() && it.y.isFinite() && it.z.isFinite() && it.lengthSquared() > 0.0001 }
        ?.normalize()

    private fun orient(local: Vector, forward: Vector): Vector {
        val right = Vector(0.0, 1.0, 0.0).crossProduct(forward)
            .let { if (it.lengthSquared() < 0.0001) Vector(1.0, 0.0, 0.0) else it.normalize() }
        val up = forward.clone().crossProduct(right).normalize()
        return right.multiply(local.x).add(up.multiply(local.y)).add(forward.clone().multiply(local.z))
    }

    private companion object {
        val CHANNEL_SPELLS = setOf(StaffSpell.INFERNO, StaffSpell.GRAVITY)
        const val MAX_CHANNELS = 16
        const val MAX_FLIGHTS = 16
        const val MAX_GRAVITY_PULSES = 16
        const val MAX_PULSE_TARGETS = 16
        const val MAX_PULSE_FALL_PROTECTIONS = MAX_GRAVITY_PULSES * MAX_PULSE_TARGETS * 2
        const val CHANNEL_STEP_TICKS = 2
        const val MAX_PULSE_FALL_PROTECTION_TICKS = 60L
        const val INFERNO_MAX_HOLD_TICKS = 60
        const val GRAVITY_MAX_HOLD_TICKS = 160
        const val GRAVITY_FLIGHT_TICKS = 36
        const val GRAVITY_IMPACT_TICKS = 16
        const val SOUND_INTERVAL_TICKS = 10
        const val INFERNO_DAMAGE_INTERVAL = 8
        const val INFERNO_MAX_REACH = 9.0
        const val INFERNO_DEFAULT_REACH = 8.0
        const val INFERNO_SWEEP_WIDTH = 0.38
        const val PARTICLE_CLEARANCE = 1.35
        const val GRAVITY_TARGET_RANGE = 32.0
        const val GRAVITY_HOLD_DISTANCE = 4.0
        const val GRAVITY_MIN_EYE_DISTANCE = 2.1
        const val GRAVITY_BLOCK_GAP = 0.65
        const val GRAVITY_RADIUS = 1.0
        const val GRAVITY_THROW_RANGE = 32.0
        const val GRAVITY_SWEEP_WIDTH = 0.65
        const val GRAVITY_BLAST_RADIUS = 2.5
        const val GRAVITY_PULSE_RADIUS = 6.5
        const val GRAVITY_PULSE_TICKS = 36
        const val GRAVITY_PULSE_LIFT_TICKS = 8
        const val GRAVITY_PULSE_LIFT_VELOCITY = 0.9
        const val GRAVITY_PULSE_SLAM_VELOCITY = 0.9
        const val GRAVITY_PULSE_MIN_LIFT = 0.6
        const val GRAVITY_PULSE_CONTACT_MAX_UPWARD_SPEED = 0.05
        const val GRAVITY_PULSE_GROUND_PROBE_OFFSET = 0.06
        const val GRAVITY_PULSE_GROUND_PROBE_RANGE = 1.6
        const val GRAVITY_PULSE_GROUND_CONTACT_EPSILON = 0.16
        const val INITIAL_THROW_SPEED = 3.2
        const val THROW_ACCELERATION = 0.16
        const val MAX_THROW_SPEED = 4.4
        const val MIN_FLIGHT_STEP = 0.03
        const val BLOCK_CLIP_EPSILON = 0.0001
    }
}
