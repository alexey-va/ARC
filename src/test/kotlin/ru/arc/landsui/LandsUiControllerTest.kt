package ru.arc.landsui

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import io.mockk.verify
import net.kyori.adventure.text.Component
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.inventory.ItemStack
import org.bukkit.persistence.PersistentDataType
import org.bukkit.entity.Player
import ru.arc.config.ConfigManager
import ru.arc.gui.ArcMenus
import ru.arc.core.Tasks
import ru.arc.core.TaskScheduler
import ru.arc.paper.menu.PaperDialogClickContext
import ru.arc.paper.menu.PaperDialogScreen
import ru.arc.paper.testing.MockBukkitTestRuntime
import java.nio.file.Files
import java.util.UUID

class LandsUiControllerTest : StringSpec({
    "panel actions resolve the current member land and open existing pages without selecting it" {
        MockBukkitTestRuntime.open().use {
            val dataPath = Files.createTempDirectory("lands-ui-panel")
            val playerId = UUID.randomUUID()
            val player = mockk<Player>(relaxed = true)
            every { player.uniqueId } returns playerId
            val land = LandsUiLand("panel-land", "Дом", playerId, 1, 64, setOf(playerId), 12, 0.0, true)
            val gateway = mockk<LandsUiGateway>(relaxed = true)
            val context = LandsUiContext(land.id, LandsUiAccess.MEMBER)
            val currentContext = LandsUiContext(land.id, LandsUiAccess.CURRENT)
            every { gateway.currentLandId(player) } returns land.id
            every { gateway.land(player, land.id) } returns land
            every { gateway.managementView(player, context) } returns landsUiTestView(
                context, land, permissions = setOf(LandsUiPermission.TRUST, LandsUiPermission.NATIVE_MENU),
            )
            every { gateway.managementView(player, currentContext) } returns landsUiTestView(currentContext, land)
            val settings = try {
                ConfigManager.clear()
                LandsUiConfig.load(dataPath).snapshot()
            } finally {
                ConfigManager.clear()
            }

            Tasks.install(mockk<TaskScheduler>(relaxed = true))
            var screen: PaperDialogScreen? = null
            mockkObject(ArcMenus)
            try {
                every { ArcMenus.openDialog(player, any(), any(), any(), any()) } answers { screen = secondArg() }
                val controller = LandsUiController(settings, gateway)
                try {
                    mapOf(
                        LandsUiPanelAction.ADD_MEMBER to "lands.add",
                        LandsUiPanelAction.MEMBERS to "lands.members",
                        LandsUiPanelAction.RULES to "lands.rules",
                        LandsUiPanelAction.TERRITORY to "lands.territory",
                        LandsUiPanelAction.SETTINGS to "lands.settings",
                        LandsUiPanelAction.OVERVIEW to "lands.details",
                    ).forEach { (action, expectedScreen) ->
                        controller.openPanelAction(player, land.id, action)
                        checkNotNull(screen).id shouldBe expectedScreen
                    }

                    every { gateway.currentLandId(player) } returns "moved-land"
                    controller.openPanelAction(player, land.id, LandsUiPanelAction.OVERVIEW)
                    checkNotNull(screen).id shouldBe "lands.home"

                    every { gateway.currentLandId(player) } returns land.id
                    every { gateway.land(player, land.id) } returns null
                    controller.openPanelAction(player, land.id, LandsUiPanelAction.MEMBERS)
                    checkNotNull(screen).id shouldBe "lands.home"

                    verify(exactly = 6) { gateway.managementView(player, context) }
                    verify(exactly = 1) { gateway.managementView(player, currentContext) }
                    verify(exactly = 0) { gateway.select(player, any()) }
                    verify(exactly = 0) { gateway.change(player, any(), any()) }
                } finally {
                    controller.close()
                }
            } finally {
                Tasks.reset()
                unmockkObject(ArcMenus)
                dataPath.toFile().deleteRecursively()
                ConfigManager.clear()
            }
        }
    }

    "details add-member and back actions rebuild valid screens" {
        MockBukkitTestRuntime.open().use {
            val dataPath = Files.createTempDirectory("lands-ui-controller")
            val playerId = UUID.fromString("00000000-0000-0000-0000-000000000001")
            val player = mockk<Player>(relaxed = true)
            every { player.uniqueId } returns playerId
            every { player.name } returns "Viewer"
            val land = LandsUiLand(
                id = "land-1",
                name = "Дом",
                ownerId = playerId,
                chunks = 1,
                maxChunks = 64,
                memberIds = setOf(playerId),
                maxMembers = 12,
                balance = 0.0,
                selected = true,
            )
            val gateway = mockk<LandsUiGateway>(relaxed = true)
            every { gateway.land(player, land.id) } returns land
            val memberContext = LandsUiContext(land.id, LandsUiAccess.MEMBER)
            val currentContext = LandsUiContext(land.id, LandsUiAccess.CURRENT)
            every { gateway.managementView(player, memberContext) } returns landsUiTestView(
                memberContext, land, permissions = setOf(LandsUiPermission.TRUST, LandsUiPermission.NATIVE_MENU),
            )
            every { gateway.managementView(player, currentContext) } returns landsUiTestView(currentContext, land)
            val settings = try {
                ConfigManager.clear()
                LandsUiConfig.load(dataPath).snapshot()
            } finally {
                ConfigManager.clear()
            }

            Tasks.install(mockk<TaskScheduler>(relaxed = true))
            var screen: PaperDialogScreen? = null
            mockkObject(ArcMenus)
            try {
                every { ArcMenus.openDialog(player, any(), any(), any(), any()) } answers {
                    screen = secondArg()
                }
                val controller = LandsUiController(settings, gateway)
                try {
                    controller.openDetails(player, land.id)
                    val details = checkNotNull(screen)
                    val detailsIds = details.buttons.map { it.id.value }
                    detailsIds shouldContain "add_member"
                    detailsIds shouldBe listOf(
                        "members", "add_member", "rules", "roles", "territory", "settings",
                    )
                    ("region_tool" in detailsIds) shouldBe false
                    detailsIds.all { '-' !in it } shouldBe true

                    val context = mockk<PaperDialogClickContext>(relaxed = true)
                    every { context.player } returns player
                    details.buttons.single { it.id.value == "add_member" }.onClick.handle(context)
                    checkNotNull(screen).id shouldBe "lands.add"

                    checkNotNull(screen).exitButton!!.onClick.handle(context)
                    checkNotNull(screen).id shouldBe "lands.members"
                    checkNotNull(screen).exitButton!!.onClick.handle(context)
                    checkNotNull(screen).id shouldBe "lands.details"

                    every { gateway.currentLandId(player) } returns land.id
                    controller.openCurrent(player)
                    checkNotNull(screen).id shouldBe "lands.details"
                    checkNotNull(screen).exitButton!!.onClick.handle(context)
                    checkNotNull(screen).id shouldBe "lands.home"
                    every { gateway.currentLandId(player) } returns null
                    controller.openCurrent(player)
                    checkNotNull(screen).id shouldBe "lands.home"
                    every { gateway.currentLandId(player) } returns "foreign-land"
                    every { gateway.land(player, "foreign-land") } returns null
                    every {
                        gateway.managementView(player, LandsUiContext("foreign-land", LandsUiAccess.CURRENT))
                    } returns null
                    controller.openCurrent(player)
                    checkNotNull(screen).id shouldBe "lands.home"

                    // Reopen once more through the public entry point to catch stale-screen cycles.
                    controller.openDetails(player, land.id)
                    checkNotNull(screen).id shouldBe "lands.details"
                    verify(exactly = 0) { gateway.select(player, any()) }
                } finally {
                    controller.close()
                }
            } finally {
                Tasks.reset()
                unmockkObject(ArcMenus)
                dataPath.toFile().deleteRecursively()
                ConfigManager.clear()
            }
        }
    }

    "list current and held-block overview compose the same member card without selecting land or item settings" {
        MockBukkitTestRuntime.open().use { paper ->
            val dataPath = Files.createTempDirectory("lands-ui-card")
            val player = paper.addPlayer("CardViewer")
            player.isOp = true
            val playerId = player.uniqueId
            val item = ItemStack(Material.GOLD_BLOCK, 6).apply {
                editMeta { meta ->
                    meta.displayName(Component.text("Claim block"))
                    meta.persistentDataContainer.set(NamespacedKey("lands", "type"), PersistentDataType.STRING, "CLAIM_BLOCK")
                    meta.persistentDataContainer.set(NamespacedKey("lands", "radius"), PersistentDataType.INTEGER, 2)
                }
            }
            player.inventory.setItemInMainHand(item)
            val owner = UUID.randomUUID()
            val land = LandsUiLand("land-card", "Дом", owner, 1, 64, setOf(owner, playerId), 12, 0.0, false)
            val gateway = mockk<LandsUiGateway>(relaxed = true)
            every { gateway.lands(player) } returns listOf(land)
            every { gateway.land(player, land.id) } returns land
            every { gateway.inspectedLand(player) } returns land
            every { gateway.currentLandId(player) } returns land.id
            val memberContext = LandsUiContext(land.id, LandsUiAccess.MEMBER)
            val mainContext = memberContext.copy(areaId = "main")
            val currentContext = LandsUiContext(land.id, LandsUiAccess.CURRENT)
            var mainView = landsUiTestView(mainContext, land, permissions = setOf(
                LandsUiPermission.TRUST, LandsUiPermission.SPAWN, LandsUiPermission.BORDERS,
                LandsUiPermission.CLAIM, LandsUiPermission.UNCLAIM, LandsUiPermission.SET_SPAWN,
                LandsUiPermission.NATIVE_MENU,
            )).copy(currentClaim = LandsUiClaim(land.id, player.world.uid, 0, 0))
            var currentView = landsUiTestView(currentContext.copy(areaId = "main"), land)
            every { gateway.managementView(player, memberContext) } answers { mainView }
            every { gateway.managementView(player, mainContext) } answers { mainView }
            every { gateway.managementView(player, currentContext) } answers { currentView }
            val settings = try {
                ConfigManager.clear()
                LandsUiConfig.load(dataPath).snapshot()
            } finally {
                ConfigManager.clear()
            }

            Tasks.install(mockk<TaskScheduler>(relaxed = true))
            var screen: PaperDialogScreen? = null
            mockkObject(ArcMenus)
            try {
                every { ArcMenus.openDialog(player, any(), any(), any(), any()) } answers { screen = secondArg() }
                val controller = LandsUiController(settings, gateway)
                val click = mockk<PaperDialogClickContext>(relaxed = true)
                every { click.player } returns player
                fun assertSameCard(expected: PaperDialogScreen) {
                    val actual = checkNotNull(screen)
                    actual.id shouldBe expected.id
                    actual.title shouldBe expected.title
                    actual.body shouldBe expected.body
                    actual.columns shouldBe expected.columns
                    actual.buttons.map { it.id to it.label } shouldBe expected.buttons.map { it.id to it.label }
                }
                try {
                    controller.openRoot(player)
                    val root = checkNotNull(screen)
                    root.buttons.none { it.id.value in setOf("claim_radius", "claim_display") } shouldBe true
                    root.buttons.single { it.id.value == "land_0" }.onClick.handle(click)
                    val card = checkNotNull(screen)
                    card.id shouldBe "lands.details"
                    card.buttons.map { it.id.value } shouldBe listOf(
                        "members", "add_member", "rules", "roles", "territory", "spawn", "settings", "borders", "admin",
                    )
                    val plain = net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText()
                    card.buttons.map { plain.serialize(it.label) } shouldBe listOf(
                        "Участники ›", "Добавить игрока ›", "Разрешения ›", "Роли ›", "Территория ›",
                        "Телепорт домой ›", "Управление ›", "Показать границы", "[Админ] Управление ›",
                    )
                    controller.openCurrent(player)
                    assertSameCard(card)
                    controller.openPanelAction(player, land.id, LandsUiPanelAction.OVERVIEW)
                    assertSameCard(card)
                    root.buttons.single { it.id.value == "inspect" }.onClick.handle(click)
                    assertSameCard(card)
                    verify(exactly = 0) { gateway.change(player, any(), any()) }

                    val adminContext = mainContext.copy(access = LandsUiAccess.ADMIN)
                    every { gateway.managementView(player, adminContext) } returns mainView.copy(context = adminContext)
                    verify(exactly = 0) { gateway.managementView(player, adminContext) }
                    card.buttons.single { it.id.value == "admin" }.onClick.handle(click)
                    checkNotNull(screen).id shouldBe "lands.admin"
                    checkNotNull(screen).buttons.none { it.id.value == "admin" } shouldBe true

                    every { gateway.change(player, mainContext, LandsUiChange.Spawn) } returns LandsUiChangeResult.DISPATCHED
                    val teleport = card.buttons.single { it.id.value == "spawn" }
                    teleport.closeDialogBeforeAction shouldBe true
                    teleport.onClick.handle(click)
                    verify(exactly = 1) { gateway.change(player, mainContext, LandsUiChange.Spawn) }

                    card.buttons.single { it.id.value == "territory" }.onClick.handle(click)
                    checkNotNull(screen).id shouldBe "lands.territory"
                    checkNotNull(screen).buttons.map { it.id.value } shouldBe
                        listOf("borders", "claim", "unclaim", "setspawn", "mainblock")

                    mainView = mainView.copy(permissions = setOf(LandsUiPermission.BORDERS))
                    every { gateway.change(player, mainContext, LandsUiChange.Spawn) } returns LandsUiChangeResult.DENIED
                    teleport.onClick.handle(click)
                    checkNotNull(screen).buttons.map { it.id.value } shouldBe
                        listOf("members", "rules", "roles", "territory", "borders", "admin")
                    verify(exactly = 2) { gateway.change(player, mainContext, LandsUiChange.Spawn) }

                    // A current-land entry keeps the actual subarea instead of silently using the default region.
                    val subareaContext = memberContext.copy(areaId = "farm")
                    currentView = currentView.copy(context = currentContext.copy(areaId = "farm"), areaName = "Ферма")
                    every { gateway.managementView(player, subareaContext) } returns mainView.copy(
                        context = subareaContext, areaName = "Ферма", viewerRole = "Участник",
                    )
                    controller.openCurrent(player)
                    val subarea = checkNotNull(screen)
                    val subareaText = subarea.body.joinToString("\n") { plain.serialize(it.text) }
                    subareaText.contains("Ферма") shouldBe true
                    subareaText.contains("Участник") shouldBe true
                    subarea.buttons.map { it.id.value } shouldBe
                        listOf("members", "rules", "roles", "territory", "borders", "admin")
                    controller.openPanelAction(player, land.id, LandsUiPanelAction.OVERVIEW)
                    assertSameCard(subarea)
                    subarea.buttons.single { it.id.value == "members" }.onClick.handle(click)
                    verify(exactly = 3) { gateway.managementView(player, subareaContext) }

                    every { gateway.managementView(player, memberContext) } returns null
                    root.buttons.single { it.id.value == "land_0" }.onClick.handle(click)
                    checkNotNull(screen).id shouldBe "lands.home"
                    verify(exactly = 0) { gateway.select(player, any()) }
                    verify(exactly = 0) { gateway.selectAndExecute(any(), any(), any()) }
                    player.inventory.itemInMainHand.amount shouldBe 6
                    player.inventory.itemInMainHand.itemMeta.persistentDataContainer.get(
                        NamespacedKey("lands", "radius"), PersistentDataType.INTEGER,
                    ) shouldBe 2
                } finally {
                    controller.close()
                }
            } finally {
                Tasks.reset()
                unmockkObject(ArcMenus)
                dataPath.toFile().deleteRecursively()
                ConfigManager.clear()
            }
        }
    }
})
