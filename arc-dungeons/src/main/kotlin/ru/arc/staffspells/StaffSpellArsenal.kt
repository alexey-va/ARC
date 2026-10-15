package ru.arc.staffspells

import org.bukkit.Bukkit
import org.bukkit.GameMode
import org.bukkit.Location
import org.bukkit.Sound
import org.bukkit.entity.LivingEntity
import org.bukkit.entity.Player
import org.bukkit.potion.PotionEffect
import org.bukkit.potion.PotionEffectType
import org.bukkit.util.Vector
import ru.arc.core.LifecycleTaskScope
import java.util.UUID
import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.sin

/** Authored spell sequences. Each stage owns its collision timing and its matching native VFX. */
internal class StaffSpellArsenal(
    private val settings: StaffSpellSettings,
    private val damage: StaffSpellDamage,
    private val effects: StaffSpellDisplayEffects,
    private val visuals: StaffSpellVisuals,
    private val targeting: StaffSpellTargeting,
) : AutoCloseable {
    private data class Attack(
        val casterId: UUID,
        val spell: StaffSpell,
        val input: StaffCastInput,
        val cast: StaffSpellCast,
        val tuning: StaffSpellTuning,
        val origin: Location,
        val point: Location,
        val aimPoint: Location,
        val travelEnd: Location,
        val forward: Vector,
        val nodes: List<Location>,
        val radius: Double,
        var duration: Int,
        val scenes: MutableList<UUID?> = mutableListOf(),
        val born: MutableSet<Int> = mutableSetOf(),
        val attempted: MutableSet<UUID> = mutableSetOf(),
        val impactedProjectiles: MutableSet<Int> = mutableSetOf(),
        val projectileScenes: MutableMap<Int, UUID?> = mutableMapOf(),
        var rays: List<Pair<Location, Location>> = emptyList(),
        var age: Int = 0,
        var requiemPulseIndex: Int = -1,
        var requiemRayLimits: List<Double> = emptyList(),
        var tideImpactAge: Int? = null,
        var tideImpactPoint: Location? = null,
        var tideBurstApplied: Boolean = false,
        var travelPoint: Location? = null,
        var galeImpactAge: Int? = null,
        var galeImpactPoint: Location? = null,
    )
    private val tasks = LifecycleTaskScope()
    private val attacks = mutableListOf<Attack>()
    private var started = false
    private var closed = false

    fun start() {
        check(!closed)
        if (started) return
        started = true
        tasks.runTimer(2, 2, ::tick)
    }

    fun cast(player: Player, spell: StaffSpell, cast: StaffSpellCast, tuning: StaffSpellTuning,
        input: StaffCastInput, charge: Double = 0.0) {
        if (closed || input.quick || spell !in SPELLS || !ready(player) || !damage.isCurrent(player, cast)) return
        val eye = player.eyeLocation
        val q = charge.takeIf(Double::isFinite)?.coerceIn(0.0, 1.0) ?: 0.0
        val forwardPath = spell == StaffSpell.GALE && input.secondary ||
            spell == StaffSpell.TIDE || spell == StaffSpell.OSSUARY && !input.secondary
        val range = minOf(settings.range, if (spell == StaffSpell.OSSUARY && !input.secondary) 24.0 else 36.0) *
            if (forwardPath) 1.0 else (.75 + q * .25)
        val casterCenteredRequiem = spell == StaffSpell.REQUIEM && input.secondary
        val selected = if (forwardPath || casterCenteredRequiem) null
            else targeting.aimed(player, range, settings.aimDegrees, cast).firstOrNull()
        if (!damage.isCurrent(player, cast)) return
        val initialDirection = if (forwardPath || casterCenteredRequiem)
            eye.clone().apply { pitch = 0f }.direction else eye.direction
        val aimDirection = if (forwardPath) targeting.assistedDirection(player, spell, cast,
            direction = initialDirection, from = eye, range = range) else initialDirection
        val aimed = if (casterCenteredRequiem) player.location.clone()
            else selected?.let(::center) ?: targeting.rayEnd(eye, aimDirection, range)
        val point = targeting.groundBelow(aimed.clone().add(0.0, 2.0, 0.0), 12.0) ?: aimed.clone().subtract(0.0, 1.2, 0.0)
        if (!casterCenteredRequiem && !visible(eye, aimed)) return
        val forward = Vector(point.x - eye.x, 0.0, point.z - eye.z).apply {
            if (lengthSquared() < .01) { x = eye.direction.x; z = eye.direction.z }
            if (lengthSquared() < .01) z = 1.0
        }.normalize()
        val launchDistance = minOf(2.5, range * .25, eye.distance(aimed) * .35)
        val origin = targeting.rayEnd(eye, forward, launchDistance).subtract(0.0, 1.62, 0.0)
        val groundOrigin = targeting.groundBelow(origin.clone().add(0.0, 2.0, 0.0), 5.0) ?: origin
        val distance = point.toVector().subtract(groundOrigin.toVector()).dot(forward).coerceIn(0.0, range)
        if (!visible(eye, groundOrigin.clone().add(0.0, 1.0, 0.0))) return
        // Ground fronts keep their reach when looking down or past a nearby mob.
        // The shared block ray below still clips their complete path at cover.
        val intendedTravelEnd = if (forwardPath) eye.clone().add(forward.clone().multiply(range)).apply { y = groundOrigin.y }
            else groundOrigin.clone().add(forward.clone().multiply(distance)).apply { y = point.y }
        val pathSpell = spell == StaffSpell.TIDE || spell == StaffSpell.GALE && input.secondary ||
            spell == StaffSpell.NOVA && !input.secondary || spell == StaffSpell.OSSUARY && !input.secondary
        val travelEnd = if (spell == StaffSpell.TIDE && !input.secondary) {
            // TIDE is a fast moving body: keep its full horizontal endpoint here and
            // clip each swept step against the current world in tideOrb. A cast-time
            // shortened endpoint would make a nearby wall stretch the flight out.
            val delta = intendedTravelEnd.toVector().subtract(groundOrigin.toVector())
            if (!delta.length().isFinite() || delta.length() < MIN_GROUND_PATH_LENGTH) return
            intendedTravelEnd
        } else if (pathSpell) {
            val delta = intendedTravelEnd.toVector().subtract(groundOrigin.toVector())
            val pathLength = delta.length()
            if (!pathLength.isFinite() || pathLength < MIN_GROUND_PATH_LENGTH) return
            val clippedEnd = targeting.rayEnd(groundOrigin, delta.multiply(1.0 / pathLength), pathLength)
            if (!clippedEnd.x.isFinite() || !clippedEnd.y.isFinite() || !clippedEnd.z.isFinite() ||
                clippedEnd.distanceSquared(groundOrigin) < MIN_GROUND_PATH_LENGTH * MIN_GROUND_PATH_LENGTH) return
            clippedEnd
        } else intendedTravelEnd
        val pathDistance = travelEnd.toVector().subtract(groundOrigin.toVector()).dot(forward).coerceIn(0.0, range)
        val count = ceil(pathDistance / 2.0).toInt().coerceIn(1, 12)
        val nodes = if (spell == StaffSpell.OSSUARY && !input.secondary) (0 until count).mapNotNull { i ->
            val at = groundOrigin.clone().add(forward.clone().multiply(pathDistance * (i + 1) / count))
            val ground = targeting.groundBelow(at.clone().add(0.0, 2.0, 0.0), 6.0) ?: return@mapNotNull null
            ground.takeIf { visible(eye, it.clone().add(0.0, .8, 0.0)) }
        } else emptyList()
        if (spell == StaffSpell.OSSUARY && !input.secondary && nodes.isEmpty()) return
        val attackPoint = if (casterCenteredRequiem)
            targeting.groundBelow(player.location.clone().add(0.0, 1.0, 0.0), 4.0) ?: player.location.clone()
        else point
        val attackOrigin = if (casterCenteredRequiem) attackPoint.clone() else groundOrigin
        while (attacks.count { it.casterId == player.uniqueId } >= 2) remove(attacks.first { it.casterId == player.uniqueId })
        while (attacks.size >= 24) remove(attacks.first())
        val radius = when {
            spell == StaffSpell.OSSUARY && input.secondary -> ossuarySecondaryRadius(q)
            spell == StaffSpell.OSSUARY -> ossuaryPrimaryRadius(q)
            spell == StaffSpell.TIDE && input.secondary -> staffTideWaveHalfWidth(q)
            else -> 2.2 + q
        }
        val a = Attack(player.uniqueId, spell, input, cast.copy(visualCharge = q), tuning,
            attackOrigin, attackPoint, selected?.let(::center) ?: aimed, travelEnd, forward, nodes, radius,
            staffArsenalLifetime(spell, input.secondary)).apply {
            if (spell == StaffSpell.GALE && input.secondary || spell == StaffSpell.TIDE && input.secondary)
                travelPoint = groundOrigin.clone()
        }
        attacks += a
        if (spell == StaffSpell.REQUIEM) a.requiemRayLimits = requiemRayLimits(a)
        sound(a, a.origin, false)
        when (spell) {
            StaffSpell.LUNAR, StaffSpell.PRISM -> scene(a, a.point, a.duration)
            StaffSpell.GALE -> if (input.secondary) scene(a, a.origin.clone().add(0.0, 1.1, 0.0), a.duration,
                a.travelEnd.clone().add(0.0, 1.1, 0.0), controllerDriven = true)
                else scene(a, a.point, a.duration)
            StaffSpell.REQUIEM -> scene(a, a.point, a.duration)
            StaffSpell.NOVA -> if (input.secondary) scene(a, a.point, a.duration)
                else scene(a, a.origin, a.duration, a.travelEnd)
            StaffSpell.TIDE -> if (!input.secondary) {
                scene(a, a.origin.clone().add(0.0, 1.2, 0.0), a.duration,
                    a.travelEnd.clone().add(0.0, 1.2, 0.0), controllerDriven = true)
            } else scene(a, a.origin, a.duration, a.travelEnd, controllerDriven = true)
            StaffSpell.OSSUARY -> if (input.secondary) scene(a, a.point, a.duration)
            else -> Unit
        }
        frame(a, player)
    }

    fun cancel(casterId: UUID) = attacks.filter { it.casterId == casterId }.toList().forEach(::remove)
    override fun close() {
        if (closed) return
        closed = true
        tasks.close()
        attacks.toList().forEach(::remove)
    }

    private fun tick() {
        attacks.toList().forEach { a ->
            val player = Bukkit.getPlayer(a.casterId)
            if (a !in attacks) return@forEach
            if (player == null || !current(a, player)) { remove(a); return@forEach }
            a.age += 2
            frame(a, player)
            if (a in attacks && a.age >= a.duration) remove(a)
        }
    }

    private fun frame(a: Attack, player: Player) {
        if (!current(a, player)) { remove(a); return }
        when (a.spell) {
            StaffSpell.TIDE -> if (a.input.secondary) {
                val step = groundStep(a, ((a.age - 4) / TIDE_WAVE_TRAVEL_TICKS).coerceIn(0.0, 1.0))
                val at = step.to
                a.travelPoint = at.clone()
                val radius = staffTideWaveHalfWidth(a.cast.visualCharge)
                particles(a, at, a.age, a.duration, radius = radius)
                a.scenes.firstOrNull()?.let { id ->
                    effects.move(id, at)
                    effects.synchronize(id, a.age.coerceAtLeast(0))
                }
                if (a.age in 8..34) area(a, player, at.clone().add(0.0, 1.0, 0.0),
                    radius, lift = .25)
                if (a.age == 34) sound(a, at, true)
            } else tideOrb(a, player)
            StaffSpell.GALE -> if (a.input.secondary) galeRam(a, player) else {
                particles(a, a.point, a.age, a.duration, radius = a.radius)
                if (a.age == 20) sound(a, a.point, true)
                if (a.age in 20..28) area(a, player, a.point.clone().add(0.0, 1.1, 0.0),
                    2.5 + a.cast.visualCharge * .45, lift = .2)
            }
            StaffSpell.LUNAR -> {
                particles(a, a.point, a.age, a.duration, radius = a.radius)
                if (a.age == LUNAR_SECONDARY_STRIKE_TICK) sound(a, a.point, true)
                if (a.input.secondary && a.age in LUNAR_SECONDARY_STRIKE_TICK..LUNAR_SECONDARY_DAMAGE_END_TICK)
                    area(a, player, a.point.clone().add(0.0, 1.3, 0.0), lunarSecondaryDamageRadius(a))
                else if (!a.input.secondary && a.age in 16..24)
                    area(a, player, a.point.clone().add(0.0, 1.3, 0.0), a.radius + .7)
            }
            StaffSpell.NOVA -> if (a.input.secondary) {
                particles(a, a.point, a.age, a.duration, secondary = true, radius = a.radius)
                if (a.age == 20) sound(a, a.point, true)
                if (a.age in 20..24) area(a, player, a.point.clone().add(0.0, 1.1, 0.0),
                    2.2 + a.cast.visualCharge * .35, slow = true)
            } else {
                particles(a, a.origin, a.age, a.duration, secondary = false,
                    radius = a.travelEnd.distance(a.origin).coerceAtLeast(1.0))
                // Keep the contact volume low at the clipped root tip so a short wall
                // cannot be bypassed by lifting the damage origin above its top.
                val contact = a.travelEnd.clone().add(0.0, .55, 0.0)
                if (a.age == NOVA_ROOT_TRAVEL_TICKS) sound(a, contact, true)
                if (a.age in NOVA_ROOT_TRAVEL_TICKS..(NOVA_ROOT_TRAVEL_TICKS + 4)) {
                    area(a, player, contact, 2.0 + a.cast.visualCharge * .35, slow = true)
                }
            }
            StaffSpell.REQUIEM -> requiemWave(a, player)
            StaffSpell.PRISM -> prism(a, player)
            StaffSpell.OSSUARY -> if (a.input.secondary) ossuaryStrike(a, player) else ossuarySpikes(a, player)
            else -> Unit
        }
    }

    private fun tideOrb(a: Attack, player: Player) {
        val impactAge = a.tideImpactAge
        if (impactAge != null) {
            val impactPoint = a.tideImpactPoint ?: return
            val stageAge = (a.age - impactAge).coerceAtLeast(0)
            particles(a, impactPoint, stageAge, STAFF_TIDE_PRIMARY_IMPACT_TICKS,
                radius = tideBurstRadius(a), impact = true)
            return
        }

        val from = tideFlightPoint(a, a.age - 2).add(0.0, 1.2, 0.0)
        val intendedAt = tideFlightPoint(a, a.age).add(0.0, 1.2, 0.0)
        val path = intendedAt.toVector().subtract(from.toVector())
        val pathLength = path.length()
        val at = if (pathLength.isFinite() && pathLength > .001)
            targeting.rayEnd(from, path.multiply(1.0 / pathLength), pathLength)
        else from.clone()
        val blocked = at.distanceSquared(intendedAt) > .0001

        if (a.age >= STAFF_TIDE_PRIMARY_LAUNCH_TICK) {
            val firstContact = targeting.lineTargets(player, from, at, TIDE_ORB_HIT_WIDTH, a.cast).firstOrNull()
            if (firstContact != null) {
                val contact = tideContactPoint(from, at, firstContact, a.cast)
                hit(a, player, firstContact, from)
                if (!current(a, player)) { remove(a); return }
                beginTideImpact(a, player, contact)
                return
            }
        }

        if (blocked || a.age >= STAFF_TIDE_PRIMARY_ARRIVAL_TICK) {
            beginTideImpact(a, player, at)
            return
        }

        particles(a, at, a.age, a.duration, radius = 1.0 + a.cast.visualCharge * .3)
        a.scenes.firstOrNull()?.let { id ->
            effects.move(id, at)
            effects.synchronize(id, a.age.coerceAtLeast(0))
        }
    }

    private fun tideContactPoint(from: Location, to: Location, target: LivingEntity,
        cast: StaffSpellCast): Location {
        val delta = to.toVector().subtract(from.toVector())
        val distance = delta.length()
        if (!distance.isFinite() || distance < .001) return from.clone()
        val width = TIDE_ORB_HIT_WIDTH * cast.enchantments.radiusMultiplier
        val direction = delta.multiply(1.0 / distance)
        val contact = target.boundingBox.expand(width).rayTrace(from.toVector(), direction, distance)?.hitPosition
        return contact?.toLocation(from.world) ?: to.clone()
    }

    private fun tideFlightPoint(a: Attack, ageTicks: Int): Location {
        val progress = ((ageTicks - STAFF_TIDE_PRIMARY_LAUNCH_TICK).toDouble() /
            STAFF_TIDE_PRIMARY_TRAVEL_TICKS).coerceIn(0.0, 1.0)
        val delta = a.travelEnd.toVector().subtract(a.origin.toVector())
        return a.origin.clone().add(delta.multiply(progress))
    }

    private fun beginTideImpact(a: Attack, player: Player, at: Location) {
        if (a.tideImpactAge != null) return
        a.tideImpactAge = a.age
        a.tideImpactPoint = at.clone()
        a.duration = minOf(a.duration, a.age + STAFF_TIDE_PRIMARY_IMPACT_TICKS)
        val radius = tideBurstRadius(a)
        a.scenes.firstOrNull()?.let { id ->
            effects.move(id, at)
            effects.synchronize(id, a.age.coerceAtLeast(0))
            effects.impact(id, at, radius, STAFF_TIDE_PRIMARY_IMPACT_TICKS)
        }
        particles(a, at, 0, STAFF_TIDE_PRIMARY_IMPACT_TICKS, radius = radius, impact = true)
        if (!a.tideBurstApplied) {
            a.tideBurstApplied = true
            area(a, player, at, radius)
            sound(a, at, true)
        }
    }

    private fun tideBurstRadius(a: Attack) = 2.2 + a.cast.visualCharge * .25

    private fun ossuarySpikes(a: Attack, player: Player) {
        a.nodes.forEachIndexed { index, at ->
            val localAge = a.age - OSSUARY_SPIKE_START_TICK - index * OSSUARY_SPIKE_STAGGER_TICKS
            val radius = ossuaryPrimaryRadius(a.cast.visualCharge)
            if (localAge >= 0 && a.born.add(index)) scene(a, at, OSSUARY_SPIKE_SCENE_TICKS, radius = radius)
            if (localAge in 0 until OSSUARY_SPIKE_SCENE_TICKS) particles(a, at, localAge,
                OSSUARY_SPIKE_SCENE_TICKS, secondary = false, radius = radius)
            if (localAge == OSSUARY_SPIKE_CONTACT_TICK) sound(a, at, true)
            if (localAge in OSSUARY_SPIKE_CONTACT_TICK..(OSSUARY_SPIKE_CONTACT_TICK + 2)) {
                // Center the query over the target's body, but keep it low enough that
                // World#getNearbyLivingEntities includes entities whose location is at their feet.
                area(a, player, at.clone().add(0.0, .8, 0.0), radius)
            }
        }
    }

    private fun ossuaryStrike(a: Attack, player: Player) {
        val radius = ossuarySecondaryRadius(a.cast.visualCharge)
        particles(a, a.point, a.age, a.duration, secondary = true, radius = radius)
        if (a.age == OSSUARY_SECONDARY_STRIKE_TICK) sound(a, a.point, true)
        if (a.age in OSSUARY_SECONDARY_STRIKE_TICK..(OSSUARY_SECONDARY_STRIKE_TICK + 2)) {
            area(a, player, a.point.clone().add(0.0, 1.2, 0.0), radius)
        }
    }

    private fun requiemWave(a: Attack, player: Player) {
        val secondary = a.input.secondary
        val radius = requiemRadius(secondary)
        particles(a, a.point, a.age, a.duration, secondary, radius)
        val pulse = staffRequiemPulse(a.age, secondary) ?: return
        if (pulse.index != a.requiemPulseIndex) {
            a.requiemPulseIndex = pulse.index
            a.attempted.clear()
        }
        if (a.age == pulse.startTick) sound(a, a.point, true)
        val previousPulse = staffRequiemPulse(a.age - 2, secondary)?.takeIf { it.index == pulse.index }
        val currentRadius = radius * pulse.progress
        val previousRadius = radius * (previousPulse?.progress ?: 0.0)
        val right = Vector(a.forward.z, 0.0, -a.forward.x)
        val frontOrigin = a.point.clone().add(0.0, .9, 0.0)
        repeat(STAFF_REQUIEM_COLLISION_RAY_COUNT) { ray ->
            val angle = ray * PI * 2 / STAFF_REQUIEM_COLLISION_RAY_COUNT
            val direction = right.clone().multiply(cos(angle)).add(a.forward.clone().multiply(sin(angle))).normalize()
            val limit = a.requiemRayLimits.getOrElse(ray) { radius }.coerceIn(0.0, radius)
            val clippedCurrent = minOf(currentRadius, limit)
            val clippedPrevious = minOf(previousRadius, limit)
            if (clippedCurrent <= clippedPrevious) return@repeat
            val from = frontOrigin.clone().add(direction.clone().multiply(clippedPrevious))
            val requestedEnd = frontOrigin.clone().add(direction.clone().multiply(clippedCurrent))
            val end = targeting.rayEnd(from, requestedEnd.toVector().subtract(from.toVector()),
                from.distance(requestedEnd))
            targeting.lineTargets(player, from, end, .55, a.cast).forEach { target ->
                if (visible(frontOrigin, center(target)))
                    hit(a, player, target, from, if (secondary) REQUIEM_SECONDARY_DAMAGE_MULTIPLIER else 1.0)
            }
        }
    }

    private fun requiemRadius(secondary: Boolean) = minOf(
        if (secondary) STAFF_REQUIEM_SECONDARY_RADIUS else STAFF_REQUIEM_PRIMARY_RADIUS,
        settings.range.coerceAtLeast(0.0))

    private fun requiemRayLimits(a: Attack): List<Double> {
        val center = a.point.clone().add(0.0, .9, 0.0)
        val radius = requiemRadius(a.input.secondary)
        val right = Vector(a.forward.z, 0.0, -a.forward.x)
        return List(STAFF_REQUIEM_COLLISION_RAY_COUNT) { ray ->
            val angle = ray * PI * 2 / STAFF_REQUIEM_COLLISION_RAY_COUNT
            val direction = right.clone().multiply(cos(angle)).add(a.forward.clone().multiply(sin(angle))).normalize()
            center.distance(targeting.rayEnd(center, direction, radius)).coerceIn(0.0, radius)
        }
    }

    private fun sweptContact(a: Attack, player: Player, from: Location, to: Location, width: Double, lift: Double = 0.0) {
        targeting.lineTargets(player, from, to, width, a.cast).forEach { target ->
            if (hit(a, player, target, from) && lift > 0.0) target.velocity = target.velocity.apply { y = maxOf(y, lift) }
        }
    }

    private fun prism(a: Attack, player: Player) {
        particles(a, a.point, a.age, a.duration)
        if (a.age == 10) {
            val emitter = a.point.clone().add(0.0, 2.6, 0.0)
            val eligible = targeting.nearby(player, emitter, 12.0, a.cast)
                .filter { visible(emitter, center(it)) }.sortedBy { center(it).distanceSquared(emitter) }.take(3)
            if (!current(a, player)) return
            a.rays = if (a.input.secondary) {
                // Three converging emitters focus on the aimed point, then pierce along the cast line.
                val right = Vector(a.forward.z, 0.0, -a.forward.x)
                (-1..1).map { lane ->
                    val from = a.origin.clone().add(0.0, 2.2, 0.0).add(right.clone().multiply(lane * 1.7))
                    from to targeting.rayEnd(from, emitter.toVector().subtract(from.toVector()).normalize(),
                        from.distance(emitter).coerceAtMost(48.0))
                }
            } else if (eligible.isNotEmpty()) eligible.map { emitter.clone() to center(it) }
            else (-1..1).map { lane -> emitter.clone() to targeting.rayEnd(emitter,
                a.forward.clone().rotateAroundY(lane * .55), 9.0) }
            sound(a, emitter, true)
        }
        if (a.age !in 10..24) return
        val front = ((a.age - 10) / 6.0).coerceIn(0.0, 1.0)
        val previous = ((a.age - 12) / 6.0).coerceIn(0.0, 1.0)
        a.rays.forEach { (from, to) ->
            if (!current(a, player)) return
            val ray = to.toVector().subtract(from.toVector())
            visuals.withOwner(a.casterId) { visuals.particles(from,
                staffPrismBeam(ray, front, a.cast.visualCharge, a.age)) }
            if (front <= previous) return@forEach
            val begin = from.clone().add(ray.clone().multiply(previous))
            val end = from.clone().add(ray.clone().multiply(front))
            targeting.lineTargets(player, begin, end, .55, a.cast).forEach { hit(a, player, it, from) }
        }
    }

    private fun along(a: Attack, progress: Double): Location {
        val t = progress.takeIf(Double::isFinite)?.coerceIn(0.0, 1.0) ?: 0.0
        val eased = t * t * (3.0 - 2.0 * t)
        val delta = a.travelEnd.toVector().subtract(a.origin.toVector())
        return a.origin.clone().add(delta.multiply(eased))
    }

    private data class GroundPathStep(val from: Location, val to: Location, val blocked: Boolean)

    /** Samples the floor at each moved frame and clips the current step before intervening blocks. */
    private fun groundStep(a: Attack, progress: Double): GroundPathStep {
        val from = a.travelPoint?.clone() ?: a.origin.clone()
        val requested = along(a, progress)
        val floor = targeting.groundBelow(requested.clone().add(0.0, 2.0, 0.0), 12.0)
            ?: return GroundPathStep(from, from, blocked = true)
        val rayFrom = from.clone().add(0.0, .12, 0.0)
        val rayTo = floor.clone().add(0.0, .12, 0.0)
        val delta = rayTo.toVector().subtract(rayFrom.toVector())
        val distance = delta.length()
        if (!distance.isFinite()) return GroundPathStep(from, from, blocked = true)
        if (distance < .001) return GroundPathStep(from, floor, blocked = false)
        val ray = staffBlockHit(rayFrom, delta, distance)
        if (!ray.pathLoaded) return GroundPathStep(from, from, blocked = true)
        val hit = ray.hit ?: return GroundPathStep(from, floor, blocked = false)
        val safeDistance = (hit.hitPosition.distance(rayFrom.toVector()) - .04).coerceAtLeast(0.0)
        val fraction = (safeDistance / distance).coerceIn(0.0, 1.0)
        val clipped = from.clone().add(floor.toVector().subtract(from.toVector()).multiply(fraction))
        val clippedFloor = targeting.groundBelow(clipped.clone().add(0.0, 2.0, 0.0), 12.0) ?: clipped
        return GroundPathStep(from, clippedFloor, blocked = true)
    }

    private fun galeRam(a: Attack, player: Player) {
        val impactAge = a.galeImpactAge
        if (impactAge != null) {
            galeRamRelease(a, player, a.galeImpactPoint ?: a.travelPoint ?: a.origin,
                a.age - impactAge)
            return
        }

        val progress = ((a.age - 4) / GALE_RAM_TRAVEL_TICKS).coerceIn(0.0, 1.0)
        val step = groundStep(a, progress)
        val at = step.to
        a.travelPoint = at.clone()
        val burstRadius = galeRamBurstRadius(a)
        particles(a, at, a.age, a.duration, radius = burstRadius)
        a.scenes.firstOrNull()?.let { id ->
            val sceneAt = at.clone().add(0.0, 1.1, 0.0)
            effects.move(id, sceneAt)
            effects.synchronize(id, a.age.coerceAtLeast(0))
        }

        if (a.age >= 6 && progress > 0.0) {
            val fromBody = step.from.clone().add(0.0, 1.1, 0.0)
            val toBody = at.clone().add(0.0, 1.1, 0.0)
            val target = targeting.lineTargets(player, fromBody, toBody, GALE_RAM_HIT_WIDTH, a.cast).firstOrNull()
            if (target != null) {
                val contact = groundContact(fromBody, toBody, target, GALE_RAM_HIT_WIDTH, a.cast) ?: at
                if (hit(a, player, target, fromBody))
                    target.velocity = target.velocity.apply { y = maxOf(y, .38) }
                if (!current(a, player)) { remove(a); return }
                beginGaleRamRelease(a, player, contact)
                return
            }
        }
        if (step.blocked || progress >= 1.0) beginGaleRamRelease(a, player, at)
    }

    private fun groundContact(from: Location, to: Location, target: LivingEntity,
        width: Double, cast: StaffSpellCast): Location? {
        val delta = to.toVector().subtract(from.toVector())
        val distance = delta.length()
        if (!distance.isFinite() || distance < .001) return null
        val direction = delta.multiply(1.0 / distance)
        val hit = target.boundingBox.expand(width * cast.enchantments.radiusMultiplier)
            .rayTrace(from.toVector(), direction, distance)?.hitPosition ?: return null
        return targeting.groundBelow(hit.toLocation(from.world).add(0.0, 2.0, 0.0), 12.0)
    }

    private fun beginGaleRamRelease(a: Attack, player: Player, at: Location) {
        if (a.galeImpactAge != null) return
        a.galeImpactAge = a.age
        a.galeImpactPoint = at.clone()
        a.duration = minOf(a.duration, a.age + GALE_RAM_RELEASE_TICKS)
        a.scenes.firstOrNull()?.let { id ->
            effects.impact(id, at, galeRamBurstRadius(a), GALE_RAM_RELEASE_TICKS)
        }
        sound(a, at, true)
        galeRamRelease(a, player, at, stageAge = 0)
    }

    private fun galeRamRelease(a: Attack, player: Player, at: Location, stageAge: Int) {
        particles(a, at, stageAge.coerceAtLeast(0), GALE_RAM_RELEASE_TICKS,
            secondary = true, radius = galeRamBurstRadius(a), impact = true)
        if (stageAge in 0..GALE_RAM_DAMAGE_TICKS) {
            val impactRadius = galeRamBurstRadius(a) * smooth(stageAge / 3.0)
            if (impactRadius > .05) area(a, player, at.clone().add(0.0, .8, 0.0), impactRadius)
        }
    }

    private fun scene(a: Attack, at: Location, duration: Int, to: Location = at.clone().add(a.forward),
        controllerDriven: Boolean = false, radius: Double = a.radius): UUID? {
        val id = effects.play(a.casterId, a.spell, at, to,
            radius = radius, durationTicks = duration, secondary = a.input.secondary,
            input = a.input, controllerDriven = controllerDriven, charge = a.cast.visualCharge)
        a.scenes += id
        return id
    }

    private fun particles(a: Attack, at: Location, age: Int, duration: Int,
        secondary: Boolean = a.input.secondary, radius: Double = a.radius, impact: Boolean = false) {
        if (a !in attacks) return
        val right = Vector(a.forward.z, 0.0, -a.forward.x)
        val reach = if (a.spell == StaffSpell.NOVA && !secondary) a.travelEnd.distance(a.origin) else radius
        val authored = staffArsenalParticles(a.spell, age, duration, radius, secondary, a.cast.visualCharge,
            reach, impact, a.requiemRayLimits)
        val limit = if (!secondary && a.spell == StaffSpell.OSSUARY)
            240 / a.nodes.size.coerceAtLeast(1) else 160
        val admitted = if (authored.size <= limit) authored else
            List(limit) { index -> authored[index * (authored.size - 1) / (limit - 1)] }
        val samples = admitted.map { sample ->
            val p = sample.offset
            sample.copy(offset = right.clone().multiply(p.x).add(Vector(0.0, p.y, 0.0)).add(a.forward.clone().multiply(p.z)))
        }
        visuals.withOwner(a.casterId) { visuals.particles(at, samples) }
    }

    private fun area(a: Attack, player: Player, at: Location, radius: Double, lift: Double = 0.0, slow: Boolean = false) {
        if (!current(a, player)) return
        targeting.nearby(player, at, radius, a.cast).sortedBy { center(it).distanceSquared(at) }
            .take(settings.maxAreaTargets.coerceIn(1, 16)).forEach { victim ->
                if (hit(a, player, victim, at) && current(a, player) && damage.eligible(player, victim, a.cast)) {
                    if (lift > 0.0) victim.velocity = victim.velocity.apply { y = maxOf(y, lift) }
                    if (slow) victim.addPotionEffect(PotionEffect(PotionEffectType.SLOWNESS, 35, 1))
                }
            }
    }

    private fun hit(a: Attack, player: Player, target: LivingEntity, from: Location,
        damageMultiplier: Double = 1.0): Boolean {
        if (!current(a, player) || a.attempted.size >= settings.maxAreaTargets || target.uniqueId in a.attempted ||
            !damage.eligible(player, target, a.cast) || center(target).distanceSquared(player.eyeLocation) > settings.range * settings.range ||
            !visible(player.eyeLocation, center(target)) ||
            !visible(from, center(target))) return false
        a.attempted += target.uniqueId
        return damage.hit(player, target, a.cast, a.tuning.power * damageMultiplier,
            a.tuning.vanillaDamage * damageMultiplier)
    }

    private fun sound(a: Attack, at: Location, impact: Boolean) {
        if (a !in attacks) return
        val sound = when (a.spell) {
            StaffSpell.GALE -> if (!impact) Sound.ENTITY_BREEZE_CHARGE
                else if (a.input.secondary) Sound.ENTITY_BREEZE_WIND_BURST else Sound.ENTITY_BREEZE_IDLE_GROUND
            StaffSpell.TIDE -> if (impact) Sound.ENTITY_PLAYER_SPLASH_HIGH_SPEED else Sound.ITEM_TRIDENT_RIPTIDE_1
            StaffSpell.NOVA -> if (impact) Sound.BLOCK_AZALEA_LEAVES_BREAK else Sound.BLOCK_ROOTED_DIRT_BREAK
            StaffSpell.REQUIEM -> if (impact) Sound.BLOCK_BELL_USE else Sound.ENTITY_EVOKER_PREPARE_SUMMON
            StaffSpell.OSSUARY -> if (impact) Sound.BLOCK_BONE_BLOCK_BREAK else Sound.ENTITY_WITHER_SKELETON_AMBIENT
            StaffSpell.LUNAR -> if (impact) Sound.BLOCK_AMETHYST_BLOCK_BREAK else Sound.BLOCK_BEACON_ACTIVATE
            else -> if (impact) Sound.BLOCK_AMETHYST_BLOCK_RESONATE else Sound.BLOCK_AMETHYST_BLOCK_CHIME
        }
        visuals.withOwner(a.casterId) { visuals.cue(at, sound, if (impact) .65f else .5f, if (impact) .85f else 1.15f) }
    }

    private fun current(a: Attack, player: Player) = a in attacks && ready(player) &&
        player.world == a.origin.world && damage.isCurrent(player, a.cast)
    private fun ready(player: Player) = player.isOnline && player.isValid && !player.isDead && player.gameMode != GameMode.SPECTATOR
    private fun remove(a: Attack) { attacks.remove(a); a.scenes.forEach(effects::remove) }

    companion object {
        val SPELLS = setOf(StaffSpell.LUNAR, StaffSpell.GALE, StaffSpell.TIDE, StaffSpell.REQUIEM,
            StaffSpell.PRISM, StaffSpell.NOVA, StaffSpell.OSSUARY)
        private const val GALE_RAM_TRAVEL_TICKS = 20.0
        private const val GALE_RAM_RELEASE_TICKS = 12
        private const val GALE_RAM_DAMAGE_TICKS = 4
        private const val GALE_RAM_HIT_WIDTH = 1.0
        private const val GALE_RAM_BURST_RADIUS = 2.3
        private const val TIDE_ORB_HIT_WIDTH = .68
        private const val TIDE_WAVE_TRAVEL_TICKS = 30.0
        private const val NOVA_ROOT_TRAVEL_TICKS = 30
        private const val REQUIEM_SECONDARY_DAMAGE_MULTIPLIER = .45
        private const val OSSUARY_SPIKE_START_TICK = 4
        private const val OSSUARY_SPIKE_STAGGER_TICKS = 2
        private const val OSSUARY_SPIKE_SCENE_TICKS = 22
        private const val OSSUARY_SPIKE_CONTACT_TICK = 6
        private const val LUNAR_SECONDARY_STRIKE_TICK = 16
        private const val LUNAR_SECONDARY_DAMAGE_END_TICK = 24
        private const val LUNAR_SECONDARY_RADIUS_BONUS = .7
        private const val MIN_GROUND_PATH_LENGTH = 0.75

        private fun ossuaryPrimaryRadius(charge: Double) = 2.75 + charge.coerceIn(0.0, 1.0) * .25
        private fun ossuarySecondaryRadius(charge: Double) = 5.5 + charge.coerceIn(0.0, 1.0) * .5
        private fun galeRamBurstRadius(a: Attack) = GALE_RAM_BURST_RADIUS + a.cast.visualCharge * .45
        private fun lunarSecondaryDamageRadius(a: Attack) = a.radius + LUNAR_SECONDARY_RADIUS_BONUS
    }
}

private fun smooth(value: Double): Double = value.takeIf(Double::isFinite)?.coerceIn(0.0, 1.0)?.let {
    it * it * (3.0 - 2.0 * it)
} ?: 0.0
