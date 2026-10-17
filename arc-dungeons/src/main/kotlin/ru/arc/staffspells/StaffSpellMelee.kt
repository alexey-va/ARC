package ru.arc.staffspells

import com.magmaguy.elitemobs.skills.MagicStrike
import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.Sound
import org.bukkit.entity.LivingEntity
import org.bukkit.entity.Player
import org.bukkit.potion.PotionEffect
import org.bukkit.potion.PotionEffectType
import org.bukkit.util.Vector
import ru.arc.core.LifecycleTaskScope
import ru.arc.core.ScheduledTask
import java.util.UUID
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

internal const val STAFF_FRONTAL_DURATION_TICKS = 16
internal const val STAFF_FRONTAL_CONTACT_TICK = 6L

internal fun staffMeleeDuration(spell: StaffSpell): Int = when (spell) {
    StaffSpell.FAULT -> 24
    StaffSpell.LUNAR -> 20
    StaffSpell.TIDE -> 20
    else -> STAFF_FRONTAL_DURATION_TICKS
}

/** Only spells with an authored broad frontal impact replace their ranged primary. */
internal fun staffMeleeReach(spell: StaffSpell): Double? = when (spell) {
    StaffSpell.FROST, StaffSpell.FAULT, StaffSpell.LUNAR -> 7.0
    else -> null
}

