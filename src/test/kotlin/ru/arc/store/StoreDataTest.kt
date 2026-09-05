package ru.arc.store

import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe
import org.bukkit.Material
import org.bukkit.inventory.ItemStack
import org.mockbukkit.mockbukkit.MockBukkit
import org.mockbukkit.mockbukkit.ServerMock
import ru.arc.util.Common
import java.util.UUID

/**
 * Tests for StoreData class - player item storage.
 *
 * Tests cover:
 * - Adding items
 * - Removing items
 * - Space checking
 * - Item stacking
 * - Forbidden items
 * - Thread safety (via lock)
 */
class StoreDataTest :
    FreeSpec({

        lateinit var server: ServerMock

        beforeSpec {
            server = MockBukkit.mock()
        }

        afterSpec {
            MockBukkit.unmock()
        }

        "StoreData" - {
            "cloud edits reject stale and forbidden replacements without partial mutation" {
                val store = StoreData(UUID.randomUUID(), size = 2)
                val empty = store.getSlots()
                val deposited = listOf(ItemStack(Material.DIAMOND, 4), null)
                store.compareAndSetSlots(empty, deposited) shouldBe true
                deposited[0]?.amount = 2
                store.getSlots()[0]?.amount shouldBe 4
                val before = store.getSlots()
                store.compareAndSetSlots(before, listOf(null, ItemStack(Material.SHULKER_BOX))) shouldBe false
                store.getSlots() shouldBe before
                store.compareAndSetSlots(empty, listOf(null, null)) shouldBe false
                store.getSlots() shouldBe before
                store.compareAndSetSlots(before, listOf(null, null)) shouldBe true
                store.getSlots() shouldBe empty
            }

            "initialization" - {
                "should create empty store with default size" {
                    val store = StoreData(UUID.randomUUID())

                    store.getItems() shouldBe emptyList()
                    store.size shouldBe 9
                    store.hasSpace() shouldBe true
                }

                "should create store with custom size" {
                    val store = StoreData(UUID.randomUUID(), size = 27)

                    store.size shouldBe 27
                    store.hasSpace() shouldBe true
                }

                "should remain usable after Gson round trip" {
                    val original = StoreData(UUID.randomUUID(), size = 27)
                    original.addItem(ItemStack(Material.DIAMOND, 10))

                    val restored = Common.gson.fromJson(Common.gson.toJson(original), StoreData::class.java)

                    restored.size shouldBe 27
                    restored.getItems().single().amount shouldBe 10
                    restored.removeItem(ItemStack(Material.DIAMOND), 4) shouldBe true
                    restored.getItems().single().amount shouldBe 6
                }
            }

            "addItem" - {
                "should add single item to empty store" {
                    val store = StoreData(UUID.randomUUID())
                    val item = ItemStack(Material.DIAMOND, 1)

                    val result = store.addItem(item)

                    result shouldBe true
                    store.getItems().size shouldBe 1
                    store.getItems()[0].type shouldBe Material.DIAMOND
                    store.getItems()[0].amount shouldBe 1
                }

                "should stack similar items" {
                    val store = StoreData(UUID.randomUUID())
                    val item1 = ItemStack(Material.DIAMOND, 10)
                    val item2 = ItemStack(Material.DIAMOND, 15)

                    store.addItem(item1) shouldBe true
                    store.addItem(item2) shouldBe true

                    store.getItems().size shouldBe 1
                    store.getItems()[0].amount shouldBe 25
                }

                "should stack into an explicit slot without moving neighbouring items" {
                    val store = StoreData(UUID.randomUUID(), size = 9)
                    store.addItemAt(4, ItemStack(Material.DIAMOND, 10)) shouldBe true
                    store.addItemAt(7, ItemStack(Material.GOLD_INGOT, 5)) shouldBe true

                    store.addItemAt(4, ItemStack(Material.DIAMOND, 15)) shouldBe true

                    store.getSlots()[4]?.amount shouldBe 25
                    store.getSlots()[7]?.type shouldBe Material.GOLD_INGOT
                }

                "should split stacks when exceeding max stack size" {
                    val store = StoreData(UUID.randomUUID())
                    val item = ItemStack(Material.DIAMOND, 64)
                    val item2 = ItemStack(Material.DIAMOND, 64)

                    store.addItem(item) shouldBe true
                    store.addItem(item2) shouldBe true

                    store.getItems().size shouldBe 2
                    store.getItems()[0].amount shouldBe 64
                    store.getItems()[1].amount shouldBe 64
                }

                "should return false when adding null item" {
                    val store = StoreData(UUID.randomUUID())

                    store.addItem(null) shouldBe false
                    store.getItems() shouldBe emptyList()
                }

                "should return true when adding AIR item (no-op)" {
                    val store = StoreData(UUID.randomUUID())
                    val air = ItemStack(Material.AIR)

                    store.addItem(air) shouldBe true
                    store.getItems() shouldBe emptyList()
                }

                "should reject forbidden items - barrier" {
                    val store = StoreData(UUID.randomUUID())
                    val barrier = ItemStack(Material.BARRIER, 1)

                    store.addItem(barrier) shouldBe false
                    store.getItems() shouldBe emptyList()
                }

                "should reject forbidden items - command block" {
                    val store = StoreData(UUID.randomUUID())
                    val commandBlock = ItemStack(Material.COMMAND_BLOCK, 1)

                    store.addItem(commandBlock) shouldBe false
                    store.getItems() shouldBe emptyList()
                }

                "should reject forbidden items - debug stick" {
                    val store = StoreData(UUID.randomUUID())
                    val debugStick = ItemStack(Material.DEBUG_STICK, 1)

                    store.addItem(debugStick) shouldBe false
                    store.getItems() shouldBe emptyList()
                }

                "should reject forbidden items - shulker box" {
                    val store = StoreData(UUID.randomUUID())
                    val shulker = ItemStack(Material.SHULKER_BOX, 1)

                    store.addItem(shulker) shouldBe false
                    store.getItems() shouldBe emptyList()
                }

                "should reject forbidden items - dragon egg" {
                    val store = StoreData(UUID.randomUUID())
                    val dragonEgg = ItemStack(Material.DRAGON_EGG, 1)

                    store.addItem(dragonEgg) shouldBe false
                    store.getItems() shouldBe emptyList()
                }

                "should return false when store is full" {
                    val store = StoreData(UUID.randomUUID(), size = 1)
                    val item1 = ItemStack(Material.DIAMOND, 64)
                    val item2 = ItemStack(Material.GOLD_INGOT, 64)

                    store.addItem(item1) shouldBe true
                    store.addItem(item2) shouldBe false // Store is full

                    store.getItems().size shouldBe 1
                }

                "should add to existing stack if space available" {
                    val store = StoreData(UUID.randomUUID(), size = 1)
                    val item1 = ItemStack(Material.DIAMOND, 32)
                    val item2 = ItemStack(Material.DIAMOND, 16)

                    store.addItem(item1) shouldBe true
                    store.addItem(item2) shouldBe true

                    store.getItems().size shouldBe 1
                    store.getItems()[0].amount shouldBe 48
                }

                "should return false when adding more than available space" {
                    val store = StoreData(UUID.randomUUID(), size = 1)
                    val item1 = ItemStack(Material.DIAMOND, 64)
                    val item2 = ItemStack(Material.DIAMOND, 32)

                    store.addItem(item1) shouldBe true
                    store.addItem(item2) shouldBe false // Would exceed max stack size

                    store.getItems().size shouldBe 1
                    store.getItems()[0].amount shouldBe 64
                }
            }

            "removeItem" - {
                "should remove exact amount from single stack" {
                    val store = StoreData(UUID.randomUUID())
                    val item = ItemStack(Material.DIAMOND, 10)
                    store.addItem(item)

                    val result = store.removeItem(ItemStack(Material.DIAMOND), 5)

                    result shouldBe true
                    store.getItems().size shouldBe 1
                    store.getItems()[0].amount shouldBe 5
                }

                "should remove entire stack when amount matches" {
                    val store = StoreData(UUID.randomUUID())
                    val item = ItemStack(Material.DIAMOND, 10)
                    store.addItem(item)

                    val result = store.removeItem(ItemStack(Material.DIAMOND), 10)

                    result shouldBe true
                    store.getItems() shouldBe emptyList()
                }

                "should remove from multiple stacks - partial removal works" {
                    val store = StoreData(UUID.randomUUID())
                    store.addItem(ItemStack(Material.DIAMOND, 64))
                    store.addItem(ItemStack(Material.DIAMOND, 64))

                    // Removing less than first stack amount
                    val result = store.removeItem(ItemStack(Material.DIAMOND), 30)

                    result shouldBe true
                    // After compact(), stacks are reorganized
                    val totalDiamonds = store.getItems().filter { it.type == Material.DIAMOND }.sumOf { it.amount }
                    totalDiamonds shouldBe 98 // 128 - 30
                }

                "should leave items unchanged when there are not enough to remove" {
                    val store = StoreData(UUID.randomUUID())
                    store.addItem(ItemStack(Material.DIAMOND, 10))

                    val result = store.removeItem(ItemStack(Material.DIAMOND), 20)

                    result shouldBe false
                    store.getItems().single().amount shouldBe 10
                }

                "should reject zero and negative amounts without changing items" {
                    val store = StoreData(UUID.randomUUID())
                    store.addItem(ItemStack(Material.DIAMOND, 10))

                    store.removeItem(ItemStack(Material.DIAMOND), 0) shouldBe false
                    store.removeItem(ItemStack(Material.DIAMOND), -5) shouldBe false
                    store.getItems().single().amount shouldBe 10
                }

                "should return false when item type not found" {
                    val store = StoreData(UUID.randomUUID())
                    store.addItem(ItemStack(Material.DIAMOND, 10))

                    val result = store.removeItem(ItemStack(Material.GOLD_INGOT), 5)

                    result shouldBe false
                    store.getItems()[0].amount shouldBe 10
                }

                "should compact stacks after removal" {
                    val store = StoreData(UUID.randomUUID())
                    store.addItem(ItemStack(Material.DIAMOND, 30))
                    store.addItem(ItemStack(Material.DIAMOND, 30))

                    store.removeItem(ItemStack(Material.DIAMOND), 20)

                    // After compaction, should have one stack of 40
                    store.getItems().size shouldBe 1
                    store.getItems()[0].amount shouldBe 40
                }
            }

            "hasSpace" - {
                "should return true for empty store" {
                    val store = StoreData(UUID.randomUUID(), size = 9)

                    store.hasSpace() shouldBe true
                }

                "should return false when store is full" {
                    val store = StoreData(UUID.randomUUID(), size = 2)
                    store.addItem(ItemStack(Material.DIAMOND, 64))
                    store.addItem(ItemStack(Material.GOLD_INGOT, 64))

                    store.hasSpace() shouldBe false
                }

                "should return true when store has empty slots" {
                    val store = StoreData(UUID.randomUUID(), size = 3)
                    store.addItem(ItemStack(Material.DIAMOND, 64))

                    store.hasSpace() shouldBe true
                }

                "should treat a preserved hole as available space" {
                    val store = StoreData(UUID.randomUUID(), size = 2)
                    store.addItem(ItemStack(Material.DIAMOND, 64))
                    store.addItem(ItemStack(Material.GOLD_INGOT, 64))
                    store.removeItemAt(0, ItemStack(Material.DIAMOND), 64)

                    store.hasSpace() shouldBe true
                    store.addItem(ItemStack(Material.EMERALD, 64)) shouldBe true
                    store.getSlots()[0]?.type shouldBe Material.EMERALD
                    store.getSlots()[1]?.type shouldBe Material.GOLD_INGOT
                }
            }

            "getItems" - {
                "should return empty list for empty store" {
                    val store = StoreData(UUID.randomUUID())

                    store.getItems() shouldBe emptyList()
                }

                "should return a detached item snapshot" {
                    val store = StoreData(UUID.randomUUID())
                    store.addItem(ItemStack(Material.DIAMOND, 10))

                    val items = store.getItems()
                    items[0].amount = 999

                    store.getItems()[0].amount shouldBe 10
                }

                "should preserve explicit slot positions across removal and serialization" {
                    val store = StoreData(UUID.randomUUID(), size = 9)
                    store.addItemAt(2, ItemStack(Material.DIAMOND, 10)) shouldBe true
                    store.addItemAt(7, ItemStack(Material.GOLD_INGOT, 5)) shouldBe true

                    store.removeItemAt(2, ItemStack(Material.DIAMOND), 10) shouldBe true

                    val slots = store.getSlots()
                    slots.size shouldBe 9
                    slots[2] shouldBe null
                    slots[7]?.type shouldBe Material.GOLD_INGOT

                    val restored = Common.gson.fromJson(Common.gson.toJson(store), StoreData::class.java)
                    restored.getSlots()[2] shouldBe null
                    restored.getSlots()[7]?.type shouldBe Material.GOLD_INGOT
                }

                "should remove the selected slot when identical stacks exist" {
                    val store = StoreData(UUID.randomUUID(), size = 9)
                    store.addItemAt(0, ItemStack(Material.DIAMOND, 64)) shouldBe true
                    store.addItemAt(5, ItemStack(Material.DIAMOND, 64)) shouldBe true

                    store.removeItemAt(5, ItemStack(Material.DIAMOND), 64) shouldBe true

                    store.getSlots()[0]?.amount shouldBe 64
                    store.getSlots()[5] shouldBe null
                }

                "should keep a hole when a stack before another item is removed" {
                    val store = StoreData(UUID.randomUUID(), size = 9)
                    store.addItem(ItemStack(Material.DIAMOND, 64)) shouldBe true
                    store.addItem(ItemStack(Material.GOLD_INGOT, 64)) shouldBe true

                    store.removeItem(ItemStack(Material.DIAMOND), 64) shouldBe true

                    store.getSlots()[0] shouldBe null
                    store.getSlots()[1]?.type shouldBe Material.GOLD_INGOT
                }
            }

            "merge" - {
                "should merge data from another store" {
                    val store1 = StoreData(UUID.randomUUID())
                    store1.addItem(ItemStack(Material.DIAMOND, 10))

                    val store2 = StoreData(store1.uuid)
                    store2.addItem(ItemStack(Material.GOLD_INGOT, 20))
                    store2.size = 27

                    store1.merge(store2)

                    store1.size shouldBe 27
                    store1.getItems().size shouldBe 1
                    store1.getItems()[0].type shouldBe Material.GOLD_INGOT

                    store2.removeItem(ItemStack(Material.GOLD_INGOT), 5)
                    store1.getItems()[0].amount shouldBe 20
                }
            }

            "id" - {
                "should return UUID as string" {
                    val uuid = UUID.randomUUID()
                    val store = StoreData(uuid)

                    store.id() shouldBe uuid.toString()
                }
            }

            "complex scenarios" - {
                "should handle multiple different items" {
                    val store = StoreData(UUID.randomUUID(), size = 10)

                    store.addItem(ItemStack(Material.DIAMOND, 32))
                    store.addItem(ItemStack(Material.GOLD_INGOT, 16))
                    store.addItem(ItemStack(Material.EMERALD, 8))
                    store.addItem(ItemStack(Material.DIAMOND, 16)) // Add more diamonds

                    val items = store.getItems()
                    val diamondStack = items.find { it.type == Material.DIAMOND }
                    val goldStack = items.find { it.type == Material.GOLD_INGOT }
                    val emeraldStack = items.find { it.type == Material.EMERALD }

                    diamondStack?.amount shouldBe 48
                    goldStack?.amount shouldBe 16
                    emeraldStack?.amount shouldBe 8
                }

                "should handle adding and removing in sequence" {
                    val store = StoreData(UUID.randomUUID(), size = 10)

                    store.addItem(ItemStack(Material.DIAMOND, 64))
                    store.addItem(ItemStack(Material.DIAMOND, 64))
                    store.removeItem(ItemStack(Material.DIAMOND), 50)
                    store.addItem(ItemStack(Material.DIAMOND, 20))

                    val items = store.getItems()
                    val total = items.filter { it.type == Material.DIAMOND }.sumOf { it.amount }
                    total shouldBe 98 // 128 - 50 + 20
                }
            }
        }
    })
