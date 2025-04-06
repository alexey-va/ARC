package ru.arc.network;

import ru.arc.util.Common;

public class RedisSerializer {


    public static String toJson(Object serializable) {
        return Common.gson.toJson(serializable);
    }

    public static <T> T fromJson(String json, Class<T> clazz) {
        return Common.gson.fromJson(json, clazz);
    }

}
