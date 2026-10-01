package ru.arc.slimefunmenu

import com.bgsoftware.superiorskyblock.api.SuperiorSkyblockAPI
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.TextDecoration
import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.command.Command
import org.bukkit.command.CommandExecutor
import org.bukkit.command.CommandSender
import org.bukkit.command.PluginCommand
import org.bukkit.command.TabCompleter
import org.bukkit.entity.Player
import ru.arc.ARC
import ru.arc.config.ArcRuntimeProfile
import ru.arc.config.Config
import ru.arc.config.ConfigManager
import ru.arc.core.PluginModule
import ru.arc.gui.ArcMenus
import ru.arc.paper.menu.PaperDialogActionId
import ru.arc.paper.menu.PaperDialogBody
import ru.arc.paper.menu.PaperDialogButton
import ru.arc.paper.menu.PaperDialogClickContext
import ru.arc.paper.menu.PaperDialogInputId
import ru.arc.paper.menu.PaperDialogScreen
import ru.arc.paper.menu.PaperDialogTextInput
import ru.arc.util.Logging.warn
import ru.arc.util.TextUtil

/** Native dialogue entry points for the Slimefun SkyBlock backend. */
object SlimefunMenuModule : PluginModule {
    override val name = "SlimefunMenu"
    override val priority = 85

    private var config: Config? = null

    override fun init() = reload()

    override fun reload() {
        config = ConfigManager.of(ARC.instance.dataPath, RESOURCE).also { it.mergeMissingFromBundled(RESOURCE) }
    }

    override fun shutdown() {
        config = null
    }

    fun open(player: Player) {
        if (!ready(player)) return
        if (!ensureConfig(player)) return
        ArcMenus.beginDialogFlow(player)
        openRoot(player)
    }

    fun handleCommand(player: Player, args: Array<String>) {
        if (!ready(player)) return
        if (!ensureConfig(player)) return
        if (args.size > 1) {
            player.sendMessage(text("usage"))
            return
        }

        when (args.firstOrNull()?.lowercase()) {
            null -> open(player)
            "guide" -> {
                ArcMenus.beginDialogFlow(player)
                openRoot(player)
                openGuide(player)
            }
            "shop" -> runLocalRoute(player, ROUTES.first { it.id == "shop" })
            "hub" -> teleportToHub(player)
            "spawn" -> sendToNetworkSpawn(player)
            else -> player.sendMessage(text("usage"))
        }
    }

    internal fun screen(player: Player): PaperDialogScreen = rootScreen(islandState(player))

    internal fun islandActionIds(hasIsland: Boolean): List<String> =
        if (hasIsland) OWN_ISLAND_ACTIONS else NO_ISLAND_ACTIONS

    internal fun curatedStarterIds(configured: List<String>, available: Set<String>): List<String> =
        configured.asSequence()
            .map { it.trim() }
            .filter { STARTER_ID.matches(it) && it in available }
            .distinct()
            .take(MAX_STARTER_CHOICES)
            .toList()

    private fun ready(player: Player): Boolean =
        player.isOnline && ARC.instance.isEnabled && ARC.instance.runtimeProfile == ArcRuntimeProfile.SLIMEFUN

    private fun ensureConfig(player: Player): Boolean {
        if (config != null) return true
        player.sendMessage(text("unavailable"))
        return false
    }

    private fun openRoot(player: Player) {
        ArcMenus.openDialog(player, rootScreen(islandState(player)), reopen = { openRoot(player) })
    }

    private fun rootScreen(hasIsland: Boolean?): PaperDialogScreen {
        val islandButtons = hasIsland?.let { islandActionIds(it).map(::islandButton) }.orEmpty()
        val stateText = when (hasIsland) {
            true -> text("state.own-island")
            false -> text("state.no-island")
            null -> text("state.unavailable")
        }
        val sharedButtons = listOf(
            button("guide", "buttons.guide", close = false) { openGuide(it.player) },
            button("shop", "buttons.shop", close = true) { runLocalRoute(it.player, ROUTES.first { route -> route.id == "shop" }) },
            button("hub", "buttons.hub", close = true) { teleportToHub(it.player) },
            button("network_spawn", "buttons.network-spawn", close = true) { sendToNetworkSpawn(it.player) },
            button("services", "buttons.services", close = false) { openServices(it.player) },
        )
        return PaperDialogScreen(
            id = "slimefun.skyblock.root",
            title = text("title"),
            body = listOf(PaperDialogBody(text("intro"), width = 420), PaperDialogBody(stateText, width = 420)),
            buttons = islandButtons + sharedButtons,
            columns = 2,
        )
    }

