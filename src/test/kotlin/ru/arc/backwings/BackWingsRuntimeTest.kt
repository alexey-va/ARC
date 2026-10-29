package ru.arc.backwings

import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.event.entity.PlayerDeathEvent
import org.bukkit.event.player.PlayerChangedWorldEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.event.player.PlayerSwapHandItemsEvent
import org.bukkit.inventory.ItemStack
import org.bukkit.persistence.PersistentDataType
import ru.arc.core.BudgetedTaskQueue
import ru.arc.core.LifecycleTaskScope
import ru.arc.core.TestTaskScheduler
import ru.arc.paper.testing.MockBukkitTestRuntime

class BackWingsRuntimeTest : FreeSpec({
    "plain F toggles and switches only our cosmetics without moving either hand or changing foreign body cosmetics" {
        wingScenario { f ->
            val dread = f.item(BackWing.DREADKNIGHT)
            val galaxy = f.item(BackWing.GALAXY)
            f.inventory.setItemInMainHand(dread)
            f.inventory.setItemInOffHand(ItemStack(Material.SHIELD))
            f.inventory.setItem(17, galaxy)
            f.nativeEquipped.add("other_backpack")
            val before = f.inventory.contents.map { it?.clone() }
            val first = f.swap()
            first.isCancelled shouldBe true
            first.mainHandItem shouldBe before[40]
            first.offHandItem shouldBe dread
            f.inventory.contents.toList() shouldBe before
            f.nativeEquipped shouldBe setOf("other_backpack", BackWing.DREADKNIGHT.cosmeticId)
            f.selection() shouldBe null

            f.inventory.setItemInMainHand(galaxy)
            f.inventory.setItem(17, dread)
            val switched = f.inventory.contents.map { it?.clone() }
            f.swap().isCancelled shouldBe true
            f.inventory.contents.toList() shouldBe switched
            f.nativeEquipped shouldBe setOf("other_backpack", BackWing.GALAXY.cosmeticId)
            f.swap().isCancelled shouldBe true
            f.nativeEquipped shouldBe setOf("other_backpack")
            f.inventory.contents.toList() shouldBe switched
            f.commands shouldBe listOf(
                "cosmetics WingHolder add arc_dreadknight_wings",
                "cosmetics WingHolder remove arc_dreadknight_wings",
                "cosmetics WingHolder add arc_galaxy_wings",
                "cosmetics WingHolder remove arc_galaxy_wings",
            )
        }
    }

    "shift F earlier cancellations ordinary items and offhand-only wings keep their existing controls" {
        wingScenario { f ->
            f.inventory.setItemInMainHand(f.item(BackWing.DREADKNIGHT))
            f.player.isSneaking = true
            f.swap().isCancelled shouldBe false
            f.player.isSneaking = false
            f.swap(cancelled = true).isCancelled shouldBe true
            f.inventory.setItemInOffHand(f.inventory.itemInMainHand)
            f.inventory.setItemInMainHand(ItemStack(Material.STONE))
            f.swap().isCancelled shouldBe false
            f.inventory.setItemInMainHand(f.itemId("another:wings"))
            f.swap().isCancelled shouldBe false
            f.commands shouldBe emptyList()
            f.granted() shouldBe emptySet()
        }
    }

    "new storage holders get exact temporary permissions and loss removes only the unowned native wing" {
        wingScenario { f ->
            val unrelated = f.player.addAttachment(f.plugin, "unrelated.backpack", true)
            val dread = f.item(BackWing.DREADKNIGHT)
            f.inventory.setItem(35, dread)
            f.advance(12)
            f.granted() shouldBe BackWing.DREADKNIGHT.permissions.toSet()
            f.commands shouldBe emptyList()
            f.inventory.setItem(18, f.item(BackWing.GALAXY))
            f.advance(12)
            f.granted() shouldBe (BackWing.DREADKNIGHT.permissions + BackWing.GALAXY.permissions).toSet()
            f.nativeEquipped.addAll(setOf("other_backpack", BackWing.DREADKNIGHT.cosmeticId, BackWing.GALAXY.cosmeticId))
            f.inventory.setItem(35, null)
            f.inventory.setItemInOffHand(dread)
            val before = f.inventory.contents.map { it?.clone() }
            f.advance(12)
            f.granted() shouldBe BackWing.GALAXY.permissions.toSet()
            f.nativeEquipped shouldBe setOf("other_backpack", BackWing.GALAXY.cosmeticId)
            f.inventory.contents.toList() shouldBe before
            f.player.hasPermission("unrelated.backpack") shouldBe true
            f.commands shouldBe listOf("cosmetics WingHolder remove arc_dreadknight_wings")
            f.inventory.setItem(18, null)
            f.advance(12)
            f.granted() shouldBe emptySet()
            f.nativeEquipped shouldBe setOf("other_backpack")
            f.player.hasPermission("unrelated.backpack") shouldBe true
            f.player.removeAttachment(unrelated)
        }
    }

    "old owned PDC selection migrates once and a native GUI unequip stays off across audits world changes and reload" {
        wingScenario { f ->
            f.inventory.setItem(35, f.item(BackWing.DECEASE))
            f.legacy(BackWing.DECEASE.itemId)
            f.advance(12)
            f.nativeEquipped shouldBe setOf(BackWing.DECEASE.cosmeticId)
            f.selection() shouldBe null
            f.commands shouldBe listOf("cosmetics WingHolder add arc_decease_wings")
            f.nativeEquipped.clear() // The native wardrobe is authoritative, including manual unequip.
            f.advance(12)
            f.runtime.onWorldChange(PlayerChangedWorldEvent(f.player, f.player.world))
            f.runtime.reconfigure(BackWingsSettings())
            f.advance(12)
            f.nativeEquipped shouldBe emptySet()
            f.commands.size shouldBe 1
        }
    }

    "native choice wins over legacy preference and an unowned legacy choice is never restored" {
        wingScenario { f ->
            f.inventory.setItem(17, f.item(BackWing.DREADKNIGHT))
            f.inventory.setItem(18, f.item(BackWing.GALAXY))
            f.nativeEquipped.add(BackWing.GALAXY.cosmeticId)
            f.legacy(BackWing.DREADKNIGHT.itemId)
            f.advance(12)
            f.selection() shouldBe null
            f.nativeEquipped shouldBe setOf(BackWing.GALAXY.cosmeticId)
            f.commands shouldBe emptyList()
            f.nativeEquipped.clear()
            f.legacy(BackWing.DECEASE.itemId)
            f.advance(12)
            f.selection() shouldBe null
            f.commands shouldBe emptyList()
            f.legacy("other:unknown_wings")
            f.advance(12)
            f.selection() shouldBe null
        }
    }

    "death quit and shutdown revoke ARC permissions while vendor persistence and unrelated permissions remain owned by their provider" {
        wingScenario { f ->
            f.inventory.setItemInMainHand(f.item(BackWing.DREADKNIGHT))
            f.swap()
            f.granted() shouldBe BackWing.DREADKNIGHT.permissions.toSet()
            f.runtime.onDeath(mockk<PlayerDeathEvent> { every { entity } returns f.player })
            f.granted() shouldBe emptySet()
            f.runtime.restore(f.player)
            f.granted() shouldBe BackWing.DREADKNIGHT.permissions.toSet()
            f.runtime.onQuit(mockk<PlayerQuitEvent> { every { player } returns f.player })
            f.granted() shouldBe emptySet()
            f.runtime.restore(f.player)
            f.granted() shouldBe BackWing.DREADKNIGHT.permissions.toSet()
            f.runtime.close()
            f.runtime.close()
            f.granted() shouldBe emptySet()
            f.scope.trackedTaskCount() shouldBe 0
            f.runtime.restore(f.player)
            f.swap().isCancelled shouldBe false
            f.nativeEquipped shouldBe setOf(BackWing.DREADKNIGHT.cosmeticId)
            f.commands shouldBe listOf("cosmetics WingHolder add arc_dreadknight_wings")
        }
    }

    "unavailable providers unresolved placeholders and a rejected native change do not claim success or discard a pending migration" {
        wingScenario { f ->
            f.inventory.setItemInMainHand(f.item(BackWing.DREADKNIGHT))
            f.legacy(BackWing.DREADKNIGHT.itemId)
            f.vendorAvailable = false
            f.advance(12)
            f.granted() shouldBe emptySet()
            f.swap().isCancelled shouldBe true
            f.commands shouldBe emptyList()
            f.selection() shouldBe BackWing.DREADKNIGHT.itemId
            f.vendorAvailable = true
            f.resolved = false
            f.advance(12)
            f.swap()
            f.commands shouldBe emptyList()
            f.selection() shouldBe BackWing.DREADKNIGHT.itemId
            f.resolved = true
            f.applyCommands = false // Bukkit dispatch accepted, but the vendor did not equip.
            f.swap()
            f.selection() shouldBe BackWing.DREADKNIGHT.itemId
            f.nativeEquipped shouldBe emptySet()
            f.applyCommands = true
            f.advance(12)
            f.selection() shouldBe null
            f.nativeEquipped shouldBe setOf(BackWing.DREADKNIGHT.cosmeticId)
            f.vendorAvailable = false
            f.advance(12)
            f.granted() shouldBe emptySet()
        }
    }

    "minimal add and remove commands succeed only when strict native readback matches the request" {
        wingScenario { f ->
            var readback = "false"
            val commands = mutableListOf<String>()
            val bridge = CosmeticsCoreBackWings(
                { true }, { _, _ -> readback }, { commands.add(it); true },
            )
            bridge.setEquipped(f.player, BackWing.DREADKNIGHT, true) shouldBe false
            readback = "true"
            bridge.setEquipped(f.player, BackWing.DREADKNIGHT, true) shouldBe true
            bridge.setEquipped(f.player, BackWing.DREADKNIGHT, false) shouldBe false
            readback = "false"
            bridge.setEquipped(f.player, BackWing.DREADKNIGHT, false) shouldBe true
            readback = "%cosmetics_has_equipped_arc_dreadknight_wings%"
            bridge.setEquipped(f.player, BackWing.DREADKNIGHT, false) shouldBe false
            commands shouldBe listOf(
                "cosmetics WingHolder add arc_dreadknight_wings",
                "cosmetics WingHolder add arc_dreadknight_wings",
                "cosmetics WingHolder remove arc_dreadknight_wings",
                "cosmetics WingHolder remove arc_dreadknight_wings",
                "cosmetics WingHolder remove arc_dreadknight_wings",
            )
        }
    }

    "ordinary listener and provider detection load without CosmeticsCore or its paid Java API" {
        wingScenario { f ->
            CosmeticsCoreBackWings.available(f.plugin) shouldBe false
            BackWingsRuntime::class.java.declaredMethods.any { it.name == "onSwapHands" } shouldBe true
        }
    }
})

