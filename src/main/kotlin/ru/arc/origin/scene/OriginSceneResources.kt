package ru.arc.origin.scene

import net.citizensnpcs.api.npc.NPC
import net.citizensnpcs.api.trait.trait.Equipment as CitizensEquipment
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.World
import org.bukkit.block.Lidded
import org.bukkit.entity.BlockDisplay
import org.bukkit.entity.AbstractHorse
import org.bukkit.entity.Cat
import org.bukkit.entity.Entity
import org.bukkit.entity.Pose as BukkitPose
import org.bukkit.entity.Sittable
import org.bukkit.inventory.ItemStack
import org.bukkit.util.Transformation
import org.joml.AxisAngle4f
import org.joml.Vector3f

/** A failure retained by [OriginSceneResources] for an explicit cleanup retry. */
internal data class OriginSceneCleanupFailure(
    val resource: String,
    val id: String,
    val failure: Exception,
)

/**
 * Owns the temporary native resources used by one ambient scene cycle.
 *
 * The owner deliberately keeps the Citizens NPC object and the exact hand
 * snapshot rather than depending on an entity being spawned during cleanup.
 * Every resource is registered before its fallible mutation where the API
 * permits it, so a partial operation remains retryable instead of becoming a
 * leaked world side effect.
 */
internal class OriginSceneResources {
    private data class HandSnapshot(
        val id: Int,
        val npc: NPC,
        val original: ItemStack?,
    )

    private data class PoseSnapshot(
        val id: Int,
        val entity: Entity,
        val pose: BukkitPose,
        val fixedPose: Boolean,
        val sitting: Boolean?,
        val catLyingDown: Boolean?,
        val catHeadUp: Boolean?,
        val horseEatingGrass: Boolean?,
    )

    private val hands = linkedMapOf<Int, HandSnapshot>()
    private val poses = linkedMapOf<Int, PoseSnapshot>()
    private val containers = linkedSetOf<Location>()
    private val displays = linkedMapOf<String, BlockDisplay>()

    val displayCount: Int
        get() = displays.size

    /** Equips the scene item while preserving the first observed hand state. */
    fun equip(actor: NPC, material: Material) {
        val actorId = actor.id
        val equipment = actor.getOrAddTrait(CitizensEquipment::class.java)
        if (actorId !in hands) {
            hands[actorId] = HandSnapshot(
                id = actorId,
                npc = actor,
                original = equipment.get(CitizensEquipment.EquipmentSlot.HAND)?.clone(),
            )
        }
        equipment.set(CitizensEquipment.EquipmentSlot.HAND, ItemStack(material))
    }

    /** Captures the original native pose once, then applies a configured pose. */
    fun setPose(actor: NPC, pose: OriginScenePose) {
        val entity = actor.entity
        capturePose(actor.id, entity)
        when (pose) {
            OriginScenePose.STAND -> clearMovementPose(entity)
            OriginScenePose.SIT -> {
                clearMovementPose(entity)
                (entity as? Sittable)?.setSitting(true) ?: entity.setPose(BukkitPose.SITTING, true)
            }
            OriginScenePose.CAT_LIE -> {
                require(entity is Cat) {
                    "origin scene CAT_LIE requires a Cat entity, got ${entity.type}"
                }
                clearMovementPose(entity)
                entity.setLyingDown(true)
            }
            OriginScenePose.HORSE_GRAZE -> {
                require(entity is AbstractHorse) {
                    "origin scene HORSE_GRAZE requires an AbstractHorse entity, got ${entity.type}"
                }
                clearMovementPose(entity)
                entity.setEatingGrass(true)
            }
        }
    }

    /** Clears movement-blocking pose state while retaining the original snapshot. */
    fun clearMovementPose(actor: NPC) {
        val entity = actor.entity
        capturePose(actor.id, entity)
        clearMovementPose(entity)
    }

    /**
     * Changes a block's lid state and owns an opening until it is explicitly
     * closed. A non-container is a contract violation, not a silent no-op.
     */
    fun setContainer(location: Location, open: Boolean) {
        val lidded = requireLidded(location)
        val ownedLocation = location.clone()
        if (open) {
            // Keep ownership before open(): failed opening must be retryable in
            // cleanup, even when the block state is only partially applied.
            containers += ownedLocation
            lidded.open()
        } else {
            lidded.close()
            containers.remove(ownedLocation)
        }
    }

