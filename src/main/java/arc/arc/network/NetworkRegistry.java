package arc.arc.network;

import arc.arc.hooks.HookRegistry;
import arc.arc.hooks.lands.LandsMessager;
import arc.arc.hooks.zauction.AuctionMessager;
import arc.arc.stock.HistoryManager;
import arc.arc.stock.HistoryMessager;
import arc.arc.xserver.announcements.AnnounceManager;
import arc.arc.xserver.announcements.AnnouncementMessager;
import arc.arc.xserver.commands.CommandReceiver;
import arc.arc.xserver.commands.CommandSender;
import arc.arc.xserver.playerlist.PlayerListMessager;
import arc.arc.xserver.ranks.RankMessager;
import lombok.RequiredArgsConstructor;

@RequiredArgsConstructor
public class NetworkRegistry {

    private final RedisManager redisManager;

    public static LandsMessager landsMessager;

    PlayerListMessager playerListMessager;
    public static CommandSender commandSender;
    CommandReceiver commandReceiver;
    public static RankMessager rankMessager;


    public void init(){
        playerListMessager = new PlayerListMessager("arc.proxy_player_list");
        redisManager.registerChannel(playerListMessager.channel(), playerListMessager);

        landsMessager = new LandsMessager(redisManager, "arc.lands_req", "arc.lands_response");
        landsMessager.init();
        redisManager.registerChannel(landsMessager.getRespChannel(), landsMessager);
        redisManager.registerChannel(landsMessager.getReqChannel(), landsMessager);

        commandSender = new CommandSender(redisManager,"arc.xcommands");
        commandReceiver = new CommandReceiver("arc.xcommands");
        redisManager.registerChannel("arc.xcommands", commandReceiver);

        rankMessager = new RankMessager(redisManager, "arc.ranks");
        redisManager.registerChannel("arc.ranks", rankMessager);

        AnnouncementMessager announcementMessager = new AnnouncementMessager("arc.announcements", redisManager);
        redisManager.registerChannel(announcementMessager.getChannel(), announcementMessager);
        AnnounceManager.messager = announcementMessager;

        HistoryMessager historyMessager = new HistoryMessager("arc.high_lows_update", redisManager);
        redisManager.registerChannel(historyMessager.channel, historyMessager);
        HistoryManager.setMessager(historyMessager);

        if(HookRegistry.auctionHook != null) {
            AuctionMessager auctionMessager = new AuctionMessager("arc.auction_items", "arc.auction_items_all", redisManager);
            redisManager.registerChannel(auctionMessager.channel, auctionMessager);
            redisManager.registerChannel(auctionMessager.channelAll, auctionMessager);
            HookRegistry.auctionHook.setAuctionMessager(auctionMessager);
        }

        redisManager.init();
    }

}
