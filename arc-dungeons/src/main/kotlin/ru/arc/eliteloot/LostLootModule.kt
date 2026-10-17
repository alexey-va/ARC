package ru.arc.eliteloot

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.TextDecoration
import org.bukkit.entity.Player
import org.bukkit.event.HandlerList
import org.bukkit.plugin.Plugin
import ru.arc.config.Config
import ru.arc.config.ConfigManager
import ru.arc.core.BoundedAsyncExecutor
import ru.arc.core.LifecycleTaskScope
import ru.arc.core.PluginModule
import ru.arc.core.whenCompleteSync
import ru.arc.dungeons.ArcDungeonsPlugin
import ru.arc.dungeons.api.ArcDungeonConfiguration
import ru.arc.paper.api.ArcNativeCleanupService
import ru.arc.paper.api.ArcSqlService
import ru.arc.paper.chunk.PaperChunkTicketRegistry
import ru.arc.logging.ArcLogging as Logging
import ru.arc.text.ConfigLocaleCatalog
import ru.arc.text.LocalizedMiniMessage
import java.util.concurrent.CancellationException
import java.util.concurrent.CompletableFuture

/** Local durability and host-owned continuity remain independent of optional EliteMobs/SQL readiness. */
object LostLootModule : PluginModule {
    override val name = "LostLoot"
    override val priority = 101
    private data class Prepared(val repository: MySqlSharedLostLootRepository, val config: Config)
    private class Generation(
        val capture: LostEliteLoot,
        val prepared: CompletableFuture<Prepared>,
        val worker: BoundedAsyncExecutor,
    ) {
        val tasks = LifecycleTaskScope()
        val ready = CompletableFuture<Void>()
        var mailbox: SharedLostLootMailbox? = null
    }
    private var current: Generation? = null
    private var drain = CompletableFuture.completedFuture<Void>(null)
    private var continuity: LostLootProtection? = null
    private var continuityHost: Plugin? = null
    private var continuityService: ArcNativeCleanupService? = null
    private var initializationFailure: Throwable? = null
    internal val isReady: Boolean get() = current?.let { it.ready.isDone && !it.ready.isCompletedExceptionally && it.mailbox != null } == true

    fun ready(): CompletableFuture<Void> = current?.ready?.copy()
        ?: CompletableFuture.failedFuture(initializationFailure ?: IllegalStateException("Lost loot is unavailable"))
    fun drained(): CompletableFuture<Void> = drain.copy()

