package net.starryisle.dominionmobclear;

import cn.lunadeer.dominion.api.dtos.DominionDTO;
import cn.lunadeer.dominion.providers.DominionProvider;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.Locale;

/** /dmc toggle|status|here|list|clear|reload */
public final class MobClearCommand implements CommandExecutor, TabCompleter {

    private final DominionMobClearPlugin plugin;

    public MobClearCommand(DominionMobClearPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        String sub = args.length == 0 ? "status" : args[0].toLowerCase(Locale.ROOT);
        if (sub.equals("toggle")) {
            return toggle(sender, args);
        }
        if (sub.equals("status")) {
            return status(sender, args);
        }
        if (sub.equals("here") || sub.equals("info")) {
            return here(sender);
        }
        if (sub.equals("list")) {
            return list(sender);
        }
        if (sub.equals("help")) {
            help(sender);
            return true;
        }
        if (!sender.hasPermission("dominionmobclear.admin")) {
            sender.sendMessage("§c你没有权限使用这个命令。");
            return true;
        }
        MobClearService service = plugin.service();
        switch (sub) {
            case "reload" -> {
                service.restart();
                sender.sendMessage("§a配置已重载。");
            }
            case "clear" -> {
                // 手动清理不受 player-radius-chunks 限制，扫全部领地区块
                MobClearService.ScanResult result = service.sweep(true);
                sender.sendMessage("§a扫描完成: §f" + result.chunks() + " §a区块，检查 §f"
                        + result.checked() + " §a只敌对生物，清理 §f" + result.removed()
                        + " §a只，耗时 §f" + result.millis() + "ms");
            }
            default -> sender.sendMessage("§c用法: /dmc <here|toggle|status|list|clear|reload>");
        }
        return true;
    }

    private void help(CommandSender sender) {
        sender.sendMessage("§6DominionMobClear");
        sender.sendMessage("§7/dmc here §f查看你当前站立的领地及清理开关");
        sender.sendMessage("§7/dmc status [领地] §f查看自己领地是否开启清理");
        sender.sendMessage("§7/dmc toggle [领地] §f切换自己/当前领地的清理");
        sender.sendMessage("§7/dmc list §f列出自己的领地及开关状态");
        sender.sendMessage("§7/dmc clear §f立即扫描一次（管理员）");
        sender.sendMessage("§7/dmc reload §f重载配置（管理员）");
    }

