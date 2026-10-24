package ru.arc.staffspells

import org.bukkit.Location
import org.bukkit.entity.Display
import org.bukkit.entity.ItemDisplay
import org.bukkit.entity.Mob
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import org.bukkit.util.Transformation
import org.joml.Quaternionf
import org.joml.Vector3f
import java.util.UUID
import kotlin.math.cos
import kotlin.math.sin

/** A client-only staff beside the left shoulder; native hands and loot remain owned by EliteMobs. */
internal class StaffMobEquipmentVisuals(mob: Mob, item: ItemStack, effects: StaffSpellDisplayEffects) : AutoCloseable {
    private var previousAnchor = anchor(mob)
    private val handle = effects.createMobStaff(item, previousAnchor)
    private val viewers = mutableMapOf<UUID, Player>()
    val isValid get() = handle.isValid

    init {
        try {
            handle.isVisibleByDefault = false
            handle.itemDisplayTransform = ItemDisplay.ItemDisplayTransform.NONE
            handle.brightness = Display.Brightness(12, 12)
            handle.teleportDuration = 2
            handle.viewRange = .65f
            handle.transformation = Transformation(Vector3f(), Quaternionf().rotationZ(-.22f), Vector3f(1.1f), Quaternionf())
        } catch (failure: RuntimeException) {
            handle.remove()
            throw failure
        } catch (failure: LinkageError) {
            handle.remove()
            throw failure
        }
    }

    fun update(mob: Mob, target: Player?) {
        val at = anchor(mob)
        handle.teleport(at)
        val audience = mob.world.getNearbyPlayers(mob.location, 32.0).asSequence()
            .filter { it.isOnline && it.isValid && !it.isDead }
            .filter { staffSweptDistance(it.eyeLocation.toVector(), previousAnchor.toVector(), at.toVector()) >=
                2.4 + it.velocity.length() * StaffSpellDisplayEffects.FRAME_TICKS }
            .sortedWith(compareBy<Player> { it.uniqueId != target?.uniqueId }
                .thenBy { it.location.distanceSquared(mob.location) })
            .take(24).associateBy { it.uniqueId }
        (viewers.keys - audience.keys).forEach { id -> viewers.remove(id)?.let(handle::hideFrom) }
        audience.forEach { (id, player) ->
            if (viewers.put(id, player) !== player) handle.showTo(player)
        }
        previousAnchor = at
    }

    override fun close() {
        viewers.clear()
        handle.remove()
    }

    private companion object {
        fun anchor(mob: Mob): Location {
            val at = mob.location
            val yaw = Math.toRadians(at.yaw.toDouble())
            val side = ((mob.boundingBox.widthX + mob.boundingBox.widthZ) * .25 + .32).coerceIn(.55, 2.0)
            return at.add(cos(yaw) * side, (mob.height * .62).coerceIn(.45, 3.0), sin(yaw) * side).also { it.pitch = 0f }
        }
    }
}
