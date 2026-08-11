package ru.arc.hooks.bank

import me.dablakbandit.bank.api.BankAPI
import org.bukkit.entity.Player

class BankHook {
    data class Account(
        val balance: Double,
        val pendingInterest: Double,
    )

    fun offlineBalance(name: String): Double = BankAPI.getInstance().getMoney(name)
    fun balance(player: Player): Double = BankAPI.getInstance().getMoney(player)

    /**
     * Public BankAPI signatures are binary-identical in compileOnly 4.6.9 and
     * the verified live 5.0.3-RELEASE artifact. Pending offline interest is
     * part of bank supply until Bank moves it into the available balance.
     */
    fun account(playerId: String, knownName: String?): Account {
        val api = BankAPI.getInstance()
        val identifier =
            knownName?.takeIf(String::isNotBlank)
                ?: api.getUsername(playerId)?.takeIf(String::isNotBlank)
                ?: playerId
        return Account(
            balance = api.getMoney(identifier),
            pendingInterest = api.getOfflineMoney(identifier),
        )
    }
}