    private fun openGuide(player: Player) {
        ArcMenus.openDialog(
            player,
            PaperDialogScreen(
                id = "slimefun.skyblock.guide",
                title = text("guide.title"),
                body = listOf(
                    PaperDialogBody(text("guide.body"), width = 420),
                    PaperDialogBody(text("guide.progression"), width = 420),
                ),
                buttons = listOf(
                    button("guide_catalog", "guide.catalog", close = true) {
                        runLocalRoute(it.player, ROUTES.first { route -> route.id == "guide" })
                    },
                ),
                columns = 1,
            ),
            reopen = { openGuide(player) },
        )
    }

    private fun openVisit(player: Player, initialPlayerName: String = "", invalidPlayerName: Boolean = false) {
        ArcMenus.openDialog(
            player,
            PaperDialogScreen(
                id = "slimefun.skyblock.visit",
                title = text("visit.title"),
                body = listOf(PaperDialogBody(text(if (invalidPlayerName) "visit.invalid-body" else "visit.body"), width = 420)),
                inputs = listOf(PaperDialogTextInput(VISIT_INPUT, text("visit.player-input"), initial = initialPlayerName, maxLength = 16, width = 250)),
                buttons = listOf(
                    contextButton("visit_submit", "visit.submit", close = false) { context ->
                        val target = context.text(VISIT_INPUT).orEmpty().trim()
                        if (!PLAYER_NAME.matches(target)) {
                            openVisit(player, initialPlayerName = target, invalidPlayerName = true)
                        } else {
                            ArcMenus.closeDialog(player)
                            runIslandCommand(player, "visit", target)
                        }
                    },
                ),
                columns = 1,
            ),
            reopen = { openVisit(player, initialPlayerName, invalidPlayerName) },
        )
    }

    private fun openStarterIslands(player: Player) {
        val source = config ?: run {
            player.sendMessage(text("unavailable"))
            return
        }
        if (!ssbAvailable()) {
            player.sendMessage(text("unavailable"))
            return
        }
        val starterIds = runCatching {
            curatedStarterIds(
                configured = source.stringList("starter-islands", emptyList()),
                available = SuperiorSkyblockAPI.getSchematics().schematics.toSet(),
            )
        }.getOrElse { failure ->
            warn("Could not load SuperiorSkyblock starter schematics for {}: {}", player.name, failure.message)
            emptyList()
        }
        val starterButtons = starterIds.mapIndexed { index, id ->
            PaperDialogButton(
                id = PaperDialogActionId.of("starter_$index"),
                label = text("starters.$id.label", "<#9bd48d>Стартовый остров ${index + 1} ›</#9bd48d>"),
                tooltip = text("starters.$id.tooltip", "<#e8dfd2>Создать остров с этим стартовым вариантом.</#e8dfd2>"),
                width = 205,
                closeDialogBeforeAction = true,
                onClick = { runIslandCommand(player, "create", id) },
            )
        }
        val body = if (starterButtons.isEmpty()) "starters.empty" else "starters.intro"
        ArcMenus.openDialog(
            player,
            PaperDialogScreen(
                id = "slimefun.skyblock.starters",
                title = text("starters.title"),
                body = listOf(PaperDialogBody(text(body), width = 420)),
                buttons = starterButtons,
                columns = 2,
            ),
            reopen = { openStarterIslands(player) },
        )
    }

