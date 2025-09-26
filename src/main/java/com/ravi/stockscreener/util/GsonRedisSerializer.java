package com.ravi.stockscreener.util;


import com.google.gson.Gson;
import org.jetbrains.annotations.NotNull;
import org.springframework.data.redis.serializer.RedisSerializer;
import org.springframework.data.redis.serializer.SerializationException;

import java.nio.charset.StandardCharsets;

public class GsonRedisSerializer<T> implements RedisSerializer<T> {

    private final Gson gson;
    private final Class<T> type;

    public GsonRedisSerializer(Class<T> type, Gson gson) {
        this.type = type;
        this.gson = gson;
    }

    @NotNull
    @Override
    public byte[] serialize(T t) throws SerializationException {
        if (t == null) return new byte[0];
        String json = gson.toJson(t);
        return json.getBytes(StandardCharsets.UTF_8);
    }

    @Override
    public T deserialize(byte[] bytes) throws SerializationException {
        if (bytes == null || bytes.length == 0) return null;
        String json = new String(bytes, StandardCharsets.UTF_8);
        return gson.fromJson(json, type);
    }
}
