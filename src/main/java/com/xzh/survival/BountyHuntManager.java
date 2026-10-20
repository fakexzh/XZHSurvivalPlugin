package com.xzh.survival;

import org.bukkit.*;
import org.bukkit.attribute.Attribute;
import org.bukkit.entity.*;
import org.bukkit.boss.BarColor;
import org.bukkit.boss.BarStyle;
import org.bukkit.boss.BossBar;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.block.Action;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.Inventory;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.block.Chest;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.Vector;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Method;
import java.util.*;

public final class BountyHuntManager implements Listener, org.bukkit.command.CommandExecutor {
    private final JavaPlugin plugin;
    private final NamespacedKey bountyTierKey, contractOwnerKey, bowKey, arrowKey, targetKey, trackingCompassKey;
    private final File dataFile;
    private final org.bukkit.configuration.file.YamlConfiguration data = new org.bukkit.configuration.file.YamlConfiguration();
    private final File cooldownFile;
    private final org.bukkit.configuration.file.YamlConfiguration cooldownData = new org.bukkit.configuration.file.YamlConfiguration();
    private final Map<UUID, Contract> active = new HashMap<>();
    private final Map<UUID, BossBar> contractBossBars = new HashMap<>();
    private final Random random = new Random();

    private static final class Contract {
        UUID owner, target;
        String tier, world, dimension;
        double x, y, z, entryPrice;
        int rewardPercent;
        long remainingSeconds, lastSavedAt;
        Contract(UUID owner, String tier, long seconds) { this.owner=owner; this.tier=tier; this.remainingSeconds=seconds; this.rewardPercent=30 + new Random().nextInt(31); }
    }

    public BountyHuntManager(JavaPlugin plugin) {
        this.plugin = plugin;
        bountyTierKey = new NamespacedKey(plugin, "bounty_tier");
        contractOwnerKey = new NamespacedKey(plugin, "bounty_owner");
        bowKey = new NamespacedKey(plugin, "hunter_bow");
        arrowKey = new NamespacedKey(plugin, "tracking_arrow");
        trackingCompassKey = new NamespacedKey(plugin, "tracking_compass");
        targetKey = new NamespacedKey(plugin, "bounty_target");
        dataFile = new File(plugin.getDataFolder(), "bounties.yml");
        cooldownFile = new File(plugin.getDataFolder(), "bounty-cooldowns.yml");
    }