    private fun openServices(player: Player) {
        val serviceRoutes = ROUTES.filter { it.id in SERVICE_ROUTE_IDS }
        ArcMenus.openDialog(
            player,
            PaperDialogScreen(
                id = "slimefun.skyblock.services",
                title = text("services.title"),
                body = listOf(PaperDialogBody(text("services.body"), width = 420)),
                buttons = serviceRoutes.map { route ->
                    PaperDialogButton(
                        id = PaperDialogActionId.of("service_${route.id}"),
                        label = text("buttons.${route.id}.label"),
                        tooltip = text("buttons.${route.id}.tooltip"),
                        width = 205,
                        closeDialogBeforeAction = true,
                        onClick = { context -> runLocalRoute(context.player, route) },
                    )
                },
                columns = 2,
            ),
            reopen = { openServices(player) },
        )
    }

    private fun externalButton(id: String, key: String, subCommand: String): PaperDialogButton =
        button(id, key, close = true) { runIslandCommand(it.player, subCommand) }

    private fun islandButton(id: String): PaperDialogButton = when (id) {
        "island_create" -> button(id, "buttons.create", close = false) { openStarterIslands(it.player) }
        "visit" -> button(id, "buttons.visit", close = false) { openVisit(it.player) }
        else -> {
            val route = ISLAND_ROUTES[id] ?: error("Unknown SkyBlock dialogue action: $id")
            externalButton(id, route.first, route.second)
        }
    }

    private fun button(
        id: String,
        key: String,
        close: Boolean,
        action: (PaperDialogClickContext) -> Unit,
    ): PaperDialogButton = PaperDialogButton(
        id = PaperDialogActionId.of(id),
        label = text("$key.label"),
        tooltip = text("$key.tooltip"),
        width = 205,
        closeDialogBeforeAction = close,
        onClick = action,
    )

    private fun contextButton(
        id: String,
        key: String,
        close: Boolean,
        action: (PaperDialogClickContext) -> Unit,
    ): PaperDialogButton = PaperDialogButton(
        id = PaperDialogActionId.of(id),
        label = text("$key.label"),
        tooltip = text("$key.tooltip"),
        width = 220,
        closeDialogBeforeAction = close,
        onClick = action,
    )

    private fun islandState(player: Player?): Boolean? {
        if (player == null || !ssbAvailable()) return null
        return runCatching { SuperiorSkyblockAPI.getPlayer(player).island != null }.getOrNull()
    }

    private fun ssbAvailable(): Boolean =
        Bukkit.getPluginManager().getPlugin(SSB_PLUGIN)?.isEnabled == true

    private fun runIslandCommand(player: Player, subCommand: String, arguments: String? = null) {
        if (!player.isOnline) return
        if (!ssbAvailable()) {
            player.sendMessage(text("unavailable"))
            return
        }
        runCatching {
            SuperiorSkyblockAPI.getSuperiorSkyblock().commands.dispatchSubCommand(player, subCommand, arguments)
        }.onFailure { failure ->
            warn("Could not dispatch SuperiorSkyblock command {} for {}: {}", subCommand, player.name, failure.message)
            player.sendMessage(text("unavailable"))
        }
    }

    private fun runLocalRoute(player: Player, route: MenuRoute) {
        if (!player.isOnline || !ARC.instance.isEnabled) return
        val provider = Bukkit.getPluginManager().getPlugin(route.providerName)
        val command = ARC.instance.server.commandMap.getCommand(route.namespacedLabel)
        val namespace = route.namespacedLabel.substringBefore(':')
        val ownerMatches = when (command) {
            is PluginCommand -> command.plugin === provider
            null -> false
            else -> provider?.name?.lowercase() == namespace
        }
        if (provider == null || !provider.isEnabled || command == null || !ownerMatches) {
            player.sendMessage(text("unavailable"))
            return
        }
        if (!Bukkit.dispatchCommand(player, route.invocation)) player.sendMessage(text("unavailable"))
    }

