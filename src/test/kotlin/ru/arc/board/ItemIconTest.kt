package ru.arc.board

import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.bukkit.Material
import ru.arc.KotestTestBase

@Suppress("DEPRECATION")
class ItemIconTest : KotestTestBase({

    describe("ItemIcon factory methods") {

        it("should create player head icon from UUID") {
            val uuid = java.util.UUID.randomUUID()
            val icon = ItemIcon.of(uuid)

            icon.material shouldBe Material.PLAYER_HEAD
            icon.headUuid shouldBe uuid
            icon.modelData shouldBe 0
        }

        it("should create material icon with model data") {
            val icon = ItemIcon.of(Material.DIAMOND_SWORD, 42)

            icon.material shouldBe Material.DIAMOND_SWORD
            icon.headUuid shouldBe null
            icon.modelData shouldBe 42
        }

        it("should create material icon with zero model data") {
            val icon = ItemIcon.of(Material.PAPER, 0)

            icon.material shouldBe Material.PAPER
            icon.modelData shouldBe 0
        }
    }

    describe("ItemIcon equality") {

        it("should be equal when all fields match") {
            val uuid = java.util.UUID.randomUUID()
            val a = ItemIcon(Material.PLAYER_HEAD, uuid, 0)
            val b = ItemIcon(Material.PLAYER_HEAD, uuid, 0)

            a shouldBe b
        }

        it("should not be equal when material differs") {
            val a = ItemIcon(Material.DIAMOND, null, 0)
            val b = ItemIcon(Material.EMERALD, null, 0)

            a shouldNotBe b
        }

        it("should not be equal when model data differs") {
            val a = ItemIcon(Material.PAPER, null, 1)
            val b = ItemIcon(Material.PAPER, null, 2)

            a shouldNotBe b
        }

        it("should not be equal when head UUID differs") {
            val a = ItemIcon(Material.PLAYER_HEAD, java.util.UUID.randomUUID(), 0)
            val b = ItemIcon(Material.PLAYER_HEAD, java.util.UUID.randomUUID(), 0)

            a shouldNotBe b
        }
    }

    describe("ItemIcon no-args constructor") {

        it("should create with default values") {
            val icon = ItemIcon()

            icon.material shouldBe Material.STONE
            icon.headUuid shouldBe null
            icon.modelData shouldBe 0
        }
    }

    describe("ItemIcon.stack()") {

        it("should return non-null stack for material") {
            val icon = ItemIcon.of(Material.DIAMOND, 0)

            val stack = icon.stack()

            stack shouldNotBe null
            stack.type shouldBe Material.DIAMOND
        }

        it("should set custom model data on stack when non-zero") {
            val icon = ItemIcon.of(Material.PAPER, 99)

            val stack = icon.stack()

            stack.itemMeta.customModelData shouldBe 99
        }

        it("should not set custom model data when zero") {
            val icon = ItemIcon.of(Material.DIAMOND, 0)

            val stack = icon.stack()

            stack.itemMeta.hasCustomModelData() shouldBe false
        }

        it("should return player head stack for player head material with null UUID") {
            val icon = ItemIcon(Material.PLAYER_HEAD, null, 0)

            val stack = icon.stack()

            stack shouldNotBe null
            stack.type shouldBe Material.PLAYER_HEAD
        }
    }

    describe("ItemIcon copy (data class)") {

        it("should create copy with changed material") {
            val original = ItemIcon.of(Material.DIAMOND, 5)
            val copy = original.copy(material = Material.EMERALD)

            original.material shouldBe Material.DIAMOND
            copy.material shouldBe Material.EMERALD
            copy.modelData shouldBe 5
        }
    }
})
