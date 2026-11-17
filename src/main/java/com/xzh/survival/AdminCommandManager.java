package com.xzh.survival;

import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCreativeEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.InventoryType;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

public final class AdminCommandManager implements Listener, CommandExecutor, TabCompleter {
    private static final String GAME_MODE_PERMISSION = "survival.admin.gamemode";
    private static final String WEATHER_PERMISSION = "survival.admin.weather";
    private static final long WEATHER_DURATION_TICKS = 12_000L;
    private static final Set<Material> DEFAULT_RESTRICTED_ITEMS = Set.of(
            Material.DIAMOND_BLOCK, Material.IRON_BLOCK, Material.NETHERITE_BLOCK,
            Material.BEACON, Material.ANVIL, Material.CHIPPED_ANVIL, Material.DAMAGED_ANVIL
    );

    private final JavaPlugin plugin;
    private final File playerDataFolder;
    private final Set<Material> restrictedCreativeItems;

    public AdminCommandManager(JavaPlugin plugin) {
        this.plugin = plugin;
        this.playerDataFolder = new File(plugin.getDataFolder(), "gamemode-data");
        Set<Material> configured = plugin.getConfig()
                .getStringList("creative-restrictions.blocked-items")
                .stream()
                .map(Material::matchMaterial)
                .filter(material -> material != null)
                .collect(Collectors.toUnmodifiableSet());
        this.restrictedCreativeItems = configured.isEmpty() ? DEFAULT_RESTRICTED_ITEMS : configured;

        Bukkit.getPluginManager().registerEvents(this, plugin);
        register("gmc");
        register("gms");
        register("gmsp");
        register("sun");
        register("rain");
        register("thunder");
    }

    private void register(String name) {
        if (plugin.getCommand(name) == null) {
            plugin.getLogger().severe("Command /" + name + " is missing from plugin.yml");
            return;
        }
        plugin.getCommand(name).setExecutor(this);
        plugin.getCommand(name).setTabCompleter(this);
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        String name = command.getName().toLowerCase(Locale.ROOT);
        if (name.equals("sun") || name.equals("rain") || name.equals("thunder")) {
            return handleWeather(sender, name, args);
        }
        return handleGameMode(sender, name, args);
    }

    private boolean handleWeather(CommandSender sender, String command, String[] args) {
        if (!sender.hasPermission(WEATHER_PERMISSION)) {
            sender.sendMessage("§cYou do not have permission to change the weather.");
            return true;
        }
        if (args.length != 0) {
            sender.sendMessage("§cUsage: /" + command);
            return true;
        }

        List<World> worlds = new ArrayList<>();
        if (sender instanceof Player player) worlds.add(player.getWorld());
        else worlds.addAll(Bukkit.getWorlds());
        if (worlds.isEmpty()) {
            sender.sendMessage("§cNo worlds are currently loaded.");
            return true;
        }

        for (World world : worlds) {
            switch (command) {
                case "sun" -> {
                    world.setStorm(false);
                    world.setThundering(false);
                    world.setWeatherDuration((int) WEATHER_DURATION_TICKS);
                    world.setClearWeatherDuration((int) WEATHER_DURATION_TICKS);
                }
                case "rain" -> {
                    world.setStorm(true);
                    world.setThundering(false);
                    world.setWeatherDuration((int) WEATHER_DURATION_TICKS);
                }
                case "thunder" -> {
                    world.setStorm(true);
                    world.setThundering(true);
                    world.setWeatherDuration((int) WEATHER_DURATION_TICKS);
                }
                default -> {
                    return false;
                }
            }
        }
        String state = switch (command) {
            case "sun" -> "clear weather";
            case "rain" -> "rain";
            default -> "thunder";
        };
        sender.sendMessage("§aSet " + state + " in " + worlds.size()
                + (worlds.size() == 1 ? " world." : " worlds."));
        return true;
    }

    private boolean handleGameMode(CommandSender sender, String command, String[] args) {
        if (!sender.hasPermission(GAME_MODE_PERMISSION)) {
            sender.sendMessage("§cYou do not have permission to change gamemodes.");
            return true;
        }
        if (args.length > 1) {
            sender.sendMessage("§cUsage: /" + command + " [player]");
            return true;
        }

        Player target;
        if (args.length == 1) {
            target = Bukkit.getPlayerExact(args[0]);
            if (target == null) {
                sender.sendMessage("§cThat player is not online.");
                return true;
            }
        } else if (sender instanceof Player player) {
            target = player;
        } else {
            sender.sendMessage("§cUsage: /" + command + " <player>");
            return true;
        }

        boolean changed = switch (command) {
            case "gmc" -> enterCreative(target);
            case "gms" -> enterSurvival(target);
            case "gmsp" -> enterSpectator(target);
            default -> false;
        };
        if (!changed) {
            sender.sendMessage("§cGamemode was not changed because inventory data could not be saved.");
            return true;
        }

        String mode = switch (command) {
            case "gmc" -> "Creative";
            case "gms" -> "Survival";
            default -> "Spectator";
        };
        sender.sendMessage("§a" + target.getName() + " is now in " + mode + " mode.");
        if (!sender.equals(target)) target.sendMessage("§eYour gamemode has been changed to " + mode + ".");
        return true;
    }

