package ru.arc.hooks

import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import ru.arc.KotestTestBase

class PAPIHookTest :
    KotestTestBase({
        describe("server-context placeholders") {
            it("advertises the complete public placeholder contract") {
                PAPIHook().getPlaceholders().shouldContainExactly(
                    "%arc_players%",
                    "%arc_jobsboosts_has_<boost_name>%",
                    "%arc_rubycount%",
                    "%arc_guildrank%",
                    "%arc_particles%",
                    "%arc_worldname%",
                    "%arc_cache_<1-300 seconds>_<placeholder_without_percent_signs>%",
                    "%arc_cache_plain_<1-300 seconds>_<placeholder_without_percent_signs>%",
                )
            }

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
                hook.onRequest(null, "cache_plain_30_server_online") shouldBe "resolved:%server_online%"
                hook.onRequest(null, "cache_plain_30_server_online") shouldBe "resolved:%server_online%"
                calls shouldBe 2
            }
        }
    })
