package ru.arc.ops

import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import ru.arc.KotestTestBase

class ItemPresetsTest :
    KotestTestBase({

        describe("ItemPresets") {

            it("should resolve sf lootbox preset amount") {
                val specs = ItemPresets.resolveSpecs("sf_lootbox", 2).getOrThrow()

                specs shouldHaveSize 1
                specs.first().get("amount").asInt shouldBe 2
                specs.first().get("material").asString shouldBe "IRON_INGOT"
            }

            it("should resolve lootbox bundle preset") {
                val specs = ItemPresets.resolveSpecs("lootbox_bundle", 1).getOrThrow()

                specs shouldHaveSize 5
            }

            it("should scale sf count in large bundle") {
                val specs = ItemPresets.resolveSpecs("lootbox_bundle_large", 4).getOrThrow()

                specs shouldHaveSize 5
                specs.count { it.get("material")?.asString == "IRON_INGOT" && it.get("amount")?.asInt == 4 } shouldBe 1
            }

            it("should fail for unknown preset") {
                val result = ItemPresets.resolveSpecs("unknown_preset", 1)

                result.isFailure shouldBe true
                result.exceptionOrNull()?.message shouldContain "Unknown preset"
            }

            it("should normalize preset names") {
                ItemPresets.normalize("SF-Lootbox") shouldBe "sf_lootbox"
            }
        }

        describe("OpsItemHandlers.validatePresets") {

            it("should return ok=true for valid presets") {
                val result = OpsItemHandlers.validatePresets(listOf("sf_lootbox", "ae_lootbox"))

                result["ok"] shouldBe true
                result["valid"] shouldBe 2
                result["invalid"] shouldBe 0
            }

            it("should return ok=false for unknown presets") {
                val result = OpsItemHandlers.validatePresets(listOf("sf_lootbox", "no_such_preset"))

                result["ok"] shouldBe false
                result["valid"] shouldBe 1
                result["invalid"] shouldBe 1

                @Suppress("UNCHECKED_CAST")
                val results = result["results"] as Map<String, Any?>
                results["no_such_preset"] shouldNotBe null
                val entry = results["no_such_preset"] as Map<*, *>
                entry["ok"] shouldBe false
            }

            it("should report all invalid for empty-ish list") {
                val result = OpsItemHandlers.validatePresets(listOf("ghost_preset"))

                result["ok"] shouldBe false
                result["total"] shouldBe 1
            }
        }
    })
