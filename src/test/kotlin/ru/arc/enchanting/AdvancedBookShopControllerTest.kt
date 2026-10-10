package ru.arc.enchanting

import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import io.mockk.verify
import net.advancedplugins.ae.api.AEAPI
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.World
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import org.bukkit.persistence.PersistentDataType
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import ru.arc.config.ConfigManager
import ru.arc.core.LifecycleTaskScope
import ru.arc.core.TestTaskScheduler
import ru.arc.helpcenter.HelpCenterEnchantment
import ru.arc.helpcenter.HelpCenterEnchantmentsCatalog
import ru.arc.paper.menu.PaperDialogClickContext
import ru.arc.paper.menu.PaperDialogScreen
import ru.arc.paper.testing.MockBukkitTestRuntime
import ru.arc.payments.ItemPaymentRequest
import ru.arc.payments.ItemPayments
import java.nio.file.Files
import java.nio.file.Path

class AdvancedBookShopControllerTest {
    private lateinit var runtime: MockBukkitTestRuntime
    private lateinit var player: Player
    private lateinit var spawn: World
    private lateinit var otherWorld: World
    private lateinit var root: Path
    private lateinit var payments: ItemPayments
    private lateinit var shop: AdvancedBookShop
    private val screens = mutableListOf<PaperDialogScreen>()
    private var available = true
    private var capturedRequest: ItemPaymentRequest? = null
    private var capturedValidate: (() -> Boolean)? = null
    private var capturedApply: (() -> Unit)? = null

    @BeforeEach
    fun setUp() {
        runtime = MockBukkitTestRuntime.open()
        runtime.createSimplePlugin("AdvancedEnchantments")
        spawn = runtime.addSimpleWorld("rc_origin_spawn")
        otherWorld = runtime.addSimpleWorld("survival")
        player = runtime.addPlayer("Shopper")
        player.teleport(spawn.spawnLocation)
        root = Files.createTempDirectory("advanced-book-shop-test")
        ConfigManager.clear()
        screens.clear()
        available = true
        capturedRequest = null
        capturedValidate = null
        capturedApply = null

        // AEAPI is a Java-static provider facade whose class initializes constants eagerly.
        Class.forName(AEAPI::class.java.name, true, AEAPI::class.java.classLoader)
        mockkStatic(AEAPI::class)
        every { AEAPI.getGroup("test_shop_enchant") } returns "UNIQUE"
        mockkStatic("ru.arc.enchanting.AdvancedBookPresentationKt")
        every { isAdvancedBookRiskSafe(any()) } returns true
        every { advancedBookChances(any()) } returns BookApplicationChances(success = 70, destroyOnFailure = 1)

        payments = mockk(relaxed = true)
        every { payments.isReady } returns true
        every { payments.submit(any(), any(), any(), any()) } answers {
            capturedRequest = firstArg()
            capturedValidate = secondArg()
            capturedApply = thirdArg()
        }

        val catalogEntry = HelpCenterEnchantment(
            id = "test_shop_enchant",
            name = "Проверочное зачарование",
            description = "Описание",
            maxLevelDescription = "Описание уровня",
            materials = setOf("DIAMOND_SWORD"),
            group = "UNIQUE",
            maxLevel = 3,
            availableFromEnchanter = true,
        )
        shop = AdvancedBookShop(
            config = EnchantingConfig.load(root),
            payments = payments,
            catalog = { HelpCenterEnchantmentsCatalog(available = true, entries = listOf(catalogEntry)) },
            configuredLevels = { listOf(1, 3) },
            isAvailableFromEnchanter = { available },
            createBook = { _, _, _ ->
                ItemStack(Material.ENCHANTED_BOOK).also { item ->
                    item.editMeta { meta ->
                        meta.persistentDataContainer.set(
                            NamespacedKey("advancedenchantments", "ae_book"),
                            PersistentDataType.STRING,
                            "test_shop_enchant",
                        )
                    }
                }
            },
            serverName = { "spawn" },
            taskScope = LifecycleTaskScope(TestTaskScheduler()),
            openDialog = { _, screen, _, _ -> screens += screen },
            beginDialogFlow = {},
        )
        shop.start()
    }

    @AfterEach
    fun tearDown() {
        shop.close()
        unmockkStatic("ru.arc.enchanting.AdvancedBookPresentationKt")
        unmockkStatic(AEAPI::class)
        ConfigManager.clear()
        root.toFile().deleteRecursively()
        runtime.close()
    }

    @Test
    fun `explicit confirm queues one exact book replacement and duplicate clicks do not queue twice`() {
        openConfirmation()
        val staleConfirmButton = screens.last().buttons.single { it.id.value == "buy_book" }
        click(staleConfirmButton)

        val request = checkNotNull(capturedRequest)
        assertEquals(0, request.slot)
        assertEquals("arc:empty-inventory-slot:v1", request.originalItemBytes.toString(Charsets.UTF_8))
        assertTrue(capturedValidate!!.invoke())
        capturedApply!!.invoke()
        assertTrue(player.inventory.getItem(request.slot)?.type == Material.ENCHANTED_BOOK)

        click(staleConfirmButton)
        verify(exactly = 1) { payments.submit(any(), any(), any(), any()) }
    }

    @Test
    fun `queued debit is rejected after native availability reload or reserved slot changes`() {
        openConfirmation()
        click(screens.last().buttons.single { it.id.value == "buy_book" })
        val request = checkNotNull(capturedRequest)
        val validate = checkNotNull(capturedValidate)

        available = false
        assertFalse(validate.invoke())

        available = true
        player.inventory.setItem(request.slot, ItemStack(Material.STONE))
        assertFalse(validate.invoke())
        assertEquals(Material.STONE, player.inventory.getItem(request.slot)?.type)
        verify(exactly = 1) { payments.submit(any(), any(), any(), any()) }
    }

    @Test
    fun `queued debit is rejected after player leaves the shop world`() {
        openConfirmation()
        click(screens.last().buttons.single { it.id.value == "buy_book" })
        val validate = checkNotNull(capturedValidate)

        player.teleport(otherWorld.spawnLocation)

        assertFalse(validate.invoke())
        assertTrue(player.inventory.getItem(0) == null)
        verify(exactly = 1) { payments.submit(any(), any(), any(), any()) }
    }

    @Test
    fun `full inventory blocks confirmation before any payment is queued`() {
        openConfirmation()
        (0..35).forEach { player.inventory.setItem(it, ItemStack(Material.STONE)) }

        click(screens.last().buttons.single { it.id.value == "buy_book" })

        assertTrue(capturedRequest == null)
        verify(exactly = 0) { payments.submit(any(), any(), any(), any()) }
    }

    private fun openConfirmation() {
        assertTrue(shop.openPurchase(player, "test_shop_enchant") {})
        assertEquals("enchanting.book.shop.select", screens.last().id)
        click(screens.last().buttons.single { it.id.value == "level_0" })
        assertEquals("enchanting.book.shop.confirm", screens.last().id)
    }

    private fun click(button: ru.arc.paper.menu.PaperDialogButton) {
        val context = mockk<PaperDialogClickContext>()
        every { context.player } returns player
        button.onClick.handle(context)
    }
}
