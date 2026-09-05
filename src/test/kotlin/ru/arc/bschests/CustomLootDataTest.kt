package ru.arc.bschests

import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.bukkit.Material
import org.bukkit.inventory.ItemStack
import ru.arc.KotestTestBase
import ru.arc.network.repos.ItemList
import java.util.UUID

class CustomLootDataTest :
    KotestTestBase({
        describe("CustomLootData") {
            it("cloud edits remove from exact slots and reject stale or added loot atomically") {
                val loot = lootWithAmounts(4, 4)
                val before = loot.snapshotItems()
                loot.compareAndSetItems(before, listOf(ItemStack(Material.DIAMOND, 5), null)).shouldBeFalse()
                loot.snapshotItems() shouldBe before
                val after = listOf(ItemStack(Material.DIAMOND, 2), null)
                loot.compareAndSetItems(before, after).shouldBeTrue()
                after[0]?.amount = 1
                loot.snapshotItems()[0]?.amount shouldBe 2
                loot.compareAndSetItems(before, listOf(null, null)).shouldBeFalse()
                loot.snapshotItems()[0]?.amount shouldBe 2
            }

            it("copies persisted state on merge") {
                val playerId = UUID.randomUUID()
                val chestId = UUID.randomUUID()
                val local =
                    CustomLootData(
                        playerUuid = playerId,
                        chestUuid = chestId,
                        timestamp = 1,
                        filled = false,
                    )
                val remoteItems = ItemList().apply { add(null) }
                val remote =
                    CustomLootData(
                        playerUuid = playerId,
                        chestUuid = chestId,
                        timestamp = 2,
                        items = remoteItems,
                        filled = true,
                    )

                local.merge(remote)

                local.timestamp shouldBe 2
                local.filled.shouldBeTrue()
                local.items shouldBe remoteItems
            }

            it("removes the requested amount across matching slots without over-removing") {
                val loot = lootWithAmounts(6, 6, 6)

                loot.removeItem(ItemStack(Material.DIAMOND, 10), 0).shouldBeTrue()

                loot.items.filterNotNull().sumOf(ItemStack::getAmount) shouldBe 8
            }

            it("does not mutate loot when the complete requested amount is unavailable") {
                val loot = lootWithAmounts(4, 4)

                loot.removeItem(ItemStack(Material.DIAMOND, 10), 0).shouldBeFalse()

                loot.items.filterNotNull().map(ItemStack::getAmount) shouldBe listOf(4, 4)
            }

            it("falls back to another matching slot when the preferred slot changed") {
                val items =
                    ItemList().apply {
                        add(ItemStack(Material.GOLD_INGOT, 3))
                        add(ItemStack(Material.DIAMOND, 7))
                    }
                val loot = CustomLootData(items = items, filled = true)

                loot.removeItem(ItemStack(Material.DIAMOND, 5), 0).shouldBeTrue()

                loot.items[0]?.amount shouldBe 3
                loot.items[1]?.amount shouldBe 2
            }

            it("treats filled loot with only empty slots as removable") {
                val loot =
                    CustomLootData(
                        items = ItemList().apply { add(null) },
                        filled = true,
                    )

                loot.shouldRemove().shouldBeTrue()
            }

            it("fills only once and keeps detached item snapshots") {
                val source = ItemStack(Material.DIAMOND, 3)
                val loot = CustomLootData()

                loot.fillIfEmpty(listOf(source)).shouldBeTrue()
                loot.fillIfEmpty(listOf(ItemStack(Material.GOLD_INGOT))).shouldBeFalse()
                source.amount = 1
                val snapshot = loot.snapshotItems()
                snapshot.single()?.amount = 2

                loot.items.single()?.type shouldBe Material.DIAMOND
                loot.items.single()?.amount shouldBe 3
            }

            it("parses stored UUIDs without throwing on corrupt metadata") {
                val valid = UUID.randomUUID()

                parsePersonalLootUuid(valid.toString()) shouldBe valid
                parsePersonalLootUuid("not-a-uuid").shouldBeNull()
                parsePersonalLootUuid(null).shouldBeNull()
            }

            it("ignores corrupt player UUID entries and removes duplicates") {
                val first = UUID.randomUUID()
                val second = UUID.randomUUID()

                parsePersonalLootPlayers("$first:::broken:::$second:::$first") shouldBe
                    setOf(first, second)
            }
        }
    })

private fun lootWithAmounts(vararg amounts: Int): CustomLootData =
    CustomLootData(
        items =
            ItemList().apply {
                amounts.forEach { add(ItemStack(Material.DIAMOND, it)) }
            },
        filled = true,
    )
