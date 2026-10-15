package ru.arc.graves

import io.mockk.every
import io.mockk.mockk
import net.kyori.adventure.text.BlockNBTComponent
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.ComponentLike
import net.kyori.adventure.text.EntityNBTComponent
import net.kyori.adventure.text.SelectorComponent
import net.kyori.adventure.text.StorageNBTComponent
import net.kyori.adventure.text.TranslatableComponent
import net.kyori.adventure.text.event.ClickEvent
import net.kyori.adventure.text.event.HoverEvent
import net.kyori.adventure.text.serializer.gson.GsonComponentSerializer
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import org.bukkit.entity.Player
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import ru.arc.config.ConfigManager
import ru.arc.paper.menu.PaperDialogClickContext
import ru.arc.paper.menu.PaperDialogScreen
import java.util.UUID

class GravesMenuTest {
    private lateinit var player: Player
    private lateinit var menu: GravesMenu
    private lateinit var screen: PaperDialogScreen
    private var reopen: (() -> Unit)? = null
    private var views = emptyList<GraveView>()
    private var tracked: UUID? = null
    private var isTrackingEnabled = true
    private var priceQuote = 100.0
    private var toggles = 0
    private var trackedSelections = mutableListOf<UUID>()
    private var teleported: Pair<GraveView, Double>? = null

    @BeforeEach
    fun setup() {
        player = mockk(relaxed = true)
        views = emptyList()
        tracked = null
        isTrackingEnabled = true
        priceQuote = 100.0
        toggles = 0
        trackedSelections = mutableListOf()
        teleported = null
        menu = GravesMenu(
            settings = GravesConfig(ConfigManager.empty()).snapshot(),
            views = { views },
            tracking = { tracked },
            trackingEnabled = { isTrackingEnabled },
            toggleTracking = { isTrackingEnabled = !isTrackingEnabled; toggles++ },
            track = { _, graveId -> tracked = graveId; trackedSelections += graveId },
            price = { priceQuote },
            teleport = { _, grave, quotedPrice -> teleported = grave to quotedPrice },
            showDialog = { _, shown, showAgain -> screen = shown; reopen = showAgain },
            closeDialog = {},
        )
    }

    @Test
    fun `grave list and item contents paginate six at a time and reopen current data`() {
        views = (1L..7L).map { grave(it) }
        menu.open(player)

        assertEquals("graves.list", screen.id)
        assertEquals(6, screen.buttons.count { it.id.value.startsWith("grave_") })
        assertTrue(screen.buttons.any { it.id.value == "next_page" })
        click("next_page")
        assertEquals(1, screen.buttons.count { it.id.value.startsWith("grave_") })
        assertFalse(screen.buttons.any { it.id.value == "next_page" })

        views = listOf(grave(8))
        reopen!!.invoke()
        assertEquals("survival · world · 8, 64, 8 ›", plain(screen.buttons.single { it.id.value == "grave_0" }.label))

        val unsafeName = Component.text("<red>player text").clickEvent(ClickEvent.runCommand("/op Viewer"))
        views = listOf(grave(9, items = (1L..7L).map {
            GraveItem(GsonComponentSerializer.gson().serialize(if (it == 1L) unsafeName else Component.text("Item $it")), 1)
        }))
        menu.open(player)
        click("grave_0")
        assertTrue(body().contains("<red>player text"))
        assertTrue(body().contains("Item 6"))
        assertFalse(body().contains("Item 7"))
        assertFalse(screen.body.any { hasActionEvent(it.text) })
        click("items_next")
        assertTrue(body().contains("Item 7"))
        assertFalse(body().contains("Item 6"))
    }

    @Test
    fun `item names cannot retain actions in translation arguments or separators`() {
        val nestedAction = Component.text("nested item name")
            .clickEvent(ClickEvent.runCommand("/op Viewer"))
            .hoverEvent(HoverEvent.showText(Component.text("unsafe hover")))
            .insertion("unsafe insertion")
        val names = listOf(
            Component.translatable("chat.type.text", nestedAction),
            Component.selector("@a", nestedAction),
            Component.blockNBT { builder ->
                builder.nbtPath("Items")
                builder.separator(nestedAction)
                builder.absoluteWorldPos(0, 64, 0)
            },
        )
        views = listOf(grave(10, items = names.map { name ->
            GraveItem(GsonComponentSerializer.gson().serialize(name), 1)
        }))

        menu.open(player)
        click("grave_0")

        val renderedJson = screen.body.joinToString("\n") { GsonComponentSerializer.gson().serialize(it.text) }
        assertTrue(renderedJson.contains("chat.type.text"))
        assertTrue(renderedJson.contains("@a"))
        assertTrue(renderedJson.contains("Items"))
        assertTrue(renderedJson.contains("nested item name"))
        assertFalse(screen.body.any { hasActionEvent(it.text) })
    }

