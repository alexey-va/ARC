package ru.arc.hooks.citizens

import net.citizensnpcs.api.CitizensAPI
import net.citizensnpcs.api.trait.trait.Equipment
import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.entity.EntityType
import org.bukkit.entity.LivingEntity
import org.bukkit.inventory.ItemStack
import ru.arc.ARC
import ru.arc.util.Logging.debug
import ru.arc.util.Logging.warn

class CitizensHook : AutoCloseable {

    data class HologramLine(val text: String, val ticks: Int)

    enum class Animation { ARM_SWING, SIT, STOP_SITTING }

    data class NearbyNpc(
        val id: Int,
        val name: String,
        val location: Location,
    )

    private var closed = false

    @Synchronized
    override fun close() {
        if (closed) return
        closed = true
    }

    fun createNpc(name: String, location: Location): Int {
        return try {
            val npc = CitizensAPI.getNPCRegistry().createNPC(EntityType.PLAYER, name)
            npc.spawn(location)
            debug("[autobuild] Created NPC id={} name={} at {}", npc.id, name, location)
            npc.id
        } catch (e: Exception) {
            warn("Failed to create NPC {} at {}", name, location, e)
            -1
        }
    }

    fun findNearestNpc(
        origin: Location,
        expectedName: String,
        maxDistance: Double,
    ): NearbyNpc? {
        val world = origin.world ?: return null
        return CitizensAPI.getNPCRegistry()
            .asSequence()
            .filter { it.isSpawned && ArcNpcHologramModule.matchesName(it, expectedName) }
            .mapNotNull { npc ->
                val location = npc.entity?.location ?: return@mapNotNull null
                if (location.world?.uid != world.uid) return@mapNotNull null
                val distanceSquared = location.distanceSquared(origin)
                if (distanceSquared > maxDistance * maxDistance) return@mapNotNull null
                Triple(npc, location, distanceSquared)
            }
            .minByOrNull { it.third }
            ?.let { (npc, location) -> NearbyNpc(npc.id, npc.name, location.clone()) }
    }

    fun livingEntity(id: Int): LivingEntity? =
        CitizensAPI.getNPCRegistry().getById(id)?.entity as? LivingEntity

    fun deleteWithNames(npcNames: Set<String>) {
        CitizensAPI.getNPCRegistry().forEach { npc ->
            if (npcNames.contains(npc.name)) npc.destroy()
        }
    }

    fun addChatBubble(id: Int, lineList: List<HologramLine>) {
        try {
            val ttlTicks = lineList.maxOfOrNull(HologramLine::ticks) ?: 1
            ArcNpcHologramModule.showTemporaryBubble(id, lineList.map(HologramLine::text), ttlTicks)
        } catch (e: Exception) {
            warn("Error adding hologram lines", e)
        }
    }

    fun lookClose(id: Int) {
        try {
            ARC.trySeverCommand("npc lookclose --id $id")
        } catch (e: Exception) {
            debug("Error looking close", e)
        }
    }

    fun setSkin(id: Int, link: String) {
        ARC.trySeverCommand("npc skin --url $link --id $id")
    }

    fun deleteNpc(id: Int) {
        CitizensAPI.getNPCRegistry().getById(id)?.destroy()
    }

    fun faceNpc(id: Int, location: Location) {
        CitizensAPI.getNPCRegistry().getById(id)?.faceLocation(location)
    }

    fun animateNpc(id: Int, animation: Animation) {
        try {
            ARC.trySeverCommand("npc panimate ${animation.name} --id $id")
        } catch (e: Exception) {
            debug("Error animating npc", e)
        }
    }

    @Suppress("DEPRECATION")
    fun setMainHand(id: Int, stack: ItemStack) {
        try {
            val npc = CitizensAPI.getNPCRegistry().getById(id) ?: return
            npc.getTrait(Equipment::class.java).set(Equipment.EquipmentSlot.HAND, stack)
        } catch (e: Exception) {
            debug("Error setting main hand", e)
        }
    }
}
