package ru.arc.itemcatalog

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import org.bukkit.Material
import org.bukkit.inventory.ItemStack
import ru.arc.paper.testing.MockBukkitTestRuntime
import java.nio.file.Files

class SystemBookRewardPathsTest : StringSpec({
    "system book rewards accept legacy filenames and nested catalogue IDs in both archive formats" {
        listOf(
            "starter-house.schem", "starter-house", "atelier/stonework-home",
            "players/baton_house.schem", "civs/houses/alpine-1",
        ).forEach { id ->
            val command = "arcbuilder:builder systembook %player% $id"
            SYSTEM_BUILD_BOOK_COMMAND.matches(command) shouldBe true
            systemBookRecipes(command).forEach { it.validate(validateBukkitStacks = false) }
        }
    }

    "system book reward commands reject paths and extra command syntax outside the catalogue grammar" {
        listOf(
            "../house", "/atelier/house", "atelier//house", "atelier/../house",
            "atelier/./house", "atelier/", "atelier\\house", "house.schematic",
            "house.schem extra", "house;give @a diamond", "house\ngive @a diamond", "",
        ).forEach { id ->
            val command = "arcbuilder:builder systembook %player% $id"
            SYSTEM_BUILD_BOOK_COMMAND.matches(command) shouldBe false
            systemBookRecipes(command).forEach { recipe ->
                shouldThrow<IllegalArgumentException> { recipe.validate(validateBukkitStacks = false) }
            }
        }
    }

    "old and nested system book archives reload without changing commands or fingerprints" {
        MockBukkitTestRuntime.open().use {
            val root = Files.createTempDirectory("arc-system-book-paths")
            try {
                val archive = FrozenPhysicalRewards(root)
                val prepared = listOf("stone-cabin.schem", "atelier/stone-cabin", "players/baton_house.schem")
                    .flatMap { id -> systemBookRecipes("arcbuilder:builder systembook %player% $id") }
                    .mapIndexed { index, recipe ->
                        val materialization = requireNotNull(archive.prepare("treasure:blueprints:$index", recipe, ItemStack(Material.PAPER)))
                        val record = requireNotNull(archive.find(materialization.sourceKey))
                        val path = root.resolve("data/reward-physical-archive/${record.fingerprint}.json")
                        Triple(record, path, Files.readString(path))
                    }

                val reloaded = FrozenPhysicalRewards(root)
                prepared.forEach { (record, path, serialized) ->
                    reloaded.find(record.key) shouldBe record
                    Files.readString(path) shouldBe serialized
                    reloaded.find(record.key)?.fingerprint shouldBe record.fingerprint
                }
            } finally {
                root.toFile().deleteRecursively()
            }
        }
    }
})

private fun systemBookRecipes(command: String) = listOf(
    FrozenPhysicalRecipe(type = "command", commandKind = "arcbuilder", commandValue = command),
    FrozenPhysicalRecipe(
        type = "treasure",
        treasure = FrozenTreasureNode("book", "command", 1, commands = listOf(command)),
    ),
)
