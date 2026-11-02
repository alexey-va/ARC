package ru.arc.graves

import org.bukkit.NamespacedKey
import org.bukkit.entity.ArmorStand
import org.bukkit.entity.Entity
import org.bukkit.entity.TextDisplay
import org.bukkit.event.EventHandler
import org.bukkit.event.HandlerList
import org.bukkit.event.Listener
import org.bukkit.event.world.EntitiesLoadEvent
import org.bukkit.persistence.PersistentDataType
import org.bukkit.plugin.Plugin
import java.util.UUID

/** Retired GravesX visuals only; never loads chunks or selects entities by visible text. */
internal class LegacyGravesHolograms(private val plugin: Plugin) : Listener, AutoCloseable {
    fun start() {
        plugin.server.pluginManager.registerEvents(this, plugin)
        if (plugin.server.pluginManager.isPluginEnabled("GravesX")) return
        // One explicit startup/reload maintenance sweep. Future chunks use the load event.
        plugin.server.worlds.forEach { world -> world.entities.forEach(::removeRetired) }
    }
    @EventHandler fun loaded(event: EntitiesLoadEvent) { event.entities.forEach(::removeRetired) }
    private fun removeRetired(entity: Entity) {
        if (!plugin.server.pluginManager.isPluginEnabled("GravesX") && owns(entity)) entity.remove()
    }
    override fun close() = HandlerList.unregisterAll(this)

    companion object {
        private val graveKey = NamespacedKey("gravesx", "grave_uuid")
        internal fun owns(entity: Entity): Boolean {
            if (entity !is ArmorStand && entity !is TextDisplay) return false
            if ("graveHologram" !in entity.scoreboardTags) return false
            val stored = entity.persistentDataContainer.get(graveKey, PersistentDataType.STRING)?.let(::uuid)
            val tagged = entity.scoreboardTags.firstOrNull { it.startsWith("graveHologramGraveUUID:") }
                ?.removePrefix("graveHologramGraveUUID:")?.let(::uuid)
            return (stored != null || tagged != null) && (stored == null || tagged == null || stored == tagged)
        }
        private fun uuid(value: String): UUID? = runCatching { UUID.fromString(value).takeIf { it.toString() == value } }.getOrNull()
    }
}