    private boolean enterCreative(Player player) {
        if (player.getGameMode() == GameMode.SURVIVAL || player.getGameMode() == GameMode.ADVENTURE) {
            if (!saveInventory(player, "survival")) return false;
        } else if (player.getGameMode() == GameMode.CREATIVE) {
            if (!saveInventory(player, "creative")) return false;
        }
        player.getInventory().clear();
        player.getInventory().setArmorContents(new ItemStack[4]);
        player.getInventory().setItemInOffHand(null);
        loadInventory(player, "creative");
        player.setGameMode(GameMode.CREATIVE);
        return saveLastMode(player, "creative");
    }

    private boolean enterSurvival(Player player) {
        if (player.getGameMode() == GameMode.CREATIVE) {
            if (!saveInventory(player, "creative")) return false;
        } else if (player.getGameMode() == GameMode.SURVIVAL || player.getGameMode() == GameMode.ADVENTURE) {
            if (!saveInventory(player, "survival")) return false;
        }
        player.getInventory().clear();
        player.getInventory().setArmorContents(new ItemStack[4]);
        player.getInventory().setItemInOffHand(null);
        loadInventory(player, "survival");
        player.setGameMode(GameMode.SURVIVAL);
        return saveLastMode(player, "survival");
    }

    private boolean enterSpectator(Player player) {
        if (player.getGameMode() == GameMode.SPECTATOR) return true;
        String active = player.getGameMode() == GameMode.CREATIVE ? "creative" : "survival";
        if (!saveInventory(player, active) || !saveLastMode(player, active)) return false;
        player.getInventory().clear();
        player.getInventory().setArmorContents(new ItemStack[4]);
        player.getInventory().setItemInOffHand(null);
        player.setGameMode(GameMode.SPECTATOR);
        return true;
    }

    private File dataFile(Player player) {
        if (!playerDataFolder.exists() && !playerDataFolder.mkdirs()) {
            throw new IllegalStateException("Could not create gamemode data folder.");
        }
        return new File(playerDataFolder, player.getUniqueId() + ".yml");
    }

    private YamlConfiguration loadData(Player player) {
        return YamlConfiguration.loadConfiguration(dataFile(player));
    }

    private boolean saveData(Player player, YamlConfiguration data) {
        try {
            data.save(dataFile(player));
            return true;
        } catch (IOException | RuntimeException exception) {
            plugin.getLogger().severe("Could not save isolated inventories for " + player.getName()
                    + ": " + exception.getMessage());
            player.sendMessage("§cYour separated inventory data could not be saved. Notify an administrator.");
            return false;
        }
    }

    private boolean saveInventory(Player player, String inventoryName) {
        YamlConfiguration data = loadData(player);
        String path = "inventories." + inventoryName;
        data.set(path + ".storage", Arrays.asList(player.getInventory().getStorageContents()));
        data.set(path + ".armor", Arrays.asList(player.getInventory().getArmorContents()));
        data.set(path + ".offhand", player.getInventory().getItemInOffHand());
        data.set("inventory-initialized." + inventoryName, true);
        return saveData(player, data);
    }

    private void loadInventory(Player player, String inventoryName) {
        YamlConfiguration data = loadData(player);
        String path = "inventories." + inventoryName;
        List<?> stored = data.getList(path + ".storage");
        ItemStack[] storage = new ItemStack[36];
        if (stored != null) {
            for (int i = 0; i < Math.min(storage.length, stored.size()); i++) {
                Object item = stored.get(i);
                if (item instanceof ItemStack stack) storage[i] = stack.clone();
            }
        }

        List<?> storedArmor = data.getList(path + ".armor");
        ItemStack[] armor = new ItemStack[4];
        if (storedArmor != null) {
            for (int i = 0; i < Math.min(armor.length, storedArmor.size()); i++) {
                Object item = storedArmor.get(i);
                if (item instanceof ItemStack stack) armor[i] = stack.clone();
            }
        }

        ItemStack offhand = data.getItemStack(path + ".offhand");
        player.getInventory().clear();
        player.getInventory().setStorageContents(storage);
        player.getInventory().setArmorContents(armor);
        player.getInventory().setItemInOffHand(offhand == null ? null : offhand.clone());
    }

