package ru.arc.itemcatalog

import dev.lone.itemsadder.api.CustomStack
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import net.kyori.adventure.text.Component
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.block.BlockFace
import org.bukkit.event.block.Action
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.inventory.EquipmentSlot
import org.bukkit.inventory.ItemStack
import org.bukkit.persistence.PersistentDataType
import ru.arc.core.Tasks
import ru.arc.core.TestTaskScheduler
import ru.arc.mounts.MountWallet
import ru.arc.onetime.OneTimeUseAbandonResult
import ru.arc.onetime.OneTimeUseClaim
import ru.arc.onetime.OneTimeUseClaimRequest
import ru.arc.onetime.OneTimeUseClaimResult
import ru.arc.onetime.OneTimeUseCommitResult
import ru.arc.onetime.OneTimeUseLedger
import ru.arc.onetime.OneTimeUseReleaseResult
import ru.arc.paper.testing.MockBukkitTestRuntime
import java.nio.file.Files
import java.util.concurrent.CompletableFuture

private const val PACKAGE_ID = "medieval_furniture"
private const val PACKAGE_ENTRY_ID = "medieval_furniture_entry"
private const val ITEM_ID = "decor:chair"
private val NATIVE_MARKER = NamespacedKey("test", "native_furniture_id")

class FurniturePackageRedemptionTest : StringSpec({
    "new packages draw eight frozen furniture items directly and a replay has no second grant" {
        MockBukkitTestRuntime.open().use { paper ->
            paper.createSimplePlugin("ItemsAdder")
            withMockItems(listOf(ITEM_ID)) { nativeItems ->
                val root = Files.createTempDirectory("arc-furniture-rolls")
                try {
                    val settings = packageSettings(
                        itemIds = listOf(ITEM_ID),
                        weights = mapOf(ITEM_ID to 7),
                        minRolls = 8,
                        maxRolls = 12,
                    )
                    val archive = FrozenPhysicalRewards(root)
                    val rewards = CatalogPhysicalRewards(settings, mockk<MountWallet>(relaxed = true), archive)
                    val entry = settings.categories.single().entries.single()
                    val materialization = checkNotNull(rewards.materialization(entry))
                    val recipe = checkNotNull(archive.find(materialization.sourceKey)).recipe
                    recipe.type shouldBe "furniture-rolls"
                    recipe.furnitureMinRolls shouldBe 8
                    recipe.furnitureMaxRolls shouldBe 12
                    recipe.treasure?.children?.single()?.weight shouldBe 7

                    val ledger = FurnitureVoucherLedger()
                    var nativeGrants = 0
                    val controller = PhysicalRewardController(
                        paper.createSimplePlugin("FurnitureRollClaim"),
                        ledger,
                        rewards::resolve,
                        rewards::canRedeem,
                        { player, spec, operationId ->
                            nativeGrants++
                            rewards.redeem(player, spec, operationId)
                        },
                        "spawn",
                    )
                    val scheduler = TestTaskScheduler()
                    Tasks.withScheduler(scheduler) {
                        controller.register()
                        try {
                            val player = paper.addPlayer("furniture-roll-owner")
                            val voucher = checkNotNull(controller.createStack(materialization.sourceKey))
                            player.inventory.setItemInMainHand(voucher)
                            paper.callEvent(interact(player))
                            flush(scheduler)

                            val delivered = player.inventory.storageContents.filterNotNull()
                                .filter { it.itemMeta?.persistentDataContainer?.get(NATIVE_MARKER, PersistentDataType.STRING) == ITEM_ID }
                            val deliveredCount = delivered.sumOf { it.amount }
                            (deliveredCount in 8..12) shouldBe true
                            player.inventory.storageContents.count { it?.type == Material.PURPLE_SHULKER_BOX } shouldBe 0
                            delivered.single().itemMeta?.displayName() shouldBe nativeItems.getValue(ITEM_ID).itemMeta?.displayName()
                            ledger.calls shouldBe listOf("claim", "commit")
                            nativeGrants shouldBe 1

                            player.inventory.setItemInMainHand(voucher.clone())
                            paper.callEvent(interact(player))
                            flush(scheduler)

                            player.inventory.storageContents.filterNotNull()
                                .filter { it.itemMeta?.persistentDataContainer?.get(NATIVE_MARKER, PersistentDataType.STRING) == ITEM_ID }
                                .sumOf { it.amount } shouldBe deliveredCount
                            ledger.calls shouldBe listOf("claim", "commit", "claim")
                            nativeGrants shouldBe 1
                        } finally {
                            controller.close()
                        }
                    }
                } finally {
                    root.toFile().deleteRecursively()
                }
            }
        }
    }

    "package preflight requires twelve free slots and releases the voucher before drawing" {
        MockBukkitTestRuntime.open().use { paper ->
            paper.createSimplePlugin("ItemsAdder")
            withMockItems(listOf(ITEM_ID)) {
                val root = Files.createTempDirectory("arc-furniture-rolls-full")
                try {
                    val settings = packageSettings(listOf(ITEM_ID), minRolls = 8, maxRolls = 12)
                    val archive = FrozenPhysicalRewards(root)
                    val rewards = CatalogPhysicalRewards(settings, mockk<MountWallet>(relaxed = true), archive)
                    val materialization = checkNotNull(rewards.materialization(settings.categories.single().entries.single()))
                    val spec = checkNotNull(rewards.resolve(materialization.sourceKey))
                    val ledger = FurnitureVoucherLedger()
                    var nativeGrants = 0
                    val controller = PhysicalRewardController(
                        paper.createSimplePlugin("FurnitureRollFull"),
                        ledger,
                        rewards::resolve,
                        rewards::canRedeem,
                        { player, reward, operationId ->
                            nativeGrants++
                            rewards.redeem(player, reward, operationId)
                        },
                        "spawn",
                    )
                    val scheduler = TestTaskScheduler()
                    Tasks.withScheduler(scheduler) {
                        controller.register()
                        try {
                            val player = paper.addPlayer("furniture-roll-full")
                            player.inventory.storageContents = Array(36) { ItemStack(Material.STONE, 64) }
                            val voucher = checkNotNull(controller.createStack(materialization.sourceKey))
                            val voucherId = PhysicalRewardVoucher.identity(voucher)!!.id
                            player.inventory.setItemInMainHand(voucher)
                            val before = player.inventory.storageContents.map { it?.clone() }

                            rewards.canRedeem(player, spec) shouldBe
                                "<red>Освободите 12 ячеек инвентаря и используйте набор повторно."
                            paper.callEvent(interact(player))
                            flush(scheduler)

                            player.inventory.storageContents.map { it?.clone() } shouldBe before
                            PhysicalRewardVoucher.identity(player.inventory.itemInMainHand)?.id shouldBe voucherId
                            ledger.calls shouldBe listOf("claim", "release")
                            nativeGrants shouldBe 0
                        } finally {
                            controller.close()
                        }
                    }
                } finally {
                    root.toFile().deleteRecursively()
                }
            }
        }
    }
})

