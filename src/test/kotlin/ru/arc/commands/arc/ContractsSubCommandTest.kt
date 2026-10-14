package ru.arc.commands.arc

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.bukkit.command.CommandSender
import org.bukkit.plugin.ServicePriority
import ru.arc.commands.arc.subcommands.ContractsSubCommand
import ru.arc.contracts.api.ArcContractsService
import ru.arc.paper.testing.MockBukkitTestRuntime

class ContractsSubCommandTest : StringSpec({
    "delegates completion to the registered service with the original sender and arguments" {
        MockBukkitTestRuntime.open().use { runtime ->
            val sender = mockk<CommandSender>()
            val service = mockk<ArcContractsService>()
            val args = arrayOf("open", "")
            val expected = listOf("open", "status")
            every { service.complete(sender, args) } returns expected
            runtime.server.servicesManager.register(
                ArcContractsService::class.java,
                service,
                runtime.createSimplePlugin("ArcContracts"),
                ServicePriority.Normal,
            )

            val suggestions = ContractsSubCommand.tabComplete(sender, args)

            suggestions shouldBe expected
            verify(exactly = 1) { service.complete(sender, args) }
        }
    }

    "returns null safely when no provider is registered" {
        MockBukkitTestRuntime.open().use {
            val sender = mockk<CommandSender>()
            val args = arrayOf("")

            val suggestions = ContractsSubCommand.tabComplete(sender, args)

            suggestions shouldBe null
        }
    }
})
