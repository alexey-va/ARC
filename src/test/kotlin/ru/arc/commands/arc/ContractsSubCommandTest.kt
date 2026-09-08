package ru.arc.commands.arc

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldNotContain
import io.mockk.mockk
import ru.arc.commands.arc.subcommands.ContractsSubCommand

class ContractsSubCommandTest : StringSpec({
    "does not advertise the NPC-owned open route as a player command" {
        val suggestions = ContractsSubCommand.tabComplete(mockk(), arrayOf(""))
        suggestions.orEmpty() shouldNotContain "open"
    }

    "does not advertise the removed direct contract submit command" {
        val suggestions = ContractsSubCommand.tabComplete(mockk(), arrayOf("submit"))
        suggestions.orEmpty() shouldNotContain "submit"
    }
})
