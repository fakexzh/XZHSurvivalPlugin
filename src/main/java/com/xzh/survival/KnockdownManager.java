package com.xzh.survival;

import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.boss.BarColor;
import org.bukkit.boss.BarStyle;
import org.bukkit.boss.BossBar;
import org.bukkit.entity.Display;
import org.bukkit.entity.Player;
import org.bukkit.entity.Pose;
import org.bukkit.util.Transformation;
import org.joml.Vector3f;
import org.joml.Quaternionf;
import org.bukkit.entity.TextDisplay;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerItemConsumeEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.event.player.PlayerToggleSneakEvent;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

public final class KnockdownManager implements Listener {
    private final JavaPlugin plugin;
    private final Map<UUID, DownedState> downed = new HashMap<>();
    private final Map<UUID, ReviveSession> revivesByReviver = new HashMap<>();
    private final Map<UUID, ReviveSession> revivesByTarget = new HashMap<>();
    private BukkitTask ticker;
    private long ticks;

    public KnockdownManager(JavaPlugin plugin) { this.plugin = plugin; }

    public void start() {
        Bukkit.getPluginManager().registerEvents(this, plugin);
        ticker = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 1L, 1L);
        plugin.getLogger().info("Knockdown and revive system enabled.");
    }

    public void shutdown() {
        if (ticker != null) ticker.cancel();
        for (DownedState state : new ArrayList<>(downed.values())) {
            Player player = Bukkit.getPlayer(state.playerId);
            if (player != null && player.isOnline()) finishAsDeath(player);
            else clearDownedState(state, true);
        }
        for (ReviveSession session : new ArrayList<>(revivesByReviver.values())) cancelRevive(session, null, false);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onLethalDamage(EntityDamageEvent event) {
        if (!(event.getEntity() instanceof Player player)) return;
        if (downed.containsKey(player.getUniqueId())) { event.setCancelled(true); return; }
        if (!plugin.getConfig().getBoolean("knockdown.enabled", true) || player.getGameMode() == GameMode.SPECTATOR) return;
        if (player.getHealth() - event.getFinalDamage() > 0.0) return;
        event.setCancelled(true);
        enterDownedState(player);
    }

    private void enterDownedState(Player player) {
        UUID id = player.getUniqueId();
        if (downed.containsKey(id)) return;
        long duration = Math.max(1, plugin.getConfig().getInt("knockdown.duration-seconds", 60)) * 20L;
        DownedState state = new DownedState(player, ticks, duration);
        downed.put(id, state);

        // Cancelling the lethal event prevents Bukkit's normal death and item-drop flow.
        player.setHealth(1.0);
        player.setInvulnerable(true);
        player.setSprinting(false);
        // GSit's API controls the crawl state and broadcasts the correct pose to observers.
        state.gsitCrawl = GsitCrawlSupport.start(player, plugin);
        player.setWalkSpeed(clampWalkSpeed(plugin.getConfig().getDouble("knockdown.crawl-speed", 0.06)));

        try {
            state.countdownIndicator = spawnIndicator(player, 1.43, "§f01:00", 0.62f, true);
            state.indicator = spawnIndicator(player, 1.15, "§c§lDOWNED", 0.82f, true);
            state.revivePrompt = spawnIndicator(player, 0.76,
                    "§eRevive: §fHold SHIFT nearby", 0.44f, false);
        } catch (RuntimeException ex) {
            plugin.getLogger().warning("Could not create downed-player indicator: " + ex.getClass().getSimpleName());
        }
        player.sendTitle("§c§lYOU ARE DOWNED", "§7Crawl to safety. Someone can revive you.", 5, 45, 10);
    }


    private void startRevive(Player reviver, Player target, DownedState state) {
        long duration = Math.max(1, plugin.getConfig().getInt("knockdown.revive.duration-seconds", 5)) * 20L;
        ReviveSession session = new ReviveSession(reviver, target, ticks, duration);
        revivesByReviver.put(reviver.getUniqueId(), session);
        revivesByTarget.put(target.getUniqueId(), session);
        state.reviverId = reviver.getUniqueId();
        if (state.indicator != null && state.indicator.isValid()) {
            state.indicator.setText("§a§lREVIVING");
            setDisplayScale(state.indicator, 0.72f);
        }
        if (state.revivePrompt != null && state.revivePrompt.isValid()) state.revivePrompt.setText("§aREVIVING...");
        session.bar = Bukkit.createBossBar("", BarColor.GREEN, BarStyle.SOLID);
        session.bar.addPlayer(reviver);
        session.bar.setVisible(true);
        reviver.sendTitle("§a§lREVIVING", "§7Stay still and keep SHIFT held", 0, 35, 5);
        target.sendTitle("§a§lHELP IS HERE", "§7Someone is trying to revive you", 0, 35, 5);
        updateReviveBar(session);
    }

    @EventHandler(ignoreCancelled = true)
    public void onReviverMove(PlayerMoveEvent event) {
        ReviveSession session = revivesByReviver.get(event.getPlayer().getUniqueId());
        if (session == null || event.getTo() == null) return;
        Location from = event.getFrom(), to = event.getTo();
        // Head movement is allowed; any positional movement interrupts revival.
        if (from.getWorld() != to.getWorld() || from.distanceSquared(to) > 0.0001)
            cancelRevive(session, "You moved.", true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onSneakChanged(PlayerToggleSneakEvent event) {
        Player player = event.getPlayer();
        if (!event.isSneaking()) {
            ReviveSession session = revivesByReviver.get(player.getUniqueId());
            if (session != null) cancelRevive(session, "You released SHIFT.", true);
            return;
        }

        if (downed.containsKey(player.getUniqueId())
                || revivesByReviver.containsKey(player.getUniqueId())) return;

        double range = Math.max(1.0, plugin.getConfig().getDouble("knockdown.revive.range-blocks", 2.0));
        double maxDistanceSquared = range * range;
        Player nearestTarget = null;
        DownedState nearestState = null;
        double nearestDistanceSquared = maxDistanceSquared + 0.0001;

        for (DownedState state : downed.values()) {
            Player target = Bukkit.getPlayer(state.playerId);
            if (target == null || !target.isOnline() || !player.getWorld().equals(target.getWorld())
                    || revivesByTarget.containsKey(target.getUniqueId())) continue;
            double distanceSquared = player.getLocation().distanceSquared(target.getLocation());
            if (distanceSquared <= maxDistanceSquared && distanceSquared < nearestDistanceSquared) {
                nearestDistanceSquared = distanceSquared;
                nearestTarget = target;
                nearestState = state;
            }
        }

        if (nearestTarget != null && nearestState != null) startRevive(player, nearestTarget, nearestState);
    }

    @EventHandler(ignoreCancelled = true)
    public void onReviverDamaged(EntityDamageEvent event) {
        if (!(event.getEntity() instanceof Player player)) return;
        ReviveSession session = revivesByReviver.get(player.getUniqueId());
        if (session != null) cancelRevive(session, "You were interrupted.", true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onAttack(EntityDamageByEntityEvent event) {
        if (!(event.getDamager() instanceof Player attacker)) return;
        if (downed.containsKey(attacker.getUniqueId())) { event.setCancelled(true); return; }
        ReviveSession session = revivesByReviver.get(attacker.getUniqueId());
        if (session != null) cancelRevive(session, "You were interrupted.", true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onDownedInteract(PlayerInteractEvent e) { if (downed.containsKey(e.getPlayer().getUniqueId())) e.setCancelled(true); }
    @EventHandler(ignoreCancelled = true)
    public void onDownedBreak(BlockBreakEvent e) { if (downed.containsKey(e.getPlayer().getUniqueId())) e.setCancelled(true); }
    @EventHandler(ignoreCancelled = true)
    public void onDownedPlace(BlockPlaceEvent e) { if (downed.containsKey(e.getPlayer().getUniqueId())) e.setCancelled(true); }
    @EventHandler(ignoreCancelled = true)
    public void onDownedDrop(PlayerDropItemEvent e) { if (downed.containsKey(e.getPlayer().getUniqueId())) e.setCancelled(true); }
    @EventHandler(ignoreCancelled = true)
    public void onDownedConsume(PlayerItemConsumeEvent e) { if (downed.containsKey(e.getPlayer().getUniqueId())) e.setCancelled(true); }
    @EventHandler(ignoreCancelled = true)
    public void onDownedSwap(PlayerSwapHandItemsEvent e) { if (downed.containsKey(e.getPlayer().getUniqueId())) e.setCancelled(true); }

    @EventHandler(ignoreCancelled = true)
    public void onDownedJump(PlayerMoveEvent event) {
        if (!downed.containsKey(event.getPlayer().getUniqueId()) || event.getTo() == null) return;
        if (event.getTo().getY() > event.getFrom().getY() + 0.2) {
            Location grounded = event.getFrom().clone();
            grounded.setYaw(event.getTo().getYaw());
            grounded.setPitch(event.getTo().getPitch());
            event.setTo(grounded);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerDeath(PlayerDeathEvent event) {
        DownedState state = downed.get(event.getEntity().getUniqueId());
        if (state != null) clearDownedState(state, true);
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        ReviveSession session = revivesByReviver.get(player.getUniqueId());
        if (session != null) cancelRevive(session, "The reviver disconnected.", true);
        if (downed.containsKey(player.getUniqueId())) finishAsDeath(player);
    }

    private void tick() {
        ticks++;
        for (DownedState state : new ArrayList<>(downed.values())) {
            Player player = Bukkit.getPlayer(state.playerId);
            if (player == null || !player.isOnline()) continue;
            long elapsed = ticks - state.startedAt;
            long remaining = state.durationTicks - elapsed;
            if (remaining <= 0) { finishAsDeath(player); continue; }
            if (ticks % 20 == 0) applyProgressiveEffects(player, elapsed / 20);
            if (state.gsitCrawl != null && ticks % 5 == 0) {
                state.gsitCrawl = GsitCrawlSupport.ensure(player, state.gsitCrawl, plugin);
            }
            if (state.indicator != null && state.indicator.isValid() && ticks % 2 == 0) {
                state.indicator.teleport(player.getLocation().add(0, 1.15, 0));
                state.indicator.setText(state.reviverId == null ? "§c§lDOWNED" : "§a§lREVIVING");
            }
            if (state.countdownIndicator != null && state.countdownIndicator.isValid() && ticks % 2 == 0) {
                long seconds = Math.max(0, (state.durationTicks - elapsed + 19) / 20);
                state.countdownIndicator.teleport(player.getLocation().add(0, 1.43, 0));
                state.countdownIndicator.setText(String.format(Locale.ROOT, "§f%02d:%02d", seconds / 60, seconds % 60));
            }
            if (state.revivePrompt != null && state.revivePrompt.isValid() && ticks % 10 == 0) {
                state.revivePrompt.teleport(player.getLocation().add(0, 0.76, 0));
                updateRevivePromptVisibility(state, player);
            }
        }
        for (ReviveSession session : new ArrayList<>(revivesByReviver.values())) {
            Player reviver = Bukkit.getPlayer(session.reviverId), target = Bukkit.getPlayer(session.targetId);
            if (reviver == null || target == null || !reviver.isOnline() || !target.isOnline()) {
                cancelRevive(session, null, false); continue;
            }
            if (!reviver.isSneaking()) { cancelRevive(session, "You released SHIFT.", true); continue; }
            double range = Math.max(1.0, plugin.getConfig().getDouble("knockdown.revive.range-blocks", 2.0));
            if (!reviver.getWorld().equals(target.getWorld())
                    || reviver.getLocation().distanceSquared(target.getLocation()) > range * range) {
                cancelRevive(session, "You moved too far away.", true); continue;
            }
            long elapsed = ticks - session.startedAt;
            if (elapsed >= session.durationTicks) finishRevive(session, reviver, target);
            else if (ticks % 2 == 0) updateReviveBar(session);
        }
    }

    private void applyProgressiveEffects(Player player, long elapsedSeconds) {
        int nauseaStart = Math.max(0, plugin.getConfig().getInt("knockdown.effects.nausea-start-seconds", 8));
        int darknessStart = Math.max(0, plugin.getConfig().getInt("knockdown.effects.darkness-start-seconds", 20));
        if (elapsedSeconds >= nauseaStart) {
            int amplifier = elapsedSeconds >= 40 ? 1 : 0;
            player.addPotionEffect(new PotionEffect(PotionEffectType.NAUSEA, 40, amplifier, true, false, false));
        }
        if (elapsedSeconds >= darknessStart)
            player.addPotionEffect(new PotionEffect(PotionEffectType.DARKNESS, 45, 0, true, false, false));
    }

    private void updateReviveBar(ReviveSession session) {
        if (session.bar == null) return;
        long remaining = Math.max(0, session.durationTicks - (ticks - session.startedAt));
        session.bar.setTitle("§aREVIVING §8• §f" + String.format(Locale.ROOT, "%.1fs", remaining / 20.0));
        session.bar.setProgress(Math.max(0, Math.min(1, 1.0 - (double) remaining / session.durationTicks)));
    }

    private void finishRevive(ReviveSession session, Player reviver, Player target) {
        DownedState state = downed.get(session.targetId);
        removeReviveSession(session);
        if (state == null) return;
        clearDownedState(state, false);
        double max = target.getAttribute(org.bukkit.attribute.Attribute.MAX_HEALTH).getValue();
        target.setHealth(Math.min(max, Math.max(0.5, plugin.getConfig().getDouble("knockdown.revive.health", 2.0))));
        target.sendTitle("§a§lREVIVED!", "§7You have been brought back with 1 heart.", 5, 40, 10);
        reviver.sendTitle("§a§lREVIVAL COMPLETE", "§7Your teammate is back.", 5, 35, 10);
    }

    private void cancelRevive(ReviveSession session, String reason, boolean showTitles) {
        if (session == null) return;
        removeReviveSession(session);
        DownedState state = downed.get(session.targetId);
        if (state != null) {
            state.reviverId = null;
            if (state.indicator != null && state.indicator.isValid()) {
                state.indicator.setText("§c§lDOWNED");
                setDisplayScale(state.indicator, 0.82f);
            }
            if (state.revivePrompt != null && state.revivePrompt.isValid()) state.revivePrompt.setText("§eRevive: §fHold SHIFT nearby");
        }
        if (!showTitles) return;
        Player reviver = Bukkit.getPlayer(session.reviverId);
        if (reviver != null && reviver.isOnline())
            reviver.sendTitle("§c§lREVIVAL CANCELLED", "§7" + (reason == null ? "Interrupted." : reason), 0, 35, 10);
        Player target = Bukkit.getPlayer(session.targetId);
        if (target != null && target.isOnline())
            target.sendTitle("§c§lREVIVAL INTERRUPTED", "§7The revival attempt was cancelled.", 0, 30, 10);
    }

    private void removeReviveSession(ReviveSession session) {
        revivesByReviver.remove(session.reviverId);
        revivesByTarget.remove(session.targetId);
        if (session.bar != null) { session.bar.removeAll(); session.bar.setVisible(false); }
    }

    private void finishAsDeath(Player player) {
        DownedState state = downed.get(player.getUniqueId());
        if (state == null) return;
        clearDownedState(state, true);
        // Existing death listener handles dimension-specific drops, XP, penalties and announcements.
        player.setHealth(0.0);
    }

    private void clearDownedState(DownedState state, boolean cancelActiveRevive) {
        if (cancelActiveRevive) {
            ReviveSession session = revivesByTarget.get(state.playerId);
            if (session != null) cancelRevive(session, null, false);
        }
        downed.remove(state.playerId);
        Player player = Bukkit.getPlayer(state.playerId);
        if (state.indicator != null && state.indicator.isValid()) state.indicator.remove();
        if (state.countdownIndicator != null && state.countdownIndicator.isValid()) state.countdownIndicator.remove();
        if (state.revivePrompt != null && state.revivePrompt.isValid()) {
            for (Player viewer : Bukkit.getOnlinePlayers()) viewer.hideEntity(plugin, state.revivePrompt);
            state.revivePrompt.remove();
        }
        GsitCrawlSupport.stop(player, state.gsitCrawl, plugin);
        if (player != null) {
            player.setInvulnerable(state.previousInvulnerable);
            player.setWalkSpeed(state.previousWalkSpeed);
            if (state.previousFixedPose) player.setPose(state.previousPose, true);
            else player.unsetFixedPose();
            player.setSwimming(state.previousSwimming);
            player.removePotionEffect(PotionEffectType.NAUSEA);
            player.removePotionEffect(PotionEffectType.DARKNESS);
            if (state.previousNausea != null) player.addPotionEffect(state.previousNausea);
            if (state.previousDarkness != null) player.addPotionEffect(state.previousDarkness);
        }
    }

    private TextDisplay spawnIndicator(Player player, double height, String text, float scale, boolean visibleByDefault) {
        return player.getWorld().spawn(player.getLocation().add(0, height, 0), TextDisplay.class, display -> {
            display.setText(text);
            display.setBillboard(Display.Billboard.CENTER);
            display.setSeeThrough(true);
            display.setShadowed(true);
            display.setDefaultBackground(false);
            display.setPersistent(false);
            display.setInvulnerable(true);
            display.setGravity(false);
            display.setVisibleByDefault(visibleByDefault);
            display.setTransformation(new Transformation(
                    new Vector3f(0, 0, 0), new Quaternionf(),
                    new Vector3f(scale, scale, scale), new Quaternionf()));
        });
    }

    private void updateRevivePromptVisibility(DownedState state, Player target) {
        if (state.revivePrompt == null || !state.revivePrompt.isValid()) return;
        for (Player viewer : Bukkit.getOnlinePlayers()) {
            boolean nearby = viewer != target && !downed.containsKey(viewer.getUniqueId())
                    && viewer.getWorld().equals(target.getWorld())
                    && viewer.getLocation().distanceSquared(target.getLocation()) <= 4.0;
            if (nearby) viewer.showEntity(plugin, state.revivePrompt);
            else viewer.hideEntity(plugin, state.revivePrompt);
        }
    }

    private void setDisplayScale(TextDisplay display, float scale) {
        display.setTransformation(new Transformation(
                new Vector3f(0, 0, 0), new Quaternionf(),
                new Vector3f(scale, scale, scale), new Quaternionf()));
    }

    private float clampWalkSpeed(double speed) { return (float) Math.max(-1.0, Math.min(1.0, speed)); }

    private static final class DownedState {
        private final UUID playerId;
        private final long startedAt, durationTicks;
        private final float previousWalkSpeed;
        private final boolean previousInvulnerable, previousSwimming, previousFixedPose;
        private final Pose previousPose;
        private final PotionEffect previousNausea, previousDarkness;
        private TextDisplay indicator, countdownIndicator, revivePrompt;
        private Object gsitCrawl;
        private UUID reviverId;
        private DownedState(Player player, long startedAt, long durationTicks) {
            this.playerId = player.getUniqueId();
            this.startedAt = startedAt;
            this.durationTicks = durationTicks;
            this.previousWalkSpeed = player.getWalkSpeed();
            this.previousInvulnerable = player.isInvulnerable();
            this.previousSwimming = player.isSwimming();
            this.previousPose = player.getPose();
            this.previousFixedPose = player.hasFixedPose();
            this.previousNausea = player.getPotionEffect(PotionEffectType.NAUSEA);
            this.previousDarkness = player.getPotionEffect(PotionEffectType.DARKNESS);
        }
    }

    private static final class ReviveSession {
        private final UUID reviverId, targetId;
        private final long startedAt, durationTicks;
        private final Location startLocation;
        private BossBar bar;
        private ReviveSession(Player reviver, Player target, long startedAt, long durationTicks) {
            this.reviverId = reviver.getUniqueId();
            this.targetId = target.getUniqueId();
            this.startedAt = startedAt;
            this.durationTicks = durationTicks;
            this.startLocation = reviver.getLocation().clone();
        }
    }
}