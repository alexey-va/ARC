package ru.arc.eliteloot

import com.magmaguy.elitemobs.api.utils.EliteItemManager
import dev.lone.itemsadder.api.CustomStack
import org.bukkit.Bukkit
import org.bukkit.Color
import org.bukkit.Location
import org.bukkit.Sound
import org.bukkit.entity.Display
import org.bukkit.entity.Item
import org.bukkit.entity.ItemDisplay
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.meta.PotionMeta
import org.bukkit.scheduler.BukkitTask
import ru.arc.ARC
import ru.arc.config.ConfigManager
import ru.arc.util.Logging

internal fun eliteLootColor(level: Int): Color = Color.fromRGB(when (eliteTooltipTier(level)) {
    "artifact" -> 0xFF7066
    "legendary" -> 0xFFC14D
    "epic" -> 0xCF77FF
    "rare" -> 0x55BBFF
    "uncommon" -> 0x72EE99
    else -> 0xE5F2FF
})

/** Short-lived visual entities only; the reward itself never leaves the inventory. */
internal object EliteLootEffects {
    private val active = mutableMapOf<ItemDisplay, BukkitTask>()
    private val missing = mutableSetOf<String>()

    fun drop(item: Item) {
        if (!Bukkit.getPluginManager().isPluginEnabled("ItemsAdder")) return
        if (!EliteItemManager.isEliteMobsItem(item.itemStack)) return
        Bukkit.getScheduler().runTask(ARC.instance, Runnable {
            // Player throws are assigned after ItemSpawnEvent; wait before checking them.
            if (item.isValid && item.thrower == null) safely {
                show(item.itemStack, item.location, null, false)
            }
        })
    }

    fun received(player: Player, item: ItemStack) {
        if (!Bukkit.getPluginManager().isPluginEnabled("ItemsAdder")) return
        val copy = item.clone()
        Bukkit.getScheduler().runTask(ARC.instance, Runnable {
            if (!player.isOnline) return@Runnable
            safely {
                val origin = player.location
                val forward = origin.direction.setY(0)
                if (forward.lengthSquared() > 0.01) origin.add(forward.normalize().multiply(1.2))
                if (!origin.block.isPassable) origin.set(player.location.x, player.location.y, player.location.z)
                if (show(copy, origin, player, true)) {
                    player.playSound(player.location, Sound.BLOCK_AMETHYST_BLOCK_CHIME, 0.6f, 1.2f)
                }
            }
        })
    }

    private fun show(reward: ItemStack, origin: Location, viewer: Player?, fromCase: Boolean): Boolean {
        if (EliteLootManager.eliteLootProcessor == null || !EliteItemManager.isEliteMobsItem(reward)) return false
        val config = ConfigManager.ofModule(ARC.instance.dataFolder.toPath(), "elite-loot.yml")
        val id = config.string("drop-effect.model", "")
        if (id.isBlank() || !config.bool("drop-effect.enabled", true) || active.size >= 64) return false
        val level = EliteItemManager.getRoundedItemLevel(reward)
        if (!fromCase && level < config.integer("drop-effect.minimum-drop-level", 40)) return false
        val visual = CustomStack.getInstance(id)?.itemStack?.clone()
        if (visual == null || visual.itemMeta !is PotionMeta) {
            if (missing.add(id)) Logging.warn("EliteLoot effect model {} is missing or not a potion; effect skipped", id)
            return false
        }
        visual.editMeta(PotionMeta::class.java) { it.color = eliteLootColor(level) }
        val location = origin.clone().add(0.0, 0.5, 0.0).apply { yaw = 0f; pitch = 0f }
        val display = location.world.spawn(location, ItemDisplay::class.java) {
            it.isPersistent = false
            it.isVisibleByDefault = viewer == null
            it.setGravity(false)
            it.setItemStack(visual)
            it.itemDisplayTransform = ItemDisplay.ItemDisplayTransform.FIXED
            it.brightness = Display.Brightness(15, 15)
            it.viewRange = 0.5f
        }
        try {
            viewer?.showEntity(ARC.instance, display)
            active[display] = Bukkit.getScheduler().runTaskLater(ARC.instance, Runnable {
                active.remove(display)
                display.remove()
            }, 40L)
        } catch (failure: Exception) {
            display.remove()
            throw failure
        }
        return true
    }

    private inline fun safely(action: () -> Unit) {
        try { action() } catch (failure: Exception) {
            Logging.warn("EliteLoot effect failed; reward is unaffected", failure)
        }
    }

    fun shutdown() {
        active.forEach { (display, task) -> task.cancel(); display.remove() }
        active.clear()
        missing.clear()
    }
}
