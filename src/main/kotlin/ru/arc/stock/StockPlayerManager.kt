package ru.arc.stock

import net.milkbowl.vault.economy.Economy
import org.bukkit.Bukkit
import org.bukkit.entity.Player
import ru.arc.audit.AuditManager
import ru.arc.audit.AuditMetadata
import ru.arc.audit.EconomyFlow
import ru.arc.audit.EconomySource
import ru.arc.audit.Type
import ru.arc.config.StockConfig
import ru.arc.core.modules.EconomyModule
import ru.arc.repository.CachedRepository
import ru.arc.util.Logging.error
import ru.arc.util.TextUtil
import ru.arc.util.TextUtil.formatAmount
import ru.arc.util.TextUtil.mm
import ru.arc.xserver.announcements.AnnounceManager
import java.util.UUID

object StockPlayerManager {

    private fun internalStockMetadata() =
        AuditMetadata(
            source = EconomySource.INTERNAL_STOCK,
            flow = EconomyFlow.INTERNAL,
            currency = "stock_balance",
            server = ru.arc.ARC.serverName ?: "unknown",
        )

    lateinit var playerRepo: CachedRepository<StockPlayer>

    @JvmStatic fun updateAllPositionsOf(symbol: String) {
        val stock = StockMarket.stock(symbol) ?: run {
            error("Stock $symbol is null while trying to update positions!")
            return
        }
        playerRepo.allNow()
            .filter { it.positionMap.containsKey(symbol) }
            .forEach { checkPosition(it, stock) }
    }

    private fun checkPosition(stockPlayer: StockPlayer, stock: Stock) {
        val positionsClone = stockPlayer.positions(stock.symbol)?.toList() ?: return
        for (position in positionsClone) {
            val bankruptResponse = position.bankrupt(stock.price, stockPlayer.getBalance())
            if (bankruptResponse.bankrupt) {
                if (stockPlayer.autoTake) {
                    val success = addToTradingBalanceFromVault(stockPlayer, bankruptResponse.total)
                    if (success) {
                        playerRepo.markDirty(stockPlayer)
                        continue
                    }
                }
                closePosition(stockPlayer, stock.symbol, position.positionUuid, 2)
                continue
            }

            val marginCall = position.marginCall(stock.price)
            if (marginCall != 0) {
                closePosition(stockPlayer, position.symbol, position.positionUuid, 1)
            }
        }
    }

    @JvmStatic fun giveDividend(symbol: String) {
        val stock = StockMarket.stock(symbol) ?: return
        for (stockPlayer in playerRepo.allNow()) {
            val gave = stockPlayer.giveDividend(symbol)
            if (gave <= 0.1) continue
            playerRepo.markDirty(stockPlayer)
            AuditManager.operation(stockPlayer.playerName, gave, Type.DIVIDEND, symbol, internalStockMetadata())
            val message = StockConfig.string("message.received-dividend")
                .replace("<amount>", formatAmount(gave))
                .replace("<symbol>", symbol)
            AnnounceManager.sendMessageGlobally(stockPlayer.playerUuid, message)
        }
    }

    suspend fun getOrCreate(player: Player): StockPlayer {
        return playerRepo.getOrCreate(player.uniqueId.toString()) {
            StockPlayer(player.name, player.uniqueId)
        }.getOrThrow()
    }

