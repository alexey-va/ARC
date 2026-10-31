package ru.arc.helpcenter

import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import org.bukkit.Bukkit
import org.bukkit.Material
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
import ru.arc.hooks.HookRegistry
import ru.arc.hooks.ztranslator.TranslatorHook
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
    fun `source overview opens all five boxes through one catalogue and restores the original source page`() {
        sourceResult = CompletableFuture.completedFuture(HelpCenterEnchantmentAcquisition(enchantmentDiscoveryPreviewFacts()))
        open()
        click("acquisition")

        assertEquals(listOf("boxes", "next"), screen.buttons.map { it.id.value })
        assertEquals("Шкатулки", plain(screen.buttons.single { it.id.value == "boxes" }.label))
        assertFalse(screen.buttons.single { it.id.value == "boxes" }.closeDialogBeforeAction)
        click("next")
        val originalBody = body()
        val originalButtons = screen.buttons.map { it.id.value }
        assertTrue(originalBody.contains("Шкатулки за урожай"))
        click("boxes")

        val expected = listOf("ae_lootbox", "ae_lootbox_enchanted", "ae_lootbox_master", "enchant_supply", "enchant_boss_supply")
        assertEquals("help.enchantments.boxes", screen.id)
        assertEquals(expected.map { "item_$it" }, screen.buttons.map { it.id.value })
        assertEquals(2, screen.columns)
        expected.forEach { topic ->
            click("item_$topic")
            assertEquals("help.enchantments.topic.$topic", screen.id)
            assertTrue(body().contains("Как использовать"))
            click("sources")
            assertEquals("help.enchantments.sources.$topic", screen.id)
            click("back")
            assertEquals("help.enchantments.topic.$topic", screen.id)
            click("back")
            assertEquals("help.enchantments.boxes", screen.id)
            assertEquals(5, screen.buttons.size)
        }

        click("back")
        assertEquals("help.enchantments.acquisition", screen.id)
        assertEquals(originalBody, body())
        assertEquals(originalButtons, screen.buttons.map { it.id.value })
        click("back")
        assertEquals("help.enchantments", screen.id)
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
    fun `survival discovery sources precede box contents and preserve the current server scope`() {
        val previousTranslator = HookRegistry.translatorHook
        HookRegistry.translatorHook = mockk<TranslatorHook>().also { translator ->
            every { translator.translate(Material.STONE) } returns "Камень"
            every { translator.translate(Material.DEEPSLATE) } returns "Глубинный сланец"
        }
        try {
            sourceResult = CompletableFuture.completedFuture(HelpCenterEnchantmentAcquisition(listOf(
                HelpCenterEnchantmentSourceFact("box-content", mapOf("item" to "slotincreaser", "per_roll" to "3,31", "amount" to "1")),
                HelpCenterEnchantmentSourceFact("scope", mapOf("server" to "spawn")),
                HelpCenterEnchantmentSourceFact("box-rolls", mapOf("min" to "1", "max" to "2", "pool" to "ae")),
                HelpCenterEnchantmentSourceFact("mining-cache-materials", mapOf("materials" to "STONE,DEEPSLATE")),
                HelpCenterEnchantmentSourceFact("mining-cache", mapOf("chance" to "0,02", "worlds" to "world")),
                HelpCenterEnchantmentSourceFact("world-cache", mapOf("chance" to "0,78125", "one_in" to "128", "worlds" to "world")),
            )))
            open()
            click("acquisition")

            body().contains("Вы на сервере: Спавн") shouldBe true
            body().contains("Исследование мира · добыча киркой") shouldBe true
            body().contains("сбор урожая") shouldBe false
            body().contains("Тайники на поверхности · Выживание") shouldBe true
            body().contains("Находки при добыче · Выживание") shouldBe true
            body().contains("Шанс выбрать чанк для проверки: 0,78125% (1 из 128)") shouldBe true
            body().contains("не гарантия сундука") shouldBe true
            body().contains("не возрождается") shouldBe true
            body().contains("киркой шанс шкатулки — 0,02%") shouldBe true
            body().contains("Одна находка на чанк для всех игроков") shouldBe true
            body().contains("«Удача» не увеличивает шанс") shouldBe true
            body().contains("Обычные блоки, поставленные игроками, исключаются") shouldBe true
            body().contains("не больше одного шанса за тик") shouldBe true
            body().contains("не подтверждает природное происхождение") shouldBe false
            body().contains("Добыча киркой · подходящие блоки") shouldBe false
            body().contains("Шанс за одну награду") shouldBe false
            body().contains("Награды на сервере") shouldBe false

            click("next")
            body().contains("Добыча киркой · подходящие блоки: Камень, Глубинный сланец") shouldBe true
            body().contains("1–2 независимых наград") shouldBe true
            body().contains("Шанс за одну награду: 3,31%") shouldBe true
        } finally {
            HookRegistry.translatorHook = previousTranslator
        }
    }

    @Test
    fun `discovery index keeps farming with all six crops ahead of eighteen mining materials`() {
        val previousTranslator = HookRegistry.translatorHook
        HookRegistry.translatorHook = mockk<TranslatorHook>().also { translator ->
            every { translator.translate(any<Material>()) } answers {
                (enchantmentDiscoveryMiningNames + enchantmentDiscoveryCropNames).getValue(firstArg())
            }
        }
        try {
            enchantmentDiscoveryMiningNames.size shouldBe 18
            enchantmentDiscoveryCropNames.size shouldBe 6
            sourceResult = CompletableFuture.completedFuture(HelpCenterEnchantmentAcquisition(listOf(
                HelpCenterEnchantmentSourceFact("unavailable", mapOf("source" to "ae-native")),
                HelpCenterEnchantmentSourceFact("box-content", mapOf("item" to "slotincreaser", "per_roll" to "3,31", "amount" to "1")),
            ) + enchantmentDiscoveryPreviewFacts()))
            open()
            click("acquisition")

            body().contains("Исследование мира · добыча киркой · сбор урожая") shouldBe true
            body().contains("Тайники на поверхности · Выживание") shouldBe true
            body().contains("Находки при добыче · Выживание") shouldBe true
            (body().indexOf("Исследование мира") < body().indexOf("Тайники на поверхности")) shouldBe true
            body().contains("Шкатулки за урожай") shouldBe false
            body().contains("подходящие блоки") shouldBe false
            body().contains("Шанс за одну награду") shouldBe false

            click("next")
            val harvest = body()
            harvest.contains("Вы на сервере: Спавн") shouldBe true
            harvest.contains("Шкатулки за урожай · Выживание") shouldBe true
            harvest.contains("При ручном сборе полностью выросших культур") shouldBe true
            harvest.contains("шанс шкатулки на подходящую попытку — 0,05%") shouldBe true
            harvest.contains("Миры: survival, survival_nether, vanilla, vanilla_nether, world, world_nether") shouldBe true
            harvest.contains("У игрока не больше одного шанса за тик") shouldBe true
            harvest.contains("новый шанс в этом чанке появится через 1 дн.") shouldBe true
            harvest.contains("ожидание общее для всех игроков") shouldBe true
            harvest.contains("Костная мука разрешена") shouldBe true
            harvest.contains("Сбор урожая · подходящие культуры: Свёкла, Морковь, Какао, Незерский нарост") shouldBe true
            harvest.contains("Сбор урожая · подходящие культуры: Картофель, Ростки пшеницы") shouldBe true
            (harvest.indexOf("Шкатулки за урожай") < harvest.indexOf("Сбор урожая · подходящие культуры")) shouldBe true
            harvest.contains("Находки при добыче") shouldBe false
            harvest.contains("подходящие блоки") shouldBe false
            harvest.contains("Исследование мира") shouldBe false
            harvest.contains("Одна находка на чанк для всех игроков") shouldBe false
            harvest.contains("Шанс за одну награду") shouldBe false

            click("next")
            val firstOres = body()
            (firstOres.split("Добыча киркой · подходящие блоки").size - 1) shouldBe 3
            firstOres.contains("Шкатулки за урожай") shouldBe false
            firstOres.contains("Исследование мира") shouldBe false
            firstOres.contains("Шанс за одну награду") shouldBe false

            click("next")
            val lastOres = body()
            (lastOres.split("Добыча киркой · подходящие блоки").size - 1) shouldBe 2
            enchantmentDiscoveryMiningNames.values.forEach { (firstOres + lastOres).contains(it) shouldBe true }
            lastOres.contains("Шанс за одну награду: 3,31%") shouldBe true
            (lastOres.lastIndexOf("Добыча киркой") < lastOres.indexOf("Шанс за одну награду")) shouldBe true
            lastOres.contains("недоступны") shouldBe false

            click("next")
            body().contains("недоступны") shouldBe true
            body().contains("Исследование мира") shouldBe false
        } finally {
            HookRegistry.translatorHook = previousTranslator
        }
    }

    @Test
    fun `discovery index includes only present sources and stays absent for unknown or disabled discovery`() {
        for (facts in listOf(emptyList(), listOf(HelpCenterEnchantmentSourceFact("unavailable", mapOf("source" to "survival-sources"))))) {
            sourceResult = CompletableFuture.completedFuture(HelpCenterEnchantmentAcquisition(facts))
            open()
            click("acquisition")

            body().contains("Исследование мира") shouldBe false
            body().contains("Добыча киркой") shouldBe false
            body().contains("Сбор урожая") shouldBe false
        }

        sourceResult = CompletableFuture.completedFuture(HelpCenterEnchantmentAcquisition(listOf(
            HelpCenterEnchantmentSourceFact("mining-cache", mapOf("chance" to "0,05", "worlds" to "survival")),
        )))
        open()
        click("acquisition")
        body().contains("Добыча киркой") shouldBe true
        body().contains("Исследование мира") shouldBe false
        body().contains("сбор урожая") shouldBe false
    }

    @Test
    fun `kit sources state the access condition for both commands without promising a permission`() {
        sourceResult = CompletableFuture.completedFuture(HelpCenterEnchantmentAcquisition(listOf(
            HelpCenterEnchantmentSourceFact("box-content", mapOf("item" to "slotincreaser", "per_roll" to "3,31", "amount" to "1")),
            HelpCenterEnchantmentSourceFact("cmi-kit-source", mapOf("kit" to "aelootbox", "item" to "ae_lootbox", "amount" to "1", "cooldown" to "24h")),
            HelpCenterEnchantmentSourceFact("gkit-source", mapOf("kit" to "enchant", "amount" to "2", "chance" to "10", "cooldown" to "168h")),
        )))
        open()
        click("acquisition")

        val visible = body()
        visible.contains("/kit aelootbox") shouldBe true
        visible.contains("/gkit") shouldBe true
        visible.contains("Отдельный розыгрыш при получении набора: 10%.") shouldBe true
        visible.contains("Интервал: 24 ч") shouldBe true
        visible.contains("Интервал: 168 ч") shouldBe true
        (visible.split("Только при наличии доступа к этому набору").size - 1) shouldBe 2
        (visible.split("могут действовать другие условия").size - 1) shouldBe 2
        (visible.indexOf("Набор чар") < visible.indexOf("Шанс за одну награду")) shouldBe true
        visible.contains("VIP") shouldBe false
        executed.isEmpty() shouldBe true
    }

    @Test
    fun `unavailable cross server and kit sources have readable names and do not imply a zero chance`() {
        sourceResult = CompletableFuture.completedFuture(HelpCenterEnchantmentAcquisition(
            listOf("cmi-kits", "ae-gkits", "survival-sources").map {
                HelpCenterEnchantmentSourceFact("unavailable", mapOf("source" to it))
            },
        ))
        open()
        click("acquisition")

        body().contains("Наборы /kit") shouldBe true
        body().contains("Наборы чар /gkit") shouldBe true
        body().contains("Находки на Выживании") shouldBe true
        body().contains("Это не означает нулевой шанс") shouldBe true
        body().contains("cmi-kits") shouldBe false
        body().contains("ae-gkits") shouldBe false
        body().contains("survival-sources") shouldBe false
    }

    @Test
    fun `material names safely fall back when the loaded translator is unavailable`() {
        val previousTranslator = HookRegistry.translatorHook
        HookRegistry.translatorHook = mockk<TranslatorHook>().also { translator ->
            every { translator.translate(any<Material>()) } throws IllegalStateException("translator unavailable")
        }
        try {
            sourceResult = CompletableFuture.completedFuture(HelpCenterEnchantmentAcquisition(listOf(
                HelpCenterEnchantmentSourceFact("mining-cache-materials", mapOf("materials" to "STONE, DEEPSLATE,FUTURE_ORE")),
            )))
            open()
            click("acquisition")

            body().contains("Добыча киркой · подходящие блоки: Stone, Deepslate, FUTURE_ORE") shouldBe true
            body().contains("translator unavailable") shouldBe false
        } finally {
            HookRegistry.translatorHook = previousTranslator
        }
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