    override fun init() {
        shutdown()
        val plugin = ArcDungeonsPlugin.instance
        val server = plugin.server
        plugin.getCommand("loot")?.setExecutor(LostLootCommand)
        initializationFailure = null
        try {
            val cleanupRegistration = requireNotNull(server.servicesManager.getRegistration(ArcNativeCleanupService::class.java)) {
                "ArcCore native cleanup service is unavailable"
            }
            val host = cleanupRegistration.plugin
            check(host.isEnabled && host !== plugin) { "Lost loot continuity requires the enabled ArcCore host" }
            val protection = if (continuityHost === host && continuityService === cleanupRegistration.provider) checkNotNull(continuity)
            else (continuity?.handoff() ?: LostLootProtection()).also { next ->
                // Register the replacement before releasing old protection: no pickup gap at handoff.
                server.pluginManager.registerEvents(next, host)
                continuity?.let(HandlerList::unregisterAll)
                continuity = next
                continuityHost = host
                continuityService = cleanupRegistration.provider
            }
            protection.starting()
            val sqlService = server.servicesManager.load(ArcSqlService::class.java)
            val settings = server.servicesManager.load(ArcDungeonConfiguration::class.java)?.lostLootSqlSettings()
            val origin = requireNotNull(plugin.serverName).also { require(it.isNotBlank()) }
            val root = plugin.legacyDataPath
            val predecessor = drain
            // This accepted future is retained even when disable precedes its completion. Cold
            // SQL never occupies the journal lane and never reads Bukkit services on the worker.
            val prepared = plugin.prepareBackground {
                predecessor.join()
                val config = ConfigManager.of(root, "modules/elitemobs.yml")
                Prepared(MySqlSharedLostLootRepository.open(
                    requireNotNull(sqlService) { "ARC SQL service is unavailable" },
                    requireNotNull(settings) { "Shared lost loot requires network SQL settings in modules/audit.yml" },
                ), config)
            }
            val worker = BoundedAsyncExecutor("lost-loot-outbox")
            val outbox = LostLootOutbox(
                { predecessor.join(); LostLootStore(root) },
                { record -> prepared.thenCompose { it.repository.publish(record, origin) } }, worker,
            )
            fun nativeAvailable() = server.pluginManager.isPluginEnabled("EliteMobs")
            val capture = LostEliteLoot(server, outbox, plugin.nativeWork, cleanupRegistration.provider,
                PaperChunkTicketRegistry(plugin), protection,
                isElite = { nativeAvailable() && NativeLostLootCredit.isElite(it) },
                mobSource = { if (nativeAvailable()) NativeLostLootCredit.mobSource() else "" },
                nativePrice = { if (nativeAvailable()) NativeLostLootCredit.resale(it) else null })
            val owner = Generation(capture, prepared, worker)
            current = owner
            server.pluginManager.registerEvents(capture, plugin)
            val localReady = capture.start()
            val sqlReady = prepared.thenCompose { it.repository.ready() }
            CompletableFuture.allOf(localReady, sqlReady).whenCompleteSync(owner.tasks) { _, failure ->
                if (current !== owner) return@whenCompleteSync
                if (failure != null) {
                    owner.ready.completeExceptionally(failure)
                    Logging.error("Shared lost loot unavailable; local capture remains independent and protected", failure)
                    return@whenCompleteSync
                }
                try {
                    val candidate = prepared.getNow(null) ?: error("Lost loot preparation did not complete")
                    val localized = LocalizedMiniMessage(
                        catalogs = mapOf("ru" to ConfigLocaleCatalog(candidate.config)), defaultLocale = { "ru" },
                        prefixPath = "_arc_dungeons_no_prefix", missingMessage = { "" },
                    )
                    val gateway = object : LostLootCreditGateway {
                        override fun ready(player: Player) = nativeAvailable() && NativeLostLootCredit.ready(player)
                        override fun hasReceipt(player: Player, token: String) = ready(player) && NativeLostLootCredit.hasReceipt(player, token)
                        override fun credit(player: Player, amount: Double, token: String): CompletableFuture<Unit> {
                            check(ready(player)); return NativeLostLootCredit.credit(player, amount, token)
                        }
                        override fun persistReceipt(player: Player): CompletableFuture<Unit> {
                            check(ready(player)); return NativeLostLootCredit.persistReceipt(player)
                        }
                    }
                    val mailbox = SharedLostLootMailbox(candidate.repository, origin,
                        { player, key, fallback ->
                            val path = "dungeon-qol.lost-loot.$key"
                            val message = if (candidate.config.stringOrNull(path) == null) candidate.config.component(path, fallback)
                            else localized.render(path, player.locale().toLanguageTag())
                            message.decoration(TextDecoration.ITALIC, false)
                        }, gateway, nativeWork = plugin.nativeWork, worker = worker)
                    owner.mailbox = mailbox
                    server.pluginManager.registerEvents(mailbox, plugin)
                    mailbox.start()
                    owner.ready.complete(null)
                } catch (attachFailure: Throwable) {
                    owner.ready.completeExceptionally(attachFailure)
                    Logging.error("Shared lost loot mailbox attachment failed; local outbox retained", attachFailure)
                }
            }
        } catch (failure: Throwable) {
            initializationFailure = failure
            shutdown()
            Logging.error("Shared lost loot initialization failed; owned drops remain protected", failure)
        }
    }

    fun open(player: Player): Boolean {
        val mailbox = current?.mailbox
        if (mailbox == null) { player.sendMessage(Component.text("Хранилище добычи временно недоступно.")); return false }
        mailbox.open(player)
        return true
    }

    override fun shutdown() {
        val owner = current ?: return
        current = null
        owner.ready.completeExceptionally(CancellationException("Lost loot generation stopped"))
        owner.tasks.close()
        HandlerList.unregisterAll(owner.capture)
        owner.mailbox?.let { HandlerList.unregisterAll(it); it.close() }
        owner.mailbox = null
        // Local capture owns the terminal lane. Never enqueue late close onto the parent's sealed
        // executor: its already-accepted cold acquisition completes before this worker disposes it.
        drain = owner.capture.closeAsync {
            val prepared = try { owner.prepared.join() } catch (_: Throwable) { null }
            prepared?.repository?.close()
        }
        drain.whenComplete { _, failure ->
            if (failure != null) Logging.error("Lost loot generation did not drain cleanly", failure)
        }
    }
}
