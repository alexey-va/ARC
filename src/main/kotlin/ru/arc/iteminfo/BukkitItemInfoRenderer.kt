package ru.arc.iteminfo

import net.kyori.adventure.bossbar.BossBar
import org.bukkit.Bukkit
import org.bukkit.Color
import org.bukkit.entity.Display
import org.bukkit.entity.Player
import org.bukkit.entity.TextDisplay
import org.joml.Matrix4f
import ru.arc.ARC
import ru.arc.onboarding.followClaimGuideDisplay
import java.util.UUID

internal class BukkitItemInfoRenderer(
    private val settings: ItemInfoSettings,
) : ItemInfoRenderer {
    private data class Hologram(val display: TextDisplay, var target: ItemInfoTarget, var preferences: ItemInfoPreferences)
    private data class Bar(val bossbar: BossBar, var target: ItemInfoTarget, var preferences: ItemInfoPreferences)

    private val holograms = mutableMapOf<UUID, Hologram>()
    private val bossbars = mutableMapOf<UUID, Bar>()

    override fun render(player: Player, preferences: ItemInfoPreferences, target: ItemInfoTarget) {
        when (preferences.mode) {
            ItemInfoMode.HOLOGRAM -> renderHologram(player, target, preferences)
            ItemInfoMode.BOSSBAR -> renderBossbar(player, target, preferences)
            ItemInfoMode.OFF -> clear(player)
        }
    }

    override fun follow(player: Player) {
        val display = holograms[player.uniqueId]?.display ?: return
        if (!display.isValid || display.world != player.world) {
            clearHologram(player.uniqueId)
            return
        }
        val preferences = holograms[player.uniqueId]?.preferences ?: return
        followClaimGuideDisplay(display, itemInfoHologramLocation(
            player.eyeLocation,
            preferences.verticalOffset,
            preferences.horizontalOffset,
        ))
    }

    override fun clear(player: Player) {
        clearHologram(player.uniqueId)
        clearBossbar(player.uniqueId, player)
    }

    override fun close() {
        holograms.values.forEach { it.display.remove() }
        holograms.clear()
        bossbars.forEach { (viewer, state) -> Bukkit.getPlayer(viewer)?.hideBossBar(state.bossbar) }
        bossbars.clear()
    }

    private fun renderHologram(player: Player, target: ItemInfoTarget, preferences: ItemInfoPreferences) {
        clearBossbar(player.uniqueId, player)
        var state = holograms[player.uniqueId]
        if (state == null || !state.display.isValid || state.display.world != player.world) {
            state?.display?.remove()
            val location = itemInfoHologramLocation(player.eyeLocation, preferences.verticalOffset, preferences.horizontalOffset)
            val display = player.world.spawn(location, TextDisplay::class.java) {
                it.isPersistent = false
                it.isVisibleByDefault = false
                it.setGravity(false)
                it.brightness = Display.Brightness(15, 15)
                it.viewRange = 2.5f
                it.billboard = Display.Billboard.CENTER
                it.isSeeThrough = true
                it.isShadowed = true
                it.backgroundColor = Color.fromARGB(255, 15, 23, 30)
                it.lineWidth = 230
                it.teleportDuration = 2
                it.setTransformationMatrix(Matrix4f().scaling(preferences.hologramScale))
                it.text(settings.hologramText(target, preferences.showNamespacedId))
            }
            player.showEntity(ARC.instance, display)
            state = Hologram(display, target, preferences)
            holograms[player.uniqueId] = state
        } else if (state.target != target || state.preferences != preferences) {
            state.display.text(settings.hologramText(target, preferences.showNamespacedId))
            state.display.setTransformationMatrix(Matrix4f().scaling(preferences.hologramScale))
            state.target = target
            state.preferences = preferences
        }
        followClaimGuideDisplay(state.display, itemInfoHologramLocation(
            player.eyeLocation,
            preferences.verticalOffset,
            preferences.horizontalOffset,
        ))
    }

    private fun renderBossbar(player: Player, target: ItemInfoTarget, preferences: ItemInfoPreferences) {
        clearHologram(player.uniqueId)
        val state = bossbars[player.uniqueId]
        if (state == null) {
            val bar = BossBar.bossBar(
                settings.bossbarText(target, preferences.showNamespacedId),
                1f,
                BossBar.Color.WHITE,
                BossBar.Overlay.PROGRESS,
            )
            bossbars[player.uniqueId] = Bar(bar, target, preferences)
            player.showBossBar(bar)
        } else if (state.target != target || state.preferences != preferences) {
            state.bossbar.name(settings.bossbarText(target, preferences.showNamespacedId))
            state.target = target
            state.preferences = preferences
        }
    }

    private fun clearHologram(viewer: UUID) {
        holograms.remove(viewer)?.display?.remove()
    }

    private fun clearBossbar(viewer: UUID, player: Player? = Bukkit.getPlayer(viewer)) {
        bossbars.remove(viewer)?.bossbar?.let { bar -> player?.hideBossBar(bar) }
    }
}
