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
        val travelEnd: Location,
        val forward: Vector,
        val nodes: List<Location>,
        val radius: Double,
        val duration: Int,
        val scenes: MutableList<UUID?> = mutableListOf(),
        val born: MutableSet<Int> = mutableSetOf(),
        val attempted: MutableSet<UUID> = mutableSetOf(),
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
        val travelEnd = if (spell == StaffSpell.GALE || spell == StaffSpell.TIDE && input.secondary) {
            val delta = intendedTravelEnd.toVector().subtract(groundOrigin.toVector())
            val pathLength = delta.length()
            if (!pathLength.isFinite() || pathLength < MIN_GROUND_PATH_LENGTH) return
            val clippedEnd = targeting.rayEnd(groundOrigin, delta.multiply(1.0 / pathLength), pathLength)
            if (!clippedEnd.x.isFinite() || !clippedEnd.y.isFinite() || !clippedEnd.z.isFinite() ||
                clippedEnd.distanceSquared(groundOrigin) < MIN_GROUND_PATH_LENGTH * MIN_GROUND_PATH_LENGTH) return
            clippedEnd
        } else intendedTravelEnd
        val count = 4 + (q * 2).toInt()
        val nodes = (0 until count).mapNotNull { i ->
            val at = groundOrigin.clone().add(forward.clone().multiply(distance * (i + 1) / count))
            if (spell == StaffSpell.NOVA && i % 2 == 1) at.add(Vector(forward.z, 0.0, -forward.x).multiply(1.15))
            val ground = targeting.groundBelow(at.clone().add(0.0, 2.0, 0.0), 6.0) ?: return@mapNotNull null
            ground.takeIf { visible(eye, it.clone().add(0.0, .8, 0.0)) }
        }
        while (attacks.count { it.casterId == player.uniqueId } >= 2) remove(attacks.first { it.casterId == player.uniqueId })
        while (attacks.size >= 24) remove(attacks.first())
        val a = Attack(player.uniqueId, spell, input, cast.copy(visualCharge = q), tuning,
            groundOrigin, point, travelEnd, forward, nodes, 2.2 + q * 1.0,
            staffArsenalLifetime(spell, input.secondary))
        attacks += a
        sound(a, a.origin, false)
        if (spell in setOf(StaffSpell.LUNAR, StaffSpell.PRISM) || spell == StaffSpell.NOVA && input.secondary) {
            scene(a, a.point, a.duration)
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
                val t = ((a.age - 4) / 30.0).coerceIn(0.0, 1.0)
                val at = along(a, t)
                particles(a, at, a.age, a.duration, secondary = true, radius = 3.2 + a.cast.visualCharge * 1.3)
                if (a.age in 6..34) area(a, player, at.clone().add(0.0, 1.2, 0.0), 3.3, lift = .28)
                if (a.age == 8) sound(a, at, true)
            } else a.nodes.forEachIndexed { i, at ->
                val localAge = a.age - 4 - i * 4
                if (localAge in 0..22) particles(a, at, localAge, 24)
                if (localAge == 4) sound(a, at, true)
                if (localAge in 4..8) area(a, player, at.clone().add(0.0, 1.4, 0.0), a.radius, lift = .42)
            }
            StaffSpell.GALE -> {
                val t = ((a.age - 4) / if (a.input.secondary) 28.0 else 40.0).coerceIn(0.0, 1.0)
                val at = along(a, t)
                particles(a, at, a.age, a.duration, radius = a.radius)
                if (a.age % 12 == 6) sound(a, at, true)
                if (a.age in 8 until a.duration - 10) area(a, player, at.clone().add(0.0, 1.3, 0.0),
                    a.radius, lift = if (a.input.secondary) .38 else .2)
            }
            StaffSpell.LUNAR -> {
                particles(a, a.point, a.age, a.duration, radius = a.radius)
                if (a.age == 16) sound(a, a.point, true)
                if (a.age in 16..24) area(a, player, a.point.clone().add(0.0, 1.3, 0.0), a.radius + .7)
            }
            StaffSpell.NOVA -> if (a.input.secondary) {
                particles(a, a.point, a.age, a.duration, secondary = true, radius = a.radius)
                if (a.age == 18) sound(a, a.point, true)
                if (a.age in 18..32) {
                    val front = .4 + (a.age - 17) * .36
                    area(a, player, a.point.clone().add(0.0, .8, 0.0), front + .7, slow = true)
                }
            } else a.nodes.forEachIndexed { i, at ->
                val localAge = a.age - i * 4
                if (localAge >= 0 && a.born.add(i)) scene(a, at, a.duration - i * 4)
                if (localAge in 0..30) particles(a, at, localAge, 32)
                if (localAge == 6) sound(a, at, true)
                if (localAge in 6..12) area(a, player, at.clone().add(0.0, 1.0, 0.0), 1.8 + a.cast.visualCharge * .5, slow = true)
            }
            StaffSpell.REQUIEM -> {
                particles(a, a.point, a.age, a.duration, radius = 3.2 + a.cast.visualCharge)
                if (a.age == 22) sound(a, a.point, true)
                if (a.age in 22..28) area(a, player, a.point.clone().add(0.0, 1.4, 0.0),
                    if (a.input.secondary) 4.2 else 2.7, slow = a.input.secondary)
            }
            StaffSpell.PRISM -> prism(a, player)
            else -> Unit
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

    private fun scene(a: Attack, at: Location, duration: Int) {
        a.scenes += effects.play(a.casterId, a.spell, at, at.clone().add(a.forward),
            radius = a.radius, durationTicks = duration, secondary = a.input.secondary,
            input = a.input, charge = a.cast.visualCharge)
    }

    private fun particles(a: Attack, at: Location, age: Int, duration: Int,
        secondary: Boolean = a.input.secondary, radius: Double = a.radius) {
        if (a !in attacks) return
        val right = Vector(a.forward.z, 0.0, -a.forward.x)
        val authored = staffArsenalParticles(a.spell, age, duration, radius, secondary, a.cast.visualCharge)
        val limit = if (!a.input.secondary && a.spell in setOf(StaffSpell.NOVA, StaffSpell.TIDE))
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
        val SPELLS = setOf(StaffSpell.LUNAR, StaffSpell.GALE, StaffSpell.TIDE, StaffSpell.REQUIEM, StaffSpell.PRISM, StaffSpell.NOVA)
        private const val MIN_GROUND_PATH_LENGTH = 0.75
    }
}
