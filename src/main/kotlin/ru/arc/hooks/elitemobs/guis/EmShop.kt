package ru.arc.hooks.elitemobs.guis

import com.github.stefvanschie.inventoryframework.adventuresupport.TextHolder
import com.github.stefvanschie.inventoryframework.gui.GuiItem
import com.github.stefvanschie.inventoryframework.gui.type.ChestGui
import com.github.stefvanschie.inventoryframework.pane.PaginatedPane
import com.github.stefvanschie.inventoryframework.pane.Pane
import com.github.stefvanschie.inventoryframework.pane.StaticPane
import com.github.stefvanschie.inventoryframework.pane.util.Slot
import net.kyori.adventure.text.minimessage.tag.Tag
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import ru.arc.config.Config
import ru.arc.hooks.HookRegistry
import ru.arc.hooks.elitemobs.EMHook
import ru.arc.util.GuiUtils
import ru.arc.util.TextUtil.formatAmount
import ru.arc.util.TextUtil.mm
import ru.arc.util.fromConfig
import ru.arc.util.guiItem
import ru.arc.util.itemComponents

class EmShop(
    private val config: Config,
    private val player: Player,
    private val shopHolder: ShopHolder,
    private var isGear: Boolean,
    private val emHook: EMHook,
) : ChestGui(config.integer("shop.rows", 6), TextHolder.deserialize(config.string("shop.title", "Shop"))) {

    init {
        setupBackground()
        setupItems()
        setupNav()
    }

    private fun setupItems() {
        val pane = PaginatedPane(9, config.integer("shop.rows", 6) - 1, Pane.Priority.HIGHEST)
        this.addPane(Slot.fromXY(0, 1), pane)
        val shop = shopHolder.getShop(player, emHook)
        val items = ArrayList<GuiItem>()

        for (item in if (isGear) shop.gear else shop.trinkets) {
            val stack = item.stack.clone()
            val meta = stack.itemMeta ?: continue
            val lore = meta.lore()?.toMutableList() ?: ArrayList()
            lore.addAll(0, config.componentList("shop.item-price.lore") { tag("price", formatAmount(item.price)) })
            val removeLast = config.integer("shop.remove-last-lore", 0)
            repeat(removeLast) {
                if (lore.isNotEmpty()) lore.removeAt(lore.size - 1)
            }
            meta.lore(lore)
            stack.itemMeta = meta
            items.add(
                guiItem(stack) {
                    onClick { click ->
                        click.isCancelled = true
                        processClick(click, click.currentItem!!, item)
                    }
                },
            )
        }
        pane.populateWithGuiItems(items)
    }

    private fun resolver(): TagResolver {
        val resetTimeTicks = config.integer("shop.reset-ticks", 20 * 60 * 5)
        val resetTime = resetTimeTicks * 50L
        val sinceLastReset = System.currentTimeMillis() - shopHolder.getShop(player, emHook).timestamp
        var minsTillReset = ((resetTime - sinceLastReset) / 1000 / 60).toInt()
        if (minsTillReset == 0) minsTillReset = 1

        return TagResolver.builder()
            .resolver(TagResolver.resolver("type", Tag.inserting(mm(if (isGear) "Снаряжение" else "Тринкеты", true))))
            .resolver(TagResolver.resolver("balance", Tag.inserting(mm(formatAmount(HookRegistry.emHook?.balance(player) ?: 0.0), true))))
            .resolver(TagResolver.resolver("player", Tag.inserting(mm(player.name, true))))
            .resolver(TagResolver.resolver("update_minutes", Tag.inserting(mm("$minsTillReset", true))))
            .build()
    }

    private fun setupNav() {
        val pane = StaticPane(9, 1)
        this.addPane(Slot.fromXY(0, 0), pane)
        val resolver = resolver()

        val change = guiItem(Material.GREEN_STAINED_GLASS_PANE) {
            onClick { click ->
                click.isCancelled = true
                GuiUtils.constructAndShowAsync({ EmShop(config, player, shopHolder, !isGear, emHook) }, click.whoClicked)
            }
            display("<gold>Сменить тип товаров")
            lore(listOf("<gray>Текущий тип: <green><type>"))
            tagResolver(resolver)
            fromConfig(config, "shop.change")
        }
        pane.addItem(change, 4, 0)

        val update = guiItem(Material.PAPER) {
            onClick { click -> click.isCancelled = true }
            modelData(31173)
            display("<gold>Обновление ассортимента")
            lore(listOf("<gray>Обновление через: <green><update_minutes> минут"))
            tagResolver(resolver)
            fromConfig(config, "shop.update")
        }
        pane.addItem(update, 8, 0)
    }

    private fun processClick(click: org.bukkit.event.inventory.InventoryClickEvent, stack: ItemStack, item: ShopHolder.ShopItem) {
        if (click.whoClicked.inventory.firstEmpty() == -1) {
            GuiUtils.temporaryChange(stack, config.component("shop.not-enough-space", "<red>Нет места в инвентаре"), null, 60) { update() }
            update()
            return
        }

        val player1 = click.whoClicked as Player
        val em = HookRegistry.emHook ?: return
        val balance = em.balance(player1)
        val cost = item.price

        if (balance < cost) {
            val resolver =
                TagResolver.resolver(
                    TagResolver.resolver("cost", Tag.inserting(mm(formatAmount(cost), true))),
                    TagResolver.resolver("balance", Tag.inserting(mm(formatAmount(balance), true))),
                )
            val (display, lore) = config.itemComponents("shop.not-enough-money", resolver)
            GuiUtils.temporaryChange(stack, display, lore, 60) { update() }
            update()
            return
        }

        em.removeBalance(player1, cost)
        player1.inventory.addItem(item.stack)
    }

    private fun setupBackground() {
        // Background setup disabled
    }
}
