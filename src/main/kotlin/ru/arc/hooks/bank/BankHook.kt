package ru.arc.hooks.bank

import me.dablakbandit.bank.api.BankAPI
import org.bukkit.entity.Player

class BankHook {
    fun offlineBalance(name: String): Double = BankAPI.getInstance().getMoney(name)
    fun balance(player: Player): Double = BankAPI.getInstance().getMoney(player)
}
