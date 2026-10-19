package com.xzh.survival;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Villager;
import org.bukkit.entity.WanderingTrader;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.inventory.Merchant;
import org.bukkit.inventory.MerchantRecipe;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.UUID;

public final class DynamicTradingManager implements Listener {
    private final JavaPlugin plugin;
    private final Random random = new Random();
    private final Map<UUID, List<MerchantRecipe>> baseRecipes = new HashMap<>();
    private final Map<UUID, Long> refreshAt = new HashMap<>();
    private final NamespacedKey specialKey;
    private final NamespacedKey cycleKey;

    public DynamicTradingManager(JavaPlugin plugin) {
        this.plugin = plugin;
        specialKey = new NamespacedKey(plugin, "xzh_special_trade");
        cycleKey = new NamespacedKey(plugin, "xzh_trade_cycle");
    }

    public void start() {
        Bukkit.getPluginManager().registerEvents(this, plugin);
    }

    @EventHandler(ignoreCancelled = true)
    public void onTraderInteract(PlayerInteractEntityEvent event) {
        Entity entity = event.getRightClicked();
        if (!(entity instanceof Villager) && !(entity instanceof WanderingTrader)) return;
        if (!plugin.getConfig().getBoolean("dynamic-trading.enabled", true)) return;
        if (!(entity instanceof Merchant merchant)) return;

        UUID id = entity.getUniqueId();
        List<MerchantRecipe> base = baseRecipes.computeIfAbsent(id, ignored -> {
            List<MerchantRecipe> copy = new ArrayList<>();
            for (MerchantRecipe recipe : merchant.getRecipes()) {
                ItemMeta meta = recipe.getResult().getItemMeta();
                if (meta == null || !meta.getPersistentDataContainer().has(specialKey, PersistentDataType.BYTE)) copy.add(recipe);
            }
            return copy;
        });

        long now = System.currentTimeMillis();
        long expiry = refreshAt.getOrDefault(id, 0L);
        if (now >= expiry) {
            long minutes = Math.max(1, plugin.getConfig().getLong("dynamic-trading.refresh-minutes", 30));
            refreshAt.put(id, now + minutes * 60_000L);
            List<MerchantRecipe> recipes = new ArrayList<>(base);
            recipes.addAll(makeSpecialOffers(entity instanceof WanderingTrader));
            merchant.setRecipes(recipes);
            event.getPlayer().sendMessage("§d[XZH Trader] §7Special offers refreshed. Each special offer can be used once this cycle.");
        } else {
            // Keep the current cycle's use counts; never recreate offers just because a player reopens the menu.
            List<MerchantRecipe> current = merchant.getRecipes();
            boolean hasSpecial = current.stream().anyMatch(this::isSpecial);
            if (!hasSpecial) {
                List<MerchantRecipe> recipes = new ArrayList<>(base);
                recipes.addAll(makeSpecialOffers(entity instanceof WanderingTrader));
                merchant.setRecipes(recipes);
            }
        }
    }

    private List<MerchantRecipe> makeSpecialOffers(boolean wandering) {
        List<MerchantRecipe> offers = new ArrayList<>();
        // NPC buys selected goods from players and pays with authentic physical XZH coins.
        addOffer(offers, createOfficialCoin(1, 1), new ItemStack(Material.IRON_INGOT, 16));
        addOffer(offers, createOfficialCoin(10, 1), new ItemStack(Material.GOLD_INGOT, 4));
        addOffer(offers, createOfficialCoin(10, 1), new ItemStack(Material.DIAMOND, 1));

        // Limited rotating stock: a player can buy each special item once per refresh cycle.
        ItemStack stock = new ItemStack(wandering ? Material.SADDLE : Material.NAME_TAG, 1);
        ItemMeta meta = stock.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(wandering ? "§dWandering Trader's Limited Saddle" : "§dVillager's Limited Name Tag");
            meta.getPersistentDataContainer().set(specialKey, PersistentDataType.BYTE, (byte) 1);
            stock.setItemMeta(meta);
        }
        addOffer(offers, stock, createOfficialCoin(10, 1));
        ItemStack rare = new ItemStack(wandering ? Material.EXPERIENCE_BOTTLE : Material.ENCHANTED_BOOK, 3);
        ItemMeta rareMeta = rare.getItemMeta();
        if (rareMeta != null) {
            rareMeta.setDisplayName("§5Limited XZH Supply");
            rareMeta.getPersistentDataContainer().set(specialKey, PersistentDataType.BYTE, (byte) 1);
            rare.setItemMeta(rareMeta);
        }
        addOffer(offers, rare, createOfficialCoin(10, 1));
        return offers;
    }

    private void addOffer(List<MerchantRecipe> offers, ItemStack result, ItemStack cost) {
        if (result == null || result.getType().isAir() || cost == null || cost.getType().isAir()) return;
        ItemMeta meta = result.getItemMeta();
        if (meta != null) {
            meta.getPersistentDataContainer().set(specialKey, PersistentDataType.BYTE, (byte) 1);
            result.setItemMeta(meta);
        }
        MerchantRecipe recipe = new MerchantRecipe(result, 1);
        recipe.setIngredients(List.of(cost));
        recipe.setMaxUses(1);
        recipe.setUses(0);
        recipe.setExperienceReward(false);
        offers.add(recipe);
    }

    private boolean isSpecial(MerchantRecipe recipe) {
        ItemMeta meta = recipe.getResult().getItemMeta();
        return meta != null && meta.getPersistentDataContainer().has(specialKey, PersistentDataType.BYTE);
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
            plugin.getLogger().warning("Could not create trader XZH coin: " + ex.getClass().getSimpleName());
            return null;
        }
    }
}
