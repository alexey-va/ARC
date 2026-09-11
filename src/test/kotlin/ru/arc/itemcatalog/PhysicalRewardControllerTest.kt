package ru.arc.itemcatalog

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import net.kyori.adventure.text.Component
import org.bukkit.Material
import org.bukkit.block.BlockFace
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
import ru.arc.onetime.OneTimeUseFingerprint
import ru.arc.onetime.OneTimeUseLedger
import ru.arc.onetime.OneTimeUseReleaseResult
import ru.arc.paper.testing.MockBukkitTestRuntime
import java.util.concurrent.CompletableFuture

class PhysicalRewardControllerTest : StringSpec({
    "cancelled air click claims before native redemption and consumes after commit" {
        MockBukkitTestRuntime.open().use { paper ->
            val timeline = mutableListOf<String>()
            val ledger = RecordingVoucherLedger(timeline = timeline)
            val nativeCalls = mutableListOf<String>()
            val plugin = paper.createSimplePlugin("PhysicalRewardTest")
            val controller = controller(plugin, ledger) { _, _, _ ->
                nativeCalls += "redeem"
                timeline += "redeem"
                CompletableFuture.completedFuture(PhysicalRewardOutcome.Applied)
            }
            val scheduler = TestTaskScheduler()
            Tasks.withScheduler(scheduler) {
                controller.register()
                try {
                    val player = paper.addPlayer("voucher-holder")
                    player.inventory.setItemInMainHand(controller.createStack("reward.test")!!)
                    val event = interact(player, Action.RIGHT_CLICK_AIR).also { it.isCancelled = true }

                    paper.callEvent(event)
                    flush(scheduler)

                    event.isCancelled shouldBe true
                    ledger.calls shouldContainExactly listOf("claim", "commit")
                    timeline shouldBe listOf("claim", "redeem", "commit")
                    nativeCalls shouldBe listOf("redeem")
                    player.inventory.itemInMainHand.type shouldBe Material.AIR
                } finally {
                    controller.close()
                }
            }
        }
    }

    "full preflight releases the claim and never invokes native redemption" {
        MockBukkitTestRuntime.open().use { paper ->
            val ledger = RecordingVoucherLedger()
            var nativeCalls = 0
            val controller = controller(paper.createSimplePlugin("PhysicalRewardFull"), ledger, canRedeem = { _, _ -> "Освободите слот." }) { _, _, _ ->
                nativeCalls++
                CompletableFuture.completedFuture(PhysicalRewardOutcome.Applied)
            }
            val scheduler = TestTaskScheduler()
            Tasks.withScheduler(scheduler) {
                controller.register()
                try {
                    val player = paper.addPlayer("full-holder")
                    val voucher = controller.createStack("reward.test")!!
                    val voucherId = PhysicalRewardVoucher.identity(voucher)!!.id
                    player.inventory.setItemInMainHand(voucher)

                    paper.callEvent(interact(player, Action.RIGHT_CLICK_BLOCK))
                    flush(scheduler)

                    ledger.calls shouldContainExactly listOf("claim", "release")
                    nativeCalls shouldBe 0
                    PhysicalRewardVoucher.identity(player.inventory.itemInMainHand)!!.id shouldBe voucherId
                } finally {
                    controller.close()
                }
            }
        }
    }

    "uncertain native result abandons recovery ownership and keeps the voucher" {
        MockBukkitTestRuntime.open().use { paper ->
            val ledger = RecordingVoucherLedger()
            val controller = controller(paper.createSimplePlugin("PhysicalRewardUnknown"), ledger) { _, _, _ ->
                CompletableFuture.completedFuture(PhysicalRewardOutcome.Uncertain("provider timeout"))
            }
            val scheduler = TestTaskScheduler()
            Tasks.withScheduler(scheduler) {
                controller.register()
                try {
                    val player = paper.addPlayer("unknown-holder")
                    val voucher = controller.createStack("reward.test")!!
                    player.inventory.setItemInMainHand(voucher)

                    paper.callEvent(interact(player, Action.RIGHT_CLICK_AIR))
                    flush(scheduler)

                    ledger.calls shouldContainExactly listOf("claim", "abandon")
                    PhysicalRewardVoucher.identity(player.inventory.itemInMainHand) shouldBe PhysicalRewardVoucher.identity(voucher)
                } finally {
                    controller.close()
                }
            }
        }
    }

    "a replay reported as already consumed never invokes native redemption and removes the copy" {
        MockBukkitTestRuntime.open().use { paper ->
            val ledger = RecordingVoucherLedger(claimResult = { OneTimeUseClaimResult.AlreadyConsumed })
            var nativeCalls = 0
            val controller = controller(paper.createSimplePlugin("PhysicalRewardReplay"), ledger) { _, _, _ ->
                nativeCalls++
                CompletableFuture.completedFuture(PhysicalRewardOutcome.Applied)
            }
            val scheduler = TestTaskScheduler()
            Tasks.withScheduler(scheduler) {
                controller.register()
                try {
                    val player = paper.addPlayer("replay-holder")
                    player.inventory.setItemInMainHand(controller.createStack("reward.test")!!)

                    paper.callEvent(interact(player, Action.RIGHT_CLICK_AIR))
                    flush(scheduler)

                    ledger.calls shouldBe listOf("claim")
                    nativeCalls shouldBe 0
                    player.inventory.itemInMainHand.type shouldBe Material.AIR
                } finally {
                    controller.close()
                }
            }
        }
    }

    "closing while claim is pending releases a late acquired claim" {
        MockBukkitTestRuntime.open().use { paper ->
            val ledger = RecordingVoucherLedger()
            val controller = controller(paper.createSimplePlugin("PhysicalRewardClose"), ledger) { _, _, _ ->
                CompletableFuture.completedFuture(PhysicalRewardOutcome.Applied)
            }
            val scheduler = TestTaskScheduler()
            Tasks.withScheduler(scheduler) {
                controller.register()
                val player = paper.addPlayer("close-holder")
                player.inventory.setItemInMainHand(controller.createStack("reward.test")!!)
                ledger.usePendingFuture = true
                paper.callEvent(interact(player, Action.RIGHT_CLICK_AIR))
                controller.close()

                ledger.claimFuture.complete(OneTimeUseClaim.acquired(ledger.lastRequest!!, true).let(OneTimeUseClaimResult::Acquired))
                ledger.calls shouldContainExactly listOf("claim", "release")
            }
        }
    }

    "quitting while claim is pending does not call native redemption" {
        MockBukkitTestRuntime.open().use { paper ->
            val ledger = RecordingVoucherLedger()
            val controller = controller(paper.createSimplePlugin("PhysicalRewardQuit"), ledger) { _, _, _ ->
                error("native redemption must not start after quit")
            }
            val scheduler = TestTaskScheduler()
            Tasks.withScheduler(scheduler) {
                controller.register()
                try {
                    val player = paper.addPlayer("quit-holder")
                    player.inventory.setItemInMainHand(controller.createStack("reward.test")!!)
                    ledger.usePendingFuture = true
                    paper.callEvent(interact(player, Action.RIGHT_CLICK_AIR))
                    paper.callEvent(org.bukkit.event.player.PlayerQuitEvent(player, Component.empty()))
                    ledger.claimFuture.complete(OneTimeUseClaim.acquired(ledger.lastRequest!!, true).let(OneTimeUseClaimResult::Acquired))
                    flush(scheduler)

                    ledger.calls shouldContainExactly listOf("claim", "release")
                } finally {
                    controller.close()
                }
            }
        }
    }

    "closeAndDrain waits for a late claim before the ledger closes" {
        MockBukkitTestRuntime.open().use { paper ->
            val ledger = RecordingVoucherLedger()
            val controller = controller(paper.createSimplePlugin("PhysicalRewardDrain"), ledger) { _, _, _ ->
                error("native redemption must not start after close")
            }
            val scheduler = TestTaskScheduler()
            Tasks.withScheduler(scheduler) {
                controller.register()
                try {
                    val player = paper.addPlayer("drain-holder")
                    player.inventory.setItemInMainHand(controller.createStack("reward.test")!!)
                    ledger.usePendingFuture = true
                    paper.callEvent(interact(player, Action.RIGHT_CLICK_AIR))

                    val drain = controller.closeAndDrain()
                    drain.isDone shouldBe false

                    ledger.claimFuture.complete(OneTimeUseClaim.acquired(ledger.lastRequest!!, true).let(OneTimeUseClaimResult::Acquired))
                    flush(scheduler)

                    drain.isDone shouldBe true
                    ledger.releaseObservedWhileOpen shouldBe true
                    ledger.close()
                } finally {
                    controller.close()
                }
            }
        }
    }
})

