package ru.arc.helpcenter

import io.mockk.every
import io.mockk.mockk
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import org.bukkit.entity.Player
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
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
import java.nio.file.Path

class HelpCenterEnchantmentsJourneyTest {
    private lateinit var paper: MockBukkitTestRuntime
    private lateinit var player: Player
    private lateinit var navigation: HelpCenterNavigation
    private lateinit var controller: HelpCenterEnchantmentsController
    private lateinit var screen: PaperDialogScreen
    private lateinit var directory: Path
    private var canPurchase = true
    private var fixtureGroup = "UNIQUE"
    private var purchaseReturn: (() -> Unit)? = null
    private val executed = mutableListOf<String>()
    private val purchases = mutableListOf<String>()

    @BeforeEach
    fun setup() {
        fixtureGroup = "UNIQUE"
        paper = MockBukkitTestRuntime.open()
        val plugin = paper.createSimplePlugin("EnchantmentsJourneyTest")
        Tasks.install(BukkitTaskScheduler(plugin))
        player = paper.addPlayer("Viewer")
        ConfigManager.clear()
        directory = Files.createTempDirectory("enchantments-journey")
        navigation = HelpCenterNavigation(plugin)

        controller = HelpCenterEnchantmentsController(
            settings = HelpCenterConfig.load(directory).snapshot(),
            guide = HelpCenterEnchantmentsGuideConfig.load(directory).snapshot(),
            navigation = navigation,
            show = { _, value -> screen = value },
            executeInventory = { _, command -> executed += command; true },
            catalog = { fixtureCatalog(fixtureGroup) },
            canPurchase = { _, _ -> canPurchase },
            openPurchase = { _, id, returnTo -> purchases += id; purchaseReturn = returnTo; true },
        )
    }

    @AfterEach
    fun cleanup() {
        navigation.close()
        Tasks.reset()
        ConfigManager.clear()
        paper.close()
        directory.toFile().deleteRecursively()
    }

    @Test
    fun `overview explains book use and opens both non-purchase services`() {
        open()

        assertEquals(
            listOf("equipment", "search", "special", "acquisition", "recycle", "alchemy"),
            screen.buttons.map { it.id.value },
        )
        assertEquals(2, screen.columns)
        assertTrue(body().contains("AdvancedEnchantments"))
        assertTrue(body().contains("40–80%"))
        assertTrue(body().contains("100%"))
        assertTrue(body().contains("Белый свиток"))

        click("recycle")
        click("alchemy")
        assertEquals(listOf("tinkerer", "alchemist"), executed)
    }

    @Test
    fun `detail table shows catalogue values and selected-enchantment purchase returns to detail`() {
        openDetail()

        assertEquals("help.enchantments.details", screen.id)
        assertTrue(body().contains("Уникальная"))
        assertTrue(body().contains("4"))
        assertTrue(body().contains("Мечи, Трезубцы"))
        // DialogTables wraps long values across rows at the configured pixel width.
        listOf("Зачарователь", "спавне", "охота", "рыбалка").forEach { assertTrue(body().contains(it), body()) }
        assertTrue(body().contains("Эффект на четвёртом уровне"))
        assertTrue(body().contains("книга расходуется"))
        assertEquals(listOf("purchase_book"), screen.buttons.map { it.id.value })

        click("purchase_book")
        assertEquals(listOf("aquatic_grip"), purchases)
        assertNotNull(purchaseReturn)
        purchaseReturn?.invoke()
        assertEquals("help.enchantments.details", screen.id)
        assertEquals(listOf("purchase_book"), screen.buttons.map { it.id.value })
    }

    @Test
    fun `purchase actions are absent outside spawn and stale random-shop action is rechecked`() {
        canPurchase = false
        open()
        click("acquisition")
        assertTrue(screen.buttons.isEmpty())
        assertTrue(body().contains("0,2%"))
        assertTrue(body().contains("0,8%"))
        assertTrue(body().contains("0,5%"))
        assertTrue(body().contains("1,5%"))
        assertTrue(body().contains("одного результата"))

        openDetail()
        assertTrue(body().contains("Купить книгу можно у зачарователя на спавне"))
        assertFalse(screen.buttons.any { it.id.value == "purchase_book" })

        canPurchase = true
        open()
        click("acquisition")
        assertTrue(screen.buttons.any { it.id.value == "enchanter" })
        canPurchase = false
        click("enchanter")
        assertTrue(executed.isEmpty())
        assertTrue(screen.buttons.isEmpty())
    }

    @Test
    fun `fabled source remains spawn only`() {
        fixtureGroup = "FABLED"
        openDetail()

        assertTrue(body().contains("Только зачарователь на спавне"))
        assertFalse(body().contains("охота · рыбалка"))
    }

    private fun open() = controller.open(player) {}

    private fun openDetail() {
        open()
        click("equipment")
        click("equip_${HelpCenterEnchantmentsEquipment.SWORD.ordinal}")
        click("enchant_0")
    }

    private fun click(id: String, input: String = "") {
        val context = mockk<PaperDialogClickContext>()
        every { context.text(any()) } returns input
        screen.buttons.single { it.id.value == id }.onClick.handle(context)
    }

    private fun body() = screen.body.joinToString("\n") { plain(it.text) }

    private fun plain(component: Component) = PlainTextComponentSerializer.plainText().serialize(component)

    private fun fixtureCatalog(group: String = "UNIQUE"): HelpCenterEnchantmentsCatalog {
        val entry = HelpCenterEnchantmentsCatalog.fromApiData(
            id = "aquatic_grip",
            displayLore = listOf("Акватический хват IV"),
            description = "Улучшает дыхание под водой.",
            maxLevelDescription = "Эффект на четвёртом уровне: дыхание заметно дольше.",
            materials = setOf("DIAMOND_SWORD", "TRIDENT"),
            group = group,
            maxLevel = 4,
            baseDisplayName = "<#d6a5ff>%group-color%Акватический хват",
        )
        return HelpCenterEnchantmentsCatalog(available = true, entries = listOf(entry))
    }
}
