package ru.arc.staffspells

import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.block.data.BlockData
import org.bukkit.entity.Display
import org.bukkit.entity.Player
import org.bukkit.util.Transformation
import org.joml.Quaternionf
import org.joml.Vector3f
import ru.arc.core.LifecycleTaskScope
import ru.arc.paper.display.PacketBlockDisplay
import ru.arc.paper.display.PaperPacketDisplays
import java.util.UUID

/** Short-lived shapes; core owns packet transport, received chunks and connection cleanup. */
internal class StaffSpellDisplayEffects(private val displays: PaperPacketDisplays) : AutoCloseable {
    private data class Defense(val allAround: Boolean, var strength: Double = 1.0,
        var impactAt: Int = -100, var endingAt: Int? = null)

    private data class Scene(
        val id: UUID,
        val casterId: UUID,
        val spell: StaffSpell,
        var origin: Location,
        var rotation: Quaternionf,
        var length: Double,
        var radius: Double,
        var duration: Int,
        var impact: Boolean,
        val secondary: Boolean,
        val input: StaffCastInput,
        var charging: Boolean = false,
        val chargeSlot: Int? = null,
        var projectileBody: List<StaffDisplayPart>? = null,
        val preparation: Boolean = false,
        val melee: Boolean = false,
        var customGeometry: ((Int) -> List<StaffDisplayPart>)? = null,
        val ownerAttachedMotion: Boolean = false,
        val defense: Defense? = null,
        var controllerDriven: Boolean = false,
        var controllerDirty: Boolean = false,
        var chargeProgress: Double = 0.0,
        var age: Int = 0,
        var priorStageTicks: Int = 0,
        var transitionFrom: List<StaffDisplayPart>? = null,
        var renderedParts: List<StaffDisplayPart> = emptyList(),
        var trail: List<Vector3f>? = null,
        var bounds: List<Pair<org.bukkit.util.Vector, Double>> = emptyList(),
        var previousBounds: List<Pair<org.bukkit.util.Vector, Double>> = emptyList(),
        var relativeBounds: List<Pair<org.bukkit.util.Vector, Double>> = emptyList(),
        var previousRelativeBounds: List<Pair<org.bukkit.util.Vector, Double>> = emptyList(),
        val handles: MutableList<PacketBlockDisplay> = mutableListOf(),
        val pendingChargeBirths: MutableSet<PacketBlockDisplay> = mutableSetOf(),
        val viewers: MutableMap<UUID, Player> = mutableMapOf(),
    )

    private val tasks = LifecycleTaskScope()
    private val scenes = linkedMapOf<UUID, Scene>()
    private val blocks = mutableMapOf<Material, BlockData>()
    private var closed = false

    private var visibilityTick = 0
    init { tasks.runTimer(1, 1) {
        if (++visibilityTick % FRAME_TICKS == 0) tick() else if (scenes.isNotEmpty()) refreshAudience()
    } }

