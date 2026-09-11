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

    fun close() = tasks.close()

    fun openCurrent(player: Player) {
        val landId = gateway.currentLandId(player)
        if (landId != null && gateway.land(player, landId) != null) selectAndOpenDetails(player, landId)
        else openRoot(player)
    }

    fun openRoot(player: Player) {
        val lands = gateway.lands(player)
        val selected = lands.firstOrNull { it.selected }
        val body = mutableListOf(
            DialogTables.body(
                rows = listOf(
                    text("table-settlements-label") to Component.text(lands.size),
                    text("table-selected-label") to Component.text(selected?.name ?: settings.text("selected-none")),
                    text("table-protected-label") to text(
                        "table-protected-value",
                        "chunks" to lands.sumOf { it.chunks }.toString(),
                    ),
                ),
                frame = DialogTables.Frame.LEGENDARY,
                width = 320,
            ),
            PaperDialogBody(
                text("root-body"),
                width = 500,
            ),
        )
        if (lands.isEmpty()) body += PaperDialogBody(text("root-empty"))
        val buttons = lands.mapIndexed { index, land ->
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
            button("create", text("create-label"), text("create-tooltip")) { openCreate(player) },
            button("guide", text("guide-label"), text("guide-tooltip")) { openGuide(player) },
        ) + listOfNotNull(claimRadiusButton(player)) + listOfNotNull(claimDisplayButton(player, null))
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
                        executeForLand(player, land.id) { LandsUiCommands.addMember(target.name) }
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

    fun openDetails(player: Player, landId: String) {
        withLand(player, landId) { land ->
            val role = settings.text(if (land.ownerId == player.uniqueId) "role-owner" else "role-member")
            val buttons = mutableListOf(
                commandButton("claim", "claim-label", "claim-tooltip", player, land.id, "claim"),
                button("unclaim", text("unclaim-label"), text("unclaim-tooltip")) { openUnclaimConfirm(player, land.id) },
                button("add_member", text("add-member-label"), text("members-tooltip")) { openAddMember(player, land.id) },
            )
            if (land.ownerId == player.uniqueId) {
                buttons += button("rename", text("rename-label"), text("rename-tooltip")) { openRename(player, land.id) }
            }
            buttons += button("members", text("members-label"), text("members-tooltip")) { openMembers(player, land.id) }
            buttons += button("territory", text("territory-label"), text("territory-tooltip")) { openTerritory(player, land.id) }
            claimRadiusButton(player)?.let(buttons::add)
            claimDisplayButton(player, land.id)?.let(buttons::add)
            buttons += button("lands_menu", text("open-lands-label"), text("open-lands-tooltip")) {
                executeForLand(player, land.id, LandsUiCommands::menu)
            }.closing()
            if (land.ownerId == player.uniqueId) {
                buttons += button("delete", text("delete-label"), text("delete-tooltip")) { openDanger(player, land.id) }
            }
            show(
                player,
                PaperDialogScreen(
                    id = "lands.details",
                    title = text("details-title", "land" to land.name),
                    body = listOf(DialogTables.body(
                        rows = listOf(
                            text("table-role-label") to Component.text(role),
                            text("table-territory-label") to text("table-territory-value",
                                "used" to land.chunks.toString(), "maximum" to land.maxChunks.toString()),
                            text("table-members-label") to text("table-slots-value",
                                "used" to land.memberIds.size.toString(), "maximum" to land.maxMembers.toString()),
                            text("table-balance-label") to text("table-coins-value", "value" to amountFormat.format(land.balance)),
                        ),
                        frame = DialogTables.Frame.LEGENDARY,
                    )),
                    buttons = buttons,
                    exitButton = back("back") { openRoot(player) },
                    columns = 2,
                ),
                reopen = { openDetails(player, landId) },
            )
        }
    }

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

    private fun openRename(player: Player, landId: String) {
        withLand(player, landId) { land ->
            if (land.ownerId != player.uniqueId) return@withLand openDetails(player, landId)
            show(
                player,
                PaperDialogScreen(
                    id = "lands.rename",
                    title = text("rename-title", "land" to land.name),
                    body = listOf(PaperDialogBody(text("rename-body"))),
                    inputs = listOf(PaperDialogTextInput(NAME_INPUT, text("name-input"), initial = land.name, maxLength = 24)),
                    buttons = listOf(
                        contextButton("rename_submit", text("rename-submit-label")) { context ->
                            val newName = context.text(NAME_INPUT).orEmpty().trim()
                            val command = runCatching { LandsUiCommands.rename(newName) }.getOrNull()
                            if (command == null) {
                                player.sendMessage(text("invalid-name"))
                                openRename(player, landId)
                            } else {
                                executeForLand(player, landId) { LandsUiCommands.rename(newName) }
                            }
                        }.closing(),
                    ),
                    exitButton = back("back") { openDetails(player, landId) },
                ),
            )
        }
    }

    private fun openMembers(player: Player, landId: String) {
        withLand(player, landId) { land ->
            val memberButtons = land.memberIds
                .asSequence()
                .filter { it != land.ownerId }
                .mapNotNull { memberId -> gateway.playerName(memberId)?.let { memberId to it } }
                .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.second })
                .take(settings.maxListedPlayers)
                .mapIndexed { index, (_, name) ->
                    button(
                        "member_$index",
                        text("member-label", "player" to name),
                        text("member-tooltip", "player" to name),
                    ) { openRemoveMember(player, landId, name) }
                }
                .toList()
            show(
                player,
                PaperDialogScreen(
                    id = "lands.members",
                    title = text("members-title", "land" to land.name),
                    body = listOf(
                        PaperDialogBody(
                            text(
                                "members-body",
                                "members" to land.memberIds.size.toString(),
                                "max_members" to land.maxMembers.toString(),
                            ),
                        ),
                    ),
                    buttons = memberButtons + button("add_member", text("add-member-label")) { openAddMember(player, landId) },
                    exitButton = back("back") { openDetails(player, landId) },
                    columns = 2,
                ),
                reopen = { openMembers(player, landId) },
            )
        }
    }

    fun openAddMember(player: Player, landId: String) {
        withLand(player, landId) { land ->
            val candidates = LandsUiPlanner.addablePlayers(player.uniqueId, land, gateway.onlinePlayers())
                .take(settings.maxListedPlayers)
            val candidateButtons = candidates.mapIndexed { index, candidate ->
                button("candidate_$index", text("candidate-label", "player" to candidate.name)) {
                    executeForLand(player, landId) { LandsUiCommands.addMember(candidate.name) }
                }.closing()
            }
            show(
                player,
                PaperDialogScreen(
                    id = "lands.add",
                    title = text("add-title", "land" to land.name),
                    body = listOf(PaperDialogBody(text("add-body", "limit" to settings.maxListedPlayers.toString()))),
                    inputs = listOf(PaperDialogTextInput(PLAYER_INPUT, text("player-input"), maxLength = 16)),
                    buttons = listOf(
                        contextButton("add_submit", text("add-submit-label")) { context ->
                            val name = context.text(PLAYER_INPUT).orEmpty().trim()
                            if (runCatching { LandsUiCommands.member(name) }.isFailure) {
                                player.sendMessage(text("invalid-player"))
                                openAddMember(player, landId)
                            } else {
                                executeForLand(player, landId) { LandsUiCommands.addMember(name) }
                            }
                        }.closing(),
                    ) + candidateButtons,
                    exitButton = back("back") { openMembers(player, landId) },
                    columns = 2,
                ),
            )
        }
    }

    private fun openRemoveMember(player: Player, landId: String, memberName: String) {
        withLand(player, landId) { land ->
            show(
                player,
                PaperDialogScreen(
                    id = "lands.remove",
                    title = text("remove-title"),
                    body = listOf(PaperDialogBody(text("remove-body", "player" to memberName, "land" to land.name))),
                    buttons = listOf(
                        button("remove_confirm", text("remove-confirm-label", "player" to memberName)) {
                            executeForLand(player, landId) { LandsUiCommands.removeMember(memberName) }
                        }.closing(),
                    ),
                    exitButton = back("back") { openMembers(player, landId) },
                ),
            )
        }
    }

    private fun openTerritory(player: Player, landId: String) {
        withLand(player, landId) { land ->
            show(
                player,
                PaperDialogScreen(
                    id = "lands.territory",
                    title = text("territory-title", "land" to land.name),
                    body = listOf(
                        territorySummary(land),
                        PaperDialogBody(text("territory-body", "land" to land.name), width = 468),
                    ),
                    buttons = listOf(
                        commandButton("claim", "claim-label", "claim-tooltip", player, landId, "claim"),
                        button("unclaim", text("unclaim-label"), text("unclaim-tooltip")) { openUnclaimConfirm(player, landId) },
                        commandButton("setspawn", "setspawn-label", "setspawn-tooltip", player, landId, "setspawn"),
                        commandButton("spawn", "spawn-label", "spawn-tooltip", player, landId, "spawn"),
                        commandButton("areas", "areas-label", "areas-tooltip", player, landId, "menu", "areas"),
                        button("mainblock", text("mainblock-label"), text("mainblock-tooltip")) {
                            openMainblockGuide(player, landId)
                        },
                    ),
                    exitButton = back("back") { openDetails(player, landId) },
                    columns = 2,
                ),
                reopen = { openTerritory(player, landId) },
            )
        }
    }

    private fun openMainblockGuide(player: Player, landId: String) {
        withLand(player, landId) { land ->
            show(
                player,
                PaperDialogScreen(
                    id = "lands.mainblock",
                    title = text("mainblock-title", "land" to land.name),
                    body = listOf(PaperDialogBody(text("mainblock-body"), width = 500)),
                    buttons = listOf(
                        button("lands_menu", text("open-lands-label"), text("open-lands-tooltip")) {
                            executeForLand(player, landId, LandsUiCommands::menu)
                        }.closing(),
                    ),
                    exitButton = back("back") { openTerritory(player, landId) },
                ),
            )
        }
    }

    private fun openUnclaimConfirm(player: Player, landId: String) {
        val claim = gateway.currentClaim(player)
        val land = gateway.land(player, landId)
        if (claim == null || land == null || claim.landId != landId) {
            player.sendMessage(text("unclaim-no-claim"))
            openDetails(player, landId)
            return
        }
        show(
            player,
            PaperDialogScreen(
                id = "lands.unclaim",
                title = text("unclaim-title", "land" to land.name),
                body = listOf(PaperDialogBody(text("unclaim-body", "land" to land.name,
                    "chunk_x" to claim.chunkX.toString(), "chunk_z" to claim.chunkZ.toString()), width = 500)),
                buttons = listOf(
                    button("unclaim_confirm", text("unclaim-confirm-label")) {
                        val fresh = gateway.currentClaim(player)
                        if (!canConfirmUnclaim(claim, fresh, gateway.land(player, landId)?.id)) {
                            player.sendMessage(text("unclaim-stale"))
                            openDetails(player, landId)
                        } else {
                            when (gateway.unclaimCurrent(player, landId)) {
                                LandsUiCommandResult.EXECUTED -> Unit
                                LandsUiCommandResult.LAND_UNAVAILABLE -> {
                                    player.sendMessage(text("land-gone")); openRoot(player)
                                }
                                LandsUiCommandResult.COMMAND_REJECTED -> player.sendMessage(text("action-failed"))
                                LandsUiCommandResult.ACTIVE_SELECTION -> player.sendMessage(text("unclaim-selection-active"))
                            }
                        }
                    }.closing(),
                ),
                exitButton = back("back") { openDetails(player, landId) },
            ),
        )
    }

    private fun openGuide(player: Player) {
        show(
            player,
            PaperDialogScreen(
                id = "lands.guide",
                title = text("guide-title"),
                body = listOf(PaperDialogBody(text("guide-body"), width = 500)),
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
                body = listOf(PaperDialogBody(text("guide-create-body"), width = 500)),
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
                body = listOf(PaperDialogBody(text("guide-expand-body"), width = 500)),
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
                body = listOf(PaperDialogBody(text("guide-members-body"), width = 500)),
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
                body = listOf(PaperDialogBody(text("guide-commands-body"), width = 500)),
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
                        commandButton("claim", "created-claim-label", "claim-tooltip", player, land.id, "claim"),
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

    private fun openDanger(player: Player, landId: String) {
        withLand(player, landId) { land ->
            if (land.ownerId != player.uniqueId) return@withLand openDetails(player, landId)
            show(
                player,
                PaperDialogScreen(
                    id = "lands.danger",
                    title = text("danger-title", "land" to land.name),
                    body = listOf(PaperDialogBody(text("danger-body", "land" to land.name))),
                    buttons = listOf(
                        button("delete_confirm", text("delete-confirm-label")) {
                            executeForLand(player, landId) { LandsUiCommands.flat("delete") }
                        }.closing(),
                    ),
                    exitButton = back("back") { openDetails(player, landId) },
                ),
            )
        }
    }

    private fun commandButton(
        id: String,
        label: String,
        tooltip: String,
        player: Player,
        landId: String,
        vararg arguments: String,
    ): PaperDialogButton = button(id, text(label), text(tooltip)) {
        executeForLand(player, landId) { LandsUiCommands.flat(*arguments) }
    }.closing()

    private fun executeForLand(player: Player, landId: String, command: () -> String) {
        val land = gateway.land(player, landId)
        if (land == null) {
            player.sendMessage(text("land-gone"))
            openRoot(player)
            return
        }
        when (gateway.selectAndExecute(player, land.id, command())) {
            LandsUiCommandResult.EXECUTED -> Unit
            LandsUiCommandResult.LAND_UNAVAILABLE -> {
                player.sendMessage(text("land-gone"))
                openRoot(player)
            }
            LandsUiCommandResult.COMMAND_REJECTED -> player.sendMessage(text("action-failed"))
            LandsUiCommandResult.ACTIVE_SELECTION -> player.sendMessage(text("unclaim-selection-active"))
        }
    }

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
        private val PLAYER_INPUT = PaperDialogInputId.of("player_name")
    }
}
