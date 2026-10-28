package ru.arc.landsui

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.TextDecoration
import net.kyori.adventure.text.minimessage.MiniMessage
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver
import org.bukkit.entity.Player
import ru.arc.core.LifecycleTaskScope
import ru.arc.gui.ArcMenus
import ru.arc.paper.menu.DialogDecoration
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
        LandsManagementMenus(settings, gateway, ::openRoot, ::claimBlockButton)
    }
    private val invitations = lazy { LandsInvitesMenus(settings, gateway, ::openRoot) }

    fun close() {
        tasks.close()
        if (invitations.isInitialized()) invitations.value.close()
    }

    fun openCurrent(player: Player) {
        val landId = gateway.currentLandId(player)
        if (landId != null) openInspection(player, landId) else openRoot(player)
    }

    internal fun openCommand(player: Player, route: LandsUiCommandRoute) {
        when (route.destination) {
            LandsUiDestination.ROOT -> openRoot(player)
            LandsUiDestination.CURRENT -> openCurrent(player)
            LandsUiDestination.INVITES -> invitations.value.open(player)
            LandsUiDestination.CREATE -> openCreate(player)
            LandsUiDestination.GUIDE -> openGuide(player)
            else -> {
                val context = gateway.commandContext(player, route.areaName)
                if (context == null) {
                    player.sendMessage(text("command-target-unavailable"))
                    openRoot(player)
                } else management.openDestination(player, context, route.destination)
            }
        }
    }

    internal fun openArea(player: Player, landId: String, areaId: String) =
        management.open(player, LandsUiContext(landId, LandsUiAccess.MEMBER, areaId))

    fun openRoot(player: Player) {
        val lands = gateway.lands(player)
        val nearby = gateway.inspectedLand(player)
        val selected = lands.firstOrNull { it.selected }
        val body = mutableListOf(
            DialogTables.factsBody(
                listOf(
                    text("table-settlements-label") to text("table-count-value", "count" to lands.size.toString()),
                    text("table-selected-label") to text(
                        "table-selected-value",
                        "land" to (selected?.name ?: settings.text("selected-none")),
                    ),
                    text("table-protected-label") to text(
                        "table-protected-value",
                        "chunks" to lands.sumOf { it.chunks }.toString(),
                    ),
                ).map { (label, value) -> label.append(Component.text(":")) to value },
                DialogTables.FactsLayout.ROWS,
                DialogTables.PanelSpec(DialogTables.PanelSkin.NAVY, 320, 1, true),
            ),
            PaperDialogBody(Component.newline()),
            PaperDialogBody(DialogDecoration.library().divider(320, DialogDecoration.Divider(
                DialogDecoration.Glyph(0xF9104, 13), DialogDecoration.Glyph(0xF9106, 13), DialogDecoration.Glyph(0xF9105, 13),
            )), width = 320),
            PaperDialogBody(Component.newline()),
            DialogTables.filledBody(
                rows = listOf(listOf(text("root-body"))),
                headers = null,
                width = 400,
                columnWeights = listOf(1),
            ),
        )
        if (lands.isEmpty()) body += PaperDialogBody(text("root-empty"))
        val inspect = nearby?.let {
            button("inspect", text("inspect-label"), text("inspect-tooltip")) {
                val current = gateway.inspectedLand(player)
                if (current == null) openRoot(player) else openInspection(player, current.id)
            }
        }
        val create = button("create", text("create-label"), text("create-tooltip")) { openCreate(player) }
        val guide = button("guide", text("guide-label"), text("guide-tooltip")) { openGuide(player) }
        val adminSearch = if (player.hasPermission("lands.admin.command.edit")) {
            button("admin_search", text("admin-search-label"), text("admin-search-tooltip")) { management.openSearch(player) }
        } else null
        val inbox = if (gateway.invitations(player).isNotEmpty()) {
            button("invites", text("invites-root-label")) { invitations.value.open(player) }
        } else null
        val technical = listOfNotNull(inspect) + listOf(claimBlockButton(player), create, guide) + listOfNotNull(inbox, adminSearch)
        val buttons = technical + lands.mapIndexed { index, land ->
            button(
                "land_$index",
                text(if (land.selected) "land-selected-label" else "land-label", "land" to land.name,
                    "owner" to (gateway.playerName(land.ownerId) ?: settings.text("owner-unknown"))),
                text(
                    "land-tooltip",
                    "chunks" to land.chunks.toString(),
                    "max_chunks" to land.maxChunks.toString(),
                    "members" to land.memberIds.size.toString(),
                    "max_members" to land.maxMembers.toString(),
                    "balance" to amountFormat.format(land.balance),
                ),
            ) { openDetails(player, land.id) }
        }
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
        management.openCurrent(player, landId)

    private fun claimBlockButton(player: Player) =
        button("claim_get", text("claim-get-label"), text("claim-get-tooltip")) {
            val message = when (gateway.giveClaimBlock(player)) {
                LandsUiClaimBlockResult.GIVEN -> "claim-get-given"
                LandsUiClaimBlockResult.ALREADY_PRESENT -> "claim-get-already-present"
                LandsUiClaimBlockResult.INVENTORY_FULL -> "claim-get-inventory-full"
                LandsUiClaimBlockResult.COOLDOWN -> "claim-get-cooldown"
                LandsUiClaimBlockResult.UNAVAILABLE -> "claim-get-unavailable"
            }
            player.sendMessage(text(message))
        }.closing()

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

    fun openDetails(player: Player, landId: String) =
        management.open(player, LandsUiContext(landId, LandsUiAccess.MEMBER))

    fun openPanelAction(player: Player, landId: String, action: LandsUiPanelAction) {
        if (gateway.currentLandId(player) != landId || gateway.land(player, landId) == null) {
            player.sendMessage(text("land-gone"))
            openRoot(player)
            return
        }
        if (action == LandsUiPanelAction.OVERVIEW) {
            openInspection(player, landId)
            return
        }
        management.openPanelAction(player, LandsUiContext(landId, LandsUiAccess.MEMBER), action)
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
                body = listOf(DialogTables.framedBody(text("guide-body"), DialogTables.Frame.UNCOMMON, width = 400)),
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
                body = listOf(DialogTables.framedBody(text("guide-create-body"), DialogTables.Frame.UNCOMMON, width = 400)),
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
                body = listOf(DialogTables.framedBody(text("guide-expand-body"), DialogTables.Frame.UNCOMMON, width = 400)),
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
                body = listOf(DialogTables.framedBody(text("guide-members-body"), DialogTables.Frame.UNCOMMON, width = 400)),
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
                body = listOf(DialogTables.framedBody(text("guide-commands-body"), DialogTables.Frame.UNCOMMON, width = 400)),
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

    private fun territorySummary(land: LandsUiLand) = DialogTables.filledBody(
        rows = listOf(
            text("table-land-label") to Component.text(land.name),
            text("table-territory-label") to text("table-territory-value",
                "used" to land.chunks.toString(), "maximum" to land.maxChunks.toString()),
        ).map { (label, value) -> listOf(label, value) },
        headers = null,
        width = 320,
        columnWeights = listOf(1, 1),
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
