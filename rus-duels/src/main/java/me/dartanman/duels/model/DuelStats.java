package me.dartanman.duels.model;

import java.util.LinkedHashMap;
import java.util.Map;

public record DuelStats(
        int wins,
        int losses,
        int rating,
        int currentStreak,
        int bestStreak,
        int ownWins,
        int ownLosses,
        int kitWins,
        int kitLosses,
        long updatedAt
) {
    public static final int DEFAULT_RATING = 1000;

    public static DuelStats empty() {
        return new DuelStats(0, 0, DEFAULT_RATING, 0, 0, 0, 0, 0, 0, 0L);
    }

    public DuelStats win(DuelMode mode, int newRating, long now) {
        int nextStreak = currentStreak + 1;
        return new DuelStats(
                wins + 1,
                losses,
                newRating,
                nextStreak,
                Math.max(bestStreak, nextStreak),
                ownWins + (mode == DuelMode.OWN_INVENTORY ? 1 : 0),
                ownLosses,
                kitWins + (mode == DuelMode.KIT ? 1 : 0),
                kitLosses,
                now
        );
    }

    public DuelStats loss(DuelMode mode, int newRating, long now) {
        return new DuelStats(
                wins,
                losses + 1,
                newRating,
                0,
                bestStreak,
                ownWins,
                ownLosses + (mode == DuelMode.OWN_INVENTORY ? 1 : 0),
                kitWins,
                kitLosses + (mode == DuelMode.KIT ? 1 : 0),
                now
        );
    }

    public Map<String, Object> toMap() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("wins", wins);
        result.put("losses", losses);
        result.put("rating", rating);
        result.put("currentStreak", currentStreak);
        result.put("bestStreak", bestStreak);
        result.put("ownWins", ownWins);
        result.put("ownLosses", ownLosses);
        result.put("kitWins", kitWins);
        result.put("kitLosses", kitLosses);
        result.put("updatedAt", updatedAt);
        return result;
    }

    public static DuelStats fromMap(Map<?, ?> map) {
        return new DuelStats(
                integer(map, "wins", 0),
                integer(map, "losses", 0),
                integer(map, "rating", DEFAULT_RATING),
                integer(map, "currentStreak", 0),
                integer(map, "bestStreak", 0),
                integer(map, "ownWins", 0),
                integer(map, "ownLosses", 0),
                integer(map, "kitWins", 0),
                integer(map, "kitLosses", 0),
                longValue(map, "updatedAt", 0L)
        );
    }

    private static int integer(Map<?, ?> map, String key, int fallback) {
        Object value = map.get(key);
        return value instanceof Number number ? number.intValue() : fallback;
    }

    private static long longValue(Map<?, ?> map, String key, long fallback) {
        Object value = map.get(key);
        return value instanceof Number number ? number.longValue() : fallback;
    }
}
