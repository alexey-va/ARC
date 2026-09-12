package ru.arc.worldcontent

import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.mockk.clearMocks
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import org.bukkit.Location
import org.bukkit.World
import org.bukkit.entity.Player
import ru.arc.paper.menu.PaperDialogClickContext

class BreweryTableDialogsTest : FreeSpec({
    val player = mockk<Player>(relaxed = true)
    val context = mockk<PaperDialogClickContext>()
    val location = mockk<Location>(relaxed = true)
    val world = mockk<World>(relaxed = true)
    var x = BreweryTableDialogs.TABLE_X
    var y = BreweryTableDialogs.TABLE_Y
    var z = BreweryTableDialogs.TABLE_Z

    beforeEach {
        x = BreweryTableDialogs.TABLE_X
        y = BreweryTableDialogs.TABLE_Y
        z = BreweryTableDialogs.TABLE_Z
        clearMocks(player, context, location, world)
        every { player.isOnline } returns true
        every { player.location } returns location
        every { location.world } returns world
        every { world.name } returns BreweryTableDialogs.ORIGIN_WORLD
        every { location.x } answers { x }
        every { location.y } answers { y }
        every { location.z } answers { z }
        every { player.performCommand(any()) } returns true
        every { context.player } returns player
    }

    "order screen presents three fixed native choices" {
        val screen = BreweryTableDialogs.orderScreen(player)
        val text = screen.body.joinToString("\n") {
            PlainTextComponentSerializer.plainText().serialize(it.text)
        }

        screen.id shouldBe BreweryTableDialogs.SCREEN_ID
        text shouldContain "Луи"
        text shouldContain "ПКМ по стулу"
        screen.buttons.map { it.id.value } shouldContainExactly listOf("brewery_egg", "brewery_fish", "brewery_steak")
        screen.buttons.map {
            PlainTextComponentSerializer.plainText().serialize(it.label)
        } shouldContainExactly listOf("Яичница с травами", "Запечённая рыба", "Стейк с перцем")
        screen.buttons.all { it.closeDialogBeforeAction } shouldBe true
        screen.buttons.all {
            PlainTextComponentSerializer.plainText().serialize(it.tooltip).isNotBlank()
        } shouldBe true
    }

    "dish buttons issue only their fixed player command" {
        BreweryTableDialogs.orderScreen(player).buttons.forEach { it.onClick.handle(context) }

        verify(exactly = 1) { player.performCommand("rcbreweryorder egg") }
        verify(exactly = 1) { player.performCommand("rcbreweryorder fish") }
        verify(exactly = 1) { player.performCommand("rcbreweryorder steak") }
    }

    "order callback rechecks the table boundary" {
        x = BreweryTableDialogs.TABLE_X + BreweryTableDialogs.TABLE_RADIUS + 0.01
        BreweryTableDialogs.orderScreen(player).buttons.first().onClick.handle(context)

        verify(exactly = 0) { player.performCommand(any()) }
    }
})
