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

    @Override
    public void onEnable() {
        saveDefaultConfig();
        getServer().getPluginManager().registerEvents(this, this);
        getLogger().info("XZH Survival Plugin enabled.");
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
        List<Block> leaves = collectNearbyLeaves(logs);
        if (logs.size() < 2 || leaves.size() <
                getConfig().getInt("tree-felling.minimum-leaves", 6)) return;

        Map<Material, Integer> totals = new HashMap<>();
        for (Block log : logs) totals.merge(log.getType(), 1, Integer::sum);
        if (!hasInventorySpace(player.getInventory(), totals)) {
            player.sendMessage("§cYour inventory needs more space to fell this tree.");
            return;
        }

        // Take over the break so vanilla cannot re-break the first log or duplicate drops.
        event.setDropItems(false);
        event.setCancelled(true);
        playWoodFlyAnimation(player, logs);
        for (Block log : logs) {
            log.setType(Material.AIR, false);
        }
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
        for (Map.Entry<Material, Integer> entry : totals.entrySet()) {
            int amount = entry.getValue();
            while (amount > 0) {
                int stack = Math.min(amount, entry.getKey().getMaxStackSize());
                player.getInventory().addItem(new ItemStack(entry.getKey(), stack));
                amount -= stack;
            }
        }
        damageTool(tool);
        player.sendMessage("§aTree felled! §fLogs collected directly into your inventory.");
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
        for (Map.Entry<Material, Integer> entry : totals.entrySet()) {
            int remaining = entry.getValue();
            for (ItemStack item : inventory.getStorageContents()) {
                if (item == null || item.getType().isAir()) {
                    remaining -= entry.getKey().getMaxStackSize();
                } else if (item.getType() == entry.getKey()) {
                    remaining -= Math.max(0, item.getMaxStackSize() - item.getAmount());
                }
                if (remaining <= 0) break;
            }
            if (remaining > 0) return false;
        }
        return true;
    }

    private void damageTool(ItemStack tool) {
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
        deductBank(player, penalty);
        player.sendMessage("§cDeath penalty: §f" + penalty + " coins from your bank. §7XP loss: " + percent + "%.");
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

    private void deductBank(Player player, int requested) {
        Plugin economy = Bukkit.getPluginManager().getPlugin("XZHEconomySystem");
        if (economy == null || !economy.isEnabled()) {
            getLogger().warning("XZHEconomySystem unavailable; skipped bank penalty for " + player.getName());
            return;
        }
        try {
            Method getManager = economy.getClass().getMethod("getBankManager");
            Object manager = getManager.invoke(economy);
            Method getBalance = manager.getClass().getMethod("getBalance", UUID.class);
            Method setBalance = manager.getClass().getMethod("setBalance", UUID.class, double.class);
            double balance = ((Number) getBalance.invoke(manager, player.getUniqueId())).doubleValue();
            double deduction = Math.min(Math.max(0, balance), requested);
            setBalance.invoke(manager, player.getUniqueId(), Math.max(0, balance - deduction));
        } catch (ReflectiveOperationException | RuntimeException exception) {
            getLogger().warning("Could not access XZHEconomySystem bank API; skipped bank penalty: "
                    + exception.getClass().getSimpleName());
        }
    }
}
