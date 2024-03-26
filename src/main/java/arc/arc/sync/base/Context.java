package arc.arc.sync.base;

import lombok.ToString;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

@ToString
public class Context {
    Map<String, Object> map = new HashMap<>();

    @SuppressWarnings("unchecked")
    public <T> T get(String key) {
        return (T) map.get(key);
    }

    public void put(String name, UUID uuid) {
        map.put(name, uuid);
    }
}
