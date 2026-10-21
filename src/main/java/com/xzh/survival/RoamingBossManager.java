package com.xzh.survival;

import org.bukkit.Bukkit;
import org.bukkit.GameRule;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.boss.BarColor;
import org.bukkit.boss.BarStyle;
import org.bukkit.boss.BossBar;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Villager;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.world.WorldLoadEvent;
import org.bukkit.event.weather.WeatherChangeEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;

public final class RoamingBossManager implements Listener {
    private static final double BOSSBAR_RANGE = 32.0;
    private final JavaPlugin plugin;
    private final NamespacedKey bossKey;
    private final NamespacedKey sealKey;
    private final NamespacedKey bossTierKey;
    private final NamespacedKey sealTierKey;
    private final Random random = new Random();
    private final Map<UUID, BossState> bosses = new HashMap<>();
    private final Map<UUID, Location> lastSpawnCheckLocation = new HashMap<>();
    private long ticks;

    private static final class BossState {
        private final UUID id;
        private final BossBar bar;
        private BossState(UUID id, BossBar bar) { this.id = id; this.bar = bar; }
    }

    public RoamingBossManager(JavaPlugin plugin) {
        this.plugin = plugin;
        this.bossKey = new NamespacedKey(plugin, "roaming_boss");
        this.sealKey = new NamespacedKey(plugin, "wanderers_seal");
        this.bossTierKey = new NamespacedKey(plugin, "roaming_boss_tier");
        this.sealTierKey = new NamespacedKey(plugin, "wanderers_seal_tier");
        Bukkit.getPluginManager().registerEvents(this, plugin);
    }

