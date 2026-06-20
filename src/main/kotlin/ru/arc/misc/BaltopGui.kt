package ru.arc.misc

import com.github.stefvanschie.inventoryframework.gui.type.ChestGui
import org.bukkit.Material
import org.bukkit.entity.Player
import ru.arc.configs.Config
import ru.arc.core.async
import ru.arc.core.sync
import ru.arc.gui.gui
import ru.arc.hooks.HookRegistry
import ru.arc.util.CooldownManager
import ru.arc.util.GuiUtils
import ru.arc.util.Logging.info
import ru.arc.util.TextUtil.formatAmount
import ru.arc.util.TextUtil.mm
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Balance leaderboard GUI.
 */
object BaltopGuiFactory {
    private data class BaltopEntry(
        val name: String,
        val uuid: UUID,
        val balance: Double,
        val bank: Double,
    ) {
        val total: Double get() = balance + bank
    }

    enum class Sort { BALANCE, BANK, TOTAL }

    private val cachedEntries = CopyOnWriteArrayList<BaltopEntry>()
    private var lastUpdate = 0L

    fun create(
        config: Config,
        player: Player,
        sort: Sort = Sort.TOTAL,
    ): ChestGui {
        val rows = config.int("baltop.rows", 6)

        // Sort entries
        val comparator: Comparator<BaltopEntry> =
            when (sort) {
                Sort.BALANCE -> compareByDescending { it.balance }
                Sort.BANK -> compareByDescending { it.bank }
                Sort.TOTAL -> compareByDescending { it.total }
            }
        val sortedEntries = cachedEntries.sortedWith(comparator)

        return gui(config, "baltop.title", rows, player) {
            // Background for content area
            contentBackground(Material.GRAY_STAINED_GLASS_PANE, 0)

            // Background for nav bar
            navBackground()

            // Player items
            pagination(0 until (rows - 1)) {
                items(sortedEntries) { entry ->
                    skull(entry.uuid)

                    // Use tags instead of manual .replace()
                    tag("player", entry.name)
                    tag("balance", formatAmount(entry.balance))
                    tag("bank", formatAmount(entry.bank))
                    tag("total", formatAmount(entry.total))

                    display("<gold><player>")
                    lore(listOf(
                        "<gray>Всего: <green><total><white>💰",
                        "",
                        "<gray>Баланс: <green><balance><white>💰",
                        "<gray>Банк: <green><bank><white>💰",
                    ))
                    fromConfig(config, "baltop.item")

                    onClick { /* No action on player click */ }
                }
            }

            // Navigation bar
            navBar {
                back(slot = 0, configKey = "baltop.back")

                prevPage(slot = 3, configKey = "baltop.previous")

                // Sort button
                button(4) {
                    material(Material.BLUE_STAINED_GLASS_PANE)
                    modelData(11021)

                    val sortName =
                        when (sort) {
                            Sort.BALANCE -> config.string("baltop.sort.balance")
                            Sort.BANK -> config.string("baltop.sort.bank")
                            Sort.TOTAL -> config.string("baltop.sort.total")
                        }

                    tag("sort", sortName)
                    display("<gray>Сортировка: <sort>")
                    lore(emptyList())
                    fromConfig(config, "baltop.sort")

                    onClick { event ->
                        val clicker = event.whoClicked as? Player ?: return@onClick

                        if (CooldownManager.cooldown(clicker.uniqueId, "baltop_sort") != 0L) {
                            clicker.sendMessage(mm(config.string("baltop.sort.cooldown"), true))
                            return@onClick
                        }

                        CooldownManager.addCooldown(clicker.uniqueId, "baltop_sort", 1000L)

                        val nextSort =
                            when (sort) {
                                Sort.BALANCE -> Sort.BANK
                                Sort.BANK -> Sort.TOTAL
                                Sort.TOTAL -> Sort.BALANCE
                            }

                        async {
                            GuiUtils.constructAndShowAsync({ create(config, clicker, nextSort) }, clicker)
                        }
                    }
                }

                nextPage(slot = 5, configKey = "baltop.next")
            }
        }
    }

    /**
     * Update cache if needed and show GUI.
     */
    fun showAsync(
        config: Config,
        player: Player,
        sort: Sort = Sort.TOTAL,
    ) {
        updateCacheIfNeeded().thenRun {
            val gui = create(config, player, sort)
            sync { gui.show(player) }
        }
    }

    /**
     * Update the cache if it's stale.
     */
    private fun updateCacheIfNeeded(): CompletableFuture<Void> {
        if (HookRegistry.redisEcoHook == null) {
            return CompletableFuture.completedFuture(null)
        }

        if (System.currentTimeMillis() - lastUpdate <= 60000) {
            return CompletableFuture.completedFuture(null)
        }

        info("Updating baltop cache")
        lastUpdate = System.currentTimeMillis()

        return HookRegistry.redisEcoHook!!
            .getTopAccounts(224)
            .thenAccept { accounts ->
                val entries =
                    accounts
                        .mapNotNull { account ->
                            val name = account.name ?: return@mapNotNull null
                            val uuid = account.uuid ?: return@mapNotNull null
                            val bank = HookRegistry.bankHook?.offlineBalance(uuid.toString()) ?: 0.0
                            BaltopEntry(
                                name = name,
                                uuid = uuid,
                                balance = account.balance,
                                bank = bank,
                            )
                        }.filter { it.total > 0.0 }

                cachedEntries.clear()
                cachedEntries.addAll(entries)
            }
    }
}
