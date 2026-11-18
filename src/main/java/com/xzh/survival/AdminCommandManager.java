package com.xzh.survival;

import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.*;

public final class AdminCommandManager implements CommandExecutor, TabCompleter, Listener {
    private static final String ADMIN = "survival.admin.commands";
    private static final Set<String> POWERS = Set.of("fly", "gmc", "gms", "gma", "gmsp", "heal", "feed", "day", "night", "weather");
    private final XZHSurvivalPlugin plugin;
    private final Map<UUID, Set<String>> sessionPowers = new HashMap<>();

    public AdminCommandManager(XZHSurvivalPlugin plugin) {
        this.plugin = plugin;
        Bukkit.getPluginManager().registerEvents(this, plugin);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        sessionPowers.remove(event.getPlayer().getUniqueId());
    }

    private boolean admin(CommandSender sender) {
        return sender.hasPermission(ADMIN);
    }

    private boolean allowed(CommandSender sender, String power) {
        return admin(sender) || sender instanceof Player p
                && sessionPowers.getOrDefault(p.getUniqueId(), Set.of()).contains(power);
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        String cmd = command.getName().toLowerCase(Locale.ROOT);
        if (cmd.equals("xzhpower")) return powerCommand(sender, args);
        String power = switch (cmd) {
            case "gmc" -> "gmc";
            case "gms" -> "gms";
            case "gma" -> "gma";
            case "gmsp" -> "gmsp";
            case "gamemode", "gm" -> "gamemode";
            case "fly" -> "fly";
            case "unfly" -> "unfly";
            case "heal" -> "heal";
            case "feed" -> "feed";
            case "day" -> "day";
            case "night" -> "night";
            case "weather" -> "weather";
            default -> null;
        };
        if (power == null) return false;

        boolean delegated = sender instanceof Player p
                && sessionPowers.getOrDefault(p.getUniqueId(), Set.of()).contains(power);
        if (!admin(sender) && !delegated) {
            sender.sendMessage("§cYou don't have permission to use this command.");
            return true;
        }
        if (!(sender instanceof Player self)) {
            sender.sendMessage("§cThis command can only be used by a player.");
            return true;
        }

        if (delegated && !admin(sender) && args.length > 0) {
            sender.sendMessage("§cTemporary delegated powers can only be used on yourself.");
            return true;
        }
        Player target = self;
        if (args.length > 0) {
            if (!admin(sender)) {
                sender.sendMessage("§cYou can only use this power on yourself.");
                return true;
            }
            target = Bukkit.getPlayerExact(args[args.length - 1]);
            if (target == null) {
                sender.sendMessage("§cThat player is not online.");
                return true;
            }
        }

        switch (cmd) {
            case "gmc" -> target.setGameMode(GameMode.CREATIVE);
            case "gms" -> target.setGameMode(GameMode.SURVIVAL);
            case "gma" -> target.setGameMode(GameMode.ADVENTURE);
            case "gmsp" -> target.setGameMode(GameMode.SPECTATOR);
            case "gamemode", "gm" -> {
                if (args.length < 1 || args.length > (admin(sender) ? 2 : 1)) {
                    sender.sendMessage("§cUsage: /gamemode <survival|creative|adventure|spectator> [player]");
                    return true;
                }
                GameMode mode = parseMode(args[0]);
                if (mode == null) {
                    sender.sendMessage("§cUnknown gamemode. Use survival, creative, adventure, or spectator.");
                    return true;
                }
                target.setGameMode(mode);
            }
            case "fly" -> {
                if (admin(sender) && args.length == 1) {
                    if (target.getAllowFlight()) {
                        sender.sendMessage("§e" + target.getName() + " already has flight enabled.");
                        return true;
                    }
                    target.setAllowFlight(true);
                    target.sendMessage("§aAn administrator enabled your flight ability.");
                } else {
                    boolean enable = !target.getAllowFlight();
                    target.setAllowFlight(enable);
                    if (!enable && target.isFlying()) target.setFlying(false);
                }
            }
            case "unfly" -> {
                if (!admin(sender) || args.length != 1) {
                    sender.sendMessage("§cUsage: /unfly <player>");
                    return true;
                }
                if (target.isFlying()) target.setFlying(false);
                target.setAllowFlight(false);
                target.sendMessage("§cAn administrator disabled your flight ability.");
            }
            case "heal" -> {
                target.setHealth(target.getMaxHealth());
                target.setFireTicks(0);
            }
            case "feed" -> {
                target.setFoodLevel(20);
                target.setSaturation(20f);
            }
            case "day" -> target.getWorld().setTime(1000L);
            case "night" -> target.getWorld().setTime(13000L);
            case "weather" -> {
                if (args.length != 1) {
                    sender.sendMessage("§cUsage: /weather <clear|rain|thunder>");
                    return true;
                }
                switch (args[0].toLowerCase(Locale.ROOT)) {
                    case "clear" -> { target.getWorld().setStorm(false); target.getWorld().setThundering(false); }
                    case "rain" -> { target.getWorld().setStorm(true); target.getWorld().setThundering(false); }
                    case "thunder" -> { target.getWorld().setStorm(true); target.getWorld().setThundering(true); }
                    default -> { sender.sendMessage("§cUse clear, rain, or thunder."); return true; }
                }
            }
        }
        if (target.equals(self)) sender.sendMessage("§a" + cmd + " applied to you.");
        else sender.sendMessage("§a" + cmd + " applied to " + target.getName() + ".");
        return true;
    }

