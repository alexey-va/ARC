package ru.arc.itemcatalog

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockkObject
import io.mockk.unmockkObject
import org.bukkit.Material
import org.bukkit.block.BlockFace
import org.bukkit.entity.Player
import org.bukkit.event.block.Action
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.inventory.EquipmentSlot
import org.bukkit.inventory.ItemStack
import ru.arc.core.Tasks
import ru.arc.core.TestTaskScheduler
import ru.arc.onetime.OneTimeUseAbandonResult
import ru.arc.onetime.OneTimeUseClaim
import ru.arc.onetime.OneTimeUseClaimRequest
import ru.arc.onetime.OneTimeUseClaimResult
import ru.arc.onetime.OneTimeUseCommitResult
import ru.arc.onetime.OneTimeUseLedger
import ru.arc.onetime.OneTimeUseReleaseResult
import ru.arc.paper.testing.MockBukkitTestRuntime
import ru.arc.treasure.core.Treasure
import ru.arc.treasure.core.TreasurePool
import ru.arc.treasure.core.Treasures
import java.nio.file.Files
import java.util.Comparator
import java.util.UUID
import java.util.concurrent.CompletableFuture

class PersonalTreasureMapMultiRollTest : StringSpec({
    "map claims roll three frozen items in one inventory grant and a replay does not roll again" {
        MockBukkitTestRuntime.open().use { paper ->
            withPreparedMapCatalog(unsupportedBranch = false) { rewards, mapKey ->
                val ledger = MapRollLedger()
                var nativeCalls = 0
                val controller = mapController(paper.createSimplePlugin("MapRollClaim"), ledger, rewards) { player, spec, operationId ->
                    nativeCalls++
                    rewards.redeem(player, spec, operationId)
                }
                val scheduler = TestTaskScheduler()
                Tasks.withScheduler(scheduler) {
                    controller.register()
                    try {
                        val player = paper.addPlayer("map-roll-claim")
                        val voucher = requireNotNull(controller.createStack(mapKey))
                        player.inventory.setItemInMainHand(voucher)

                        paper.callEvent(interact(player))
                        flush(scheduler)

                        rewardItemCount(player) shouldBe 3
                        ledger.calls shouldBe listOf("claim", "commit")
                        nativeCalls shouldBe 1

                        player.inventory.setItemInMainHand(voucher.clone())
                        paper.callEvent(interact(player))
                        flush(scheduler)

                        rewardItemCount(player) shouldBe 3
                        ledger.calls shouldBe listOf("claim", "commit", "claim")
                        ledger.requests.map { it.identity.useId } shouldBe listOf(
                            PhysicalRewardVoucher.identity(voucher)!!.id,
                            PhysicalRewardVoucher.identity(voucher)!!.id,
                        )
                        ledger.requests.map { it.scope?.value } shouldBe listOf("spawn", "spawn")
                        nativeCalls shouldBe 1
                        PhysicalRewardVoucher.identity(player.inventory.itemInMainHand) shouldBe null
                    } finally {
                        controller.close()
                    }
                }
            }
        }
    }

    "a full inventory rejects the complete map bundle without partial items or consuming its voucher" {
        MockBukkitTestRuntime.open().use { paper ->
            withPreparedMapCatalog(unsupportedBranch = false) { rewards, mapKey ->
                val ledger = MapRollLedger()
                var nativeCalls = 0
                val controller = mapController(paper.createSimplePlugin("MapRollFull"), ledger, rewards) { player, spec, operationId ->
                    nativeCalls++
                    rewards.redeem(player, spec, operationId)
                }
                val scheduler = TestTaskScheduler()
                Tasks.withScheduler(scheduler) {
                    controller.register()
                    try {
                        val player = paper.addPlayer("map-roll-full")
                        player.inventory.storageContents = Array<ItemStack?>(36) { ItemStack(Material.COBBLESTONE, 64) }
                        val voucher = requireNotNull(controller.createStack(mapKey))
                        player.inventory.setItemInMainHand(voucher)
                        val before = player.inventory.storageContents.map { it?.clone() }

                        paper.callEvent(interact(player))
                        flush(scheduler)

                        player.inventory.storageContents.toList() shouldBe before
                        PhysicalRewardVoucher.identity(player.inventory.itemInMainHand) shouldBe PhysicalRewardVoucher.identity(voucher)
                        ledger.calls shouldBe listOf("claim", "release")
                        nativeCalls shouldBe 0
                    } finally {
                        controller.close()
                    }
                }
            }
        }
    }

    "worst-case space for all draws is checked before rolling a mixed stackable pool" {
        MockBukkitTestRuntime.open().use { paper ->
            withPreparedMapCatalog(unsupportedBranch = false, unstackableAmount = 3) { rewards, mapKey ->
                val ledger = MapRollLedger()
                var nativeCalls = 0
                val controller = mapController(paper.createSimplePlugin("MapRollWorstCase"), ledger, rewards) { player, spec, operationId ->
                    nativeCalls++
                    rewards.redeem(player, spec, operationId)
                }
                val scheduler = TestTaskScheduler()
                Tasks.withScheduler(scheduler) {
                    controller.register()
                    try {
                        val player = paper.addPlayer("map-roll-worst-case")
                        player.inventory.storageContents = Array<ItemStack?>(36) { index ->
                            if (index < 28) ItemStack(Material.COBBLESTONE, 64) else null
                        }
                        val voucher = requireNotNull(controller.createStack(mapKey))
                        player.inventory.setItemInMainHand(voucher)
                        player.inventory.storageContents.count { it == null || it.type.isAir } shouldBe 8
                        val before = player.inventory.storageContents.map { it?.clone() }

                        paper.callEvent(interact(player))
                        flush(scheduler)

                        player.inventory.storageContents.toList() shouldBe before
                        PhysicalRewardVoucher.identity(player.inventory.itemInMainHand) shouldBe PhysicalRewardVoucher.identity(voucher)
                        ledger.calls shouldBe listOf("claim", "release")
                        nativeCalls shouldBe 0
                    } finally {
                        controller.close()
                    }
                }
            }
        }
    }

    "a multi-roll tree with an unsupported selectable child is rejected before grant" {
        MockBukkitTestRuntime.open().use { paper ->
            withPreparedMapCatalog(unsupportedBranch = true) { rewards, mapKey ->
                rewards.canMaterialize(mapKey) shouldBe false
                val ledger = MapRollLedger()
                var nativeCalls = 0
                val controller = mapController(paper.createSimplePlugin("MapRollInvalid"), ledger, rewards) { player, spec, operationId ->
                    nativeCalls++
                    rewards.redeem(player, spec, operationId)
                }
                val scheduler = TestTaskScheduler()
                Tasks.withScheduler(scheduler) {
                    controller.register()
                    try {
                        val player = paper.addPlayer("map-roll-invalid")
                        val voucher = requireNotNull(controller.createStack(mapKey))
                        player.inventory.setItemInMainHand(voucher)

                        paper.callEvent(interact(player))
                        flush(scheduler)

                        rewardItemCount(player) shouldBe 0
                        PhysicalRewardVoucher.identity(player.inventory.itemInMainHand) shouldBe PhysicalRewardVoucher.identity(voucher)
                        ledger.calls shouldBe listOf("claim", "release")
                        nativeCalls shouldBe 0
                    } finally {
                        controller.close()
                    }
                }
            }
        }
    }
})

