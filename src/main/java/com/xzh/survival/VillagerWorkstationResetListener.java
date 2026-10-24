package com.xzh.survival;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Villager;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.inventory.MerchantRecipe;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Makes a villager's workstation break release its profession even after the
 * villager has been traded with. Only the nearest matching villager is reset,
 * because a workstation is normally claimed by one villager.
 */
public final class VillagerWorkstationResetListener implements Listener {
    private final XZHSurvivalPlugin plugin;
    private final Map<Material, Villager.Profession> workstationProfessions =
            new EnumMap<>(Material.class);

    public VillagerWorkstationResetListener(XZHSurvivalPlugin plugin) {
        this.plugin = plugin;
        workstationProfessions.put(Material.BLAST_FURNACE, Villager.Profession.ARMORER);
        workstationProfessions.put(Material.SMOKER, Villager.Profession.BUTCHER);
        workstationProfessions.put(Material.CARTOGRAPHY_TABLE, Villager.Profession.CARTOGRAPHER);
        workstationProfessions.put(Material.BREWING_STAND, Villager.Profession.CLERIC);
        workstationProfessions.put(Material.COMPOSTER, Villager.Profession.FARMER);
        workstationProfessions.put(Material.BARREL, Villager.Profession.FISHERMAN);
        workstationProfessions.put(Material.FLETCHING_TABLE, Villager.Profession.FLETCHER);
        workstationProfessions.put(Material.CAULDRON, Villager.Profession.LEATHERWORKER);
        workstationProfessions.put(Material.LECTERN, Villager.Profession.LIBRARIAN);
        workstationProfessions.put(Material.STONECUTTER, Villager.Profession.MASON);
        workstationProfessions.put(Material.LOOM, Villager.Profession.SHEPHERD);
        workstationProfessions.put(Material.SMITHING_TABLE, Villager.Profession.TOOLSMITH);
        workstationProfessions.put(Material.GRINDSTONE, Villager.Profession.WEAPONSMITH);
    }

    @EventHandler(ignoreCancelled = true)
    public void onWorkstationBreak(BlockBreakEvent event) {
        if (!plugin.getConfig().getBoolean("villager-workstation-reset.enabled", true)) return;

        Material workstation = event.getBlock().getType();
        Villager.Profession profession = workstationProfessions.get(workstation);
        if (profession == null) return;

        Location center = event.getBlock().getLocation().add(0.5, 0.5, 0.5);
        World world = center.getWorld();
        if (world == null) return;

        double radius = Math.max(1.0, Math.min(16.0,
                plugin.getConfig().getDouble("villager-workstation-reset.search-radius-blocks", 6.0)));
        Player breaker = event.getPlayer();

        // Wait until the block is actually gone so the villager cannot instantly
        // reclaim the workstation during the same block-break event.
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (event.getBlock().getType() == workstation) return;

            Villager closest = null;
            double closestDistanceSquared = radius * radius;
            for (Entity entity : world.getNearbyEntities(center, radius, radius, radius)) {
                if (!(entity instanceof Villager villager) || villager.getProfession() != profession) continue;
                double distanceSquared = villager.getLocation().distanceSquared(center);
                if (distanceSquared <= closestDistanceSquared) {
                    closest = villager;
                    closestDistanceSquared = distanceSquared;
                }
            }

            if (closest == null) return;

            closest.setProfession(Villager.Profession.NONE);
            closest.setRecipes(List.<MerchantRecipe>of());
            closest.setVillagerLevel(1);
            closest.setVillagerExperience(0);

            if (breaker.isOnline()) {
                breaker.sendMessage("§eVillager workstation removed: the nearest §f"
                        + profession.name().toLowerCase(java.util.Locale.ROOT).replace('_', ' ')
                        + " §evillager lost its profession, trades, level, and trading XP.");
            }
        });
    }
}
