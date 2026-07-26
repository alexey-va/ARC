package ru.arc.stock

import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.shouldBe
import org.bukkit.Material
import ru.arc.config.StockConfig
import ru.arc.hooks.HookRegistry
import java.util.TreeMap
import java.util.UUID

class StockPlayerLimitTest : FreeSpec({
    val playerUuid = UUID.randomUUID()

    fun position() =
        Position(
            symbol = "AAPL",
            startPrice = 100.0,
            leverage = 1.0,
            upperBoundMargin = 0.0,
            lowerBoundMargin = 0.0,
            commission = 0.0,
            timestamp = 0L,
            type = Position.Type.BOUGHT,
            amount = 1.0,
            iconMaterial = Material.PAPER,
        )

    beforeEach {
        HookRegistry.luckPermsHook = null
        StockConfig.defaultStockMaxAmount = 5
        StockConfig.permissionMap = TreeMap()
    }

    afterEach {
        HookRegistry.luckPermsHook = null
    }

    "maxStockAmount falls back to the configured default when LuckPerms is unavailable" {
        StockPlayer(playerUuid = playerUuid).maxStockAmount() shouldBe 5
    }

    "a higher granted tier allows positions even when the nearest tier is denied" {
        StockConfig.permissionMap =
            TreeMap<Int, String>().apply {
                this[4] = "stock.max.4"
                this[10] = "stock.max.10"
            }
        val player =
            StockPlayer(playerUuid = playerUuid).apply {
                repeat(4) { addPosition(position()) }
            }

        val hasPermission: (String) -> Boolean = { permission -> permission == "stock.max.10" }
        player.maxStockAmount(hasPermission) shouldBe 10
        player.isBelowMaxStockAmount(hasPermission).shouldBeTrue()
    }

    "a granted tier below the default does not reduce the configured limit" {
        StockConfig.permissionMap =
            TreeMap<Int, String>().apply {
                this[3] = "stock.max.3"
            }

        StockPlayer(playerUuid = playerUuid).maxStockAmount { true } shouldBe 5
    }
})
