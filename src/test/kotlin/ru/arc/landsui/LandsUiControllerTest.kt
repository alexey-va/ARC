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
                context, land, permissions = setOf(LandsUiPermission.TRUST),
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
                        LandsUiPanelAction.OVERVIEW to "lands.inspect",
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

                    verify(exactly = 5) { gateway.managementView(player, context) }
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
                memberContext, land, permissions = setOf(LandsUiPermission.TRUST),
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
                        "members", "rules", "roles", "territory", "add_member", "settings",
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
                    checkNotNull(screen).id shouldBe "lands.inspect"
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

    "details radius flow updates the held claim block and preserves its amount" {
        MockBukkitTestRuntime.open().use { paper ->
            val dataPath = Files.createTempDirectory("lands-ui-radius")
            val player = paper.addPlayer("RadiusViewer")
            val playerId = player.uniqueId
            val item = ItemStack(Material.GOLD_BLOCK, 6).apply {
                editMeta { meta ->
                    meta.displayName(Component.text("Claim block"))
                    meta.persistentDataContainer.set(NamespacedKey("lands", "type"), PersistentDataType.STRING, "CLAIM_BLOCK")
                    meta.persistentDataContainer.set(NamespacedKey("lands", "radius"), PersistentDataType.INTEGER, 0)
                }
            }
            player.inventory.setItemInMainHand(item)
            val land = LandsUiLand("land-radius", "Дом", playerId, 1, 64, setOf(playerId), 12, 0.0, true)
            val gateway = mockk<LandsUiGateway>(relaxed = true)
            every { gateway.land(player, land.id) } returns land
            val memberContext = LandsUiContext(land.id, LandsUiAccess.MEMBER)
            every { gateway.managementView(player, memberContext) } returns landsUiTestView(memberContext, land)
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
                    controller.openDetails(player, land.id)
                    val details = checkNotNull(screen)
                    details.buttons.map { it.id.value } shouldContain "claim_radius"
                    details.buttons.map { it.id.value } shouldContain "claim_display"
                    val context = mockk<PaperDialogClickContext>(relaxed = true)
                    every { context.player } returns player
                    details.buttons.single { it.id.value == "claim_display" }.onClick.handle(context)
                    val display = checkNotNull(screen)
                    display.id shouldBe "lands.claim-display"
                    display.buttons.map { it.id.value } shouldBe
                        listOf(
                            "grid_down", "grid_up", "label_down", "label_up", "snap",
                            "radius_down", "radius_up", "color", "posts", "reset",
                        )

                    controller.openDetails(player, land.id)
                    details.buttons.single { it.id.value == "claim_radius" }.onClick.handle(context)

                    val radius = checkNotNull(screen)
                    radius.id shouldBe "lands.claim-radius"
                    radius.buttons.size shouldBe 5
                    radius.buttons.single { it.id.value == "radius_2" }.onClick.handle(context)

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
