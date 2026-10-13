package ru.arc.staffspells

import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.Particle
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
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/** Cast-time horizontal sweep endpoint, before the live block ray clips it. */
internal fun staffMeleeSweepEndpoint(forward: Vector, progress: Double, halfAngleDegrees: Double,
    range: Double): Vector? {
    val flat = Vector(forward.x, 0.0, forward.z)
    val magnitudeSquared = flat.lengthSquared()
    if (!magnitudeSquared.isFinite() || magnitudeSquared < 1.0e-12 ||
        !progress.isFinite() || !halfAngleDegrees.isFinite() || !range.isFinite() || range <= 0.0) return null
    val halfAngle = halfAngleDegrees.coerceIn(0.0, 85.0)
    val angle = Math.toRadians(-halfAngle + 2.0 * halfAngle * progress.coerceIn(0.0, 1.0))
    return flat.normalize().rotateAroundY(angle).multiply(range)
}

/** One contact owner for every close primary; alternate spells keep their own owner. */
internal fun staffMeleeReach(spell: StaffSpell): Double? = when (spell) {
    StaffSpell.FAULT, StaffSpell.PRISM, StaffSpell.OSSUARY -> 5.0
    StaffSpell.LANCE, StaffSpell.MARK, StaffSpell.FROST, StaffSpell.NOVA,
    StaffSpell.LUNAR, StaffSpell.GALE, StaffSpell.TIDE -> 4.5
    else -> null
}

