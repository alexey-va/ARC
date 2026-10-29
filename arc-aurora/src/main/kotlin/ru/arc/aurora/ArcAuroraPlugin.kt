package ru.arc.aurora

import net.kyori.adventure.text.Component
import org.bukkit.Bukkit
import org.bukkit.command.Command
import org.bukkit.command.CommandSender
import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerChangedWorldEvent
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.event.player.PlayerTeleportEvent
import org.bukkit.plugin.ServicePriority
import org.bukkit.plugin.java.JavaPlugin
import ru.arc.aurora.api.ArcAuroraService
import ru.arc.config.Config
import ru.arc.core.PaperArcRuntime
import ru.arc.core.Tasks
import ru.arc.core.ScheduledTask
import ru.arc.logging.ArcLogging
import ru.arc.logging.LoggingConfigSource
import ru.arc.logging.paper.PaperLoggingPlatform
import ru.arc.observability.RuntimeHealthContribution
import ru.arc.observability.RuntimeHealthState
import ru.arc.paper.runtime.PaperPluginRuntime
import ru.arc.paper.runtime.PaperPluginHotload
import ru.arc.paper.api.ArcVisualPacketBudget
import ru.arc.text.ConfigLocaleCatalog
import ru.arc.text.LocalizedMiniMessage
import java.nio.file.Files
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.logging.Level

open class ArcAuroraPlugin : JavaPlugin(), Listener {
    private var runtime: PaperPluginRuntime? = null
    internal var engine: AuroraEngine? = null; private set
    private var messages: LocalizedMiniMessage? = null
    private val events = linkedMapOf<UUID, LinkedHashMap<UUID, String>>()
    private class Preview(val preset: String) { var timer: ScheduledTask? = null }
    private val previews = mutableMapOf<UUID, Preview>()
    @Volatile private var eventCount = 0
    @Volatile private var previewCount = 0
    private val hotload = PaperPluginHotload(this) {
        stopRuntime()
        mapOf("particles" to CompletableFuture.completedFuture(Unit))
    }

    override fun onEnable() {
        try {
            hotload.beginGeneration()
            hotload.register()
            checkNotNull(getCommand("arcaurora")).setExecutor(this)
            getCommand("arcaurora")?.tabCompleter = this
            val settings = loadSettings()
            messages = loadMessages()
            ArcLogging.install(PaperLoggingPlatform("ArcAurora"), LoggingConfigSource { ru.arc.config.EmptyConfig })
            PaperArcRuntime.installScheduling(this)
            val owner = PaperPluginRuntime(this).also { runtime = it; it.start() }
            owner.registerHealth("ambient") {
                RuntimeHealthContribution(
                    state = if (engine != null) RuntimeHealthState.UP else RuntimeHealthState.DOWN,
                    activeLeases = eventCount + previewCount,
                    dependencies = mapOf("packet-budget" to (engine != null)),
                )
            }
            installEngine(prepareEngine(settings))
            server.pluginManager.registerEvents(this, this)
            server.servicesManager.register(ArcAuroraService::class.java, object : ArcAuroraService {
                override fun startWorldEvent(worldId: UUID, preset: String): AutoCloseable = beginEvent(worldId, preset)
            }, this, ServicePriority.Normal)
            owner.ready("profiles" to settings.profiles.size, "permission" to settings.permission)
        } catch (failure: Exception) {
            logger.log(Level.SEVERE, "ArcAurora startup failed; no ambient particles will be sent", failure)
            server.pluginManager.disablePlugin(this)
        }
    }

    /** Explicit startup/manual reload maintenance IO; the tick/render path never reads files. */
    private fun loadSettings(): AuroraSettings {
        if (!dataFolder.resolve("config.yml").isFile) saveResource("config.yml", false)
        Config(dataFolder.toPath(), "config.yml").mergeMissingFromBundled("config.yml", setOf("profiles", "biome-aliases", "event-presets"))
        val yaml = YamlConfiguration().apply { loadFromString(Files.readString(dataFolder.resolve("config.yml").toPath())) }
        return AuroraConfig.parse(yaml)
    }

