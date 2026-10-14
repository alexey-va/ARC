package ru.arc.commands

import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import org.bukkit.plugin.ServicePriority
import ru.arc.origin.api.ArcOriginCommands
import ru.arc.origin.api.ArcOriginCommands.CommandId
import io.mockk.verify
import org.bukkit.command.Command as BukkitCommand
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import ru.arc.TestBase
import ru.arc.commands.arc.ArcCommand

class BreweryCommandAccessTest : TestBase() {
    private lateinit var arcCommand: ArcCommand
    private lateinit var command: BukkitCommand
    private val service = mockk<ArcOriginCommands>()

    @BeforeEach
    override fun setUpBase() {
        super.setUpBase()
        every { service.execute(CommandId.BREWERY, any(), any()) } returns true
        server.servicesManager.register(ArcOriginCommands::class.java, service, plugin, ServicePriority.Normal)
        arcCommand = ArcCommand()
        command = mock(BukkitCommand::class.java)
    }

    @AfterEach
    fun unregisterOriginService() {
        server.servicesManager.unregister(ArcOriginCommands::class.java, service)
        io.mockk.clearMocks(service)
    }

    @Test
    fun `ordinary player can use exact public order action`() {
        val visitor = server.addPlayer("BreweryVisitor")

        visitor.isOp shouldBe false
        arcCommand.onCommand(visitor, command, "arc", arrayOf("brewery", "order")) shouldBe true

        verify(exactly = 1) { service.execute(CommandId.BREWERY, visitor, arrayOf("order")) }
    }

    @Test
    fun `domain arguments reach the child validator unchanged`() {
        val visitor = server.addPlayer("BreweryVisitor")

        arcCommand.onCommand(visitor, command, "arc", arrayOf("brewery", "order", "extra")) shouldBe true

        verify(exactly = 1) { service.execute(CommandId.BREWERY, visitor, arrayOf("order", "extra")) }
    }

    @Test
    fun `restaurant menu is an explicit public native dialog route`() {
        val visitor = server.addPlayer("RestaurantVisitor")
        arcCommand.onCommand(visitor, command, "arc", arrayOf("brewery", "order", "restaurant")) shouldBe true
        verify(exactly = 1) { service.execute(CommandId.BREWERY, visitor, arrayOf("order", "restaurant")) }
    }
}