    private fun teleportToHub(player: Player) {
        val source = config ?: run {
            player.sendMessage(text("unavailable"))
            return
        }
        val worldName = source.string("hub.world", "slimefun_hub").trim()
        val x = source.double("hub.x", DEFAULT_HUB_X)
        val y = source.double("hub.y", DEFAULT_HUB_Y)
        val z = source.double("hub.z", DEFAULT_HUB_Z)
        val yaw = source.double("hub.yaw", 0.0)
        val pitch = source.double("hub.pitch", 0.0)
        val rotationYaw = yaw.toFloat()
        val rotationPitch = pitch.toFloat()
        if (worldName.isEmpty() || listOf(x, y, z, yaw, pitch).any { !it.isFinite() } ||
            !rotationYaw.isFinite() || !rotationPitch.isFinite()
        ) {
            player.sendMessage(text("hub.invalid"))
            return
        }
        val world = Bukkit.getWorld(worldName)
        if (world == null) {
            player.sendMessage(text("hub.unavailable"))
            return
        }
        val destination = Location(world, x, y, z, rotationYaw, rotationPitch)
        if (!player.teleport(destination)) player.sendMessage(text("hub.unavailable"))
    }

    fun sendToNetworkSpawn(player: Player) {
        val messenger = ARC.pluginMessenger
        if (!player.isOnline || messenger == null || !messenger.sendPlayerToServer(player, NETWORK_SPAWN_SERVER)) {
            player.sendMessage(text("spawn.unavailable"))
        }
    }

    private fun text(key: String): Component {
        val value = config?.string(key, DEFAULT_TEXT[key] ?: key) ?: DEFAULT_TEXT[key] ?: key
        return TextUtil.mm(value).decoration(TextDecoration.ITALIC, false)
    }

    private fun text(key: String, fallback: String): Component {
        val value = config?.string(key, fallback) ?: fallback
        return TextUtil.mm(value).decoration(TextDecoration.ITALIC, false)
    }

    internal data class MenuRoute(
        val id: String,
        val providerName: String,
        val namespacedLabel: String,
        val arguments: List<String> = emptyList(),
    ) {
        val invocation: String get() = (listOf(namespacedLabel) + arguments).joinToString(" ")
    }

    internal val ROUTES = listOf(
        MenuRoute("guide", "Slimefun", "slimefun:slimefun", listOf("guide")),
        MenuRoute("rtp", "RTP", "rtp:rtp"),
        MenuRoute("homes", "HuskHomes", "huskhomes:homes"),
        MenuRoute("lands", "Lands", "lands:lands"),
        MenuRoute("team", "justTeams", "justteams:team"),
        MenuRoute("shop", "EconomyShopGUI-Premium", "economyshopgui-premium:shop", listOf("slimefun_resources")),
    )

    internal val NO_ISLAND_ACTIONS = listOf("island_create", "island_top", "visit")
    internal val OWN_ISLAND_ACTIONS = listOf(
        "island_home", "island_manage", "island_team", "island_visitors", "island_settings", "island_biome", "island_top", "visit",
    )