    @Test
    fun `grave selection and compass switch refresh the detail state`() {
        val selected = grave(5)
        views = listOf(selected)
        menu.open(player)
        click("tracking_toggle")
        assertEquals(1, toggles)
        assertFalse(isTrackingEnabled)

        click("grave_0")
        click("track_grave")
        assertEquals(listOf(selected.id), trackedSelections)
        assertEquals(selected.id, tracked)
        assertTrue(body().contains("Эта могила выбрана для компаса."))
        assertFalse(screen.buttons.any { it.id.value == "track_grave" })
    }

    @Test
    fun `expired or missing grave cannot reach teleport confirmation`() {
        val selected = grave(6)
        views = listOf(selected)
        menu.open(player)
        click("grave_0")

        views = emptyList()
        click("teleport_grave")

        assertEquals("graves.list", screen.id)
        assertTrue(body().contains("Эта могила больше недоступна"))
        assertFalse(screen.buttons.any { it.id.value == "confirm_teleport" })

        views = listOf(grave(7, expiresAt = System.currentTimeMillis() - 1L))
        menu.open(player)
        assertTrue(body().contains("Активных могил сейчас нет."))
        assertFalse(screen.buttons.any { it.id.value.startsWith("grave_") })
    }

    @Test
    fun `confirmation displays and sends the exact captured price and grave`() {
        val selected = grave(11)
        views = listOf(selected)
        priceQuote = 12.345
        menu.open(player)
        click("grave_0")
        click("teleport_grave")

        assertEquals("graves.teleport-confirmation", screen.id)
        assertTrue(body().contains("12.345"))
        assertTrue(body().contains("💰"))
        priceQuote = 99.0
        click("confirm_teleport")

        assertEquals(selected, teleported?.first)
        assertEquals(12.345, teleported?.second)
    }

    @Test
    fun `zero price is presented as the free entitlement`() {
        views = listOf(grave(12))
        priceQuote = 0.0
        menu.open(player)
        click("grave_0")
        click("teleport_grave")

        assertTrue(body().contains("Бесплатно по праву"))
        assertFalse(body().contains("💰"))
    }

    @Test
    fun `invalid price quote cannot be confirmed`() {
        views = listOf(grave(13))
        priceQuote = Double.NaN
        menu.open(player)
        click("grave_0")
        click("teleport_grave")

        assertEquals("graves.detail", screen.id)
        assertTrue(body().contains("Стоимость перехода сейчас недоступна."))
        assertFalse(screen.buttons.any { it.id.value == "teleport_grave" || it.id.value == "confirm_teleport" })
    }

    private fun click(id: String) {
        val context = mockk<PaperDialogClickContext>()
        every { context.text(any()) } returns ""
        screen.buttons.single { it.id.value == id }.onClick.handle(context)
    }

    private fun body(): String = screen.body.joinToString("\n") {
        PlainTextComponentSerializer.plainText().serialize(it.text)
    }

    private fun plain(component: Component): String = PlainTextComponentSerializer.plainText().serialize(component)

    private fun grave(id: Long, expiresAt: Long = -1L, items: List<GraveItem> = emptyList()) = GraveView(
        id = UUID(0, id),
        ownerId = UUID(1, id),
        server = "survival",
        world = "world",
        x = id.toInt(),
        y = 64,
        z = id.toInt(),
        createdAt = 1L,
        expiresAt = expiresAt,
        experience = 42,
        items = items,
    )

    private fun hasActionEvent(component: Component): Boolean {
        val hasStyleAction = component.clickEvent() != null || component.hoverEvent() != null || component.insertion() != null
        val hasNestedTranslationAction = component is TranslatableComponent && component.arguments().any { argument ->
            when (val value = argument.value()) {
                is Component -> hasActionEvent(value)
                is ComponentLike -> hasActionEvent(value.asComponent())
                else -> false
            }
        }
        val separator = when (component) {
            is SelectorComponent -> component.separator()
            is BlockNBTComponent -> component.separator()
            is EntityNBTComponent -> component.separator()
            is StorageNBTComponent -> component.separator()
            else -> null
        }
        return hasStyleAction || hasNestedTranslationAction || component.children().any(::hasActionEvent) ||
            separator?.let(::hasActionEvent) == true
    }
}
