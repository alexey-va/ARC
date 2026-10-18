package ru.arc.onboarding

import com.Zrips.CMI.CMI
import com.Zrips.CMI.Containers.CMIPlayerInventory.CMIInventorySlot
import com.Zrips.CMI.Modules.Kits.Kit
import com.Zrips.CMI.Modules.Kits.KitsManager
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.unmockkObject
import io.mockk.unmockkStatic
import io.mockk.verify
import net.kyori.adventure.text.Component
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.event.inventory.InventoryType
import org.bukkit.inventory.ItemStack
import org.mockbukkit.mockbukkit.inventory.InventoryMock
import org.mockbukkit.mockbukkit.inventory.SimpleInventoryViewMock
import ru.arc.commands.arc.CommandConfig
import ru.arc.commands.arc.subcommands.OnboardingSubCommand
import ru.arc.core.BoundedAsyncExecutor
import ru.arc.core.LifecycleTaskScope
import ru.arc.core.TestTaskScheduler
import ru.arc.paper.playerstate.PaperItemStackBinaryCodec
import ru.arc.paper.playerstate.PaperPlayerStateCodec
import ru.arc.paper.playerstate.PaperPlayerStateEnvelope
import ru.arc.paper.playerstate.PaperPlayerStateService
import ru.arc.paper.testing.MockBukkitTestRuntime
import ru.arc.util.Common
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class OnboardingStarterInventoryTest : FunSpec({
    test("test remains enrollment-only, restart is separate, stop stays routed and other-player arguments are refused") {
        val player = mockk<Player>(relaxed = true)
        mockkObject(OnboardingService, CommandConfig)
        try {
            every { OnboardingService.enrollSelf(player) } returns Unit
            every { OnboardingService.restartSelf(player) } returns Unit
            every { OnboardingService.stopSelf(player) } returns Unit
            every { CommandConfig.getCommandUsage("onboarding", any()) } returns "/arc onboarding [test|restart|stop]"
            every { CommandConfig.usage(any()) } returns Component.text("usage")
            OnboardingSubCommand.execute(player, arrayOf("test"))
            OnboardingSubCommand.execute(player, arrayOf("restart"))
            OnboardingSubCommand.execute(player, arrayOf("stop"))
            OnboardingSubCommand.execute(player, arrayOf("restart", "OtherPlayer"))
            verify(exactly = 1) { OnboardingService.enrollSelf(player) }
            verify(exactly = 1) { OnboardingService.restartSelf(player) }
            verify(exactly = 1) { OnboardingService.stopSelf(player) }
        } finally { unmockkObject(OnboardingService, CommandConfig) }
    }

    test("durable backup and enrollment precede one complete replacement; duplicate restart is rejected") {
        StarterInventoryFixture().use { fixture ->
            val player = fixture.player
            player.inventory.setItem(2, ItemStack.of(Material.DIAMOND, 3))
            player.inventory.helmet = ItemStack.of(Material.IRON_HELMET)
            player.inventory.setItemInOffHand(ItemStack.of(Material.SHIELD))
            player.setItemOnCursor(ItemStack.of(Material.EMERALD, 2))
            player.inventory.heldItemSlot = 5
            player.level = 21
            player.exp = 0.4f
            player.totalExperience = 321
            val original = fixture.states.capture(player, 1_787_730_000_000)
            val release = fixture.holdWorker()
            val enrollment = CompletableFuture<Unit>()
            var enrollments = 0
            val result = fixture.owner.replace(player, { true }) { enrollments++; enrollment }

            result.isDone shouldBe false
            fixture.states.inventoryMismatches(player, original) shouldBe emptyList()
            val duplicate = fixture.owner.replace(player, { true }) { error("duplicate enrollment") }
            duplicate.isCompletedExceptionally shouldBe true
            fixture.grants shouldBe 0
            enrollments shouldBe 0

            release.countDown()
            fixture.until { enrollments == 1 }
            fixture.states.inventoryMismatches(player, original) shouldBe emptyList()
            result.isDone shouldBe false
            enrollment.complete(Unit)
            fixture.await(result)
            val path = result.join()
            Files.isRegularFile(path) shouldBe true
            val envelope = Common.gson.fromJson(Files.readString(path), PaperPlayerStateEnvelope::class.java)
            fixture.codec.validateEnvelope(envelope) shouldBe player.uniqueId
            fixture.grants shouldBe 1
            fixture.saves shouldBe 1
            fixture.owner.isPending(player.uniqueId) shouldBe false
            player.inventory.storageContents.filterNotNull().filterNot(ItemStack::isEmpty).map { it.type } shouldBe listOf(Material.WOODEN_SWORD)
            player.inventory.helmet?.type shouldBe Material.LEATHER_HELMET
            player.inventory.itemInOffHand.isEmpty shouldBe true
            player.itemOnCursor.isEmpty shouldBe true
            player.inventory.heldItemSlot shouldBe 0
            player.level shouldBe 21
            player.exp shouldBe 0.4f
            player.totalExperience shouldBe 321
        }
    }

    test("inventory changes during backup or enrollment abort without overwriting newer items") {
        StarterInventoryFixture().use { fixture ->
            val release = fixture.holdWorker()
            var enrollments = 0
            val pendingBackup = fixture.owner.replace(fixture.player, { true }) {
                enrollments++; CompletableFuture.completedFuture(Unit)
            }
            fixture.player.inventory.setItem(3, ItemStack.of(Material.GOLD_INGOT, 7))
            release.countDown()
            fixture.await(pendingBackup)
            pendingBackup.isCompletedExceptionally shouldBe true
            fixture.grants shouldBe 0
            enrollments shouldBe 0
            fixture.player.inventory.getItem(3)?.amount shouldBe 7

            val enrollment = CompletableFuture<Unit>()
            val pendingEnrollment = fixture.owner.replace(fixture.player, { true }) { enrollments++; enrollment }
            fixture.until { enrollments == 1 }
            fixture.player.setItemOnCursor(ItemStack.of(Material.DIAMOND))
            enrollment.complete(Unit)
            fixture.await(pendingEnrollment)
            pendingEnrollment.isCompletedExceptionally shouldBe true
            fixture.grants shouldBe 0
            fixture.player.itemOnCursor.type shouldBe Material.DIAMOND
            fixture.player.inventory.getItem(3)?.amount shouldBe 7
        }
    }

    test("a superseded run and a closed lifecycle cannot clear inventory after delayed IO") {
        StarterInventoryFixture().use { fixture ->
            val release = fixture.holdWorker()
            var current = true
            val result = fixture.owner.replace(fixture.player, { current }) { error("stale enrollment") }
            current = false
            release.countDown()
            fixture.await(result)
            result.isCompletedExceptionally shouldBe true
            fixture.grants shouldBe 0

            val secondRelease = fixture.holdWorker()
            val closed = fixture.owner.replace(fixture.player, { true }) { error("closed enrollment") }
            fixture.scope.close()
            secondRelease.countDown()
            fixture.await(closed)
            closed.isCompletedExceptionally shouldBe true
            fixture.owner.isPending(fixture.player.uniqueId) shouldBe false
            fixture.grants shouldBe 0
        }
    }

    test("a failed native kit restores metadata, armor, offhand, cursor and selected slot") {
        StarterInventoryFixture().use { fixture ->
            val named = ItemStack.of(Material.DIAMOND, 3).apply {
                itemMeta = itemMeta.apply { displayName(Component.text("Backup metadata")) }
            }
            fixture.player.inventory.setItem(0, named)
            fixture.player.inventory.chestplate = ItemStack.of(Material.IRON_CHESTPLATE)
            fixture.player.inventory.setItemInOffHand(ItemStack.of(Material.TORCH, 12))
            fixture.player.setItemOnCursor(ItemStack.of(Material.EMERALD, 4))
            fixture.player.inventory.heldItemSlot = 6
            val original = fixture.states.capture(fixture.player, 1_787_730_000_000)
            fixture.grant = { actor ->
                actor.inventory.setItem(2, ItemStack.of(Material.APPLE))
                error("native kit failure")
            }
            val result = fixture.owner.replace(fixture.player, { true }) { CompletableFuture.completedFuture(Unit) }
            fixture.await(result)
            result.isCompletedExceptionally shouldBe true
            fixture.states.inventoryMismatches(fixture.player, original) shouldBe emptyList()
            fixture.saves shouldBe 1
            fixture.owner.isPending(fixture.player.uniqueId) shouldBe false
        }
    }

    test("nonempty crafting inputs or result abort before an inventory can be lost or dropped") {
        StarterInventoryFixture().use { fixture ->
            val crafting = fixture.player.openInventory.topInventory
            crafting.setItem(1, ItemStack.of(Material.OAK_LOG))
            val input = fixture.owner.replace(fixture.player, { true }) { error("crafting enrollment") }
            input.isCompletedExceptionally shouldBe true
            crafting.getItem(1)?.type shouldBe Material.OAK_LOG
            crafting.clear()
            crafting.setItem(0, ItemStack.of(Material.OAK_PLANKS, 4))
            val result = fixture.owner.replace(fixture.player, { true }) { error("crafting result enrollment") }
            result.isCompletedExceptionally shouldBe true
            crafting.getItem(0)?.amount shouldBe 4
            fixture.grants shouldBe 0
            fixture.owner.isPending(fixture.player.uniqueId) shouldBe false
        }
    }

    test("native CMI grant uses start without the command cooldown route and detects silent cancellation") {
        MockBukkitTestRuntime.open().use { paper ->
            paper.createSimplePlugin("CMI")
            val player = paper.addPlayer("NativeStarter")
            val cmi = mockk<CMI>()
            val manager = mockk<KitsManager>()
            val kit = mockk<Kit>()
            val items = MutableList<ItemStack?>(36) { null }.apply { this[27] = ItemStack.of(Material.WOODEN_SWORD) }
            every { cmi.kitsManager } returns manager
            every { manager.getKit("start", true) } returns kit
            every { kit.isEnabled } returns true
            every { kit.cost } returns 0.0
            every { kit.expCost } returns 0
            every { kit.getItems(player) } returns items
            every { kit.getExtraItem(player, any()) } returns null
            every { kit.getExtraItem(player, CMIInventorySlot.Helmet) } returns ItemStack.of(Material.LEATHER_HELMET)
            every { kit.getCommands(player) } returns listOf("asConsole! example [playerName]")
            every { manager.giveKit(player, kit) } answers {
                player.inventory.setItem(0, ItemStack.of(Material.WOODEN_SWORD))
                player.inventory.helmet = ItemStack.of(Material.LEATHER_HELMET)
            }
            mockkStatic(CMI::class)
            try {
                every { CMI.getInstance() } returns cmi
                nativeStarterKit(player).invoke()
                verify(exactly = 1) { manager.giveKit(player, kit) }
                player.inventory.clear()
                player.inventory.helmet = null
                every { manager.giveKit(player, kit) } returns Unit
                shouldThrow<IllegalStateException> { nativeStarterKit(player).invoke() }
                every { kit.cost } returns 1.0
                shouldThrow<IllegalStateException> { nativeStarterKit(player) }
                verify(exactly = 2) { manager.giveKit(player, kit) }
            } finally { unmockkStatic(CMI::class) }
        }
    }

    test("failed durable backup and rejected worker admission preserve items and release the retry guard") {
        StarterInventoryFixture(capacity = 1).use { fixture ->
            fixture.player.inventory.setItem(0, ItemStack.of(Material.DIAMOND))
            val blocker = fixture.root.resolve("data")
            Files.writeString(blocker, "not a directory")
            val failedWrite = fixture.owner.replace(fixture.player, { true }) { error("unsaved enrollment") }
            fixture.await(failedWrite)
            failedWrite.isCompletedExceptionally shouldBe true
            fixture.owner.isPending(fixture.player.uniqueId) shouldBe false
            fixture.player.inventory.getItem(0)?.type shouldBe Material.DIAMOND
            Files.delete(blocker)

            val release = fixture.holdWorker()
            val queued = fixture.worker.submit { Unit }
            val rejected = fixture.owner.replace(fixture.player, { true }) { error("rejected enrollment") }
            rejected.isCompletedExceptionally shouldBe true
            fixture.owner.isPending(fixture.player.uniqueId) shouldBe false
            fixture.grants shouldBe 0
            fixture.player.inventory.getItem(0)?.type shouldBe Material.DIAMOND
            release.countDown()
            queued.get(5, TimeUnit.SECONDS)

            val failedEnrollment = fixture.owner.replace(fixture.player, { true }) {
                CompletableFuture.failedFuture(IllegalStateException("quest write failed"))
            }
            fixture.await(failedEnrollment)
            failedEnrollment.isCompletedExceptionally shouldBe true
            fixture.grants shouldBe 0
            fixture.owner.isPending(fixture.player.uniqueId) shouldBe false
            fixture.player.inventory.getItem(0)?.type shouldBe Material.DIAMOND

            val retry = fixture.owner.replace(fixture.player, { true }) { CompletableFuture.completedFuture(Unit) }
            fixture.await(retry)
            retry.isCompletedExceptionally shouldBe false
            fixture.grants shouldBe 1
        }
    }
})

