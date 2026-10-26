package com.xzh.survival;

import org.bukkit.Bukkit;
import org.bukkit.GameRule;
import org.bukkit.World;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Controls the real-world duration of the Overworld's day and night phases.
 * External time jumps (including vanilla's sleep-to-morning jump) are detected
 * and used to re-anchor the phase clock instead of being immediately undone.
 */
public final class CustomTimeManager {
    private static final long TICKS_PER_PHASE = 12_000L;
    private final JavaPlugin plugin;
    private final Map<World, CycleState> states = new HashMap<>();
    private final Set<World> controlledWorlds = new HashSet<>();
    private BukkitTask task;

    public CustomTimeManager(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    public void start() {
        if (!plugin.getConfig().getBoolean("custom-time.enabled", true)) return;
        long now = System.currentTimeMillis();
        for (World world : Bukkit.getWorlds()) {
            if (world.getEnvironment() != World.Environment.NORMAL) continue;
            world.setGameRule(GameRule.DO_DAYLIGHT_CYCLE, false);
            controlledWorlds.add(world);
            long time = Math.floorMod(world.getTime(), 24_000L);
            boolean day = time < TICKS_PER_PHASE;
            double phaseProgress = day
                    ? time / (double) TICKS_PER_PHASE
                    : (time - TICKS_PER_PHASE) / (double) TICKS_PER_PHASE;
            long durationMillis = getDurationSeconds(day) * 1000L;
            CycleState state = new CycleState(day, now - (long) (durationMillis * phaseProgress));
            state.lastWrittenTime = world.getTime();
            states.put(world, state);
        }
        task = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 1L, 1L);
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

    private void tick() {
        long now = System.currentTimeMillis();
        for (World world : Bukkit.getWorlds()) {
            if (world.getEnvironment() != World.Environment.NORMAL) continue;

            CycleState state = states.get(world);
            if (state == null) {
                world.setGameRule(GameRule.DO_DAYLIGHT_CYCLE, false);
                controlledWorlds.add(world);
                long time = Math.floorMod(world.getTime(), 24_000L);
                boolean day = time < TICKS_PER_PHASE;
                double phaseProgress = day
                        ? time / (double) TICKS_PER_PHASE
                        : (time - TICKS_PER_PHASE) / (double) TICKS_PER_PHASE;
                state = new CycleState(day,
                        now - (long) (getDurationSeconds(day) * 1000L * phaseProgress));
                state.lastWrittenTime = world.getTime();
                states.put(world, state);
            }

            // If vanilla or an administrator changed world time, re-anchor the
            // custom clock. In particular, sleep can move the world to morning.
            long observedTime = world.getTime();
            if (observedTime != state.lastWrittenTime) {
                long normalizedTime = Math.floorMod(observedTime, 24_000L);
                state.day = normalizedTime < TICKS_PER_PHASE;
                double phaseProgress = state.day
                        ? normalizedTime / (double) TICKS_PER_PHASE
                        : (normalizedTime - TICKS_PER_PHASE) / (double) TICKS_PER_PHASE;
                state.phaseStartMillis = now
                        - (long) (getDurationSeconds(state.day) * 1000L * phaseProgress);
            }

            long durationMillis = Math.max(1000L, getDurationSeconds(state.day) * 1000L);
            double progress = (now - state.phaseStartMillis) / (double) durationMillis;
            if (progress >= 1.0) {
                state.day = !state.day;
                state.phaseStartMillis = now;
                progress = 0.0;
            }

            long phaseTicks = Math.min(TICKS_PER_PHASE - 1L,
                    Math.max(0L, (long) (progress * TICKS_PER_PHASE)));
            long newTime = state.day ? phaseTicks : TICKS_PER_PHASE + phaseTicks;
            world.setTime(newTime);
            state.lastWrittenTime = newTime;
        }
    }

    private long getDurationSeconds(boolean day) {
        String path = day ? "custom-time.day-duration-seconds" : "custom-time.night-duration-seconds";
        long fallback = day ? 2400L : 1500L;
        return Math.max(1L, plugin.getConfig().getLong(path, fallback));
    }

    private static final class CycleState {
        private boolean day;
        private long phaseStartMillis;
        private long lastWrittenTime;

        private CycleState(boolean day, long phaseStartMillis) {
            this.day = day;
            this.phaseStartMillis = phaseStartMillis;
        }
    }
}