    /** Shows the claim the sender is standing in, including claims owned by others. */
    private boolean here(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("§c这个命令只能玩家使用。");
            return true;
        }
        MobClearService service = plugin.service();
        DominionDTO dom = service.api().getDominion(player.getLocation());
        if (dom == null) {
            sender.sendMessage("§7你当前不在任何领地内。");
            return true;
        }
        if (dom.getId() < 0) {
            sender.sendMessage("§7你当前在 Dominion 的世界级领地内（id " + dom.getId()
                    + "），这不是玩家领地，本插件不会清理它。");
            return true;
        }
        boolean owner = dom.getOwner().equals(player.getUniqueId());
        boolean on = service.isFlagOn(dom);
        sender.sendMessage("§6当前领地: §f" + dom.getName() + " §8#" + dom.getId()
                + (owner ? " §a(你的领地)" : ""));
        sender.sendMessage("§7主人: §f" + service.api().getPlayerName(dom.getOwner()));
        if (!service.useFlag()) {
            sender.sendMessage("§7敌对生物清理: §e全服生效（未启用 flag 控制）");
            return true;
        }
        sender.sendMessage("§7敌对生物清理: " + (on ? "§a已开启" : "§c未开启"));
        if (!on) {
            sender.sendMessage(owner
                    ? "§7用 §f/dmc toggle §7可以开启这块领地的清理。"
                    : "§7需要领地主人或管理员用 §f/dmc toggle §7开启。");
        }
        return true;
    }

    private boolean status(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("§c这个命令只能玩家使用。");
            return true;
        }
        MobClearService service = plugin.service();
        if (args.length >= 2) {
            DominionDTO dom = findOwnClaim(player, args[1]);
            if (dom == null) {
                sender.sendMessage("§c找不到你的领地: " + args[1]);
                return true;
            }
            showClaim(sender, service, dom);
            return true;
        }
        return listOwnClaims(sender, player);
    }

    private boolean toggle(CommandSender sender, String[] args) {
        MobClearService service = plugin.service();
        if (!service.useFlag() || service.flag() == null) {
            sender.sendMessage("§c领地 flag 不可用，请检查 Dominion 是否正常加载。");
            return true;
        }
        boolean admin = sender.hasPermission("dominionmobclear.admin");
        DominionDTO dom;
        boolean selfClaim;
        if (args.length >= 2) {
            // Admins may type another player's claim name; normal players only own.
            dom = admin ? findAllClaim(args[1]) : findOwnClaim(sender, args[1]);
            if (dom == null) {
                sender.sendMessage("§c找不到你有权限的领地: " + args[1]);
                return true;
            }
            selfClaim = sender instanceof Player player
                    && dom.getOwner().equals(player.getUniqueId());
        } else if (sender instanceof Player player) {
            dom = currentClaim(player);
            if (dom == null) {
                sender.sendMessage("§c你当前不在玩家领地内，请用 /dmc toggle <领地> 指定一块。");
                return true;
            }
            if (!dom.getOwner().equals(player.getUniqueId()) && !admin) {
                sender.sendMessage("§c只有领地主人或管理员可以切换这块领地。");
                return true;
            }
            selfClaim = dom.getOwner().equals(player.getUniqueId());
        } else {
            sender.sendMessage("§c控制台请指定领地: /dmc toggle <领地>");
            return true;
        }

        if (!selfClaim && !admin) {
            sender.sendMessage("§c只有领地主人或管理员可以切换这块领地。");
            return true;
        }

        boolean current = service.isFlagOn(dom);
        boolean next = !current;
        CommandSender operator = selfClaim && sender instanceof Player player
                ? player : Bukkit.getConsoleSender();
        try {
            DominionProvider.getInstance()
                    .setDominionEnvFlag(operator, dom, service.flag(), next)
                    .whenComplete((result, error) -> Bukkit.getScheduler().runTask(plugin, () -> {
                        if (error != null || result == null) {
                            sender.sendMessage("§c设置失败: "
                                    + (error == null ? "未知错误" : error.getMessage()));
                            return;
                        }
                        sender.sendMessage("§a已" + (next ? "开启" : "关闭") + " 领地 §f"
                                + dom.getName() + " §a的敌对生物清理。");
                    }));
        } catch (Throwable throwable) {
            sender.sendMessage("§c设置失败: " + throwable.getMessage());
        }
        return true;
    }

    private boolean list(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("§c这个命令只能玩家使用。");
            return true;
        }
        return listOwnClaims(sender, player);
    }

    /** Lists only the sender's own claims, with the current claim marked. */
    private boolean listOwnClaims(CommandSender sender, Player player) {
        MobClearService service = plugin.service();
        List<DominionDTO> dominions = service.api().getAllDominionsOfPlayer(player.getUniqueId());
        if (dominions == null || dominions.isEmpty()) {
            sender.sendMessage("§7你没有领地。");
            return true;
        }
        DominionDTO current = currentClaim(player);
        int currentId = current == null ? Integer.MIN_VALUE : current.getId();
        sender.sendMessage("§6你的领地（" + dominions.size() + "）：");
        int shown = 0;
        for (DominionDTO dom : dominions) {
            if (shown++ >= 30) {
                sender.sendMessage("§7... 只显示前 30 个");
                break;
            }
            sender.sendMessage("§7- §f" + dom.getName() + " §8#" + dom.getId() + " §7清理: "
                    + (service.isFlagOn(dom) ? "§a开启" : "§c关闭")
                    + (dom.getId() == currentId ? " §e(当前)" : ""));
        }
        return true;
    }

    private void showClaim(CommandSender sender, MobClearService service, DominionDTO dom) {
        String owner = service.api().getPlayerName(dom.getOwner());
        sender.sendMessage("§6领地: §f" + dom.getName() + " §8#" + dom.getId());
        sender.sendMessage("§7主人: §f" + owner);
        sender.sendMessage("§7敌对生物清理: "
                + (service.isFlagOn(dom) ? "§a已开启" : "§c未开启"));
        sender.sendMessage("§7世界: §f" + (dom.getWorld() == null
                ? dom.getWorldUid().toString() : dom.getWorld().getName()));
    }

    private DominionDTO currentClaim(Player player) {
        DominionDTO dom = plugin.service().api().getDominion(player.getLocation());
        return dom == null || dom.getId() < 0 ? null : dom;
    }

    private DominionDTO findOwnClaim(CommandSender sender, String name) {
        if (!(sender instanceof Player player)) {
            return null;
        }
        return findOwnClaim(player, name);
    }

    private DominionDTO findOwnClaim(Player player, String name) {
        List<DominionDTO> owned = plugin.service().api().getAllDominionsOfPlayer(player.getUniqueId());
        if (owned == null) {
            return null;
        }
        for (DominionDTO dom : owned) {
            if (dom.getId() > 0 && dom.getName().equalsIgnoreCase(name)) {
                return dom;
            }
        }
        return null;
    }

    /** Admin-only: exact name lookup across all claims, used when typed manually. */
    private DominionDTO findAllClaim(String name) {
        for (DominionDTO dom : plugin.service().api().getAllDominions()) {
            if (dom.getId() > 0 && dom.getName().equalsIgnoreCase(name)) {
                return dom;
            }
        }
        return null;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> out = new java.util.ArrayList<>();
        if (args.length == 1) {
            for (String option : new String[]{"here", "toggle", "status", "list", "clear", "reload", "help"}) {
                if (option.startsWith(args[0].toLowerCase(Locale.ROOT))) {
                    out.add(option);
                }
            }
            return out;
        }
        // Only the sender's own claim names are suggested. Other players' claims
        // must be typed manually (and only admins can actually use them).
        if (args.length == 2 && (args[0].equalsIgnoreCase("toggle")
                || args[0].equalsIgnoreCase("status"))
                && sender instanceof Player player) {
            List<DominionDTO> owned = plugin.service().api()
                    .getAllDominionsOfPlayer(player.getUniqueId());
            if (owned != null) {
                for (DominionDTO dom : owned) {
                    if (dom.getName().toLowerCase(Locale.ROOT)
                            .startsWith(args[1].toLowerCase(Locale.ROOT))) {
                        out.add(dom.getName());
                    }
                }
            }
        }
        return out;
    }
}
