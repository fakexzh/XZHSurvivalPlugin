package com.xzh.survival;

import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Monster;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.entity.TextDisplay;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.RayTraceResult;

import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.UUID;

public final class SurvivalFeatureManager implements Listener {
    private final JavaPlugin plugin;
    private final Random random = new Random();
    private final Map<UUID, Long> playtimeSeconds = new HashMap<>();
    private final Map<UUID, TextDisplay> healthDisplays = new HashMap<>();
    private final Map<UUID, UUID> healthTargets = new HashMap<>();
    private final NamespacedKey monsterLevelKey;
    private final File playtimeFile;
    private final YamlConfiguration playtimeData = new YamlConfiguration();
    private BukkitTask ticker;
    private long elapsedSeconds;

    public SurvivalFeatureManager(JavaPlugin plugin) {
        this.plugin = plugin;
        this.monsterLevelKey = new NamespacedKey(plugin, "monster_level");
        this.playtimeFile = new File(plugin.getDataFolder(), "playtime.yml");
    }

    public void start() {
        loadPlaytime();
        Bukkit.getPluginManager().registerEvents(this, plugin);
        for (Player player : Bukkit.getOnlinePlayers()) {
            playtimeSeconds.putIfAbsent(player.getUniqueId(), 0L);
            hideOtherPlayersHealthDisplays(player);
            updateTab(player);
        }
        ticker = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 20L, 20L);
    }

    public void shutdown() {
        if (ticker != null) {
            ticker.cancel();
            ticker = null;
        }
        savePlaytime();
        for (TextDisplay display : healthDisplays.values()) {
            if (display != null && display.isValid()) display.remove();
        }
        healthDisplays.clear();
        healthTargets.clear();
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        playtimeSeconds.putIfAbsent(player.getUniqueId(), 0L);
        hideOtherPlayersHealthDisplays(player);
        updateTab(player);
        Bukkit.getScheduler().runTask(plugin, this::refreshAllTabs);
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        removeHealthDisplay(event.getPlayer().getUniqueId());
        savePlaytime();
        Bukkit.getScheduler().runTask(plugin, this::refreshAllTabs);
    }

    @EventHandler(ignoreCancelled = true)
    public void onCreatureSpawn(CreatureSpawnEvent event) {
        if (!plugin.getConfig().getBoolean("mob-progression.enabled", true)) return;
        if (!(event.getEntity() instanceof Monster monster)) return;
        if (event.getSpawnReason() == CreatureSpawnEvent.SpawnReason.CUSTOM
                || event.getSpawnReason() == CreatureSpawnEvent.SpawnReason.SPAWNER_EGG) return;

        Player nearest = findNearestPlayer(monster.getLocation(),
                plugin.getConfig().getDouble("mob-progression.player-detection-range", 48.0));
        if (nearest == null) return;

        int level = getMonsterLevel(nearest);
        monster.getPersistentDataContainer().set(monsterLevelKey, PersistentDataType.INTEGER, level);
        applyMonsterProgression(monster, level);
    }

    @EventHandler(ignoreCancelled = true)
    public void onMobDamaged(EntityDamageByEntityEvent event) {
        if (!plugin.getConfig().getBoolean("combat-indicators.damage-numbers-enabled", true)) return;
        if (!(event.getEntity() instanceof LivingEntity victim)
                || victim instanceof Player || victim instanceof ArmorStand) return;

        Entity damager = event.getDamager();
        if (damager instanceof Projectile projectile && projectile.getShooter() instanceof Entity shooter) {
            damager = shooter;
        }
        if (!(damager instanceof Player)) return;

        double damage = event.getFinalDamage();
        if (damage <= 0.0) return;
        Location location = victim.getLocation().add(0.0, Math.max(0.8, victim.getHeight() * 0.7), 0.0);
        World world = victim.getWorld();
        Bukkit.getScheduler().runTask(plugin, () -> spawnDamageNumber(world, location, damage));
    }

    private void tick() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            playtimeSeconds.merge(player.getUniqueId(), 1L, Long::sum);
            updateTab(player);
            updateTargetHealth(player);
        }
        elapsedSeconds++;
        if (elapsedSeconds >= 60L) {
            elapsedSeconds = 0L;
            savePlaytime();
        }
    }

    private void updateTab(Player player) {
        int online = Bukkit.getOnlinePlayers().size();
        int level = getMonsterLevel(player);
        player.setPlayerListName(player.getName() + " §e" + Math.max(0, player.getPing()) + "ms");
        player.setPlayerListHeaderFooter(
                "§d§lNETWORK §f• §d" + online + " ONLINE",
                "§dMONSTER LEVEL §f" + level + " §8| §7Your playtime: §f" + formatPlaytime(
                        playtimeSeconds.getOrDefault(player.getUniqueId(), 0L)));
    }

    private void refreshAllTabs() {
        for (Player player : Bukkit.getOnlinePlayers()) updateTab(player);
    }

    private String formatPlaytime(long seconds) {
        long hours = seconds / 3600L;
        long minutes = (seconds % 3600L) / 60L;
        if (hours > 0) return hours + "h " + minutes + "m";
        return minutes + "m";
    }

    private int getMonsterLevel(Player player) {
        long seconds = playtimeSeconds.getOrDefault(player.getUniqueId(), 0L);
        long hoursPerLevel = Math.max(1L, plugin.getConfig().getLong("mob-progression.hours-per-level", 3L));
        int maxLevel = Math.max(1, plugin.getConfig().getInt("mob-progression.maximum-level", 5));
        long progressedLevels = seconds / (hoursPerLevel * 3600L);
        return (int) Math.min(maxLevel, 1L + progressedLevels);
    }

    private Player findNearestPlayer(Location location, double maxDistance) {
        Player nearest = null;
        double bestDistanceSquared = maxDistance * maxDistance;
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (!player.getWorld().equals(location.getWorld()) || player.isDead()) continue;
            double distanceSquared = player.getLocation().distanceSquared(location);
            if (distanceSquared <= bestDistanceSquared) {
                bestDistanceSquared = distanceSquared;
                nearest = player;
            }
        }
        return nearest;
    }

    private void applyMonsterProgression(Monster monster, int level) {
        if (level >= 3) {
            AttributeInstance maxHealth = monster.getAttribute(Attribute.MAX_HEALTH);
            if (maxHealth != null) {
                double original = maxHealth.getBaseValue();
                double bonus = Math.min(6.0, (level - 2) * 2.0);
                maxHealth.setBaseValue(Math.min(30.0, original + bonus));
                monster.setHealth(maxHealth.getValue());
            }
        }

        if (level < 2 || random.nextDouble() > Math.min(0.45, 0.12 + (level - 2) * 0.11)) return;
        var equipment = monster.getEquipment();
        if (equipment == null) return;

        String armorTier;
        if (level == 2) armorTier = "CHAINMAIL";
        else if (level >= 5 && random.nextDouble() < 0.12) armorTier = "DIAMOND";
        else armorTier = "IRON";

        Material[] helmets = {Material.valueOf(armorTier + "_HELMET")};
        Material[] chestplates = {Material.valueOf(armorTier + "_CHESTPLATE")};
        Material[] leggings = {Material.valueOf(armorTier + "_LEGGINGS")};
        Material[] boots = {Material.valueOf(armorTier + "_BOOTS")};

        if (random.nextDouble() < 0.70) equipment.setHelmet(new ItemStack(helmets[0]));
        if (random.nextDouble() < 0.55) equipment.setChestplate(new ItemStack(chestplates[0]));
        if (random.nextDouble() < 0.55) equipment.setLeggings(new ItemStack(leggings[0]));
        if (random.nextDouble() < 0.70) equipment.setBoots(new ItemStack(boots[0]));

        if (level >= 3 && monster instanceof org.bukkit.entity.Zombie
                && random.nextDouble() < 0.06 * (level - 2)) {
            equipment.setItemInOffHand(new ItemStack(Material.SHIELD));
        }

        ItemStack weapon = equipment.getItemInMainHand();
        if (level >= 3 && weapon != null && !weapon.getType().isAir()
                && random.nextDouble() < 0.12 * (level - 2)) {
            addEnchantment(weapon, weapon.getType() == Material.BOW ? "power" : "sharpness", 1);
            equipment.setItemInMainHand(weapon);
        }

        if (level >= 3) {
            ItemStack helmet = equipment.getHelmet();
            ItemStack chestplate = equipment.getChestplate();
            ItemStack leggings = equipment.getLeggings();
            ItemStack boots = equipment.getBoots();
            enchantArmor(helmet);
            enchantArmor(chestplate);
            enchantArmor(leggings);
            enchantArmor(boots);
            equipment.setHelmet(helmet);
            equipment.setChestplate(chestplate);
            equipment.setLeggings(leggings);
            equipment.setBoots(boots);
        }
        equipment.setDropChance(EquipmentSlot.HEAD, 0.0f);
        equipment.setDropChance(EquipmentSlot.CHEST, 0.0f);
        equipment.setDropChance(EquipmentSlot.LEGS, 0.0f);
        equipment.setDropChance(EquipmentSlot.FEET, 0.0f);
        equipment.setDropChance(EquipmentSlot.HAND, 0.0f);
        equipment.setDropChance(EquipmentSlot.OFF_HAND, 0.0f);
    }

    private void enchantArmor(ItemStack armor) {
        if (armor != null && !armor.getType().isAir() && random.nextDouble() < 0.15) {
            addEnchantment(armor, "protection", 1);
        }
    }

    private void addEnchantment(ItemStack item, String key, int level) {
        Enchantment enchantment = Enchantment.getByKey(NamespacedKey.minecraft(key));
        if (enchantment != null && item.getType().getMaxStackSize() == 1) {
            item.addUnsafeEnchantment(enchantment, level);
        }
    }

    private void updateTargetHealth(Player player) {
        if (!plugin.getConfig().getBoolean("combat-indicators.target-health-enabled", true)) {
            removeHealthDisplay(player.getUniqueId());
            return;
        }
        Location eye = player.getEyeLocation();
        double range = Math.max(1.0, plugin.getConfig().getDouble("combat-indicators.target-range", 6.0));
        double entityRange = range;
        RayTraceResult blockTrace = player.getWorld().rayTraceBlocks(eye, eye.getDirection(), range);
        if (blockTrace != null) {
            entityRange = Math.min(range, eye.distance(blockTrace.getHitPosition().toLocation(eye.getWorld())));
        }
        if (entityRange <= 0.05) {
            removeHealthDisplay(player.getUniqueId());
            return;
        }
        RayTraceResult trace = player.getWorld().rayTraceEntities(
                eye, eye.getDirection(), entityRange, 0.1,
                entity -> entity instanceof LivingEntity
                        && !(entity instanceof Player)
                        && !(entity instanceof ArmorStand)
                        && !(entity instanceof TextDisplay));
        Entity hit = trace == null ? null : trace.getHitEntity();
        if (!(hit instanceof LivingEntity target) || target.isDead()) {
            removeHealthDisplay(player.getUniqueId());
            return;
        }

        TextDisplay display = healthDisplays.get(player.getUniqueId());
        if (display == null || !display.isValid()) {
            display = target.getWorld().spawn(healthDisplayLocation(target), TextDisplay.class);
            display.setBillboard(Display.Billboard.CENTER);
            display.setSeeThrough(true);
            display.setShadowed(false);
            display.setDefaultBackground(false);
            display.setBackgroundColor(Color.fromARGB(0, 0, 0, 0));
            display.setAlignment(TextDisplay.TextAlignment.CENTER);
            display.setLineWidth(100);
            display.setViewRange(0.8f);
            display.setPersistent(false);
            healthDisplays.put(player.getUniqueId(), display);
            hideDisplayFromEveryoneExcept(player, display);
        } else if (!target.getUniqueId().equals(healthTargets.get(player.getUniqueId()))) {
            hideDisplayFromEveryoneExcept(player, display);
        }

        int current = Math.max(0, (int) Math.ceil(target.getHealth()));
        AttributeInstance maxHealthAttribute = target.getAttribute(Attribute.MAX_HEALTH);
        double maxHealth = maxHealthAttribute == null ? target.getHealth() : maxHealthAttribute.getValue();
        int maximum = Math.max(1, (int) Math.ceil(maxHealth));
        display.setText("§f" + current + "/" + maximum);
        display.teleport(healthDisplayLocation(target));
        healthTargets.put(player.getUniqueId(), target.getUniqueId());
    }

    private Location healthDisplayLocation(LivingEntity target) {
        return target.getLocation().add(0.0, target.getHeight() + 0.35, 0.0);
    }

    private void hideDisplayFromEveryoneExcept(Player viewer, TextDisplay display) {
        for (Player other : Bukkit.getOnlinePlayers()) {
            if (!other.getUniqueId().equals(viewer.getUniqueId())) {
                other.hideEntity(plugin, display);
            }
        }
    }

    private void hideOtherPlayersHealthDisplays(Player joiningPlayer) {
        for (Map.Entry<UUID, TextDisplay> entry : healthDisplays.entrySet()) {
            if (!entry.getKey().equals(joiningPlayer.getUniqueId())) {
                joiningPlayer.hideEntity(plugin, entry.getValue());
            }
        }
    }

    private void removeHealthDisplay(UUID playerId) {
        TextDisplay display = healthDisplays.remove(playerId);
        healthTargets.remove(playerId);
        if (display != null && display.isValid()) display.remove();
    }

    private void spawnDamageNumber(World world, Location location, double damage) {
        if (!plugin.getConfig().getBoolean("combat-indicators.damage-numbers-enabled", true)) return;
        TextDisplay display = world.spawn(location, TextDisplay.class);
        display.setText("§f" + formatDamage(damage));
        display.setBillboard(Display.Billboard.CENTER);
        display.setSeeThrough(true);
        display.setShadowed(false);
        display.setDefaultBackground(false);
        display.setBackgroundColor(Color.fromARGB(0, 0, 0, 0));
        display.setAlignment(TextDisplay.TextAlignment.CENTER);
        display.setViewRange(0.8f);
        display.setPersistent(false);
        world.spawnParticle(Particle.CRIT, location, 3, 0.15, 0.15, 0.15, 0.02);
        new BukkitRunnable() {
            private int ticks;
            @Override
            public void run() {
                if (!display.isValid() || ticks++ >= 12) {
                    display.remove();
                    cancel();
                    return;
                }
                display.teleport(display.getLocation().add(0.0, 0.035, 0.0));
            }
        }.runTaskTimer(plugin, 1L, 1L);
    }

    private String formatDamage(double damage) {
        String value = String.format(Locale.ROOT, "%.1f", damage);
        return value.endsWith(".0") ? value.substring(0, value.length() - 2) : value;
    }

    private void loadPlaytime() {
        if (!playtimeFile.exists()) return;
        playtimeData.loadConfiguration(playtimeFile);
        ConfigurationSection section = playtimeData.getConfigurationSection("players");
        if (section == null) return;
        for (String key : section.getKeys(false)) {
            try {
                playtimeSeconds.put(UUID.fromString(key), Math.max(0L, section.getLong(key, 0L)));
            } catch (IllegalArgumentException exception) {
                plugin.getLogger().warning("Ignoring invalid player UUID in playtime.yml: " + key);
            }
        }
    }

    private void savePlaytime() {
        playtimeData.set("players", null);
        for (Map.Entry<UUID, Long> entry : playtimeSeconds.entrySet()) {
            playtimeData.set("players." + entry.getKey(), entry.getValue());
        }
        try {
            if (!plugin.getDataFolder().exists() && !plugin.getDataFolder().mkdirs()) {
                plugin.getLogger().warning("Could not create plugin data folder to save playtime.");
                return;
            }
            playtimeData.save(playtimeFile);
        } catch (IOException exception) {
            plugin.getLogger().warning("Could not save player playtime: " + exception.getMessage());
        }
    }
}