    fun play(casterId: UUID, spell: StaffSpell, from: Location, to: Location = from,
        radius: Double = 1.0, durationTicks: Int = 16, impact: Boolean = false, secondary: Boolean = false,
        input: StaffCastInput = StaffCastInput.of(secondary, false), charging: Boolean = false,
        controllerDriven: Boolean = false, charge: Double = 0.0, chargeSlot: Int? = null,
        preparation: Boolean = false): UUID? {
        check(Bukkit.isPrimaryThread())
        if (closed || !valid(from) || !valid(to) || from.world != to.world) return null
        val caster = Bukkit.getPlayer(casterId) ?: return null
        if (!eligible(caster, from)) return null
        reserve(casterId)
        val offset = to.toVector().subtract(from.toVector())
        val length = offset.length()
        val rotation = if (preparation) Quaternionf().rotationY(-Math.toRadians(from.yaw.toDouble()).toFloat())
        else if (charging) staffChargeOrientation(spell, from.yaw,
            Vector3f(from.direction.x.toFloat(), from.direction.y.toFloat(), from.direction.z.toFloat()))
        else if (spell == StaffSpell.MARK)
            Quaternionf().rotationY(kotlin.math.atan2(caster.location.x - from.x, caster.location.z - from.z).toFloat())
        else if (spell == StaffSpell.GALE && !secondary)
            staffYawOnlyOrientation(Vector3f(offset.x.toFloat(), offset.y.toFloat(), offset.z.toFloat()))
        else if (spell == StaffSpell.LANCE)
            staffLanceOrientation(Vector3f(offset.x.toFloat(), offset.y.toFloat(), offset.z.toFloat()))
        else if (length > 0.001)
            Quaternionf().rotationTo(Vector3f(0f, 0f, 1f), Vector3f(offset.x.toFloat(), offset.y.toFloat(), offset.z.toFloat()).normalize())
        else Quaternionf()
        val scene = Scene(UUID.randomUUID(), casterId, spell, from.clone(), rotation,
            length.coerceAtLeast(0.1),
            radius.takeIf(Double::isFinite)?.coerceIn(0.1, if (spell == StaffSpell.FROST) 96.0 else 16.0) ?: 1.0,
            durationTicks.coerceIn(1, 240), impact, secondary, input, charging = charging, chargeSlot = chargeSlot,
            preparation = preparation,
            controllerDriven = controllerDriven,
            chargeProgress = charge.takeIf(Double::isFinite)?.coerceIn(0.0, 1.0) ?: 0.0)
        scenes[scene.id] = scene
        if (spell == StaffSpell.CHAIN) scene.trail = listOf(Vector3f())
        render(scene)
        refreshAudience()
        return scene.id
    }

    private fun reserve(casterId: UUID) {
        // ponytail: bounded oldest-first visual eviction; combat admission never depends on this pool.
        while (scenes.values.count { it.casterId == casterId } >= MAX_PER_CASTER)
            remove(scenes.values.first { it.casterId == casterId }.id)
        while (scenes.size >= MAX_SCENES) remove(scenes.keys.first())
    }

    /** Ability-owned geometry shares the same bounded scenes, interpolation and viewer transport. */
    fun custom(casterId: UUID, spell: StaffSpell, origin: Location, durationTicks: Int,
        parts: (Int) -> List<StaffDisplayPart>): UUID? =
        custom(casterId, spell, origin, durationTicks, ownerAttachedMotion = false, parts = parts)

    /** Only rigs anchored to the moving owner's feet use an owner-relative camera sweep. */
    fun custom(casterId: UUID, spell: StaffSpell, origin: Location, durationTicks: Int, ownerAttachedMotion: Boolean,
        parts: (Int) -> List<StaffDisplayPart>): UUID? {
        check(Bukkit.isPrimaryThread())
        val caster = Bukkit.getPlayer(casterId) ?: return null
        if (closed || !valid(origin) || !eligible(caster, origin)) return null
        reserve(casterId)
        val scene = Scene(UUID.randomUUID(), casterId, spell, origin.clone(), Quaternionf(),
            1.0, 1.0, durationTicks.coerceIn(1, 240), false, false, StaffCastInput.PRIMARY,
            customGeometry = parts, ownerAttachedMotion = ownerAttachedMotion)
        scenes[scene.id] = scene
        render(scene)
        refreshAudience()
        return scene.id
    }

    fun isActive(sceneId: UUID?): Boolean = sceneId != null && sceneId in scenes

    /** Snapshot a controller pose; the common two-tick render clock sends it once. */
    fun updateCustom(sceneId: UUID?, origin: Location, parts: List<StaffDisplayPart>) {
        val scene = scenes[sceneId] ?: return
        if (scene.customGeometry == null || !valid(origin) || origin.world != scene.origin.world) return
        val snapshot = parts.toList()
        scene.origin = origin.clone()
        scene.customGeometry = { snapshot }
    }

