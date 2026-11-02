package com.xzh.survival;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;

import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;
import java.util.Random;
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
        if (survivalFeatureManager != null) survivalFeatureManager.shutdown();
        if (bountyHuntManager != null) bountyHuntManager.shutdown();
        if (roamingBossManager != null) roamingBossManager.shutdown();
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