    private val DEFAULT_TEXT = mapOf(
        "title" to "<#e6bc76>Остров и Слаймфан</#e6bc76>",
        "intro" to "<#e8dfd2>SkyBlock, развитие острова и мир технологий Slimefun.</#e8dfd2>",
        "unavailable" to "<#e8dfd2>Сервис временно недоступен. Попробуйте позже.</#e8dfd2>",
        "usage" to "<#e8dfd2>Использование: /skyblock [guide|shop|hub|spawn]</#e8dfd2>",
        "state.own-island" to "<#92bed8>Ваш остров: управление и команда.</#92bed8>",
        "state.no-island" to "<#e8dfd2>Остров ещё не создан — начните с создания.</#e8dfd2>",
        "state.unavailable" to "<#e8dfd2>Данные SkyBlock сейчас недоступны.</#e8dfd2>",
        "buttons.create.label" to "<#c4a7e7>Создать остров ›</#c4a7e7>",
        "buttons.create.tooltip" to "<#e8dfd2>Начать путь на своём острове.</#e8dfd2>",
        "buttons.home.label" to "<#92bed8>Домой на остров ›</#92bed8>",
        "buttons.home.tooltip" to "<#e8dfd2>Перейти на свой остров.</#e8dfd2>",
        "buttons.manage.label" to "<#c4a7e7>Управление островом ›</#c4a7e7>",
        "buttons.manage.tooltip" to "<#e8dfd2>Открыть настройки и инструменты острова.</#e8dfd2>",
        "buttons.island-team.label" to "<#9bd48d>Команда острова ›</#9bd48d>",
        "buttons.island-team.tooltip" to "<#e8dfd2>Посмотреть участников и доступные действия.</#e8dfd2>",
        "buttons.visitors.label" to "<#9bd48d>Посетители ›</#9bd48d>",
        "buttons.visitors.tooltip" to "<#e8dfd2>Настроить доступ посетителей к острову.</#e8dfd2>",
        "buttons.settings.label" to "<#c4a7e7>Настройки острова ›</#c4a7e7>",
        "buttons.settings.tooltip" to "<#e8dfd2>Изменить доступные настройки острова.</#e8dfd2>",
        "buttons.biome.label" to "<#9bd48d>Биом острова ›</#9bd48d>",
        "buttons.biome.tooltip" to "<#e8dfd2>Открыть выбор биома острова.</#e8dfd2>",
        "buttons.top.label" to "<#9bd48d>Рейтинг островов ›</#9bd48d>",
        "buttons.top.tooltip" to "<#e8dfd2>Посмотреть рейтинг островов.</#e8dfd2>",
        "buttons.visit.label" to "<#92bed8>Посетить остров ›</#92bed8>",
        "buttons.visit.tooltip" to "<#e8dfd2>Ввести имя игрока для посещения острова.</#e8dfd2>",
        "buttons.guide.label" to "<#85dfc4>Гайд Slimefun ›</#85dfc4>",
        "buttons.guide.tooltip" to "<#e8dfd2>Открыть шаги технологического развития.</#e8dfd2>",
        "buttons.shop.label" to "<#f4d87a>Магазин ресурсов ›</#f4d87a>",
        "buttons.shop.tooltip" to "<#e8dfd2>Покупать ресурсы для Slimefun.</#e8dfd2>",
        "buttons.hub.label" to "<#92bed8>Хаб SkyBlock ›</#92bed8>",
        "buttons.hub.tooltip" to "<#e8dfd2>Перейти в локальный хаб этого сервера.</#e8dfd2>",
        "buttons.network-spawn.label" to "<#92bed8>На спавн сети ›</#92bed8>",
        "buttons.network-spawn.tooltip" to "<#e8dfd2>Перейти на общий сервер spawn.</#e8dfd2>",
        "buttons.services.label" to "<#ffffff>Другие сервисы ›</#ffffff>",
        "buttons.services.tooltip" to "<#e8dfd2>Открыть RTP, дома и территории.</#e8dfd2>",
        "buttons.rtp.label" to "<#92bed8>Случайная телепортация ›</#92bed8>",
        "buttons.rtp.tooltip" to "<#e8dfd2>Найти безопасное место в мире Slimefun.</#e8dfd2>",
        "buttons.homes.label" to "<#92bed8>Мои дома ›</#92bed8>",
        "buttons.homes.tooltip" to "<#e8dfd2>Посмотреть и выбрать сохранённый дом.</#e8dfd2>",
        "buttons.lands.label" to "<#9bd48d>Мои земли ›</#9bd48d>",
        "buttons.lands.tooltip" to "<#e8dfd2>Управлять территориями и участками.</#e8dfd2>",
        "buttons.team.label" to "<#ffffff>Команды JustTeams ›</#ffffff>",
        "buttons.team.tooltip" to "<#e8dfd2>Открыть общее меню команд.</#e8dfd2>",
        "guide.title" to "<#85dfc4>Путь технологий</#85dfc4>",
        "guide.body" to "<#e8dfd2>1. Создайте остров и начните с доступных базовых ресурсов.</#e8dfd2>",
        "guide.progression" to "<#85dfc4>2. Откройте гайд Slimefun: изучите базовые рецепты и многоблочные механизмы.\n3. Собирайте ресурсы на острове, а нужные материалы приобретайте за слаймы в местном магазине.</#85dfc4>",
        "guide.catalog.label" to "<#85dfc4>Открыть гайд Slimefun ›</#85dfc4>",
        "guide.catalog.tooltip" to "<#e8dfd2>Открыть каталог предметов и рецептов.</#e8dfd2>",
        "visit.title" to "<#92bed8>Посещение острова</#92bed8>",
        "visit.body" to "<#e8dfd2>Укажите имя игрока, чей остров хотите посетить.</#e8dfd2>",
        "visit.player-input" to "<#e8dfd2>Имя игрока</#e8dfd2>",
        "visit.submit.label" to "<#92bed8>Найти остров ›</#92bed8>",
        "visit.submit.tooltip" to "<#e8dfd2>Найти остров игрока по имени.</#e8dfd2>",
        "visit.invalid-body" to "<#e8dfd2>Проверьте имя: 3–16 букв, цифр или символов _. Исправьте значение и попробуйте снова.</#e8dfd2>",
        "services.title" to "<#ffffff>Сервисы Slimefun</#ffffff>",
        "services.body" to "<#e8dfd2>Полезные команды этого мира.</#e8dfd2>",
        "hub.invalid" to "<#e8dfd2>Координаты хаба в конфигурации заданы неверно.</#e8dfd2>",
        "hub.unavailable" to "<#e8dfd2>Хаб SkyBlock временно недоступен.</#e8dfd2>",
        "spawn.unavailable" to "<#e8dfd2>Сейчас не удалось отправить вас на сетевой спавн.</#e8dfd2>",
        "starters.title" to "<#c4a7e7>Выбор острова</#c4a7e7>",
        "starters.intro" to "<#e8dfd2>Выберите остров и начните исследование.</#e8dfd2>",
        "starters.empty" to "<#e8dfd2>Сейчас нет доступных стартовых вариантов. Попробуйте позже.</#e8dfd2>",
    )

