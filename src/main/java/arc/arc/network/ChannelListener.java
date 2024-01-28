package arc.arc.network;

public abstract interface ChannelListener {

    public abstract void consume(String channel, String message);


}
