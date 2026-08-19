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
 *   - 每次等級變動時，重新套用對應的附魔等級 / 屬性數值，並更新 lore 顯示目前等級
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

    /**
     * 從 config.yml 讀取所有分類設定。可以在 /fkci reload 時重新呼叫。
     */
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
                plugin.getLogger().info("已載入強化分類：" + key);
            }
        }
    }

    /**
     * 找出這個物品符合哪個強化分類；找不到回傳 null（代表這個物品不能強化）。
     */
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
        return section.getDouble(String.valueOf(currentLevel), 0);
    }

    /**
     * 把指定等級的效果實際套用到物品上（附魔或屬性），並更新等級標記跟 lore 顯示。
     * 這個方法會直接修改傳入的 ItemStack。
     */
    public void applyLevel(ItemStack item, ToolCategory category, int level) {
        ItemMeta meta = item.getItemMeta();
        if (meta == null) {
            return;
        }

        // 寫入等級標記
        meta.getPersistentDataContainer().set(levelKey, PersistentDataType.INTEGER, level);

        if (category.getKind() == ToolCategory.Kind.ENCHANT) {
            applyEnchantLevel(item, meta, category, level);
        } else {
            applyAttributeLevel(meta, category, level);
        }

        // 更新 lore：先移除舊的強化等級那一行，再補上新的
        updateLoreLevelLine(meta, level);

        item.setItemMeta(meta);
    }

    private void applyEnchantLevel(ItemStack item, ItemMeta meta, ToolCategory category, int level) {
        NamespacedKey enchantKey = NamespacedKey.minecraft(category.getEnchantName().toLowerCase(Locale.ROOT));
        Enchantment enchantment = Registry.ENCHANTMENT.get(enchantKey);
        if (enchantment == null) {
            plugin.getLogger().warning("找不到附魔：" + category.getEnchantName() + "，無法套用強化效果");
            return;
        }
        if (level <= 0) {
            meta.removeEnchant(enchantment);
        } else {
            // addUnsafeEnchantment 可以無視原版等級上限，符合 +1~+20 這種自訂範圍需求
            meta.addEnchant(enchantment, level, true);
        }
    }

    private void applyAttributeLevel(ItemMeta meta, ToolCategory category, int level) {
        NamespacedKey attrKey = NamespacedKey.minecraft(toVanillaAttributeKey(category.getAttributeName()));
        Attribute attribute = Registry.ATTRIBUTE.get(attrKey);
        if (attribute == null) {
            plugin.getLogger().warning("找不到屬性：" + category.getAttributeName() + "，無法套用強化效果");
            return;
        }

        // 用固定的 NamespacedKey 當作這個強化 modifier 的識別碼，
        // 這樣之後升級/降級時才能準確找到、移除舊的 modifier，換上新的
        NamespacedKey modifierKey = new NamespacedKey(plugin, "fkc_intensify_" + category.getName().toLowerCase(Locale.ROOT));
        EquipmentSlotGroup slotGroup = resolveSlotGroup(category.getSlotGroupName());

        // removeAttributeModifier 需要傳入完整的 AttributeModifier 物件（用 key 比對是否相符），
        // 不能只傳 NamespacedKey，所以先建一個「佔位用」的 modifier（amount 數值不重要，
        // 移除時只比對 key 是否一致）
        AttributeModifier placeholderForRemoval = new AttributeModifier(
                modifierKey, 0, AttributeModifier.Operation.ADD_NUMBER, slotGroup
        );
        meta.removeAttributeModifier(attribute, placeholderForRemoval);

        if (level > 0) {
            double amount = category.getValuePerLevel() * level;
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
     * 轉換成現代原版屬性的 NamespacedKey 字串（例如 generic.attack_damage）。
     */
    private String toVanillaAttributeKey(String legacyName) {
        String lower = legacyName.toLowerCase(Locale.ROOT);
        int firstUnderscore = lower.indexOf('_');
        if (firstUnderscore < 0) {
            return lower;
        }
        return lower.substring(0, firstUnderscore) + "." + lower.substring(firstUnderscore + 1);
    }

    private void updateLoreLevelLine(ItemMeta meta, int level) {
        List<String> lore = meta.hasLore() && meta.getLore() != null ? new ArrayList<>(meta.getLore()) : new ArrayList<>();
        String prefix = ChatColor.GOLD + "強化等級: " + ChatColor.YELLOW + "+";

        // 移除舊的強化等級那一行（用前綴比對）
        lore.removeIf(line -> ChatColor.stripColor(line) != null
                && ChatColor.stripColor(line).startsWith(ChatColor.stripColor(prefix)));

        if (level > 0) {
            lore.add(prefix + level);
        }
        meta.setLore(lore);
    }
}