    private fun loadMessages(): LocalizedMiniMessage {
        val catalogs = listOf("ru", "en").associateWith { locale ->
            val file = "locales/$locale.yml"
            if (!dataFolder.resolve(file).isFile) saveResource(file, false)
            Config(dataFolder.toPath(), file).also { it.mergeMissingFromBundled(file) }.let(::ConfigLocaleCatalog)
        }
        return LocalizedMiniMessage(catalogs, { "ru" })
    }

    private fun prepareEngine(settings: AuroraSettings): AuroraEngine {
        val owner = checkNotNull(runtime)
        checkNotNull(server.servicesManager.load(ArcVisualPacketBudget::class.java)) {
            "ArcCore visual packet budget is not ready"
        }
        val replacement = AuroraEngine(this, owner.tasks, settings, eventProfiles = { world ->
            events[world]?.values?.lastOrNull()?.let { engine?.settings?.eventPresets?.get(it) }
        }, previewProfiles = { playerId ->
            previews[playerId]?.let { engine?.settings?.eventPresets?.get(it.preset) }
        })
        try {
            server.onlinePlayers.forEach(replacement::join) // startup/manual maintenance, no online-player scan on tick
        } catch (failure: Exception) {
            replacement.close()
            throw failure
        }
        return replacement
    }

    private fun installEngine(replacement: AuroraEngine) {
        val owner = checkNotNull(runtime)
        engine?.close()
        engine = replacement
        owner.tasks.runTimer(1L, 1L) {
            try { replacement.tick() }
            catch (failure: Exception) {
                logger.log(Level.SEVERE, "ArcAurora render failed; stopping its runtime to prevent repeated failures", failure)
                stopRuntime()
            }
        }
        owner.reportHealthEvery(600L)
    }

    private fun beginEvent(worldId: UUID, preset: String): AutoCloseable {
        check(Bukkit.isPrimaryThread()) { "ArcAurora events must change on Paper's primary thread" }
        val current = checkNotNull(engine) { "ArcAurora runtime is stopped" }
        require(Bukkit.getWorld(worldId) != null) { "Unknown world: $worldId" }
        require(current.settings.eventPresets.containsKey(preset)) { "Unknown ArcAurora event preset: $preset" }
        require(eventCount < 128) { "ArcAurora event lease limit reached" }
        val token = UUID.randomUUID()
        val leases = events.getOrPut(worldId) { linkedMapOf() }
        leases[token] = preset
        eventCount++
        current.invalidateWorld(worldId)
        return AutoCloseable {
            check(Bukkit.isPrimaryThread()) { "ArcAurora events must close on Paper's primary thread" }
            if (leases.remove(token) != null && events[worldId] === leases) {
                eventCount--
                if (leases.isEmpty()) events.remove(worldId)
                engine?.invalidateWorld(worldId)
            }
        }
    }

