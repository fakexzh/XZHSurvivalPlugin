package com.xzh.survival;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.type.Bed;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.EventPriority;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.HashMap;
import java.util.Map;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

public final class BedHomeManager implements Listener, CommandExecutor {
    private final JavaPlugin plugin;
    private final Map<UUID, Location> homes = new HashMap<>();

    public BedHomeManager(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    public void start() {
        loadHomes();
        Bukkit.getPluginManager().registerEvents(this, plugin);
        if (plugin.getCommand("home") != null) {
            plugin.getCommand("home").setExecutor(this);
        }
    }

    public void shutdown() {
        saveHomes();
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("This command can only be used by a player.");
            return true;
        }
        teleportHome(player);
        return true;
    }

    private void teleportHome(Player player) {
        Location home = homes.get(player.getUniqueId());
        if (home == null) {
            player.sendMessage("§cYou don't have a home yet. Place a bed to create one.");
            return;
        }

        World world = home.getWorld();
        if (world == null || !isFootOfBed(home.getBlock())) {
            homes.remove(player.getUniqueId());
            saveHomes();
            player.sendMessage("§cYour bed home no longer exists. Place a new bed to set a home.");
            return;
        }

        Location destination = home.clone().add(0.5, 1.0, 0.5);
        if (!player.teleport(destination)) {
            player.sendMessage("§cCould not teleport you to your bed home.");
            return;
        }
        player.sendMessage("§aTeleported to your bed home.");
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onHomeCommandPreprocess(PlayerCommandPreprocessEvent event) {
        String raw = event.getMessage();
        if (raw == null || !raw.startsWith("/")) return;
        String[] parts = raw.substring(1).trim().split("\\s+");
        if (parts.length == 0 || parts[0].isBlank()) return;

        String root = parts[0].toLowerCase(Locale.ROOT);
        int namespace = root.indexOf(':');
        if (namespace >= 0) root = root.substring(namespace + 1);

        Set<String> homeManagementCommands = Set.of(
                "sethome", "homes", "delhome", "deletehome", "removehome", "home");
        if (!homeManagementCommands.contains(root)) return;

        event.setCancelled(true);
        Player player = event.getPlayer();
        if (root.equals("home") && parts.length == 1) {
            teleportHome(player);
        } else {
            player.sendMessage("§cOnly §f/home §cis available. Place a bed to set your home; breaking it clears the home.");
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onBedPlace(BlockPlaceEvent event) {
        Location foot = findFoot(event.getBlockPlaced());
        if (foot == null) return;

        // Placing a new bed replaces the player's previous home.
        homes.put(event.getPlayer().getUniqueId(), foot);
        saveHomes();
        event.getPlayer().sendMessage("§aBed home set! Use §f/home §ato return here.");
    }

    @EventHandler(ignoreCancelled = true)
    public void onBedBreak(BlockBreakEvent event) {
        Location foot = findFoot(event.getBlock());
        if (foot == null) return;

        boolean changed = homes.entrySet().removeIf(entry -> sameBlock(entry.getValue(), foot));
        if (changed) {
            saveHomes();
            event.getPlayer().sendMessage("§eA bed home was broken and has been cleared.");
        }
    }

    private Location findFoot(Block block) {
        BlockData data = block.getBlockData();
        if (!(data instanceof Bed bed)) return null;
        Block foot = bed.getPart() == Bed.Part.FOOT
                ? block
                : block.getRelative(bed.getFacing().getOppositeFace());
        return new Location(block.getWorld(), foot.getX(), foot.getY(), foot.getZ());
    }

    private boolean isFootOfBed(Block block) {
        BlockData data = block.getBlockData();
        return data instanceof Bed bed && bed.getPart() == Bed.Part.FOOT;
    }

    private boolean sameBlock(Location first, Location second) {
        return first != null && second != null
                && first.getWorld() != null && second.getWorld() != null
                && first.getWorld().getUID().equals(second.getWorld().getUID())
                && first.getBlockX() == second.getBlockX()
                && first.getBlockY() == second.getBlockY()
                && first.getBlockZ() == second.getBlockZ();
    }

    private void loadHomes() {
        homes.clear();
        if (!plugin.getConfig().isConfigurationSection("bed-homes")) return;
        for (String key : plugin.getConfig().getConfigurationSection("bed-homes").getKeys(false)) {
            try {
                UUID id = UUID.fromString(key);
                Location location = plugin.getConfig().getLocation("bed-homes." + key);
                if (location != null && location.getWorld() != null) {
                    homes.put(id, new Location(location.getWorld(), location.getBlockX(),
                            location.getBlockY(), location.getBlockZ()));
                }
            } catch (IllegalArgumentException ignored) {
                plugin.getLogger().warning("Ignoring invalid bed-home UUID: " + key);
            }
        }
    }

    private void saveHomes() {
        plugin.getConfig().set("bed-homes", null);
        for (Map.Entry<UUID, Location> entry : homes.entrySet()) {
            plugin.getConfig().set("bed-homes." + entry.getKey(), entry.getValue());
        }
        plugin.saveConfig();
    }
}
