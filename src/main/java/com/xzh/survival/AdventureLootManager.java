package com.xzh.survival;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Item;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.ItemSpawnEvent;
import org.bukkit.event.entity.PiglinBarterEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.player.PlayerFishEvent;
import org.bukkit.event.player.PlayerAttemptPickupItemEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.*;

public final class AdventureLootManager implements Listener {
    private final JavaPlugin plugin;
    private final Random random = new Random();
    private final NamespacedKey mobLootKey;
    private final Set<UUID> announcedMobDrops = new HashSet<>();

    public AdventureLootManager(JavaPlugin plugin) {
        this.plugin = plugin;
        mobLootKey = new NamespacedKey(plugin, "mob_loot_pickup");
    }

    public void start() {
        Bukkit.getPluginManager().registerEvents(this, plugin);
    }

    @EventHandler(ignoreCancelled = true)
    public void onMobDeath(EntityDeathEvent event) {
        if (!plugin.getConfig().getBoolean("adventure-loot.enabled", true)
                || !plugin.getConfig().getBoolean("adventure-loot.mob-loot.enabled", true)) return;
        LivingEntity mob = event.getEntity();
        if (mob.getKiller() == null) return;
        double rare = plugin.getConfig().getDouble("adventure-loot.mob-loot.rare-extra-chance", 0.025);
        double common = plugin.getConfig().getDouble("adventure-loot.mob-loot.common-extra-chance", 0.12);
        if (random.nextDouble() < common) {
            Material[] commonLoot = {Material.IRON_INGOT, Material.GOLD_NUGGET, Material.COPPER_INGOT,
                    Material.LAPIS_LAZULI, Material.STRING, Material.LEATHER, Material.BREAD};
            event.getDrops().add(new ItemStack(commonLoot[random.nextInt(commonLoot.length)],
                    1 + random.nextInt(3)));
        }
        if (random.nextDouble() < rare) {
            Material[] rareLoot = {Material.DIAMOND, Material.EMERALD, Material.BOOK, Material.GOLD_INGOT,
                    Material.IRON_BLOCK, Material.OBSIDIAN, Material.EXPERIENCE_BOTTLE};
            event.getDrops().add(new ItemStack(rareLoot[random.nextInt(rareLoot.length)], 1));
        }
        if (random.nextDouble() < plugin.getConfig().getDouble("adventure-loot.mob-loot.extreme-rare-chance", 0.001)) {
            Material[] extreme = {Material.ENCHANTED_BOOK, Material.DIAMOND, Material.NETHERITE_SCRAP,
                    Material.GOLD_BLOCK, Material.DIAMOND_BLOCK};
            event.getDrops().add(new ItemStack(extreme[random.nextInt(extreme.length)], 1));
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onFishing(PlayerFishEvent event) {
        if (event.getState() != PlayerFishEvent.State.CAUGHT_FISH
                || !plugin.getConfig().getBoolean("adventure-loot.fishing.enabled", true)) return;
        if (!(event.getCaught() instanceof Item caught)) return;
        double bonusChance = plugin.getConfig().getDouble("adventure-loot.fishing.bonus-reward-chance", 0.20);
        if (random.nextDouble() >= bonusChance) return;
        Material reward;
        double roll = random.nextDouble();
        if (roll < plugin.getConfig().getDouble("adventure-loot.fishing.extreme-rare-chance", 0.001)) {
            Material[] extreme = {Material.ENCHANTED_BOOK, Material.DIAMOND, Material.NETHERITE_SCRAP};
            reward = extreme[random.nextInt(extreme.length)];
        } else if (roll < plugin.getConfig().getDouble("adventure-loot.fishing.rare-reward-chance", 0.035)) {
            Material[] rare = {Material.NAME_TAG, Material.SADDLE, Material.ENCHANTED_BOOK,
                    Material.GOLD_INGOT, Material.DIAMOND};
            reward = rare[random.nextInt(rare.length)];
        } else {
            Material[] ordinary = {Material.IRON_INGOT, Material.GOLD_NUGGET, Material.STRING,
                    Material.LEATHER, Material.BONE, Material.BOOK, Material.COPPER_INGOT};
            reward = ordinary[random.nextInt(ordinary.length)];
        }
        Item extra = event.getPlayer().getWorld().dropItemNaturally(caught.getLocation(), new ItemStack(reward));
        markMobLoot(extra);
    }

    @EventHandler(ignoreCancelled = true)
    public void onPickup(PlayerAttemptPickupItemEvent event) {
        Item item = event.getItem();
        ItemStack stack = item.getItemStack();
        if (isXzhCoin(stack)) {
            int denomination = getCoinDenomination(stack);
            event.getPlayer().sendMessage("§6[XZH] §a+" + stack.getAmount() + " × " + denomination + " XZH Coin"
                    + (stack.getAmount() == 1 ? "" : "s") + " §7(+" + ((long) denomination * stack.getAmount()) + " total)");
            return;
        }
        if (item.getPersistentDataContainer().has(mobLootKey, PersistentDataType.BYTE)
                && announcedMobDrops.add(item.getUniqueId())) {
            event.getPlayer().sendMessage("§a[Loot] §f+" + stack.getAmount() + " " + prettyName(stack.getType()));
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onItemSpawn(ItemSpawnEvent event) {
        // Only item entities tagged by our own mob/fishing bonus logic are announced as loot.
        // Vanilla mob drops are marked by the death event below through a short scheduled scan.
    }

    @EventHandler(ignoreCancelled = true)
    public void onPiglinBarter(PiglinBarterEvent event) {
        if (!plugin.getConfig().getBoolean("adventure-loot.enabled", true)
                || random.nextDouble() >= plugin.getConfig().getDouble("adventure-loot.exploration.piglin-barter-coin-chance", 0.04)) return;
        event.getOutcome().add(new ItemStack(Material.GOLD_NUGGET, 1 + random.nextInt(3)));
    }

    private void markMobLoot(Item item) {
        item.getPersistentDataContainer().set(mobLootKey, PersistentDataType.BYTE, (byte) 1);
    }

    private boolean isXzhCoin(ItemStack stack) {
        if (stack == null || !stack.hasItemMeta()) return false;
        var pdc = stack.getItemMeta().getPersistentDataContainer();
        return pdc.getKeys().stream().anyMatch(key -> key.getNamespace().equals("xzheconomysystem")
                && key.getKey().equals("coin_denomination"));
    }

    private int getCoinDenomination(ItemStack stack) {
        for (var key : stack.getItemMeta().getPersistentDataContainer().getKeys()) {
            if (key.getNamespace().equals("xzheconomysystem") && key.getKey().equals("coin_denomination")) {
                Integer value = stack.getItemMeta().getPersistentDataContainer().get(key, PersistentDataType.INTEGER);
                if (value != null) return value;
            }
        }
        return 1;
    }

    private String prettyName(Material material) {
        String[] parts = material.name().toLowerCase(Locale.ROOT).split("_");
        StringJoiner name = new StringJoiner(" ");
        for (String part : parts) name.add(Character.toUpperCase(part.charAt(0)) + part.substring(1));
        return name.toString();
    }
}
