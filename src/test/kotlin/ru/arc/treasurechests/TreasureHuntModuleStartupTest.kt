package ru.arc.treasurechests

import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.nulls.shouldNotBeNull
import ru.arc.KotestTestBase
import ru.arc.config.ConfigManager
import ru.arc.treasure.core.Treasures

/**
 * Ensures [TreasureHuntRegistry] is initialized from [ru.arc.core.modules.TreasureModule]
 * (not only from tests calling [TreasureHuntRegistry.init] manually).
 */
class TreasureHuntModuleStartupTest : KotestTestBase({

    it("should expose hunt registry defaults after plugin enable") {
        TreasureHuntRegistry.getDefaultStartMessage().shouldNotBeNull()
        TreasureHuntRegistry.getDefaultStopMessage().shouldNotBeNull()
    }

    it("should reload hunt types from disk after loadTreasureHuntTypes") {
        ConfigManager.moduleYamlPath(plugin.dataFolder.toPath(), "treasure-hunt.yml").toFile().writeText(
            """
            treasure-hunt-types:
              module-smoke-hunt:
                location-pool-id: none
                chest-types:
                  default:
                    type: VANILLA
                    treasure-pool-id: module-smoke-treasure
                    weight: 1
            """.trimIndent()
        )
        Treasures.getOrCreate("module-smoke-treasure")
        ConfigManager.reloadAll()
        TreasureHuntManager.loadTreasureHuntTypes()

        TreasureHuntManager.getTreasureHuntTypes() shouldContain "module-smoke-hunt"
    }
})
