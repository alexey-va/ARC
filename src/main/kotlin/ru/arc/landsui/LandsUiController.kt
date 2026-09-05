package ru.arc.landsui

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.minimessage.MiniMessage
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver
import org.bukkit.entity.Player
import ru.arc.core.LifecycleTaskScope
import ru.arc.gui.ArcMenus
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
    private val openHelp: (Player) -> Unit,
) {
    private val miniMessage = MiniMessage.miniMessage()
    private val amountFormat = DecimalFormat("#,##0.##")
    private val tasks = LifecycleTaskScope()

    fun close() = tasks.close()

    fun openRoot(player: Player) {
        val lands = gateway.lands(player)
        val selected = lands.firstOrNull { it.selected }
        val body = mutableListOf(
            PaperDialogBody(
                text(
                    "root-body",
                    "count" to lands.size.toString(),
                    "selected" to (selected?.name ?: settings.text("selected-none")),
                ),
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
        )
        ArcMenus.openDialog(
            player,
            PaperDialogScreen(
                id = "lands.home",
                title = text("root-title"),
                body = body,
                buttons = buttons,
                exitButton = button("help", text("help-label"), text("help-tooltip")) { openHelp(player) },
                columns = 2,
            ),
        )
    }

    fun openInvite(player: Player, target: LandsUiPlayer) {
        val lands = LandsUiPlanner.inviteableLands(target.id, gateway.lands(player))
        if (lands.isEmpty()) {
            player.sendMessage(text("invite-none", "player" to target.name))
            openRoot(player)
            return
        }
        ArcMenus.openDialog(
            player,
            PaperDialogScreen(
                id = "lands.invite-picker",
                title = text("invite-picker-title"),
                body = listOf(PaperDialogBody(text("invite-picker-body", "player" to target.name), width = 500)),
                buttons = lands.mapIndexed { index, land ->
                    button("invite_land_$index", text("land-label", "land" to land.name)) {
                        executeForLand(player, land.id) { LandsUiCommands.addMember(target.name) }
                    }.closing()
                },
                exitButton = back("back") { openRoot(player) },
                columns = 2,
            ),
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

    private fun openDetails(player: Player, landId: String) {
        withLand(player, landId) { land ->
            val role = settings.text(if (land.ownerId == player.uniqueId) "role-owner" else "role-member")
            val buttons = mutableListOf(
                button("lands_menu", text("open-lands-label"), text("open-lands-tooltip")) {
                    executeForLand(player, land.id, LandsUiCommands::menu)
                }.closing(),
                button("members", text("members-label"), text("members-tooltip")) { openMembers(player, land.id) },
                button("territory", text("territory-label"), text("territory-tooltip")) { openTerritory(player, land.id) },
            )
            if (land.ownerId == player.uniqueId) {
                buttons += button("rename", text("rename-label"), text("rename-tooltip")) { openRename(player, land.id) }
                buttons += button("delete", text("delete-label"), text("delete-tooltip")) { openDanger(player, land.id) }
            }
            ArcMenus.openDialog(
                player,
                PaperDialogScreen(
                    id = "lands.details",
                    title = text("details-title", "land" to land.name),
                    body = listOf(
                        PaperDialogBody(
                            text(
                                "details-body",
                                "role" to role,
                                "chunks" to land.chunks.toString(),
                                "max_chunks" to land.maxChunks.toString(),
                                "members" to land.memberIds.size.toString(),
                                "max_members" to land.maxMembers.toString(),
                                "balance" to amountFormat.format(land.balance),
                            ),
                        ),
                    ),
                    buttons = buttons,
                    exitButton = back("back") { openRoot(player) },
                    columns = 2,
                ),
            )
        }
    }

    private fun openCreate(player: Player) {
        ArcMenus.openDialog(
            player,
            PaperDialogScreen(
                id = "lands.create",
                title = text("create-title"),
                body = listOf(PaperDialogBody(text("create-body"))),
                inputs = listOf(PaperDialogTextInput(NAME_INPUT, text("name-input"), maxLength = 24)),
                buttons = listOf(
                    contextButton("create_submit", text("submit-label")) { context ->
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
            ArcMenus.openDialog(
                player,
                PaperDialogScreen(
                    id = "lands.rename",
                    title = text("rename-title", "land" to land.name),
                    body = listOf(PaperDialogBody(text("rename-body"))),
                    inputs = listOf(PaperDialogTextInput(NAME_INPUT, text("name-input"), initial = land.name, maxLength = 24)),
                    buttons = listOf(
                        contextButton("rename_submit", text("submit-label")) { context ->
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
            ArcMenus.openDialog(
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
                    buttons = listOf(button("add_member", text("add-member-label")) { openAddMember(player, landId) }) + memberButtons,
                    exitButton = back("back") { openDetails(player, landId) },
                    columns = 2,
                ),
            )
        }
    }

    private fun openAddMember(player: Player, landId: String) {
        withLand(player, landId) { land ->
            val candidates = LandsUiPlanner.addablePlayers(player.uniqueId, land, gateway.onlinePlayers())
                .take(settings.maxListedPlayers)
            val candidateButtons = candidates.mapIndexed { index, candidate ->
                button("candidate_$index", text("candidate-label", "player" to candidate.name)) {
                    executeForLand(player, landId) { LandsUiCommands.addMember(candidate.name) }
                }.closing()
            }
            ArcMenus.openDialog(
                player,
                PaperDialogScreen(
                    id = "lands.add",
                    title = text("add-title", "land" to land.name),
                    body = listOf(PaperDialogBody(text("add-body", "limit" to settings.maxListedPlayers.toString()))),
                    inputs = listOf(PaperDialogTextInput(PLAYER_INPUT, text("player-input"), maxLength = 16)),
                    buttons = listOf(
                        contextButton("add_submit", text("submit-label")) { context ->
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
            ArcMenus.openDialog(
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
            ArcMenus.openDialog(
                player,
                PaperDialogScreen(
                    id = "lands.territory",
                    title = text("territory-title", "land" to land.name),
                    body = listOf(PaperDialogBody(text("territory-body", "land" to land.name))),
                    buttons = listOf(
                        commandButton("claim", "claim-label", "claim-tooltip", player, landId, "claim"),
                        commandButton("unclaim", "unclaim-label", "unclaim-tooltip", player, landId, "unclaim"),
                        commandButton("setspawn", "setspawn-label", "setspawn-tooltip", player, landId, "spawn", "set"),
                        commandButton("spawn", "spawn-label", "spawn-tooltip", player, landId, "spawn"),
                        commandButton("areas", "areas-label", "areas-tooltip", player, landId, "area", "menu"),
                        button("mainblock", text("mainblock-label"), text("mainblock-tooltip")) {
                            openMainblockGuide(player, landId)
                        },
                    ),
                    exitButton = back("back") { openDetails(player, landId) },
                    columns = 2,
                ),
            )
        }
    }

    private fun openMainblockGuide(player: Player, landId: String) {
        withLand(player, landId) { land ->
            ArcMenus.openDialog(
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

    private fun openGuide(player: Player) {
        ArcMenus.openDialog(
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
        ArcMenus.openDialog(
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
        ArcMenus.openDialog(
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
        ArcMenus.openDialog(
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
        ArcMenus.openDialog(
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
            ArcMenus.openDialog(
                player,
                PaperDialogScreen(
                    id = "lands.created",
                    title = text("created-title"),
                    body = listOf(
                        PaperDialogBody(
                            text(
                                "created-body",
                                "land" to land.name,
                                "chunks" to land.chunks.toString(),
                                "max_chunks" to land.maxChunks.toString(),
                            ),
                            width = 500,
                        ),
                    ),
                    buttons = listOf(
                        commandButton("claim", "created-claim-label", "claim-tooltip", player, land.id, "claim"),
                        button("details", text("created-details-label")) { openDetails(player, land.id) },
                    ),
                    exitButton = back("back") { openRoot(player) },
                    columns = 2,
                ),
            )
        }
    }

    private fun openDanger(player: Player, landId: String) {
        withLand(player, landId) { land ->
            if (land.ownerId != player.uniqueId) return@withLand openDetails(player, landId)
            ArcMenus.openDialog(
                player,
                PaperDialogScreen(
                    id = "lands.danger",
                    title = text("danger-title", "land" to land.name),
                    body = listOf(PaperDialogBody(text("danger-body", "land" to land.name))),
                    buttons = listOf(
                        button("delete_confirm", text("delete-confirm-label")) {
                            executeForLand(player, landId) { LandsUiCommands.land("delete") }
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
        executeForLand(player, landId) { LandsUiCommands.land(*arguments) }
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
        onClick = { action(it) },
    )

    private fun PaperDialogButton.closing(): PaperDialogButton = copy(closeDialogBeforeAction = true)

    private fun button(id: String, label: Component, tooltip: Component = Component.empty(), action: () -> Unit): PaperDialogButton =
        contextButton(id, label, tooltip) { _ -> action() }

    private fun back(id: String, action: () -> Unit): PaperDialogButton = button(id, text("back-label"), action = action)

    private fun text(key: String, vararg values: Pair<String, String>): Component {
        val resolver = TagResolver.builder()
        values.forEach { (name, value) -> resolver.resolver(Placeholder.component(name, Component.text(value))) }
        return miniMessage.deserialize(settings.text(key), resolver.build())
    }

    companion object {
        private const val CREATE_POLL_ATTEMPTS = 10
        private val NAME_INPUT = PaperDialogInputId.of("land_name")
        private val PLAYER_INPUT = PaperDialogInputId.of("player_name")
    }
}
