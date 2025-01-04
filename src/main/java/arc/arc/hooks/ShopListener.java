package arc.arc.hooks;

import arc.arc.audit.AuditManager;
import arc.arc.audit.Type;
import me.gypopo.economyshopgui.api.events.PostTransactionEvent;
import me.gypopo.economyshopgui.objects.ShopItem;
import me.gypopo.economyshopgui.util.Transaction;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;

import java.util.Map;
import java.util.Optional;
import java.util.Set;

public class ShopListener implements Listener {

    Set<Transaction.Type> sellTypes = Set.of(Transaction.Type.SELL_ALL_COMMAND,
            Transaction.Type.SELL_SCREEN, Transaction.Type.SELL_GUI_SCREEN, Transaction.Type.QUICK_SELL,
            Transaction.Type.AUTO_SELL_CHEST, Transaction.Type.SHOPSTAND_SELL_SCREEN, Transaction.Type.SELL_ALL_SCREEN);

    Set<Transaction.Type> buyTypes = Set.of(Transaction.Type.BUY_SCREEN, Transaction.Type.BUY_STACKS_SCREEN,
            Transaction.Type.QUICK_BUY, Transaction.Type.SHOPSTAND_BUY_SCREEN);


    @EventHandler
    public void onShopSell(PostTransactionEvent event) {
        if (!sellTypes.contains(event.getTransactionType())) return;
        if (event.getTransactionResult() != Transaction.Result.SUCCESS) return;
        //ShopItem shopItem = event.getShopItem();
        //if (shopItem == null) return;
        //ItemStack item = shopItem.getShopItem();
        //if (item == null) return;
        String playerName = event.getPlayer().getName();
        double amount = event.getPrice();
        String comment = comment(event.getItems());
        AuditManager.operation(playerName, amount, Type.SHOP, "Sold " + comment);
    }

    private String comment(Map<ShopItem, Integer> map) {
        if (map == null || map.isEmpty()) return "unknown";
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<ShopItem, Integer> entry : map.entrySet()) {
            sb.append(entry.getValue()).append(" ").append(entry.getKey().getShopItem().getType()).append(", ");
        }
        if (sb.length() > 2) {
            sb.setLength(sb.length() - 2);
        }
        return sb.toString();
    }

    @EventHandler
    public void onShopBuy(PostTransactionEvent event) {
        if (!buyTypes.contains(event.getTransactionType())) return;
        if (event.getTransactionResult() != Transaction.Result.SUCCESS) return;
        //ShopItem shopItem = event.getShopItem();
        //if (shopItem == null) return;
        //ItemStack item = shopItem.getShopItem();
        //if (item == null) return;
        String playerName = event.getPlayer().getName();
        double amount = event.getPrice();
        String appendix = Optional.ofNullable(event.getShopItem())
                .map(ShopItem::getShopItem)
                .map(i -> i.getType().name())
                .orElse("unknown");
        AuditManager.operation(playerName, -amount, Type.SHOP, "Bought " + appendix);
    }
}