private fun controller(
    plugin: org.bukkit.plugin.Plugin,
    ledger: RecordingVoucherLedger,
    canRedeem: (org.bukkit.entity.Player, PhysicalRewardSpec) -> String? = { _, _ -> null },
    redeem: (org.bukkit.entity.Player, PhysicalRewardSpec, java.util.UUID) -> CompletableFuture<PhysicalRewardOutcome>,
): PhysicalRewardController {
    val spec = PhysicalRewardSpec(
        key = "reward.test",
        fingerprint = OneTimeUseFingerprint.sha256Fields("reward.test", "v1"),
        preview = ItemStack(Material.DIAMOND).apply {
            editMeta { it.displayName(Component.text("Награда")) }
        },
    )
    return PhysicalRewardController(plugin, ledger, { key -> spec.takeIf { it.key == key } }, canRedeem, redeem, "arc.catalog-reward")
}

private fun interact(player: org.bukkit.entity.Player, action: Action): PlayerInteractEvent =
    PlayerInteractEvent(player, action, null, null, BlockFace.SELF, EquipmentSlot.HAND)

private fun flush(scheduler: TestTaskScheduler) {
    repeat(12) { scheduler.executeImmediate() }
}

private class RecordingVoucherLedger(
    private val timeline: MutableList<String>? = null,
    private val claimResult: (OneTimeUseClaimRequest) -> OneTimeUseClaimResult = { request ->
        OneTimeUseClaimResult.Acquired(OneTimeUseClaim.acquired(request, true))
    },
) : OneTimeUseLedger {
    val calls = mutableListOf<String>()
    val claimFuture = CompletableFuture<OneTimeUseClaimResult>()
    var lastRequest: OneTimeUseClaimRequest? = null
    var usePendingFuture: Boolean = false
    var releaseObservedWhileOpen: Boolean = false
    private var closed: Boolean = false

    override fun claim(request: OneTimeUseClaimRequest): CompletableFuture<OneTimeUseClaimResult> {
        calls += "claim"
        timeline?.add("claim")
        lastRequest = request
        if (usePendingFuture) return claimFuture
        return CompletableFuture.completedFuture(claimResult(request))
    }

    override fun commit(claim: OneTimeUseClaim): CompletableFuture<OneTimeUseCommitResult> {
        calls += "commit"
        timeline?.add("commit")
        return CompletableFuture.completedFuture(OneTimeUseCommitResult.COMMITTED)
    }

    override fun release(claim: OneTimeUseClaim): CompletableFuture<OneTimeUseReleaseResult> {
        calls += "release"
        timeline?.add("release")
        releaseObservedWhileOpen = !closed
        return CompletableFuture.completedFuture(OneTimeUseReleaseResult.RELEASED)
    }

    override fun abandon(claim: OneTimeUseClaim): CompletableFuture<OneTimeUseAbandonResult> {
        calls += "abandon"
        timeline?.add("abandon")
        return CompletableFuture.completedFuture(OneTimeUseAbandonResult.RETAINED_FOR_RECOVERY)
    }

    override fun close() {
        closed = true
    }
}
