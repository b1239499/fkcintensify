package com.fkc.fkcintensify;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Item;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.player.PlayerFishEvent;
import org.bukkit.inventory.ItemStack;

import java.util.List;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 監聽三種掉落來源：破壞方塊、釣魚、擊殺怪物。
 * 絲綢挖礦不會觸發方塊掉落（避免放置又挖掘無限刷）。
 */
public class DropListener implements Listener {

    private final FkcIntensifyPlugin plugin;
    private final CustomItemFactory itemFactory;
    private final ChunkLimitManager chunkLimitManager;

    public DropListener(FkcIntensifyPlugin plugin, CustomItemFactory itemFactory, ChunkLimitManager chunkLimitManager) {
        this.plugin = plugin;
        this.itemFactory = itemFactory;
        this.chunkLimitManager = chunkLimitManager;
    }

    // ============================================================
    // 破壞方塊
    // ============================================================
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockBreak(BlockBreakEvent event) {
        if (!plugin.getConfig().getBoolean("block.enabled", true)) {
            return;
        }

        // 絲綢挖礦排除：絲綢可以把方塊原封不動挖回來、放回去再挖一次，
        // 等於能無限重複觸發，所以絲綢挖到的方塊一律不算強化石掉落來源
        if (isUsingSilkTouch(event.getPlayer())) {
            return;
        }

        Material blockType = event.getBlock().getType();
        double rate = readRate("block.drops", blockType.name());
        if (rate <= 0) {
            return;
        }

        if (!rollChance(rate)) {
            return;
        }

        if (!passChunkLimit(event.getBlock().getChunk())) {
            return;
        }

        dropAt(event.getBlock().getLocation().add(0.5, 0.5, 0.5));
    }

    // ============================================================
    // 釣魚
    // ============================================================
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerFish(PlayerFishEvent event) {
        if (event.getState() != PlayerFishEvent.State.CAUGHT_FISH) {
            return;
        }
        if (!plugin.getConfig().getBoolean("fish.enabled", true)) {
            return;
        }

        Entity caught = event.getCaught();
        if (!(caught instanceof Item caughtItem)) {
            return;
        }

        Material fishType = caughtItem.getItemStack().getType();
        double rate = readRate("fish.drops", fishType.name());
        if (rate <= 0) {
            return;
        }

        if (!rollChance(rate)) {
            return;
        }

        Player player = event.getPlayer();
        if (!passChunkLimit(player.getLocation().getChunk())) {
            return;
        }

        dropAt(player.getLocation());
    }

    // ============================================================
    // 擊殺怪物
    // ============================================================
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEntityDeath(EntityDeathEvent event) {
        if (!plugin.getConfig().getBoolean("kill.enabled", true)) {
            return;
        }

        LivingEntity dead = event.getEntity();
        Player killer = dead.getKiller();
        if (killer == null) {
            return;
        }

        if (!isWorldAllowed(dead.getWorld())) {
            return;
        }

        double rate = resolveKillRate(dead.getType().name());
        if (rate <= 0) {
            return;
        }

        if (!rollChance(rate)) {
            return;
        }

        if (!passChunkLimit(dead.getLocation().getChunk())) {
            return;
        }

        dropAt(dead.getLocation());
    }

    // ============================================================
    // 共用邏輯
    // ============================================================

    private double readRate(String sectionPath, String key) {
        ConfigurationSection section = plugin.getConfig().getConfigurationSection(sectionPath);
        if (section == null) {
            return 0;
        }
        return section.getDouble(key, 0);
    }

    private double resolveKillRate(String entityTypeName) {
        ConfigurationSection overrides = plugin.getConfig().getConfigurationSection("kill.overrides");
        if (overrides != null && overrides.contains(entityTypeName)) {
            return overrides.getDouble(entityTypeName, 0);
        }
        return plugin.getConfig().getDouble("kill.default-rate", 0);
    }

    private boolean isWorldAllowed(World world) {
        String whitelist = plugin.getConfig().getString("kill.world-whitelist", "ALL");
        if (whitelist == null || whitelist.equalsIgnoreCase("ALL")) {
            return true;
        }
        List<String> allowedWorlds = plugin.getConfig().getStringList("kill.world-whitelist");
        if (allowedWorlds.isEmpty()) {
            Set<String> parts = Set.of(whitelist.split(","));
            return parts.contains(world.getName());
        }
        return allowedWorlds.contains(world.getName());
    }

    private boolean passChunkLimit(org.bukkit.Chunk chunk) {
        if (!plugin.getConfig().getBoolean("chunk-limit.enabled", true)) {
            return true;
        }
        long cooldown = plugin.getConfig().getLong("chunk-limit.cooldown-seconds", 1800);
        return chunkLimitManager.tryTrigger(chunk, cooldown);
    }

    private boolean rollChance(double rate) {
        if (rate >= 1.0) {
            return true;
        }
        return ThreadLocalRandom.current().nextDouble() < rate;
    }

    private boolean isUsingSilkTouch(Player player) {
        ItemStack tool = player.getInventory().getItemInMainHand();
        if (tool.getType() == Material.AIR) {
            return false;
        }
        return tool.getEnchantments().containsKey(org.bukkit.enchantments.Enchantment.SILK_TOUCH);
    }

    private void dropAt(Location location) {
        ItemStack stone = itemFactory.createIntensifyStone(1);
        World world = location.getWorld();
        if (world != null) {
            world.dropItemNaturally(location, stone);
        }
    }
}