private fun mapController(
    plugin: org.bukkit.plugin.Plugin,
    ledger: MapRollLedger,
    rewards: CatalogPhysicalRewards,
    redeem: (Player, PhysicalRewardSpec, UUID) -> CompletableFuture<PhysicalRewardOutcome>,
) = PhysicalRewardController(
    plugin,
    ledger,
    rewards::resolve,
    rewards::canRedeem,
    redeem,
    "spawn",
)

private fun withPreparedMapCatalog(
    unsupportedBranch: Boolean,
    unstackableAmount: Int? = null,
    block: (CatalogPhysicalRewards, String) -> Unit,
) {
    val poolId = "map-roll-root-${UUID.randomUUID()}"
    val nestedPoolId = "map-roll-items-${UUID.randomUUID()}"
    val nested = Treasure.SubPool(nestedPoolId, id = "nested_items")
    val rootPool = TreasurePool(poolId, listOf(nested))
    val items = mutableListOf<Treasure>(
        Treasure.Item(ItemStack(Material.DIAMOND), min = 1, max = 1, id = "diamond"),
        Treasure.Item(ItemStack(Material.EMERALD), min = 1, max = 1, id = "emerald"),
        Treasure.Item(ItemStack(Material.GOLD_INGOT), min = 1, max = 1, id = "gold"),
    )
    unstackableAmount?.let { amount ->
        items += Treasure.Item(ItemStack(Material.ELYTRA), min = amount, max = amount, id = "nonstackable")
    }
    if (unsupportedBranch) items += Treasure.Enchant(min = 1, max = 1, id = "unsupported_book")
    val nestedPool = TreasurePool(nestedPoolId, items)
    val pools = mapOf(rootPool.id to rootPool, nestedPool.id to nestedPool)
    mockkObject(Treasures)
    val root = Files.createTempDirectory("arc-personal-map-rolls")
    try {
        every { Treasures.getPool(any()) } answers { pools[firstArg<String>()] }
        val prize = RewardCatalogEntry(
            id = "map_prize",
            name = "Награда тайника",
            description = emptyList(),
            rarity = null,
            requires = emptyList(),
            source = RewardCatalogSource.Treasure(poolId, nested.id),
            icon = CatalogIconStyle(Material.CHEST.name),
        )
        val map = RewardCatalogEntry(
            id = "weekly_map_${UUID.randomUUID()}",
            name = "Карта тайника",
            description = emptyList(),
            rarity = null,
            requires = emptyList(),
            source = RewardCatalogSource.PersonalMap(
                "rewards",
                prize.id,
                PersonalTreasureMapSearchPolicy("survival", "world", 32),
                prizeRolls = 3,
            ),
            icon = CatalogIconStyle(Material.FILLED_MAP.name),
        )
        val settings = RewardCatalogSettings(
            enabled = true,
            title = "Награды",
            categories = listOf(
                RewardCatalogCategory(
                    "rewards", "Награды", emptyList(), CatalogIconStyle(Material.CHEST.name), listOf(prize, map),
                ),
            ),
            messages = RewardCatalogMessages.DEFAULT,
        )
        val rewards = CatalogPhysicalRewards(settings, frozen = FrozenPhysicalRewards(root))
        val snapshots = requireNotNull(rewards.captureInteractiveArchives())
        rewards.persistInteractiveArchives(snapshots) shouldBe true
        val materialization = requireNotNull(rewards.materialization(map))
        block(rewards, materialization.sourceKey)
    } finally {
        unmockkObject(Treasures)
        Files.walk(root).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) }
    }
}

