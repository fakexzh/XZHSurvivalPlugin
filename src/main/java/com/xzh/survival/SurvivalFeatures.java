package com.xzh.survival;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Monster;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.entity.TextDisplay;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EntityEquipment;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.NamespacedKey;
import org.bukkit.projectiles.ProjectileSource;

import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;

public final class SurvivalFeatures implements Listener {
    private static final long HOUR_MILLIS = 60L * 60L * 1000L;

    private final XZHSurvivalPlugin plugin;
    private final Random random = new Random();
    private final File playtimeFile;
    private final org.bukkit.configuration.file.YamlConfiguration playtimeData;
    private final Map<UUID, Long> accumulatedPlaytime = new HashMap<>();
    private final Map<UUID, Long> sessionStarted = new HashMap<>();
    private final Map<UUID, TextDisplay> healthDisplays = new HashMap<>();
    private final Set<UUID> visibleHealthDisplays = new HashSet<>();
    private final Set<TextDisplay> activeDamageDisplays = new HashSet<>();
    private final NamespacedKey monsterLevelKey;
    private BukkitTask ticker;
    private int ticksSinceSave;

    public SurvivalFeatures(XZHSurvivalPlugin plugin) {
        this.plugin = plugin;
        this.playtimeFile = new File(plugin.getDataFolder(), "playtime.yml");
        this.playtimeData = org.bukkit.configuration.file.YamlConfiguration.loadConfiguration(playtimeFile);
        this.monsterLevelKey = new NamespacedKey(plugin, "monster_level");
        for (String key : playtimeData.getKeys(false)) {
            try {
                accumulatedPlaytime.put(UUID.fromString(key), Math.max(0L, playtimeData.getLong(key, 0L)));
            } catch (IllegalArgumentException ignored) {
                plugin.getLogger().warning("Ignoring invalid player UUID in playtime.yml: " + key);
            }
        }
    }

