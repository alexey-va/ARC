package ru.arc.landsui

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import io.mockk.verify
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
import java.util.UUID

class LandsUiInspectionTest : StringSpec({
    "visitor sees current owner and every member without selecting or modifying a foreign land" {
        MockBukkitTestRuntime.open().use {
            val data = Files.createTempDirectory("lands-inspection")
            val player = mockk<Player>(relaxed = true)
            val owner = UUID.randomUUID()
            val members = (1..13).map { UUID(0, it.toLong()) }.toSet() + owner
            val land = LandsUiLand("foreign", "Соседи", owner, 7, 64, members, 25, 1234.0, false)
            val gateway = mockk<LandsUiGateway>(relaxed = true)
            every { gateway.lands(player) } returns emptyList()
            every { gateway.land(player, any()) } returns null
            every { gateway.currentLandId(player) } returns land.id
            every { gateway.inspectedLand(player) } returns land
            every { gateway.playerName(any()) } answers { "Player" + firstArg<UUID>().leastSignificantBits }
            every { gateway.playerName(owner) } returns "HeadPlayer"
            // Unknown offline profiles must remain visible, not silently disappear.
            val unknown = members.first { it != owner }
            every { gateway.playerName(unknown) } returns null
            val context = mockk<PaperDialogClickContext>(relaxed = true)
            every { context.player } returns player
            Tasks.install(mockk<TaskScheduler>(relaxed = true))
            ConfigManager.clear()
            val controller = LandsUiController(LandsUiConfig.load(data).snapshot(), gateway)
            var screen: PaperDialogScreen? = null
            mockkObject(ArcMenus)
            try {
                every { ArcMenus.openDialog(player, any(), any(), any(), any()) } answers { screen = secondArg() }
                controller.openRoot(player)
                checkNotNull(screen).buttons.first().id.value shouldBe "inspect"
                bodyText(checkNotNull(screen)).contains("HeadPlayer") shouldBe true
                checkNotNull(screen).buttons.first().onClick.handle(context)
                checkNotNull(screen).id shouldBe "lands.inspect"
                bodyText(checkNotNull(screen)).contains("HeadPlayer") shouldBe true
                bodyText(checkNotNull(screen)).contains("1234") shouldBe false
                checkNotNull(screen).buttons.map { it.id.value } shouldBe listOf("inspect_members")
                checkNotNull(screen).buttons.single().onClick.handle(context)
                val firstPage = checkNotNull(screen)
                firstPage.id shouldBe "lands.inspect-members"
                bodyText(firstPage).contains("HeadPlayer") shouldBe true
                bodyText(firstPage).contains(unknown.toString().takeLast(12)) shouldBe true
                firstPage.buttons.single { it.id.value == "next" }.onClick.handle(context)
                val lastPage = checkNotNull(screen)
                bodyText(lastPage).contains("Страница 2 из 2") shouldBe true
                lastPage.buttons.map { it.id.value } shouldBe listOf("previous")
                val allText = bodyText(firstPage) + bodyText(lastPage)
                members.filter { it != owner && it != unknown }.forEach { id ->
                    allText.contains("Player" + id.leastSignificantBits) shouldBe true
                }
                lastPage.exitButton!!.onClick.handle(context)
                checkNotNull(screen).id shouldBe "lands.inspect"
                controller.openCurrent(player)
                checkNotNull(screen).id shouldBe "lands.inspect"
                every { gateway.inspectedLand(player) } returns land.copy(id = "another-land")
                checkNotNull(screen).buttons.first().onClick.handle(context)
                checkNotNull(screen).id shouldBe "lands.home"
                verify(exactly = 0) { gateway.select(any(), any()) }
                verify(exactly = 0) { gateway.selectAndExecute(any(), any(), any()) }
                verify(exactly = 0) { gateway.execute(any(), any()) }
                verify(exactly = 0) { gateway.administerCurrent(any(), any(), any()) }

                every { gateway.inspectedLand(player) } returns land
                every { player.hasPermission("lands.admin.command.edit") } returns true
                every { player.hasPermission("lands.command.menu") } returns true
                every { player.hasPermission("lands.command.member.menu") } returns true
                controller.openCurrent(player)
                val adminScreen = checkNotNull(screen)
                adminScreen.buttons.map { it.id.value } shouldBe listOf("inspect_members", "admin_menu", "admin_members")
                every { gateway.administerCurrent(player, land.id, LandsUiAdminAction.MEMBERS) } returns LandsUiCommandResult.EXECUTED
                adminScreen.buttons.single { it.id.value == "admin_members" }.onClick.handle(context)
                verify { gateway.administerCurrent(player, land.id, LandsUiAdminAction.MEMBERS) }
                every { player.hasPermission("lands.admin.command.edit") } returns false
                adminScreen.buttons.single { it.id.value == "admin_menu" }.onClick.handle(context)
                verify(exactly = 0) { gateway.administerCurrent(any(), any(), LandsUiAdminAction.MENU) }
                checkNotNull(screen).buttons.map { it.id.value } shouldBe listOf("inspect_members")
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

private fun bodyText(screen: PaperDialogScreen): String = screen.body.joinToString("\n") {
    PlainTextComponentSerializer.plainText().serialize(it.text)
}
