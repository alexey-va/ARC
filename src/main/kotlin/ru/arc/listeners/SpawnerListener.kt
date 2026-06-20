package ru.arc.listeners

import net.kyori.adventure.text.Component
import org.bukkit.Material
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.block.BlockBreakEvent
import ru.arc.util.CooldownManager
import ru.arc.util.TextUtil

class SpawnerListener : Listener {

    @EventHandler
    fun spawnerBreak(event: BlockBreakEvent) {
        if (event.block.type != Material.SPAWNER) return
        if (CooldownManager.cooldown(event.player.uniqueId, "spawner_break") > 0) return
        val tileState = event.block.state as? org.bukkit.block.TileState ?: return
        val hasOhDungeon = tileState.persistentDataContainer.keys.any {
            it.namespace.startsWith("oh_the_dungeons")
        }
        if (!hasOhDungeon) return
        val msg = TextUtil.strip(Component.text("В этом данже спавнеры не добываются!")) ?: return
        event.player.sendActionBar(msg)
        CooldownManager.addCooldown(event.player.uniqueId, "spawner_break", 1200)
    }
}