    public void start() {
        disableRainInAllWorlds();
        for (World world : Bukkit.getWorlds()) {
            for (Entity entity : world.getEntities()) {
                if (entity instanceof LivingEntity living
                        && living.getPersistentDataContainer().has(bossKey, PersistentDataType.BYTE)) {
                    trackBoss(living);
                }
            }
        }
        Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 20L, 20L);
    }

    public void shutdown() {
        for (BossState state : bosses.values()) state.bar.removeAll();
        bosses.clear();
    }

    private void tick() {
        ticks++;
        disableRainInAllWorlds();
        for (BossState state : new ArrayList<>(bosses.values())) {
            Entity entity = Bukkit.getEntity(state.id);
            if (!(entity instanceof LivingEntity living) || living.isDead()) {
                state.bar.removeAll();
                bosses.remove(state.id);
                continue;
            }
            double max = living.getAttribute(Attribute.MAX_HEALTH) == null
                    ? 40.0 : living.getAttribute(Attribute.MAX_HEALTH).getValue();
            state.bar.setProgress(Math.max(0.0, Math.min(1.0, living.getHealth() / Math.max(1.0, max))));
            String tier = living.getPersistentDataContainer().getOrDefault(bossTierKey, PersistentDataType.STRING, "rare");
            state.bar.setTitle(bossTitle(tier) + " §8• §c" + (int)Math.ceil(living.getHealth()) + "§7/§c" + (int)max + " HP");
            for (Player player : Bukkit.getOnlinePlayers()) {
                if (player.getWorld().equals(living.getWorld())
                        && player.getLocation().distanceSquared(living.getLocation()) <= BOSSBAR_RANGE * BOSSBAR_RANGE) {
                    if (!state.bar.getPlayers().contains(player)) state.bar.addPlayer(player);
                } else state.bar.removePlayer(player);
            }
            if (ticks % attackInterval(tier) == 0) launchSpecialAttack(living, tier);
        }
        if (!plugin.getConfig().getBoolean("roaming-bosses.enabled", true)) return;
        if (ticks % 30 != 0 || bosses.size() >= 3) return;
        for (Player player : Bukkit.getOnlinePlayers()) {
            Location current = player.getLocation();
            Location previous = lastSpawnCheckLocation.put(player.getUniqueId(), current.clone());
            if (previous == null || previous.getWorld() != current.getWorld()
                    || previous.distanceSquared(current) < 100.0) continue; // Must have explored at least 10 blocks since the previous check.
            if (player.getWorld().getEnvironment() != World.Environment.NORMAL) continue;
            if (hasBossInWorld(player.getWorld())) continue;
            if (random.nextDouble() > plugin.getConfig().getDouble("roaming-bosses.spawn-chance-per-check", 0.035)) continue;
            LivingEntity boss = spawnNear(player);
            if (boss != null) {
                trackBoss(boss);
                player.sendMessage("§d§lXZH EXPLORATION §8» §fA roaming boss has appeared somewhere nearby. Watch for its boss bar!");
                break;
            }
        }
    }

    private boolean hasBossInWorld(World world) {
        for (BossState state : bosses.values()) {
            Entity entity = Bukkit.getEntity(state.id);
            if (entity != null && entity.getWorld().equals(world) && !entity.isDead()) return true;
        }
        return false;
    }

    private LivingEntity spawnNear(Player player) {
        World world = player.getWorld();
        Location base = player.getLocation();
        for (int attempt = 0; attempt < 20; attempt++) {
            double angle = random.nextDouble() * Math.PI * 2.0;
            int distance = 20 + random.nextInt(16);
            int x = base.getBlockX() + (int) Math.round(Math.cos(angle) * distance);
            int z = base.getBlockZ() + (int) Math.round(Math.sin(angle) * distance);
            int y = world.getHighestBlockYAt(x, z) + 1;
            if (y < world.getMinHeight() + 2 || y >= world.getMaxHeight() - 2) continue;
            Location spawn = new Location(world, x + 0.5, y, z + 0.5);
            Material ground = world.getBlockAt(x, y - 1, z).getType();
            if (!ground.isSolid() || !spawn.getBlock().isPassable()
                    || !spawn.clone().add(0, 1, 0).getBlock().isPassable()) continue;
            EntityType type = switch (random.nextInt(4)) {
                case 0 -> EntityType.ZOMBIE;
                case 1 -> EntityType.SKELETON;
                case 2 -> EntityType.HUSK;
                default -> EntityType.STRAY;
            };
            Entity entity = world.spawnEntity(spawn, type);
            if (!(entity instanceof LivingEntity living)) { entity.remove(); continue; }
            String tier = rollTier();
            double hp = tier.equals("legendary") ? 180.0 : tier.equals("rare") ? 120.0 : 80.0;
            if (living.getAttribute(Attribute.MAX_HEALTH) != null) {
                living.getAttribute(Attribute.MAX_HEALTH).setBaseValue(hp);
                living.setHealth(hp);
            }
            if (living.getAttribute(Attribute.ATTACK_DAMAGE) != null) living.getAttribute(Attribute.ATTACK_DAMAGE).setBaseValue(tier.equals("legendary") ? 12.0 : tier.equals("rare") ? 9.0 : 6.0);
            if (living.getAttribute(Attribute.MOVEMENT_SPEED) != null) living.getAttribute(Attribute.MOVEMENT_SPEED).setBaseValue(tier.equals("legendary") ? 0.34 : tier.equals("rare") ? 0.31 : 0.28);
            living.setCustomName(bossTitle(tier));
            living.setCustomNameVisible(true);
            living.setGlowing(true);
            living.setRemoveWhenFarAway(false);
            living.getPersistentDataContainer().set(bossKey, PersistentDataType.BYTE, (byte) 1);
            living.getPersistentDataContainer().set(bossTierKey, PersistentDataType.STRING, tier);
            return living;
        }
        return null;
    }

    private void trackBoss(LivingEntity boss) {
        if (bosses.containsKey(boss.getUniqueId())) return;
        String tier = boss.getPersistentDataContainer().getOrDefault(bossTierKey, PersistentDataType.STRING, "rare");
        BossBar bar = Bukkit.createBossBar(bossTitle(tier), tier.equals("legendary") ? BarColor.RED : tier.equals("rare") ? BarColor.PURPLE : BarColor.BLUE, BarStyle.SOLID);
        bar.setVisible(true);
        bosses.put(boss.getUniqueId(), new BossState(boss.getUniqueId(), bar));
    }

    @EventHandler
    public void onBossDeath(EntityDeathEvent event) {
        LivingEntity entity = event.getEntity();
        if (!entity.getPersistentDataContainer().has(bossKey, PersistentDataType.BYTE)) return;
        BossState state = bosses.remove(entity.getUniqueId());
        if (state != null) state.bar.removeAll();
        String tier = entity.getPersistentDataContainer().getOrDefault(bossTierKey, PersistentDataType.STRING, "rare");
        int reward = rewardForTier(tier);
        ItemStack seal = new ItemStack(Material.TRIAL_KEY);
        ItemMeta meta = seal.getItemMeta();
        meta.setDisplayName("§d§l" + tierName(tier) + " Boss Reward Key");
        meta.setLore(List.of("§7Earned by defeating a " + tierName(tier) + " roaming boss.", "§7Reward: §e" + reward + " bank coins", "§eBring this key to a Smith Villager", "§eand right-click to claim your reward."));
        meta.getPersistentDataContainer().set(sealKey, PersistentDataType.BYTE, (byte) 1);
        meta.getPersistentDataContainer().set(sealTierKey, PersistentDataType.STRING, tier);
        seal.setItemMeta(meta);
        event.getDrops().add(seal);
        Player killer = entity.getKiller();
        if (killer != null) killer.sendMessage("§d§lBOSS DEFEATED §8» §fYou received a §d" + tierName(tier) + " Boss Reward Key§f worth §e" + reward + " bank coins§f. Bring it to an Armorer, Weaponsmith, or Toolsmith and right-click to redeem.");
    }

    @EventHandler(ignoreCancelled = true)
    public void onRedeemSeal(PlayerInteractEntityEvent event) {
        if (event.getHand() != EquipmentSlot.HAND || !(event.getRightClicked() instanceof Villager villager)) return;
        Villager.Profession profession = villager.getProfession();
        if (profession != Villager.Profession.ARMORER && profession != Villager.Profession.WEAPONSMITH
                && profession != Villager.Profession.TOOLSMITH) return;
        Player player = event.getPlayer();
        ItemStack held = player.getInventory().getItemInMainHand();
        if (held.getType().isAir() || !held.hasItemMeta()
                || !held.getItemMeta().getPersistentDataContainer().has(sealKey, PersistentDataType.BYTE)) return;
        event.setCancelled(true);
        String tier = held.getItemMeta().getPersistentDataContainer().getOrDefault(sealTierKey, PersistentDataType.STRING, "common");
        int reward = rewardForTier(tier);
        if (!depositToBank(player.getUniqueId(), reward)) {
            player.sendMessage("§cYour key was not consumed because the XZH Bank could not be reached.");
            return;
        }
        if (held.getAmount() <= 1) player.getInventory().setItemInMainHand(null);
        else held.setAmount(held.getAmount() - 1);
        player.sendMessage("§a" + tierName(tier) + " Boss Reward Key redeemed! §e" + reward + " coins §awere deposited directly into your bank.");
        Bukkit.broadcastMessage("§d[XZH Exploration] §f" + player.getName() + " redeemed a " + tierName(tier) + " Boss Reward Key for " + reward + " bank coins.");
    }

    private String rollTier() { int roll = random.nextInt(100); return roll < 55 ? "common" : roll < 88 ? "rare" : "legendary"; }
    private String tierName(String tier) { return switch (tier.toLowerCase(java.util.Locale.ROOT)) { case "legendary" -> "Legendary"; case "rare" -> "Rare"; default -> "Common"; }; }
    private String bossTitle(String tier) { return switch (tier.toLowerCase(java.util.Locale.ROOT)) { case "legendary" -> "§4§lLEGENDARY ROAMING BOSS"; case "rare" -> "§5§lRARE ROAMING BOSS"; default -> "§9§lCOMMON ROAMING BOSS"; }; }
    private int rewardForTier(String tier) { String key = tier.toLowerCase(java.util.Locale.ROOT); int fallback = key.equals("legendary") ? 2500 : key.equals("rare") ? 1200 : 500; return Math.max(1, plugin.getConfig().getInt("roaming-bosses.rewards." + key, plugin.getConfig().getInt("roaming-bosses.bank-reward", fallback))); }
    private long attackInterval(String tier) { return tier.equals("legendary") ? 3L : tier.equals("rare") ? 4L : 5L; }
    private void launchSpecialAttack(LivingEntity boss, String tier) {
        Player target = null; double best = 24.0 * 24.0;
        for (Player player : boss.getWorld().getPlayers()) { double d = player.getLocation().distanceSquared(boss.getLocation()); if (d < best && player.getGameMode() != org.bukkit.GameMode.SPECTATOR) { best = d; target = player; } }
        if (target == null) return;
        Location origin = boss.getEyeLocation(); Vector direction = target.getEyeLocation().toVector().subtract(origin.toVector()).normalize();
        int attack = random.nextInt(tier.equals("legendary") ? 4 : tier.equals("rare") ? 3 : 2);
        if (attack == 0) { org.bukkit.entity.SmallFireball fire = boss.getWorld().spawn(origin.add(direction.clone().multiply(1.2)), org.bukkit.entity.SmallFireball.class); fire.setDirection(direction); fire.setShooter(boss); }
        else if (attack == 1) { org.bukkit.entity.Arrow arrow = boss.getWorld().spawnArrow(origin.add(direction.clone().multiply(1.2)), direction, tier.equals("legendary") ? 2.8f : 2.2f, 3.0f); arrow.setShooter(boss); arrow.setFireTicks(80); arrow.setDamage(tier.equals("legendary") ? 6.0 : 3.5); }
        else if (attack == 2) { Location blast = target.getLocation().clone(); boss.getWorld().spawnParticle(org.bukkit.Particle.FLAME, blast.clone().add(0, 0.8, 0), 18, 0.45, 0.5, 0.45, 0.02); boss.getWorld().playSound(blast, org.bukkit.Sound.ENTITY_CREEPER_PRIMED, 1.0f, 0.8f); org.bukkit.entity.TNTPrimed tnt = boss.getWorld().spawn(blast, org.bukkit.entity.TNTPrimed.class); tnt.setFuseTicks(50); tnt.setYield(tier.equals("legendary") ? 3.0f : 2.0f); tnt.setSource(boss); }
        else { org.bukkit.entity.Fireball fire = boss.getWorld().spawn(origin.add(direction.clone().multiply(1.2)), org.bukkit.entity.Fireball.class); fire.setDirection(direction); fire.setYield(1.5f); fire.setIsIncendiary(true); fire.setShooter(boss); }
    }

    private boolean depositToBank(UUID playerId, int amount) {
        Plugin economy = Bukkit.getPluginManager().getPlugin("XZHEconomySystem");
        if (economy == null || !economy.isEnabled()) {
            plugin.getLogger().warning("XZHEconomySystem unavailable; roaming boss reward could not be deposited.");
            return false;
        }
        try {
            Object manager = economy.getClass().getMethod("getBankManager").invoke(economy);
            Method getBalance = manager.getClass().getMethod("getBalance", UUID.class);
            Method setBalance = manager.getClass().getMethod("setBalance", UUID.class, double.class);
            double balance = ((Number) getBalance.invoke(manager, playerId)).doubleValue();
            setBalance.invoke(manager, playerId, balance + amount);
            return true;
        } catch (ReflectiveOperationException | RuntimeException ex) {
            plugin.getLogger().warning("Could not deposit roaming boss reward: " + ex.getClass().getSimpleName());
            return false;
        }
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        lastSpawnCheckLocation.remove(event.getPlayer().getUniqueId());
    }

    @EventHandler
    public void onWorldLoad(WorldLoadEvent event) {
        clearRain(event.getWorld());
        for (Entity entity : event.getWorld().getEntities()) {
            if (entity instanceof LivingEntity living
                    && living.getPersistentDataContainer().has(bossKey, PersistentDataType.BYTE)) trackBoss(living);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onWeatherChange(WeatherChangeEvent event) {
        if (event.toWeatherState()) event.setCancelled(true);
    }

    private void disableRainInAllWorlds() {
        for (World world : Bukkit.getWorlds()) clearRain(world);
    }

    private void clearRain(World world) {
        world.setStorm(false);
        world.setThundering(false);
        world.setWeatherDuration(Integer.MAX_VALUE);
        world.setThunderDuration(Integer.MAX_VALUE);
        world.setGameRule(GameRule.DO_WEATHER_CYCLE, false);
    }
}