    @JvmStatic fun buyStock(stockPlayer: StockPlayer, stock: Stock, amount: Double, leverage: Int, lowerBound: Double, upperBound: Double) {
        if (!validOrder(stock, amount, leverage, lowerBound, upperBound)) return
        val stockPositions = stockPlayer.positions(stock.symbol)
        val canHaveMore = stockPlayer.isBelowMaxStockAmount() && (stockPositions == null || stockPositions.size < 9)
        if (!canHaveMore || stockPlayer.positions().size >= 30) {
            stockPlayer.player()?.sendMessage(mm(StockConfig.string("message.too-many-positions")))
            return
        }

        val response = economyCheck(stockPlayer, stock, amount, leverage)
        if (!response.success) return

        stockPlayer.addToBalance(-response.totalPrice, true)
        val position = Position(
            symbol = stock.symbol,
            startPrice = stock.price,
            leverage = leverage.toDouble(),
            upperBoundMargin = upperBound,
            lowerBoundMargin = lowerBound,
            commission = response.commission,
            timestamp = System.currentTimeMillis(),
            positionUuid = UUID.randomUUID(),
            type = Position.Type.BOUGHT,
            amount = amount,
            iconMaterial = ru.arc.util.RandomUtils.random(StockConfig.iconMaterials),
            receivedDividend = 0.0,
        )
        stockPlayer.addPosition(position)
        playerRepo.markDirty(stockPlayer)

        AuditManager.operation(stockPlayer.playerName, -response.totalPrice, Type.STOCK, "Buy ${stock.symbol}", internalStockMetadata())
    }

    @JvmStatic fun shortStock(stockPlayer: StockPlayer, stock: Stock, amount: Double, leverage: Int, lowerBound: Double, upperBound: Double) {
        if (!validOrder(stock, amount, leverage, lowerBound, upperBound)) return
        val stockPositions = stockPlayer.positions(stock.symbol)
        val canHaveMore = stockPlayer.isBelowMaxStockAmount() && (stockPositions == null || stockPositions.size < 9)
        if (!canHaveMore || stockPlayer.positions().size >= 30) {
            stockPlayer.player()?.sendMessage(mm(StockConfig.string("message.too-many-positions")))
            return
        }

        val response = economyCheck(stockPlayer, stock, amount, leverage)
        if (!response.success) return

        stockPlayer.addToBalance(-response.totalPrice, true)
        val position = Position(
            symbol = stock.symbol,
            startPrice = stock.price,
            leverage = leverage.toDouble(),
            upperBoundMargin = upperBound,
            lowerBoundMargin = lowerBound,
            commission = response.commission,
            timestamp = System.currentTimeMillis(),
            positionUuid = UUID.randomUUID(),
            type = Position.Type.SHORTED,
            amount = amount,
            iconMaterial = ru.arc.util.RandomUtils.random(StockConfig.iconMaterials),
            receivedDividend = 0.0,
        )
        stockPlayer.addPosition(position)
        playerRepo.markDirty(stockPlayer)

        AuditManager.operation(stockPlayer.playerName, -response.totalPrice, Type.STOCK, "Short ${stock.symbol}", internalStockMetadata())
    }

    @JvmStatic fun closePosition(stockPlayer: StockPlayer, symbol: String, positionUuid: UUID, reason: Int) {
        val stock = StockMarket.stock(symbol) ?: run {
            error("Could not find stock with symbol: $symbol")
            return
        }
        val candidate = stockPlayer.find(symbol, positionUuid)
        if (candidate != null && !validPosition(candidate, stock.price)) {
            error("Refusing to close invalid stock position {}", positionUuid)
            return
        }
        val position = stockPlayer.remove(symbol, positionUuid)
        if (position != null) {
            val gains = position.gains(stock.price)
            stockPlayer.addToBalance(gains + position.startPrice * position.amount, true)
            playerRepo.markDirty(stockPlayer)

            AuditManager.operation(stockPlayer.playerName, gains, Type.STOCK, "Close $symbol", internalStockMetadata())

            val message = StockConfig.string("message.closed-$reason")
                .replace("<gains>", formatAmount(gains - position.commission))
                .replace("<symbol>", symbol)
                .replace("<money_received>", formatAmount(gains + position.startPrice * position.amount))
            AnnounceManager.sendMessageGlobally(stockPlayer.playerUuid, message)
        } else {
            error("Could not find position with such id {}", positionUuid)
        }
    }