    private boolean powerCommand(CommandSender sender, String[] args) {
        if (!admin(sender)) {
            sender.sendMessage("§cOnly administrators can manage temporary powers.");
            return true;
        }
        if (args.length < 2) {
            sender.sendMessage("§e/xzhpower <give|revoke|list|clear> <player> [power]");
            return true;
        }
        Player target = Bukkit.getPlayerExact(args[1]);
        if (target == null) {
            sender.sendMessage("§cPlayer must be online. Powers are session-only.");
            return true;
        }
        Set<String> granted = sessionPowers.computeIfAbsent(target.getUniqueId(), id -> new HashSet<>());
        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "give", "revoke" -> {
                if (args.length != 3 || !POWERS.contains(args[2].toLowerCase(Locale.ROOT))) {
                    sender.sendMessage("§cAvailable powers: " + String.join(", ", new TreeSet<>(POWERS)));
                    return true;
                }
                String power = args[2].toLowerCase(Locale.ROOT);
                if (args[0].equalsIgnoreCase("give")) {
                    granted.add(power);
                    sender.sendMessage("§aGranted temporary " + power + " power to " + target.getName() + ".");
                    target.sendMessage("§aTemporary admin power granted: " + power + ". It ends when you disconnect.");
                } else {
                    granted.remove(power);
                    if (granted.isEmpty()) sessionPowers.remove(target.getUniqueId());
                    sender.sendMessage("§aRevoked " + power + " power from " + target.getName() + ".");
                    target.sendMessage("§cYour temporary " + power + " power was revoked.");
                }
            }
            case "list" -> sender.sendMessage("§e" + target.getName() + " temporary powers: "
                    + (granted.isEmpty() ? "none" : String.join(", ", new TreeSet<>(granted))));
            case "clear" -> {
                sessionPowers.remove(target.getUniqueId());
                sender.sendMessage("§aCleared all temporary powers for " + target.getName() + ".");
                target.sendMessage("§cAll your temporary admin powers were cleared.");
            }
            default -> sender.sendMessage("§e/xzhpower <give|revoke|list|clear> <player> [power]");
        }
        return true;
    }

    private GameMode parseMode(String input) {
        return switch (input.toLowerCase(Locale.ROOT)) {
            case "s", "survival", "0" -> GameMode.SURVIVAL;
            case "c", "creative", "1" -> GameMode.CREATIVE;
            case "a", "adventure", "2" -> GameMode.ADVENTURE;
            case "sp", "spectator", "3" -> GameMode.SPECTATOR;
            default -> null;
        };
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        String cmd = command.getName().toLowerCase(Locale.ROOT);
        if (cmd.equals("xzhpower")) {
            if (!admin(sender)) return List.of();
            if (args.length == 1) return filter(List.of("give", "revoke", "list", "clear"), args[0]);
            if (args.length == 2) return filter(Bukkit.getOnlinePlayers().stream().map(Player::getName).toList(), args[1]);
            if (args.length == 3 && (args[0].equalsIgnoreCase("give") || args[0].equalsIgnoreCase("revoke")))
                return filter(new ArrayList<>(POWERS), args[2]);
            return List.of();
        }
        if (args.length == 1 && admin(sender) && Set.of("gmc","gms","gma","gmsp","fly","unfly","heal","feed").contains(cmd))
            return filter(Bukkit.getOnlinePlayers().stream().map(Player::getName).toList(), args[0]);
        if ((cmd.equals("gamemode") || cmd.equals("gm")) && args.length == 1)
            return filter(List.of("survival","creative","adventure","spectator"), args[0]);
        if ((cmd.equals("gamemode") || cmd.equals("gm")) && args.length == 2 && admin(sender))
            return filter(Bukkit.getOnlinePlayers().stream().map(Player::getName).toList(), args[1]);
        if (cmd.equals("weather") && args.length == 1)
            return filter(List.of("clear","rain","thunder"), args[0]);
        return List.of();
    }

    private List<String> filter(List<String> values, String prefix) {
        String p = prefix.toLowerCase(Locale.ROOT);
        return values.stream().filter(v -> v.toLowerCase(Locale.ROOT).startsWith(p)).sorted().toList();
    }
}
