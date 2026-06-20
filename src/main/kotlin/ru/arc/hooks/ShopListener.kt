package ru.arc.hooks

import me.gypopo.economyshopgui.api.events.PostTransactionEvent
import me.gypopo.economyshopgui.objects.ShopItem
import me.gypopo.economyshopgui.util.Transaction
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import ru.arc.audit.AuditManager
import ru.arc.audit.Type

class ShopListener : Listener {

    private val sellTypes = setOf(
        Transaction.Type.SELL_ALL_COMMAND,
        Transaction.Type.SELL_SCREEN,
        Transaction.Type.SELL_GUI_SCREEN,
        Transaction.Type.QUICK_SELL,
        Transaction.Type.AUTO_SELL_CHEST,
        Transaction.Type.SHOPSTAND_SELL_SCREEN,
        Transaction.Type.SELL_ALL_SCREEN,
    )

    private val buyTypes = setOf(
        Transaction.Type.BUY_SCREEN,
        Transaction.Type.BUY_STACKS_SCREEN,
        Transaction.Type.QUICK_BUY,
        Transaction.Type.SHOPSTAND_BUY_SCREEN,
    )

    @EventHandler
    fun onShopSell(event: PostTransactionEvent) {
        if (!sellTypes.contains(event.transactionType)) return
        if (event.transactionResult != Transaction.Result.SUCCESS) return
        val playerName = event.player.name
        val amount = event.price
        val comment = comment(event.items)
        AuditManager.operation(playerName, amount, Type.SHOP, "Sold $comment")
    }

    @EventHandler
    fun onShopBuy(event: PostTransactionEvent) {
        if (!buyTypes.contains(event.transactionType)) return
        if (event.transactionResult != Transaction.Result.SUCCESS) return
        val playerName = event.player.name
        val amount = event.price
        val appendix = event.shopItem?.shopItem?.type?.name ?: "unknown"
        AuditManager.operation(playerName, -amount, Type.SHOP, "Bought $appendix")
    }

    private fun comment(map: Map<ShopItem, Int>?): String {
        if (map.isNullOrEmpty()) return "unknown"
        val sb = StringBuilder()
        for ((item, count) in map) {
            sb.append(count).append(' ').append(item.shopItem.type).append(", ")
        }
        if (sb.length > 2) sb.setLength(sb.length - 2)
        return sb.toString()
    }
}
