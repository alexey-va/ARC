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
import org.joml.Quaternionf
import org.joml.Vector3f
import ru.arc.core.LifecycleTaskScope
import java.util.UUID
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.math.tan

internal enum class StaffArsenalCastResult { ACCEPTED, NO_GROUND, BLOCKED, UNAVAILABLE }

/** Authored spell sequences. Each stage owns its collision timing and its matching native VFX. */
internal class StaffSpellArsenal(
    private val settings: StaffSpellSettings,
    private val damage: StaffSpellDamage,
    private val effects: StaffSpellDisplayEffects,
    private val visuals: StaffSpellVisuals,
    private val targeting: StaffSpellTargeting,
    private val rootTarget: (Player, LivingEntity, StaffSpellCast, Int) -> Boolean = { _, _, _, _ -> false },
    private val rootActive: (UUID, UUID) -> Boolean = { _, _ -> false },
    private val clearRoots: (UUID) -> Unit = {},
) : AutoCloseable {
    private data class RootCapture(val target: LivingEntity, val age: Int)
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
        var novaPaths: List<StaffRootBranch> = emptyList(),
        val novaCaptures: MutableList<RootCapture> = mutableListOf(),
        val novaWrapGroups: MutableSet<Int> = mutableSetOf(),
        val novaWrapScenes: MutableSet<UUID?> = mutableSetOf(),
        var prismScene: UUID? = null,
        var prismTargets: List<LivingEntity> = emptyList(),

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
        input: StaffCastInput, charge: Double = 0.0): StaffArsenalCastResult {
        if (closed || input.quick || spell !in SPELLS || !ready(player) || !damage.isCurrent(player, cast))
            return StaffArsenalCastResult.UNAVAILABLE
        if (spell == StaffSpell.NOVA) return castNova(player, cast, tuning, input, charge)
        if (spell == StaffSpell.GALE && input.secondary || spell == StaffSpell.TIDE && !input.secondary)
            return castAirProjectile(player, spell, cast, tuning, input, charge)
        val eye = player.eyeLocation
        val q = charge.takeIf(Double::isFinite)?.coerceIn(0.0, 1.0) ?: 0.0
        val forwardPath = spell == StaffSpell.TIDE || spell == StaffSpell.OSSUARY && !input.secondary
        val range = minOf(settings.range, if (spell == StaffSpell.OSSUARY && !input.secondary) 24.0 else 56.0)
        val casterCenteredRequiem = spell == StaffSpell.REQUIEM && input.secondary
        val selected = if (forwardPath || casterCenteredRequiem) null
            else targeting.aimed(player, range, settings.aimDegrees, cast).firstOrNull()
        if (!damage.isCurrent(player, cast)) return StaffArsenalCastResult.UNAVAILABLE
        val initialDirection = if (forwardPath || casterCenteredRequiem)
            eye.clone().apply { pitch = 0f }.direction else eye.direction
        val aimDirection = if (forwardPath) targeting.assistedDirection(player, spell, cast,
            direction = initialDirection, from = eye, range = range) else initialDirection
        val aimed = if (casterCenteredRequiem) player.location.clone()
            else selected?.let { staffVisibleTargetPoint(eye, it) } ?: targeting.rayEnd(eye, aimDirection, range)
        val point = targeting.groundBelow(aimed.clone().add(0.0, 2.0, 0.0), 12.0) ?: aimed.clone().subtract(0.0, 1.2, 0.0)
        if (!casterCenteredRequiem && !visible(eye, aimed)) return StaffArsenalCastResult.BLOCKED
        val forward = Vector(point.x - eye.x, 0.0, point.z - eye.z).apply {
            if (lengthSquared() < .01) { x = eye.direction.x; z = eye.direction.z }
            if (lengthSquared() < .01) z = 1.0
        }.normalize()
        val launchDistance = minOf(2.5, range * .25, eye.distance(aimed) * .35)
        val origin = targeting.rayEnd(eye, forward, launchDistance).subtract(0.0, 1.62, 0.0)
        val groundOrigin = targeting.groundBelow(origin.clone().add(0.0, 2.0, 0.0), 5.0) ?: origin
        val distance = point.toVector().subtract(groundOrigin.toVector()).dot(forward).coerceIn(0.0, range)
        if (!visible(eye, groundOrigin.clone().add(0.0, 1.0, 0.0))) return StaffArsenalCastResult.BLOCKED
        // Ground fronts keep their reach when looking down or past a nearby mob.
        // The shared block ray below still clips their complete path at cover.
        val intendedTravelEnd = if (forwardPath) eye.clone().add(forward.clone().multiply(range)).apply { y = groundOrigin.y }
            else groundOrigin.clone().add(forward.clone().multiply(distance)).apply { y = point.y }
        val pathSpell = spell == StaffSpell.TIDE || spell == StaffSpell.OSSUARY && !input.secondary
        val travelEnd = if (pathSpell) {
            val delta = intendedTravelEnd.toVector().subtract(groundOrigin.toVector())
            val pathLength = delta.length()
            if (!pathLength.isFinite() || pathLength < MIN_GROUND_PATH_LENGTH) return StaffArsenalCastResult.BLOCKED
            val clippedEnd = targeting.rayEnd(groundOrigin, delta.multiply(1.0 / pathLength), pathLength)
            if (!clippedEnd.x.isFinite() || !clippedEnd.y.isFinite() || !clippedEnd.z.isFinite() ||
                clippedEnd.distanceSquared(groundOrigin) < MIN_GROUND_PATH_LENGTH * MIN_GROUND_PATH_LENGTH)
                return StaffArsenalCastResult.BLOCKED
            clippedEnd
        } else intendedTravelEnd
        val pathDistance = travelEnd.toVector().subtract(groundOrigin.toVector()).dot(forward).coerceIn(0.0, range)
        val count = ceil(pathDistance / 2.0).toInt().coerceIn(1, 12)
        val nodes = if (spell == StaffSpell.OSSUARY && !input.secondary) (0 until count).mapNotNull { i ->
            val at = groundOrigin.clone().add(forward.clone().multiply(pathDistance * (i + 1) / count))
            val ground = targeting.groundBelow(at.clone().add(0.0, 2.0, 0.0), 6.0) ?: return@mapNotNull null
            ground.takeIf { visible(eye, it.clone().add(0.0, .8, 0.0)) }
        } else emptyList()
        if (spell == StaffSpell.OSSUARY && !input.secondary && nodes.isEmpty()) return StaffArsenalCastResult.NO_GROUND
        val attackPoint = if (casterCenteredRequiem)
            targeting.groundBelow(player.location.clone().add(0.0, 1.0, 0.0), 4.0) ?: player.location.clone()
        else point
        val attackOrigin = if (casterCenteredRequiem) attackPoint.clone() else groundOrigin
        val radius = when {
            spell == StaffSpell.OSSUARY && input.secondary -> ossuarySecondaryRadius(q)
            spell == StaffSpell.OSSUARY -> ossuaryPrimaryRadius(q)
            spell == StaffSpell.TIDE && input.secondary -> staffTideWaveHalfWidth(q)
            else -> 2.2 + q
        }
        val a = Attack(player.uniqueId, spell, input, cast.copy(visualCharge = q), tuning,
            attackOrigin, attackPoint, aimed, travelEnd, forward, nodes, radius,
            staffArsenalLifetime(spell, input.secondary)).apply {
            if (spell == StaffSpell.TIDE && input.secondary)
                travelPoint = groundOrigin.clone()
        }
        if (spell == StaffSpell.REQUIEM) a.requiemRayLimits = requiemRayLimits(a)
        admit(a)
        when (spell) {
            StaffSpell.LUNAR, StaffSpell.PRISM -> scene(a, a.point, a.duration)
            StaffSpell.GALE -> scene(a, a.point, a.duration, controllerDriven = true)
            StaffSpell.REQUIEM -> scene(a, a.point, a.duration)
            StaffSpell.TIDE -> scene(a, a.origin, a.duration, a.travelEnd, controllerDriven = true)
            StaffSpell.OSSUARY -> if (input.secondary) ossuaryScene(a, a.point, a.duration, 0)
            else -> Unit
        }
        frame(a, player)
        return StaffArsenalCastResult.ACCEPTED
    }

    private fun admit(a: Attack) {
        while (attacks.count { it.casterId == a.casterId } >= 2)
            remove(attacks.first { it.casterId == a.casterId }, releaseRoots = false)
        while (attacks.size >= 24) remove(attacks.first(), releaseRoots = false)
        attacks += a
        sound(a, a.origin, false)
    }

    private fun castNova(player: Player, cast: StaffSpellCast, tuning: StaffSpellTuning,
        input: StaffCastInput, charge: Double): StaffArsenalCastResult {
        val eye = player.eyeLocation
        val q = charge.takeIf(Double::isFinite)?.coerceIn(0.0, 1.0) ?: 0.0
        val forward = eye.clone().apply { pitch = 0f }.direction.normalize()
        val nearbyFloor = targeting.groundBelow(player.location.clone().add(0.0, 1.0, 0.0), 3.0)
            ?.takeIf { abs(it.y - player.location.y) <= 1.25 }
        val reach = minOf(settings.range, STAFF_NOVA_FAN_REACH)
        val projected = input.secondary || nearbyFloor == null
        val selected = if (projected) targeting.aimed(player, minOf(settings.range, 56.0),
            settings.aimDegrees, cast).firstOrNull() else null
        val aim = if (projected) selected?.let { staffVisibleTargetPoint(eye, it) }
            ?: targeting.rayEnd(eye, eye.direction, minOf(settings.range, 56.0))
            else nearbyFloor!!.clone().add(forward.clone().multiply(reach))
        val landing = if (projected) targeting.groundBelow(aim.clone().add(0.0, 1.25, 0.0), 64.0)
            ?: return StaffArsenalCastResult.NO_GROUND else nearbyFloor!!
        if (projected && !visible(eye, landing.clone().add(0.0, .4, 0.0))) return StaffArsenalCastResult.BLOCKED
        // In flight, seed the fan on the aimed ledge, with only as much run-up as its connected ground allows.
        val origin = if (!input.secondary && projected) staffRootSurfacePath(listOf(landing,
            landing.clone().subtract(forward.clone().multiply(3.0))), targeting).lastOrNull()
            ?.takeIf { visible(eye, it.clone().add(0.0, .4, 0.0)) } ?: landing
            else landing
        val point = if (input.secondary) landing else origin.clone().add(forward.clone().multiply(reach))
        val radius = (4.5 + q * .4) * cast.enchantments.radiusMultiplier
        val a = Attack(player.uniqueId, StaffSpell.NOVA, input, cast.copy(visualCharge = q), tuning,
            origin, point, aim, point, forward, emptyList(), radius,
            STAFF_NOVA_ATTACK_TICKS)
        val right = Vector(forward.z, 0.0, -forward.x)
        val anchor = if (input.secondary) point else origin
        val seed = cast.attackId.mostSignificantBits xor cast.attackId.leastSignificantBits
        val layout = if (input.secondary) staffNovaSnarePaths(radius, seed) else staffNovaFanPaths(reach, seed)
        val resolved = mutableListOf<StaffRootBranch>()
        layout.forEach { path ->
            val knots = path.points.map { p -> anchor.clone().add(right.clone().multiply(p.x.toDouble()))
                .add(forward.clone().multiply(p.z.toDouble())) }
            val ground = if (path.parentKey != null) {
                val parent = resolved.firstOrNull { it.key == path.parentKey } ?: return@forEach
                val wanted = localPoint(origin, knots.first())
                val junction = staffRootNearestPoint(parent, wanted) ?: return@forEach
                if (hypot((junction.x - wanted.x).toDouble(), (junction.z - wanted.z).toDouble()) > .2) return@forEach
                staffRootSurfacePath(listOf(origin.clone().add(junction.x.toDouble(), junction.y.toDouble(), junction.z.toDouble())) +
                    knots.drop(1), targeting)
            } else if (input.secondary) staffRootSurfacePath(listOf(anchor) + knots.reversed(), targeting).reversed()
                else staffRootSurfacePath(listOf(origin) + knots, targeting)
            if (ground.size >= 2) resolved += path.copy(points = ground.map { at -> localPoint(origin, at) })
        }
        a.novaPaths = resolved
        if (a.novaPaths.isEmpty()) return StaffArsenalCastResult.NO_GROUND
        admit(a)
        repeat(2) { pool ->
            a.scenes += effects.custom(a.casterId, a.spell, origin, STAFF_NOVA_FIELD_TICKS) { age ->
                val parts = if (input.secondary) staffNovaSnareParts(age, STAFF_NOVA_FIELD_TICKS, a.novaPaths)
                    else staffNovaFanParts(age, STAFF_NOVA_FIELD_TICKS, reach, a.novaPaths)
                parts.drop(pool * 48).take(48)
            }
        }
        frame(a, player)
        return StaffArsenalCastResult.ACCEPTED
    }

    private fun castAirProjectile(player: Player, spell: StaffSpell, cast: StaffSpellCast, tuning: StaffSpellTuning,
        input: StaffCastInput, charge: Double): StaffArsenalCastResult {
        val eye = player.eyeLocation
        val q = charge.takeIf(Double::isFinite)?.coerceIn(0.0, 1.0) ?: 0.0
        val range = minOf(settings.range, if (spell == StaffSpell.TIDE) STAFF_TIDE_PRIMARY_RANGE else 56.0)
        val direction = targeting.assistedDirection(player, spell, cast, eye.direction, eye, range)
        val origin = targeting.rayEnd(eye, direction, minOf(2.5, range * .25))
        if (origin.distanceSquared(eye) < .25) return StaffArsenalCastResult.BLOCKED
        val endpoint = eye.clone().add(direction.clone().multiply(range))
        val a = Attack(player.uniqueId, spell, input, cast.copy(visualCharge = q), tuning,
            origin, endpoint, endpoint, endpoint, direction, emptyList(), 2.2 + q,
            staffArsenalLifetime(spell, input.secondary)).apply { travelPoint = origin.clone() }
        admit(a)
        scene(a, origin, a.duration, endpoint, controllerDriven = true)
        frame(a, player)
        return StaffArsenalCastResult.ACCEPTED
    }

    fun cancel(casterId: UUID) = attacks.filter { it.casterId == casterId }.toList().forEach { remove(it) }
    override fun close() {
        if (closed) return
        closed = true
        tasks.close()
        attacks.toList().forEach { remove(it) }
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
            StaffSpell.GALE -> if (a.input.secondary) galeRam(a, player) else galeTornado(a, player)
            StaffSpell.LUNAR -> {
                particles(a, a.point, a.age, a.duration, radius = a.radius)
                if (a.age == LUNAR_SECONDARY_STRIKE_TICK) sound(a, a.point, true)
                if (a.input.secondary && a.age in LUNAR_SECONDARY_STRIKE_TICK..LUNAR_SECONDARY_DAMAGE_END_TICK)
                    area(a, player, a.point.clone().add(0.0, 1.3, 0.0), lunarSecondaryDamageRadius(a))
                else if (!a.input.secondary && a.age in 16..24)
                    area(a, player, a.point.clone().add(0.0, 1.3, 0.0), a.radius + .7)
            }
            StaffSpell.NOVA -> novaRoots(a, player)
            StaffSpell.REQUIEM -> requiemWave(a, player)
            StaffSpell.PRISM -> prism(a, player)
            StaffSpell.OSSUARY -> if (a.input.secondary) ossuaryStrike(a, player) else ossuarySpikes(a, player)
            else -> Unit
        }
    }

    private fun localPoint(origin: Location, at: Location) = Vector3f(
        (at.x - origin.x).toFloat(), (at.y - origin.y).toFloat(), (at.z - origin.z).toFloat())

    private fun novaRoots(a: Attack, player: Player) {
        val reach = minOf(settings.range, STAFF_NOVA_FAN_REACH)
        if (a.age <= STAFF_NOVA_CONTACT_TICK) {
            visuals.withOwner(a.casterId) {
                visuals.particles(a.origin, staffNovaFieldParticles(a.age, a.input.secondary, reach, a.novaPaths))
            }
        }
        val contact = if (a.input.secondary) a.age == STAFF_NOVA_CONTACT_TICK
            else a.age in (STAFF_NOVA_FAN_START_TICK + 2)..STAFF_NOVA_CONTACT_TICK
        if (!contact) return
        val front = staffNovaFanFront(a.age, reach)
        val previous = staffNovaFanFront(a.age - 2, reach)
        val center = if (a.input.secondary) a.point else a.origin
        val candidates = targeting.nearby(player, center.clone().add(0.0, .8, 0.0),
            if (a.input.secondary) a.radius / a.cast.enchantments.radiusMultiplier else reach + 1.0, a.cast)
            .filter { it.uniqueId !in a.attempted }
            .filter { target ->
                if (a.input.secondary) true else {
                    val offset = target.location.toVector().subtract(a.origin.toVector())
                    val z = offset.dot(a.forward)
                    val x = offset.x * a.forward.z - offset.z * a.forward.x
                    val body = maxOf(target.boundingBox.widthX, target.boundingBox.widthZ) * .5
                    val distance = hypot(x, z)
                    z >= -body && abs(x) <= z.coerceAtLeast(0.0) * tan(STAFF_NOVA_FAN_HALF_ANGLE) + body &&
                        distance - body <= front && (distance + body >= previous ||
                            a.age == STAFF_NOVA_FAN_START_TICK + 2 && distance <= .75 + body)
                }
            }.sortedBy { it.location.distanceSquared(a.origin) }
        var captured = false
        for (target in candidates) {
            if (!current(a, player) || a.attempted.size >= settings.maxAreaTargets.coerceIn(1, 16)) break
            val feet = localPoint(a.origin, target.location)
            val nearest = a.novaPaths.mapNotNull { staffRootNearestPoint(it, feet) }
                .filter { abs(it.y - feet.y) <= 1.5 }
                .minByOrNull { hypot((it.x - feet.x).toDouble(), (it.z - feet.z).toDouble()) } ?: continue
            val body = maxOf(target.boundingBox.widthX, target.boundingBox.widthZ) * .5
            if (hypot((nearest.x - feet.x).toDouble(), (nearest.z - feet.z).toDouble()) > 1.35 + body) continue
            val start = a.origin.clone().add(nearest.x.toDouble(), nearest.y.toDouble(), nearest.z.toDouble())
            val path = staffRootSurfacePath(listOf(start, target.location), targeting)
            if (path.isEmpty() || hypot(path.last().x - target.location.x, path.last().z - target.location.z) > .2 ||
                abs(path.last().y - target.location.y) > 1.5) continue
            val from = path.last().clone().add(0.0, .55, 0.0)
            if (!hit(a, player, target, from) || !current(a, player) ||
                !rootTarget(player, target, a.cast, STAFF_NOVA_HOLD_TICKS)) continue
            a.novaCaptures += RootCapture(target, a.age)
            captured = true
            val group = (a.novaCaptures.size - 1) / 8
            if (a.novaWrapGroups.add(group)) {
                val born = a.age
                val scene = effects.custom(a.casterId, a.spell, a.origin,
                    STAFF_NOVA_ATTACK_TICKS - born) { sceneAge ->
                    val wraps = a.novaCaptures.drop(group * 8).take(8)
                        .filter { rootActive(it.target.uniqueId, a.cast.attackId) }.map { capture ->
                            val entity = capture.target
                            StaffRootWrap(entity.uniqueId.toString(), localPoint(a.origin, entity.location),
                                (maxOf(entity.boundingBox.widthX, entity.boundingBox.widthZ) * .5 + .18).coerceIn(.45, 1.6),
                                entity.height.coerceIn(.8, 3.5), capture.age - born)
                        }
                    staffNovaWrapParts(sceneAge, STAFF_NOVA_HOLD_TICKS, wraps)
                }
                a.scenes += scene
                a.novaWrapScenes += scene
            }
        }
        if (captured || a.input.secondary) sound(a, if (a.input.secondary) a.point else
            a.origin.clone().add(a.forward.clone().multiply(front)), true)
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

        val from = tideFlightPoint(a, a.age - 2)
        val intendedAt = tideFlightPoint(a, a.age)
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
            if (localAge >= 0 && a.born.add(index)) ossuaryScene(a, at, OSSUARY_SPIKE_SCENE_TICKS, index)
            if (localAge in 0 until OSSUARY_SPIKE_SCENE_TICKS) particles(a, at, localAge,
                OSSUARY_SPIKE_SCENE_TICKS, secondary = false, radius = radius)
            if (localAge == OSSUARY_SPIKE_CONTACT_TICK && (index % 3 == 0 || index == a.nodes.lastIndex))
                sound(a, at, true)
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

    private fun ossuaryScene(a: Attack, at: Location, duration: Int, index: Int) {
        val shape = staffOssuaryShape(a.cast.attackId, index)
        val rotation = Quaternionf().rotationTo(Vector3f(0f, 0f, 1f),
            Vector3f(a.forward.x.toFloat(), a.forward.y.toFloat(), a.forward.z.toFloat()))
        a.scenes += effects.custom(a.casterId, a.spell, at, duration) { age ->
            staffOssuaryRibParts(age, duration, a.radius, a.input.secondary, a.cast.visualCharge, shape).map { part ->
                part.copy(center = rotation.transform(Vector3f(part.center)), rotation = Quaternionf(rotation).mul(part.rotation))
            }
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
                if (visible(frontOrigin, target))
                    hit(a, player, target, from, if (secondary)
                        1.35 / STAFF_REQUIEM_SECONDARY_CLANG_TICKS.size else 1.0 / STAFF_REQUIEM_PRIMARY_CLANG_TICKS.size)
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
        if (a.input.secondary) { prismRefraction(a, player); return }
        particles(a, a.point, a.age, a.duration)
        val emitter = a.point.clone().add(0.0, 2.6, 0.0)
        if (a.age == 10) {
            a.prismTargets = targeting.nearby(player, emitter, 12.0, a.cast)
                .filter { visible(emitter, it) && visible(player.eyeLocation, it) }
                .sortedBy { center(it).distanceSquared(emitter) }.take(3)
            if (!current(a, player)) return
            if (a.prismTargets.isEmpty()) a.rays = (-1..1).map { lane ->
                emitter.clone() to targeting.rayEnd(emitter, a.forward.clone().rotateAroundY(lane * .55), 9.0)
            }
            sound(a, emitter, true)
        }
        if (a.age !in 10..24) return
        if (a.age <= 16 && a.prismTargets.isNotEmpty()) {
            a.rays = a.prismTargets.mapNotNull { target ->
                if (!damage.eligible(player, target, a.cast) || target.world != emitter.world ||
                    center(target).distanceSquared(emitter) > 12.0 * 12.0) return@mapNotNull null
                staffVisibleTargetPoint(emitter, target, player.eyeLocation)?.let { emitter.clone() to it }
            }
        }
        val front = ((a.age - 10) / 6.0).coerceIn(0.0, 1.0)
        a.rays.forEach { (from, to) ->
            if (!current(a, player)) return
            val ray = to.toVector().subtract(from.toVector())
            if (front <= 0.0 || ray.lengthSquared() < .01) return@forEach
            val end = targeting.rayEnd(from, ray, ray.length() * front)
            val visibleFront = from.distance(end) / ray.length()
            visuals.withOwner(a.casterId) { visuals.particles(from,
                staffPrismBeam(ray, visibleFront, a.cast.visualCharge, a.age)) }
            targeting.lineTargets(player, from, end, .55, a.cast).forEach {
                hit(a, player, it, from, PRISM_PRIMARY_DAMAGE_SCALE)
            }
        }
    }

    private fun prismRefraction(a: Attack, player: Player) {
        if (a.age < 10) { particles(a, a.point, a.age, a.duration); return }
        if (a.age == 10) {
            val emitter = a.origin.clone().add(0.0, 1.65, 0.0)
            val right = Vector(a.forward.z, 0.0, -a.forward.x)
            val requestedMirror = a.point.clone().add(0.0, 1.5, 0.0)
                .subtract(a.forward.clone().multiply(2.2)).add(right.multiply(1.8))
            val firstDelta = requestedMirror.toVector().subtract(emitter.toVector())
            val first = targeting.rayEnd(emitter, firstDelta, firstDelta.length())
            val points = mutableListOf(emitter, first)
            val remaining = targeting.nearby(player, a.point.clone().add(0.0, 1.0, 0.0), 9.0, a.cast)
                .filter { center(it).distanceSquared(player.eyeLocation) <= settings.range * settings.range }
                .toMutableList()
            repeat(minOf(6, settings.maxAreaTargets.coerceIn(1, 16))) {
                val from = points.last()
                val next = remaining.filter { visible(from, it) }
                    .minByOrNull { center(it).distanceSquared(from) } ?: return@repeat
                remaining.remove(next)
                val aim = staffVisibleTargetPoint(from, next, player.eyeLocation) ?: return@repeat
                val direction = aim.toVector().subtract(from.toVector())
                if (direction.lengthSquared() < .01) return@repeat
                // A mirror just beyond each victim lets the same ray pierce the body before turning.
                points += targeting.rayEnd(from, direction, direction.length() + .85)
            }
            if (points.size == 2) points += targeting.rayEnd(first, a.forward, 8.0)
            a.rays = points.zipWithNext().filter { (from, to) -> from.distanceSquared(to) > .01 }
            if (!current(a, player) || a.rays.isEmpty()) return
            a.prismScene = effects.custom(a.casterId, a.spell, a.origin, a.duration - 10) { age ->
                prismRefractionFrame(a, age)
            }
            a.scenes += a.prismScene
            sound(a, first, true)
        }
        if (a.rays.isEmpty() || a.age > 32) return
        val connected = mutableListOf<Pair<Location, Location>>()
        for (segment in a.rays) {
            val (from, to) = segment
            val delta = to.toVector().subtract(from.toVector())
            val end = targeting.rayEnd(from, delta, delta.length())
            if (from.distanceSquared(end) + .001 < from.distanceSquared(to)) {
                if (from.distanceSquared(end) > .01) connected += from to end
                // Once cover severs the route, later mirrors cannot carry detached damage or light.
                a.rays = connected
                effects.updateCustom(a.prismScene, a.origin, prismRefractionFrame(a, a.age - 10))
                break
            }
            connected += segment
        }
        val total = a.rays.sumOf { (from, to) -> from.distance(to) }
        val travel = total * ((a.age - 10) / 14.0).coerceIn(0.0, 1.0)
        var passed = 0.0
        a.rays.forEach { (from, to) ->
            val ray = to.toVector().subtract(from.toVector())
            val length = ray.length()
            val front = ((travel - passed) / length).coerceIn(0.0, 1.0)
            passed += length
            if (front <= 0.0 || !current(a, player)) return@forEach
            // Bound the whole route's particle budget; displays carry its continuous beam.
            val samples = staffPrismBeam(ray, front, a.cast.visualCharge, a.age)
            visuals.withOwner(a.casterId) { visuals.particles(from, samples.filterIndexed { index, _ -> index % 4 == 0 }) }
            val requested = from.clone().add(ray.clone().multiply(front))
            val end = targeting.rayEnd(from, ray, from.distance(requested))
            targeting.lineTargets(player, from, end, .70, a.cast).forEach { target ->
                hit(a, player, target, from, PRISM_SECONDARY_DAMAGE_SCALE)
            }
        }
    }

    private fun prismRefractionFrame(a: Attack, age: Int): List<StaffDisplayPart> {
        val points = a.rays.firstOrNull()?.let { listOf(it.first) + a.rays.map { ray -> ray.second } }
            ?: return emptyList()
        return staffPrismRefractionParts(age, a.duration - 10, points.map { localPoint(a.origin, it) })
    }

    private fun along(a: Attack, progress: Double): Location {
        val t = progress.takeIf(Double::isFinite)?.coerceIn(0.0, 1.0) ?: 0.0
        val eased = t * t * (3.0 - 2.0 * t)
        val delta = a.travelEnd.toVector().subtract(a.origin.toVector())
        return a.origin.clone().add(delta.multiply(eased))
    }

    private data class GroundPathStep(val from: Location, val to: Location, val blocked: Boolean)

    private fun galeTornado(a: Attack, player: Player) {
        val previous = a.travelPoint?.clone() ?: a.point.clone()
        val right = Vector(a.forward.z, 0.0, -a.forward.x)
        val relative = previous.toVector().subtract(a.point.toVector())
        val requested = staffGaleWanderStep(Vector3f(relative.dot(right).toFloat(), 0f,
            relative.dot(a.forward).toFloat()), a.age, a.cast.visualCharge)
        val desired = a.point.clone().add(right.multiply(requested.x.toDouble()))
            .add(a.forward.clone().multiply(requested.z.toDouble())).apply { y = previous.y }
        val delta = desired.toVector().subtract(previous.toVector())
        val bodyFrom = previous.clone().add(0.0, 1.1, 0.0)
        val clipped = if (delta.lengthSquared() > .0001)
            targeting.rayEnd(bodyFrom, delta, delta.length()).subtract(0.0, 1.1, 0.0) else previous
        val floor = targeting.groundBelow(clipped.clone().add(0.0, 1.0, 0.0), 3.0)
        val at = floor?.takeIf { abs(it.y - previous.y) <= .65 &&
            visible(bodyFrom, it.clone().add(0.0, 1.1, 0.0)) } ?: previous
        a.travelPoint = at.clone()
        a.scenes.firstOrNull()?.let { effects.move(it, at); effects.synchronize(it, a.age) }
        particles(a, at, a.age, a.duration, radius = a.radius)
        if (a.age in 6..42 && (a.age - 6) % 8 == 0) visuals.withOwner(a.casterId) {
            visuals.cue(at.clone().add(0.0, 1.2, 0.0), Sound.ITEM_ELYTRA_FLYING,
                .72f, .72f + (a.age % 16) * .018f)
        }
        if (a.age == 8) sound(a, at, true)
        if (a.age in 8..44) {
            val body = at.clone().add(0.0, 1.1, 0.0)
            val radius = 2.5 + a.cast.visualCharge * .45
            sweptContact(a, player, bodyFrom, body, radius, lift = .2)
            area(a, player, body, radius, lift = .2)
        }
    }

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
        val ray = staffBlockHit(rayFrom, delta, distance, ignoreVegetation = true)
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
        val from = a.travelPoint?.clone() ?: a.origin.clone()
        val requested = along(a, progress)
        val delta = requested.toVector().subtract(from.toVector())
        val clipped = if (delta.lengthSquared() > .0001) targeting.rayEnd(from, delta, delta.length()) else from
        val step = GroundPathStep(from, clipped, clipped.distanceSquared(requested) > .0001)
        val at = step.to
        a.travelPoint = at.clone()
        val burstRadius = galeRamBurstRadius(a)
        particles(a, at, a.age, a.duration, radius = burstRadius)
        a.scenes.firstOrNull()?.let { id ->
            effects.move(id, at)
            effects.synchronize(id, a.age.coerceAtLeast(0))
        }

        if (a.age >= 6 && progress > 0.0) {
            val fromBody = step.from
            val toBody = at
            val target = targeting.lineTargets(player, fromBody, toBody, GALE_RAM_HIT_WIDTH, a.cast).firstOrNull()
            if (target != null) {
                val contact = airContact(fromBody, toBody, target, GALE_RAM_HIT_WIDTH, a.cast) ?: at
                if (hit(a, player, target, fromBody))
                    target.velocity = target.velocity.apply { y = maxOf(y, .38) }
                if (!current(a, player)) { remove(a); return }
                beginGaleRamRelease(a, player, contact)
                return
            }
        }
        if (step.blocked || progress >= 1.0) beginGaleRamRelease(a, player, at)
    }

    private fun airContact(from: Location, to: Location, target: LivingEntity,
        width: Double, cast: StaffSpellCast): Location? {
        val delta = to.toVector().subtract(from.toVector())
        val distance = delta.length()
        if (!distance.isFinite() || distance < .001) return null
        val direction = delta.multiply(1.0 / distance)
        val hit = target.boundingBox.expand(width * cast.enchantments.radiusMultiplier)
            .rayTrace(from.toVector(), direction, distance)?.hitPosition ?: return null
        return hit.toLocation(from.world)
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
            if (impactRadius > .05) area(a, player, at, impactRadius)
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
        val airRotation = if (a.spell == StaffSpell.GALE && secondary || a.spell == StaffSpell.TIDE && !secondary)
            Quaternionf().rotationTo(Vector3f(0f, 0f, 1f), Vector3f(a.forward.x.toFloat(),
                a.forward.y.toFloat(), a.forward.z.toFloat())) else null
        val samples = admitted.map { sample ->
            val p = sample.offset
            val offset = if (airRotation != null) airRotation.transform(Vector3f(p.x.toFloat(), p.y.toFloat(), p.z.toFloat()))
                .let { Vector(it.x.toDouble(), it.y.toDouble(), it.z.toDouble()) }
                else right.clone().multiply(p.x).add(Vector(0.0, p.y, 0.0)).add(a.forward.clone().multiply(p.z))
            sample.copy(offset = offset)
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
            !visible(player.eyeLocation, target) ||
            !visible(from, target)) return false
        a.attempted += target.uniqueId
        return damage.hit(player, target, a.cast, a.tuning.power * damageMultiplier,
            a.tuning.vanillaDamage * damageMultiplier)
    }

    private fun sound(a: Attack, at: Location, impact: Boolean) {
        if (a !in attacks) return
        val sound = when (a.spell) {
            StaffSpell.GALE -> if (!impact) Sound.ENTITY_BREEZE_CHARGE
                else Sound.ENTITY_BREEZE_WIND_BURST
            StaffSpell.TIDE -> if (impact) Sound.ENTITY_PLAYER_SPLASH_HIGH_SPEED else Sound.ITEM_TRIDENT_RIPTIDE_1
            StaffSpell.NOVA -> if (impact) Sound.BLOCK_AZALEA_LEAVES_BREAK else Sound.BLOCK_ROOTED_DIRT_BREAK
            StaffSpell.REQUIEM -> if (impact) Sound.BLOCK_BELL_USE else Sound.BLOCK_BELL_RESONATE
            StaffSpell.OSSUARY -> if (impact) Sound.BLOCK_BONE_BLOCK_BREAK else Sound.ENTITY_WITHER_SKELETON_AMBIENT
            StaffSpell.LUNAR -> if (impact) Sound.BLOCK_AMETHYST_BLOCK_BREAK else Sound.BLOCK_AMETHYST_BLOCK_RESONATE
            else -> if (impact) Sound.BLOCK_AMETHYST_BLOCK_RESONATE else Sound.BLOCK_AMETHYST_BLOCK_CHIME
        }
        visuals.withOwner(a.casterId) {
            // bell.use has a native sample gain of twelve; its pulse must not drown every other staff.
            val volume = when {
                a.spell == StaffSpell.REQUIEM && impact -> .085f
                a.spell in setOf(StaffSpell.GALE, StaffSpell.OSSUARY) -> if (impact) .95f else .80f
                impact -> .65f
                else -> .5f
            }
            visuals.cue(at, sound, volume, if (impact) .85f else 1.15f)
            if (impact) when (a.spell) {
                StaffSpell.GALE -> visuals.cue(at, Sound.ENTITY_BREEZE_SHOOT, .72f, .60f)
                StaffSpell.OSSUARY -> visuals.cue(at, Sound.ENTITY_SKELETON_HURT, .72f, .58f)
                StaffSpell.TIDE -> visuals.cue(at, Sound.BLOCK_BUBBLE_COLUMN_UPWARDS_AMBIENT, .32f, 1.3f)
                StaffSpell.NOVA -> visuals.cue(at, Sound.BLOCK_ROOTED_DIRT_BREAK, .3f, .7f)
                StaffSpell.LUNAR -> visuals.cue(at, Sound.ENTITY_PLAYER_ATTACK_SWEEP, .38f, .72f)
                StaffSpell.PRISM -> visuals.cue(at, Sound.BLOCK_GLASS_BREAK, .26f, 1.65f)
                else -> Unit
            }
        }
    }

    private fun current(a: Attack, player: Player) = a in attacks && ready(player) &&
        player.world == a.origin.world && damage.isCurrent(player, a.cast)
    private fun ready(player: Player) = player.isOnline && player.isValid && !player.isDead && player.gameMode != GameMode.SPECTATOR
    private fun remove(a: Attack, releaseRoots: Boolean = true) {
        attacks.remove(a)
        a.scenes.filter { releaseRoots || it !in a.novaWrapScenes }.forEach(effects::remove)
        // Eviction ends only the attack controller. Root controls and wraps retain their existing
        // bounded owners; Module cancellation clears Conjurations and DisplayEffects together.
        if (a.spell == StaffSpell.NOVA && releaseRoots) clearRoots(a.cast.attackId)
    }

    companion object {
        private const val PRISM_PRIMARY_DAMAGE_SCALE = 1.20
        private const val PRISM_SECONDARY_DAMAGE_SCALE = 1.25
        val SPELLS = setOf(StaffSpell.LUNAR, StaffSpell.GALE, StaffSpell.TIDE, StaffSpell.REQUIEM,
            StaffSpell.PRISM, StaffSpell.NOVA, StaffSpell.OSSUARY)
        private const val GALE_RAM_TRAVEL_TICKS = 20.0
        private const val GALE_RAM_RELEASE_TICKS = 12
        private const val GALE_RAM_DAMAGE_TICKS = 4
        private const val GALE_RAM_HIT_WIDTH = 1.0
        private const val GALE_RAM_BURST_RADIUS = 2.3
        private const val TIDE_ORB_HIT_WIDTH = .68
        private const val TIDE_WAVE_TRAVEL_TICKS = 30.0
        private const val OSSUARY_SPIKE_START_TICK = 4
        private const val OSSUARY_SPIKE_STAGGER_TICKS = 2
        private const val OSSUARY_SPIKE_SCENE_TICKS = 22
        private const val OSSUARY_SPIKE_CONTACT_TICK = 6
        private const val LUNAR_SECONDARY_STRIKE_TICK = 16
        private const val LUNAR_SECONDARY_DAMAGE_END_TICK = 24
        private const val LUNAR_SECONDARY_RADIUS_BONUS = .7
        private const val MIN_GROUND_PATH_LENGTH = 0.75

        private fun ossuaryPrimaryRadius(charge: Double) = 3.4 + charge.coerceIn(0.0, 1.0) * .35
        private fun ossuarySecondaryRadius(charge: Double) = 5.5 + charge.coerceIn(0.0, 1.0) * .5
        private fun galeRamBurstRadius(a: Attack) = GALE_RAM_BURST_RADIUS + a.cast.visualCharge * .45
        private fun lunarSecondaryDamageRadius(a: Attack) = a.radius + LUNAR_SECONDARY_RADIUS_BONUS
    }
}

private fun smooth(value: Double): Double = value.takeIf(Double::isFinite)?.coerceIn(0.0, 1.0)?.let {
    it * it * (3.0 - 2.0 * it)
} ?: 0.0