    public void start() {
        load();
        loadCooldowns();
        Bukkit.getPluginManager().registerEvents(this, plugin);
        Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 20L, 20L);
        Bukkit.getScheduler().runTaskTimer(plugin, this::save, 100L, 100L);
        for (Contract c : active.values()) restoreTarget(c);
    }

    public void shutdown() {
        save();
        saveCooldowns();
        for (BossBar bar : contractBossBars.values()) bar.removeAll();
        contractBossBars.clear();
    }

    @Override
    public boolean onCommand(org.bukkit.command.CommandSender sender, org.bukkit.command.Command command,
                             String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("This command can only be used in-game.");
            return true;
        }
        if (args.length == 1 && args[0].equalsIgnoreCase("cancel")) {
            if (!active.containsKey(player.getUniqueId())) {
                player.sendMessage("§cYou don't have an active bounty contract.");
            } else {
                finish(player.getUniqueId(), "abandoned");
            }
            return true;
        }
        player.sendMessage("§dBounty Hunt §8» §fUse /bounty cancel to abandon your active contract.");
        return true;
    }

    @EventHandler(ignoreCancelled = true)
    public void onUse(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_AIR && event.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        Player player = event.getPlayer();
        ItemStack hand = event.getItem();
        if (hand == null || !hand.hasItemMeta()) return;
        ItemMeta meta = hand.getItemMeta();
        String tier = null;
        for (NamespacedKey key : meta.getPersistentDataContainer().getKeys()) {
            if (key.getNamespace().equals("xzheconomysystem") && key.getKey().equals("bounty_tier")) {
                tier = meta.getPersistentDataContainer().get(key, PersistentDataType.STRING);
                break;
            }
        }
        if (tier != null) {
            event.setCancelled(true);
            String dimension = meta.getPersistentDataContainer().get(new NamespacedKey("xzheconomysystem", "bounty_dimension"), PersistentDataType.STRING);
            if (dimension == null) dimension = "overworld";
            dimension = dimension.toLowerCase(Locale.ROOT);
            if (!dimension.equals("overworld") && !dimension.equals("nether")) { player.sendMessage("§cThis bounty map has an invalid dimension."); return; }
            World.Environment requiredEnvironment = dimension.equals("nether") ? World.Environment.NETHER : World.Environment.NORMAL;
            if (player.getWorld().getEnvironment() != requiredEnvironment) {
                player.sendMessage("§cThis is a " + dimension + " bounty map. Travel to the " + dimension + " before activating it. Your map was not consumed.");
                return;
            }
            String cooldownPath = "players." + player.getUniqueId() + "." + dimension + "." + tier;
            long cooldownLeft = cooldownData.getLong(cooldownPath, 0L) - System.currentTimeMillis();
            if (cooldownLeft > 0L) {
                long hours = cooldownLeft / 3_600_000L;
                long minutes = (cooldownLeft % 3_600_000L + 59_999L) / 60_000L;
                player.sendMessage("§c" + tier.toUpperCase(Locale.ROOT) + " " + dimension.toUpperCase(Locale.ROOT)
                        + " bounty cooldown: " + hours + "h " + minutes + "m remaining. Your map was not consumed.");
                return;
            }
            if (active.containsKey(player.getUniqueId())) { player.sendMessage("§cYou already have an active bounty contract."); return; }
            int emptyHotbarSlots = countEmptyHotbarSlots(player.getInventory());
            int neededEmptySlots = hand.getAmount() > 1 ? 3 : 2;
            if (emptyHotbarSlots < neededEmptySlots) {
                player.sendMessage("§cBounty map not used: keep enough empty hotbar slots for both the Hunter's Bow and Tracking Arrow.");
                return;
            }
            if (hand.getAmount() > 1) hand.setAmount(hand.getAmount() - 1);
            else if (event.getHand() == org.bukkit.inventory.EquipmentSlot.OFF_HAND) player.getInventory().setItemInOffHand(null);
            else player.getInventory().setItemInMainHand(null);
            player.updateInventory();
            long defaultSeconds = tier.equals("common") ? 25*60L : tier.equals("rare") ? 45*60L : 2*60*60L;
            long seconds = plugin.getConfig().getLong("bounty-hunt.timers-seconds." + tier, defaultSeconds);
            Contract c = new Contract(player.getUniqueId(), tier, seconds);
            c.dimension = dimension;
            Double mapPrice = meta.getPersistentDataContainer().get(new NamespacedKey("xzheconomysystem", "mission_entry_price"), PersistentDataType.DOUBLE);
            c.entryPrice = mapPrice == null || mapPrice <= 0 ? (tier.equalsIgnoreCase("legendary") ? 600 : tier.equalsIgnoreCase("rare") ? 300 : 100) : mapPrice;
            active.put(player.getUniqueId(), c);
            cooldownData.set(cooldownPath, System.currentTimeMillis() + 12L * 60L * 60L * 1000L);
            saveCooldowns();
            giveEquipment(player, c);
            spawnTarget(player, c);
            player.sendMessage("§d§lXZH BOUNTY HUNT");
            player.sendMessage("§a✦ " + tier.toUpperCase(Locale.ROOT) + " " + dimension.toUpperCase(Locale.ROOT) + " bounty accepted!");
            player.sendMessage("§7This bounty type is now on cooldown for 12 hours.");
            player.sendMessage("§e➤ Hold your Tracking Arrow and right-click to locate your bounty target.");
            player.sendMessage("§e➤ You can use the special Hunter's Bow to fight and defeat your target.");
            player.sendMessage("§7➤ Your mission equipment will be removed when the contract ends.");
            return;
        }
        if (meta.getPersistentDataContainer().has(arrowKey, PersistentDataType.BYTE)) {
            event.setCancelled(true);
            String itemOwner = meta.getPersistentDataContainer().get(contractOwnerKey, PersistentDataType.STRING);
            if (!player.getUniqueId().toString().equals(itemOwner)) {
                player.sendMessage("§cThis Tracking Arrow belongs to another player's bounty contract.");
                return;
            }
            Contract c = active.get(player.getUniqueId());
            if (c == null || c.target == null) { player.sendMessage("§cThis Tracking Arrow has no active bounty target."); return; }
            Entity target = Bukkit.getEntity(c.target);
            if (target == null || target.isDead()) { player.sendMessage("§cYour target cannot be located. The contract will remain active until its timer expires."); return; }
            Location targetLocation = target.getLocation().clone();
            // Use compass/action-bar navigation for custom target tracking; no Eye of Ender is spawned.
            Vector direction = targetLocation.toVector().subtract(player.getLocation().toVector()).setY(0);
            if (direction.lengthSquared() > 0.01) {
                float yaw = (float) Math.toDegrees(Math.atan2(-direction.getX(), direction.getZ()));
                player.setRotation(yaw, player.getLocation().getPitch());
            }
            player.setCompassTarget(targetLocation);
            player.sendActionBar("§dBounty target §8• §f" + (int) player.getLocation().distance(targetLocation) + " blocks away");
            player.getWorld().spawnParticle(Particle.END_ROD, player.getEyeLocation().add(player.getEyeLocation().getDirection().multiply(1.2)), 8, 0.2, 0.2, 0.2, 0.02);
            player.sendMessage("§dTracking updated. Follow the bearing and distance above your hotbar.");
        }
    }

    private int countEmptyHotbarSlots(PlayerInventory inv) {
        int count=0;
        for (int i=0;i<9;i++) if (inv.getItem(i)==null || inv.getItem(i).getType().isAir()) count++;
        return count;
    }

    private void giveEquipment(Player player, Contract c) {
        PlayerInventory inv = player.getInventory();
        ItemStack bow = new ItemStack(Material.BOW);
        ItemMeta bm = bow.getItemMeta();
        bm.setDisplayName("§dXZH Hunter's Bow");
        bm.setLore(List.of("§7Special bounty equipment", "§7Removed when the contract ends."));
        bm.addEnchant(org.bukkit.enchantments.Enchantment.POWER, 5, true);
        bm.addEnchant(org.bukkit.enchantments.Enchantment.INFINITY, 1, true);
        bm.addEnchant(org.bukkit.enchantments.Enchantment.FLAME, 1, true);
        bm.setEnchantmentGlintOverride(true);
        bm.getPersistentDataContainer().set(bowKey, PersistentDataType.BYTE, (byte)1);
        bm.getPersistentDataContainer().set(contractOwnerKey, PersistentDataType.STRING, c.owner.toString());
        bow.setItemMeta(bm);
        ItemStack arrow = new ItemStack(Material.ARROW);
        ItemMeta am = arrow.getItemMeta();
        am.setDisplayName("§dHunter's Arrow");
        am.setLore(List.of("§7Ammunition for your Hunter's Bow."));
        am.getPersistentDataContainer().set(arrowKey, PersistentDataType.BYTE, (byte)1);
        am.getPersistentDataContainer().set(contractOwnerKey, PersistentDataType.STRING, c.owner.toString());
        arrow.setItemMeta(am);
        ItemStack compass = new ItemStack(Material.COMPASS);
        ItemMeta cm = compass.getItemMeta();
        cm.setDisplayName("§dBounty Tracker");
        cm.setLore(List.of("§7Live direction and distance to your target."));
        cm.getPersistentDataContainer().set(trackingCompassKey, PersistentDataType.BYTE, (byte)1);
        cm.getPersistentDataContainer().set(contractOwnerKey, PersistentDataType.STRING, c.owner.toString());
        compass.setItemMeta(cm);
        int bowSlot = -1;
        int arrowSlot = -1;
        int compassSlot = -1;
        for (int i = 0; i < 9; i++) {
            ItemStack current = inv.getItem(i);
            if (current == null || current.getType().isAir()) {
                if (bowSlot < 0) bowSlot = i;
                else if (arrowSlot < 0) arrowSlot = i;
                else { compassSlot = i; break; }
            }
        }
        if (bowSlot < 0 || arrowSlot < 0 || compassSlot < 0) {
            player.sendMessage("§cNot enough empty hotbar slots for the bow, arrows and tracker compass.");
            return;
        }
        inv.setItem(bowSlot, bow);
        arrow.setAmount(16);
        inv.setItem(arrowSlot, arrow);
        inv.setItem(compassSlot, compass);
        player.updateInventory();
    }

    private void spawnTarget(Player player, Contract c) {
        World world = player.getWorld();
        Location base = player.getLocation();
        Location floor = null;
        for (int tries = 0; tries < 48; tries++) {
            double angle = random.nextDouble() * Math.PI * 2.0;
            int distance = 90 + random.nextInt(151);
            int x = base.getBlockX() + (int) Math.round(Math.cos(angle) * distance);
            int z = base.getBlockZ() + (int) Math.round(Math.sin(angle) * distance);
            int floorY;
            if (c.dimension.equals("nether")) {
                floorY = 32 + random.nextInt(62);
                boolean found = false;
                for (int scan = 0; scan < 30; scan++) {
                    int y = 32 + random.nextInt(62);
                    Material ground = world.getBlockAt(x, y, z).getType();
                    Material feet = world.getBlockAt(x, y + 1, z).getType();
                    Material head = world.getBlockAt(x, y + 2, z).getType();
                    if (ground.isSolid() && ground != Material.BEDROCK && feet.isAir() && head.isAir()
                            && feet != Material.LAVA && head != Material.LAVA) {
                        floorY = y; found = true; break;
                    }
                }
                if (!found) continue;
            } else {
                floorY = world.getHighestBlockYAt(x, z);
                if (floorY <= world.getMinHeight() + 5 || floorY >= world.getMaxHeight() - 8) continue;
            }
            Material ground = world.getBlockAt(x, floorY, z).getType();
            if (!ground.isSolid() || ground == Material.BEDROCK || ground == Material.LAVA) continue;
            floor = new Location(world, x, floorY, z);
            break;
        }
        if (floor == null) {
            int x = base.getBlockX() + 24, z = base.getBlockZ() + 24;
            int y = world.getHighestBlockYAt(x, z);
            floor = new Location(world, x, y, z);
        }

        boolean challenging = !c.tier.equals("common");
        Location spawn = challenging ? buildBountyChamber(world, floor, c.dimension) : floor.clone().add(0.5, 1, 0.5);
        EntityType type;
        if (c.dimension.equals("nether")) {
            type = switch (c.tier) {
                case "legendary" -> new EntityType[]{EntityType.WITHER_SKELETON, EntityType.PIGLIN_BRUTE, EntityType.HOGLIN}[random.nextInt(3)];
                case "rare" -> new EntityType[]{EntityType.WITHER_SKELETON, EntityType.HOGLIN, EntityType.PIGLIN}[random.nextInt(3)];
                default -> new EntityType[]{EntityType.ZOMBIFIED_PIGLIN, EntityType.MAGMA_CUBE, EntityType.SKELETON}[random.nextInt(3)];
            };
        } else {
            type = switch (c.tier) {
                case "legendary" -> random.nextBoolean() ? EntityType.RAVAGER : EntityType.PILLAGER;
                case "rare" -> new EntityType[]{EntityType.VINDICATOR, EntityType.HUSK, EntityType.STRAY, EntityType.PILLAGER}[random.nextInt(4)];
                default -> new EntityType[]{EntityType.ZOMBIE, EntityType.SKELETON, EntityType.SPIDER, EntityType.HUSK}[random.nextInt(4)];
            };
        }
        Entity entity = world.spawnEntity(spawn, type);
        if (!(entity instanceof LivingEntity living)) { entity.remove(); return; }
        c.target = entity.getUniqueId(); c.world = world.getName();
        c.x = spawn.getX(); c.y = spawn.getY(); c.z = spawn.getZ();
        living.setGlowing(true);
        living.setCustomName("§d§l" + c.tier.toUpperCase(Locale.ROOT) + " " + c.dimension.toUpperCase(Locale.ROOT) + " BOUNTY TARGET");
        living.setCustomNameVisible(true);
        refreshContractBossBar(c, living);
        living.getPersistentDataContainer().set(targetKey, PersistentDataType.STRING, c.owner.toString());
        if (living.getAttribute(Attribute.MAX_HEALTH) != null) {
            double hp = c.tier.equals("legendary") ? 180 : c.tier.equals("rare") ? 100 : 45;
            living.getAttribute(Attribute.MAX_HEALTH).setBaseValue(hp);
            living.setHealth(hp);
        }
        living.setRemoveWhenFarAway(false);

        if (challenging) {
            int backupCount = c.tier.equals("legendary") ? 4 : 2;
            for (int i = 0; i < backupCount; i++) {
                Location guard = spawn.clone().add((i % 2 == 0 ? -1 : 1) * (2 + i / 2), 0, (i % 3) - 1);
                if (!guard.getBlock().isPassable()) guard = spawn.clone().add(i + 1, 0, 0);
                Entity backup = world.spawnEntity(guard, EntityType.ZOMBIE);
                if (backup instanceof LivingEntity guardMob) {
                    guardMob.setCustomName("§cBounty Guard");
                    guardMob.setCustomNameVisible(false);
                    guardMob.setRemoveWhenFarAway(false);
                    guardMob.getPersistentDataContainer().set(targetKey, PersistentDataType.STRING, c.owner.toString());
                    if (guardMob.getAttribute(Attribute.MAX_HEALTH) != null) {
                        guardMob.getAttribute(Attribute.MAX_HEALTH).setBaseValue(c.tier.equals("legendary") ? 40 : 28);
                        guardMob.setHealth(guardMob.getAttribute(Attribute.MAX_HEALTH).getValue());
                    }
                }
            }
        }
    }

    private Location buildBountyChamber(World world, Location floor, String dimension) {
        int x = floor.getBlockX(), y = floor.getBlockY(), z = floor.getBlockZ();
        Material brick = dimension.equals("nether") ? Material.POLISHED_BLACKSTONE_BRICKS : Material.STONE_BRICKS;
        Material trim = dimension.equals("nether") ? Material.NETHER_BRICKS : Material.CRACKED_STONE_BRICKS;
        // A compact, open-front combat chamber makes Rare and Legendary hunts feel distinct.
        for (int dx = -4; dx <= 4; dx++) {
            for (int dz = -4; dz <= 4; dz++) {
                world.getBlockAt(x + dx, y, z + dz).setType(brick, false);
                for (int dy = 1; dy <= 4; dy++) {
                    boolean wall = Math.abs(dx) == 4 || Math.abs(dz) == 4;
                    boolean roof = dy == 4;
                    boolean doorway = dx == -4 && dz == 0 && dy <= 2;
                    if (doorway) {
                        world.getBlockAt(x + dx, y + dy, z + dz).setType(Material.AIR, false);
                    } else if (wall || roof) {
                        world.getBlockAt(x + dx, y + dy, z + dz).setType((dy == 4 && dx % 2 == 0) ? trim : brick, false);
                    } else {
                        world.getBlockAt(x + dx, y + dy, z + dz).setType(Material.AIR, false);
                    }
                }
            }
        }
        world.getBlockAt(x, y + 1, z).setType(Material.AIR, false);
        world.getBlockAt(x, y + 2, z).setType(Material.AIR, false);
        return new Location(world, x + 0.5, y + 1, z + 0.5);
    }

    private void restoreTarget(Contract c) {
        if (c.target==null || Bukkit.getEntity(c.target)!=null) return;
        World w=Bukkit.getWorld(c.world);
        if (w==null) return;
        Location loc=new Location(w,c.x,c.y,c.z);
        if (!w.isChunkLoaded(loc.getBlockX()>>4,loc.getBlockZ()>>4)) w.getChunkAt(loc);
        if (Bukkit.getEntity(c.target) != null) return;
        Player owner=Bukkit.getPlayer(c.owner);
        if (owner==null) return;
        spawnTarget(owner,c);
    }

    @EventHandler(ignoreCancelled=true)
    public void onTargetDeath(EntityDeathEvent event) {
        String ownerText=event.getEntity().getPersistentDataContainer().get(targetKey,PersistentDataType.STRING);
        if (ownerText==null) return;
        UUID ownerId;
        try { ownerId=UUID.fromString(ownerText); } catch (IllegalArgumentException ex) { return; }
        Contract c=active.get(ownerId);
        if (c==null || !event.getEntity().getUniqueId().equals(c.target)) return;
        Player owner=Bukkit.getPlayer(ownerId);
        int reward=(int)Math.ceil(c.entryPrice * (1.0 + c.rewardPercent / 100.0));
        boolean paid = payReward(ownerId,reward);
        if (owner!=null) owner.sendMessage(paid
                ? "§a§lBOUNTY COMPLETE! §fReward: §e"+reward+" coins §fdeposited directly into your bank."
                : "§cBounty completed, but the bank payout failed. Contact an admin.");
        if (paid) Bukkit.broadcastMessage("§d[XZH Bounty] §f" + (owner == null ? "A player" : owner.getName())
                + " completed a " + c.tier + " bounty. §e" + reward + " coins §fwere deposited into their bank.");
        finish(ownerId,"completed");
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) { save(); }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        Contract c=active.get(event.getPlayer().getUniqueId());
        if(c==null) {
            removeTaggedEquipment(event.getPlayer().getInventory(), event.getPlayer().getUniqueId());
            return;
        }
        if(c!=null) {
            Bukkit.getScheduler().runTaskLater(plugin,()->{
                if(active.containsKey(event.getPlayer().getUniqueId())) {
                    restoreTarget(c);
                    event.getPlayer().sendMessage("§dYour "+c.tier+" bounty resumed with "+format(c.remainingSeconds)+" remaining.");
                }
            },20L);
        }
    }

    private void tick() {
        for (Contract c:new ArrayList<>(active.values())) {
            Player p=Bukkit.getPlayer(c.owner);
            if(p==null || !p.isOnline()) continue;
            c.remainingSeconds--;
            Entity activeTarget = c.target == null ? null : Bukkit.getEntity(c.target);
            if (activeTarget instanceof LivingEntity living && !living.isDead()) {
                refreshContractBossBar(c, living);
                if (p.getWorld() == living.getWorld()) {
                    p.setCompassTarget(living.getLocation());
                    double dx = living.getLocation().getX() - p.getLocation().getX();
                    double dz = living.getLocation().getZ() - p.getLocation().getZ();
                    double angle = (Math.toDegrees(Math.atan2(-dx, dz)) + 360.0) % 360.0;
                    String[] dirs = {"N","NE","E","SE","S","SW","W","NW"};
                    String dir = dirs[(int)Math.round(angle / 45.0) % 8];
                    p.sendActionBar("§dBounty §8• §f" + dir + " §7| §e" + (int)p.getLocation().distance(living.getLocation()) + " blocks §8| §7" + format(c.remainingSeconds));
                }
            } else removeContractBossBar(c.owner);
            if(c.remainingSeconds<=0) {
                p.sendMessage("§cBounty failed: your contract timer expired.");
                finish(c.owner,"expired");
            } else if(c.remainingSeconds%60==0) {
                p.sendActionBar("§d"+c.tier.toUpperCase(Locale.ROOT)+" BOUNTY §8• §f"+format(c.remainingSeconds)+" remaining");
            }
        }
    }

    private void refreshContractBossBar(Contract contract, LivingEntity target) {
        Player owner = Bukkit.getPlayer(contract.owner);
        if (owner == null || !owner.isOnline()) return;
        BossBar bar = contractBossBars.computeIfAbsent(contract.owner, id -> {
            BossBar created = Bukkit.createBossBar("", BarColor.PURPLE, BarStyle.SEGMENTED_10);
            created.setVisible(true);
            return created;
        });
        double maxHealth = target.getAttribute(Attribute.MAX_HEALTH) == null
                ? Math.max(1.0, target.getHealth())
                : Math.max(1.0, target.getAttribute(Attribute.MAX_HEALTH).getValue());
        bar.setTitle("§d§l" + contract.tier.toUpperCase(Locale.ROOT) + " BOUNTY §8• §f"
                + (int)Math.ceil(target.getHealth()) + "/" + (int)Math.ceil(maxHealth) + " HP §8• §e" + format(contract.remainingSeconds));
        bar.setProgress(Math.max(0.0, Math.min(1.0, target.getHealth() / maxHealth)));
        if (!bar.getPlayers().contains(owner)) bar.addPlayer(owner);
        bar.setVisible(true);
    }

    private void removeContractBossBar(UUID owner) {
        BossBar bar = contractBossBars.remove(owner);
        if (bar != null) {
            bar.removeAll();
            bar.setVisible(false);
        }
    }

    private void finish(UUID owner,String reason) {
        Contract c=active.remove(owner);
        removeContractBossBar(owner);
        if(c==null)return;
        Entity target=c.target==null?null:Bukkit.getEntity(c.target);
        if (target == null && c.target != null && c.world != null) {
            World targetWorld = Bukkit.getWorld(c.world);
            if (targetWorld != null) {
                Location targetLocation = new Location(targetWorld, c.x, c.y, c.z);
                targetWorld.getChunkAt(targetLocation);
                target = Bukkit.getEntity(c.target);
            }
        }
        if(target!=null) target.remove();
        for (World world : Bukkit.getWorlds()) {
            for (Entity entity : new ArrayList<>(world.getEntities())) {
                String taggedOwner = entity.getPersistentDataContainer().get(targetKey, PersistentDataType.STRING);
                if (owner.toString().equals(taggedOwner)) entity.remove();
            }
        }
        Player p=Bukkit.getPlayer(owner);
        if(p!=null) {
            removeTaggedEquipment(p.getInventory(),owner);
            p.sendMessage(reason.equals("completed")?"§aBounty contract closed.":"§cBounty contract "+reason+". Mission equipment removed.");
        }
        save();
    }

    private void removeTaggedEquipment(PlayerInventory inv,UUID owner) {
        for(int i=0;i<inv.getSize();i++) {
            ItemStack item=inv.getItem(i);
            if(item==null||!item.hasItemMeta())continue;
            String tagged=item.getItemMeta().getPersistentDataContainer().get(contractOwnerKey,PersistentDataType.STRING);
            if(owner.toString().equals(tagged)) inv.setItem(i,null);
        }
        for (World world : Bukkit.getWorlds()) {
            for (Entity e : world.getEntities()) {
                if (e instanceof Item dropped && dropped.getItemStack().hasItemMeta()) {
                    String tagged = dropped.getItemStack().getItemMeta().getPersistentDataContainer()
                            .get(contractOwnerKey, PersistentDataType.STRING);
                    if (owner.toString().equals(tagged)) dropped.remove();
                }
            }
        }
    }

    private boolean payReward(UUID playerId,int amount) {
        Plugin economy=Bukkit.getPluginManager().getPlugin("XZHEconomySystem");
        if(economy==null||!economy.isEnabled()) {
            Player online=Bukkit.getPlayer(playerId);
            if(online!=null) online.sendMessage("§cEconomy unavailable; bank reward could not be paid. Contact an admin.");
            return false;
        }
        try {
            Object bankManager=economy.getClass().getMethod("getBankManager").invoke(economy);
            Method getBalance=bankManager.getClass().getMethod("getBalance",UUID.class);
            Method setBalance=bankManager.getClass().getMethod("setBalance",UUID.class,double.class);
            double balance=((Number)getBalance.invoke(bankManager,playerId)).doubleValue();
            setBalance.invoke(bankManager,playerId,balance+amount);
            return true;
        } catch(ReflectiveOperationException|RuntimeException ex) {
            plugin.getLogger().warning("Could not deposit bounty reward into bank: "+ex.getClass().getSimpleName());
            Player online=Bukkit.getPlayer(playerId);
            if(online!=null) online.sendMessage("§cBank reward failed; contact an admin.");
            return false;
        }
    }

    private void save() {
        for (String key : new HashSet<>(data.getKeys(false))) data.set(key, null);
        for(Contract c:active.values()) {
            String k=c.owner.toString();
            data.set(k+".tier",c.tier); data.set(k+".dimension",c.dimension); data.set(k+".target",c.target==null?null:c.target.toString());
            data.set(k+".world",c.world); data.set(k+".x",c.x); data.set(k+".y",c.y); data.set(k+".z",c.z);
            data.set(k+".remaining",c.remainingSeconds); data.set(k+".entryPrice",c.entryPrice); data.set(k+".rewardPercent",c.rewardPercent);
        }
        try { data.save(dataFile); } catch(IOException e) { plugin.getLogger().warning("Could not save bounty data: "+e.getMessage()); }
    }

    private void load() {
        if(!dataFile.exists())return;
        try { data.load(dataFile); } catch(Exception e) { plugin.getLogger().warning("Could not load bounties.yml"); return; }
        for(String key:data.getKeys(false)) {
            try {
                UUID owner=UUID.fromString(key);
                String tier=data.getString(key+".tier","common");
                Contract c=new Contract(owner,tier,data.getLong(key+".remaining",0));
                String target=data.getString(key+".target"); if(target!=null)c.target=UUID.fromString(target);
                c.dimension=data.getString(key+".dimension", "overworld");
                c.world=data.getString(key+".world"); c.x=data.getDouble(key+".x"); c.y=data.getDouble(key+".y"); c.z=data.getDouble(key+".z");
                c.entryPrice=data.getDouble(key+".entryPrice", c.tier.equals("legendary") ? 600 : c.tier.equals("rare") ? 300 : 100);
                c.rewardPercent=data.getInt(key+".rewardPercent", 30 + random.nextInt(31));
                if(c.remainingSeconds>0)active.put(owner,c);
            } catch(Exception ignored) {}
        }
    }

    private void loadCooldowns() {
        if (!cooldownFile.exists()) return;
        try { cooldownData.load(cooldownFile); }
        catch (Exception ex) { plugin.getLogger().warning("Could not load bounty cooldowns: " + ex.getMessage()); }
    }

    private void saveCooldowns() {
        try { cooldownData.save(cooldownFile); }
        catch (IOException ex) { plugin.getLogger().warning("Could not save bounty cooldowns: " + ex.getMessage()); }
    }

    private String format(long s) { return String.format(Locale.ROOT,"%02d:%02d",s/60,s%60); }
}
