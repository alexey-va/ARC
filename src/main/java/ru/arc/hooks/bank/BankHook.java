package ru.arc.hooks.bank;

import lombok.extern.log4j.Log4j2;
import me.dablakbandit.bank.api.BankAPI;
import org.bukkit.entity.Player;

@Log4j2
public class BankHook {

    public BankHook() {

    }

    public double offlineBalance(String name) {
        //log.info("Offline bank balance of " + name + " is " + balance);
        return BankAPI.getInstance().getMoney(name);
    }

    public double balance(Player player) {
        return BankAPI.getInstance().getMoney(player);
    }

}
