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
import kotlin.math.sin

/** A short, visible guard with a separate cooldown; it never enables an otherwise forbidden hit. */
internal class StaffSpellDefense(
    private val config: StaffSpellConfig,
    private val damage: StaffSpellDamage,
    private val visuals: StaffSpellVisuals,
    private val effects: StaffSpellDisplayEffects,
) : Listener, AutoCloseable {
    private data class Guard(val spell: StaffSpell, val cast: StaffSpellCast, val worldId: UUID,
        val ends: Int, var hits: Int, val capacity: Int, val scene: UUID?)
    private data class Shield(
        val spell: StaffSpell,
        val cast: StaffSpellCast,
        val worldId: UUID,
        val started: Int,
        val ends: Int,
        val capacity: Double,
        var remaining: Double,
        val scene: UUID?,
    )
    private data class Recovery(val spell: StaffSpell, val ends: Int, val duration: Int, val allAround: Boolean)
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
        val scene = effects.defend(player, spell, duration, allAround)
        guards.remove(player.uniqueId)?.let { effects.remove(it.scene) }
        shields.remove(player.uniqueId)?.let { effects.remove(it.scene) }
        if (allAround) {
            shields[player.uniqueId] = Shield(spell, cast, player.world.uid, tick,
                tick + duration, SHIELD_CAPACITY, SHIELD_CAPACITY, scene)
        } else {
            guards[player.uniqueId] = Guard(spell, cast, player.world.uid, tick + duration,
                hits = if (heavy) 3 else 2, capacity = if (heavy) 3 else 2, scene = scene)
        }
        recovery[player.uniqueId] = Recovery(spell, tick + cooldown, cooldown, allAround)
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
                effects.remove(shield.scene)
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
            effects.defenseImpact(shield.scene, shield.remaining / shield.capacity, depleted)
            renderFrame(player, staffDefenseParticles(shield.spell, tick - shield.started,
                allAround = true, impact = true, depleted = depleted))
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
        effects.defenseImpact(guard.scene, guard.hits.toDouble() / guard.capacity, guard.hits <= 0)
        renderFrame(player, staffDefenseParticles(guard.spell, tick, allAround = false,
            impact = true, depleted = guard.hits <= 0))
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
            if (player == null || !current(player, guard)) {
                guards.remove(id)
                effects.remove(guard.scene)
            }
        }
        shields.toMap().forEach { (id, shield) ->
            val player = Bukkit.getPlayer(id)
            if (player == null || !current(player, shield)) {
                shields.remove(id)
                effects.remove(shield.scene)
            }
        }
        recovery.keys.toList().forEach { id ->
            val player = Bukkit.getPlayer(id)
            if (player == null || !player.isOnline || player.isDead ||
                StaffSpell.from(player.inventory.itemInMainHand) == null) hide(id)
            else show(player)
            if ((recovery[id]?.ends ?: 0) <= tick) { recovery.remove(id); hide(id) }
        }
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
            ?: config.defense(state.spell, active, remaining / 20.0, state.allAround)
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
    fun cancel(id: UUID) {
        guards.remove(id)?.let { effects.remove(it.scene) }
        shields.remove(id)?.let { effects.remove(it.scene) }
        hide(id)
    }
    fun reset(id: UUID) { cancel(id); recovery.remove(id) }
    override fun close() {
        tasks.close()
        (guards.keys + shields.keys).toSet().forEach(::cancel)
        recovery.clear()
        bars.keys.toList().forEach(::hide)
    }

    private companion object {
        const val SHIELD_CAPACITY = 8.0
        const val SHIELD_DURATION_TICKS = 400
        const val SHIELD_COOLDOWN_TICKS = 500
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

/** The solid shield owns idle visibility; particles are a short contact cue only. */
internal fun staffDefenseParticles(
    spell: StaffSpell,
    age: Int,
    allAround: Boolean,
    impact: Boolean = false,
    depleted: Boolean = false,
): List<StaffParticleSample> {
    if (!impact) return emptyList()
    val particle = when (spell) {
        StaffSpell.EMBER, StaffSpell.INFERNO -> Particle.FLAME
        StaffSpell.OSSUARY, StaffSpell.NECROMANCER, StaffSpell.FAULT, StaffSpell.GRAVITY -> Particle.CRIT
        else -> Particle.ELECTRIC_SPARK
    }
    val count = if (depleted) 12 else 6
    return List(count) { i ->
        val angle = i * PI * 2 / count + age * .07
        val point = if (allAround) Vector(cos(angle) * 1.7, .85 + (i % 3) * .25, sin(angle) * 1.7)
            else Vector(cos(angle) * .72, 1.10 + sin(angle) * .78, 1.96)
        StaffParticleSample(particle, point, 1, Vector(.035, .035, .035), if (depleted) .075 else .025)
    }
}
