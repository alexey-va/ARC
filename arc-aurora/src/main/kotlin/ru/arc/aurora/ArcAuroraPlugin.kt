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
import java.util.Collections
import java.util.Locale
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.logging.Level

open class ArcAuroraPlugin : JavaPlugin(), Listener {
    private var runtime: PaperPluginRuntime? = null
    internal var engine: AuroraEngine? = null; private set
    private var pendingEngine: AuroraEngine? = null
    private var messages: LocalizedMiniMessage? = null
    private val events = linkedMapOf<UUID, LinkedHashMap<UUID, String>>()
    private class Preview(profiles: List<AuroraProfile>) {
        val profiles: List<AuroraProfile> = Collections.unmodifiableList(profiles.toList())
        var timer: ScheduledTask? = null
    }
    private val previews = mutableMapOf<UUID, Preview>()
    @Volatile private var eventCount = 0
    @Volatile private var previewCount = 0
    private var retirement = CompletableFuture.completedFuture<Void>(null)
    private val hotload = PaperPluginHotload(this) {
        stopRuntime()
        mapOf("visuals" to retirement)
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
            installEngine(prepareEngine(settings)) {
                owner.ready("profiles" to settings.profiles.size, "permission" to settings.permission,
                    "night_sky" to settings.nightSky.enabled, "night_preset" to settings.nightSky.preset)
            }
            server.pluginManager.registerEvents(this, this)
            server.servicesManager.register(ArcAuroraService::class.java, object : ArcAuroraService {
                override fun startWorldEvent(worldId: UUID, preset: String): AutoCloseable = beginEvent(worldId, preset)
            }, this, ServicePriority.Normal)
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
        // Item registries belong to Paper startup/reload, never detached geometry or config parsing.
        (settings.profiles + settings.eventPresets.values.flatten()).forEach { profile ->
            profile.skyModel?.let { model ->
                require(model.material.isItem) { "${profile.id}.sky-model.material must be an item material" }
            }
        }
        checkNotNull(server.servicesManager.load(ArcVisualPacketBudget::class.java)) {
            "ArcCore visual packet budget is not ready"
        }
        val replacement = AuroraEngine(this, owner.tasks, settings, eventProfiles = { world ->
            events[world]?.values?.lastOrNull()?.let { engine?.settings?.eventPresets?.get(it) }
        }, previewProfiles = { playerId ->
            previews[playerId]?.profiles
        })
        try {
            server.onlinePlayers.forEach(replacement::join) // startup/manual maintenance, no online-player scan on tick
        } catch (failure: Exception) {
            replacement.close()
            throw failure
        }
        return replacement
    }