private fun wingScenario(body: (WingFixture) -> Unit) {
    MockBukkitTestRuntime.open().use { paper -> WingFixture(paper).use(body) }
}

private class WingFixture(private val paper: MockBukkitTestRuntime) : AutoCloseable {
    val plugin = paper.createSimplePlugin("BackWingsTest")
    val player = paper.addPlayer("WingHolder")
    val inventory = player.inventory
    private val scheduler = TestTaskScheduler()
    private val queueScope = LifecycleTaskScope(scheduler, true)
    val scope = LifecycleTaskScope(scheduler, true)
    private val work = BudgetedTaskQueue(queueScope, 64, 4_000_000L, { 0L }, 32)
    val commands = mutableListOf<String>()
    val nativeEquipped = mutableSetOf<String>()
    var vendorAvailable = true
    var resolved = true
    var applyCommands = true
    private val cosmetics = CosmeticsCoreBackWings(
        { vendorAvailable },
        { _, token ->
            val wing = BackWing.entries.single { token == "%cosmetics_has_equipped_${it.cosmeticId}%" }
            if (resolved) (wing.cosmeticId in nativeEquipped).toString() else token
        },
        { command ->
            commands += command
            val args = command.split(' ')
            args[0] shouldBe "cosmetics"
            args[1] shouldBe player.name
            args.size shouldBe 4
            BackWing.entries.any { it.cosmeticId == args[3] } shouldBe true
            if (applyCommands) when (args[2]) {
                "add" -> nativeEquipped.add(args[3])
                "remove" -> nativeEquipped.remove(args[3])
                else -> error("Unexpected native command: $command")
            }
            true
        },
    )
    val runtime = BackWingsRuntime(plugin, BackWingsSettings(), cosmetics, work, scope) {
        it.itemMeta?.persistentDataContainer?.get(ITEM_ID, PersistentDataType.STRING)
    }.also { it.start() }

