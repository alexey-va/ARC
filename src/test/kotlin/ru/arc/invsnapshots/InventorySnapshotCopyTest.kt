package ru.arc.invsnapshots

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.event.inventory.ClickType
import org.bukkit.inventory.ItemStack
import org.bukkit.persistence.PersistentDataType
import net.kyori.adventure.text.Component
import ru.arc.paper.testing.MockBukkitTestRuntime
import java.time.Instant
import java.util.UUID

class InventorySnapshotCopyTest : FunSpec({
    test("copy preserves exact stack metadata and never changes the detached source") {
        MockBukkitTestRuntime.open().use { paper ->
            val plugin = paper.createSimplePlugin("SnapshotTest")
            val viewer = paper.addPlayer("Inspector")
            viewer.addAttachment(plugin, SNAPSHOT_VIEW_PERMISSION, true)
            viewer.addAttachment(plugin, SNAPSHOT_COPY_PERMISSION, true)
            val original = ItemStack(Material.DIAMOND, 17).apply {
                editMeta {
                    it.displayName(Component.text("Original name"))
                    it.lore(listOf(Component.text("Original lore")))
                    it.persistentDataContainer.set(NamespacedKey("test", "opaque"), PersistentDataType.BYTE_ARRAY, byteArrayOf(1, 3, 7))
                    it.isUnbreakable = true
                }
            }
            val snapshot = snapshotWith(40, original)
            original.amount = 2 // Mutating the provider's input must not change our detached snapshot.
            val expected = original.clone().also { it.amount = 17 }
            snapshot.item(40)!!.amount = 9 // Neither may a caller change its retained source.
            var audited: ItemStack? = null
            SnapshotCopy.copy(viewer, viewer, true, ClickType.LEFT, snapshot, 40) { audited = it } shouldBe SnapshotCopyResult.COPIED
            viewer.inventory.getItem(0) shouldBe expected
            audited shouldBe expected
            snapshot.item(40) shouldBe expected
            viewer.inventory.setItem(0, null)
            snapshot.item(40) shouldBe expected
        }
    }

    test("full inventory including partial stacks receives nothing and never audits a copy") {
        MockBukkitTestRuntime.open().use { paper ->
            val plugin = paper.createSimplePlugin("SnapshotTest")
            val viewer = paper.addPlayer("Inspector")
            viewer.addAttachment(plugin, SNAPSHOT_VIEW_PERMISSION, true)
            viewer.addAttachment(plugin, SNAPSHOT_COPY_PERMISSION, true)
            (0 until 36).forEach { viewer.inventory.setItem(it, ItemStack(Material.DIAMOND, 1)) }
            val before = viewer.inventory.contents.map { it?.clone() }
            val snapshot = snapshotWith(4, ItemStack(Material.DIAMOND, 3))
            SnapshotCopy.copy(viewer, viewer, true, ClickType.LEFT, snapshot, 4) { error("Unexpected audit") } shouldBe SnapshotCopyResult.FULL
            viewer.inventory.contents.toList() shouldBe before
            snapshot.item(4) shouldBe ItemStack(Material.DIAMOND, 3)
        }
    }

    test("permission revocation different actor stale view and every alternate click cannot copy") {
        MockBukkitTestRuntime.open().use { paper ->
            val plugin = paper.createSimplePlugin("SnapshotTest")
            val viewer = paper.addPlayer("Inspector")
            val other = paper.addPlayer("Other")
            viewer.addAttachment(plugin, SNAPSHOT_VIEW_PERMISSION, true)
            val permission = viewer.addAttachment(plugin, SNAPSHOT_COPY_PERMISSION, true)
            val snapshot = snapshotWith(39, ItemStack(Material.DIAMOND_HELMET))
            fun copy(actor: org.bukkit.entity.Player = viewer, current: Boolean = true, click: ClickType = ClickType.LEFT) =
                SnapshotCopy.copy(viewer, actor, current, click, snapshot, 39) { error("Unexpected audit") }
            copy(actor = other) shouldBe SnapshotCopyResult.STALE
            copy(current = false) shouldBe SnapshotCopyResult.STALE
            ClickType.entries.filterNot { it == ClickType.LEFT }.forEach { copy(click = it) shouldBe SnapshotCopyResult.UNSUPPORTED_CLICK }
            permission.setPermission(SNAPSHOT_COPY_PERMISSION, false)
            copy() shouldBe SnapshotCopyResult.DENIED
            permission.setPermission(SNAPSHOT_COPY_PERMISSION, true)
            viewer.addAttachment(plugin, SNAPSHOT_VIEW_PERMISSION, false)
            copy() shouldBe SnapshotCopyResult.DENIED
            viewer.inventory.storageContents.all { it == null || it.type.isAir } shouldBe true
        }
    }
})

internal fun snapshotWith(slot: Int, item: ItemStack): InventorySnapshot = InventorySnapshot(
    UUID.randomUUID(), Instant.parse("2026-11-10T08:30:00Z"), "Survival", "WORLD_SAVE", false,
    List(41) { if (it == slot) item else null },
)
