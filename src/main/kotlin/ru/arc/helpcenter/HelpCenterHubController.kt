package ru.arc.helpcenter

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.minimessage.MiniMessage
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver
import org.bukkit.entity.Player
import ru.arc.core.LifecycleTaskScope
import ru.arc.core.whenCompleteSync
import java.util.concurrent.TimeUnit
import ru.arc.onboarding.OnboardingService
import ru.arc.paper.menu.PaperDialogActionId
import ru.arc.paper.menu.PaperDialogBody
import ru.arc.paper.menu.PaperDialogButton
import ru.arc.paper.menu.PaperDialogScreen

/** Secondary hub surfaces kept out of the primary navigation controller. */
internal class HelpCenterHubController(
    private val settings: HelpCenterSettings,
    private val gateway: HelpCenterGateway,
    private val preferences: HelpCenterPreferenceStore,
    private val availableCatalog: (Player) -> List<HelpCenterCommand>,
    private val executeCatalog: (Player, String) -> Unit,
    private val executeRaw: (Player, String) -> Unit,
    private val openPage: (Player, HelpCenterPage) -> Unit,
    private val navigation: HelpCenterNavigation,
    private val showDialog: (Player, PaperDialogScreen) -> Unit,
    private val executeInventory: (Player, String) -> Boolean,
) : AutoCloseable {
    private val miniMessage = MiniMessage.miniMessage()
    private val tasks = LifecycleTaskScope()

    fun openFavorites(player: Player) {
        val token = navigation.visit(player) { openFavorites(player) }
        showDialog(
            player,
            PaperDialogScreen(
                id = "help.favorites",
                title = text("favorites-title"),
                body = listOf(PaperDialogBody(text("personalization-loading"))),
                buttons = listOf(backTo(player, HelpCenterPage.MY)),
            ),
        )
        preferences.load(player.uniqueId).orTimeout(settings.loadTimeoutSeconds, TimeUnit.SECONDS).whenCompleteSync(tasks) { value, failure ->
            if (!player.isOnline || !navigation.isCurrent(player, token)) return@whenCompleteSync
            if (failure != null || value == null) showPersonalizationUnavailable(player)
            else showFavorites(player, value)
        }
    }

    fun openCatalogAction(player: Player, command: HelpCenterCommand, returnTo: () -> Unit = { openPage(player, HelpCenterPage.COMMANDS) }) {
        val token = navigation.visit(player) { openCatalogAction(player, command, returnTo) }
        preferences.load(player.uniqueId).orTimeout(settings.loadTimeoutSeconds, TimeUnit.SECONDS).whenCompleteSync(tasks) { value, _ ->
            if (!player.isOnline || !navigation.isCurrent(player, token)) return@whenCompleteSync
            val favorite = value?.favorites?.contains(command.id) == true
            val full = value != null && !favorite && value.favorites.size >= HelpCenterPreferences.MAX_FAVORITES
            showDialog(
                player,
                PaperDialogScreen(
                    id = "help.favorite.action",
                    title = text("action-title", "action" to command.label),
                    body = listOf(
                        PaperDialogBody(
                            text(
                                "action-body",
                                "description" to command.description,
                                "command" to command.command,
                            ),
                        ),
                    ) + if (full) listOf(PaperDialogBody(text("favorites-full"))) else emptyList(),
                    buttons = listOf(
                        button("run_action", text("action-run-label")) { executeCatalog(player, command.id) }.let { if (command.opensInventory) it else it.closing() },
                    ) + if (value == null || full) emptyList() else listOf(
                        button(
                            "toggle_favorite",
                            text(if (favorite) "favorite-remove-label" else "favorite-add-label"),
                        ) { toggleFavorite(player, command.id) },
                    ),
                    exitButton = button("back", text("back-label"), action = returnTo),
                    columns = 2,
                ),
            )
        }
    }

    fun recordRecent(player: Player, actionId: String) {
        preferences.recordRecent(player.uniqueId, actionId).exceptionally { null }
    }

    fun openGoals(player: Player) {
        navigation.visit(player) { openGoals(player) }
        showDialog(
            player,
            PaperDialogScreen(
                id = "help.goals",
                title = text("goals-title"),
                body = listOf(PaperDialogBody(text("goals-body"), width = 500)),
                buttons = HelpCenterGoal.entries.map { goal ->
                    button("goal_${goal.name.lowercase()}", text("goal-${goal.name.lowercase()}-label")) { openGoal(player, goal) }
                },
                exitButton = backTo(player, HelpCenterPage.ACTIVITIES),
                columns = 2,
            ),
        )
    }

    fun openItem(player: Player) {
        navigation.visit(player) { openItem(player) }
        val context = gateway.context(player)
        val item = context.heldItem
        val body = if (item == null) {
            listOf(PaperDialogBody(text("item-empty"), width = 500))
        } else {
            listOf(
                PaperDialogBody(
                    text(
                        "item-body",
                        "item" to item.displayName,
                        "amount" to item.amount.toString(),
                        "kind" to plain(if (item.itemsAdderId == null) "item-kind-vanilla" else "item-kind-custom"),
                    ),
                    width = 500,
                ),
            )
        }
        val catalog = availableCatalog(player).associateBy { it.id }
        val buttons = HelpCenterHubPlanner.itemActions(item, context.features).mapNotNull { actionId ->
            when (actionId) {
                "item-recipe" -> item?.let(HelpCenterHubPlanner::itemRecipeCommand)?.let { command ->
                    button("item_recipe", text("item-recipe-label")) { executeInventory(player, command) }
                }
                else -> catalog[actionId]?.let { command ->
                    button("item_$actionId", text("command-label", "label" to command.label)) { executeCatalog(player, actionId) }.let { if (command.opensInventory) it else it.closing() }
                }
            }
        }
        showDialog(
            player,
            PaperDialogScreen(
                id = "help.item",
                title = text("item-title"),
                body = body,
                buttons = buttons,
                exitButton = backTo(player, HelpCenterPage.TECHNOLOGY),
                columns = 2,
            ),
        )
    }

    fun openContext(player: Player) {
        navigation.visit(player) { openContext(player) }
        val snapshot = gateway.context(player)
        val world = when (snapshot.worldKind) {
            HelpCenterWorldKind.VANILLA -> plain("world-kind-vanilla")
            HelpCenterWorldKind.MINING -> plain("world-kind-mining")
            HelpCenterWorldKind.NEW_BIOMES -> plain("world-kind-biomes")
            HelpCenterWorldKind.OTHER -> snapshot.world
        }
        val land = snapshot.landName ?: plain("context-outside-privat")
        showDialog(
            player,
            PaperDialogScreen(
                id = "help.context",
                title = text("context-title"),
                body = listOf(
                    PaperDialogBody(
                        text(
                            "context-body",
                            "world" to world,
                            "server" to snapshot.server,
                            "coordinates" to "${snapshot.x}, ${snapshot.y}, ${snapshot.z}",
                            "land" to land,
                            "item" to (snapshot.heldItem?.displayName ?: plain("context-empty-hand")),
                        ),
                        width = 500,
                    ),
                ),
                buttons = listOf(
                    button("context_item", text("context-item-label")) { openItem(player) },
                    button("context_travel", text("travel-label")) { openPage(player, HelpCenterPage.TRAVEL) },
                    button("context_privat", text("privat-label")) { openPage(player, HelpCenterPage.PRIVAT) },
                    button("context_goals", text("goals-short-label")) { openGoals(player) },
                ),
                exitButton = backTo(player, HelpCenterPage.MY),
                columns = 2,
            ),
        )
    }

    fun openRequests(player: Player) {
        navigation.visit(player) { openRequests(player) }
        runCatching { gateway.pendingRequests(player) }
            .onSuccess { showRequests(player, it) }
            .onFailure { showRequests(player, HelpCenterPendingRequests(), "requests-unavailable") }
    }

    private fun showRequests(player: Player, pending: HelpCenterPendingRequests, notice: String? = null) {
        val entries = availableCatalog(player).associateBy { it.id }
        val ids = listOf("quests", "battle-pass", "vote", "events", "duels", "privat")
        val responseButtons = buildList {
            if (pending.teleport) {
                add(button("tpa_accept", text("request-tpa-accept-label")) { respondToRequest(player, "huskhomes:tpaccept") { it.teleport } })
                add(button("tpa_deny", text("request-tpa-deny-label")) { respondToRequest(player, "huskhomes:tpdeny") { it.teleport } })
            }
            if (pending.duel) {
                add(button("duel_accept", text("request-duel-accept-label")) { respondToRequest(player, "duel accept") { it.duel } })
                add(button("duel_deny", text("request-duel-deny-label")) { respondToRequest(player, "duel deny") { it.duel } })
            }
        }
        showDialog(
            player,
            PaperDialogScreen(
                id = "help.requests",
                title = text("requests-title"),
                body = listOf(
                    PaperDialogBody(
                        text(
                            "requests-body",
                            "hint" to (OnboardingService.nextPendingHintId(player.uniqueId)?.let { plain("onboarding-pending") }
                                ?: plain("onboarding-clear")),
                        ),
                        width = 500,
                    ),
                ) + listOfNotNull(notice?.let { PaperDialogBody(text(it), width = 500) }),
                buttons = responseButtons + ids.mapNotNull { id -> entries[id]?.let { command ->
                    button("request_$id", requestLabel(id, command.label)) {
                        if (id == "privat") openPage(player, HelpCenterPage.PRIVAT) else executeCatalog(player, id)
                    }.let { if (id == "privat" || command.opensInventory) it else it.closing() }
                } },
                exitButton = backTo(player, HelpCenterPage.MY),
                columns = 2,
            ),
        )
    }

    private fun respondToRequest(player: Player, command: String, isPending: (HelpCenterPendingRequests) -> Boolean) {
        val pending = runCatching { gateway.pendingRequests(player) }.getOrNull()
        if (pending == null) showRequests(player, HelpCenterPendingRequests(), "requests-unavailable")
        else if (isPending(pending)) {
            navigation.visit(player)
            // ARC still compiles against pre-dialog Paper; the installed native runtime provides this public method.
            player.javaClass.getMethod("closeDialog").invoke(player)
            executeRaw(player, command)
        } else showRequests(player, pending)
    }

    fun openDiagnostics(player: Player, problem: HelpCenterProblem) {
        navigation.visit(player) { openDiagnostics(player, problem) }
        val context = gateway.context(player)
        val label = plain("problem-${problem.name.lowercase().replace('_', '-')}-label")
        val facts = HelpCenterHubPlanner.diagnosticFacts(problem, context, homesLoaded = null)
        val factsText = facts.joinToString("\n") { fact ->
            val marker = if (fact.positive) "✓" else "•"
            "$marker ${plainFact(fact.id, fact.positive)}"
        }
        val actions = when (problem) {
            HelpCenterProblem.CANNOT_TELEPORT -> listOf(HelpCenterPage.TRAVEL, HelpCenterPage.PLAYERS)
            HelpCenterProblem.CANNOT_CLAIM -> listOf(HelpCenterPage.PRIVAT, HelpCenterPage.CONTEXT)
            HelpCenterProblem.CANNOT_FIND_PLAYER -> listOf(HelpCenterPage.PLAYERS, HelpCenterPage.SETTINGS)
            HelpCenterProblem.LOST_ITEM -> listOf(HelpCenterPage.ITEM, HelpCenterPage.COMMANDS)
            HelpCenterProblem.COMMAND_FAILED -> listOf(HelpCenterPage.COMMANDS, HelpCenterPage.SETTINGS)
        }
        showDialog(
            player,
            PaperDialogScreen(
                id = "help.diagnostic",
                title = text("diagnostic-title", "problem" to label),
                body = listOf(
                    PaperDialogBody(text("diagnostic-body", "facts" to factsText), width = 420),
                    PaperDialogBody(text("diagnostic-${problem.name.lowercase().replace('_', '-')}-help"), width = 420),
                ),
                buttons = actions.mapIndexed { index, page ->
                    button("diagnostic_$index", pageLabel(page)) { openPage(player, page) }
                },
                exitButton = backTo(player, HelpCenterPage.RECOVERY),
                columns = 2,
            ),
        )
    }

    private fun openGoal(player: Player, goal: HelpCenterGoal) {
        navigation.visit(player) { openGoal(player, goal) }
        val catalog = availableCatalog(player).associateBy { it.id }
        val commands = HelpCenterHubPlanner.goalActions(goal).mapNotNull(catalog::get)
        showDialog(
            player,
            PaperDialogScreen(
                id = "help.goal",
                title = text("goal-title", "goal" to plain("goal-${goal.name.lowercase()}-label")),
                body = listOf(PaperDialogBody(text("goal-${goal.name.lowercase()}-body"))) +
                    if (commands.isEmpty() && goal != HelpCenterGoal.TOGETHER) listOf(PaperDialogBody(text("category-empty"))) else emptyList(),
                buttons = ((if (goal == HelpCenterGoal.TOGETHER) listOf(
                    button("goal_players", text("players-label")) { openPage(player, HelpCenterPage.PLAYERS) },
                ) else emptyList()) + commands.map { command ->
                    button("goal_action_${command.id}", text("command-label", "label" to command.label)) {
                        if (command.id == "privat") openPage(player, HelpCenterPage.PRIVAT) else executeCatalog(player, command.id)
                    }.let { if (command.id == "privat" || command.opensInventory) it else it.closing() }
                }).ifEmpty { listOf(button("empty_search", text("commands-label")) { openPage(player, HelpCenterPage.COMMANDS) }) },
                exitButton = button("back", text("back-label")) { openGoals(player) },
                columns = 2,
            ),
        )
    }

    private fun showFavorites(player: Player, value: HelpCenterPreferences) {
        val catalog = availableCatalog(player).associateBy { it.id }
        val favorites = value.favorites.mapNotNull(catalog::get)
        val recent = value.recent.filterNot(value.favorites::contains).mapNotNull(catalog::get)
        val body = listOf(
            PaperDialogBody(
                text("favorites-body", "favorites" to favorites.size.toString(), "recent" to recent.size.toString()),
                width = 500,
            ),
        )
        val buttons = favorites.map { command ->
            button("favorite_${command.id}", text("favorite-command-label", "label" to command.label)) {
                openCatalogAction(player, command) { openFavorites(player) }
            }
        } + recent.map { command ->
            button("recent_${command.id}", text("recent-command-label", "label" to command.label)) {
                openCatalogAction(player, command) { openFavorites(player) }
            }
        } + button("find_action", text("favorite-find-label")) { openFavoritePicker(player) }
        showDialog(
            player,
            PaperDialogScreen(
                id = "help.favorites",
                title = text("favorites-title"),
                body = body,
                buttons = buttons,
                exitButton = backTo(player, HelpCenterPage.MY),
                columns = 2,
            ),
        )
    }

    private fun openFavoritePicker(player: Player, category: HelpCenterCategory? = null) {
        navigation.visit(player) { openFavoritePicker(player, category) }
        val catalog = availableCatalog(player)
        val buttons = if (category == null) {
            HelpCenterCategory.entries.filter { candidate -> catalog.any { it.category == candidate } }.map { candidate ->
                button("pick_${candidate.configId}", text("category-${candidate.configId}-label")) {
                    openFavoritePicker(player, candidate)
                }
            }
        } else {
            catalog.filter { it.category == category }.map { command ->
                button("pick_${command.id}", text("command-label", "label" to command.label),
                    text("command-tooltip", "description" to command.description, "command" to command.command)) {
                    openCatalogAction(player, command) { openFavoritePicker(player, category) }
                }
            }
        }
        showDialog(player, PaperDialogScreen(
            id = "help.favorites.picker",
            title = text("favorites-picker-title"),
            body = listOf(PaperDialogBody(text("favorites-picker-body"), width = 420)),
            buttons = buttons.ifEmpty { listOf(backTo(player, HelpCenterPage.COMMANDS)) },
            exitButton = button("picker_back", text("back-label")) {
                if (category == null) openFavorites(player) else openFavoritePicker(player)
            },
            columns = 2,
        ))
    }

    private fun showPersonalizationUnavailable(player: Player) {
        showDialog(
            player,
            PaperDialogScreen(
                id = "help.favorites.unavailable",
                title = text("favorites-title"),
                body = listOf(PaperDialogBody(text("personalization-unavailable"), width = 500)),
                buttons = listOf(button("find_action", text("commands-label")) { openPage(player, HelpCenterPage.COMMANDS) }),
                exitButton = backTo(player, HelpCenterPage.MY),
            ),
        )
    }

    private fun toggleFavorite(player: Player, id: String) {
        val token = navigation.visit(player) { openFavorites(player) }
        preferences.toggleFavorite(player.uniqueId, id).whenCompleteSync(tasks) { _, failure ->
            if (!player.isOnline || !navigation.isCurrent(player, token)) return@whenCompleteSync
            if (failure != null) player.sendMessage(text("personalization-unavailable"))
            openFavorites(player)
        }
    }

    private fun pageLabel(page: HelpCenterPage): Component = when (page) {
        HelpCenterPage.TRAVEL -> text("travel-label")
        HelpCenterPage.PLAYERS -> text("players-label")
        HelpCenterPage.PRIVAT -> text("privat-label")
        HelpCenterPage.CONTEXT -> text("context-short-label")
        HelpCenterPage.ITEM -> text("context-item-label")
        HelpCenterPage.SETTINGS -> text("category-settings-label")
        else -> text("commands-label")
    }

    private fun requestLabel(id: String, fallback: String): Component = when (id) {
        "quests", "battle-pass", "vote", "events", "duels", "privat" -> text("check-$id-label")
        else -> text("check-command-label", "label" to fallback)
    }

    private fun plainFact(id: String, positive: Boolean): String = plain("fact-$id-${if (positive) "yes" else "no"}")

    private fun backTo(player: Player, page: HelpCenterPage): PaperDialogButton =
        button("back", text("back-label")) { openPage(player, page) }

    private fun button(id: String, label: Component, tooltip: Component = Component.empty(), action: () -> Unit): PaperDialogButton =
        PaperDialogButton(PaperDialogActionId.of(id.replace('-', '_')), label, tooltip, onClick = { action() })

    private fun PaperDialogButton.closing(): PaperDialogButton = copy(closeDialogBeforeAction = true)

    private fun text(key: String, vararg placeholders: Pair<String, String>): Component = miniMessage.deserialize(
        settings.text(key),
        TagResolver.resolver(placeholders.map { (name, value) -> Placeholder.unparsed(name, value) }),
    )

    private fun plain(key: String): String = net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
        .plainText().serialize(text(key))

    override fun close() {
        tasks.close()
        preferences.close()
    }
}
