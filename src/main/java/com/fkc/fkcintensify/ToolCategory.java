package com.fkc.fkcintensify;

import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Tag;
import org.bukkit.Bukkit;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;

/**
 * 代表 config.yml 裡 intensify.categories 底下的一個分類。
 * 例如「SWORD」這個分類，底下可能掛了好幾個等級區間（Stage），
 * 每個區間各自對應不同的附魔/屬性效果。
 */
public class ToolCategory {

    private final String name;
    private final Tag<Material> matchTag;
    private final Material matchMaterial;
    private final List<Stage> stages;

    public ToolCategory(String name, Tag<Material> matchTag, Material matchMaterial, List<Stage> stages) {
        this.name = name;
        this.matchTag = matchTag;
        this.matchMaterial = matchMaterial;
        this.stages = stages;
    }

    /**
     * 依 config.yml 裡的一段設定，建立一個 ToolCategory。
     */
    public static ToolCategory fromConfig(FkcIntensifyPlugin plugin, String name, ConfigurationSection section) {
        boolean enabled = section.getBoolean("enabled", true);
        if (!enabled) {
            plugin.getLogger().info("分類：" + name + " 已在設定檔中關閉（enabled: false），不會提供強化功能");
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

        List<Stage> stages = new ArrayList<>();
        List<?> rawList = section.getList("stages");
        if (rawList == null || rawList.isEmpty()) {
            plugin.getLogger().warning("[分類:" + name + "] 沒有設定任何 stages，這個分類不會有任何強化效果");
            return new ToolCategory(name, matchTag, matchMaterial, stages);
        }

        for (int i = 0; i < rawList.size(); i++) {
            ConfigurationSection stageSection = section.getConfigurationSection("stages." + i);
            if (stageSection == null) continue;
            Stage stage = Stage.fromConfig(plugin, "分類:" + name + ".stages[" + i + "]", stageSection);
            if (stage != null) {
                stages.add(stage);
            }
        }
        return new ToolCategory(name, matchTag, matchMaterial, stages);
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

    public List<Stage> getStages() {
        return stages;
    }
}
