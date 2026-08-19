package com.fkc.fkcintensify;

import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

import java.util.List;

/**
 * 統一管理插件的三種自訂道具：
 *   - 強化石（掉落系統 + 強化系統共用素材）
 *   - 防止降階石（強化失敗時，可以擋掉「降階」這個懲罰）
 *   - 防止破壞石（強化失敗時，可以擋掉「道具消失」這個懲罰）
 *
 * 每種道具都用各自獨立的 PersistentDataContainer 標記辨識，
 * 不比對物品名稱字串，避免玩家改名或語言檔變動導致辨識失敗。
 */
public class CustomItemFactory {

    private final FkcIntensifyPlugin plugin;
    private final NamespacedKey stoneKey;
    private final NamespacedKey preventDowngradeKey;
    private final NamespacedKey preventDestroyKey;

    public CustomItemFactory(FkcIntensifyPlugin plugin) {
        this.plugin = plugin;
        this.stoneKey = new NamespacedKey(plugin, "intensify_stone");
        this.preventDowngradeKey = new NamespacedKey(plugin, "prevent_downgrade_stone");
        this.preventDestroyKey = new NamespacedKey(plugin, "prevent_destroy_stone");
    }

    // ============================================================
    // 強化石
    // ============================================================
    public ItemStack createIntensifyStone(int amount) {
        return createMarkedItem(
                amount,
                plugin.getConfig().getString("item.material", "NETHER_STAR"),
                plugin.getConfig().getString("item.display-name", "&d&l強化石"),
                plugin.getConfig().getStringList("item.lore"),
                stoneKey
        );
    }

    public boolean isIntensifyStone(ItemStack item) {
        return hasMarker(item, stoneKey);
    }

    // ============================================================
    // 防止降階石
    // ============================================================
    public ItemStack createPreventDowngradeStone(int amount) {
        return createMarkedItem(
                amount,
                plugin.getConfig().getString("intensify.protection.prevent-downgrade-item.material", "EMERALD"),
                plugin.getConfig().getString("intensify.protection.prevent-downgrade-item.display-name", "&b防止降階石"),
                plugin.getConfig().getStringList("intensify.protection.prevent-downgrade-item.lore"),
                preventDowngradeKey
        );
    }

    public boolean isPreventDowngradeStone(ItemStack item) {
        return hasMarker(item, preventDowngradeKey);
    }

    // ============================================================
    // 防止破壞石
    // ============================================================
    public ItemStack createPreventDestroyStone(int amount) {
        return createMarkedItem(
                amount,
                plugin.getConfig().getString("intensify.protection.prevent-destroy-item.material", "DIAMOND"),
                plugin.getConfig().getString("intensify.protection.prevent-destroy-item.display-name", "&b防止破壞石"),
                plugin.getConfig().getStringList("intensify.protection.prevent-destroy-item.lore"),
                preventDestroyKey
        );
    }

    public boolean isPreventDestroyStone(ItemStack item) {
        return hasMarker(item, preventDestroyKey);
    }

    // ============================================================
    // 共用邏輯
    // ============================================================
    private ItemStack createMarkedItem(int amount, String materialName, String displayName,
                                        List<String> lore, NamespacedKey markerKey) {
        Material material = Material.matchMaterial(materialName == null ? "" : materialName);
        if (material == null) {
            plugin.getLogger().warning("設定檔裡的材質名稱無效：" + materialName + "，改用石頭代替");
            material = Material.STONE;
        }

        ItemStack item = new ItemStack(material, Math.max(1, amount));
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(colorize(displayName));
            if (lore != null && !lore.isEmpty()) {
                meta.setLore(lore.stream().map(this::colorize).toList());
            }
            meta.getPersistentDataContainer().set(markerKey, PersistentDataType.BYTE, (byte) 1);
            item.setItemMeta(meta);
        }
        return item;
    }

    private boolean hasMarker(ItemStack item, NamespacedKey key) {
        if (item == null || !item.hasItemMeta()) {
            return false;
        }
        ItemMeta meta = item.getItemMeta();
        if (meta == null) {
            return false;
        }
        Byte marker = meta.getPersistentDataContainer().get(key, PersistentDataType.BYTE);
        return marker != null && marker == (byte) 1;
    }

    private String colorize(String input) {
        if (input == null) return "";
        return ChatColor.translateAlternateColorCodes('&', input);
    }
}