    @JvmStatic fun addToTradingBalanceFromVault(stockPlayer: StockPlayer, amount: Double): Boolean {
        if (!amount.isFinite() || amount == 0.0) return false
        val offlinePlayer = Bukkit.getOfflinePlayer(stockPlayer.playerUuid)
        val econ: Economy = EconomyModule.getEconomy() ?: return false

        return if (amount > 0) {
            if (econ.withdrawPlayer(offlinePlayer, amount).transactionSuccess()) {
                stockPlayer.addToBalance(amount, false)
                playerRepo.markDirty(stockPlayer)
                true
            } else false
        } else {
            if (stockPlayer.getBalance() < -amount) return false
            if (econ.depositPlayer(offlinePlayer, -amount).transactionSuccess()) {
                stockPlayer.addToBalance(amount, false)
                playerRepo.markDirty(stockPlayer)
                true
            } else false
        }
    }

    @JvmStatic fun switchAuto(stockPlayer: StockPlayer) {
        stockPlayer.updateAutoTake(!stockPlayer.autoTake)
        playerRepo.markDirty(stockPlayer)
    }

    @JvmStatic fun getNow(uniqueId: UUID): StockPlayer? = playerRepo.getNow(uniqueId.toString())

    data class EconomyCheckResponse(val success: Boolean, val totalPrice: Double, val lack: Double, val commission: Double)

    @JvmStatic fun economyCheck(player: StockPlayer, stock: Stock, amount: Double, leverage: Int): EconomyCheckResponse {
        if (!validOrder(stock, amount, leverage, 0.0, 0.0)) {
            return EconomyCheckResponse(false, 0.0, 0.0, 0.0)
        }
        val cost = cost(stock, amount)
        val commission = commission(stock, amount, leverage)
        val balance = player.getBalance()
        if (!balance.isFinite() || !cost.isFinite() || !commission.isFinite()) {
            return EconomyCheckResponse(false, 0.0, 0.0, 0.0)
        }
        return if (balance < cost + commission) {
            EconomyCheckResponse(false, cost + commission, cost + commission - balance, commission)
        } else {
            EconomyCheckResponse(true, cost + commission, 0.0, commission)
        }
    }

    @JvmStatic fun cost(stock: Stock, amount: Double): Double = stock.price * amount

    @JvmStatic fun commission(stock: Stock, amount: Double, leverage: Int): Double =
        cost(stock, amount) * StockConfig.commission *
            if (leverage < 100) 1.0
            else 1.0 + leverage.toDouble().pow(StockConfig.leveragePower) - 100.0.pow(StockConfig.leveragePower)

    private fun Double.pow(exp: Double): Double = Math.pow(this, exp)

    private fun validOrder(
        stock: Stock,
        amount: Double,
        leverage: Int,
        lowerBound: Double,
        upperBound: Double,
    ): Boolean {
        if (!StockMarket.isEnabledStock(stock)) return false
        if (!amount.isFinite() || amount <= 0.0 || !stock.price.isFinite() || stock.price <= 0.0) return false
        if (leverage < 1 || leverage > StockMarket.effectiveMaxLeverage(stock)) return false
        if (!lowerBound.isFinite() || lowerBound < 0.0 || !upperBound.isFinite() || upperBound < 0.0) return false
        val principal = stock.price * amount
        val exposure = principal * leverage
        return principal.isFinite() &&
            exposure.isFinite() &&
            principal <= StockMarket.effectiveMaxBuyPrice() &&
            exposure <= StockMarket.effectiveMaxLeveragedPrice()
    }

    private fun validPosition(position: Position, currentPrice: Double): Boolean {
        if (!position.amount.isFinite() || position.amount <= 0.0) return false
        if (!position.startPrice.isFinite() || position.startPrice <= 0.0) return false
        if (!position.leverage.isFinite() || position.leverage < 1.0) return false
        val gains = position.gains(currentPrice)
        val payout = gains + position.startPrice * position.amount
        return gains.isFinite() && payout.isFinite()
    }
}
