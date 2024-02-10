package arc.arc.network;

import arc.arc.board.Board;
import arc.arc.board.BoardMessager;
import arc.arc.hooks.lands.LandsMessager;
import arc.arc.xserver.announcements.AnnounceManager;
import arc.arc.xserver.announcements.AnnouncementMessager;
import arc.arc.xserver.playerlist.PlayerListListener;
import arc.arc.xserver.playerlist.PlayerListTask;
import arc.arc.xserver.commands.CommandReceiver;
import arc.arc.xserver.commands.CommandSender;
import arc.arc.xserver.ranks.RankMessager;
import lombok.RequiredArgsConstructor;

@RequiredArgsConstructor
public class NetworkRegistry {

    private final RedisManager redisManager;

    public static LandsMessager landsMessager;

    PlayerListListener playerListListener;
    PlayerListTask playerListTask;
    public static CommandSender commandSender;
    CommandReceiver commandReceiver;
    public static RankMessager rankMessager;


    public void init(){
        playerListTask = new PlayerListTask(redisManager);
        playerListTask.init();

        playerListListener = new PlayerListListener("arc.player_list");
        redisManager.registerChannel("arc.player_list", playerListListener);

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

        BoardMessager boardMessager = new BoardMessager("arc.board_update", redisManager);
        redisManager.registerChannel(boardMessager.channel, boardMessager);
        Board.instance().setMessager(boardMessager);

        redisManager.init();
    }

}
