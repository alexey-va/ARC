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
        val duration: Int,
        val scenes: MutableList<UUID?> = mutableListOf(),
        val born: MutableSet<Int> = mutableSetOf(),
        val attempted: MutableSet<UUID> = mutableSetOf(),
        val impactedProjectiles: MutableSet<Int> = mutableSetOf(),
        val projectileScenes: MutableMap<Int, UUID?> = mutableMapOf(),
        var rays: List<Pair<Location, Location>> = emptyList(),
        var age: Int = 0,
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
        val range = minOf(settings.range, 36.0) * (.75 + q * .25)
        val selected = targeting.aimed(player, range, settings.aimDegrees, cast).firstOrNull()
        if (!damage.isCurrent(player, cast)) return
        val aimed = selected?.let(::center) ?: targeting.rayEnd(eye, eye.direction, range)
        val point = targeting.groundBelow(aimed.clone().add(0.0, 2.0, 0.0), 12.0) ?: aimed.clone().subtract(0.0, 1.2, 0.0)
        if (!visible(eye, aimed)) return
        val forward = Vector(point.x - eye.x, 0.0, point.z - eye.z).apply {
            if (lengthSquared() < .01) { x = eye.direction.x; z = eye.direction.z }
            if (lengthSquared() < .01) z = 1.0
        }.normalize()
        val launchDistance = minOf(2.5, range * .25, eye.distance(aimed) * .35)
        val origin = targeting.rayEnd(eye, forward, launchDistance).subtract(0.0, 1.62, 0.0)
        val groundOrigin = targeting.groundBelow(origin.clone().add(0.0, 2.0, 0.0), 5.0) ?: origin
        val distance = point.toVector().subtract(groundOrigin.toVector()).dot(forward).coerceIn(0.0, range)
        if (!visible(eye, groundOrigin.clone().add(0.0, 1.0, 0.0))) return
        val intendedTravelEnd = groundOrigin.clone().add(forward.clone().multiply(distance)).apply { y = point.y }
        val pathSpell = spell == StaffSpell.TIDE || spell == StaffSpell.GALE && input.secondary ||
            spell == StaffSpell.NOVA && !input.secondary || spell == StaffSpell.OSSUARY && !input.secondary
        val travelEnd = if (pathSpell) {
            val delta = intendedTravelEnd.toVector().subtract(groundOrigin.toVector())
            val pathLength = delta.length()
            if (!pathLength.isFinite() || pathLength < MIN_GROUND_PATH_LENGTH) return
            val clippedEnd = targeting.rayEnd(groundOrigin, delta.multiply(1.0 / pathLength), pathLength)
            if (!clippedEnd.x.isFinite() || !clippedEnd.y.isFinite() || !clippedEnd.z.isFinite() ||
                clippedEnd.distanceSquared(groundOrigin) < MIN_GROUND_PATH_LENGTH * MIN_GROUND_PATH_LENGTH) return
            clippedEnd
        } else intendedTravelEnd
        val pathDistance = travelEnd.toVector().subtract(groundOrigin.toVector()).dot(forward).coerceIn(0.0, range)
        val count = 4 + (q * 2).toInt()
        val nodes = if (spell == StaffSpell.OSSUARY && !input.secondary) (0 until count).mapNotNull { i ->
            val at = groundOrigin.clone().add(forward.clone().multiply(pathDistance * (i + 1) / count))
            val ground = targeting.groundBelow(at.clone().add(0.0, 2.0, 0.0), 6.0) ?: return@mapNotNull null
            ground.takeIf { visible(eye, it.clone().add(0.0, .8, 0.0)) }
        } else emptyList()
        if (spell == StaffSpell.OSSUARY && !input.secondary && nodes.isEmpty()) return
        while (attacks.count { it.casterId == player.uniqueId } >= 2) remove(attacks.first { it.casterId == player.uniqueId })
        while (attacks.size >= 24) remove(attacks.first())
        val a = Attack(player.uniqueId, spell, input, cast.copy(visualCharge = q), tuning,
            groundOrigin, point, selected?.let(::center) ?: aimed, travelEnd, forward, nodes, 2.2 + q * 1.0,
            staffArsenalLifetime(spell, input.secondary))
        attacks += a
        sound(a, a.origin, false)
        when (spell) {
            StaffSpell.LUNAR, StaffSpell.PRISM -> scene(a, a.point, a.duration)
            StaffSpell.GALE -> if (input.secondary) scene(a, a.origin.clone().add(0.0, 1.1, 0.0), a.duration,
                a.travelEnd.clone().add(0.0, 1.1, 0.0), controllerDriven = true)
            StaffSpell.REQUIEM -> if (input.secondary) scene(a, a.point, a.duration)
            StaffSpell.NOVA -> if (input.secondary) scene(a, a.point, a.duration)
                else scene(a, a.origin, a.duration, a.travelEnd)
            StaffSpell.TIDE -> if (!input.secondary) {
                scene(a, a.origin.clone().add(0.0, 1.2, 0.0), a.duration,
                    a.travelEnd.clone().add(0.0, 1.2, 0.0), controllerDriven = true)
            }
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
                val at = along(a, ((a.age - 4) / TIDE_WAVE_TRAVEL_TICKS).coerceIn(0.0, 1.0))
                particles(a, at, a.age, a.duration, radius = 2.8 + a.cast.visualCharge * .7)
                if (a.age in 8..34) area(a, player, at.clone().add(0.0, 1.0, 0.0),
                    2.5 + a.cast.visualCharge * .45, lift = .25)
                if (a.age == 34) sound(a, at, true)
            } else tideOrb(a, player)
            StaffSpell.GALE -> if (a.input.secondary) {
                val at = along(a, ((a.age - 4) / GALE_RAM_TRAVEL_TICKS).coerceIn(0.0, 1.0))
                particles(a, at, a.age, a.duration, radius = 2.0 + a.cast.visualCharge * .35)
                a.scenes.firstOrNull()?.let { id ->
                    val sceneAt = at.clone().add(0.0, 1.1, 0.0)
                    effects.move(id, sceneAt)
                    effects.synchronize(id, a.age.coerceAtLeast(0))
                }
                if (a.age >= 6 && a.age <= 24) sweptContact(a, player,
                    along(a, ((a.age - 2 - 4) / GALE_RAM_TRAVEL_TICKS).coerceIn(0.0, 1.0)).add(0.0, 1.1, 0.0),
                    at.clone().add(0.0, 1.1, 0.0), width = 1.0, lift = .38)
                if (a.age == 24) sound(a, at, true)
            } else {
                particles(a, a.point, a.age, a.duration, radius = a.radius)
                if (a.age == 20) sound(a, a.point, true)
                if (a.age in 20..28) area(a, player, a.point.clone().add(0.0, 1.1, 0.0),
                    2.5 + a.cast.visualCharge * .45, lift = .2)
            }
            StaffSpell.LUNAR -> {
                particles(a, a.point, a.age, a.duration, radius = a.radius)
                if (a.age == 16) sound(a, a.point, true)
                if (a.age in 16..24) area(a, player, a.point.clone().add(0.0, 1.3, 0.0), a.radius + .7)
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
            StaffSpell.REQUIEM -> if (a.input.secondary) requiemShock(a, player) else requiemMissiles(a, player)
            StaffSpell.PRISM -> prism(a, player)
            StaffSpell.OSSUARY -> if (a.input.secondary) ossuaryCage(a, player) else ossuarySpikes(a, player)
            else -> Unit
        }
    }

    private fun tideOrb(a: Attack, player: Player) {
        val progress = ((a.age - TIDE_ORB_LAUNCH_TICK) / TIDE_ORB_TRAVEL_TICKS).coerceIn(0.0, 1.0)
        val previousProgress = ((a.age - 2 - TIDE_ORB_LAUNCH_TICK) / TIDE_ORB_TRAVEL_TICKS).coerceIn(0.0, 1.0)
        val at = along(a, progress).add(0.0, 1.2, 0.0)
        particles(a, at, a.age, a.duration, radius = 1.0 + a.cast.visualCharge * .3)
        a.scenes.firstOrNull()?.let { id ->
            effects.move(id, at)
            effects.synchronize(id, a.age.coerceAtLeast(0))
            if (a.age == TIDE_ORB_ARRIVAL_TICK) effects.impact(id, at, 1.6 + a.cast.visualCharge * .4, 10)
        }
        if (a.age >= TIDE_ORB_LAUNCH_TICK && a.age <= TIDE_ORB_ARRIVAL_TICK) {
            val from = along(a, previousProgress).add(0.0, 1.2, 0.0)
            sweptContact(a, player, from, at, width = .68, lift = .12)
        }
        if (a.age == TIDE_ORB_ARRIVAL_TICK) sound(a, at, true)
    }

    private fun ossuarySpikes(a: Attack, player: Player) {
        a.nodes.forEachIndexed { index, at ->
            val localAge = a.age - OSSUARY_SPIKE_START_TICK - index * OSSUARY_SPIKE_STAGGER_TICKS
            if (localAge >= 0 && a.born.add(index)) scene(a, at, OSSUARY_SPIKE_SCENE_TICKS)
            if (localAge in 0 until OSSUARY_SPIKE_SCENE_TICKS) particles(a, at, localAge,
                OSSUARY_SPIKE_SCENE_TICKS, secondary = false, radius = 1.0 + a.cast.visualCharge * .2)
            if (localAge == OSSUARY_SPIKE_CONTACT_TICK) sound(a, at, true)
            if (localAge in OSSUARY_SPIKE_CONTACT_TICK..(OSSUARY_SPIKE_CONTACT_TICK + 2)) {
                // Center the query over the target's body, but keep it low enough that
                // World#getNearbyLivingEntities includes entities whose location is at their feet.
                area(a, player, at.clone().add(0.0, .8, 0.0), 1.2 + a.cast.visualCharge * .2)
            }
        }
    }

    private fun ossuaryCage(a: Attack, player: Player) {
        particles(a, a.point, a.age, a.duration, secondary = true, radius = 2.1 + a.cast.visualCharge * .4)
        if (a.age == OSSUARY_CAGE_CLOSE_TICK) sound(a, a.point, true)
        if (a.age in OSSUARY_CAGE_CLOSE_TICK..(OSSUARY_CAGE_CLOSE_TICK + 2)) {
            area(a, player, a.point.clone().add(0.0, .9, 0.0),
                1.9 + a.cast.visualCharge * .35)
        }
    }

    private fun requiemMissiles(a: Attack, player: Player) {
        val count = 3 + (a.cast.visualCharge * 2).toInt()
        val right = Vector(a.forward.z, 0.0, -a.forward.x)
        repeat(count) { index ->
            val launchTick = REQUIEM_MISSILE_LAUNCH_TICK + index * REQUIEM_MISSILE_STAGGER_TICKS
            val localAge = a.age - launchTick
            if (localAge < 0 || index in a.impactedProjectiles) return@repeat
            val start = a.origin.clone().add(right.clone().multiply((index - (count - 1) / 2.0) * .62))
                .add(0.0, 1.35, 0.0)
            val rawTarget = a.aimPoint.clone()
            val delta = rawTarget.toVector().subtract(start.toVector())
            val distance = delta.length()
            if (!distance.isFinite() || distance < .25) return@repeat
            val end = targeting.rayEnd(start, delta.clone().multiply(1.0 / distance), distance)
            if (end.distanceSquared(start) < .04 || !visible(start, end)) return@repeat
            val id = a.projectileScenes.getOrPut(index) {
                scene(a, start, REQUIEM_MISSILE_SCENE_TICKS, end, controllerDriven = true)
            }
            val progress = (localAge.toDouble() / REQUIEM_MISSILE_TRAVEL_TICKS).coerceIn(0.0, 1.0)
            val previous = ((localAge - 2).toDouble() / REQUIEM_MISSILE_TRAVEL_TICKS).coerceIn(0.0, 1.0)
            val missileDelta = end.toVector().subtract(start.toVector())
            val at = start.clone().add(missileDelta.clone().multiply(smooth(progress)))
            val from = start.clone().add(missileDelta.clone().multiply(smooth(previous)))
            val directed = at.toVector().subtract(from.toVector())
            effects.moveProjectile(id, at, if (directed.lengthSquared() < .001) end.toVector().subtract(start.toVector()) else directed)
            effects.synchronize(id, localAge.coerceAtMost(REQUIEM_MISSILE_SCENE_TICKS - 1))
            particles(a, at, localAge.coerceAtMost(REQUIEM_MISSILE_TRAVEL_TICKS),
                REQUIEM_MISSILE_SCENE_TICKS, secondary = false, radius = .75 + a.cast.visualCharge * .2)
            if (localAge in 2..REQUIEM_MISSILE_TRAVEL_TICKS) {
                targeting.lineTargets(player, from, at, .45, a.cast).firstOrNull()?.let { target ->
                    if (hit(a, player, target, from)) {
                        a.impactedProjectiles += index
                        effects.impact(id, center(target), 1.1, 8)
                        sound(a, center(target), true)
                    }
                }
            }
            if (localAge >= REQUIEM_MISSILE_TRAVEL_TICKS && index !in a.impactedProjectiles) {
                a.impactedProjectiles += index
                effects.impact(id, end, 1.1, 8)
            }
        }
    }

    private fun requiemShock(a: Attack, player: Player) {
        val radius = 4.0 + a.cast.visualCharge * .75
        particles(a, a.point, a.age, a.duration, secondary = true, radius = radius)
        val progress = ((a.age - REQUIEM_SHOCK_START_TICK) / REQUIEM_SHOCK_TRAVEL_TICKS).coerceIn(0.0, 1.0)
        val previous = ((a.age - 2 - REQUIEM_SHOCK_START_TICK) / REQUIEM_SHOCK_TRAVEL_TICKS).coerceIn(0.0, 1.0)
        val currentRadius = .25 + progress * radius
        val previousRadius = .25 + previous * radius
        if (a.age in REQUIEM_SHOCK_START_TICK..REQUIEM_SHOCK_ARRIVAL_TICK && currentRadius > previousRadius) {
            repeat(24) { ray ->
                val angle = ray * Math.PI * 2 / 24
                val direction = Vector(kotlin.math.cos(angle), 0.0, kotlin.math.sin(angle))
                val from = a.point.clone().add(direction.clone().multiply(previousRadius)).add(0.0, .9, 0.0)
                val requestedEnd = a.point.clone().add(direction.clone().multiply(currentRadius)).add(0.0, .9, 0.0)
                val end = targeting.rayEnd(from, requestedEnd.toVector().subtract(from.toVector()),
                    from.distance(requestedEnd))
                targeting.lineTargets(player, from, end, .55, a.cast).forEach { hit(a, player, it, from) }
            }
        }
        if (a.age == REQUIEM_SHOCK_ARRIVAL_TICK) sound(a, a.point, true)
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

    private fun scene(a: Attack, at: Location, duration: Int, to: Location = at.clone().add(a.forward),
        controllerDriven: Boolean = false): UUID? {
        val id = effects.play(a.casterId, a.spell, at, to,
            radius = a.radius, durationTicks = duration, secondary = a.input.secondary,
            input = a.input, controllerDriven = controllerDriven, charge = a.cast.visualCharge)
        a.scenes += id
        return id
    }

    private fun particles(a: Attack, at: Location, age: Int, duration: Int,
        secondary: Boolean = a.input.secondary, radius: Double = a.radius) {
        if (a !in attacks) return
        val right = Vector(a.forward.z, 0.0, -a.forward.x)
        val reach = if (a.spell == StaffSpell.NOVA && !secondary) a.travelEnd.distance(a.origin) else radius
        val authored = staffArsenalParticles(a.spell, age, duration, radius, secondary, a.cast.visualCharge, reach)
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

    private fun hit(a: Attack, player: Player, target: LivingEntity, from: Location): Boolean {
        if (!current(a, player) || a.attempted.size >= settings.maxAreaTargets || target.uniqueId in a.attempted ||
            !damage.eligible(player, target, a.cast) || center(target).distanceSquared(player.eyeLocation) > settings.range * settings.range ||
            !visible(player.eyeLocation, center(target)) ||
            !visible(from, center(target))) return false
        a.attempted += target.uniqueId
        return damage.hit(player, target, a.cast, a.tuning.power, a.tuning.vanillaDamage)
    }

    private fun sound(a: Attack, at: Location, impact: Boolean) {
        if (a !in attacks) return
        val sound = when (a.spell) {
            StaffSpell.GALE -> if (impact) Sound.ENTITY_BREEZE_WIND_BURST else Sound.ENTITY_BREEZE_CHARGE
            StaffSpell.TIDE -> if (impact) Sound.ENTITY_PLAYER_SPLASH_HIGH_SPEED else Sound.ITEM_TRIDENT_RIPTIDE_1
            StaffSpell.NOVA -> if (impact) Sound.BLOCK_AZALEA_LEAVES_BREAK else Sound.BLOCK_ROOTED_DIRT_BREAK
            StaffSpell.REQUIEM -> if (impact) Sound.ENTITY_WARDEN_SONIC_BOOM else Sound.ENTITY_EVOKER_PREPARE_SUMMON
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
        private const val TIDE_ORB_LAUNCH_TICK = 4
        private const val TIDE_ORB_TRAVEL_TICKS = 24.0
        private const val TIDE_ORB_ARRIVAL_TICK = TIDE_ORB_LAUNCH_TICK + TIDE_ORB_TRAVEL_TICKS.toInt()
        private const val TIDE_WAVE_TRAVEL_TICKS = 30.0
        private const val NOVA_ROOT_TRAVEL_TICKS = 30
        private const val REQUIEM_MISSILE_LAUNCH_TICK = 4
        private const val REQUIEM_MISSILE_STAGGER_TICKS = 6
        private const val REQUIEM_MISSILE_TRAVEL_TICKS = 14
        private const val REQUIEM_MISSILE_SCENE_TICKS = 22
        private const val REQUIEM_SHOCK_START_TICK = 8
        private const val REQUIEM_SHOCK_TRAVEL_TICKS = 20.0
        private const val REQUIEM_SHOCK_ARRIVAL_TICK = REQUIEM_SHOCK_START_TICK + REQUIEM_SHOCK_TRAVEL_TICKS.toInt()
        private const val OSSUARY_SPIKE_START_TICK = 4
        private const val OSSUARY_SPIKE_STAGGER_TICKS = 4
        private const val OSSUARY_SPIKE_SCENE_TICKS = 22
        private const val OSSUARY_SPIKE_CONTACT_TICK = 8
        private const val OSSUARY_CAGE_CLOSE_TICK = 12
        private const val MIN_GROUND_PATH_LENGTH = 0.75
    }
}

private fun smooth(value: Double): Double = value.takeIf(Double::isFinite)?.coerceIn(0.0, 1.0)?.let {
    it * it * (3.0 - 2.0 * it)
} ?: 0.0
