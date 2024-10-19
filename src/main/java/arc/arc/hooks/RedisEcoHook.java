package arc.arc.hooks;

import arc.arc.ARC;
import dev.unnm3d.rediseconomy.api.RedisEconomyAPI;
import org.bukkit.Bukkit;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

public class RedisEcoHook {

    public record Account(String name, UUID uuid, double balance) {
    }

    private static RedisEcoListener listener;

    public RedisEcoHook() {
        if (listener == null) {
            listener = new RedisEcoListener();
            Bukkit.getPluginManager().registerEvents(listener, ARC.plugin);
        }
    }

    public CompletableFuture<List<Account>> getTopAccounts(int n) {
        if (RedisEconomyAPI.getAPI() == null) return null;
        var currency = RedisEconomyAPI.getAPI().getDefaultCurrency();

        return currency.getOrderedAccounts(n)
                .thenApply(balances -> {
                    List<Account> accounts = new ArrayList<>();
                    Object o = balances.get(0);
                    try {
                        Method getValueMethod = o.getClass().getMethod("getValue");
                        Method getScoreMethod = o.getClass().getMethod("getScore");
                        for (Object tuple : balances) {
                            try {
                                Object valueResult = getValueMethod.invoke(tuple);
                                Object scoreResult = getScoreMethod.invoke(tuple);

                                // Convert the extracted values to the types you expect
                                String valueAsString = String.valueOf(valueResult);
                                UUID uuid = UUID.fromString(valueAsString);
                                String username = RedisEconomyAPI.getAPI().getUsernameFromUUIDCache(uuid);
                                double amount = (Double) scoreResult; // Cast score result to Double, ensure this is the correct type

                                // Add the new Account object to the list
                                accounts.add(new Account(username == null ? valueAsString + "-Unknown" : username, uuid, amount));
                                //System.out.println("Added account: " + username + " with balance: " + amount);
                            } catch (Exception e) {
                                e.printStackTrace();
                            }
                        }
                        return accounts;
                    } catch (Exception e) {
                        e.printStackTrace();
                        return null;
                    }
                }).toCompletableFuture();
    }
}