private class StarterInventoryFixture(capacity: Int = 8) : AutoCloseable {
    private val paper = MockBukkitTestRuntime.open()
    val player = paper.addPlayer("StarterAdmin").also { actor ->
        // MockBukkit's default crafting view has a null top inventory; Paper always has its five slots.
        val crafting = InventoryMock(actor, 5, InventoryType.CRAFTING)
        actor.openInventory(SimpleInventoryViewMock(actor, crafting, actor.inventory, InventoryType.CRAFTING))
    }
    val root: Path = Files.createTempDirectory("onboarding-inventory-")
    val worker = BoundedAsyncExecutor("onboarding-inventory-test", 1, capacity)
    val scheduler = TestTaskScheduler()
    val scope = LifecycleTaskScope(scheduler)
    // MockBukkit lacks Paper's native item NBT codec. The production default remains native;
    // this seam tests durable sequencing and rollback, not Paper's NBT implementation.
    val codec = PaperPlayerStateCodec(StarterTestItemCodec)
    val states = PaperPlayerStateService(persistPlayerData = {})
    var saves = 0
    var grants = 0
    var grant: (Player) -> Unit = { actor ->
        actor.inventory.contents.filterNotNull().filterNot(ItemStack::isEmpty) shouldBe emptyList()
        actor.itemOnCursor.isEmpty shouldBe true
        actor.inventory.setItem(27, ItemStack.of(Material.WOODEN_SWORD))
        actor.inventory.helmet = ItemStack.of(Material.LEATHER_HELMET)
    }
    val owner = OnboardingStarterInventory(root, worker, scope,
        resolveKit = { actor -> { grants++; grant(actor) } }, codec = codec, persistPlayerData = { saves++ })
    private val releases = mutableListOf<CountDownLatch>()

