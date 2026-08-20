package com.fkc.fkcintensify;

import org.bukkit.ChatColor;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.inventory.EquipmentSlotGroup;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 強化系統的核心邏輯：
 *   - 用 PersistentDataContainer 在物品上記錄目前的強化等級（真正的資料來源）
 *   - 每次等級變動時，把所有區間(Stage)重新計算一遍、重新套用效果
 *     （不是累加式修改，而是每次都從頭算，這樣「換區間後前段定格、
 *      新段疊加」這個規則天生就會成立，不需要額外記錄歷史）
 *   - 提供成功機率、降階門檻、損毀門檻的查詢
 */
public class EnhanceManager {

    private final FkcIntensifyPlugin plugin;
    private final NamespacedKey levelKey;
    private final List<ToolCategory> categories = new ArrayList<>();

    public EnhanceManager(FkcIntensifyPlugin plugin) {
        this.plugin = plugin;
        this.levelKey = new NamespacedKey(plugin, "fkc_intensify_level");
        loadCategories();
    }

    public void loadCategories() {
        categories.clear();
        var section = plugin.getConfig().getConfigurationSection("intensify.categories");
        if (section == null) {
            plugin.getLogger().warning("config.yml 找不到 intensify.categories 區塊，強化系統將沒有任何可用分類！");
            return;
        }
        for (String key : section.getKeys(false)) {
            var sub = section.getConfigurationSection(key);
            if (sub == null) continue;
            ToolCategory category = ToolCategory.fromConfig(plugin, key, sub);
            if (category != null) {
                categories.add(category);
                plugin.getLogger().info("已載入強化分類：" + key + "（" + category.getStages().size() + " 個等級區間）");
            }
        }
    }

    public ToolCategory resolveCategory(ItemStack item) {
        for (ToolCategory category : categories) {
            if (category.matches(item)) {
                return category;
            }
        }
        return null;
    }

    public int getLevel(ItemStack item) {
        if (item == null || !item.hasItemMeta()) {
            return 0;
        }
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return 0;
        Integer level = meta.getPersistentDataContainer().get(levelKey, PersistentDataType.INTEGER);
        return level == null ? 0 : level;
    }

    public int getMaxLevel() {
        return plugin.getConfig().getInt("intensify.max-level", 20);
    }

    public int getDowngradeStartLevel() {
        return plugin.getConfig().getInt("intensify.downgrade-start-level", 10);
    }

    public int getDestroyStartLevel() {
        return plugin.getConfig().getInt("intensify.destroy-start-level", 16);
    }

    public double getDestroyChanceOnFail() {
        return plugin.getConfig().getDouble("intensify.destroy-chance-on-fail", 0.3);
    }

    public int getStonesPerAttempt() {
        return plugin.getConfig().getInt("intensify.stones-per-attempt", 1);
    }

    /**
     * 取得「從目前等級升到下一級」的成功機率。
     * 已經到最高等級的話回傳 0（不能再升）。
     */
    public double getSuccessRate(int currentLevel) {
        if (currentLevel >= getMaxLevel()) {
            return 0;
        }
        var section = plugin.getConfig().getConfigurationSection("intensify.success-rates");
        if (section == null) {
            return 0;
        }
        String key = String.valueOf(currentLevel);
        if (!section.contains(key)) {
            plugin.getLogger().warning("config.yml 的 intensify.success-rates 裡找不到等級 " + key
                    + " 的成功率設定，這次強化會直接判定失敗（0%成功率）！請檢查設定檔。");
            return 0;
        }
        return section.getDouble(key, 0);
    }

    /**
     * 把指定等級的效果實際套用到物品上。
     * 會把這個分類底下「所有區間」都重新算一遍：
     *   - 還沒到的區間：效果歸零（等於移除）
     *   - 已經走完的區間：效果定格在該區間的最大值
     *   - 目前所在的區間：效果依區間內的局部等級計算
     * 這個方法會直接修改傳入的 ItemStack。
     */
    public void applyLevel(ItemStack item, ToolCategory category, int level) {
        ItemMeta meta = item.getItemMeta();
        if (meta == null) {
            return;
        }

        // 寫入等級標記
        meta.getPersistentDataContainer().set(levelKey, PersistentDataType.INTEGER, level);

        List<Stage> stages = category.getStages();
        for (int stageIndex = 0; stageIndex < stages.size(); stageIndex++) {
            Stage stage = stages.get(stageIndex);
            int localLevel = stage.computeLocalLevel(level);
            List<StageEffect> effects = stage.getEffects();
            for (int effectIndex = 0; effectIndex < effects.size(); effectIndex++) {
                StageEffect effect = effects.get(effectIndex);
                String effectId = category.getName() + "_s" + stageIndex + "_e" + effectIndex;
                if (effect.getKind() == StageEffect.Kind.ENCHANT) {
                    applyEnchantEffect(meta, effect, localLevel);
                } else {
                    applyAttributeEffect(meta, effect, localLevel, effectId);
                }
            }
        }

        // 更新 lore：先移除舊的強化等級那一行，再補上新的
        updateLoreLevelLine(meta, level);

        item.setItemMeta(meta);
    }

