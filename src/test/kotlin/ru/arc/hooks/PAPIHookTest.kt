package ru.arc.hooks

import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import ru.arc.KotestTestBase

class PAPIHookTest :
    KotestTestBase({
        describe("server-context placeholders") {
            it("resolves arc_players without an OfflinePlayer") {
                PAPIHook().onRequest(null, "players") shouldBe ""
            }

            it("returns null for player-specific placeholders without an OfflinePlayer") {
                PAPIHook().onRequest(null, "worldname").shouldBeNull()
            }

            it("routes cached server-context placeholders before the null-player guard") {
                var calls = 0
                val hook =
                    PAPIHook(
                        CachedPlaceholderResolver(
                            delegate = { player, token ->
                                player.shouldBeNull()
                                calls++
                                "resolved:$token"
                            },
                        ),
                    )

                hook.onRequest(null, "cache_30_server_online") shouldBe "resolved:%server_online%"
                hook.onRequest(null, "cache_30_server_online") shouldBe "resolved:%server_online%"
                calls shouldBe 1
            }
        }
    })
