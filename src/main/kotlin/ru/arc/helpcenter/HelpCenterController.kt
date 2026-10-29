package ru.arc.helpcenter

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.TextColor
import net.kyori.adventure.text.format.TextDecoration
import net.kyori.adventure.text.minimessage.MiniMessage
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import org.bukkit.entity.Player
import ru.arc.ARC
import ru.arc.config.ArcRuntimeProfile
import ru.arc.core.LifecycleTaskScope
import ru.arc.core.whenCompleteSync
import ru.arc.graves.GravesModule
import ru.arc.gui.ArcMenus
import ru.arc.paper.menu.DialogDecoration
import ru.arc.paper.menu.DialogTables
import ru.arc.paper.menu.PaperDialogActionId
import ru.arc.paper.menu.PaperDialogBody
import ru.arc.paper.menu.PaperDialogButton
import ru.arc.paper.menu.PaperDialogClickContext
import ru.arc.paper.menu.PaperDialogInputId
import ru.arc.paper.menu.PaperDialogScreen
import ru.arc.paper.menu.PaperDialogTextInput
import ru.arc.slimefunmenu.SlimefunMenuModule

internal class HelpCenterController(
    private val settings: HelpCenterSettings,
    private val gateway: HelpCenterGateway,
    private val openLands: (Player) -> Unit,
    private val inventoryReturn: HelpCenterInventoryReturnRuntime,
    private val inviteToLand: (Player, HelpCenterPlayer) -> Unit,
    private val navigation: HelpCenterNavigation = HelpCenterNavigation(ru.arc.ARC.instance, inventoryReturn::cancel),
    private val showDialog: (Player, PaperDialogScreen) -> Unit = { player, screen ->
        val close = PaperDialogButton(
            PaperDialogActionId.of("close_menu"),
            MiniMessage.miniMessage().deserialize(settings.text("menu-close-label")),
            width = 200, closeDialogBeforeAction = true, onClick = { navigation.visit(player) },
        )
        ArcMenus.openDialog(player, screen, close, reopen = navigation.returnTarget(player),
            onDismiss = { navigation.visit(player) },
            preferBackFooter = screen.id == "help.now" || screen.id == "help.now.failure")
    },
    private val legacySettings: HelpCenterLegacySettings = HelpCenterLegacySettings(),
    private val closeDialog: (Player) -> Unit = ArcMenus::closeDialog,
    private val enchantmentsGuide: HelpCenterEnchantmentsGuideSettings,
    private val enchantmentsCatalog: () -> HelpCenterEnchantmentsCatalog = HelpCenterEnchantmentsCatalog::fromAeApi,
    private val activeGraveCount: (Player) -> Int = { player -> GravesModule.activeCount(player) },
    private val openGraves: (Player) -> Boolean = { player -> GravesModule.open(player) },
    private val searchService: HelpCenterSearchService = HelpCenterSearchService(options = settings.searchAi),
) {
    private val presentation = HelpCenterPresentation(settings)
    private val present: (Player, PaperDialogScreen) -> Unit = showDialog
    private val miniMessage = MiniMessage.miniMessage()
    private val plainText = PlainTextComponentSerializer.plainText()
    private val tasks = LifecycleTaskScope()
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
            definition.opensInventory,
            definition.anyPermissions,
            opensDialog = definition.id in NATIVE_DIALOG_COMMANDS,
        )
    }
    private val catalogById = catalog.associateBy { it.id }
    private val searchCatalog: List<HelpCenterSearchEntry> = buildList {
        // Slimefun's menu route is explained by its always-visible network guide.
        catalog.filterNot { it.id == "slimefun" }.forEach { command ->
            add(
                HelpCenterSearchEntry(
                    id = command.id,
                    label = command.label,
                    description = command.description,
                    keywords = command.keywords,
                    action = when (command.id) {
                        "privat" -> HelpCenterSearchAction.OpenPage(HelpCenterPage.PRIVAT)
                        "rules" -> HelpCenterSearchAction.OpenPage(HelpCenterPage.RULES)
                        else -> HelpCenterSearchAction.Execute(command.command)
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
        addAll(HelpCenterGuideCatalog.searchEntries(settings))
    }
    private val hub by lazy {
        HelpCenterHubController(
            settings = settings,
            gateway = gateway,
            availableCatalog = ::availableCatalog,
            executeCatalog = ::executeCatalog,
            executeRaw = ::execute,
            openPage = ::open,
            navigation = navigation,
            showDialog = present,
            executeInventory = ::executeInventory,
        )
    }

    private val dungeons by lazy {
        HelpCenterDungeonsController(settings, navigation, present, ::openDungeonMenu, ::execute)
    }

    private val enchantments by lazy {
        HelpCenterEnchantmentsController(settings, enchantmentsGuide, navigation, present, ::executeInventory, enchantmentsCatalog)
    }

    private val personalSettings by lazy {
        HelpCenterSettingsController(settings, gateway, legacySettings, navigation, present,
            ::executeCatalog, ::executeInventory, ::openSettingsRoot)
    }

    fun close() {
        active = false
        navigation.close()
        tasks.close()
        searchService.close()
        personalSettings.close()
        hub.close()
        inventoryReturn.close()
    }

    fun open(player: Player, page: HelpCenterPage = HelpCenterPage.ROOT) {
        ArcMenus.beginDialogFlow(player)
        when (page) {
            HelpCenterPage.ROOT -> openRoot(player)
            HelpCenterPage.HELP -> openHelp(player)
            HelpCenterPage.RULES -> openRules(player)
            HelpCenterPage.NOW, HelpCenterPage.MY -> openNow(player)
            HelpCenterPage.GUIDE -> openGuide(player)
            HelpCenterPage.FEATURES -> openSearchCatalog(player)
            HelpCenterPage.DUNGEONS_GUIDE -> openDungeonsGuide(player)
            HelpCenterPage.COMMANDS -> openCommands(player)
            HelpCenterPage.RTP -> openRtp(player)
            HelpCenterPage.TRAVEL -> openTravel(player)
            HelpCenterPage.WARPS -> openWarps(player)
            HelpCenterPage.PRIVAT -> {
                markNavigation(player)
                openLands(player)
            }
            HelpCenterPage.ACTIVITIES -> openCategory(player, HelpCenterCategory.ACTIVITIES, returnToRoot = true)
            HelpCenterPage.PLAYERS -> openPlayers(player)
            HelpCenterPage.TECHNOLOGY -> openCategory(player, HelpCenterCategory.TECHNOLOGY, returnToRoot = true)
            HelpCenterPage.ENCHANTMENTS -> enchantments.open(player) { openRoot(player) }
            HelpCenterPage.SETTINGS -> openSettings(player)
            HelpCenterPage.RECOVERY -> openRecovery(player)
            HelpCenterPage.GOALS -> hub.openGoals(player)
            HelpCenterPage.ITEM -> hub.openItem(player)
            HelpCenterPage.CONTEXT -> hub.openContext(player)
            HelpCenterPage.REQUESTS -> hub.openRequests(player)
        }
    }

    fun search(player: Player, query: String) {
        ArcMenus.beginDialogFlow(player)
        openSearch(player, query)
    }

    private fun openRoot(player: Player) {
        val token = markNavigation(player) { openRoot(player) }
        gateway.loadProfile(player, settings.loadTimeoutSeconds).whenCompleteSync(tasks) { profile, failure ->
            if (!active || !player.isOnline || !navigation.isCurrent(player, token)) return@whenCompleteSync
            showRoot(player, profile.takeIf { failure == null })
        }
    }

    private fun showRoot(player: Player, profile: HelpCenterProfile? = null) {
        val activeGraves = runCatching { activeGraveCount(player) }.getOrDefault(0).coerceAtLeast(0)
        val body = mutableListOf<PaperDialogBody>()
        if (profile == null) {
            body += PaperDialogBody(text("my-error"), width = 506)
        } else {
            val unavailable = settings.text("not-available")
            fun label(key: String, icon: DialogDecoration.FieldIcon) = presentation.fieldLabel(icon, text(key))
            val rows = listOf(
                    label("table-player-label", DialogDecoration.FieldIcon.PLAYER) to Component.text(profile.playerName),
                    label("table-online-label", DialogDecoration.FieldIcon.ONLINE) to Component.text(profile.onlinePlayers),
                    label("table-rank-label", DialogDecoration.FieldIcon.RANK) to text("table-rank-value", "value" to (profile.rank ?: unavailable)),
                    label("table-balance-label", DialogDecoration.FieldIcon.BALANCE) to currencyValue("table-coins-value", profile.balance),
                    label("table-homes-label", DialogDecoration.FieldIcon.HOMES) to text(
                        "table-slots-value",
                        "used" to (profile.homes?.usedSlots?.toString() ?: unavailable),
                        "maximum" to (profile.homes?.maxSlots?.toString() ?: unavailable),
                    ),
                    label("table-lands-label", DialogDecoration.FieldIcon.LANDS) to text(
                        "table-lands-value",
                        "count" to (profile.lands?.toString() ?: unavailable),
                        "chunks" to (profile.claimedChunks?.toString() ?: unavailable),
                    ),
                    label("table-tokens-label", DialogDecoration.FieldIcon.TOKENS) to currencyValue("table-tokens-value", profile.tokens),
                    label("table-world-label", DialogDecoration.FieldIcon.WORLD) to Component.text(worldLabel(profile.worldKind, profile.world), TextColor.color(0xe6fff3)),
                )
            body += presentation.filledFacts(rows.chunked(2), 508, DialogTables.PanelSkin.ORNATE)
        }
        body += presentation.section(text("root-sections-heading"), 506)
        present(
            player,
            PaperDialogScreen(
                id = "help.root",
                title = text("root-title"),
                body = body,
                buttons = buildList {
                    if (activeGraves > 0) {
                        add(button("graves", text("main-graves-label", "count" to activeGraves.toString()), text("graves-tooltip")) {
                            openGraves(player)
                        })
                    }
                    add(button("now", text("now-label"), text("now-tooltip")) { openNow(player) })
                    availableCatalog(player).firstOrNull { it.id == "teams" }?.let { teams ->
                        add(button("teams", text("teams-label"), commandTooltip(teams.id)) { executeCatalog(player, teams.id) })
                    }
                    add(button("travel", text("travel-label"), text("travel-tooltip")) { openTravel(player) })
                    add(button("privat", text("privat-label"), text("privat-tooltip")) { open(player, HelpCenterPage.PRIVAT) })
                    add(rootCategoryButton(player, HelpCenterCategory.ACTIVITIES))
                    add(rootCategoryButton(player, HelpCenterCategory.TRADE))
                    val available = availableCatalog(player).associateBy { it.id }
                    if ("dungeons" in available) {
                        add(button("dungeons", text("main-dungeons-label"), text("dungeons-menu-tooltip")) { openDungeonMenu(player) })
                    }
                    for (id in listOf("rank", "jobs", "skills", "quests", "enchants")) {
                        if (id in available) {
                            add(button(id, text("main-$id-label"), commandTooltip(id)) { executeCatalog(player, id) })
                        }
                    }
                    available["mounts"]?.let {
                        add(button("mounts", text("main-mounts-label"), commandTooltip("mounts")) {
                            executeCatalog(player, "mounts")
                        })
                    }
                    add(button("search", text("commands-label"), text("commands-tooltip")) { openCommands(player) })
                    add(button("settings", text("category-settings-label"), text("category-settings-tooltip")) { openSettings(player) })
                }.map {
                    it.copy(
                        width = 166,
                        label = if (it.id.value == "graves") it.label
                        else text("main-${it.id.value.removePrefix("root_")}-label"),
                    )
                },
                columns = 3,
            ),
        )
    }

    private fun openHelp(player: Player) {
        markNavigation(player) { openHelp(player) }
        present(
            player,
            PaperDialogScreen(
                id = "help.help",
                title = text("help-title"),
                body = listOf(presentation.panel(text("help-body"), 468)),
                buttons = listOf(
                    button("help_features", text("guide-catalog-label"), text("guide-catalog-tooltip")) { openSearchCatalog(player) },
                    button("help_commands", text("help-commands-label"), text("commands-tooltip")) { openCommands(player) },
                    button("help_recovery", text("help-recovery-label"), text("recovery-tooltip")) {
                        openRecovery(player, HelpCenterPage.HELP)
                    },
                    button("help_guide", text("help-guide-label"), text("guide-tooltip")) {
                        openGuide(player) { openHelp(player) }
                    },
                    button("help_rules", text("rules-label"), text("rules-tooltip")) { openRules(player) },
                    button("help_goals", text("help-goals-label"), text("goals-tooltip")) {
                        hub.openGoals(player, HelpCenterPage.HELP)
                    },
                    button("help_players", text("help-players-label"), text("players-tooltip")) { openPlayers(player) },
                    button("help_requests", text("help-requests-label"), text("requests-tooltip")) {
                        hub.openRequests(player, HelpCenterPage.HELP)
                    },
                    button("help_context", text("help-context-label"), text("context-tooltip")) {
                        hub.openContext(player, HelpCenterPage.HELP)
                    },
                ).map { it.copy(width = 230) },
                columns = 2,
            ),
        )
    }

    private fun openRules(player: Player) {
        markNavigation(player) { openRules(player) }
        present(player, PaperDialogScreen(
            id = "help.rules",
            title = text("rules-title"),
            body = listOf(presentation.panel(text("rules-body"), 468)),
            buttons = emptyList(),
        ))
    }

    private fun openDungeonsGuide(player: Player) {
        val returnTo = navigation.returnTarget(player) ?: { open(player, HelpCenterPage.ACTIVITIES) }
        dungeons.openGuide(player) { dungeons.open(player, returnTo) }
    }

    internal fun openDungeonsGuide(player: Player, returnTo: () -> Unit) {
        ArcMenus.beginDialogFlow(player)
        dungeons.openGuide(player, returnTo)
    }

    private fun openNow(player: Player) {
        val token = markNavigation(player) { openNow(player) }
        present(
            player,
            PaperDialogScreen(
                id = "help.now",
                title = text("now-title"),
                body = listOf(PaperDialogBody(text("my-loading"), width = 500)),
                buttons = nowButtons(player),
                exitButton = rootButton(player),
                columns = 2,
            ),
        )
        gateway.loadProfile(player, settings.loadTimeoutSeconds).whenCompleteSync(tasks) { profile, failure ->
            if (!active || !player.isOnline || !navigation.isCurrent(player, token)) return@whenCompleteSync
            if (failure == null && profile != null) showNow(player, profile) else showMyFailure(player)
        }
    }

    private fun showNow(player: Player, profile: HelpCenterProfile) {
        val unavailable = settings.text("not-available")
        fun label(key: String, icon: DialogDecoration.FieldIcon) = presentation.fieldLabel(icon, text(key))
        val rows = listOf(
            label("table-player-label", DialogDecoration.FieldIcon.PLAYER) to Component.text(profile.playerName),
            label("table-rank-label", DialogDecoration.FieldIcon.RANK) to text("table-rank-value", "value" to (profile.rank ?: unavailable)),
            label("table-balance-label", DialogDecoration.FieldIcon.BALANCE) to currencyValue("table-coins-value", profile.balance),
            label("table-tokens-label", DialogDecoration.FieldIcon.TOKENS) to currencyValue("table-tokens-value", profile.tokens),
            label("table-homes-label", DialogDecoration.FieldIcon.HOMES) to text("table-slots-value",
                "used" to (profile.homes?.usedSlots?.toString() ?: unavailable),
                "maximum" to (profile.homes?.maxSlots?.toString() ?: unavailable)),
            label("table-lands-label", DialogDecoration.FieldIcon.LANDS) to text("table-lands-value",
                "count" to (profile.lands?.toString() ?: unavailable),
                "chunks" to (profile.claimedChunks?.toString() ?: unavailable)),
            label("table-world-label", DialogDecoration.FieldIcon.WORLD) to Component.text(worldLabel(profile.worldKind, profile.world)),
            label("table-coordinates-label", DialogDecoration.FieldIcon.COORDINATES) to Component.text("${profile.x}, ${profile.y}, ${profile.z}"),
            label("table-chat-label", DialogDecoration.FieldIcon.CHAT) to text(if (profile.chatMode == HelpCenterChatMode.GLOBAL) "table-chat-global" else "table-chat-local"),
        )
        present(
            player,
            PaperDialogScreen(
                id = "help.now",
                title = text("now-title"),
                body = buildList {
                    add(presentation.compactFacts(rows.take(2), 468, DialogTables.PanelSkin.ORNATE))
                    add(presentation.section(text("my-resources-heading"), 468, DialogDecoration.HeadingStyle.DIAMOND))
                    add(presentation.groupedFacts(listOf(
                        DialogTables.FactsGroup(text("my-wallet-heading"), rows.slice(2..3)),
                        DialogTables.FactsGroup(text("my-territory-heading"), rows.slice(4..5)),
                    ), 468, DialogTables.PanelSkin.ORNATE))
                    add(presentation.section(text("my-location-heading"), 468))
                    add(presentation.compactFacts(rows.drop(6), 468, DialogTables.PanelSkin.NAVY))
                },
                buttons = nowButtons(player),
                exitButton = rootButton(player),
                columns = 2,
            ),
        )
    }

    private fun currencyValue(key: String, value: String?): Component = text(
        if (settings.decorationFont.isEmpty()) key else "table-currency-amount-value",
        "value" to (value ?: settings.text("not-available")),
    )

    private fun worldLabel(kind: HelpCenterWorldKind, raw: String): String =
        resolveHelpCenterWorldLabel(kind, raw, settings::text)

    private fun showMyFailure(player: Player) {
        present(
            player,
            PaperDialogScreen(
                id = "help.now.failure",
                title = text("now-title"),
                body = listOf(PaperDialogBody(text("my-error"), width = 500)),
                buttons = nowButtons(player),
                exitButton = rootButton(player),
                columns = 2,
            ),
        )
    }

    private fun nowButtons(player: Player): List<PaperDialogButton> = listOf(
        button("now_homes", text("my-homes-label"), text("my-homes-tooltip")) { openTravel(player) },
        button("now_lands", text("my-lands-label"), text("my-lands-tooltip")) { open(player, HelpCenterPage.PRIVAT) },
    ).map { it.copy(width = 230) }

    private fun openGuide(player: Player, returnTo: () -> Unit = { openRoot(player) }) {
        markNavigation(player) { openGuide(player, returnTo) }
        present(
            player,
            PaperDialogScreen(
                id = "help.guide",
                title = text("guide-title"),
                body = listOf(presentation.panel(text("guide-body"), 468)),
                buttons = listOf(
                    button("kit", text("kit-label"), commandTooltip("kit")) { executeCatalog(player, "kit") }.closing(),
                    button("jobs", text("jobs-label"), commandTooltip("jobs")) { executeCatalog(player, "jobs") },
                    button("home", text("travel-label"), text("travel-tooltip")) { openTravel(player) },
                    button("privat", text("privat-label"), text("privat-tooltip")) { open(player, HelpCenterPage.PRIVAT) },
                    button("rules", text("rules-label"), text("rules-tooltip")) { openRules(player) },
                ).map { it.copy(width = 230) },
                exitButton = backButton("back", player, action = { returnTo() }),
                columns = 2,
            ),
        )
    }

    private fun openCommands(player: Player) {
        markNavigation(player) { openCommands(player) }
        present(
            player,
            PaperDialogScreen(
                id = "help.commands",
                title = text("commands-title"),
                body = listOf(presentation.panel(text("commands-body"), 468)),
                inputs = listOf(PaperDialogTextInput(SEARCH_INPUT, text("search-input"), maxLength = 120, width = 460)),
                buttons = listOf(
                    contextButton("search", text("search-label"), text("search-tooltip")) { context ->
                        openSearch(player, context.text(SEARCH_INPUT).orEmpty())
                    },
                    button("search_catalog", text("search-catalog-label"), text("search-catalog-tooltip")) {
                        openSearchCatalog(player)
                    },
                ).map { it.copy(width = 230) },
                exitButton = rootButton(player),
                columns = 2,
            ),
        )
    }

    private fun openSearchCatalog(player: Player) {
        markNavigation(player) { openSearchCatalog(player) }
        present(player, PaperDialogScreen(
                id = "help.catalog",
                title = text("search-catalog-title"),
                body = listOf(presentation.panel(text("guide-catalog-body", "count" to HelpCenterGuideCatalog.topics.size.toString()), 468)),
                buttons = HelpCenterCategory.entries.map { category ->
                    val topics = HelpCenterGuideCatalog.topics.filter { it.category == category }
                    button("category_${category.configId}", text("category-${category.configId}-label"),
                        text("guide-category-tooltip", "topics" to topics.joinToString("\n") { settings.guide(it.id).title })) {
                        openGuideCategory(player, category)
                    }.copy(width = 230)
                },
                exitButton = backButton("back", player, ::openCommands),
                columns = 2,
            ))
    }

    private fun openGuideCategory(player: Player, category: HelpCenterCategory, requestedPage: Int = 0) {
        val topics = HelpCenterGuideCatalog.topics.filter { it.category == category }
        val pages = maxOf(1, (topics.size + GUIDE_PAGE_SIZE - 1) / GUIDE_PAGE_SIZE)
        val page = requestedPage.coerceIn(0, pages - 1)
        markNavigation(player) { openGuideCategory(player, category, page) }
        present(player, PaperDialogScreen(
            id = "help.guides.${category.configId}",
            title = text("category-${category.configId}-title"),
            body = listOf(presentation.panel(text("guide-category-body", "count" to topics.size.toString(),
                "page" to (page + 1).toString(), "pages" to pages.toString()), 468)),
            buttons = buildList {
                topics.drop(page * GUIDE_PAGE_SIZE).take(GUIDE_PAGE_SIZE).forEach { topic ->
                    add(guideButton(player, topic.id) { openGuideCategory(player, category, page) })
                }
                if (page > 0) add(button("guide_previous", text("guide-previous-label")) { openGuideCategory(player, category, page - 1) })
                if (page + 1 < pages) add(button("guide_next", text("guide-next-label")) { openGuideCategory(player, category, page + 1) })
            }.map { it.copy(width = 230) },
            exitButton = backButton("back", player, ::openSearchCatalog),
            columns = 2,
        ))
    }

    private fun guideButton(player: Player, id: String, returnTo: () -> Unit): PaperDialogButton {
        val guide = settings.guide(id)
        return button("guide_${id.replace('-', '_')}", text("guide-topic-label", "label" to guide.title),
            text("search-result-tooltip", "description" to guide.description)) { openFeatureGuide(player, id, returnTo) }
    }

    private fun openFeatureGuide(player: Player, id: String, returnTo: () -> Unit) {
        val topic = HelpCenterGuideCatalog.byId[id] ?: return
        val guide = settings.guide(id)
        val reopen = { openFeatureGuide(player, id, returnTo) }
        markNavigation(player, reopen)
        val available = availableCatalog(player).associateBy { it.id }
        val features = gateway.features()
        val buttons = topic.actions.mapNotNull { action ->
            when (action) {
                is HelpCenterGuideAction.Command -> available[action.id]?.let { commandButton(player, it) }
                is HelpCenterGuideAction.Topic -> guideButton(player, action.id, reopen)
                is HelpCenterGuideAction.Page -> {
                    val allowed = when (action.page) {
                        HelpCenterPage.PRIVAT -> HelpCenterFeature.LANDS in features
                        HelpCenterPage.WARPS -> "warps" in available
                        HelpCenterPage.DUNGEONS_GUIDE -> "dungeons" in available
                        else -> true
                    }
                    if (!allowed) null else button("guide_page_${action.page.name.lowercase()}", text(action.labelKey)) {
                        when (action.page) {
                            HelpCenterPage.PRIVAT -> if (HelpCenterFeature.LANDS in gateway.features()) open(player, action.page)
                            HelpCenterPage.DUNGEONS_GUIDE -> if (availableCatalog(player).any { it.id == "dungeons" }) open(player, action.page)
                            else -> open(player, action.page)
                        }
                    }
                }
                HelpCenterGuideAction.SlimefunMenu -> if (!gateway.canOpenSlimefunMenu()) null else {
                    button("guide_slimefun_menu", text("guide-slimefun-menu-label")) {
                        markNavigation(player)
                        if (!gateway.openSlimefunMenu(player)) player.sendMessage(text("action-unavailable"))
                    }
                }
            }
        }
        present(player, PaperDialogScreen(
            id = "help.guide.$id",
            title = text("guide-topic-title", "title" to guide.title),
            body = listOf(presentation.panel(text("guide-topic-body", "description" to guide.description, "body" to guide.body), 468)),
            buttons = buttons.map { it.copy(width = 230) },
            exitButton = backButton("back", player, action = { returnTo() }),
            columns = 2,
        ))
    }

    private fun openPlayers(player: Player, rawQuery: String = "", page: Int = 0, localOnly: Boolean = false) {
        markNavigation(player) { openPlayers(player, rawQuery, page, localOnly) }
        val query = rawQuery.trim().take(16)
        val currentServer = gateway.context(player).server
        val result = HelpCenterPlanner.playerPage(
            player.uniqueId, gateway.onlinePlayers(), query, page,
            server = currentServer.takeIf { localOnly },
        )
        val body = mutableListOf(PaperDialogBody(text(
            "players-body", "count" to result.items.size.toString(), "total" to result.total.toString(),
            "page" to (result.page + 1).toString(), "pages" to result.pages.toString(),
            "scope" to if (localOnly) currentServer else plainText("players-all-servers"),
        ), width = 420))
        if (result.items.isEmpty()) body += PaperDialogBody(text("players-empty"))
        val returnToList = { openPlayers(player, query, result.page, localOnly) }
        present(player, PaperDialogScreen(
            id = "help.players",
            title = text("players-title"), body = body,
            inputs = listOf(PaperDialogTextInput(PLAYER_SEARCH_INPUT, text("players-input"), initial = query, maxLength = 16)),
            buttons = result.items.mapIndexed { index, target ->
                button("player_$index", text("player-label", "player" to target.name),
                    text("player-tooltip", "server" to (target.server ?: plainText("player-server-unknown")))) {
                    openPlayer(player, target, returnToList)
                }
            } + buildList {
                availableCatalog(player).firstOrNull { it.id == "teams" }?.let { add(commandButton(player, it)) }
                add(contextButton("find_player", text("players-search-label"), text("players-search-tooltip")) {
                    openPlayers(player, it.text(PLAYER_SEARCH_INPUT).orEmpty(), localOnly = localOnly)
                })
                add(button("player_scope", text(if (localOnly) "players-network-label" else "players-local-label")) {
                    openPlayers(player, query, localOnly = !localOnly)
                })
                if (query.isNotBlank()) add(button("clear_filter", text("players-clear-label")) {
                    openPlayers(player, localOnly = localOnly)
                })
                add(button("refresh_players", text("refresh-label"), text("refresh-tooltip"), returnToList))
                if (result.page > 0) add(button("previous", text("page-previous-label")) {
                    openPlayers(player, query, result.page - 1, localOnly)
                })
                if (result.page + 1 < result.pages) add(button("next", text("page-next-label")) {
                    openPlayers(player, query, result.page + 1, localOnly)
                })
            },
            exitButton = rootButton(player), columns = 2,
        ))
    }

    private fun openPlayer(player: Player, target: HelpCenterPlayer, returnToList: () -> Unit = { openPlayers(player) }) {
        markNavigation(player) { openPlayer(player, target, returnToList) }
        val buttons = mutableListOf(
            button("tpa", text("player-tpa-label")) { execute(player, HelpCenterCommands.teleportRequest(target.name)) }.closing(),
            button("tpahere", text("player-tpahere-label")) { execute(player, HelpCenterCommands.teleportHere(target.name)) }.closing(),
            button("message", text("player-message-label")) { openPlayerMessage(player, target, returnToList) },
            button("pay", text("player-pay-label")) { openPlayerPayment(player, target, returnToList) },
        )
        if (HelpCenterFeature.DUELS in gateway.features()) {
            buttons += button("duel", text("player-duel-label")) { execute(player, HelpCenterCommands.duel(target.name)) }.closing()
        }
        if (HelpCenterFeature.LANDS in gateway.features()) {
            buttons += button("invite", text("player-invite-label")) { inviteToLand(player, target) }
        }
        present(
            player,
            PaperDialogScreen(
                id = "help.player",
                title = text("player-title", "player" to target.name),
                body = listOf(
                    DialogTables.body(
                        rows = listOf(
                            text("table-player-label") to Component.text(target.name),
                            text("table-server-label") to Component.text(target.server ?: plainText("player-server-unknown")),
                        ),
                        headers = text("table-label-heading") to text("table-value-heading"),
                        frame = DialogTables.Frame.EPIC,
                        width = 320,
                    ),
                    PaperDialogBody(text("table-player-help"), width = 500),
                ),
                buttons = buttons,
                exitButton = button("back", text("back-label"), action = returnToList),
                columns = 2,
            ),
        )
    }

    private fun openPlayerMessage(player: Player, target: HelpCenterPlayer, returnToList: () -> Unit = { openPlayers(player) }) {
        markNavigation(player)
        present(
            player,
            PaperDialogScreen(
                id = "help.message",
                title = text("message-title", "player" to target.name),
                body = listOf(PaperDialogBody(text("message-body", "player" to target.name))),
                inputs = listOf(PaperDialogTextInput(MESSAGE_INPUT, text("message-input"), maxLength = 128)),
                buttons = listOf(contextButton("send", text("message-send-label")) { context ->
                    val command = runCatching { HelpCenterCommands.message(target.name, context.text(MESSAGE_INPUT).orEmpty()) }.getOrNull()
                    if (command == null) {
                        player.sendMessage(text("invalid-message"))
                        openPlayerMessage(player, target, returnToList)
                    } else execute(player, command)
                }.closing()),
                exitButton = backButton("back", player, action = { openPlayer(it, target, returnToList) }),
            ),
        )
    }

    private fun openPlayerPayment(player: Player, target: HelpCenterPlayer, returnToList: () -> Unit = { openPlayers(player) }) {
        markNavigation(player)
        present(
            player,
            PaperDialogScreen(
                id = "help.pay",
                title = text("pay-title", "player" to target.name),
                body = listOf(PaperDialogBody(text("pay-body", "player" to target.name))),
                inputs = listOf(PaperDialogTextInput(AMOUNT_INPUT, text("pay-input"), maxLength = 16)),
                buttons = listOf(contextButton("continue", text("pay-continue-label")) { context ->
                    val rawAmount = context.text(AMOUNT_INPUT).orEmpty()
                    val command = runCatching { HelpCenterCommands.pay(target.name, rawAmount) }.getOrNull()
                    if (command == null) {
                        player.sendMessage(text("invalid-amount"))
                        openPlayerPayment(player, target, returnToList)
                    } else openPaymentConfirmation(player, target, rawAmount.replace(',', '.').trim(), command, returnToList)
                }),
                exitButton = backButton("back", player, action = { openPlayer(it, target, returnToList) }),
            ),
        )
    }

    private fun openPaymentConfirmation(player: Player, target: HelpCenterPlayer, amount: String, command: String, returnToList: () -> Unit = { openPlayers(player) }) {
        markNavigation(player)
        present(
            player,
            PaperDialogScreen(
                id = "help.pay.confirm",
                title = text("pay-confirm-title"),
                body = listOf(PaperDialogBody(text("pay-confirm-body", "player" to target.name, "amount" to amount))),
                buttons = listOf(button("confirm", text("pay-confirm-label")) { execute(player, command) }.closing()),
                exitButton = backButton("back", player, action = { openPlayerPayment(it, target, returnToList) }),
            ),
        )
    }

    private fun openSettings(player: Player) = personalSettings.open(player)

    private fun openSettingsRoot(player: Player) {
        if (ARC.instance.runtimeProfile == ArcRuntimeProfile.SLIMEFUN) {
            markNavigation(player)
            SlimefunMenuModule.open(player)
        } else {
            openRoot(player)
        }
    }

    private fun openRecovery(player: Player, returnTo: HelpCenterPage = HelpCenterPage.COMMANDS) {
        markNavigation(player) { openRecovery(player, returnTo) }
        present(
            player,
            PaperDialogScreen(
                id = "help.recovery",
                title = text("recovery-title"),
                body = listOf(presentation.callout(text("recovery-body"), 500)),
                buttons = HelpCenterProblem.entries.map { problem ->
                    button(
                        "problem_${problem.name.lowercase()}",
                        text("problem-${problem.name.lowercase().replace('_', '-')}-label"),
                    ) { hub.openDiagnostics(player, problem) { openRecovery(player, returnTo) } }
                }.plus(button("stuck", text("stuck-label"), commandTooltip("stuck")) { execute(player, "stuck") }.closing())
                    .map { it.copy(width = 230) },
                exitButton = backButton("back", player, action = { open(player, returnTo) }),
                columns = 2,
            ),
        )
    }

    private fun openSearch(player: Player, rawQuery: String) {
        val query = rawQuery.trim().take(120)
        val token = markNavigation(player) { openSearch(player, query) }
        present(player, PaperDialogScreen(
            id = "help.search",
            title = text("search-title"),
            body = listOf(presentation.panel(text("search-loading"), 468)),
            inputs = listOf(searchInput(query)),
            buttons = searchControls(player),
            exitButton = backButton("back", player, ::openCommands),
            columns = 2,
        ))
        searchService.search(availableSearchCatalog(player), query, settings.maxSearchResults, playerId = player.uniqueId)
            .whenCompleteSync(tasks) { result, failure ->
                if (!active || !player.isOnline || !navigation.isCurrent(player, token)) return@whenCompleteSync
                val available = availableSearchCatalog(player)
                val allowedIds = available.mapTo(hashSetOf()) { it.id }
                val resolved = if (failure == null && result != null) {
                    result.copy(entries = result.entries.filter { it.id in allowedIds })
                } else HelpCenterSearchResult(HelpCenterPlanner.search(available, query, settings.maxSearchResults), HelpCenterSearchMode.LEXICAL)
                showSearchResults(player, query, resolved)
            }
    }

    private fun searchInput(query: String) =
        PaperDialogTextInput(SEARCH_INPUT, text("search-input"), initial = query, maxLength = 120, width = 460)

    private fun searchControls(player: Player): List<PaperDialogButton> = listOf(
        contextButton("search", text("search-label"), text("search-tooltip")) { context ->
            openSearch(player, context.text(SEARCH_INPUT).orEmpty())
        },
        button("search_catalog", text("search-catalog-label"), text("search-catalog-tooltip")) { openSearchCatalog(player) },
    ).map { it.copy(width = 230) }

    private fun showSearchResults(player: Player, query: String, result: HelpCenterSearchResult) {
        val available = availableSearchCatalog(player).associateBy { it.id }
        val entries = result.entries.mapNotNull { available[it.id] }
        val summary = text("search-body", "query" to query.ifEmpty { plainText("search-all-query") }, "count" to entries.size.toString())
        val body = buildList {
            add(presentation.panel(summary, 468))
            if (result.mode == HelpCenterSearchMode.LEXICAL) add(presentation.paragraph(text("search-lexical-notice"), 468))
            if (entries.isEmpty()) add(presentation.panel(text("search-empty"), 468))
        }
        present(player, PaperDialogScreen(
            id = "help.search",
            title = text("search-title"),
            body = body,
            inputs = listOf(searchInput(query)),
            buttons = entries.map { searchResultButton(player, it) { showSearchResults(player, query, result) } }
                .map { it.copy(width = 230) } + searchControls(player),
            exitButton = backButton("back", player, ::openCommands),
            columns = 2,
        ))
    }

    private fun openCategory(player: Player, category: HelpCenterCategory, returnToRoot: Boolean = false) {
        markNavigation(player) { openCategory(player, category, returnToRoot) }
        val hidden = when (category) {
            HelpCenterCategory.PROGRESS -> setOf("rankup")
            HelpCenterCategory.TECHNOLOGY -> setOf("items")
            else -> emptySet()
        }
        val entries = availableCatalog(player).filter { it.category == category && it.id !in hidden }
        val bodyKey = when (category) {
            HelpCenterCategory.ACTIVITIES, HelpCenterCategory.TRADE, HelpCenterCategory.PROGRESS,
            HelpCenterCategory.TECHNOLOGY ->
                "category-${category.configId}-body"
            else -> "category-body"
        }
        present(
            player,
            PaperDialogScreen(
                id = "help.category.${category.configId}",
                title = text("category-${category.configId}-title"),
                body = listOfNotNull(
                    presentation.paragraph(text(if (entries.isEmpty()) "category-empty" else bodyKey), 500),
                    presentation.separator(500),
                ),
                buttons = buildList {
                    if (category == HelpCenterCategory.TECHNOLOGY) {
                        add(button("held_item", text("context-item-label"), text("item-tooltip")) { hub.openItem(player) })
                    }
                    addAll(entries.map { commandButton(player, it) })
                }.ifEmpty { listOf(button("empty_search", text("commands-label")) { openCommands(player) }) }
                    .map { it.copy(width = 230) },
                exitButton = backButton("back", player, if (returnToRoot) ::openRoot else ::openSearchCatalog),
                columns = 2,
            ),
        )
    }

    private fun openTravel(player: Player) {
        val token = markNavigation(player) { openTravel(player) }
        present(
            player,
            PaperDialogScreen(
                id = "help.travel",
                title = text("travel-title"),
                body = listOf(PaperDialogBody(text("travel-loading"))),
                buttons = listOf(button("root", text("root-label"), text("main-menu-tooltip")) { openRoot(player) }),
            ),
        )
        gateway.loadHomes(player, settings.loadTimeoutSeconds).whenCompleteSync(tasks) { homes, failure ->
            if (!active || !player.isOnline || !navigation.isCurrent(player, token)) return@whenCompleteSync
            if (failure == null && homes != null) showTravel(player, homes) else showTravelFailure(player)
        }
    }

    private fun showTravel(player: Player, snapshot: HelpCenterHomes) {
        val homes = snapshot.homes
            .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.name })
            .take(settings.maxHomes)
        val unavailable = settings.text("not-available")
        val body = mutableListOf(
            presentation.facts(
                rows = listOf(listOf(
                    text("table-homes-label") to text(
                        "table-slots-value",
                        "used" to snapshot.usedSlots.toString(),
                        "maximum" to snapshot.maxSlots.toString(),
                    ),
                    text("table-warps-label") to text(
                        "table-slots-value",
                        "used" to (snapshot.usedWarps?.toString() ?: unavailable),
                        "maximum" to (snapshot.maxWarps?.toString() ?: unavailable),
                    ),
                )),
                width = 468,
                corners = true,
            ),
            presentation.paragraph(text("travel-body"), 500),
        )
        if (homes.isEmpty()) body += PaperDialogBody(text("travel-empty"))
        if (snapshot.maxSlots > 0 && snapshot.usedSlots >= snapshot.maxSlots) body += PaperDialogBody(text("travel-limit"))
        presentation.separator(500)?.let(body::add)
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
        present(
            player,
            PaperDialogScreen(
                id = "help.travel",
                title = text("travel-title"),
                body = body,
                buttons = homeButtons + createButton + travelButtons(player),
                exitButton = rootButton(player),
                columns = 3,
            ),
        )
    }

    private fun showTravelFailure(player: Player) {
        present(
            player,
            PaperDialogScreen(
                id = "help.travel.failure",
                title = text("travel-title"),
                body = listOf(PaperDialogBody(text("travel-error"))),
                buttons = travelButtons(player),
                exitButton = rootButton(player),
                columns = 2,
            ),
        )
    }

    private fun openWarps(player: Player, requestedPage: Int = 0) {
        markNavigation(player) { openWarps(player, requestedPage) }
        if (availableCatalog(player).none { it.id == "warps" }) {
            present(player, PaperDialogScreen(
                id = "help.travel.warps",
                title = text("warps-title"),
                body = listOf(PaperDialogBody(text("action-unavailable"), width = 500)),
                buttons = emptyList(),
                exitButton = backButton("back", player, ::openTravel),
            ))
            return
        }
        val warps = runCatching { gateway.loadWarps(player) }.getOrElse { failure ->
            ru.arc.util.Logging.error("Could not load native warp catalog for {}", player.name, failure)
            present(player, PaperDialogScreen(
                id = "help.travel.warps",
                title = text("warps-title"),
                body = listOf(PaperDialogBody(text("warps-error"), width = 500)),
                buttons = listOf(button("retry", text("warps-retry-label")) { openWarps(player, requestedPage) }),
                exitButton = backButton("back", player, ::openTravel),
            ))
            return
        }
        val pages = maxOf(1, (warps.size + PUBLIC_HOMES_PAGE_SIZE - 1) / PUBLIC_HOMES_PAGE_SIZE)
        val page = requestedPage.coerceIn(0, pages - 1)
        val visible = warps.drop(page * PUBLIC_HOMES_PAGE_SIZE).take(PUBLIC_HOMES_PAGE_SIZE)
        present(player, PaperDialogScreen(
            id = "help.travel.warps",
            title = text("warps-title"),
            body = listOf(PaperDialogBody(text(
                if (warps.isEmpty()) "warps-empty" else "warps-body",
                "count" to warps.size.toString(), "page" to (page + 1).toString(), "pages" to pages.toString(),
            ), width = 500)),
            buttons = visible.mapIndexed { index, warp ->
                button("warp_$index", text("warp-label", "warp" to warp.name),
                    text("warp-tooltip", "owner" to warp.owner, "world" to warp.world)) {
                    openWarp(player, warp, page)
                }
            } + buildList {
                if (page > 0) add(button("previous", text("page-previous-label")) { openWarps(player, page - 1) })
                if (page + 1 < pages) add(button("next", text("page-next-label")) { openWarps(player, page + 1) })
            },
            exitButton = backButton("back", player, ::openTravel),
            columns = 2,
        ))
    }

    private fun openWarp(player: Player, warp: HelpCenterWarp, page: Int, changed: Boolean = false) {
        markNavigation(player) { openWarps(player, page) }
        val body = mutableListOf(
            DialogTables.body(rows = listOf(
                text("warp-owner-label") to text("warp-value", "value" to warp.owner),
                text("warp-server-label") to text("warp-value", "value" to warp.server),
                text("warp-world-label") to text("warp-value", "value" to warp.world),
                text("warp-price-label") to text("warp-value", "value" to warp.priceLabel),
            ), frame = DialogTables.Frame.RARE, width = 320),
            PaperDialogBody(text("warp-description", "description" to (warp.description ?: settings.text("warp-no-description"))), width = 500),
            PaperDialogBody(text("warp-conditions"), width = 500),
        )
        if (changed) body += PaperDialogBody(text("warp-changed"), width = 500)
        present(player, PaperDialogScreen(
            id = "help.travel.warp",
            title = text("warp-title", "warp" to warp.name),
            body = body,
            buttons = if (changed) listOf(button("refresh", text("warps-refresh-label")) { openWarps(player, page) }) else listOf(
                button("teleport", text("warp-teleport-label"), text("warp-teleport-tooltip")) {
                    val sent = runCatching { gateway.teleportWarp(player, warp) }.onFailure { failure ->
                        ru.arc.util.Logging.error("Could not request warp {} for {}", warp.id, player.name, failure)
                    }.getOrDefault(false)
                    if (sent) closeDialog(player) else openWarp(player, warp, page, changed = true)
                },
            ),
            exitButton = backButton("back", player, action = { openWarps(it, page) }),
        ))
    }

    private fun openPublicHomes(player: Player, page: Int = 0) {
        val token = markNavigation(player) { openPublicHomes(player, page) }
        present(
            player,
            PaperDialogScreen(
                id = "help.travel.public-homes",
                title = text("public-homes-title"),
                body = listOf(PaperDialogBody(text("public-homes-loading"), width = 500)),
                buttons = emptyList(),
                exitButton = backButton("back", player, ::openTravel),
            ),
        )
        gateway.loadPublicHomes(player, settings.loadTimeoutSeconds).whenCompleteSync(tasks) { homes, failure ->
            if (!active || !player.isOnline || !navigation.isCurrent(player, token)) return@whenCompleteSync
            if (failure == null && homes != null) showPublicHomes(player, homes, page) else showPublicHomesFailure(player)
        }
    }

    private fun showPublicHomes(player: Player, homes: List<HelpCenterPublicHome>, requestedPage: Int) {
        val pages = maxOf(1, (homes.size + PUBLIC_HOMES_PAGE_SIZE - 1) / PUBLIC_HOMES_PAGE_SIZE)
        val page = requestedPage.coerceIn(0, pages - 1)
        val visible = homes.drop(page * PUBLIC_HOMES_PAGE_SIZE).take(PUBLIC_HOMES_PAGE_SIZE)
        val buttons = visible.mapIndexed { index, home ->
            button(
                "public_home_$index",
                text("public-home-label", "home" to home.name),
                text("public-home-tooltip", "owner" to home.owner, "world" to home.world),
            ) { openPublicHome(player, home, page) }
        } + buildList {
            if (page > 0) add(button("previous", text("page-previous-label")) { openPublicHomes(player, page - 1) })
            if (page + 1 < pages) add(button("next", text("page-next-label")) { openPublicHomes(player, page + 1) })
        }
        val body = mutableListOf(
            PaperDialogBody(
                text(
                    "public-homes-body",
                    "count" to homes.size.toString(),
                    "page" to (page + 1).toString(),
                    "pages" to pages.toString(),
                ),
                width = 500,
            ),
        )
        if (visible.isEmpty()) body += PaperDialogBody(text("public-homes-empty"), width = 500)
        present(
            player,
            PaperDialogScreen(
                id = "help.travel.public-homes",
                title = text("public-homes-title"),
                body = body,
                buttons = buttons,
                exitButton = backButton("back", player, ::openTravel),
                columns = 2,
            ),
        )
    }

    private fun openPublicHome(player: Player, home: HelpCenterPublicHome, page: Int) {
        markNavigation(player) { openPublicHome(player, home, page) }
        present(
            player,
            PaperDialogScreen(
                id = "help.travel.public-home",
                title = text("public-home-title", "home" to home.name),
                body = listOf(
                    DialogTables.body(
                        rows = listOf(
                            text("table-owner-label") to Component.text(home.owner),
                            text("table-server-label") to Component.text(home.server),
                            text("table-world-label") to Component.text(home.world),
                            text("table-coordinates-label") to Component.text("${home.x}, ${home.y}, ${home.z}"),
                        ),
                        frame = DialogTables.Frame.RARE,
                        width = 320,
                    ),
                    PaperDialogBody(
                        if (home.description == null) text("public-home-no-description")
                        else text("public-home-description", "description" to home.description),
                        width = 500,
                    ),
                ),
                buttons = listOf(
                    button("teleport", text("public-home-teleport-label"), text("public-home-teleport-tooltip")) {
                        execute(player, HelpCenterCommands.publicHome(home.identifier))
                    }.closing(),
                ),
                exitButton = backButton("back", player, action = { openPublicHomes(it, page) }),
            ),
        )
    }

    private fun showPublicHomesFailure(player: Player) {
        present(
            player,
            PaperDialogScreen(
                id = "help.travel.public-homes.failure",
                title = text("public-homes-title"),
                body = listOf(PaperDialogBody(text("public-homes-error"), width = 500)),
                buttons = emptyList(),
                exitButton = backButton("back", player, ::openTravel),
            ),
        )
    }

    private fun openRtp(player: Player) {
        markNavigation(player) { openRtp(player) }
        present(
            player,
            PaperDialogScreen(
                id = "help.travel.rtp",
                title = text("rtp-title"),
                body = listOf(presentation.callout(text("rtp-body"), 500)),
                buttons = listOf(
                    button("rtp_vanilla", text("rtp-vanilla-label"), text("rtp-vanilla-tooltip")) {
                        execute(player, settings.action("rtp-vanilla"))
                    }.closing(),
                    button("rtp_mining", text("rtp-mining-label"), text("rtp-mining-tooltip")) {
                        execute(player, settings.action("rtp-mining"))
                    }.closing(),
                    button("rtp_biomes", text("rtp-biomes-label"), text("rtp-biomes-tooltip")) {
                        execute(player, settings.action("rtp-biomes"))
                    }.closing(),
                ),
                exitButton = backButton("back", player, ::openTravel),
                columns = 2,
            ),
        )
    }

    private fun openBuilder(player: Player) {
        markNavigation(player) { openBuilder(player) }
        present(
            player,
            PaperDialogScreen(
                id = "help.builder",
                title = text("builder-title"),
                body = listOf(PaperDialogBody(text("builder-body"), width = 500)),
                buttons = listOf(
                    button("builder_wand", text("builder-wand-label"), text("builder-wand-tooltip")) {
                        execute(player, "builder wand")
                    }.closing(),
                    button("builder_help", text("builder-help-label"), text("builder-help-tooltip")) {
                        execute(player, "builder")
                    }.closing(),
                ),
                exitButton = backButton("back", player, action = { openCategory(it, HelpCenterCategory.TECHNOLOGY, true) }),
                columns = 2,
            ),
        )
    }

    private fun openCreateHome(player: Player) {
        markNavigation(player)
        present(
            player,
            PaperDialogScreen(
                id = "help.home.create",
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

    fun openHome(player: Player, home: HelpCenterHome) {
        markNavigation(player)
        val visibility = if (home.isPublic) "public" else "private"
        val visibilityAction = if (home.isPublic) "private" else "public"
        present(
            player,
            PaperDialogScreen(
                id = "help.home",
                title = text("home-title", "home" to home.name),
                body = listOf(DialogTables.body(
                    rows = listOf(
                        text("table-server-label") to Component.text(home.server),
                        text("table-world-label") to Component.text(home.world),
                        text("table-coordinates-label") to Component.text("${home.x}, ${home.y}, ${home.z}"),
                        text("table-home-visibility-label") to text("home-visibility-$visibility"),
                    ),
                    headers = text("table-label-heading") to text("table-value-heading"),
                    frame = DialogTables.Frame.EPIC,
                ), PaperDialogBody(text("home-visibility-$visibility-body"))),
                buttons = listOfNotNull(
                    button("teleport", text("home-teleport-label"), text("home-teleport-tooltip")) {
                        execute(player, HelpCenterCommands.home(home.name))
                    }.closing(),
                    button("relocate", text("home-relocate-label"), text("home-relocate-tooltip")) { openRelocateHome(player, home) },
                    if (player.hasPermission("huskhomes.command.edithome") &&
                        player.hasPermission("huskhomes.command.edithome.privacy")
                    ) {
                        button("visibility", text("home-$visibilityAction-label"), text("home-$visibilityAction-tooltip")) {
                            execute(player, HelpCenterCommands.setHomePublic(home.name, !home.isPublic))
                        }.closing()
                    } else null,
                    button("delete", text("home-delete-label"), text("home-delete-tooltip")) { openDeleteHome(player, home) },
                ),
                exitButton = backButton("back", player, ::openTravel),
                columns = 2,
            ),
        )
    }

    private fun openRelocateHome(player: Player, home: HelpCenterHome) {
        markNavigation(player)
        present(
            player,
            PaperDialogScreen(
                id = "help.home.relocate",
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
        present(
            player,
            PaperDialogScreen(
                id = "help.home.delete",
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
        button("warps", text("warps-label"), commandTooltip("warps")) { executeCatalog(player, "warps") },
        button("public_homes", text("public-homes-label"), text("public-homes-tooltip")) { openPublicHomes(player) },
        button("spawn", text("spawn-label"), commandTooltip("spawn")) { executeCatalog(player, "spawn") }.closing(),
        button("rtp", text("rtp-label"), text("rtp-tooltip")) { openRtp(player) },
        button("back_command", text("back-command-label"), commandTooltip("back")) { executeCatalog(player, "back") }.closing(),
    ).filter { it.id.value != "warps" || availableCatalog(player).any { command -> command.id == "warps" } }

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
            "events", "duels", "giveaways", "dungeons", "farms", "vote", "parkour",
            "shops", "loot", "sell", "auction", "bank",
            "slimefun", "enchants", "enchanter", "builder", "mounts",
            "chat-global", "chat-local", "lands-borders", "trails-on", "trails-off", "particles", "tpa-ignore" ->
                text("command-${command.id}-label")
            else -> text("command-label", "label" to command.label)
        }
        val result = button(
            "command_${command.id}",
            label,
            if (command.id == "slimefun") text("search-result-tooltip", "description" to command.description)
            else text("command-tooltip", "description" to command.description, "command" to command.command),
        ) {
            if (command.id == "privat") open(player, HelpCenterPage.PRIVAT) else executeCatalog(player, command.id)
        }
        return if (command.id == "privat" || command.opensDialog || command.opensInventory) {
            result
        } else result.closing()
    }

    private fun searchResultButton(player: Player, entry: HelpCenterSearchEntry, returnTo: () -> Unit): PaperDialogButton {
        val result = button(
            "result_${entry.id.replace('-', '_')}",
            text(if (entry.action is HelpCenterSearchAction.OpenGuide) "search-guide-label" else "command-label", "label" to entry.label),
            entry.command?.takeIf { entry.action is HelpCenterSearchAction.Execute }?.let { command ->
                text("command-tooltip", "description" to entry.description, "command" to command)
            } ?: text("search-result-tooltip", "description" to entry.description),
        ) {
            when (val action = entry.action) {
                is HelpCenterSearchAction.Execute -> catalogById[entry.id]?.let { hub.openCatalogAction(player, it, returnTo) }
                is HelpCenterSearchAction.OpenPage -> open(player, action.page)
                is HelpCenterSearchAction.OpenGuide -> openFeatureGuide(player, action.guideId, returnTo)
                HelpCenterSearchAction.CreateHome -> openCreateHome(player)
            }
        }
        return result
    }

    private fun execute(player: Player, command: String): Boolean {
        markNavigation(player)
        return gateway.execute(player, command).also { executed ->
            if (!executed) player.sendMessage(text("action-failed"))
        }
    }

    private fun openDungeonMenu(player: Player): Boolean {
        markNavigation(player)
        return gateway.openDungeons(player).also { opened ->
            if (!opened) player.sendMessage(text("action-unavailable"))
        }
    }

    private fun executeCommand(player: Player, command: HelpCenterCommand): Boolean =
        if (command.opensInventory) executeInventory(player, command.command)
        else execute(player, command.command)

    private fun executeCatalog(player: Player, id: String) {
        val command = availableCatalog(player).firstOrNull { it.id == id }
        if (command == null) {
            player.sendMessage(text("action-unavailable"))
            return
        }
        if (id == "rules") {
            openRules(player)
        } else if (id == "warps") {
            openWarps(player)
        } else if (id == "builder") {
            openBuilder(player)
        } else if (id == "dungeons") {
            val returnTo = navigation.returnTarget(player) ?: { open(player, HelpCenterPage.ACTIVITIES) }
            dungeons.open(player, returnTo)
        } else if (id == "enchants") {
            val returnTo = navigation.returnTarget(player) ?: { openRoot(player) }
            enchantments.open(player, returnTo)
        } else if (id == "slimefun") {
            val returnTo = navigation.returnTarget(player) ?: { openRoot(player) }
            openFeatureGuide(player, "slimefun", returnTo)
        } else executeCommand(player, command)
    }

    private fun executeInventory(player: Player, command: String): Boolean {
        val returnTo = navigation.returnTarget(player) ?: { openRoot(player) }
        markNavigation(player)
        inventoryReturn.arm(player, returnTo)
        val executed = try {
            gateway.execute(player, command)
        } catch (failure: Throwable) {
            inventoryReturn.cancel(player)
            throw failure
        }
        if (!executed) {
            inventoryReturn.cancel(player)
            player.sendMessage(text("action-failed"))
            returnTo()
        }
        return executed
    }

    private fun commandTooltip(id: String): Component {
        if (id in setOf("chat-global", "chat-local", "trails-on", "trails-off", "particles")) return text("setting-$id-tooltip")
        val command = catalogById.getValue(id)
        return text("command-tooltip", "description" to command.description, "command" to command.command)
    }

    private fun availableCatalog(player: Player): List<HelpCenterCommand> {
        val features = gateway.features()
        return catalog.filter { command ->
            (command.requiredFeature == null || command.requiredFeature in features) &&
                (command.permission == null || player.hasPermission(command.permission)) &&
                (command.anyPermissions.isEmpty() || command.anyPermissions.any(player::hasPermission))
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
    ): PaperDialogButton = PaperDialogButton(PaperDialogActionId.of(id.replace('-', '_')), label, tooltip) { action() }

    private fun contextButton(
        id: String,
        label: Component,
        tooltip: Component = Component.empty(),
        action: (PaperDialogClickContext) -> Unit,
    ): PaperDialogButton = PaperDialogButton(PaperDialogActionId.of(id.replace('-', '_')), label, tooltip, onClick = action)

    private fun PaperDialogButton.closing(): PaperDialogButton = copy(closeDialogBeforeAction = true)

    private fun text(key: String, vararg placeholders: Pair<String, String>): Component = miniMessage.deserialize(
        settings.text(key),
        TagResolver.resolver(placeholders.map { (name, value) -> Placeholder.unparsed(name, value) }),
    ).decoration(TextDecoration.ITALIC, false)

    private fun plainText(key: String): String = plainText.serialize(text(key))

    private fun markNavigation(player: Player, reopen: (() -> Unit)? = null): Long = navigation.visit(player, reopen)

    private data class CommandDefinition(
        val id: String,
        val category: HelpCenterCategory,
        val command: String,
        val requiredFeature: HelpCenterFeature? = null,
        val permission: String? = null,
        val opensInventory: Boolean = false,
        val anyPermissions: Set<String> = emptySet(),
    )

    private data class IntentDefinition(val id: String, val action: HelpCenterSearchAction)

    companion object {
        // These commands join Core's shared history while this callback is active.
        private val NATIVE_DIALOG_COMMANDS = setOf("rules", "events", "farms", "civs", "giveaways", "jobs", "rank", "teams", "quests", "builder", "warps", "enchants", "slimefun", "resource-contracts")
        private const val GUIDE_PAGE_SIZE = 10
        internal val catalogCommandIds: Set<String> get() = DEFINITIONS.mapTo(linkedSetOf()) { it.id }
        private const val PUBLIC_HOMES_PAGE_SIZE = 10
        private val SEARCH_INPUT = PaperDialogInputId.of("search")
        private val HOME_INPUT = PaperDialogInputId.of("home_name")
        private val PLAYER_SEARCH_INPUT = PaperDialogInputId.of("player_search")
        private val MESSAGE_INPUT = PaperDialogInputId.of("message")
        private val AMOUNT_INPUT = PaperDialogInputId.of("amount")

        private val DEFINITIONS = listOf(
            CommandDefinition("menu", HelpCenterCategory.START, "menu"),
            CommandDefinition("kit", HelpCenterCategory.START, "kit start"),
            CommandDefinition("rules", HelpCenterCategory.START, "rules"),
            CommandDefinition("warps", HelpCenterCategory.TRAVEL, "warps", HelpCenterFeature.PLAYER_WARPS, "pw.warp"),
            CommandDefinition("spawn", HelpCenterCategory.TRAVEL, "spawn"),
            CommandDefinition("rtp", HelpCenterCategory.TRAVEL, "rtp"),
            CommandDefinition("back", HelpCenterCategory.TRAVEL, "back"),
            CommandDefinition("stuck", HelpCenterCategory.TRAVEL, "stuck"),
            CommandDefinition("privat", HelpCenterCategory.PROTECTION, "privat", HelpCenterFeature.LANDS),
            CommandDefinition(
                "events",
                HelpCenterCategory.ACTIVITIES,
                "arcevents",
                HelpCenterFeature.EVENTS,
                "arcevents.play",
            ),
            CommandDefinition(
                "duels",
                HelpCenterCategory.ACTIVITIES,
                "duel",
                HelpCenterFeature.DUELS,
                "arcduels.use",
                opensInventory = true,
            ),
            CommandDefinition("giveaways", HelpCenterCategory.ACTIVITIES, "giveaway", HelpCenterFeature.GIVEAWAYS, "arcgiveaways.use"),
            CommandDefinition(
                "dungeons",
                HelpCenterCategory.ACTIVITIES,
                "em",
                HelpCenterFeature.DUNGEONS,
                opensInventory = true,
            ),
            CommandDefinition(
                "farms",
                HelpCenterCategory.ACTIVITIES,
                "arcfarms",
                HelpCenterFeature.FARMS,
            ),
            CommandDefinition("civs", HelpCenterCategory.ACTIVITIES, "civs", HelpCenterFeature.CIVS, "arccivs.use"),
            CommandDefinition("vote", HelpCenterCategory.ACTIVITIES, "vote", HelpCenterFeature.VOTES, "arcvotes.use"),
            CommandDefinition("parkour", HelpCenterCategory.ACTIVITIES, "pa joinall", HelpCenterFeature.PARKOUR,
                "parkour.basic.joinall", opensInventory = true),
            CommandDefinition("teams", HelpCenterCategory.SOCIAL, "clans", HelpCenterFeature.TEAMS, "arcjustteams.use"),
            CommandDefinition("shops", HelpCenterCategory.TRADE, "shops", opensInventory = true),
            CommandDefinition("loot", HelpCenterCategory.TRADE, "loot", opensInventory = true),
            CommandDefinition("sell", HelpCenterCategory.TRADE, "sell", opensInventory = true),
            CommandDefinition("auction", HelpCenterCategory.TRADE, "ah", opensInventory = true),
            CommandDefinition("resource-contracts", HelpCenterCategory.TRADE, "arc contracts", HelpCenterFeature.CONTRACTS),
            CommandDefinition(
                "bank",
                HelpCenterCategory.TRADE,
                "bank open",
                HelpCenterFeature.BANK,
                "bank.open.command",
                opensInventory = true,
            ),
            CommandDefinition("rank", HelpCenterCategory.PROGRESS, "rank dialog", HelpCenterFeature.RANKS, "arcranks.use"),
            CommandDefinition("rankup", HelpCenterCategory.PROGRESS, "rankup", HelpCenterFeature.RANKS, "arcranks.rankup"),
            CommandDefinition("jobs", HelpCenterCategory.PROGRESS, "arcjobs dialog", HelpCenterFeature.JOBS, "arcecojobs.use"),
            CommandDefinition("quests", HelpCenterCategory.PROGRESS, "quests", HelpCenterFeature.RANKS, "arcranks.use"),
            CommandDefinition("skills", HelpCenterCategory.PROGRESS, "skills", HelpCenterFeature.SKILLS, opensInventory = true),
            CommandDefinition(
                "slimefun",
                HelpCenterCategory.TECHNOLOGY,
                "sf open_guide",
                HelpCenterFeature.SLIMEFUN,
                opensInventory = true,
            ),
            CommandDefinition(
                "items",
                HelpCenterCategory.TECHNOLOGY,
                "ia",
                HelpCenterFeature.ITEMS,
                "ia.user.ia",
                opensInventory = true,
            ),
            CommandDefinition(
                "enchants",
                HelpCenterCategory.TECHNOLOGY,
                "arc help enchants",
                HelpCenterFeature.ENCHANTMENTS,
            ),
            CommandDefinition(
                "enchanter",
                HelpCenterCategory.TECHNOLOGY,
                "enchanter",
                HelpCenterFeature.ENCHANTMENTS,
                opensInventory = true,
            ),
            CommandDefinition(
                "builder", HelpCenterCategory.TECHNOLOGY, "builder", HelpCenterFeature.BUILDER,
                anyPermissions = setOf(
                    "arcbuild.use", "arcbuild.fill", "arcbuild.replace", "arcbuild.disconnect",
                    "arcbuild.copy", "arcbuild.paste", "arcbuild.deconstruct", "arcbuild.crown",
                    "arcbuild.book.create", "arcbuild.book.sell", "arcbuild.book.use",
                ),
            ),
            CommandDefinition(
                "mounts",
                HelpCenterCategory.TECHNOLOGY,
                "mount",
                HelpCenterFeature.MOUNTS,
                "arc.mounts.use",
                opensInventory = true,
            ),
            CommandDefinition("chat-global", HelpCenterCategory.SETTINGS, "g"),
            CommandDefinition("chat-local", HelpCenterCategory.SETTINGS, "l"),
            CommandDefinition("lands-borders", HelpCenterCategory.SETTINGS, "lands view here", HelpCenterFeature.LANDS),
            CommandDefinition("trails-on", HelpCenterCategory.SETTINGS, "trails on", HelpCenterFeature.TRAILS),
            CommandDefinition("trails-off", HelpCenterCategory.SETTINGS, "trails off", HelpCenterFeature.TRAILS),
            CommandDefinition("particles", HelpCenterCategory.SETTINGS, "pp toggle", HelpCenterFeature.PLAYER_PARTICLES),
            CommandDefinition("tpa-ignore", HelpCenterCategory.SETTINGS, "huskhomes:tpignore", HelpCenterFeature.HUSK_HOMES),
        )

        private val INTENT_DEFINITIONS = listOf(
            IntentDefinition("goals", HelpCenterSearchAction.OpenPage(HelpCenterPage.GOALS)),
            IntentDefinition("held-item", HelpCenterSearchAction.OpenPage(HelpCenterPage.ITEM)),
            IntentDefinition("requests", HelpCenterSearchAction.OpenPage(HelpCenterPage.REQUESTS)),
            IntentDefinition("context", HelpCenterSearchAction.OpenPage(HelpCenterPage.CONTEXT)),
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

internal fun resolveHelpCenterWorldLabel(
    kind: HelpCenterWorldKind,
    raw: String,
    alias: (String) -> String,
): String {
    val world = raw.lowercase(java.util.Locale.ROOT)
    return when {
        world in setOf("spawn", "rc_origin_spawn") -> alias("world-alias-spawn")
        world == "lobby" -> alias("world-alias-lobby")
        world == "em_adventurers_guild" || world == "ag" -> alias("world-alias-adventure-guild")
        world == "nether" || world.endsWith("_nether") -> alias("world-alias-nether")
        world == "end" || world.endsWith("_the_end") -> alias("world-alias-end")
        world.startsWith("em_") -> alias("world-alias-dungeon")
        world.startsWith("parkour") -> alias("world-alias-parkour")
        world == "survival" -> alias("world-alias-biomes")
        else -> alias(when (kind) {
            HelpCenterWorldKind.VANILLA -> "world-alias-vanilla"
            HelpCenterWorldKind.NEW_BIOMES -> "world-alias-biomes"
            HelpCenterWorldKind.MINING -> "world-alias-mining"
            HelpCenterWorldKind.OTHER -> "world-alias-other"
        })
    }
}
