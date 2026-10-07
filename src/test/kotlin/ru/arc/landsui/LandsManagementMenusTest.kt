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

class LandsManagementMenusTest : StringSpec({
    "role rules stay read-only while natural flags and member actions use the management flow" {
        MockBukkitTestRuntime.open().use {
            val data = Files.createTempDirectory("lands-management")
            val playerId = UUID.randomUUID()
            val player = mockk<Player>(relaxed = true)
            every { player.uniqueId } returns playerId
            val memberId = UUID.randomUUID()
            val land = LandsUiLand("managed", "<red>SafeLand</red>", playerId, 8, 64,
                setOf(playerId, memberId), 20, 0.0, true)
            val context = LandsUiContext(land.id, LandsUiAccess.MEMBER)
            val memberName = "<gold>Builder</gold>"
            val roles = listOf(LandsUiRole("builder", "Builder", false), LandsUiRole("owner", "Owner", false))
            val members = listOf(
                LandsUiMember(playerId, "Owner", "owner", true, true, false, emptySet()),
                LandsUiMember(memberId, memberName, "Visitor", false, false, true, setOf("builder")),
            )
            val view = landsUiTestView(
                context, land,
                permissions = setOf(LandsUiPermission.NATIVE_MENU, LandsUiPermission.DELETE),
                members = members,
                roles = roles,
            ).copy(
                naturalRules = listOf(LandsUiRule("fire_spread", true, true)),
                effectiveRules = listOf(LandsUiRule("block_break", true, false), LandsUiRule("block_place", false, false)),
            )
            val readonlyRuleLabels = listOf(
                "block_break" to "Разрушение блоков",
                "block_place" to "Установка блоков",
                "interact_container" to "Сундуки и хранилища",
                "interact_door" to "Двери",
                "interact_trapdoor" to "Люки",
                "interact_mechanism" to "Кнопки и рычаги",
                "attack_player" to "PvP",
                "attack_animal" to "Урон животным",
                "interact_villager" to "Торговля с жителями",
            )
            val readonlyRoleRules = readonlyRuleLabels.map { (key, _) -> LandsUiRule(key, true, false) }
            val gateway = mockk<LandsUiGateway>(relaxed = true)
            every { gateway.managementView(player, context) } returns view
            every { gateway.roleRules(player, context, "builder") } returns readonlyRoleRules
            every { gateway.change(player, context, LandsUiChange.NativeMenu) } returns LandsUiChangeResult.DISPATCHED
            every {
                gateway.change(player, context, LandsUiChange.NaturalFlag("fire_spread", true))
            } returns LandsUiChangeResult.APPLIED
            every {
                gateway.change(player, context, LandsUiChange.AssignRole(memberId, "builder"))
            } returns LandsUiChangeResult.APPLIED
            every { gateway.change(player, context, LandsUiChange.Remove(memberId)) } returns LandsUiChangeResult.APPLIED
            val settings = try {
                ConfigManager.clear()
                LandsUiConfig.load(data).snapshot()
            } finally {
                ConfigManager.clear()
            }
            Tasks.install(mockk<TaskScheduler>(relaxed = true))
            val click = mockk<PaperDialogClickContext>(relaxed = true)
            every { click.player } returns player
            var screen: PaperDialogScreen? = null
            mockkObject(ArcMenus)
            val controller = LandsUiController(settings, gateway)
            try {
                every { ArcMenus.openDialog(player, any(), any(), any(), any()) } answers { screen = secondArg() }
                controller.openDetails(player, land.id)
                plainText(checkNotNull(screen).title).contains(land.name) shouldBe true

                checkNotNull(screen).buttons.single { it.id.value == "roles" }.onClick.handle(click)
                checkNotNull(screen).id shouldBe "lands.roles"
                checkNotNull(screen).buttons.single { it.id.value == "role_0" }.onClick.handle(click)
                checkNotNull(screen).id shouldBe "lands.role"
                val roleScreen = checkNotNull(screen)
                roleScreen.body.size shouldBe 2
                roleScreen.body.last().width shouldBe 320
                val roleText = roleScreen.body.joinToString("\n") { plainText(it.text) }
                readonlyRuleLabels.forEach { (_, label) -> roleText.contains(label) shouldBe true }
                roleScreen.buttons.map { it.id.value } shouldBe listOf("native")
                roleScreen.buttons.single().closeDialogBeforeAction shouldBe true
                roleScreen.buttons.single().onClick.handle(click)
                verify { gateway.change(player, context, LandsUiChange.NativeMenu) }

                checkNotNull(screen).exitButton!!.onClick.handle(click)
                checkNotNull(screen).exitButton!!.onClick.handle(click)

                checkNotNull(screen).buttons.single { it.id.value == "rules" }.onClick.handle(click)
                checkNotNull(screen).id shouldBe "lands.rules"
                val rulesScreen = checkNotNull(screen)
                rulesScreen.body.size shouldBe 2
                val rulesText = plainText(rulesScreen.body.last().text)
                rulesText.contains("Разрушение блоков") shouldBe true
                rulesText.contains("Установка блоков") shouldBe true
                rulesText.contains("Разрешено") shouldBe true
                rulesText.contains("Запрещено") shouldBe true
                checkNotNull(screen).buttons.single { it.id.value == "environment" }.onClick.handle(click)
                checkNotNull(screen).id shouldBe "lands.environment"
                checkNotNull(screen).buttons.map { it.id.value } shouldBe listOf("flag_0")
                checkNotNull(screen).buttons.single().onClick.handle(click)
                verify { gateway.change(player, context, LandsUiChange.NaturalFlag("fire_spread", true)) }
                checkNotNull(screen).id shouldBe "lands.environment"

                checkNotNull(screen).exitButton!!.onClick.handle(click)
                checkNotNull(screen).exitButton!!.onClick.handle(click)
                checkNotNull(screen).buttons.single { it.id.value == "members" }.onClick.handle(click)
                val memberEntry = checkNotNull(screen).buttons.single { plainText(it.label).contains(memberName) }
                memberEntry.onClick.handle(click)
                checkNotNull(screen).id shouldBe "lands.member"
                plainText(checkNotNull(screen).title).contains(memberName) shouldBe true
                checkNotNull(screen).buttons.map { it.id.value } shouldBe listOf("role", "remove")

                checkNotNull(screen).buttons.single { it.id.value == "role" }.onClick.handle(click)
                checkNotNull(screen).id shouldBe "lands.assign-role"
                checkNotNull(screen).buttons.map { it.id.value } shouldBe listOf("role_0")
                checkNotNull(screen).buttons.single().onClick.handle(click)
                verify { gateway.change(player, context, LandsUiChange.AssignRole(memberId, "builder")) }

                checkNotNull(screen).buttons.single { it.id.value == "remove" }.onClick.handle(click)
                checkNotNull(screen).id shouldBe "lands.confirm-remove"
                checkNotNull(screen).buttons.single { it.id.value == "confirm" }.onClick.handle(click)
                verify { gateway.change(player, context, LandsUiChange.Remove(memberId)) }

                checkNotNull(screen).buttons.single { it.id.value == "settings" }.onClick.handle(click)
                checkNotNull(screen).buttons.single { it.id.value == "delete" }.onClick.handle(click)
                checkNotNull(screen).id shouldBe "lands.confirm-delete"
                val mismatch = mockk<PaperDialogClickContext>(relaxed = true)
                every { mismatch.player } returns player
                every { mismatch.text(any()) } returns "different name"
                checkNotNull(screen).buttons.single { it.id.value == "confirm" }.onClick.handle(mismatch)
                checkNotNull(screen).id shouldBe "lands.confirm-delete"
                verify(exactly = 0) { gateway.change(player, context, LandsUiChange.Delete(playerId)) }
            } finally {
                controller.close()
                Tasks.reset()
                unmockkObject(ArcMenus)
                ConfigManager.clear()
                data.toFile().deleteRecursively()
            }
        }
    }

    "revoked admin search permission prevents a search call" {
        MockBukkitTestRuntime.open().use {
            val data = Files.createTempDirectory("lands-search-permission")
            val player = mockk<Player>(relaxed = true)
            val gateway = mockk<LandsUiGateway>(relaxed = true)
            every { gateway.lands(player) } returns emptyList()
            every { gateway.inspectedLand(player) } returns null
            every { player.hasPermission("lands.admin.command.edit") } returns true
            val settings = try {
                ConfigManager.clear()
                LandsUiConfig.load(data).snapshot()
            } finally {
                ConfigManager.clear()
            }
            Tasks.install(mockk<TaskScheduler>(relaxed = true))
            var screen: PaperDialogScreen? = null
            mockkObject(ArcMenus)
            val controller = LandsUiController(settings, gateway)
            try {
                every { ArcMenus.openDialog(player, any(), any(), any(), any()) } answers { screen = secondArg() }
                controller.openRoot(player)
                val openSearch = mockk<PaperDialogClickContext>(relaxed = true)
                every { openSearch.player } returns player
                checkNotNull(screen).buttons.single { it.id.value == "admin_search" }.onClick.handle(
                    openSearch,
                )
                checkNotNull(screen).id shouldBe "lands.search"

                every { player.hasPermission("lands.admin.command.edit") } returns false
                val searchClick = mockk<PaperDialogClickContext>(relaxed = true)
                every { searchClick.player } returns player
                every { searchClick.text(any()) } returns "private"
                checkNotNull(screen).buttons.single { it.id.value == "search" }.onClick.handle(
                    searchClick,
                )
                checkNotNull(screen).id shouldBe "lands.home"
                checkNotNull(screen).buttons.map { it.id.value }.contains("admin_search") shouldBe false
                verify(exactly = 0) { gateway.searchLands(player, any()) }
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

internal fun landsUiTestView(
    context: LandsUiContext,
    land: LandsUiLand,
    permissions: Set<LandsUiPermission> = emptySet(),
    members: List<LandsUiMember> = listOf(LandsUiMember(land.ownerId, "Owner", "owner", true, true, false, emptySet())),
    roles: List<LandsUiRole> = listOf(LandsUiRole("member", "Member", false)),
): LandsUiManagementView = LandsUiManagementView(
    context = context,
    land = land,
    areaName = "Main",
    viewerRole = "owner",
    description = "",
    members = members.toList(),
    roles = roles.toList(),
    areas = listOf(LandsUiArea(context.areaId ?: "main", "Main", true)),
    effectiveRules = emptyList(),
    naturalRules = emptyList(),
    permissions = permissions.toSet(),
    currentClaim = null,
)

private fun plainText(component: Component): String = PlainTextComponentSerializer.plainText().serialize(component)
