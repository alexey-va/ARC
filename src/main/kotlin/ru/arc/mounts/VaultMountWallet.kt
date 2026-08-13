package ru.arc.mounts

import net.milkbowl.vault.economy.Economy
import org.bukkit.Server
import java.util.UUID

class VaultMountWallet(
    private val server: Server,
    private val economy: Economy?,
) : MountWallet {
    override val available: Boolean get() = economy != null

    override fun balance(playerId: UUID): Double =
        economy?.getBalance(server.getOfflinePlayer(playerId)) ?: 0.0

    override fun withdraw(playerId: UUID, amount: Double): Boolean {
        val provider = economy ?: return false
        if (!validAmount(amount)) return false
        val player = server.getOfflinePlayer(playerId)
        return provider.has(player, amount) && provider.withdrawPlayer(player, amount).transactionSuccess()
    }

    override fun deposit(playerId: UUID, amount: Double): Boolean {
        val provider = economy ?: return false
        if (!validAmount(amount)) return false
        return provider.depositPlayer(server.getOfflinePlayer(playerId), amount).transactionSuccess()
    }

    private fun validAmount(amount: Double): Boolean = amount.isFinite() && amount > 0.0
}
