package ru.arc.stock

import net.milkbowl.vault.economy.Economy
import org.bukkit.Bukkit
import org.bukkit.entity.Player
import ru.arc.audit.AuditManager
import ru.arc.audit.Type
import ru.arc.configs.StockConfig
import ru.arc.core.modules.EconomyModule
import ru.arc.repository.CachedRepository
import ru.arc.util.Logging.error
import ru.arc.util.TextUtil
import ru.arc.util.TextUtil.formatAmount
import ru.arc.util.TextUtil.mm
import ru.arc.xserver.announcements.AnnounceManager
import java.util.UUID

object StockPlayerManager {

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
            AuditManager.operation(stockPlayer.playerName, gave, Type.DIVIDEND, symbol)
            val message = StockConfig.string("message.received-dividend")
                .replace("<amount>", formatAmount(gave))
                .replace("<symbol>", symbol)
            AnnounceManager.sendMessageGlobally(stockPlayer.playerUuid, message)
        }
    }

    suspend fun getOrCreate(player: Player): StockPlayer {
        return playerRepo.getOrCreate(player.uniqueId.toString()) {
            StockPlayer(player.name, player.uniqueId)
        }.getOrNull() ?: StockPlayer(player.name, player.uniqueId)
    }

    @JvmStatic fun buyStock(stockPlayer: StockPlayer, stock: Stock, amount: Double, leverage: Int, lowerBound: Double, upperBound: Double) {
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

        AuditManager.operation(stockPlayer.playerName, -response.totalPrice, Type.STOCK, "Buy ${stock.symbol}")
    }

    @JvmStatic fun shortStock(stockPlayer: StockPlayer, stock: Stock, amount: Double, leverage: Int, lowerBound: Double, upperBound: Double) {
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

        AuditManager.operation(stockPlayer.playerName, -response.totalPrice, Type.STOCK, "Short ${stock.symbol}")
    }

    @JvmStatic fun closePosition(stockPlayer: StockPlayer, symbol: String, positionUuid: UUID, reason: Int) {
        val stock = StockMarket.stock(symbol) ?: run {
            error("Could not find stock with symbol: $symbol")
            return
        }
        val position = stockPlayer.remove(symbol, positionUuid)
        if (position != null) {
            val gains = position.gains(stock.price)
            stockPlayer.addToBalance(gains + position.startPrice * position.amount, true)
            playerRepo.markDirty(stockPlayer)

            AuditManager.operation(stockPlayer.playerName, gains, Type.STOCK, "Close $symbol")

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
        val cost = cost(stock, amount)
        val commission = commission(stock, amount, leverage)
        val balance = player.getBalance()
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
}
