package ru.arc.commands.arc.subcommands

import io.kotest.matchers.shouldBe
import ru.arc.KotestTestBase
import ru.arc.common.WeightedRandom
import ru.arc.treasurechests.ChestType
import ru.arc.treasurechests.ChestVariant
import ru.arc.treasurechests.TreasureHuntConfig

class HuntTypesFormatterTest :
    KotestTestBase({

        describe("HuntTypesFormatter") {
            it("formats chest models and treasure pools") {
                val config =
                    TreasureHuntConfig(
                        id = "easter",
                        locationPoolId = "spawn",
                        chestTypes =
                            WeightedRandom<ChestType>().apply {
                                add(
                                    ChestType(
                                        ChestVariant.ITEMS_ADDER,
                                        "easter",
                                        namespaceId = "pumpkin_1",
                                    ),
                                    1.0,
                                )
                                add(
                                    ChestType(ChestVariant.VANILLA, "sf"),
                                    1.0,
                                )
                            },
                    )

                HuntTypesFormatter.chestModels(config, mapOf("easter" to "pumpkin_1")) shouldBe "easter, vanilla"
                HuntTypesFormatter.treasurePools(config) shouldBe "easter, sf"
            }

            it("applies named overrides while retaining weighted preset settings") {
                val config =
                    TreasureHuntConfig(
                        id = "easter",
                        locationPoolId = "old-locations",
                        chestTypes =
                            WeightedRandom<ChestType>().apply {
                                add(ChestType.itemsAdder("pumpkin_1", "easter", weight = 2), 2.0)
                                add(ChestType.vanilla("common", weight = 5), 5.0)
                            },
                        timeoutSeconds = 900,
                    )

                val overridden =
                    HuntTypesFormatter.withOverrides(
                        config,
                        locationPoolId = "new-locations",
                        chestTypeOverride = ChestType.vanilla("placeholder"),
                        treasurePoolIdOverride = "shared-rewards",
                    )

                overridden.locationPoolId shouldBe "new-locations"
                overridden.timeoutSeconds shouldBe 900
                overridden.chestTypes.entries().map { it.value.type to it.value.treasurePoolId } shouldBe
                    listOf(ChestVariant.VANILLA to "shared-rewards", ChestVariant.VANILLA to "shared-rewards")
                overridden.chestTypes.entries().map { it.weight } shouldBe listOf(2.0, 5.0)
            }

            it("formats location pool size suffix") {
                HuntTypesFormatter.locationPoolSizeSuffix(2847) shouldBe " <gray>(2847 точек)"
                HuntTypesFormatter.locationPoolSizeSuffix(null) shouldBe ""
                HuntTypesFormatter.locationPoolSizeSuffix(0) shouldBe ""
            }
        }
    })