    @EventHandler fun onJoin(event: PlayerJoinEvent) { engine?.join(event.player) }
    @EventHandler fun onQuit(event: PlayerQuitEvent) { stopPreview(event.player.uniqueId); engine?.quit(event.player.uniqueId) }
    @EventHandler fun onWorld(event: PlayerChangedWorldEvent) { engine?.invalidate(event.player.uniqueId) }
    @EventHandler(ignoreCancelled = true) fun onTeleport(event: PlayerTeleportEvent) {
        if (event.from.world?.uid != event.to.world?.uid) engine?.invalidate(event.player.uniqueId)
    }

    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<out String>): Boolean {
        val action = args.firstOrNull()?.lowercase() ?: "toggle"
        if (action in setOf("reload", "status", "preview")) {
            if (!sender.hasPermission("arcaurora.admin")) return reply(sender, "no-permission")
            if (action == "preview") {
                val current = engine ?: return reply(sender, "stopped")
                if (args.size == 1 && sender !is Player) return reply(sender, "players-only")
                val target = args.getOrNull(1)?.let(server::getPlayerExact) ?: if (args.size == 1) sender as? Player else null
                if (target == null) return reply(sender, "player-not-found", mapOf("player" to args.getOrNull(1).orEmpty()))
                if (previews.containsKey(target.uniqueId)) {
                    stopPreview(target.uniqueId)
                    return reply(sender, "preview-off")
                }
                if (!current.settings.eventPresets.containsKey("treasure-hunt")) return reply(sender, "preview-missing")
                val preview = Preview("treasure-hunt")
                val targetId = target.uniqueId
                previews[targetId] = preview
                previewCount = previews.size
                current.invalidate(targetId)
                preview.timer = runtime!!.tasks.runLater(1200L) {
                    if (previews[targetId] === preview) stopPreview(targetId)
                }
                if (sender !== target) reply(sender, "preview-on")
                return reply(target, "preview-on")
            }
            if (action == "status") return reply(sender, "status", mapOf(
                "profiles" to (engine?.settings?.profiles?.size ?: 0).toString(),
                "events" to eventCount.toString(), "viewers" to (engine?.viewerCount ?: 0).toString(),
            ))
            if (engine == null) return reply(sender, "stopped")
            try {
                val candidate = loadSettings()
                require(events.values.flatMap { it.values }.all(candidate.eventPresets::containsKey)) {
                    "A running event preset cannot be removed"
                }
                val candidateMessages = loadMessages()
                val replacement = prepareEngine(candidate)
                previews.keys.toList().forEach(::stopPreview)
                runtime!!.reload()
                installEngine(replacement)
                messages = candidateMessages
                runtime!!.ready("profiles" to candidate.profiles.size)
                return reply(sender, "reloaded")
            } catch (failure: Exception) {
                logger.log(Level.WARNING, "ArcAurora reload rejected; existing settings remain active", failure)
                return reply(sender, "reload-failed", mapOf("reason" to (failure.message ?: failure.javaClass.simpleName)))
            }
        }
        val current = engine ?: return reply(sender, "stopped")
        if (sender !is Player) return reply(sender, "players-only")
        if (!sender.hasPermission(current.settings.permission)) return reply(sender, "no-permission")
        if (action == "biome") return reply(sender, "biome", mapOf("biome" to sender.location.block.biome.key.toString()))
        if (action !in setOf("on", "off", "toggle")) return reply(sender, "usage")
        val enabled = when (action) { "on" -> true; "off" -> false; else -> !current.personallyEnabled(sender) }
        current.setPersonallyEnabled(sender, enabled)
        return reply(sender, if (enabled) "enabled" else "disabled")
    }

    private fun stopPreview(playerId: UUID) {
        previews.remove(playerId)?.let { it.timer?.cancel(); engine?.invalidate(playerId) }
        previewCount = previews.size
    }

    private fun reply(sender: CommandSender, key: String, values: Map<String, String> = emptyMap()): Boolean {
        val locale = if (sender is Player && sender.locale().language != "ru") "en" else "ru"
        sender.sendMessage(messages?.render(key, locale, values.mapValues { Component.text(it.value) }) ?: Component.text("ArcAurora: $key"))
        return true
    }

    private fun stopRuntime() {
        val owner = runtime
        runtime = null
        owner?.tasks?.close()
        previews.keys.toList().forEach(::stopPreview)
        engine?.close(); engine = null
        events.clear(); eventCount = 0
        server.servicesManager.unregisterAll(this)
        org.bukkit.event.HandlerList.unregisterAll(this as org.bukkit.plugin.Plugin)
        owner?.close()
        Tasks.reset()
        ArcLogging.close()
        hotload.retireGameplayRegistrations()
    }

    override fun onDisable() { hotload.prepare(); hotload.close(); messages = null }
}