/** Shared contact/lifecycle owner; each spell keeps its own substantial visual silhouette. */
internal class StaffSpellMelee(
    private val settings: StaffSpellSettings,
    private val damage: StaffSpellDamage,
    private val effects: StaffSpellDisplayEffects,
    private val visuals: StaffSpellVisuals,
    private val targeting: StaffSpellTargeting,
    private val tasks: LifecycleTaskScope = LifecycleTaskScope(),
    internal var onFrostSlow: (Player, LivingEntity, Int) -> Unit = { _, _, _ -> },
) : AutoCloseable {
    private data class Swing(
        val cast: StaffSpellCast,
        val spell: StaffSpell,
        val tuning: StaffSpellTuning,
        val eye: Location,
        val horizontal: Vector,
        val range: Double,
        val duration: Int,
        val scene: UUID?,
        val handles: MutableList<ScheduledTask> = mutableListOf(),
    )

    private val pending = mutableMapOf<UUID, Swing>()
    private var closed = false

    fun cast(player: Player, cast: StaffSpellCast, spell: StaffSpell, tuning: StaffSpellTuning): Boolean {
        val range = staffMeleeReach(spell) ?: return false
        if (closed || !ready(player) || !damage.isCurrent(player, cast)) return false
        cancel(player.uniqueId)
        val eye = player.eyeLocation.clone()
        val assistedAim = targeting.assistedDirection(player, spell, cast,
            direction = eye.direction, from = eye, range = range)
        val forward = horizontalDirection(assistedAim, eye.yaw) ?: return false
        val origin = eye.clone().subtract(0.0, 0.25, 0.0)
        val duration = staffMeleeDuration(spell)
        val scene = effects.melee(player.uniqueId, spell, origin,
            origin.clone().add(forward.clone().multiply(range)), duration)
        val swing = Swing(cast, spell, tuning, eye, forward, range, duration, scene)
        pending[player.uniqueId] = swing
        visuals.withOwner(player.uniqueId) {
            visuals.cue(origin.clone().add(forward.clone().multiply(2.0)),
                launchSound(spell), if (spell == StaffSpell.FAULT) 0.85f else 0.65f,
                if (spell == StaffSpell.FAULT) 0.68f else 1.08f)
            frame(swing, 0)
        }
        for (age in 2 until duration step 2) schedule(swing, age.toLong()) {
            visuals.withOwner(player.uniqueId) { frame(swing, age) }
        }
        // Age-four full-size keyframe reaches the client at six with two-tick interpolation.
        schedule(swing, STAFF_FRONTAL_CONTACT_TICK) { contact(player, swing) }
        schedule(swing, duration.toLong()) { cancel(player.uniqueId) }
        return true
    }

    fun cancel(casterId: UUID) {
        val swing = pending.remove(casterId) ?: return
        swing.handles.toList().forEach(ScheduledTask::cancel)
        swing.handles.clear()
        effects.remove(swing.scene)
    }

    override fun close() {
        if (closed) return
        closed = true
        pending.keys.toList().forEach(::cancel)
        tasks.close()
    }

    private fun contact(player: Player, swing: Swing) {
        // Query at contact: entering/leaving the visibly growing area has a real consequence.
        val targets = targeting.nearby(player, swing.eye, swing.range, swing.cast)
            .filter { inArea(swing, it) && visible(swing.eye, it) && visible(player.eyeLocation, it) }
            .sortedBy { center(it).distanceSquared(swing.eye) }
            .take(settings.maxAreaTargets.coerceIn(1, 3))
        visuals.withOwner(player.uniqueId) {
            val at = swing.eye.clone().add(swing.horizontal.clone().multiply(4.0))
            visuals.cue(at, attackSound(swing.spell), if (swing.spell == StaffSpell.FAULT) 1.0f else 0.88f,
                if (swing.spell == StaffSpell.FAULT) 0.62f else 0.78f)
            val accent = when (swing.spell) {
                StaffSpell.FROST -> Sound.BLOCK_AMETHYST_BLOCK_RESONATE
                StaffSpell.FAULT -> Sound.BLOCK_DEEPSLATE_BREAK
                StaffSpell.LUNAR -> Sound.BLOCK_AMETHYST_BLOCK_CHIME
                else -> Sound.BLOCK_BUBBLE_COLUMN_UPWARDS_AMBIENT
            }
            visuals.cue(at, accent, if (swing.spell == StaffSpell.FAULT) 0.90f else 0.54f,
                if (swing.spell == StaffSpell.FAULT) 0.58f else 1.28f)
            if (swing.spell == StaffSpell.TIDE)
                visuals.cue(at, Sound.ITEM_TRIDENT_RIPTIDE_1, .62f, .68f)
        }
        for (target in targets) {
            if (pending[player.uniqueId] !== swing || !current(player, swing)) break
            if (!target.isValid || target.isDead || target.isInvulnerable || !inArea(swing, target) ||
                !visible(swing.eye, target) || !visible(player.eyeLocation, target) ||
                !damage.eligible(player, target, swing.cast)) continue
            val hit = if (swing.cast.nativeEliteWeapon && swing.cast.eliteMobsEnabled &&
                swing.cast.duelAuthorization == null && swing.cast.progressionSkill == StaffWeaponFamily.STAVES) {
                damage.hit(player, target, swing.cast, swing.tuning.power, swing.tuning.vanillaDamage,
                    magicStrike = MagicStrike.STAFF_MELEE)
            } else {
                damage.hit(player, target, swing.cast, swing.tuning.power, swing.tuning.vanillaDamage)
            }
            if (!hit) continue
            if (pending[player.uniqueId] !== swing || !current(player, swing)) break
            if (!damage.eligible(player, target, swing.cast)) continue
            if (swing.spell == StaffSpell.FROST &&
                target.addPotionEffect(PotionEffect(PotionEffectType.SLOWNESS, 16, 0, false, false)) &&
                pending[player.uniqueId] === swing && current(player, swing) &&
                damage.eligible(player, target, swing.cast) && visible(player.eyeLocation, target)) {
                onFrostSlow(player, target, 16)
            }
            val push = when (swing.spell) { StaffSpell.FAULT -> 0.42; StaffSpell.TIDE -> 0.35; else -> 0.0 }
            if (push > 0.0) target.velocity = target.velocity.clone().add(swing.horizontal.clone().multiply(push))
        }
    }

    private fun frame(swing: Swing, age: Int) {
        val origin = swing.eye.clone().subtract(0.0, 0.25, 0.0)
        val right = Vector(swing.horizontal.z, 0.0, -swing.horizontal.x)
        val samples = staffMeleeParticles(swing.spell, age, swing.duration, swing.range).map { sample ->
            sample.copy(offset = right.clone().multiply(sample.offset.x)
                .add(Vector(0.0, sample.offset.y, 0.0))
                .add(swing.horizontal.clone().multiply(sample.offset.z)))
        }.filter { visible(swing.eye, origin.clone().add(it.offset)) }
        visuals.particles(origin, samples, clearance = 1.15)
        if (age == swing.duration - 2) {
            val tail = when (swing.spell) {
                StaffSpell.FROST -> Sound.BLOCK_POWDER_SNOW_BREAK
                StaffSpell.FAULT -> Sound.BLOCK_GRAVEL_BREAK
                StaffSpell.LUNAR -> Sound.BLOCK_AMETHYST_CLUSTER_HIT
                else -> Sound.ENTITY_PLAYER_SWIM
            }
            visuals.cue(origin.clone().add(swing.horizontal.clone().multiply(4.5)), tail, if (swing.spell == StaffSpell.FAULT) 0.6f else 0.34f,
                if (swing.spell == StaffSpell.FAULT) 0.70f else 1.38f)
        }
    }

    private fun inArea(swing: Swing, entity: LivingEntity): Boolean {
        if (entity.world != swing.eye.world) return false
        val offset = center(entity).toVector().subtract(swing.eye.toVector())
        val distanceSquared = offset.lengthSquared()
        if (!distanceSquared.isFinite() || distanceSquared > swing.range * swing.range || abs(offset.y) > 2.5) return false
        val flat = sqrt(offset.x * offset.x + offset.z * offset.z)
        if (flat < 1.0e-6) return false
        val forward = offset.x * swing.horizontal.x + offset.z * swing.horizontal.z
        return forward / flat >= cos(Math.toRadians(55.0))
    }

    private fun schedule(swing: Swing, delay: Long, action: () -> Unit) {
        var handle: ScheduledTask? = null
        handle = tasks.runLater(delay) {
            handle?.let(swing.handles::remove)
            if (pending[swing.cast.casterId] !== swing) return@runLater
            val player = Bukkit.getPlayer(swing.cast.casterId)
            if (player == null || !current(player, swing)) cancel(swing.cast.casterId) else action()
        }
        handle?.let(swing.handles::add)
    }

    private fun current(player: Player, swing: Swing) = ready(player) && player.world == swing.eye.world &&
        damage.isCurrent(player, swing.cast)

    private fun ready(player: Player) = player.isOnline && player.isValid && !player.isDead &&
        player.gameMode != org.bukkit.GameMode.SPECTATOR

    private fun horizontalDirection(direction: Vector, yaw: Float): Vector? {
        val flat = direction.clone().setY(0.0)
        if (!flat.lengthSquared().isFinite()) return null
        if (flat.lengthSquared() < 1.0e-12) {
            val radians = Math.toRadians(yaw.toDouble())
            return Vector(-sin(radians), 0.0, cos(radians))
        }
        return flat.normalize()
    }

    private fun attackSound(spell: StaffSpell) = when (spell) {
        StaffSpell.FROST -> Sound.BLOCK_GLASS_BREAK
        StaffSpell.FAULT -> Sound.ENTITY_GENERIC_EXPLODE
        StaffSpell.LUNAR -> Sound.ENTITY_PLAYER_ATTACK_SWEEP
        StaffSpell.TIDE -> Sound.ENTITY_PLAYER_SPLASH_HIGH_SPEED
        else -> Sound.ENTITY_PLAYER_ATTACK_SWEEP
    }

    private fun launchSound(spell: StaffSpell) = when (spell) {
        StaffSpell.FROST -> Sound.BLOCK_AMETHYST_CLUSTER_HIT
        StaffSpell.FAULT -> Sound.BLOCK_DEEPSLATE_PLACE
        StaffSpell.TIDE -> Sound.ENTITY_PLAYER_SPLASH
        else -> Sound.ENTITY_PLAYER_ATTACK_SWEEP
    }
}