    private boolean saveLastMode(Player player, String mode) {
        YamlConfiguration data = loadData(player);
        data.set("last-active-mode", mode);
        return saveData(player, data);
    }

    private boolean hasSavedInventory(Player player, String mode) {
        YamlConfiguration data = loadData(player);
        return data.getBoolean("inventory-initialized." + mode, false);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (!player.isOnline()) return;
            GameMode mode = player.getGameMode();
            if (mode == GameMode.SPECTATOR) {
                if (!hasSavedInventory(player, "survival") && !saveInventory(player, "survival")) return;
                player.getInventory().clear();
                player.getInventory().setArmorContents(new ItemStack[4]);
                player.getInventory().setItemInOffHand(null);
                return;
            }
            String inventoryName = mode == GameMode.CREATIVE ? "creative" : "survival";
            if (!hasSavedInventory(player, inventoryName)) {
                // First use: preserve the inventory already present in the player's vanilla data.
                saveInventory(player, inventoryName);
            } else {
                loadInventory(player, inventoryName);
            }
        });
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        GameMode mode = event.getPlayer().getGameMode();
        if (mode == GameMode.CREATIVE) saveInventory(event.getPlayer(), "creative");
        else if (mode == GameMode.SURVIVAL || mode == GameMode.ADVENTURE) saveInventory(event.getPlayer(), "survival");
    }

    public void shutdown() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            GameMode mode = player.getGameMode();
            if (mode == GameMode.CREATIVE) saveInventory(player, "creative");
            else if (mode == GameMode.SURVIVAL || mode == GameMode.ADVENTURE) saveInventory(player, "survival");
        }
    }

    private boolean isRestricted(ItemStack item) {
        return item != null && !item.getType().isAir() && restrictedCreativeItems.contains(item.getType());
    }

    private boolean isCreative(Player player) {
        return player.getGameMode() == GameMode.CREATIVE;
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onCreativeInventoryClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player) || !isCreative(player)) return;
        InventoryType topType = event.getView().getTopInventory().getType();
        if (topType != InventoryType.CRAFTING && topType != InventoryType.CREATIVE
                && topType != InventoryType.PLAYER) {
            event.setCancelled(true);
            return;
        }
        if (isRestricted(event.getCurrentItem()) || isRestricted(event.getCursor())) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onCreativeInventory(InventoryCreativeEvent event) {
        if (!(event.getWhoClicked() instanceof Player player) || !isCreative(player)) return;
        if (isRestricted(event.getCursor()) || isRestricted(event.getCurrentItem())) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onCreativeInventoryDrag(InventoryDragEvent event) {
        if (!(event.getWhoClicked() instanceof Player player) || !isCreative(player)) return;
        InventoryType topType = event.getView().getTopInventory().getType();
        if (topType != InventoryType.CRAFTING && topType != InventoryType.CREATIVE
                && topType != InventoryType.PLAYER) {
            event.setCancelled(true);
            return;
        }
        if (isRestricted(event.getOldCursor())
                || event.getNewItems().values().stream().anyMatch(this::isRestricted)) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onCreativeDrop(PlayerDropItemEvent event) {
        if (isCreative(event.getPlayer())) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onCreativePickup(EntityPickupItemEvent event) {
        Entity entity = event.getEntity();
        if (entity instanceof Player player && isCreative(player)
                && isRestricted(event.getItem().getItemStack())) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onCreativePlace(BlockPlaceEvent event) {
        if (isCreative(event.getPlayer()) && isRestricted(event.getItemInHand())) {
            event.setCancelled(true);
            event.getPlayer().sendMessage("§cThat block is restricted in Creative mode.");
        }
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (!sender.hasPermission(GAME_MODE_PERMISSION)) return List.of();
        String name = command.getName().toLowerCase(Locale.ROOT);
        if (!Set.of("gmc", "gms", "gmsp").contains(name) || args.length != 1) return List.of();
        String prefix = args[0].toLowerCase(Locale.ROOT);
        return Bukkit.getOnlinePlayers().stream()
                .map(Player::getName)
                .filter(playerName -> playerName.toLowerCase(Locale.ROOT).startsWith(prefix))
                .sorted(String.CASE_INSENSITIVE_ORDER)
                .toList();
    }
}
