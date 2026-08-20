package com.fkc.fkcintensify;

import org.bukkit.Chunk;

import java.util.concurrent.ConcurrentHashMap;
import java.util.Map;

/**
 * 管理「同一區塊在冷卻時間內只能觸發一次掉落」的邏輯。
 */
public class ChunkLimitManager {

    private final Map<String, Long> lastDropTime = new ConcurrentHashMap<>();

    public boolean tryTrigger(Chunk chunk, long cooldownSeconds) {
        String key = chunkKey(chunk);
        long now = System.currentTimeMillis();
        long cooldownMillis = cooldownSeconds * 1000L;

        Long last = lastDropTime.get(key);
        if (last != null && (now - last) < cooldownMillis) {
            return false;
        }

        lastDropTime.put(key, now);
        return true;
    }

    private String chunkKey(Chunk chunk) {
        return chunk.getWorld().getName() + "_" + chunk.getX() + "_" + chunk.getZ();
    }

    public void clear() {
        lastDropTime.clear();
    }
}