    fun defend(player: Player, spell: StaffSpell, durationTicks: Int, allAround: Boolean): UUID? {
        check(Bukkit.isPrimaryThread())
        if (closed || !eligible(player, player.location)) return null
        reserve(player.uniqueId)
        val scene = Scene(UUID.randomUUID(), player.uniqueId, spell, player.location.clone(),
            Quaternionf().rotationY(-Math.toRadians(player.location.yaw.toDouble()).toFloat()),
            1.0, 1.0, durationTicks, false, allAround, StaffCastInput.of(allAround, true),
            defense = Defense(allAround))
        scenes[scene.id] = scene
        render(scene)
        refreshAudience()
        return scene.id
    }

    fun melee(casterId: UUID, spell: StaffSpell, from: Location, to: Location, durationTicks: Int): UUID? {
        check(Bukkit.isPrimaryThread())
        if (closed || !valid(from) || !valid(to) || from.world != to.world) return null
        val caster = Bukkit.getPlayer(casterId) ?: return null
        if (!eligible(caster, from)) return null
        reserve(casterId)
        val delta = to.toVector().subtract(from.toVector())
        val scene = Scene(UUID.randomUUID(), casterId, spell, from.clone(),
            staffYawOnlyOrientation(Vector3f(delta.x.toFloat(), 0f, delta.z.toFloat())),
            delta.length(), 1.0, durationTicks, false, false, StaffCastInput.PRIMARY, melee = true)
        scenes[scene.id] = scene
        render(scene)
        refreshAudience()
        return scene.id
    }

    fun defenseImpact(sceneId: UUID?, strength: Double, depleted: Boolean) {
        val scene = scenes[sceneId] ?: return
        val defense = scene.defense ?: return
        defense.strength = strength.coerceIn(0.0, 1.0)
        defense.impactAt = scene.age
        if (depleted) {
            defense.endingAt = scene.age
            scene.duration = scene.age + 6
        }
        render(scene)
    }

    fun charge(sceneId: UUID?, eye: Location, progress: Double) {
        val scene = scenes[sceneId] ?: return
        scene.origin = eye.clone()
        scene.rotation = staffChargeOrientation(scene.spell, eye.yaw,
            Vector3f(eye.direction.x.toFloat(), eye.direction.y.toFloat(), eye.direction.z.toFloat()))
        scene.chargeProgress = progress.takeIf(Double::isFinite)?.coerceIn(0.0, 1.0) ?: 0.0
        if (scene.controllerDriven) scene.controllerDirty = true
        else if (scene.charging) render(scene)
    }

    /** Actual held position, used by collision before transferring ownership to flight. */
    fun chargeOrigin(sceneId: UUID?): Location? {
        val scene = scenes[sceneId]?.takeIf { it.charging } ?: return null
        val offset = if (scene.spell == StaffSpell.EMBER) staffEmberChargeOffset() else Vector3f()
        scene.rotation.transform(offset)
        return scene.origin.clone().add(offset.x.toDouble(), offset.y.toDouble(), offset.z.toDouble())
    }

    /** Release the existing physical body. No respawn, assembly reset or replacement model. */
    fun releaseCharge(sceneId: UUID?, durationTicks: Int, controllerDriven: Boolean = false): UUID? {
        val scene = scenes[sceneId]?.takeIf { it.charging } ?: return null
        val origin = chargeOrigin(sceneId) ?: return null
        val offset = if (scene.spell == StaffSpell.EMBER) staffEmberChargeOffset() else Vector3f()
        scene.projectileBody = scene.renderedParts.map { it.copy(center = Vector3f(it.center).sub(offset)) }
        scene.origin = origin
        scene.charging = false
        scene.pendingChargeBirths.clear()
        scene.age = 0
        scene.duration = durationTicks.coerceIn(1, 240)
        scene.controllerDriven = controllerDriven
        scene.controllerDirty = false
        render(scene)
        return scene.id
    }

    /** Applies the controller's animation clock without letting the display timer advance it again. */
    fun synchronize(sceneId: UUID?, ageTicks: Int) {
        val scene = scenes[sceneId] ?: return
        if (!scene.controllerDriven) return
        val age = ageTicks.coerceAtLeast(0)
        if (scene.age == age && !scene.controllerDirty) return
        scene.age = age
        scene.controllerDirty = false
        render(scene)
    }

