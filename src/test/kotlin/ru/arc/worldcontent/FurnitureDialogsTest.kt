package ru.arc.worldcontent

import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.mockk.every
import io.mockk.clearMocks
import io.mockk.mockk
import io.mockk.verify
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import org.bukkit.entity.Player
import ru.arc.paper.menu.PaperDialogClickContext

class FurnitureDialogsTest : FreeSpec({
    val player = mockk<Player>(relaxed = true)
    val context = mockk<PaperDialogClickContext>()

    beforeEach {
        clearMocks(player, context)
        every { player.isOnline } returns true
        every { player.world.name } returns FurnitureDialogs.ORIGIN_WORLD
        every { player.performCommand(any()) } returns true
        every { context.player } returns player
    }

    "guide keeps the reviewed read-only furniture rules and one gallery action" {
        val screen = FurnitureDialogs.guideScreen(player)
        val text = screen.body.joinToString("\n") { PlainTextComponentSerializer.plainText().serialize(it.text) }

        screen.id shouldBe "furniture.guide"
        text shouldContain "ПКМ"
        text shouldContain "ЛКМ"
        text shouldContain "F3+G"
        text shouldContain "своём"
        text shouldContain "привате."
        text shouldContain "20"
        text shouldNotContain "manifest"
        text shouldNotContain "Denizen"
        screen.buttons.map { it.id.value } shouldContainExactly listOf("gallery")
        screen.buttons.single().closeDialogBeforeAction shouldBe false
    }

    "gallery keeps one readable native action and closes before travel" {
        val screen = FurnitureDialogs.galleryScreen(player)

        screen.id shouldBe "furniture.gallery"
        screen.columns shouldBe 1
        PlainTextComponentSerializer.plainText().serialize(screen.body.first().text) shouldContain "без выставленных предметов"
        screen.body.joinToString("\n") { PlainTextComponentSerializer.plainText().serialize(it.text) } shouldNotContain "manifest"
        screen.buttons.map { it.id.value }.shouldContainExactly(FurnitureDialogs.rooms.map { it.id })
        screen.buttons.all { it.closeDialogBeforeAction } shouldBe true
        screen.buttons.forEach { button ->
            PlainTextComponentSerializer.plainText().serialize(button.label).endsWith(" ›") shouldBe true
            PlainTextComponentSerializer.plainText().serialize(button.tooltip).isNotBlank() shouldBe true
        }
    }

    "gallery callback uses only the fixed reviewed room command" {
        val button = FurnitureDialogs.galleryScreen(player).buttons.single { it.id.value == "room_01" }

        button.onClick.handle(context)

        verify(exactly = 1) { player.performCommand("rcfurniturevisit room_01") }
    }

    "gallery callback refuses a player who left the supported entry worlds" {
        every { player.world.name } returns "survival"
        val button = FurnitureDialogs.galleryScreen(player).buttons.single { it.id.value == "room_01" }

        button.onClick.handle(context)

        verify(exactly = 0) { player.performCommand(any()) }
    }
})
