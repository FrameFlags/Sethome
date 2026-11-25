package com.frameflags.sethome.core;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

public final class HomeData {
    private static final Gson GSON = new GsonBuilder().create();

    private final Map<String, Map<String, HomeRecord>> byPlayer = new LinkedHashMap<>();

    public static String normalize(String name) {
        return name == null ? "" : name.trim().toLowerCase(Locale.ROOT);
    }

    public synchronized HomeResult set(UUID player, String rawName, HomeRecord record, int maxHomes) {
        String name = normalize(rawName);
        if (name.isEmpty()) return HomeResult.INVALID_NAME;
        if (name.equals("list")) return HomeResult.RESERVED_NAME;
        Map<String, HomeRecord> homes = byPlayer.get(player.toString());
        if (homes == null) {
            homes = new LinkedHashMap<>();
            byPlayer.put(player.toString(), homes);
        }
        if (!homes.containsKey(name) && maxHomes >= 0 && homes.size() >= maxHomes) {
            return HomeResult.LIMIT_REACHED;
        }
        homes.put(name, record);
        return HomeResult.OK;
    }

    public synchronized HomeRecord get(UUID player, String rawName) {
        Map<String, HomeRecord> homes = byPlayer.get(player.toString());
        return homes == null ? null : homes.get(normalize(rawName));
    }

    public synchronized HomeResult remove(UUID player, String rawName) {
        Map<String, HomeRecord> homes = byPlayer.get(player.toString());
        return homes != null && homes.remove(normalize(rawName)) != null ? HomeResult.OK : HomeResult.NOT_FOUND;
    }

    public synchronized List<String> list(UUID player) {
        Map<String, HomeRecord> homes = byPlayer.get(player.toString());
        if (homes == null) return Collections.emptyList();
        return new ArrayList<>(homes.keySet());
    }

    public synchronized String toJson() {
        return GSON.toJson(byPlayer);
    }

    public static HomeData fromJson(String json) {
        HomeData data = new HomeData();
        if (json == null || json.isEmpty() || json.equals("null")) return data;
        Type type = new TypeToken<Map<String, Map<String, HomeRecord>>>() { }.getType();
        Map<String, Map<String, HomeRecord>> parsed = GSON.fromJson(json, type);
        if (parsed == null) return data;
        for (Map.Entry<String, Map<String, HomeRecord>> entry : parsed.entrySet()) {
            if (entry.getValue() != null) data.byPlayer.put(entry.getKey(), entry.getValue());
        }
        return data;
    }
}
