package arc.arc.network;

import org.bukkit.event.Cancellable;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.jetbrains.annotations.NotNull;

import java.util.UUID;

public class MessageEvent extends Event implements Cancellable {

    private String message;
    private static final HandlerList HANDLERS_LIST = new HandlerList();
    private boolean isCancelled;
    private String channelId;
    private String originServer;
    private UUID messageUuid;

    public MessageEvent(String channelId, String message,  String originServer, UUID messageUuid) {
        this.message = message;
        this.isCancelled = isCancelled;
        this.channelId = channelId;
        this.originServer = originServer;
        this.messageUuid = messageUuid;
    }

    @Override
    public boolean isCancelled() {
        return false;
    }

    @Override
    public void setCancelled(boolean b) {

    }

    @Override
    public @NotNull HandlerList getHandlers() {
        return HANDLERS_LIST;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS_LIST;
    }

    public String getMessage() {
        return message;
    }

    public String getChannelId() {
        return channelId;
    }

    public String getOriginServer() {
        return originServer;
    }

    public UUID getMessageUuid() {
        return messageUuid;
    }
}
