package com.xzh.survival;

import org.bukkit.Bukkit;
import org.bukkit.GameRule;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class CustomTimeManager implements CommandExecutor, TabCompleter {
    private static final long TICKS_PER_DAY_PHASE = 12_000L;
    private static final long MAX_DURATION_SECONDS = 7L * 24L * 60L * 60L;
    private static final Pattern DURATION_PATTERN = Pattern.compile("(?i)^(\\d+)(s|m|h|d)?$");

    private final XZHSurvivalPlugin plugin;
    private final Map<World, CycleState> states = new HashMap<>();
    private final Set<World> controlledWorlds = new HashSet<>();
    private BukkitTask task;

    public CustomTimeManager(XZHSurvivalPlugin plugin) {
        this.plugin = plugin;
    }

    public void start() {
        if (plugin.getCommand("customtime") != null) {
            plugin.getCommand("customtime").setExecutor(this);
            plugin.getCommand("customtime").setTabCompleter(this);
        }
        if (plugin.getConfig().getBoolean("custom-time.enabled", false)) {
            enableCycle();
        }
    }

    public void shutdown() {
        if (task != null) {
            task.cancel();
            task = null;
        }
        for (World world : controlledWorlds) {
            if (world != null) world.setGameRule(GameRule.DO_DAYLIGHT_CYCLE, true);
        }
        controlledWorlds.clear();
        states.clear();
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("xzhsurvival.admin")) {
            sender.sendMessage("§cYou don't have permission to use custom time.");
            return true;
        }
        if (args.length == 0 || args[0].equalsIgnoreCase("help")) {
            sendHelp(sender, label);
            return true;
        }

        if (args[0].equalsIgnoreCase("info")) {
            sender.sendMessage("§6Custom Time §7| §f" + (isEnabled() ? "Enabled" : "Disabled"));
            sender.sendMessage("§eDay: §f" + formatDuration(getDuration("day")) +
                    " §7| §bNight: §f" + formatDuration(getDuration("night")));
            sender.sendMessage("§7Use §f/" + label + " set day 30m §7or §f/" + label + " set night 30m§7.");
            return true;
        }

        if (args[0].equalsIgnoreCase("set")) {
            if (args.length != 3 || !(args[1].equalsIgnoreCase("day") ||
                    args[1].equalsIgnoreCase("night"))) {
                sender.sendMessage("§cUsage: /" + label + " set <day|night> <duration, e.g. 30m>");
                return true;
            }
            long seconds;
            try {
                seconds = parseDuration(args[2]);
            } catch (IllegalArgumentException exception) {
                sender.sendMessage("§cInvalid duration. Use values such as 30m, 45s, 2h, or 1d.");
                return true;
            }
            String phase = args[1].toLowerCase(Locale.ROOT);
            plugin.getConfig().set("custom-time." + phase + "-duration-seconds", seconds);
            plugin.getConfig().set("custom-time.enabled", true);
            plugin.saveConfig();
            enableCycle();
            sender.sendMessage("§aCustom " + phase + " duration set to §f" +
                    formatDuration(seconds) + "§a. The custom day/night cycle is now enabled.");
            return true;
        }

        if (args[0].equalsIgnoreCase("on") || args[0].equalsIgnoreCase("enable")) {
            plugin.getConfig().set("custom-time.enabled", true);
            plugin.saveConfig();
            enableCycle();
            sender.sendMessage("§aCustom day/night cycle enabled.");
            return true;
        }

        if (args[0].equalsIgnoreCase("off") || args[0].equalsIgnoreCase("disable")) {
            plugin.getConfig().set("custom-time.enabled", false);
            plugin.saveConfig();
            disableCycle(true);
            sender.sendMessage("§eCustom day/night cycle disabled. Vanilla daylight cycling has been restored.");
            return true;
        }

        sendHelp(sender, label);
        return true;
    }

    private void sendHelp(CommandSender sender, String label) {
        sender.sendMessage("§6Custom Time Commands");
        sender.sendMessage("§e/" + label + " set day <duration> §7- set daytime length");
        sender.sendMessage("§e/" + label + " set night <duration> §7- set nighttime length");
        sender.sendMessage("§e/" + label + " info §7- view current settings");
        sender.sendMessage("§e/" + label + " on|off §7- enable or disable custom timing");
        sender.sendMessage("§7Durations: §f30m, 45s, 2h, 1d §7(or a number in minutes).");
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> suggestions = new ArrayList<>();
        if (args.length == 1) {
            for (String value : List.of("set", "info", "on", "off", "help")) {
                if (value.startsWith(args[0].toLowerCase(Locale.ROOT))) suggestions.add(value);
            }
        } else if (args.length == 2 && args[0].equalsIgnoreCase("set")) {
            for (String value : List.of("day", "night")) {
                if (value.startsWith(args[1].toLowerCase(Locale.ROOT))) suggestions.add(value);
            }
        } else if (args.length == 3 && args[0].equalsIgnoreCase("set")) {
            suggestions.add("30m");
            suggestions.add("15m");
            suggestions.add("1h");
        }
        return suggestions;
    }

    private void enableCycle() {
        if (task != null) {
            task.cancel();
            task = null;
        }
        states.clear();
        long now = System.currentTimeMillis();
        for (World world : Bukkit.getWorlds()) {
            if (world.getEnvironment() != World.Environment.NORMAL) continue;
            world.setGameRule(GameRule.DO_DAYLIGHT_CYCLE, false);
            controlledWorlds.add(world);
            long time = Math.floorMod(world.getTime(), 24_000L);
            boolean day = time < TICKS_PER_DAY_PHASE;
            double progress = day ? time / (double) TICKS_PER_DAY_PHASE
                    : (time - TICKS_PER_DAY_PHASE) / (double) TICKS_PER_DAY_PHASE;
            long duration = getDuration(day ? "day" : "night") * 1000L;
            long phaseStart = now - (long) (duration * progress);
            states.put(world, new CycleState(day, phaseStart));
        }
        task = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 1L, 1L);
    }

    private void disableCycle(boolean restoreVanilla) {
        if (task != null) {
            task.cancel();
            task = null;
        }
        if (restoreVanilla) {
            for (World world : controlledWorlds) {
                if (world != null) world.setGameRule(GameRule.DO_DAYLIGHT_CYCLE, true);
            }
            controlledWorlds.clear();
        }
        states.clear();
    }

    private void tick() {
        long now = System.currentTimeMillis();
        for (World world : Bukkit.getWorlds()) {
            if (world.getEnvironment() != World.Environment.NORMAL) continue;
            CycleState state = states.get(world);
            if (state == null) {
                world.setGameRule(GameRule.DO_DAYLIGHT_CYCLE, false);
                controlledWorlds.add(world);
                long time = Math.floorMod(world.getTime(), 24_000L);
                boolean day = time < TICKS_PER_DAY_PHASE;
                double progress = day ? time / (double) TICKS_PER_DAY_PHASE
                        : (time - TICKS_PER_DAY_PHASE) / (double) TICKS_PER_DAY_PHASE;
                state = new CycleState(day, now - (long) (getDuration(day ? "day" : "night") * 1000L * progress));
                states.put(world, state);
            }

            long duration = Math.max(1L, getDuration(state.day ? "day" : "night") * 1000L);
            double progress = (now - state.phaseStartMillis) / (double) duration;
            if (progress >= 1.0) {
                state.day = !state.day;
                state.phaseStartMillis = now;
                progress = 0.0;
            }
            long phaseTicks = Math.min(TICKS_PER_DAY_PHASE - 1,
                    Math.max(0L, (long) (progress * TICKS_PER_DAY_PHASE)));
            world.setTime(state.day ? phaseTicks : TICKS_PER_DAY_PHASE + phaseTicks);
        }
    }

    private boolean isEnabled() {
        return task != null && !task.isCancelled();
    }

    private long getDuration(String phase) {
        long defaultValue = 30L * 60L;
        return Math.max(1L, plugin.getConfig().getLong("custom-time." + phase + "-duration-seconds", defaultValue));
    }

    private long parseDuration(String input) {
        Matcher matcher = DURATION_PATTERN.matcher(input.trim());
        if (!matcher.matches()) throw new IllegalArgumentException("Bad duration");
        long amount;
        try {
            amount = Long.parseLong(matcher.group(1));
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException("Bad duration", exception);
        }
        if (amount <= 0) throw new IllegalArgumentException("Duration must be positive");
        String unit = matcher.group(2) == null ? "m" : matcher.group(2).toLowerCase(Locale.ROOT);
        long multiplier = switch (unit) {
            case "s" -> 1L;
            case "m" -> 60L;
            case "h" -> 3_600L;
            case "d" -> 86_400L;
            default -> throw new IllegalArgumentException("Bad unit");
        };
        if (amount > MAX_DURATION_SECONDS / multiplier) throw new IllegalArgumentException("Duration too long");
        return amount * multiplier;
    }

    private String formatDuration(long seconds) {
        if (seconds % 86_400L == 0) return (seconds / 86_400L) + "d";
        if (seconds % 3_600L == 0) return (seconds / 3_600L) + "h";
        if (seconds % 60L == 0) return (seconds / 60L) + "m";
        return seconds + "s";
    }

    private static final class CycleState {
        private boolean day;
        private long phaseStartMillis;

        private CycleState(boolean day, long phaseStartMillis) {
            this.day = day;
            this.phaseStartMillis = phaseStartMillis;
        }
    }
}
