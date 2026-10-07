package ru.arc.landsui

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.runs
import io.mockk.unmockkObject
import io.mockk.verify
import io.papermc.paper.event.player.PlayerArmSwingEvent
import me.angeschossen.lands.api.LandsIntegration
import me.angeschossen.lands.api.applicationframework.util.ULID
import me.angeschossen.lands.api.land.Land
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.block.BlockFace
import org.bukkit.entity.Player
import org.bukkit.event.Event
import org.bukkit.event.block.Action
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.inventory.EquipmentSlot
import org.bukkit.inventory.ItemStack
import org.bukkit.persistence.PersistentDataType
import ru.arc.core.TaskScheduler
import ru.arc.core.Tasks
import ru.arc.onboarding.ClaimBlockIdentity
import ru.arc.onboarding.OnboardingModule
import ru.arc.paper.testing.MockBukkitTestRuntime
import java.util.UUID

class ClaimBlockMenuInputTest : StringSpec({
    "main-hand menu click consumes off-hand duplicate and swing in the same tick" {
        withFixture { fixture ->
            fixture.player.inventory.itemInMainHand.type shouldBe Material.AIR
            ClaimBlockIdentity.heldRadius(fixture.player) shouldBe 0
            OnboardingModule.claimGuide shouldBe null

            mockkObject(LandsUiModule)
            try {
                every { LandsUiModule.openPanelAction(fixture.player, fixture.landId, LandsUiPanelAction.ADD_MEMBER) } just runs

                val mainHand = interact(fixture.player, EquipmentSlot.HAND, null)
                fixture.tool.interact(mainHand)
                mainHand.isCancelled shouldBe true

                val offHand = interact(fixture.player, EquipmentSlot.OFF_HAND, fixture.claimBlock)
                fixture.tool.interact(offHand)
                offHand.isCancelled shouldBe true

                val swing = mockk<PlayerArmSwingEvent>(relaxed = true)
                every { swing.player } returns fixture.player
                every { swing.hand } returns EquipmentSlot.HAND
                fixture.tool.swingMenu(swing)

                verify { swing.isCancelled = true }
                verify(exactly = 1) {
                    LandsUiModule.openPanelAction(fixture.player, fixture.landId, LandsUiPanelAction.ADD_MEMBER)
                }
                verify(exactly = 3) { fixture.lands.getWorld(fixture.player.world) }
                verify(exactly = 3) {
                    fixture.lands.getLandByUnloadedChunk(fixture.player.world, any(), any())
                }
                verify(exactly = 3) { fixture.land.exists() }
                verify(exactly = 3) { fixture.land.isTrusted(fixture.player.uniqueId) }
                verify(exactly = 0) { fixture.lands.getLandPlayer(fixture.player.uniqueId) }
                verify(exactly = 0) { fixture.scheduler.runSync(any()) }
            } finally {
                unmockkObject(LandsUiModule)
            }
        }
    }

    "off-hand event only cancels and main-hand event routes the action once" {
        withFixture { fixture ->
            mockkObject(LandsUiModule)
            try {
                every { LandsUiModule.openPanelAction(fixture.player, fixture.landId, LandsUiPanelAction.ADD_MEMBER) } just runs

                val offHand = interact(fixture.player, EquipmentSlot.OFF_HAND, fixture.claimBlock)
                fixture.tool.interact(offHand)
                offHand.isCancelled shouldBe true
                verify(exactly = 0) { LandsUiModule.openPanelAction(any(), any(), any()) }

                val mainHand = interact(fixture.player, EquipmentSlot.HAND, null)
                fixture.tool.interact(mainHand)
                mainHand.isCancelled shouldBe true
                verify(exactly = 1) {
                    LandsUiModule.openPanelAction(fixture.player, fixture.landId, LandsUiPanelAction.ADD_MEMBER)
                }
                verify(exactly = 0) { fixture.lands.getLandPlayer(fixture.player.uniqueId) }
                verify(exactly = 0) { fixture.scheduler.runSync(any()) }
            } finally {
                unmockkObject(LandsUiModule)
            }
        }
    }

    "revoked membership or a different current land rejects a stale panel target" {
        withFixture { fixture ->
            val otherOwner = UUID.randomUUID()
            fixture.landState.ownerId = otherOwner

            mockkObject(LandsUiModule)
            try {
                every { LandsUiModule.openPanelAction(any(), any(), any()) } just runs

                val revoked = interact(fixture.player, EquipmentSlot.HAND, null)
                fixture.tool.interact(revoked)
                revoked.isCancelled shouldBe false

                val movedLand = mockk<Land>(relaxed = true)
                val movedUlid = mockk<ULID>()
                every { movedLand.exists() } returns true
                every { movedLand.ownerUID } returns fixture.player.uniqueId
                every { movedLand.isTrusted(fixture.player.uniqueId) } returns true
                every { movedLand.ulid } returns movedUlid
                every { movedUlid.toString() } returns "01KMOVEDLAND0000000000000000"
                fixture.landState.ownerId = fixture.player.uniqueId
                fixture.landState.land = movedLand

                val moved = interact(fixture.player, EquipmentSlot.HAND, null)
                fixture.tool.interact(moved)
                moved.isCancelled shouldBe false

                verify(exactly = 0) { LandsUiModule.openPanelAction(any(), any(), any()) }
                verify(exactly = 0) { fixture.lands.getLandPlayer(fixture.player.uniqueId) }
                verify(exactly = 0) { fixture.scheduler.runSync(any()) }
            } finally {
                unmockkObject(LandsUiModule)
            }
        }
    }

    "trusted member is eligible and revoking native trust blocks the next click" {
        withFixture { fixture ->
            fixture.landState.ownerId = UUID.randomUUID()
            fixture.landState.trustedIds += fixture.player.uniqueId

            mockkObject(LandsUiModule)
            try {
                every { LandsUiModule.openPanelAction(any(), any(), any()) } just runs

                val memberClick = interact(fixture.player, EquipmentSlot.HAND, null)
                fixture.tool.interact(memberClick)
                memberClick.isCancelled shouldBe true
                verify(exactly = 1) {
                    LandsUiModule.openPanelAction(fixture.player, fixture.landId, LandsUiPanelAction.ADD_MEMBER)
                }

                fixture.landState.trustedIds.remove(fixture.player.uniqueId)
                val revokedClick = interact(fixture.player, EquipmentSlot.HAND, null)
                fixture.tool.interact(revokedClick)
                revokedClick.isCancelled shouldBe false

                verify(exactly = 1) { LandsUiModule.openPanelAction(any(), any(), any()) }
                verify(exactly = 2) { fixture.land.isTrusted(fixture.player.uniqueId) }
                verify(exactly = 0) { fixture.lands.getLandPlayer(fixture.player.uniqueId) }
                verify(exactly = 0) { fixture.scheduler.runSync(any()) }
            } finally {
                unmockkObject(LandsUiModule)
            }
        }
    }

    "a menu miss leaves the native off-hand claim-block placement path active" {
        withFixture(action = null) { fixture ->
            OnboardingModule.claimGuide shouldBe null
            val block = fixture.player.world.getBlockAt(
                fixture.player.location.blockX,
                fixture.player.location.blockY,
                fixture.player.location.blockZ,
            )
            val event = PlayerInteractEvent(
                fixture.player,
                Action.RIGHT_CLICK_BLOCK,
                fixture.claimBlock,
                block,
                BlockFace.UP,
                EquipmentSlot.OFF_HAND,
            ).apply { setUseItemInHand(Event.Result.ALLOW) }

            fixture.tool.interact(event)

            event.isCancelled shouldBe true
            verify(exactly = 1) { fixture.scheduler.runSync(any()) }
            verify(exactly = 0) { fixture.lands.getLandPlayer(fixture.player.uniqueId) }
        }
    }
})

