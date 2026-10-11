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
import org.bukkit.util.Vector
import ru.arc.core.LifecycleTaskScope
import java.util.UUID
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** A short, visible guard with a separate cooldown; it never enables an otherwise forbidden hit. */
internal class StaffSpellDefense(
    private val config: StaffSpellConfig,
    private val damage: StaffSpellDamage,
    private val visuals: StaffSpellVisuals,
) : Listener, AutoCloseable {
    private data class Guard(val spell: StaffSpell, val cast: StaffSpellCast, val worldId: UUID,
        val ends: Int, val allAround: Boolean, var hits: Int)
    private data class Recovery(val spell: StaffSpell, val ends: Int, val duration: Int)
    private val tasks = LifecycleTaskScope()
    private val guards = mutableMapOf<UUID, Guard>()
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
        val duration = if (allAround) 14 else if (heavy) 32 else 24
        val cooldown = if (allAround) 120 else 80
        guards[player.uniqueId] = Guard(spell, cast, player.world.uid, tick + duration,
            allAround, if (allAround) 1 else if (heavy) 3 else 2)
        recovery[player.uniqueId] = Recovery(spell, tick + cooldown, cooldown)
        frame(player, guards.getValue(player.uniqueId), impact = false)
        visuals.withOwner(player.uniqueId) { visuals.cue(player.location, Sound.ITEM_SHIELD_BLOCK, .7f, 1.25f) }
        show(player)
        return true
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onDamage(event: EntityDamageByEntityEvent) {
        val player = event.entity as? Player ?: return
        val guard = guards[player.uniqueId] ?: return
        if (event.damage <= 0 || !current(player, guard)) return
        val projectile = event.damager as? Projectile
        val enemy = (projectile?.shooter as? LivingEntity) ?: event.damager as? LivingEntity ?: return
        if (enemy.uniqueId == player.uniqueId || enemy.world != player.world) return
        val incoming = if (projectile != null && projectile.velocity.lengthSquared() > .0001)
            projectile.velocity.clone().multiply(-1.0)
        else enemy.eyeLocation.toVector().subtract(player.eyeLocation.toVector())
        if (!staffGuardFaces(player.eyeLocation.direction, incoming, guard.allAround)) return
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

    private fun update() {
        guards.toMap().forEach { (id, guard) ->
            val player = Bukkit.getPlayer(id)
            if (player == null || !current(player, guard)) guards.remove(id)
            else frame(player, guard, impact = false)
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
        val forward = player.eyeLocation.direction.setY(0.0).apply { if (lengthSquared() < .001) z = 1.0 }.normalize()
        val right = Vector(forward.z, 0.0, -forward.x)
        val samples = staffDefenseParticles(guard.spell, tick, guard.allAround, impact).map { sample ->
            sample.copy(offset = right.clone().multiply(sample.offset.x).add(Vector(0.0, sample.offset.y, 0.0))
                .add(forward.clone().multiply(sample.offset.z)))
        }
        visuals.withOwner(player.uniqueId) { visuals.particles(player.location, samples) }
    }

    private fun show(player: Player) {
        val state = recovery[player.uniqueId] ?: return
        val remaining = (state.ends - tick).coerceAtLeast(0)
        val active = guards[player.uniqueId]?.let { current(player, it) } == true
        val title = config.defense(state.spell, active, remaining / 20.0)
        val progress = if (active) 1f else (1f - remaining.toFloat() / state.duration).coerceIn(0f, 1f)
        val bar = bars.getOrPut(player.uniqueId) {
            BossBar.bossBar(title, progress, BossBar.Color.WHITE, BossBar.Overlay.PROGRESS).also(player::showBossBar)
        }
        bar.name(title); bar.progress(progress); bar.color(if (active) BossBar.Color.YELLOW else BossBar.Color.WHITE)
    }

    private fun hide(id: UUID) { bars.remove(id)?.let { bar -> Bukkit.getPlayer(id)?.hideBossBar(bar) } }
    fun cancel(id: UUID) { guards.remove(id); hide(id) }
    fun reset(id: UUID) { cancel(id); recovery.remove(id) }
    override fun close() { tasks.close(); guards.clear(); recovery.clear(); bars.keys.toList().forEach(::hide) }
}

internal fun staffGuardFaces(look: Vector, incoming: Vector, allAround: Boolean): Boolean {
    if (allAround) return true
    if (!look.lengthSquared().isFinite() || !incoming.lengthSquared().isFinite()) return false
    if (incoming.lengthSquared() < .0001) return true
    return look.clone().normalize().dot(incoming.clone().normalize()) >= .25 // About 150 degrees in front.
}

/** Sparse seams define the barrier; the crosshair stays open and no full-screen dust sheet accumulates. */
internal fun staffDefenseParticles(spell: StaffSpell, age: Int, allAround: Boolean, impact: Boolean = false): List<StaffParticleSample> {
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
    val count = if (allAround) 48 else 36
    repeat(count) { i ->
        val a = i * PI * 2 / count
        val at = if (allAround) Vector(cos(a) * 1.85, .45 + (i % 3) * .55, sin(a) * 1.85)
        else Vector(cos(a) * 1.15, 1.25 + sin(a) * 1.1, 2.0)
        samples += StaffParticleSample(Particle.DUST_COLOR_TRANSITION, at,
            data = staffFade(color, color and 0xbfbfbf, if (impact) 1.7f else 1.1f))
        if (impact && i % 4 == 0) samples += StaffParticleSample(Particle.ELECTRIC_SPARK, at, 2, Vector(.08, .08, .08), .03)
    }
    return samples
}