    fun move(sceneId: UUID?, at: Location) {
        val scene = scenes[sceneId] ?: return
        if (!valid(at) || at.world != scene.origin.world) { remove(sceneId); return }
        scene.origin = at.clone()
        if (scene.controllerDriven) scene.controllerDirty = true
        if (scene.spell == StaffSpell.EMBER && !scene.impact && !scene.controllerDriven) render(scene)
    }

    fun moveProjectile(sceneId: UUID?, at: Location, direction: org.bukkit.util.Vector) {
        val scene = scenes[sceneId] ?: return
        move(sceneId, at)
        val vector = Vector3f(direction.x.toFloat(), direction.y.toFloat(), direction.z.toFloat())
        scene.rotation = when (scene.spell) {
            StaffSpell.GALE -> staffYawOnlyOrientation(vector)
            // Carry the held camera frame into flight, including an exactly vertical aim.
            StaffSpell.ICICLE -> staffIcicleFlightOrientation(scene.rotation, vector)
            else -> staffLanceOrientation(vector)
        }
        if (scene.controllerDriven) scene.controllerDirty = true
    }

    fun moveTrail(sceneId: UUID?, points: List<Location>) {
        val scene = scenes[sceneId] ?: return
        if (points.isEmpty() || points.any { !valid(it) || it.world != scene.origin.world }) return
        scene.origin = points.first().clone()
        scene.rotation = Quaternionf()
        scene.trail = points.take(41).map { at ->
            val delta = at.toVector().subtract(scene.origin.toVector())
            Vector3f(delta.x.toFloat(), delta.y.toFloat(), delta.z.toFloat())
        }
        if (scene.controllerDriven) scene.controllerDirty = true
    }

    fun finishTrail(sceneId: UUID?) {
        val scene = scenes[sceneId] ?: return
        scene.controllerDriven = false
        scene.controllerDirty = false
        scene.impact = true
        scene.priorStageTicks += scene.age
        scene.age = 0
        scene.duration = 8
    }

    /** Charge/flight becomes its own release; retain the physical pieces and packet IDs. */
    fun impact(sceneId: UUID?, at: Location, radius: Double, durationTicks: Int) {
        val scene = scenes[sceneId] ?: return
        if (!valid(at) || at.world != scene.origin.world) { remove(sceneId); return }
        scene.transitionFrom = if (scene.spell == StaffSpell.EMBER) {
            // Flight frames are keyed at the projectile position; keep the retained core
            // centered on the collision point. Rebase only a repeated impact-stage transition.
            val delta = if (scene.impact) scene.origin.toVector().subtract(at.toVector()) else org.bukkit.util.Vector()
            val priorRotation = Quaternionf(scene.rotation)
            scene.rotation = Quaternionf() // Meteor and horizontal comet impacts both expand over the world ground plane.
            scene.renderedParts.map { part -> part.copy(
                center = priorRotation.transform(Vector3f(part.center)).add(delta.x.toFloat(), delta.y.toFloat(), delta.z.toFloat()),
                rotation = Quaternionf(priorRotation).mul(part.rotation),
            ) }
        } else scene.renderedParts
        scene.origin = at.clone()
        scene.radius = radius.takeIf(Double::isFinite)?.coerceIn(0.1, 16.0) ?: 1.0
        scene.duration = durationTicks.coerceIn(8, 160)
        scene.impact = true
        scene.controllerDriven = false
        scene.controllerDirty = false
        scene.priorStageTicks += scene.age
        scene.age = 0
        render(scene)
    }

    fun remove(sceneId: UUID?) {
        scenes.remove(sceneId)?.handles?.forEach { it.remove() }
    }

    fun cancel(casterId: UUID) {
        scenes.values.filter { it.casterId == casterId }.map { it.id }.forEach(::remove)
    }

