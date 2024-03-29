package arc.arc.hooks;

import dev.unnm3d.rediseconomy.api.RedisEconomyAPI;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

public class RedisEcoHook {

    public record Account(String name, UUID uuid, double balance) {
    }

    public CompletableFuture<List<Account>> getTopAccounts(int n) {
        if (RedisEconomyAPI.getAPI() == null) return null;
        var currency = RedisEconomyAPI.getAPI().getDefaultCurrency();

        return currency.getOrderedAccounts(n)
                .thenApply(balances -> {
                    List<Account> accounts = new ArrayList<>();
                    for (Object tuple : balances) {
                        try {
                            Method getValueMethod = tuple.getClass().getMethod("getValue");
                            Object valueResult = getValueMethod.invoke(tuple);
                            Method getScoreMethod = tuple.getClass().getMethod("getScore");
                            Object scoreResult = getScoreMethod.invoke(tuple);

                            // Convert the extracted values to the types you expect
                            String valueAsString = String.valueOf(valueResult);
                            UUID uuid = UUID.fromString(valueAsString);
                            String username = RedisEconomyAPI.getAPI().getUsernameFromUUIDCache(uuid);
                            double amount = (Double) scoreResult; // Cast score result to Double, ensure this is the correct type

                            // Add the new Account object to the list
                            accounts.add(new Account(username == null ? valueAsString + "-Unknown" : username, uuid, amount));
                            //System.out.println("Added account: " + username + " with balance: " + amount);
                        } catch (Exception e){
                            e.printStackTrace();
                        }
                    }
                    return accounts;
                }).toCompletableFuture();
    }
}