    private val SERVICE_ROUTE_IDS = setOf("rtp", "homes", "lands", "team")
    private val ISLAND_ROUTES = mapOf(
        "island_home" to ("buttons.home" to "teleport"),
        "island_manage" to ("buttons.manage" to "panel"),
        "island_team" to ("buttons.island-team" to "team"),
        "island_visitors" to ("buttons.visitors" to "visitors"),
        "island_settings" to ("buttons.settings" to "settings"),
        "island_biome" to ("buttons.biome" to "biome"),
        "island_top" to ("buttons.top" to "top"),
    )
    private val VISIT_INPUT = PaperDialogInputId.of("island_player")
    private val PLAYER_NAME = Regex("[A-Za-z0-9_]{3,16}")
    private val STARTER_ID = Regex("[A-Za-z0-9_-]{1,48}")
    private const val MAX_STARTER_CHOICES = 12
    private const val RESOURCE = "modules/slimefun-menu.yml"
    private const val SSB_PLUGIN = "SuperiorSkyblock2"
    private const val NETWORK_SPAWN_SERVER = "spawn"
    private const val DEFAULT_HUB_X = 170.5
    private const val DEFAULT_HUB_Y = 71.0
    private const val DEFAULT_HUB_Z = 206.5
}

object SlimefunMenuCommand : CommandExecutor, TabCompleter {
    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<String>): Boolean {
        val player = sender as? Player ?: run {
            sender.sendMessage("Эта команда доступна только игроку.")
            return true
        }
        if (command.name.equals("menu", ignoreCase = true) && args.isNotEmpty()) return false
        SlimefunMenuModule.handleCommand(player, args)
        return true
    }

    override fun onTabComplete(sender: CommandSender, command: Command, alias: String, args: Array<String>): List<String> =
        if (command.name.equals("skyblock", ignoreCase = true) && args.size == 1) {
            listOf("guide", "shop", "hub", "spawn").filter { it.startsWith(args[0], ignoreCase = true) }
        } else {
            emptyList()
        }
}

class NetworkSpawnCommand : Command("spawn") {
    override fun execute(sender: CommandSender, label: String, args: Array<String>): Boolean {
        val player = sender as? Player ?: run {
            sender.sendMessage("Эта команда доступна только игроку.")
            return true
        }
        if (args.isNotEmpty()) {
            sender.sendMessage("Использование: /spawn")
            return true
        }
        SlimefunMenuModule.sendToNetworkSpawn(player)
        return true
    }
}

internal class SlimefunMenuAliasCommand : Command("mm") {
    override fun execute(sender: CommandSender, label: String, args: Array<String>): Boolean =
        SlimefunMenuCommand.onCommand(sender, this, label, args)
}
