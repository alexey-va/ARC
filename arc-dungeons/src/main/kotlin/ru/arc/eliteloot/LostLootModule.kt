package ru.arc.eliteloot

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.TextDecoration
import org.bukkit.Bukkit
import org.bukkit.entity.Player
import org.bukkit.event.HandlerList
import ru.arc.dungeons.ArcDungeonsPlugin
import ru.arc.config.ConfigManager
import ru.arc.core.PluginModule
import ru.arc.logging.ArcLogging as Logging
import ru.arc.text.ConfigLocaleCatalog
import ru.arc.text.LocalizedMiniMessage
import java.util.concurrent.CompletableFuture

/** Network access is independent of the optional native EliteMobs plugin. */
object LostLootModule : PluginModule {
    override val name = "LostLoot"
    override val priority = 101
    private var repository: SharedLostLootRepository? = null
    private var capture: LostEliteLoot? = null
    private var mailbox: SharedLostLootMailbox? = null
    private fun nativeAvailable() = Bukkit.getPluginManager().isPluginEnabled("EliteMobs")

    override fun init() {
        shutdown()
        ArcDungeonsPlugin.instance.getCommand("loot")?.setExecutor(LostLootCommand)
        try {
            val config = ConfigManager.of(ArcDungeonsPlugin.instance.legacyDataPath, "modules/elitemobs.yml")
            val localized = LocalizedMiniMessage(
                catalogs = mapOf("ru" to ConfigLocaleCatalog(config)),
                defaultLocale = { "ru" },
                prefixPath = "_arc_dungeons_no_prefix",
                missingMessage = { "" },
            )
            val server = requireNotNull(ArcDungeonsPlugin.instance.serverName).also { require(it.isNotBlank()) }
            val shared = MySqlSharedLostLootRepository.open(requireNotNull(Bukkit.getServicesManager()
                .load(ru.arc.dungeons.api.ArcDungeonConfiguration::class.java)?.lostLootSqlSettings()) {
                "Shared lost loot requires the network SQL connection in modules/audit.yml"
            })
            repository = shared
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
            mailbox = SharedLostLootMailbox(shared, server,
                { player, key, fallback ->
                    val path = "dungeon-qol.lost-loot.$key"
                    val message = if (config.stringOrNull(path) == null) config.component(path, fallback)
                    else localized.render(path, player.locale().toLanguageTag())
                    message.decoration(TextDecoration.ITALIC, false)
                }, gateway)
                .also { Bukkit.getPluginManager().registerEvents(it, ArcDungeonsPlugin.instance); it.start() }
            capture = LostEliteLoot(ArcDungeonsPlugin.instance.legacyDataPath, { shared.publish(it, server) },
                isElite = { nativeAvailable() && NativeLostLootCredit.isElite(it) },
                mobSource = { if (nativeAvailable()) NativeLostLootCredit.mobSource() else "" },
                nativePrice = { if (nativeAvailable()) NativeLostLootCredit.resale(it) else null })
                .also { Bukkit.getPluginManager().registerEvents(it, ArcDungeonsPlugin.instance); it.start() }
        } catch (failure: Exception) {
            shutdown()
            Logging.error("Shared lost loot initialization failed; local capture outbox remains on disk", failure)
        }
    }

    fun open(player: Player): Boolean {
        val current = mailbox
        if (current == null) { player.sendMessage(Component.text("Хранилище добычи временно недоступно.")); return false }
        current.open(player)
        return true
    }

    override fun shutdown() {
        capture?.let { HandlerList.unregisterAll(it); it.close() }; capture = null
        mailbox?.let { HandlerList.unregisterAll(it); it.close() }; mailbox = null
        repository?.close(); repository = null
    }
}
