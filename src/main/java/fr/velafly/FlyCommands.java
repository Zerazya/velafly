package fr.velafly;

import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** /fly, /flytime et /velafly. */
public final class FlyCommands implements TabExecutor {

    private static final List<String> SUBS = List.of("add", "remove", "set", "check", "voucher", "withdraw", "reload", "help");

    private final VelaFlyPlugin plugin;
    private final FlyManager fly;

    public FlyCommands(VelaFlyPlugin plugin) {
        this.plugin = plugin;
        this.fly = plugin.fly();
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        switch (command.getName().toLowerCase(Locale.ROOT)) {
            case "fly" -> fly(sender, args);
            case "flytime" -> flytime(sender, args);
            default -> velafly(sender, args);
        }
        return true;
    }

    // ---------------------------------------------------------------- /fly [on|off] [joueur]

    private void fly(CommandSender sender, String[] args) {
        Boolean forced = null;
        Player target = sender instanceof Player p ? p : null;
        boolean other = false;
        for (String a : args) {
            if (a.equalsIgnoreCase("on")) {
                forced = true;
            } else if (a.equalsIgnoreCase("off")) {
                forced = false;
            } else {
                if (!sender.hasPermission("velafly.fly.others")) {
                    plugin.send(sender, "no-permission");
                    return;
                }
                target = Bukkit.getPlayerExact(a);
                if (target == null) {
                    plugin.send(sender, "unknown-player", "player", a);
                    return;
                }
                other = !(sender instanceof Player p && p.equals(target));
            }
        }
        if (target == null) {
            plugin.send(sender, "usage-fly");
            return;
        }
        if (!other && !sender.hasPermission("velafly.use")) {
            plugin.send(sender, "no-permission");
            return;
        }
        boolean want = forced != null ? forced : !fly.active(target.getUniqueId());
        if (want) {
            fly.enable(target);
        } else {
            fly.disable(target, "fly-disabled");
        }
        if (other) {
            plugin.send(sender, "fly-toggled-other", "player", target.getName(),
                    "state", want ? "activé" : "désactivé");
        }
    }

    // ---------------------------------------------------------------- /flytime [joueur]

    private void flytime(CommandSender sender, String[] args) {
        if (args.length == 0) {
            if (!(sender instanceof Player p)) {
                plugin.send(sender, "player-only");
                return;
            }
            if (!sender.hasPermission("velafly.time")) {
                plugin.send(sender, "no-permission");
                return;
            }
            plugin.send(sender, "time-self", "time", fly.timeText(p));
            return;
        }
        if (!sender.hasPermission("velafly.time.others")) {
            plugin.send(sender, "no-permission");
            return;
        }
        OfflinePlayer target = resolve(args[0]);
        if (target == null) {
            plugin.send(sender, "unknown-player", "player", args[0]);
            return;
        }
        plugin.send(sender, "time-other", "player", name(target), "time", timeOf(target));
    }

    // ---------------------------------------------------------------- /velafly

    private void velafly(CommandSender sender, String[] args) {
        String sub = args.length == 0 ? "help" : args[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "withdraw", "retirer" -> withdraw(sender, args);
            case "add", "give" -> adjust(sender, args, 1);
            case "remove", "take" -> adjust(sender, args, -1);
            case "set" -> adjust(sender, args, 0);
            case "check" -> check(sender, args);
            case "voucher", "papier" -> voucher(sender, args);
            case "reload" -> {
                if (!sender.hasPermission("velafly.admin")) {
                    plugin.send(sender, "no-permission");
                    return;
                }
                plugin.reload();
                plugin.send(sender, "reloaded");
            }
            default -> sender.sendMessage(plugin.msg("help"));
        }
    }

    /** mode : 1 = ajouter, -1 = retirer, 0 = définir. */
    private void adjust(CommandSender sender, String[] args, int mode) {
        if (!sender.hasPermission("velafly.admin")) {
            plugin.send(sender, "no-permission");
            return;
        }
        if (args.length < 3) {
            plugin.send(sender, "usage-adjust");
            return;
        }
        OfflinePlayer target = resolve(args[1]);
        if (target == null) {
            plugin.send(sender, "unknown-player", "player", args[1]);
            return;
        }
        long secs = TimeUtil.parse(args[2]);
        if (secs < 0 || (secs == 0 && mode != 0)) {
            plugin.send(sender, "invalid-time");
            return;
        }
        var id = target.getUniqueId();
        String n = name(target);
        if (mode == 1) {
            fly.add(id, secs, n);
        } else if (mode == -1) {
            fly.setSeconds(id, fly.seconds(id) - secs, n);
        } else {
            fly.setSeconds(id, secs, n);
        }
        fly.saveNow();
        String key = mode == 1 ? "added" : (mode == -1 ? "removed" : "set");
        plugin.send(sender, key, "player", n, "time", TimeUtil.format(secs), "total", TimeUtil.format(fly.seconds(id)));
    }

    private void check(CommandSender sender, String[] args) {
        if (!sender.hasPermission("velafly.admin")) {
            plugin.send(sender, "no-permission");
            return;
        }
        if (args.length < 2) {
            plugin.send(sender, "usage-adjust");
            return;
        }
        OfflinePlayer target = resolve(args[1]);
        if (target == null) {
            plugin.send(sender, "unknown-player", "player", args[1]);
            return;
        }
        plugin.send(sender, "time-other", "player", name(target), "time", timeOf(target));
    }

