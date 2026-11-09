package com.xzh.survival;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.boss.BarColor;
import org.bukkit.boss.BarStyle;
import org.bukkit.boss.BossBar;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerToggleSneakEvent;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

import java.lang.reflect.Method;
import java.text.NumberFormat;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/** Hold-to-steal interaction. Currency is transferred through the Economy pocket-coin API only. */
public final class RobberyManager implements Listener {
    private static final double STILL_MOVEMENT_SQUARED = 0.0004;
    private final XZHSurvivalPlugin plugin;
    private final Map<UUID, RobberySession> byRobber = new HashMap<>();
    private final Map<UUID, RobberySession> byVictim = new HashMap<>();
    private BukkitTask ticker;
    private long ticks;

    public RobberyManager(XZHSurvivalPlugin plugin) { this.plugin = plugin; }

    public void start() {
        Bukkit.getPluginManager().registerEvents(this, plugin);
        ticker = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 1L, 1L);
        plugin.getLogger().info("Player robbery system enabled.");
    }

    public void shutdown() {
        if (ticker != null) ticker.cancel();
        for (RobberySession session : new ArrayList<>(byRobber.values())) cancel(session);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onSneak(PlayerToggleSneakEvent event) {
        Player robber = event.getPlayer();
        if (!event.isSneaking()) {
            RobberySession active = byRobber.get(robber.getUniqueId());
            if (active != null) cancel(active);
            return;
        }
        if (!plugin.getConfig().getBoolean("robbery.enabled", true)
                || plugin.isPlayerDowned(robber)
                || robber.getGameMode() == GameMode.SPECTATOR
                || robber.getGameMode() == GameMode.CREATIVE
                || byRobber.containsKey(robber.getUniqueId())) return;
        Player victim = nearestEligibleVictim(robber);
        if (victim == null || pocketCoins(victim) < 10L) return;
        startRobbery(robber, victim);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onRobberDamaged(EntityDamageEvent event) {
        if (!(event.getEntity() instanceof Player player)) return;
        RobberySession session = byRobber.get(player.getUniqueId());
        if (session != null) cancel(session);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        UUID id = event.getPlayer().getUniqueId();
        RobberySession session = byRobber.get(id);
        if (session == null) session = byVictim.get(id);
        if (session != null) cancel(session);
    }

    private void tick() {
        ticks++;
        for (RobberySession session : new ArrayList<>(byRobber.values())) tickSession(session);
        if (ticks % 10L == 0L) updatePrompts();
    }

    private void updatePrompts() {
        if (!plugin.getConfig().getBoolean("robbery.enabled", true)) return;
        for (Player robber : Bukkit.getOnlinePlayers()) {
            if (byRobber.containsKey(robber.getUniqueId()) || plugin.isPlayerDowned(robber)
                    || robber.isDead() || robber.getGameMode() == GameMode.SPECTATOR
                    || robber.getGameMode() == GameMode.CREATIVE) continue;
            Player victim = nearestEligibleVictim(robber);
            if (victim != null && pocketCoins(victim) >= 10L) {
                robber.sendActionBar(Component.text("Hold SHIFT to steal coins", NamedTextColor.YELLOW));
            }
        }
    }

    private Player nearestEligibleVictim(Player robber) {
        double range = Math.max(0.5, plugin.getConfig().getDouble("robbery.range-blocks", 2.0));
        double nearestDistance = range * range;
        Player nearest = null;
        for (Player candidate : Bukkit.getOnlinePlayers()) {
            if (candidate.equals(robber) || candidate.isDead()
                    || candidate.getWorld() != robber.getWorld()
                    || plugin.isPlayerDowned(candidate)
                    || byVictim.containsKey(candidate.getUniqueId())) continue;
            double distance = robber.getLocation().distanceSquared(candidate.getLocation());
            if (distance <= nearestDistance && pocketCoins(candidate) >= 10L) {
                nearest = candidate;
                nearestDistance = distance;
            }
        }
        return nearest;
    }

    private void startRobbery(Player robber, Player victim) {
        int seconds = Math.max(1, plugin.getConfig().getInt("robbery.duration-seconds", 7));
        BossBar bar = Bukkit.createBossBar("Stealing coins...", BarColor.RED, BarStyle.SOLID);
        bar.setProgress(0.0);
        bar.addPlayer(robber);
        RobberySession session = new RobberySession(robber, victim, robber.getLocation().clone(),
                seconds * 20, bar);
        byRobber.put(robber.getUniqueId(), session);
        byVictim.put(victim.getUniqueId(), session);
    }

    private void tickSession(RobberySession session) {
        Player robber = Bukkit.getPlayer(session.robberId);
        Player victim = Bukkit.getPlayer(session.victimId);
        if (robber == null || victim == null || !robber.isOnline() || !victim.isOnline()
                || !robber.isSneaking() || plugin.isPlayerDowned(robber)
                || plugin.isPlayerDowned(victim) || robber.getWorld() != victim.getWorld()
                || robber.getLocation().distanceSquared(session.startLocation) > STILL_MOVEMENT_SQUARED) {
            cancel(session);
            return;
        }
        double range = Math.max(0.5, plugin.getConfig().getDouble("robbery.range-blocks", 2.0));
        if (robber.getLocation().distanceSquared(victim.getLocation()) > range * range) {
            cancel(session);
            return;
        }
        session.elapsedTicks++;
        session.bar.setProgress(Math.min(1.0, session.elapsedTicks / (double) session.durationTicks));
        if (session.elapsedTicks >= session.durationTicks) complete(session, robber, victim);
    }

    private void complete(RobberySession session, Player robber, Player victim) {
        int percent = Math.max(1, Math.min(100, plugin.getConfig().getInt("robbery.steal-percent", 10)));
        long requested = (long) Math.floor(pocketCoins(victim) * (percent / 100.0));
        long transferred = requested > 0L ? transferPocketCoins(victim, robber, requested) : 0L;
        cancel(session);
        if (transferred <= 0L) return;
        String amount = NumberFormat.getIntegerInstance(Locale.US).format(transferred);
        Bukkit.broadcastMessage("§c§lROBBERY §8» §e" + victim.getName()
                + " §7was robbed by §c" + robber.getName()
                + " §7for §6" + amount + " coins§7.");
    }

    private void cancel(RobberySession session) {
        byRobber.remove(session.robberId, session);
        byVictim.remove(session.victimId, session);
        session.bar.removeAll();
    }

    private long pocketCoins(Player player) {
        try {
            Object manager = getPocketCoinManager();
            if (manager == null) return 0L;
            Method count = manager.getClass().getMethod("count", Player.class);
            return Math.max(0L, Math.round(((Number) count.invoke(manager, player)).doubleValue()));
        } catch (ReflectiveOperationException | RuntimeException exception) {
            plugin.getLogger().warning("Robbery could not read pocket coins: " + exception.getClass().getSimpleName());
            return 0L;
        }
    }

    private long transferPocketCoins(Player victim, Player robber, long amount) {
        try {
            Object manager = getPocketCoinManager();
            if (manager == null) return 0L;
            Method transfer = manager.getClass().getMethod("transferCoins", Player.class, Player.class, long.class);
            return Math.max(0L, ((Number) transfer.invoke(manager, victim, robber, amount)).longValue());
        } catch (ReflectiveOperationException | RuntimeException exception) {
            plugin.getLogger().warning("Robbery could not transfer pocket coins: " + exception.getClass().getSimpleName());
            return 0L;
        }
    }

    private Object getPocketCoinManager() throws ReflectiveOperationException {
        Plugin economy = Bukkit.getPluginManager().getPlugin("XZHEconomySystem");
        if (economy == null || !economy.isEnabled()) return null;
        return economy.getClass().getMethod("getPocketCoinManager").invoke(economy);
    }

    private static final class RobberySession {
        private final UUID robberId;
        private final UUID victimId;
        private final Location startLocation;
        private final int durationTicks;
        private final BossBar bar;
        private int elapsedTicks;

        private RobberySession(Player robber, Player victim, Location startLocation,
                               int durationTicks, BossBar bar) {
            this.robberId = robber.getUniqueId();
            this.victimId = victim.getUniqueId();
            this.startLocation = startLocation;
            this.durationTicks = durationTicks;
            this.bar = bar;
        }
    }
}
