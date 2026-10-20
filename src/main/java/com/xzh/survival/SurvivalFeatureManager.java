package com.xzh.survival;

import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Statistic;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
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
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
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
    private final Map<UUID, Integer> announcedMonsterLevels = new HashMap<>();
    private final Map<UUID, TextDisplay> healthDisplays = new HashMap<>();
    private final Map<UUID, UUID> healthTargets = new HashMap<>();
    private final NamespacedKey monsterLevelKey;
    private final NamespacedKey builtStepKey;
    private final File playtimeFile;
    private final YamlConfiguration playtimeData = new YamlConfiguration();
    private BukkitTask ticker;
    private long elapsedSeconds;

    public SurvivalFeatureManager(JavaPlugin plugin) {
        this.plugin = plugin;
        this.monsterLevelKey = new NamespacedKey(plugin, "monster_level");
        this.builtStepKey = new NamespacedKey(plugin, "monster_built_step");
        this.playtimeFile = new File(plugin.getDataFolder(), "playtime.yml");
    }

    public void start() {
        loadPlaytime();
        if (plugin.getConfig().getLong("mob-progression.hours-per-level", 3L) != 1L) {
            plugin.getConfig().set("mob-progression.hours-per-level", 1L);
            plugin.saveConfig();
        }
        Bukkit.getPluginManager().registerEvents(this, plugin);
        for (Player player : Bukkit.getOnlinePlayers()) {
            syncLifetimePlaytime(player);
            initializeLevelAnnouncements(player);
            hideOtherPlayersHealthDisplays(player);
            updateTab(player);
        }
        ticker = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 20L, 20L);
        Bukkit.getScheduler().runTaskTimer(plugin, this::tryMonsterBuilding, 60L, 60L);
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
        syncLifetimePlaytime(player);
        initializeLevelAnnouncements(player);
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
    public void onNautilusNaturalSpawn(CreatureSpawnEvent event) {
        if (event.getEntityType() != org.bukkit.entity.EntityType.NAUTILUS) return;
        CreatureSpawnEvent.SpawnReason reason = event.getSpawnReason();
        if (reason == CreatureSpawnEvent.SpawnReason.NATURAL || reason == CreatureSpawnEvent.SpawnReason.CHUNK_GEN) event.setCancelled(true);
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

    private void tick() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            // Vanilla's persisted play-time statistic survives reconnects and server restarts.
            // Keep our saved counter as a fallback, and never let lifetime playtime move backwards.
            syncLifetimePlaytime(player);
            announceLevelProgress(player);
            updateTab(player);
            updateTargetHealth(player);
        }
        elapsedSeconds++;
        if (elapsedSeconds >= 60L) {
            elapsedSeconds = 0L;
            savePlaytime();
        }
    }

    private void syncLifetimePlaytime(Player player) {
        UUID id = player.getUniqueId();
        long savedSeconds = playtimeSeconds.getOrDefault(id, 0L);
        long vanillaSeconds = Math.max(0L, player.getStatistic(Statistic.PLAY_ONE_MINUTE) / 20L);
        playtimeSeconds.put(id, Math.max(savedSeconds, vanillaSeconds));
    }

    private void updateTab(Player player) {
        int level = getMonsterLevel(player);
        int ping = Math.max(0, player.getPing());
        long lifetimeSeconds = playtimeSeconds.getOrDefault(player.getUniqueId(), 0L);

        // Keep each player row minimal: white username followed by a green numeric ping.
        player.setPlayerListName("§f" + player.getName() + " §8| §e" + ping + "ms");
        // The header/footer stay clean and show this viewer's own level and saved all-time playtime.
        player.setPlayerListHeaderFooter(
                "§d§lXZH SURVIVAL\n§8━━━━━━━━━━━━━━━━",
                "\n§6Monster Level: §d" + level + "§8/§d100 §8| §bPlay Time: §f" + formatPlaytime(lifetimeSeconds) + globalQuestFooter());
    }

    private String globalQuestFooter() {
        org.bukkit.plugin.Plugin economy = Bukkit.getPluginManager().getPlugin("XZHEconomySystem");
        if (economy == null || !economy.isEnabled()) return "";
        try {
            Object manager = economy.getClass().getMethod("getQuestManager").invoke(economy);
            if (manager == null) return "";
            Object status = manager.getClass().getMethod("getGlobalQuestTabText").invoke(manager);
            return status instanceof String text ? "\n§8━━━━━━━━━━━━━━━━\n" + text : "";
        } catch (ReflectiveOperationException ignored) {
            return "";
        }
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

    private void initializeLevelAnnouncements(Player player) {
        UUID id = player.getUniqueId();
        if (announcedMonsterLevels.containsKey(id)) return;
        int currentLevel = getMonsterLevel(player);
        long savedSeconds = playtimeSeconds.getOrDefault(id, 0L);
        if (savedSeconds == 0L) {
            Bukkit.broadcastMessage("§d§lXZH Survival §8» §f@" + player.getName() + " §ahas reached Monster Level 1!");
            announcedMonsterLevels.put(id, 1);
        } else {
            // Avoid spamming old level announcements for players already established before this update.
            announcedMonsterLevels.put(id, currentLevel);
        }
    }

    private void announceLevelProgress(Player player) {
        UUID id = player.getUniqueId();
        int currentLevel = getMonsterLevel(player);
        Integer previousAnnounced = announcedMonsterLevels.get(id);
        if (previousAnnounced == null) {
            announcedMonsterLevels.put(id, currentLevel);
            return;
        }
        if (currentLevel <= previousAnnounced) return;
        for (int level = previousAnnounced + 1; level <= currentLevel; level++) {
            Bukkit.broadcastMessage("§d§lXZH Survival §8» §f@" + player.getName() + " §ahas reached Monster Level " + level + "!");
        }
        announcedMonsterLevels.put(id, currentLevel);
        savePlaytime();
    }

    private int getMonsterLevel(Player player) {
        long seconds = playtimeSeconds.getOrDefault(player.getUniqueId(), 0L);
        long hoursPerLevel = 1L;
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

    private void tryMonsterBuilding() {
        if (!plugin.getConfig().getBoolean("mob-progression.enabled", true)
                || !plugin.getConfig().getBoolean("mob-progression.special-block-building-enabled", true)) return;
        java.util.Set<UUID> checked = new java.util.HashSet<>();
        for (Player nearbyPlayer : Bukkit.getOnlinePlayers()) {
            for (Entity entity : nearbyPlayer.getNearbyEntities(16.0, 16.0, 16.0)) {
                if (!(entity instanceof Monster monster) || monster.isDead()
                        || !checked.add(monster.getUniqueId())) continue;
                int level = monster.getPersistentDataContainer().getOrDefault(monsterLevelKey, PersistentDataType.INTEGER, 1);
                int startLevel = Math.max(1, plugin.getConfig().getInt("mob-progression.special-abilities-start-level", 20));
                if (level < startLevel || monster.getPersistentDataContainer().has(builtStepKey, PersistentDataType.BYTE)) continue;
                if (random.nextDouble() > Math.min(0.08, 0.01 + level * 0.0007)) continue;
                Player target = findNearestPlayer(monster.getLocation(), 12.0);
                if (target == null || target.getLocation().getY() <= monster.getLocation().getY() + 0.8) continue;
                BlockFace face = yawToFace(monster.getLocation().getYaw());
                Block step = monster.getLocation().getBlock().getRelative(face);
                Block floor = step.getRelative(0, -1, 0);
                if (!step.getType().isAir() || !floor.getType().isSolid() || step.isLiquid()) continue;
                step.setType(Material.DIRT, false);
                monster.getPersistentDataContainer().set(builtStepKey, PersistentDataType.BYTE, (byte) 1);
                Bukkit.getScheduler().runTaskLater(plugin, () -> {
                    if (step.getType() == Material.DIRT) step.setType(Material.AIR, false);
                }, 20L * 15L);
            }
        }
    }

    private BlockFace yawToFace(float yaw) {
        int direction = Math.floorMod(Math.round(yaw / 90.0f), 4);
        return switch (direction) {
            case 0 -> BlockFace.SOUTH;
            case 1 -> BlockFace.WEST;
            case 2 -> BlockFace.NORTH;
            default -> BlockFace.EAST;
        };
    }

    private void applyMonsterProgression(Monster monster, int level) {
        int speedStart = Math.max(1, plugin.getConfig().getInt("mob-progression.speed-effect-start-level", 15));
        if (level >= speedStart) {
            int amplifier = level >= 60 ? 1 : 0;
            monster.addPotionEffect(new PotionEffect(PotionEffectType.SPEED, 20 * 60 * 10, amplifier, true, false, false));
        }
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
        double weaponEnchantChance = Math.min(0.45, 0.06 + level * 0.004);
        if (level >= 3 && weapon != null && !weapon.getType().isAir()
                && random.nextDouble() < weaponEnchantChance) {
            if (weapon.getType() == Material.BOW) {
                addEnchantment(weapon, "power", 1);
                if (level >= 20 && random.nextDouble() < 0.15) addEnchantment(weapon, "flame", 1);
            } else {
                addEnchantment(weapon, "sharpness", level >= 30 && random.nextDouble() < 0.15 ? 2 : 1);
                if (level >= 20 && random.nextDouble() < 0.10) addEnchantment(weapon, "fire_aspect", 1);
                if (level >= 35 && random.nextDouble() < 0.10) addEnchantment(weapon, "knockback", 1);
            }
            equipment.setItemInMainHand(weapon);
        }

        if (level >= 3) {
            ItemStack helmetItem = equipment.getHelmet();
            ItemStack chestplateItem = equipment.getChestplate();
            ItemStack leggingsItem = equipment.getLeggings();
            ItemStack bootsItem = equipment.getBoots();
            enchantArmor(helmetItem);
            enchantArmor(chestplateItem);
            enchantArmor(leggingsItem);
            enchantArmor(bootsItem);
            equipment.setHelmet(helmetItem);
            equipment.setChestplate(chestplateItem);
            equipment.setLeggings(leggingsItem);
            equipment.setBoots(bootsItem);
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

    private void loadPlaytime() {
        if (!playtimeFile.exists()) return;
        playtimeData.loadConfiguration(playtimeFile);
        ConfigurationSection section = playtimeData.getConfigurationSection("players");
        if (section != null) for (String key : section.getKeys(false)) {
            try {
                playtimeSeconds.put(UUID.fromString(key), Math.max(0L, section.getLong(key, 0L)));
            } catch (IllegalArgumentException exception) {
                plugin.getLogger().warning("Ignoring invalid player UUID in playtime.yml: " + key);
            }
        }
        ConfigurationSection announced = playtimeData.getConfigurationSection("announced-levels");
        if (announced != null) {
            for (String key : announced.getKeys(false)) {
                try {
                    announcedMonsterLevels.put(UUID.fromString(key), Math.max(1, announced.getInt(key, 1)));
                } catch (IllegalArgumentException exception) {
                    plugin.getLogger().warning("Ignoring invalid player UUID in announced-levels: " + key);
                }
            }
        }
    }

    private void savePlaytime() {
        playtimeData.set("players", null);
        playtimeData.set("announced-levels", null);
        for (Map.Entry<UUID, Long> entry : playtimeSeconds.entrySet()) {
            playtimeData.set("players." + entry.getKey(), entry.getValue());
        }
        for (Map.Entry<UUID, Integer> entry : announcedMonsterLevels.entrySet()) {
            playtimeData.set("announced-levels." + entry.getKey(), entry.getValue());
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
