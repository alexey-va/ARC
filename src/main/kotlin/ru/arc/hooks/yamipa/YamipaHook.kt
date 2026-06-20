package ru.arc.hooks.yamipa

import io.josemmo.bukkit.plugin.YamipaPlugin
import io.josemmo.bukkit.plugin.renderer.FakeImage
import io.josemmo.bukkit.plugin.renderer.ImageRenderer
import io.josemmo.bukkit.plugin.renderer.WorldAreaId
import org.bukkit.Location
import org.bukkit.entity.Player

class YamipaHook {

    @Suppress("UNCHECKED_CAST")
    fun updateImages(location: Location, players: List<Player>) {
        if (players.isEmpty()) return
        try {
            val player = players[0]
            val imageRenderer = YamipaPlugin.getInstance().renderer
            val worldAreaId = WorldAreaId.fromLocation(location)

            val method = ImageRenderer::class.java.getDeclaredMethod("getImagesInViewDistance", WorldAreaId::class.java)
            method.isAccessible = true

            val field = ImageRenderer::class.java.getDeclaredField("playersLocation")
            field.isAccessible = true
            val map = field.get(imageRenderer) as Map<Player, WorldAreaId>
            val prevWorldAreaId = map[player]

            val desiredState = method.invoke(imageRenderer, worldAreaId) as Set<FakeImage>
            val currentState = if (prevWorldAreaId == null) emptySet()
                else method.invoke(imageRenderer, prevWorldAreaId) as Set<FakeImage>

            currentState.forEach { it.destroy() }
            desiredState.forEach { image -> players.forEach { image.spawn(it) } }
        } catch (e: Exception) {
            ru.arc.util.Logging.error("Failed to update yamipa images", e)
        }
    }
}
