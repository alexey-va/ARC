package ru.arc.eliteloot

import com.magmaguy.elitemobs.api.utils.EliteItemManager
import dev.lone.itemsadder.api.CustomStack
import org.bukkit.Bukkit
import org.bukkit.Color
import org.bukkit.Location
import org.bukkit.entity.Display
import org.bukkit.entity.Item
import org.bukkit.entity.ItemDisplay
import org.bukkit.inventory.meta.PotionMeta
import org.bukkit.scheduler.BukkitTask
import ru.arc.ARC
import ru.arc.config.ConfigManager
import ru.arc.util.Logging

internal fun eliteEffectFinished(displayValid: Boolean, groundItemValid: Boolean): Boolean =
    !displayValid || !groundItemValid

// The model floor is one block below its origin; compensate after the 1.25x scale.
internal fun eliteEffectPosition(origin: Location): Location =
    origin.clone().add(0.0, 1.35, 0.0).apply { yaw = 0f; pitch = 0f }

internal fun eliteLootColor(level: Int): Color = Color.fromRGB(when (eliteTooltipTier(level)) {
    "artifact" -> 0xFF7066
    "legendary" -> 0xFFC14D
    "epic" -> 0xCF77FF
    "rare" -> 0x55BBFF
    "uncommon" -> 0x72EE99
    else -> 0xE5F2FF
})

/** Ground-only visuals follow their item until pickup, removal or unload. */
internal object EliteLootEffects {
    private val active = mutableMapOf<ItemDisplay, BukkitTask>()
    private val missing = mutableSetOf<String>()

    fun drop(item: Item) {
        if (!Bukkit.getPluginManager().isPluginEnabled("ItemsAdder")) return
        if (!EliteItemManager.isEliteMobsItem(item.itemStack)) return
        Bukkit.getScheduler().runTask(ARC.instance, Runnable {
            if (item.isValid) safely { show(item) }
        })
    }

    private fun show(groundItem: Item): Boolean {
        val reward = groundItem.itemStack
        if (EliteLootManager.eliteLootProcessor == null || !EliteItemManager.isEliteMobsItem(reward)) return false
        val config = ConfigManager.ofModule(ARC.instance.dataFolder.toPath(), "elite-loot.yml")
        val id = config.string("drop-effect.model", "")
        if (id.isBlank() || !config.bool("drop-effect.enabled", true) || active.size >= 64) return false
        val level = EliteItemManager.getRoundedItemLevel(reward)
        if (level < config.integer("drop-effect.minimum-drop-level", 0)) return false
        val visual = CustomStack.getInstance(id)?.itemStack?.clone()
        if (visual == null || visual.itemMeta !is PotionMeta) {
            if (missing.add(id)) Logging.warn("EliteLoot effect model {} is missing or not a potion; effect skipped", id)
            return false
        }
        visual.editMeta(PotionMeta::class.java) { it.color = eliteLootColor(level) }
        val location = eliteEffectPosition(groundItem.location)
        val display = location.world.spawn(location, ItemDisplay::class.java) {
            it.isPersistent = false
            it.isVisibleByDefault = true
            it.setGravity(false)
            it.setItemStack(visual)
            it.itemDisplayTransform = ItemDisplay.ItemDisplayTransform.FIXED
            it.brightness = Display.Brightness(15, 15)
            it.viewRange = 0.5f
            it.transformation = org.bukkit.util.Transformation(
                org.joml.Vector3f(), org.joml.Quaternionf(), org.joml.Vector3f(1.25f), org.joml.Quaternionf())
        }
        try {
            active[display] = Bukkit.getScheduler().runTaskTimer(ARC.instance, Runnable {
                if (eliteEffectFinished(display.isValid, groundItem.isValid)) {
                    active.remove(display)?.cancel()
                    display.remove()
                } else {
                    display.teleport(eliteEffectPosition(groundItem.location))
                }
            }, 2L, 2L)
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
