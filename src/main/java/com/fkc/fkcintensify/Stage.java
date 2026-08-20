package com.fkc.fkcintensify;

import org.bukkit.configuration.ConfigurationSection;

import java.util.ArrayList;
import java.util.List;

/**
 * 代表一個等級區間，例如「+1 ~ +10」。
 * 一個區間底下可以掛多個效果（StageEffect）。
 */
public class Stage {

    private final int from;
    private final int to;
    private final List<StageEffect> effects;

    public Stage(int from, int to, List<StageEffect> effects) {
        this.from = from;
        this.to = to;
        this.effects = effects;
    }

    public static Stage fromConfig(FkcIntensifyPlugin plugin, String contextLabel, ConfigurationSection section) {
        int from = section.getInt("from", -1);
        int to = section.getInt("to", -1);
        if (from < 0 || to < 0 || to < from) {
            plugin.getLogger().warning("[" + contextLabel + "] from/to 設定不合法（from=" + from + ", to=" + to + "），這個區間將不會生效");
            return null;
        }

        List<StageEffect> effects = new ArrayList<>();
        List<?> rawList = section.getList("effects");
        if (rawList == null || rawList.isEmpty()) {
            plugin.getLogger().warning("[" + contextLabel + "] 沒有設定任何 effects，這個區間不會有任何效果");
            return new Stage(from, to, effects);
        }

        for (int i = 0; i < rawList.size(); i++) {
            ConfigurationSection effectSection = section.getConfigurationSection("effects." + i);
            if (effectSection == null) continue;
            StageEffect effect = StageEffect.fromConfig(plugin, contextLabel + ".effects[" + i + "]", effectSection);
            if (effect != null) {
                effects.add(effect);
            }
        }
        return new Stage(from, to, effects);
    }

    /**
     * 計算「目前等級」在這個區間裡貢獻的局部等級。
     * 還沒到這個區間：回傳 0（沒有任何貢獻）。
     * 已經超過這個區間：回傳區間長度（等於定格在區間最大值）。
     * 落在區間內：回傳實際落在區間裡的第幾格。
     */
    public int computeLocalLevel(int overallLevel) {
        if (overallLevel < from) {
            return 0;
        }
        int cappedLevel = Math.min(overallLevel, to);
        return cappedLevel - from + 1;
    }

    public int getFrom() {
        return from;
    }

    public int getTo() {
        return to;
    }

    public List<StageEffect> getEffects() {
        return effects;
    }
}
