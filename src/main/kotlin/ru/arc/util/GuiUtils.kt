package ru.arc.util

import com.github.stefvanschie.inventoryframework.gui.GuiItem
import com.github.stefvanschie.inventoryframework.gui.type.ChestGui
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver
import org.bukkit.Material
import org.bukkit.entity.HumanEntity
import org.bukkit.inventory.ItemStack
import ru.arc.core.ScheduledTask
import ru.arc.core.async
import ru.arc.core.delayed
import ru.arc.core.ticks
import ru.arc.util.Logging.error
import ru.arc.util.TextUtil.strip
import java.util.UUID
import java.util.function.Supplier

object GuiUtils {

    private val backgrounds = mutableMapOf<BgKey, GuiItem>()

    data class BgKey(val material: Material, val model: Int)

    /**
     * Clears the backgrounds cache. Used in tests to reset state.
     */
    @JvmStatic
    fun clearBackgrounds() {
        backgrounds.clear()
    }

    @JvmStatic
    fun background(material: Material, model: Int): GuiItem {
        val key = BgKey(material, model)
        return backgrounds.getOrPut(key) {
            guiItem(material) {
                display(" ")
                if (model != 0) modelData(model)
                onClick { it.isCancelled = true }
            }
        }
    }

    /**
     * Temporarily change an item's display and lore, then restore after delay.
     * Uses Task DSL for scheduling.
     */
    @JvmStatic
    fun temporaryChange(
        stack: ItemStack,
        display: Component?,
        lore: List<Component>?,
        ticks: Long,
        callback: Runnable,
        resolver: TagResolver?,
    ): ScheduledTask? {
        val meta = stack.itemMeta
        val oldDisplay = meta?.displayName()
        val oldLore = meta?.lore()

        if (display != null) meta?.displayName(strip(display))
        if (lore != null && lore.isNotEmpty()) {
            meta?.lore(lore.map { strip(it)!! })
        }
        stack.itemMeta = meta

        if (ticks < 0) return null

        return delayed(ticks.ticks) {
            val meta1 = stack.itemMeta
            meta1?.displayName(strip(oldDisplay))
            if (oldLore != null) {
                meta1?.lore(oldLore.map { strip(it)!! })
            } else {
                meta1?.lore(null)
            }
            stack.itemMeta = meta1
            callback.run()
        }
    }

    @JvmStatic
    fun temporaryChange(
        stack: ItemStack,
        display: Component?,
        lore: List<Component>?,
        ticks: Long,
        callback: Runnable,
    ): ScheduledTask? = temporaryChange(stack, display, lore, ticks, callback, null)

    @JvmStatic
    fun cooldownCheck(guiItem: GuiItem, playerUuid: UUID, chestGui: ChestGui?): Boolean {
        val cooldown = CooldownManager.cooldown(playerUuid, "gui_click")
        if (cooldown > 0) {
            temporaryChange(
                guiItem.item,
                strip(Component.text("Не кликайте так быстро!", NamedTextColor.RED)),
                null,
                cooldown,
                Runnable { chestGui?.update() },
            )
            return false
        }
        CooldownManager.addCooldown(playerUuid, "gui_click", 10)
        return true
    }

    /**
     * Construct and show a GUI on the main server thread after an optional delay.
     * ChestGui construction requires the main thread (Bukkit API restriction),
     * so this always runs the supplier synchronously on the main thread.
     */
    @JvmStatic
    fun constructAndShowAsync(supplier: Supplier<ChestGui>, player: HumanEntity, delay: Int) {
        delayed(delay.ticks) {
            val gui =
                try {
                    supplier.get()
                } catch (e: Exception) {
                    error("Error constructing GUI", e)
                    return@delayed
                }
            gui.show(player)
        }
    }

    @JvmStatic
    fun constructAndShowAsync(supplier: Supplier<ChestGui>, player: HumanEntity) {
        constructAndShowAsync(supplier, player, 3)
    }

    @JvmStatic
    fun background(material: Material): GuiItem {
        return background(material, 0)
    }

    @JvmStatic
    fun background(): GuiItem {
        return background(Material.GRAY_STAINED_GLASS_PANE, 11000)
    }
}