    private fun tick() {
        scenes.values.toList().forEach { scene ->
            if (scenes[scene.id] !== scene) return@forEach
            val caster = Bukkit.getPlayer(scene.casterId)
            if (caster == null || !eligible(caster, scene.origin)) {
                remove(scene.id)
            } else if (!scene.controllerDriven) {
                scene.age += FRAME_TICKS
                if (scene.defense != null) {
                    scene.origin = caster.location.clone()
                    scene.rotation = Quaternionf().rotationY(-Math.toRadians(caster.location.yaw.toDouble()).toFloat())
                }
                val tracked = scene.customGeometry == null && scene.defense == null && !scene.preparation && !scene.melee && !scene.charging && !scene.impact && scene.spell in setOf(StaffSpell.MARK, StaffSpell.EMBER)
                val expired = if (tracked) scene.age >= 160 else scene.age >= scene.duration
                if (expired) remove(scene.id)
                // Flying comets have one movement owner sending two-tick forward keyframes.
                // Charge geometry is progress-only; the shared timer must not restart its tween.
                else if (scene.customGeometry != null || scene.defense != null || scene.preparation ||
                    (!scene.charging && (scene.spell != StaffSpell.EMBER || scene.impact))) render(scene)
            }
        }
        if (scenes.isNotEmpty()) refreshAudience()
    }

