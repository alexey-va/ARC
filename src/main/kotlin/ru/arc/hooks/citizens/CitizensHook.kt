package ru.arc.hooks.citizens

import com.google.common.cache.CacheBuilder
import net.citizensnpcs.api.CitizensAPI
import net.citizensnpcs.api.trait.trait.Equipment
import net.citizensnpcs.trait.HologramTrait
import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.entity.EntityType
import org.bukkit.event.HandlerList
import org.bukkit.inventory.ItemStack
import ru.arc.ARC
import ru.arc.util.Logging.debug
import ru.arc.util.Logging.warn
import java.util.concurrent.ConcurrentLinkedDeque
import java.util.concurrent.TimeUnit

class CitizensHook : AutoCloseable {

    data class HologramLine(val text: String, val ticks: Int)
    data class InsertedHologramLine(val line: Int, val expireTime: Long)

    enum class Animation { ARM_SWING, SIT, STOP_SITTING }

    private val linesCache = CacheBuilder.newBuilder()
        .expireAfterAccess(10, TimeUnit.MINUTES)
        .build<Int, ConcurrentLinkedDeque<InsertedHologramLine>>()

    private var listener: CitizensListener? = null
    private var closed = false

    @Synchronized
    fun registerListeners() {
        check(!closed) { "CitizensHook is closed" }
        if (listener != null) return
        val newListener = CitizensListener()
        try {
            Bukkit.getPluginManager().registerEvents(newListener, ARC.instance)
            listener = newListener
        } catch (failure: Throwable) {
            HandlerList.unregisterAll(newListener)
            throw failure
        }
    }

    @Synchronized
    override fun close() {
        if (closed) return
        closed = true
        listener?.let(HandlerList::unregisterAll)
        listener = null
        linesCache.invalidateAll()
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

    fun deleteWithNames(npcNames: Set<String>) {
        CitizensAPI.getNPCRegistry().forEach { npc ->
            if (npcNames.contains(npc.name)) npc.destroy()
        }
    }

    fun addChatBubble(id: Int, lineList: List<HologramLine>) {
        try {
            val npc = CitizensAPI.getNPCRegistry().getById(id)
            if (npc == null) {
                warn("NPC {} is null", id)
                return
            }
            val trait = npc.getOrAddTrait(HologramTrait::class.java)
            val lineCache = linesCache.get(id) { ConcurrentLinkedDeque() }
            for (line in lineCache.reversed()) {
                trait.removeLine(line.line)
            }
            lineCache.clear()

            var nextLine = trait.lines.size
            lineList.reversed().forEach { line ->
                trait.addTemporaryLine(line.text, line.ticks)
                lineCache.add(InsertedHologramLine(nextLine++, System.currentTimeMillis() + line.ticks * 50L))
            }
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
