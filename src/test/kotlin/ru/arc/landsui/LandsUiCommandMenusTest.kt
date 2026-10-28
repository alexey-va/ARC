package ru.arc.landsui

import com.google.gson.GsonBuilder
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import io.mockk.verify
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.minimessage.MiniMessage
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import org.bukkit.entity.Player
import ru.arc.config.ConfigManager
import ru.arc.core.TaskScheduler
import ru.arc.core.Tasks
import ru.arc.gui.ArcMenus
import ru.arc.paper.menu.PaperDialogClickContext
import ru.arc.paper.menu.PaperDialogScreen
import ru.arc.paper.testing.MockBukkitTestRuntime
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID

class LandsUiCommandMenusTest : StringSpec({
    "command entries reuse native screens and explicit area identity without selecting or mutating land" {
        MockBukkitTestRuntime.open().use {
            val data = Files.createTempDirectory("lands-command-menus")
            val player = mockk<Player>(relaxed = true)
            val owner = UUID.randomUUID()
            every { player.uniqueId } returns owner
            val land = LandsUiLand("selected-land", "Дом", owner, 3, 64, setOf(owner), 20, 0.0, true)
            val context = LandsUiContext(land.id, LandsUiAccess.MEMBER, "selected-area")
            val gateway = mockk<LandsUiGateway>(relaxed = true)
            every { gateway.commandContext(player, any()) } returns context
            every { gateway.managementView(player, context) } returns landsUiTestView(context, land,
                permissions = setOf(LandsUiPermission.TRUST, LandsUiPermission.RENAME, LandsUiPermission.DESCRIPTION,
                    LandsUiPermission.LEAVE, LandsUiPermission.DELETE))
            every { gateway.inspectedLand(player) } returns null
            val settings = try {
                ConfigManager.clear()
                LandsUiConfig.load(data).snapshot()
            } finally { ConfigManager.clear() }
            Tasks.install(mockk<TaskScheduler>(relaxed = true))
            var screen: PaperDialogScreen? = null
            mockkObject(ArcMenus)
            val controller = LandsUiController(settings, gateway)
            try {
                every { ArcMenus.openDialog(player, any(), any(), any(), any()) } answers { screen = secondArg() }
                mapOf(
                    LandsUiDestination.OVERVIEW to "lands.details",
                    LandsUiDestination.ADD_MEMBER to "lands.add",
                    LandsUiDestination.MEMBERS to "lands.members",
                    LandsUiDestination.RULES to "lands.rules",
                    LandsUiDestination.ROLES to "lands.roles",
                    LandsUiDestination.ENVIRONMENT to "lands.environment",
                    LandsUiDestination.TERRITORY to "lands.territory",
                    LandsUiDestination.SETTINGS to "lands.settings",
                    LandsUiDestination.RENAME to "lands.rename",
                    LandsUiDestination.DESCRIPTION to "lands.description",
                    LandsUiDestination.LEAVE to "lands.confirm-leave",
                    LandsUiDestination.DELETE to "lands.confirm-delete",
                    LandsUiDestination.AREAS to "lands.areas",
                    LandsUiDestination.ROOT to "lands.home",
                    LandsUiDestination.CREATE to "lands.create",
                    LandsUiDestination.GUIDE to "lands.guide",
                ).forEach { (destination, expectedId) ->
                    controller.openCommand(player, LandsUiCommandRoute(destination, "Green_House"))
                    checkNotNull(screen).id shouldBe expectedId
                }
                verify(exactly = 13) { gateway.commandContext(player, "Green_House") }
                every { gateway.currentLandId(player) } returns null
                controller.openCommand(player, LandsUiCommandRoute(LandsUiDestination.CURRENT))
                checkNotNull(screen).id shouldBe "lands.home"
                controller.openArea(player, land.id, context.areaId!!)
                checkNotNull(screen).id shouldBe "lands.details"
                controller.openCommand(player, LandsUiCommandRoute(LandsUiDestination.AREAS))
                val click = mockk<PaperDialogClickContext>(relaxed = true)
                checkNotNull(screen).buttons.single { it.id.value == "area_0" }.onClick.handle(click)
                checkNotNull(screen).id shouldBe "lands.details"
                every { gateway.commandContext(player, "missing") } returns null
                controller.openCommand(player, LandsUiCommandRoute(LandsUiDestination.ADD_MEMBER, "missing"))
                checkNotNull(screen).id shouldBe "lands.home"
                verify(exactly = 1) { gateway.currentLandId(player) }
                verify(exactly = 0) { gateway.select(player, any()) }
                verify(exactly = 0) { gateway.change(player, any(), any()) }
            } finally {
                controller.close()
                Tasks.reset()
                unmockkObject(ArcMenus)
                ConfigManager.clear()
                data.toFile().deleteRecursively()
            }
        }
    }

    "root starts with a utility row and literal owner labels while expansion selection is explicit" {
        MockBukkitTestRuntime.open().use {
            val data = Files.createTempDirectory("lands-root-menu")
            val player = mockk<Player>(relaxed = true)
            val owner = UUID.randomUUID()
            every { player.uniqueId } returns owner
            every { player.hasPermission("lands.command.edit") } returns true
            every { player.hasPermission("lands.admin.command.edit") } returns false
            var land = LandsUiLand("owned", "<red>Долина</red>", owner, 3, 64, setOf(owner), 20, 0.0, false)
            val context = LandsUiContext(land.id, LandsUiAccess.MEMBER)
            val gateway = mockk<LandsUiGateway>(relaxed = true)
            every { gateway.lands(player) } answers { listOf(land) }
            every { gateway.inspectedLand(player) } answers { land }
            every { gateway.playerName(owner) } returns "<gold>Builder</gold>"
            every { gateway.managementView(player, context) } answers {
                landsUiTestView(context, land, permissions = setOf(LandsUiPermission.CLAIM))
            }
            every { gateway.select(player, land.id) } answers { land = land.copy(selected = true); true }
            val settings = try {
                ConfigManager.clear()
                LandsUiConfig.load(data).snapshot()
            } finally { ConfigManager.clear() }
            Tasks.install(mockk<TaskScheduler>(relaxed = true))
            var screen: PaperDialogScreen? = null
            val exports = linkedMapOf<String, Any>()
            mockkObject(ArcMenus)
            val controller = LandsUiController(settings, gateway)
            try {
                every { ArcMenus.openDialog(player, any(), any(), any(), any()) } answers { screen = secondArg() }
                controller.openRoot(player)
                val root = checkNotNull(screen)
                root.buttons.map { it.id.value } shouldBe listOf("inspect", "claim_get", "create", "guide", "land_0")
                plain(root.buttons.last().label) shouldBe "○ <red>Долина</red> (<gold>Builder</gold>) ›"
                root.body.any { plain(it.text).contains("/lands ") } shouldBe false
                verify(exactly = 0) { gateway.select(player, any()) }
                verify(exactly = 0) { gateway.giveClaimBlock(player) }

                val click = mockk<PaperDialogClickContext>(relaxed = true)
                root.buttons.last().onClick.handle(click)
                val card = checkNotNull(screen)
                card.id shouldBe "lands.details"
                verify(exactly = 0) { gateway.select(player, any()) }
                card.buttons.single { it.id.value == "select" }.onClick.handle(click)
                verify(exactly = 1) { gateway.select(player, land.id) }
                checkNotNull(screen).buttons.any { it.id.value == "select" } shouldBe false
                checkNotNull(screen).body.any { plain(it.text).contains("✔ Это поселение") } shouldBe true

                land = land.copy(name = "Северная долина")
                every { gateway.playerName(owner) } returns "BuilderAlex"
                controller.openRoot(player)
                exports["root-nearby"] = exportLandsDialogScreen(checkNotNull(screen))
                every { player.hasPermission("lands.command.invites.menu") } returns true
                every { gateway.invitations(player) } returns listOf(
                    LandsUiInvitation("invited", "Лесная гавань", owner, 123L, LandsUiInviteIntent.TRUST),
                )
                controller.openRoot(player)
                checkNotNull(screen).buttons.map { it.id.value } shouldBe listOf("inspect", "claim_get", "create", "guide", "invites", "land_0")
                exports["root-inbox"] = exportLandsDialogScreen(checkNotNull(screen))
                checkNotNull(screen).buttons.single { it.id.value == "invites" }.onClick.handle(click)
                checkNotNull(screen).id shouldBe "lands.invites"
                every { gateway.invitations(player) } returns emptyList()
                every { gateway.inspectedLand(player) } returns null
                controller.openRoot(player)
                checkNotNull(screen).buttons.map { it.id.value } shouldBe listOf("claim_get", "create", "guide", "land_0")
                exports["root-away"] = exportLandsDialogScreen(checkNotNull(screen))
                land = land.copy(name = "ДолинаСеверныхСтроителей")
                controller.openRoot(player)
                exports["root-away-long"] = exportLandsDialogScreen(checkNotNull(screen))
                land = land.copy(name = "Северная долина", selected = false)
                controller.openDetails(player, land.id)
                exports["selection-card"] = exportLandsDialogScreen(checkNotNull(screen))
                checkNotNull(screen).buttons.single { it.id.value == "territory" }.onClick.handle(click)
                checkNotNull(screen).buttons.single { it.id.value == "mainblock" }.onClick.handle(click)
                checkNotNull(screen).id shouldBe "lands.mainblock"
                checkNotNull(screen).buttons.map { it.id.value } shouldBe listOf("claim_get")
                exports["claim-block-guide"] = exportLandsDialogScreen(checkNotNull(screen))
                controller.openDetails(player, land.id)
                every { gateway.select(player, land.id) } returns false
                checkNotNull(screen).buttons.single { it.id.value == "select" }.onClick.handle(click)
                checkNotNull(screen).id shouldBe "lands.home"
                verify(exactly = 0) { gateway.change(player, any(), any()) }

                val output = Path.of("build/reports/lands-command-ui/screens.json")
                Files.createDirectories(output.parent)
                Files.writeString(output, GsonBuilder().disableHtmlEscaping().setPrettyPrinting().create().toJson(exports))
            } finally {
                controller.close()
                Tasks.reset()
                unmockkObject(ArcMenus)
                ConfigManager.clear()
                data.toFile().deleteRecursively()
            }
        }
    }
})

private fun plain(component: Component) = PlainTextComponentSerializer.plainText().serialize(component)

internal fun exportLandsDialogScreen(screen: PaperDialogScreen): Map<String, Any> {
    val mini = MiniMessage.miniMessage()
    val legacy = LegacyComponentSerializer.builder().character('&').hexColors().build()
    val exit = screen.exitButton
    return mapOf(
        "id" to screen.id, "title" to legacy.serialize(screen.title), "columns" to screen.columns,
        "body" to screen.body.map { mapOf("text" to legacy.serialize(it.text), "font-source" to mini.serialize(it.text), "width" to it.width) },
        "buttons" to screen.buttons.map { mapOf("id" to it.id.value, "text" to legacy.serialize(it.label),
            "tooltip" to legacy.serialize(it.tooltip), "font-source" to mini.serialize(it.label), "width" to it.width) },
        "exit" to mapOf("text" to legacy.serialize(exit?.label ?: Component.text("Закрыть")),
            "tooltip" to legacy.serialize(exit?.tooltip ?: Component.empty()), "width" to (exit?.width ?: 200)),
    )
}
