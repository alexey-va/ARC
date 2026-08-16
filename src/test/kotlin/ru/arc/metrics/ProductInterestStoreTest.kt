package ru.arc.metrics

import com.google.gson.Gson
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.maps.shouldContainKey
import io.kotest.matchers.shouldBe
import ru.arc.product.ProductOnboardingHint
import java.nio.file.Files
import java.time.Instant
import java.time.ZoneId

class ProductInterestStoreTest :
    StringSpec({
        "aggregates detailed journeys and exit context without returning identities" {
            val now = Instant.parse("2026-08-16T12:00:00Z").toEpochMilli()
            val path = Files.createTempDirectory("product-interest-store-").resolve("data/product-interest-v1.json")
            val config = ProductInterestConfig(networkEnabled = false, zoneId = ZoneId.of("UTC"))
            val store = ProductInterestStore.open(path, config, now)
            val player = ProductPseudonym.of("00000000-0000-0000-0000-000000000017")

            listOf(
                signal(player, now, ProductEventKind.SESSION_START),
                signal(player, now, ProductEventKind.FIRST_JOIN),
                signal(player, now + 100, ProductEventKind.DETAIL, detail = ProductDetail(ProductDetailType.SERVER, "classic_survival"), source = "proxy"),
                signal(player, now + 200, ProductEventKind.DETAIL, detail = ProductDetail(ProductDetailType.CONNECTION, "server_connect"), source = "proxy"),
                signal(player, now + 1_000, ProductEventKind.DETAIL, detail = ProductDetail(ProductDetailType.COMMAND, "rtp")),
                signal(player, now + 2_000, ProductEventKind.DETAIL, detail = ProductDetail(ProductDetailType.WORLD, "vanilla")),
                signal(player, now + 3_000, ProductEventKind.DETAIL, detail = ProductDetail(ProductDetailType.NPC, "17", "Проводник")),
                signal(
                    player,
                    now + 4_000,
                    ProductEventKind.DETAIL,
                    detail = ProductDetail(ProductDetailType.ONBOARDING_HINT, ProductOnboardingHint.FOOTHOLD_MISMATCH.label),
                ),
                signal(
                    player,
                    now + 60_000,
                    ProductEventKind.SESSION_END,
                    exit =
                        ProductExitContext(
                            server = "survival",
                            world = "vanilla",
                            command = "rtp",
                            npcId = "17",
                            npcName = "Проводник",
                            stage = ProductExitStage.BEFORE_MENU,
                            teleportCause = "plugin",
                            trail = listOf("world=vanilla", "npc=17", "command=rtp"),
                        ),
                    sessionSeconds = 60,
                    activeSeconds = 45,
                ),
            ).forEach(store::apply)

            val report = store.report(now + 61_000, 7, 20)
            report["players"] shouldBe 1
            report["sessions"] shouldBe 1L
            report.toString().contains(player) shouldBe false
            report.toString().contains("00000000-0000") shouldBe false

            @Suppress("UNCHECKED_CAST")
            val dimensions = report["dimensions"] as Map<String, List<Map<String, Any?>>>
            dimensions shouldContainKey "command"
            dimensions.getValue("command").first()["value"] shouldBe "rtp"
            dimensions.getValue("world").first()["value"] shouldBe "vanilla"
            dimensions.getValue("npc").first()["display"] shouldBe "Проводник"
            dimensions.getValue("server").first()["value"] shouldBe "classic_survival"
            dimensions.getValue("connection").first()["value"] shouldBe "server_connect"
            dimensions.getValue("onboarding_hint").first()["value"] shouldBe "foothold_mismatch"

            @Suppress("UNCHECKED_CAST")
            val exits = report["exitContexts"] as List<Map<String, Any?>>
            exits.first()["stage"] shouldBe "before_menu"
            exits.first()["command"] shouldBe "rtp"
            exits.first()["uniquePlayers"] shouldBe 1
            exits.first()["trail"] shouldBe
                listOf(
                    "server=classic_survival",
                    "connection=server_connect",
                    "command=rtp",
                    "world=vanilla",
                    "npc=17",
                    "onboarding_hint=foothold_mismatch",
                )

            store.flush(now + 62_000, force = true) shouldBe true
            val restored = ProductInterestStore.open(path, config, now + 63_000)
            restored.report(now + 63_000, 7, 20).toString() shouldBe report.copyWithGeneratedAt(now + 63_000).toString()
        }

        "moves invalid state aside instead of failing startup" {
            val now = Instant.parse("2026-08-16T12:00:00Z").toEpochMilli()
            val path = Files.createTempDirectory("product-interest-invalid-").resolve("product-interest-v1.json")
            Files.writeString(path, "not-json")

            val store = ProductInterestStore.open(path, ProductInterestConfig(networkEnabled = false), now)

            store.recoveredInvalidPath?.fileName.toString().contains(".invalid-$now") shouldBe true
            Files.exists(path) shouldBe false
        }

        "does not carry a terminal proxy disconnect into the next exit trail" {
            val now = Instant.parse("2026-08-16T12:00:00Z").toEpochMilli()
            val path = Files.createTempDirectory("product-interest-terminal-").resolve("product-interest-v1.json")
            val store =
                ProductInterestStore.open(
                    path,
                    ProductInterestConfig(networkEnabled = false, zoneId = ZoneId.of("UTC")),
                    now,
                )
            val player = ProductPseudonym.of("terminal-boundary")

            listOf(
                signal(player, now, ProductEventKind.DETAIL, ProductDetail(ProductDetailType.SERVER, "classic"), source = "proxy"),
                signal(
                    player,
                    now + 1,
                    ProductEventKind.DETAIL,
                    ProductDetail(ProductDetailType.CONNECTION, ProductConnection.DISCONNECT_ACTIVE.label),
                    source = "proxy",
                ),
                signal(player, now + 2, ProductEventKind.DETAIL, ProductDetail(ProductDetailType.COMMAND, "rtp")),
                signal(
                    player,
                    now + 3,
                    ProductEventKind.SESSION_END,
                    exit = ProductExitContext(stage = ProductExitStage.BEFORE_MENU),
                ),
            ).forEach(store::apply)

            @Suppress("UNCHECKED_CAST")
            val exits = store.report(now + 4, 1, 20)["exitContexts"] as List<Map<String, Any?>>
            exits.single()["trail"] shouldBe listOf("command=rtp")
            @Suppress("UNCHECKED_CAST")
            val dimensions = store.report(now + 4, 1, 20)["dimensions"] as Map<String, List<Map<String, Any?>>>
            dimensions.getValue("connection").single()["value"] shouldBe ProductConnection.DISCONNECT_ACTIVE.label
        }

        "bounds dynamic detail values per player-day" {
            val now = Instant.parse("2026-08-16T12:00:00Z").toEpochMilli()
            val path = Files.createTempDirectory("product-interest-bounded-").resolve("product-interest-v1.json")
            val config = ProductInterestConfig(networkEnabled = false, maxDetailValuesPerPlayerDay = 16, zoneId = ZoneId.of("UTC"))
            val store = ProductInterestStore.open(path, config, now)
            val player = ProductPseudonym.of("bounded")
            repeat(40) { index ->
                store.apply(signal(player, now + index, ProductEventKind.DETAIL, detail = ProductDetail(ProductDetailType.COMMAND, "cmd$index")))
            }

            @Suppress("UNCHECKED_CAST")
            val commands = ((store.report(now + 100, 1, 100)["dimensions"] as Map<String, List<Map<String, Any?>>>).getValue("command"))
            commands.map { it["value"] } shouldContain "__other__"
            commands.size shouldBe 17
        }

        "reapplies configured bounds while restoring persisted state" {
            val now = Instant.parse("2026-08-16T12:00:00Z").toEpochMilli()
            val path = Files.createTempDirectory("product-interest-restore-bounds-").resolve("product-interest-v1.json")
            val player = ProductPseudonym.of("persisted-bounds")
            val details =
                (0 until 40).map { index ->
                    linkedMapOf("source" to "survival", "key" to "cmd$index", "count" to 1)
                }
            val state =
                linkedMapOf(
                    "version" to 1,
                    "savedAt" to now,
                    "players" to
                        listOf(
                            linkedMapOf(
                                "id" to player,
                                "firstSeenAt" to now,
                                "lastSeenAt" to now,
                                "days" to
                                    listOf(
                                        linkedMapOf(
                                            "date" to "2026-08-16",
                                            "details" to linkedMapOf("command" to details),
                                        ),
                                    ),
                            ),
                        ),
                )
            Files.writeString(path, Gson().toJson(state))

            val store =
                ProductInterestStore.open(
                    path,
                    ProductInterestConfig(networkEnabled = false, maxDetailValuesPerPlayerDay = 16, zoneId = ZoneId.of("UTC")),
                    now,
                )

            @Suppress("UNCHECKED_CAST")
            val commands = ((store.report(now, 1, 100)["dimensions"] as Map<String, List<Map<String, Any?>>>).getValue("command"))
            commands.size shouldBe 16
        }
    })

private fun signal(
    player: String,
    now: Long,
    kind: ProductEventKind,
    detail: ProductDetail? = null,
    exit: ProductExitContext? = null,
    sessionSeconds: Long = 0,
    activeSeconds: Long = 0,
    source: String = "survival",
): ProductSignal =
    ProductSignal(
        eventId = ProductPseudonym.eventId(),
        source = source,
        player = player,
        occurredAt = now,
        kind = kind,
        detail = detail,
        exit = exit,
        sessionSeconds = sessionSeconds,
        activeSeconds = activeSeconds,
    )

private fun Map<String, Any?>.copyWithGeneratedAt(now: Long): Map<String, Any?> = LinkedHashMap(this).also { it["generatedAt"] = now }
