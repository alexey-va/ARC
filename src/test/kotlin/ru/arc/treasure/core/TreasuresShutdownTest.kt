package ru.arc.treasure.core

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import ru.arc.paper.testing.MockBukkitTestRuntime
import java.nio.file.Files

class TreasuresShutdownTest : StringSpec({
    "shutdown preserves a deployed clean pool while saving an edited pool" {
        MockBukkitTestRuntime.open().use {
            val directory = Files.createTempDirectory("treasure-shutdown").toFile()
            val cache = directory.resolve("weekly_map_cache.yml")
            val original = "id: weekly_map_cache\ntreasures:\n- type: sub-pool\n  id: rare_find\n  weight: 1\n  poolId: vanilla_rare\n"
            val deployed = original.replace("vanilla_rare", "weekly_map_valuables")
            cache.writeText(original)
            val manager = TreasureManager()
            manager.loadFrom(directory)
            manager.getPool("weekly_map_cache")!!.isDirty shouldBe false
            manager.createPool("edited")
            cache.writeText(deployed)

            val managerField = Treasures::class.java.getDeclaredField("_manager").apply { isAccessible = true }
            val directoryField = Treasures::class.java.getDeclaredField("dataDir").apply { isAccessible = true }
            val oldManager = managerField.get(Treasures)
            val oldDirectory = directoryField.get(Treasures)
            try {
                managerField.set(Treasures, manager)
                directoryField.set(Treasures, directory)
                Treasures.shutdown()
                cache.readText() shouldBe deployed
                directory.resolve("edited.yml").exists() shouldBe true
                manager.getPool("edited")!!.isDirty shouldBe false
                val restarted = TreasureManager()
                restarted.loadFrom(directory)
                (restarted.getPool("weekly_map_cache")!!.treasures.single() as Treasure.SubPool).poolId shouldBe "weekly_map_valuables"
            } finally {
                managerField.set(Treasures, oldManager)
                directoryField.set(Treasures, oldDirectory)
                directory.deleteRecursively()
            }
        }
    }
})