    fun holdWorker(): CountDownLatch {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1).also(releases::add)
        worker.submit { entered.countDown(); check(release.await(10, TimeUnit.SECONDS)) }
        check(entered.await(5, TimeUnit.SECONDS))
        return release
    }

    fun until(condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (!condition() && System.nanoTime() < deadline) {
            scheduler.executeImmediate()
            Thread.sleep(1)
        }
        check(condition()) { "Inventory restart did not reach its actual completion" }
    }

    fun await(future: CompletableFuture<*>) = until { future.isDone }

    override fun close() {
        releases.forEach(CountDownLatch::countDown)
        scope.close()
        worker.closeAsync().get(10, TimeUnit.SECONDS)
        paper.close()
        root.toFile().deleteRecursively()
    }
}

private object StarterTestItemCodec : PaperItemStackBinaryCodec {
    override fun encodeItems(items: List<ItemStack?>): ByteArray = ByteArrayOutputStream().use { bytes ->
        DataOutputStream(bytes).use { output ->
            output.writeInt(items.size)
            items.forEach { item ->
                val normalized = item?.takeUnless(ItemStack::isEmpty)
                output.writeBoolean(normalized != null)
                if (normalized != null) { output.writeUTF(normalized.type.name); output.writeInt(normalized.amount) }
            }
        }
        bytes.toByteArray()
    }

    override fun decodeItems(payload: ByteArray): List<ItemStack?> = DataInputStream(ByteArrayInputStream(payload)).use { input ->
        List(input.readInt()) { if (input.readBoolean()) ItemStack.of(Material.valueOf(input.readUTF()), input.readInt()) else null }
    }

    override fun encodeItem(item: ItemStack): ByteArray = encodeItems(listOf(item))
    override fun decodeItem(payload: ByteArray): ItemStack = requireNotNull(decodeItems(payload).single())
}
