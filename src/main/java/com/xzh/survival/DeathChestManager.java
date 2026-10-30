package com.xzh.survival;

import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.block.Chest;
import org.bukkit.block.data.type.Chest.Type;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockPistonExtendEvent;
import org.bukkit.event.block.BlockPistonRetractEvent;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.InventoryMoveItemEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.inventory.DoubleChestInventory;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class DeathChestManager implements Listener {
    private static final long LIFETIME_MILLIS = 30L * 60L * 1000L;
    private final JavaPlugin plugin;
    private final NamespacedKey chestIdKey;
    private final NamespacedKey keyIdKey;
    private final File dataFile;
    private final YamlConfiguration data = new YamlConfiguration();
    private final Map<UUID, Record> records = new HashMap<>();
    private final Map<UUID, ItemStack> pendingKeys = new HashMap<>();
    private BukkitTask expiryTask;

    private record Record(UUID id, UUID worldId, int x1, int y1, int z1,
                          int x2, int y2, int z2, long expiresAt,
                          UUID ownerId, String ownerName, boolean unlocked) {}

    public DeathChestManager(JavaPlugin plugin) {
        this.plugin = plugin;
        chestIdKey = new NamespacedKey(plugin, "death_chest_id");
        keyIdKey = new NamespacedKey(plugin, "death_chest_key_id");
        dataFile = new File(plugin.getDataFolder(), "death-chests.yml");
    }

    public void start() {
        load();
        Bukkit.getPluginManager().registerEvents(this, plugin);
        expiryTask = Bukkit.getScheduler().runTaskTimer(plugin, this::expire, 20L, 20L);
        expire();
    }

    public void shutdown() {
        if (expiryTask != null) expiryTask.cancel();
        save();
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onDeath(PlayerDeathEvent event) {
        if (event.getKeepInventory() || event.getDrops().isEmpty()) return;
        Player player = event.getEntity();
        Location origin = player.getLocation().getBlock().getLocation();
        Location[] pair = findChestSpace(origin);
        if (pair == null) {
            plugin.getLogger().warning("Could not place death chest for " + player.getName()
                    + "; leaving normal death drops at the death location.");
            return;
        }

        UUID id = UUID.randomUUID();
        Block first = pair[0].getBlock();
        Block second = pair[1].getBlock();
        first.setType(Material.CHEST, false);
        second.setType(Material.CHEST, false);
        org.bukkit.block.data.type.Chest firstData =
                (org.bukkit.block.data.type.Chest) first.getBlockData();
        firstData.setFacing(org.bukkit.block.BlockFace.NORTH);
        firstData.setType(Type.RIGHT);
        first.setBlockData(firstData, false);
        org.bukkit.block.data.type.Chest secondData =
                (org.bukkit.block.data.type.Chest) second.getBlockData();
        secondData.setFacing(org.bukkit.block.BlockFace.NORTH);
        secondData.setType(Type.LEFT);
        second.setBlockData(secondData, false);

        BlockState state = first.getState();
        if (!(state instanceof Chest chest)) {
            first.setType(Material.AIR, false);
            second.setType(Material.AIR, false);
            return;
        }
        chest.getPersistentDataContainer().set(chestIdKey, PersistentDataType.STRING, id.toString());
        chest.update(true, false);
        BlockState secondState = second.getState();
        if (secondState instanceof Chest secondChest) {
            secondChest.getPersistentDataContainer().set(chestIdKey, PersistentDataType.STRING, id.toString());
            secondChest.update(true, false);
        }

        Inventory inventory = chest.getInventory();
        if (!(inventory instanceof DoubleChestInventory)) {
            // A paired chest must expose a 54-slot inventory. If the server did not
            // assemble the pair, preserve the drops rather than silently lose items.
            first.setType(Material.AIR, false);
            second.setType(Material.AIR, false);
            return;
        }
        for (ItemStack item : event.getDrops()) {
            if (item != null && !item.getType().isAir()) {
                Map<Integer, ItemStack> overflow = inventory.addItem(item.clone());
                for (ItemStack leftover : overflow.values()) {
                    first.getWorld().dropItemNaturally(first.getLocation().add(0.5, 0.5, 0.5), leftover);
                }
            }
        }
        event.getDrops().clear();

        long expires = System.currentTimeMillis() + LIFETIME_MILLIS;
        Record record = new Record(id, first.getWorld().getUID(),
                first.getX(), first.getY(), first.getZ(), second.getX(), second.getY(), second.getZ(),
                expires, player.getUniqueId(), player.getName(), false);
        records.put(id, record);
        pendingKeys.put(player.getUniqueId(), createKey(record));
        save();
        player.sendMessage("§6Your death chest is ready. §eA matching key will be given when you respawn.");
        player.sendMessage("§7It will disappear in 30 minutes, even if you log out.");
    }

    @EventHandler
    public void onRespawn(PlayerRespawnEvent event) {
        Player player = event.getPlayer();
        ItemStack key = pendingKeys.remove(player.getUniqueId());
        if (key == null) return;
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (player.isOnline()) {
                Map<Integer, ItemStack> overflow = player.getInventory().addItem(key);
                for (ItemStack leftover : overflow.values()) {
                    player.getWorld().dropItemNaturally(player.getLocation(), leftover);
                }
                player.sendMessage("§eDeath Chest Key §7received. It only unlocks your chest.");
            }
        });
    }

    @EventHandler(ignoreCancelled = true)
    public void onChestInteract(PlayerInteractEvent event) {
        if (event.getClickedBlock() == null || event.getClickedBlock().getType() != Material.CHEST) return;
        BlockState state = event.getClickedBlock().getState();
        if (!(state instanceof Chest chest)) return;
        String idText = chest.getPersistentDataContainer().get(chestIdKey, PersistentDataType.STRING);
        if (idText == null) return;
        UUID id;
        try { id = UUID.fromString(idText); } catch (IllegalArgumentException ignored) {
            event.setCancelled(true);
            return;
        }
        Record record = records.get(id);
        if (record == null || record.expiresAt() <= System.currentTimeMillis()) {
            event.setCancelled(true);
            return;
        }
        if (record.unlocked()) return;
        event.setCancelled(true);
        Player player = event.getPlayer();
        ItemStack hand = event.getHand() == org.bukkit.inventory.EquipmentSlot.OFF_HAND
                ? player.getInventory().getItemInOffHand() : player.getInventory().getItemInMainHand();
        if (!isMatchingKey(hand, id)) {
            player.sendMessage("§cThis death chest is locked. You need its matching key.");
            return;
        }
        hand.setAmount(hand.getAmount() - 1);
        records.put(id, new Record(record.id(), record.worldId(), record.x1(), record.y1(), record.z1(),
                record.x2(), record.y2(), record.z2(), record.expiresAt(),
                record.ownerId(), record.ownerName(), true));
        save();
        player.sendMessage("§aDeath chest unlocked! §7The key was consumed.");
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (player.isOnline() && event.getClickedBlock().getType() == Material.CHEST) {
                player.openInventory(((Chest) event.getClickedBlock().getState()).getInventory());
            }
        });
    }

    @EventHandler(ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        if (isManagedChest(event.getBlock())) {
            Record record = recordFor(event.getBlock());
            if (record != null && !record.unlocked()) {
                event.setCancelled(true);
                event.getPlayer().sendMessage("§cThis death chest is protected until its key unlocks it.");
            }
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onHopperMove(InventoryMoveItemEvent event) {
        if (event.getSource().getHolder() instanceof Chest chest && isLocked(chest.getBlock())) {
            event.setCancelled(true);
        }
        if (event.getDestination().getHolder() instanceof Chest chest && isLocked(chest.getBlock())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onEntityExplode(EntityExplodeEvent event) {
        event.blockList().removeIf(this::isManagedChest);
    }

    @EventHandler(ignoreCancelled = true)
    public void onBlockExplode(BlockExplodeEvent event) {
        event.blockList().removeIf(this::isManagedChest);
    }

    @EventHandler(ignoreCancelled = true)
    public void onPistonExtend(BlockPistonExtendEvent event) {
        if (event.getBlocks().stream().anyMatch(this::isManagedChest)) event.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onPistonRetract(BlockPistonRetractEvent event) {
        if (event.getBlocks().stream().anyMatch(this::isManagedChest)) event.setCancelled(true);
    }

    private boolean isManagedChest(Block block) {
        return block.getType() == Material.CHEST && block.getState() instanceof Chest chest
                && chest.getPersistentDataContainer().has(chestIdKey, PersistentDataType.STRING);
    }

    private Record recordFor(Block block) {
        if (!(block.getState() instanceof Chest chest)) return null;
        String value = chest.getPersistentDataContainer().get(chestIdKey, PersistentDataType.STRING);
        if (value == null) return null;
        try { return records.get(UUID.fromString(value)); } catch (IllegalArgumentException ignored) { return null; }
    }

    private boolean isLocked(Block block) {
        Record record = recordFor(block);
        return record != null && !record.unlocked();
    }

    private boolean isMatchingKey(ItemStack item, UUID id) {
        if (item == null || item.getType() != Material.TRIPWIRE_HOOK || !item.hasItemMeta()) return false;
        String value = item.getItemMeta().getPersistentDataContainer().get(keyIdKey, PersistentDataType.STRING);
        return id.toString().equals(value);
    }

    private ItemStack createKey(Record record) {
        ItemStack key = new ItemStack(Material.TRIPWIRE_HOOK);
        ItemMeta meta = key.getItemMeta();
        meta.setDisplayName("§6Death Chest Key");
        meta.setLore(List.of("§7Owner: §f" + record.ownerName(), "§7One-time unlock key",
                "§8Chest ID: " + record.id().toString().substring(0, 8)));
        meta.getPersistentDataContainer().set(keyIdKey, PersistentDataType.STRING, record.id().toString());
        key.setItemMeta(meta);
        return key;
    }

    private Location[] findChestSpace(Location origin) {
        int[][] offsets = {{0,0,0},{1,0,0},{-1,0,0},{0,0,1},{0,0,-1},{2,0,0},{-2,0,0},{0,0,2},{0,0,-2}};
        World world = origin.getWorld();
        for (int[] offset : offsets) {
            Block a = world.getBlockAt(origin.getBlockX()+offset[0], origin.getBlockY()+offset[1], origin.getBlockZ()+offset[2]);
            Block b = a.getRelative(1, 0, 0);
            if (a.getType().isAir() && b.getType().isAir()
                    && a.getRelative(0,-1,0).getType().isSolid()
                    && b.getRelative(0,-1,0).getType().isSolid()
                    && !a.isLiquid() && !b.isLiquid()) {
                return new Location[]{a.getLocation(), b.getLocation()};
            }
        }
        return null;
    }

    @EventHandler\n    public void onChunkLoad(ChunkLoadEvent event) {\n        long now = System.currentTimeMillis();\n        for (Record record : new ArrayList<>(records.values())) {\n            if (record.expiresAt() > now || !record.worldId().equals(event.getWorld().getUID())) continue;\n            if ((record.x1() >> 4) == event.getChunk().getX() && (record.z1() >> 4) == event.getChunk().getZ()) removeChestHalf(event.getWorld(), record.x1(), record.y1(), record.z1(), record.id());\n            if ((record.x2() >> 4) == event.getChunk().getX() && (record.z2() >> 4) == event.getChunk().getZ()) removeChestHalf(event.getWorld(), record.x2(), record.y2(), record.z2(), record.id());\n            if (bothChunksLoaded(record)) { records.remove(record.id()); save(); }\n        }\n    }\n\n    private boolean bothChunksLoaded(Record r) {\n        World w = Bukkit.getWorld(r.worldId());\n        return w != null && w.isChunkLoaded(r.x1() >> 4, r.z1() >> 4) && w.isChunkLoaded(r.x2() >> 4, r.z2() >> 4);\n    }\n\n    private void expire() {
        long now = System.currentTimeMillis();
        Iterator<Record> iterator = records.values().iterator();
        List<UUID> expired = new ArrayList<>();
        while (iterator.hasNext()) {
            Record record = iterator.next();
            if (record.expiresAt() > now) continue;
            removeIfLoaded(record);
            expired.add(record.id());
        }
        if (!expired.isEmpty()) {
            expired.forEach(records::remove);
            save();
        }
    }

    private void removeIfLoaded(Record record) {
        World world = Bukkit.getWorld(record.worldId());
        if (world == null) return;
        removeChestHalf(world, record.x1(), record.y1(), record.z1(), record.id());
        removeChestHalf(world, record.x2(), record.y2(), record.z2(), record.id());
    }

    private void removeChestHalf(World world, int x, int y, int z, UUID id) {
        if (!world.isChunkLoaded(x >> 4, z >> 4)) return;
        Block block = world.getBlockAt(x, y, z);
        if (block.getType() != Material.CHEST || !(block.getState() instanceof Chest chest)) return;
        String stored = chest.getPersistentDataContainer().get(chestIdKey, PersistentDataType.STRING);
        if (id.toString().equals(stored)) block.setType(Material.AIR, false);
    }

    private void load() {
        if (!dataFile.exists()) return;
        try { data.load(dataFile); } catch (IOException | org.bukkit.configuration.InvalidConfigurationException ex) {
            plugin.getLogger().severe("Could not load death-chests.yml: " + ex.getMessage());
            return;
        }
        var section = data.getConfigurationSection("chests");
        if (section == null) return;
        for (String rawId : section.getKeys(false)) {
            try {
                UUID id = UUID.fromString(rawId);
                String path = "chests." + rawId + ".";
                records.put(id, new Record(id, UUID.fromString(data.getString(path+"world")),
                        data.getInt(path+"x1"), data.getInt(path+"y1"), data.getInt(path+"z1"),
                        data.getInt(path+"x2"), data.getInt(path+"y2"), data.getInt(path+"z2"),
                        data.getLong(path+"expires"), UUID.fromString(data.getString(path+"owner")),
                        data.getString(path+"owner-name", "Unknown"), data.getBoolean(path+"unlocked")));
            } catch (RuntimeException ex) {
                plugin.getLogger().warning("Ignoring invalid death chest record " + rawId);
            }
        }
    }

    private void save() {
        data.set("chests", null);
        for (Record r : records.values()) {
            String p = "chests." + r.id() + ".";
            data.set(p+"world", r.worldId().toString());
            data.set(p+"x1", r.x1()); data.set(p+"y1", r.y1()); data.set(p+"z1", r.z1());
            data.set(p+"x2", r.x2()); data.set(p+"y2", r.y2()); data.set(p+"z2", r.z2());
            data.set(p+"expires", r.expiresAt());
            data.set(p+"owner", r.ownerId().toString());
            data.set(p+"owner-name", r.ownerName());
            data.set(p+"unlocked", r.unlocked());
        }
        try {
            if (!plugin.getDataFolder().exists() && !plugin.getDataFolder().mkdirs()) {
                plugin.getLogger().warning("Could not create data folder for death chests.");
                return;
            }
            data.save(dataFile);
        } catch (IOException ex) {
            plugin.getLogger().severe("Could not save death chests: " + ex.getMessage());
        }
    }
}
