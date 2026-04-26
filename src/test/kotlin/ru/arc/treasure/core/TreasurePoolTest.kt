package ru.arc.treasure.core

import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.ints.shouldBeInRange
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.bukkit.Material
import org.bukkit.inventory.ItemStack
import ru.arc.KotestTestBase

class TreasurePoolTest :
    KotestTestBase({

        describe("TreasurePool creation") {

            it("should create empty pool") {
                val pool = TreasurePool("test-pool")

                pool.id shouldBe "test-pool"
                pool.size shouldBe 0
                pool.totalWeight shouldBe 0
                pool.isEmpty() shouldBe true
                pool.messages.shouldBeEmpty()
            }

            it("should create pool with treasures") {
                val treasure1 = Treasure.Item(ItemStack(Material.DIAMOND), weight = 10)
                val treasure2 = Treasure.Money(min = 100.0, max = 200.0, weight = 5)

                val pool =
                    TreasurePool(
                        id = "mixed-pool",
                        treasures = listOf(treasure1, treasure2),
                    )

                pool.size shouldBe 2
                pool.totalWeight shouldBe 15
                pool.isEmpty() shouldBe false
            }

            it("should create pool with messages") {
                val message = TreasureMessage.chat("You found something!")
                val pool =
                    TreasurePool(
                        id = "messaged-pool",
                        messages = listOf(message),
                    )

                pool.messages shouldHaveSize 1
                pool.messages.first().text shouldBe "You found something!"
            }
        }

        describe("TreasurePool modification") {

            it("should add treasure") {
                val pool = TreasurePool("add-test")
                val treasure = Treasure.Item(ItemStack(Material.GOLD_INGOT), weight = 5)

                val updated = pool.add(treasure)

                pool.size shouldBe 0 // Original unchanged
                updated.size shouldBe 1
                updated.totalWeight shouldBe 5
                updated.treasures.first() shouldBe treasure
            }

            it("should remove treasure by id") {
                val treasure = Treasure.Item(ItemStack(Material.DIAMOND))
                val pool = TreasurePool("remove-test", treasures = listOf(treasure))

                val updated = pool.remove(treasure.id)

                pool.size shouldBe 1 // Original unchanged
                updated.size shouldBe 0
            }

            it("should remove treasure by reference") {
                val treasure = Treasure.Money(min = 100.0, max = 200.0)
                val pool = TreasurePool("remove-ref-test", treasures = listOf(treasure))

                val updated = pool.remove(treasure)

                updated.size shouldBe 0
            }

            it("should update treasure") {
                val original = Treasure.Item(ItemStack(Material.STONE), weight = 1)
                val pool = TreasurePool("update-test", treasures = listOf(original))

                val modified = original.copy(weight = 10)
                val updated = pool.update(modified)

                updated.treasures.first().weight shouldBe 10
            }

            it("should add message with addMessage") {
                val pool = TreasurePool("msg-test")
                val message = TreasureMessage.chat("New message")

                val updated = pool.addMessage(message)

                pool.messages.shouldBeEmpty() // Original unchanged
                updated.messages shouldHaveSize 1
                updated.messages.first().text shouldBe "New message"
            }

            it("should clear messages") {
                val message = TreasureMessage.chat("Test")
                val pool = TreasurePool("clear-msg", messages = listOf(message))

                val updated = pool.clearMessages()

                pool.messages shouldHaveSize 1
                updated.messages.shouldBeEmpty()
            }

            it("should update messages with withMessages") {
                val pool = TreasurePool("set-msgs")
                val messages =
                    listOf(
                        TreasureMessage.chat("First"),
                        TreasureMessage.broadcast("Second"),
                    )

                val updated = pool.withMessages(messages)

                updated.messages shouldHaveSize 2
            }
        }

        describe("TreasurePool random selection") {

            it("should return null for empty pool") {
                val pool = TreasurePool("empty")

                val result = pool.random()

                result.shouldBeNull()
            }

            it("should return the only treasure in pool") {
                val treasure = Treasure.Item(ItemStack(Material.DIAMOND))
                val pool = TreasurePool("single", treasures = listOf(treasure))

                val result = pool.random()

                result shouldBe treasure
            }

            it("should respect weights in selection") {
                // Create pool where one treasure has overwhelming weight
                val heavy = Treasure.Item(ItemStack(Material.DIAMOND), weight = 1000)
                val light = Treasure.Item(ItemStack(Material.STONE), weight = 1)
                val pool = TreasurePool("weighted", treasures = listOf(heavy, light))

                // Run many selections - heavy should win almost always
                var heavyCount = 0
                repeat(100) {
                    val result = pool.random()
                    if (result is Treasure.Item && result.stack.type == Material.DIAMOND) heavyCount++
                }

                // With 1000:1 ratio, we expect ~99 diamonds out of 100
                heavyCount shouldBeInRange 90..100
            }

            it("should exclude zero-weight treasures") {
                val treasure = Treasure.Item(ItemStack(Material.STONE), weight = 0)
                val pool = TreasurePool("zero-weight", treasures = listOf(treasure))

                val result = pool.random()

                result.shouldBeNull() // Zero-weight means pool effectively empty
            }
        }

        describe("TreasurePool lookup") {

            it("should find treasure by id") {
                val treasure = Treasure.Item(ItemStack(Material.GOLD_INGOT))
                val pool = TreasurePool("lookup", treasures = listOf(treasure))

                val found = pool.findById(treasure.id)

                found shouldBe treasure
            }

            it("should return null for unknown id") {
                val pool = TreasurePool("lookup-miss")

                val found = pool.findById("nonexistent")

                found.shouldBeNull()
            }

            it("should check if contains treasure") {
                val treasure = Treasure.Money(min = 50.0, max = 100.0)
                val pool = TreasurePool("contains", treasures = listOf(treasure))

                pool.contains(treasure.id) shouldBe true
                pool.contains("unknown") shouldBe false
            }
        }

        describe("TreasurePool serialization") {

            it("should serialize to map") {
                val treasure = Treasure.Item(ItemStack(Material.EMERALD), weight = 3)
                val message = TreasureMessage.chat("Pool message")
                val pool =
                    TreasurePool(
                        id = "serialize-test",
                        treasures = listOf(treasure),
                        messages = listOf(message),
                    )

                val map = pool.toMap()

                map["id"] shouldBe "serialize-test"
                (map["messages"] as List<*>) shouldHaveSize 1
                (map["treasures"] as List<*>) shouldHaveSize 1
            }

            it("should omit messages if empty") {
                val pool = TreasurePool("no-msg", messages = emptyList())

                val map = pool.toMap()

                map.containsKey("messages") shouldBe false
            }

            it("should deserialize from map") {
                val map =
                    mapOf(
                        "id" to "deserialized",
                        "messages" to
                            listOf(
                                mapOf("text" to "Loaded message"),
                            ),
                        "treasures" to
                            listOf(
                                mapOf(
                                    "type" to "money",
                                    "amount" to "100.0-200.0",
                                    "weight" to 5,
                                ),
                            ),
                    )

                val pool = TreasurePool.fromMap(map)

                pool shouldNotBe null
                pool?.id shouldBe "deserialized"
                pool?.messages?.shouldHaveSize(1)
                pool?.messages?.first()?.text shouldBe "Loaded message"
                pool?.size shouldBe 1
                pool?.treasures?.first().shouldBeInstanceOf<Treasure.Money>()
            }

            it("should migrate legacy message format") {
                val map =
                    mapOf(
                        "id" to "legacy-pool",
                        "commonMessage" to "Common message",
                        "commonAnnounceMessage" to "Announce message",
                        "commonAnnounce" to true,
                        "treasures" to emptyList<Any>(),
                    )

                val pool = TreasurePool.fromMap(map)

                pool shouldNotBe null
                pool?.messages?.shouldHaveSize(2) // common + announce
            }

            it("should handle missing treasures field") {
                val map = mapOf("id" to "empty-pool")

                val pool = TreasurePool.fromMap(map)

                pool?.size shouldBe 0
            }

            it("should return null for missing id") {
                val map = mapOf("treasures" to emptyList<Any>())

                val pool = TreasurePool.fromMap(map)

                pool.shouldBeNull()
            }
        }

        describe("TreasurePool round-trip") {

            it("should survive serialization round-trip") {
                val original =
                    TreasurePool(
                        id = "roundtrip",
                        treasures =
                            listOf(
                                Treasure.Item(ItemStack(Material.DIAMOND), min = 1, max = 5, weight = 10),
                                Treasure.Money(min = 50.0, max = 150.0, weight = 5),
                                Treasure.Command(commands = listOf("say hi"), weight = 2),
                            ),
                        messages =
                            listOf(
                                TreasureMessage.chat("Pool message"),
                                TreasureMessage.broadcast("Broadcast!"),
                            ),
                    )

                val map = original.toMap()
                val restored = TreasurePool.fromMap(map)

                restored shouldNotBe null
                restored?.id shouldBe original.id
                restored?.messages?.shouldHaveSize(2)
                restored?.size shouldBe original.size
                restored?.totalWeight shouldBe original.totalWeight
            }

            it("should preserve treasure types through round-trip") {
                val pool =
                    TreasurePool(
                        id = "types-test",
                        treasures =
                            listOf(
                                Treasure.Item(ItemStack(Material.STONE)),
                                Treasure.Money(),
                                Treasure.Command(commands = listOf("test")),
                                Treasure.SubPool(poolId = "other"),
                                Treasure.Enchant(),
                                Treasure.Potion(),
                            ),
                    )

                val restored = TreasurePool.fromMap(pool.toMap())

                restored?.treasures?.get(0).shouldBeInstanceOf<Treasure.Item>()
                restored.treasures.get(1).shouldBeInstanceOf<Treasure.Money>()
                restored.treasures.get(2).shouldBeInstanceOf<Treasure.Command>()
                restored.treasures.get(3).shouldBeInstanceOf<Treasure.SubPool>()
                restored.treasures.get(4).shouldBeInstanceOf<Treasure.Enchant>()
                restored.treasures.get(5).shouldBeInstanceOf<Treasure.Potion>()
            }
        }

        describe("TreasurePool dirty tracking") {

            it("should start dirty (new pool needs saving)") {
                val pool = TreasurePool("new-pool")

                pool.isDirty shouldBe true
            }

            it("should mark dirty on add") {
                val pool = TreasurePool("dirty-add")
                val updated = pool.add(Treasure.Item(ItemStack(Material.STONE)))

                updated.isDirty shouldBe true
            }

            it("should mark dirty on remove") {
                val treasure = Treasure.Item(ItemStack(Material.STONE))
                val pool = TreasurePool("dirty-remove", treasures = listOf(treasure)).markClean()

                val updated = pool.remove(treasure)

                updated.isDirty shouldBe true
            }

            it("should mark dirty on update") {
                val treasure = Treasure.Item(ItemStack(Material.STONE))
                val pool = TreasurePool("dirty-update", treasures = listOf(treasure)).markClean()

                val updated = pool.update(treasure.copy(weight = 99))

                updated.isDirty shouldBe true
            }

            it("should mark dirty on addMessage") {
                val pool = TreasurePool("dirty-msg").markClean()

                val updated = pool.addMessage(TreasureMessage.chat("Test"))

                updated.isDirty shouldBe true
            }

            it("should mark dirty on clearMessages") {
                val pool = TreasurePool("dirty-clear", messages = listOf(TreasureMessage.chat("X"))).markClean()

                val updated = pool.clearMessages()

                updated.isDirty shouldBe true
            }

            it("should mark clean explicitly") {
                val pool = TreasurePool("make-clean").add(Treasure.Item(ItemStack(Material.STONE)))
                pool.isDirty shouldBe true

                val cleaned = pool.markClean()

                cleaned.isDirty shouldBe false
            }

            it("should start not dirty when loaded from map") {
                val map = mapOf("id" to "loaded-pool")

                val pool = TreasurePool.fromMap(map)

                pool?.isDirty shouldBe false
            }
        }
    })
