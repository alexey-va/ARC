package ru.arc.landsui

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
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
                    detailsIds shouldContain "region_tool"
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
                    every { gateway.select(player, land.id) } returns true
                    controller.openCurrent(player)
                    checkNotNull(screen).id shouldBe "lands.details"
                    checkNotNull(screen).exitButton!!.onClick.handle(context)
                    checkNotNull(screen).id shouldBe "lands.home"
                    every { gateway.currentLandId(player) } returns null
                    controller.openCurrent(player)
                    checkNotNull(screen).id shouldBe "lands.home"
                    every { gateway.currentLandId(player) } returns "foreign-land"
                    every { gateway.land(player, "foreign-land") } returns null
                    controller.openCurrent(player)
                    checkNotNull(screen).id shouldBe "lands.home"

                    // Reopen once more through the public entry point to catch stale-screen cycles.
                    controller.openDetails(player, land.id)
                    checkNotNull(screen).id shouldBe "lands.details"
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
                    val context = mockk<PaperDialogClickContext>(relaxed = true)
                    every { context.player } returns player
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
