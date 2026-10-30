package ru.arc.helpcenter

import io.mockk.every
import io.mockk.mockk
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import org.bukkit.Bukkit
import org.bukkit.entity.Player
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import ru.arc.config.ConfigManager
import ru.arc.core.BukkitTaskScheduler
import ru.arc.core.Tasks
import ru.arc.paper.menu.PaperDialogClickContext
import ru.arc.paper.menu.PaperDialogScreen
import ru.arc.paper.testing.MockBukkitTestRuntime
import java.nio.file.Files
import java.util.concurrent.CompletableFuture

class HelpCenterEnchantmentsControllerTest {
    private lateinit var paper: MockBukkitTestRuntime
    private lateinit var player: Player
    private lateinit var controller: HelpCenterController
    private lateinit var screen: PaperDialogScreen
    private var screenDisplays = 0
    private lateinit var inventoryReturn: HelpCenterInventoryReturnRuntime
    private val executed = mutableListOf<String>()
    private val directory = Files.createTempDirectory("enchantments-controller")
    private var sourceResult = CompletableFuture.completedFuture(sourceReport("3,31"))

    @BeforeEach
    fun setup() {
        paper = MockBukkitTestRuntime.open()
        val plugin = paper.createSimplePlugin("EnchantmentsControllerTest")
        Tasks.install(BukkitTaskScheduler(plugin))
        player = paper.addPlayer("Viewer")
        ConfigManager.clear()

        val gateway = mockk<HelpCenterGateway>()
        every { gateway.features() } returns HelpCenterFeature.entries.toSet()
        every { gateway.execute(player, any()) } answers {
            executed += secondArg<String>()
            player.openInventory(Bukkit.createInventory(null, 9))
            true
        }

        inventoryReturn = HelpCenterInventoryReturnRuntime(plugin, returnOnClose = { true })
        val navigation = HelpCenterNavigation(plugin, inventoryReturn::cancel)
        val fixtureCatalog = HelpCenterEnchantmentsCatalog(
            available = true,
            entries = (1..9).map { number ->
                enchantment(
                    id = "sword_$number",
                    name = "Меч %02d".format(number),
                    material = "DIAMOND_SWORD",
                    description = "Описание меча $number",
                )
            } + enchantment(
                id = "axe",
                name = "Топор",
                material = "DIAMOND_AXE",
                description = "Поджигает цель",
            ),
        )

        controller = HelpCenterController(
            settings = HelpCenterConfig.load(directory).snapshot(),
            gateway = gateway,
            openLands = {},
            inventoryReturn = inventoryReturn,
            inviteToLand = { _, _ -> },
            navigation = navigation,
            showDialog = { _, value ->
                screen = value
                screenDisplays++
            },
            closeDialog = {},
            enchantmentsGuide = HelpCenterEnchantmentsGuideConfig.load(directory).snapshot(),
            enchantmentsCatalog = { fixtureCatalog },
            enchantmentAcquisition = { sourceResult },
        )
    }

    @AfterEach
    fun cleanup() {
        controller.close()
        Tasks.reset()
        ConfigManager.clear()
        paper.close()
        directory.toFile().deleteRecursively()
    }

    @Test
    fun `equipment filter keeps its page when returning from enchantment details`() {
        open()
        click("equipment")
        click("equip_${HelpCenterEnchantmentsEquipment.SWORD.ordinal}")
        assertEquals("help.enchantments.results", screen.id)
        assertTrue(body().contains("Найдено зачарований: 9"))

        click("next")
        assertTrue(body().contains("страница 2/2"))
        assertEquals(listOf("search_submit", "enchant_0", "previous"), screen.buttons.map { it.id.value })
        assertEquals("Меч 09", plain(screen.buttons.single { it.id.value == "enchant_0" }.label))

        click("enchant_0")
        assertEquals("help.enchantments.details", screen.id)
        assertTrue(body().contains("Описание меча 9"))
        click("back")

        assertEquals("help.enchantments.results", screen.id)
        assertTrue(body().contains("Найдено зачарований: 9"))
        assertTrue(body().contains("страница 2/2"))
        assertEquals(listOf("search_submit", "enchant_0", "previous"), screen.buttons.map { it.id.value })
        assertEquals("Меч 09", plain(screen.buttons.single { it.id.value == "enchant_0" }.label))
    }

    @Test
    fun `search input filters the catalogue when submitted`() {
        open()
        click("search")
        assertEquals("help.enchantments.results", screen.id)
        assertEquals("", screen.inputs.single().initial)

        click("search_submit", "Поджигает")

        assertTrue(body().contains("Найдено зачарований: 1"))
        assertEquals(listOf("search_submit", "enchant_0"), screen.buttons.map { it.id.value })
        assertEquals("Топор", plain(screen.buttons.single { it.id.value == "enchant_0" }.label))
        assertFalse(screen.buttons.any { it.id.value == "enchant_1" })
    }

    @Test
    fun `special item opens its own card and returns to the item list`() {
        open()
        click("special")
        assertEquals("help.enchantments.special", screen.id)
        assertEquals(10, screen.buttons.count { it.id.value.startsWith("item_") })

        click("item_whitescroll")
        assertEquals("help.enchantments.topic.whitescroll", screen.id)
        assertTrue(body().contains("Назначение"))
        assertTrue(body().contains("Как использовать"))
        assertTrue(body().contains("ЛКМ"))

        click("back")
        assertEquals("help.enchantments.special", screen.id)
        assertEquals(10, screen.buttons.count { it.id.value.startsWith("item_") })
    }

