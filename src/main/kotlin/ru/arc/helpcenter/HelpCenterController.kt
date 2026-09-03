package ru.arc.helpcenter

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.minimessage.MiniMessage
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver
import org.bukkit.entity.Player
import ru.arc.core.LifecycleTaskScope
import ru.arc.core.whenCompleteSync
import ru.arc.gui.ArcMenus
import ru.arc.paper.menu.PaperDialogActionId
import ru.arc.paper.menu.PaperDialogBody
import ru.arc.paper.menu.PaperDialogButton
import ru.arc.paper.menu.PaperDialogClickContext
import ru.arc.paper.menu.PaperDialogInputId
import ru.arc.paper.menu.PaperDialogScreen
import ru.arc.paper.menu.PaperDialogTextInput
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

class HelpCenterController(
    private val settings: HelpCenterSettings,
    private val gateway: HelpCenterGateway,
    private val openLands: (Player) -> Unit,
) {
    private val miniMessage = MiniMessage.miniMessage()
    private val tasks = LifecycleTaskScope()
    private val serial = AtomicLong()
    private val navigation = ConcurrentHashMap<UUID, Long>()
    @Volatile private var active = true

    private val catalog: List<HelpCenterCommand> = DEFINITIONS.map { definition ->
        val configured = settings.command(definition.id)
        HelpCenterCommand(
            definition.id,
            definition.category,
            HelpCenterCommands.execute(definition.command),
            configured.label,
            configured.description,
            configured.keywords,
        )
    }
    private val catalogById = catalog.associateBy { it.id }
    private val searchCatalog: List<HelpCenterSearchEntry> = buildList {
        catalog.forEach { command ->
            add(
                HelpCenterSearchEntry(
                    id = command.id,
                    label = command.label,
                    description = command.description,
                    keywords = command.keywords,
                    action = if (command.id == "privat") {
                        HelpCenterSearchAction.OpenPage(HelpCenterPage.PRIVAT)
                    } else {
                        HelpCenterSearchAction.Execute(command.command)
                    },
                    command = command.command,
                ),
            )
        }
        INTENT_DEFINITIONS.forEach { definition ->
            val configured = settings.intent(definition.id)
            add(
                HelpCenterSearchEntry(
                    id = definition.id,
                    label = configured.label,
                    description = configured.description,
                    keywords = configured.keywords,
                    action = definition.action,
                ),
            )
        }
    }

    fun close() {
        active = false
        navigation.clear()
        tasks.close()
    }

    fun open(player: Player, page: HelpCenterPage = HelpCenterPage.ROOT) {
        when (page) {
            HelpCenterPage.ROOT -> openRoot(player)
            HelpCenterPage.MY -> openMy(player)
            HelpCenterPage.GUIDE -> openGuide(player)
            HelpCenterPage.COMMANDS -> openCommands(player)
            HelpCenterPage.TRAVEL -> openTravel(player)
            HelpCenterPage.PRIVAT -> {
                markNavigation(player)
                openLands(player)
            }
        }
    }

    private fun openRoot(player: Player) {
        markNavigation(player)
        ArcMenus.openDialog(
            player,
            PaperDialogScreen(
                title = text("root-title"),
                body = listOf(PaperDialogBody(text("root-body"), width = 500)),
                buttons = listOf(
                    button("my", text("my-label"), text("my-tooltip")) { openMy(player) },
                    button("guide", text("guide-label"), text("guide-tooltip")) { openGuide(player) },
                    button("commands", text("commands-label"), text("commands-tooltip")) { openCommands(player) },
                    button("travel", text("travel-label"), text("travel-tooltip")) { openTravel(player) },
                    button("privat", text("privat-label"), text("privat-tooltip")) { open(player, HelpCenterPage.PRIVAT) },
                    button("main_menu", text("main-menu-label"), text("main-menu-tooltip")) { execute(player, "menu") }.closing(),
                ),
                columns = 2,
            ),
        )
    }

    private fun openMy(player: Player) {
        val token = markNavigation(player)
        ArcMenus.openDialog(
            player,
            PaperDialogScreen(
                title = text("my-title"),
                body = listOf(PaperDialogBody(text("my-loading"), width = 500)),
                buttons = myButtons(player),
                exitButton = rootButton(player),
                columns = 2,
            ),
        )
        gateway.loadProfile(player, settings.loadTimeoutSeconds).whenCompleteSync(tasks) { profile, failure ->
            if (!active || !player.isOnline || navigation[player.uniqueId] != token) return@whenCompleteSync
            if (failure == null && profile != null) showMy(player, profile) else showMyFailure(player)
        }
    }

    private fun showMy(player: Player, profile: HelpCenterProfile) {
        val unavailable = settings.text("not-available")
        val placeholders = arrayOf(
            "player" to profile.playerName,
            "server" to profile.server,
            "rank" to (profile.rank ?: unavailable),
            "balance" to (profile.balance ?: unavailable),
            "homes" to (profile.homes?.usedSlots?.toString() ?: unavailable),
            "max_homes" to (profile.homes?.maxSlots?.toString() ?: unavailable),
            "lands" to (profile.lands?.toString() ?: unavailable),
            "world" to profile.world,
            "x" to profile.x.toString(),
            "y" to profile.y.toString(),
            "z" to profile.z.toString(),
        )
        ArcMenus.openDialog(
            player,
            PaperDialogScreen(
                title = text("my-title"),
                body = listOf("my-identity", "my-summary", "my-location").map { key ->
                    PaperDialogBody(text(key, *placeholders), width = 420)
                },
                buttons = myButtons(player),
                exitButton = rootButton(player),
                columns = 2,
            ),
        )
    }

    private fun showMyFailure(player: Player) {
        ArcMenus.openDialog(
            player,
            PaperDialogScreen(
                title = text("my-title"),
                body = listOf(PaperDialogBody(text("my-error"), width = 500)),
                buttons = myButtons(player),
                exitButton = rootButton(player),
                columns = 2,
            ),
        )
    }

    private fun myButtons(player: Player): List<PaperDialogButton> = listOf(
        button("my_homes", text("my-homes-label"), text("my-homes-tooltip")) { openTravel(player) },
        button("my_lands", text("my-lands-label"), text("my-lands-tooltip")) { open(player, HelpCenterPage.PRIVAT) },
        button("my_rank", text("my-rank-label"), text("my-rank-tooltip")) { execute(player, "rank") }.closing(),
        button("my_jobs", text("my-jobs-label"), text("my-jobs-tooltip")) { execute(player, "jobsgui") }.closing(),
        button("my_quests", text("my-quests-label"), text("my-quests-tooltip")) { execute(player, "quests") }.closing(),
        button("my_skills", text("my-skills-label"), text("my-skills-tooltip")) { execute(player, "skills") }.closing(),
    )

    private fun openGuide(player: Player) {
        markNavigation(player)
        ArcMenus.openDialog(
            player,
            PaperDialogScreen(
                title = text("guide-title"),
                body = listOf(PaperDialogBody(text("guide-body"), width = 500)),
                buttons = listOf(
                    button("kit", text("kit-label"), commandTooltip("kit")) { execute(player, "kit start") }.closing(),
                    button("vanilla", text("vanilla-label"), commandTooltip("vanilla")) { execute(player, "pw vanilla") }.closing(),
                    button("mining", text("mining-label"), commandTooltip("mining")) { execute(player, "mining") }.closing(),
                    button("biomes", text("biomes-label"), commandTooltip("biomes")) { execute(player, "pw survival") }.closing(),
                    button("jobs", text("jobs-label"), commandTooltip("jobs")) { execute(player, "jobsgui") }.closing(),
                    button("home", text("travel-label"), text("travel-tooltip")) { openTravel(player) },
                    button("privat", text("privat-label"), text("privat-tooltip")) { open(player, HelpCenterPage.PRIVAT) },
                    button("rules", text("rules-label"), commandTooltip("rules")) { execute(player, "rules") }.closing(),
                ),
                exitButton = rootButton(player),
                columns = 2,
            ),
        )
    }

    private fun openCommands(player: Player) {
        markNavigation(player)
        ArcMenus.openDialog(
            player,
            PaperDialogScreen(
                title = text("commands-title"),
                body = listOf(PaperDialogBody(text("commands-body"), width = 500)),
                inputs = listOf(PaperDialogTextInput(SEARCH_INPUT, text("search-input"), maxLength = 48)),
                buttons = listOf(
                    contextButton("search", text("search-label"), text("search-tooltip")) { context ->
                        openSearch(player, context.text(SEARCH_INPUT).orEmpty())
                    },
                    categoryButton(player, HelpCenterCategory.START),
                    categoryButton(player, HelpCenterCategory.TRAVEL),
                    categoryButton(player, HelpCenterCategory.PROTECTION),
                    categoryButton(player, HelpCenterCategory.TRADE),
                    categoryButton(player, HelpCenterCategory.PROGRESS),
                    categoryButton(player, HelpCenterCategory.SOCIAL),
                ),
                exitButton = rootButton(player),
                columns = 2,
            ),
        )
    }

    private fun openSearch(player: Player, rawQuery: String) {
        markNavigation(player)
        val query = rawQuery.trim()
        val results = HelpCenterPlanner.search(searchCatalog, query, settings.maxSearchResults)
        val body = mutableListOf(
            PaperDialogBody(
                text(
                    "search-body",
                    "query" to query.ifEmpty { "все команды" },
                    "count" to results.size.toString(),
                ),
            ),
        )
        if (results.isEmpty()) body += PaperDialogBody(text("search-empty"))
        ArcMenus.openDialog(
            player,
            PaperDialogScreen(
                title = text("search-title"),
                body = body,
                buttons = results.map { searchResultButton(player, it) }.ifEmpty {
                    listOf(button("search_again", text("commands-label"), text("commands-tooltip")) { openCommands(player) })
                },
                exitButton = backButton("back", player, ::openCommands),
                columns = 2,
            ),
        )
    }

    private fun openCategory(player: Player, category: HelpCenterCategory) {
        markNavigation(player)
        val entries = catalog.filter { it.category == category }
        ArcMenus.openDialog(
            player,
            PaperDialogScreen(
                title = text("category-${category.configId}-title"),
                body = listOf(PaperDialogBody(text("category-body"))),
                buttons = entries.map { commandButton(player, it) },
                exitButton = backButton("back", player, ::openCommands),
                columns = 2,
            ),
        )
    }

    private fun openTravel(player: Player) {
        val token = markNavigation(player)
        ArcMenus.openDialog(
            player,
            PaperDialogScreen(
                title = text("travel-title"),
                body = listOf(PaperDialogBody(text("travel-loading"))),
                buttons = listOf(button("root", text("root-label"), text("main-menu-tooltip")) { openRoot(player) }),
            ),
        )
        gateway.loadHomes(player, settings.loadTimeoutSeconds).whenCompleteSync(tasks) { homes, failure ->
            if (!active || !player.isOnline || navigation[player.uniqueId] != token) return@whenCompleteSync
            if (failure == null && homes != null) showTravel(player, homes) else showTravelFailure(player)
        }
    }

    private fun showTravel(player: Player, snapshot: HelpCenterHomes) {
        val homes = snapshot.homes
            .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.name })
            .take(settings.maxHomes)
        val body = mutableListOf(
            PaperDialogBody(
                text(
                    "travel-body",
                    "homes" to snapshot.usedSlots.toString(),
                    "max_homes" to snapshot.maxSlots.toString(),
                ),
            ),
        )
        if (homes.isEmpty()) body += PaperDialogBody(text("travel-empty"))
        if (snapshot.maxSlots > 0 && snapshot.usedSlots >= snapshot.maxSlots) body += PaperDialogBody(text("travel-limit"))
        val homeButtons = homes.mapIndexed { index, home ->
            button(
                "home_$index",
                text("home-label", "home" to home.name),
                text(
                    "home-tooltip",
                    "server" to home.server,
                    "world" to home.world,
                    "x" to home.x.toString(),
                    "y" to home.y.toString(),
                    "z" to home.z.toString(),
                ),
            ) { openHome(player, home) }
        }
        val createButton = if (snapshot.maxSlots == 0 || snapshot.usedSlots < snapshot.maxSlots) {
            listOf(button("create_home", text("create-home-label"), text("create-home-tooltip")) { openCreateHome(player) })
        } else {
            emptyList()
        }
        ArcMenus.openDialog(
            player,
            PaperDialogScreen(
                title = text("travel-title"),
                body = body,
                buttons = homeButtons + createButton + travelButtons(player),
                exitButton = rootButton(player),
                columns = 3,
            ),
        )
    }

    private fun showTravelFailure(player: Player) {
        ArcMenus.openDialog(
            player,
            PaperDialogScreen(
                title = text("travel-title"),
                body = listOf(PaperDialogBody(text("travel-error"))),
                buttons = travelButtons(player),
                exitButton = rootButton(player),
                columns = 2,
            ),
        )
    }

    private fun openCreateHome(player: Player) {
        markNavigation(player)
        ArcMenus.openDialog(
            player,
            PaperDialogScreen(
                title = text("home-create-title"),
                body = listOf(PaperDialogBody(text("home-create-body"))),
                inputs = listOf(PaperDialogTextInput(HOME_INPUT, text("home-name-input"), maxLength = 32)),
                buttons = listOf(
                    contextButton("create_home", text("home-create-submit")) { context ->
                        val command = runCatching {
                            HelpCenterCommands.createHome(context.text(HOME_INPUT).orEmpty().trim())
                        }.getOrNull()
                        if (command == null) {
                            player.sendMessage(text("invalid-home"))
                            openCreateHome(player)
                        } else {
                            execute(player, command)
                        }
                    }.closing(),
                ),
                exitButton = backButton("back", player, ::openTravel),
            ),
        )
    }

    private fun openHome(player: Player, home: HelpCenterHome) {
        markNavigation(player)
        ArcMenus.openDialog(
            player,
            PaperDialogScreen(
                title = text("home-title", "home" to home.name),
                body = listOf(
                    PaperDialogBody(
                        text(
                            "home-body",
                            "server" to home.server,
                            "world" to home.world,
                            "x" to home.x.toString(),
                            "y" to home.y.toString(),
                            "z" to home.z.toString(),
                        ),
                    ),
                ),
                buttons = listOf(
                    button("teleport", text("home-teleport-label"), text("home-teleport-tooltip")) {
                        execute(player, HelpCenterCommands.home(home.name))
                    }.closing(),
                    button("relocate", text("home-relocate-label"), text("home-relocate-tooltip")) { openRelocateHome(player, home) },
                    button("delete", text("home-delete-label"), text("home-delete-tooltip")) { openDeleteHome(player, home) },
                ),
                exitButton = backButton("back", player, ::openTravel),
                columns = 2,
            ),
        )
    }

    private fun openRelocateHome(player: Player, home: HelpCenterHome) {
        markNavigation(player)
        ArcMenus.openDialog(
            player,
            PaperDialogScreen(
                title = text("home-relocate-title", "home" to home.name),
                body = listOf(PaperDialogBody(text("home-relocate-body", "home" to home.name))),
                buttons = listOf(
                    button("relocate_confirm", text("home-relocate-confirm"), text("home-relocate-body", "home" to home.name)) {
                        execute(player, HelpCenterCommands.relocateHome(home.name))
                    }.closing(),
                ),
                exitButton = backButton("back", player, action = { target -> openHome(target, home) }),
            ),
        )
    }

    private fun openDeleteHome(player: Player, home: HelpCenterHome) {
        markNavigation(player)
        ArcMenus.openDialog(
            player,
            PaperDialogScreen(
                title = text("home-delete-title", "home" to home.name),
                body = listOf(PaperDialogBody(text("home-delete-body", "home" to home.name))),
                buttons = listOf(
                    button("delete_confirm", text("home-delete-confirm"), text("home-delete-body", "home" to home.name)) {
                        execute(player, HelpCenterCommands.deleteHome(home.name))
                    }.closing(),
                ),
                exitButton = backButton("back", player, action = { target -> openHome(target, home) }),
            ),
        )
    }

    private fun travelButtons(player: Player): List<PaperDialogButton> = listOf(
        button("warps", text("warps-label"), commandTooltip("warps")) { execute(player, "warps") }.closing(),
        button("public_homes", text("public-homes-label"), text("public-homes-tooltip")) { execute(player, "phome") }.closing(),
        button("spawn", text("spawn-label"), commandTooltip("spawn")) { execute(player, "spawn") }.closing(),
        button("rtp", text("rtp-label"), commandTooltip("rtp")) { execute(player, "rtp") }.closing(),
        button("back_command", text("back-command-label"), commandTooltip("back")) { execute(player, "back") }.closing(),
        button("stuck", text("stuck-label"), commandTooltip("stuck")) { execute(player, "stuck") }.closing(),
        button("vanilla", text("vanilla-label"), commandTooltip("vanilla")) { execute(player, "pw vanilla") }.closing(),
        button("mining", text("mining-label"), commandTooltip("mining")) { execute(player, "mining") }.closing(),
        button("biomes", text("biomes-label"), commandTooltip("biomes")) { execute(player, "pw survival") }.closing(),
    )

    private fun categoryButton(player: Player, category: HelpCenterCategory): PaperDialogButton =
        button("category_${category.configId}", text("category-${category.configId}-label"), text("category-body")) {
            openCategory(player, category)
        }

    private fun commandButton(player: Player, command: HelpCenterCommand): PaperDialogButton {
        val result = button(
            "command_${command.id}",
            text("command-label", "label" to command.label),
            text("command-tooltip", "description" to command.description, "command" to command.command),
        ) {
            if (command.id == "privat") open(player, HelpCenterPage.PRIVAT) else execute(player, command.command)
        }
        return if (command.id == "privat") result else result.closing()
    }

    private fun searchResultButton(player: Player, entry: HelpCenterSearchEntry): PaperDialogButton {
        val result = button(
            "result_${entry.id.replace('-', '_')}",
            text("command-label", "label" to entry.label),
            entry.command?.let { command ->
                text("command-tooltip", "description" to entry.description, "command" to command)
            } ?: text("search-result-tooltip", "description" to entry.description),
        ) {
            when (val action = entry.action) {
                is HelpCenterSearchAction.Execute -> execute(player, action.command)
                is HelpCenterSearchAction.OpenPage -> open(player, action.page)
                HelpCenterSearchAction.CreateHome -> openCreateHome(player)
            }
        }
        return if (entry.action is HelpCenterSearchAction.Execute) result.closing() else result
    }

    private fun execute(player: Player, command: String) {
        markNavigation(player)
        if (!gateway.execute(player, command)) player.sendMessage(text("action-failed"))
    }

    private fun commandTooltip(id: String): Component {
        val command = catalogById.getValue(id)
        return text("command-tooltip", "description" to command.description, "command" to command.command)
    }

    private fun rootButton(player: Player): PaperDialogButton = backButton("root", player, ::openRoot, "root-label")

    private fun backButton(
        id: String,
        player: Player,
        action: (Player) -> Unit,
        label: String = "back-label",
    ): PaperDialogButton = button(id, text(label)) { action(player) }

    private fun button(
        id: String,
        label: Component,
        tooltip: Component = Component.empty(),
        action: () -> Unit,
    ): PaperDialogButton = PaperDialogButton(PaperDialogActionId.of(id), label, tooltip) { action() }

    private fun contextButton(
        id: String,
        label: Component,
        tooltip: Component = Component.empty(),
        action: (PaperDialogClickContext) -> Unit,
    ): PaperDialogButton = PaperDialogButton(PaperDialogActionId.of(id), label, tooltip, onClick = action)

    private fun PaperDialogButton.closing(): PaperDialogButton = copy(closeDialogBeforeAction = true)

    private fun text(key: String, vararg placeholders: Pair<String, String>): Component = miniMessage.deserialize(
        settings.text(key),
        TagResolver.resolver(placeholders.map { (name, value) -> Placeholder.unparsed(name, value) }),
    )

    private fun markNavigation(player: Player): Long = serial.incrementAndGet().also { navigation[player.uniqueId] = it }

    private data class CommandDefinition(val id: String, val category: HelpCenterCategory, val command: String)

    private data class IntentDefinition(val id: String, val action: HelpCenterSearchAction)

    companion object {
        private val SEARCH_INPUT = PaperDialogInputId.of("search")
        private val HOME_INPUT = PaperDialogInputId.of("home_name")

        private val DEFINITIONS = listOf(
            CommandDefinition("menu", HelpCenterCategory.START, "menu"),
            CommandDefinition("kit", HelpCenterCategory.START, "kit start"),
            CommandDefinition("rules", HelpCenterCategory.START, "rules"),
            CommandDefinition("tutorial", HelpCenterCategory.START, "tutorial"),
            CommandDefinition("warps", HelpCenterCategory.TRAVEL, "warps"),
            CommandDefinition("spawn", HelpCenterCategory.TRAVEL, "spawn"),
            CommandDefinition("rtp", HelpCenterCategory.TRAVEL, "rtp"),
            CommandDefinition("back", HelpCenterCategory.TRAVEL, "back"),
            CommandDefinition("stuck", HelpCenterCategory.TRAVEL, "stuck"),
            CommandDefinition("vanilla", HelpCenterCategory.TRAVEL, "pw vanilla"),
            CommandDefinition("mining", HelpCenterCategory.TRAVEL, "mining"),
            CommandDefinition("biomes", HelpCenterCategory.TRAVEL, "pw survival"),
            CommandDefinition("privat", HelpCenterCategory.PROTECTION, "privat"),
            CommandDefinition("shops", HelpCenterCategory.TRADE, "shops"),
            CommandDefinition("sell", HelpCenterCategory.TRADE, "sell"),
            CommandDefinition("auction", HelpCenterCategory.TRADE, "ah"),
            CommandDefinition("rank", HelpCenterCategory.PROGRESS, "rank"),
            CommandDefinition("rankup", HelpCenterCategory.PROGRESS, "rankup"),
            CommandDefinition("jobs", HelpCenterCategory.PROGRESS, "jobsgui"),
            CommandDefinition("quests", HelpCenterCategory.PROGRESS, "quests"),
            CommandDefinition("skills", HelpCenterCategory.PROGRESS, "skills"),
            CommandDefinition("notes", HelpCenterCategory.SOCIAL, "notes"),
            CommandDefinition("donate", HelpCenterCategory.SOCIAL, "donate"),
        )

        private val INTENT_DEFINITIONS = listOf(
            IntentDefinition("my", HelpCenterSearchAction.OpenPage(HelpCenterPage.MY)),
            IntentDefinition("home-create", HelpCenterSearchAction.CreateHome),
            IntentDefinition("home-move", HelpCenterSearchAction.OpenPage(HelpCenterPage.TRAVEL)),
            IntentDefinition("home-delete", HelpCenterSearchAction.OpenPage(HelpCenterPage.TRAVEL)),
            IntentDefinition("land-create", HelpCenterSearchAction.OpenPage(HelpCenterPage.PRIVAT)),
            IntentDefinition("land-delete", HelpCenterSearchAction.OpenPage(HelpCenterPage.PRIVAT)),
            IntentDefinition("land-invite", HelpCenterSearchAction.OpenPage(HelpCenterPage.PRIVAT)),
            IntentDefinition("land-remove", HelpCenterSearchAction.OpenPage(HelpCenterPage.PRIVAT)),
            IntentDefinition("land-main-block", HelpCenterSearchAction.OpenPage(HelpCenterPage.PRIVAT)),
            IntentDefinition("land-claim", HelpCenterSearchAction.OpenPage(HelpCenterPage.PRIVAT)),
        )
    }
}
