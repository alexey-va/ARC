package ru.arc.commands

import io.mockk.every
import io.mockk.mockkObject
import io.mockk.unmockkObject
import io.mockk.verify
import org.bukkit.command.CommandSender
import org.bukkit.command.Command as BukkitCommand
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import io.kotest.matchers.shouldBe
import org.mockito.Mockito.mock
import ru.arc.TestBase
import ru.arc.commands.arc.ArcCommand
import ru.arc.commands.arc.subcommands.FurnitureSubCommand

class FurnitureCommandAccessTest : TestBase() {
    private lateinit var arcCommand: ArcCommand
    private lateinit var command: BukkitCommand

    @BeforeEach
    override fun setUpBase() {
        super.setUpBase()
        mockkObject(FurnitureSubCommand)
        every { FurnitureSubCommand.name } returns "furniture"
        every { FurnitureSubCommand.aliases } returns emptyList()
        every { FurnitureSubCommand.isAvailable() } returns true
        every { FurnitureSubCommand.permission } returns "arc.furniture.admin"
        every { FurnitureSubCommand.playerOnly } returns true
        every { FurnitureSubCommand.isPublicAction(any()) } answers { callOriginal() }
        every { FurnitureSubCommand.execute(any<CommandSender>(), any<Array<String>>()) } returns true
        arcCommand = ArcCommand()
        command = mock(BukkitCommand::class.java)
    }

    @AfterEach
    fun unmockFurnitureCommand() {
        unmockkObject(FurnitureSubCommand)
    }

    @Test
    fun `only exact public furniture actions bypass admin permission`() {
        val visitor = server.addPlayer("FurnitureVisitor")
        listOf("guide", "gallery", "shop").forEach { action ->
            arcCommand.onCommand(visitor, command, "arc", arrayOf("furniture", action)) shouldBe true
        }
        val cleanup = arcCommand.onCommand(visitor, command, "arc", arrayOf("furniture", "cleanup", "4"))
        val guideWithExtraArg = arcCommand.onCommand(visitor, command, "arc", arrayOf("furniture", "guide", "extra"))

        cleanup shouldBe true
        guideWithExtraArg shouldBe true
        verify(exactly = 3) { FurnitureSubCommand.execute(any<CommandSender>(), any<Array<String>>()) }
    }

}
