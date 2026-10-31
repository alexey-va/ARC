package ru.arc.helpcenter

import com.google.gson.GsonBuilder
import com.google.gson.JsonParser
import io.mockk.every
import io.mockk.mockk
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer
import net.kyori.adventure.text.serializer.gson.GsonComponentSerializer
import org.bukkit.Material
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
import ru.arc.hooks.HookRegistry
import ru.arc.hooks.ztranslator.TranslatorHook
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
    private var previousTranslator: TranslatorHook? = null
    private val executed = mutableListOf<String>()
    private val purchases = mutableListOf<String>()

    @BeforeEach
    fun setup() {
        fixtureGroup = "UNIQUE"
        previousTranslator = HookRegistry.translatorHook
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
        HookRegistry.translatorHook = previousTranslator
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
            listOf("equipment", "search", "special", "acquisition", "enchanter", "recycle", "alchemy"),
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
        assertTrue(screen.buttons.any { it.id.value == "enchanter" })
        click("acquisition")
        assertFalse(screen.buttons.any { it.id.value == "enchanter" })
        assertEquals(listOf("boxes"), screen.buttons.map { it.id.value })
        click("back")
        canPurchase = false
        click("enchanter")
        assertTrue(executed.isEmpty())
        assertFalse(screen.buttons.any { it.id.value == "enchanter" })

        canPurchase = true
        open()
        click("enchanter")
        assertEquals(listOf("enchanter"), executed)
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
        assertTrue(body().contains("Магическая пыль"), body())
        assertTrue(body().contains("один расчёт за 1 с."), body())
        assertFalse(body().contains("1s с"))
        assertFalse(body().contains("недоступны"), body())
        click("next")
        assertTrue(body().contains("недоступны"), body())
        click("previous")
        click("tinkerer")
        assertEquals(listOf("tinkerer"), executed)
    }

    @Test
    fun `representative long usage and dynamic source cards export their actual decorated components`() {
        var discoveryOverview = false
        canPurchase = true
        HookRegistry.translatorHook = mockk<TranslatorHook>().also { translator ->
            every { translator.translate(any<Material>()) } answers {
                (enchantmentDiscoveryMiningNames + enchantmentDiscoveryCropNames).getValue(firstArg())
            }
        }
        controller.close()
        controller = HelpCenterEnchantmentsController(
            settings = HelpCenterConfig.load(directory).snapshot().copy(decorationFont = "arc:dialog_ui"),
            guide = HelpCenterEnchantmentsGuideConfig.load(directory).snapshot(),
            navigation = navigation,
            show = { _, value -> screen = value },
            executeInventory = { _, _ -> true },
            catalog = { fixtureCatalog() },
            canPurchase = { _, _ -> canPurchase },
            acquisition = { topicId -> CompletableFuture.completedFuture(if (topicId == "overview" && discoveryOverview) HelpCenterEnchantmentAcquisition(
                enchantmentDiscoveryPreviewFacts(),
            ) else if (topicId == "overview") HelpCenterEnchantmentAcquisition(listOf(
                HelpCenterEnchantmentSourceFact("scope", mapOf("server" to "survival")),
                HelpCenterEnchantmentSourceFact("crate-source", mapOf("source" to "case_daily", "crate" to "Ежедневный тайник", "item" to "ae_lootbox", "chance" to "9,5", "amount" to "1")),
                HelpCenterEnchantmentSourceFact("crate-source", mapOf("source" to "case_daily", "crate" to "Ежедневный тайник", "item" to "ae_lootbox_enchanted", "chance" to "5,7", "amount" to "1")),
                HelpCenterEnchantmentSourceFact("quest-source", mapOf("source" to "daily_fishing", "quest" to "Улов дня", "item" to "enchant_supply", "amount" to "1")),
            )) else if (topicId == "ae_lootbox") HelpCenterEnchantmentAcquisition(
                enchantmentDiscoveryPreviewFacts(),
            ) else HelpCenterEnchantmentAcquisition(
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
        assertFalse(body().contains("недоступны"), body())
        click("next")
        screens["ae-sources-last"] = screen
        assertTrue(body().contains("недоступны"), body())
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

        discoveryOverview = true
        controller.open(player) {}
        screens["ae-overview-with-enchanter"] = screen
        assertEquals(7, screen.buttons.size)
        assertTrue(screen.buttons.any { it.id.value == "enchanter" })
        click("acquisition")
        screens["ae-discovery-overview"] = screen
        assertTrue(body().contains("Исследование мира · добыча киркой · сбор урожая"), body())
        assertEquals(listOf("boxes", "next"), screen.buttons.map { it.id.value })
        click("boxes")
        screens["ae-boxes-catalog"] = screen
        assertEquals("help.enchantments.boxes", screen.id)
        assertEquals(5, screen.buttons.size)
        assertEquals(HelpCenterEnchantmentGuideTopic.entries.filter { it.container }.map { "item_${it.id}" }, screen.buttons.map { it.id.value })
        click("back")
        assertEquals("help.enchantments.acquisition", screen.id)
        assertTrue(body().contains("Исследование мира · добыча киркой · сбор урожая"), body())

        controller.openTopic(player, HelpCenterEnchantmentGuideTopic.BASE_BOX) {}
        click("sources")
        screens["ae-discovery-summary"] = screen
        assertTrue(body().contains("Исследование мира · добыча киркой · сбор урожая"), body())
        assertTrue(body().contains("Тайники на поверхности"), body())
        assertTrue(body().contains("Находки при добыче"), body())
        assertFalse(body().contains("Шкатулки за урожай"))

        click("next")
        screens["ae-discovery-farming"] = screen
        assertTrue(body().contains("Шкатулки за урожай"), body())
        enchantmentDiscoveryCropNames.values.forEach { assertTrue(body().contains(it), body()) }
        assertFalse(body().contains("Добыча киркой · подходящие блоки"))

        click("next")
        screens["ae-discovery-mining-continuation"] = screen
        assertTrue(body().contains("Добыча киркой · подходящие блоки"), body())
        assertFalse(body().contains("Шкатулки за урожай"))
        assertTrue(screen.buttons.any { it.id.value == "previous" })
        assertTrue(screen.buttons.any { it.id.value == "next" })

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
        (screen.buttons + listOfNotNull(screen.exitButton)).single { it.id.value == id }.onClick.handle(context)
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

/** Mirrors the configured 18 mining materials and six crops, in the source builder's four-item groups. */
internal val enchantmentDiscoveryMiningNames = linkedMapOf(
    Material.STONE to "Камень",
    Material.DEEPSLATE to "Глубинный сланец",
    Material.COAL_ORE to "Угольная руда",
    Material.DEEPSLATE_COAL_ORE to "Угленосный глубинный сланец",
    Material.IRON_ORE to "Железная руда",
    Material.DEEPSLATE_IRON_ORE to "Железоносный глубинный сланец",
    Material.COPPER_ORE to "Медная руда",
    Material.DEEPSLATE_COPPER_ORE to "Меденосный глубинный сланец",
    Material.GOLD_ORE to "Золотая руда",
    Material.DEEPSLATE_GOLD_ORE to "Золотоносный глубинный сланец",
    Material.REDSTONE_ORE to "Редстоуновая руда",
    Material.DEEPSLATE_REDSTONE_ORE to "Редстоуноносный глубинный сланец",
    Material.LAPIS_ORE to "Лазуритовая руда",
    Material.DEEPSLATE_LAPIS_ORE to "Лазуритоносный глубинный сланец",
    Material.DIAMOND_ORE to "Алмазная руда",
    Material.DEEPSLATE_DIAMOND_ORE to "Алмазоносный глубинный сланец",
    Material.EMERALD_ORE to "Изумрудная руда",
    Material.DEEPSLATE_EMERALD_ORE to "Изумрудоносный глубинный сланец",
)

internal val enchantmentDiscoveryCropNames = linkedMapOf(
    Material.WHEAT to "Ростки пшеницы",
    Material.CARROTS to "Морковь",
    Material.POTATOES to "Картофель",
    Material.BEETROOTS to "Свёкла",
    Material.COCOA to "Какао",
    Material.NETHER_WART to "Незерский нарост",
)

internal fun enchantmentDiscoveryPreviewFacts(): List<HelpCenterEnchantmentSourceFact> = buildList {
    add(HelpCenterEnchantmentSourceFact("scope", mapOf("server" to "spawn")))
    add(HelpCenterEnchantmentSourceFact("mining-cache", mapOf("chance" to "0,05", "worlds" to "mining, survival, vanilla")))
    enchantmentDiscoveryMiningNames.keys.map { it.name }.sorted().chunked(4).forEach {
        add(HelpCenterEnchantmentSourceFact("mining-cache-materials", mapOf("materials" to it.joinToString(","))))
    }
    add(HelpCenterEnchantmentSourceFact("world-cache", mapOf("chance" to "0,78125", "one_in" to "128", "worlds" to "survival, vanilla")))
    add(HelpCenterEnchantmentSourceFact("farming-cache", mapOf(
        "chance" to "0,05", "worlds" to "survival, survival_nether, vanilla, vanilla_nether, world, world_nether", "cooldown" to "1d",
    )))
    enchantmentDiscoveryCropNames.keys.map { it.name }.sorted().chunked(4).forEach {
        add(HelpCenterEnchantmentSourceFact("farming-cache-materials", mapOf("materials" to it.joinToString(","))))
    }
}
