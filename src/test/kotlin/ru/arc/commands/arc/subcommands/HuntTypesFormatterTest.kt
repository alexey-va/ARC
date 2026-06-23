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

                HuntTypesFormatter.chestModels(config) shouldBe "pumpkin_1, vanilla"
                HuntTypesFormatter.treasurePools(config) shouldBe "easter, sf"
            }

            it("formats location pool size suffix") {
                HuntTypesFormatter.locationPoolSizeSuffix(2847) shouldBe " <gray>(2847 loc)"
                HuntTypesFormatter.locationPoolSizeSuffix(null) shouldBe ""
                HuntTypesFormatter.locationPoolSizeSuffix(0) shouldBe ""
            }
        }
    })
