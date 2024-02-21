package arc.arc.network;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JSR310Module;

public class RedisSerializer {


    public static String toJson(Object serializable){
        ObjectMapper mapper = new ObjectMapper();
        mapper.registerModule(new JSR310Module());
        try {
            return mapper.writeValueAsString(serializable);
        } catch (Exception e){
            e.printStackTrace();
            return null;
        }
    }

    public static <T> T fromJson(String json, Class<T> clazz){
        ObjectMapper mapper = new ObjectMapper();
        mapper.registerModule(new JSR310Module());
        try {
            return mapper.readValue(json, clazz);
        } catch (Exception e){
            e.printStackTrace();
            return null;
        }
    }

}
