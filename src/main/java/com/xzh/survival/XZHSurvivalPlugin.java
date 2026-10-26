package com.xzh.survival;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Tag;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.type.Leaves;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.Vector;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.Damageable;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;

import java.lang.reflect.Method;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;

public final class XZHSurvivalPlugin extends JavaPlugin implements Listener {
    private final Random random = new Random();
    private final Map<UUID, Integer> pendingXpRestore = new HashMap<>();
    private SurvivalFeatureManager survivalFeatureManager;
    private AdventureLootManager adventureLootManager;
    private DynamicTradingManager dynamicTradingManager;
    private FarmingManager farmingManager;
    private BountyHuntManager bountyHuntManager;
    private RoamingBossManager roamingBossManager;
    private CustomTimeManager customTimeManager;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        migrateSurvivalConfig();
        getServer().getPluginManager().registerEvents(this, this);
        getServer().getPluginManager().registerEvents(new VillagerWorkstationResetListener(this), this);
        // Restore vanilla daylight cycling if an older plugin version left it disabled.
        for (World world : Bukkit.getWorlds()) {
            world.setGameRule(org.bukkit.GameRule.DO_DAYLIGHT_CYCLE, true);
        }
        customTimeManager = new CustomTimeManager(this);
        customTimeManager.start();
        survivalFeatureManager = new SurvivalFeatureManager(this);
        survivalFeatureManager.start();
        adventureLootManager = new AdventureLootManager(this);
        adventureLootManager.start();
        dynamicTradingManager = new DynamicTradingManager(this);
        dynamicTradingManager.start();
        farmingManager = new FarmingManager(this);
        farmingManager.start();
        bountyHuntManager = new BountyHuntManager(this);
        bountyHuntManager.start();
        roamingBossManager = new RoamingBossManager(this);
        roamingBossManager.start();
        if (getCommand("bounty") != null) getCommand("bounty").setExecutor(bountyHuntManager);
        getLogger().info("XZH Survival Plugin enabled.");
    }

    private void migrateSurvivalConfig() {
        boolean changed = false;
        // Initialize the custom clock only when a server config does not have settings yet.
        // Existing custom-time values remain editable and are not overwritten on each boot.
        if (!getConfig().contains("custom-time")) {
            getConfig().set("custom-time.enabled", true);
            getConfig().set("custom-time.day-duration-seconds", 2400);
            getConfig().set("custom-time.night-duration-seconds", 1500);
            changed = true;
        }
        // Upgrade the plugin's former bundled bounty defaults without overriding custom server values.
        if (getConfig().getInt("bounty-hunt.rewards.common", -1) == 500) {
            getConfig().set("bounty-hunt.rewards.common", 200);
            changed = true;
        }
        if (getConfig().getInt("bounty-hunt.rewards.rare", -1) == 1300) {
            getConfig().set("bounty-hunt.rewards.rare", 500);
            changed = true;
        }
        if (getConfig().getInt("bounty-hunt.rewards.legendary", -1) == 3500) {
            getConfig().set("bounty-hunt.rewards.legendary", 900);
            changed = true;
        }
        if (changed) saveConfig();
    }

    @Override
    public void onDisable() {
        if (customTimeManager != null) customTimeManager.shutdown();
        if (survivalFeatureManager != null) survivalFeatureManager.shutdown();
        if (bountyHuntManager != null) bountyHuntManager.shutdown();
        if (roamingBossManager != null) roamingBossManager.shutdown();
    }

    @EventHandler(ignoreCancelled = true)
    public void onBlockBreak(BlockBreakEvent event) {
        if (!getConfig().getBoolean("tree-felling.enabled", true)) return;
        Player player = event.getPlayer();
        if (getConfig().getBoolean("tree-felling.require-sneak-to-disable", true) && player.isSneaking()) return;

        ItemStack tool = player.getInventory().getItemInMainHand();
        // All vanilla axe tiers can fell trees.
        if (!tool.getType().name().endsWith("_AXE")) return;

        Block start = event.getBlock();
        if (!Tag.LOGS.isTagged(start.getType())) return;

        List<Block> logs = collectConnectedLogs(start,
                getConfig().getInt("tree-felling.maximum-logs", 256));
        List<Block> nearbyLeaves = collectNearbyLeaves(logs);
        if (logs.size() < 2 || nearbyLeaves.size() <
                getConfig().getInt("tree-felling.minimum-leaves", 6)) return;

        // Take over the break so vanilla cannot re-break the first log or duplicate drops.
        event.setDropItems(false);
        event.setCancelled(true);

        // Every harvested log costs one durability point (subject to normal Unbreaking).
        // If the axe breaks, logs beyond that point remain in the world and are not collected.
        List<Block> felledLogs = new ArrayList<>();
        for (Block log : logs) {
            if (tool.getType().isAir() || tool.getAmount() <= 0) break;
            Material woodType = log.getType();
            Location dropLocation = log.getLocation().add(0.5, 0.5, 0.5);
            log.setType(Material.AIR, false);
            felledLogs.add(log);
            // Spawn real collectible item entities at the original log positions.
            // Do not route wood through inventory or use fake animation-only items.
            log.getWorld().dropItemNaturally(dropLocation, new ItemStack(woodType, 1));
            damageTool(tool);
            if (tool.getType().isAir() || tool.getAmount() <= 0) break;
        }
        if (felledLogs.isEmpty()) return;

        List<Block> leaves = collectNearbyLeaves(felledLogs);
        new BukkitRunnable() {
            private int index = 0;
            @Override public void run() {
                int removedThisTick = 0;
                while (index < leaves.size() && removedThisTick < 2) {
                    Block leaf = leaves.get(index++);
                    if (leaf.getBlockData() instanceof Leaves) {
                        leaf.getWorld().spawnParticle(org.bukkit.Particle.BLOCK, leaf.getLocation().add(0.5, 0.5, 0.5),
                                3, 0.2, 0.2, 0.2, leaf.getBlockData());
                        leaf.setType(Material.AIR, false);
                    }
                    removedThisTick++;
                }
                if (index >= leaves.size()) cancel();
            }
        }.runTaskTimer(this, 1L, 1L);

        player.sendMessage("§aTree felled! §f" + felledLogs.size() + " logs dropped nearby. Walk over them to collect.");
    }

    private List<Block> collectConnectedLogs(Block start, int maximum) {
        List<Block> found = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        ArrayDeque<Block> queue = new ArrayDeque<>();
        queue.add(start);
        while (!queue.isEmpty() && found.size() < maximum) {
            Block current = queue.removeFirst();
            String key = current.getX() + ":" + current.getY() + ":" + current.getZ();
            if (!seen.add(key) || !Tag.LOGS.isTagged(current.getType())) continue;
            found.add(current);
            for (int x = -1; x <= 1; x++) {
                for (int y = -1; y <= 1; y++) {
                    for (int z = -1; z <= 1; z++) {
                        if (x == 0 && y == 0 && z == 0) continue;
                        Block next = current.getRelative(x, y, z);
                        String nextKey = next.getX() + ":" + next.getY() + ":" + next.getZ();
                        if (!seen.contains(nextKey) && Tag.LOGS.isTagged(next.getType())) queue.addLast(next);
                    }
                }
            }
        }
        return found;
    }

    private List<Block> collectNearbyLeaves(List<Block> logs) {
        Map<String, Block> leaves = new HashMap<>();
        for (Block log : logs) {
            for (int x = -4; x <= 4; x++) {
                for (int y = -3; y <= 6; y++) {
                    for (int z = -4; z <= 4; z++) {
                        Block nearby = log.getRelative(x, y, z);
                        if (nearby.getBlockData() instanceof Leaves) {
                            String key = nearby.getX() + ":" + nearby.getY() + ":" + nearby.getZ();
                            leaves.putIfAbsent(key, nearby);
                        }
                    }
                }
            }
        }
        return new ArrayList<>(leaves.values());
    }

    private void playWoodFlyAnimation(Player player, List<Block> logs) {
        int stride = Math.max(1, (int) Math.ceil(logs.size() / 16.0));
        for (int i = 0; i < logs.size(); i += stride) {
            Block log = logs.get(i);
            Item visual = log.getWorld().dropItem(log.getLocation().add(0.5, 0.5, 0.5),
                    new ItemStack(log.getType(), 1));
            visual.setPickupDelay(Integer.MAX_VALUE);
            visual.setGravity(false);
            new BukkitRunnable() {
                private int ticks = 0;
                @Override public void run() {
                    if (!player.isOnline() || visual.isDead() || ticks++ >= 12) {
                        visual.remove();
                        cancel();
                        return;
                    }
                    Location target = player.getLocation().add(0, 1.0, 0);
                    Vector direction = target.toVector().subtract(visual.getLocation().toVector());
                    if (direction.lengthSquared() < 1.0) {
                        visual.remove();
                        cancel();
                        return;
                    }
                    visual.setVelocity(direction.normalize().multiply(0.75));
                }
            }.runTaskTimer(XZHSurvivalPlugin.this, 1L, 1L);
        }
    }

    private boolean hasInventorySpace(PlayerInventory inventory, Map<Material, Integer> totals) {
        // Simulate adding every wood type to the same temporary inventory. Checking
        // each material independently can incorrectly count the same empty slots
        // several times for mixed-wood trees.
        org.bukkit.inventory.Inventory simulation = Bukkit.createInventory(null, 36);
        simulation.setStorageContents(inventory.getStorageContents());
        for (Map.Entry<Material, Integer> entry : totals.entrySet()) {
            int amount = entry.getValue();
            while (amount > 0) {
                int stack = Math.min(amount, entry.getKey().getMaxStackSize());
                if (!simulation.addItem(new ItemStack(entry.getKey(), stack)).isEmpty()) {
                    return false;
                }
                amount -= stack;
            }
        }
        return true;
    }

    private void damageTool(ItemStack tool) {
        if (tool == null || tool.getType().isAir() || tool.getAmount() <= 0) return;
        int unbreaking = tool.getEnchantmentLevel(Enchantment.UNBREAKING);
        if (unbreaking > 0 && random.nextInt(unbreaking + 1) != 0) return;

        if (tool.getItemMeta() instanceof Damageable damageable) {
            int nextDamage = damageable.getDamage() + 1;
            if (nextDamage >= tool.getType().getMaxDurability()) {
                tool.setAmount(0);
            } else {
                damageable.setDamage(nextDamage);
                tool.setItemMeta(damageable);
            }
        }
    }

    @EventHandler
    public void onDeath(PlayerDeathEvent event) {
        if (!getConfig().getBoolean("death.enabled", true)) return;
        Player player = event.getEntity();
        World.Environment environment = player.getWorld().getEnvironment();
        String dimension = environment == World.Environment.NETHER ? "nether"
                : environment == World.Environment.THE_END ? "end" : "overworld";

        boolean keep = getConfig().getBoolean("death." + dimension + ".keep-inventory",
                !dimension.equals("overworld"));
        int totalXp = Math.max(0, player.getTotalExperience());
        if (keep) {
            event.setKeepInventory(true);
            event.getDrops().clear();
        } else {
            event.setKeepInventory(false);
            // If keepInventory gamerule left Bukkit's list empty, explicitly add the
            // player's inventory so the Overworld death cannot silently erase it.
            if (event.getDrops().isEmpty()) {
                for (ItemStack item : player.getInventory().getContents()) {
                    if (item != null && !item.getType().isAir()) event.getDrops().add(item.clone());
                }
            }
        }

        int min = getConfig().getInt("death.xp-loss-min-percent", 25);
        int max = getConfig().getInt("death.xp-loss-max-percent", 50);
        int percent = min + random.nextInt(Math.max(1, max - min + 1));
        int loss = Math.round(totalXp * percent / 100.0f);
        pendingXpRestore.put(player.getUniqueId(), Math.max(0, totalXp - loss));
        // Overworld loss becomes XP orbs; Nether/End keep inventory and lose XP on respawn.
        event.setDroppedExp(keep ? 0 : loss);

        int penalty;
        if (dimension.equals("overworld")) {
            penalty = overworldPenalty(player);
        } else {
            int low = getConfig().getInt("death." + dimension + ".minimum-bank-penalty");
            int high = getConfig().getInt("death." + dimension + ".maximum-bank-penalty");
            penalty = low + random.nextInt(Math.max(1, high - low + 1));
        }

        // Replace vanilla's automatic death line with one consistent, global XZH announcement.
        String vanillaDeathMessage = event.getDeathMessage();
        event.setDeathMessage(null);
        double actualPenalty = deductBank(player, penalty);
        broadcastDeath(player, vanillaDeathMessage, actualPenalty, percent);
    }

    @EventHandler
    public void onRespawn(org.bukkit.event.player.PlayerRespawnEvent event) {
        UUID id = event.getPlayer().getUniqueId();
        Integer remainingXp = pendingXpRestore.remove(id);
        if (remainingXp == null) return;
        Bukkit.getScheduler().runTask(this, () -> {
            Player player = event.getPlayer();
            player.setTotalExperience(0);
            player.setLevel(0);
            player.setExp(0.0f);
            player.giveExp(remainingXp);
        });
    }

    private int overworldPenalty(Player player) {
        Location home = player.getBedSpawnLocation();
        if (home == null) {
            return getConfig().getInt("death.overworld.no-home-penalty", 50);
        }
        if (home.getWorld() == null || home.getWorld() != player.getWorld()) {
            return getConfig().getInt("death.overworld.no-home-penalty", 50);
        }
        double distance = player.getLocation().distance(home);
        double cap = Math.max(1.0, getConfig().getDouble("death.overworld.distance-cap-blocks", 1000));
        int min = getConfig().getInt("death.overworld.minimum-bank-penalty", 10);
        int max = getConfig().getInt("death.overworld.maximum-bank-penalty", 100);
        return min + (int) Math.round((max - min) * Math.min(1.0, distance / cap));
    }

    private double deductBank(Player player, int requested) {
        Plugin economy = Bukkit.getPluginManager().getPlugin("XZHEconomySystem");
        if (economy == null || !economy.isEnabled()) {
            getLogger().warning("XZHEconomySystem unavailable; skipped bank penalty for " + player.getName());
            return 0.0;
        }
        try {
            Method getManager = economy.getClass().getMethod("getBankManager");
            Object manager = getManager.invoke(economy);
            Method getBalance = manager.getClass().getMethod("getBalance", UUID.class);
            Method setBalance = manager.getClass().getMethod("setBalance", UUID.class, double.class);
            double balance = ((Number) getBalance.invoke(manager, player.getUniqueId())).doubleValue();
            double safeBalance = Double.isFinite(balance) ? Math.max(0.0, balance) : 0.0;
            double deduction = Math.min(safeBalance, Math.max(0, requested));
            setBalance.invoke(manager, player.getUniqueId(), Math.max(0.0, safeBalance - deduction));
            return deduction;
        } catch (ReflectiveOperationException | RuntimeException exception) {
            getLogger().warning("Could not access XZHEconomySystem bank API; skipped bank penalty: "
                    + exception.getClass().getSimpleName());
            return 0.0;
        }
    }

    private void broadcastDeath(Player player, String vanillaMessage, double coinsLost, int xpPercent) {
        String reason = "reason unavailable";
        if (vanillaMessage != null && !vanillaMessage.isBlank()) {
            String clean = org.bukkit.ChatColor.stripColor(vanillaMessage);
            String displayName = org.bukkit.ChatColor.stripColor(player.getDisplayName());
            if (displayName != null && clean.startsWith(displayName)) {
                clean = clean.substring(displayName.length()).trim();
            } else if (clean.startsWith(player.getName())) {
                clean = clean.substring(player.getName().length()).trim();
            }
            if (clean.startsWith("was ")) clean = clean.substring(4);
            if (clean.endsWith(".")) clean = clean.substring(0, clean.length() - 1);
            if (!clean.isBlank()) reason = clean;
        }

        Bukkit.broadcastMessage("§c☠ " + player.getName() + " died! §7(" + reason + ")");
        Bukkit.broadcastMessage("§6Lost " + money(coinsLost) + " coins §8• §cXP lost: " + xpPercent + "%");
        Bukkit.broadcastMessage("§bUse /back §7or §abuy your last inventory in /shop §7(Death Inventory).");
    }

    private String money(double amount) {
        return String.format(java.util.Locale.US, "%.2f", amount).replaceAll("\\.00$", "");
    }
}
