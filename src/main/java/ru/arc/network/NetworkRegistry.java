package ru.arc.network;

import ru.arc.hooks.HookRegistry;
import ru.arc.hooks.lands.LandsMessager;
import ru.arc.hooks.zauction.AuctionMessager;
import ru.arc.stock.HistoryManager;
import ru.arc.stock.HistoryMessager;
import ru.arc.xserver.playerlist.PlayerListMessager;
import lombok.RequiredArgsConstructor;

@RequiredArgsConstructor
public class NetworkRegistry {

    private final RedisManager redisManager;
    public static LandsMessager landsMessager;
    PlayerListMessager playerListMessager;

    public void init() {
        playerListMessager = new PlayerListMessager("arc.proxy_player_list");
        redisManager.registerChannelUnique(playerListMessager.channel(), playerListMessager);

        landsMessager = new LandsMessager(redisManager, "arc.lands_req", "arc.lands_response");
        landsMessager.init();
        redisManager.registerChannelUnique(landsMessager.getRespChannel(), landsMessager);
        redisManager.registerChannelUnique(landsMessager.getReqChannel(), landsMessager);

        HistoryMessager historyMessager = new HistoryMessager("arc.high_lows_update", redisManager);
        redisManager.registerChannelUnique(historyMessager.channel, historyMessager);
        HistoryManager.setMessager(historyMessager);

        if (HookRegistry.auctionHook != null) {
            AuctionMessager auctionMessager = new AuctionMessager("arc.auction_items", "arc.auction_items_all", redisManager);
            redisManager.registerChannelUnique(auctionMessager.channel, auctionMessager);
            redisManager.registerChannelUnique(auctionMessager.channelAll, auctionMessager);
            HookRegistry.auctionHook.setAuctionMessager(auctionMessager);
        }

        redisManager.init();
    }

}
