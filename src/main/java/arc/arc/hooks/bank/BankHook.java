package arc.arc.hooks.bank;

import lombok.extern.log4j.Log4j2;
import me.dablakbandit.bank.api.BankAPI;
import org.bukkit.entity.Player;

import java.util.UUID;

@Log4j2
public class BankHook {

    public BankHook() {

    }

    public double offlineBalance(String name) {
        double balance = BankAPI.getInstance().getOfflineMoney(name);
        //log.info("Offline bank balance of " + name + " is " + balance);
        return balance;
    }

    public double balance(Player player) {
        return BankAPI.getInstance().getMoney(player);
    }

}
