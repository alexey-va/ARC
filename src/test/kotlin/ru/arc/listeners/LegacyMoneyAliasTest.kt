package ru.arc.listeners

import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.bukkit.entity.Player
import org.bukkit.event.player.PlayerCommandPreprocessEvent
import org.bukkit.event.server.TabCompleteEvent
import ru.arc.config.Config

class LegacyMoneyAliasTest :
    FreeSpec({
        "parser" - {
            "rewrites a valid legacy command to the exact RedisEconomy grammar" {
                val result = parseLegacyMoneyCommand("/money give Player_1 100")

                result shouldBe
                    LegacyMoneyCommandResult.Valid(
                        LegacyMoneyCommand(LegacyMoneyAction.GIVE, "Player_1", 100.0),
                    )
                (result as LegacyMoneyCommandResult.Valid).command.canonical shouldBe
                    "money Player_1 vault give 100.0"
            }

            "routes an explicit active currency in either compatible position" {
                val currencies = listOf("vault", "tokens")

                parseLegacyMoneyCommand("/money give Player_1 tokens 25", currencies) shouldBe
                    LegacyMoneyCommandResult.Valid(
                        LegacyMoneyCommand(LegacyMoneyAction.GIVE, "Player_1", 25.0, "tokens"),
                    )
                val amountFirst = parseLegacyMoneyCommand("/money give Player_1 25 tokens", currencies)
                amountFirst shouldBe
                    LegacyMoneyCommandResult.Valid(
                        LegacyMoneyCommand(LegacyMoneyAction.GIVE, "Player_1", 25.0, "tokens"),
                    )
                (amountFirst as LegacyMoneyCommandResult.Valid).command.canonical shouldBe
                    "money Player_1 tokens give 25.0"
            }

            "rejects an unknown currency before dispatch" {
                parseLegacyMoneyCommand(
                    "/money give Player_1 tokenz 25",
                    listOf("vault", "tokens"),
                ) shouldBe LegacyMoneyCommandResult.Invalid(LegacyMoneyCommandError.INVALID_CURRENCY)
                parseLegacyMoneyCommand(
                    "/money give Player_1 tokens;op 25",
                    listOf("vault", "tokens"),
                ) shouldBe LegacyMoneyCommandResult.Invalid(LegacyMoneyCommandError.INVALID_CURRENCY)
            }

            "normalizes case, whitespace, and finite scientific notation" {
                parseLegacyMoneyCommand("  MoNeY   TAKE   Player   1e3  ") shouldBe
                    LegacyMoneyCommandResult.Valid(
                        LegacyMoneyCommand(LegacyMoneyAction.TAKE, "Player", 1000.0),
                    )
            }

            "accepts the verified Floodgate dot-prefixed username format" {
                parseLegacyMoneyCommand("/money give .Bedrock_User 100") shouldBe
                    LegacyMoneyCommandResult.Valid(
                        LegacyMoneyCommand(LegacyMoneyAction.GIVE, ".Bedrock_User", 100.0),
                    )
            }

            "leaves RedisEconomy's canonical grammar untouched" {
                parseLegacyMoneyCommand("/money Player vault give 100") shouldBe
                    LegacyMoneyCommandResult.NotLegacy
            }

            "owns an incomplete recognized legacy action and returns usage" {
                parseLegacyMoneyCommand("/money give Player") shouldBe
                    LegacyMoneyCommandResult.Invalid(LegacyMoneyCommandError.USAGE)
            }

            "rejects non-finite and malformed amounts before dispatch" {
                parseLegacyMoneyCommand("/money give Player NaN") shouldBe
                    LegacyMoneyCommandResult.Invalid(LegacyMoneyCommandError.INVALID_AMOUNT)
                parseLegacyMoneyCommand("/money give Player Infinity") shouldBe
                    LegacyMoneyCommandResult.Invalid(LegacyMoneyCommandError.INVALID_AMOUNT)
                parseLegacyMoneyCommand("/money give Player 10k") shouldBe
                    LegacyMoneyCommandResult.Invalid(LegacyMoneyCommandError.INVALID_AMOUNT)
            }

            "rejects unsafe targets before composing a server command" {
                parseLegacyMoneyCommand("/money give Player;op 100") shouldBe
                    LegacyMoneyCommandResult.Invalid(LegacyMoneyCommandError.INVALID_TARGET)
            }

            "rejects negative deltas but preserves RedisEconomy's finite set semantics" {
                parseLegacyMoneyCommand("/money take Player -1") shouldBe
                    LegacyMoneyCommandResult.Invalid(LegacyMoneyCommandError.NEGATIVE_AMOUNT)
                parseLegacyMoneyCommand("/money set Player -1") shouldBe
                    LegacyMoneyCommandResult.Valid(
                        LegacyMoneyCommand(LegacyMoneyAction.SET, "Player", -1.0),
                    )
            }

            "allows give-all only for give" {
                parseLegacyMoneyCommand("/money give * 100") shouldBe
                    LegacyMoneyCommandResult.Valid(
                        LegacyMoneyCommand(LegacyMoneyAction.GIVE, "*", 100.0),
                    )
                parseLegacyMoneyCommand("/money set * 100") shouldBe
                    LegacyMoneyCommandResult.Invalid(LegacyMoneyCommandError.GIVE_ALL_REQUIRES_GIVE)
            }
        }

        "completion" - {
            "suggests legacy actions after one trailing space" {
                legacyMoneyCompletions("/money ", emptyList(), emptyList(), false) shouldContainExactly
                    listOf("give", "take", "set")
            }

            "filters actions by the typed prefix without losing native players" {
                legacyMoneyCompletions("/money g", emptyList(), emptyList(), false) shouldContainExactly
                    listOf("give")
                legacyMoneyCompletions("/money al", listOf("Alice"), emptyList(), false) shouldContainExactly
                    listOf("Alice")
            }

            "switches to sorted network player names only after an exact legacy action" {
                legacyMoneyCompletions(
                    "/MoNeY GIVE a",
                    listOf("vault"),
                    listOf("alex", "Alice", "invalid-name"),
                    false,
                ) shouldContainExactly listOf("alex", "Alice")
                legacyMoneyCompletions(
                    "/money give .a",
                    emptyList(),
                    listOf(".Alex_Bedrock", "Alice"),
                    false,
                ) shouldContainExactly listOf(".Alex_Bedrock")
            }

            "preserves native RedisEconomy completion for its player-first grammar" {
                legacyMoneyCompletions("/money Alice ", listOf("vault"), listOf("Alice"), false) shouldBe null
            }

            "suggests give-all only when both action and permission allow it" {
                legacyMoneyCompletions("/money give ", emptyList(), listOf("Alice"), true) shouldContainExactly
                    listOf("*", "Alice")
                legacyMoneyCompletions("/money take ", emptyList(), listOf("Alice"), true) shouldContainExactly
                    listOf("Alice")
            }

            "suggests active currencies and bounded amounts for both compatible orders" {
                val currencies = listOf("vault", "tokens")

                legacyMoneyCompletions(
                    "/money take Alice ",
                    emptyList(),
                    emptyList(),
                    false,
                    currencies,
                ) shouldContainExactly listOf("vault", "tokens", "100", "1000", "10000")
                legacyMoneyCompletions(
                    "/money take Alice 10",
                    emptyList(),
                    emptyList(),
                    false,
                    currencies,
                ) shouldContainExactly
                    listOf("100", "1000", "10000")
                legacyMoneyCompletions(
                    "/money take Alice tokens ",
                    emptyList(),
                    emptyList(),
                    false,
                    currencies,
                ) shouldContainExactly listOf("100", "1000", "10000")
                legacyMoneyCompletions(
                    "/money take Alice 100 ",
                    emptyList(),
                    emptyList(),
                    false,
                    currencies,
                ) shouldContainExactly listOf("vault", "tokens")
            }

            "listener preserves native completion while the feature flag is disabled" {
                val player = player(admin = true)
                val event = TabCompleteEvent(player, "/money ", listOf("native"))

                listener(enabled = false).onTabComplete(event)

                event.completions shouldContainExactly listOf("native")
            }

            "listener does not expose legacy completion without the admin permission" {
                val player = player(admin = false)
                val event = TabCompleteEvent(player, "/money ", listOf("native"))

                listener(enabled = true).onTabComplete(event)

                event.completions shouldContainExactly listOf("native")
            }

            "listener publishes legacy completion only when flag and permission allow it" {
                val player = player(admin = true)
                val event = TabCompleteEvent(player, "/money ", emptyList())

                listener(enabled = true).onTabComplete(event)

                event.completions shouldContainExactly listOf("give", "take", "set")
            }

            "listener publishes the live token currency after a legacy target" {
                val player = player(admin = true)
                val event = TabCompleteEvent(player, "/money give Alice t", emptyList())

                listener(enabled = true).onTabComplete(event)

                event.completions shouldContainExactly listOf("tokens")
            }
        }

        "listener execution" - {
            "leaves the legacy command untouched while the feature flag is disabled" {
                val player = player(admin = true)
                val event = PlayerCommandPreprocessEvent(player, "/money give Alice 100")

                listener(enabled = false).onPlayerCommand(event)

                event.isCancelled shouldBe false
                verify(exactly = 0) { player.performCommand(any()) }
            }

            "cancels a recognized legacy command before rejecting an unauthorized sender" {
                val player = player(admin = false)
                val event = PlayerCommandPreprocessEvent(player, "/money give Alice 100")

                listener(enabled = true).onPlayerCommand(event)

                event.isCancelled shouldBe true
                verify(exactly = 0) { player.performCommand(any()) }
            }

            "dispatches exactly one allowlisted canonical command" {
                val player = player(admin = true)
                every { player.performCommand(any()) } returns true
                val event = PlayerCommandPreprocessEvent(player, "/money give Alice 100")

                listener(enabled = true).onPlayerCommand(event)

                event.isCancelled shouldBe true
                verify(exactly = 1) { player.performCommand("money Alice vault give 100.0") }
            }

            "dispatches an explicit active currency through the canonical grammar" {
                val player = player(admin = true)
                every { player.performCommand(any()) } returns true
                val event = PlayerCommandPreprocessEvent(player, "/money give Alice tokens 25")

                listener(enabled = true).onPlayerCommand(event)

                event.isCancelled shouldBe true
                verify(exactly = 1) { player.performCommand("money Alice tokens give 25.0") }
            }

            "rejects give-all before dispatch without its dedicated permission" {
                val player = player(admin = true, giveAll = false)
                val event = PlayerCommandPreprocessEvent(player, "/money give * 100")

                listener(enabled = true).onPlayerCommand(event)

                event.isCancelled shouldBe true
                verify(exactly = 0) { player.performCommand(any()) }
            }

            "cancels malformed legacy syntax without dispatching it to RedisEconomy" {
                val player = player(admin = true)
                val event = PlayerCommandPreprocessEvent(player, "/money give Alice 100 extra")

                listener(enabled = true).onPlayerCommand(event)

                event.isCancelled shouldBe true
                verify(exactly = 0) { player.performCommand(any()) }
            }
        }
    })

private fun listener(enabled: Boolean): CommandListener {
    val config = mockk<Config>()
    every { config.bool(LEGACY_MONEY_ALIAS_ENABLED_PATH, false) } returns enabled
    return CommandListener(config, { listOf("Alice") }, { listOf("vault", "tokens") })
}

private fun player(admin: Boolean, giveAll: Boolean = false): Player {
    val player = mockk<Player>(relaxed = true)
    every { player.name } returns "Admin"
    every { player.hasPermission("arc.bypass-portal") } returns true
    every { player.hasPermission(LEGACY_MONEY_ADMIN_PERMISSION) } returns admin
    every { player.hasPermission("rediseconomy.admin.giveall") } returns giveAll
    return player
}
