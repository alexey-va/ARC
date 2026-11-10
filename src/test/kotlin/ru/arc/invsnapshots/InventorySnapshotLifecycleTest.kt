package ru.arc.invsnapshots

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import net.kyori.adventure.text.minimessage.MiniMessage
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver
import org.bukkit.Material
import org.bukkit.inventory.ItemStack
import ru.arc.ARC
import ru.arc.commands.arc.CommandConfig
import ru.arc.core.Tasks
import ru.arc.core.TestTaskScheduler
import ru.arc.gui.ArcMenus
import ru.arc.paper.menu.PaperDialogScreen
import ru.arc.paper.testing.MockBukkitTestRuntime
import java.util.UUID
import java.util.concurrent.CompletableFuture

class InventorySnapshotLifecycleTest : FunSpec({
    test("late async results cannot reopen a dismissed list and active results render only through scheduler") {
        MockBukkitTestRuntime.open().use { paper ->
            val plugin = paper.createSimplePlugin("SnapshotTest")
            val viewer = paper.addPlayer("Inspector")
            viewer.addAttachment(plugin, SNAPSHOT_VIEW_PERMISSION, true)
            val arc = mockk<ARC>(relaxed = true)
            val previous = ARC.plugin
            ARC.plugin = arc
            every { arc.isEnabled } returns true
            mockkObject(ArcMenus, CommandConfig)
            val shown = mutableListOf<PaperDialogScreen>()
            val renderThreads = mutableListOf<Thread>()
            var dismiss: () -> Unit = {}
            every { ArcMenus.beginDialogFlow(viewer) } answers { dismiss() }
            every { ArcMenus.openDialog(viewer, any(), any(), any(), any()) } answers {
                shown += secondArg<PaperDialogScreen>()
                renderThreads += Thread.currentThread()
                dismiss = arg(4)
            }
            every { CommandConfig.get(any(), any(), any<TagResolver>()) } answers {
                MiniMessage.miniMessage().deserialize(secondArg(), thirdArg<TagResolver>())
            }
            val scheduler = TestTaskScheduler()
            try {
                Tasks.withScheduler(scheduler) {
                    val first = CompletableFuture<SnapshotHistory?>()
                    InventorySnapshotInspector.load(viewer, "OfflineOwner", InventorySnapshotSource { first })
                    shown.size shouldBe 1
                    dismiss()
                    first.complete(SnapshotHistory(UUID.randomUUID(), "OfflineOwner", listOf(snapshotWith(0, ItemStack(Material.STONE)))))
                    scheduler.executeImmediate()
                    shown.size shouldBe 1

                    val second = CompletableFuture<SnapshotHistory?>()
                    InventorySnapshotInspector.load(viewer, "OfflineOwner", InventorySnapshotSource { second })
                    val history = SnapshotHistory(UUID.randomUUID(), "OfflineOwner", listOf(snapshotWith(0, ItemStack(Material.STONE))))
                    val worker = Thread { second.complete(history) }
                    worker.start()
                    worker.join(2_000)
                    worker.isAlive shouldBe false
                    shown.size shouldBe 2
                    scheduler.executeImmediate()
                    shown.size shouldBe 3
                    shown.last().buttons.count { it.id.value.startsWith("snapshot_") } shouldBe 1
                    renderThreads.all { it === Thread.currentThread() } shouldBe true

                    val revoked = CompletableFuture<SnapshotHistory?>()
                    InventorySnapshotInspector.load(viewer, "OfflineOwner", InventorySnapshotSource { revoked })
                    viewer.addAttachment(plugin, SNAPSHOT_VIEW_PERMISSION, false)
                    every { CommandConfig.noPermission() } returns net.kyori.adventure.text.Component.text("Denied")
                    revoked.complete(history)
                    scheduler.executeImmediate()
                    shown.size shouldBe 4 // The pending screen stays; no target data was opened.
                }
            } finally {
                ARC.plugin = previous
                unmockkObject(ArcMenus, CommandConfig)
            }
        }
    }
})
