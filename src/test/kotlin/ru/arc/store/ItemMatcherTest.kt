package ru.arc.store

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import org.bukkit.Material
import org.bukkit.inventory.ItemStack
import ru.arc.KotestTestBase
import ru.arc.util.withCustomModelData

class ItemMatcherTest :
    KotestTestBase({
        describe("ItemMatcher") {
            it("matches an exact material") {
                val matcher = ItemMatcher.of(Material.BARRIER)

                matcher.matches(ItemStack(Material.BARRIER)).shouldBeTrue()
                matcher.matches(ItemStack(Material.STONE)).shouldBeFalse()
            }

            it("matches material names without depending on enum case") {
                val matcher = ItemMatcher.ofRegex(".*SHULKER.*")

                matcher.matches(ItemStack(Material.SHULKER_BOX)).shouldBeTrue()
                matcher.matches(ItemStack(Material.CHEST)).shouldBeFalse()
            }

            it("rejects an invalid material pattern when the matcher is created") {
                shouldThrow<IllegalArgumentException> {
                    ItemMatcher.ofRegex("[")
                }
            }

            it("rejects a blank material pattern") {
                shouldThrow<IllegalArgumentException> {
                    ItemMatcher.ofRegex("  ")
                }
            }

            it("requires every configured custom NBT tag") {
                val existingTags = setOf("first", "second")

                ItemMatcher
                    .ofNbt("first", "second")
                    .matchesCustomNbtTags(existingTags::contains)
                    .shouldBeTrue()
                ItemMatcher
                    .ofNbt("first", "missing")
                    .matchesCustomNbtTags(existingTags::contains)
                    .shouldBeFalse()
            }

            it("matches presence and absence of custom model data") {
                val modeled = ItemStack(Material.STICK).withCustomModelData(42)
                val plain = ItemStack(Material.STICK)

                ItemMatcher.modelData(true).matches(modeled).shouldBeTrue()
                ItemMatcher.modelData(true).matches(plain).shouldBeFalse()
                ItemMatcher.modelData(false).matches(modeled).shouldBeFalse()
                ItemMatcher.modelData(false).matches(plain).shouldBeTrue()
            }

            it("does not classify items as Slimefun when the hook is unavailable") {
                ItemMatcher.sfItem(true).matches(ItemStack(Material.STONE)).shouldBeFalse()
                ItemMatcher.sfItem(false).matches(ItemStack(Material.STONE)).shouldBeFalse()
            }

            it("an empty matcher never matches") {
                ItemMatcher().matches(ItemStack(Material.STONE)).shouldBeFalse()
                ItemMatcher().matchesCustomNbtTags { true }.shouldBeFalse()
            }
        }
    })
