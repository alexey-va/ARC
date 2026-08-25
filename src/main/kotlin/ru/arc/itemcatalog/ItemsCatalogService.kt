package ru.arc.itemcatalog

import dev.lone.itemsadder.api.Events.ItemsAdderLoadDataEvent
import org.bukkit.Bukkit
import org.bukkit.event.EventHandler
import org.bukkit.event.HandlerList
import org.bukkit.event.Listener
import org.bukkit.plugin.Plugin
import ru.arc.util.Logging.info
import ru.arc.util.Logging.warn
import java.util.concurrent.Executors
import java.util.concurrent.ThreadFactory
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

class ItemsCatalogService(
    private val plugin: Plugin,
    @Volatile private var settings: ItemsCatalogSettings,
    val gateway: ItemsAdderCatalogGateway,
    private val scanner: ItemsAdderCategoryScanner = ItemsAdderCategoryScanner(),
) {
    private val snapshot = AtomicReference<ItemsCatalogSnapshot?>()
    private val refreshRequested = AtomicBoolean(false)
    private val refreshRunning = AtomicBoolean(false)
    private val closed = AtomicBoolean(false)
    private val executor = Executors.newSingleThreadExecutor(CatalogThreadFactory)
    private val listener = ItemsCatalogItemsAdderListener { requestRefresh("itemsadder-load") }

    fun start() {
        Bukkit.getPluginManager().registerEvents(listener, plugin)
        requestRefresh("startup")
    }

    fun currentSnapshot(): ItemsCatalogSnapshot? = snapshot.get()

    fun reconfigure(newSettings: ItemsCatalogSettings) {
        settings = newSettings
        requestRefresh("arc-reload")
    }

    fun requestRefresh(reason: String) {
        if (closed.get()) return
        refreshRequested.set(true)
        if (!refreshRunning.compareAndSet(false, true)) return
        try {
            executor.execute { drainRefreshes(reason) }
        } catch (_: RuntimeException) {
            refreshRunning.set(false)
        }
    }

    fun shutdown() {
        if (!closed.compareAndSet(false, true)) return
        HandlerList.unregisterAll(listener)
        executor.shutdownNow()
        snapshot.set(null)
    }

    private fun drainRefreshes(initialReason: String) {
        var reason = initialReason
        while (!closed.get()) {
            refreshRequested.set(false)
            rebuild(reason)
            reason = "coalesced-refresh"
            if (refreshRequested.get()) continue
            refreshRunning.set(false)
            if (!refreshRequested.get() || !refreshRunning.compareAndSet(false, true)) return
        }
        refreshRunning.set(false)
    }

    private fun rebuild(reason: String) {
        val activeSettings = settings
        if (!activeSettings.enabled || !gateway.available) return
        val contentsRoot = gateway.contentsRoot()
        if (contentsRoot == null) {
            warn("Items catalog refresh skipped because the ItemsAdder contents root is unavailable")
            return
        }

        val registry =
            try {
                gateway.registeredItemIds()
            } catch (_: Exception) {
                warn("Items catalog refresh skipped because the ItemsAdder registry is not ready")
                return
            }
        if (registry.isEmpty() && snapshot.get() == null) {
            info("Items catalog is waiting for ItemsAdder data")
            return
        }

        val scan =
            try {
                scanner.scan(contentsRoot)
            } catch (_: Exception) {
                warn("Items catalog refresh failed while reading ItemsAdder categories; previous snapshot retained")
                return
            }
        val plan =
            try {
                ItemsCatalogPlanner.plan(
                    scan.categories,
                    registry,
                    activeSettings.groups,
                    activeSettings.hiddenCategoryIds,
                    activeSettings.hiddenItemPatterns,
                    scan.recipeResultItemIds,
                )
            } catch (_: Exception) {
                warn("Items catalog refresh failed while planning categories; previous snapshot retained")
                return
            }
        if (closed.get() || activeSettings !== settings) return

        val published = plan.snapshot.copy(issues = (scan.issues + plan.snapshot.issues).distinct())
        snapshot.set(published)
        info(
            "Items catalog refreshed from {} file(s): {} item(s), {} recipe result(s), {} category(ies), {} group(s), {} issue(s); reason={}",
            scan.scannedFiles,
            published.registryItemIds.size,
            published.recipeResultItemIds.size,
            published.categoryCount,
            published.groups.size,
            published.issues.size,
            reason,
        )
    }

    private object CatalogThreadFactory : ThreadFactory {
        override fun newThread(task: Runnable): Thread =
            Thread(task, "ARC-ItemsCatalog-Indexer").apply { isDaemon = true }
    }
}

private class ItemsCatalogItemsAdderListener(
    private val loaded: () -> Unit,
) : Listener {
    @EventHandler
    fun onItemsAdderLoaded(@Suppress("UNUSED_PARAMETER") event: ItemsAdderLoadDataEvent) {
        loaded()
    }
}