    private fun render(scene: Scene) {
        val previousParts = scene.renderedParts
        val previousBounds = scene.bounds
        var parts = (scene.customGeometry?.invoke(scene.age) ?: if (scene.melee) staffMeleeDisplayParts(scene.spell, scene.age, scene.duration, scene.length)
        else if (scene.preparation) staffPreparationParts(scene.spell, scene.secondary, scene.age, scene.duration)
        else if (scene.defense != null) staffDefenseParts(scene.spell, scene.age, scene.duration,
            scene.defense.allAround, scene.defense.strength, scene.age - scene.defense.impactAt,
            scene.defense.endingAt?.let { scene.age - it }) else if (scene.charging) {
            if (scene.spell == StaffSpell.ICICLE && scene.chargeSlot != null)
                staffIcicleChargeParts(scene.chargeProgress, scene.chargeSlot)
            else staffChargeParts(scene.spell, scene.chargeProgress, scene.age)
        } else scene.projectileBody?.takeUnless { scene.impact } ?: scene.trail?.let { path ->
            staffChargeResponsiveOldParts(scene.spell, staffLightningTrailParts(path,
                if (scene.impact) (1.0 - scene.age / 8.0).coerceAtLeast(0.0) else 1.0), scene.chargeProgress)
        }
            ?: staffDisplayParts(scene.spell, scene.age, scene.duration, scene.length, scene.radius, scene.impact,
                scene.secondary, animationAgeTicks = scene.priorStageTicks + scene.age, input = scene.input,
                charge = scene.chargeProgress)).take(MAX_PARTS)
        scene.transitionFrom?.let { previous ->
            parts = blendStaffParts(parts, previous, scene.age)
            if (scene.age >= (if (scene.spell == StaffSpell.EMBER) 4 else 2)) scene.transitionFrom = null
        }
        while (scenes.values.filter { it.id != scene.id && it.casterId == scene.casterId }.sumOf { it.handles.size } + parts.size > MAX_PARTS_PER_CASTER)
            remove(scenes.values.first { it.id != scene.id && it.casterId == scene.casterId }.id)
        while (scenes.values.filter { it.id != scene.id }.sumOf { it.handles.size } + parts.size > MAX_TOTAL_PARTS)
            remove(scenes.values.first { it.id != scene.id }.id)
        val previousIndexByPart = mutableListOf<Int?>()
        val matchedPrevious = mutableSetOf<Int>()
        parts.forEachIndexed { index, part ->
            val previousIndex = if (part.identity == null) {
                index.takeIf { it !in matchedPrevious && previousParts.getOrNull(it)?.identity == null }
            } else {
                previousParts.indices.firstOrNull {
                    it !in matchedPrevious && previousParts[it].identity == part.identity
                }
            }
            if (previousIndex != null) matchedPrevious += previousIndex
            previousIndexByPart += previousIndex
        }
        val casterEye = Bukkit.getPlayer(scene.casterId)?.eyeLocation
        // The broad ice fan needs per-piece clipping; projectile owners already sweep their paths.
        if ((scene.melee || scene.defense == null && !scene.preparation && scene.spell == StaffSpell.FROST && !scene.charging) && casterEye != null) parts = parts.map { part ->
            val point = scene.rotation.transform(Vector3f(part.center))
            val at = scene.origin.clone().add(point.x.toDouble(), point.y.toDouble(), point.z.toDouble())
            part.copy(visible = part.visible && visible(casterEye, at))
        }
        val currentBounds = parts.map { part ->
            val point = scene.rotation.transform(Vector3f(part.center))
            scene.origin.toVector().add(org.bukkit.util.Vector(point.x.toDouble(), point.y.toDouble(), point.z.toDouble())) to
                part.scale.length() * 0.5
        }
        scene.previousBounds = previousIndexByPart.mapIndexed { index, previousIndex ->
            previousIndex?.let { previousBounds.getOrNull(it) } ?: currentBounds[index]
        }
        if (scene.ownerAttachedMotion) {
            val relative = currentBounds.map { (at, radius) -> at.clone().subtract(scene.origin.toVector()) to radius }
            scene.previousRelativeBounds = previousIndexByPart.mapIndexed { index, previousIndex ->
                previousIndex?.let { scene.relativeBounds.getOrNull(it) } ?: relative[index]
            }
            scene.relativeBounds = relative
        }
        scene.bounds = currentBounds
        scene.renderedParts = parts
        val previousHandles = scene.handles.toList()
        val reusedHandles = previousIndexByPart.map { previousIndex ->
            previousIndex?.let { previousHandles.getOrNull(it) }?.takeIf(PacketBlockDisplay::isValid)
        }
        val retainedHandles = reusedHandles.filterNotNull().toSet()
        previousHandles.filterNot(retainedHandles::contains).forEach(PacketBlockDisplay::remove)
        scene.pendingChargeBirths.retainAll(retainedHandles)
        scene.handles.clear()
        val interpolationTicks = when {
            scene.customGeometry != null -> FRAME_TICKS
            scene.defense != null || scene.preparation -> FRAME_TICKS
            scene.spell == StaffSpell.CHAIN -> 0
            scene.charging -> CHARGE_INTERPOLATION_TICKS
            else -> FRAME_TICKS
        }
        val teleportTicks = when {
            scene.customGeometry != null -> FRAME_TICKS
            scene.preparation -> FRAME_TICKS
            scene.spell == StaffSpell.CHAIN -> 0
            scene.charging -> CHARGE_INTERPOLATION_TICKS
            else -> FRAME_TICKS
        }
        val newbornCharges = mutableListOf<PacketBlockDisplay>()
        parts.forEachIndexed { index, part ->
            val center = scene.rotation.transform(Vector3f(part.center))
            val at = scene.origin.clone().add(center.x.toDouble(), center.y.toDouble(), center.z.toDouble()).apply {
                yaw = 0f; pitch = 0f
            }
            val handle = reusedHandles[index] ?: displays.spawnBlock(at,
                blocks.getOrPut(part.material) { part.material.createBlockData() }).also {
                it.isVisibleByDefault = false
                it.brightness = Display.Brightness(15, 15)
                it.viewRange = VIEW_RANGE.toFloat() / 64f
                it.shadowStrength = 0f
            }
            scene.handles += handle
            if (!handle.isValid) return@forEachIndexed
            if (reusedHandles[index] == null && scene.charging && scene.spell == StaffSpell.ICICLE) {
                scene.pendingChargeBirths += handle
                newbornCharges += handle
            }
            if (handle.blockData.material != part.material)
                handle.blockData = blocks.getOrPut(part.material) { part.material.createBlockData() }
            handle.interpolationDuration = interpolationTicks
            if (scene.defense != null) {
                val caster = Bukkit.getPlayer(scene.casterId) ?: return@forEachIndexed
                // Native passenger movement follows client prediction; only the local pose changes.
                handle.attachTo(caster)
                val rotation = Quaternionf(scene.rotation).mul(part.rotation)
                val translation = rotation.transform(Vector3f(part.scale).mul(-0.5f)).add(center)
                    .add(0f, -caster.height.toFloat(), 0f)
                handle.transformation = Transformation(translation, rotation, Vector3f(part.scale), Quaternionf())
                return@forEachIndexed
            }
            handle.teleportDuration = teleportTicks
            handle.teleport(at)
            val rotation = Quaternionf(scene.rotation).mul(part.rotation)
            // Block models occupy [0,1]^3. Center them at their real packet anchor for chunk visibility.
            handle.transformation = if (handle in scene.pendingChargeBirths)
                Transformation(Vector3f(), rotation, Vector3f(), Quaternionf())
            else Transformation(rotation.transform(Vector3f(part.scale).mul(-0.5f)),
                rotation, Vector3f(part.scale), Quaternionf())
        }
        if (newbornCharges.isNotEmpty()) {
            // A new native display needs a zero pose in its spawn metadata before its first growth tween.
            refreshAudience()
            tasks.runLater(1) {
                if (closed || scenes[scene.id] !== scene || !scene.charging) return@runLater
                newbornCharges.forEach { handle ->
                    if (!scene.pendingChargeBirths.remove(handle) || !handle.isValid) return@forEach
                    val part = scene.renderedParts.getOrNull(scene.handles.indexOf(handle)) ?: return@forEach
                    val rotation = Quaternionf(scene.rotation).mul(part.rotation)
                    handle.transformation = Transformation(rotation.transform(Vector3f(part.scale).mul(-0.5f)),
                        rotation, Vector3f(part.scale), Quaternionf())
                }
            }
        }
    }

