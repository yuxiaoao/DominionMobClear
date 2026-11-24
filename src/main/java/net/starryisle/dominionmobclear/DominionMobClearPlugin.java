package net.starryisle.dominionmobclear;

import org.bukkit.plugin.java.JavaPlugin;

/**
 * DominionMobClear - periodically removes hostile mobs that are inside a
 * Dominion claim. Spawning inside claims is left to Dominion's own flags;
 * this addon only looks at what is currently inside a claim.
 */
public final class DominionMobClearPlugin extends JavaPlugin {

    private MobClearService service;
    private MobClearCommand command;

    @Override
    public void onEnable() {
        if (!getServer().getPluginManager().isPluginEnabled("Dominion")) {
            getLogger().severe("Dominion is not enabled; DominionMobClear will be disabled.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        saveDefaultConfig();
        service = new MobClearService(this);
        if (!service.init()) {
            getLogger().severe("DominionAPI could not be loaded; DominionMobClear will be disabled.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        getServer().getPluginManager().registerEvents(new DominionIndexListener(service), this);
        command = new MobClearCommand(this);
        var pluginCommand = getCommand("dmc");
        if (pluginCommand != null) {
            pluginCommand.setExecutor(command);
            pluginCommand.setTabCompleter(command);
        } else {
            getLogger().severe("plugin.yml is missing the dmc command.");
        }
        service.start();
    }

    @Override
    public void onDisable() {
        if (service != null) {
            service.stop();
        }
        getServer().getScheduler().cancelTasks(this);
    }

    public MobClearService service() {
        return service;
    }
}
