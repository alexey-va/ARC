package ru.arc.treasure.core.gui

import com.github.stefvanschie.inventoryframework.gui.type.ChestGui
import net.kyori.adventure.text.Component
import org.bukkit.Material
import org.bukkit.entity.Player
import ru.arc.TitleInput
import ru.arc.board.guis.Inputable
import ru.arc.gui.gui
import ru.arc.treasure.core.TreasureConfig
import ru.arc.treasure.core.TreasurePool
import ru.arc.treasure.core.Treasures
import ru.arc.util.GuiUtils
import ru.arc.util.TextUtil

/**
 * Main GUI showing all treasure pools.
 */
object MainTreasuresGui {
    private const val ROWS = 6

    fun create(player: Player): ChestGui {
        val pools = Treasures.getAllPools().sortedBy { it.id }

        return gui(
            title = TreasureConfig.Gui.mainTitle,
            rows = ROWS,
            player = player,
        ) {
            navBackground()

            pagination(0 until (ROWS - 1)) {
                items(pools) { pool ->
                    material(Material.CHEST)
                    display("<yellow>${pool.id}")
                    lore(buildPoolLore(pool))
                    onClick {
                        GuiUtils.constructAndShowAsync({ PoolGui.create(player, pool) }, player)
                    }
                }
            }

            navBar {
                back(
                    configKey = "main.back",
                    command = TreasureConfig.Gui.mainBackCommand,
                )

                prevPage(slot = 3)
                nextPage(slot = 5)

                button(8) {
                    material(Material.LIME_DYE)
                    display(TreasureConfig.Gui.mainCreatePool)
                    lore(TreasureConfig.Gui.mainCreatePoolLore)
                    onClick {
                        TitleInput(player, CreatePoolInput(player), 0)
                        player.closeInventory()
                    }
                }
            }
        }
    }

    private fun buildPoolLore(pool: TreasurePool): List<String> =
        TreasureConfig.Gui.mainPoolLore.map { line ->
            line
                .replace("%size%", pool.size.toString())
                .replace("%weight%", pool.totalWeight.toString())
        }

    private class CreatePoolInput(
        private val player: Player,
    ) : Inputable {
        override fun setParameter(
            n: Int,
            s: String,
        ) {
            if (n == 0) {
                val pool = Treasures.getOrCreate(s)
                player.sendMessage(TextUtil.mm(TreasureConfig.Messages.poolCreated.replace("%pool%", s)))
                GuiUtils.constructAndShowAsync({ PoolGui.create(player, pool) }, player)
            }
        }

        override fun proceed() {
            GuiUtils.constructAndShowAsync({ create(player) }, player)
        }

        override fun satisfy(
            input: String,
            id: Int,
        ): Boolean = id == 0 && input.isNotBlank() && !Treasures.exists(input)

        override fun denyMessage(
            input: String,
            id: Int,
        ): Component = TextUtil.mm(TreasureConfig.Gui.mainCreatePoolDeny)

        override fun startMessage(id: Int): Component = TextUtil.mm(TreasureConfig.Gui.mainCreatePoolStart)
    }
}
