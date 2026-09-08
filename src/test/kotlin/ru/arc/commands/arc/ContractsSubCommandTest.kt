package ru.arc.commands.arc

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.collections.shouldContain
import io.mockk.mockk
import ru.arc.commands.arc.subcommands.ContractsSubCommand

class ContractsSubCommandTest : StringSpec({
    "advertises read-only book browsing as a player command" {
        val suggestions = ContractsSubCommand.tabComplete(mockk(), arrayOf(""))
        suggestions.orEmpty() shouldContain "open"
    }

    "does not advertise the removed direct contract submit command" {
        val suggestions = ContractsSubCommand.tabComplete(mockk(), arrayOf("submit"))
        suggestions.orEmpty() shouldNotContain "submit"
    }
})
