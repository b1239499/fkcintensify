package com.fkc.fkcintensify;

import org.bukkit.plugin.java.JavaPlugin;

public class FkcIntensifyPlugin extends JavaPlugin {

    private CustomItemFactory itemFactory;
    private ChunkLimitManager chunkLimitManager;
    private EnhanceManager enhanceManager;

    @Override
    public void onEnable() {
        saveDefaultConfig();

        this.itemFactory = new CustomItemFactory(this);
        this.chunkLimitManager = new ChunkLimitManager();
        this.enhanceManager = new EnhanceManager(this);

        getServer().getPluginManager().registerEvents(
                new DropListener(this, itemFactory, chunkLimitManager),
                this
        );

        FkcIntensifyCommand commandHandler = new FkcIntensifyCommand(
                this, itemFactory, enhanceManager, chunkLimitManager
        );
        var cmd = getCommand("fkcintensify");
        if (cmd != null) {
            cmd.setExecutor(commandHandler);
        } else {
            getLogger().warning("找不到 fkcintensify 指令，plugin.yml 的 commands 區塊可能有誤。");
        }

        getLogger().info("fkcintensify 已啟用（掉落系統 + 強化系統）。");
    }

    @Override
    public void onDisable() {
        getLogger().info("fkcintensify 已停用。");
    }
}
