package ru.arc.hooks.elitemobs.guis

import net.kyori.adventure.text.Component
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import ru.arc.config.Config
import ru.arc.dungeons.menu.DungeonsMenus
import ru.arc.dungeons.menu.DungeonsMenuSchema
import ru.arc.hooks.elitemobs.EMHook
import ru.arc.paper.menu.PaperMenuEntry
import ru.arc.paper.menu.PaperMenuItemRenderContext
import ru.arc.util.TextUtils.formatAmount

object EmShop {
    fun open(
        config: Config,
        player: Player,
        shopHolder: ShopHolder,
        isGear: Boolean,
        emHook: EMHook,
    ) {
        val shop = shopHolder.getShop(player, emHook)
        val resetTime = config.integer("shop.reset-ticks", 20 * 60 * 5) * 50L
        val sinceLastReset = System.currentTimeMillis() - shop.timestamp
        val minsTillReset = maxOf(1, ((resetTime - sinceLastReset) / 1000 / 60).toInt())
        val entries = (if (isGear) shop.gear else shop.trinkets).map { shopItem ->
            itemEntry(config, player, shopItem, isGear, shopHolder, emHook)
        }
        DungeonsMenus.open(
            player,
            DungeonsMenuSchema.EM_SHOP,
            config.component("shop.title", "<dark_gray>Магазин EliteMobs"),
            elements = mapOf(
                "change" to DungeonsMenus.entry(
                    DungeonsMenus.item(
                        DungeonsMenuSchema.EM_SHOP,
                        "change",
                        values("type" to if (isGear) "Снаряжение" else "Тринкеты"),
                    ),
                ) { open(config, it, shopHolder, !isGear, emHook) },
                "update" to DungeonsMenus.entry(
                    DungeonsMenus.item(
                        DungeonsMenuSchema.EM_SHOP,
                        "update",
                        values(
                            "minutes" to minsTillReset.toString(),
                            "balance" to formatAmount(ru.arc.dungeons.ArcDungeonsPlugin.instance.emHook?.balance(player) ?: 0.0),
                            "player" to player.name,
                        ),
                    ),
                    enabled = false,
                ),
            ),
            regions = mapOf(DungeonsMenuSchema.EM_SHOP_ITEMS to entries),
        )
    }

    private fun itemEntry(
        config: Config,
        player: Player,
        item: ShopHolder.ShopItem,
        isGear: Boolean,
        shopHolder: ShopHolder,
        emHook: EMHook,
    ): PaperMenuEntry {
        val original = item.stack.itemMeta?.lore().orEmpty().toMutableList()
        repeat(config.integer("shop.remove-last-lore", 0)) { if (original.isNotEmpty()) original.removeLast() }
        val name = item.stack.itemMeta?.displayName() ?: Component.translatable(item.stack.type.translationKey())
        val rendered = DungeonsMenus.item(
            "em-shop-item",
            PaperMenuItemRenderContext(
                values = mapOf("name" to name, "price" to Component.text(formatAmount(item.price))),
                repeats = mapOf("original" to original.map { mapOf("line" to it) }),
            ),
        )
        val shown = applyPresentation(item.stack, rendered)
        return DungeonsMenus.entry(shown) {
            when {
                it.inventory.firstEmpty() == -1 ->
                    it.sendActionBar(config.component("shop.not-enough-space", "<red>Нет места в инвентаре"))
                emHook.balance(it) < item.price ->
                    it.sendActionBar(
                        config.component("shop.not-enough-money", "<red>Недостаточно валюты", tags = {
                            tag("cost", formatAmount(item.price))
                            tag("balance", formatAmount(emHook.balance(it)))
                        }),
                    )
                else -> {
                    emHook.removeBalance(it, item.price)
                    it.inventory.addItem(item.stack.clone())
                    open(config, it, shopHolder, isGear, emHook)
                }
            }
        }
    }

    private fun applyPresentation(base: ItemStack, presentation: ItemStack): ItemStack =
        base.clone().also { target ->
            val source = presentation.itemMeta
            target.editMeta { meta ->
                meta.displayName(source.displayName())
                meta.lore(source.lore())
            }
        }

    private fun values(vararg pairs: Pair<String, String>) = PaperMenuItemRenderContext(
        values = pairs.associate { (key, value) -> key to Component.text(value) },
    )
}
