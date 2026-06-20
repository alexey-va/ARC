package ru.arc.invest

import com.sk89q.worldedit.bukkit.BukkitAdapter
import com.sk89q.worldguard.WorldGuard
import com.sk89q.worldguard.protection.regions.ProtectedRegion
import org.bukkit.Bukkit
import org.bukkit.configuration.serialization.ConfigurationSerializable

class BusinessLocation(
    private val worldName: String,
    private val regionName: String,
) : ConfigurationSerializable {

    var region: ProtectedRegion? = null
        private set

    fun init() {
        val world = Bukkit.getWorld(worldName)
        if (world == null) {
            println("World $worldName was not found!")
            return
        }
        val regionContainer = WorldGuard.getInstance().platform.regionContainer
        region = regionContainer.get(BukkitAdapter.adapt(world))?.getRegion(regionName)
        if (region == null) {
            println("No region named $regionName in world $worldName")
        }
    }

    override fun serialize(): Map<String, Any> = mapOf(
        "world-name" to worldName,
        "region-name" to regionName,
    )

    companion object {
        @JvmStatic
        fun deserialize(map: Map<String, Any>): BusinessLocation {
            val worldName = map["world-name"] as String
            val regionName = map["region-name"] as String
            return BusinessLocation(worldName, regionName)
        }
    }
}
