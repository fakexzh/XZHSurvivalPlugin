package com.xzh.survival;

import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class AdminFlyCommand implements CommandExecutor, TabCompleter {
    private final boolean grantFlight;

    public AdminFlyCommand(boolean grantFlight) {
        this.grantFlight = grantFlight;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission(grantFlight ? "survival.admin.fly" : "survival.admin.unfly")) {
            sender.sendMessage("§cYou don't have permission to use this command.");
            return true;
        }
        if (args.length != 1) {
            sender.sendMessage("§cUsage: /" + label + " <player>");
            return true;
        }

        Player target = Bukkit.getPlayerExact(args[0]);
        if (target == null) {
            sender.sendMessage("§cPlayer not found or not online: §f" + args[0]);
            return true;
        }

        if (grantFlight) {
            if (target.getAllowFlight()) {
                sender.sendMessage("§e" + target.getName() + " already has flight enabled.");
                return true;
            }
            target.setAllowFlight(true);
            sender.sendMessage("§aGranted flight ability to §f" + target.getName() + "§a.");
            target.sendMessage("§aAn administrator has enabled your flight ability.");
        } else {
            if (!target.getAllowFlight()) {
                sender.sendMessage("§e" + target.getName() + " already has flight disabled.");
                return true;
            }
            if (target.isFlying()) target.setFlying(false);
            target.setAllowFlight(false);
            sender.sendMessage("§aRemoved flight ability from §f" + target.getName() + "§a.");
            target.sendMessage("§cAn administrator has disabled your flight ability.");
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length != 1) return List.of();
        String prefix = args[0].toLowerCase(Locale.ROOT);
        List<String> matches = new ArrayList<>();
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (player.getName().toLowerCase(Locale.ROOT).startsWith(prefix)) {
                matches.add(player.getName());
            }
        }
        return matches;
    }
}