    private fun installEngine(replacement: AuroraEngine, ready: () -> Unit) {
        val owner = checkNotNull(runtime)
        engine?.let { retirement = CompletableFuture.allOf(retirement, it.closeAsync()) }
        engine = null
        pendingEngine = replacement
        fun activate() {
            if (runtime !== owner || pendingEngine !== replacement) return
            try {
                retirement.join() // Already complete; failure must prevent a successor becoming ready.
                pendingEngine = null
                engine = replacement
                ready()
            } catch (failure: Exception) {
                logger.log(Level.SEVERE, "ArcAurora visual retirement failed; successor will not start", failure)
                stopRuntime()
            }
        }
        if (retirement.isDone) activate()
        else retirement.whenComplete { _, _ -> owner.tasks.runSync { activate() } }
        owner.tasks.runTimer(1L, 1L) {
            if (engine !== replacement) return@runTimer
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

    @EventHandler fun onJoin(event: PlayerJoinEvent) { (engine ?: pendingEngine)?.join(event.player) }
    @EventHandler fun onQuit(event: PlayerQuitEvent) { stopPreview(event.player.uniqueId); (engine ?: pendingEngine)?.quit(event.player.uniqueId) }
    @EventHandler fun onWorld(event: PlayerChangedWorldEvent) { (engine ?: pendingEngine)?.invalidate(event.player.uniqueId) }
    @EventHandler(ignoreCancelled = true) fun onTeleport(event: PlayerTeleportEvent) {
        if (event.from.world?.uid != event.to.world?.uid) engine?.invalidate(event.player.uniqueId)
    }

    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<out String>): Boolean {
        val action = args.firstOrNull()?.lowercase(Locale.ROOT) ?: "toggle"
        if (action in setOf("reload", "status", "preview", "catalog")) {
            if (!sender.hasPermission("arcaurora.admin")) return reply(sender, "no-permission")
            if (action == "catalog") {
                val current = engine ?: return reply(sender, "stopped")
                if (args.size > 2) return reply(sender, "usage")
                val preset = args.getOrNull(1) ?: return reply(sender, "catalog-presets", mapOf(
                    "presets" to current.settings.eventPresets.keys.sorted().joinToString(", "),
                ))
                val profiles = current.settings.eventPresets[preset]
                    ?: return reply(sender, "preset-not-found", mapOf("preset" to preset))
                return reply(sender, "catalog-profiles", mapOf(
                    "preset" to preset, "profiles" to profiles.map { it.id }.sorted().joinToString(", "),
                ))
            }
            if (action == "preview") {
                val current = engine ?: return reply(sender, "stopped")
                if (args.size > 4) return reply(sender, "usage")
                val targetName = args.getOrNull(1)
                val target = if (targetName == null || targetName.equals("self", ignoreCase = true))
                    sender as? Player ?: return reply(sender, "players-only")
                else server.getPlayerExact(targetName)
                    ?: return reply(sender, "player-not-found", mapOf("player" to targetName))
                val explicit = args.size >= 3
                if (!explicit && previews.containsKey(target.uniqueId)) {
                    stopPreview(target.uniqueId)
                    return reply(sender, "preview-off")
                }
                val preset = args.getOrNull(2) ?: "treasure-hunt"
                val profiles = current.settings.eventPresets[preset] ?: return if (explicit)
                    reply(sender, "preset-not-found", mapOf("preset" to preset)) else reply(sender, "preview-missing")
                val profileId = args.getOrNull(3)
                val selected = if (profileId == null) profiles else listOf(profiles.firstOrNull { it.id == profileId }
                    ?: return reply(sender, "profile-not-found", mapOf("preset" to preset, "profile" to profileId)))
                val preview = Preview(selected)
                val targetId = target.uniqueId
                stopPreview(targetId)
                previews[targetId] = preview
                previewCount = previews.size
                current.invalidate(targetId)
                preview.timer = runtime!!.tasks.runLater(1200L) {
                    if (previews[targetId] !== preview) return@runLater
                    val remaining = engine?.beginPreviewDrain(targetId, preview.profiles) ?: 0L
                    if (remaining <= 0L) stopPreview(targetId)
                    else {
                        preview.timer?.cancel()
                        preview.timer = runtime!!.tasks.runLater(remaining) {
                            if (previews[targetId] === preview) stopPreview(targetId)
                        }
                    }
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
                messages = candidateMessages
                installEngine(replacement) {
                    runtime!!.ready("profiles" to candidate.profiles.size,
                        "night_sky" to candidate.nightSky.enabled, "night_preset" to candidate.nightSky.preset)
                }
                return reply(sender, "reloaded")
            } catch (failure: Exception) {
                logger.log(Level.WARNING, "ArcAurora reload rejected; existing settings remain active", failure)
                return reply(sender, "reload-failed", mapOf("reason" to (failure.message ?: failure.javaClass.simpleName)))
            }
        }
        val current = engine ?: return reply(sender, "stopped")
        if (sender !is Player) return reply(sender, "players-only")
        val personalAction = action in setOf("on", "off", "toggle")
        if (!sender.hasPermission(current.settings.permission) && !(personalAction && current.settings.nightSky.enabled))
            return reply(sender, "no-permission")
        if (action == "biome") return reply(sender, "biome", mapOf("biome" to sender.location.block.biome.key.toString()))
        if (!personalAction) return reply(sender, "usage")
        val enabled = when (action) { "on" -> true; "off" -> false; else -> !current.personallyEnabled(sender) }
        current.setPersonallyEnabled(sender, enabled)
        return reply(sender, if (enabled) "enabled" else "disabled")
    }

    override fun onTabComplete(sender: CommandSender, command: Command, alias: String, args: Array<out String>): List<String> {
        val admin = sender.hasPermission("arcaurora.admin")
        val candidates = if (args.size <= 1) buildList {
            if (sender is Player) addAll(listOf("on", "off", "toggle", "biome"))
            if (admin) addAll(listOf("reload", "status", "catalog", "preview"))
        } else {
            if (!admin) return emptyList()
            val settings = engine?.settings ?: return emptyList()
            when (args[0].lowercase(Locale.ROOT)) {
                "catalog" -> if (args.size == 2) settings.eventPresets.keys.toList() else emptyList()
                "preview" -> when (args.size) {
                    2 -> server.onlinePlayers.map { it.name } + if (sender is Player) listOf("self") else emptyList()
                    3 -> settings.eventPresets.keys.toList()
                    4 -> settings.eventPresets[args[2]]?.map { it.id }.orEmpty()
                    else -> emptyList()
                }
                else -> emptyList()
            }
        }
        val prefix = args.lastOrNull().orEmpty()
        return candidates.distinct().sorted().filter { it.startsWith(prefix, ignoreCase = true) }
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
        engine?.let { retirement = CompletableFuture.allOf(retirement, it.closeAsync()) }; engine = null
        pendingEngine?.let { retirement = CompletableFuture.allOf(retirement, it.closeAsync()) }; pendingEngine = null
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
