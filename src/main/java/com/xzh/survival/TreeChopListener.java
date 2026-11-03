package com.xzh.survival;

import org.bukkit.Material;
import org.bukkit.Tag;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.Damageable;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Queue;
import java.util.Set;
import java.util.stream.Collectors;

public final class TreeChopListener implements Listener {
    private static final int MIN_LEAVES = 5;
    private static final int MAX_LOGS = 256;
    private static final int LEAF_SEARCH_RADIUS = 4;
    private final JavaPlugin plugin;
    private final Set<String> playerPlacedLogs = new HashSet<>();
    private final File placedLogsFile;
    private final YamlConfiguration placedLogsData = new YamlConfiguration();

    public TreeChopListener(JavaPlugin plugin) {
        this.plugin = plugin;
        this.placedLogsFile = new File(plugin.getDataFolder(), "player-placed-logs.yml");
        loadPlacedLogs();
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
        if (logs.isEmpty() || countNearbyLeaves(logs) < MIN_LEAVES) return;

        // Only a qualifying tree cancels the vanilla single-block interaction.
        event.setCancelled(true);

        // Break only logs. Leaves are deliberately left completely untouched.
        for (Block log : logs) {
            if (!Tag.LOGS.isTagged(log.getType())) continue;
            List<ItemStack> drops = new ArrayList<>(log.getDrops(axe, player));
            for (ItemStack drop : drops) {
                log.getWorld().dropItemNaturally(log.getLocation(), drop);
            }
            // Enable block physics so vanilla leaf-distance propagation starts and natural leaves decay.
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
            String key = current.getWorld().getUID() + ":" + current.getX() + ":" + current.getY() + ":" + current.getZ();
            if (!visited.add(key) || !Tag.LOGS.isTagged(current.getType())) continue;
            found.add(current);

            // 26-neighbour connectivity includes naturally branching tree trunks.
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
                        String key = candidate.getWorld().getUID() + ":" + candidate.getX() + ":"
                                + candidate.getY() + ":" + candidate.getZ();
                        leaves.add(key);
                        if (leaves.size() >= MIN_LEAVES) return leaves.size();
                    }
                }
            }
        }
        return leaves.size();
    }

    private String key(Block block) {\n        return block.getWorld().getUID() + ":" + block.getX() + ":" + block.getY() + ":" + block.getZ();\n    }\n\n    private void loadPlacedLogs() {\n        if (!placedLogsFile.exists()) return;\n        placedLogsData.loadConfiguration(placedLogsFile);\n        playerPlacedLogs.addAll(placedLogsData.getStringList("blocks"));\n    }\n\n    private void savePlacedLogs() {\n        placedLogsData.set("blocks", playerPlacedLogs.stream().sorted().collect(Collectors.toList()));\n        try {\n            if (!plugin.getDataFolder().exists()) plugin.getDataFolder().mkdirs();\n            placedLogsData.save(placedLogsFile);\n        } catch (IOException exception) {\n            plugin.getLogger().warning("Could not save player-placed log tracking: " + exception.getMessage());\n        }\n    }\n\n    private boolean isAxe(Material material) {
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
