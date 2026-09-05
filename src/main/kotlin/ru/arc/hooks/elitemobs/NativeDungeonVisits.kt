package ru.arc.hooks.elitemobs

import com.magmaguy.elitemobs.config.contentpackages.ContentPackagesConfig
import com.magmaguy.elitemobs.instanced.dungeons.DungeonInstance
import com.magmaguy.elitemobs.utils.ConfigurationLocation
import org.bukkit.Location
import org.bukkit.World
import java.util.UUID
import java.util.WeakHashMap

internal data class DungeonVisit(
    val run: String,
    val waiting: Boolean = false,
    val canResume: Boolean = true,
    val entry: Location? = null,
    val members: Set<UUID>? = null,
    val instanced: Boolean = false,
)

internal class NativeDungeonVisits {
    private val runs = WeakHashMap<DungeonInstance, String>()
    fun resolve(world: World): DungeonVisit? {
        val instance = DungeonInstance.getDungeonInstances().firstOrNull { it.world == world }
        if (instance != null) {
            val state = instance.state.name
            // Same conversion as DynamicDungeonInstance.generate: blueprint coordinates,
            // rebound to this exact clone. Never use the blueprint world as the target.
            val entry = runCatching {
                ConfigurationLocation.serialize(instance.contentPackagesConfigFields.startLocationString)?.clone()?.also { it.world = world }
            }.getOrNull()
            return DungeonVisit(runs.getOrPut(instance) { UUID.randomUUID().toString() },
                waiting = state == "WAITING" && !instance.isCancelled,
                canResume = state == "ONGOING" && !instance.isCancelled,
                entry = entry, members = instance.players.map { it.uniqueId }.toSet(), instanced = true)
        }
        val fields = ContentPackagesConfig.getDungeonPackages().values.firstOrNull { it.worldName == world.name } ?: return null
        return if (fields.contentType.name == "OPEN_DUNGEON") {
            DungeonVisit("open", entry = fields.teleportLocation?.clone()?.takeIf { it.world == world })
        } else null
    }
}
