package com.xzh.survival;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Tag;
import org.bukkit.block.Block;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.Damageable;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Queue;
import java.util.Set;
import java.util.stream.Collectors;

public final class TreeChopListener implements Listener {
    private static final int MIN_LEAVES = 12;
    private static final int MAX_LOGS = 256;
    private static final int LEAF_SEARCH_RADIUS = 4;

    private final JavaPlugin plugin;
    private final Set<String> playerPlacedLogs = new HashSet<>();
    private final File placedLogsFile;
    private final YamlConfiguration placedLogsData = new YamlConfiguration();
    private boolean placedLogsDirty;

    public TreeChopListener(JavaPlugin plugin) {
        this.plugin = plugin;
        this.placedLogsFile = new File(plugin.getDataFolder(), "player-placed-logs.yml");
        loadPlacedLogs();
        Bukkit.getScheduler().runTaskTimer(plugin, this::flushPlacedLogs, 6000L, 6000L);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onLogPlace(BlockPlaceEvent event) {
        if (!Tag.LOGS.isTagged(event.getBlockPlaced().getType())) return;
        playerPlacedLogs.add(key(event.getBlockPlaced()));
        savePlacedLogs();
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onLogBreak(BlockBreakEvent event) {
        if (playerPlacedLogs.remove(key(event.getBlock()))) savePlacedLogs();
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onTreeChop(PlayerInteractEvent event) {
        if (event.getAction() != Action.LEFT_CLICK_BLOCK || event.getClickedBlock() == null) return;

        Player player = event.getPlayer();
        if (!player.isSneaking() || !isAxe(player.getInventory().getItemInMainHand().getType())) return;

        Block clicked = event.getClickedBlock();
        if (!Tag.LOGS.isTagged(clicked.getType())) return;

        ItemStack axe = player.getInventory().getItemInMainHand();
        Set<Block> logs = findConnectedLogs(clicked);

        // A connected structure containing even one player-placed log is not a natural tree.
        if (logs.isEmpty() || logs.stream().anyMatch(log -> playerPlacedLogs.contains(key(log)))) return;
        if (countNearbyLeaves(logs) < MIN_LEAVES) return;

        event.setCancelled(true);

        for (Block log : logs) {
            if (!Tag.LOGS.isTagged(log.getType())) continue;
            List<ItemStack> drops = new ArrayList<>(log.getDrops(axe, player));
            for (ItemStack drop : drops) {
                log.getWorld().dropItemNaturally(log.getLocation(), drop);
            }
            // Keep normal block physics enabled; vanilla handles leaf-distance updates and random-tick decay.
            log.setType(Material.AIR, true);
            damageAxeTwice(player, axe);
            if (axe.getType().isAir()) break;
        }
    }

    private Set<Block> findConnectedLogs(Block start) {
        Set<Block> found = new LinkedHashSet<>();
        Queue<Block> queue = new ArrayDeque<>();
        Set<String> visited = new HashSet<>();
        queue.add(start);

        while (!queue.isEmpty() && found.size() < MAX_LOGS) {
            Block current = queue.remove();
            String blockKey = key(current);
            if (!visited.add(blockKey) || !Tag.LOGS.isTagged(current.getType())) continue;
            found.add(current);

            // 26-neighbour connectivity supports naturally branching tree trunks.
            for (int dx = -1; dx <= 1; dx++) {
                for (int dy = -1; dy <= 1; dy++) {
                    for (int dz = -1; dz <= 1; dz++) {
                        if (dx == 0 && dy == 0 && dz == 0) continue;
                        Block next = current.getRelative(dx, dy, dz);
                        if (Tag.LOGS.isTagged(next.getType())) queue.add(next);
                    }
                }
            }
        }
        return found;
    }

    private int countNearbyLeaves(Set<Block> logs) {
        Set<String> leaves = new HashSet<>();
        for (Block log : logs) {
            for (int dx = -LEAF_SEARCH_RADIUS; dx <= LEAF_SEARCH_RADIUS; dx++) {
                for (int dy = -LEAF_SEARCH_RADIUS; dy <= LEAF_SEARCH_RADIUS; dy++) {
                    for (int dz = -LEAF_SEARCH_RADIUS; dz <= LEAF_SEARCH_RADIUS; dz++) {
                        if (dx * dx + dy * dy + dz * dz > LEAF_SEARCH_RADIUS * LEAF_SEARCH_RADIUS) continue;
                        Block candidate = log.getRelative(dx, dy, dz);
                        if (!Tag.LEAVES.isTagged(candidate.getType())) continue;
                        leaves.add(key(candidate));
                        if (leaves.size() >= MIN_LEAVES) return leaves.size();
                    }
                }
            }
        }
        return leaves.size();
    }

    private String key(Block block) {
        return block.getWorld().getUID() + ":" + block.getX() + ":" + block.getY() + ":" + block.getZ();
    }

    private void loadPlacedLogs() {
        if (!placedLogsFile.exists()) return;
        YamlConfiguration loaded = YamlConfiguration.loadConfiguration(placedLogsFile);
        playerPlacedLogs.addAll(loaded.getStringList("blocks"));
    }

    private void savePlacedLogs() {
        placedLogsDirty = true;
    }

    public void shutdown() {
        flushPlacedLogs();
    }

    private void flushPlacedLogs() {
        if (!placedLogsDirty) return;
        placedLogsData.set("blocks", playerPlacedLogs.stream().sorted().collect(Collectors.toList()));
        try {
            if (!plugin.getDataFolder().exists() && !plugin.getDataFolder().mkdirs()) {
                plugin.getLogger().warning("Could not create plugin data folder to save player-placed logs.");
                return;
            }
            placedLogsData.save(placedLogsFile);
            placedLogsDirty = false;
        } catch (IOException exception) {
            plugin.getLogger().warning("Could not save player-placed log tracking: " + exception.getMessage());
        }
    }

    private boolean isAxe(Material material) {
        return material.name().endsWith("_AXE");
    }

    private void damageAxeTwice(Player player, ItemStack axe) {
        if (axe == null || axe.getType().isAir()) return;
        ItemMeta meta = axe.getItemMeta();
        if (!(meta instanceof Damageable damageable)) return;
        int nextDamage = damageable.getDamage() + 2;
        if (nextDamage >= axe.getType().getMaxDurability()) {
            player.getInventory().setItemInMainHand(null);
            player.getWorld().playSound(player.getLocation(), org.bukkit.Sound.ENTITY_ITEM_BREAK, 1.0f, 1.0f);
            return;
        }
        damageable.setDamage(nextDamage);
        axe.setItemMeta(meta);
    }
}
