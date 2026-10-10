package ru.arc.staffspells

import org.bukkit.Bukkit
import org.bukkit.GameMode
import org.bukkit.Location
import org.bukkit.entity.LivingEntity
import org.bukkit.entity.Player
import org.bukkit.util.Vector
import ru.arc.core.LifecycleTaskScope
import java.util.UUID
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/** Bounded motion and impact owner for the five added staves and the reworked Nova. */
internal class StaffSpellArsenal(
    private val settings: StaffSpellSettings,
    private val damage: StaffSpellDamage,
    private val effects: StaffSpellDisplayEffects,
    private val visuals: StaffSpellVisuals,
    private val targeting: StaffSpellTargeting,
) : AutoCloseable {
    private enum class Path { LINE, ARC, SEEK, RETURN, ORBIT_RELEASE, SWEEP, PIERCE, RICOCHET, TORNADO }

    private data class Shot(
        var position: Location,
        val source: Location,
        var direction: Vector,
        val initialDirection: Vector,
        var target: LivingEntity?,
        var point: Location,
        val path: Path,
        val speed: Double,
        val maxDistance: Double,
        val width: Double,
        val impactRadius: Double,
        val auraRadius: Double,
        val pull: Double,
        val push: Double,
        val lift: Double,
        val delay: Int,
        val turnAfter: Int,
        val orbitTicks: Int,
        val orbitRadius: Double,
        val lane: Int,
        var bounces: Int,
        val maxTargets: Int,
        val visualId: UUID?,
        val attemptedTargets: MutableSet<UUID> = linkedSetOf(),
        var age: Int = 0,
        var travelled: Double = 0.0,
        var returning: Boolean = false,
        var active: Boolean = true,
    )

    private data class Field(
        val center: Location,
        val radius: Double,
        val delay: Int,
        val duration: Int,
        val interval: Int,
        val pulses: Int,
        val expanding: Boolean,
        val maxHitsPerTarget: Int,
        val pull: Double,
        val lift: Double,
        val visualId: UUID?,
        var started: Boolean = false,
        var pulseCount: Int = 0,
        var previousFront: Double = 0.0,
        var complete: Boolean = false,
    )

    private data class Attack(
        val casterId: UUID,
        val worldId: UUID,
        val spell: StaffSpell,
        val input: StaffCastInput,
        val cast: StaffSpellCast,
        val tuning: StaffSpellTuning,
        val chargeScale: Double,
        val reach: Double,
        val maxTargets: Int,
        val shots: MutableList<Shot> = mutableListOf(),
        val attempts: MutableMap<UUID, Int> = linkedMapOf(),
        var field: Field? = null,
        var age: Int = 0,
    )

    private val tasks = LifecycleTaskScope()
    private val attacks = mutableListOf<Attack>()
    private var started = false
    private var closed = false

    fun start() {
        check(!closed) { "Staff spell arsenal is closed" }
        if (started) return
        started = true
        tasks.runTimer(1, 1, ::tick)
    }

    fun cast(
        player: Player,
        spell: StaffSpell,
        cast: StaffSpellCast,
        tuning: StaffSpellTuning,
        input: StaffCastInput,
        charge: Double = 0.0,
    ) {
        if (closed || !ready(player) || player.uniqueId != cast.casterId || spell !in ARSENAL_SPELLS ||
            !damage.isCurrent(player, cast)) return
        val chargeValue = if (input.quick) 0.0 else charge.takeIf(Double::isFinite)?.coerceIn(0.0, 1.0) ?: 0.0
        val reach = settings.range * (0.72 + chargeValue * 0.28)
        val eye = player.eyeLocation
        val forward = eye.direction.normalize()
        val right = horizontalRight(forward)
        val targets = targeting.aimed(player, settings.range, settings.aimDegrees, cast)
        if (!damage.isCurrent(player, cast)) return
        val selected = targets.firstOrNull()
        val point = selected?.let(::center) ?: targeting.aimPoint(player)
        val attack = Attack(player.uniqueId, player.world.uid, spell, input, cast, tuning,
            0.62 + chargeValue * 0.38, reach, settings.maxAreaTargets.coerceIn(1, 16))
        while (attacks.count { it.casterId == player.uniqueId } >= MAX_ATTACKS_PER_CASTER)
            removeAttack(attacks.first { it.casterId == player.uniqueId })
        while (attacks.size >= MAX_ATTACKS) removeAttack(attacks.first())
        attacks += attack

        when (spell) {
            StaffSpell.LUNAR -> lunar(attack, eye, forward, right, selected, point)
            StaffSpell.GALE -> gale(attack, eye, forward, selected, point)
            StaffSpell.TIDE -> tide(attack, eye, forward, selected, point)
            StaffSpell.REQUIEM -> requiem(attack, player, eye, forward, targets, point)
            StaffSpell.PRISM -> prism(attack, player, eye, forward, targets, point)
            StaffSpell.NOVA -> nova(attack, eye, forward, targets, point)
            else -> removeAttack(attack)
        }
        if (attack in attacks) arcane(attack, eye, 0, attack.field?.radius ?: 1.0)
    }

    fun cancel(casterId: UUID) {
        attacks.filter { it.casterId == casterId }.toList().forEach(::removeAttack)
    }

    override fun close() {
        if (closed) return
        closed = true
        tasks.close()
        attacks.toList().forEach(::removeAttack)
    }

    private fun lunar(a: Attack, eye: Location, dir: Vector, right: Vector,
        target: LivingEntity?, point: Location) {
        when (a.input) {
            StaffCastInput.PRIMARY -> shot(a, eye, dir, target, point, Path.RETURN, 1.15, a.reach * 1.65,
                0.42, turnAfter = maxOf(4, (a.reach / 2.8).toInt()))
            StaffCastInput.SECONDARY -> shot(a, eye, dir, target, point, Path.ORBIT_RELEASE, 1.3,
                a.reach * 1.25, 0.34, orbitTicks = 8, orbitRadius = 1.35)
            StaffCastInput.QUICK -> shot(a, eye, dir, target, point, Path.LINE, 2.4, a.reach * 0.72,
                0.24, impactRadius = 0.8)
            StaffCastInput.QUICK_SECONDARY -> fan(a, eye, dir, right, target, point, Path.PIERCE,
                count = 2, angle = 0.18, speed = 1.75, width = 0.24, range = a.reach * 0.9)
        }
    }

    private fun gale(a: Attack, eye: Location, dir: Vector, target: LivingEntity?, point: Location) {
        when (a.input) {
            StaffCastInput.PRIMARY -> shot(a, eye, dir, null, point, Path.TORNADO, 0.85, a.reach,
                0.45, auraRadius = 1.8, pull = 0.34)
            StaffCastInput.SECONDARY -> field(a, target?.let(::center) ?: point, 3.2,
                delay = 4, duration = 16, interval = 6, pulses = 3, expanding = false,
                maxHitsPerTarget = 2, pull = 0.36)
            StaffCastInput.QUICK -> shot(a, eye, dir, target, point, Path.LINE, 2.8, a.reach * 0.8,
                0.3, push = 0.58)
            StaffCastInput.QUICK_SECONDARY -> field(a, eye, 2.4, delay = 0, duration = 8,
                interval = 4, pulses = 2, expanding = false, maxHitsPerTarget = 2,
                pull = 0.42, lift = 0.72)
        }
    }

    private fun tide(a: Attack, eye: Location, dir: Vector, target: LivingEntity?, point: Location) {
        when (a.input) {
            StaffCastInput.PRIMARY -> shot(a, eye, dir.clone().add(Vector(0.0, 0.28, 0.0)).normalize(), target,
                point, Path.ARC, 1.12, a.reach, 0.34, impactRadius = 2.0, lift = 0.38)
            StaffCastInput.SECONDARY -> field(a,
                targeting.groundBelow(point.clone().add(0.0, 0.2, 0.0), 16.0) ?: point,
                3.6, delay = 8, duration = 10, interval = 5, pulses = 2,
                expanding = true, maxHitsPerTarget = 1, lift = 0.65)
            StaffCastInput.QUICK -> shot(a, eye, dir, target, point, Path.LINE, 2.6, a.reach * 0.75,
                0.24, impactRadius = 1.0)
            StaffCastInput.QUICK_SECONDARY -> fan(a, eye, dir, horizontalRight(dir), target, point,
                Path.ARC, count = 3, angle = 0.22, speed = 1.5, width = 0.25,
                range = a.reach * 0.8, delayStep = 2, impactRadius = 1.25)
        }
    }

    private fun requiem(a: Attack, p: Player, eye: Location, dir: Vector,
        targets: List<LivingEntity>, point: Location) {
        when (a.input) {
            StaffCastInput.PRIMARY -> repeat(5) { index ->
                shot(a, eye, dir, targets.getOrNull(index) ?: targets.firstOrNull(), point, Path.SEEK,
                    1.28, a.reach, 0.3, delay = index * 2, impactRadius = 1.0)
            }
            StaffCastInput.SECONDARY -> field(a, targets.firstOrNull()?.let(::center) ?: point,
                4.2, delay = 10, duration = 12, interval = 6, pulses = 2,
                expanding = true, maxHitsPerTarget = 2, pull = 0.18)
            StaffCastInput.QUICK -> shot(a, eye, dir, targets.firstOrNull(), point, Path.PIERCE,
                2.75, a.reach * 0.9, 0.34)
            StaffCastInput.QUICK_SECONDARY -> fan(a, eye, dir, horizontalRight(dir), targets.firstOrNull(),
                point, Path.SWEEP, count = 3, angle = 0.75, speed = 1.55, width = 0.3,
                range = a.reach * 0.72, delayStep = 1, impactRadius = 0.85)
        }
    }

    private fun prism(a: Attack, p: Player, eye: Location, dir: Vector,
        targets: List<LivingEntity>, point: Location) {
        when (a.input) {
            StaffCastInput.PRIMARY -> fan(a, eye, dir, horizontalRight(dir), targets.firstOrNull(), point,
                Path.PIERCE, count = 3, angle = 0.12, speed = 1.9, width = 0.16,
                range = a.reach, maxTargetsPerShot = 4)
            StaffCastInput.SECONDARY -> shot(a, eye, dir, targets.firstOrNull(), point, Path.RICOCHET,
                1.8, a.reach, 0.32, bounces = 2, impactRadius = 0.65)
            StaffCastInput.QUICK -> shot(a, eye, dir, targets.firstOrNull(), point, Path.LINE,
                3.0, a.reach * 0.74, 0.22)
            StaffCastInput.QUICK_SECONDARY -> fan(a, eye, dir, horizontalRight(dir), targets.firstOrNull(),
                point, Path.PIERCE, count = 5, angle = 0.28, speed = 2.0, width = 0.18,
                range = a.reach * 0.82, impactRadius = 0.55)
        }
    }

    private fun nova(a: Attack, eye: Location, dir: Vector, targets: List<LivingEntity>, point: Location) {
        val right = horizontalRight(dir)
        when (a.input) {
            StaffCastInput.PRIMARY -> shot(a, eye, dir, targets.firstOrNull(), point, Path.SWEEP,
                1.2, a.reach, 0.34, impactRadius = 1.6)
            StaffCastInput.SECONDARY -> field(a, targets.firstOrNull()?.let(::center) ?: point,
                4.4, delay = 6, duration = 12, interval = 8, pulses = 1,
                expanding = true, maxHitsPerTarget = 1)
            StaffCastInput.QUICK -> shot(a, eye, dir, targets.firstOrNull(), point, Path.LINE,
                3.2, a.reach * 0.72, 0.2, impactRadius = 0.55)
            StaffCastInput.QUICK_SECONDARY -> repeat(3) { index ->
                shot(a, eye, dir.clone().rotateAroundY((index - 1) * 0.24), targets.getOrNull(index), point,
                    Path.ORBIT_RELEASE, 1.7, a.reach * 0.82, 0.22, orbitTicks = 5,
                    orbitRadius = 1.5, lane = index, impactRadius = 0.9)
            }
        }
    }

    private fun fan(
        a: Attack, from: Location, direction: Vector, right: Vector, target: LivingEntity?, point: Location,
        path: Path, count: Int, angle: Double, speed: Double, width: Double, range: Double,
        delayStep: Int = 0, impactRadius: Double = 0.0, maxTargetsPerShot: Int = 1,
    ) {
        repeat(count) { index ->
            val caster = Bukkit.getPlayer(a.casterId) ?: return
            if (!current(a, caster)) return
            val lane = index - (count - 1) / 2.0
            val spread = angle * lane
            val ray = direction.clone().rotateAroundY(spread)
            val selected = if (count == 3) targeting.aimed(
                caster, settings.range, settings.aimDegrees, a.cast).getOrNull(index) ?: target else target
            if (!current(a, caster)) return
            shot(a, from, ray, selected, point, path, speed, range, width,
                delay = index * delayStep, impactRadius = impactRadius, lane = index,
                maxTargetsPerShot = maxTargetsPerShot)
        }
    }

    private fun shot(
        a: Attack, from: Location, direction: Vector, target: LivingEntity?, point: Location, path: Path,
        speed: Double, range: Double, width: Double, delay: Int = 0, turnAfter: Int = 0,
        orbitTicks: Int = 0, orbitRadius: Double = 0.0, lane: Int = 0,
        impactRadius: Double = 0.0, auraRadius: Double = 0.0, pull: Double = 0.0,
        push: Double = 0.0, lift: Double = 0.0, bounces: Int = 0, maxTargetsPerShot: Int = 1,
    ) {
        val player = Bukkit.getPlayer(a.casterId) ?: return
        if (a.shots.size >= MAX_SHOTS_PER_CAST || !current(a, player)) return
        val dir = direction.clone().takeIf { it.lengthSquared() > 1.0e-8 }?.normalize() ?: Vector(0.0, 0.0, 1.0)
        val speedScale = 0.82 + a.chargeScale * 0.28
        val realSpeed = speed * speedScale
        val realRange = min(range * (0.78 + a.chargeScale * 0.22), a.reach * 1.8)
        val duration = (realRange / realSpeed).toInt().coerceIn(8, 72) + turnAfter + orbitTicks + delay + 4
        val visualRadius = maxOf(0.55, impactRadius, auraRadius, width * 2.0)
        val id = effects.play(a.casterId, a.spell, from, from.clone().add(dir.clone().multiply(2.0)),
            radius = visualRadius, durationTicks = duration.coerceIn(8, 100),
            secondary = a.input.secondary, input = a.input)
        if (!current(a, player)) {
            effects.remove(id)
            return
        }
        a.shots += Shot(from.clone(), from.clone(), dir, dir.clone(), target, point.clone(), path,
            realSpeed, realRange, width, impactRadius, auraRadius, pull, push, lift, delay,
            turnAfter, orbitTicks, orbitRadius, lane, bounces, maxTargetsPerShot, id)
    }

    private fun field(
        a: Attack, center: Location, radius: Double, delay: Int, duration: Int, interval: Int,
        pulses: Int, expanding: Boolean, maxHitsPerTarget: Int, pull: Double = 0.0, lift: Double = 0.0,
    ) {
        val player = Bukkit.getPlayer(a.casterId) ?: return
        if (a.field != null || !current(a, player)) return
        val reach = radius * (0.72 + a.chargeScale * 0.28)
        val life = (delay + duration + pulses * interval + 4).coerceIn(8, 64)
        val id = effects.play(a.casterId, a.spell, center, center, radius = reach,
            durationTicks = life, secondary = a.input.secondary, input = a.input)
        if (!current(a, player)) {
            effects.remove(id)
            return
        }
        a.field = Field(center.clone(), reach, delay, duration, interval, pulses, expanding,
            maxHitsPerTarget, pull, lift, id)
    }

    private fun tick() {
        if (closed) return
        attacks.toList().forEach { attack ->
            if (attack !in attacks) return@forEach
            val player = Bukkit.getPlayer(attack.casterId)
            if (player == null || !current(attack, player)) {
                removeAttack(attack)
                return@forEach
            }
            attack.age++
            attack.field?.let { tickField(attack, player, it) }
            attack.shots.toList().forEach { shot ->
                if (shot.active && attack in attacks) tickShot(attack, player, shot)
            }
            if (attack.shots.none { it.active } && (attack.field == null || attack.field?.complete == true))
                attacks.remove(attack)
        }
    }

    private fun tickShot(a: Attack, player: Player, shot: Shot) {
        shot.age++
        if (shot.age <= shot.delay) return
        val from = shot.position.clone()
        if (shot.path == Path.RETURN && shot.age >= shot.turnAfter) shot.returning = true
        if (shot.path == Path.RETURN && shot.returning) {
            val desired = player.eyeLocation.toVector().subtract(from.toVector())
            if (desired.lengthSquared() < 1.8 * 1.8) {
                impact(a, shot, from, null)
                return
            }
            steer(shot, desired, 0.42)
        } else if (shot.path == Path.SEEK || shot.path == Path.RICOCHET) {
            val aimed = shot.target?.takeIf { damage.eligible(player, it, a.cast) && it.world.uid == a.worldId }
                ?.let(::center) ?: shot.point
            steer(shot, aimed.toVector().subtract(from.toVector()), 0.34)
        } else if (shot.path == Path.ARC) {
            shot.direction.add(Vector(0.0, -0.055, 0.0)).normalize()
        } else if (shot.path == Path.SWEEP) {
            val aimed = shot.target?.takeIf { damage.eligible(player, it, a.cast) && it.world.uid == a.worldId }
                ?.let(::center) ?: shot.point
            val toward = aimed.toVector().subtract(from.toVector())
            if (toward.lengthSquared() > 1.0e-8) {
                val distance = toward.length()
                val wobble = horizontalRight(toward).multiply(
                    sin(shot.age * 0.36) * 0.09 * (distance / 6.0).coerceAtMost(1.0))
                steer(shot, toward.add(wobble), 0.36)
            }
        } else if (shot.path == Path.TORNADO) {
            shot.direction = shot.initialDirection.clone()
        } else if (shot.path == Path.ORBIT_RELEASE && shot.age <= shot.orbitTicks) {
            val angle = shot.age * (PI * 2.0 / maxOf(shot.orbitTicks, 1)) + shot.lane * PI * 2.0 / 3.0
            // Orbit in front of the caster; the nearest arc point stays 2.1 blocks beyond the eye.
            val orbitCenter = player.eyeLocation.clone().add(
                shot.initialDirection.clone().multiply(shot.orbitRadius + 2.1))
            val next = orbitCenter.add(
                horizontalRight(shot.initialDirection).multiply(cos(angle) * shot.orbitRadius))
                .add(0.0, sin(angle) * 0.38, 0.0)
            shot.direction = next.toVector().subtract(from.toVector()).takeIf { it.lengthSquared() > 1.0e-8 }
                ?.normalize() ?: shot.initialDirection.clone()
        } else if (shot.path == Path.ORBIT_RELEASE && shot.age == shot.orbitTicks + 1) {
            steer(shot, shot.point.toVector().subtract(from.toVector()), 1.0)
        }

        val step = min(shot.speed, (shot.maxDistance - shot.travelled).coerceAtLeast(0.0))
        if (step <= 0.001) {
            impact(a, shot, from, null)
            return
        }
        val direction = shot.direction.clone().takeIf { it.lengthSquared() > 1.0e-8 }
            ?.normalize() ?: shot.initialDirection.clone()
        val end = targeting.rayEnd(from, direction, step)
        val traveled = from.distance(end)
        val blocked = traveled < step - 0.08
        shot.travelled += traveled

        if (shot.auraRadius > 0.0) {
            area(a, player, end, shot.auraRadius, from, 1, shot.pull, shot.push, shot.lift)
            if (a !in attacks) return
        } else {
            val candidates = targeting.lineTargets(player, from, end, shot.width, a.cast)
                .filter { it.uniqueId !in shot.attemptedTargets }
            if (shot.path == Path.PIERCE) {
                candidates.take((shot.maxTargets - shot.attemptedTargets.size).coerceAtLeast(0)).forEach { victim ->
                    shot.attemptedTargets += victim.uniqueId
                    hit(a, player, victim, from, shot.pull, shot.push, shot.lift)
                }
                if (a !in attacks) return
            } else {
                val victim = candidates.firstOrNull()
                if (victim != null) {
                    val hitAt = sweptHitPoint(victim, from, end, shot.width) ?: end
                    shot.attemptedTargets += victim.uniqueId
                    hit(a, player, victim, from, shot.pull, shot.push, shot.lift)
                    if (a !in attacks) return
                    if (shot.path == Path.RICOCHET && shot.bounces > 0 && ricochet(a, player, shot, victim, hitAt)) return
                    if (shot.path == Path.RICOCHET || shot.path !in setOf(Path.RETURN, Path.ORBIT_RELEASE)) {
                        effects.moveProjectile(shot.visualId, hitAt, direction)
                        shot.position = hitAt.clone()
                        impact(a, shot, hitAt, victim)
                        return
                    }
                }
            }
        }

        shot.position = end.clone()
        effects.moveProjectile(shot.visualId, end, direction)
        if (shot.age % 3 == 0 || shot.auraRadius > 0.0) arcane(a, end, 1,
            maxOf(shot.auraRadius, shot.impactRadius, shot.width * 2.0, 0.55))

        if (blocked || shot.travelled >= shot.maxDistance - 0.001 ||
            (shot.path == Path.RETURN && shot.returning && shot.age >= shot.turnAfter * 2 + 16)) {
            impact(a, shot, end, null)
        }
    }

    private fun tickField(a: Attack, player: Player, field: Field) {
        if (field.complete || a.age < field.delay) return
        val elapsed = a.age - field.delay
        if (!field.started) {
            field.started = true
            effects.impact(field.visualId, field.center, field.radius, field.duration)
            if (!current(a, player)) { removeAttack(a); return }
            arcane(a, field.center, 2, field.radius)
        }
        if (field.expanding) {
            val front = staffArsenalFront(elapsed, field.duration, field.radius)
            val low = (field.previousFront - 0.55).coerceAtLeast(0.0)
            area(a, player, field.center, front + 0.55, field.center, field.maxHitsPerTarget,
                field.pull, 0.0, field.lift) { target ->
                val distance = center(target).distance(field.center)
                distance >= low && distance <= front + 0.55
            }
            if (a !in attacks) return
            field.previousFront = front
            if (elapsed >= field.duration) field.complete = true
        } else if (elapsed % field.interval == 0 && field.pulseCount < field.pulses) {
            field.pulseCount++
            area(a, player, field.center, field.radius, field.center, field.maxHitsPerTarget,
                field.pull, 0.0, if (field.pulseCount == field.pulses) field.lift else 0.0)
            if (a !in attacks) return
            if (field.pulseCount >= field.pulses) field.complete = true
        }
        if (elapsed % 4 == 0) arcane(a, field.center, 1, field.radius)
    }

    private fun area(
        a: Attack, player: Player, centerAt: Location, radius: Double, losFrom: Location,
        maxHitsPerTarget: Int, pull: Double, push: Double, lift: Double,
        predicate: (LivingEntity) -> Boolean = { true },
    ) {
        targeting.nearby(player, centerAt, radius, a.cast).asSequence()
            .filter { center(it).distanceSquared(centerAt) <= radius * radius }
            .filter(predicate)
            .sortedBy { center(it).distanceSquared(centerAt) }
            .forEach { target -> hit(a, player, target, losFrom, pull, push, lift, maxHitsPerTarget) }
    }

    private fun hit(
        a: Attack, player: Player, target: LivingEntity, losFrom: Location,
        pull: Double = 0.0, push: Double = 0.0, lift: Double = 0.0, maxHits: Int = 1,
    ): Boolean {
        if (a !in attacks) return false
        if (!current(a, player)) { removeAttack(a); return false }
        if (target.world.uid != a.worldId || !damage.eligible(player, target, a.cast)) return false
        val at = center(target)
        if (!visible(player.eyeLocation, at) || !visible(losFrom, at)) return false
        val prior = a.attempts[target.uniqueId] ?: 0
        if (prior >= maxHits || (prior == 0 && a.attempts.size >= a.maxTargets)) return false
        if (!current(a, player)) { removeAttack(a); return false }
        a.attempts[target.uniqueId] = prior + 1
        val damaged = damage.hit(player, target, a.cast, a.tuning.power, a.tuning.vanillaDamage)
        if (!current(a, player)) { if (a in attacks) removeAttack(a); return false }
        if (damaged && target.isValid && !target.isDead && target.world.uid == a.worldId &&
            damage.eligible(player, target, a.cast)) {
            if (!current(a, player)) { if (a in attacks) removeAttack(a); return false }
            applyForce(target, center(target), losFrom, pull, push, lift)
        }
        return damaged
    }

    private fun ricochet(a: Attack, player: Player, shot: Shot, victim: LivingEntity, at: Location): Boolean {
        if (!current(a, player)) { removeAttack(a); return false }
        val next = targeting.nearby(player, center(victim), minOf(6.0, settings.chainRadius), a.cast)
            .filter { (a.attempts[it.uniqueId] ?: 0) == 0 && visible(at, center(it)) && visible(player.eyeLocation, center(it)) }
            .minByOrNull { center(it).distanceSquared(at) } ?: return false
        shot.target = next
        shot.point = center(next)
        shot.direction = shot.point.toVector().subtract(at.toVector()).takeIf { it.lengthSquared() > 1.0e-8 }
            ?.normalize() ?: shot.initialDirection.clone()
        shot.position = at.clone()
        shot.bounces--
        effects.moveProjectile(shot.visualId, at, shot.direction)
        arcane(a, at, 2, 1.0)
        return true
    }

    private fun impact(a: Attack, shot: Shot, at: Location, primary: LivingEntity?) {
        val player = Bukkit.getPlayer(a.casterId) ?: return
        if (!shot.active || !current(a, player)) { if (a in attacks) removeAttack(a); return }
        shot.active = false
        val radius = shot.impactRadius
        effects.impact(shot.visualId, at, maxOf(0.65, radius), 12)
        if (!current(a, player)) { if (a in attacks) removeAttack(a); return }
        arcane(a, at, 2, maxOf(0.65, radius))
        if (radius > 0.0 && a in attacks) area(a, player, at, radius, at,
            1, 0.0, shot.push, shot.lift) { target -> primary == null || target.uniqueId != primary.uniqueId }
        if (primary != null && a in attacks) hit(a, player, primary, at,
            shot.pull, shot.push, shot.lift)
    }

    private fun arcane(a: Attack, at: Location, phase: Int, radius: Double) {
        val player = Bukkit.getPlayer(a.casterId) ?: return
        if (!current(a, player)) { if (a in attacks) removeAttack(a); return }
        visuals.withOwner(a.casterId) { visuals.arcane(at, a.spell, phase, radius) }
    }

    private fun current(a: Attack, player: Player) = a in attacks && ready(player) &&
        player.uniqueId == a.casterId && player.world.uid == a.worldId && damage.isCurrent(player, a.cast)

    private fun applyForce(target: LivingEntity, targetAt: Location, centerAt: Location,
        pull: Double, push: Double, lift: Double) {
        var impulse = Vector()
        if (pull > 0.0) {
            val toward = centerAt.toVector().subtract(targetAt.toVector())
            if (toward.lengthSquared() > 1.0e-8) impulse = toward.normalize().multiply(pull)
        }
        if (push > 0.0) {
            val away = targetAt.toVector().subtract(centerAt.toVector())
            if (away.lengthSquared() > 1.0e-8) impulse = away.normalize().multiply(push)
        }
        val velocity = target.velocity.add(impulse)
        if (lift > velocity.y) velocity.y = lift
        if (impulse.lengthSquared() > 0.0 || lift > 0.0) target.velocity = velocity
    }

    private fun steer(shot: Shot, toward: Vector, amount: Double) {
        if (toward.lengthSquared() < 1.0e-8) return
        shot.direction.multiply(1.0 - amount).add(toward.clone().normalize().multiply(amount)).normalize()
    }

    private fun sweptHitPoint(target: LivingEntity, from: Location, to: Location, width: Double): Location? {
        val delta = to.toVector().subtract(from.toVector())
        val distance = delta.length()
        if (distance < 1.0e-6) return null
        return target.boundingBox.expand(width).rayTrace(from.toVector(), delta.multiply(1.0 / distance), distance)
            ?.hitPosition?.toLocation(from.world)
    }

    private fun removeAttack(attack: Attack) {
        attacks.remove(attack)
        attack.shots.forEach { it.active = false; effects.remove(it.visualId) }
        attack.field?.let { effects.remove(it.visualId) }
    }

    private fun ready(player: Player) = player.isOnline && player.isValid && !player.isDead &&
        player.gameMode != GameMode.SPECTATOR

    private fun horizontalRight(direction: Vector) = Vector(direction.z, 0.0, -direction.x).apply {
        if (lengthSquared() < 1.0e-8) { x = 1.0; y = 0.0; z = 0.0 } else normalize()
    }

    private companion object {
        val ARSENAL_SPELLS = setOf(StaffSpell.LUNAR, StaffSpell.GALE, StaffSpell.TIDE,
            StaffSpell.REQUIEM, StaffSpell.PRISM, StaffSpell.NOVA)
        const val MAX_SHOTS_PER_CAST = 5
        const val MAX_ATTACKS = 32
        const val MAX_ATTACKS_PER_CASTER = 4
    }
}
