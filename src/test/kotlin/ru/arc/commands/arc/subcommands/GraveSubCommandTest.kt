package ru.arc.commands.arc.subcommands

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import io.mockk.verify
import org.bukkit.entity.Player
import ru.arc.graves.GRAVES_ADMIN_PERMISSION
import ru.arc.graves.GravesModule

class GraveSubCommandTest : FunSpec({
    test("direct command entry and completion require the fixed admin permission") {
        val player = mockk<Player>(relaxed = true)
        var admin = false
        every { player.hasPermission(GRAVES_ADMIN_PERMISSION) } answers { admin }
        mockkObject(GravesModule)
        try {
            every { GravesModule.decorativeCommand(any(), any()) } returns true
            GraveSubCommand.defaultPermission shouldBe GRAVES_ADMIN_PERMISSION
            GraveSubCommand.defaultPlayerOnly shouldBe true
            GraveSubCommand.execute(player, arrayOf("create", "random")) shouldBe true
            GraveSubCommand.tabComplete(player, arrayOf("")) shouldBe emptyList()
            verify(exactly = 0) { GravesModule.decorativeCommand(any(), any()) }
            admin = true
            GraveSubCommand.execute(player, arrayOf("CREATE", "RANDOM")) shouldBe true
            verify(exactly = 1) { GravesModule.decorativeCommand(player, listOf("create", "random")) }
            GraveSubCommand.tabComplete(player, arrayOf("")) shouldBe listOf("create", "edit", "remove")
            GraveSubCommand.tabComplete(player, arrayOf("create", "")) shouldBe listOf("random")
            GraveSubCommand.tabComplete(player, arrayOf("remove", "")) shouldBe emptyList()
        } finally { unmockkObject(GravesModule) }
    }
})
