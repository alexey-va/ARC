package ru.arc.graves

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import net.kyori.adventure.text.Component
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import org.bukkit.persistence.PersistentDataType
import ru.arc.config.ConfigManager
import ru.arc.gui.ArcMenuConfiguration
import ru.arc.gui.ArcMenuSchema
import ru.arc.menu.MenuElementId
import ru.arc.paper.menu.PaperMenuClickContext
import ru.arc.paper.menu.PaperMenuContent
import ru.arc.paper.menu.PaperMenuItemFactory
import ru.arc.paper.testing.MockBukkitTestRuntime
import java.util.UUID

class GravesContentsMenuTest : FunSpec({
    test("contents are detached exact items and only the explicit claim delegates id and revision after closing") {
        MockBukkitTestRuntime.open().use {
            val f = GraveContentsFixture()
            val sword = ItemStack(Material.DIAMOND_SWORD).also { stack ->
                stack.editMeta { meta ->
                    meta.displayName(Component.text("Именной меч"))
                    meta.lore(listOf(Component.text("Сохранённое описание")))
                    meta.persistentDataContainer.set(NamespacedKey("graves_test", "identity"), PersistentDataType.STRING, "kept")
                }
            }
            f.stacks["sword"] = sword
            f.stacks["diamonds"] = ItemStack(Material.DIAMOND, 17)
            val record = f.record(items = listOf("sword", "diamonds"))
            f.menu.open(f.player, record)
            val icons = f.content!!.regions.getValue(ArcMenuSchema.GRAVE_ITEMS)
            icons.map { it.item } shouldBe listOf(sword, f.stacks.getValue("diamonds"))
            (icons.first().item === sword) shouldBe false
            icons.all { !it.enabled && it.acceptedClicks.isEmpty() && it.transfer == null } shouldBe true
            f.claims shouldBe emptyList()
            f.click("claim")
            f.claims shouldBe listOf(record.id to record.revision)
            f.events shouldBe listOf("close", "claim")
        }
    }

    test("admin inspection never includes a claim and permission revocation stops page retrieval") {
        MockBukkitTestRuntime.open().use {
            val f = GraveContentsFixture()
            val record = f.record(owner = UUID(8, 8), items = List(46) { "stone" })
            f.menu.open(f.player, record, readOnly = true)
            f.content!!.elements.keys.map { it.value }.toSet() shouldBe setOf("summary", "next")
            f.content!!.regions.values.flatten().all { it.transfer == null && !it.enabled } shouldBe true
            f.click("next")
            f.requests shouldBe listOf(Triple(record.id, true, 1))
            f.admin = false
            f.click("next")
            f.requests.size shouldBe 1
            f.claims shouldBe emptyList()
            f.events shouldBe listOf("close")
        }
    }

    test("stale claim availability requests fresh contents without giving items and later pages use fresh retrieval") {
        MockBukkitTestRuntime.open().use {
            val f = GraveContentsFixture()
            val record = f.record(items = List(46) { "stone" })
            f.menu.open(f.player, record)
            f.content!!.regions.getValue(ArcMenuSchema.GRAVE_ITEMS).size shouldBe 45
            f.claimable = false
            f.click("claim")
            f.requests shouldBe listOf(Triple(record.id, false, 0))
            f.claims shouldBe emptyList()
            f.events shouldBe emptyList()
            f.click("next")
            f.requests.last() shouldBe Triple(record.id, false, 1)
            f.menu.open(f.player, record, page = 1)
            f.content!!.regions.getValue(ArcMenuSchema.GRAVE_ITEMS).size shouldBe 1
            f.content!!.elements.getValue(MenuElementId.of("claim")).enabled shouldBe false
        }
    }

    test("foreign owner expired records and item decoding failure cannot expose a partial chest") {
        MockBukkitTestRuntime.open().use {
            val f = GraveContentsFixture()
            f.menu.open(f.player, f.record(owner = UUID(9, 9)))
            f.menu.open(f.player, f.record().copy(expiresAt = 1))
            f.menu.open(f.player, f.record().copy(phase = NativeGravePhase.REVIEW))
            f.menu.open(f.player, f.record(items = listOf("stone", "bad-data")))
            f.content shouldBe null
            f.claims shouldBe emptyList()
        }
    }
})

private class GraveContentsFixture {
    val owner: UUID = UUID(1, 1)
    val player = mockk<Player>(relaxed = true)
    var admin = true
    var claimable = true
    var content: PaperMenuContent? = null
    val stacks = mutableMapOf("stone" to ItemStack(Material.STONE))
    val claims = mutableListOf<Pair<UUID, Long>>()
    val requests = mutableListOf<Triple<UUID, Boolean, Int>>()
    val events = mutableListOf<String>()
    private val configuration = ArcMenuConfiguration.loadResource(javaClass.classLoader)
    private val factory = PaperMenuItemFactory()
    val menu: GravesContentsMenu

    init {
        every { player.uniqueId } returns owner
        every { player.isOnline } returns true
        every { player.hasPermission(GRAVES_ADMIN_PERMISSION) } answers { admin }
        menu = GravesContentsMenu(GravesConfig(ConfigManager.empty()).snapshot(), { Component.text("Ванильный мир") },
            { _, _ -> claimable }, { _, id, revision -> claims += id to revision; events += "claim" },
            { _, id, readOnly, page -> requests += Triple(id, readOnly, page) },
            show = { _, shown -> content = shown },
            item = { element, context -> factory.create(configuration.template(ArcMenuSchema.GRAVE_CONTENTS, MenuElementId.of(element)), context) },
            decode = { stacks.getValue(it) }, close = { events += "close" },
            capacity = { configuration.catalog.require(ArcMenuSchema.GRAVE_CONTENTS).region(ArcMenuSchema.GRAVE_ITEMS).size },
        )
    }

    fun record(owner: UUID = this.owner, items: List<String> = listOf("stone")) = NativeGraveRecord(
        UUID(2, 2), owner, "Owner", "survival", "vanilla", 1, 64, 1, 1, -1, 40, items, revision = 7,
    )

    fun click(id: String) {
        val context = mockk<PaperMenuClickContext>()
        every { context.player } returns player
        content!!.elements.getValue(MenuElementId.of(id)).onClick.handle(context)
    }
}