    private fun refreshAudience() {
        val online = Bukkit.getOnlinePlayers().associateBy { it.uniqueId }
        val selected = mutableMapOf<UUID, MutableSet<UUID>>()
        online.values.forEach { player ->
            val eye = player.eyeLocation
            var remainingParts = MAX_PARTS_PER_VIEWER
            scenes.values.asSequence().filter { it.origin.world == eye.world }
                .map { scene -> scene to (scene.bounds.minOfOrNull { (at, radius) ->
                    (at.distance(eye.toVector()) - radius).coerceAtLeast(0.0)
                } ?: scene.origin.distance(eye)) }.filter { it.second <= VIEW_RANGE }
                .sortedWith(compareBy<Pair<Scene, Double>> { it.first.casterId != player.uniqueId }.thenBy { it.second })
                .take(MAX_PER_VIEWER).forEach { (scene, _) ->
                    if (scene.handles.size <= remainingParts) {
                        remainingParts -= scene.handles.size
                        selected.getOrPut(scene.id, ::mutableSetOf).add(player.uniqueId)
                    }
                }
        }
        scenes.values.forEach { scene ->
            val audience = selected[scene.id].orEmpty()
            // Clear explicit visibility even for an offline viewer; core handles its connection teardown.
            (scene.viewers.keys - audience).forEach { id -> scene.viewers[id]?.let { player -> scene.handles.forEach { it.hideFrom(player) } } }
            audience.forEach { id -> online[id]?.let { player ->
                scene.handles.forEachIndexed { index, handle ->
                    val ownerRelative = scene.ownerAttachedMotion && player.uniqueId == scene.casterId
                    val bound = (if (ownerRelative) scene.relativeBounds else scene.bounds).getOrNull(index)
                    val previous = if (ownerRelative) scene.previousRelativeBounds.getOrNull(index) ?: bound
                        else if (scene.defense != null || scene.spell == StaffSpell.CHAIN) bound
                        else scene.previousBounds.getOrNull(index) ?: bound
                    val eye = player.eyeLocation.toVector().apply {
                        if (ownerRelative) subtract(player.location.toVector())
                    }
                    val padding = if (ownerRelative) .35
                        else if (scene.defense != null) .25
                        else staffEyeClearance(scene.spell, scene.impact) + player.velocity.length() * FRAME_TICKS + 0.15
                    if (scene.renderedParts.getOrNull(index)?.visible == true && bound != null && previous != null &&
                        staffSweptDistance(eye, previous.first, bound.first) >= padding + maxOf(bound.second, previous.second))
                        handle.showTo(player)
                    else handle.hideFrom(player)
                }
            } }
            scene.viewers.clear()
            audience.forEach { id -> online[id]?.let { scene.viewers[id] = it } }
        }
    }

