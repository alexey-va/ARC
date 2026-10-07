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

class HelpCenterEnchantmentsControllerTest {
    private lateinit var paper: MockBukkitTestRuntime
    private lateinit var player: Player
    private lateinit var controller: HelpCenterController
    private lateinit var screen: PaperDialogScreen
    private lateinit var inventoryReturn: HelpCenterInventoryReturnRuntime
    private val executed = mutableListOf<String>()
    private val directory = Files.createTempDirectory("enchantments-controller")

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
            showDialog = { _, value -> screen = value },
            closeDialog = {},
            enchantmentsGuide = HelpCenterEnchantmentsGuideConfig.load(directory).snapshot(),
            enchantmentsCatalog = { fixtureCatalog },
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
        assertEquals(10, screen.buttons.size)

        click("item_white")
        assertEquals("help.enchantments.special.item", screen.id)
        assertTrue(body().contains("Что делает"))
        assertTrue(body().contains("Как использовать"))
        assertTrue(body().contains("ЛКМ"))

        click("back")
        assertEquals("help.enchantments.special", screen.id)
        assertEquals(10, screen.buttons.size)
    }

    @Test
    fun `acquisition inventory bridge returns to the acquisition screen`() {
        open()
        click("acquisition")
        assertEquals("help.enchantments.acquisition", screen.id)
        click("enchanter")
        assertEquals(listOf("enchanter"), executed)

        player.closeInventory()
        paper.performTicks(2)

        assertEquals("help.enchantments.acquisition", screen.id)
    }

    private fun open() = controller.open(player, HelpCenterPage.ENCHANTMENTS)

    private fun click(id: String, input: String = "") {
        val context = mockk<PaperDialogClickContext>()
        every { context.text(any()) } returns input
        (screen.buttons + listOfNotNull(screen.exitButton)).single { it.id.value == id }.onClick.handle(context)
    }

    private fun body() = screen.body.joinToString("\n") { plain(it.text) }

    private fun plain(component: Component) = PlainTextComponentSerializer.plainText().serialize(component)

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
