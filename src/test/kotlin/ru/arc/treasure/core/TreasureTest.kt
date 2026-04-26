package ru.arc.treasure.core

import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.bukkit.Material
import org.bukkit.inventory.ItemStack
import ru.arc.KotestTestBase

@Suppress("USELESS_CAST")
class TreasureTest :
    KotestTestBase({

        describe("Treasure.Item") {

            it("should create with defaults") {
                val stack = ItemStack(Material.DIAMOND)
                val treasure = Treasure.Item(stack)

                treasure.min shouldBe 1
                treasure.max shouldBe 1
                treasure.weight shouldBe 1
                treasure.messages.shouldBeEmpty()
                treasure.type shouldBe "item"
                treasure.displayMaterial shouldBe Material.DIAMOND
            }

            it("should create with custom values") {
                val stack = ItemStack(Material.GOLD_INGOT)
                val message = TreasureMessage.chat("You got gold!")
                val treasure =
                    Treasure.Item(
                        stack = stack,
                        min = 5,
                        max = 10,
                        weight = 3,
                        messages = listOf(message),
                    )

                treasure.min shouldBe 5
                treasure.max shouldBe 10
                treasure.weight shouldBe 3
                treasure.messages shouldHaveSize 1
                treasure.messages.first().text shouldBe "You got gold!"
            }

            it("should serialize to map") {
                val stack = ItemStack(Material.EMERALD)
                val treasure =
                    Treasure.Item(
                        stack = stack,
                        min = 2,
                        max = 8,
                        weight = 5,
                    )

                val map = treasure.toMap()

                map["type"] shouldBe "item"
                map["amount"] shouldBe "2-8"
                map["weight"] shouldBe 5
                map["stack"] shouldNotBe null
            }

            it("should serialize single amount as int") {
                val treasure = Treasure.Item(ItemStack(Material.DIAMOND), min = 5, max = 5)

                val map = treasure.toMap()

                map["amount"] shouldBe 5
            }

            it("should be immutable - copy creates new instance") {
                val original = Treasure.Item(ItemStack(Material.DIAMOND), weight = 5)
                val modified = original.copy(weight = 10)

                original.weight shouldBe 5
                modified.weight shouldBe 10
            }

            it("should add message with addMessage") {
                val treasure = Treasure.Item(ItemStack(Material.DIAMOND))
                val message = TreasureMessage.chat("Test")

                val updated = treasure.addMessage(message) as Treasure.Item

                treasure.messages.shouldBeEmpty()
                updated.messages shouldHaveSize 1
            }

            it("should use withWeight for weight changes") {
                val treasure = Treasure.Item(ItemStack(Material.DIAMOND), weight = 5)

                val updated = treasure.withWeight(10)

                updated.weight shouldBe 10
                treasure.weight shouldBe 5
            }

            it("should use withAmount for amount changes") {
                val treasure = Treasure.Item(ItemStack(Material.DIAMOND), min = 1, max = 1)

                val updated = treasure.withAmount(5, 10)

                updated.min shouldBe 5
                updated.max shouldBe 10
            }
        }

        describe("Treasure.Money") {

            it("should create with defaults") {
                val treasure = Treasure.Money()

                treasure.min shouldBe 1.0
                treasure.max shouldBe 1.0
                treasure.weight shouldBe 1
                treasure.type shouldBe "money"
                treasure.displayMaterial shouldBe Material.GOLD_INGOT
            }

            it("should create with range") {
                val treasure = Treasure.Money(min = 100.0, max = 500.0, weight = 2)

                treasure.min shouldBe 100.0
                treasure.max shouldBe 500.0
                treasure.weight shouldBe 2
            }

            it("should serialize to map") {
                val treasure = Treasure.Money(min = 50.0, max = 100.0)

                val map = treasure.toMap()

                map["type"] shouldBe "money"
                map["amount"] shouldBe "50.0-100.0"
            }

            it("should use withAmount for amount changes") {
                val treasure = Treasure.Money(min = 100.0, max = 100.0)

                val updated = treasure.withAmount(50.0, 200.0)

                updated.min shouldBe 50.0
                updated.max shouldBe 200.0
            }
        }

        describe("Treasure.Command") {

            it("should create with commands") {
                val treasure =
                    Treasure.Command(
                        commands = listOf("say Hello", "give %player% diamond 1"),
                        weight = 2,
                    )

                treasure.commands shouldHaveSize 2
                treasure.weight shouldBe 2
                treasure.type shouldBe "command"
                treasure.displayMaterial shouldBe Material.COMMAND_BLOCK
            }

            it("should serialize to map") {
                val treasure = Treasure.Command(commands = listOf("say test"))

                val map = treasure.toMap()

                map["type"] shouldBe "command"
                map["commands"] shouldBe listOf("say test")
            }

            it("should use withCommands for command changes") {
                val treasure = Treasure.Command(commands = listOf("cmd1"))

                val updated = treasure.withCommands(listOf("cmd2", "cmd3"))

                updated.commands shouldBe listOf("cmd2", "cmd3")
            }
        }

        describe("Treasure.SubPool") {

            it("should create with pool ID") {
                val treasure = Treasure.SubPool(poolId = "rare-items")

                treasure.poolId shouldBe "rare-items"
                treasure.type shouldBe "sub-pool"
                treasure.displayMaterial shouldBe Material.CHEST_MINECART
            }

            it("should serialize to map") {
                val treasure = Treasure.SubPool(poolId = "test-pool", weight = 3)

                val map = treasure.toMap()

                map["type"] shouldBe "sub-pool"
                map["poolId"] shouldBe "test-pool"
                map["weight"] shouldBe 3
            }
        }

        describe("Treasure.Enchant") {

            it("should create with defaults") {
                val treasure = Treasure.Enchant()

                treasure.min shouldBe 1
                treasure.max shouldBe 1
                treasure.exclude shouldBe emptySet()
                treasure.type shouldBe "enchant"
                treasure.displayMaterial shouldBe Material.ENCHANTED_BOOK
            }

            it("should create with exclusions") {
                val treasure =
                    Treasure.Enchant(
                        min = 1,
                        max = 3,
                        exclude = setOf("mending", "infinity"),
                    )

                treasure.exclude shouldHaveSize 2
            }

            it("should serialize to map") {
                val treasure = Treasure.Enchant(min = 1, max = 2, exclude = setOf("mending"))

                val map = treasure.toMap()

                map["type"] shouldBe "enchant"
                map["amount"] shouldBe "1-2"
                map["exclude"] shouldBe listOf("mending")
            }
        }

        describe("Treasure.Potion") {

            it("should create with defaults") {
                val treasure = Treasure.Potion()

                treasure.min shouldBe 1
                treasure.max shouldBe 1
                treasure.type shouldBe "potion"
                treasure.displayMaterial shouldBe Material.POTION
            }

            it("should create random potion") {
                val potion = Treasure.Potion.randomPotion()

                potion shouldNotBe null
                potion.type.name
                    .lowercase()
                    .contains("potion") shouldBe true
            }
        }

        describe("Treasure.fromMap") {

            it("should deserialize Item treasure") {
                val stack = ItemStack(Material.DIAMOND)
                val map =
                    mapOf(
                        "type" to "item",
                        "stack" to stack.serialize(),
                        "amount" to "5-10",
                        "weight" to 3,
                    )

                val treasure = Treasure.fromMap(map)

                treasure shouldNotBe null
                treasure.shouldBeInstanceOf<Treasure.Item>()
                treasure as Treasure.Item
                treasure.stack.type shouldBe Material.DIAMOND
                treasure.min shouldBe 5
                treasure.max shouldBe 10
                treasure.weight shouldBe 3
            }

            it("should deserialize Item with messages") {
                val stack = ItemStack(Material.DIAMOND)
                val map =
                    mapOf(
                        "type" to "item",
                        "stack" to stack.serialize(),
                        "amount" to 1,
                        "messages" to
                            listOf(
                                mapOf(
                                    "text" to "You got a diamond!",
                                    "destination" to "chat",
                                    "target" to "player",
                                ),
                            ),
                    )

                val treasure = Treasure.fromMap(map)

                treasure.shouldBeInstanceOf<Treasure.Item>()
                treasure as Treasure.Item
                treasure.messages shouldHaveSize 1
                treasure.messages.first().text shouldBe "You got a diamond!"
            }

            it("should migrate legacy message format") {
                val stack = ItemStack(Material.DIAMOND)
                val map =
                    mapOf(
                        "type" to "item",
                        "stack" to stack.serialize(),
                        "message" to "Personal message",
                        "globalMessage" to "Global message",
                        "announce" to true,
                    )

                val treasure = Treasure.fromMap(map)

                treasure.shouldBeInstanceOf<Treasure.Item>()
                treasure as Treasure.Item
                treasure.messages shouldHaveSize 2 // personal + global
            }

            it("should deserialize Money treasure") {
                val map =
                    mapOf(
                        "type" to "money",
                        "amount" to 100.0,
                        "weight" to 2,
                    )

                val treasure = Treasure.fromMap(map)

                treasure.shouldBeInstanceOf<Treasure.Money>()
                treasure as Treasure.Money
                treasure.min shouldBe 100.0
                treasure.max shouldBe 100.0
            }

            it("should deserialize Command treasure") {
                val map =
                    mapOf(
                        "type" to "command",
                        "commands" to listOf("say hi", "say bye"),
                        "weight" to 1,
                    )

                val treasure = Treasure.fromMap(map)

                treasure.shouldBeInstanceOf<Treasure.Command>()
                treasure as Treasure.Command
                treasure.commands shouldHaveSize 2
            }

            it("should deserialize SubPool treasure") {
                val map =
                    mapOf(
                        "type" to "sub-pool",
                        "poolId" to "other-pool",
                    )

                val treasure = Treasure.fromMap(map)

                treasure.shouldBeInstanceOf<Treasure.SubPool>()
                (treasure as Treasure.SubPool).poolId shouldBe "other-pool"
            }

            it("should handle alternative sub-pool-id field") {
                val map =
                    mapOf(
                        "type" to "sub-pool",
                        "sub-pool-id" to "alt-pool",
                    )

                val treasure = Treasure.fromMap(map)

                treasure.shouldBeInstanceOf<Treasure.SubPool>()
                (treasure as Treasure.SubPool).poolId shouldBe "alt-pool"
            }

            it("should deserialize Enchant treasure") {
                val map =
                    mapOf(
                        "type" to "enchant",
                        "amount" to "1-3",
                        "exclude" to listOf("MENDING"),
                        "weight" to 2,
                    )

                val treasure = Treasure.fromMap(map)

                treasure.shouldBeInstanceOf<Treasure.Enchant>()
                treasure as Treasure.Enchant
                treasure.min shouldBe 1
                treasure.max shouldBe 3
                treasure.exclude shouldBe setOf("mending")
            }

            it("should deserialize Potion treasure") {
                val map =
                    mapOf(
                        "type" to "potion",
                        "amount" to 2,
                        "weight" to 1,
                    )

                val treasure = Treasure.fromMap(map)

                treasure.shouldBeInstanceOf<Treasure.Potion>()
                (treasure as Treasure.Potion).min shouldBe 2
            }

            it("should return null for unknown type") {
                val map = mapOf("type" to "unknown")

                val treasure = Treasure.fromMap(map)

                treasure shouldBe null
            }

            it("should return null for missing type") {
                val map = mapOf("weight" to 1)

                val treasure = Treasure.fromMap(map)

                treasure shouldBe null
            }

            it("should use defaults for missing optional fields") {
                val stack = ItemStack(Material.STONE)
                val map =
                    mapOf(
                        "type" to "item",
                        "stack" to stack.serialize(),
                    )

                val treasure = Treasure.fromMap(map)

                treasure shouldNotBe null
                treasure as Treasure.Item
                treasure.weight shouldBe 1
                treasure.min shouldBe 1
                treasure.max shouldBe 1
                treasure.messages.shouldBeEmpty()
            }
        }

        describe("Round-trip serialization") {

            it("should round-trip Item treasure") {
                val message = TreasureMessage.chat("Gold!")
                val broadcast = TreasureMessage.broadcast("Player got gold!", global = true)
                val original =
                    Treasure.Item(
                        stack = ItemStack(Material.GOLD_INGOT),
                        min = 3,
                        max = 7,
                        weight = 4,
                        messages = listOf(message, broadcast),
                    )

                val map = original.toMap()
                val restored = Treasure.fromMap(map)

                restored.shouldBeInstanceOf<Treasure.Item>()
                restored as Treasure.Item
                restored.stack.type shouldBe Material.GOLD_INGOT
                restored.min shouldBe 3
                restored.max shouldBe 7
                restored.weight shouldBe 4
                restored.messages shouldHaveSize 2
            }

            it("should round-trip Money treasure") {
                val original =
                    Treasure.Money(
                        min = 50.0,
                        max = 150.0,
                        weight = 2,
                    )

                val map = original.toMap()
                val restored = Treasure.fromMap(map)

                restored.shouldBeInstanceOf<Treasure.Money>()
                restored as Treasure.Money
                restored.min shouldBe 50.0
                restored.max shouldBe 150.0
            }

            it("should round-trip Command treasure") {
                val original =
                    Treasure.Command(
                        commands = listOf("cmd1", "cmd2"),
                        weight = 5,
                    )

                val map = original.toMap()
                val restored = Treasure.fromMap(map)

                restored.shouldBeInstanceOf<Treasure.Command>()
                restored as Treasure.Command
                restored.commands shouldBe listOf("cmd1", "cmd2")
                restored.weight shouldBe 5
            }
        }

        describe("GiveConfig") {

            it("should have sensible defaults") {
                val config = GiveConfig.DEFAULT

                config.sendMessages shouldBe true
                config.sendPoolMessages shouldBe true
            }

            it("should have silent option") {
                val config = GiveConfig.SILENT

                config.sendMessages shouldBe false
                config.sendPoolMessages shouldBe false
            }
        }

        describe("TreasureMessage") {

            it("should create chat message") {
                val message = TreasureMessage.chat("Hello!")

                message.text shouldBe "Hello!"
                message.destination shouldBe MessageDestination.CHAT
                message.target shouldBe MessageTarget.PLAYER
            }

            it("should create broadcast message") {
                val message = TreasureMessage.broadcast("Broadcast!", global = true)

                message.destination shouldBe MessageDestination.CHAT
                message.target shouldBe MessageTarget.GLOBAL
            }

            it("should create action bar message") {
                val message = TreasureMessage.actionBar("Action!")

                message.destination shouldBe MessageDestination.ACTION_BAR
            }

            it("should create boss bar message") {
                val message = TreasureMessage.bossBar("Boss!", seconds = 10)

                message.destination shouldBe MessageDestination.BOSS_BAR
                message.bossBarSeconds shouldBe 10
            }

            it("should create title message") {
                val message = TreasureMessage.title("Title", subtitle = "Subtitle")

                message.destination shouldBe MessageDestination.TITLE
                message.titleSubtitle shouldBe "Subtitle"
            }

            it("should serialize to map") {
                val message = TreasureMessage.broadcast("Test", global = true)

                val map = message.toMap()

                map["text"] shouldBe "Test"
                map["target"] shouldBe "global"
            }

            it("should deserialize from map") {
                val map =
                    mapOf(
                        "text" to "Hello",
                        "destination" to "action_bar",
                        "target" to "server",
                    )

                val message = TreasureMessage.fromMap(map)

                message shouldNotBe null
                message?.text shouldBe "Hello"
                message?.destination shouldBe MessageDestination.ACTION_BAR
                message?.target shouldBe MessageTarget.SERVER
            }

            it("should migrate from legacy format") {
                val messages =
                    TreasureMessage.fromLegacy(
                        message = "Personal",
                        globalMessage = "Global",
                        announce = true,
                    )

                messages shouldHaveSize 2
                messages[0].text shouldBe "Personal"
                messages[0].target shouldBe MessageTarget.PLAYER
                messages[1].text shouldBe "Global"
                messages[1].target shouldBe MessageTarget.GLOBAL
            }

            it("should not create global message if announce is false") {
                val messages =
                    TreasureMessage.fromLegacy(
                        message = "Personal",
                        globalMessage = "Global",
                        announce = false,
                    )

                messages shouldHaveSize 1
            }
        }
    })
