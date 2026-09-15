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
    private data class Hologram(val display: TextDisplay, var target: ItemInfoTarget)
    private data class Bar(val bossbar: BossBar, var target: ItemInfoTarget)

    private val holograms = mutableMapOf<UUID, Hologram>()
    private val bossbars = mutableMapOf<UUID, Bar>()

    override fun render(player: Player, mode: ItemInfoMode, target: ItemInfoTarget) {
        when (mode) {
            ItemInfoMode.HOLOGRAM -> renderHologram(player, target)
            ItemInfoMode.BOSSBAR -> renderBossbar(player, target)
            ItemInfoMode.OFF -> clear(player)
        }
    }

    override fun follow(player: Player) {
        val display = holograms[player.uniqueId]?.display ?: return
        if (!display.isValid || display.world != player.world) {
            clearHologram(player.uniqueId)
            return
        }
        followClaimGuideDisplay(display, itemInfoHologramLocation(player.eyeLocation))
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

    private fun renderHologram(player: Player, target: ItemInfoTarget) {
        clearBossbar(player.uniqueId, player)
        var state = holograms[player.uniqueId]
        if (state == null || !state.display.isValid || state.display.world != player.world) {
            state?.display?.remove()
            val location = itemInfoHologramLocation(player.eyeLocation)
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
                it.setTransformationMatrix(Matrix4f().scaling(1.30f))
                it.text(settings.hologramText(target))
            }
            player.showEntity(ARC.instance, display)
            state = Hologram(display, target)
            holograms[player.uniqueId] = state
        } else if (state.target != target) {
            state.display.text(settings.hologramText(target))
            state.target = target
        }
        followClaimGuideDisplay(state.display, itemInfoHologramLocation(player.eyeLocation))
    }

    private fun renderBossbar(player: Player, target: ItemInfoTarget) {
        clearHologram(player.uniqueId)
        val state = bossbars[player.uniqueId]
        if (state == null) {
            val bar = BossBar.bossBar(
                settings.bossbarText(target),
                1f,
                BossBar.Color.WHITE,
                BossBar.Overlay.PROGRESS,
            )
            bossbars[player.uniqueId] = Bar(bar, target)
            player.showBossBar(bar)
        } else if (state.target != target) {
            state.bossbar.name(settings.bossbarText(target))
            state.target = target
        }
    }

    private fun clearHologram(viewer: UUID) {
        holograms.remove(viewer)?.display?.remove()
    }

    private fun clearBossbar(viewer: UUID, player: Player? = Bukkit.getPlayer(viewer)) {
        bossbars.remove(viewer)?.bossbar?.let { bar -> player?.hideBossBar(bar) }
    }
}
