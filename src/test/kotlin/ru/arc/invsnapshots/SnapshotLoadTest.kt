package ru.arc.invsnapshots

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import net.kyori.adventure.text.Component
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.event.inventory.ClickType
import org.bukkit.inventory.ItemStack
import org.bukkit.persistence.PersistentDataType
import ru.arc.core.Tasks
import ru.arc.core.TestTaskScheduler
import ru.arc.paper.testing.MockBukkitTestRuntime
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CompletableFuture

class SnapshotLoadTest : FunSpec({
    test("one verified backup precedes exact replacement of storage equipment hotbar and selected slot") {
        MockBukkitTestRuntime.open().use { paper ->
            val plugin = paper.createSimplePlugin("SnapshotTest")
            val viewer = paper.addPlayer("Inspector")
            viewer.addAttachment(plugin, SNAPSHOT_VIEW_PERMISSION, true)
            viewer.addAttachment(plugin, SNAPSHOT_COPY_PERMISSION, true)
            viewer.inventory.setItem(6, ItemStack(Material.GOLDEN_APPLE, 3))
            viewer.inventory.helmet = ItemStack(Material.GOLDEN_HELMET)
            viewer.inventory.heldItemSlot = 6
            viewer.inventory.setItem(41, ItemStack(Material.WOLF_ARMOR))
            val original = SnapshotInventory.capture(viewer)
            viewer.enderChest.setItem(0, ItemStack(Material.EMERALD))
            val stacks = List(41) { slot ->
                when (slot) {
                    5 -> ItemStack(Material.STONE, 64)
                    22 -> ItemStack(Material.DIAMOND, 9)
                    38 -> ItemStack(Material.DIAMOND_CHESTPLATE)
                    40 -> ItemStack(Material.TOTEM_OF_UNDYING).apply {
                        editMeta {
                            it.displayName(Component.text("Артефакт"))
                            it.persistentDataContainer.set(NamespacedKey("test", "opaque"), PersistentDataType.BYTE_ARRAY, byteArrayOf(1, 3, 7))
                        }
                    }
                    else -> null
                }
            }
            val snapshot = InventorySnapshot(UUID.randomUUID(), Instant.EPOCH, "Survival", "API", false, stacks, 5)
            val backup = CompletableFuture<UUID>()
            val id = UUID.randomUUID()
            var backups = 0
            val results = mutableListOf<Pair<SnapshotLoadResult, UUID?>>()
            val load = SnapshotLoad({ true }, { player ->
                player shouldBe viewer
                original.matches(player) shouldBe true
                backups++
                backup
            })
            val scheduler = TestTaskScheduler()
            Tasks.withScheduler(scheduler) {
                fun click() = load.start(viewer, viewer, ClickType.LEFT, { true }, snapshot) { status, backupId -> results += status to backupId }
                click()
                click()
                backups shouldBe 1
                original.matches(viewer) shouldBe true
                results shouldBe listOf(SnapshotLoadResult.SAVING to null)
                backup.complete(id)
                original.matches(viewer) shouldBe true
                scheduler.executeImmediate()
                viewer.inventory.contents.take(41) shouldBe stacks
                viewer.inventory.getItem(41) shouldBe ItemStack(Material.WOLF_ARMOR)
                viewer.inventory.heldItemSlot shouldBe 5
                viewer.enderChest.getItem(0) shouldBe ItemStack(Material.EMERALD)
                results.last() shouldBe (SnapshotLoadResult.LOADED to id)
                click()
                backups shouldBe 1
                results.last().first shouldBe SnapshotLoadResult.ALREADY_LOADED
                snapshot.item(40) shouldBe stacks[40]

                val legacy = InventorySnapshot(UUID.randomUUID(), Instant.EPOCH, "Survival", "API", false,
                    stacks, 5, exactSlotOrder = false)
                load.start(viewer, viewer, ClickType.LEFT, { true }, legacy) { result, backupId -> results += result to backupId }
                results.last().first shouldBe SnapshotLoadResult.LEGACY
                backups shouldBe 1

                val expanded = InventorySnapshot(UUID.randomUUID(), Instant.EPOCH, "Survival", "API", false,
                    stacks + listOf(null, ItemStack(Material.SADDLE)), 5)
                SnapshotLoad({ true }, { CompletableFuture.completedFuture(UUID.randomUUID()) }).start(
                    viewer, viewer, ClickType.LEFT, { true }, expanded,
                ) { result, backupId -> results += result to backupId }
                scheduler.executeImmediate()
                viewer.inventory.getItem(41) shouldBe null
                viewer.inventory.getItem(42) shouldBe ItemStack(Material.SADDLE)
                results.last().first shouldBe SnapshotLoadResult.LOADED
            }
        }
    }

    test("failed backup, changed contents, revoked right, stale view, locked player or occupied cursor cannot overwrite") {
        MockBukkitTestRuntime.open().use { paper ->
            val plugin = paper.createSimplePlugin("SnapshotTest")
            val viewer = paper.addPlayer("Inspector")
            val other = paper.addPlayer("Other")
            viewer.addAttachment(plugin, SNAPSHOT_VIEW_PERMISSION, true)
            val rights = viewer.addAttachment(plugin, SNAPSHOT_COPY_PERMISSION, true)
            viewer.inventory.setItem(7, ItemStack(Material.APPLE))
            val snapshot = snapshotWith(40, ItemStack(Material.DIAMOND))
            val scheduler = TestTaskScheduler()
            Tasks.withScheduler(scheduler) {
                for (outcome in listOf("failed", "changed", "denied", "stale", "locked", "cursor")) {
                    val future = CompletableFuture<UUID>()
                    var ready = true
                    var current = true
                    val results = mutableListOf<SnapshotLoadResult>()
                    val load = SnapshotLoad({ ready }, { future })
                    fun attempt(actor: org.bukkit.entity.Player, click: ClickType) = load.start(viewer, actor, click,
                        { current }, snapshot) { result, _ -> results += result }
                    attempt(other, ClickType.LEFT)
                    attempt(viewer, ClickType.SHIFT_LEFT)
                    results shouldBe emptyList()
                    attempt(viewer, ClickType.LEFT)
                    when (outcome) {
                        "changed" -> viewer.inventory.setItem(5, ItemStack(Material.BOOK))
                        "denied" -> rights.setPermission(SNAPSHOT_COPY_PERMISSION, false)
                        "stale" -> current = false
                        "locked" -> ready = false
                        "cursor" -> viewer.setItemOnCursor(ItemStack(Material.CLOCK))
                    }
                    val before = SnapshotInventory.capture(viewer)
                    if (outcome == "failed") future.completeExceptionally(IllegalStateException("No durable snapshot"))
                    else future.complete(UUID.randomUUID())
                    scheduler.executeImmediate()
                    before.matches(viewer) shouldBe true
                    results.last() shouldBe when (outcome) {
                        "failed" -> SnapshotLoadResult.BACKUP_FAILED
                        "changed" -> SnapshotLoadResult.CHANGED
                        "denied" -> SnapshotLoadResult.DENIED
                        "stale" -> SnapshotLoadResult.SAVING
                        else -> SnapshotLoadResult.NOT_READY
                    }
                    viewer.setItemOnCursor(null)
                    rights.setPermission(SNAPSHOT_COPY_PERMISSION, true)
                }
            }
        }
    }
})
