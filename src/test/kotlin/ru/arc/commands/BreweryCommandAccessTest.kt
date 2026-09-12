package ru.arc.commands

import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.just
import io.mockk.mockkObject
import io.mockk.runs
import io.mockk.unmockkObject
import io.mockk.verify
import org.bukkit.command.Command as BukkitCommand
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import ru.arc.TestBase
import ru.arc.commands.arc.ArcCommand
import ru.arc.worldcontent.BreweryTableDialogs

class BreweryCommandAccessTest : TestBase() {
    private lateinit var arcCommand: ArcCommand
    private lateinit var command: BukkitCommand

    @BeforeEach
    override fun setUpBase() {
        super.setUpBase()
        mockkObject(BreweryTableDialogs)
        every { BreweryTableDialogs.openOrder(any()) } just runs
        arcCommand = ArcCommand()
        command = mock(BukkitCommand::class.java)
    }

    @AfterEach
    fun unmockBreweryDialogs() {
        unmockkObject(BreweryTableDialogs)
    }

    @Test
    fun `ordinary player can use exact public order action`() {
        val visitor = server.addPlayer("BreweryVisitor")

        visitor.isOp shouldBe false
        arcCommand.onCommand(visitor, command, "arc", arrayOf("brewery", "order")) shouldBe true

        verify(exactly = 1) { BreweryTableDialogs.openOrder(visitor) }
    }

    @Test
    fun `extra arguments are rejected without opening the menu`() {
        val visitor = server.addPlayer("BreweryVisitor")

        arcCommand.onCommand(visitor, command, "arc", arrayOf("brewery", "order", "extra")) shouldBe true

        verify(exactly = 0) { BreweryTableDialogs.openOrder(any()) }
    }
}
