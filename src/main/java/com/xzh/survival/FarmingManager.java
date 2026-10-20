package com.xzh.survival;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.data.Ageable;
import org.bukkit.entity.ExperienceOrb;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.Map;

/**
 * Small farming quality-of-life features: replant mature crops from their
 * harvest drops and award a tiny vanilla XP orb for successful harvests.
 */
public final class FarmingManager implements Listener {
    private final JavaPlugin plugin;

    public FarmingManager(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    public void start() {
        Bukkit.getPluginManager().registerEvents(this, plugin);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onMatureCropBreak(BlockBreakEvent event) {
        if (!plugin.getConfig().getBoolean("farming.enabled", true)) return;

        Block block = event.getBlock();
        if (!(block.getBlockData() instanceof Ageable ageable)) return;
        if (!isSupportedCrop(block.getType()) || ageable.getAge() < ageable.getMaximumAge()) return;

        Player player = event.getPlayer();
        Location harvestLocation = block.getLocation().add(0.5, 0.15, 0.5);
        Material cropType = block.getType();
        int minXp = Math.max(0, plugin.getConfig().getInt("farming.xp-min", 1));
        int maxXp = Math.max(minXp, plugin.getConfig().getInt("farming.xp-max", 2));

        // Run after vanilla has removed the crop and spawned its actual drops.
        // This preserves normal Fortune, tool durability, and drop behavior.
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (!player.isOnline() || !harvestLocation.getWorld().isChunkLoaded(
                    harvestLocation.getBlockX() >> 4, harvestLocation.getBlockZ() >> 4)) return;

            if (plugin.getConfig().getBoolean("farming.auto-replant", true)) {
                Material plantingItem = plantingItemFor(cropType);
                if (plantingItem != null) {
                    Item harvestedPlantingItem = findHarvestDrop(harvestLocation, plantingItem);
                    if (harvestedPlantingItem != null) {
                        ItemStack stack = harvestedPlantingItem.getItemStack();
                        if (stack.getAmount() <= 1) {
                            harvestedPlantingItem.remove();
                        } else {
                            stack.setAmount(stack.getAmount() - 1);
                            harvestedPlantingItem.setItemStack(stack);
                        }
                        Block replanted = harvestLocation.getBlock();
                        if (replanted.getType().isAir()) {
                            replanted.setType(cropType, false);
                            if (replanted.getBlockData() instanceof Ageable newAge) {
                                newAge.setAge(0);
                                replanted.setBlockData(newAge, false);
                            }
                        } else {
                            // Do not consume a seed if another plugin/player filled the spot.
                            giveBackPlantingItem(player, plantingItem, harvestLocation);
                        }
                    }
                }
            }

            if (maxXp > 0) {
                int xp = minXp + (int) Math.floor(Math.random() * (maxXp - minXp + 1));
                if (xp > 0) {
                    ExperienceOrb orb = harvestLocation.getWorld().spawn(harvestLocation, ExperienceOrb.class);
                    orb.setExperience(xp);
                }
            }
        });
    }

    private boolean isSupportedCrop(Material material) {
        return switch (material) {
            case WHEAT, CARROTS, POTATOES, BEETROOTS, NETHER_WART, COCOA -> true;
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
            default -> null;
        };
    }

    private Item findHarvestDrop(Location center, Material material) {
        return center.getWorld().getNearbyEntities(center, 1.35, 1.0, 1.35).stream()
                .filter(entity -> entity instanceof Item)
                .map(entity -> (Item) entity)
                .filter(item -> item.getItemStack().getType() == material)
                .filter(item -> item.getLocation().distanceSquared(center) <= 2.0)
                .findFirst().orElse(null);
    }

    private void giveBackPlantingItem(Player player, Material material, Location location) {
        Map<Integer, ItemStack> overflow = player.getInventory().addItem(new ItemStack(material, 1));
        for (ItemStack item : overflow.values()) {
            location.getWorld().dropItemNaturally(location, item);
        }
    }
}
