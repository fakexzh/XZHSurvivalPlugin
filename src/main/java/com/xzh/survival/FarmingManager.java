package com.xzh.survival;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.Ageable;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.type.Cocoa;
import org.bukkit.entity.ExperienceOrb;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockDamageEvent;
import org.bukkit.inventory.ItemStack;

import java.util.Map;
import org.bukkit.plugin.java.JavaPlugin;


/**
 * Crop interaction rules:
 * - normal left-click harvests mature supported crops normally, then replants
 *   using a matching item already in the player's inventory and spawns vanilla XP orbs;
 * - normal left-click cannot break immature supported crops;
 * - sneaking + left-click removes a crop without auto-replant or harvest XP.
 */
public final class FarmingManager implements Listener {
    private final JavaPlugin plugin;

    public FarmingManager(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    public void start() {
        Bukkit.getPluginManager().registerEvents(this, plugin);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onImmatureCropDamage(BlockDamageEvent event) {
        if (!plugin.getConfig().getBoolean("farming.enabled", true)) return;

        Block block = event.getBlock();
        if (!isSupportedCrop(block.getType())) return;
        if (event.getPlayer().isSneaking()) return;
        if (!(block.getBlockData() instanceof Ageable ageable)) return;

        // Left-clicking an immature crop must not start breaking it.
        if (ageable.getAge() < ageable.getMaximumAge()) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onImmatureCropBreak(BlockBreakEvent event) {
        if (!plugin.getConfig().getBoolean("farming.enabled", true)) return;
        if (event.getPlayer().isSneaking()) return;

        Block block = event.getBlock();
        if (!isSupportedCrop(block.getType())) return;
        if (!(block.getBlockData() instanceof Ageable ageable)) return;

        // Defensive guard for creative mode or another break path that bypasses
        // BlockDamageEvent. Sneak-breaking remains the explicit removal action.
        if (ageable.getAge() < ageable.getMaximumAge()) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onMatureCropBreak(BlockBreakEvent event) {
        if (!plugin.getConfig().getBoolean("farming.enabled", true)) return;

        Player player = event.getPlayer();
        // Shift + left-click is a deliberate removal: no replanting and no harvest XP.
        if (player.isSneaking()) return;

        Block block = event.getBlock();
        Material cropType = block.getType();
        if (!isSupportedCrop(cropType)) return;
        BlockData originalData = block.getBlockData().clone();
        if (!(originalData instanceof Ageable ageable)
                || ageable.getAge() < ageable.getMaximumAge()) return;

        Location harvestLocation = block.getLocation().add(0.5, 0.15, 0.5);
        Material plantingItem = plantingItemFor(cropType);
        int minXp = Math.max(0, plugin.getConfig().getInt("farming.xp-min", 1));
        int maxXp = Math.max(minXp, plugin.getConfig().getInt("farming.xp-max", 2));

        // Let vanilla finish its normal drop calculation first, preserving Fortune,
        // tool durability, and the ordinary harvest drops.
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (!player.isOnline()) return;
            if (harvestLocation.getWorld() == null
                    || !harvestLocation.getWorld().isChunkLoaded(
                    harvestLocation.getBlockX() >> 4, harvestLocation.getBlockZ() >> 4)) return;

            if (plugin.getConfig().getBoolean("farming.auto-replant", true)
                    && plantingItem != null
                    && player.getInventory().contains(plantingItem)
                    && canReplant(harvestLocation.getBlock(), cropType, originalData)) {
                Block target = harvestLocation.getBlock();
                target.setType(cropType, false);
                BlockData replantedData = originalData.clone();
                if (replantedData instanceof Ageable newAge) newAge.setAge(0);
                target.setBlockData(replantedData, false);

                // Consume the planting item only after a valid replant was placed.
                Map<Integer, ItemStack> overflow = player.getInventory()
                        .removeItem(new ItemStack(plantingItem, 1));
                // Inventory#contains was checked above; this is defensive in case
                // another plugin changed the inventory during the scheduled tick.
                if (!overflow.isEmpty()) {
                    target.setType(Material.AIR, false);
                    giveBackPlantingItem(player, plantingItem, harvestLocation);
                }
            }

            if (maxXp > 0) {
                int xp = minXp + (int) Math.floor(Math.random() * (maxXp - minXp + 1));
                if (xp > 0) {
                    ExperienceOrb orb = harvestLocation.getWorld()
                            .spawn(harvestLocation, ExperienceOrb.class);
                    orb.setExperience(xp);
                }
            }
        });
    }

    private boolean canReplant(Block target, Material crop, BlockData originalData) {
        if (!target.getType().isAir()) return false;

        if (crop == Material.COCOA) {
            if (!(originalData instanceof Cocoa cocoa)) return false;
            Block support = target.getRelative(cocoa.getFacing().getOppositeFace());
            return isJungleLog(support.getType());
        }

        Material ground = target.getRelative(BlockFace.DOWN).getType();
        return switch (crop) {
            case WHEAT, CARROTS, POTATOES, BEETROOTS,
                    TORCHFLOWER_CROP, PITCHER_CROP -> ground == Material.FARMLAND;
            case NETHER_WART -> ground == Material.SOUL_SAND;
            case SWEET_BERRY_BUSH -> isBerrySoil(ground);
            default -> false;
        };
    }

    private boolean isJungleLog(Material material) {
        return material == Material.JUNGLE_LOG || material == Material.JUNGLE_WOOD
                || material == Material.STRIPPED_JUNGLE_LOG
                || material == Material.STRIPPED_JUNGLE_WOOD;
    }

    private boolean isBerrySoil(Material material) {
        return switch (material) {
            case GRASS_BLOCK, DIRT, COARSE_DIRT, PODZOL, MYCELIUM,
                    ROOTED_DIRT, MOSS_BLOCK -> true;
            default -> false;
        };
    }

    private boolean isSupportedCrop(Material material) {
        return switch (material) {
            case WHEAT, CARROTS, POTATOES, BEETROOTS, NETHER_WART, COCOA,
                    SWEET_BERRY_BUSH, TORCHFLOWER_CROP, PITCHER_CROP -> true;
            default -> false;
        };
    }

    private Material plantingItemFor(Material crop) {
        return switch (crop) {
            case WHEAT -> Material.WHEAT_SEEDS;
            case CARROTS -> Material.CARROT;
            case POTATOES -> Material.POTATO;
            case BEETROOTS -> Material.BEETROOT_SEEDS;
            case NETHER_WART -> Material.NETHER_WART;
            case COCOA -> Material.COCOA_BEANS;
            case SWEET_BERRY_BUSH -> Material.SWEET_BERRIES;
            case TORCHFLOWER_CROP -> Material.TORCHFLOWER_SEEDS;
            case PITCHER_CROP -> Material.PITCHER_POD;
            default -> null;
        };
    }

    private void giveBackPlantingItem(Player player, Material material, Location location) {
        Map<Integer, ItemStack> overflow = player.getInventory().addItem(new ItemStack(material, 1));
        for (ItemStack item : overflow.values()) {
            location.getWorld().dropItemNaturally(location, item);
        }
    }
}
