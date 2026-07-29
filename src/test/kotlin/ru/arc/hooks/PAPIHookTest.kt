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
        }
    })
