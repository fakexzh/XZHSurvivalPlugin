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
import org.bukkit.event.world.LootGenerateEvent;
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
    private final NamespacedKey fishingQuestProgressKey;
    private final NamespacedKey pickupAnnouncedKey;
    private final NamespacedKey lootSourceKey;

    public AdventureLootManager(JavaPlugin plugin) {
        this.plugin = plugin;
        mobLootKey = new NamespacedKey("xzhsurvivalplugin", "mob_loot_pickup");
        villageChestCheckedKey = new NamespacedKey("xzhsurvivalplugin", "village_coin_checked");
        monsterLevelKey = new NamespacedKey("xzhsurvivalplugin", "monster_level");
        fishingQuestProgressKey = new NamespacedKey("xzhsurvivalplugin", "fishing_quest_progress");
        pickupAnnouncedKey = new NamespacedKey("xzhsurvivalplugin", "loot_pickup_announced");
        lootSourceKey = new NamespacedKey("xzhsurvivalplugin", "loot_source");
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

        // Preserve every vanilla mob drop. Survival adds only the configured tagged coin reward.
        ItemStack coin = rollCoinReward("adventure-loot.mob-loot");
        if (coin != null) event.getDrops().add(coin);

        org.bukkit.Location deathLocation = mob.getLocation().clone();
        org.bukkit.World deathWorld = mob.getWorld();
        List<ItemStack> expectedDrops = event.getDrops().stream().map(ItemStack::clone)
                .filter(stack -> !stack.getType().isAir()).toList();
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (!deathWorld.isChunkLoaded(deathLocation.getBlockX() >> 4, deathLocation.getBlockZ() >> 4)) return;
            for (Entity nearby : deathWorld.getNearbyEntities(deathLocation, 3.0, 3.0, 3.0)) {
                if (!(nearby instanceof Item dropped)
                        || dropped.getLocation().distanceSquared(deathLocation) > 9.0) continue;
                ItemStack actual = dropped.getItemStack();
                if (expectedDrops.stream().anyMatch(expected -> expected.isSimilar(actual))) {
                    if (isXzhCoin(actual)) markLootSource(dropped, "mob");
                    else markMobLoot(dropped);
                }
            }
        });
    }

    @EventHandler(ignoreCancelled = true)
    public void onFishing(PlayerFishEvent event) {
        if (event.getState() != PlayerFishEvent.State.CAUGHT_FISH) return;
        if (!(event.getCaught() instanceof Item caught)) return;
        progressFishingQuest(event.getPlayer(), caught.getLocation());
        if (!plugin.getConfig().getBoolean("adventure-loot.fishing.enabled", true)) return;

        // Keep the complete vanilla catch (fish, junk and treasure) unchanged.
        event.getPlayer().sendMessage("§b§l🎣 FISHING §8» §fCaught §e" + caught.getItemStack().getAmount()
                + "x §f" + prettyName(caught.getItemStack().getType()) + "§b!");
        ItemStack coin = rollCoinReward("adventure-loot.fishing");
        if (coin != null) {
            Item coinDrop = event.getPlayer().getWorld().dropItemNaturally(caught.getLocation(), coin);
            markLootSource(coinDrop, "fishing");
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onPickup(PlayerAttemptPickupItemEvent event) {
        Item item = event.getItem();
        ItemStack stack = item.getItemStack();
        if (isXzhCoin(stack)) {
            int denomination = getCoinDenomination(stack);
            long collectedValue = (long) denomination * stack.getAmount();
            long totalCoins = getInventoryCoinTotal(event.getPlayer()) + collectedValue;
            event.getPlayer().sendMessage("§f" + stack.getAmount() + "x §e"
                    + String.format(Locale.US, "%,d", collectedValue) + " coins collected §8| §7("
                    + String.format(Locale.US, "%,d", totalCoins) + " coins total§7)");
            return;
        }
        // Regular mob loot is intentionally silent. Fishing catches and bonus items are announced at catch time.
    }

    @EventHandler
    public void onBuriedTreasureLootGenerate(LootGenerateEvent event) {
        if (!plugin.getConfig().getBoolean("adventure-loot.enabled", true)) return;
        if (event.getLootTable() == null || event.getLootTable().getKey() == null
                || !event.getLootTable().getKey().getKey().endsWith("buried_treasure")) return;
        ItemStack hundredCoins = createOfficialCoin(100, 2 + random.nextInt(7));
        ItemStack thousandCoins = createOfficialCoin(1000, 1);
        if (hundredCoins != null) event.getLoot().add(hundredCoins);
        if (thousandCoins != null) event.getLoot().add(thousandCoins);
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
        player.sendMessage("§6§eYou discovered coins hidden in this village chest!");
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

    private void progressFishingQuest(Player player, org.bukkit.Location location) {
        if (!plugin.getConfig().getBoolean("adventure-loot.fishing.quest-enabled", true)) return;
        int goal = Math.max(1, plugin.getConfig().getInt("adventure-loot.fishing.quest-catches", 10));
        int progress = player.getPersistentDataContainer().getOrDefault(fishingQuestProgressKey, PersistentDataType.INTEGER, 0) + 1;
        if (progress >= goal) {
            player.getPersistentDataContainer().set(fishingQuestProgressKey, PersistentDataType.INTEGER, 0);
            ItemStack reward = rollCoinReward("adventure-loot.fishing");
            if (reward != null) {
                Item coinDrop = player.getWorld().dropItemNaturally(location, reward);
                markLootSource(coinDrop, "fishing");
            }
            player.sendMessage("§b[Fishing Quest] §aComplete! You caught " + goal + " fish. A coin reward is waiting nearby.");
        } else {
            player.getPersistentDataContainer().set(fishingQuestProgressKey, PersistentDataType.INTEGER, progress);
            if (progress % 5 == 0) player.sendMessage("§b[Fishing Quest] §fProgress: " + progress + "/" + goal + " catches.");
        }
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
        double thousandChance = Math.max(0.0, Math.min(1.0,
                plugin.getConfig().getDouble(path + ".coin-1000-chance", 0.01)));
        double hundredChance = Math.max(0.0, Math.min(1.0 - thousandChance,
                plugin.getConfig().getDouble(path + ".coin-100-chance", 0.10)));
        double roll = random.nextDouble();
        if (roll < thousandChance) return createOfficialCoin(1000, 1);
        if (roll < thousandChance + hundredChance) return createOfficialCoin(100, 1);
        return null;
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
            plugin.getLogger().warning("Could not create coin loot: " + ex.getClass().getSimpleName());
            return null;
        }
    }

    private void markMobLoot(Item item) {
        item.getPersistentDataContainer().set(mobLootKey, PersistentDataType.BYTE, (byte) 1);
    }

    private void markLootSource(Item item, String source) {
        item.getPersistentDataContainer().set(lootSourceKey, PersistentDataType.STRING, source);
    }

    private boolean isXzhCoin(ItemStack stack) {
        if (stack == null || !stack.hasItemMeta()) return false;
        var pdc = stack.getItemMeta().getPersistentDataContainer();
        return pdc.getKeys().stream().anyMatch(key -> key.getNamespace().equals("xzheconomysystem")
                && key.getKey().equals("coin_denomination"));
    }

    private long getInventoryCoinTotal(Player player) {
        long total = 0L;
        for (ItemStack inventoryItem : player.getInventory().getContents()) {
            if (isXzhCoin(inventoryItem)) {
                total += (long) getCoinDenomination(inventoryItem) * inventoryItem.getAmount();
            }
        }
        return total;
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
