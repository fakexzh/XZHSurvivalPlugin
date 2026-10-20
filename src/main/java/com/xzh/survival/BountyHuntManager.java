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
    private final NamespacedKey bountyTierKey, contractOwnerKey, bowKey, arrowKey, targetKey;
    private final File dataFile;
    private final org.bukkit.configuration.file.YamlConfiguration data = new org.bukkit.configuration.file.YamlConfiguration();
    private final Map<UUID, Contract> active = new HashMap<>();
    private final Map<UUID, BossBar> contractBossBars = new HashMap<>();
    private final Map<UUID, Location> activeTreasures = new HashMap<>();
    private final Random random = new Random();

    private static final class Contract {
        UUID owner, target;
        String tier, world;
        double x, y, z;
        long remainingSeconds, lastSavedAt;
        Contract(UUID owner, String tier, long seconds) { this.owner=owner; this.tier=tier; this.remainingSeconds=seconds; }
    }

    public BountyHuntManager(JavaPlugin plugin) {
        this.plugin = plugin;
        bountyTierKey = new NamespacedKey(plugin, "bounty_tier");
        contractOwnerKey = new NamespacedKey(plugin, "bounty_owner");
        bowKey = new NamespacedKey(plugin, "hunter_bow");
        arrowKey = new NamespacedKey(plugin, "tracking_arrow");
        targetKey = new NamespacedKey(plugin, "bounty_target");
        dataFile = new File(plugin.getDataFolder(), "bounties.yml");
    }

    public void start() {
        load();
        Bukkit.getPluginManager().registerEvents(this, plugin);
        Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 20L, 20L);
        Bukkit.getScheduler().runTaskTimer(plugin, this::save, 100L, 100L);
        for (Contract c : active.values()) restoreTarget(c);
    }

    public void shutdown() {
        save();
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
        boolean treasureMap = meta.getPersistentDataContainer().getKeys().stream()
                .anyMatch(key -> key.getNamespace().equals("xzheconomysystem")
                        && key.getKey().equals("treasure_hunt_map")
                        && meta.getPersistentDataContainer().has(key, PersistentDataType.BYTE));
        if (treasureMap) {
            event.setCancelled(true);
            startTreasureHunt(player, hand);
            return;
        }
        String tier = null;
        for (NamespacedKey key : meta.getPersistentDataContainer().getKeys()) {
            if (key.getNamespace().equals("xzheconomysystem") && key.getKey().equals("bounty_tier")) {
                tier = meta.getPersistentDataContainer().get(key, PersistentDataType.STRING);
                break;
            }
        }
        if (tier != null) {
            event.setCancelled(true);
            if (active.containsKey(player.getUniqueId())) { player.sendMessage("§cYou already have an active bounty contract."); return; }
            int emptyHotbarSlots = countEmptyHotbarSlots(player.getInventory());
            int neededEmptySlots = hand.getAmount() > 1 ? 2 : 1;
            if (emptyHotbarSlots < neededEmptySlots) {
                player.sendMessage("§cBounty map not used: keep enough empty hotbar slots for both the Hunter's Bow and Tracking Arrow.");
                return;
            }
            int slot = player.getInventory().getHeldItemSlot();
            if (hand.getAmount() > 1) hand.setAmount(hand.getAmount() - 1);
            else player.getInventory().setItem(slot, null);
            long defaultSeconds = tier.equals("common") ? 25*60L : tier.equals("rare") ? 45*60L : 2*60*60L;
            long seconds = plugin.getConfig().getLong("bounty-hunt.timers-seconds." + tier, defaultSeconds);
            Contract c = new Contract(player.getUniqueId(), tier, seconds);
            active.put(player.getUniqueId(), c);
            giveEquipment(player, c);
            spawnTarget(player, c);
            player.sendMessage("§d§lXZH BOUNTY HUNT");
            player.sendMessage("§a✦ Bounty contract accepted successfully!");
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
            // Spawn the same guided Eye-of-Ender entity players recognize, aimed at the live target.
            try {
                EnderSignal signal = player.getWorld().spawn(player.getEyeLocation(), EnderSignal.class);
                signal.setTargetLocation(targetLocation);
                signal.setDropItem(false);
                signal.setDespawnTimer(80);
            } catch (RuntimeException ex) {
                plugin.getLogger().warning("Could not spawn bounty tracking Eye of Ender: " + ex.getClass().getSimpleName());
            }
            Vector direction = targetLocation.toVector().subtract(player.getLocation().toVector()).setY(0);
            if (direction.lengthSquared() > 0.01) {
                float yaw = (float) Math.toDegrees(Math.atan2(-direction.getX(), direction.getZ()));
                player.setRotation(yaw, player.getLocation().getPitch());
            }
            player.setCompassTarget(targetLocation);
            player.sendActionBar("§dBounty target §8• §f" + (int) player.getLocation().distance(targetLocation) + " blocks away");
            player.getWorld().spawnParticle(Particle.END_ROD, player.getEyeLocation().add(player.getEyeLocation().getDirection().multiply(1.2)), 8, 0.2, 0.2, 0.2, 0.02);
            player.sendMessage("§dTracking Eye launched! Follow its direction and the compass bearing.");
        }
    }

    private void startTreasureHunt(Player player, ItemStack map) {
        Location existing = activeTreasures.get(player.getUniqueId());
        if (existing != null && existing.getBlock().getType() == Material.CHEST) {
            player.setCompassTarget(existing);
            player.sendMessage("§6You already have an active treasure hunt. Follow your compass to the buried chest.");
            return;
        }
        if (player.getWorld().getEnvironment() != World.Environment.NORMAL) {
            player.sendMessage("§cTreasure Hunt Maps can only be activated in the Overworld. Your map was not consumed.");
            return;
        }
        World world = player.getWorld();
        Location origin = player.getLocation();
        Location chestLocation = null;
        for (int attempt = 0; attempt < 40; attempt++) {
            double angle = random.nextDouble() * Math.PI * 2.0;
            int distance = 180 + random.nextInt(221);
            int x = origin.getBlockX() + (int)Math.round(Math.cos(angle) * distance);
            int z = origin.getBlockZ() + (int)Math.round(Math.sin(angle) * distance);
            int surfaceY = world.getHighestBlockYAt(x, z);
            if (surfaceY <= world.getMinHeight() + 3 || surfaceY >= world.getMaxHeight() - 3) continue;
            Material surfaceType = world.getBlockAt(x, surfaceY, z).getType();
            Material groundType = world.getBlockAt(x, surfaceY - 2, z).getType();
            if (!surfaceType.isSolid() || surfaceType.isLiquid() || surfaceType.name().contains("LEAVES")
                    || !groundType.isSolid()) continue;
            Location candidate = new Location(world, x, surfaceY - 1, z);
            if (candidate.getBlock().getType() == Material.BEDROCK
                    || candidate.getBlock().getType() == Material.CHEST) continue;
            chestLocation = candidate;
            break;
        }
        if (chestLocation == null) {
            player.sendMessage("§cCouldn't find a safe treasure location nearby. Your map was not consumed; try again in a different area.");
            return;
        }
        chestLocation.getBlock().setType(Material.CHEST, false);
        if (chestLocation.getBlock().getState() instanceof Chest chest) {
            Inventory inventory = chest.getBlockInventory();
            inventory.addItem(new ItemStack(Material.DIAMOND, 2 + random.nextInt(4)));
            inventory.addItem(new ItemStack(Material.EMERALD, 5 + random.nextInt(8)));
            inventory.addItem(new ItemStack(Material.GOLD_INGOT, 6 + random.nextInt(11)));
            inventory.addItem(new ItemStack(Material.IRON_INGOT, 12 + random.nextInt(13)));
            if (random.nextInt(3) == 0) inventory.addItem(new ItemStack(Material.GOLDEN_APPLE, 1));
            chest.update(true, false);
        }
        activeTreasures.put(player.getUniqueId(), chestLocation.clone());
        player.setCompassTarget(chestLocation);
        try {
            EnderSignal signal = world.spawn(player.getEyeLocation(), EnderSignal.class);
            signal.setTargetLocation(chestLocation);
            signal.setDropItem(false);
            signal.setDespawnTimer(80);
        } catch (RuntimeException ex) {
            plugin.getLogger().warning("Could not spawn treasure tracking Eye of Ender: " + ex.getClass().getSimpleName());
        }
        if (map.getAmount() <= 1) player.getInventory().setItemInMainHand(null);
        else map.setAmount(map.getAmount() - 1);
        player.updateInventory();
        player.sendMessage("§6§lXZH TREASURE HUNT");
        player.sendMessage("§eAn Eye of Ender has launched toward your buried treasure!");
        player.sendMessage("§fFollow your compass. The chest is hidden one block below the surface.");
        player.sendActionBar("§6Treasure located §8• §f" + (int)player.getLocation().distance(chestLocation) + " blocks away");
    }

    @EventHandler(ignoreCancelled = true)
    public void onTreasureChestOpen(InventoryOpenEvent event) {
        if (!(event.getPlayer() instanceof Player player)) return;
        Location opened = event.getInventory().getLocation();
        if (opened == null) return;
        UUID foundOwner = null;
        for (Map.Entry<UUID, Location> entry : activeTreasures.entrySet()) {
            Location target = entry.getValue();
            if (target.getWorld().equals(opened.getWorld()) && target.getBlockX() == opened.getBlockX()
                    && target.getBlockY() == opened.getBlockY() && target.getBlockZ() == opened.getBlockZ()) {
                foundOwner = entry.getKey();
                break;
            }
        }
        if (foundOwner == null) return;
        activeTreasures.remove(foundOwner);
        player.sendMessage("§a§lTREASURE FOUND! §fThe buried chest is yours to loot.");
        Player owner = Bukkit.getPlayer(foundOwner);
        if (owner != null && !owner.getUniqueId().equals(player.getUniqueId())) {
            owner.sendMessage("§cYour treasure chest was found by " + player.getName() + ".");
        }
        Bukkit.broadcastMessage("§6[XZH Treasure Hunt] §f" + player.getName() + " discovered a buried treasure!");
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
        am.setDisplayName("§dTracking Arrow");
        am.setLore(List.of("§7Hold and right-click to locate your bounty target."));
        am.getPersistentDataContainer().set(arrowKey, PersistentDataType.BYTE, (byte)1);
        am.getPersistentDataContainer().set(contractOwnerKey, PersistentDataType.STRING, c.owner.toString());
        arrow.setItemMeta(am);
        int bowSlot = -1;
        int arrowSlot = -1;
        for (int i = 0; i < 9; i++) {
            ItemStack current = inv.getItem(i);
            if (current == null || current.getType().isAir()) {
                if (bowSlot < 0) bowSlot = i;
                else { arrowSlot = i; break; }
            }
        }
        if (bowSlot < 0 || arrowSlot < 0) {
            player.sendMessage("§cNot enough empty hotbar slots for mission equipment. Keep the map and both equipment slots clear.");
            return;
        }
        inv.setItem(bowSlot, bow);
        inv.setItem(arrowSlot, arrow);
        player.updateInventory();
    }

    private void spawnTarget(Player player, Contract c) {
        World world = player.getWorld();
        Location base = player.getLocation();
        Location spawn = null;
        for (int tries=0;tries<24;tries++) {
            double angle = random.nextDouble()*Math.PI*2;
            int distance = 70 + random.nextInt(81);
            int x = base.getBlockX() + (int)Math.round(Math.cos(angle)*distance);
            int z = base.getBlockZ() + (int)Math.round(Math.sin(angle)*distance);
            int y = world.getHighestBlockYAt(x,z)+1;
            Location candidate = new Location(world,x+0.5,y,z+0.5);
            if (y < world.getMinHeight()+2 || y >= world.getMaxHeight()-3 || !candidate.getBlock().isPassable()
                    || !candidate.clone().add(0,1,0).getBlock().isPassable()) continue;
            spawn=candidate; break;
        }
        if (spawn==null) spawn=base.clone().add(0,1,0);
        EntityType type = switch (c.tier) {
            case "legendary" -> random.nextBoolean()?EntityType.RAVAGER:EntityType.PILLAGER;
            case "rare" -> new EntityType[]{EntityType.VINDICATOR,EntityType.HUSK,EntityType.STRAY,EntityType.PILLAGER}[random.nextInt(4)];
            default -> new EntityType[]{EntityType.ZOMBIE,EntityType.SKELETON,EntityType.SPIDER,EntityType.HUSK}[random.nextInt(4)];
        };
        Entity entity=world.spawnEntity(spawn,type);
        if (!(entity instanceof LivingEntity living)) { entity.remove(); return; }
        c.target=entity.getUniqueId(); c.world=world.getName(); c.x=spawn.getX(); c.y=spawn.getY(); c.z=spawn.getZ();
        living.setGlowing(true);
        living.setCustomName("§d§l"+c.tier.toUpperCase(Locale.ROOT)+" BOUNTY TARGET");
        living.setCustomNameVisible(true);
        refreshContractBossBar(c, living);
        living.getPersistentDataContainer().set(targetKey,PersistentDataType.STRING,c.owner.toString());
        if (living.getAttribute(Attribute.MAX_HEALTH)!=null) {
            double hp = c.tier.equals("legendary")?120:c.tier.equals("rare")?60:30;
            living.getAttribute(Attribute.MAX_HEALTH).setBaseValue(hp);
            living.setHealth(hp);
        }
        living.setRemoveWhenFarAway(false);
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
        int defaultReward=c.tier.equals("legendary")?900:c.tier.equals("rare")?500:200;
        int reward=plugin.getConfig().getInt("bounty-hunt.rewards."+c.tier,defaultReward);
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
            if (activeTarget instanceof LivingEntity living && !living.isDead()) refreshContractBossBar(c, living);
            else removeContractBossBar(c.owner);
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
            data.set(k+".tier",c.tier); data.set(k+".target",c.target==null?null:c.target.toString());
            data.set(k+".world",c.world); data.set(k+".x",c.x); data.set(k+".y",c.y); data.set(k+".z",c.z);
            data.set(k+".remaining",c.remainingSeconds);
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
                c.world=data.getString(key+".world"); c.x=data.getDouble(key+".x"); c.y=data.getDouble(key+".y"); c.z=data.getDouble(key+".z");
                if(c.remainingSeconds>0)active.put(owner,c);
            } catch(Exception ignored) {}
        }
    }

    private String format(long s) { return String.format(Locale.ROOT,"%02d:%02d",s/60,s%60); }
}
