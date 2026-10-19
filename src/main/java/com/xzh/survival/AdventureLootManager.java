package com.xzh.survival;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.inventory.meta.EnchantmentStorageMeta;
import org.bukkit.block.Chest;
import org.bukkit.block.BlockState;
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
import org.bukkit.plugin.Plugin;
import java.lang.reflect.Method;

import java.util.*;

public final class AdventureLootManager implements Listener {
    private final JavaPlugin plugin;
    private final Random random = new Random();
    private final NamespacedKey mobLootKey;
    private final NamespacedKey villageChestCheckedKey;
    private final NamespacedKey monsterLevelKey;
    private final Set<UUID> announcedMobDrops = new HashSet<>();

    public AdventureLootManager(JavaPlugin plugin) {
        this.plugin = plugin;
        mobLootKey = new NamespacedKey(plugin, "mob_loot_pickup");
        villageChestCheckedKey = new NamespacedKey(plugin, "village_coin_checked");
        monsterLevelKey = new NamespacedKey(plugin, "monster_level");
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
        // Tag vanilla mob drops one tick later so each dropped item gets a first-pickup notice.
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (!mob.getWorld().isChunkLoaded(mob.getLocation().getBlockX() >> 4, mob.getLocation().getBlockZ() >> 4)) return;
            for (Entity nearby : mob.getNearbyEntities(4.0, 4.0, 4.0)) {
                if (nearby instanceof Item dropped && !isXzhCoin(dropped.getItemStack())) markMobLoot(dropped);
            }
        });
        int mobLevel = Math.max(1, mob.getPersistentDataContainer().getOrDefault(monsterLevelKey, PersistentDataType.INTEGER, 1));
        double rare = Math.min(0.08, plugin.getConfig().getDouble("adventure-loot.mob-loot.rare-extra-chance", 0.025) + (mobLevel - 1) * 0.0003);
        double common = Math.min(0.30, plugin.getConfig().getDouble("adventure-loot.mob-loot.common-extra-chance", 0.12) + (mobLevel - 1) * 0.001);
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
        double coinChance = Math.min(0.30, plugin.getConfig().getDouble("adventure-loot.mob-loot.coin-drop-chance", 0.15) + (mobLevel - 1) * 0.001);
        if (random.nextDouble() < coinChance) {
            ItemStack coin = rollCoinReward("adventure-loot.mob-loot");
            if (coin != null) event.getDrops().add(coin);
        }
        double extremeChance = Math.min(0.002, plugin.getConfig().getDouble("adventure-loot.mob-loot.extreme-rare-chance", 0.001) + (mobLevel - 1) * 0.00001);
        if (random.nextDouble() < extremeChance) {
            Material[] extreme = {Material.ENCHANTED_BOOK, Material.DIAMOND, Material.NETHERITE_SCRAP,
                    Material.GOLD_BLOCK, Material.DIAMOND_BLOCK};
            event.getDrops().add(createLootItem(extreme[random.nextInt(extreme.length)]));
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onFishing(PlayerFishEvent event) {
        if (event.getState() != PlayerFishEvent.State.CAUGHT_FISH
                || !plugin.getConfig().getBoolean("adventure-loot.fishing.enabled", true)) return;
        if (!(event.getCaught() instanceof Item caught)) return;
        double coinChance = plugin.getConfig().getDouble("adventure-loot.fishing.coin-chance", 0.06);
        if (random.nextDouble() < coinChance) {
            ItemStack coin = rollCoinReward("adventure-loot.mob-loot");
            if (coin != null) event.getPlayer().getWorld().dropItemNaturally(caught.getLocation(), coin);
        }
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
        Item extra = event.getPlayer().getWorld().dropItemNaturally(caught.getLocation(), createLootItem(reward));
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
    public void onVillageChestOpen(InventoryOpenEvent event) {
        if (!(event.getPlayer() instanceof Player player)
                || !plugin.getConfig().getBoolean("adventure-loot.enabled", true)) return;
        if (!plugin.getConfig().getBoolean("adventure-loot.exploration.village-chest-rewards-enabled", true)) return;
        if (event.getInventory().getLocation() == null) return;
        BlockState state = event.getInventory().getLocation().getBlock().getState();
        if (!(state instanceof Chest chest)) return;
        if (chest.getPersistentDataContainer().has(villageChestCheckedKey, PersistentDataType.BYTE)) return;

        boolean villageNearby = chest.getWorld().getNearbyEntities(chest.getLocation(), 32, 16, 32).stream()
                .anyMatch(entity -> entity instanceof org.bukkit.entity.Villager);
        if (!villageNearby) return;
        // Mark every checked village chest, including misses, to prevent rerolls by reopening it.
        chest.getPersistentDataContainer().set(villageChestCheckedKey, PersistentDataType.BYTE, (byte) 1);
        chest.update(true, false);
        double chance = plugin.getConfig().getDouble("adventure-loot.exploration.village-chest-coin-chance", 0.12);
        if (random.nextDouble() >= chance) return;
        ItemStack coin = rollCoinReward("adventure-loot.mob-loot");
        if (coin == null) return;
        Map<Integer, ItemStack> overflow = chest.getBlockInventory().addItem(coin);
        for (ItemStack item : overflow.values()) chest.getWorld().dropItemNaturally(chest.getLocation(), item);
        player.sendMessage("§6[XZH] §eYou discovered XZH coins hidden in this village chest!");
    }

    @EventHandler(ignoreCancelled = true)
    public void onItemSpawn(ItemSpawnEvent event) {
        // Vanilla mob drops are tagged by the one-tick scan scheduled in onMobDeath.
    }

    @EventHandler(ignoreCancelled = true)
    public void onPiglinBarter(PiglinBarterEvent event) {
        if (!plugin.getConfig().getBoolean("adventure-loot.enabled", true)
                || random.nextDouble() >= plugin.getConfig().getDouble("adventure-loot.exploration.piglin-barter-coin-chance", 0.04)) return;
        ItemStack coin = rollCoinReward("adventure-loot.mob-loot");
        if (coin != null) event.getOutcome().add(coin);
    }

    private ItemStack createLootItem(Material material) {
        ItemStack item = new ItemStack(material);
        if (material == Material.ENCHANTED_BOOK && item.getItemMeta() instanceof EnchantmentStorageMeta book) {
            Enchantment[] choices = {Enchantment.MENDING, Enchantment.UNBREAKING, Enchantment.EFFICIENCY,
                    Enchantment.FORTUNE, Enchantment.SILK_TOUCH, Enchantment.PROTECTION, Enchantment.SHARPNESS,
                    Enchantment.LOOTING, Enchantment.POWER, Enchantment.FEATHER_FALLING};
            Enchantment enchantment = choices[random.nextInt(choices.length)];
            int level = 1 + random.nextInt(Math.max(1, enchantment.getMaxLevel()));
            book.addStoredEnchant(enchantment, level, true);
            item.setItemMeta(book);
        }
        return item;
    }

    private ItemStack rollCoinReward(String path) {
        // Higher denominations are intentionally rare; the economy plugin creates the authentic coin.
        double jackpot = plugin.getConfig().getDouble(path + ".coin-1000-chance", 0.0001);
        double hundred = plugin.getConfig().getDouble(path + ".coin-100-chance", 0.001);
        double ten = plugin.getConfig().getDouble(path + ".coin-10-chance", 0.025);
        double roll = random.nextDouble();
        int denomination = roll < jackpot ? 1000 : roll < jackpot + hundred ? 100 : roll < jackpot + hundred + ten ? 10 : 1;
        int amount = random.nextDouble() < plugin.getConfig().getDouble(path + ".multiple-coins-chance", 0.005) ? 2 + random.nextInt(3) : 1;
        return createOfficialCoin(denomination, amount);
    }

    private ItemStack createOfficialCoin(int denomination, int amount) {
        Plugin economy = Bukkit.getPluginManager().getPlugin("XZHEconomySystem");
        if (economy == null || !economy.isEnabled()) return null;
        try {
            Object manager = economy.getClass().getMethod("getPocketCoinManager").invoke(economy);
            Method create = manager.getClass().getDeclaredMethod("createCoin", int.class, int.class);
            create.setAccessible(true);
            return (ItemStack) create.invoke(manager, denomination, amount);
        } catch (ReflectiveOperationException | RuntimeException ex) {
            plugin.getLogger().warning("Could not create official XZH coin loot: " + ex.getClass().getSimpleName());
            return null;
        }
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
