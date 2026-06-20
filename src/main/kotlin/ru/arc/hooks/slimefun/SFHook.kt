package ru.arc.hooks.slimefun

import io.github.thebusybiscuit.slimefun4.api.events.PlayerRightClickEvent
import io.github.thebusybiscuit.slimefun4.api.items.SlimefunItem
import io.github.thebusybiscuit.slimefun4.implementation.Slimefun
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import org.bukkit.Material
import org.bukkit.block.Block
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.inventory.ItemStack
import ru.arc.ARC
import ru.arc.configs.ConfigManager
import ru.arc.sync.SyncManager
import ru.arc.util.TextUtil.mm
import java.util.UUID

class SFHook : Listener {

    private val config = ConfigManager.of(ARC.instance.dataPath, "backpacks.yml")

    @EventHandler(priority = EventPriority.LOWEST)
    fun onUseBackpack(event: PlayerRightClickEvent) {
        if (checkForOthersPlayersBackpackUse(event)) return
        if (checkForBackpack(event)) return
        SyncManager.processEvent(event)
    }

    fun isSlimefunBlock(block: Block): Boolean =
        Slimefun.getBlockDataService().getBlockData(block).isPresent

    fun isSlimefunItem(stack: ItemStack): Boolean =
        SlimefunItem.getByItem(stack) != null

    fun getSlimefunItemStack(id: String): ItemStack? =
        SlimefunItem.getById(id)?.item

    fun checkForOthersPlayersBackpackUse(event: PlayerRightClickEvent): Boolean {
        if (event.player.inventory.itemInMainHand.type == Material.AIR) return false
        val item = event.player.inventory.itemInMainHand
        val sfItem = SlimefunItem.getByItem(item) ?: return false
        if (!sfItem.id.contains("BACKPACK")) return false

        val lore = item.itemMeta?.lore() ?: return false
        if (lore.isEmpty()) return false

        for (comp in lore) {
            if (comp == null) continue
            val string = PlainTextComponentSerializer.plainText().serialize(comp)
            if (string.contains("ID")) {
                val split = string.split(" ")
                if (split.size < 2) return false
                val id = split[1]
                val uuidString = id.split("#")[0]
                if (uuidString.length != 36) return false
                val uuid = UUID.fromString(uuidString)
                if (uuid == event.player.uniqueId) return false
                event.cancel()
                event.player.sendMessage(
                    mm(config.string("backpacks.use-other-player", "<dark_red>Вы не можете использовать чужие рюкзаки!"))
                )
                return true
            }
        }
        return false
    }

    fun checkForBackpack(event: PlayerRightClickEvent): Boolean {
        if (event.player.inventory.itemInMainHand.type == Material.AIR) return false
        if (!isBackpackDisabled()) return false
        val item = event.player.inventory.itemInMainHand
        val sfItem = SlimefunItem.getByItem(item) ?: return false
        if (!sfItem.id.contains("BACKPACK")) return false
        event.player.sendMessage(config.component("backpacks.forbidden", "<dark_red>Вы не можете использовать рюкзак здесь!"))
        event.cancel()
        return true
    }

    private fun isBackpackDisabled(): Boolean {
        val misc = ConfigManager.of(ARC.instance.dataPath, "misc.yml")
        return misc.bool("redis.main-server", false)
    }
}
