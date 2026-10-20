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
    private static final int BANK_REWARD = 750;
    private final JavaPlugin plugin;
    private final NamespacedKey bossKey;
    private final NamespacedKey sealKey;
    private final Random random = new Random();
    private final Map<UUID, BossState> bosses = new HashMap<>();
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
            for (Player player : Bukkit.getOnlinePlayers()) {
                if (player.getWorld().equals(living.getWorld())
                        && player.getLocation().distanceSquared(living.getLocation()) <= BOSSBAR_RANGE * BOSSBAR_RANGE) {
                    if (!state.bar.getPlayers().contains(player)) state.bar.addPlayer(player);
                } else {
                    state.bar.removePlayer(player);
                }
            }
        }
        if (ticks % 30 != 0 || bosses.size() >= 3) return;
        for (Player player : Bukkit.getOnlinePlayers()) {
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
            if (!ground.isSolid() || ground.isLiquid() || !spawn.getBlock().isPassable()
                    || !spawn.clone().add(0, 1, 0).getBlock().isPassable()) continue;
            EntityType type = switch (random.nextInt(4)) {
                case 0 -> EntityType.ZOMBIE;
                case 1 -> EntityType.SKELETON;
                case 2 -> EntityType.HUSK;
                default -> EntityType.STRAY;
            };
            Entity entity = world.spawnEntity(spawn, type);
            if (!(entity instanceof LivingEntity living)) { entity.remove(); continue; }
            if (living.getAttribute(Attribute.MAX_HEALTH) != null) {
                living.getAttribute(Attribute.MAX_HEALTH).setBaseValue(40.0);
                living.setHealth(40.0);
            }
            living.setCustomName("§d§lWandering Boss");
            living.setCustomNameVisible(true);
            living.setGlowing(true);
            living.setRemoveWhenFarAway(false);
            living.getPersistentDataContainer().set(bossKey, PersistentDataType.BYTE, (byte) 1);
            return living;
        }
        return null;
    }

    private void trackBoss(LivingEntity boss) {
        if (bosses.containsKey(boss.getUniqueId())) return;
        BossBar bar = Bukkit.createBossBar("§dKill the boss and collect the key.", BarColor.PURPLE, BarStyle.SOLID);
        bar.setVisible(true);
        bosses.put(boss.getUniqueId(), new BossState(boss.getUniqueId(), bar));
    }

    @EventHandler
    public void onBossDeath(EntityDeathEvent event) {
        LivingEntity entity = event.getEntity();
        if (!entity.getPersistentDataContainer().has(bossKey, PersistentDataType.BYTE)) return;
        BossState state = bosses.remove(entity.getUniqueId());
        if (state != null) state.bar.removeAll();
        ItemStack seal = new ItemStack(Material.TRIPWIRE_HOOK);
        ItemMeta meta = seal.getItemMeta();
        meta.setDisplayName("§d§lWanderer's Seal");
        meta.setLore(List.of("§7A seal dropped by a roaming boss.", "§eBring it to a smith villager and right-click to redeem."));
        meta.getPersistentDataContainer().set(sealKey, PersistentDataType.BYTE, (byte) 1);
        seal.setItemMeta(meta);
        event.getDrops().add(seal);
        Player killer = entity.getKiller();
        if (killer != null) {
            killer.sendMessage("§d§lWANDERER'S SEAL §8» §fBring the key to an armorer, weaponsmith, or toolsmith villager and right-click while holding it to claim your bank reward.");
        }
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
        if (!depositToBank(player.getUniqueId(), BANK_REWARD)) {
            player.sendMessage("§cYour seal was not consumed because the XZH Bank could not be reached.");
            return;
        }
        if (held.getAmount() <= 1) player.getInventory().setItemInMainHand(null);
        else held.setAmount(held.getAmount() - 1);
        player.sendMessage("§aWanderer's Seal redeemed! §e" + BANK_REWARD + " coins §awere deposited directly into your bank.");
        Bukkit.broadcastMessage("§d[XZH Exploration] §f" + player.getName() + " redeemed a Wanderer's Seal for " + BANK_REWARD + " bank coins.");
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