    fun item(wing: BackWing): ItemStack = itemId(wing.itemId)
    fun itemId(id: String): ItemStack = ItemStack(Material.PAPER).apply {
        editMeta { it.persistentDataContainer.set(ITEM_ID, PersistentDataType.STRING, id) }
    }

    fun swap(cancelled: Boolean = false): PlayerSwapHandItemsEvent = PlayerSwapHandItemsEvent(
        player, inventory.itemInOffHand.clone(), inventory.itemInMainHand.clone(),
    ).also { it.isCancelled = cancelled; paper.callEvent(it) }

    fun legacy(id: String) = player.persistentDataContainer.set(BackWingsRuntime.SELECTION_KEY, PersistentDataType.STRING, id)
    fun selection(): String? = player.persistentDataContainer.get(BackWingsRuntime.SELECTION_KEY, PersistentDataType.STRING)
    fun granted(): Set<String> = player.effectivePermissions.filter {
        it.value && it.permission.startsWith("cosmeticscore.")
    }.mapTo(mutableSetOf()) { it.permission }
    fun advance(ticks: Int) { repeat(ticks) { paper.performTicks(1L); scheduler.tick(1L) } }

    override fun close() {
        runtime.close()
        work.close()
        queueScope.close()
    }

    companion object { private val ITEM_ID = NamespacedKey("test", "wing-id") }
}
