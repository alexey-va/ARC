package ru.arc.helpcenter

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.minimessage.MiniMessage
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
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
    private val inviteToLand: (Player, HelpCenterPlayer) -> Unit,
) {
    private val miniMessage = MiniMessage.miniMessage()
    private val plainText = PlainTextComponentSerializer.plainText()
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
            definition.requiredFeature,
            definition.permission,
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
            HelpCenterPage.NOW, HelpCenterPage.MY -> openNow(player)
            HelpCenterPage.GUIDE -> openGuide(player)
            HelpCenterPage.COMMANDS -> openCommands(player)
            HelpCenterPage.TRAVEL -> openTravel(player)
            HelpCenterPage.PRIVAT -> {
                markNavigation(player)
                openLands(player)
            }
            HelpCenterPage.ACTIVITIES -> openCategory(player, HelpCenterCategory.ACTIVITIES, returnToRoot = true)
            HelpCenterPage.PLAYERS -> openPlayers(player)
            HelpCenterPage.TECHNOLOGY -> openCategory(player, HelpCenterCategory.TECHNOLOGY, returnToRoot = true)
            HelpCenterPage.SETTINGS -> openSettings(player)
            HelpCenterPage.RECOVERY -> openRecovery(player)
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
                    button("now", text("now-label"), text("now-tooltip")) { openNow(player) },
                    button("search", text("commands-label"), text("commands-tooltip")) { openCommands(player) },
                    button("travel", text("travel-label"), text("travel-tooltip")) { openTravel(player) },
                    button("privat", text("privat-label"), text("privat-tooltip")) { open(player, HelpCenterPage.PRIVAT) },
                    rootCategoryButton(player, HelpCenterCategory.ACTIVITIES),
                    rootCategoryButton(player, HelpCenterCategory.PROGRESS),
                    rootCategoryButton(player, HelpCenterCategory.TRADE),
                    button("players", text("players-label"), text("players-tooltip")) { openPlayers(player) },
                    rootCategoryButton(player, HelpCenterCategory.TECHNOLOGY),
                    button("settings", text("category-settings-label"), text("category-settings-tooltip")) { openSettings(player) },
                ),
                columns = 2,
            ),
        )
    }

    private fun openNow(player: Player) {
        val token = markNavigation(player)
        ArcMenus.openDialog(
            player,
            PaperDialogScreen(
                title = text("now-title"),
                body = listOf(PaperDialogBody(text("my-loading"), width = 500)),
                buttons = listOf(
                    button("now_homes", text("my-homes-label")) { openTravel(player) },
                    button("now_lands", text("my-lands-label")) { open(player, HelpCenterPage.PRIVAT) },
                    button("now_guide", text("guide-label")) { openGuide(player) },
                ),
                exitButton = rootButton(player),
                columns = 2,
            ),
        )
        gateway.loadProfile(player, settings.loadTimeoutSeconds).whenCompleteSync(tasks) { profile, failure ->
            if (!active || !player.isOnline || navigation[player.uniqueId] != token) return@whenCompleteSync
            if (failure == null && profile != null) showNow(player, profile) else showMyFailure(player)
        }
    }

    private fun showNow(player: Player, profile: HelpCenterProfile) {
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
            "chat" to if (profile.chatMode == HelpCenterChatMode.GLOBAL) "глобальный" else "локальный",
            "online" to profile.onlinePlayers.toString(),
        )
        ArcMenus.openDialog(
            player,
            PaperDialogScreen(
                title = text("now-title"),
                body = listOf("now-identity", "now-progress", "now-location").map { key ->
                    PaperDialogBody(text(key, *placeholders), width = 420)
                },
                buttons = recommendationButtons(player, profile) + listOf(
                    button("now_homes", text("my-homes-label"), text("my-homes-tooltip")) { openTravel(player) },
                    button("now_lands", text("my-lands-label"), text("my-lands-tooltip")) { open(player, HelpCenterPage.PRIVAT) },
                    button("now_guide", text("guide-label"), text("guide-tooltip")) { openGuide(player) },
                ),
                exitButton = rootButton(player),
                columns = 2,
            ),
        )
    }

    private fun recommendationButtons(player: Player, profile: HelpCenterProfile): List<PaperDialogButton> =
        HelpCenterPlanner.recommendations(profile, gateway.features(), 4).map { recommendation ->
            when (recommendation.id) {
                HelpCenterRecommendationId.CREATE_HOME -> button("rec_home", text("rec-home-label")) { openCreateHome(player) }
                HelpCenterRecommendationId.CREATE_LAND -> button("rec_land", text("rec-land-label")) { open(player, HelpCenterPage.PRIVAT) }
                HelpCenterRecommendationId.RANK_GOAL -> button("rec_rank", text("rec-rank-label")) { execute(player, "rank why") }.closing()
                HelpCenterRecommendationId.BATTLE_PASS -> button("rec_bp", text("rec-bp-label")) { execute(player, "bp") }.closing()
                HelpCenterRecommendationId.EVENTS -> button("rec_events", text("rec-events-label")) { execute(player, "events") }.closing()
            }
        }

    private fun showMyFailure(player: Player) {
        ArcMenus.openDialog(
            player,
            PaperDialogScreen(
                title = text("now-title"),
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
                    categoryButton(player, HelpCenterCategory.ACTIVITIES),
                    categoryButton(player, HelpCenterCategory.TRADE),
                    categoryButton(player, HelpCenterCategory.PROGRESS),
                    categoryButton(player, HelpCenterCategory.TECHNOLOGY),
                    categoryButton(player, HelpCenterCategory.SETTINGS),
                    button("recovery", text("recovery-label"), text("recovery-tooltip")) { openRecovery(player) },
                ),
                exitButton = rootButton(player),
                columns = 2,
            ),
        )
    }

    private fun openPlayers(player: Player, rawQuery: String = "") {
        markNavigation(player)
        val query = rawQuery.trim()
        val players = HelpCenterPlanner.players(player.uniqueId, gateway.onlinePlayers(), query, 12)
        val body = mutableListOf(
            PaperDialogBody(text("players-body", "count" to players.size.toString()), width = 500),
        )
        if (players.isEmpty()) body += PaperDialogBody(text("players-empty"))
        ArcMenus.openDialog(
            player,
            PaperDialogScreen(
                title = text("players-title"),
                body = body,
                inputs = listOf(PaperDialogTextInput(PLAYER_SEARCH_INPUT, text("players-input"), initial = query, maxLength = 16)),
                buttons = listOf(
                    contextButton("find_player", text("players-search-label"), text("players-search-tooltip")) { context ->
                        openPlayers(player, context.text(PLAYER_SEARCH_INPUT).orEmpty())
                    },
                ) + players.mapIndexed { index, target ->
                    button(
                        "player_$index",
                        text("player-label", "player" to target.name),
                        text("player-tooltip", "server" to (target.server ?: plainText("player-server-unknown"))),
                    ) {
                        openPlayer(player, target)
                    }
                },
                exitButton = rootButton(player),
                columns = 2,
            ),
        )
    }

    private fun openPlayer(player: Player, target: HelpCenterPlayer) {
        markNavigation(player)
        val buttons = mutableListOf(
            button("tpa", text("player-tpa-label")) { execute(player, HelpCenterCommands.teleportRequest(target.name)) }.closing(),
            button("tpahere", text("player-tpahere-label")) { execute(player, HelpCenterCommands.teleportHere(target.name)) }.closing(),
            button("message", text("player-message-label")) { openPlayerMessage(player, target) },
            button("pay", text("player-pay-label")) { openPlayerPayment(player, target) },
        )
        if (HelpCenterFeature.DUELS in gateway.features()) {
            buttons += button("duel", text("player-duel-label")) { execute(player, HelpCenterCommands.duel(target.name)) }.closing()
        }
        if (HelpCenterFeature.LANDS in gateway.features()) {
            buttons += button("invite", text("player-invite-label")) { inviteToLand(player, target) }
        }
        ArcMenus.openDialog(
            player,
            PaperDialogScreen(
                title = text("player-title", "player" to target.name),
                body = listOf(
                    PaperDialogBody(
                        text(
                            "player-body",
                            "player" to target.name,
                            "server" to (target.server ?: plainText("player-server-unknown")),
                        ),
                        width = 500,
                    ),
                ),
                buttons = buttons,
                exitButton = backButton("back", player, ::openPlayers),
                columns = 2,
            ),
        )
    }

    private fun openPlayerMessage(player: Player, target: HelpCenterPlayer) {
        markNavigation(player)
        ArcMenus.openDialog(
            player,
            PaperDialogScreen(
                title = text("message-title", "player" to target.name),
                body = listOf(PaperDialogBody(text("message-body", "player" to target.name))),
                inputs = listOf(PaperDialogTextInput(MESSAGE_INPUT, text("message-input"), maxLength = 128)),
                buttons = listOf(contextButton("send", text("message-send-label")) { context ->
                    val command = runCatching { HelpCenterCommands.message(target.name, context.text(MESSAGE_INPUT).orEmpty()) }.getOrNull()
                    if (command == null) {
                        player.sendMessage(text("invalid-message"))
                        openPlayerMessage(player, target)
                    } else execute(player, command)
                }.closing()),
                exitButton = backButton("back", player, action = { openPlayer(it, target) }),
            ),
        )
    }

    private fun openPlayerPayment(player: Player, target: HelpCenterPlayer) {
        markNavigation(player)
        ArcMenus.openDialog(
            player,
            PaperDialogScreen(
                title = text("pay-title", "player" to target.name),
                body = listOf(PaperDialogBody(text("pay-body", "player" to target.name))),
                inputs = listOf(PaperDialogTextInput(AMOUNT_INPUT, text("pay-input"), maxLength = 16)),
                buttons = listOf(contextButton("continue", text("pay-continue-label")) { context ->
                    val rawAmount = context.text(AMOUNT_INPUT).orEmpty()
                    val command = runCatching { HelpCenterCommands.pay(target.name, rawAmount) }.getOrNull()
                    if (command == null) {
                        player.sendMessage(text("invalid-amount"))
                        openPlayerPayment(player, target)
                    } else openPaymentConfirmation(player, target, rawAmount.replace(',', '.').trim(), command)
                }),
                exitButton = backButton("back", player, action = { openPlayer(it, target) }),
            ),
        )
    }

    private fun openPaymentConfirmation(player: Player, target: HelpCenterPlayer, amount: String, command: String) {
        markNavigation(player)
        ArcMenus.openDialog(
            player,
            PaperDialogScreen(
                title = text("pay-confirm-title"),
                body = listOf(PaperDialogBody(text("pay-confirm-body", "player" to target.name, "amount" to amount))),
                buttons = listOf(button("confirm", text("pay-confirm-label")) { execute(player, command) }.closing()),
                exitButton = backButton("back", player, action = { openPlayerPayment(it, target) }),
            ),
        )
    }

    private fun openSettings(player: Player) {
        markNavigation(player)
        val global = ru.arc.chat.ChatModeService.getMode(player.uniqueId).name == "GLOBAL"
        ArcMenus.openDialog(
            player,
            PaperDialogScreen(
                title = text("category-settings-title"),
                body = listOf(PaperDialogBody(text("settings-body", "chat" to if (global) "глобальный" else "локальный"))),
                buttons = listOf(
                    if (global) button("chat_local", text("command-chat-local-label")) { execute(player, "l") }.closing()
                    else button("chat_global", text("command-chat-global-label")) { execute(player, "g") }.closing(),
                ) + availableCatalog(player).filter { it.category == HelpCenterCategory.SETTINGS && it.id !in setOf("chat-global", "chat-local") }.map { commandButton(player, it) },
                exitButton = rootButton(player),
                columns = 2,
            ),
        )
    }

    private fun openRecovery(player: Player) {
        markNavigation(player)
        ArcMenus.openDialog(
            player,
            PaperDialogScreen(
                title = text("recovery-title"),
                body = listOf(PaperDialogBody(text("recovery-body"), width = 500)),
                buttons = listOf(
                    button("stuck", text("stuck-label"), commandTooltip("stuck")) { execute(player, "stuck") }.closing(),
                    button("homes", text("my-homes-label")) { openTravel(player) },
                    button("lands", text("my-lands-label")) { open(player, HelpCenterPage.PRIVAT) },
                    button("rules", text("rules-label"), commandTooltip("rules")) { execute(player, "rules") }.closing(),
                ),
                exitButton = backButton("back", player, ::openCommands),
                columns = 2,
            ),
        )
    }

    private fun openSearch(player: Player, rawQuery: String) {
        markNavigation(player)
        val query = rawQuery.trim()
        val results = HelpCenterPlanner.search(availableSearchCatalog(player), query, settings.maxSearchResults)
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
                } + listOf(button("recovery", text("recovery-label"), text("recovery-tooltip")) { openRecovery(player) }),
                exitButton = backButton("back", player, ::openCommands),
                columns = 2,
            ),
        )
    }

    private fun openCategory(player: Player, category: HelpCenterCategory, returnToRoot: Boolean = false) {
        markNavigation(player)
        val entries = availableCatalog(player).filter { it.category == category }
        ArcMenus.openDialog(
            player,
            PaperDialogScreen(
                title = text("category-${category.configId}-title"),
                body = listOf(PaperDialogBody(text("category-body"))),
                buttons = entries.map { commandButton(player, it) },
                exitButton = backButton("back", player, if (returnToRoot) ::openRoot else ::openCommands),
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

    private fun rootCategoryButton(player: Player, category: HelpCenterCategory): PaperDialogButton =
        button(
            "root_${category.configId}",
            text("category-${category.configId}-label"),
            text("category-${category.configId}-tooltip"),
        ) {
            openCategory(player, category, returnToRoot = true)
        }

    private fun commandButton(player: Player, command: HelpCenterCommand): PaperDialogButton {
        val label = when (command.id) {
            "vote", "chat-global", "chat-local" -> text("command-${command.id}-label")
            else -> text("command-label", "label" to command.label)
        }
        val result = button(
            "command_${command.id}",
            label,
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

    private fun availableCatalog(player: Player): List<HelpCenterCommand> {
        val features = gateway.features()
        return catalog.filter { command ->
            (command.requiredFeature == null || command.requiredFeature in features) &&
                (command.permission == null || player.hasPermission(command.permission))
        }
    }

    private fun availableSearchCatalog(player: Player): List<HelpCenterSearchEntry> {
        val availableIds = availableCatalog(player).mapTo(hashSetOf()) { it.id }
        return searchCatalog.filter { entry -> entry.command == null || entry.id in availableIds }
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

    private fun plainText(key: String): String = plainText.serialize(text(key))

    private fun markNavigation(player: Player): Long = serial.incrementAndGet().also { navigation[player.uniqueId] = it }

    private data class CommandDefinition(
        val id: String,
        val category: HelpCenterCategory,
        val command: String,
        val requiredFeature: HelpCenterFeature? = null,
        val permission: String? = null,
    )

    private data class IntentDefinition(val id: String, val action: HelpCenterSearchAction)

    companion object {
        private val SEARCH_INPUT = PaperDialogInputId.of("search")
        private val HOME_INPUT = PaperDialogInputId.of("home_name")
        private val PLAYER_SEARCH_INPUT = PaperDialogInputId.of("player_search")
        private val MESSAGE_INPUT = PaperDialogInputId.of("message")
        private val AMOUNT_INPUT = PaperDialogInputId.of("amount")

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
            CommandDefinition("events", HelpCenterCategory.ACTIVITIES, "events", HelpCenterFeature.EVENTS),
            CommandDefinition("duels", HelpCenterCategory.ACTIVITIES, "duel", HelpCenterFeature.DUELS),
            CommandDefinition("battle-pass", HelpCenterCategory.ACTIVITIES, "bp", HelpCenterFeature.BATTLE_PASS),
            CommandDefinition("giveaways", HelpCenterCategory.ACTIVITIES, "giveaway", HelpCenterFeature.GIVEAWAYS),
            CommandDefinition("dungeons", HelpCenterCategory.ACTIVITIES, "em", HelpCenterFeature.DUNGEONS),
            CommandDefinition("dungeon-portals", HelpCenterCategory.ACTIVITIES, "pw aguild", HelpCenterFeature.DUNGEONS),
            CommandDefinition("farms", HelpCenterCategory.ACTIVITIES, "arcfarms", HelpCenterFeature.FARMS),
            CommandDefinition("vote", HelpCenterCategory.ACTIVITIES, "vote", HelpCenterFeature.VOTES),
            CommandDefinition("shops", HelpCenterCategory.TRADE, "shops"),
            CommandDefinition("sell", HelpCenterCategory.TRADE, "sell"),
            CommandDefinition("auction", HelpCenterCategory.TRADE, "ah"),
            CommandDefinition("bank", HelpCenterCategory.TRADE, "bank open", HelpCenterFeature.BANK, "bank.open.command"),
            CommandDefinition("investments", HelpCenterCategory.TRADE, "arc-invest"),
            CommandDefinition("rank", HelpCenterCategory.PROGRESS, "rank"),
            CommandDefinition("rankup", HelpCenterCategory.PROGRESS, "rankup"),
            CommandDefinition("jobs", HelpCenterCategory.PROGRESS, "jobsgui"),
            CommandDefinition("quests", HelpCenterCategory.PROGRESS, "quests"),
            CommandDefinition("skills", HelpCenterCategory.PROGRESS, "skills"),
            CommandDefinition("slimefun", HelpCenterCategory.TECHNOLOGY, "sf open_guide", HelpCenterFeature.SLIMEFUN),
            CommandDefinition("items", HelpCenterCategory.TECHNOLOGY, "ia", HelpCenterFeature.ITEMS, "ia.user.ia"),
            CommandDefinition("enchants", HelpCenterCategory.TECHNOLOGY, "enchants", HelpCenterFeature.ENCHANTMENTS),
            CommandDefinition("enchanter", HelpCenterCategory.TECHNOLOGY, "enchanter", HelpCenterFeature.ENCHANTMENTS),
            CommandDefinition("builder", HelpCenterCategory.TECHNOLOGY, "builder book", HelpCenterFeature.BUILDER),
            CommandDefinition("mounts", HelpCenterCategory.TECHNOLOGY, "mount", HelpCenterFeature.MOUNTS, "arc.mounts.use"),
            CommandDefinition("chat-global", HelpCenterCategory.SETTINGS, "g"),
            CommandDefinition("chat-local", HelpCenterCategory.SETTINGS, "l"),
            CommandDefinition("lands-borders", HelpCenterCategory.SETTINGS, "lands view here", HelpCenterFeature.LANDS),
            CommandDefinition("trails-on", HelpCenterCategory.SETTINGS, "trails on", HelpCenterFeature.TRAILS),
            CommandDefinition("trails-off", HelpCenterCategory.SETTINGS, "trails off", HelpCenterFeature.TRAILS),
            CommandDefinition("trails-boost-on", HelpCenterCategory.SETTINGS, "trails boost on", HelpCenterFeature.TRAILS),
            CommandDefinition("trails-boost-off", HelpCenterCategory.SETTINGS, "trails boost off", HelpCenterFeature.TRAILS),
            CommandDefinition("particles", HelpCenterCategory.SETTINGS, "pp toggle", HelpCenterFeature.PLAYER_PARTICLES),
            CommandDefinition("tpa-ignore", HelpCenterCategory.SETTINGS, "huskhomes:tpignore", HelpCenterFeature.HUSK_HOMES),
        )

        private val INTENT_DEFINITIONS = listOf(
            IntentDefinition("my", HelpCenterSearchAction.OpenPage(HelpCenterPage.MY)),
            IntentDefinition("player-find", HelpCenterSearchAction.OpenPage(HelpCenterPage.PLAYERS)),
            IntentDefinition("recovery", HelpCenterSearchAction.OpenPage(HelpCenterPage.RECOVERY)),
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
