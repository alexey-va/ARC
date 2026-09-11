package ru.arc.itemcatalog

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import org.bukkit.command.Command
import org.bukkit.command.CommandSender
import org.bukkit.command.ConsoleCommandSender
import org.bukkit.entity.Player

class CaseRewardIssueCommandTest : StringSpec({
    "accepts an exact case outcome from the console" {
        val console = mockk<ConsoleCommandSender>(relaxed = true)
        val player = mockk<Player>(relaxed = true) {
            every { isOnline } returns true
            every { name } returns "Winner"
        }
        var call: Triple<String, String, String>? = null
        val executor = CaseRewardIssueCommand(
            playerLookup = { name -> player.takeIf { name == "Winner" } },
            issuer = CaseRewardIssuer { resolvedPlayer, caseId, entryId ->
                call = Triple(resolvedPlayer.name, caseId, entryId)
                CaseRewardIssueResult.INVENTORY
            },
        )

        executor.onCommand(
            console,
            mockk<Command>(relaxed = true),
            "arc-reward-issue",
            arrayOf("Winner", "case_daily", "coins_handful"),
        ) shouldBe true
        call shouldBe Triple("Winner", "case_daily", "coins_handful")
    }

    "rejects player and command-block senders before resolving a reward" {
        val sender = mockk<CommandSender>(relaxed = true)
        var lookupCalled = false
        val executor = CaseRewardIssueCommand(
            playerLookup = { lookupCalled = true; null },
            issuer = CaseRewardIssuer { _, _, _ -> error("must not issue") },
        )

        executor.onCommand(
            sender,
            mockk<Command>(relaxed = true),
            "arc-reward-issue",
            arrayOf("Winner", "case_daily", "coins_handful"),
        ) shouldBe true
        lookupCalled shouldBe false
    }
})
