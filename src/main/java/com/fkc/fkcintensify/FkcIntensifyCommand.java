package com.fkc.fkcintensify;

import org.bukkit.ChatColor;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;

import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Predicate;

public class FkcIntensifyCommand implements CommandExecutor {

    private final FkcIntensifyPlugin plugin;
    private final CustomItemFactory itemFactory;
    private final EnhanceManager enhanceManager;
    private final ChunkLimitManager chunkLimitManager;

    public FkcIntensifyCommand(FkcIntensifyPlugin plugin, CustomItemFactory itemFactory,
                                EnhanceManager enhanceManager, ChunkLimitManager chunkLimitManager) {
        this.plugin = plugin;
        this.itemFactory = itemFactory;
        this.enhanceManager = enhanceManager;
        this.chunkLimitManager = chunkLimitManager;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) {
            sendUsage(sender);
            return true;
        }

        switch (args[0].toLowerCase()) {
            case "reload" -> handleReload(sender);
            case "give" -> handleGive(sender, args);
            case "enhance" -> handleEnhance(sender);
            case "info" -> handleInfo(sender);
            default -> sendUsage(sender);
        }
        return true;
    }

    private void sendUsage(CommandSender sender) {
        sender.sendMessage(ChatColor.YELLOW + "用法:");
        sender.sendMessage(ChatColor.YELLOW + "/fkci reload");
        sender.sendMessage(ChatColor.YELLOW + "/fkci give <玩家> <stone/nodowngrade/nodestroy> [數量]");
        sender.sendMessage(ChatColor.YELLOW + "/fkci enhance");
        sender.sendMessage(ChatColor.YELLOW + "/fkci info");
    }

    // ============================================================
    // reload
    // ============================================================
    private void handleReload(CommandSender sender) {
        if (!sender.hasPermission("fkcintensify.admin")) {
            sender.sendMessage(ChatColor.RED + "你沒有權限執行這個指令。");
            return;
        }
        plugin.reloadConfig();
        enhanceManager.loadCategories();
        chunkLimitManager.clear();
        sender.sendMessage(ChatColor.GREEN + "fkcintensify 設定檔已重新載入。");
    }

    // ============================================================
    // give
    // ============================================================
    private void handleGive(CommandSender sender, String[] args) {
        if (!sender.hasPermission("fkcintensify.admin")) {
            sender.sendMessage(ChatColor.RED + "你沒有權限執行這個指令。");
            return;
        }
        if (args.length < 3) {
            sender.sendMessage(ChatColor.RED + "用法: /fkci give <玩家> <stone/nodowngrade/nodestroy> [數量]");
            return;
        }

        Player target = Bukkit.getPlayer(args[1]);
        if (target == null) {
            sender.sendMessage(ChatColor.RED + "找不到在線玩家：" + args[1]);
            return;
        }

        int amount = 1;
        if (args.length >= 4) {
            try {
                amount = Integer.parseInt(args[3]);
            } catch (NumberFormatException e) {
                sender.sendMessage(ChatColor.RED + "數量必須是整數：" + args[3]);
                return;
            }
        }

        ItemStack item = switch (args[2].toLowerCase()) {
            case "stone" -> itemFactory.createIntensifyStone(amount);
            case "nodowngrade" -> itemFactory.createPreventDowngradeStone(amount);
            case "nodestroy" -> itemFactory.createPreventDestroyStone(amount);
            default -> null;
        };

        if (item == null) {
            sender.sendMessage(ChatColor.RED + "未知的道具種類，請用 stone / nodowngrade / nodestroy");
            return;
        }

        target.getInventory().addItem(item);
        sender.sendMessage(ChatColor.GREEN + "已給予 " + target.getName() + " x" + amount + " 個道具。");
        target.sendMessage(ChatColor.GREEN + "你收到了 x" + amount + " 個道具。");
    }

    // ============================================================
    // info
    // ============================================================
    private void handleInfo(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(ChatColor.RED + "只有玩家能使用這個指令。");
            return;
        }

        ItemStack held = player.getInventory().getItemInMainHand();
        ToolCategory category = enhanceManager.resolveCategory(held);
        if (category == null) {
            player.sendMessage(ChatColor.RED + "你手持的物品無法強化。");
            return;
        }

        int level = enhanceManager.getLevel(held);
        int maxLevel = enhanceManager.getMaxLevel();
        double nextRate = enhanceManager.getSuccessRate(level);

        player.sendMessage(ChatColor.GOLD + "===== 強化資訊 =====");
        player.sendMessage(ChatColor.YELLOW + "分類: " + ChatColor.WHITE + category.getName());
        player.sendMessage(ChatColor.YELLOW + "目前等級: " + ChatColor.WHITE + "+" + level + " / +" + maxLevel);
        if (level < maxLevel) {
            player.sendMessage(ChatColor.YELLOW + "下一級成功率: " + ChatColor.WHITE
                    + String.format("%.1f%%", nextRate * 100));
        } else {
            player.sendMessage(ChatColor.YELLOW + "已達最高等級。");
        }
        player.sendMessage(ChatColor.YELLOW + "降階門檻: " + ChatColor.WHITE + "+" + enhanceManager.getDowngradeStartLevel() + " 起");
        player.sendMessage(ChatColor.YELLOW + "損毀門檻: " + ChatColor.WHITE + "+" + enhanceManager.getDestroyStartLevel() + " 起");
    }

    // ============================================================
    // enhance（核心流程）
    // ============================================================
    private void handleEnhance(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(ChatColor.RED + "只有玩家能使用這個指令。");
            return;
        }
        if (!player.hasPermission("fkcintensify.use")) {
            player.sendMessage(ChatColor.RED + "你沒有權限使用強化功能。");
            return;
        }

        PlayerInventory inventory = player.getInventory();
        ItemStack held = inventory.getItemInMainHand();

        ToolCategory category = enhanceManager.resolveCategory(held);
        if (category == null) {
            player.sendMessage(ChatColor.RED + "你手持的物品無法強化。");
            return;
        }

        int currentLevel = enhanceManager.getLevel(held);
        int maxLevel = enhanceManager.getMaxLevel();
        if (currentLevel >= maxLevel) {
            player.sendMessage(ChatColor.RED + "這件裝備已經是最高等級 +" + maxLevel + "，無法再強化。");
            return;
        }

        int stonesNeeded = enhanceManager.getStonesPerAttempt();
        if (countMatching(inventory, itemFactory::isIntensifyStone) < stonesNeeded) {
            player.sendMessage(ChatColor.RED + "強化石不足，需要 " + stonesNeeded + " 個。");
            return;
        }

        // 扣除強化石（不管這次強化成功或失敗，素材都會被消耗）
        consumeMatching(inventory, itemFactory::isIntensifyStone, stonesNeeded);

        double successRate = enhanceManager.getSuccessRate(currentLevel);
        boolean success = ThreadLocalRandom.current().nextDouble() < successRate;

        if (success) {
            int newLevel = currentLevel + 1;
            enhanceManager.applyLevel(held, category, newLevel);
            inventory.setItemInMainHand(held); // 一定要寫回去，不然玩家手上的裝備不會真的更新
            player.sendMessage(ChatColor.GREEN + "強化成功！等級提升至 +" + newLevel + "！");
            return;
        }

        // ---------- 強化失敗 ----------
        handleFailure(player, inventory, held, category, currentLevel);
    }

    private void handleFailure(Player player, PlayerInventory inventory, ItemStack held,
                                ToolCategory category, int currentLevel) {
        int downgradeStart = enhanceManager.getDowngradeStartLevel();
        int destroyStart = enhanceManager.getDestroyStartLevel();

        player.sendMessage(ChatColor.RED + "強化失敗……");

        // 判斷是否要降階
        int resultLevel = currentLevel;
        if (currentLevel >= downgradeStart) {
            if (countMatching(inventory, itemFactory::isPreventDowngradeStone) >= 1) {
                consumeMatching(inventory, itemFactory::isPreventDowngradeStone, 1);
                player.sendMessage(ChatColor.AQUA + "防止降階石發揮作用，等級沒有下降！");
            } else {
                resultLevel = currentLevel - 1;
                player.sendMessage(ChatColor.RED + "等級下降至 +" + resultLevel + "！");
            }
        }

        // 判斷是否要損毀（跟降階各自獨立判定）
        boolean destroyed = false;
        if (currentLevel >= destroyStart) {
            boolean destroyRolled = ThreadLocalRandom.current().nextDouble() < enhanceManager.getDestroyChanceOnFail();
            if (destroyRolled) {
                if (countMatching(inventory, itemFactory::isPreventDestroyStone) >= 1) {
                    consumeMatching(inventory, itemFactory::isPreventDestroyStone, 1);
                    player.sendMessage(ChatColor.AQUA + "防止破壞石發揮作用，裝備沒有損毀！");
                } else {
                    destroyed = true;
                }
            }
        }

        if (destroyed) {
            inventory.setItemInMainHand(null);
            player.sendMessage(ChatColor.DARK_RED + "裝備在強化失敗中徹底損毀了……");
            return;
        }

        // 沒有損毀的話，把等級變化（可能降階、可能不變）重新套用到裝備上
        enhanceManager.applyLevel(held, category, resultLevel);
        inventory.setItemInMainHand(held); // 一定要寫回去，不然玩家手上的裝備不會真的更新
    }

    // ============================================================
    // 小工具
    // ============================================================
    private int countMatching(PlayerInventory inventory, Predicate<ItemStack> predicate) {
        int total = 0;
        for (ItemStack item : inventory.getContents()) {
            if (item != null && predicate.test(item)) {
                total += item.getAmount();
            }
        }
        return total;
    }

    private void consumeMatching(PlayerInventory inventory, Predicate<ItemStack> predicate, int amount) {
        int remaining = amount;
        ItemStack[] contents = inventory.getContents();
        for (int i = 0; i < contents.length && remaining > 0; i++) {
            ItemStack item = contents[i];
            if (item == null || !predicate.test(item)) {
                continue;
            }
            int take = Math.min(remaining, item.getAmount());
            item.setAmount(item.getAmount() - take);
            remaining -= take;
            if (item.getAmount() <= 0) {
                contents[i] = null;
            }
        }
        inventory.setContents(contents);
    }
}
