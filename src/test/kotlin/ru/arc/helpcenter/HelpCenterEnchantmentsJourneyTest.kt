package ru.arc.helpcenter

import com.google.gson.GsonBuilder
import com.google.gson.JsonParser
import io.mockk.every
import io.mockk.mockk
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer
import net.kyori.adventure.text.serializer.gson.GsonComponentSerializer
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
import java.util.concurrent.CompletableFuture

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
            acquisition = { CompletableFuture.completedFuture(HelpCenterEnchantmentAcquisition(listOf(
                HelpCenterEnchantmentSourceFact("natural-books", mapOf("kill" to "0,2", "fishing" to "0,5", "worlds" to "Survival")),
                HelpCenterEnchantmentSourceFact("natural-dust", mapOf("kill" to "0,8", "fishing" to "1,5", "worlds" to "Survival", "min" to "1", "max" to "15")),
            ))) },
        )
    }

    @AfterEach
    fun cleanup() {
        controller.close()
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
        assertTrue(body().contains("AE"))
        assertTrue(body().contains("Успех и риск разрушения указаны на книге"))
        assertTrue(body().contains("белый свиток защищает предмет при неудаче"))

        click("recycle")
        click("alchemy")
        assertEquals(listOf("tinkerer", "alchemist"), executed)
    }

    @Test
    fun `detail table shows catalogue values and selected-enchantment purchase returns to detail`() {
        openDetail()

        assertEquals("help.enchantments.details", screen.id)
        assertTrue(body().contains("Необычная"), body())
        assertTrue(body().contains("4"))
        assertTrue(body().contains("Мечи, Трезубцы"))
        assertTrue(body().contains("Эффект на четвёртом уровне"))
        assertTrue(body().contains("книга расходуется"))
        assertEquals(listOf("purchase_book", "acquisition"), screen.buttons.map { it.id.value })

        click("purchase_book")
        assertEquals(listOf("aquatic_grip"), purchases)
        assertNotNull(purchaseReturn)
        purchaseReturn?.invoke()
        assertEquals("help.enchantments.details", screen.id)
        assertEquals(listOf("purchase_book", "acquisition"), screen.buttons.map { it.id.value })
    }

    @Test
    fun `purchase actions are absent outside spawn and stale random-shop action is rechecked`() {
        canPurchase = false
        open()
        click("acquisition")
        assertFalse(screen.buttons.any { it.id.value == "enchanter" })
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
        assertFalse(screen.buttons.any { it.id.value == "enchanter" })
    }

    @Test
    fun `fabled detail links to current acquisition instead of claiming an exclusive source`() {
        fixtureGroup = "FABLED"
        openDetail()

        assertFalse(body().contains("Только зачарователь на спавне"))
        assertTrue(screen.buttons.any { it.id.value == "acquisition" })
        click("acquisition")
        assertEquals("help.enchantments.acquisition", screen.id)
    }

    @Test
    fun `tinkerer source leads before conversion parameters and reuses the inventory service`() {
        controller.close()
        controller = HelpCenterEnchantmentsController(
            settings = HelpCenterConfig.load(directory).snapshot(),
            guide = HelpCenterEnchantmentsGuideConfig.load(directory).snapshot(),
            navigation = navigation,
            show = { _, value -> screen = value },
            executeInventory = { _, command -> executed += command; true },
            acquisition = { CompletableFuture.completedFuture(HelpCenterEnchantmentAcquisition(listOf(
                HelpCenterEnchantmentSourceFact("unavailable", mapOf("source" to "external-sources")),
                HelpCenterEnchantmentSourceFact("secret-result", mapOf("group" to "SIMPLE", "magic" to "22", "mystery" to "78", "min" to "1", "max" to "14")),
                HelpCenterEnchantmentSourceFact("tinkerer-source", mapOf("input" to "1", "output" to "1")),
                HelpCenterEnchantmentSourceFact("denizen-monsters", mapOf("base" to "2", "cap" to "5", "health" to "20", "end" to "0,2", "spawner" to "0,15", "world" to "spawn_end", "cooldown" to "1s")),
            ))) },
        )
        controller.openTopic(player, HelpCenterEnchantmentGuideTopic.SECRET_DUST) {}
        click("sources")
        assertTrue(body().indexOf("Тинкер") < body().indexOf("Магическая пыль"), body())
        assertTrue(body().contains("один расчёт за 1 с."), body())
        assertFalse(body().contains("1s с"))
        click("tinkerer")
        assertEquals(listOf("tinkerer"), executed)
    }

    @Test
    fun `representative long usage and dynamic source cards export their actual decorated components`() {
        controller.close()
        controller = HelpCenterEnchantmentsController(
            settings = HelpCenterConfig.load(directory).snapshot().copy(decorationFont = "arc:dialog_ui"),
            guide = HelpCenterEnchantmentsGuideConfig.load(directory).snapshot(),
            navigation = navigation,
            show = { _, value -> screen = value },
            executeInventory = { _, _ -> true },
            catalog = { HelpCenterEnchantmentsCatalog.unavailable() },
            acquisition = { topicId -> CompletableFuture.completedFuture(if (topicId == "overview") HelpCenterEnchantmentAcquisition(listOf(
                HelpCenterEnchantmentSourceFact("scope", mapOf("server" to "survival")),
                HelpCenterEnchantmentSourceFact("crate-source", mapOf("source" to "case_daily", "crate" to "Ежедневный тайник", "item" to "ae_lootbox", "chance" to "9,5", "amount" to "1")),
                HelpCenterEnchantmentSourceFact("crate-source", mapOf("source" to "case_daily", "crate" to "Ежедневный тайник", "item" to "ae_lootbox_enchanted", "chance" to "5,7", "amount" to "1")),
                HelpCenterEnchantmentSourceFact("quest-source", mapOf("source" to "daily_fishing", "quest" to "Улов дня", "item" to "enchant_supply", "amount" to "1")),
            )) else HelpCenterEnchantmentAcquisition(
                facts = listOf(
                    HelpCenterEnchantmentSourceFact("scope", mapOf("server" to "survival")),
                    HelpCenterEnchantmentSourceFact("pool-item", mapOf("box" to "ae_lootbox", "pool" to "ae", "per_roll" to "3,31", "per_box" to "4,90", "amount" to "1")),
                    HelpCenterEnchantmentSourceFact("pool-item-value", mapOf("box" to "ae_lootbox", "item" to "slotincreaser", "valueKind" to "none", "min" to "", "max" to "", "group" to "random", "level" to "")),
                    HelpCenterEnchantmentSourceFact("slot-settings", mapOf("base" to "7", "max" to "11", "increment" to "1")),
                    HelpCenterEnchantmentSourceFact("unavailable", mapOf("source" to "ae-native")),
                ),
                relatedTopics = listOf("ae_lootbox"),
            )) },
        )
        val screens = linkedMapOf<String, PaperDialogScreen>()
        controller.openTopic(player, HelpCenterEnchantmentGuideTopic.TOOL_ORB) {}
        screens["ae-orb-usage"] = screen
        assertFalse(screen.buttons.single { it.id.value == "sources" }.closeDialogBeforeAction)
        controller.openTopic(player, HelpCenterEnchantmentGuideTopic.SLOT_INCREASER) {}
        screens["ae-slot-usage"] = screen
        click("sources")
        screens["ae-slot-sources"] = screen
        assertTrue(body().contains("3,31%"), body())
        assertTrue(body().contains("11"), body())
        click("next")
        screens["ae-sources-last"] = screen
        assertTrue(screen.buttons.any { it.id.value == "previous" })
        assertFalse(screen.buttons.any { it.id.value == "next" })

        controller.open(player) {}
        click("acquisition")
        screens["ae-acquisition"] = screen
        assertTrue(body().contains("Таинственная"), body())
        assertTrue(body().contains("Зачарованная"), body())
        assertTrue(body().contains("Припасы чародея"), body())
        assertTrue(body().contains("9,5%"), body())
        assertTrue(body().contains("5,7%"), body())

        val legacy = LegacyComponentSerializer.builder().character('&').hexColors().build()
        val gson = GsonComponentSerializer.gson()
        fun export(serialize: (Component) -> Any): Map<String, Any> = screens.mapValues { (_, view) -> mapOf(
            "title" to serialize(view.title), "columns" to view.columns,
            "body" to view.body.map { mapOf("text" to serialize(it.text), "width" to it.width) },
            "inputs" to emptyList<Any>(),
            "buttons" to view.buttons.map { mapOf("id" to it.id.value, "text" to serialize(it.label), "tooltip" to serialize(it.tooltip), "width" to it.width) },
            "exit" to view.exitButton?.let { mapOf("text" to serialize(it.label), "width" to it.width) },
        ) }
        val output = Path.of("build/reports/ae-help-preview")
        Files.createDirectories(output)
        val json = GsonBuilder().disableHtmlEscaping().setPrettyPrinting().create()
        Files.writeString(output.resolve("content.json"), json.toJson(export(legacy::serialize)))
        Files.writeString(output.resolve("components.json"), json.toJson(export { JsonParser.parseString(gson.serialize(it)) }))
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
        paper.performTicks(2)
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
