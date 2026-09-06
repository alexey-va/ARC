package ru.arc.hooks

import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import ru.arc.KotestTestBase
import ru.arc.mounts.ActiveMountSnapshot
import java.util.UUID

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
                    "%arc_dungeon_active%",
                    "%arc_dungeon_title%",
                    "%arc_dungeon_line_<1-11>%",
                    "%arc_mount_active%",
                    "%arc_mount_id%",
                    "%arc_mount_name%",
                    "%arc_mount_rarity%",
                    "%arc_mount_entity_uuid%",
                    "%arc_cache_<1-300 seconds>_<placeholder_without_percent_signs>%",
                    "%arc_cache_plain_<1-300 seconds>_<placeholder_without_percent_signs>%",
                )
            }

            it("exposes only the immutable confirmed mount snapshot fields") {
                val entityId = UUID.randomUUID()
                val snapshot = ActiveMountSnapshot("pig", "Поросёнок", "Редкий", entityId)

                mountPlaceholderValue(snapshot, "mount_active") shouldBe "true"
                mountPlaceholderValue(snapshot, "mount_id") shouldBe "pig"
                mountPlaceholderValue(snapshot, "mount_name") shouldBe "Поросёнок"
                mountPlaceholderValue(snapshot, "mount_rarity") shouldBe "Редкий"
                mountPlaceholderValue(snapshot, "mount_entity_uuid") shouldBe entityId.toString()
                mountPlaceholderValue(null, "mount_active") shouldBe "false"
                mountPlaceholderValue(null, "mount_id") shouldBe ""
                mountPlaceholderValue(snapshot, "mount_unknown").shouldBeNull()
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