    @Test
    fun `global search has one direct enchantment destination without guide suffix`() {
        controller.search(player, "зачарование")
        paper.performTicks(2)

        assertEquals(1, screen.buttons.count { it.id.value == "result_enchants" })
        assertFalse(screen.buttons.any { it.id.value == "result_guide_enchants" })
        assertFalse(screen.buttons.any { plain(it.label).contains("· справка") })
        click("result_enchants")
        assertEquals("help.enchantments", screen.id)
    }

    @Test
    fun `colloquial slot sphere search includes the slot increaser`() {
        controller.search(player, "сфера увеличения слотов")
        paper.performTicks(2)
        click("result_ae_slotincreaser")
        assertEquals("help.enchantments.topic.slotincreaser", screen.id)
    }

    @Test
    fun `every individual item and box is searchable and directly opens its own usage page`() {
        val guide = HelpCenterEnchantmentsGuideConfig.load(directory).snapshot()
        HelpCenterEnchantmentGuideTopic.entries.forEach { topic ->
            val label = net.kyori.adventure.text.minimessage.MiniMessage.miniMessage().deserialize(guide.text(topic.labelKey))
            controller.search(player, plain(label))
            paper.performTicks(2)
            click("result_ae_${topic.id}")
            assertEquals("help.enchantments.topic.${topic.id}", screen.id)
            assertTrue(body().contains("Как использовать"), topic.id)
            assertFalse(screen.buttons.single { it.id.value == "sources" }.closeDialogBeforeAction)
        }
    }

    @Test
    fun `special item pagination separates each orb and tracker and preserves the visited page`() {
        open()
        click("special")
        click("next")
        val secondPage = screen.buttons.filter { it.id.value.startsWith("item_") }.map { it.id.value }
        assertEquals(9, secondPage.size)
        assertTrue(secondPage.containsAll(listOf("item_orb_weapon", "item_orb_armor", "item_orb_tool", "item_slotincreaser", "item_fishtrak")))
        click("item_slotincreaser")
        assertTrue(body().contains("Осталось зачарований"))
        click("back")
        assertEquals(secondPage, screen.buttons.filter { it.id.value.startsWith("item_") }.map { it.id.value })
    }

    @Test
    fun `source refresh uses new data and late completion cannot replace another page`() {
        controller.search(player, "увеличитель слотов")
        paper.performTicks(2)
        click("result_ae_slotincreaser")
        val pending = CompletableFuture<HelpCenterEnchantmentAcquisition>()
        sourceResult = pending
        click("sources")
        assertTrue(body().contains("Проверяем"))
        click("back")
        pending.complete(sourceReport("3,31"))
        paper.performTicks(2)
        assertEquals("help.enchantments.topic.slotincreaser", screen.id)

        sourceResult = CompletableFuture.completedFuture(sourceReport("7,25"))
        click("sources")
        assertEquals("help.enchantments.sources.slotincreaser", screen.id)
        assertTrue(body().contains("7,25%"), body())
        assertFalse(body().contains("3,31%"))
        assertTrue(body().contains("4,90%"))
        assertFalse(screen.buttons.single { it.id.value == "item_ae_lootbox" }.closeDialogBeforeAction)
        click("item_ae_lootbox")
        assertEquals("help.enchantments.topic.ae_lootbox", screen.id)
        click("back")
        assertEquals("help.enchantments.sources.slotincreaser", screen.id)
    }

    @Test
    fun `closing the controller discards an outstanding acquisition result`() {
        controller.search(player, "увеличитель слотов")
        paper.performTicks(2)
        click("result_ae_slotincreaser")
        sourceResult = CompletableFuture()
        click("sources")
        val beforeClose = screenDisplays
        controller.close()
        sourceResult.complete(sourceReport("55"))
        paper.performTicks(2)
        assertEquals(beforeClose, screenDisplays)
    }

    @Test
    fun `recycling inventory bridge returns to the enchantment overview`() {
        open()
        click("recycle")
        assertEquals(listOf("tinkerer"), executed)
        val beforeReturn = screenDisplays

        player.closeInventory()
        paper.performTicks(2)

        assertEquals(beforeReturn + 1, screenDisplays)
        assertEquals("help.enchantments", screen.id)
    }

    private fun open() = controller.open(player, HelpCenterPage.ENCHANTMENTS)

    private fun click(id: String, input: String = "") {
        val context = mockk<PaperDialogClickContext>()
        every { context.text(any()) } returns input
        (screen.buttons + listOfNotNull(screen.exitButton)).single { it.id.value == id }.onClick.handle(context)
        paper.performTicks(2)
    }

    private fun body() = screen.body.joinToString("\n") { plain(it.text) }

    private fun plain(component: Component) = PlainTextComponentSerializer.plainText().serialize(component)

    private fun sourceReport(chance: String) = HelpCenterEnchantmentAcquisition(
        facts = listOf(
            HelpCenterEnchantmentSourceFact("scope", mapOf("server" to "survival")),
            HelpCenterEnchantmentSourceFact("pool-item", mapOf("box" to "ae_lootbox", "pool" to "ae", "per_roll" to chance, "per_box" to "4,90", "amount" to "1")),
            HelpCenterEnchantmentSourceFact("slot-settings", mapOf("base" to "7", "max" to "11", "increment" to "1")),
        ),
        relatedTopics = listOf("ae_lootbox"),
    )

    private fun enchantment(id: String, name: String, material: String, description: String) =
        HelpCenterEnchantmentsCatalog.fromApiData(
            id = id,
            displayLore = listOf(name),
            description = description,
            maxLevelDescription = description,
            materials = setOf(material),
            group = "SIMPLE",
            maxLevel = 3,
        )
}
