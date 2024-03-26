package arc.arc.sync.base;

import java.util.UUID;

public interface SyncData {

    long timestamp();
    String server();
    UUID uuid();

    default boolean trash(){
        return false;
    };
}
