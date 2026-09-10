package ru.arc.eliteloot

import com.magmaguy.elitemobs.api.utils.EliteItemManager
import com.magmaguy.elitemobs.config.ItemSettingsConfig
import com.magmaguy.elitemobs.economy.EconomyHandler
import com.magmaguy.elitemobs.economy.VaultCompatibility
import com.magmaguy.elitemobs.items.ItemWorthCalculator
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import ru.arc.sync.EmSync
import ru.arc.sync.SyncManager
import java.util.concurrent.CompletableFuture

/** Loaded only on a backend with EliteMobs. The shared mailbox never links native EM types on parkour. */
internal object NativeLostLootCredit : LostLootCreditGateway {
    private fun sync() = SyncManager.getSync(EmSync::class.java) as? EmSync
    override fun ready(player: Player) = !VaultCompatibility.VAULT_ENABLED && sync()?.isReady(player.uniqueId) == true
    override fun hasReceipt(player: Player, token: String) = sync()?.lootCreditReceipt(player.uniqueId) == token
    override fun credit(player: Player, amount: Double, token: String): CompletableFuture<Unit> {
        val sync = requireNotNull(sync())
        check(player.isOnline && ready(player))
        require(amount.isFinite() && amount > 0)
        // Native EM owns crystal rounding and any gambling-debt repayment.
        EconomyHandler.addCurrency(player.uniqueId, amount)
        sync.markLootCredit(player.uniqueId, token)
        return sync.persistConfirmed(player.uniqueId).thenApply { Unit }
    }
    override fun persistReceipt(player: Player) = requireNotNull(sync()).persistConfirmed(player.uniqueId).thenApply { Unit }

    fun isElite(item: ItemStack) = EliteItemManager.isEliteMobsItem(item)
    fun mobSource(): String = ItemSettingsConfig.getMobItemSource()
    fun resale(item: ItemStack): Double? = (ItemWorthCalculator.determineResaleWorth(item, null) * item.amount)
        .takeIf { it.isFinite() && it > 0 }
}

internal interface LostLootCreditGateway {
    fun ready(player: Player): Boolean
    fun hasReceipt(player: Player, token: String): Boolean
    fun credit(player: Player, amount: Double, token: String): CompletableFuture<Unit>
    fun persistReceipt(player: Player): CompletableFuture<Unit>
}
