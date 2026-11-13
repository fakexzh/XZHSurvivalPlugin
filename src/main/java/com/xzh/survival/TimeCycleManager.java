package com.xzh.survival;

import io.papermc.paper.block.bed.BedRuleResult;
import org.bukkit.Bukkit;
import org.bukkit.GameRules;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerBedEnterEvent;
import org.bukkit.event.player.PlayerBedLeaveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.world.TimeSkipEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

public final class TimeCycleManager implements Listener, CommandExecutor, TabCompleter {
    private static final long DAY_START = 0L;
    private static final long NIGHT_START = 12_000L;
    private static final long CYCLE_LENGTH = 24_000L;
    private static final double PHASE_TICKS = 12_000.0;
    private static final double TICKS_PER_SECOND = 20.0;
    private static final double MIN_DURATION_MINUTES = 1.0;
    private static final double MAX_DURATION_MINUTES = 1_440.0;

    private final JavaPlugin plugin;
    private final Map<UUID, WorldState> states = new HashMap<>();
    private double dayMinutes;
    private double nightMinutes;
    private boolean enabled;
    private boolean paused;
    private boolean started;
    private int taskId = -1;
    private int sleepScanCounter;

    private static final class WorldState {
        private double fractionalTicks;
        private boolean sleeping;
    }

    public TimeCycleManager(JavaPlugin plugin) {
        this.plugin = plugin;
        reloadSettings();
        Bukkit.getPluginManager().registerEvents(this, plugin);
        if (plugin.getCommand("timecycle") != null) {
            plugin.getCommand("timecycle").setExecutor(this);
            plugin.getCommand("timecycle").setTabCompleter(this);
        }
    }

    public void start() {
        for (World world : Bukkit.getWorlds()) {
            if (isManaged(world)) {
                WorldState state = states.computeIfAbsent(world.getUID(), ignored -> new WorldState());
                state.sleeping = hasSleepingPlayers(world);
                synchronizeAdvanceTimeRule(world);
            }
        }
        started = true;
        updateTaskState();
    }

    private void updateTaskState() {
        if (!started) return;
        if (enabled && taskId == -1) {
            taskId = Bukkit.getScheduler().scheduleSyncRepeatingTask(plugin, this::tick, 1L, 1L);
        } else if (!enabled && taskId != -1) {
            Bukkit.getScheduler().cancelTask(taskId);
            taskId = -1;
        }
        synchronizeAllWorldRules();
    }

    public void shutdown() {
        if (taskId != -1) {
            Bukkit.getScheduler().cancelTask(taskId);
            taskId = -1;
        }
        for (World world : Bukkit.getWorlds()) {
            if (isManaged(world)) {
                setAdvanceTime(world, true);
            }
        }
        states.clear();
    }

    public void reloadSettings() {
        enabled = plugin.getConfig().getBoolean("custom-time.enabled", true);
        dayMinutes = clampDuration(plugin.getConfig().getDouble("custom-time.day-duration-minutes", 20.0));
        nightMinutes = clampDuration(plugin.getConfig().getDouble("custom-time.night-duration-minutes", 10.0));
        states.values().forEach(state -> state.fractionalTicks = 0.0);
        updateTaskState();
    }

    private static double clampDuration(double minutes) {
        if (!Double.isFinite(minutes)) return 20.0;
        return Math.max(MIN_DURATION_MINUTES, Math.min(MAX_DURATION_MINUTES, minutes));
    }

    private void tick() {
        if (!enabled) return;
        sleepScanCounter++;
        boolean refreshSleepState = sleepScanCounter >= 5;
        if (refreshSleepState) sleepScanCounter = 0;
        for (World world : Bukkit.getWorlds()) {
            if (!isManaged(world)) continue;
            WorldState state = states.computeIfAbsent(world.getUID(), ignored -> new WorldState());
            if (refreshSleepState) state.sleeping = hasSleepingPlayers(world);

            if (state.sleeping) {
                setAdvanceTime(world, true);
                continue;
            }

            setAdvanceTime(world, false);
            if (paused) continue;

            long time = Math.floorMod(world.getTime(), CYCLE_LENGTH);
            boolean day = time < NIGHT_START;
            double durationSeconds = (day ? dayMinutes : nightMinutes) * 60.0;
            double ticksPerServerTick = PHASE_TICKS / (durationSeconds * TICKS_PER_SECOND);
            state.fractionalTicks += ticksPerServerTick;

            long advance = (long) Math.floor(state.fractionalTicks);
            if (advance <= 0L) continue;

            long boundary = day ? NIGHT_START : CYCLE_LENGTH;
            long remaining = boundary - time;
            if (advance >= remaining) {
                world.setTime(boundary);
                state.fractionalTicks = 0.0;
            } else {
                world.setTime(time + advance);
                state.fractionalTicks -= advance;
            }
        }
    }

