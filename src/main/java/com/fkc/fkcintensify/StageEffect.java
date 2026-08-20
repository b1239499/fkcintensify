package com.fkc.fkcintensify;

import org.bukkit.configuration.ConfigurationSection;

/**
 * 代表一個「等級區間」裡的其中一種效果。
 * 一個區間可以有多個 StageEffect（例如同時加攻擊力又加攻速）。
 */
public class StageEffect {

    public enum Kind {
        ENCHANT,
        ATTRIBUTE
    }

    private final Kind kind;

    // ENCHANT 用
    private final String enchantName;

    // ATTRIBUTE 用
    private final String attributeName;
    private final double valuePerLevel;
    private final String slotGroupName;

    private StageEffect(Kind kind, String enchantName, String attributeName,
                         double valuePerLevel, String slotGroupName) {
        this.kind = kind;
        this.enchantName = enchantName;
        this.attributeName = attributeName;
        this.valuePerLevel = valuePerLevel;
        this.slotGroupName = slotGroupName;
    }

    /**
     * 從 config.yml 裡的一段效果設定建立 StageEffect。
     * 設定有誤的話回傳 null，並印出警告，不會讓整個插件崩潰。
     */
    public static StageEffect fromConfig(FkcIntensifyPlugin plugin, String contextLabel, ConfigurationSection section) {
        String kindRaw = section.getString("kind", "");
        Kind kind;
        try {
            kind = Kind.valueOf(kindRaw.toUpperCase());
        } catch (IllegalArgumentException e) {
            plugin.getLogger().warning("[" + contextLabel + "] kind 設定無效：" + kindRaw + "，這個效果將不會生效");
            return null;
        }

        if (kind == Kind.ENCHANT) {
            String enchantName = section.getString("enchant");
            if (enchantName == null) {
                plugin.getLogger().warning("[" + contextLabel + "] kind=ENCHANT 但沒有設定 enchant 欄位");
                return null;
            }
            return new StageEffect(kind, enchantName, null, 0, null);
        } else {
            String attributeName = section.getString("attribute");
            double valuePerLevel = section.getDouble("value-per-level", 0);
            String slotGroup = section.getString("slot-group", "MAINHAND");
            if (attributeName == null) {
                plugin.getLogger().warning("[" + contextLabel + "] kind=ATTRIBUTE 但沒有設定 attribute 欄位");
                return null;
            }
            return new StageEffect(kind, null, attributeName, valuePerLevel, slotGroup);
        }
    }

    public Kind getKind() {
        return kind;
    }

    public String getEnchantName() {
        return enchantName;
    }

    public String getAttributeName() {
        return attributeName;
    }

    public double getValuePerLevel() {
        return valuePerLevel;
    }

    public String getSlotGroupName() {
        return slotGroupName;
    }
}
