package ru.arc.hooks.bank

import me.dablakbandit.bank.api.BankAPI
import org.bukkit.entity.Player

class BankHook(
    private val accountReader: (String) -> Account = { playerId ->
        val api = BankAPI.getInstance()
        Account(
            balance = api.getMoney(playerId),
            pendingInterest = api.getOfflineMoney(playerId),
        )
    },
) {
    data class Account(
        val balance: Double,
        val pendingInterest: Double,
    )

    fun offlineBalance(name: String): Double = BankAPI.getInstance().getMoney(name)
    fun balance(player: Player): Double = BankAPI.getInstance().getMoney(player)

    /**
     * Bank 5.0.3 delegates its String overloads to CorePlayers(String), whose
     * verified runtime bytecode parses the value with UUID.fromString. A cached
     * username is display metadata, never a valid account identifier here.
     */
    @Suppress("UNUSED_PARAMETER")
    fun account(playerId: String, knownName: String?): Account = accountReader(playerId)
}
