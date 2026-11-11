package ru.arc.hooks.cmi

import com.Zrips.CMI.CMI
import com.Zrips.CMI.Containers.CMIUser
import com.Zrips.CMI.PlayerManager
import com.Zrips.CMI.commands.CAnnotation
import com.Zrips.CMI.commands.CMICommand
import com.Zrips.CMI.commands.Cmd
import com.Zrips.CMI.commands.CommandsHandler
import com.Zrips.CMI.commands.list.ignore as NativeIgnore
import com.Zrips.CMI.events.CMIConfigReloadEvent
import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeSameInstanceAs
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.bukkit.command.CommandSender
import java.util.UUID

/** Callback rendering/pagination require native CMILib; its version bootstrap is not a unit-test boundary. */
class CmiIgnorePersistenceHookTest : FreeSpec({
    "removal queues the captured user and preserves other entries without toggling on repeat" {
        val ignored = UUID.randomUUID()
        val retained = UUID.randomUUID()
        val entries = linkedSetOf(ignored, retained)
        val queued = mutableListOf<CMIUser>()
        val target = mockk<CMIUser>()
        val actor = mockk<CMIUser>(relaxed = true)
        val actorEntries = linkedSetOf(ignored)
        every { target.ignores } returns entries
        every { target.removeIgnore(any()) } answers { entries.remove(firstArg<UUID>()); Unit }
        every { target.addForDelayedSave() } answers { queued.add(target); Unit }
        every { actor.ignores } returns actorEntries

        removeAndQueueIgnore(target, ignored) shouldBe true

        entries shouldBe setOf(retained)
        queued shouldBe listOf(target)
        actor.ignores shouldBe setOf(ignored)
        removeAndQueueIgnore(target, ignored) shouldBe false
        entries shouldBe setOf(retained)
        queued shouldBe listOf(target)
        verify(exactly = 1) { target.removeIgnore(ignored) }
        verify(exactly = 1) { target.addForDelayedSave() }
        verify(exactly = 0) { actor.removeIgnore(any()) }
        verify(exactly = 0) { actor.addForDelayedSave() }
    }

    "normal toggle stays delegated including numeric player names" {
        val cmi = mockk<CMI>()
        val players = mockk<PlayerManager>()
        val sender = mockk<CommandSender>()
        val original = mockk<Cmd>()
        val numericPlayer = mockk<CMIUser>()
        val command = ignore(original)
        every { cmi.playerManager } returns players
        every { players.getUser(any<String>()) } returns null
        every { players.getUser("123") } returns numericPlayer
        every { original.perform(cmi, sender, any()) } returns false

        command.perform(cmi, sender, arrayOf("123")) shouldBe false
        command.perform(cmi, sender, arrayOf("all")) shouldBe false

        verify { original.perform(cmi, sender, arrayOf("123")) }
        verify { original.perform(cmi, sender, arrayOf("all")) }
        verify(exactly = 0) { cmi.getUser(any(), any<String>(), any<Any>()) }
        command.close()
    }

    "command annotation preserves native gates and class-name lookup" {
        ignore::class.java.simpleName shouldBe "ignore"
        val annotation = ignore::class.java.getMethod(
            "perform", CMI::class.java, CommandSender::class.java, Array<String>::class.java,
        ).getAnnotation(CAnnotation::class.java)
        annotation.regVar.toList() shouldBe listOf(0, 1, 2)
        annotation.consoleVar.toList() shouldBe listOf(1, 2)
        annotation.customAlias.toList() shouldBe listOf("!ignorelist")
        annotation.others shouldBe true
        annotation.priority shouldBe 48
    }

    "install reload and close preserve CMI command ownership" {
        val cmi = mockk<CMI>()
        val commands = mockk<CommandsHandler>()
        val native = NativeIgnore()
        val first = commandSlot(native)
        val registry = mutableMapOf("ignore" to first)
        every { cmi.commandManager } returns commands
        every { commands.commands } returns registry
        val hook = CmiIgnorePersistenceHook(cmi)
        hook.install()
        val initial = first.cmdClass
        hook.install()
        hook.onCmiReload(mockk<CMIConfigReloadEvent>())
        first.cmdClass shouldBeSameInstanceAs initial
        registry["ignore"] shouldBeSameInstanceAs first

        val replacementNative = NativeIgnore()
        val replacement = commandSlot(replacementNative)
        registry["ignore"] = replacement
        hook.onCmiReload(mockk<CMIConfigReloadEvent>())
        first.cmdClass shouldBeSameInstanceAs native
        (replacement.cmdClass is ignore) shouldBe true
        hook.close()
        hook.close()
        replacement.cmdClass shouldBeSameInstanceAs replacementNative
    }

    "close does not overwrite a later command owner" {
        val cmi = mockk<CMI>()
        val commands = mockk<CommandsHandler>()
        val slot = commandSlot(NativeIgnore())
        every { cmi.commandManager } returns commands
        every { commands.commands } returns mutableMapOf("ignore" to slot)
        val hook = CmiIgnorePersistenceHook(cmi)
        hook.install()
        val laterOwner = mockk<Cmd>()
        slot.setCmdClass(laterOwner)
        hook.close()
        slot.cmdClass shouldBeSameInstanceAs laterOwner
    }
})

private fun commandSlot(original: Cmd): CMICommand {
    var current = original
    return mockk<CMICommand>().also { slot ->
        every { slot.cmdClass } answers { current }
        every { slot.setCmdClass(any()) } answers { current = firstArg(); slot }
    }
}
