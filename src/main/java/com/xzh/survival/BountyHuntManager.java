package com.xzh.survival;

import org.bukkit.*;
import org.bukkit.attribute.Attribute;
import org.bukkit.entity.*;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.block.Action;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
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

public final class BountyHuntManager implements Listener {
    private final JavaPlugin plugin;
    private final NamespacedKey bountyTierKey, contractOwnerKey, bowKey, arrowKey, targetKey;
    private final File dataFile;
    private final org.bukkit.configuration.file.YamlConfiguration data = new org.bukkit.configuration.file.YamlConfiguration();
    private final Map<UUID, Contract> active = new HashMap<>();
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

    public void shutdown() { save(); }

    @EventHandler(ignoreCancelled = true)
    public void onUse(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_AIR && event.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        Player player = event.getPlayer();
        ItemStack hand = event.getItem();
        if (hand == null || !hand.hasItemMeta()) return;
        ItemMeta meta = hand.getItemMeta();
        String tier = meta.getPersistentDataContainer().get(bountyTierKey, PersistentDataType.STRING);
        if (tier != null) {
            event.setCancelled(true);
            if (active.containsKey(player.getUniqueId())) { player.sendMessage("§cYou already have an active bounty contract."); return; }
            int emptyHotbarSlots = countEmptyHotbarSlots(player.getInventory());
            if (emptyHotbarSlots < 1) { player.sendMessage("§cBounty cancelled: keep at least 3 hotbar slots available for the map and mission equipment."); return; }
            int slot = player.getInventory().getHeldItemSlot();
            if (hand.getAmount() > 1) hand.setAmount(hand.getAmount() - 1);
            else player.getInventory().setItem(slot, null);
            long seconds = tier.equals("common") ? 25*60L : tier.equals("rare") ? 45*60L : 2*60*60L;
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
            Contract c = active.get(player.getUniqueId());
            if (c == null || c.target == null) { player.sendMessage("§cThis Tracking Arrow has no active bounty target."); return; }
            Entity target = Bukkit.getEntity(c.target);
            if (target == null || target.isDead()) { player.sendMessage("§cYour target cannot be located. The contract will remain active until its timer expires."); return; }
            Vector direction = target.getLocation().toVector().subtract(player.getLocation().toVector()).setY(0);
            if (direction.lengthSquared() > 0.01) {
                float yaw = (float) Math.toDegrees(Math.atan2(-direction.getX(), direction.getZ()));
                player.setRotation(yaw, player.getLocation().getPitch());
            }
            player.setCompassTarget(target.getLocation());
            player.sendActionBar("§dBounty target direction §8• §f" + (int) player.getLocation().distance(target.getLocation()) + " blocks away");
            player.spawnParticle(Particle.END_ROD, player.getEyeLocation().add(player.getEyeLocation().getDirection().multiply(1.2)), 8, 0.2, 0.2, 0.2, 0.02);
            player.sendMessage("§dTracking pulse sent. Follow the direction shown above.");
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
        am.setDisplayName("§dTracking Arrow");
        am.setLore(List.of("§7Hold and right-click to locate your bounty target."));
        am.getPersistentDataContainer().set(arrowKey, PersistentDataType.BYTE, (byte)1);
        am.getPersistentDataContainer().set(contractOwnerKey, PersistentDataType.STRING, c.owner.toString());
        arrow.setItemMeta(am);
        int mapSlot = inv.getHeldItemSlot();
        inv.setItem(mapSlot, bow);
        for (int i=0;i<9;i++) if (inv.getItem(i)==null || inv.getItem(i).getType().isAir()) { inv.setItem(i,arrow); break; }
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
        if (!w.isChunkLoaded(loc.getBlockX()>>4,loc.getBlockZ()>>4)) return;
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
        if (owner!=null) {
            int reward=c.tier.equals("legendary")?2500:c.tier.equals("rare")?900:300;
            payReward(owner,reward);
            owner.sendMessage("§a§lBOUNTY COMPLETE! §fReward: §e"+reward+" coins.");
        }
        finish(ownerId,"completed");
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) { save(); }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        Contract c=active.get(event.getPlayer().getUniqueId());
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
            if(c.remainingSeconds<=0) {
                p.sendMessage("§cBounty failed: your contract timer expired.");
                finish(c.owner,"expired");
            } else if(c.remainingSeconds%60==0) {
                p.sendActionBar("§d"+c.tier.toUpperCase(Locale.ROOT)+" BOUNTY §8• §f"+format(c.remainingSeconds)+" remaining");
            }
        }
    }

    private void finish(UUID owner,String reason) {
        Contract c=active.remove(owner);
        if(c==null)return;
        Entity target=c.target==null?null:Bukkit.getEntity(c.target);
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
        for(Entity e: inv.getHolder() instanceof Player p ? p.getWorld().getEntities() : Collections.<Entity>emptyList()) {
            if(e instanceof Item dropped && dropped.getItemStack().hasItemMeta()) {
                String tagged=dropped.getItemStack().getItemMeta().getPersistentDataContainer().get(contractOwnerKey,PersistentDataType.STRING);
                if(owner.toString().equals(tagged)) dropped.remove();
            }
        }
    }

    private void payReward(Player player,int amount) {
        Plugin economy=Bukkit.getPluginManager().getPlugin("XZHEconomySystem");
        if(economy==null||!economy.isEnabled()) { player.sendMessage("§cEconomy unavailable; reward could not be paid. Contact an admin."); return; }
        try {
            Method getBalance=economy.getClass().getMethod("getBalance",UUID.class);
            Method setBalance=economy.getClass().getMethod("setBalance",UUID.class,double.class);
            double balance=((Number)getBalance.invoke(economy,player.getUniqueId())).doubleValue();
            setBalance.invoke(economy,player.getUniqueId(),balance+amount);
        } catch(ReflectiveOperationException|RuntimeException ex) {
            plugin.getLogger().warning("Could not pay bounty reward: "+ex.getClass().getSimpleName());
            player.sendMessage("§cEconomy reward failed; contact an admin.");
        }
    }

    private void save() {
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
