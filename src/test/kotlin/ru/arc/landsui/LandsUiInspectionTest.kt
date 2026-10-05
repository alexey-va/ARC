package ru.arc.landsui

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import io.mockk.verify
import net.kyori.adventure.text.Component
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
    "visitor sees current members across pages without admin or mutation actions" {
        MockBukkitTestRuntime.open().use {
            val data = Files.createTempDirectory("lands-inspection")
            val player = mockk<Player>(relaxed = true)
            val owner = UUID.randomUUID()
            val members = (1..13).map { UUID(0, it.toLong()) }.toSet() + owner
            val land = LandsUiLand("foreign", "Соседи", owner, 7, 64, members, 25, 1234.0, false)
            val currentContext = LandsUiContext(land.id, LandsUiAccess.CURRENT)
            val unknown = members.first { it != owner }
            val uiMembers = members.map { id ->
                LandsUiMember(
                    id = id,
                    name = when (id) {
                        owner -> "HeadPlayer"
                        unknown -> id.toString()
                        else -> "Player${id.leastSignificantBits}"
                    },
                    role = if (id == owner) "owner" else "member",
                    online = false,
                    owner = id == owner,
                    removable = false,
                    assignableRoleIds = emptySet(),
                )
            }
            val currentView = landsUiTestView(currentContext, land, members = uiMembers)
            val gateway = mockk<LandsUiGateway>(relaxed = true)
            every { gateway.lands(player) } returns emptyList()
            every { gateway.land(player, any()) } returns null
            every { gateway.currentLandId(player) } returns land.id
            every { gateway.inspectedLand(player) } returns land
            every { gateway.playerName(owner) } returns "HeadPlayer"
            every { gateway.managementView(player, currentContext) } returns currentView
            val context = mockk<PaperDialogClickContext>(relaxed = true)
            every { context.player } returns player
            every { player.hasPermission("lands.admin.command.edit") } returns false
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
                val inspection = checkNotNull(screen)
                inspection.id shouldBe "lands.inspect"
                bodyText(inspection).contains("HeadPlayer") shouldBe true
                bodyText(inspection).contains("1234") shouldBe false
                inspection.buttons.map { it.id.value } shouldBe listOf("members", "rules", "roles", "territory")

                inspection.buttons.single { it.id.value == "members" }.onClick.handle(context)
                val firstPage = checkNotNull(screen)
                firstPage.id shouldBe "lands.members"
                bodyText(firstPage).contains("Страница 1 из 2") shouldBe true
                firstPage.buttons.last().id.value shouldBe "next"
                firstPage.buttons.single { it.id.value == "next" }.onClick.handle(context)
                val lastPage = checkNotNull(screen)
                bodyText(lastPage).contains("Страница 2 из 2") shouldBe true
                lastPage.buttons.last().id.value shouldBe "previous"

                val memberLabels = (firstPage.buttons + lastPage.buttons)
                    .filter { it.id.value.startsWith("member_") }
                    .map { plainText(it.label) }
                memberLabels.size shouldBe members.size
                memberLabels.joinToString("\n").contains("HeadPlayer") shouldBe true
                memberLabels.joinToString("\n").contains(unknown.toString()) shouldBe true
                members.filter { it != owner && it != unknown }.forEach { id ->
                    memberLabels.joinToString("\n").contains("Player${id.leastSignificantBits}") shouldBe true
                }

                lastPage.exitButton!!.onClick.handle(context)
                checkNotNull(screen).id shouldBe "lands.inspect"
                controller.openCurrent(player)
                checkNotNull(screen).id shouldBe "lands.inspect"

                // A current-view lookup can expire after a page was opened (for example, the player moved).
                every { gateway.managementView(player, currentContext) } returns null
                controller.openCurrent(player)
                checkNotNull(screen).id shouldBe "lands.home"

                every { gateway.managementView(player, currentContext) } returns currentView
                controller.openCurrent(player)
                checkNotNull(screen).buttons.map { it.id.value } shouldBe listOf("members", "rules", "roles", "territory")
                every { player.hasPermission("lands.admin.command.edit") } returns true
                controller.openCurrent(player)
                checkNotNull(screen).buttons.map { it.id.value } shouldBe listOf("members", "rules", "roles", "territory", "admin")

                verify(exactly = 0) { gateway.select(any(), any()) }
                verify(exactly = 0) { gateway.selectAndExecute(any(), any(), any()) }
                verify(exactly = 0) { gateway.execute(any(), any()) }
                verify(exactly = 0) { gateway.administerCurrent(any(), any(), any()) }
                verify(exactly = 0) { gateway.change(any(), any(), any()) }
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

private fun plainText(component: Component): String = PlainTextComponentSerializer.plainText().serialize(component)
