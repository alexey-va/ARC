package ru.arc.landsui

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.TextColor
import net.kyori.adventure.text.format.TextDecoration
import net.kyori.adventure.text.minimessage.MiniMessage
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder
import org.bukkit.entity.Player
import ru.arc.gui.ArcMenus
import ru.arc.paper.menu.DialogTables
import ru.arc.paper.menu.PaperDialogActionId
import ru.arc.paper.menu.PaperDialogBody
import ru.arc.paper.menu.PaperDialogButton
import ru.arc.paper.menu.PaperDialogClickContext
import ru.arc.paper.menu.PaperDialogInputId
import ru.arc.paper.menu.PaperDialogScreen
import ru.arc.paper.menu.PaperDialogTextInput
import java.util.UUID
import java.text.DecimalFormat

/** Native menu pages; the gateway resolves the target and authorizes every click again. */
internal class LandsManagementMenus(
    private val settings: LandsUiSettings,
    private val gateway: LandsUiGateway,
    private val openRoot: (Player) -> Unit,
    private val extraButtons: (Player, LandsUiContext) -> List<PaperDialogButton>,
) {
    fun execute(player: Player, context: LandsUiContext, change: LandsUiChange) =
        apply(player, context, change) { open(player, context) }

    fun openPanelAction(player: Player, context: LandsUiContext, action: LandsUiPanelAction) {
        when (action) {
            LandsUiPanelAction.ADD_MEMBER -> openAddMember(player, context)
            LandsUiPanelAction.MEMBERS -> openMembers(player, context)
            LandsUiPanelAction.RULES -> openRules(player, context)
            LandsUiPanelAction.TERRITORY -> openTerritory(player, context)
            LandsUiPanelAction.SETTINGS -> openSettings(player, context)
            LandsUiPanelAction.OVERVIEW -> open(player, context)
        }
    }

    fun open(player: Player, context: LandsUiContext): Unit = withView(player, context) { view ->
        val land = view.land
        val rows = listOf(
            text("table-owner-label") to value(gateway.playerName(land.ownerId) ?: land.ownerId.toString()),
            text("table-role-label") to value(view.viewerRole),
            text("table-area-label") to value(view.areaName),
            text("table-territory-label") to if (context.access == LandsUiAccess.CURRENT)
                text("inspect-chunks", "chunks" to land.chunks.toString())
            else text("table-territory-value", "used" to land.chunks.toString(), "maximum" to land.maxChunks.toString()),
            text("table-members-label") to text("management-member-count",
                "count" to view.members.size.toString(), "online" to view.members.count { it.online }.toString()),
        ) + if (context.access == LandsUiAccess.CURRENT) emptyList() else listOf(
            text("table-balance-label") to text("table-coins-value", "value" to DecimalFormat("#,##0.##").format(land.balance)),
        )
        val buttons = buildList {
            add(button("members", "members-label") { openMembers(player, context) })
            add(button("rules", "rules-label") { openRules(player, context) })
            add(button("roles", "roles-label") { openRoles(player, context) })
            add(button("territory", "territory-label") { openTerritory(player, context) })
            if (LandsUiPermission.TRUST in view.permissions) {
                add(button("add_member", "add-member-label") { openAddMember(player, context) })
            }
            if (context.access != LandsUiAccess.CURRENT) {
                add(button("settings", "management-settings-label") { openSettings(player, context) })
            }
            if (LandsUiPermission.BORDERS in view.permissions) {
                add(actionButton(player, context, "borders", "borders-label", LandsUiChange.Borders))
            }
            if (context.access == LandsUiAccess.CURRENT) {
                if (gateway.land(player, land.id) != null) add(button("manage", "manage-label") {
                    open(player, context.copy(access = LandsUiAccess.MEMBER))
                })
                if (player.hasPermission(ADMIN_PERMISSION)) add(button("admin", "admin-panel-label") {
                    withView(player, context) { open(player, it.context.copy(access = LandsUiAccess.ADMIN)) }
                })
            }
            addAll(extraButtons(player, context))
        }
        show(player, PaperDialogScreen(
            id = when (context.access) {
                LandsUiAccess.MEMBER -> "lands.details"
                LandsUiAccess.CURRENT -> "lands.inspect"
                LandsUiAccess.ADMIN -> "lands.admin"
            },
            title = text(if (context.access == LandsUiAccess.ADMIN) "admin-title" else "details-title", "land" to land.name),
            body = listOf(DialogTables.body(rows, frame = DialogTables.Frame.LEGENDARY, width = 360)) +
                listOfNotNull(view.description.takeIf { it.isNotBlank() }?.let {
                    PaperDialogBody(text("management-description", "description" to it), width = 460)
                }) + if (context.access == LandsUiAccess.ADMIN) listOf(PaperDialogBody(text("admin-body"))) else emptyList(),
            buttons = buttons,
            exitButton = back { openRoot(player) },
            columns = 2,
        )) { open(player, context) }
    }

    private fun openMembers(player: Player, context: LandsUiContext, page: Int = 0): Unit = withView(player, context) { view ->
        val members = view.members.sortedWith(compareByDescending<LandsUiMember> { it.owner }
            .thenByDescending { it.online }.thenBy(String.CASE_INSENSITIVE_ORDER) { it.name })
        val paging = page(members, page)
        show(player, PaperDialogScreen(
            id = "lands.members",
            title = text("members-title", "land" to view.land.name),
            body = listOf(PaperDialogBody(text("management-members-body", "area" to view.areaName,
                "members" to members.size.toString(), "online" to members.count { it.online }.toString(),
                "page" to (paging.index + 1).toString(), "pages" to paging.pages.toString()))),
            buttons = paging.items.mapIndexed { index, member ->
                button("member_$index", text("management-member-label", "player" to member.name,
                    "role" to member.role, "status" to settings.text(if (member.online) "status-online" else "status-offline"))) {
                    openMember(player, context, member.id, paging.index)
                }
            } + pagination(paging) { openMembers(player, context, it) } +
                if (LandsUiPermission.TRUST in view.permissions) listOf(button("add_member", "add-member-label") {
                    openAddMember(player, context)
                }) else emptyList(),
            exitButton = back { open(player, context) }, columns = 2,
        )) { openMembers(player, context, paging.index) }
    }

    private fun openMember(player: Player, context: LandsUiContext, memberId: UUID, page: Int = 0): Unit = withView(player, context) { view ->
        val member = view.members.firstOrNull { it.id == memberId } ?: return@withView openMembers(player, context, page)
        show(player, PaperDialogScreen(
            id = "lands.member",
            title = text("member-title", "player" to member.name),
            body = listOf(PaperDialogBody(text("member-body", "player" to member.name, "role" to member.role,
                "status" to settings.text(if (member.online) "status-online" else "status-offline"),
                "land" to view.land.name, "area" to view.areaName))),
            buttons = buildList {
                if (member.assignableRoleIds.isNotEmpty()) add(button("role", "assign-role-label") {
                    openAssignRole(player, context, memberId, page)
                })
                if (member.removable) add(button("remove", "remove-member-label") {
                    confirm(player, context, "remove", text("remove-title"),
                        text("remove-body", "land" to view.land.name, "player" to member.name),
                        text("remove-confirm-label", "player" to member.name), LandsUiChange.Remove(memberId),
                        backTo = { openMember(player, context, memberId, page) })
                })
                if (!member.owner && LandsUiPermission.TRANSFER in view.permissions) add(button("transfer", "transfer-label") {
                    confirm(player, context, "transfer", text("transfer-title"),
                        text("transfer-body", "land" to view.land.name, "player" to member.name),
                        text("transfer-confirm"), LandsUiChange.Transfer(memberId, view.land.ownerId),
                        backTo = { openMember(player, context, memberId, page) }, typedName = view.land.name)
                })
            },
            exitButton = back { openMembers(player, context, page) }, columns = 2,
        )) { openMember(player, context, memberId, page) }
    }

    private fun openAssignRole(player: Player, context: LandsUiContext, memberId: UUID, page: Int): Unit = withView(player, context) { view ->
        val member = view.members.firstOrNull { it.id == memberId } ?: return@withView openMembers(player, context, page)
        show(player, PaperDialogScreen(
            id = "lands.assign-role", title = text("assign-role-title", "player" to member.name),
            body = listOf(PaperDialogBody(text("assign-role-body", "role" to member.role, "area" to view.areaName))),
            buttons = view.roles.filter { it.id in member.assignableRoleIds }.mapIndexed { index, role ->
                actionButton(player, context, "role_$index", text("role-entry", "role" to role.name),
                    LandsUiChange.AssignRole(memberId, role.id)) { openMember(player, context, memberId, page) }
            }, exitButton = back { openMember(player, context, memberId, page) }, columns = 2,
        )) { openAssignRole(player, context, memberId, page) }
    }

    fun openAddMember(player: Player, context: LandsUiContext): Unit = withView(player, context) { view ->
        if (LandsUiPermission.TRUST !in view.permissions) return@withView denied(player, context)
        val candidates = LandsUiPlanner.addablePlayers(player.uniqueId,
            view.land.copy(memberIds = view.members.mapTo(linkedSetOf()) { it.id }), gateway.onlinePlayers()).take(settings.maxListedPlayers)
        show(player, PaperDialogScreen(
            id = "lands.add", title = text("add-title", "land" to view.land.name),
            body = listOf(PaperDialogBody(text("add-body", "limit" to settings.maxListedPlayers.toString()))),
            inputs = listOf(PaperDialogTextInput(INPUT, text("player-input"), maxLength = 16)),
            buttons = listOf(inputButton("add_submit", "add-submit-label") { click ->
                val name = click.text(INPUT).orEmpty().trim()
                if (runCatching { LandsUiCommands.member(name) }.isFailure) {
                    player.sendMessage(text("invalid-player")); openAddMember(player, context)
                } else apply(player, context, LandsUiChange.Trust(name)) { openMembers(player, context) }
            }) + candidates.mapIndexed { index, candidate ->
                actionButton(player, context, "candidate_$index", text("candidate-label", "player" to candidate.name),
                    LandsUiChange.Trust(candidate.name)) { openMembers(player, context) }
            }, exitButton = back { openMembers(player, context) }, columns = 2,
        )) { openAddMember(player, context) }
    }

    private fun openRules(player: Player, context: LandsUiContext): Unit = withView(player, context) { view ->
        show(player, PaperDialogScreen(
            id = "lands.rules", title = text("rules-title", "area" to view.areaName),
            body = listOf(PaperDialogBody(text("rules-body", "role" to view.viewerRole))) +
                rulesTable(view.effectiveRules),
            buttons = listOf(button("roles", "roles-label") { openRoles(player, context) },
                button("environment", "environment-label") { openEnvironment(player, context) }),
            exitButton = back { open(player, context) }, columns = 2,
        )) { openRules(player, context) }
    }

    private fun openRoles(player: Player, context: LandsUiContext): Unit = withView(player, context) { view ->
        show(player, PaperDialogScreen(
            id = "lands.roles", title = text("roles-title", "area" to view.areaName),
            body = listOf(PaperDialogBody(text("roles-body"))),
            buttons = view.roles.mapIndexed { index, role ->
                button("role_$index", text(if (role.visitor) "visitor-role-entry" else "role-entry", "role" to role.name)) {
                    openRole(player, context, role.id)
                }
            }, exitButton = back { open(player, context) }, columns = 2,
        )) { openRoles(player, context) }
    }

    private fun openRole(player: Player, context: LandsUiContext, roleId: String): Unit = withView(player, context) { view ->
        val role = view.roles.firstOrNull { it.id == roleId } ?: return@withView openRoles(player, context)
        val rules = gateway.roleRules(player, context, roleId)
        show(player, PaperDialogScreen(
            id = "lands.role", title = text("role-title", "role" to role.name),
            body = listOf(PaperDialogBody(text(if (LandsUiPermission.NATIVE_MENU in view.permissions) "role-body" else "role-readonly-body", "area" to view.areaName))) +
                rulesTable(rules),
            buttons = if (LandsUiPermission.NATIVE_MENU in view.permissions) listOf(
                actionButton(player, context, "native", "native-role-settings-label", LandsUiChange.NativeMenu),
            ) else emptyList(),
            exitButton = back { openRoles(player, context) }, columns = 2,
        )) { openRole(player, context, roleId) }
    }

    private fun openEnvironment(player: Player, context: LandsUiContext): Unit = withView(player, context) { view ->
        show(player, PaperDialogScreen(
            id = "lands.environment", title = text("environment-title", "area" to view.areaName),
            body = listOf(PaperDialogBody(text("environment-body"))) +
                rulesTable(view.naturalRules.filterNot { it.editable }),
            buttons = view.naturalRules.filter { it.editable }.mapIndexed { index, rule ->
                button("flag_$index", ruleText(rule)) {
                    apply(player, context, LandsUiChange.NaturalFlag(rule.key, rule.enabled)) { openEnvironment(player, context) }
                }
            }, exitButton = back { openRules(player, context) }, columns = 2,
        )) { openEnvironment(player, context) }
    }

    private fun openTerritory(player: Player, context: LandsUiContext): Unit = withView(player, context) { view ->
        show(player, PaperDialogScreen(
            id = "lands.territory", title = text("territory-title", "land" to view.land.name),
            body = listOf(PaperDialogBody(text("management-territory-body", "area" to view.areaName,
                "chunks" to view.land.chunks.toString(), "max_chunks" to view.land.maxChunks.toString()))),
            buttons = buildList {
                if (LandsUiPermission.BORDERS in view.permissions) add(actionButton(player, context, "borders", "borders-label", LandsUiChange.Borders))
                if (LandsUiPermission.SPAWN in view.permissions) add(actionButton(player, context, "spawn", "spawn-label", LandsUiChange.Spawn))
                if (LandsUiPermission.CLAIM in view.permissions) add(actionButton(player, context, "claim", "claim-label", LandsUiChange.Claim))
                if (LandsUiPermission.UNCLAIM in view.permissions && view.currentClaim != null) add(button("unclaim", "unclaim-label") {
                    openUnclaim(player, context)
                })
                if (LandsUiPermission.SET_SPAWN in view.permissions) add(button("setspawn", "setspawn-label") {
                    confirm(player, context, "setspawn", text("setspawn-label"),
                        text("setspawn-confirm-body", "land" to view.land.name), text("save-label"), LandsUiChange.SetSpawn,
                        backTo = { openTerritory(player, context) })
                })
                add(button("areas", "areas-label") { openAreas(player, context) })
                if (LandsUiPermission.NATIVE_MENU in view.permissions) add(button("mainblock", "mainblock-label") {
                    show(player, PaperDialogScreen(id = "lands.mainblock", title = text("mainblock-title", "land" to view.land.name),
                        body = listOf(PaperDialogBody(text("mainblock-body"))),
                        buttons = listOf(actionButton(player, context, "native", "open-lands-label", LandsUiChange.NativeMenu)),
                        exitButton = back { openTerritory(player, context) }))
                })
            }, exitButton = back { open(player, context) }, columns = 2,
        )) { openTerritory(player, context) }
    }

    private fun openUnclaim(player: Player, context: LandsUiContext): Unit = withView(player, context) { view ->
        val claim = view.currentClaim
        if (claim == null || claim.landId != view.land.id) {
            player.sendMessage(text("unclaim-no-claim")); return@withView openTerritory(player, context)
        }
        confirm(player, context, "unclaim", text("unclaim-title", "land" to view.land.name),
            text("unclaim-body", "land" to view.land.name, "chunk_x" to claim.chunkX.toString(), "chunk_z" to claim.chunkZ.toString()),
            text("unclaim-confirm-label"), LandsUiChange.Unclaim(claim), backTo = { openTerritory(player, context) })
    }

    private fun openAreas(player: Player, context: LandsUiContext, page: Int = 0): Unit = withView(player, context) { view ->
        val paging = page(view.areas, page)
        show(player, PaperDialogScreen(
            id = "lands.areas", title = text("region-list-title", "land" to view.land.name),
            body = listOf(PaperDialogBody(text("management-areas-body"))),
            buttons = paging.items.mapIndexed { index, area ->
                button("area_$index", text(if (area.main) "main-area-entry" else "region-entry", "area" to area.name)) {
                    open(player, context.copy(areaId = area.id))
                }
            } + pagination(paging) { openAreas(player, context, it) },
            exitButton = back { openTerritory(player, context) }, columns = 2,
        )) { openAreas(player, context, paging.index) }
    }

    private fun openSettings(player: Player, context: LandsUiContext): Unit = withView(player, context) { view ->
        if (context.access == LandsUiAccess.CURRENT) return@withView denied(player, context)
        show(player, PaperDialogScreen(
            id = "lands.settings", title = text("management-settings-title", "land" to view.land.name),
            body = listOf(PaperDialogBody(text("management-settings-body"))),
            buttons = buildList {
                if (LandsUiPermission.RENAME in view.permissions) add(button("rename", "rename-label") { openEditText(player, context, false) })
                if (LandsUiPermission.DESCRIPTION in view.permissions) add(button("description", "description-label") { openEditText(player, context, true) })
                if (LandsUiPermission.TRANSFER in view.permissions) add(button("transfer", "transfer-label") { openMembers(player, context) })
                if (LandsUiPermission.NATIVE_MENU in view.permissions) add(actionButton(player, context, "native", "open-lands-label", LandsUiChange.NativeMenu))
                if (LandsUiPermission.LEAVE in view.permissions) add(button("leave", "leave-label") {
                    confirm(player, context, "leave", text("leave-label"), text("leave-body", "land" to view.land.name),
                        text("leave-confirm"), LandsUiChange.Leave, backTo = { openSettings(player, context) })
                })
                if (LandsUiPermission.DELETE in view.permissions) add(button("delete", "delete-label") {
                    confirm(player, context, "delete", text("danger-title", "land" to view.land.name),
                        text("danger-body", "land" to view.land.name), text("delete-confirm-label"),
                        LandsUiChange.Delete(view.land.ownerId), backTo = { openSettings(player, context) }, typedName = view.land.name)
                })
            }, exitButton = back { open(player, context) }, columns = 2,
        )) { openSettings(player, context) }
    }

    private fun openEditText(player: Player, context: LandsUiContext, description: Boolean): Unit = withView(player, context) { view ->
        val permission = if (description) LandsUiPermission.DESCRIPTION else LandsUiPermission.RENAME
        if (permission !in view.permissions) return@withView denied(player, context)
        val maxLength = if (description) 160 else 24
        show(player, PaperDialogScreen(
            id = if (description) "lands.description" else "lands.rename",
            title = text(if (description) "description-title" else "rename-title", "land" to view.land.name),
            body = listOf(PaperDialogBody(text(if (description) "description-body" else "rename-body"))),
            inputs = listOf(PaperDialogTextInput(INPUT, text(if (description) "description-input" else "name-input"),
                initial = (if (description) view.description else view.land.name).take(maxLength), maxLength = maxLength)),
            buttons = listOf(inputButton("save", "save-label") { click ->
                val entered = click.text(INPUT).orEmpty().trim()
                if (!description && runCatching { LandsUiCommands.landName(entered) }.isFailure) {
                    player.sendMessage(text("invalid-name")); openEditText(player, context, false)
                } else apply(player, context, if (description) LandsUiChange.Description(entered) else LandsUiChange.Rename(entered)) {
                    openSettings(player, context)
                }
            }), exitButton = back { openSettings(player, context) },
        )) { openEditText(player, context, description) }
    }

    fun openSearch(player: Player, query: String = "", page: Int = 0) {
        if (!player.hasPermission(ADMIN_PERMISSION)) {
            player.sendMessage(text("admin-denied")); return openRoot(player)
        }
        val results = if (query.isBlank()) emptyList() else gateway.searchLands(player, query)
        val paging = page(results, page)
        show(player, PaperDialogScreen(
            id = "lands.search", title = text("admin-search-title"),
            body = listOf(PaperDialogBody(text("admin-search-body"))) + if (query.isBlank()) emptyList() else
                listOf(PaperDialogBody(text("admin-search-results", "count" to results.size.toString(),
                    "page" to (paging.index + 1).toString(), "pages" to paging.pages.toString()))),
            inputs = listOf(PaperDialogTextInput(INPUT, text("admin-search-input"), initial = query.take(64), maxLength = 64)),
            buttons = listOf(inputButton("search", "search-submit", close = false) {
                openSearch(player, it.text(INPUT).orEmpty().trim().take(64))
            }) + paging.items.mapIndexed { index, land ->
                button("result_$index", text("admin-search-entry", "land" to land.name,
                    "owner" to (gateway.playerName(land.ownerId) ?: land.ownerId.toString()))) {
                    open(player, LandsUiContext(land.id, LandsUiAccess.ADMIN))
                }
            } + pagination(paging) { openSearch(player, query, it) },
            exitButton = back { openRoot(player) }, columns = 2,
        )) { openSearch(player, query, paging.index) }
    }

    private fun confirm(
        player: Player, context: LandsUiContext, id: String, title: Component, body: Component, label: Component,
        change: LandsUiChange, backTo: () -> Unit, typedName: String? = null,
    ): Unit = withView(player, context) {
        show(player, PaperDialogScreen(
            id = "lands.confirm-$id", title = title,
            body = listOf(PaperDialogBody(body)) + listOfNotNull(typedName?.let { PaperDialogBody(text("confirm-type-name", "land" to it)) }),
            inputs = if (typedName == null) emptyList() else listOf(PaperDialogTextInput(INPUT, text("name-input"), maxLength = 64)),
            buttons = listOf(inputButton("confirm", label) { click ->
                if (typedName != null && click.text(INPUT).orEmpty().trim() != typedName) {
                    player.sendMessage(text("confirm-name-mismatch"))
                    confirm(player, context, id, title, body, label, change, backTo, typedName)
                } else apply(player, context, change) { open(player, context) }
            }), exitButton = back(backTo),
        )) { confirm(player, context, id, title, body, label, change, backTo, typedName) }
    }

    private fun apply(player: Player, context: LandsUiContext, change: LandsUiChange, refresh: () -> Unit) {
        when (val result = gateway.change(player, context, change)) {
            LandsUiChangeResult.APPLIED -> { player.sendMessage(text("management-saved")); refresh() }
            LandsUiChangeResult.DISPATCHED -> Unit // Lands reports the actual command result to the player.
            else -> {
                player.sendMessage(text(when (result) {
                    LandsUiChangeResult.DENIED -> "management-denied"
                    LandsUiChangeResult.NOT_FOUND -> "land-gone"
                    LandsUiChangeResult.STALE -> "management-stale"
                    LandsUiChangeResult.INVALID -> "management-invalid"
                    LandsUiChangeResult.ACTIVE_SELECTION -> "unclaim-selection-active"
                    else -> "action-failed"
                }))
                refresh()
            }
        }
    }

    private fun withView(player: Player, context: LandsUiContext, action: (LandsUiManagementView) -> Unit) {
        val view = gateway.managementView(player, context)
        if (view == null) {
            player.sendMessage(text(if (context.access == LandsUiAccess.CURRENT) "inspect-moved" else "land-gone"))
            openRoot(player)
        } else action(view)
    }

    private fun denied(player: Player, context: LandsUiContext) {
        player.sendMessage(text("management-denied")); open(player, context)
    }

    private fun ruleText(rule: LandsUiRule): Component = text(
        if (rule.enabled) "rule-enabled" else "rule-disabled", "rule" to settings.text("rule-${rule.key}"),
    )

    private fun rulesTable(rules: List<LandsUiRule>): List<PaperDialogBody> =
        if (rules.isEmpty()) emptyList() else listOf(DialogTables.body(
            rows = rules.map {
                text("rule-${it.key}").colorIfAbsent(TextColor.color(0xe8dfd2)) to
                    text(if (it.enabled) "rules-allowed" else "rules-denied")
            },
            headers = text("rules-action-heading") to text("rules-access-heading"),
            frame = DialogTables.Frame.LEGENDARY,
            width = 320,
            columns = DialogTables.Columns.LABEL_WIDE,
        ))

    private fun actionButton(player: Player, context: LandsUiContext, id: String, label: String, change: LandsUiChange): PaperDialogButton =
        actionButton(player, context, id, text(label), change) { open(player, context) }

    private fun actionButton(player: Player, context: LandsUiContext, id: String, label: Component,
        change: LandsUiChange, refresh: () -> Unit): PaperDialogButton =
        button(id, label) { apply(player, context, change, refresh) }.copy(closeDialogBeforeAction = true)

    private fun <T> page(items: List<T>, requested: Int): Page<T> {
        val pages = ((items.size + settings.maxListedPlayers - 1) / settings.maxListedPlayers).coerceAtLeast(1)
        val index = requested.coerceIn(0, pages - 1)
        return Page(items.drop(index * settings.maxListedPlayers).take(settings.maxListedPlayers), index, pages)
    }

    private fun pagination(page: Page<*>, open: (Int) -> Unit): List<PaperDialogButton> = buildList {
        if (page.index > 0) add(button("previous", "previous-page") { open(page.index - 1) })
        if (page.index + 1 < page.pages) add(button("next", "next-page") { open(page.index + 1) })
    }

    private fun inputButton(id: String, label: String, close: Boolean = true, action: (PaperDialogClickContext) -> Unit) =
        inputButton(id, text(label), close, action)

    private fun inputButton(id: String, label: Component, close: Boolean = true, action: (PaperDialogClickContext) -> Unit) =
        PaperDialogButton(PaperDialogActionId.of(id), label, width = 230, closeDialogBeforeAction = close, onClick = { action(it) })

    private fun button(id: String, label: String, action: () -> Unit) = button(id, text(label), action)
    private fun button(id: String, label: Component, action: () -> Unit) = inputButton(id, label, close = false) { action() }
    private fun back(action: () -> Unit) = button("back", "back-label", action).copy(width = 200)
    private fun value(value: String) = text("inspect-value", "value" to value)

    private fun show(player: Player, screen: PaperDialogScreen, reopen: (() -> Unit)? = null) =
        ArcMenus.openDialog(player, screen,
            closeButton = button("close", "close-label") {}.copy(width = 200, closeDialogBeforeAction = true), reopen = reopen)

    private fun text(key: String, vararg values: Pair<String, String>): Component = MiniMessage.miniMessage()
        .deserialize(settings.text(key), *values.map { (name, value) -> Placeholder.component(name, Component.text(value)) }.toTypedArray())
        .decoration(TextDecoration.ITALIC, false)

    private data class Page<T>(val items: List<T>, val index: Int, val pages: Int)
    companion object {
        private val INPUT = PaperDialogInputId.of("value")
        private const val ADMIN_PERMISSION = "lands.admin.command.edit"
    }
}