internal class StaffSpellMelee(
    private val settings: StaffSpellSettings,
    private val damage: StaffSpellDamage,
    private val effects: StaffSpellDisplayEffects,
    private val visuals: StaffSpellVisuals,
    private val targeting: StaffSpellTargeting,
    private val tasks: LifecycleTaskScope = LifecycleTaskScope(),
) : AutoCloseable {
    private data class Swing(
        val casterId: UUID,
        val worldId: UUID,
        val spell: StaffSpell,
        val cast: StaffSpellCast,
        val tuning: StaffSpellTuning,
        val eye: Location,
        val horizontal: Vector,
        val right: Vector,
        val targets: List<LivingEntity>,
        var faultPath: FaultPath?,
        val range: Double,
        val halfAngle: Double,
        val taskHandles: MutableList<ScheduledTask> = mutableListOf(),
    )

    private data class FaultPath(
        val base: Location,
        val origin: Location,
        val end: Location,
        val clippedRange: Double,
    )

    private val pending = mutableMapOf<UUID, Swing>()
    private val scenesByCaster = mutableMapOf<UUID, MutableSet<UUID>>()
    private var closed = false

    /** True means a supported primary cast was accepted, including an empty-air swing. */
    fun cast(player: Player, cast: StaffSpellCast, spell: StaffSpell, tuning: StaffSpellTuning): Boolean {
        val range = staffMeleeReach(spell) ?: return false
        if (closed || !ready(player) || !damage.isCurrent(player, cast)) return false

        pending[player.uniqueId]?.let(::discard)
        val eye = player.eyeLocation.clone()
        val horizontal = horizontalDirection(eye.direction, eye.yaw) ?: return false
        val halfAngle = if (spell == StaffSpell.LANCE) LANCE_HALF_ANGLE else 25.0
        val faultPath = if (spell == StaffSpell.FAULT)
            faultWavePath(player.location, eye, horizontal, range) else null
        val visibleRange = faultPath?.clippedRange ?: range
        val targets = targeting.nearby(player, eye, range, cast)
            .filter { inContactArea(eye, horizontal, it, spell, range, visibleRange, halfAngle) }
            .filter { visible(eye, center(it)) }
            .sortedBy { center(it).distanceSquared(eye) }
            .take((if (spell == StaffSpell.LANCE) settings.lanceTargets else settings.maxAreaTargets)
                .coerceIn(1, MAX_TARGETS))

        val right = Vector(horizontal.z, 0.0, -horizontal.x).normalize()
        val swing = Swing(player.uniqueId, player.world.uid, spell, cast, tuning, eye,
            horizontal, right, targets, faultPath, range, halfAngle)
        pending[player.uniqueId] = swing

        if (spell == StaffSpell.LANCE) {
            visuals.withOwner(player.uniqueId) {
                visuals.cue(eye.clone().add(horizontal.clone().multiply(LANCE_MIN_VISUAL_DISTANCE)),
                    Sound.ENTITY_PLAYER_ATTACK_SWEEP, 0.28f, 1.35f)
                lanceFrame(swing, 0.0)
            }
            schedule(swing, 1) { visuals.withOwner(swing.casterId) { lanceFrame(swing, 0.5) } }
            schedule(swing, 2) { visuals.withOwner(swing.casterId) { lanceFrame(swing, 1.0) } }
        } else if (spell == StaffSpell.FAULT) {
            visuals.withOwner(player.uniqueId) {
                visuals.cue(requireNotNull(faultPath).origin, Sound.BLOCK_STONE_PLACE, 0.32f, 0.72f)
                faultFrame(swing, 0.16)
            }
            schedule(swing, 1) { visuals.withOwner(swing.casterId) { faultFrame(swing, 0.48) } }
            schedule(swing, 2) { visuals.withOwner(swing.casterId) { faultFrame(swing, 0.78) } }
        } else {
            val origin = eye.clone().subtract(0.0, 0.25, 0.0)
            trackScene(player.uniqueId, effects.melee(player.uniqueId, spell, origin,
                origin.clone().add(horizontal.clone().multiply(range)), DURATION_TICKS))
            visuals.withOwner(player.uniqueId) {
                val (sound, pitch) = attackSound(spell)
                visuals.cue(origin.clone().add(horizontal.clone().multiply(1.6)), sound, 0.48f, pitch)
                themedFrame(swing, 0)
            }
            for (age in 2 until DURATION_TICKS step 2) schedule(swing, age.toLong()) {
                visuals.withOwner(swing.casterId) { themedFrame(swing, age) }
            }
        }
        val contactTick = if (spell == StaffSpell.LANCE || spell == StaffSpell.FAULT) CONTACT_TICKS else 4L
        schedule(swing, contactTick) { contact(swing) }
        schedule(swing, DURATION_TICKS.toLong()) { finish(swing) }
        return true
    }

    /** Cancels this caster's pending contact and every display scene owned by this attack. */
    fun cancel(casterId: UUID) {
        pending[casterId]?.let(::discard)
        scenesByCaster.remove(casterId)?.toList()?.forEach(effects::remove)
    }

    override fun close() {
        if (closed) return
        closed = true
        pending.keys.toList().forEach(::cancel)
        scenesByCaster.keys.toList().forEach(::cancel)
        tasks.close()
        pending.clear()
        scenesByCaster.clear()
    }

    private fun contact(swing: Swing) {
        val player = Bukkit.getPlayer(swing.casterId)
        if (player == null || !current(player, swing)) {
            discard(swing)
            return
        }

        if (swing.spell == StaffSpell.FAULT) faultImpact(swing)
        var firstHit = true
        for (target in swing.targets) {
            if (pending[swing.casterId] !== swing || !current(player, swing)) break
            if (!target.isValid || target.isDead || target.isInvulnerable || target.world.uid != swing.worldId ||
                !inContactArea(swing.eye, swing.horizontal, target, swing.spell, swing.range,
                    swing.faultPath?.clippedRange ?: swing.range, swing.halfAngle) ||
                !visible(swing.eye, center(target)) ||
                !visible(player.eyeLocation, center(target)) || !damage.eligible(player, target, swing.cast)) continue

            val hit = damage.hit(player, target, swing.cast, swing.tuning.power, swing.tuning.vanillaDamage)
            if (pending[swing.casterId] !== swing || !damage.isCurrent(player, swing.cast)) break
            if (!hit) continue

            if (swing.spell == StaffSpell.LANCE) lanceContact(swing, target)
            else if (swing.spell == StaffSpell.FAULT) {
                faultContact(swing, target)
                if (damage.eligible(player, target, swing.cast)) applyFaultKnockback(swing, target)
            } else themedContact(swing, player, target)
            if (firstHit) {
                firstHit = false
                if (swing.spell == StaffSpell.LANCE) visuals.withOwner(swing.casterId) {
                    visuals.cue(center(target), Sound.BLOCK_AMETHYST_CLUSTER_HIT, 0.42f, 1.45f)
                }
            }
        }
    }

    private fun themedFrame(swing: Swing, age: Int) {
        val origin = swing.eye.clone().subtract(0.0, 0.25, 0.0)
        val samples = staffMeleeParticles(swing.spell, age, DURATION_TICKS, swing.range).map { sample ->
            sample.copy(offset = swing.right.clone().multiply(sample.offset.x)
                .add(Vector(0.0, sample.offset.y, 0.0))
                .add(swing.horizontal.clone().multiply(sample.offset.z)))
        }.filter { visible(swing.eye, origin.clone().add(it.offset)) }
        visuals.particles(origin, samples, clearance = CAMERA_CLEARANCE)
    }

    private fun themedContact(swing: Swing, player: Player, target: LivingEntity) {
        if (!damage.eligible(player, target, swing.cast)) return
        val slow = when (swing.spell) {
            StaffSpell.FROST -> 16
            StaffSpell.NOVA -> 20
            else -> 0
        }
        if (slow > 0) target.addPotionEffect(PotionEffect(PotionEffectType.SLOWNESS, slow, 0, false, false))
        val push = when (swing.spell) {
            StaffSpell.MARK -> -0.18
            StaffSpell.GALE -> 0.60
            StaffSpell.TIDE -> 0.35
            else -> 0.0
        }
        if (push != 0.0) target.velocity = target.velocity.clone().add(swing.horizontal.clone().multiply(push))
        visuals.withOwner(swing.casterId) {
            val (sound, pitch) = attackSound(swing.spell)
            visuals.cue(center(target), sound, 0.35f, pitch * 0.85f)
        }
    }

    private fun attackSound(spell: StaffSpell): Pair<Sound, Float> = when (spell) {
        StaffSpell.MARK -> Sound.ENTITY_ENDERMAN_TELEPORT to 1.45f
        StaffSpell.FROST -> Sound.BLOCK_GLASS_BREAK to 1.35f
        StaffSpell.NOVA -> Sound.BLOCK_AZALEA_LEAVES_BREAK to 0.8f
        StaffSpell.LUNAR -> Sound.ENTITY_PLAYER_ATTACK_SWEEP to 0.8f
        StaffSpell.GALE -> Sound.ENTITY_BREEZE_WIND_BURST to 1.25f
        StaffSpell.TIDE -> Sound.ENTITY_PLAYER_SPLASH to 1.15f
        StaffSpell.PRISM -> Sound.BLOCK_AMETHYST_CLUSTER_BREAK to 1.6f
        StaffSpell.OSSUARY -> Sound.ENTITY_SKELETON_HURT to 0.75f
        else -> Sound.ENTITY_PLAYER_ATTACK_SWEEP to 1.0f
    }

    private fun lanceContact(swing: Swing, target: LivingEntity) {
        val at = target.location.clone().add(0.0, maxOf(0.12, target.height * 0.20), 0.0)
        val samples = listOf(
            StaffParticleSample(Particle.SWEEP_ATTACK, Vector(0.0, 0.18, 0.0)),
            StaffParticleSample(Particle.CRIT, Vector(0.0, 0.42, 0.0), count = 8,
                spread = Vector(0.24, 0.22, 0.24), speed = 0.08),
            StaffParticleSample(Particle.DUST_COLOR_TRANSITION, Vector(0.0, 0.72, 0.0), count = 4,
                spread = Vector(0.16, 0.12, 0.16), data = staffFade(LANCE_GOLD, LANCE_WHITE, 1.05f)),
        )
        visuals.withOwner(swing.casterId) { visuals.particles(at, samples, clearance = CAMERA_CLEARANCE) }

        val toward = at.toVector().subtract(swing.eye.toVector())
        if (toward.lengthSquared() > 0.01) {
            val direction = toward.normalize()
            val from = at.clone().subtract(direction.clone().multiply(BLADE_CONTACT_LENGTH))
            if (visible(swing.eye, from)) trackScene(swing.casterId,
                effects.play(swing.casterId, StaffSpell.LANCE, from, at,
                    radius = BLADE_DISPLAY_RADIUS + settings.lanceWidth.coerceIn(0.0, 0.35),
                    durationTicks = BLADE_DISPLAY_TICKS, impact = true, input = StaffCastInput.PRIMARY))
        }
    }

    private fun faultImpact(swing: Swing) {
        val at = requireNotNull(refreshFaultPath(swing)).end
        visuals.withOwner(swing.casterId) {
            faultFrame(swing, 1.0)
            visuals.faultBurst(at, FAULT_DISPLAY_RADIUS)
        }
        trackScene(swing.casterId, effects.play(swing.casterId, StaffSpell.FAULT, at,
            radius = FAULT_DISPLAY_RADIUS, durationTicks = FAULT_DISPLAY_TICKS, impact = true,
            input = StaffCastInput.PRIMARY))
    }

    private fun faultContact(swing: Swing, target: LivingEntity) {
        val at = target.location.clone().add(0.0, 0.12, 0.0)
        visuals.withOwner(swing.casterId) {
            visuals.particles(at, listOf(
                StaffParticleSample(Particle.BLOCK, Vector(0.0, 0.12, 0.0), count = 5,
                    spread = Vector(0.24, 0.05, 0.24), speed = 0.12, data = Material.BASALT.createBlockData()),
                StaffParticleSample(Particle.DUST_COLOR_TRANSITION, Vector(0.0, 0.16, 0.0), count = 5,
                    spread = Vector(0.3, 0.06, 0.3), data = staffFade(FAULT_AMETHYST, FAULT_STONE, 1.0f)),
            ), clearance = CAMERA_CLEARANCE)
        }
    }

    private fun applyFaultKnockback(swing: Swing, target: LivingEntity) {
        if (!target.isValid || target.isDead || target.world.uid != swing.worldId ||
            !damage.isCurrent(Bukkit.getPlayer(swing.casterId) ?: return, swing.cast)) return
        val push = swing.horizontal.clone().multiply(FAULT_KNOCKBACK)
        target.velocity = target.velocity.clone().add(push).apply { y = target.velocity.y }
    }

    private fun lanceFrame(swing: Swing, progress: Double) {
        val endpoint = staffMeleeSweepEndpoint(swing.horizontal, progress, swing.halfAngle, swing.range) ?: return
        val direction = endpoint.clone().normalize()
        val clipped = targeting.rayEnd(swing.eye, direction, swing.range)
        val visibleLength = swing.eye.distance(clipped)
        if (!visibleLength.isFinite() || visibleLength < LANCE_MIN_VISUAL_DISTANCE) return
        val usableLength = (visibleLength - LANCE_MIN_VISUAL_DISTANCE).coerceAtLeast(0.0)
        val samples = buildList {
            for (index in 0..4) {
                val distance = LANCE_MIN_VISUAL_DISTANCE + usableLength * index / 4.0
                val center = direction.clone().multiply(distance).add(Vector(0.0, -0.26, 0.0))
                for (side in -1..1) {
                    add(StaffParticleSample(
                        particle = if (index == 4 && side == 0) Particle.SWEEP_ATTACK else Particle.DUST_COLOR_TRANSITION,
                        offset = center.clone().add(swing.right.clone().multiply(side * LANCE_BLADE_HALF_WIDTH)),
                        count = if (index == 4 && side == 0) 1 else 2,
                        spread = if (index == 4 && side == 0) Vector() else Vector(0.025, 0.025, 0.025),
                        data = if (index == 4 && side == 0) null else staffFade(LANCE_GOLD, LANCE_WHITE, 1.15f),
                    ))
                }
            }
        }
        visuals.particles(swing.eye, samples, clearance = CAMERA_CLEARANCE)
    }

    private fun faultFrame(swing: Swing, progress: Double) {
        val path = refreshFaultPath(swing) ?: return
        val origin = path.origin
        val end = path.end
        val reach = origin.distance(end)
        val front = reach * progress.coerceIn(0.0, 1.0)
        val samples = buildList {
            val rows = 5
            for (row in 0..rows) {
                val along = if (rows == 0) front else front * row / rows
                for (side in -1..1) {
                    val offset = swing.horizontal.clone().multiply(along)
                        .add(swing.right.clone().multiply(side * FAULT_WAVE_HALF_WIDTH))
                        .add(Vector(0.0, 0.10 + if (row == rows) 0.08 else 0.0, 0.0))
                    add(StaffParticleSample(
                        particle = if (progress >= 0.78 && row == rows) Particle.BLOCK else Particle.CRIT,
                        offset = offset,
                        count = if (side == 0) 2 else 1,
                        spread = Vector(0.03, 0.025, 0.03),
                        speed = 0.03,
                        data = if (progress >= 0.78 && row == rows) Material.BASALT.createBlockData() else null,
                    ))
                }
            }
        }
        visuals.particles(origin, samples, clearance = CAMERA_CLEARANCE)
    }

    private fun faultWavePath(base: Location, eye: Location, direction: Vector, range: Double): FaultPath {
        val highEnd = targeting.rayEnd(eye, direction, range)
        val lowStart = eye.clone().subtract(0.0, FAULT_LOW_RAY_DROP, 0.0)
        val lowEnd = targeting.rayEnd(lowStart, direction, range)
        val clipped = min(eye.distance(highEnd), lowStart.distance(lowEnd)).coerceIn(0.0, range)
        val waveStartDistance = min(FAULT_WAVE_START, clipped)
        val groundBase = base.clone().add(0.0, FAULT_WAVE_Y, 0.0)
        val groundStart = groundBase.clone()
            .add(direction.clone().multiply(waveStartDistance))
        val groundEnd = groundBase.clone().add(direction.clone().multiply(clipped))
        return FaultPath(base.clone(), groundStart, groundEnd, clipped)
    }

    private fun refreshFaultPath(swing: Swing): FaultPath? {
        val previous = swing.faultPath ?: return null
        return faultWavePath(previous.base, swing.eye, swing.horizontal, swing.range).also { swing.faultPath = it }
    }

    private fun inContactArea(origin: Location, horizontal: Vector, entity: LivingEntity, spell: StaffSpell,
        range: Double, visibleRange: Double, halfAngle: Double): Boolean {
        if (entity.world != origin.world) return false
        val target = center(entity)
        val offset = target.toVector().subtract(origin.toVector())
        val distanceSquared = offset.lengthSquared()
        if (!distanceSquared.isFinite() || distanceSquared > range * range) return false
        if (abs(offset.y) > MAX_VERTICAL_DELTA) return false
        val flatLength = sqrt(offset.x * offset.x + offset.z * offset.z)
        if (!flatLength.isFinite() || flatLength < MIN_TARGET_DISTANCE) return false
        val forward = offset.x * horizontal.x + offset.z * horizontal.z
        if (!forward.isFinite() || forward < 0.0 || forward > visibleRange) return false
        if (spell in setOf(StaffSpell.FAULT, StaffSpell.PRISM, StaffSpell.OSSUARY)) {
            if (visibleRange < MIN_TARGET_DISTANCE) return false
            val bounds = entity.boundingBox
            val lateralRadius = 0.5 * ((bounds.maxX - bounds.minX) * abs(horizontal.z) +
                (bounds.maxZ - bounds.minZ) * abs(horizontal.x))
            val forwardRadius = 0.5 * ((bounds.maxX - bounds.minX) * abs(horizontal.x) +
                (bounds.maxZ - bounds.minZ) * abs(horizontal.z))
            val lateral = abs(offset.x * horizontal.z - offset.z * horizontal.x)
            val visibleStart = min(FAULT_WAVE_START, visibleRange)
            return lateral <= FAULT_WAVE_HALF_WIDTH + lateralRadius &&
                forward + forwardRadius >= visibleStart && forward - forwardRadius <= visibleRange
        }
        val dot = forward / flatLength
        return dot.isFinite() && dot >= cos(Math.toRadians(halfAngle))
    }

    private fun schedule(swing: Swing, delay: Long, action: () -> Unit) {
        var handle: ScheduledTask? = null
        handle = tasks.runLater(delay) {
            handle?.let(swing.taskHandles::remove)
            if (pending[swing.casterId] !== swing) return@runLater
            val player = Bukkit.getPlayer(swing.casterId)
            if (player == null || !current(player, swing)) {
                discard(swing)
                return@runLater
            }
            action()
        }
        handle?.let(swing.taskHandles::add)
    }

    private fun current(player: Player, swing: Swing) = ready(player) && player.world.uid == swing.worldId &&
        damage.isCurrent(player, swing.cast)

    private fun ready(player: Player) = player.isOnline && player.isValid && !player.isDead &&
        player.gameMode != org.bukkit.GameMode.SPECTATOR

    private fun trackScene(casterId: UUID, sceneId: UUID?) {
        if (sceneId == null) return
        scenesByCaster.getOrPut(casterId, ::mutableSetOf).add(sceneId)
        tasks.runLater(SCENE_CLEANUP_TICKS) {
            val scenes = scenesByCaster[casterId] ?: return@runLater
            scenes.remove(sceneId)
            if (scenes.isEmpty()) scenesByCaster.remove(casterId)
        }
    }

    private fun discard(swing: Swing) {
        if (pending[swing.casterId] === swing) pending.remove(swing.casterId)
        swing.taskHandles.toList().forEach(ScheduledTask::cancel)
        swing.taskHandles.clear()
        scenesByCaster.remove(swing.casterId)?.toList()?.forEach(effects::remove)
    }

    private fun finish(swing: Swing) {
        if (pending[swing.casterId] === swing) pending.remove(swing.casterId)
        swing.taskHandles.toList().forEach(ScheduledTask::cancel)
        swing.taskHandles.clear()
    }

    private fun horizontalDirection(direction: Vector, yaw: Float): Vector? {
        val flat = direction.clone().setY(0.0)
        if (!flat.lengthSquared().isFinite()) return null
        if (flat.lengthSquared() < MIN_DIRECTION_SQUARED) {
            val radians = Math.toRadians(yaw.toDouble())
            return Vector(-sin(radians), 0.0, cos(radians)).normalize()
        }
        return flat.normalize()
    }

    private companion object {
        const val DURATION_TICKS = 10
        const val CONTACT_TICKS = 3L
        const val SCENE_CLEANUP_TICKS = 14L
        const val MAX_TARGETS = 3
        const val LANCE_HALF_ANGLE = 55.0
        const val LANCE_MIN_VISUAL_DISTANCE = 1.45
        const val LANCE_BLADE_HALF_WIDTH = 0.12
        const val BLADE_CONTACT_LENGTH = 0.9
        const val BLADE_DISPLAY_RADIUS = 0.62
        const val BLADE_DISPLAY_TICKS = 8
        const val FAULT_DISPLAY_RADIUS = 1.8
        const val FAULT_DISPLAY_TICKS = 10
        const val FAULT_KNOCKBACK = 0.45
        const val FAULT_WAVE_START = 1.25
        const val FAULT_WAVE_HALF_WIDTH = 0.85
        const val FAULT_WAVE_Y = 0.12
        const val FAULT_LOW_RAY_DROP = 0.8
        const val MAX_VERTICAL_DELTA = 2.25
        const val CAMERA_CLEARANCE = 1.15
        const val MIN_TARGET_DISTANCE = 1.0e-6
        const val MIN_DIRECTION_SQUARED = 1.0e-12
        const val LANCE_GOLD = 0xFFD35A
        const val LANCE_WHITE = 0xFFF6C7
        const val FAULT_AMETHYST = 0xD8A4FF
        const val FAULT_STONE = 0xB8C8D8
    }
}