private data class LandLookupState(
    var land: Land,
    var ownerId: UUID,
    val trustedIds: MutableSet<UUID> = mutableSetOf(),
)

private data class ClaimBlockMenuFixture(
    val player: Player,
    val lands: LandsIntegration,
    val land: Land,
    val landId: String,
    val landState: LandLookupState,
    val claimBlock: ItemStack,
    val scheduler: TaskScheduler,
    val tool: ClaimBlockTool,
)

private fun withFixture(
    action: LandsUiPanelAction? = LandsUiPanelAction.ADD_MEMBER,
    run: (ClaimBlockMenuFixture) -> Unit,
) {
    MockBukkitTestRuntime.open().use { paper ->
        val scheduler = mockk<TaskScheduler>(relaxed = true)
        Tasks.install(scheduler)
        val player = paper.addPlayer("claim-menu")
        val lands = mockk<LandsIntegration>(relaxed = true)
        val landId = "01KTESTLAND0000000000000000"
        val ulid = mockk<ULID>()
        val land = mockk<Land>(relaxed = true)
        val landState = LandLookupState(land, player.uniqueId)
        val claimBlock = nativeClaimBlock()
        val menu = mockk<ClaimLandMenu>(relaxed = true)

        every { ulid.toString() } returns landId
        every { land.exists() } returns true
        every { land.ownerUID } answers { landState.ownerId }
        every { land.isTrusted(any<UUID>()) } answers {
            val playerId = firstArg<UUID>()
            playerId == landState.ownerId || playerId in landState.trustedIds
        }
        every { land.ulid } returns ulid
        every { lands.getWorld(player.world) } returns mockk(relaxed = true)
        every { lands.getLandByUnloadedChunk(player.world, any(), any()) } answers { landState.land }
        every { lands.configuration.mainConfig.getBoolean("land.claimblock.only-owner") } returns false
        every { menu.target(player) } returns action
        every { menu.landId(player.uniqueId) } returns landId
        player.inventory.setItemInOffHand(claimBlock)

        val tool = ClaimBlockTool(
            LandsUiSettings(true, 12, emptyMap()),
            lands,
        )
        ClaimBlockTool::class.java.getDeclaredField("menu").apply { isAccessible = true }.set(tool, menu)
        val fixture = ClaimBlockMenuFixture(
            player = player,
            lands = lands,
            land = land,
            landId = landId,
            landState = landState,
            claimBlock = claimBlock,
            scheduler = scheduler,
            tool = tool,
        )
        try {
            run(fixture)
        } finally {
            tool.close()
            Tasks.reset()
        }
    }
}

private fun nativeClaimBlock() = ItemStack(Material.GOLD_BLOCK).apply {
    editMeta { meta ->
        meta.persistentDataContainer.set(NamespacedKey("lands", "type"), PersistentDataType.STRING, "CLAIM_BLOCK")
        meta.persistentDataContainer.set(NamespacedKey("lands", "radius"), PersistentDataType.INTEGER, 0)
    }
}

private fun interact(player: Player, hand: EquipmentSlot, item: ItemStack?) = PlayerInteractEvent(
    player,
    Action.RIGHT_CLICK_BLOCK,
    item,
    player.world.getBlockAt(player.location.blockX, player.location.blockY, player.location.blockZ),
    BlockFace.UP,
    hand,
)