    public void start() {
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
        long now = System.currentTimeMillis();
        for (Player player : Bukkit.getOnlinePlayers()) {
            sessionStarted.put(player.getUniqueId(), now);
            accumulatedPlaytime.putIfAbsent(player.getUniqueId(), 0L);
            updateTab(player);
        }
        ticker = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 1L, 20L);
    }

    public void shutdown() {
        if (ticker != null) {
            ticker.cancel();
            ticker = null;
        }
        long now = System.currentTimeMillis();
        for (UUID id : new HashSet<>(sessionStarted.keySet())) finishSession(id, now);
        savePlaytime();
        for (TextDisplay display : healthDisplays.values()) {
            if (display != null && display.isValid()) display.remove();
        }
        healthDisplays.clear();
        visibleHealthDisplays.clear();
        for (TextDisplay display : new HashSet<>(activeDamageDisplays)) {
            if (display != null && display.isValid()) display.remove();
        }
        activeDamageDisplays.clear();
        for (Player player : Bukkit.getOnlinePlayers()) {
            player.setPlayerListName(player.getName());
            player.setPlayerListHeaderFooter("", "");
        }
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        accumulatedPlaytime.putIfAbsent(player.getUniqueId(), 0L);
        sessionStarted.put(player.getUniqueId(), System.currentTimeMillis());
        updateTab(player);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        UUID id = event.getPlayer().getUniqueId();
        finishSession(id, System.currentTimeMillis());
        removeHealthDisplay(id);
        for (TextDisplay display : new HashSet<>(activeDamageDisplays)) {
            // Damage displays are private to their attacker and are cleaned by the animation too.
            if (display != null && display.isValid()) {
                // Do not remove displays belonging to other online attackers.
            }
        }
        savePlaytime();
    }

    @EventHandler
    public void onCreatureSpawn(CreatureSpawnEvent event) {
        if (!plugin.getConfig().getBoolean("progression.enabled", true)) return;
        if (!(event.getEntity() instanceof Monster monster)) return;
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (!monster.isValid() || monster.isDead()) return;
            Player nearest = findNearestPlayer(monster.getLocation(),
                    plugin.getConfig().getDouble("progression.player-detection-radius", 96.0));
            if (nearest != null) applyProgression(monster, nearest);
        });
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEntityDamage(EntityDamageByEntityEvent event) {
        if (event.getEntity() instanceof Player targetPlayer && event.getDamager() instanceof Monster monster
                && plugin.getConfig().getBoolean("progression.enabled", true)) {
            applyProgression(monster, targetPlayer);
        }

        if (!plugin.getConfig().getBoolean("combat.damage-indicators.enabled", true)) return;
        if (!(event.getEntity() instanceof Mob victim)) return;
        Player attacker = getPlayerAttacker(event.getDamager());
        if (attacker == null || event.getFinalDamage() <= 0.0) return;

        Location hitLocation = victim.getLocation().clone().add(0, Math.max(0.5, victim.getHeight() * 0.65), 0);
        double healthBefore = victim.getHealth();
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (!attacker.isOnline()) return;
            double healthAfter = victim.isDead() ? 0.0 : victim.getHealth();
            double dealt = Math.max(0.0, healthBefore - healthAfter);
            if (dealt <= 0.0) return;
            Location displayLocation = victim.isValid() ? victim.getLocation().clone()
                    .add(0, Math.max(0.5, victim.getHeight() * 0.65), 0) : hitLocation;
            showDamageNumber(attacker, displayLocation, dealt);
            displayLocation.getWorld().spawnParticle(Particle.CRIT, displayLocation, 3, 0.12, 0.12, 0.12, 0.02);
        });
    }

    private Player getPlayerAttacker(Entity damager) {
        if (damager instanceof Player player) return player;
        if (damager instanceof Projectile projectile) {
            ProjectileSource shooter = projectile.getShooter();
            if (shooter instanceof Player player) return player;
        }
        return null;
    }

    private void tick() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            updateTab(player);
            updateTargetHealth(player);
        }
        if (++ticksSinceSave >= 60) {
            ticksSinceSave = 0;
            savePlaytime();
        }
    }

    private void updateTab(Player player) {
        if (!plugin.getConfig().getBoolean("tab.enabled", true)) return;
        int ping = Math.max(0, player.getPing());
        player.setPlayerListName(ChatColor.WHITE + player.getName() + " " + ChatColor.YELLOW + ping + " ms");
        String header = ChatColor.LIGHT_PURPLE + "NETWORK " + ChatColor.WHITE
                + Bukkit.getOnlinePlayers().size() + " ONLINE";
        String footer = ChatColor.LIGHT_PURPLE + "MONSTER LEVEL " + ChatColor.WHITE + getPlayerLevel(player);
        player.setPlayerListHeaderFooter(header, footer);
    }

    private void updateTargetHealth(Player player) {
        if (!plugin.getConfig().getBoolean("combat.target-health.enabled", true)) {
            hideHealthDisplay(player);
            return;
        }
        Entity targeted = player.getTargetEntity(plugin.getConfig().getInt("combat.target-range", 6));
        if (!(targeted instanceof Mob target) || !target.isValid() || target.isDead()) {
            hideHealthDisplay(player);
            return;
        }

        TextDisplay display = healthDisplays.get(player.getUniqueId());
        if (display != null && (!display.isValid() || display.getWorld() != target.getWorld())) {
            display.remove();
            healthDisplays.remove(player.getUniqueId());
            visibleHealthDisplays.remove(player.getUniqueId());
            display = null;
        }
        if (display == null) {
            display = createTextDisplay(target.getLocation());
            healthDisplays.put(player.getUniqueId(), display);
        }
        Location above = target.getLocation().clone().add(0, target.getHeight() + 0.35, 0);
        display.teleport(above);
        double maxHealth = target.getAttribute(Attribute.MAX_HEALTH) == null
                ? 20.0 : target.getAttribute(Attribute.MAX_HEALTH).getValue();
        display.setText(ChatColor.WHITE + formatHealth(target.getHealth()) + "/"
                + formatHealth(maxHealth));
        if (visibleHealthDisplays.add(player.getUniqueId())) {
            player.showEntity(plugin, display);
        }
    }

    private void hideHealthDisplay(Player player) {
        UUID id = player.getUniqueId();
        TextDisplay display = healthDisplays.get(id);
        if (display != null && visibleHealthDisplays.remove(id)) {
            player.hideEntity(plugin, display);
        }
    }

    private void removeHealthDisplay(UUID id) {
        TextDisplay display = healthDisplays.remove(id);
        visibleHealthDisplays.remove(id);
        if (display != null && display.isValid()) display.remove();
    }

    private TextDisplay createTextDisplay(Location location) {
        TextDisplay display = location.getWorld().spawn(location, TextDisplay.class);
        display.setVisibleByDefault(false);
        display.setPersistent(false);
        display.setBillboard(Display.Billboard.CENTER);
        display.setShadowed(false);
        display.setSeeThrough(true);
        display.setDefaultBackground(false);
        display.setBackgroundColor(Color.fromARGB(0, 0, 0, 0));
        display.setAlignment(TextDisplay.TextAlignment.CENTER);
        return display;
    }

    private void showDamageNumber(Player attacker, Location location, double damage) {
        TextDisplay display = createTextDisplay(location);
        display.setText(ChatColor.WHITE + "-" + formatDamage(damage));
        attacker.showEntity(plugin, display);
        activeDamageDisplays.add(display);
        new BukkitRunnable() {
            private int age;
            @Override
            public void run() {
                if (!attacker.isOnline() || !display.isValid() || age++ >= 12) {
                    display.remove();
                    activeDamageDisplays.remove(display);
                    cancel();
                    return;
                }
                display.teleport(location.clone().add(0, age * 0.045, 0));
            }
        }.runTaskTimer(plugin, 1L, 1L);
    }

    private String formatDamage(double damage) {
        if (Math.abs(damage - Math.rint(damage)) < 0.05) return Long.toString(Math.round(damage));
        return String.format(Locale.ROOT, "%.1f", damage);
    }

    private String formatHealth(double health) {
        return Long.toString(Math.max(0L, (long) Math.ceil(health)));
    }

    private void applyProgression(Monster monster, Player referencePlayer) {
        if (!plugin.getConfig().getBoolean("progression.enabled", true)
                || monster.getPersistentDataContainer().has(monsterLevelKey, PersistentDataType.INTEGER)) return;
        int level = getPlayerLevel(referencePlayer);
        monster.getPersistentDataContainer().set(monsterLevelKey, PersistentDataType.INTEGER, level);
        if (level <= 1) return;

        EntityEquipment equipment = monster.getEquipment();
        if (equipment == null) return;
        double chancePerSlot = plugin.getConfig().getDouble("progression.equipment-chance-per-level", 0.09);
        double armorChance = Math.min(0.32, (level - 1) * chancePerSlot);
        Material[] armor = armorForLevel(level);

        if (isEmpty(equipment.getHelmet()) && random.nextDouble() < armorChance) {
            ItemStack item = new ItemStack(armor[0]);
            maybeEnchantArmor(item, level);
            equipment.setHelmet(item);
            equipment.setHelmetDropChance(0.0f);
        }
        if (isEmpty(equipment.getChestplate()) && random.nextDouble() < armorChance) {
            ItemStack item = new ItemStack(armor[1]);
            maybeEnchantArmor(item, level);
            equipment.setChestplate(item);
            equipment.setChestplateDropChance(0.0f);
        }
        if (isEmpty(equipment.getLeggings()) && random.nextDouble() < armorChance) {
            ItemStack item = new ItemStack(armor[2]);
            equipment.setLeggings(item);
            equipment.setLeggingsDropChance(0.0f);
        }
        if (isEmpty(equipment.getBoots()) && random.nextDouble() < armorChance) {
            ItemStack item = new ItemStack(armor[3]);
            equipment.setBoots(item);
            equipment.setBootsDropChance(0.0f);
        }

        if (level >= 3 && isEmpty(equipment.getItemInOffHand()) && random.nextDouble() < armorChance * 0.35) {
            equipment.setItemInOffHand(new ItemStack(Material.SHIELD));
            equipment.setItemInOffHandDropChance(0.0f);
        }

        if (isEmpty(equipment.getItemInMainHand()) && random.nextDouble() < Math.min(0.40, 0.15 + (level - 2) * 0.08)) {
            ItemStack weapon = new ItemStack(weaponForLevel(level));
            if (level >= 3 && random.nextDouble() < 0.05 * (level - 2)) {
                weapon.addEnchantment(Enchantment.SHARPNESS, 1);
            }
            equipment.setItemInMainHand(weapon);
            equipment.setItemInMainHandDropChance(0.0f);
        }

        if (level >= 4) {
            AttributeInstance maxHealth = monster.getAttribute(Attribute.MAX_HEALTH);
            if (maxHealth != null) {
                double bonusPerLevel = Math.max(0.0,
                        plugin.getConfig().getDouble("progression.health-bonus-per-level", 0.05));
                double cap = Math.max(0.0,
                        plugin.getConfig().getDouble("progression.max-health-bonus", 0.10));
                double bonus = Math.min(cap, (level - 3) * bonusPerLevel);
                double oldMax = maxHealth.getValue();
                maxHealth.setBaseValue(maxHealth.getBaseValue() * (1.0 + bonus));
                double addedHealth = Math.max(0.0, maxHealth.getValue() - oldMax);
                monster.setHealth(Math.min(maxHealth.getValue(), monster.getHealth() + addedHealth));
            }
        }
    }

    private boolean isEmpty(ItemStack item) {
        return item == null || item.getType().isAir();
    }

    private Material[] armorForLevel(int level) {
        if (level <= 2) {
            return new Material[] {Material.LEATHER_HELMET, Material.LEATHER_CHESTPLATE,
                    Material.LEATHER_LEGGINGS, Material.LEATHER_BOOTS};
        }
        if (level == 3) {
            return new Material[] {Material.CHAINMAIL_HELMET, Material.CHAINMAIL_CHESTPLATE,
                    Material.CHAINMAIL_LEGGINGS, Material.CHAINMAIL_BOOTS};
        }
        return new Material[] {Material.IRON_HELMET, Material.IRON_CHESTPLATE,
                Material.IRON_LEGGINGS, Material.IRON_BOOTS};
    }

    private Material weaponForLevel(int level) {
        if (level <= 2) return Material.WOODEN_SWORD;
        if (level == 3) return Material.STONE_SWORD;
        return Material.IRON_SWORD;
    }

    private void maybeEnchantArmor(ItemStack item, int level) {
        if (level >= 4 && random.nextDouble() < 0.05 * (level - 2)) {
            item.addEnchantment(Enchantment.PROTECTION, 1);
        }
    }

    private Player findNearestPlayer(Location location, double radius) {
        Player nearest = null;
        double nearestDistance = radius * radius;
        for (Player player : location.getWorld().getPlayers()) {
            double distance = player.getLocation().distanceSquared(location);
            if (distance <= nearestDistance) {
                nearestDistance = distance;
                nearest = player;
            }
        }
        return nearest;
    }

    private int getPlayerLevel(Player player) {
        double hours = getPlaytimeMillis(player.getUniqueId()) / (double) HOUR_MILLIS;
        if (hours >= plugin.getConfig().getDouble("progression.level-5-hours", 20.0)) return 5;
        if (hours >= plugin.getConfig().getDouble("progression.level-4-hours", 10.0)) return 4;
        if (hours >= plugin.getConfig().getDouble("progression.level-3-hours", 5.0)) return 3;
        if (hours >= plugin.getConfig().getDouble("progression.level-2-hours", 2.0)) return 2;
        return 1;
    }

    private long getPlaytimeMillis(UUID id) {
        long accumulated = accumulatedPlaytime.getOrDefault(id, 0L);
        Long started = sessionStarted.get(id);
        return accumulated + (started == null ? 0L : Math.max(0L, System.currentTimeMillis() - started));
    }

    private void finishSession(UUID id, long now) {
        Long started = sessionStarted.remove(id);
        if (started != null) {
            accumulatedPlaytime.merge(id, Math.max(0L, now - started), Long::sum);
        }
    }

    private void savePlaytime() {
        for (Map.Entry<UUID, Long> entry : accumulatedPlaytime.entrySet()) {
            playtimeData.set(entry.getKey().toString(), entry.getValue());
        }
        for (UUID id : sessionStarted.keySet()) {
            playtimeData.set(id.toString(), getPlaytimeMillis(id));
        }
        try {
            File parent = playtimeFile.getParentFile();
            if (parent != null && !parent.exists() && !parent.mkdirs()) {
                plugin.getLogger().warning("Could not create plugin data folder for playtime.");
                return;
            }
            playtimeData.save(playtimeFile);
        } catch (IOException exception) {
            plugin.getLogger().warning("Could not save player playtime: " + exception.getMessage());
        }
    }
}
