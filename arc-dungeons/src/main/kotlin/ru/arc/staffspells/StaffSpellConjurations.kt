package ru.arc.staffspells

import org.bukkit.Bukkit
import org.bukkit.GameMode
import org.bukkit.Location
import org.bukkit.Particle
import org.bukkit.Sound
import org.bukkit.entity.LivingEntity
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.entity.EntityDamageEvent
import org.bukkit.potion.PotionEffect
import org.bukkit.potion.PotionEffectType
import org.bukkit.util.Vector
import ru.arc.core.LifecycleTaskScope
import java.util.UUID
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.sin

/** Owner for one active Q conjuration per player. The controller owns shared cooldown admission. */
internal class StaffSpellConjurations(
    private val settings: StaffSpellSettings,
    private val damage: StaffSpellDamage,
    private val effects: StaffSpellDisplayEffects,
    private val visuals: StaffSpellVisuals,
    private val targeting: StaffSpellTargeting,
) : Listener, AutoCloseable {
    private sealed interface Payload
    private data class FaultPayload(val barrierId: UUID) : Payload
    private data class MarkPayload(val targetId: UUID?, var targetOffset: Vector) : Payload
    private enum class TrapKind { NOVA, OSSUARY }
    private data class TrapPayload(
        val kind: TrapKind,
        val radius: Double,
        val armTicks: Int,
        val damageMultiplier: Double,
        val rootTicks: Int = 0,
        var triggered: Boolean = false,
    ) : Payload
    private data class GalePayload(val sideways: Vector, var hit: Boolean = false) : Payload
    private data class TidePayload(val targetId: UUID?) : Payload
    private data class RequiemPayload(var struck: Boolean = false) : Payload
    private data class FrostPulsePayload(val targets: List<UUID>) : Payload

    private data class Session(
        val ownerId: UUID,
        val spell: StaffSpell,
        val cast: StaffSpellCast,
        val tuning: StaffSpellTuning,
        val anchor: Location,
        val forward: Vector,
        val normal: Vector,
        val yaw: Double,
        var durationTicks: Int,
        val payload: Payload,
        var sceneId: UUID?,
        var age: Int = 0,
        var recast: Boolean = false,
    )

    private data class SlowKey(val ownerId: UUID, val targetId: UUID)
    private data class FrostSlow(
        val key: SlowKey,
        val cast: StaffSpellCast,
        val appliedAtTick: Long,
        val durationTicks: Int,
        val amplifier: Int,
    )
    private data class RootControl(
        val ownerId: UUID,
        val targetId: UUID,
        val cast: StaffSpellCast,
        val previousSlow: PotionEffect?,
        val durationTicks: Int,
        val installedAtTick: Int,
        val breakOnDamage: Boolean,
        var damagePending: Boolean = false,
    )

    private val tasks = LifecycleTaskScope()
    private val sessions = linkedMapOf<UUID, Session>()
    private val frostSlows = linkedMapOf<SlowKey, FrostSlow>()
    private val roots = linkedMapOf<UUID, RootControl>()
    private var started = false
    private var closed = false
    private var clock = 0L

    fun start() {
        if (closed || started) return
        started = true
        tasks.runTimer(1, 1, ::tick)
    }

    fun cast(player: Player, spell: StaffSpell, cast: StaffSpellCast, tuning: StaffSpellTuning): Boolean {
        if (closed || !ready(player) || cast.casterId != player.uniqueId || !damage.isCurrent(player, cast)) return false
        start()
        sessions[player.uniqueId]?.let { active ->
            if (active.spell != spell || !canRecast(player.uniqueId, spell)) return false
            return when (active.payload) {
                is FaultPayload -> shatterBarrier(player, active)
                is RequiemPayload -> strikeRequiem(player, active)
                else -> false
            }
        }
        if (sessions.size >= MAX_SESSIONS) return false
        return when (spell) {
            StaffSpell.MARK -> placeMark(player, cast, tuning)
            StaffSpell.FROST -> thaw(player, cast, tuning)
            StaffSpell.NOVA -> placeTrap(player, cast, tuning, TrapKind.NOVA)
            StaffSpell.FAULT -> placeFaultBarrier(player, cast, tuning)
            StaffSpell.GALE -> placeGaleCorridor(player, cast, tuning)
            StaffSpell.TIDE -> castTideCapsule(player, cast, tuning)
            StaffSpell.REQUIEM -> placeRequiem(player, cast, tuning)
            StaffSpell.OSSUARY -> placeTrap(player, cast, tuning, TrapKind.OSSUARY)
            else -> false
        }
    }

    fun canRecast(owner: UUID, spell: StaffSpell? = null): Boolean = sessions[owner]?.let { session ->
        if (spell != null && session.spell != spell) return@let false
        when (session.payload) {
            is FaultPayload -> !session.recast && session.age in BARRIER_RECAST_START..BARRIER_RECAST_END
            is RequiemPayload -> !session.recast && session.age in REQUIEM_RECAST_START..REQUIEM_RECAST_END
            else -> false
        }
    } == true

    fun continuationLabel(owner: UUID, spell: StaffSpell? = null): String? =
        if (!canRecast(owner, spell)) null else when (sessions[owner]?.payload) {
        is FaultPayload -> "Расколоть"
        is RequiemPayload -> "Ударить"
        else -> null
    }

    fun rememberFrostSlow(player: Player, target: LivingEntity, durationTicks: Int) {
        if (closed || !ready(player) || !target.isValid || target.isDead || durationTicks <= 0) return
        start()
        val cast = damage.capture(player) ?: damage.capture(player, duelOnly = true) ?: return
        if (!damage.isCurrent(player, cast) || !damage.eligible(player, target, cast) ||
            !visible(player.eyeLocation, center(target))) return
        val active = target.getPotionEffect(PotionEffectType.SLOWNESS) ?: return
        val duration = minOf(durationTicks, active.duration).coerceAtLeast(1)
        val key = SlowKey(player.uniqueId, target.uniqueId)
        // A later staff slow from another owner replaces the tracked provenance for this target.
        frostSlows.keys.filter { it.targetId == target.uniqueId }.forEach(frostSlows::remove)
        frostSlows[key] = FrostSlow(key, cast, clock, duration, active.amplifier)
    }

    fun rootTarget(player: Player, target: LivingEntity, cast: StaffSpellCast, durationTicks: Int): Boolean {
        if (closed) return false
        start()
        return applyRoot(player, target, cast, durationTicks, breakOnDamage = false)
    }

    fun rootActive(targetId: UUID, attackId: UUID): Boolean = roots[targetId]?.let { root ->
        val player = Bukkit.getPlayer(root.ownerId)
        val target = Bukkit.getEntity(targetId) as? LivingEntity
        root.cast.attackId == attackId && player != null && target != null && target.isValid && !target.isDead &&
            current(player, root.cast) && damage.eligible(player, target, root.cast) &&
            visible(player.eyeLocation, center(target)) && rootElapsed(root) < root.durationTicks && rootStillInstalled(target, root)
    } == true

    fun clearRoots(attackId: UUID) {
        roots.values.filter { it.cast.attackId == attackId }.toList().forEach(::clearRoot)
    }

    fun cancel(owner: UUID) {
        sessions.remove(owner)?.let(::remove)
        frostSlows.keys.filter { it.ownerId == owner }.forEach(frostSlows::remove)
        roots.values.filter { it.ownerId == owner }.toList().forEach(::clearRoot)
        StaffMagicBarriers.cancel(owner)
    }

    override fun close() {
        if (closed) return
        closed = true
        sessions.keys.toList().forEach(::cancel)
        roots.values.toList().forEach(::clearRoot)
        frostSlows.clear()
        tasks.close()
    }

    private fun placeFaultBarrier(player: Player, cast: StaffSpellCast, tuning: StaffSpellTuning): Boolean {
        val eye = player.eyeLocation
        val forward = horizontal(player.eyeLocation.direction, eye.yaw) ?: return false
        val ray = staffBlockHit(eye, forward, BARRIER_PLACEMENT_RANGE)
        if (!ray.pathLoaded) return false
        val distance = ray.hit?.hitPosition?.distance(eye.toVector()) ?: BARRIER_PLACEMENT_RANGE
        if (distance < BARRIER_MIN_DISTANCE) return false
        val point = eye.clone().add(forward.clone().multiply((distance - 0.05).coerceAtLeast(0.0)))
        val anchor = targeting.groundBelow(point.clone().add(0.0, BARRIER_GROUND_PROBE_HEIGHT, 0.0), BARRIER_GROUND_PROBE_RANGE)
            ?: return false
        if (anchor.world != player.world || anchor.distance(eye) < BARRIER_MIN_DISTANCE ||
            !visible(eye, anchor.clone().add(0.0, BARRIER_VISUAL_HEIGHT * 0.55, 0.0))) return false

        // The angled face is deliberate; the virtual wall and displayed plane share its exact yaw.
        val normal = forward.clone().rotateAroundY(BARRIER_ANGLE_RADIANS).normalize()
        val yaw = atan2(normal.x, normal.z)
        val barrier = StaffMagicBarriers.register(player.uniqueId, anchor, normal, BARRIER_HALF_WIDTH, BARRIER_VISUAL_HEIGHT)
            ?: return false
        if (!damage.isCurrent(player, cast)) {
            StaffMagicBarriers.remove(barrier)
            return false
        }
        val scene = effects.custom(player.uniqueId, StaffSpell.FAULT, anchor, BARRIER_DURATION_TICKS) { age ->
            staffFaultBarrierParts(age, yaw)
        }
        if (scene == null) {
            StaffMagicBarriers.remove(barrier)
            return false
        }
        val session = Session(player.uniqueId, StaffSpell.FAULT, cast, tuning, anchor.clone(), forward, normal, yaw,
            BARRIER_DURATION_TICKS, FaultPayload(barrier), scene)
        sessions[player.uniqueId] = session
        visuals.withOwner(player.uniqueId) {
            visuals.cue(anchor.clone().add(0.0, 1.4, 0.0), Sound.BLOCK_AMETHYST_BLOCK_CHIME, 0.78f, 0.72f)
            visuals.cue(anchor, Sound.BLOCK_DEEPSLATE_PLACE, 0.46f, 0.65f)
            visuals.particles(anchor.clone().add(0.0, 1.35, 0.0), listOf(
                StaffParticleSample(Particle.DUST, Vector(0.0, 0.0, 0.0), 8,
                    Vector(0.30, 0.55, 0.12), 0.0, staffDust(0xC58BFF, 1.1f)),
            ))
        }
        return true
    }

    private fun placeMark(player: Player, cast: StaffSpellCast, tuning: StaffSpellTuning): Boolean {
        val target = targeting.aimed(player, settings.range, settings.aimDegrees, cast).firstOrNull()
        if (target != null && (!damage.eligible(player, target, cast) || !visible(player.eyeLocation, center(target)))) return false
        val eye = player.eyeLocation
        val forward = horizontal(eye.direction, eye.yaw) ?: return false
        val anchor = target?.let(::center) ?: emptyAimPoint(player) ?: return false
        val payload = MarkPayload(target?.uniqueId, if (target == null) Vector(0.0, 1.1, 0.0) else Vector())
        val duration = if (target == null) 36 else settings.markTicks.coerceIn(MARK_MIN_DURATION, MARK_MAX_DURATION)
        val session = newSession(player, StaffSpell.MARK, cast, tuning, anchor, forward, forward,
            atan2(forward.x, forward.z), duration, payload) { age -> staffMarkTetherParts(age, payload.targetOffset) }
            ?: return false
        sessions[player.uniqueId] = session
        visuals.withOwner(player.uniqueId) {
            visuals.cue(anchor, Sound.BLOCK_AMETHYST_BLOCK_CHIME, 0.7f, 1.25f)
            visuals.particles(anchor, listOf(
                StaffParticleSample(Particle.DUST, Vector(), 7, Vector(0.2, 0.25, 0.2), data = staffDust(0xC46BFF)),
            ))
        }
        return true
    }

    private fun thaw(player: Player, cast: StaffSpellCast, tuning: StaffSpellTuning): Boolean {
        val candidates = frostSlows.values.asSequence()
            .filter { it.key.ownerId == player.uniqueId && frostSlowStillOwned(player, it) }
            .mapNotNull { mark -> (Bukkit.getEntity(mark.key.targetId) as? LivingEntity)?.let { mark to it } }
            .filter { (_, target) -> center(target).distanceSquared(player.location) <= FROST_THAW_RANGE * FROST_THAW_RANGE }
            .filter { (_, target) -> damage.eligible(player, target, cast) && visible(player.eyeLocation, center(target)) }
            .sortedBy { (_, target) -> center(target).distanceSquared(player.location) }
            .take(settings.maxAreaTargets.coerceIn(1, CONJURATION_TARGET_LIMIT))
            .toList()
        val focus = candidates.firstOrNull()?.second?.let(::center)
            ?: emptyAimPoint(player, 4.5)?.subtract(0.0, .6, 0.0) ?: return false
        val forward = horizontal(player.eyeLocation.direction, player.eyeLocation.yaw) ?: return false
        val scene = effects.custom(player.uniqueId, StaffSpell.FROST, focus, FROST_THAW_TICKS, ::staffFrostThawParts)
            ?: return false
        val consumed = mutableListOf<UUID>()
        for ((mark, target) in candidates) {
            if (!damage.isCurrent(player, cast) || !damage.eligible(player, target, cast) ||
                !visible(player.eyeLocation, center(target)) || !frostSlowStillOwned(player, mark)) continue
            if (!damage.hit(player, target, cast, tuning.power * FROST_THAW_DAMAGE_MULTIPLIER,
                    tuning.vanillaDamage * FROST_THAW_DAMAGE_MULTIPLIER)) continue
            if (!damage.isCurrent(player, cast) || !damage.eligible(player, target, cast) ||
                !visible(player.eyeLocation, center(target)) || !frostSlowStillOwned(player, mark)) continue
            target.removePotionEffect(PotionEffectType.SLOWNESS)
            frostSlows.remove(mark.key)
            consumed += target.uniqueId
        }
        if (!damage.isCurrent(player, cast)) {
            effects.remove(scene)
            return consumed.isNotEmpty()
        }
        sessions[player.uniqueId] = Session(player.uniqueId, StaffSpell.FROST, cast, tuning, focus,
            forward, forward, atan2(forward.x, forward.z), FROST_THAW_TICKS,
            FrostPulsePayload(consumed), scene)
        visuals.withOwner(player.uniqueId) {
            visuals.cue(focus, Sound.BLOCK_GLASS_BREAK, 0.8f, 1.65f)
            visuals.cue(focus, Sound.BLOCK_AMETHYST_BLOCK_CHIME, 0.28f, 1.48f)
            visuals.particles(focus, listOf(
                StaffParticleSample(Particle.SNOWFLAKE, Vector(), 14, Vector(0.55, 0.45, 0.55), 0.015),
                StaffParticleSample(Particle.CRIT, Vector(0.0, 0.25, 0.0), 5, Vector(0.35, 0.2, 0.35), 0.01),
            ))
        }
        return true
    }

    private fun placeTrap(player: Player, cast: StaffSpellCast, tuning: StaffSpellTuning, kind: TrapKind): Boolean {
        val point = groundPoint(player, TRAP_PLACEMENT_RANGE, TRAP_MIN_DISTANCE) ?: return false
        val forward = horizontal(player.eyeLocation.direction, player.eyeLocation.yaw) ?: return false
        val yaw = atan2(forward.x, forward.z)
        val payload = when (kind) {
            TrapKind.NOVA -> TrapPayload(kind, NOVA_TRAP_RADIUS, NOVA_ARM_TICKS, NOVA_TRAP_DAMAGE_MULTIPLIER,
                rootTicks = NOVA_ROOT_TICKS)
            TrapKind.OSSUARY -> TrapPayload(kind, OSSUARY_TRAP_RADIUS, OSSUARY_ARM_TICKS,
                OSSUARY_TRAP_DAMAGE_MULTIPLIER)
        }
        val spell = if (kind == TrapKind.NOVA) StaffSpell.NOVA else StaffSpell.OSSUARY
        val duration = if (kind == TrapKind.NOVA) NOVA_TRAP_DURATION else OSSUARY_TRAP_DURATION
        val session = newSession(player, spell, cast, tuning, point, forward, forward, yaw, duration, payload) { age ->
            if (kind == TrapKind.NOVA) staffNovaSeedParts(age) else staffOssuaryJawParts(age)
        } ?: return false
        sessions[player.uniqueId] = session
        val sound = if (kind == TrapKind.NOVA) Sound.BLOCK_ROOTED_DIRT_BREAK else Sound.BLOCK_BONE_BLOCK_PLACE
        visuals.withOwner(player.uniqueId) {
            visuals.cue(point.clone().add(0.0, 0.35, 0.0), sound, 0.76f,
                if (kind == TrapKind.NOVA) 1.2f else 0.72f)
            visuals.particles(point.clone().add(0.0, 0.25, 0.0), trapParticles(kind))
        }
        return true
    }

    private fun placeGaleCorridor(player: Player, cast: StaffSpellCast, tuning: StaffSpellTuning): Boolean {
        val eye = player.eyeLocation
        val forward = horizontal(eye.direction, eye.yaw) ?: return false
        val sideways = Vector(forward.z, 0.0, -forward.x)
        val yaw = atan2(forward.x, forward.z)
        val anchor = player.location.clone().add(0.0, 0.08, 0.0)
        val payload = GalePayload(sideways)
        val session = newSession(player, StaffSpell.GALE, cast, tuning, anchor, forward, sideways, yaw,
            GALE_CORRIDOR_DURATION, payload) { age -> staffGaleCorridorParts(age, GALE_CORRIDOR_LENGTH, GALE_CORRIDOR_HALF_WIDTH, yaw) }
            ?: return false
        sessions[player.uniqueId] = session
        visuals.withOwner(player.uniqueId) {
            visuals.cue(anchor.clone().add(forward.clone().multiply(3.0)).add(0.0, 0.8, 0.0),
                Sound.ENTITY_BREEZE_WIND_BURST, 0.82f, 1.15f)
            visuals.particles(anchor.clone().add(forward.clone().multiply(1.8)).add(0.0, 0.8, 0.0), listOf(
                StaffParticleSample(Particle.GUST, Vector(), 1),
                StaffParticleSample(Particle.CRIT, Vector(0.0, 0.1, 0.0), 8, Vector(0.7, 0.25, 0.7), 0.02),
            ))
        }
        return true
    }

    private fun castTideCapsule(player: Player, cast: StaffSpellCast, tuning: StaffSpellTuning): Boolean {
        val target = targeting.aimed(player, settings.range, settings.aimDegrees, cast).firstOrNull()
        if (target != null && (!damage.eligible(player, target, cast) || !visible(player.eyeLocation, center(target)))) return false
        val anchor = target?.location?.clone() ?: emptyAimPoint(player)?.subtract(0.0, .8, 0.0) ?: return false
        val forward = horizontal(player.eyeLocation.direction, player.eyeLocation.yaw) ?: return false
        val payload = TidePayload(target?.uniqueId)
        val session = newSession(player, StaffSpell.TIDE, cast, tuning, anchor, forward, forward,
            atan2(forward.x, forward.z), TIDE_CAPSULE_DURATION, payload, ::staffTideCapsuleParts) ?: return false
        if (target != null && !damage.hit(player, target, cast, tuning.power * TIDE_OPENING_DAMAGE_MULTIPLIER,
                tuning.vanillaDamage * TIDE_OPENING_DAMAGE_MULTIPLIER)) {
            effects.remove(session.sceneId)
            return false
        }
        if (!damage.isCurrent(player, cast) || target != null && (!damage.eligible(player, target, cast) ||
            !visible(player.eyeLocation, center(target)))) {
            effects.remove(session.sceneId)
            return true
        }
        val rooted = target == null || applyRoot(player, target, cast, TIDE_CAPSULE_DURATION, breakOnDamage = true)
        if (!rooted) {
            effects.remove(session.sceneId)
            return true // The accepted opening hit remains an admitted cast if another root already owns the target.
        }
        sessions[player.uniqueId] = session
        visuals.withOwner(player.uniqueId) {
            val at = target?.let(::center) ?: anchor.clone().add(0.0, .9, 0.0)
            visuals.cue(at, Sound.ENTITY_PLAYER_SPLASH, 0.82f, 0.75f)
            visuals.cue(at, Sound.BLOCK_BUBBLE_COLUMN_UPWARDS_AMBIENT, 0.42f, 0.72f)
            visuals.particles(at, listOf(
                StaffParticleSample(Particle.SPLASH, Vector(), 16, Vector(0.35, 0.5, 0.35), 0.04),
                StaffParticleSample(Particle.BUBBLE, Vector(0.0, 0.45, 0.0), 7, Vector(0.22, 0.4, 0.22), 0.015),
            ))
        }
        return true
    }

    private fun placeRequiem(player: Player, cast: StaffSpellCast, tuning: StaffSpellTuning): Boolean {
        val point = groundPoint(player, REQUIEM_PLACEMENT_RANGE, REQUIEM_MIN_DISTANCE) ?: return false
        val forward = horizontal(player.eyeLocation.direction, player.eyeLocation.yaw) ?: return false
        val payload = RequiemPayload()
        val yaw = atan2(forward.x, forward.z)
        val session = newSession(player, StaffSpell.REQUIEM, cast, tuning, point, forward, forward, yaw,
            REQUIEM_DURATION, payload, ::staffRequiemBellParts) ?: return false
        sessions[player.uniqueId] = session
        visuals.withOwner(player.uniqueId) {
            visuals.cue(point.clone().add(0.0, 2.0, 0.0), Sound.BLOCK_BELL_RESONATE, 0.38f, 0.82f)
            visuals.particles(point.clone().add(0.0, 1.4, 0.0), listOf(
                StaffParticleSample(Particle.NOTE, Vector(), 2, Vector(0.0, 0.08, 0.0)),
                StaffParticleSample(Particle.ENCHANT, Vector(0.0, 0.45, 0.0), 5, Vector(0.25, 0.22, 0.25), 0.02),
            ))
        }
        return true
    }

    private fun newSession(
        player: Player,
        spell: StaffSpell,
        cast: StaffSpellCast,
        tuning: StaffSpellTuning,
        anchor: Location,
        forward: Vector,
        normal: Vector,
        yaw: Double,
        duration: Int,
        payload: Payload,
        geometry: (Int) -> List<StaffDisplayPart>,
    ): Session? {
        if (!damage.isCurrent(player, cast) || !ready(player) || anchor.world != player.world) return null
        val scene = effects.custom(player.uniqueId, spell, anchor, duration, geometry) ?: return null
        return Session(player.uniqueId, spell, cast, tuning, anchor.clone(), forward.clone(), normal.clone(), yaw,
            duration, payload, scene)
    }

    private fun groundPoint(player: Player, range: Double, minDistance: Double): Location? {
        val eye = player.eyeLocation
        val direction = eye.direction
        val ray = staffBlockHit(eye, direction, range)
        if (!ray.pathLoaded) return null
        val distance = ray.hit?.hitPosition?.distance(eye.toVector()) ?: range
        if (distance < minDistance) return null
        val point = eye.clone().add(direction.clone().normalize().multiply((distance - 0.05).coerceAtLeast(0.0)))
        val ground = targeting.groundBelow(point.clone().add(0.0, GROUND_PROBE_HEIGHT, 0.0), GROUND_PROBE_RANGE) ?: return null
        if (ground.world != player.world || ground.distance(eye) < minDistance ||
            !visible(eye, ground.clone().add(0.0, 0.85, 0.0))) return null
        return ground
    }

    private fun emptyAimPoint(player: Player, range: Double = 6.0): Location? {
        val eye = player.eyeLocation
        val at = targeting.rayEnd(eye, eye.direction, minOf(settings.range, range))
        if (at.world != player.world || eye.distanceSquared(at) < 1.2 * 1.2) return null
        at.subtract(eye.direction.clone().multiply(.08))
        return at.takeIf { player.world.isChunkLoaded(it.blockX shr 4, it.blockZ shr 4) && visible(eye, it) }
    }

    private fun trapParticles(kind: TrapKind) = if (kind == TrapKind.NOVA) listOf(
        StaffParticleSample(Particle.HAPPY_VILLAGER, Vector(0.0, 0.3, 0.0), 5, Vector(0.28, 0.12, 0.28), 0.005),
        StaffParticleSample(Particle.BLOCK, Vector(), 5, Vector(0.36, 0.04, 0.36), 0.0,
            data = org.bukkit.Material.MOSS_BLOCK.createBlockData()),
    ) else listOf(
        StaffParticleSample(Particle.CRIT, Vector(0.0, 0.25, 0.0), 6, Vector(0.4, 0.12, 0.4), 0.01),
        StaffParticleSample(Particle.WHITE_ASH, Vector(), 6, Vector(0.34, 0.05, 0.34), 0.0),
    )

    private fun strikeRequiem(player: Player, session: Session): Boolean {
        if (!current(player, session)) {
            cancel(session.ownerId)
            return false
        }
        val payload = session.payload as? RequiemPayload ?: return false
        if (payload.struck) return false
        payload.struck = true
        session.recast = true
        val bellScene = session.sceneId
        session.sceneId = null
        effects.remove(bellScene)
        // The impact has its own bounded lifetime and must outlive session cleanup below.
        effects.custom(player.uniqueId, StaffSpell.REQUIEM, session.anchor, REQUIEM_STRIKE_TICKS) { age ->
            staffRequiemImpactParts(age, REQUIEM_STRIKE_RADIUS)
        }
        val impact = session.anchor.clone().add(0.0, 0.2, 0.0)
        visuals.withOwner(player.uniqueId) {
            // Vanilla bell.use already multiplies each sample's volume by twelve.
            visuals.cue(session.anchor.clone().add(0.0, 1.4, 0.0), Sound.BLOCK_BELL_USE, 0.12f, 0.58f)
            visuals.cue(session.anchor.clone().add(0.0, 1.4, 0.0), Sound.BLOCK_BELL_RESONATE, 0.32f, 0.68f)
            visuals.particles(impact, listOf(
                StaffParticleSample(Particle.SONIC_BOOM, Vector(0.0, 0.75, 0.0)),
                StaffParticleSample(Particle.NOTE, Vector(0.0, 1.15, 0.0), 4, Vector(0.25, 0.15, 0.25)),
            ))
        }
        val targets = targeting.nearby(player, impact, REQUIEM_STRIKE_RADIUS, session.cast)
            .filter { visible(player.eyeLocation, center(it)) && visible(impact.clone().add(0.0, 0.9, 0.0), center(it)) }
            .sortedBy { center(it).distanceSquared(impact) }
            .take(settings.maxAreaTargets.coerceIn(1, REQUIEM_TARGET_LIMIT))
        for (target in targets) {
            if (!current(player, session) || !damage.eligible(player, target, session.cast)) break
            if (!target.isValid || target.isDead || target.isInvulnerable ||
                !visible(player.eyeLocation, center(target)) || !visible(impact.clone().add(0.0, 0.9, 0.0), center(target))) continue
            damage.hit(player, target, session.cast,
                session.tuning.power * REQUIEM_STRIKE_DAMAGE_MULTIPLIER,
                session.tuning.vanillaDamage * REQUIEM_STRIKE_DAMAGE_MULTIPLIER)
        }
        endSession(session)
        return true
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onConjurationTargetDamage(event: EntityDamageEvent) {
        if (closed || event.finalDamage <= ROOT_DAMAGE_EPSILON) return
        val target = event.entity as? LivingEntity ?: return
        val root = roots[target.uniqueId]?.takeIf { it.breakOnDamage } ?: return
        if (root.damagePending) return
        val before = target.health + target.absorptionAmount
        root.damagePending = true
        tasks.runLater(1) {
            if (roots[target.uniqueId] !== root) return@runLater
            if (!target.isValid || target.isDead) {
                clearRoot(root)
                return@runLater
            }
            val accepted = before - target.health - target.absorptionAmount > ROOT_DAMAGE_EPSILON
            if (!accepted) {
                root.damagePending = false
                return@runLater
            }
            clearRoot(root)
            val session = sessions[root.ownerId]
            if (session?.payload is TidePayload && session.payload.targetId == target.uniqueId) {
                visuals.withOwner(session.ownerId) {
                    visuals.cue(center(target), Sound.ENTITY_PLAYER_SPLASH, 0.42f, 1.5f)
                }
                endSession(session)
            }
        }
    }

    private fun tick() {
        if (closed) return
        clock++
        pruneFrostSlows()
        sessions.values.toList().forEach { session ->
            if (sessions[session.ownerId] !== session) return@forEach
            val player = Bukkit.getPlayer(session.ownerId)
            if (player == null || !current(player, session)) {
                cancel(session.ownerId)
                return@forEach
            }
            if (session.payload is FaultPayload && !effects.isActive(session.sceneId)) {
                // The virtual wall may never outlive its visible footprint, including visual-pool eviction.
                cancel(session.ownerId)
                return@forEach
            }
            session.age++
            when (val payload = session.payload) {
                is MarkPayload -> tickMark(player, session, payload)
                is TrapPayload -> if (payload.kind == TrapKind.OSSUARY && payload.triggered) Unit
                    else tickTrap(player, session, payload)
                is GalePayload -> if (!payload.hit && session.age >= GALE_CONTACT_TICK) {
                    payload.hit = true
                    hitGaleCorridor(player, session, payload)
                }
                is TidePayload -> tickTideCapsule(player, session, payload)
                is RequiemPayload -> if (session.age == REQUIEM_RECAST_START) visuals.withOwner(session.ownerId) {
                    visuals.cue(session.anchor.clone().add(0.0, 1.4, 0.0), Sound.BLOCK_BELL_USE, 0.025f, 1.55f)
                }
                is FaultPayload, is FrostPulsePayload -> Unit
            }
            if (sessions[session.ownerId] === session && session.age >= session.durationTicks) {
                visuals.withOwner(session.ownerId) {
                    when (val payload = session.payload) {
                        is TidePayload -> visuals.cue((payload.targetId?.let(Bukkit::getEntity) as? LivingEntity)
                            ?.let(::center) ?: session.anchor, Sound.ENTITY_PLAYER_SWIM, 0.46f, 1.3f)
                        is TrapPayload -> if (!payload.triggered) visuals.cue(session.anchor,
                            if (payload.kind == TrapKind.NOVA) Sound.BLOCK_AZALEA_LEAVES_BREAK else Sound.BLOCK_BONE_BLOCK_FALL,
                            0.28f, 0.86f)
                        is FaultPayload -> visuals.cue(session.anchor, Sound.BLOCK_AMETHYST_CLUSTER_BREAK, 0.34f, 1.25f)
                        is MarkPayload -> visuals.cue(session.anchor, Sound.BLOCK_AMETHYST_CLUSTER_HIT, 0.28f, 0.78f)
                        else -> Unit
                    }
                }
                endSession(session)
            }
        }
        tickRoots()
    }

    private fun tickMark(player: Player, session: Session, payload: MarkPayload) {
        val targetId = payload.targetId ?: return
        val target = Bukkit.getEntity(targetId) as? LivingEntity
        if (target == null || !target.isValid || target.isDead || target.world != session.anchor.world ||
            !damage.eligible(player, target, session.cast)) {
            endSession(session)
            return
        }
        val targetAt = center(target)
        if (!visible(player.eyeLocation, targetAt) || !visible(session.anchor, targetAt)) {
            // A blocked tether quietly expires; it cannot punish through cover.
            endSession(session)
            return
        }
        payload.targetOffset = targetAt.toVector().subtract(session.anchor.toVector())
        effects.updateCustom(session.sceneId, session.anchor, staffMarkTetherParts(session.age, payload.targetOffset))
        if (targetAt.distance(session.anchor) <= MARK_TETHER_RADIUS) return
        val hit = damage.hit(player, target, session.cast, session.tuning.power, session.tuning.vanillaDamage)
        if (hit && current(player, session) && damage.eligible(player, target, session.cast) &&
            visible(player.eyeLocation, center(target))) {
            visuals.withOwner(player.uniqueId) {
                visuals.cue(center(target), Sound.BLOCK_RESPAWN_ANCHOR_DEPLETE, 0.8f, 1.5f)
                visuals.particles(center(target), listOf(
                    StaffParticleSample(Particle.CRIT, Vector(), 8, Vector(0.22, 0.3, 0.22), 0.03),
                ))
            }
        }
        // Radius break is single-use even when protection rejects the punishment.
        endSession(session)
    }

    private fun tickTrap(player: Player, session: Session, payload: TrapPayload) {
        if (payload.triggered || session.age < payload.armTicks) return
        if (session.age == payload.armTicks) visuals.withOwner(session.ownerId) {
            visuals.cue(session.anchor, if (payload.kind == TrapKind.NOVA) Sound.BLOCK_AZALEA_PLACE
                else Sound.BLOCK_BONE_BLOCK_HIT, 0.34f, if (payload.kind == TrapKind.NOVA) 1.25f else 0.9f)
        }
        val targets = targeting.nearby(player, session.anchor, payload.radius, session.cast)
            .filter { center(it).distanceSquared(session.anchor) <= payload.radius * payload.radius }
            .filter { visible(player.eyeLocation, center(it)) && visible(session.anchor.clone().add(0.0, 0.45, 0.0), center(it)) }
            .sortedBy { center(it).distanceSquared(session.anchor) }
            .take(1)
        val target = targets.firstOrNull() ?: return
        payload.triggered = true
        if (!current(player, session) || !target.isValid || target.isDead || target.isInvulnerable ||
            !damage.eligible(player, target, session.cast) || !visible(player.eyeLocation, center(target)) ||
            !visible(session.anchor.clone().add(0.0, 0.45, 0.0), center(target))) {
            endSession(session)
            return
        }
        val hit = damage.hit(player, target, session.cast,
            session.tuning.power * payload.damageMultiplier, session.tuning.vanillaDamage * payload.damageMultiplier)
        if (hit && current(player, session) && damage.eligible(player, target, session.cast) &&
            visible(player.eyeLocation, center(target)) && visible(session.anchor.clone().add(0.0, 0.45, 0.0), center(target))) {
            if (payload.kind == TrapKind.NOVA) applyRoot(player, target, session.cast, payload.rootTicks, breakOnDamage = false)
            visuals.withOwner(player.uniqueId) {
                visuals.cue(center(target), if (payload.kind == TrapKind.NOVA) Sound.BLOCK_AZALEA_LEAVES_BREAK else Sound.BLOCK_BONE_BLOCK_BREAK,
                    0.8f, if (payload.kind == TrapKind.NOVA) 0.85f else 0.7f)
                visuals.particles(center(target), trapParticles(payload.kind))
            }
        }
        if (payload.kind == TrapKind.OSSUARY) {
            val trapScene = session.sceneId
            session.sceneId = null
            effects.custom(player.uniqueId, StaffSpell.OSSUARY, session.anchor, OSSUARY_CLOSE_SCENE_TICKS) { age ->
                staffOssuaryJawClosingParts(age)
            }
            effects.remove(trapScene)
            session.durationTicks = session.age + OSSUARY_CLOSE_SCENE_TICKS
            return
        }
        endSession(session)
    }

    private fun hitGaleCorridor(player: Player, session: Session, payload: GalePayload) {
        val centerLine = session.anchor.clone().add(session.forward.clone().multiply(GALE_CORRIDOR_LENGTH * 0.5))
            .add(0.0, 1.0, 0.0)
        visuals.withOwner(session.ownerId) {
            visuals.cue(centerLine, Sound.ENTITY_BREEZE_WIND_BURST, 0.62f, 0.78f)
        }
        val targets = targeting.nearby(player, centerLine, GALE_QUERY_RADIUS, session.cast)
            .filter { inGaleCorridor(session, it) && visible(player.eyeLocation, center(it)) && visible(centerLine, center(it)) }
            .sortedBy { center(it).distanceSquared(centerLine) }
            .take(settings.maxAreaTargets.coerceIn(1, GALE_TARGET_LIMIT))
        for (target in targets) {
            if (!current(player, session) || !damage.eligible(player, target, session.cast)) break
            if (!target.isValid || target.isDead || target.isInvulnerable || !inGaleCorridor(session, target) ||
                !visible(player.eyeLocation, center(target)) || !visible(centerLine, center(target))) continue
            if (!damage.hit(player, target, session.cast,
                    session.tuning.power * GALE_DAMAGE_MULTIPLIER, session.tuning.vanillaDamage * GALE_DAMAGE_MULTIPLIER)) continue
            if (!current(player, session) || !damage.eligible(player, target, session.cast) ||
                !visible(player.eyeLocation, center(target)) || !visible(centerLine, center(target))) continue
            target.velocity = target.velocity.clone()
                .add(payload.sideways.clone().multiply(GALE_SIDE_IMPULSE))
                .add(Vector(0.0, GALE_LIFT_IMPULSE, 0.0))
        }
    }

    private fun inGaleCorridor(session: Session, target: LivingEntity): Boolean {
        if (target.world != session.anchor.world) return false
        val offset = center(target).toVector().subtract(session.anchor.toVector())
        val along = offset.dot(session.forward)
        val lateral = offset.dot(session.normal)
        return along in GALE_CORRIDOR_MIN_FORWARD..(GALE_CORRIDOR_LENGTH + 1.0) &&
            abs(lateral) <= GALE_CORRIDOR_HALF_WIDTH && offset.y in -0.25..GALE_CORRIDOR_HEIGHT
    }

    private fun tickTideCapsule(player: Player, session: Session, payload: TidePayload) {
        val targetId = payload.targetId ?: return
        val target = Bukkit.getEntity(targetId) as? LivingEntity
        val root = roots[targetId]
        if (target == null || root == null || !target.isValid || target.isDead ||
            !damage.eligible(player, target, session.cast) || !visible(player.eyeLocation, center(target))) {
            cancel(session.ownerId)
            return
        }
        effects.updateCustom(session.sceneId, target.location, staffTideCapsuleParts(session.age))
    }

    private fun tickRoots() {
        roots.values.toList().forEach { root ->
            if (roots[root.targetId] !== root) return@forEach
            val player = Bukkit.getPlayer(root.ownerId)
            val target = Bukkit.getEntity(root.targetId) as? LivingEntity
            if (player == null || target == null || !target.isValid || target.isDead || !current(player, root.cast) ||
                !damage.eligible(player, target, root.cast) || !visible(player.eyeLocation, center(target))) {
                clearRoot(root)
                val session = sessions[root.ownerId]
                if (session?.payload is TidePayload && session.payload.targetId == root.targetId) endSession(session)
                return@forEach
            }
            val elapsed = rootElapsed(root)
            if (elapsed >= root.durationTicks || !rootStillInstalled(target, root)) clearRoot(root)
            else target.velocity = target.velocity.clone().setX(0.0).setZ(0.0)
        }
    }

    private fun applyRoot(player: Player, target: LivingEntity, cast: StaffSpellCast, durationTicks: Int,
        breakOnDamage: Boolean): Boolean {
        if (closed || roots.containsKey(target.uniqueId) || !current(player, cast) ||
            !damage.eligible(player, target, cast) || !visible(player.eyeLocation, center(target))) return false
        val duration = durationTicks.coerceIn(1, ROOT_MAX_DURATION)
        val previous = target.getPotionEffect(PotionEffectType.SLOWNESS)
        val applied = target.addPotionEffect(PotionEffect(PotionEffectType.SLOWNESS, duration, ROOT_AMPLIFIER, false, false), true)
        if (!applied) return false
        val installed = target.getPotionEffect(PotionEffectType.SLOWNESS)
        if (installed == null || installed.amplifier != ROOT_AMPLIFIER ||
            abs(installed.duration - duration) > POTION_TICK_TOLERANCE) return false
        roots[target.uniqueId] = RootControl(player.uniqueId, target.uniqueId, cast, previous,
            duration, Bukkit.getCurrentTick(), breakOnDamage)
        return true
    }

    private fun rootElapsed(root: RootControl) = (Bukkit.getCurrentTick() - root.installedAtTick).coerceAtLeast(0)

    private fun rootStillInstalled(target: LivingEntity, root: RootControl): Boolean {
        val expected = (root.durationTicks - rootElapsed(root)).coerceAtLeast(0)
        val active = target.getPotionEffect(PotionEffectType.SLOWNESS) ?: return false
        return active.amplifier == ROOT_AMPLIFIER && abs(active.duration - expected) <= POTION_TICK_TOLERANCE
    }

    private fun clearRoot(root: RootControl) {
        if (roots[root.targetId] === root) roots.remove(root.targetId)
        val target = Bukkit.getEntity(root.targetId) as? LivingEntity ?: return
        if (!target.isValid || target.isDead || !rootStillInstalled(target, root)) return
        target.removePotionEffect(PotionEffectType.SLOWNESS)
        val elapsed = rootElapsed(root)
        val previous = root.previousSlow ?: return
        val remaining = previous.duration - elapsed
        if (remaining > 0 && target.isValid && !target.isDead) {
            target.addPotionEffect(PotionEffect(PotionEffectType.SLOWNESS, remaining,
                previous.amplifier, previous.isAmbient, previous.hasParticles()))
        }
    }

    private fun pruneFrostSlows() {
        frostSlows.values.toList().forEach { mark ->
            val owner = Bukkit.getPlayer(mark.key.ownerId)
            val target = Bukkit.getEntity(mark.key.targetId) as? LivingEntity
            if (owner == null || target == null || !frostSlowStillOwned(owner, mark)) frostSlows.remove(mark.key)
        }
    }

    private fun frostSlowStillOwned(player: Player, mark: FrostSlow): Boolean {
        if (player.uniqueId != mark.key.ownerId || !current(player, mark.cast)) return false
        val target = Bukkit.getEntity(mark.key.targetId) as? LivingEntity ?: return false
        if (!target.isValid || target.isDead || !damage.eligible(player, target, mark.cast) ||
            !visible(player.eyeLocation, center(target))) return false
        val expected = (mark.durationTicks - (clock - mark.appliedAtTick).toInt()).coerceAtLeast(0)
        val active = target.getPotionEffect(PotionEffectType.SLOWNESS) ?: return false
        return expected > 0 && active.amplifier == mark.amplifier &&
            abs(active.duration - expected) <= POTION_TICK_TOLERANCE
    }

    private fun endSession(session: Session) {
        if (sessions[session.ownerId] === session) sessions.remove(session.ownerId)
        remove(session)
        (session.payload as? TidePayload)?.let { payload ->
            payload.targetId?.let(roots::get)?.takeIf { it.ownerId == session.ownerId }?.let(::clearRoot)
        }
    }

    private fun shatterBarrier(player: Player, session: Session): Boolean {
        if (!current(player, session)) {
            cancel(session.ownerId)
            return false
        }
        session.recast = true
        StaffMagicBarriers.remove((session.payload as FaultPayload).barrierId)
        val barrierScene = session.sceneId
        session.sceneId = null
        effects.remove(barrierScene)
        // Shards are a transient scene; endSession must not erase them in the same tick.
        effects.custom(player.uniqueId, StaffSpell.FAULT, session.anchor, BARRIER_SHATTER_TICKS) { age ->
            staffFaultBarrierShatterParts(age, session.yaw)
        }
        val impact = session.anchor.clone().add(0.0, 1.35, 0.0)
        visuals.withOwner(player.uniqueId) {
            visuals.cue(impact, Sound.BLOCK_AMETHYST_BLOCK_BREAK, 0.95f, 0.62f)
            visuals.cue(impact, Sound.BLOCK_DEEPSLATE_BREAK, 0.42f, 0.72f)
            visuals.particles(impact, List(8) { index ->
                val angle = index * Math.PI / 4.0
                StaffParticleSample(Particle.CRIT, Vector(kotlin.math.cos(angle) * 0.65, 0.16, kotlin.math.sin(angle) * 0.65))
            })
        }

        val targets = targeting.nearby(player, impact, BARRIER_SHATTER_RADIUS, session.cast)
            .filter { inForwardBurst(session, it) && visible(player.eyeLocation, center(it)) && visible(impact, center(it)) }
            .sortedBy { center(it).distanceSquared(impact) }
            .take(settings.maxAreaTargets.coerceIn(1, BARRIER_TARGET_LIMIT))
        for (target in targets) {
            if (!current(player, session) || !damage.eligible(player, target, session.cast)) break
            if (!target.isValid || target.isDead || target.isInvulnerable || !visible(player.eyeLocation, center(target)) ||
                !visible(impact, center(target))) continue
            if (!damage.hit(player, target, session.cast, session.tuning.power, session.tuning.vanillaDamage)) continue
            if (!current(player, session) || !damage.eligible(player, target, session.cast) ||
                !visible(player.eyeLocation, center(target)) || !visible(impact, center(target))) continue
            target.velocity = target.velocity.clone().add(session.normal.clone().multiply(BARRIER_SHATTER_PUSH))
        }
        endSession(session)
        return true
    }

    private fun inForwardBurst(session: Session, target: LivingEntity): Boolean {
        if (target.world != session.anchor.world) return false
        val offset = center(target).toVector().subtract(session.anchor.toVector())
        if (offset.lengthSquared() > BARRIER_SHATTER_RADIUS * BARRIER_SHATTER_RADIUS || abs(offset.y - 1.35) > 2.2) return false
        val forward = offset.dot(session.normal)
        return forward in -0.45..BARRIER_SHATTER_FORWARD
    }

    private fun remove(session: Session) {
        (session.payload as? FaultPayload)?.let { StaffMagicBarriers.remove(it.barrierId) }
        effects.remove(session.sceneId)
    }

    private fun current(player: Player, session: Session) = ready(player) && player.world == session.anchor.world &&
        damage.isCurrent(player, session.cast)

    private fun current(player: Player, cast: StaffSpellCast) = ready(player) && damage.isCurrent(player, cast)

    private fun ready(player: Player) = player.isOnline && player.isValid && !player.isDead && player.gameMode != GameMode.SPECTATOR

    private fun horizontal(direction: Vector, yaw: Float): Vector? {
        val flat = direction.clone().setY(0.0)
        if (!flat.lengthSquared().isFinite()) return null
        if (flat.lengthSquared() < 1.0e-12) {
            val radians = Math.toRadians(yaw.toDouble())
            return Vector(-sin(radians), 0.0, kotlin.math.cos(radians))
        }
        return flat.normalize()
    }

    private companion object {
        const val MAX_SESSIONS = 128
        const val CONJURATION_TARGET_LIMIT = 3
        const val ROOT_MAX_DURATION = 80
        const val ROOT_AMPLIFIER = 7
        const val ROOT_DAMAGE_EPSILON = 1.0e-6
        const val POTION_TICK_TOLERANCE = 3
        const val MARK_MIN_DURATION = 60
        const val MARK_MAX_DURATION = 180
        const val MARK_TETHER_RADIUS = 3.0
        const val FROST_THAW_RANGE = 8.0
        const val FROST_THAW_TICKS = 18
        const val FROST_THAW_DAMAGE_MULTIPLIER = 0.72
        const val NOVA_TRAP_RADIUS = 1.65
        const val NOVA_ARM_TICKS = 8
        const val NOVA_TRAP_DURATION = 72
        const val NOVA_TRAP_DAMAGE_MULTIPLIER = 0.28
        const val NOVA_ROOT_TICKS = 36
        const val TRAP_PLACEMENT_RANGE = 7.0
        const val TRAP_MIN_DISTANCE = 1.5
        const val GROUND_PROBE_HEIGHT = 3.5
        const val GROUND_PROBE_RANGE = 6.5
        const val GALE_CORRIDOR_DURATION = 22
        const val GALE_CONTACT_TICK = 7
        const val GALE_CORRIDOR_LENGTH = 6.5
        const val GALE_CORRIDOR_HALF_WIDTH = 2.0
        const val GALE_CORRIDOR_HEIGHT = 3.0
        const val GALE_CORRIDOR_MIN_FORWARD = 0.8
        const val GALE_QUERY_RADIUS = 5.5
        const val GALE_TARGET_LIMIT = 3
        const val GALE_DAMAGE_MULTIPLIER = 0.45
        const val GALE_SIDE_IMPULSE = 0.52
        const val GALE_LIFT_IMPULSE = 0.07
        const val OSSUARY_TRAP_RADIUS = 2.85
        const val OSSUARY_ARM_TICKS = 8
        const val OSSUARY_TRAP_DURATION = 56
        const val OSSUARY_TRAP_DAMAGE_MULTIPLIER = 1.12
        const val OSSUARY_CLOSE_SCENE_TICKS = 9
        const val TIDE_CAPSULE_DURATION = 25
        const val TIDE_OPENING_DAMAGE_MULTIPLIER = 0.24
        const val REQUIEM_PLACEMENT_RANGE = 8.0
        const val REQUIEM_MIN_DISTANCE = 2.0
        const val REQUIEM_DURATION = 74
        const val REQUIEM_RECAST_START = 18
        const val REQUIEM_RECAST_END = 46
        const val REQUIEM_STRIKE_TICKS = 14
        const val REQUIEM_STRIKE_RADIUS = 4.1
        const val REQUIEM_STRIKE_DAMAGE_MULTIPLIER = 1.35
        const val REQUIEM_TARGET_LIMIT = 4
        const val BARRIER_DURATION_TICKS = 90
        const val BARRIER_RECAST_START = 10
        const val BARRIER_RECAST_END = 76
        const val BARRIER_SHATTER_TICKS = 12
        const val BARRIER_PLACEMENT_RANGE = 5.2
        const val BARRIER_MIN_DISTANCE = 2.0
        const val BARRIER_GROUND_PROBE_HEIGHT = 3.4
        const val BARRIER_GROUND_PROBE_RANGE = 6.0
        const val BARRIER_VISUAL_HEIGHT = 2.8
        const val BARRIER_HALF_WIDTH = 2.2
        const val BARRIER_ANGLE_RADIANS = Math.PI / 7.5
        const val BARRIER_SHATTER_RADIUS = 3.0
        const val BARRIER_SHATTER_FORWARD = 3.0
        const val BARRIER_TARGET_LIMIT = 3
        const val BARRIER_SHATTER_PUSH = 0.36
    }
}
