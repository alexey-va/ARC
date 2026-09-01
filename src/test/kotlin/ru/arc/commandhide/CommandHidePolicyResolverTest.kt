package ru.arc.commandhide

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe
import java.util.UUID

class CommandHidePolicyResolverTest :
    FreeSpec({
        "group resolution" - {
            "arc.hide child permission includes inherited commands" {
                val resolver =
                    CommandHidePolicyResolver(
                        config(
                            group("base", commands = listOf("plugins **")),
                            group("player", inherits = listOf("base"), commands = listOf("version **")),
                        ),
                    )

                val policy = resolver.policy(UUID.randomUUID()) { it == "arc.command.hide.player" }

                policy.blocks("/plugins") shouldBe true
                policy.blocks("/version") shouldBe true
            }

            "multiple granted groups are combined" {
                val resolver =
                    CommandHidePolicyResolver(
                        config(
                            group("first", commands = listOf("one **")),
                            group("second", commands = listOf("two **")),
                        ),
                    )

                val policy = resolver.policy(UUID.randomUUID()) { it in setOf("arc.command.hide.first", "arc.command.hide.second") }

                policy.blocks("/one") shouldBe true
                policy.blocks("/two") shouldBe true
            }

            "bypass permission returns an empty policy" {
                val resolver =
                    CommandHidePolicyResolver(
                        config(
                            group("player", commands = listOf("plugins **")),
                            bypassPermission = "arc.command.hide.bypass",
                        ),
                    )

                val policy = resolver.policy(UUID.randomUUID()) { true }

                policy.isEmpty shouldBe true
                policy.blocks("/plugins") shouldBe false
            }

            "unknown parent fails closed during compilation" {
                shouldThrow<IllegalArgumentException> {
                    CommandHidePolicyResolver(config(group("player", inherits = listOf("missing"))))
                }
            }

            "inheritance cycle is rejected" {
                shouldThrow<IllegalStateException> {
                    CommandHidePolicyResolver(
                        config(
                            group("first", inherits = listOf("second")),
                            group("second", inherits = listOf("first")),
                        ),
                    )
                }
            }
        }

        "player policy cache" - {
            "does not repeat permission checks inside the configured ttl" {
                var now = 0L
                var permissionChecks = 0
                val resolver =
                    CommandHidePolicyResolver(
                        config(group("player", commands = listOf("plugins **")), cacheMillis = 5_000L),
                        nanoTime = { now },
                    )
                val playerId = UUID.randomUUID()
                val checker: (String) -> Boolean = {
                    permissionChecks++
                    it == "arc.command.hide.player"
                }

                resolver.policy(playerId, checker)
                resolver.policy(playerId, checker)

                permissionChecks shouldBe 1
                now = 5_000_000_000L
                resolver.policy(playerId, checker)
                permissionChecks shouldBe 2
            }

            "explicit refresh observes permission changes immediately" {
                var hidden = true
                val resolver = CommandHidePolicyResolver(config(group("player", commands = listOf("plugins **"))))
                val playerId = UUID.randomUUID()
                val checker: (String) -> Boolean = { hidden && it == "arc.command.hide.player" }

                resolver.policy(playerId, checker).blocks("/plugins") shouldBe true
                hidden = false
                resolver.refresh(playerId, checker).blocks("/plugins") shouldBe false
            }

            "reload compiles a replacement before clearing the old cache" {
                val resolver = CommandHidePolicyResolver(config(group("player", commands = listOf("plugins **"))))
                val playerId = UUID.randomUUID()
                val checker: (String) -> Boolean = { it == "arc.command.hide.player" }

                resolver.policy(playerId, checker).blocks("/plugins") shouldBe true
                resolver.reload(config(group("player", commands = listOf("version **"))))

                resolver.policy(playerId, checker).blocks("/plugins") shouldBe false
                resolver.policy(playerId, checker).blocks("/version") shouldBe true
            }
        }

        "namespaced root visibility" - {
            "restricted players hide namespaced aliases without blocking execution" {
                val resolver =
                    CommandHidePolicyResolver(
                        config(group("player"), hideNamespacedRoots = true),
                    )

                val policy = resolver.policy(UUID.randomUUID()) { it == "arc.command.hide.player" }

                policy.hidesRoot("playerwarps:pwarp") shouldBe true
                policy.hidesRoot("pwarp") shouldBe false
                policy.blocks("/playerwarps:pwarp") shouldBe false
            }

            "unrestricted players retain namespaced aliases" {
                val resolver =
                    CommandHidePolicyResolver(
                        config(group("player"), hideNamespacedRoots = true),
                    )

                val policy = resolver.policy(UUID.randomUUID()) { false }

                policy.hidesRoot("playerwarps:pwarp") shouldBe false
                policy.isEmpty shouldBe true
            }
        }

        "default group" - {
            "applies the player policy before permission plugins finish loading" {
                val resolver =
                    CommandHidePolicyResolver(
                        config(
                            group("player", commands = listOf("world **")),
                            defaultGroup = "player",
                        ),
                    )

                val policy = resolver.policy(UUID.randomUUID()) { false }

                policy.blocks("/world create") shouldBe true
                policy.hidesRoot("worldedit:worldedit") shouldBe true
            }

            "bypass still disables the default policy" {
                val resolver =
                    CommandHidePolicyResolver(
                        config(
                            group("player", commands = listOf("world **")),
                            bypassPermission = "arc.command.hide.bypass",
                            defaultGroup = "player",
                        ),
                    )

                val policy = resolver.policy(UUID.randomUUID()) { it == "arc.command.hide.bypass" }

                policy.isEmpty shouldBe true
            }

            "unknown default group is rejected" {
                shouldThrow<IllegalArgumentException> {
                    CommandHidePolicyResolver(
                        config(group("player"), defaultGroup = "missing"),
                    )
                }
            }
        }
    })

private fun config(
    vararg groups: CommandHideGroupConfig,
    bypassPermission: String = "",
    cacheMillis: Long = 5_000L,
    hideNamespacedRoots: Boolean = true,
    defaultGroup: String = "",
): CommandHideModuleConfig =
    TestCommandHideModuleConfig(
        groups = groups.toList(),
        bypassPermission = bypassPermission,
        policyCacheMillis = cacheMillis,
        hideNamespacedRoots = hideNamespacedRoots,
        defaultGroup = defaultGroup,
    )

private fun group(
    id: String,
    inherits: List<String> = emptyList(),
    commands: List<String> = emptyList(),
): CommandHideGroupConfig = CommandHideGroupConfig(id, inherits, commands)
