package ru.arc.staffspells

import org.bukkit.Bukkit
import org.bukkit.Color
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.Particle
import org.bukkit.Sound
import org.bukkit.SoundCategory
import org.bukkit.entity.Player
import org.bukkit.util.Vector
import org.joml.Vector3f
import ru.arc.core.LifecycleTaskScope
import java.util.UUID
import kotlin.math.abs
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/** Native staff particles and sound; all delayed phases run on Paper's main thread. */
internal class StaffSpellVisuals(
    private val tasks: LifecycleTaskScope,
    private val sceneryImpact: (UUID, Location, Double) -> Unit = { _, _, _ -> },
    private val sceneryDepression: (UUID, Location, Double) -> Unit = { _, _, _ -> },
    private val currentTick: () -> Long = { Bukkit.getCurrentTick().toLong() },
) {
    private data class OwnerTasks(var generation: Long = 0L, var pending: Int = 0)
    private data class ChargeFeedbackState(val spell: StaffSpell, var progress: Double, var stages: Int)
    private data class QuickParticle(val at: Location, val particle: Particle, val data: Any?, val velocity: Vector)

    private val activeOwner = ThreadLocal<UUID?>()
    private val ownerTasks = mutableMapOf<UUID, OwnerTasks>()
    private val chargeFeedbackStates = mutableMapOf<UUID, ChargeFeedbackState>()
    private val lastCooldownFeedbackTick = mutableMapOf<UUID, Long>()
    private var particleWindow = Long.MIN_VALUE
    private val framePackets = mutableMapOf<UUID, Int>()
    private val frameParticles = mutableMapOf<UUID, Int>()

    /** Associates visual work scheduled by a cast with its owner so a round reset can invalidate it. */
    fun withOwner(ownerId: UUID, action: () -> Unit) {
        val previous = activeOwner.get()
        activeOwner.set(ownerId)
        try {
            action()
        } finally {
            activeOwner.set(previous)
        }
    }

    fun cancel(ownerId: UUID) {
        chargeFeedbackStates.remove(ownerId)
        lastCooldownFeedbackTick.remove(ownerId)
        ownerTasks[ownerId]?.let { state ->
            state.generation++
            if (state.pending == 0) ownerTasks.remove(ownerId, state)
        }
    }

    /** Drops only the active charge stages; the shared cooldown click throttle remains intact. */
    fun cancelChargeFeedback(playerId: UUID) {
        chargeFeedbackStates.remove(playerId)
    }

    /** Clears per-player feedback and invalidates delayed visual work when the module closes. */
    fun close() {
        ownerTasks.values.forEach { it.generation++ }
        ownerTasks.clear()
        chargeFeedbackStates.clear()
        lastCooldownFeedbackTick.clear()
        framePackets.clear()
        frameParticles.clear()
    }

    private fun runLater(delay: Long, action: () -> Unit) {
        val owner = activeOwner.get()
        val state = owner?.let { ownerTasks.getOrPut(it) { OwnerTasks() } }
        val generation = state?.generation
        if (state != null) state.pending++
        tasks.runLater(delay) {
            try {
                if (state == null || state.generation == generation) runOwned(owner, action)
            } finally {
                if (owner != null && state != null) release(owner, state)
            }
        }
    }

    private fun release(owner: UUID, state: OwnerTasks) {
        state.pending--
        if (state.pending <= 0 && ownerTasks[owner] === state) ownerTasks.remove(owner)
    }

    private fun runOwned(owner: UUID?, action: () -> Unit) {
        if (owner == null) {
            action()
            return
        }
        withOwner(owner, action)
    }

    /** One local cast cue, including empty-air casts and the three-branch secondary. */
    fun lightningLaunch(at: Location) = dispatch {
        val origin = at.clone()
        localSound(origin, Sound.BLOCK_RESPAWN_ANCHOR_CHARGE, 0.42f, 1.85f, audibleDistance = 24.0)
        localSound(origin, Sound.ENTITY_LIGHTNING_BOLT_IMPACT, 0.62f, 1.6f, audibleDistance = 32.0)
    }

    /** Quiet charge accents stay just in front of the caster instead of hiding behind the camera. */
    fun chargeFeedback(player: Player, spell: StaffSpell, progress: Double) = dispatch {
        val palette = arcanePalette(spell) ?: return@dispatch
        val anchor = handAnchor(player) ?: return@dispatch
        val playerId = player.uniqueId
        val charge = progress.takeIf(Double::isFinite)?.coerceIn(0.0, 1.0) ?: 0.0
        val stepSound = when (spell) {
            StaffSpell.EMBER, StaffSpell.INFERNO -> Sound.ITEM_FIRECHARGE_USE
            StaffSpell.ICICLE, StaffSpell.FROST -> Sound.BLOCK_AMETHYST_CLUSTER_HIT
            StaffSpell.GRAVITY -> Sound.BLOCK_AMETHYST_BLOCK_RESONATE
            else -> palette.chargeSound
        }
        val readySound = when (spell) {
            StaffSpell.EMBER, StaffSpell.INFERNO -> Sound.ENTITY_BLAZE_SHOOT
            StaffSpell.ICICLE, StaffSpell.FROST -> Sound.BLOCK_AMETHYST_BLOCK_RESONATE
            StaffSpell.GRAVITY -> Sound.BLOCK_RESPAWN_ANCHOR_CHARGE
            else -> palette.launchSound
        }
        val state = chargeFeedbackStates[playerId]
        val activeState = if (state == null || state.spell != spell ||
            charge + CHARGE_RESET_EPSILON < state.progress) {
            ChargeFeedbackState(spell, charge, CHARGE_STAGE_START).also {
                chargeFeedbackStates[playerId] = it
                playPrivate(player, anchor, palette.chargeSound, 0.16f, palette.pitch * 0.78f)
            }
        } else {
            state.progress = maxOf(state.progress, charge)
            state
        }
        if (charge >= CHARGE_QUARTER && activeState.stages and CHARGE_STAGE_QUARTER == 0) {
            activeState.stages = activeState.stages or CHARGE_STAGE_QUARTER
            playPrivate(player, anchor, stepSound, 0.14f, 0.95f)
        }
        if (charge >= CHARGE_HALF && activeState.stages and CHARGE_STAGE_HALF == 0) {
            activeState.stages = activeState.stages or CHARGE_STAGE_HALF
            playPrivate(player, anchor, stepSound, 0.15f, 1.12f)
        }
        if (charge >= CHARGE_THREE_QUARTERS && activeState.stages and CHARGE_STAGE_THREE_QUARTERS == 0) {
            activeState.stages = activeState.stages or CHARGE_STAGE_THREE_QUARTERS
            playPrivate(player, anchor, stepSound, 0.16f, 1.30f)
        }
        if (charge >= CHARGE_READY && activeState.stages and CHARGE_STAGE_READY == 0) {
            activeState.stages = activeState.stages or CHARGE_STAGE_READY
            playPrivate(player, anchor, readySound, 0.22f, 1.45f)
        }

        when (spell) {
            StaffSpell.EMBER -> emberChargeStream(player, anchor, palette.transition, charge)
            StaffSpell.ICICLE -> icicleChargeWisps(player, anchor, palette.transition, charge)
            else -> {
                val radius = 0.07 + charge * 0.12
                repeat(CHARGE_PARTICLE_COUNT) { index ->
                    val angle = index * PI / (CHARGE_PARTICLE_COUNT / 2.0) + charge * PI * 2.0
                    val point = anchor.clone().add(cos(angle) * radius, sin(angle) * radius * 0.55,
                        sin(angle) * radius)
                    emitPrivate(player, point, Particle.DUST_COLOR_TRANSITION, palette.transition)
                }
                if (charge >= CHARGE_QUARTER) emitPrivate(player, anchor, palette.accent)
            }
        }
    }

    private fun emberChargeStream(player: Player, anchor: Location, color: Particle.DustTransition, charge: Double) {
        val eye = player.eyeLocation
        val yaw = Math.toRadians(eye.yaw.toDouble())
        val forward = Vector(-sin(yaw), 0.0, cos(yaw))
        val right = forward.clone().crossProduct(Vector(0.0, 1.0, 0.0))
        val start = anchor.clone().add(forward.clone().multiply(CHARGE_STREAM_START_FORWARD))
        val orientation = chargeOrientation(eye, StaffSpell.EMBER)
        val frontFacet = staffEmberBodyParts(charge, staffEmberChargeOffset())
            .first { it.identity == "ember:5:hotspot" }
        val targetOffset = orientation.transform(Vector3f(frontFacet.center))
        val target = eye.clone().add(targetOffset.x.toDouble(), targetOffset.y.toDouble(), targetOffset.z.toDouble())
        val reach = target.toVector().subtract(start.toVector())
        val arc = 0.10 + charge * 0.08
        for (index in 1..4) {
            val t = index / 4.0
            val curl = sin(t * PI)
            val point = start.clone().add(reach.clone().multiply(t))
                .add(right.clone().multiply(curl * arc)).add(0.0, curl * 0.07, 0.0)
            emitChargeParticle(player, eye, point, Particle.FLAME)
            if (index % 2 == 0) emitChargeParticle(player, eye, point, Particle.DUST_COLOR_TRANSITION, color)
        }
    }

    private fun icicleChargeWisps(player: Player, anchor: Location, color: Particle.DustTransition, charge: Double) {
        val eye = player.eyeLocation
        val yaw = Math.toRadians(eye.yaw.toDouble())
        val forward = Vector(-sin(yaw), 0.0, cos(yaw))
        val right = forward.clone().crossProduct(Vector(0.0, 1.0, 0.0))
        val start = anchor.clone().add(forward.clone().multiply(CHARGE_STREAM_START_FORWARD))
        val orientation = chargeOrientation(eye, StaffSpell.ICICLE)
        for (slot in ICICLE_FEEDBACK_SLOTS) {
            val core = staffIcicleChargeParts(charge, slot).firstOrNull() ?: continue
            val facingSurface = core.rotation.transform(Vector3f(0f, 0f, core.scale.z * 0.45f))
            val localTarget = Vector3f(staffIcicleChargeAnchorOffset(slot))
                .add(core.center).add(facingSurface)
            val targetOffset = orientation.transform(localTarget)
            val target = eye.clone().add(targetOffset.x.toDouble(), targetOffset.y.toDouble(), targetOffset.z.toDouble())
            val drift = target.toVector().subtract(start.toVector())
            for (sample in 0..1) {
                val t = if (sample == 0) 0.72 else 1.0
                val curl = sin(t * PI)
                val point = start.clone().add(drift.clone().multiply(t))
                    .add(right.clone().multiply(curl * (0.04 + charge * 0.04)))
                    .add(0.0, curl * 0.04, 0.0)
                emitChargeParticle(player, eye, point,
                    if (sample == 0) Particle.SNOWFLAKE else Particle.DUST_COLOR_TRANSITION,
                    if (sample == 0) null else color)
            }
        }
    }

    private fun chargeOrientation(eye: Location, spell: StaffSpell) = staffChargeOrientation(spell, eye.yaw,
        Vector3f(eye.direction.x.toFloat(), eye.direction.y.toFloat(), eye.direction.z.toFloat()))

    private fun emitChargeParticle(player: Player, eye: Location, at: Location, particle: Particle, data: Any? = null) {
        if (eye.world != at.world || eye.distanceSquared(at) < CHARGE_PARTICLE_MIN_EYE_DISTANCE_SQUARED) return
        emitPrivate(player, at, particle, data)
    }

    /** One subdued, caster-only cooldown click and dust pulse shared by all staff inputs. */
    fun cooldownFeedback(player: Player, spell: StaffSpell) = dispatch {
        val palette = arcanePalette(spell) ?: return@dispatch
        val anchor = handAnchor(player) ?: return@dispatch
        val playerId = player.uniqueId
        val now = currentTick()
        val previous = lastCooldownFeedbackTick[playerId]
        if (previous != null && now >= previous && now - previous < COOLDOWN_FEEDBACK_TICKS) return@dispatch
        lastCooldownFeedbackTick[playerId] = now
        val sound = when (spell) {
            StaffSpell.EMBER, StaffSpell.INFERNO -> Sound.BLOCK_FIRE_EXTINGUISH
            StaffSpell.TIDE -> Sound.ENTITY_PLAYER_SWIM
            StaffSpell.GALE -> Sound.ENTITY_BREEZE_SLIDE
            StaffSpell.FAULT -> Sound.BLOCK_DEEPSLATE_HIT
            StaffSpell.NOVA -> Sound.BLOCK_ROOTED_DIRT_HIT
            StaffSpell.OSSUARY, StaffSpell.NECROMANCER -> Sound.BLOCK_BONE_BLOCK_HIT
            StaffSpell.REQUIEM -> Sound.BLOCK_SOUL_SAND_HIT
            else -> Sound.BLOCK_AMETHYST_CLUSTER_HIT
        }
        playPrivate(player, anchor, sound, 0.12f, 0.68f)
        repeat(COOLDOWN_PARTICLE_COUNT) { index ->
            val angle = index * PI / (COOLDOWN_PARTICLE_COUNT / 2)
            val point = anchor.clone().add(cos(angle) * COOLDOWN_PARTICLE_RADIUS, 0.015,
                sin(angle) * COOLDOWN_PARTICLE_RADIUS)
            emitPrivate(player, point, Particle.DUST_COLOR_TRANSITION, palette.transition)
        }
    }

    /** Compact six-strand gale funnel; callers drive frames at their existing three-tick cadence. */
    fun galeFunnel(at: Location, ageTicks: Int, charge: Double) = dispatch {
        val palette = arcanePalette(StaffSpell.GALE) ?: return@dispatch
        val origin = at.clone()
        if (!drawable(origin)) return@dispatch
        val intensity = charge.takeIf(Double::isFinite)?.coerceIn(0.0, 1.0) ?: 0.0
        val height = 2.6 + intensity
        val bottomRadius = 0.4 + intensity * 0.08
        val topRadius = 0.88 + intensity * 0.22
        val time = ageTicks.coerceAtLeast(0) * GALE_PHASE_PER_TICK

        repeat(GALE_STRANDS) { strand ->
            repeat(GALE_ROPE_LEVELS) { level ->
                val fraction = level / (GALE_ROPE_LEVELS - 1.0)
                val radius = bottomRadius + (topRadius - bottomRadius) * fraction * fraction
                val angle = strand * PI / (GALE_STRANDS / 2.0) + fraction * PI * 1.7 + time * 1.6
                val point = origin.clone().add(cos(angle) * radius,
                    GALE_BASE_Y + fraction * height, sin(angle) * radius)
                emit(point, Particle.DUST_COLOR_TRANSITION, palette.transition,
                    minimumEyeDistance = COMPACT_CAMERA_CLEARANCE)
            }
        }
        // The crown spins against the ropes; four small clouds orbit at different heights as debris.
        repeat(GALE_CROWN_PARTICLES) { index ->
            val angle = index * PI / (GALE_CROWN_PARTICLES / 2.0) - time * 1.9
            val point = origin.clone().add(cos(angle) * topRadius * 0.84,
                GALE_BASE_Y + height * 0.9, sin(angle) * topRadius * 0.84)
            emit(point, palette.accent, minimumEyeDistance = COMPACT_CAMERA_CLEARANCE)
        }
        repeat(GALE_DEBRIS_PARTICLES) { index ->
            val fraction = 0.22 + index * 0.18
            val radius = bottomRadius + (topRadius - bottomRadius) * fraction * fraction
            val angle = index * PI / (GALE_DEBRIS_PARTICLES / 2.0) + time * 2.5
            val point = origin.clone().add(cos(angle) * radius,
                GALE_BASE_Y + height * fraction, sin(angle) * radius)
            emit(point, palette.accent, minimumEyeDistance = COMPACT_CAMERA_CLEARANCE)
        }

        // Frames arrive every three ticks, so this window catches each twenty-tick boundary.
        if (ageTicks > 0 && ageTicks % GALE_SOUND_INTERVAL_TICKS < GALE_FRAME_TICKS) {
            val sound = if ((ageTicks / GALE_SOUND_INTERVAL_TICKS) % 2 == 0)
                Sound.ENTITY_BREEZE_IDLE_GROUND else Sound.ITEM_ELYTRA_FLYING
            localSound(origin, sound, 0.14f, 1.0f, audibleDistance = GALE_SOUND_DISTANCE)
        }
    }

    /** Native-only primary attacks: a swept air crescent or a low breaking tide wall. */
    fun quickElement(at: Location, spell: StaffSpell, ageTicks: Int, durationTicks: Int, radius: Double) = dispatch {
        if (spell != StaffSpell.GALE && spell != StaffSpell.TIDE) return@dispatch
        val palette = arcanePalette(spell) ?: return@dispatch
        if (durationTicks <= 0 || ageTicks >= durationTicks) return@dispatch
        val origin = at.clone()
        if (!drawable(origin)) return@dispatch
        val effectRadius = boundedRadius(radius, 0.55, QUICK_ELEMENT_MAX_RADIUS) ?: return@dispatch
        val age = ageTicks.coerceAtLeast(0)
        val progress = (age.toDouble() / durationTicks).coerceIn(0.0, 1.0)
        val remaining = durationTicks - age
        val fade = if (remaining < QUICK_ELEMENT_FADE_TICKS) remaining.toDouble() / QUICK_ELEMENT_FADE_TICKS
            else 1.0
        if (fade <= 0.0) return@dispatch
        val form = QUICK_ELEMENT_MIN_FORM + (age / QUICK_ELEMENT_FORM_TICKS).coerceIn(0.0, 1.0) *
            (1.0 - QUICK_ELEMENT_MIN_FORM)
        val maximumParticles = if (spell == StaffSpell.GALE) GALE_QUICK_PARTICLE_CAP else TIDE_QUICK_PARTICLE_CAP
        val activeParticles = (maximumParticles * form * fade).roundToInt().coerceIn(1, maximumParticles)
        val yaw = Math.toRadians(origin.yaw.toDouble())
        val forward = Vector(-sin(yaw), 0.0, cos(yaw))
        val right = forward.clone().crossProduct(Vector(0.0, 1.0, 0.0)).normalize()
        val particles = ArrayList<QuickParticle>(maximumParticles)

        if (spell == StaffSpell.GALE) {
            val sweep = progress * QUICK_ELEMENT_SWEEP_DISTANCE
            repeat(GALE_QUICK_BANDS) { band ->
                repeat(GALE_QUICK_POINTS_PER_BAND) { pointIndex ->
                    val across = pointIndex / (GALE_QUICK_POINTS_PER_BAND - 1.0) * 2.0 - 1.0
                    val curve = 1.0 - across * across
                    val width = effectRadius * (0.42 + progress * 0.10 + band * 0.12)
                    val depth = 0.24 + band * 0.12
                    val point = origin.clone()
                        .add(forward.clone().multiply(0.18 + sweep + band * 0.18 + curve * depth))
                        .add(right.clone().multiply(across * width))
                        .add(0.0, (band - 1) * 0.22 + curve * 0.06, 0.0)
                    val velocity = forward.clone().multiply(0.035 + band * 0.006)
                        .add(right.clone().multiply(across * 0.006))
                        .add(Vector(0.0, 0.006 + curve * 0.004, 0.0))
                    particles += QuickParticle(point, Particle.CLOUD, null, velocity)
                }
            }
            repeat(GALE_QUICK_DUST_POINTS) { index ->
                val across = (index - 1) * 0.32
                val point = origin.clone()
                    .add(forward.clone().multiply(-0.08 + sweep * 0.35))
                    .add(right.clone().multiply(across))
                    .add(0.0, -0.14 + index * 0.04, 0.0)
                particles += QuickParticle(point, Particle.DUST_COLOR_TRANSITION, palette.transition,
                    forward.clone().multiply(0.012).add(Vector(0.0, 0.004, 0.0)))
            }
        } else {
            val sweep = progress * QUICK_ELEMENT_SWEEP_DISTANCE
            val halfWidth = effectRadius * (0.46 + progress * 0.42)
            repeat(TIDE_QUICK_COLUMNS) { column ->
                val across = column / (TIDE_QUICK_COLUMNS - 1.0) * 2.0 - 1.0
                val curve = 1.0 - across * across
                val front = 0.22 + sweep + curve * 0.34
                val wallHeight = 0.58 + progress * 0.20 + curve * 0.16
                repeat(TIDE_QUICK_SPLASH_ROWS) { row ->
                    val point = origin.clone()
                        .add(forward.clone().multiply(front + row * 0.035))
                        .add(right.clone().multiply(across * halfWidth))
                        .add(0.0, TIDE_QUICK_BASE_Y + wallHeight * row /
                            (TIDE_QUICK_SPLASH_ROWS - 1.0), 0.0)
                    val velocity = forward.clone().multiply(0.014 + progress * 0.008)
                        .add(right.clone().multiply(across * 0.004))
                        .add(Vector(0.0, 0.012 + row * 0.006 + curve * 0.004, 0.0))
                    particles += QuickParticle(point, Particle.SPLASH, null, velocity)
                }
                if (column < TIDE_QUICK_FOAM_POINTS) {
                    val crest = origin.clone()
                        .add(forward.clone().multiply(front + 0.08))
                        .add(right.clone().multiply(across * halfWidth))
                        .add(0.0, TIDE_QUICK_BASE_Y + wallHeight + 0.08 + (column % 2) * 0.05, 0.0)
                    particles += QuickParticle(crest, Particle.CLOUD, null,
                        forward.clone().multiply(0.018).add(Vector(0.0, 0.018, 0.0)))
                }
                val base = origin.clone()
                    .add(forward.clone().multiply(front - 0.12))
                    .add(right.clone().multiply(across * halfWidth))
                    .add(0.0, TIDE_QUICK_BASE_Y + 0.06, 0.0)
                particles += QuickParticle(base, Particle.DUST_COLOR_TRANSITION, palette.transition,
                    forward.clone().multiply(0.01).add(Vector(0.0, 0.006, 0.0)))
            }
        }

        repeat(activeParticles) { index ->
            val particle = particles[index * particles.size / activeParticles]
            emitNativeVelocity(particle.at, particle.particle, particle.data, particle.velocity,
                minimumEyeDistance = QUICK_ELEMENT_MIN_EYE_DISTANCE)
        }
    }

    /** One cast cue; arcane phase 0 adds its own positional launch accent at the effect point. */
    fun release(at: Location, spell: StaffSpell, charged: Boolean) = dispatch {
        val palette = arcanePalette(spell) ?: return@dispatch
        val origin = at.clone()
        val intensity = if (charged) 0.68f else 0.48f
        localSound(origin, palette.launchSound, intensity, palette.pitch, audibleDistance = 36.0)
        if (charged && spell in LAYERED_RELEASE_SPELLS) localSound(origin, palette.chargeSound, 0.24f, palette.pitch + 0.28f,
            audibleDistance = 24.0)
    }

    /** Phase 0 launches, phase 1 leaves a compact moving wake, and phase 2 opens the impact ring. */
    fun arcane(at: Location, spell: StaffSpell, phase: Int, radius: Double) = dispatch {
        val palette = arcanePalette(spell) ?: return@dispatch
        val origin = at.clone()
        if (!drawable(origin)) return@dispatch
        val reach = boundedRadius(radius, 0.35, 5.5) ?: return@dispatch
        when (phase) {
            0 -> {
                localSound(origin, palette.launchSound, 0.48f, palette.pitch + 0.12f,
                    audibleDistance = 32.0)
                repeat(6) { index ->
                    val angle = index * PI / 3.0 + if (spell.id == "prism") PI / 6.0 else 0.0
                    val ring = reach * when (spell.id) {
                        "gale" -> if (index % 2 == 0) 0.72 else 1.0
                        "requiem" -> 0.58 + (index % 3) * 0.14
                        "nova" -> 0.72 + (index % 2) * 0.20
                        else -> 0.82
                    }
                    val lift = when (spell.id) {
                        "tide" -> sin(angle * 2.0) * 0.24
                        "gale" -> sin(angle) * 0.18
                        "requiem" -> (index % 3) * 0.12
                        "nova" -> 0.08 + (index % 2) * 0.13
                        else -> 0.10
                    }
                    val point = origin.clone().add(cos(angle) * ring, lift, sin(angle) * ring)
                    emit(point, Particle.DUST_COLOR_TRANSITION, palette.transition,
                        minimumEyeDistance = COMPACT_CAMERA_CLEARANCE)
                    if (index % 2 == 0) emit(point.clone().add(0.0, 0.12, 0.0), palette.accent,
                        minimumEyeDistance = COMPACT_CAMERA_CLEARANCE)
                }
                emit(origin.clone().add(0.0, 0.14, 0.0), Particle.DUST, palette.highlight,
                    minimumEyeDistance = COMPACT_CAMERA_CLEARANCE)
            }
            1 -> {
                // Four particles per travel call; callers may animate this at projectile tick rate.
                repeat(4) { index ->
                    val angle = index * PI / 2.0 + when (spell.id) {
                        "tide" -> PI / 4.0
                        "prism" -> PI / 8.0
                        else -> 0.0
                    }
                    val radiusAt = reach * if (spell.id == "gale") 0.72 else 0.42
                    val point = origin.clone().add(cos(angle) * radiusAt,
                        sin(angle * 2.0 + index * 0.7) * 0.12, sin(angle) * radiusAt)
                    if (index % 2 == 0) emit(point, palette.accent,
                        minimumEyeDistance = COMPACT_CAMERA_CLEARANCE)
                    else emit(point, Particle.DUST_COLOR_TRANSITION, palette.transition,
                        minimumEyeDistance = COMPACT_CAMERA_CLEARANCE)
                }
            }
            2 -> {
                localSound(origin, palette.impactSound, 0.78f, palette.pitch - 0.14f,
                    audibleDistance = 64.0)
                repeat(10) { index ->
                    val angle = index * PI / 5.0 + if (spell.id == "lunar") PI / 10.0 else 0.0
                    val ring = reach * (0.72 + (index % 3) * 0.10)
                    val lift = when (spell.id) {
                        "tide" -> sin(angle * 2.0) * 0.26
                        "gale" -> 0.10 + (index % 2) * 0.18
                        "requiem" -> 0.18 + (index % 4) * 0.12
                        "nova" -> 0.22 + (index % 3) * 0.15
                        else -> 0.14 + (index % 2) * 0.12
                    }
                    val point = origin.clone().add(cos(angle) * ring, lift, sin(angle) * ring)
                    emit(point, Particle.DUST_COLOR_TRANSITION, palette.transition,
                        minimumEyeDistance = COMPACT_CAMERA_CLEARANCE)
                    if (index % 2 == 0) emit(point.clone().add(0.0, 0.12, 0.0), palette.accent,
                        minimumEyeDistance = COMPACT_CAMERA_CLEARANCE)
                }
                emit(origin.clone().add(0.0, 0.2, 0.0), palette.impactParticle,
                    minimumEyeDistance = COMPACT_CAMERA_CLEARANCE)
            }
        }
    }

    fun lightning(from: Location, to: Location) = dispatch {
        val start = from.clone()
        val end = to.clone()
        val distance = segmentLength(start, end) ?: return@dispatch
        val direction = end.toVector().subtract(start.toVector())
        val points = boltPoints(start, end, distance)
        points.forEachIndexed { i, point ->
            emit(point, Particle.ELECTRIC_SPARK)
            if (i % 4 == 0) emit(point.clone().add(0.0, 0.035, 0.0), Particle.END_ROD)
            if (i % 3 == 0) emit(point.clone().add(0.0, 0.055, 0.0),
                Particle.DUST_COLOR_TRANSITION, CHAIN_ARC)
        }
        val frame = frame(direction.clone().multiply(-1.0)) ?: return@dispatch
        listOf(0.31, 0.68).forEachIndexed { branch, fraction ->
            val anchor = points[(fraction * (points.lastIndex)).toInt()]
            val sign = if (branch == 1) -1.0 else 1.0
            for (step in 1..2) {
                val t = step / 2.0
                emit(anchor.clone()
                    .add(frame.first.clone().multiply(sign * t * 0.52))
                    .add(frame.second.clone().multiply(t * (if (branch == 1) -0.22 else 0.18))),
                    Particle.ELECTRIC_SPARK)
            }
        }
        localSound(start.clone().add(direction.normalize().multiply(min(0.8, distance * 0.6))),
            Sound.BLOCK_RESPAWN_ANCHOR_CHARGE, 0.5f, 1.7f, audibleDistance = 24.0)
        localSound(end, Sound.ENTITY_LIGHTNING_BOLT_IMPACT, 0.82f, 1.15f, audibleDistance = 64.0)
        // A compact charged crown makes the contact point read brighter than the travel line.
        emit(end.clone().add(0.0, 0.12, 0.0), Particle.FLASH)
        val impactFrame = frame(direction) ?: return@dispatch
        repeat(6) { i ->
            val angle = i * PI / 3.0
            emit(end.clone()
                .add(impactFrame.first.clone().multiply(cos(angle) * 0.22))
                .add(impactFrame.second.clone().multiply(sin(angle) * 0.22)),
                if (i % 2 == 0) Particle.END_ROD else Particle.ELECTRIC_SPARK)
        }
        runLater(1) {
            lineSamples(start, end, (distance * 0.55).toInt().coerceIn(6, 16)).forEachIndexed { i, point ->
                if (i % 3 == 0) emit(point, Particle.ELECTRIC_SPARK)
            }
            repeat(3) { i ->
                val side = if (i == 1) -1.0 else 1.0
                emit(end.clone().add(frame.first.clone().multiply(side * (0.12 + i * 0.08))), Particle.ELECTRIC_SPARK)
            }
            localSound(end, Sound.ENTITY_LIGHTNING_BOLT_THUNDER, 0.58f, 1.35f, audibleDistance = 64.0)
        }
    }

    /** Stepwise homing-bolt trail: five accents max and no head flash or repeated sound. */
    fun lightningTrail(from: Location, to: Location) = dispatch {
        val start = from.clone()
        val end = to.clone()
        val distance = segmentLength(start, end) ?: return@dispatch
        val direction = end.toVector().subtract(start.toVector())
        val basis = frame(direction) ?: return@dispatch
        // Controller steps are at most two blocks; show only the route segment just traveled.
        val segments = 2
        val samples = lineSamples(start, end, segments)
        samples.forEachIndexed { index, point ->
            if (index == 1) emit(point, Particle.DUST_COLOR_TRANSITION, CHAIN_ARC,
                minimumEyeDistance = COMPACT_CAMERA_CLEARANCE)
            else emit(point, Particle.ELECTRIC_SPARK, minimumEyeDistance = COMPACT_CAMERA_CLEARANCE)
        }
        repeat(2) { i ->
            val side = if (i == 0) -1.0 else 1.0
            emit(interpolate(start, end, 0.75).add(basis.first.clone().multiply(side * 0.09)),
                if (i == 0) Particle.END_ROD else Particle.ELECTRIC_SPARK,
                minimumEyeDistance = COMPACT_CAMERA_CLEARANCE)
        }
    }

    /** Compact ice trail for a single guided icicle step. */
    fun icicleTrail(from: Location, to: Location) = dispatch {
        val start = from.clone()
        val end = to.clone()
        val distance = segmentLength(start, end) ?: return@dispatch
        val direction = end.toVector().subtract(start.toVector())
        val basis = frame(direction) ?: return@dispatch
        val midpoint = interpolate(start, end, 0.52)
        emit(midpoint, Particle.SNOWFLAKE, minimumEyeDistance = COMPACT_CAMERA_CLEARANCE)
        emit(interpolate(start, end, 0.78).add(basis.first.clone().multiply(0.08)),
            Particle.DUST_COLOR_TRANSITION, ICE_TO_AQUA, minimumEyeDistance = COMPACT_CAMERA_CLEARANCE)
        if (distance > 1.0) emit(end, Particle.SNOWFLAKE, minimumEyeDistance = COMPACT_CAMERA_CLEARANCE)
    }

    /** A short, camera-culled ice shard burst when the guided projectile stops. */
    fun icicleBurst(at: Location) = dispatch {
        val origin = at.clone()
        if (!drawable(origin)) return@dispatch
        localSound(origin, Sound.BLOCK_GLASS_BREAK, 0.72f, 0.82f, audibleDistance = 32.0)
        localSound(origin, Sound.BLOCK_AMETHYST_BLOCK_CHIME, 0.42f, 1.18f, audibleDistance = 24.0)
        val ice = Material.PACKED_ICE.createBlockData()
        repeat(6) { index ->
            val angle = index * PI / 3.0
            emit(origin.clone().add(cos(angle) * 0.24, 0.12 + (index % 3) * 0.09,
                sin(angle) * 0.24), Particle.BLOCK, ice, COMPACT_CAMERA_CLEARANCE)
        }
        emit(origin.clone().add(0.0, 0.28, 0.0), Particle.END_ROD,
            minimumEyeDistance = COMPACT_CAMERA_CLEARANCE)
        emit(origin.clone().add(0.0, 0.08, 0.0), Particle.SNOWFLAKE,
            minimumEyeDistance = COMPACT_CAMERA_CLEARANCE)
    }

    /** Three restrained ground pulses before the delayed targeted rupture. */
    fun faultCharge(at: Location, radius: Double) = dispatch {
        val origin = at.clone()
        val reach = boundedRadius(radius, 1.8, 8.0) ?: return@dispatch
        if (!drawable(origin)) return@dispatch
        localSound(origin, Sound.BLOCK_RESPAWN_ANCHOR_CHARGE, 0.42f, 0.58f, audibleDistance = 24.0)
        localSound(origin, Sound.BLOCK_AMETHYST_CLUSTER_HIT, 0.24f, 0.72f, audibleDistance = 18.0)
        val basalt = Material.BASALT.createBlockData()
        val amethyst = Material.AMETHYST_BLOCK.createBlockData()
        repeat(3) { phase ->
            val draw = {
                repeat(4) { index ->
                    val angle = index * PI / 2.0 + phase * 0.22
                    val ring = reach * (0.20 + phase * 0.045 + (index % 2) * 0.035)
                    val point = origin.clone().add(cos(angle) * ring, 0.10 + (index % 2) * 0.06,
                        sin(angle) * ring)
                    emit(point, Particle.BLOCK, if ((index + phase) % 4 == 0) amethyst else basalt,
                        COMPACT_CAMERA_CLEARANCE)
                    emit(point.clone().add(0.0, 0.12, 0.0), Particle.DUST_COLOR_TRANSITION,
                        FAULT_TO_AMETHYST, COMPACT_CAMERA_CLEARANCE)
                }
            }
            if (phase == 0) draw() else runLater((phase * 2).toLong()) { draw() }
        }
    }

    /** A local stone impact with a tight block-chip ring and a sparse amethyst dust tail. */
    fun sceneryBurst(at: Location, radius: Double) = dispatch {
        activeOwner.get()?.let { sceneryImpact(it, at, radius) }
    }

    fun faultBurst(at: Location, radius: Double) = dispatch {
        sceneryBurst(at, radius)
        val origin = at.clone()
        val reach = boundedRadius(radius, 1.8, 8.0) ?: return@dispatch
        if (!drawable(origin)) return@dispatch
        localSound(origin, Sound.BLOCK_STONE_BREAK, 0.82f, 0.72f, audibleDistance = 64.0)
        localSound(origin, Sound.BLOCK_DEEPSLATE_BREAK, 0.58f, 0.62f, audibleDistance = 32.0)
        localSound(origin, Sound.BLOCK_AMETHYST_CLUSTER_HIT, 0.36f, 0.9f, audibleDistance = 24.0)
        val basalt = Material.BASALT.createBlockData()
        val obsidian = Material.OBSIDIAN.createBlockData()
        repeat(10) { index ->
            val angle = index * PI / 5.0
            val ring = reach * (0.42 + (index % 3) * 0.045)
            val point = origin.clone().add(cos(angle) * ring, 0.16 + (index % 3) * 0.10,
                sin(angle) * ring)
            emit(point, Particle.BLOCK, if (index % 3 == 0) obsidian else basalt,
                COMPACT_CAMERA_CLEARANCE)
            if (index % 2 == 0) emit(point.clone().add(0.0, 0.12, 0.0),
                Particle.DUST_COLOR_TRANSITION, FAULT_TO_AMETHYST,
                COMPACT_CAMERA_CLEARANCE)
        }
        repeat(4) { index ->
            val angle = index * PI / 2.0 + PI / 4.0
            emit(origin.clone().add(cos(angle) * 0.28, 0.36 + (index % 2) * 0.10,
                sin(angle) * 0.28), Particle.END_ROD,
                minimumEyeDistance = COMPACT_CAMERA_CLEARANCE)
        }
        runLater(2) {
            repeat(6) { index ->
                val angle = index * PI / 3.0 + 0.16
                val ring = reach * (0.56 + (index % 2) * 0.035)
                emit(origin.clone().add(cos(angle) * ring, 0.22 + (index % 3) * 0.12,
                    sin(angle) * ring), Particle.DUST, FAULT_DUST,
                    COMPACT_CAMERA_CLEARANCE)
            }
        }
    }

    fun markLaunch(from: Location, to: Location) = dispatch {
        val start = from.clone()
        val end = to.clone()
        if (segmentLength(start, end) == null) return@dispatch
        val offset = end.toVector().subtract(start.toVector())
        val basis = frame(offset) ?: return@dispatch
        val castPoint = start.clone().add(offset.normalize().multiply(0.75))
        localSound(castPoint, Sound.ENTITY_ENDERMAN_TELEPORT, 0.72f, 1.15f, audibleDistance = 40.0)
        localSound(castPoint, Sound.BLOCK_AMETHYST_BLOCK_CHIME, 0.32f, 1.35f, audibleDistance = 24.0)
        for (phase in 0..3) {
            val draw = { markTearStage(start, end, basis.first, basis.second, phase) }
            if (phase == 0) draw() else runLater(phase.toLong()) { draw() }
        }
    }

    /** Called every two ticks: a shrinking violet-white vortex with one restrained cast cue. */
    fun markCharge(at: Location, progress: Double) = dispatch {
        val origin = at.clone()
        if (!drawable(origin)) return@dispatch
        val charge = progress.takeIf(Double::isFinite)?.coerceIn(0.0, 1.0) ?: 0.0
        val reach = 0.95 * (1.0 - charge) + 0.12
        repeat(3) { i ->
            val angle = i * PI * 2 / 3 - PI / 2 + charge * PI * 2.0
            val point = origin.clone().add(cos(angle) * reach,
                0.12 + sin(angle * 1.5 + charge * PI) * 0.16, sin(angle) * reach)
            emit(point, Particle.REVERSE_PORTAL, minimumEyeDistance = MARK_CAMERA_CLEARANCE)
            emit(point.clone().add(0.0, 0.08, 0.0), Particle.DUST_COLOR_TRANSITION, VIOLET_TO_WHITE,
                minimumEyeDistance = MARK_CAMERA_CLEARANCE)
        }
        if (charge > 0.35) emit(origin.clone().add(0.0, 0.18, 0.0), Particle.END_ROD,
            minimumEyeDistance = MARK_CAMERA_CLEARANCE)
    }

    fun markBurst(at: Location, radius: Double, blackhole: Boolean = false) = dispatch {
        sceneryBurst(at, radius)
        val origin = at.clone()
        val reach = boundedRadius(radius, 0.5, 10.0) ?: return@dispatch
        if (blackhole) {
            blackholeCollapse(origin, reach)
            return@dispatch
        }
        localSound(origin, Sound.ENTITY_EVOKER_CAST_SPELL, 0.86f, 1.08f, audibleDistance = 64.0)
        localSound(origin, Sound.BLOCK_BEACON_POWER_SELECT, 0.38f, 0.72f, audibleDistance = 28.0)
        emit(origin.clone().add(0.0, 0.48, 0.0), Particle.FLASH)
        emit(origin.clone().add(0.0, 0.22, 0.0), Particle.END_ROD)
        val crackRadius = min(reach * 0.72, 3.0)
        repeat(12) { i ->
            val angle = i * PI * 2.0 / 12.0
            val point = origin.clone().add(cos(angle) * crackRadius, 0.18 + (i % 3) * 0.12,
                sin(angle) * crackRadius)
            emit(point, Particle.DUST_COLOR_TRANSITION, VIOLET_TO_WHITE)
            if (i % 3 == 0) emit(point.clone().add(0.0, 0.12, 0.0), Particle.END_ROD)
        }
        // The tear opens over four ticks, then sheds a sparse twenty-tick dust tail.
        runLater(2) {
            repeat(12) { i ->
                val angle = i * PI * 2.0 / 12.0 + 0.12
                val radiusAt = crackRadius * 0.72
                val point = origin.clone().add(cos(angle) * radiusAt, 0.12 + (i % 4) * 0.16,
                    sin(angle) * radiusAt)
                emit(point, Particle.DUST_COLOR_TRANSITION, VIOLET_TO_WHITE)
                if (i % 2 == 0) emit(point, Particle.REVERSE_PORTAL)
            }
        }
        runLater(4) {
            repeat(16) { i ->
                val angle = i * PI * 2.0 / 16.0 + 0.18
                val point = origin.clone().add(cos(angle) * crackRadius * 1.08,
                    0.16 + (i % 4) * 0.14, sin(angle) * crackRadius * 1.08)
                emit(point, Particle.DUST, VIOLET_BURST)
                if (i % 2 == 0) emit(point.clone().add(0.0, 0.1, 0.0), Particle.END_ROD)
            }
        }
        listOf(8L to 10, 12L to 8, 16L to 5, 20L to 3).forEach { (delay, count) ->
            runLater(delay) {
                repeat(count) { i ->
                    val angle = i * PI * 2.0 / count + delay * 0.045
                    val radiusAt = crackRadius * (0.88 + (i % 3) * 0.04)
                    emit(origin.clone().add(cos(angle) * radiusAt, 0.24 + (i % 4) * 0.14,
                        sin(angle) * radiusAt), Particle.DUST, VIOLET)
                }
            }
        }
    }

    /** A short floor-and-crest front, or a broad radial ring, over ticks 2 through 18. */
    fun wave(origin: Location, end: Location, radius: Double, frost: Boolean, directional: Boolean) = dispatch {
        val start = origin.clone()
        val endpoint = end.clone()
        if (!drawable(start) || endpoint.world !== start.world || !valid(endpoint)) return@dispatch

        if (directional && !frost) {
            val offset = endpoint.toVector().subtract(start.toVector()).apply { y = 0.0 }
            val length = offset.length().takeIf { it.isFinite() && it in 0.5..96.0 } ?: return@dispatch
            val width = boundedRadius(radius, 0.5, 5.5) ?: return@dispatch
            val basis = frame(offset) ?: return@dispatch
            val forward = offset.normalize()
            val reach = min(length, 12.0)
            repeat(10) { phase ->
                val age = 2 + phase * 2
                runLater(age.toLong()) {
                    if (phase == 0) localSound(start.clone().apply { y = start.blockY + 0.08 },
                        Sound.BLOCK_ROOTED_DIRT_BREAK, 0.74f, 1.12f)
                    novaWaveFrame(start, forward, basis.first, reach, width, age)
                }
            }
        } else if (directional) {
            val offset = endpoint.toVector().subtract(start.toVector()).apply { y = 0.0 }
            val length = offset.length().takeIf { it.isFinite() && it in 0.5..96.0 } ?: return@dispatch
            val width = boundedRadius(radius, 0.5, 5.5) ?: return@dispatch
            val basis = frame(offset) ?: return@dispatch
            val forward = offset.normalize()
            val reach = min(length, 12.0)
            repeat(9) { phase ->
                runLater((2 + phase * 2).toLong()) {
                    val front = start.clone().add(forward.clone().multiply(1.8 + (reach - 1.8) * phase / 8.0))
                    val floorY = start.blockY + 0.06
                    repeat(7) { i ->
                        val side = (i - 3) / 3.0 * width * (1.8 + (reach - 1.8) * phase / 8.0) / reach
                        val floor = front.clone().add(basis.first.clone().multiply(side)).apply { y = floorY }
                        val crestHeight = if (frost) 0.34 else 1.0 + sin(PI * phase / 8.0) * 1.1
                        val crest = floor.clone().add(0.0, crestHeight + (i % 2) * 0.08, 0.0)
                        if (frost) {
                            emit(floor, Particle.SNOWFLAKE)
                            emit(crest, Particle.DUST_COLOR_TRANSITION, ICE_TO_AQUA)
                        } else {
                            emit(floor, Particle.DUST_COLOR_TRANSITION, GREEN_TO_AQUA)
                            emit(crest, Particle.CLOUD)
                            if (i % 3 == 0) emit(crest.clone().add(0.0, 0.22, 0.0), Particle.END_ROD)
                        }
                    }
                }
            }
        } else {
            val reach = boundedRadius(radius, 0.5, 8.0) ?: return@dispatch
            val packedIce = if (frost) Material.PACKED_ICE.createBlockData() else null
            if (frost) {
                localSound(start, Sound.BLOCK_GLASS_PLACE, .76f, .62f)
                localSound(start, Sound.BLOCK_POWDER_SNOW_BREAK, .40f, .75f)
            }
            repeat(9) { phase ->
                runLater((2 + phase * 2).toLong()) {
                    val ringRadius = 1.8 + (reach - 1.8) * phase / 8.0
                    val floorY = start.blockY + 0.06
                    val samples = if (frost) 16 else 12
                    if (frost && phase == 8) {
                        localSound(start, Sound.BLOCK_GLASS_BREAK, .90f, .85f)
                        localSound(start, Sound.BLOCK_AMETHYST_BLOCK_CHIME, .32f, 1.3f)
                    }
                    repeat(samples) { i ->
                        val angle = i * PI * 2 / samples + if (frost) 0.0 else phase * 0.09
                        val point = start.clone().add(cos(angle) * ringRadius, 0.0, sin(angle) * ringRadius)
                            .apply { y = floorY }
                        if (frost) emit(point, Particle.SNOWFLAKE)
                        else emit(point, Particle.DUST_COLOR_TRANSITION, GREEN_TO_AQUA)
                        if (i % 2 == 0) {
                            val crest = point.clone().add(0.0, if (frost) .36 + sin(phase * PI / 8.0) * .65 else .34, 0.0)
                            if (frost && (i / 2 + phase) % 3 == 0) emit(crest, Particle.BLOCK, packedIce)
                            else if (frost) emit(crest, Particle.END_ROD)
                            else emit(crest, Particle.DUST_COLOR_TRANSITION, GREEN_TO_AQUA)
                        }
                    }
                }
            }
        }
    }

    /** Three inward-spiraling arms and a bright core; one low cast cue, no repeated sound. */
    fun gravity(at: Location, radius: Double, progress: Double) = dispatch {
        val origin = at.clone()
        if (!drawable(origin)) return@dispatch
        val reach = boundedRadius(radius, 0.5, 10.0) ?: return@dispatch
        val charge = progress.takeIf(Double::isFinite)?.coerceIn(0.0, 1.0) ?: 0.0
        if (charge <= 0.000001) {
            localSound(origin, Sound.BLOCK_RESPAWN_ANCHOR_CHARGE, 0.32f, 0.55f, audibleDistance = 24.0)
            localSound(origin, Sound.BLOCK_BEACON_AMBIENT, 0.18f, 0.62f, audibleDistance = 18.0)
        }
        val orbit = 0.16 + (reach - 0.16) * (1.0 - charge)
        // Three dense, continuously rotating arms and a bright rim collapse inward across the charge.
        repeat(3) { arm ->
            repeat(6) { step ->
                val progressAlongArm = step / 5.0
                val angle = arm * PI * 2.0 / 3.0 + charge * PI * 5.0 + progressAlongArm * PI * 2.2
                val radiusAt = 0.18 + (orbit - 0.18) * (1.0 - progressAlongArm)
                val height = sin(progressAlongArm * PI * 2.0 + arm * 0.72) * 0.78 + progressAlongArm * 0.10
                val point = origin.clone().add(cos(angle) * radiusAt, height, sin(angle) * radiusAt)
                if ((arm + step) % 2 == 0) emit(point, Particle.DUST_COLOR_TRANSITION, ACCRETION_DUST,
                    minimumEyeDistance = MARK_CAMERA_CLEARANCE)
                else emit(point, Particle.REVERSE_PORTAL, minimumEyeDistance = MARK_CAMERA_CLEARANCE)
            }
        }
        repeat(12) { i ->
            val angle = i * PI * 2.0 / 12.0 - charge * PI * 3.0
            val rimRadius = orbit * 0.82
            val point = origin.clone().add(cos(angle) * rimRadius, sin(angle * 2.0 + charge * PI) * 0.18,
                sin(angle) * rimRadius)
            emit(point, if (i % 4 == 0) Particle.REVERSE_PORTAL else Particle.DUST_COLOR_TRANSITION,
                if (i % 4 == 0) null else BLACKHOLE_RIM, minimumEyeDistance = MARK_CAMERA_CLEARANCE)
        }
        repeat(4) { i ->
            val angle = i * PI / 2.0 - charge * PI * 2.0
            val coreRadius = 0.12 + (i % 3) * 0.06
            val point = origin.clone().add(cos(angle) * coreRadius, -0.28 + (i % 4) * 0.18,
                sin(angle) * coreRadius)
            if (i % 2 == 0) emit(point, Particle.END_ROD, minimumEyeDistance = MARK_CAMERA_CLEARANCE)
            else emit(point, Particle.DUST_COLOR_TRANSITION, BLACKHOLE_CORE,
                minimumEyeDistance = MARK_CAMERA_CLEARANCE)
        }
        emit(origin, Particle.END_ROD, minimumEyeDistance = MARK_CAMERA_CLEARANCE)
    }

    private fun blackholeCollapse(origin: Location, reach: Double) {
        localSound(origin, Sound.ENTITY_ENDERMAN_TELEPORT, 0.88f, 0.72f, audibleDistance = 64.0)
        localSound(origin, Sound.BLOCK_PORTAL_TRAVEL, 0.46f, 0.48f, audibleDistance = 64.0)
        val rimRadius = min(reach * 0.76, 3.4)
        emit(origin.clone().add(0.0, 0.34, 0.0), Particle.FLASH)
        repeat(12) { i ->
            val angle = i * PI * 2.0 / 12.0
            val point = origin.clone().add(cos(angle) * rimRadius, sin(angle * 2.0) * 0.2,
                sin(angle) * rimRadius)
            emit(point, if (i % 3 == 0) Particle.REVERSE_PORTAL else Particle.DUST_COLOR_TRANSITION,
                if (i % 3 == 0) null else BLACKHOLE_RIM)
        }
        runLater(2) {
            repeat(10) { i ->
                val angle = i * PI * 2.0 / 10.0 + 0.2
                val point = origin.clone().add(cos(angle) * rimRadius * 0.58,
                    sin(angle * 2.0) * 0.12, sin(angle) * rimRadius * 0.58)
                emit(point, Particle.REVERSE_PORTAL)
                if (i % 2 == 0) emit(point, Particle.DUST_COLOR_TRANSITION, BLACKHOLE_CORE)
            }
        }
        runLater(4) {
            localSound(origin, Sound.BLOCK_BEACON_DEACTIVATE, 0.34f, 0.62f, audibleDistance = 32.0)
            emit(origin, Particle.FLASH)
            repeat(6) { i ->
                val angle = i * PI / 3.0
                val point = origin.clone().add(cos(angle) * 0.22, (i % 3) * 0.12, sin(angle) * 0.22)
                if (i % 2 == 0) emit(point, Particle.END_ROD)
                else emit(point, Particle.DUST_COLOR_TRANSITION, BLACKHOLE_CORE)
            }
        }
        runLater(8) {
            repeat(3) { i ->
                val angle = i * PI * 2.0 / 3.0 + 0.35
                emit(origin.clone().add(cos(angle) * 0.16, 0.08 + i * 0.08, sin(angle) * 0.16),
                    Particle.DUST_COLOR_TRANSITION, BLACKHOLE_CORE)
            }
        }
    }

    fun frost(originFeet: Location, horizontalEnd: Location, halfAngleDegrees: Double,
        startDelayTicks: Int = 2, travelTicks: Int = 12) = dispatch {
        val origin = originFeet.clone()
        val endpoint = horizontalEnd.clone()
        if (origin.world == null || endpoint.world !== origin.world || !drawable(origin) || !valid(endpoint)) return@dispatch
        val offset = endpoint.toVector().subtract(origin.toVector()).apply { y = 0.0 }
        val range = offset.length().takeIf { it.isFinite() && it in 0.5..96.0 } ?: return@dispatch
        val spread = halfAngleDegrees.takeIf(Double::isFinite)?.coerceIn(5.0, 85.0) ?: return@dispatch
        val basis = frame(offset) ?: return@dispatch
        val reach = range.coerceAtMost(48.0)
        val delay = startDelayTicks.coerceIn(0, 8)
        val travel = travelTicks.coerceIn(1, 24)
        val steps = ((travel + 1) / 2).coerceIn(1, 12)
        repeat(steps + 1) { phase ->
            val atTick = delay + phase * travel / steps
            runLater(atTick.toLong()) {
                if (phase == 0) localSound(origin.clone().apply { y = origin.blockY + 0.08 }, Sound.BLOCK_GLASS_BREAK, 0.76f, 0.86f)
                frostFront(origin, offset.clone().normalize(), basis.first, reach, spread, phase, steps)
            }
        }
    }

    fun lance(from: Location, to: Location) = dispatch {
        val start = from.clone()
        val end = to.clone()
        val distance = segmentLength(start, end) ?: return@dispatch
        val offset = end.toVector().subtract(start.toVector())
        val orientation = staffLanceOrientation(Vector3f(offset.x.toFloat(), offset.y.toFloat(), offset.z.toFloat()))
        fun axis(x: Float, y: Float, z: Float): Vector {
            val rotated = orientation.transform(Vector3f(x, y, z))
            return Vector(rotated.x.toDouble(), rotated.y.toDouble(), rotated.z.toDouble())
        }
        val forward = axis(0f, 0f, 1f)
        val right = axis(-1f, 0f, 0f)
        val up = axis(0f, 1f, 0f)
        localSound(start, Sound.BLOCK_BEACON_ACTIVATE, 0.76f, 1.15f)
        lanceCharge(start, forward, right, up, distance, 0)
        val flightTicks = staffLanceFlightTicks(distance)
        for (age in 1..flightTicks) {
            runLater(age.toLong()) {
                if (age <= 4) lanceCharge(start, forward, right, up, distance, age)
                else lanceStage(start, forward, right, up, distance, age, flightTicks)
            }
        }
        listOf(3L to 8, 6L to 5, 9L to 3).forEach { (delay, count) ->
            runLater((flightTicks + delay).toLong()) {
                lanceAfterglow(start, forward, right, up, distance, count)
            }
        }
    }

    /** Per-tick trail: at most three particles, no smoke or repeated sound. */
    fun emberTrail(from: Location, to: Location, meteor: Boolean = false) = dispatch {
        val start = from.clone()
        val end = to.clone()
        if (start.world == null || end.world !== start.world || !valid(start) || !valid(end)) return@dispatch
        if (start.toVector().distanceSquared(end.toVector()) < 0.0001) {
            // Cast cue only: keep the moving orb's trail off the player's face.
            localSound(end, Sound.ENTITY_BLAZE_SHOOT, 0.72f, 0.95f)
            return@dispatch
        }
        val distance = segmentLength(start, end) ?: return@dispatch
        val direction = end.toVector().subtract(start.toVector()).normalize()
        // Display keyframes lead the logical flight by their interpolation interval.
        // Leave the wake behind the previous logical head, with room for packet sampling.
        val trailHead = start.clone().subtract(direction.clone().multiply(0.75))
        val trailEnd = trailHead.clone().subtract(direction.clone().multiply(distance.coerceAtMost(2.4)))
        val samples = if (distance > 0.55) 2 else 1
        lineSamples(trailEnd, trailHead, samples).forEachIndexed { index, point ->
            if (meteor && index == samples / 2)
                emit(point, Particle.DUST, HOT_WHITE, minimumEyeDistance = COMPACT_CAMERA_CLEARANCE)
            else emit(point, Particle.FLAME, minimumEyeDistance = COMPACT_CAMERA_CLEARANCE)
        }
    }

    fun emberBurst(at: Location, radius: Double, meteor: Boolean = false) = dispatch {
        sceneryBurst(at, radius)
        val origin = at.clone()
        val reach = boundedRadius(radius, 0.5, 10.0) ?: return@dispatch
        val ejecta = Material.TUFF.createBlockData()
        localSound(origin, Sound.ENTITY_GENERIC_EXPLODE, 0.88f, 1.12f)
        // A white-hot core breaks into a broad fire crown and a measured ember tail (206 positions max).
        emit(origin.clone().add(0.0, 0.58, 0.0), Particle.FLASH)
        emit(origin.clone().add(0.0, 0.45, 0.0), Particle.EXPLOSION)
        repeat(10) { i ->
            val angle = i * PI * 2.0 / 10.0
            val point = origin.clone().add(cos(angle) * reach * 0.13, 0.32 + (i % 3) * 0.12,
                sin(angle) * reach * 0.13)
            emit(point, Particle.DUST, HOT_WHITE)
            if (i % 2 == 0) emit(point.clone().add(0.0, 0.16, 0.0), Particle.END_ROD)
        }
        repeat(14) { i ->
            val angle = i * PI * 2.0 / 14.0
            val point = origin.clone().add(cos(angle) * reach * 0.2, 0.08 + (i % 4) * 0.1,
                sin(angle) * reach * 0.2)
            if (meteor) emit(point, Particle.BLOCK, ejecta) else emit(point, Particle.FLAME)
        }
        runLater(2) {
            emit(origin.clone().add(0.0, 0.9, 0.0), Particle.EXPLOSION)
            repeat(28) { i ->
                val angle = i * PI * 2.0 / 28.0
                val ring = reach * (0.28 + (i % 4) * 0.035)
                val point = origin.clone().add(cos(angle) * ring, 0.06 + (i % 5) * 0.1,
                    sin(angle) * ring)
                emit(point, if (meteor) Particle.CLOUD else Particle.FLAME)
            }
            repeat(8) { i ->
                val angle = i * PI / 4.0
                emit(origin.clone().add(cos(angle) * reach * 0.22, 0.28, sin(angle) * reach * 0.22),
                    Particle.DUST_COLOR_TRANSITION, GOLD_TO_WHITE)
            }
        }
        runLater(4) {
            repeat(36) { i ->
                val angle = i * PI * 2.0 / 36.0
                val ring = reach * (0.58 + (i % 3) * 0.12)
                emit(origin.clone().add(cos(angle) * ring, 0.08 + (i % 3) * 0.12,
                    sin(angle) * ring), Particle.DUST_COLOR_TRANSITION, GOLD_CORONA)
            }
            repeat(18) { i ->
                val angle = i * PI * 2.0 / 18.0 + 0.08
                val ring = reach * (0.36 + (i % 3) * 0.12)
                val point = origin.clone().add(cos(angle) * ring, 0.12 + (i % 4) * 0.12,
                    sin(angle) * ring)
                if (meteor) emit(point, Particle.DUST_COLOR_TRANSITION, GOLD_TO_WHITE)
                else emit(point, Particle.FLAME)
            }
        }
        runLater(8) {
            repeat(28) { i ->
                val angle = i * PI * 2.0 / 28.0 + 0.04
                val ring = reach * (0.52 + (i % 5) * 0.095)
                val point = origin.clone().add(cos(angle) * ring, 0.16 + (i % 6) * 0.16,
                    sin(angle) * ring)
                if (meteor) emit(point, Particle.DUST, GOLD_TAIL)
                else emit(point, if (i % 4 == 0) Particle.DUST else Particle.FLAME,
                    if (i % 4 == 0) GOLD_TAIL else null)
            }
            repeat(10) { i ->
                val angle = i * PI / 5.0
                emit(origin.clone().add(cos(angle) * reach * 0.54, 0.62 + (i % 3) * 0.12,
                    sin(angle) * reach * 0.54), Particle.SMOKE)
            }
        }
        runLater(12) {
            repeat(18) { i ->
                val angle = i * PI * 2.0 / 18.0 + 0.15
                val ring = reach * (0.48 + (i % 4) * 0.12)
                val point = origin.clone().add(cos(angle) * ring, 0.34 + (i % 5) * 0.19,
                    sin(angle) * ring)
                if (meteor || i % 3 == 0) emit(point, Particle.DUST, GOLD_TAIL)
                else emit(point, Particle.FLAME)
            }
            repeat(8) { i ->
                val angle = i * PI / 4.0 + 0.2
                emit(origin.clone().add(cos(angle) * reach * 0.36, 0.86 + (i % 2) * 0.14,
                    sin(angle) * reach * 0.36), Particle.SMOKE)
            }
        }
        runLater(16) {
            repeat(12) { i ->
                val angle = i * PI * 2.0 / 12.0
                val ring = reach * (0.54 + (i % 3) * 0.11)
                emit(origin.clone().add(cos(angle) * ring, 0.62 + (i % 4) * 0.18,
                    sin(angle) * ring), Particle.DUST, GOLD_TAIL)
            }
        }
        runLater(20) {
            repeat(8) { i ->
                val angle = i * PI / 4.0 + 0.3
                val ring = reach * (0.58 + (i % 2) * 0.12)
                emit(origin.clone().add(cos(angle) * ring, 0.8 + (i % 3) * 0.16,
                    sin(angle) * ring), Particle.DUST, GOLD_TAIL)
            }
        }
    }

    fun nova(at: Location, radius: Double, secondary: Boolean = false) = dispatch {
        val origin = at.clone()
        val reach = boundedRadius(radius, 2.0, 10.0) ?: return@dispatch
        repeat(10) { phase ->
            val age = 2 + phase * 2
            runLater(age.toLong()) {
                if (phase == 0) localSound(origin, Sound.BLOCK_ROOTED_DIRT_BREAK, 0.74f, 1.12f)
                if (phase == 8) localSound(origin, Sound.BLOCK_AZALEA_LEAVES_BREAK, 0.48f, 0.86f)
                novaFrame(origin, reach, age, secondary)
            }
        }
    }

    private fun markTearStage(from: Location, to: Location, right: Vector, up: Vector, phase: Int) {
        val progress = (phase + 1) / 4.0
        val tip = interpolate(from, to, progress)
        val slash = 0.14 + phase * 0.025
        repeat(5) { i ->
            val side = (i - 2) / 2.0
            emit(tip.clone()
                .add(right.clone().multiply(side * slash))
                .add(up.clone().multiply(-side * slash * 0.7)), Particle.DUST_COLOR_TRANSITION, VIOLET_TO_WHITE,
                minimumEyeDistance = MARK_CAMERA_CLEARANCE)
        }
        if (phase == 0 || phase == 3) {
            emit(tip.clone().add(right.clone().multiply(-slash)), Particle.REVERSE_PORTAL,
                minimumEyeDistance = MARK_CAMERA_CLEARANCE)
            emit(tip.clone().add(right.clone().multiply(slash)), Particle.REVERSE_PORTAL,
                minimumEyeDistance = MARK_CAMERA_CLEARANCE)
            emit(tip, Particle.END_ROD, minimumEyeDistance = MARK_CAMERA_CLEARANCE)
        } else emit(tip, Particle.REVERSE_PORTAL, minimumEyeDistance = MARK_CAMERA_CLEARANCE)
    }

    private fun frostFront(origin: Location, forward: Vector, right: Vector, range: Double,
        halfAngle: Double, phase: Int, steps: Int) {
        val distance = range * phase / steps
        val floorY = origin.blockY + 0.06
        if (phase == 0) {
            val start = origin.clone().apply { y = floorY }
            emit(start, Particle.SNOWFLAKE)
            emit(start.clone().add(0.0, 0.03, 0.0), Particle.DUST_COLOR_TRANSITION, ICE_TO_AQUA)
            return
        }
        val halfAngleRadians = Math.toRadians(halfAngle)
        val ice = Material.PACKED_ICE.createBlockData()
        for (i in 0..4) {
            val angle = (i / 2.0 - 1.0) * halfAngleRadians
            val radial = forward.clone().multiply(cos(angle)).add(right.clone().multiply(sin(angle)))
            val point = origin.clone().add(radial.multiply(distance)).apply { y = floorY }
            emit(point, Particle.SNOWFLAKE)
            if (i == 2) {
                emit(point.clone().add(0.0, 0.035, 0.0), Particle.DUST_COLOR_TRANSITION, ICE_TO_AQUA)
                repeat(2) { side ->
                    val sign = if (side == 0) -1.0 else 1.0
                    emit(point.clone().add(right.clone().multiply(sign * 0.2)).add(0.0, 0.12, 0.0), Particle.END_ROD)
                }
            }
            if (i == 1 || i == 3) emit(point.clone().add(0.0, 0.045, 0.0), Particle.BLOCK, ice)
            if (i == 0 || i == 4) emit(point.clone().add(0.0, 0.1, 0.0), Particle.END_ROD)
        }
        if (phase > 1 && phase < steps && phase % 2 == 0) {
            val frost = origin.clone().add(forward.clone().multiply(distance * 0.72)).apply { y = floorY }
            repeat(2) { i ->
                val side = if (i == 0) -1.0 else 1.0
                emit(frost.clone().add(right.clone().multiply(side * min(distance * 0.12, 0.55))), Particle.SNOWFLAKE)
            }
        }
    }

    private fun lanceStage(start: Location, forward: Vector, right: Vector, up: Vector,
        length: Double, age: Int, flightTicks: Int) {
        val front = staffLanceFront(age, length)
        val launchFront = min(2.0, length.coerceIn(0.1, 48.0))
        val tip = start.clone().add(forward.clone().multiply(front))
            .add(up.clone().multiply(staffLanceCenterOffset(front, length)))
        val tailStart = (front - min(2.6, (front - launchFront).coerceAtLeast(0.0)))
            .coerceAtLeast(launchFront)
        val bodyStart = start.clone().add(forward.clone().multiply(tailStart))
            .add(up.clone().multiply(staffLanceCenterOffset(tailStart, length)))
        lineSamples(bodyStart, tip, 2).forEachIndexed { index, point ->
            emit(point, Particle.DUST_COLOR_TRANSITION, GOLD_TO_WHITE,
                minimumEyeDistance = COMPACT_CAMERA_CLEARANCE)
            if (index == 2) emit(point, Particle.DUST, HOT_WHITE,
                minimumEyeDistance = COMPACT_CAMERA_CLEARANCE)
            if (index != 1) emit(point.clone().add(up.clone().multiply(0.12)), Particle.FLAME,
                minimumEyeDistance = COMPACT_CAMERA_CLEARANCE)
        }
        emit(tip, Particle.END_ROD, minimumEyeDistance = COMPACT_CAMERA_CLEARANCE)
        if (age == flightTicks) {
            localSound(tip, Sound.ENTITY_BLAZE_SHOOT, 0.58f, 1.3f)
            // FLASH uses the default 3.2-block POV guard; each compact corona point is culled individually.
            emit(tip.clone().add(up.clone().multiply(0.10)), Particle.FLASH)
            emit(tip, Particle.END_ROD, minimumEyeDistance = COMPACT_CAMERA_CLEARANCE)
            repeat(12) { index ->
                val angle = index * PI / 6.0
                val ringRadius = 0.42 + (index % 3) * 0.05
                emit(tip.clone()
                    .add(right.clone().multiply(cos(angle) * ringRadius))
                    .add(up.clone().multiply(sin(angle) * ringRadius)),
                    Particle.DUST_COLOR_TRANSITION, GOLD_CORONA,
                    minimumEyeDistance = COMPACT_CAMERA_CLEARANCE)
                if (index % 3 == 0) emit(tip.clone()
                    .add(right.clone().multiply(cos(angle) * (ringRadius + 0.12)))
                    .add(up.clone().multiply(sin(angle) * (ringRadius + 0.12))), Particle.FLAME,
                    minimumEyeDistance = COMPACT_CAMERA_CLEARANCE)
            }
        }
    }

    private fun lanceCharge(start: Location, forward: Vector, right: Vector, up: Vector,
        length: Double, age: Int) {
        val muzzle = min(2.0, length.coerceIn(0.1, 48.0))
        val center = start.clone().add(forward.clone().multiply(muzzle))
            .add(up.clone().multiply(staffLanceCenterOffset(muzzle, length)))
        val phase = age.coerceIn(0, 4)
        val ringRadius = 0.42 - phase * 0.045
        val facets = if (age == 0) 3 else 4
        repeat(facets) { index ->
            val angle = index * PI * 2.0 / facets + phase * PI / 8.0
            emit(center.clone()
                .add(right.clone().multiply(cos(angle) * ringRadius))
                .add(up.clone().multiply(sin(angle) * ringRadius)),
                Particle.DUST_COLOR_TRANSITION, GOLD_TO_WHITE,
                minimumEyeDistance = COMPACT_CAMERA_CLEARANCE)
        }
        if (age >= 3) emit(center, Particle.END_ROD, minimumEyeDistance = COMPACT_CAMERA_CLEARANCE)
        else emit(center, Particle.DUST_COLOR_TRANSITION, GOLD_TO_WHITE,
            minimumEyeDistance = COMPACT_CAMERA_CLEARANCE)
        if (age == 4) localSound(center, Sound.ENTITY_BLAZE_SHOOT, 0.48f, 1.62f)
    }

    private fun lanceAfterglow(start: Location, forward: Vector, right: Vector, up: Vector,
        length: Double, count: Int) {
        val front = staffLanceFront(staffLanceFlightTicks(length), length)
        val tip = start.clone().add(forward.clone().multiply(front))
            .add(up.clone().multiply(staffLanceCenterOffset(front, length)))
        repeat(count) { index ->
            val angle = index * PI * 2.0 / count
            emit(tip.clone()
                .add(right.clone().multiply(cos(angle) * 0.34))
                .add(up.clone().multiply(sin(angle) * 0.27)),
                if (index % 3 == 0) Particle.FLAME else Particle.DUST_COLOR_TRANSITION,
                if (index % 3 == 0) null else GOLD_CORONA,
                minimumEyeDistance = COMPACT_CAMERA_CLEARANCE)
        }
    }

    private fun novaFrame(at: Location, radius: Double, age: Int, secondary: Boolean) {
        val front = 1.8 + staffWaveFront(age, radius - 1.8, 16)
        val travel = ((age - 2) / 16.0).coerceIn(0.0, 1.0)
        val floorY = at.blockY + 0.06
        if (age >= 20) {
            repeat(3) { index ->
                val angle = -0.24 + index * 0.24 + travel * 0.34
                val point = at.clone().add(cos(angle) * front, 0.38 + index * 0.08,
                    sin(angle) * front).apply { y = floorY + 0.38 + index * 0.08 }
                if (index == 1) emit(point, Particle.END_ROD)
                else emit(point, Particle.HAPPY_VILLAGER)
            }
            return
        }
        val arcPoints = when {
            age <= 10 -> 11
            age <= 14 -> 8
            age <= 18 -> 5
            else -> 3
        }
        val waveLift = 0.22 + sin(PI * travel) * 0.48
        repeat(arcPoints) { index ->
            val across = if (arcPoints == 1) 0.5 else index / (arcPoints - 1.0)
            val angle = -PI * 0.70 + across * PI * 1.40 + travel * 0.38
            val crown = sin(across * PI)
            val pointRadius = front - abs(across - 0.5) * 0.18
            val point = at.clone().add(cos(angle) * pointRadius,
                0.0, sin(angle) * pointRadius).apply {
                y = floorY + 0.18 + crown * (0.50 + waveLift)
            }
            emit(point, Particle.DUST_COLOR_TRANSITION, GREEN_TO_WHITE)
            if (index % 2 == 0) {
                emit(point.clone().add(0.0, 0.17, 0.0), Particle.HAPPY_VILLAGER)
                emit(point.clone().add(cos(angle) * -0.14, 0.14, sin(angle) * -0.14),
                    Particle.DUST_COLOR_TRANSITION, GREEN_TO_WHITE)
            }
            if (index % 4 == 0) emit(point.clone().add(0.0, 0.22, 0.0),
                if ((index + age / 2) % 3 == 0) Particle.HAPPY_VILLAGER else Particle.END_ROD)
            if (secondary && index % 3 == 0) emit(point.clone().add(0.0, -0.12, 0.0),
                Particle.DUST_COLOR_TRANSITION, VIOLET_TO_AQUA)
        }
    }

    private fun novaWaveFrame(at: Location, forward: Vector, right: Vector,
        reach: Double, configuredWidth: Double, age: Int) {
        val front = 1.8 + staffWaveFront(age, reach - 1.8, 16)
        val halfWidth = configuredWidth * front / reach
        val floorY = at.blockY + 0.06
        if (age >= 20) {
            val crest = at.clone().add(forward.clone().multiply(reach)).apply { y = floorY + 0.42 }
            emit(crest, Particle.DUST_COLOR_TRANSITION, GREEN_TO_WHITE)
            emit(crest.clone().add(right.clone().multiply(-0.18)), Particle.HAPPY_VILLAGER)
            emit(crest.clone().add(right.clone().multiply(0.18)), Particle.END_ROD)
            return
        }
        val arcPoints = when {
            age <= 10 -> 9
            age <= 14 -> 7
            age <= 18 -> 4
            else -> 2
        }
        val lift = sin(PI * ((age - 2) / 18.0).coerceIn(0.0, 1.0))
        repeat(arcPoints) { index ->
            val across = if (arcPoints == 1) 0.0 else index / (arcPoints - 1.0) * 2.0 - 1.0
            val crown = 1.0 - abs(across)
            val point = at.clone()
                .add(forward.clone().multiply(front + crown * 0.24))
                .add(right.clone().multiply(across * halfWidth))
                .apply { y = floorY + 0.16 + crown * (0.78 + lift * 0.24) }
            emit(point, Particle.DUST_COLOR_TRANSITION, GREEN_TO_WHITE)
            if (index % 2 == 0) emit(point.clone()
                .add(right.clone().multiply(0.08)).add(forward.clone().multiply(-0.12))
                .add(0.0, 0.14, 0.0), Particle.HAPPY_VILLAGER)
            if (index % 3 == 0) emit(point.clone().add(0.0, 0.2, 0.0), Particle.END_ROD)
            if (index % 2 == 1) {
                val spore = (index + age / 2) % 3 == 0
                if (spore) emit(point.clone().add(0.0, 0.12, 0.0), Particle.HAPPY_VILLAGER)
                else emit(point.clone().add(0.0, 0.12, 0.0), Particle.DUST_COLOR_TRANSITION, GREEN_TO_AQUA)
            }
        }
    }

    private fun boltPoints(from: Location, to: Location, distance: Double): List<Location> {
        val direction = to.toVector().subtract(from.toVector())
        val frame = frame(direction) ?: return emptyList()
        val steps = (distance * 0.6).toInt().coerceIn(4, 16)
        return (0..steps).map { i ->
            val t = i / steps.toDouble()
            val base = interpolate(from, to, t)
            if (i == 0 || i == steps) base else {
                val taper = sin(PI * t)
                base.add(frame.first.clone().multiply(sin(i * 2.1) * 0.3 * taper))
                    .add(frame.second.clone().multiply(cos(i * 2.7) * 0.22 * taper))
            }
        }
    }

    private fun lineSamples(from: Location, to: Location, segments: Int): List<Location> =
        (0..segments).map { interpolate(from, to, it / segments.toDouble()) }

    private fun interpolate(from: Location, to: Location, t: Double): Location =
        from.clone().add(to.toVector().subtract(from.toVector()).multiply(t))

    private fun frame(direction: Vector): Pair<Vector, Vector>? {
        if (!direction.x.isFinite() || !direction.y.isFinite() || !direction.z.isFinite() || direction.lengthSquared() < 1.0e-8) return null
        val forward = direction.clone().normalize()
        var right = forward.clone().crossProduct(Vector(0.0, 1.0, 0.0))
        if (right.lengthSquared() < 1.0e-8) right = forward.clone().crossProduct(Vector(1.0, 0.0, 0.0))
        right.normalize()
        return right to right.clone().crossProduct(forward).normalize()
    }

    private fun segmentLength(from: Location, to: Location): Double? {
        val world = from.world ?: return null
        if (to.world !== world || !valid(from) || !valid(to)) return null
        return to.toVector().subtract(from.toVector()).length().takeIf { it.isFinite() && it in 0.01..96.0 }
    }

    private fun boundedRadius(radius: Double, min: Double, max: Double) =
        radius.takeIf { it.isFinite() && it > 0.0 }?.coerceIn(min, max)

    private fun valid(location: Location) = location.x.isFinite() && location.y.isFinite() && location.z.isFinite()

    private fun valid(vector: Vector) = vector.x.isFinite() && vector.y.isFinite() && vector.z.isFinite()

    private fun drawable(location: Location): Boolean {
        val world = location.world ?: return false
        return valid(location) && world.isChunkLoaded(location.blockX shr 4, location.blockZ shr 4)
    }

    private fun handAnchor(player: Player): Location? {
        val eye = player.eyeLocation
        if (!drawable(eye)) return null
        val yaw = Math.toRadians(eye.yaw.toDouble())
        val forward = Vector(-sin(yaw), 0.0, cos(yaw))
        val right = forward.clone().crossProduct(Vector(0.0, 1.0, 0.0))
        return eye.clone().add(forward.multiply(HAND_FORWARD_DISTANCE))
            .add(0.0, -HAND_BELOW_EYE, 0.0).add(right.multiply(HAND_SIDE_DISTANCE))
            .takeIf(::drawable)
    }

    /** One audience lookup per authored frame; Minecraft owns each particle's subsequent motion. */
    fun particles(anchor: Location, samples: List<StaffParticleSample>, clearance: Double = 1.35) = dispatch {
        if (!drawable(anchor)) return@dispatch
        val window = currentTick() / 2
        if (window != particleWindow) {
            particleWindow = window
            framePackets.clear()
            frameParticles.clear()
        }
        val world = anchor.world ?: return@dispatch
        val frame = samples.take(192).mapNotNull { sample ->
            if (!valid(sample.offset) || !valid(sample.spread) || !sample.speed.isFinite()) return@mapNotNull null
            val at = anchor.clone().add(sample.offset)
            if (!drawable(at)) null else sample to at
        }
        world.getNearbyPlayers(anchor, 64.0).forEach { viewer ->
            val eye = viewer.eyeLocation
            if (eye.world !== world) return@forEach
            for ((sample, at) in frame) {
                val distance = eye.distanceSquared(at)
                if (distance < clearance * clearance || distance > 56.0 * 56.0) continue
                val data = sample.data ?: if (sample.particle == Particle.FLASH) Color.WHITE else null
                val spread = sample.spread
                val count = sample.count.coerceIn(0, 96)
                // Overlapping casts stay best-effort: discard excess, never build a delayed queue.
                val id = viewer.uniqueId
                val packets = framePackets.getOrDefault(id, 0)
                val spawned = frameParticles.getOrDefault(id, 0)
                val cost = count.coerceAtLeast(1)
                if (packets >= 480 || spawned + cost > 4096) continue
                framePackets[id] = packets + 1
                frameParticles[id] = spawned + cost
                if (data == null) viewer.spawnParticle(sample.particle, at, count,
                    spread.x, spread.y, spread.z, sample.speed)
                else viewer.spawnParticle(sample.particle, at, count,
                    spread.x, spread.y, spread.z, sample.speed, data)
            }
        }
    }

    fun groundDepression(at: Location, radius: Double) = dispatch {
        activeOwner.get()?.let { sceneryDepression(it, at, radius) }
    }

    fun cue(at: Location, sound: Sound, volume: Float = .65f, pitch: Float = 1.0f) = dispatch {
        localSound(at, sound, volume, pitch)
    }

    private fun emitPrivate(player: Player, at: Location, particle: Particle, data: Any? = null) {
        if (!drawable(at) || player.world !== at.world) return
        val particleData = data ?: if (particle == Particle.FLASH) Color.WHITE else null
        if (particleData == null) player.spawnParticle(particle, at, 1, 0.0, 0.0, 0.0, 0.0)
        else player.spawnParticle(particle, at, 1, 0.0, 0.0, 0.0, 0.0, particleData)
    }

    /** Sends one native-velocity particle per nearby viewer instead of a stationary point. */
    private fun emitNativeVelocity(at: Location, particle: Particle, data: Any?, velocity: Vector,
        minimumEyeDistance: Double) {
        if (!drawable(at) || !valid(velocity)) return
        val world = at.world ?: return
        val minEyeDistanceSquared = minimumEyeDistance * minimumEyeDistance
        val particleData = data ?: if (particle == Particle.FLASH) Color.WHITE else null
        world.getNearbyPlayers(at, QUICK_ELEMENT_VIEW_DISTANCE).forEach { player ->
            val eye = player.eyeLocation
            if (eye.world !== world) return@forEach
            val distanceSquared = eye.distanceSquared(at)
            if (!distanceSquared.isFinite() || distanceSquared < minEyeDistanceSquared ||
                distanceSquared > QUICK_ELEMENT_VIEW_DISTANCE * QUICK_ELEMENT_VIEW_DISTANCE) return@forEach
            if (particleData == null) {
                player.spawnParticle(particle, at, 0, velocity.x, velocity.y, velocity.z, 1.0)
            } else {
                player.spawnParticle(particle, at, 0, velocity.x, velocity.y, velocity.z, 1.0, particleData)
            }
        }
    }

    private fun playPrivate(player: Player, at: Location, sound: Sound, volume: Float, pitch: Float) {
        if (!drawable(at) || player.world !== at.world) return
        val safeVolume = volume.takeIf(Float::isFinite)?.coerceIn(0.0f, 1.0f) ?: return
        val safePitch = pitch.takeIf(Float::isFinite)?.coerceIn(0.5f, 2.0f) ?: return
        player.playSound(at, sound, SoundCategory.PLAYERS, safeVolume, safePitch)
    }

    private fun emit(at: Location, particle: Particle, data: Any? = null,
        minimumEyeDistance: Double = IMPACT_CAMERA_CLEARANCE) {
        if (!drawable(at)) return
        val world = at.world ?: return
        val clearance = minimumEyeDistance.takeIf { it.isFinite() && it >= 0.0 } ?: IMPACT_CAMERA_CLEARANCE
        val minEyeDistanceSquared = clearance * clearance
        val maxViewerDistanceSquared = 48.0 * 48.0
        // Paper 1.21.11 requires Color payload for FLASH; keep this typed default centralized.
        val particleData = data ?: if (particle == Particle.FLASH) Color.WHITE else null
        world.getNearbyPlayers(at, 48.0).forEach { player ->
            val eye = player.eyeLocation
            if (eye.world !== world) return@forEach
            val distanceSquared = eye.distanceSquared(at)
            if (distanceSquared < minEyeDistanceSquared || distanceSquared > maxViewerDistanceSquared) return@forEach
            if (particleData == null) player.spawnParticle(particle, at, 1, 0.0, 0.0, 0.0, 0.0)
            else player.spawnParticle(particle, at, 1, 0.0, 0.0, 0.0, 0.0, particleData)
        }
    }

    /**
     * Send positional cues only to nearby listeners. Each per-listener packet keeps the source
     * bearing while gently compressing far distances into a still-positional 2.5–4 block cue.
     * Recipients are independently range-checked, so no sound is broadcast to the whole world.
     */
    private fun localSound(at: Location, sound: Sound, volume: Float, pitch: Float,
        audibleDistance: Double = DEFAULT_SOUND_DISTANCE) {
        if (!drawable(at)) return
        val world = at.world ?: return
        val radius = audibleDistance.takeIf { it.isFinite() && it > 0.0 }
            ?.coerceIn(MIN_SOUND_DISTANCE, MAX_SOUND_AUDIENCE) ?: DEFAULT_SOUND_DISTANCE
        val safeVolume = volume.takeIf(Float::isFinite)?.coerceIn(0.0f, 1.0f) ?: return
        val safePitch = pitch.takeIf(Float::isFinite)?.coerceIn(0.5f, 2.0f) ?: return
        world.getNearbyPlayers(at, radius + 0.01).forEach { player ->
            val eye = player.eyeLocation
            if (eye.world !== world) return@forEach
            val distanceSquared = eye.distanceSquared(at)
            if (!distanceSquared.isFinite() || distanceSquared > radius * radius) return@forEach
            val distance = sqrt(distanceSquared)
            val compression = ((distance - SOUND_GAIN_NEAR_DISTANCE) /
                (SOUND_GAIN_FAR_DISTANCE - SOUND_GAIN_NEAR_DISTANCE)).coerceIn(0.0, 1.0)
            val virtualDistance = if (distance <= SOUND_NATIVE_NEAR_DISTANCE) distance
                else SOUND_VIRTUAL_NEAR_DISTANCE + compression *
                    (SOUND_VIRTUAL_FAR_DISTANCE - SOUND_VIRTUAL_NEAR_DISTANCE)
            val direction = at.toVector().subtract(eye.toVector())
            val virtualSource = if (direction.lengthSquared() <= 1.0e-8) at.clone()
                else eye.clone().add(direction.normalize().multiply(virtualDistance))
            val listenerGain = SOUND_GAIN_NEAR + compression * (SOUND_GAIN_FAR - SOUND_GAIN_NEAR)
            player.playSound(virtualSource, sound, SoundCategory.PLAYERS,
                (safeVolume.toDouble() * listenerGain).coerceIn(0.0, 1.0).toFloat(), safePitch)
        }
    }

    private fun arcanePalette(spell: StaffSpell): ArcanePalette? = ARCANE_PALETTES[spell]

    private fun dispatch(action: () -> Unit) {
        val owner = activeOwner.get()
        if (Bukkit.isPrimaryThread()) {
            runOwned(owner, action)
        } else if (owner == null) {
            tasks.runSync(action)
        } else {
            val state = ownerTasks.getOrPut(owner) { OwnerTasks() }
            val generation = state.generation
            state.pending++
            tasks.runSync {
                try {
                    if (state.generation == generation) runOwned(owner, action)
                } finally {
                    release(owner, state)
                }
            }
        }
    }

    private data class ArcanePalette(
        val transition: Particle.DustTransition,
        val highlight: Particle.DustOptions,
        val accent: Particle,
        val impactParticle: Particle,
        val chargeSound: Sound,
        val launchSound: Sound,
        val impactSound: Sound,
        val pitch: Float,
    )

    private companion object {
        const val COMPACT_CAMERA_CLEARANCE = 1.15
        const val MARK_CAMERA_CLEARANCE = 1.6
        const val IMPACT_CAMERA_CLEARANCE = 3.2
        const val MIN_SOUND_DISTANCE = 15.0
        const val DEFAULT_SOUND_DISTANCE = 64.0
        const val MAX_SOUND_AUDIENCE = 64.0
        const val SOUND_NATIVE_NEAR_DISTANCE = 2.5
        const val SOUND_VIRTUAL_NEAR_DISTANCE = 2.5
        const val SOUND_VIRTUAL_FAR_DISTANCE = 4.0
        const val SOUND_GAIN_NEAR_DISTANCE = 15.0
        const val SOUND_GAIN_FAR_DISTANCE = 40.0
        const val SOUND_GAIN_NEAR = 1.0
        const val SOUND_GAIN_FAR = 0.78
        const val CHARGE_PARTICLE_COUNT = 4
        const val CHARGE_STAGE_START = 1
        const val CHARGE_STAGE_QUARTER = 1 shl 1
        const val CHARGE_STAGE_HALF = 1 shl 2
        const val CHARGE_STAGE_THREE_QUARTERS = 1 shl 3
        const val CHARGE_STAGE_READY = 1 shl 4
        const val CHARGE_QUARTER = 0.25
        const val CHARGE_HALF = 0.5
        const val CHARGE_THREE_QUARTERS = 0.75
        const val CHARGE_READY = 0.99
        const val CHARGE_RESET_EPSILON = 0.12
        const val CHARGE_STREAM_START_FORWARD = 0.22
        const val CHARGE_PARTICLE_MIN_EYE_DISTANCE_SQUARED = 1.5 * 1.5
        val ICICLE_FEEDBACK_SLOTS = intArrayOf(0, 5, 10)
        const val COOLDOWN_FEEDBACK_TICKS = 8L
        const val COOLDOWN_PARTICLE_COUNT = 4
        const val COOLDOWN_PARTICLE_RADIUS = 0.08
        const val HAND_FORWARD_DISTANCE = 1.35
        const val HAND_BELOW_EYE = 0.45
        const val HAND_SIDE_DISTANCE = 0.24
        const val GALE_STRANDS = 6
        const val GALE_ROPE_LEVELS = 4
        const val GALE_CROWN_PARTICLES = 4
        const val GALE_DEBRIS_PARTICLES = 4
        const val GALE_BASE_Y = -0.8
        const val GALE_FRAME_TICKS = 3
        const val GALE_SOUND_INTERVAL_TICKS = 20
        const val GALE_SOUND_DISTANCE = 56.0
        const val GALE_PHASE_PER_TICK = 0.045
        const val QUICK_ELEMENT_MAX_RADIUS = 3.0
        const val QUICK_ELEMENT_MIN_EYE_DISTANCE = 0.35
        const val QUICK_ELEMENT_VIEW_DISTANCE = 48.0
        const val QUICK_ELEMENT_MIN_FORM = 0.35
        const val QUICK_ELEMENT_FORM_TICKS = 4.0
        const val QUICK_ELEMENT_FADE_TICKS = 4
        const val QUICK_ELEMENT_SWEEP_DISTANCE = 0.72
        const val GALE_QUICK_BANDS = 3
        const val GALE_QUICK_POINTS_PER_BAND = 6
        const val GALE_QUICK_DUST_POINTS = 3
        const val GALE_QUICK_PARTICLE_CAP =
            GALE_QUICK_BANDS * GALE_QUICK_POINTS_PER_BAND + GALE_QUICK_DUST_POINTS
        const val TIDE_QUICK_COLUMNS = 5
        const val TIDE_QUICK_SPLASH_ROWS = 3
        const val TIDE_QUICK_FOAM_POINTS = 4
        const val TIDE_QUICK_PARTICLE_CAP =
            TIDE_QUICK_COLUMNS * (TIDE_QUICK_SPLASH_ROWS + 1) + TIDE_QUICK_FOAM_POINTS
        const val TIDE_QUICK_BASE_Y = -0.55
        val CHAIN_ARC = Particle.DustTransition(Color.fromRGB(80, 220, 255), Color.fromRGB(230, 255, 255), 1.1f)
        val VIOLET = Particle.DustOptions(Color.fromRGB(166, 74, 255), 1.15f)
        val VIOLET_BURST = Particle.DustOptions(Color.fromRGB(207, 112, 255), 1.5f)
        val VIOLET_TO_WHITE = Particle.DustTransition(Color.fromRGB(178, 78, 255), Color.fromRGB(250, 240, 255), 1.45f)
        val VIOLET_TO_AQUA = Particle.DustTransition(Color.fromRGB(205, 90, 255), Color.fromRGB(70, 235, 255), 1.2f)
        val ACCRETION_DUST = Particle.DustTransition(Color.fromRGB(104, 30, 196), Color.fromRGB(238, 132, 255), 2.2f)
        val BLACKHOLE_RIM = Particle.DustTransition(Color.fromRGB(82, 20, 168), Color.fromRGB(248, 178, 255), 2.0f)
        val BLACKHOLE_CORE = Particle.DustTransition(Color.fromRGB(116, 56, 215), Color.fromRGB(255, 250, 255), 1.75f)
        val GOLD = Particle.DustOptions(Color.fromRGB(255, 193, 66), 1.3f)
        val GOLD_TAIL = Particle.DustOptions(Color.fromRGB(255, 222, 145), 0.75f)
        val HOT_WHITE = Particle.DustOptions(Color.fromRGB(255, 250, 226), 1.85f)
        val GOLD_TO_WHITE = Particle.DustTransition(Color.fromRGB(255, 176, 42), Color.fromRGB(255, 255, 240), 1.55f)
        val GOLD_CORONA = Particle.DustTransition(Color.fromRGB(255, 255, 230), Color.fromRGB(255, 179, 44), 1.7f)
        val FAULT_DUST = Particle.DustOptions(Color.fromRGB(89, 74, 103), 1.15f)
        val FAULT_TO_AMETHYST = Particle.DustTransition(Color.fromRGB(80, 82, 86), Color.fromRGB(184, 111, 224), 1.25f)
        val ICE_TO_AQUA = Particle.DustTransition(Color.fromRGB(180, 230, 255), Color.fromRGB(90, 245, 255), 0.9f)
        val GREEN_TO_AQUA = Particle.DustTransition(Color.fromRGB(75, 255, 128), Color.fromRGB(25, 225, 210), 1.15f)
        val GREEN_TO_WHITE = Particle.DustTransition(Color.fromRGB(196, 255, 220), Color.fromRGB(50, 255, 125), 1.6f)
        val ARCANE_LUNAR = ArcanePalette(
            Particle.DustTransition(Color.fromRGB(155, 180, 255), Color.fromRGB(247, 236, 255), 1.35f),
            Particle.DustOptions(Color.fromRGB(218, 227, 255), 1.35f), Particle.END_ROD, Particle.FLASH,
            Sound.BLOCK_AMETHYST_BLOCK_CHIME, Sound.BLOCK_AMETHYST_BLOCK_CHIME,
            Sound.ENTITY_PHANTOM_FLAP, 1.28f)
        val ARCANE_GALE = ArcanePalette(
            Particle.DustTransition(Color.fromRGB(110, 235, 224), Color.fromRGB(226, 255, 255), 1.25f),
            Particle.DustOptions(Color.fromRGB(191, 255, 247), 1.1f), Particle.CLOUD, Particle.CLOUD,
            Sound.ENTITY_BREEZE_WIND_BURST, Sound.ENTITY_BREEZE_SLIDE,
            Sound.ENTITY_BREEZE_WIND_BURST, 1.18f)
        val ARCANE_TIDE = ArcanePalette(
            Particle.DustTransition(Color.fromRGB(39, 166, 209), Color.fromRGB(86, 248, 220), 1.35f),
            Particle.DustOptions(Color.fromRGB(111, 225, 255), 1.2f), Particle.BUBBLE,
            Particle.BUBBLE_POP, Sound.BLOCK_CONDUIT_AMBIENT_SHORT, Sound.ENTITY_PLAYER_SPLASH,
            Sound.ENTITY_PLAYER_SPLASH_HIGH_SPEED, 0.92f)
        val ARCANE_REQUIEM = ArcanePalette(
            Particle.DustTransition(Color.fromRGB(84, 46, 146), Color.fromRGB(210, 113, 186), 1.4f),
            Particle.DustOptions(Color.fromRGB(168, 110, 203), 1.2f), Particle.REVERSE_PORTAL,
            Particle.SOUL, Sound.BLOCK_SCULK_SENSOR_CLICKING, Sound.ENTITY_WITHER_SKELETON_AMBIENT,
            Sound.BLOCK_SCULK_SHRIEKER_SHRIEK, 0.72f)
        val ARCANE_PRISM = ArcanePalette(
            Particle.DustTransition(Color.fromRGB(255, 206, 112), Color.fromRGB(128, 236, 255), 1.35f),
            Particle.DustOptions(Color.fromRGB(255, 244, 191), 1.25f), Particle.END_ROD, Particle.FLASH,
            Sound.BLOCK_AMETHYST_BLOCK_CHIME, Sound.BLOCK_NOTE_BLOCK_CHIME,
            Sound.BLOCK_GLASS_BREAK, 1.44f)
        val ARCANE_NOVA = ArcanePalette(
            Particle.DustTransition(Color.fromRGB(75, 255, 128), Color.fromRGB(25, 225, 210), 1.3f),
            Particle.DustOptions(Color.fromRGB(196, 255, 220), 1.3f), Particle.HAPPY_VILLAGER,
            Particle.END_ROD, Sound.BLOCK_ROOTED_DIRT_HIT, Sound.BLOCK_ROOTED_DIRT_BREAK,
            Sound.BLOCK_AZALEA_LEAVES_BREAK, 1.08f)
        val ARCANE_CHAIN = ArcanePalette(
            Particle.DustTransition(Color.fromRGB(79, 201, 255), Color.fromRGB(241, 254, 255), 1.2f),
            Particle.DustOptions(Color.fromRGB(112, 226, 255), 1.1f), Particle.ELECTRIC_SPARK,
            Particle.FLASH, Sound.BLOCK_RESPAWN_ANCHOR_CHARGE, Sound.ENTITY_LIGHTNING_BOLT_IMPACT,
            Sound.ENTITY_LIGHTNING_BOLT_THUNDER, 1.2f)
        val ARCANE_MARK = ArcanePalette(
            Particle.DustTransition(Color.fromRGB(166, 74, 255), Color.fromRGB(250, 240, 255), 1.35f),
            Particle.DustOptions(Color.fromRGB(207, 112, 255), 1.3f), Particle.REVERSE_PORTAL,
            Particle.FLASH, Sound.BLOCK_PORTAL_TRAVEL, Sound.ENTITY_ENDERMAN_TELEPORT,
            Sound.ENTITY_EVOKER_CAST_SPELL, 0.9f)
        val ARCANE_FROST = ArcanePalette(
            Particle.DustTransition(Color.fromRGB(180, 230, 255), Color.fromRGB(90, 245, 255), 1.15f),
            Particle.DustOptions(Color.fromRGB(220, 248, 255), 1.1f), Particle.SNOWFLAKE,
            Particle.SNOWFLAKE, Sound.BLOCK_GLASS_BREAK, Sound.BLOCK_AMETHYST_CLUSTER_HIT,
            Sound.BLOCK_GLASS_BREAK, 0.9f)
        val ARCANE_LANCE = ArcanePalette(
            Particle.DustTransition(Color.fromRGB(255, 176, 42), Color.fromRGB(255, 255, 240), 1.45f),
            Particle.DustOptions(Color.fromRGB(255, 250, 226), 1.35f), Particle.END_ROD,
            Particle.FLASH, Sound.BLOCK_BEACON_AMBIENT, Sound.BLOCK_BEACON_ACTIVATE,
            Sound.ENTITY_BLAZE_SHOOT, 1.15f)
        val ARCANE_EMBER = ArcanePalette(
            Particle.DustTransition(Color.fromRGB(255, 106, 38), Color.fromRGB(255, 229, 125), 1.4f),
            Particle.DustOptions(Color.fromRGB(255, 193, 66), 1.3f), Particle.FLAME,
            Particle.EXPLOSION, Sound.BLOCK_FIRE_AMBIENT, Sound.ENTITY_BLAZE_SHOOT,
            Sound.ENTITY_GENERIC_EXPLODE, 0.95f)
        val ARCANE_ICICLE = ArcanePalette(
            Particle.DustTransition(Color.fromRGB(145, 222, 255), Color.fromRGB(227, 253, 255), 1.25f),
            Particle.DustOptions(Color.fromRGB(196, 240, 255), 1.2f), Particle.SNOWFLAKE,
            Particle.SNOWFLAKE, Sound.BLOCK_AMETHYST_BLOCK_CHIME, Sound.BLOCK_GLASS_BREAK,
            Sound.BLOCK_AMETHYST_CLUSTER_HIT, 1.08f)
        val ARCANE_FAULT = ArcanePalette(
            Particle.DustTransition(Color.fromRGB(82, 82, 91), Color.fromRGB(184, 111, 224), 1.3f),
            Particle.DustOptions(Color.fromRGB(130, 118, 140), 1.15f), Particle.END_ROD,
            Particle.FLASH, Sound.BLOCK_RESPAWN_ANCHOR_CHARGE, Sound.BLOCK_STONE_BREAK,
            Sound.BLOCK_DEEPSLATE_BREAK, 0.76f)
        val ARCANE_PALETTES = mapOf(
            StaffSpell.CHAIN to ARCANE_CHAIN,
            StaffSpell.MARK to ARCANE_MARK,
            StaffSpell.FROST to ARCANE_FROST,
            StaffSpell.LANCE to ARCANE_LANCE,
            StaffSpell.EMBER to ARCANE_EMBER,
            StaffSpell.NOVA to ARCANE_NOVA,
            StaffSpell.ICICLE to ARCANE_ICICLE,
            StaffSpell.FAULT to ARCANE_FAULT,
            StaffSpell.LUNAR to ARCANE_LUNAR,
            StaffSpell.GALE to ARCANE_GALE,
            StaffSpell.TIDE to ARCANE_TIDE,
            StaffSpell.REQUIEM to ARCANE_REQUIEM,
            StaffSpell.NECROMANCER to ARCANE_REQUIEM,
            StaffSpell.PRISM to ARCANE_PRISM,
            StaffSpell.INFERNO to ARCANE_EMBER,
            StaffSpell.GRAVITY to ARCANE_FAULT,
            StaffSpell.OSSUARY to ARCANE_FAULT.copy(transition = staffFade(0xe4d4b2, 0x867769, 1.1f)),
        )
        val LAYERED_RELEASE_SPELLS = setOf(
            StaffSpell.MARK, StaffSpell.ICICLE, StaffSpell.FAULT,
            StaffSpell.LUNAR, StaffSpell.GALE, StaffSpell.TIDE, StaffSpell.REQUIEM,
            StaffSpell.PRISM, StaffSpell.NOVA,
        )
    }
}