    /**
     * 附魔效果：localLevel 直接當作附魔等級。
     * 注意：如果兩個不同區間剛好用了同一種附魔，會互相覆蓋（附魔本身沒有像屬性
     * 那種「多重修飾器共存」的機制），這是原版遊戲機制的限制，設定時請避免
     * 讓同一種附魔出現在多個區間裡。
     */
    private void applyEnchantEffect(ItemMeta meta, StageEffect effect, int localLevel) {
        NamespacedKey enchantKey = NamespacedKey.minecraft(effect.getEnchantName().toLowerCase(Locale.ROOT));
        Enchantment enchantment = Registry.ENCHANTMENT.get(enchantKey);
        if (enchantment == null) {
            plugin.getLogger().warning("找不到附魔：" + effect.getEnchantName() + "，無法套用強化效果");
            return;
        }
        if (localLevel <= 0) {
            // 注意：這裡不能無條件移除，否則會把「另一個區間也用同一種附魔、
            // 且目前有效」的效果誤刪。實務上請避免兩個區間用同一種附魔。
            meta.removeEnchant(enchantment);
        } else {
            meta.addEnchant(enchantment, localLevel, true);
        }
    }

    /**
     * 屬性效果：用「分類名+區間編號+效果編號」組成獨一無二的 modifier key，
     * 讓不同區間的屬性修飾器可以同時存在、互不覆蓋。
     */
    private void applyAttributeEffect(ItemMeta meta, StageEffect effect, int localLevel, String effectId) {
        NamespacedKey attrKey = NamespacedKey.minecraft(toVanillaAttributeKey(effect.getAttributeName()));
        Attribute attribute = Registry.ATTRIBUTE.get(attrKey);
        if (attribute == null) {
            plugin.getLogger().warning("找不到屬性：" + effect.getAttributeName() + "，無法套用強化效果");
            return;
        }

        NamespacedKey modifierKey = new NamespacedKey(plugin, "fkc_intensify_" + effectId.toLowerCase(Locale.ROOT));
        EquipmentSlotGroup slotGroup = resolveSlotGroup(effect.getSlotGroupName());

        // removeAttributeModifier 需要傳入完整的 AttributeModifier 物件（用 key 比對是否相符），
        // 先建一個佔位用的 modifier 來移除舊的（amount 數值不影響移除判定，只看 key）
        AttributeModifier placeholderForRemoval = new AttributeModifier(
                modifierKey, 0, AttributeModifier.Operation.ADD_NUMBER, slotGroup
        );
        meta.removeAttributeModifier(attribute, placeholderForRemoval);

        if (localLevel > 0) {
            double amount = effect.getValuePerLevel() * localLevel;
            AttributeModifier modifier = new AttributeModifier(
                    modifierKey,
                    amount,
                    AttributeModifier.Operation.ADD_NUMBER,
                    slotGroup
            );
            meta.addAttributeModifier(attribute, modifier);
        }
    }

    private EquipmentSlotGroup resolveSlotGroup(String name) {
        return switch (name.toUpperCase(Locale.ROOT)) {
            case "MAINHAND" -> EquipmentSlotGroup.MAINHAND;
            case "OFFHAND" -> EquipmentSlotGroup.OFFHAND;
            case "HAND" -> EquipmentSlotGroup.HAND;
            case "HEAD" -> EquipmentSlotGroup.HEAD;
            case "CHEST" -> EquipmentSlotGroup.CHEST;
            case "LEGS" -> EquipmentSlotGroup.LEGS;
            case "FEET" -> EquipmentSlotGroup.FEET;
            case "ARMOR" -> EquipmentSlotGroup.ARMOR;
            case "BODY" -> EquipmentSlotGroup.BODY;
            default -> EquipmentSlotGroup.ANY;
        };
    }

    /**
     * 把 config.yml 裡用的舊式屬性命名（例如 GENERIC_ATTACK_DAMAGE）
     * 轉換成現代原版屬性的 NamespacedKey 字串。
     *
     * 注意：Minecraft 1.21.2 開始，所有 generic.* 屬性的 "generic." 前綴被拿掉了
     * （例如 generic.scale 變成 scale），所以這裡直接把 GENERIC_ 前綴整個去掉，
     * 不是轉換成「generic.」開頭。
     */
    private String toVanillaAttributeKey(String legacyName) {
        String lower = legacyName.toLowerCase(Locale.ROOT);
        String prefix = "generic_";
        if (lower.startsWith(prefix)) {
            return lower.substring(prefix.length());
        }
        return lower;
    }

    private void updateLoreLevelLine(ItemMeta meta, int level) {
        List<String> lore = meta.hasLore() && meta.getLore() != null ? new ArrayList<>(meta.getLore()) : new ArrayList<>();
        String prefix = ChatColor.GOLD + "強化等級: " + ChatColor.YELLOW + "+";

        lore.removeIf(line -> ChatColor.stripColor(line) != null
                && ChatColor.stripColor(line).startsWith(ChatColor.stripColor(prefix)));

        if (level > 0) {
            lore.add(prefix + level);
        }
        meta.setLore(lore);
    }
}
