package ru.arc.ops

import com.Zrips.CMI.Modules.Display.CMIBillboard
import com.Zrips.CMI.Modules.Display.CMITextAlignment
import com.Zrips.CMI.Modules.Holograms.CMIHologramType
import com.google.gson.JsonParser
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe

class OpsCmiHologramSpecTest : FreeSpec({

    "presence-aware CMI HologramSpec" - {
        "orders coupled range changes around CMI's visibility invariant" {
            val shrinkingCalls = mutableListOf<String>()
            applyCmiRangePatch(
                currentShowRange = 40,
                currentUpdateRange = 40,
                showRange = CmiHologramPatch.Set(18),
                updateRange = CmiHologramPatch.Set(18),
                setShowRange = { shrinkingCalls += "show:$it" },
                setUpdateRange = { shrinkingCalls += "update:$it" },
            )
            shrinkingCalls shouldBe listOf("update:18", "show:18")

            val growingCalls = mutableListOf<String>()
            applyCmiRangePatch(
                currentShowRange = 18,
                currentUpdateRange = 18,
                showRange = CmiHologramPatch.Set(40),
                updateRange = CmiHologramPatch.Set(40),
                setShowRange = { growingCalls += "show:$it" },
                setUpdateRange = { growingCalls += "update:$it" },
            )
            growingCalls shouldBe listOf("show:40", "update:40")
        }

        "rejects a range patch CMI cannot represent" {
            shouldThrow<IllegalArgumentException> {
                applyCmiRangePatch(
                    currentShowRange = 40,
                    currentUpdateRange = 40,
                    showRange = CmiHologramPatch.Set(12),
                    updateRange = CmiHologramPatch.Set(18),
                    setShowRange = {},
                    setUpdateRange = {},
                )
            }.message shouldBe "showRange must be greater than or equal to updateRange"
        }

        "preserves omitted fields in a lines-only patch" {
            val spec =
                OpsCmiHologramSpec.parse(
                    JsonParser.parseString("""{"lines":["&6Биржа","&9/stocks"]}""").asJsonObject,
                )

            (spec.lines as CmiHologramPatch.Set).value shouldBe listOf("&6Биржа", "&9/stocks")
            spec.location shouldBe CmiHologramPatch.Absent
            spec.commands shouldBe CmiHologramPatch.Absent
            spec.display shouldBe CmiHologramPatch.Absent
            spec.changedFields shouldBe listOf("lines")
        }

        "preserves omitted nested display fields" {
            val spec =
                OpsCmiHologramSpec.parse(
                    JsonParser
                        .parseString(
                            """{"display":{"billboard":"fixed","alignment":"left","scaleWidth":3.5}}""",
                        ).asJsonObject,
                )
            val display = (spec.display as CmiHologramPatch.Set).value

            (display.billboard as CmiHologramPatch.Set).value shouldBe CMIBillboard.FIXED
            (display.alignment as CmiHologramPatch.Set).value shouldBe CMITextAlignment.LEFT
            (display.scaleWidth as CmiHologramPatch.Set).value shouldBe 3.5
            display.scaleHeight shouldBe CmiHologramPatch.Absent
            display.backgroundAlpha shouldBe CmiHologramPatch.Absent
        }

        "requires a complete location for create" {
            val incomplete =
                OpsCmiHologramSpec.parse(
                    JsonParser.parseString("""{"location":{"world":"spawn","x":1,"z":2}}""").asJsonObject,
                )

            shouldThrow<IllegalArgumentException> {
                incomplete.requireCreateFields()
            }.message shouldBe "location.y required when creating a CMI hologram"
        }

        "accepts a complete creation payload" {
            val spec =
                OpsCmiHologramSpec.parse(
                    JsonParser
                        .parseString(
                            """
                            {
                              "location":{"world":"spawn","x":1.5,"y":100,"z":2.5,"yaw":90},
                              "lines":[],
                              "interactable":true,
                              "commands":["asPlayer! spawn"]
                            }
                            """.trimIndent(),
                        ).asJsonObject,
                )

            spec.requireCreateFields()
            spec.changedFields shouldBe listOf("location", "lines", "interactable", "commands")
        }

        "accepts every supported CMI settings block" {
            val spec =
                OpsCmiHologramSpec.parse(
                    JsonParser
                        .parseString(
                            """
                            {
                              "type":"text_display",
                              "group":"spawn",
                              "autoPagination":true,
                              "display":{
                                "billboard":"fixed",
                                "yaw":90,
                                "pitch":10,
                                "alignment":"right",
                                "backgroundColor":"#112233",
                                "backgroundAlpha":128,
                                "textAlpha":240,
                                "doubleSided":true,
                                "shadowed":true,
                                "scaleWidth":1.2,
                                "scaleHeight":1.3,
                                "seeThrough":false,
                                "lineWidth":320,
                                "fillerAmount":40,
                                "direction":{"x":1,"y":2,"z":3},
                                "offset":{"x":0.1,"y":0.2,"z":0.3},
                                "skyLight":15,
                                "blockLight":8
                              },
                              "icon":{
                                "billboard":"center",
                                "offset":{"x":1,"y":2,"z":3},
                                "scale":{"x":1,"y":1.5,"z":2},
                                "direction":{"x":0,"y":90,"z":0},
                                "yaw":45,
                                "pitch":10,
                                "roll":5
                              },
                              "board":{
                                "enabled":true,
                                "material":"blackstone",
                                "dimensions":{"x":2,"y":1,"z":0.1},
                                "offset":{"x":0,"y":0,"z":0.2},
                                "direction":{"x":0,"y":90,"z":0}
                              },
                              "interaction":{
                                "dimensions":{"x":2,"y":1},
                                "offset":{"x":0,"y":0.2,"z":0},
                                "particleDimensions":{"x":2.1,"y":1.1},
                                "particleOffset":{"x":0,"y":0.1,"z":0},
                                "particlePosition":4,
                                "particleSpacing":-0.04,
                                "particleCount":6,
                                "effect":{
                                  "particle":"dolphin",
                                  "color":"#ffffff",
                                  "colorFrom":"#000000",
                                  "colorTo":"#ff0000",
                                  "offset":{"x":0.1,"y":0.2,"z":0.3},
                                  "amount":3,
                                  "speed":0.2,
                                  "size":2,
                                  "material":"stone",
                                  "duration":20
                                },
                                "showHoverParticle":true,
                                "showClickParticle":false,
                                "basePrefix":"&7",
                                "hoverPrefix":"&e"
                              },
                              "animation":{
                                "fadeInTicks":5,
                                "fadeOutTicks":7,
                                "autoRotateDegreesPerTick":-2
                              }
                            }
                            """.trimIndent(),
                        ).asJsonObject,
                )

            (spec.type as CmiHologramPatch.Set).value shouldBe CMIHologramType.TextDisplay
            (spec.display as CmiHologramPatch.Set).value.fillerAmount shouldBe
                CmiHologramPatch.Set(40)
            (spec.board as CmiHologramPatch.Set).value.material shouldBe
                CmiHologramPatch.Set("blackstone")
            (spec.interaction as CmiHologramPatch.Set).value.effect
                .let { it as CmiHologramPatch.Set }
                .value.particle shouldBe "dolphin"
            (spec.animation as CmiHologramPatch.Set).value.autoRotateDegreesPerTick shouldBe
                CmiHologramPatch.Set(-2)
            spec.changedFields shouldBe
                listOf(
                    "type",
                    "group",
                    "autoPagination",
                    "display",
                    "icon",
                    "board",
                    "interaction",
                    "animation",
                )
        }

        "rejects unknown, null, and unsafe values" {
            shouldThrow<IllegalArgumentException> {
                OpsCmiHologramSpec.parse(JsonParser.parseString("""{"yaml":"raw"}""").asJsonObject)
            }
            shouldThrow<IllegalArgumentException> {
                OpsCmiHologramSpec.parse(JsonParser.parseString("""{"lines":null}""").asJsonObject)
            }
            shouldThrow<IllegalArgumentException> {
                OpsCmiHologramSpec.parse(JsonParser.parseString("""{"showRange":9999}""").asJsonObject)
            }
            shouldThrow<IllegalArgumentException> {
                OpsCmiHologramSpec.parse(
                    JsonParser.parseString("""{"display":{"backgroundAlpha":256}}""").asJsonObject,
                )
            }
            shouldThrow<IllegalArgumentException> {
                OpsCmiHologramSpec.parse(
                    JsonParser.parseString("""{"type":"dragon"}""").asJsonObject,
                )
            }
            shouldThrow<IllegalArgumentException> {
                OpsCmiHologramSpec.parse(
                    JsonParser
                        .parseString("""{"interaction":{"effect":{"particle":"dust","color":"red"}}}""")
                        .asJsonObject,
                )
            }
        }
    }
})