private fun interact(player: Player) =
    PlayerInteractEvent(player, Action.RIGHT_CLICK_AIR, null, null, BlockFace.SELF, EquipmentSlot.HAND)

private fun flush(scheduler: TestTaskScheduler) {
    repeat(12) { scheduler.executeImmediate() }
}

private fun rewardItemCount(player: Player): Int = player.inventory.storageContents.filterNotNull()
    .filter { it.type in setOf(Material.DIAMOND, Material.EMERALD, Material.GOLD_INGOT) }
    .sumOf { it.amount }

private class MapRollLedger : OneTimeUseLedger {
    val calls = mutableListOf<String>()
    val requests = mutableListOf<OneTimeUseClaimRequest>()
    private val consumed = mutableSetOf<UUID>()

    override fun claim(request: OneTimeUseClaimRequest): CompletableFuture<OneTimeUseClaimResult> {
        calls += "claim"
        requests += request
        val result = if (request.identity.useId in consumed) {
            OneTimeUseClaimResult.AlreadyConsumed
        } else {
            OneTimeUseClaimResult.Acquired(OneTimeUseClaim.acquired(request, true))
        }
        return CompletableFuture.completedFuture(result)
    }

    override fun commit(claim: OneTimeUseClaim): CompletableFuture<OneTimeUseCommitResult> {
        calls += "commit"
        consumed += claim.identity.useId
        return CompletableFuture.completedFuture(OneTimeUseCommitResult.COMMITTED)
    }

    override fun release(claim: OneTimeUseClaim): CompletableFuture<OneTimeUseReleaseResult> {
        calls += "release"
        return CompletableFuture.completedFuture(OneTimeUseReleaseResult.RELEASED)
    }

    override fun abandon(claim: OneTimeUseClaim): CompletableFuture<OneTimeUseAbandonResult> =
        CompletableFuture.completedFuture(OneTimeUseAbandonResult.RETAINED_FOR_RECOVERY)

    override fun close() = Unit
}