    /** /velafly voucher <durée> [nombre] [joueur] : donne des papiers de temps de vol. */
    private void voucher(CommandSender sender, String[] args) {
        if (!sender.hasPermission("velafly.admin")) {
            plugin.send(sender, "no-permission");
            return;
        }
        if (args.length < 2) {
            plugin.send(sender, "usage-voucher");
            return;
        }
        long secs = TimeUtil.parse(args[1]);
        if (secs <= 0) {
            plugin.send(sender, "invalid-time");
            return;
        }
        int amount = 1;
        Player target = sender instanceof Player p ? p : null;
        for (int i = 2; i < args.length; i++) {
            try {
                amount = Math.max(1, Math.min(64, Integer.parseInt(args[i])));
            } catch (NumberFormatException ex) {
                target = Bukkit.getPlayerExact(args[i]);
                if (target == null) {
                    plugin.send(sender, "unknown-player", "player", args[i]);
                    return;
                }
            }
        }
        if (target == null) {
            plugin.send(sender, "usage-voucher");
            return;
        }
        give(target, secs, amount);
        plugin.send(sender, "voucher-given", "amount", amount, "time", TimeUtil.format(secs), "player", target.getName());
        if (!target.equals(sender)) {
            plugin.send(target, "voucher-received", "amount", amount, "time", TimeUtil.format(secs));
        }
    }

    /** /velafly withdraw <durée> [nombre] : transforme son propre temps de vol en papier. */
    private void withdraw(CommandSender sender, String[] args) {
        if (!(sender instanceof Player p)) {
            plugin.send(sender, "player-only");
            return;
        }
        if (!p.hasPermission("velafly.withdraw") || !plugin.getConfig().getBoolean("voucher.allow-withdraw", true)) {
            plugin.send(sender, "no-permission");
            return;
        }
        if (args.length < 2) {
            plugin.send(sender, "usage-withdraw");
            return;
        }
        long secs = TimeUtil.parse(args[1]);
        if (secs <= 0) {
            plugin.send(sender, "invalid-time");
            return;
        }
        long min = Math.max(1, plugin.getConfig().getLong("voucher.min-seconds", 60));
        if (secs < min) {
            plugin.send(sender, "withdraw-min", "min", TimeUtil.format(min));
            return;
        }
        int amount = 1;
        if (args.length > 2) {
            try {
                amount = Math.max(1, Math.min(64, Integer.parseInt(args[2])));
            } catch (NumberFormatException ex) {
                plugin.send(sender, "usage-withdraw");
                return;
            }
        }
        long total = secs * amount;
        if (fly.seconds(p.getUniqueId()) < total) {
            plugin.send(sender, "not-enough-time", "time", TimeUtil.format(fly.seconds(p.getUniqueId())));
            return;
        }
        // le temps est retiré AVANT de donner le papier
        fly.setSeconds(p.getUniqueId(), fly.seconds(p.getUniqueId()) - total, p.getName());
        fly.saveNow();
        give(p, secs, amount);
        plugin.send(p, "withdrawn", "amount", amount, "time", TimeUtil.format(secs),
                "total", TimeUtil.format(fly.seconds(p.getUniqueId())));
    }

    private void give(Player target, long secs, int amount) {
        ItemStack item = plugin.voucher(secs, amount);
        for (ItemStack rest : target.getInventory().addItem(item).values()) {
            target.getWorld().dropItemNaturally(target.getLocation(), rest);
        }
    }

    // ---------------------------------------------------------------- utilitaires

    private OfflinePlayer resolve(String name) {
        Player online = Bukkit.getPlayerExact(name);
        if (online != null) {
            return online;
        }
        return Bukkit.getOfflinePlayerIfCached(name);
    }

    private String name(OfflinePlayer p) {
        return p.getName() == null ? "?" : p.getName();
    }

    private String timeOf(OfflinePlayer p) {
        if (p instanceof Player online && fly.unlimited(online)) {
            return plugin.getConfig().getString("unlimited-text", "illimité");
        }
        return TimeUtil.format(fly.seconds(p.getUniqueId()));
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> out = new ArrayList<>();
        String cmd = command.getName().toLowerCase(Locale.ROOT);
        if (cmd.equals("fly")) {
            if (args.length == 1) {
                out.addAll(List.of("on", "off"));
                if (sender.hasPermission("velafly.fly.others")) {
                    Bukkit.getOnlinePlayers().forEach(p -> out.add(p.getName()));
                }
            }
        } else if (cmd.equals("flytime")) {
            if (args.length == 1 && sender.hasPermission("velafly.time.others")) {
                Bukkit.getOnlinePlayers().forEach(p -> out.add(p.getName()));
            }
        } else if (args.length == 1) {
            for (String s : SUBS) {
                if (s.equals("withdraw") || sender.hasPermission("velafly.admin")) {
                    out.add(s);
                }
            }
        } else {
            String sub = args[0].toLowerCase(Locale.ROOT);
            boolean admin = sender.hasPermission("velafly.admin");
            if (args.length == 2 && admin && List.of("add", "remove", "set", "check", "give", "take").contains(sub)) {
                Bukkit.getOnlinePlayers().forEach(p -> out.add(p.getName()));
            } else if (args.length == 2 && (sub.equals("voucher") && admin || sub.equals("withdraw"))) {
                out.addAll(List.of("10m", "30m", "1h", "1d"));
            } else if (args.length == 3 && admin && List.of("add", "remove", "set", "give", "take").contains(sub)) {
                out.addAll(List.of("10m", "30m", "1h", "1d"));
            } else if (args.length >= 3 && sub.equals("voucher") && admin) {
                out.addAll(List.of("1", "5", "16", "64"));
                Bukkit.getOnlinePlayers().forEach(p -> out.add(p.getName()));
            }
        }
        String last = args.length == 0 ? "" : args[args.length - 1].toLowerCase(Locale.ROOT);
        out.removeIf(s -> !s.toLowerCase(Locale.ROOT).startsWith(last));
        return out;
    }
}
