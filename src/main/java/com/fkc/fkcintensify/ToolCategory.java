package com.fkc.fkcintensify;

import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Tag;
import org.bukkit.Bukkit;
import org.bukkit.inventory.ItemStack;

/**
 * 代表 config.yml 裡 intensify.categories 底下的一個分類。
 * 例如「PICKAXE -> 強化效率附魔」、「SWORD -> 強化攻擊力屬性」。
 */
public class ToolCategory {

    public enum Kind {
        ENCHANT,
        ATTRIBUTE
    }

    private final String name;
    private final Kind kind;

    // ENCHANT 用
    private final String enchantName; // 例如 EFFICIENCY, QUICK_CHARGE

    // ATTRIBUTE 用
    private final String attributeName; // 例如 GENERIC_ATTACK_DAMAGE
    private final double valuePerLevel;
    private final String slotGroupName; // 例如 MAINHAND, HEAD, CHEST...

    // 比對用
    private final Tag<Material> matchTag;      // 用原版標籤比對（可能是 null）
    private final Material matchMaterial;      // 用單一材質比對（可能是 null）

    public ToolCategory(String name, Kind kind, String enchantName,
                         String attributeName, double valuePerLevel, String slotGroupName,
                         Tag<Material> matchTag, Material matchMaterial) {
        this.name = name;
        this.kind = kind;
        this.enchantName = enchantName;
        this.attributeName = attributeName;
        this.valuePerLevel = valuePerLevel;
        this.slotGroupName = slotGroupName;
        this.matchTag = matchTag;
        this.matchMaterial = matchMaterial;
    }

    /**
     * 依 config.yml 裡的一段設定，建立一個 ToolCategory。
     * 如果標籤或附魔/屬性名稱在目前伺服器版本上找不到，會回傳 null，
     * 並且在 console 印出警告，讓管理員知道這個分類沒有生效，而不是讓整個插件崩潰。
     */
    public static ToolCategory fromConfig(FkcIntensifyPlugin plugin, String name,
                                           org.bukkit.configuration.ConfigurationSection section) {
        String kindRaw = section.getString("kind", "");
        Kind kind;
        try {
            kind = Kind.valueOf(kindRaw.toUpperCase());
        } catch (IllegalArgumentException e) {
            plugin.getLogger().warning("[分類:" + name + "] kind 設定無效：" + kindRaw + "，這個分類將不會生效");
            return null;
        }

        Tag<Material> matchTag = null;
        Material matchMaterial = null;

        String tagName = section.getString("match-tag");
        String materialName = section.getString("match-material");

        if (tagName != null) {
            matchTag = Bukkit.getTag(Tag.REGISTRY_ITEMS, NamespacedKey.minecraft(tagName), Material.class);
            if (matchTag == null) {
                plugin.getLogger().warning("[分類:" + name + "] 找不到原版標籤：" + tagName + "，這個分類將不會生效");
                return null;
            }
        } else if (materialName != null) {
            matchMaterial = Material.matchMaterial(materialName);
            if (matchMaterial == null) {
                plugin.getLogger().warning("[分類:" + name + "] 找不到材質：" + materialName + "，這個分類將不會生效");
                return null;
            }
        } else {
            plugin.getLogger().warning("[分類:" + name + "] 沒有設定 match-tag 或 match-material，這個分類將不會生效");
            return null;
        }

        if (kind == Kind.ENCHANT) {
            String enchantName = section.getString("enchant");
            if (enchantName == null) {
                plugin.getLogger().warning("[分類:" + name + "] kind=ENCHANT 但沒有設定 enchant 欄位");
                return null;
            }
            return new ToolCategory(name, kind, enchantName, null, 0, null, matchTag, matchMaterial);
        } else {
            String attributeName = section.getString("attribute");
            double valuePerLevel = section.getDouble("value-per-level", 0);
            String slotGroup = section.getString("slot-group", "MAINHAND");
            if (attributeName == null) {
                plugin.getLogger().warning("[分類:" + name + "] kind=ATTRIBUTE 但沒有設定 attribute 欄位");
                return null;
            }
            return new ToolCategory(name, kind, null, attributeName, valuePerLevel, slotGroup, matchTag, matchMaterial);
        }
    }

    /**
     * 檢查傳入的物品是不是屬於這個分類。
     */
    public boolean matches(ItemStack item) {
        if (item == null) {
            return false;
        }
        Material type = item.getType();
        if (matchMaterial != null) {
            return type == matchMaterial;
        }
        if (matchTag != null) {
            return matchTag.isTagged(type);
        }
        return false;
    }

    public String getName() {
        return name;
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