    override fun close() {
        if (closed) return
        closed = true
        tasks.close()
        scenes.keys.toList().forEach(::remove)
        blocks.clear()
        displays.close()
    }

    private fun eligible(player: Player, origin: Location) = player.isOnline && player.isValid && !player.isDead && player.world == origin.world
    private fun valid(at: Location) = at.world != null && at.x.isFinite() && at.y.isFinite() && at.z.isFinite()

    internal companion object {
        const val FRAME_TICKS = 2
        const val CHARGE_INTERPOLATION_TICKS = 4
        const val MAX_SCENES = 32
        const val MAX_PER_CASTER = 12
        const val MAX_PER_VIEWER = 16
        const val MAX_PARTS = 48
        const val MAX_PARTS_PER_CASTER = 192
        const val MAX_PARTS_PER_VIEWER = 192
        const val MAX_TOTAL_PARTS = 576
        const val VIEW_RANGE = 64.0
    }
}

/** Compact flight remains close to the reticle; large explosions retain a wider exclusion. */
internal fun staffEyeClearance(spell: StaffSpell, impact: Boolean): Double =
    when {
        impact && spell in setOf(StaffSpell.MARK, StaffSpell.EMBER) -> 3.2
        spell == StaffSpell.NOVA -> 0.55
        else -> 1.15
    }

/** Charged bodies follow the aim; the crown also preserves the camera's horizontal axis. */
internal fun staffChargeOrientation(spell: StaffSpell, yaw: Float, direction: Vector3f): Quaternionf =
    when (spell) {
        StaffSpell.EMBER -> staffLanceOrientation(Vector3f(direction))
        StaffSpell.ICICLE -> Quaternionf().rotationY(-Math.toRadians(yaw.toDouble()).toFloat())
            .rotateX(kotlin.math.atan2(-direction.y, kotlin.math.hypot(direction.x, direction.z)))
        else -> Quaternionf().rotationY(-Math.toRadians(yaw.toDouble()).toFloat())
    }

internal fun staffIcicleFlightOrientation(previous: Quaternionf, direction: Vector3f): Quaternionf =
    Quaternionf().rotationTo(previous.transform(Vector3f(0f, 0f, 1f)), direction).mul(previous)

/** Conservative sphere includes the entire transformed cuboid, not only its anchor. */
internal fun staffPartClearOfEye(part: StaffDisplayPart, origin: Location, rotation: Quaternionf, eye: Location,
    clearance: Double = 3.2): Boolean {
    if (origin.world != eye.world) return false
    val center = rotation.transform(Vector3f(part.center))
    val distance = eye.toVector().distance(origin.toVector().add(org.bukkit.util.Vector(
        center.x.toDouble(), center.y.toDouble(), center.z.toDouble())))
    return distance >= clearance + part.scale.length() * 0.5
}

internal fun staffSweptDistance(eye: org.bukkit.util.Vector, from: org.bukkit.util.Vector, to: org.bukkit.util.Vector): Double {
    val delta = to.clone().subtract(from)
    val t = if (delta.lengthSquared() < 0.000001) 0.0 else
        eye.clone().subtract(from).dot(delta).div(delta.lengthSquared()).coerceIn(0.0, 1.0)
    return eye.distance(from.clone().add(delta.multiply(t)))
}

/** Gale visuals pitch only around the world vertical axis, even for an elevated target. */
internal fun staffYawOnlyOrientation(direction: Vector3f): Quaternionf {
    val x = direction.x.toDouble()
    val z = direction.z.toDouble()
    if (!x.isFinite() || !z.isFinite()) return Quaternionf()
    return Quaternionf().rotationY(kotlin.math.atan2(x, z).toFloat())
}
