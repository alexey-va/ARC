package ru.arc.landsui

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.TextDecoration
import net.kyori.adventure.text.minimessage.MiniMessage
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver
import org.bukkit.entity.Player
import ru.arc.core.LifecycleTaskScope
import ru.arc.gui.ArcMenus
import ru.arc.onboarding.ClaimBlockIdentity
import ru.arc.onboarding.ClaimGuideGridColor
import ru.arc.onboarding.ClaimGuideView
import ru.arc.onboarding.OnboardingModule
import ru.arc.paper.menu.DialogTables
import ru.arc.gui.MenuEscapeBehavior
import ru.arc.paper.menu.PaperDialogActionId
import ru.arc.paper.menu.PaperDialogBody
import ru.arc.paper.menu.PaperDialogButton
import ru.arc.paper.menu.PaperDialogInputId
import ru.arc.paper.menu.PaperDialogScreen
import ru.arc.paper.menu.PaperDialogTextInput
import java.text.DecimalFormat

class LandsUiController(
    private val settings: LandsUiSettings,
    private val gateway: LandsUiGateway,
) {
    private val miniMessage = MiniMessage.miniMessage()
    private val amountFormat = DecimalFormat("#,##0.##")
    private val tasks = LifecycleTaskScope()

    private val management by lazy {
        LandsManagementMenus(settings, gateway, ::openRoot) { player, context ->
            if (context.access == LandsUiAccess.MEMBER) {
                listOfNotNull(claimRadiusButton(player), claimDisplayButton(player, context.landId))
            } else emptyList()
        }
    }

    fun close() = tasks.close()

    fun openCurrent(player: Player) {
        val landId = gateway.currentLandId(player)
        if (landId != null && gateway.land(player, landId) != null) selectAndOpenDetails(player, landId)
        else if (landId != null) openInspection(player, landId)
        else openRoot(player)
    }

    fun openRoot(player: Player) {
        val lands = gateway.lands(player)
        val nearby = gateway.inspectedLand(player)
        val selected = lands.firstOrNull { it.selected }
        val body = mutableListOf(
            DialogTables.body(
                rows = listOf(
                    text("table-settlements-label") to text("table-count-value", "count" to lands.size.toString()),
                    text("table-selected-label") to text(
                        "table-selected-value",
                        "land" to (selected?.name ?: settings.text("selected-none")),
                    ),
                    text("table-protected-label") to text(
                        "table-protected-value",
                        "chunks" to lands.sumOf { it.chunks }.toString(),
                    ),
                ),
                frame = DialogTables.Frame.LEGENDARY,
                width = 320,
                columns = DialogTables.Columns.BALANCED,
            ),
            DialogTables.framedBody(
                text("root-body"),
                frame = DialogTables.Frame.LEGENDARY,
                width = 320,
            ),
            DialogTables.body(
                rows = listOf("create", "edit", "claim", "trust", "untrust").map { command ->
                    text("root-command-$command") to text("root-command-$command-help")
                },
                headers = text("root-command-heading") to text("root-command-help-heading"),
                frame = DialogTables.Frame.LEGENDARY,
                width = 320,
                columns = DialogTables.Columns.BALANCED,
            ),
        )
        if (lands.isEmpty()) body += PaperDialogBody(text("root-empty"))
        val buttons = listOfNotNull(nearby?.let {
            button("inspect", text("inspect-label"), text("inspect-tooltip")) {
                val current = gateway.inspectedLand(player)
                if (current == null) openRoot(player) else openInspection(player, current.id)
            }
        }) + lands.mapIndexed { index, land ->
            button(
                "land_$index",
                text(if (land.selected) "land-selected-label" else "land-label", "land" to land.name),
                text(
                    "land-tooltip",
                    "chunks" to land.chunks.toString(),
                    "max_chunks" to land.maxChunks.toString(),
                    "members" to land.memberIds.size.toString(),
                    "max_members" to land.maxMembers.toString(),
                    "balance" to amountFormat.format(land.balance),
                ),
            ) { selectAndOpenDetails(player, land.id) }
        } + listOf(
            button("claim_get", text("claim-get-label"), text("claim-get-tooltip")) {
                val message = when (gateway.giveClaimBlock(player)) {
                    LandsUiClaimBlockResult.GIVEN -> "claim-get-given"
                    LandsUiClaimBlockResult.ALREADY_PRESENT -> "claim-get-already-present"
                    LandsUiClaimBlockResult.INVENTORY_FULL -> "claim-get-inventory-full"
                    LandsUiClaimBlockResult.COOLDOWN -> "claim-get-cooldown"
                    LandsUiClaimBlockResult.UNAVAILABLE -> "claim-get-unavailable"
                }
                player.sendMessage(text(message))
            }.closing(),
            button("create", text("create-label"), text("create-tooltip")) { openCreate(player) },
            button("guide", text("guide-label"), text("guide-tooltip")) { openGuide(player) },
        ) + (if (player.hasPermission("lands.admin.command.edit")) listOf(
            button("admin_search", text("admin-search-label"), text("admin-search-tooltip")) {
                management.openSearch(player)
            },
        ) else emptyList()) + listOfNotNull(claimRadiusButton(player)) + listOfNotNull(claimDisplayButton(player, null))
        show(
            player,
            PaperDialogScreen(
                id = "lands.home",
                title = text("root-title"),
                body = body,
                buttons = buttons,
                exitButton = if (MenuEscapeBehavior.goesBack(player)) back("back") {} else null,
                columns = 2,
            ),
            reopen = { openRoot(player) },
        )
    }

    private fun openInspection(player: Player, landId: String) =
        management.open(player, LandsUiContext(landId, LandsUiAccess.CURRENT))

    fun openInvite(player: Player, target: LandsUiPlayer) {
        val lands = LandsUiPlanner.inviteableLands(target.id, gateway.lands(player))
        if (lands.isEmpty()) {
            player.sendMessage(text("invite-none", "player" to target.name))
            openRoot(player)
            return
        }
        show(
            player,
            PaperDialogScreen(
                id = "lands.invite-picker",
                title = text("invite-picker-title"),
                body = listOf(PaperDialogBody(text("invite-picker-body", "player" to target.name), width = 500)),
                buttons = lands.mapIndexed { index, land ->
                    button("invite_land_$index", text("invite-land-label", "land" to land.name)) {
                        management.execute(player, LandsUiContext(land.id, LandsUiAccess.MEMBER), LandsUiChange.Trust(target.name))
                    }.closing()
                },
                exitButton = back("back") { openRoot(player) },
                columns = 2,
            ),
            reopen = { openInvite(player, target) },
        )
    }

    private fun selectAndOpenDetails(player: Player, landId: String) {
        if (!gateway.select(player, landId)) {
            player.sendMessage(text("land-gone"))
            openRoot(player)
            return
        }
        openDetails(player, landId)
    }

    fun openDetails(player: Player, landId: String) =
        management.open(player, LandsUiContext(landId, LandsUiAccess.MEMBER))

    private fun claimRadiusButton(player: Player): PaperDialogButton? =
        ClaimBlockIdentity.heldRadius(player)?.let { radius ->
            button("claim_radius", text("claim-radius-label", "size" to (radius * 2 + 1).toString()),
                text("claim-radius-tooltip")) { openClaimRadius(player) }
        }

    private fun claimDisplayButton(player: Player, returnLandId: String?): PaperDialogButton? =
        ClaimBlockIdentity.heldRadius(player)?.let {
            button("claim_display", text("claim-display-label"), text("claim-display-tooltip")) {
                openClaimDisplay(player, returnLandId)
            }
        }

    private fun openClaimDisplay(player: Player, returnLandId: String?) {
        if (ClaimBlockIdentity.heldRadius(player) == null) return openRoot(player)
        val view = OnboardingModule.claimGuideView(player)
        show(player, PaperDialogScreen(
            id = "lands.claim-display",
            title = text("claim-display-title"),
            body = listOf(PaperDialogBody(text("claim-display-body",
                "grid" to offsetText(view.gridOffset),
                "label" to offsetText(view.labelOffset),
                "snap" to snapText(view),
                "radius" to gridRadiusText(view.gridRadius),
                "color" to colorText(view.gridColor),
                "posts" to settings.text(if (view.showPosts) "claim-display-enabled" else "claim-display-disabled"),
            ))),
            buttons = listOf(
                button("grid_down", text("claim-display-grid-down")) {
                    OnboardingModule.adjustClaimGuideView(player, gridSteps = -1)
                    openClaimDisplay(player, returnLandId)
                },
                button("grid_up", text("claim-display-grid-up")) {
                    OnboardingModule.adjustClaimGuideView(player, gridSteps = 1)
                    openClaimDisplay(player, returnLandId)
                },
                button("label_down", text("claim-display-label-down")) {
                    OnboardingModule.adjustClaimGuideView(player, labelSteps = -1)
                    openClaimDisplay(player, returnLandId)
                },
                button("label_up", text("claim-display-label-up")) {
                    OnboardingModule.adjustClaimGuideView(player, labelSteps = 1)
                    openClaimDisplay(player, returnLandId)
                },
                button("snap", text("claim-display-snap", "snap" to snapText(view))) {
                    OnboardingModule.adjustClaimGuideView(player, cycleSnap = true)
                    openClaimDisplay(player, returnLandId)
                },
                button("radius_down", text("claim-display-radius-down")) {
                    OnboardingModule.adjustClaimGuideView(player, radiusSteps = -1)
                    openClaimDisplay(player, returnLandId)
                },
                button("radius_up", text("claim-display-radius-up")) {
                    OnboardingModule.adjustClaimGuideView(player, radiusSteps = 1)
                    openClaimDisplay(player, returnLandId)
                },
                button("color", text("claim-display-color", "color" to colorText(view.gridColor))) {
                    OnboardingModule.adjustClaimGuideView(player, cycleColor = true)
                    openClaimDisplay(player, returnLandId)
                },
                button("posts", text("claim-display-posts",
                    "state" to settings.text(if (view.showPosts) "claim-display-enabled" else "claim-display-disabled"))) {
                    OnboardingModule.adjustClaimGuideView(player, togglePosts = true)
                    openClaimDisplay(player, returnLandId)
                },
                button("reset", text("claim-display-reset")) {
                    OnboardingModule.adjustClaimGuideView(player, reset = true)
                    openClaimDisplay(player, returnLandId)
                },
            ),
            exitButton = back("back") {
                if (returnLandId == null) openRoot(player) else openDetails(player, returnLandId)
            },
            columns = 2,
        ), reopen = { openClaimDisplay(player, returnLandId) })
    }

    private fun offsetText(value: Double): String =
        if (value == 0.0) settings.text("claim-display-default")
        else (if (value > 0) "+" else "") + amountFormat.format(value) + " " + settings.text("claim-display-blocks")

    private fun snapText(view: ClaimGuideView): String =
        if (view.snapBlocks == 0) settings.text("claim-display-snap-smooth")
        else settings.text("claim-display-snap-blocks").replace("<blocks>", view.snapBlocks.toString())

    private fun gridRadiusText(radius: Int): String = "${radius * 2 + 1}×${radius * 2 + 1}"

    private fun colorText(color: ClaimGuideGridColor): String = settings.text(
        when (color) {
            ClaimGuideGridColor.ICE -> "claim-display-color-ice"
            ClaimGuideGridColor.WHITE -> "claim-display-color-white"
            ClaimGuideGridColor.PURPLE -> "claim-display-color-purple"
            ClaimGuideGridColor.GOLD -> "claim-display-color-gold"
            ClaimGuideGridColor.GRAY -> "claim-display-color-gray"
        },
    )

    private fun openClaimRadius(player: Player) {
        val current = ClaimBlockIdentity.heldRadius(player) ?: return openRoot(player)
        show(player, PaperDialogScreen(
            id = "lands.claim-radius",
            title = text("claim-radius-title"),
            body = listOf(PaperDialogBody(text("claim-radius-body"))),
            buttons = (0..4).map { radius ->
                val size = (radius * 2 + 1).toString()
                val chunks = ((radius * 2 + 1) * (radius * 2 + 1)).toString()
                button("radius_$radius", text(if (radius == current) "claim-radius-selected" else "claim-radius-option",
                    "size" to size, "chunks" to chunks)) {
                    val lore = listOf(Component.empty(),
                        text("claim-block-size", "size" to size, "chunks" to chunks),
                        text("claim-block-reusable"), Component.empty(), text("claim-block-shortcut"))
                        .map { it.decoration(TextDecoration.ITALIC, false) }
                    if (!ClaimBlockIdentity.setHeldRadius(player, radius, lore)) player.sendMessage(text("claim-block-missing"))
                }.closing()
            },
            exitButton = back("back") { openRoot(player) },
            columns = 2,
        ))
    }

    private fun openCreate(player: Player) {
        show(
            player,
            PaperDialogScreen(
                id = "lands.create",
                title = text("create-title"),
                body = listOf(PaperDialogBody(text("create-body"))),
                inputs = listOf(PaperDialogTextInput(NAME_INPUT, text("name-input"), maxLength = 24)),
                buttons = listOf(
                    contextButton("create_submit", text("create-submit-label")) { context ->
                        val name = context.text(NAME_INPUT).orEmpty().trim()
                        val command = runCatching { LandsUiCommands.create(name) }.getOrNull()
                        if (command == null) {
                            player.sendMessage(text("invalid-name"))
                            openCreate(player)
                        } else {
                            val previousIds = gateway.lands(player).mapTo(linkedSetOf()) { it.id }
                            if (gateway.execute(player, command)) {
                                awaitCreatedLand(player, previousIds, attempt = 0)
                            } else {
                                player.sendMessage(text("action-failed"))
                                openRoot(player)
                            }
                        }
                    }.closing(),
                ),
                exitButton = back("back") { openRoot(player) },
            ),
        )
    }

    fun openAddMember(player: Player, landId: String) =
        management.openAddMember(player, LandsUiContext(landId, LandsUiAccess.MEMBER))

    private fun openGuide(player: Player) {
        show(
            player,
            PaperDialogScreen(
                id = "lands.guide",
                title = text("guide-title"),
                body = listOf(DialogTables.framedBody(text("guide-body"), DialogTables.Frame.LEGENDARY, width = 400)),
                buttons = listOf(
                    button("guide_create", text("guide-create-label"), text("guide-create-tooltip")) { openCreationGuide(player) },
                    button("guide_expand", text("guide-expand-label"), text("guide-expand-tooltip")) { openExpansionGuide(player) },
                    button("guide_members", text("guide-members-label"), text("guide-members-tooltip")) { openMembersGuide(player) },
                    button("guide_commands", text("guide-commands-label"), text("guide-commands-tooltip")) { openCommandsGuide(player) },
                ),
                exitButton = back("back") { openRoot(player) },
                columns = 2,
            ),
        )
    }

    private fun openCreationGuide(player: Player) {
        show(
            player,
            PaperDialogScreen(
                id = "lands.guide-create",
                title = text("guide-create-title"),
                body = listOf(DialogTables.framedBody(text("guide-create-body"), DialogTables.Frame.LEGENDARY, width = 400)),
                buttons = listOf(button("create", text("create-label")) { openCreate(player) }),
                exitButton = back("back") { openGuide(player) },
            ),
        )
    }

    private fun openExpansionGuide(player: Player) {
        show(
            player,
            PaperDialogScreen(
                id = "lands.guide-expand",
                title = text("guide-expand-title"),
                body = listOf(DialogTables.framedBody(text("guide-expand-body"), DialogTables.Frame.LEGENDARY, width = 400)),
                buttons = listOf(button("lands", text("my-lands-label")) { openRoot(player) }),
                exitButton = back("back") { openGuide(player) },
            ),
        )
    }

    private fun openMembersGuide(player: Player) {
        show(
            player,
            PaperDialogScreen(
                id = "lands.guide-members",
                title = text("guide-members-title"),
                body = listOf(DialogTables.framedBody(text("guide-members-body"), DialogTables.Frame.LEGENDARY, width = 400)),
                buttons = listOf(button("lands", text("my-lands-label")) { openRoot(player) }),
                exitButton = back("back") { openGuide(player) },
            ),
        )
    }

    private fun openCommandsGuide(player: Player) {
        show(
            player,
            PaperDialogScreen(
                id = "lands.guide-commands",
                title = text("guide-commands-title"),
                body = listOf(DialogTables.framedBody(text("guide-commands-body"), DialogTables.Frame.LEGENDARY, width = 400)),
                buttons = listOf(button("lands", text("my-lands-label")) { openRoot(player) }),
                exitButton = back("back") { openGuide(player) },
            ),
        )
    }

    private fun awaitCreatedLand(player: Player, previousIds: Set<String>, attempt: Int) {
        tasks.runLater(if (attempt == 0) 2L else 4L) {
            if (!player.isOnline) return@runLater
            val created = LandsUiPlanner.createdLand(previousIds, gateway.lands(player))
            when {
                created != null -> {
                    gateway.select(player, created.id)
                    openCreated(player, created.id)
                }
                attempt < CREATE_POLL_ATTEMPTS -> awaitCreatedLand(player, previousIds, attempt + 1)
                else -> {
                    player.sendMessage(text("create-not-found"))
                    openRoot(player)
                }
            }
        }
    }

    private fun openCreated(player: Player, landId: String) {
        withLand(player, landId) { land ->
            show(
                player,
                PaperDialogScreen(
                    id = "lands.created",
                    title = text("created-title"),
                    body = listOf(
                        territorySummary(land),
                        PaperDialogBody(text("created-table-help"), width = 468),
                    ),
                    buttons = listOf(
                        button("claim", text("created-claim-label"), text("claim-tooltip")) {
                            management.execute(player, LandsUiContext(land.id, LandsUiAccess.MEMBER), LandsUiChange.Claim)
                        }.closing(),
                        button("details", text("created-details-label")) { openDetails(player, land.id) },
                    ),
                    exitButton = back("back") { openRoot(player) },
                    columns = 2,
                ),
                reopen = { openCreated(player, landId) },
            )
        }
    }

    private fun territorySummary(land: LandsUiLand) = DialogTables.body(
        rows = listOf(
            text("table-land-label") to Component.text(land.name),
            text("table-territory-label") to text("table-territory-value",
                "used" to land.chunks.toString(), "maximum" to land.maxChunks.toString()),
        ),
        frame = DialogTables.Frame.LEGENDARY,
        width = 320,
    )

    private fun withLand(player: Player, landId: String, action: (LandsUiLand) -> Unit) {
        val land = gateway.land(player, landId)
        if (land == null) {
            player.sendMessage(text("land-gone"))
            openRoot(player)
        } else {
            action(land)
        }
    }

    private fun contextButton(
        id: String,
        label: Component,
        tooltip: Component = Component.empty(),
        action: (ru.arc.paper.menu.PaperDialogClickContext) -> Unit,
    ): PaperDialogButton = PaperDialogButton(
        id = PaperDialogActionId.of(id),
        label = label,
        tooltip = tooltip,
        width = 230,
        onClick = { action(it) },
    )

    private fun PaperDialogButton.closing(): PaperDialogButton = copy(closeDialogBeforeAction = true)

    private fun button(id: String, label: Component, tooltip: Component = Component.empty(), action: () -> Unit): PaperDialogButton =
        contextButton(id, label, tooltip) { _ -> action() }

    private fun back(id: String, action: () -> Unit): PaperDialogButton = button(id, text("back-label"), action = action).copy(width = 200)

    private fun show(player: Player, screen: PaperDialogScreen, reopen: (() -> Unit)? = null) {
        val close = button("close", text("close-label")) {}.copy(width = 200, closeDialogBeforeAction = true)
        ArcMenus.openDialog(player, screen, closeButton = close, reopen = reopen)
    }

    private fun text(key: String, vararg values: Pair<String, String>): Component {
        val resolver = TagResolver.builder()
        values.forEach { (name, value) -> resolver.resolver(Placeholder.component(name, Component.text(value))) }
        return miniMessage.deserialize(settings.text(key), resolver.build()).decoration(TextDecoration.ITALIC, false)
    }

    companion object {
        private const val CREATE_POLL_ATTEMPTS = 10
        private val NAME_INPUT = PaperDialogInputId.of("land_name")
    }
}
