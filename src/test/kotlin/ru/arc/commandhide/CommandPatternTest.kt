package ru.arc.commandhide

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe

class CommandPatternTest :
    FreeSpec({
        "command patterns" - {
            "match literals case-insensitively with or without a slash" {
                val policy = policy("Plugins")

                policy.blocks("/plugins") shouldBe true
                policy.blocks("PLUGINS") shouldBe true
                policy.blocks("/plugins extra") shouldBe false
            }

            "single wildcard matches exactly one word" {
                val policy = policy("example * reload")

                policy.blocks("/example shop reload") shouldBe true
                policy.blocks("/example reload") shouldBe false
                policy.blocks("/example shop force reload") shouldBe false
            }

            "remaining wildcard matches zero or more final words" {
                val policy = policy("plugins **")

                policy.blocks("/plugins") shouldBe true
                policy.blocks("/plugins verbose") shouldBe true
                policy.blocks("/plugins verbose all") shouldBe true
            }

            "remaining wildcard is accepted only as the final word" {
                shouldThrow<IllegalArgumentException> {
                    CommandPattern.parse("example ** reload", stripCommandNamespace = true)
                }
            }

            "partial-word wildcards are rejected" {
                shouldThrow<IllegalArgumentException> {
                    CommandPattern.parse("plug*", stripCommandNamespace = true)
                }
            }

            "namespace stripping closes namespaced command aliases" {
                val stripped = policy("plugins **", stripCommandNamespace = true)
                val exact = policy("plugins **", stripCommandNamespace = false)

                stripped.blocks("/bukkit:plugins anything") shouldBe true
                exact.blocks("/bukkit:plugins anything") shouldBe false
            }

            "overlapping literal and wildcard branches remain deterministic" {
                val policy = policy("example admin reload", "example * delete")

                policy.blocks("/example admin reload") shouldBe true
                policy.blocks("/example admin delete") shouldBe true
                policy.blocks("/example player delete") shouldBe true
                policy.blocks("/example player reload") shouldBe false
            }
        }

        "tab completion filtering" - {
            "removes a blocked completion while preserving allowed siblings" {
                val policy = policy("example admin **")

                policy.filterCompletions("/example a", listOf("admin", "about")) shouldContainExactly listOf("about")
            }

            "appends a completion after trailing whitespace" {
                val policy = policy("example admin **")

                policy.filterCompletions("/example ", listOf("admin", "help")) shouldContainExactly listOf("help")
            }

            "clears every completion inside an already blocked subtree" {
                val policy = policy("example admin **")

                policy.filterCompletions("/example admin ", listOf("reload", "status")) shouldBe emptyList()
            }

            "keeps allowed descendants when only the exact parent command is blocked" {
                val policy = policy("example admin")

                policy.filterCompletions("/example admin ", listOf("reload", "status")) shouldContainExactly
                    listOf("reload", "status")
            }
        }

        "subtree matching" - {
            "deep wildcard marks a literal branch as completely blocked" {
                val policy = policy("example admin **")

                policy.blocksSubtree(treePath("example", "admin")) shouldBe true
                policy.blocksSubtree(treePath("example", "public")) shouldBe false
            }

            "exact patterns do not remove descendants from a command tree" {
                val policy = policy("example admin")

                policy.blocksSubtree(treePath("example", "admin")) shouldBe false
                policy.blocksExactTreePath(treePath("example", "admin")) shouldBe true
            }

            "single wildcard covers both literal and argument tree nodes" {
                val pattern = CommandPattern.parse("example * **", stripCommandNamespace = true)
                val policy =
                    CommandHidePolicy(
                        patterns = listOf(pattern),
                        stripCommandNamespace = true,
                        hideNamespacedRoots = false,
                        blockedMessage = null,
                    )

                policy.blocksSubtree(
                    listOf(CommandTreeToken.Literal("example"), CommandTreeToken.Argument),
                ) shouldBe true
                policy.blocksSubtree(treePath("example", "anything")) shouldBe true
            }

            "production-sized catalogs keep root and deep lookups exact" {
                val patterns = (0 until 290).map { index -> "hidden$index **" } + "arc admin **" + "example * leaf"
                val policy = policy(*patterns.toTypedArray())

                policy.hidesRoot("hidden289") shouldBe true
                policy.hidesRoot("public") shouldBe false
                policy.blocksSubtree(treePath("arc", "admin")) shouldBe true
                policy.blocksSubtree(treePath("arc", "public")) shouldBe false
                policy.blocksExactTreePath(
                    listOf(
                        CommandTreeToken.Literal("example"),
                        CommandTreeToken.Argument,
                        CommandTreeToken.Literal("leaf"),
                    ),
                ) shouldBe true
            }
        }
    })

private fun policy(
    vararg patterns: String,
    stripCommandNamespace: Boolean = true,
): CommandHidePolicy =
    CommandHidePolicy(
        patterns = patterns.map { CommandPattern.parse(it, stripCommandNamespace) },
        stripCommandNamespace = stripCommandNamespace,
        hideNamespacedRoots = false,
        blockedMessage = null,
    )

private fun treePath(vararg literals: String): List<CommandTreeToken> =
    literals.map(CommandTreeToken::Literal)