    private boolean isManaged(World world) {
        if (world.getEnvironment() != World.Environment.NORMAL) return false;
        List<String> configuredWorlds = plugin.getConfig().getStringList("custom-time.worlds");
        return configuredWorlds.isEmpty() || configuredWorlds.contains(world.getName());
    }

    private boolean hasSleepingPlayers(World world) {
        return world.getPlayers().stream().anyMatch(Player::isSleeping);
    }

    private void setAdvanceTime(World world, boolean value) {
        Boolean current = world.getGameRuleValue(GameRules.ADVANCE_TIME);
        if (current == null || current != value) {
            world.setGameRule(GameRules.ADVANCE_TIME, value);
        }
    }

    private void synchronizeAdvanceTimeRule(World world) {
        setAdvanceTime(world, !enabled || hasSleepingPlayers(world));
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBedEnter(PlayerBedEnterEvent event) {
        World world = event.getPlayer().getWorld();
        if (!enabled || !isManaged(world)) return;

        BedRuleResult sleepRule = event.enterAction().canSleep();
        if (!sleepRule.success()) return;

        // Vanilla requires time advancement to remain enabled while a player sleeps.
        // Re-enable it before the player enters the bed so the normal sleep countdown
        // and NIGHT_SKIP time-skip can complete without being interrupted.
        setAdvanceTime(world, true);
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (!hasSleepingPlayers(world)) {
                setAdvanceTime(world, false);
            }
        }, 2L);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBedLeave(PlayerBedLeaveEvent event) {
        scheduleSleepStateSync(event.getPlayer().getWorld());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerQuit(PlayerQuitEvent event) {
        scheduleSleepStateSync(event.getPlayer().getWorld());
    }

    private void scheduleSleepStateSync(World world) {
        if (!enabled || !isManaged(world)) return;
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (!hasSleepingPlayers(world)) {
                setAdvanceTime(world, false);
                WorldState state = states.computeIfAbsent(world.getUID(), ignored -> new WorldState());
                state.fractionalTicks = 0.0;
            }
        }, 1L);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onTimeSkip(TimeSkipEvent event) {
        if (!enabled || !isManaged(event.getWorld())) return;
        if (event.getSkipReason() == TimeSkipEvent.SkipReason.NIGHT_SKIP
                || event.getSkipReason() == TimeSkipEvent.SkipReason.COMMAND) {
            WorldState state = states.computeIfAbsent(event.getWorld().getUID(), ignored -> new WorldState());
            state.fractionalTicks = 0.0;
        }
        if (event.getSkipReason() == TimeSkipEvent.SkipReason.NIGHT_SKIP) {
            World world = event.getWorld();
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                if (!hasSleepingPlayers(world)) {
                    setAdvanceTime(world, false);
                    WorldState state = states.computeIfAbsent(world.getUID(), ignored -> new WorldState());
                    state.fractionalTicks = 0.0;
                }
            }, 1L);
        }
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("survival.timecycle.admin")) {
            sender.sendMessage("§cYou do not have permission to manage the time cycle.");
            return true;
        }

        if (args.length == 0 || args[0].equalsIgnoreCase("status")) {
            World world = resolveWorld(sender);
            if (world == null || !isManaged(world)) {
                sender.sendMessage("§cNo managed Overworld is available.");
                return true;
            }
            sendStatus(sender, world);
            return true;
        }

        String subcommand = args[0].toLowerCase(Locale.ROOT);
        switch (subcommand) {
            case "day" -> setPhase(sender, false);
            case "night" -> setPhase(sender, true);
            case "setday" -> setDuration(sender, args, true);
            case "setnight" -> setDuration(sender, args, false);
            case "pause" -> {
                paused = true;
                synchronizeAllWorldRules();
                sender.sendMessage("§eThe custom time cycle is paused. Sleeping remains available.");
            }
            case "resume" -> {
                paused = false;
                synchronizeAllWorldRules();
                sender.sendMessage("§aThe custom time cycle has resumed.");
            }
            case "reload" -> {
                plugin.reloadConfig();
                reloadSettings();
                synchronizeAllWorldRules();
                sender.sendMessage("§aTime-cycle settings reloaded.");
            }
            case "reset" -> {
                plugin.getConfig().set("custom-time.enabled", true);
                plugin.getConfig().set("custom-time.day-duration-minutes", 20.0);
                plugin.getConfig().set("custom-time.night-duration-minutes", 10.0);
                plugin.getConfig().set("custom-time.worlds", List.of());
                plugin.saveConfig();
                paused = false;
                reloadSettings();
                synchronizeAllWorldRules();
                sender.sendMessage("§aTime-cycle settings reset to 20 minutes of day and 10 minutes of night.");
            }
            default -> sendUsage(sender, label);
        }
        return true;
    }

    private void setPhase(CommandSender sender, boolean night) {
        World world = resolveWorld(sender);
        if (world == null || !isManaged(world)) {
            sender.sendMessage("§cNo managed Overworld is available.");
            return;
        }
        if (hasSleepingPlayers(world)) {
            sender.sendMessage("§cThe phase cannot be changed while a player is sleeping.");
            return;
        }
        long current = Math.floorMod(world.getTime(), CYCLE_LENGTH);
        if (night && current < NIGHT_START) {
            world.setTime(NIGHT_START);
        } else if (!night && current >= NIGHT_START) {
            world.setTime(DAY_START);
        }
        WorldState state = states.computeIfAbsent(world.getUID(), ignored -> new WorldState());
        state.fractionalTicks = 0.0;
        sender.sendMessage(night ? "§7The world is now in its night phase." : "§7The world is now in its day phase.");
    }

    private void setDuration(CommandSender sender, String[] args, boolean day) {
        if (args.length != 2) {
            sender.sendMessage(day ? "§cUsage: /timecycle setday <minutes>" : "§cUsage: /timecycle setnight <minutes>");
            return;
        }
        try {
            double value = Double.parseDouble(args[1]);
            if (!Double.isFinite(value) || value < MIN_DURATION_MINUTES || value > MAX_DURATION_MINUTES) {
                sender.sendMessage("§cDuration must be between 1 and 1440 minutes.");
                return;
            }
            String path = day ? "custom-time.day-duration-minutes" : "custom-time.night-duration-minutes";
            plugin.getConfig().set(path, value);
            plugin.saveConfig();
            reloadSettings();
            sender.sendMessage("§a" + (day ? "Day" : "Night") + " duration set to " + value + " minutes.");
        } catch (NumberFormatException exception) {
            sender.sendMessage("§cEnter a valid number of minutes.");
        }
    }

    private void sendStatus(CommandSender sender, World world) {
        long time = Math.floorMod(world.getTime(), CYCLE_LENGTH);
        boolean day = time < NIGHT_START;
        long remaining = (day ? NIGHT_START : CYCLE_LENGTH) - time;
        double durationSeconds = (day ? dayMinutes : nightMinutes) * 60.0;
        double secondsRemaining = remaining * durationSeconds / PHASE_TICKS;
        String phase = day ? "Day" : "Night";
        String state = !enabled ? "Disabled" : paused ? "Paused" : "Running";
        sender.sendMessage("§6[Time] §fWorld: §e" + world.getName());
        sender.sendMessage("§6[Time] §fState: §e" + state + " §8| §fPhase: §e" + phase);
        sender.sendMessage("§6[Time] §fDay: §e" + dayMinutes + " min §8| §fNight: §e" + nightMinutes + " min");
        sender.sendMessage("§6[Time] §fApprox. phase remaining: §e" + formatDuration(secondsRemaining));
    }

    private String formatDuration(double seconds) {
        long total = Math.max(0L, (long) Math.ceil(seconds));
        long minutes = total / 60L;
        long remainder = total % 60L;
        return minutes + "m " + remainder + "s";
    }

    private World resolveWorld(CommandSender sender) {
        if (sender instanceof Player player) return player.getWorld();
        for (World world : Bukkit.getWorlds()) {
            if (isManaged(world)) return world;
        }
        return null;
    }

    private void synchronizeAllWorldRules() {
        for (World world : Bukkit.getWorlds()) {
            if (isManaged(world)) synchronizeAdvanceTimeRule(world);
        }
    }

    private void sendUsage(CommandSender sender, String label) {
        sender.sendMessage("§6Time-cycle commands:");
        sender.sendMessage("§e/" + label + " status §7- Show current settings");
        sender.sendMessage("§e/" + label + " day|night §7- Change the current phase");
        sender.sendMessage("§e/" + label + " setday|setnight <minutes> §7- Set phase duration");
        sender.sendMessage("§e/" + label + " pause|resume §7- Pause or resume the cycle");
        sender.sendMessage("§e/" + label + " reload|reset §7- Reload or reset settings");
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (!sender.hasPermission("survival.timecycle.admin")) return List.of();
        if (args.length == 1) {
            return List.of("status", "day", "night", "setday", "setnight", "pause", "resume", "reload", "reset")
                    .stream().filter(value -> value.startsWith(args[0].toLowerCase(Locale.ROOT))).toList();
        }
        if (args.length == 2 && (args[0].equalsIgnoreCase("setday") || args[0].equalsIgnoreCase("setnight"))) {
            return List.of("20", "10", "30", "60").stream()
                    .filter(value -> value.startsWith(args[1])).toList();
        }
        return new ArrayList<>();
    }
}
