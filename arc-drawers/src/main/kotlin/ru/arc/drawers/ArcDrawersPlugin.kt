package ru.arc.drawers

import net.kyori.adventure.text.Component
import org.bukkit.Chunk
import org.bukkit.Material
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.world.ChunkLoadEvent
import org.bukkit.event.world.ChunkUnloadEvent
import org.bukkit.plugin.java.JavaPlugin
import ru.arc.config.EmptyConfig
import ru.arc.core.BudgetedTaskQueue
import ru.arc.core.PaperArcRuntime
import ru.arc.logging.ArcLogging
import ru.arc.logging.LoggingConfigSource
import ru.arc.logging.paper.PaperLoggingPlatform
import ru.arc.observability.RuntimeHealthContribution
import ru.arc.observability.RuntimeHealthState
import ru.arc.paper.display.PaperPacketDisplays
import ru.arc.paper.runtime.PaperPluginHotload
import ru.arc.paper.runtime.PaperPluginRuntime
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import java.util.logging.Level

class ArcDrawersPlugin : JavaPlugin() {
    private val hotload = PaperPluginHotload(this, ::stopRuntime)
    private var runtime: PaperPluginRuntime? = null
    private var packets: PaperPacketDisplays? = null
    private var drained: CompletionStage<*> = CompletableFuture.completedFuture(Unit)

    override fun onEnable() {
        hotload.beginGeneration()
        hotload.register()
        PaperArcRuntime.installScheduling(this)
        ArcLogging.install(PaperLoggingPlatform(name, name), LoggingConfigSource { EmptyConfig })
        val lifecycle = PaperPluginRuntime(this, "arc-drawers").also { runtime = it }
        lifecycle.start("version" to pluginMeta.version)
        lifecycle.registerHealth("drawers") {
            RuntimeHealthContribution(state = if (packets == null) RuntimeHealthState.STARTING else RuntimeHealthState.UP)
        }
        val work = lifecycle.own(BudgetedTaskQueue(lifecycle.tasks, maximumSteps = 128, maximumNanos = 1_000_000))
        val displays = PaperPacketDisplays(this, "arc-drawers", work).also { packets = it }
        val visuals = DrawerVisuals(displays) { logger.log(Level.WARNING, "Drawer facade update failed", it) }
        val store = DrawerStore(this)
        lifecycle.own(AutoCloseable(store::clear))
        val items = lifecycle.own(DrawerItems(this))
        items.register()
        val discovery = DrawerDiscovery(store, visuals, work)
        server.pluginManager.registerEvents(discovery, this)
        server.pluginManager.registerEvents(DrawerGameplay(store, items, visuals), this)
        server.pluginManager.registerEvents(DrawerHoppers(store, visuals), this)
        lifecycle.tasks.runSync { server.worlds.forEach { world -> world.loadedChunks.forEach(discovery::discover) } }
        val command = requireNotNull(getCommand("arcdrawers"))
        command.setExecutor { sender, _, _, args ->
            if (args.firstOrNull() == "give" && sender.hasPermission("arcdrawers.admin")) {
                give(sender, args, items)
            } else {
                if (sender is Player) sender.discoverRecipes(items.recipeKeys())
                sender.sendMessage(DrawerText.message("help"))
            }
            true
        }
        command.setTabCompleter { sender, _, _, args ->
            val choices = when {
                args.size == 1 && sender.hasPermission("arcdrawers.admin") -> listOf("help", "give")
                args.size == 1 -> listOf("help")
                args.firstOrNull() == "give" && sender.hasPermission("arcdrawers.admin") && args.size == 2 -> null
                args.firstOrNull() == "give" && sender.hasPermission("arcdrawers.admin") && args.size == 3 -> DrawerItem.byId.keys
                else -> emptyList()
            }
            choices?.filter { it.startsWith(args.lastOrNull().orEmpty(), ignoreCase = true) }
        }
        lifecycle.reportHealthEvery(1200L)
        lifecycle.ready("storage" to "chunk-pdc")
    }

    private fun give(sender: CommandSender, args: Array<out String>, items: DrawerItems) {
        val player = args.getOrNull(1)?.let(server::getPlayerExact)
        val type = DrawerItem.byId[args.getOrNull(2)]
        val amount = args.getOrNull(3)?.toIntOrNull() ?: if (args.size == 3) 1 else 0
        if (player == null || type == null || amount !in 1..type.material.maxStackSize || args.size > 4) {
            sender.sendMessage(Component.text("/arcdrawers give <игрок> <предмет> [количество]"))
            return
        }
        giveBack(player, items.create(type, amount))
        sender.sendMessage(DrawerText.message("gave", "item" to DrawerText.message(type.id), "count" to Component.text(amount)))
    }

    private fun stopRuntime(): Map<String, CompletionStage<*>> {
        val lifecycle = runtime
        if (lifecycle != null) {
            runtime = null
            drained = packets?.closeAsync() ?: CompletableFuture.completedFuture(Unit)
            packets = null
            lifecycle.close()
            hotload.retireGameplayRegistrations()
        }
        return mapOf("facades" to drained)
    }

    override fun onDisable() {
        hotload.prepare()
        hotload.close()
    }
}

/** Only loaded chunks have facades. The shared native queue slices discovery of storage walls. */
private class DrawerDiscovery(
    private val store: DrawerStore,
    private val visuals: DrawerVisuals,
    private val work: BudgetedTaskQueue,
) : Listener {
    private data class Address(val world: UUID, val chunk: Long)
    private val pending = HashMap<Address, Any>()

    @EventHandler
    fun loaded(event: ChunkLoadEvent) = discover(event.chunk)

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun unloaded(event: ChunkUnloadEvent) {
        val chunk = event.chunk
        pending.remove(Address(chunk.world.uid, chunk.chunkKey))
        store.cachedBlocks(chunk).forEach(visuals::forget)
    }

    fun discover(chunk: Chunk) {
        val positions = store.positions(chunk).iterator()
        if (!positions.hasNext()) return
        val address = Address(chunk.world.uid, chunk.chunkKey)
        val token = Any()
        pending[address] = token
        work.submit({ pending[address] === token && chunk.isLoaded }) {
            val block = store.blockAt(chunk, positions.next())
            if (block.type == Material.BARREL) {
                store.find(block)?.drawer?.let { visuals.refresh(block, it) }
            }
            !positions.hasNext()
        }.whenComplete { _, _ -> pending.remove(address, token) }
    }
}
