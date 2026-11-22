package com.coloryr.allmusic.client.core;

import java.util.Collections;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

public class PlayerBlockSettings {
    public enum UnknownPolicy {
        MUTE, PLAY
    }

    private Map<String, String> players = new TreeMap<>();
    private UnknownPolicy unknownPolicy = UnknownPolicy.MUTE;

    public Map<String, String> getPlayers() {
        return Collections.unmodifiableMap(players);
    }

    public boolean contains(String player) {
        return player != null && players.containsKey(normalize(player));
    }

    public boolean add(String player) {
        String name = player.trim();
        if (!isPlayerName(name)) {
            throw new IllegalArgumentException("玩家名须为 1 至 16 位字母、数字或下划线");
        }
        return players.putIfAbsent(normalize(name), name) == null;
    }

    public boolean remove(String player) {
        return players.remove(normalize(player)) != null;
    }

    public UnknownPolicy getUnknownPolicy() {
        return unknownPolicy;
    }

    public void setUnknownPolicy(UnknownPolicy policy) {
        if (policy == null) {
            throw new IllegalArgumentException("未知来源策略不能为空");
        }
        unknownPolicy = policy;
    }

    void validate() {
        if (players == null || unknownPolicy == null) {
            throw new IllegalArgumentException("黑名单或未知来源策略无效");
        }
        Map<String, String> normalized = new TreeMap<>();
        for (Map.Entry<String, String> entry : players.entrySet()) {
            if (entry.getValue() == null || !isPlayerName(entry.getValue())
                    || !normalize(entry.getValue()).equals(entry.getKey())) {
                throw new IllegalArgumentException("黑名单中存在无效玩家名");
            }
            normalized.put(entry.getKey(), entry.getValue());
        }
        players = normalized;
    }

    public static boolean isPlayerName(String name) {
        return name != null && name.matches("[A-Za-z0-9_]{1,16}");
    }

    private static String normalize(String name) {
        return name.trim().toLowerCase(Locale.ROOT);
    }
}
