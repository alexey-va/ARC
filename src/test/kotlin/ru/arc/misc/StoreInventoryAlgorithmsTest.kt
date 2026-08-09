package ru.arc.misc

import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.bukkit.Material
import org.bukkit.inventory.ItemStack
import ru.arc.KotestTestBase

class StoreInventoryAlgorithmsTest :
    KotestTestBase({
        describe("vanilla store-to-player transfer") {
            it("uses the client-predicted reverse hotbar order") {
                val plan = VanillaPlayerStorageTransfer.planFull(emptyStorage(), ItemStack(Material.DIAMOND))

                plan?.updates?.map(StorageSlotUpdate::slot).shouldContainExactly(8)
                plan?.updates?.single()?.item?.amount shouldBe 1
            }

            it("splits large stacks through the same reverse order") {
                val plan = VanillaPlayerStorageTransfer.planFull(emptyStorage(), ItemStack(Material.DIAMOND, 65))

                plan?.updates?.map(StorageSlotUpdate::slot).shouldContainExactly(8, 7)
                plan?.updates?.map { it.item.amount }.shouldContainExactly(64, 1)
            }

            it("merges matching stacks before using an empty hotbar slot") {
                val storage = emptyStorage().also { it[35] = ItemStack(Material.DIAMOND, 63) }

                val plan = VanillaPlayerStorageTransfer.planFull(storage, ItemStack(Material.DIAMOND, 2))

                plan?.updates?.map(StorageSlotUpdate::slot).shouldContainExactly(35, 8)
                plan?.updates?.map { it.item.amount }.shouldContainExactly(64, 1)
            }

            it("returns no plan when the complete stack cannot fit") {
                val storage = Array<ItemStack?>(36) { ItemStack(Material.STONE, 64) }
                storage[8] = ItemStack(Material.DIAMOND, 63)

                VanillaPlayerStorageTransfer.planFull(storage, ItemStack(Material.DIAMOND, 2)).shouldBeNull()
                storage[8]?.amount shouldBe 63
                storage
                    .withIndex()
                    .filter { it.index != 8 }
                    .all { indexed ->
                        val item = indexed.value
                        item?.type == Material.STONE && item.amount == 64
                    } shouldBe true
            }
        }

        describe("store GUI state diff") {
            it("does not refresh cloned slots whose actual state is unchanged") {
                val previous = listOf(ItemStack(Material.DIAMOND, 3), null, ItemStack(Material.STONE, 2))
                val desired = previous.map { it?.clone() }

                StoreSlotDiff.changedSlots(previous, desired).shouldContainExactly()
            }

            it("returns only slots whose item state really changed") {
                val previous = listOf(ItemStack(Material.DIAMOND, 3), null, ItemStack(Material.STONE, 2))
                val desired = listOf(ItemStack(Material.DIAMOND, 2), ItemStack(Material.GOLD_INGOT), null)

                StoreSlotDiff.changedSlots(previous, desired).shouldContainExactly(0, 1, 2)
            }
        }
    })

private fun emptyStorage(): Array<ItemStack?> = arrayOfNulls(36)
