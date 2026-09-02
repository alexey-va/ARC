package ru.arc.treasure.core.gui

import net.kyori.adventure.text.Component
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import ru.arc.TitleInput
import ru.arc.board.guis.Inputable
import ru.arc.gui.ArcMenuSchema
import ru.arc.gui.ArcMenus
import ru.arc.paper.menu.PaperMenuItemRenderContext
import ru.arc.treasure.core.TreasureConfig
import ru.arc.treasure.core.Treasures
import ru.arc.util.TextUtil

object MainTreasuresGui {
    fun open(player: Player) {
        val entries = Treasures.getAllPools().sortedBy { it.id }.map { pool ->
            val rendered = ArcMenus.item(
                "treasure-pool-entry",
                values("pool" to pool.id, "size" to pool.size.toString(), "weight" to pool.totalWeight.toString()),
            )
            ArcMenus.entry(applyPresentation(TreasureGuiIcons.poolPreviewIcon(pool), rendered)) { PoolGui.open(it, pool) }
        }
        ArcMenus.open(
            player,
            ArcMenuSchema.TREASURE_POOLS,
            TextUtil.mm(TreasureConfig.Gui.mainTitle, true),
            elements = mapOf(
                "back" to ArcMenus.entry(ArcMenus.item(ArcMenuSchema.TREASURE_POOLS, "back")) {
                    it.closeInventory()
                    it.performCommand(TreasureConfig.Gui.mainBackCommand)
                },
                "previous" to ArcMenus.entryWithContext(ArcMenus.item(ArcMenuSchema.TREASURE_POOLS, "previous")) { it.session.previousPage() },
                "next" to ArcMenus.entryWithContext(ArcMenus.item(ArcMenuSchema.TREASURE_POOLS, "next")) { it.session.nextPage() },
                "create" to ArcMenus.entry(ArcMenus.item(ArcMenuSchema.TREASURE_POOLS, "create")) {
                    TitleInput(it, CreatePoolInput(it), 0)
                    it.closeInventory()
                },
            ),
            regions = mapOf(ArcMenuSchema.TREASURE_POOL_ENTRIES to entries),
        )
    }

    private class CreatePoolInput(private val player: Player) : Inputable {
        override fun setParameter(n: Int, s: String) {
            if (n == 0) {
                val pool = Treasures.getOrCreatePool(s)
                player.sendMessage(TextUtil.mm(TreasureConfig.Messages.poolCreated.replace("%pool%", s)))
                PoolGui.open(player, pool)
            }
        }

        override fun proceed() = open(player)

        override fun satisfy(input: String, id: Int): Boolean = id == 0 && input.isNotBlank() && !Treasures.exists(input)

        override fun denyMessage(input: String, id: Int): Component = TextUtil.mm(TreasureConfig.Gui.mainCreatePoolDeny)

        override fun startMessage(id: Int): Component = TextUtil.mm(TreasureConfig.Gui.mainCreatePoolStart)
    }

    private fun values(vararg pairs: Pair<String, String>) = PaperMenuItemRenderContext(
        values = pairs.associate { (key, value) -> key to Component.text(value) },
    )

    internal fun applyPresentation(base: ItemStack, presentation: ItemStack): ItemStack =
        base.clone().also { target ->
            val source = presentation.itemMeta
            target.editMeta { meta ->
                meta.displayName(source.displayName())
                meta.lore(source.lore())
            }
        }
}
