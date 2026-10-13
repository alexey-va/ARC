package ru.arc.eliteloot

import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.maps.shouldBeEmpty
import io.kotest.matchers.shouldBe
import org.bukkit.Material

class EliteLootManagerSafetyTest : FreeSpec({
    beforeEach {
        EliteLootManager.shutdown()
    }

    afterEach {
        EliteLootManager.shutdown()
    }

    "addDecorItem rejects a non-persistent mutation before initialization" {
        EliteLootManager
            .addDecorItem(
                lootType = LootType.SWORD,
                material = Material.DIAMOND_SWORD,
                weight = 1.0,
                modelId = 42,
                color = null,
                iaNamespace = null,
                iaId = null,
            ).shouldBeFalse()

        EliteLootManager.map.shouldBeEmpty()
    }

    "shutdown is idempotent" {
        EliteLootManager.shutdown()
        EliteLootManager.shutdown()
        EliteLootManager.map.shouldBeEmpty()
    }

    "material families map to their loot type" {
        EliteLootManager.toLootType(Material.NETHERITE_AXE) shouldBe LootType.AXE
        EliteLootManager.toLootType(Material.CROSSBOW) shouldBe LootType.CROSSBOW
        EliteLootManager.toLootType(Material.LEATHER_CHESTPLATE) shouldBe LootType.CHESTPLATE
    }

    "unsupported and null items have no loot type" {
        EliteLootManager.toLootType(Material.STONE).shouldBe(null)
        EliteLootManager.toLootType(null).shouldBe(null)
    }
})
