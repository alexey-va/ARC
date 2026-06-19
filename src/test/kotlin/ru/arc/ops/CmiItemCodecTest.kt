package ru.arc.ops

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import org.bukkit.Material
import org.bukkit.inventory.ItemStack
import ru.arc.KotestTestBase
import ru.arc.util.itemStack

class CmiItemCodecTest :
    KotestTestBase({
        describe("CmiItemCodec") {
            it("should round-trip ItemStack via gzip nbt blob") {
                val stack =
                    itemStack(Material.IRON_INGOT, 1) {
                        display("<gold>Test")
                        modelData(73026)
                    }

                val blob = CmiItemCodec.encode(stack, displayAmount = 1)
                blob shouldNotBe ""
                blob.length shouldBeGreaterThan 20

                val decoded = CmiItemCodec.decode(blob)
                decoded.type shouldBe Material.IRON_INGOT
                decoded.amount shouldBe 1
            }

            it("should force display amount in blob regardless of stack size") {
                val stack = ItemStack(Material.STICK, 64)
                val blob = CmiItemCodec.encode(stack, displayAmount = 1)
                CmiItemCodec.decode(blob).amount shouldBe 1
            }

            it("should build cmi blob from item spec json") {
                val json =
                    JsonParser.parseString(
                        """
                        {
                          "material": "STICK",
                          "customModelData": 11138,
                          "display": "<gold>Bag"
                        }
                        """.trimIndent(),
                    ).asJsonObject

                val response =
                    OpsItemHandlers.cmiBlobFromSpec(
                        JsonObject().apply {
                            add("item", json)
                            addProperty("amount", 1)
                        },
                    )

                response["format"] shouldBe "cmi-gzip-nbt"
                val blob = response["blob"] as String
                CmiItemCodec.decode(blob).type shouldBe Material.STICK
            }
        }

        describe("OpsItemHandlers.handCmiBlob") {

            it("should return cmi blob for item in player's main hand") {
                val player = server.addPlayer("TestAdmin")
                player.inventory.heldItemSlot = 0
                player.inventory.setItem(0, ItemStack(Material.DIAMOND_SWORD))

                val result = OpsItemHandlers.handCmiBlob("TestAdmin", 1)

                result["format"] shouldBe "cmi-gzip-nbt"
                val blob = result["blob"] as String
                blob shouldNotBe ""
                blob.length shouldBeGreaterThan 20
                CmiItemCodec.decode(blob).type shouldBe Material.DIAMOND_SWORD
            }

            it("should throw when player holds air") {
                server.addPlayer("EmptyHand")

                shouldThrow<IllegalArgumentException> {
                    OpsItemHandlers.handCmiBlob("EmptyHand", 1)
                }.message shouldContain "not holding"
            }

            it("should throw when player is not online") {
                shouldThrow<IllegalArgumentException> {
                    OpsItemHandlers.handCmiBlob("OfflinePlayer999", 1)
                }.message shouldContain "not online"
            }
        }
    })