private fun packageSettings(
    itemIds: List<String>,
    weights: Map<String, Int> = emptyMap(),
    minRolls: Int = 8,
    maxRolls: Int = 12,
): RewardCatalogSettings =
    RewardCatalogSettings(
        enabled = true,
        title = "Каталог",
        categories = listOf(
            RewardCatalogCategory(
                id = "furniture",
                name = "Мебель",
                description = emptyList(),
                icon = CatalogIconStyle(Material.CHEST.name),
                entries = listOf(
                    RewardCatalogEntry(
                        id = PACKAGE_ENTRY_ID,
                        name = "Средневеклый набор",
                        description = listOf("Предметы мебели"),
                        rarity = null,
                        requires = emptyList(),
                        source = RewardCatalogSource.FurniturePackage(PACKAGE_ID),
                        icon = CatalogIconStyle(Material.CHEST.name),
                    ),
                ),
            ),
        ),
        messages = RewardCatalogMessages.DEFAULT,
        packages = mapOf(PACKAGE_ID to RewardFurniturePackage("Средневеклый набор", itemIds, weights, minRolls, maxRolls)),
    )

private fun interact(player: org.bukkit.entity.Player): PlayerInteractEvent =
    PlayerInteractEvent(player, Action.RIGHT_CLICK_AIR, null, null, BlockFace.SELF, EquipmentSlot.HAND)

private fun flush(scheduler: TestTaskScheduler) {
    repeat(12) { scheduler.executeImmediate() }
}

private class FurnitureVoucherLedger : OneTimeUseLedger {
    val calls = mutableListOf<String>()
    private var claims = 0
    override val available: Boolean = true

    override fun claim(request: OneTimeUseClaimRequest): CompletableFuture<OneTimeUseClaimResult> {
        calls += "claim"
        claims++
        return CompletableFuture.completedFuture(
            if (claims == 1) OneTimeUseClaimResult.Acquired(OneTimeUseClaim.acquired(request, true))
            else OneTimeUseClaimResult.AlreadyConsumed,
        )
    }

    override fun commit(claim: OneTimeUseClaim): CompletableFuture<OneTimeUseCommitResult> {
        calls += "commit"
        return CompletableFuture.completedFuture(OneTimeUseCommitResult.COMMITTED)
    }

    override fun release(claim: OneTimeUseClaim): CompletableFuture<OneTimeUseReleaseResult> {
        calls += "release"
        return CompletableFuture.completedFuture(OneTimeUseReleaseResult.RELEASED)
    }

    override fun abandon(claim: OneTimeUseClaim): CompletableFuture<OneTimeUseAbandonResult> {
        calls += "abandon"
        return CompletableFuture.completedFuture(OneTimeUseAbandonResult.RETAINED_FOR_RECOVERY)
    }

    override fun close() = Unit
}

private fun <T> withMockItems(
    itemIds: List<String>,
    block: (Map<String, ItemStack>) -> T,
): T {
    mockkStatic(CustomStack::class)
    val nativeItems = itemIds.associateWith { id ->
        ItemStack(Material.DIAMOND).apply {
            editMeta { meta ->
                meta.displayName(Component.text("Native $id"))
                meta.persistentDataContainer.set(NATIVE_MARKER, PersistentDataType.STRING, id)
            }
        }
    }
    val handles = nativeItems.mapValues { (_, stack) ->
        mockk<CustomStack>(relaxed = true).also { handle ->
            every { handle.itemStack } returns stack
        }
    }
    every { CustomStack.getInstance(any()) } answers { handles[firstArg()] }
    return try {
        block(nativeItems)
    } finally {
        unmockkStatic(CustomStack::class)
    }
}
