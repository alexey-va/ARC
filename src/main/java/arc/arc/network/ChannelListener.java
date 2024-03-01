package arc.arc.network;

public interface ChannelListener {

    void consume(String channel, String message, String originServer);


}
