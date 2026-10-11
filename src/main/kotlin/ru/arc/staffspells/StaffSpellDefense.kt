package ru.arc.staffspells

import net.kyori.adventure.bossbar.BossBar
import org.bukkit.Bukkit
import org.bukkit.Particle
import org.bukkit.Sound
import org.bukkit.entity.LivingEntity
import org.bukkit.entity.Player
import org.bukkit.entity.Projectile
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.entity.EntityDamageByEntityEvent
import org.bukkit.event.entity.EntityDamageEvent
import org.bukkit.event.entity.EntityDamageEvent.DamageModifier
import org.bukkit.util.Vector
import ru.arc.core.LifecycleTaskScope
import java.util.UUID
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sqrt
import kotlin.math.sin

/** A short, visible guard with a separate cooldown; it never enables an otherwise forbidden hit. */
internal class StaffSpellDefense(
    private val config: StaffSpellConfig,
    private val damage: StaffSpellDamage,
    private val visuals: StaffSpellVisuals,
) : Listener, AutoCloseable {
    private data class Guard(val spell: StaffSpell, val cast: StaffSpellCast, val worldId: UUID,
        val ends: Int, var hits: Int)
    private data class Shield(
        val spell: StaffSpell,
        val cast: StaffSpellCast,
        val worldId: UUID,
        val started: Int,
        val ends: Int,
        val capacity: Double,
        var remaining: Double,
    )
    private data class Recovery(val spell: StaffSpell, val ends: Int, val duration: Int)
    private val tasks = LifecycleTaskScope()
    private val guards = mutableMapOf<UUID, Guard>()
    private val shields = mutableMapOf<UUID, Shield>()
    private val recovery = mutableMapOf<UUID, Recovery>()
    private val bars = mutableMapOf<UUID, BossBar>()
    private var tick = 0

    fun start() { tasks.runTimer(2, 2) { tick += 2; update() } }

    fun activate(player: Player, spell: StaffSpell, cast: StaffSpellCast, allAround: Boolean): Boolean {
        if (!damage.isCurrent(player, cast)) return false
        if ((recovery[player.uniqueId]?.ends ?: 0) > tick) {
            visuals.cooldownFeedback(player, spell)
            return false
        }
        val heavy = spell in setOf(StaffSpell.FAULT, StaffSpell.GRAVITY, StaffSpell.OSSUARY,
            StaffSpell.NOVA, StaffSpell.NECROMANCER)
        val duration = if (allAround) SHIELD_DURATION_TICKS else if (heavy) 32 else 24
        val cooldown = if (allAround) SHIELD_COOLDOWN_TICKS else 80
        if (allAround) {
            guards.remove(player.uniqueId)
            shields[player.uniqueId] = Shield(spell, cast, player.world.uid, tick,
                tick + duration, SHIELD_CAPACITY, SHIELD_CAPACITY)
        } else {
            shields.remove(player.uniqueId)
            guards[player.uniqueId] = Guard(spell, cast, player.world.uid, tick + duration,
                hits = if (heavy) 3 else 2)
        }
        recovery[player.uniqueId] = Recovery(spell, tick + cooldown, cooldown)
        shields[player.uniqueId]?.let { frame(player, it, impact = false) }
            ?: frame(player, guards.getValue(player.uniqueId), impact = false)
        visuals.withOwner(player.uniqueId) { visuals.cue(player.location, Sound.ITEM_SHIELD_BLOCK, .7f, 1.25f) }
        show(player)
        return true
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onDamage(event: EntityDamageEvent) {
        val player = event.entity as? Player ?: return
        if (event.isCancelled) return
        val shield = shields[player.uniqueId]
        if (shield != null) {
            if (!current(player, shield)) {
                shields.remove(player.uniqueId, shield)
                return
            }
            val preAbsorptionDamage = staffPreAbsorptionDamage(event)
            if (!preAbsorptionDamage.isFinite() || preAbsorptionDamage <= 0.0 || shield.remaining <= 0.0) return
            val absorbed = min(preAbsorptionDamage, shield.remaining)
            val overflow = preAbsorptionDamage - absorbed
            if (overflow <= 0.0) event.isCancelled = true
            else if (!staffSetPreAbsorptionDamage(event, overflow)) return
            shield.remaining = (shield.remaining - absorbed).coerceAtLeast(0.0)
            val depleted = shield.remaining <= 0.0
            if (depleted) shield.remaining = 0.0
            frame(player, shield, impact = true, depleted = depleted)
            visuals.withOwner(player.uniqueId) {
                visuals.cue(player.location,
                    if (depleted) Sound.ITEM_SHIELD_BREAK else Sound.ITEM_SHIELD_BLOCK,
                    .9f, if (depleted) .8f else 1.15f)
            }
            if (depleted) shields.remove(player.uniqueId, shield)
            show(player)
            return
        }

        val guard = guards[player.uniqueId] ?: return
        val byEntityEvent = event as? EntityDamageByEntityEvent ?: return
        if (event.damage <= 0 || !current(player, guard)) return
        val projectile = byEntityEvent.damager as? Projectile
        val enemy = (projectile?.shooter as? LivingEntity) ?: byEntityEvent.damager as? LivingEntity ?: return
        if (enemy.uniqueId == player.uniqueId || enemy.world != player.world) return
        val incoming = if (projectile != null && projectile.velocity.lengthSquared() > .0001)
            projectile.velocity.clone().multiply(-1.0)
        else enemy.eyeLocation.toVector().subtract(player.eyeLocation.toVector())
        if (!staffGuardFaces(player.eyeLocation.direction, incoming, allAround = false)) return
        event.isCancelled = true
        guard.hits--
        frame(player, guard, impact = true)
        visuals.withOwner(player.uniqueId) {
            visuals.cue(player.location, if (guard.hits == 0) Sound.ITEM_SHIELD_BREAK else Sound.ITEM_SHIELD_BLOCK,
                .9f, if (guard.hits == 0) 1.25f else .9f)
        }
        if (guard.hits <= 0) guards.remove(player.uniqueId)
    }

    private fun current(player: Player, guard: Guard): Boolean = tick < guard.ends && guard.hits > 0 &&
        player.world.uid == guard.worldId && StaffSpell.from(player.inventory.itemInMainHand) == guard.spell &&
        damage.isCurrent(player, guard.cast)

    private fun current(player: Player, shield: Shield): Boolean = tick < shield.ends && shield.remaining > 0.0 &&
        player.world.uid == shield.worldId && StaffSpell.from(player.inventory.itemInMainHand) == shield.spell &&
        damage.isCurrent(player, shield.cast)

    private fun update() {
        guards.toMap().forEach { (id, guard) ->
            val player = Bukkit.getPlayer(id)
            if (player == null || !current(player, guard)) guards.remove(id)
            else frame(player, guard, impact = false)
        }
        shields.toMap().forEach { (id, shield) ->
            val player = Bukkit.getPlayer(id)
            if (player == null || !current(player, shield)) shields.remove(id)
            else frame(player, shield, impact = false)
        }
        recovery.keys.toList().forEach { id ->
            val player = Bukkit.getPlayer(id)
            if (player == null || !player.isOnline || player.isDead ||
                StaffSpell.from(player.inventory.itemInMainHand) == null) hide(id)
            else show(player)
            if ((recovery[id]?.ends ?: 0) <= tick) { recovery.remove(id); hide(id) }
        }
    }

    private fun frame(player: Player, guard: Guard, impact: Boolean) {
        renderFrame(player, staffDefenseParticles(guard.spell, tick, allAround = false, impact = impact))
    }

    private fun frame(player: Player, shield: Shield, impact: Boolean, depleted: Boolean = false) {
        val age = tick - shield.started
        renderFrame(player, staffDefenseParticles(shield.spell, age, allAround = true,
            impact = impact, depleted = depleted))
    }

    private fun renderFrame(player: Player, particles: List<StaffParticleSample>) {
        val forward = player.eyeLocation.direction.setY(0.0).apply { if (lengthSquared() < .001) z = 1.0 }.normalize()
        val right = Vector(forward.z, 0.0, -forward.x)
        val samples = particles.map { sample ->
            sample.copy(offset = right.clone().multiply(sample.offset.x).add(Vector(0.0, sample.offset.y, 0.0))
                .add(forward.clone().multiply(sample.offset.z)))
        }
        visuals.withOwner(player.uniqueId) { visuals.particles(player.location, samples) }
    }

    private fun show(player: Player) {
        val state = recovery[player.uniqueId] ?: return
        val remaining = (state.ends - tick).coerceAtLeast(0)
        val shield = shields[player.uniqueId]?.takeIf { current(player, it) }
        val guard = guards[player.uniqueId]?.takeIf { current(player, it) }
        val active = shield != null || guard != null
        val title = shield?.let { config.shield(it.spell, it.remaining, it.capacity) }
            ?: config.defense(state.spell, active, remaining / 20.0)
        val progress = when {
            shield != null -> (shield.remaining / shield.capacity).toFloat().coerceIn(0f, 1f)
            guard != null -> 1f
            else -> (1f - remaining.toFloat() / state.duration).coerceIn(0f, 1f)
        }
        val bar = bars.getOrPut(player.uniqueId) {
            BossBar.bossBar(title, progress, BossBar.Color.WHITE, BossBar.Overlay.PROGRESS).also(player::showBossBar)
        }
        val color = when {
            shield != null -> staffDefenseBarColor(shield.spell)
            guard != null -> BossBar.Color.YELLOW
            else -> BossBar.Color.WHITE
        }
        bar.name(title); bar.progress(progress); bar.color(color)
    }

    private fun hide(id: UUID) { bars.remove(id)?.let { bar -> Bukkit.getPlayer(id)?.hideBossBar(bar) } }
    fun cancel(id: UUID) { guards.remove(id); shields.remove(id); hide(id) }
    fun reset(id: UUID) { cancel(id); recovery.remove(id) }
    override fun close() { tasks.close(); guards.clear(); shields.clear(); recovery.clear(); bars.keys.toList().forEach(::hide) }

    private companion object {
        const val SHIELD_CAPACITY = 8.0
        const val SHIELD_DURATION_TICKS = 60
        const val SHIELD_COOLDOWN_TICKS = 160
    }
}

private const val STAFF_DAMAGE_EPSILON = 0.00001

/** Remove the native absorption modifier so shield capacity is charged before vanilla absorption hearts. */
@Suppress("DEPRECATION")
private fun staffPreAbsorptionDamage(event: EntityDamageEvent): Double {
    val absorption = if (event.isApplicable(DamageModifier.ABSORPTION))
        event.getDamage(DamageModifier.ABSORPTION) else 0.0
    return event.finalDamage - absorption
}

/** Paper adjusts raw damage and recalculates armor/resistance; invert that mapping while preserving vanilla absorption. */
@Suppress("DEPRECATION")
private fun staffSetPreAbsorptionDamage(event: EntityDamageEvent, desiredPreAbsorptionDamage: Double): Boolean {
    val originalBase = event.damage
    val originalPreAbsorption = staffPreAbsorptionDamage(event)
    if (!originalBase.isFinite() || originalBase <= 0.0 || !desiredPreAbsorptionDamage.isFinite() ||
        desiredPreAbsorptionDamage <= 0.0 || desiredPreAbsorptionDamage >= originalPreAbsorption) return false

    event.damage = 0.0
    val zeroBasePreAbsorption = staffPreAbsorptionDamage(event)
    if (!zeroBasePreAbsorption.isFinite() || zeroBasePreAbsorption > desiredPreAbsorptionDamage) {
        event.damage = originalBase
        return false
    }

    var low = 0.0
    var high = originalBase
    repeat(32) {
        val candidate = (low + high) / 2.0
        event.damage = candidate
        val candidatePreAbsorption = staffPreAbsorptionDamage(event)
        if (!candidatePreAbsorption.isFinite()) {
            event.damage = originalBase
            return false
        }
        if (candidatePreAbsorption > desiredPreAbsorptionDamage) high = candidate else low = candidate
    }
    val selected = listOf(low, high).minBy { candidate ->
        event.damage = candidate
        abs(staffPreAbsorptionDamage(event) - desiredPreAbsorptionDamage)
    }
    event.damage = selected
    if (abs(staffPreAbsorptionDamage(event) - desiredPreAbsorptionDamage) <= STAFF_DAMAGE_EPSILON) return true
    event.damage = originalBase
    return false
}

internal fun staffGuardFaces(look: Vector, incoming: Vector, allAround: Boolean): Boolean {
    if (allAround) return true
    if (!look.lengthSquared().isFinite() || !incoming.lengthSquared().isFinite()) return false
    if (incoming.lengthSquared() < .0001) return true
    return look.clone().normalize().dot(incoming.clone().normalize()) >= .25 // About 150 degrees in front.
}

private fun staffDefenseBarColor(spell: StaffSpell): BossBar.Color = when (spell) {
    StaffSpell.EMBER, StaffSpell.INFERNO -> BossBar.Color.RED
    StaffSpell.FROST, StaffSpell.ICICLE, StaffSpell.TIDE, StaffSpell.LANCE -> BossBar.Color.BLUE
    StaffSpell.NOVA, StaffSpell.CHAIN, StaffSpell.GALE -> BossBar.Color.GREEN
    StaffSpell.FAULT, StaffSpell.GRAVITY, StaffSpell.OSSUARY, StaffSpell.NECROMANCER -> BossBar.Color.YELLOW
    StaffSpell.MARK, StaffSpell.REQUIEM, StaffSpell.LUNAR, StaffSpell.PRISM -> BossBar.Color.PURPLE
}

/** Sparse seams define the barrier; the crosshair stays open and no full-screen dust sheet accumulates. */
internal fun staffDefenseParticles(
    spell: StaffSpell,
    age: Int,
    allAround: Boolean,
    impact: Boolean = false,
    depleted: Boolean = false,
): List<StaffParticleSample> {
    val color = when (spell) {
        StaffSpell.EMBER, StaffSpell.INFERNO -> 0xffae52
        StaffSpell.FROST, StaffSpell.ICICLE, StaffSpell.TIDE -> 0x8ceeff
        StaffSpell.NOVA -> 0x9bdb57
        StaffSpell.FAULT, StaffSpell.GRAVITY, StaffSpell.OSSUARY, StaffSpell.NECROMANCER -> 0xe4d4b2
        StaffSpell.MARK, StaffSpell.REQUIEM, StaffSpell.LUNAR, StaffSpell.PRISM -> 0xc9a1fa
        StaffSpell.LANCE -> 0xffe79b
        else -> 0xc2eafa
    }
    val samples = mutableListOf<StaffParticleSample>()
    if (allAround) {
        val radius = 1.48 + sin(age * PI / 12.0) * .045
        val centerY = .95
        val shell = mutableListOf<Vector>()
        for (latitude in listOf(-.62, 0.0, .62)) {
            val ringRadius = sqrt(radius * radius - latitude * latitude)
            repeat(24) { i ->
                val angle = i * PI * 2.0 / 24
                shell += Vector(cos(angle) * ringRadius, centerY + latitude, sin(angle) * ringRadius)
            }
        }
        val longitudes = listOf(0.0, PI / 2.0, PI, PI * 1.5)
        longitudes.forEach { longitude ->
            repeat(11) { i ->
                val latitude = -PI / 3.0 + i * (PI / 15.0)
                val ringRadius = radius * cos(latitude)
                shell += Vector(sin(longitude) * ringRadius, centerY + sin(latitude) * radius,
                    cos(longitude) * ringRadius)
            }
        }
        shell.forEachIndexed { i, point ->
            // Leave a broad forward cap open so the moving shell frames the player instead of the crosshair.
            if (point.z > radius * .78 && abs(point.y - centerY) < .7) return@forEachIndexed
            samples += StaffParticleSample(Particle.DUST_COLOR_TRANSITION, point,
                data = staffFade(color, color and 0xbfbfbf, if (impact) 1.65f else 1.15f))
            if (impact && i % 4 == 0) {
                samples += StaffParticleSample(Particle.ELECTRIC_SPARK, point, 2, Vector(.08, .08, .08), .035)
            }
            if (depleted && i % 6 == 0) {
                samples += StaffParticleSample(Particle.ELECTRIC_SPARK, point, 3, Vector(.16, .16, .16), .08)
            }
        }
        return samples
    }

    val count = 36
    repeat(count) { i ->
        val a = i * PI * 2 / count
        val at = Vector(cos(a) * 1.15, 1.25 + sin(a) * 1.1, 2.0)
        samples += StaffParticleSample(Particle.DUST_COLOR_TRANSITION, at,
            data = staffFade(color, color and 0xbfbfbf, if (impact) 1.7f else 1.1f))
        if (impact && i % 4 == 0) samples += StaffParticleSample(Particle.ELECTRIC_SPARK, at, 2, Vector(.08, .08, .08), .03)
    }
    return samples
}
