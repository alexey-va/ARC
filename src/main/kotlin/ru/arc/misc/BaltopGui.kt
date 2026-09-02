package ru.arc.misc

import net.kyori.adventure.text.Component
import org.bukkit.Bukkit
import org.bukkit.entity.Player
import org.bukkit.inventory.meta.SkullMeta
import ru.arc.config.Config
import ru.arc.core.sync
import ru.arc.gui.ArcMenuSchema
import ru.arc.gui.ArcMenus
import ru.arc.hooks.HookRegistry
import ru.arc.paper.menu.PaperMenuItemRenderContext
import ru.arc.util.CooldownManager
import ru.arc.util.Logging.error
import ru.arc.util.Logging.info
import ru.arc.util.TextUtil
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
    private var refreshInFlight: CompletableFuture<Void>? = null

    fun open(
        config: Config,
        player: Player,
        sort: Sort = Sort.TOTAL,
    ) {
        val comparator: Comparator<BaltopEntry> =
            when (sort) {
                Sort.BALANCE -> compareByDescending { it.balance }
                Sort.BANK -> compareByDescending { it.bank }
                Sort.TOTAL -> compareByDescending { it.total }
            }
        val sortedEntries = cachedEntries.sortedWith(comparator)
        val entries = sortedEntries.map { entry ->
            val item = ArcMenus.item(
                "baltop-entry",
                PaperMenuItemRenderContext(values = mapOf(
                    "player" to Component.text(entry.name),
                    "balance" to Component.text(formatAmount(entry.balance)),
                    "bank" to Component.text(formatAmount(entry.bank)),
                    "total" to Component.text(formatAmount(entry.total)),
                )),
            )
            (item.itemMeta as? SkullMeta)?.let { meta ->
                meta.owningPlayer = Bukkit.getOfflinePlayer(entry.uuid)
                item.itemMeta = meta
            }
            ArcMenus.entry(item, enabled = false)
        }
        val sortName = when (sort) {
            Sort.BALANCE -> config.string("baltop.sort.balance")
            Sort.BANK -> config.string("baltop.sort.bank")
            Sort.TOTAL -> config.string("baltop.sort.total")
        }
        ArcMenus.open(
            player,
            ArcMenuSchema.BALTOP,
            config.component("baltop.title", "<dark_gray>Топ богачей"),
            elements = mapOf(
                "back" to ArcMenus.entry(ArcMenus.item(ArcMenuSchema.BALTOP, "back")) { it.closeInventory() },
                "previous" to ArcMenus.entryWithContext(ArcMenus.item(ArcMenuSchema.BALTOP, "previous")) { it.session.previousPage() },
                "sort" to ArcMenus.entry(ArcMenus.item(ArcMenuSchema.BALTOP, "sort", PaperMenuItemRenderContext(
                    values = mapOf("sort" to TextUtil.mm(sortName, true)),
                ))) { clicker ->
                    if (CooldownManager.cooldown(clicker.uniqueId, "baltop_sort") != 0L) {
                        clicker.sendMessage(mm(config.string("baltop.sort.cooldown"), true))
                    } else {
                        CooldownManager.addCooldown(clicker.uniqueId, "baltop_sort", 1000L)
                        val next = when (sort) {
                            Sort.BALANCE -> Sort.BANK
                            Sort.BANK -> Sort.TOTAL
                            Sort.TOTAL -> Sort.BALANCE
                        }
                        open(config, clicker, next)
                    }
                },
                "next" to ArcMenus.entryWithContext(ArcMenus.item(ArcMenuSchema.BALTOP, "next")) { it.session.nextPage() },
            ),
            regions = mapOf(ArcMenuSchema.BALTOP_ENTRIES to entries),
        )
    }

    /**
     * Update cache if needed and show GUI.
     */
    fun showAsync(
        config: Config,
        player: Player,
        sort: Sort = Sort.TOTAL,
    ) {
        updateCacheIfNeeded()
            .thenRun {
                sync { open(config, player, sort) }
            }.exceptionally { failure ->
                error("Failed to show baltop for {}", player.name, failure.cause ?: failure)
                sync {
                    player.sendMessage(
                        mm(
                            config.string(
                                "baltop.error",
                                "<red>Не удалось загрузить таблицу лидеров. Попробуйте ещё раз позже.",
                            ),
                            true,
                        ),
                    )
                }
                null
            }
    }

    /**
     * Update the cache if it's stale.
     */
    @Synchronized
    private fun updateCacheIfNeeded(): CompletableFuture<Void> {
        val redisEco = HookRegistry.redisEcoHook
        if (redisEco == null) {
            return CompletableFuture.completedFuture(null)
        }

        if (System.currentTimeMillis() - lastUpdate <= 60000) {
            return CompletableFuture.completedFuture(null)
        }
        refreshInFlight?.takeUnless { it.isDone }?.let { return it }

        info("Updating baltop cache")
        val refresh =
            redisEco
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
                lastUpdate = System.currentTimeMillis()
            }
        refreshInFlight = refresh
        refresh.whenComplete { _, _ ->
            synchronized(this) {
                if (refreshInFlight === refresh) {
                    refreshInFlight = null
                }
            }
        }
        return refresh
    }
}
