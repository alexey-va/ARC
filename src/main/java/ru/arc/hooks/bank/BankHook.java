package ru.arc.hooks.bank;

import me.dablakbandit.bank.api.BankAPI;
import org.bukkit.entity.Player;

public class BankHook {

    public BankHook() {

    }

    public double offlineBalance(String name) {
        //info("Offline bank balance of " + name + " is " + balance);
        return BankAPI.getInstance().getMoney(name);
    }

    public double balance(Player player) {
        return BankAPI.getInstance().getMoney(player);
    }

}