    /**
     * Creates or updates one non-persistent display using the same transform
     * semantics as the scene runtime. The return value reports creation, not
     * whether the display was updated.
     */
    fun updateDisplay(
        key: String,
        world: World,
        resolved: OriginSceneResolvedProp,
        step: OriginSceneStep.BlockDisplay,
        tags: Set<String>,
        rotationYDegrees: Float = step.rotationYDegrees,
    ): Boolean {
        val material = requireNotNull(Material.matchMaterial(step.material)?.takeIf(Material::isBlock)) {
            "origin scene display $key requires a block material: ${step.material}"
        }
        val location = Location(world, resolved.x, resolved.y, resolved.z)
        var created = false
        val display = displays[key]?.takeIf(BlockDisplay::isValid)
            ?: run {
                // An invalid old entity is no longer useful, but the key must
                // be replaced by the newly owned entity if spawning succeeds.
                displays.remove(key)
                val spawned = world.spawn(location, BlockDisplay::class.java) { candidate ->
                    // Register before any consumer mutation. If a tag or
                    // persistence call throws, cleanup still owns this entity.
                    displays[key] = candidate
                    candidate.isPersistent = false
                    tags.forEach(candidate::addScoreboardTag)
                }
                if (displays[key] !== spawned) displays[key] = spawned
                created = true
                spawned
            }

        display.block = material.createBlockData()
        check(display.teleport(location)) {
            "origin scene display $key rejected teleport to $location"
        }
        display.interpolationDelay = -1
        display.interpolationDuration = step.interpolationTicks
        display.teleportDuration = step.interpolationTicks.coerceAtMost(59)
        display.transformation = Transformation(
            Vector3f(resolved.translationX, resolved.translationY, resolved.translationZ),
            AxisAngle4f(Math.toRadians(rotationYDegrees.toDouble()).toFloat(), 0f, 1f, 0f),
            Vector3f(step.scale.x.toFloat(), step.scale.y.toFloat(), step.scale.z.toFloat()),
            AxisAngle4f(),
        )
        return created
    }

    fun hasDisplay(key: String): Boolean = displays[key]?.isValid == true

    /** Removes one owned display; a failed removal remains owned for cleanup. */
    fun removeDisplay(key: String): Boolean {
        val display = displays[key] ?: return false
        if (display.isValid) display.remove()
        displays.remove(key)
        return true
    }

    /**
     * Attempts every currently owned resource. Successful resources are
     * removed from ownership; failures stay registered for a later retry.
     */
    fun cleanup(): List<OriginSceneCleanupFailure> {
        val failures = mutableListOf<OriginSceneCleanupFailure>()

        hands.values.toList().forEach { snapshot ->
            try {
                val equipment = snapshot.npc.getOrAddTrait(CitizensEquipment::class.java)
                equipment.set(
                    CitizensEquipment.EquipmentSlot.HAND,
                    snapshot.original?.clone(),
                )
                hands.remove(snapshot.id)
            } catch (failure: Exception) {
                failures += OriginSceneCleanupFailure("hand", snapshot.id.toString(), failure)
            }
        }

        containers.toList().forEach { location ->
            try {
                requireLidded(location).close()
                containers.remove(location)
            } catch (failure: Exception) {
                failures += OriginSceneCleanupFailure("container", location.toString(), failure)
            }
        }

        poses.values.toList().forEach { snapshot ->
            try {
                restorePose(snapshot)
            } catch (failure: Exception) {
                failures += OriginSceneCleanupFailure("pose", snapshot.id.toString(), failure)
            }
        }

        displays.toMap().forEach { (key, display) ->
            try {
                if (display.isValid) display.remove()
                displays.remove(key)
            } catch (failure: Exception) {
                failures += OriginSceneCleanupFailure("display", key, failure)
            }
        }

        return failures
    }

    /** Restores pose snapshots after a return route has stopped moving actors. */
    fun restorePoses() {
        val failures = mutableListOf<OriginSceneCleanupFailure>()
        poses.values.forEach { snapshot ->
            try {
                restorePose(snapshot)
            } catch (failure: Exception) {
                failures += OriginSceneCleanupFailure("pose", snapshot.id.toString(), failure)
            }
        }
        if (failures.isNotEmpty()) {
            throw IllegalStateException("Origin scene pose restoration failed").apply {
                failures.forEach { addSuppressed(it.failure) }
            }
        }
    }

    private fun requireLidded(location: Location): Lidded =
        requireNotNull(location.block.state as? Lidded) {
            "origin scene resource at $location is not a lidded block"
        }

    private fun capturePose(id: Int, entity: Entity) {
        if (id in poses) return
        val cat = entity as? Cat
        val horse = entity as? AbstractHorse
        poses[id] = PoseSnapshot(
            id = id,
            entity = entity,
            pose = entity.pose,
            fixedPose = entity.hasFixedPose(),
            sitting = (entity as? Sittable)?.isSitting,
            catLyingDown = cat?.isLyingDown,
            catHeadUp = cat?.isHeadUp,
            horseEatingGrass = horse?.isEatingGrass,
        )
    }

    private fun clearMovementPose(entity: Entity) {
        entity.setPose(BukkitPose.STANDING, false)
        (entity as? Sittable)?.setSitting(false)
        (entity as? Cat)?.setLyingDown(false)
        (entity as? AbstractHorse)?.setEatingGrass(false)
    }

    private fun restorePose(snapshot: PoseSnapshot) {
        snapshot.entity.setPose(snapshot.pose, snapshot.fixedPose)
        snapshot.sitting?.let { (snapshot.entity as Sittable).setSitting(it) }
        val cat = snapshot.entity as? Cat
        snapshot.catLyingDown?.let { cat?.setLyingDown(it) }
        snapshot.catHeadUp?.let { cat?.setHeadUp(it) }
        snapshot.horseEatingGrass?.let { (snapshot.entity as AbstractHorse).setEatingGrass(it) }
    }
}
