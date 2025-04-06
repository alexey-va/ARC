package ru.arc.hooks.zauction;

import fr.maxlego08.zauctionhouse.api.event.events.*;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;

public class AuctionListener implements Listener {

    @EventHandler
    public void onBuy(AuctionPostBuyEvent event){
        System.out.println("Buy: "+event);
    }

    @EventHandler
    public void onSell(AuctionSellEvent event){
        System.out.println("Sell: "+event);
    }

    @EventHandler
    public void onRemove(AuctionRemoveEvent event){
        System.out.println("Remove: "+event);
    }

    @EventHandler
    public void onRemove2(AuctionRetrieveEvent event){
        System.out.println("Retreive: "+event);
    }

    @EventHandler
    public void onExpire(AuctionItemExpireEvent event){
        System.out.println("Expire: "+event);
    }
}
