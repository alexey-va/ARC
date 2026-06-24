package ru.arc.stock

import net.kyori.adventure.text.minimessage.tag.Tag
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver
import org.bukkit.Bukkit
import org.bukkit.OfflinePlayer
import org.bukkit.entity.Player
import ru.arc.config.StockConfig
import ru.arc.hooks.HookRegistry
import ru.arc.repository.Entity
import ru.arc.util.Logging.debug
import ru.arc.util.TextUtil
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList

class StockPlayer(
    var playerName: String = "",
    var playerUuid: UUID = UUID.randomUUID(),
    var positionMap: MutableMap<String, MutableList<Position>> = HashMap(),
    private var balance: Double = 0.0,
    var autoTake: Boolean = true,
    var totalGains: Double = 0.0,
    var receivedDividend: Double = 0.0,
) : Entity {

    fun getBalance(): Double = balance

    fun setBalance(value: Double) {
        balance = value
    }

    @Synchronized
    fun addToBalance(add: Double, fromPosition: Boolean) {
        balance += add
        if (fromPosition) totalGains += add
    }

    @Synchronized
    fun totalBalance(): Double {
        val fromPositions = positionMap.values
            .flatMap { list -> list.map { it.totalValue() } }
            .fold(0.0, Double::plus)
        return balance + fromPositions
    }

    @Synchronized
    fun giveDividend(symbol: String): Double {
        if (!positionMap.containsKey(symbol)) return 0.0
        debug("Giving dividend for {} to {}", symbol, playerName)
        val stock = StockMarket.stock(symbol) ?: return 0.0
        if (stock.dividend < 0.00001) return 0.0

        var gave = 0.0
        for (position in positionMap[symbol]!!) {
            val dividend = stock.dividend * position.amount
            if (dividend == 0.0) continue
            balance += dividend
            receivedDividend += dividend
            gave += dividend
            position.receivedDividend += dividend
        }
        return gave
    }

    @Synchronized
    fun find(symbol: String, uuid: UUID): Position? =
        positionMap[symbol]?.firstOrNull { it.positionUuid == uuid }

    fun isBelowMaxStockAmount(): Boolean {
        val currentAmount = positions().size
        if (currentAmount < StockConfig.defaultStockMaxAmount) return true
        val entry = StockConfig.permissionMap.ceilingEntry(currentAmount + 1) ?: return false
        val offlinePlayer = Bukkit.getOfflinePlayer(playerUuid)
        if (!offlinePlayer.isOnline && HookRegistry.luckPermsHook == null) return false
        return HookRegistry.luckPermsHook!!.hasPermission(offlinePlayer, entry.value)
    }

    fun maxStockAmount(): Int {
        var max = -1
        val offlinePlayer = Bukkit.getOfflinePlayer(playerUuid)
        if (!offlinePlayer.isOnline && HookRegistry.luckPermsHook == null) return -1
        for (entry in StockConfig.permissionMap.entries) {
            if (HookRegistry.luckPermsHook!!.hasPermission(offlinePlayer, entry.value) && entry.key > max) {
                max = entry.key
            }
        }
        return if (max == -1) StockConfig.defaultStockMaxAmount else max
    }

    fun tagResolver(): TagResolver {
        val bal = balance
        val total = totalBalance()
        return TagResolver.builder()
            .resolver(TagResolver.resolver("balance", Tag.inserting(TextUtil.mm(TextUtil.formatAmount(bal), true))))
            .resolver(TagResolver.resolver("name", Tag.inserting(TextUtil.mm(playerName, true))))
            .resolver(TagResolver.resolver("position_amount", Tag.inserting(TextUtil.mm("${positions().size}", true))))
            .resolver(TagResolver.resolver("uuid", Tag.inserting(TextUtil.mm(playerUuid.toString().split("-")[0], true))))
            .resolver(TagResolver.resolver("auto_take", Tag.inserting(TextUtil.mm(if (autoTake) "<green>Да" else "<red>Нет", true))))
            .resolver(TagResolver.resolver("received_dividends", Tag.inserting(TextUtil.mm(TextUtil.formatAmount(receivedDividend), true))))
            .resolver(TagResolver.resolver("total_balance", Tag.inserting(TextUtil.mm(TextUtil.formatAmount(total), true))))
            .resolver(TagResolver.resolver("gains", Tag.inserting(TextUtil.mm(TextUtil.formatAmount(total - bal), true))))
            .resolver(TagResolver.resolver("total_gains", Tag.inserting(TextUtil.mm(TextUtil.formatAmount(totalGains), true))))
            .resolver(TagResolver.resolver("position_count", Tag.inserting(TextUtil.mm("${positions().size}", true))))
            .build()
    }

    @Synchronized
    fun remove(symbol: String, uuid: UUID): Position? {
        val list = positionMap[symbol] ?: return null
        val position = list.firstOrNull { it.positionUuid == uuid } ?: return null
        list.remove(position)
        if (list.isEmpty()) positionMap.remove(symbol)
        return position
    }

    @Synchronized
    fun addPosition(position: Position) {
        positionMap.getOrPut(position.symbol) { CopyOnWriteArrayList() }.add(position)
    }

    fun updateAutoTake(newValue: Boolean) {
        if (newValue == autoTake) return
        autoTake = newValue
    }

    @Synchronized
    fun positions(symbol: String): List<Position>? = positionMap[symbol]

    @Synchronized
    fun positions(): List<Position> = positionMap.values.flatten()

    @Synchronized
    fun totalGainsList(): Double = positionMap.values
        .flatMap { it.map(Position::gains) }
        .fold(0.0, Double::plus)

    fun player(): Player? {
        val offlinePlayer = Bukkit.getOfflinePlayer(playerUuid)
        return offlinePlayer as? Player
    }

    override fun id(): String = playerUuid.toString()
}
