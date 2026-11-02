package com.xzh.survival;

import net.md_5.bungee.api.ChatMessageType;
import net.md_5.bungee.api.chat.ClickEvent;
import net.md_5.bungee.api.chat.TextComponent;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.boss.BarColor;
import org.bukkit.boss.BarStyle;
import org.bukkit.boss.BossBar;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.*;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.EntityTargetLivingEntityEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.Vector;

import java.lang.reflect.Method;
import java.util.*;

public final class BossChallengeManager implements Listener {
    private static final long HOUR = 60L * 60L * 1000L;
    private static final long ACCEPT_WINDOW = 5L * 60L * 1000L;
    private final JavaPlugin plugin;
    private final Random random = new Random();
    private final NamespacedKey bossTag, tierTag, projectileTag, keyTag, keyTierTag, keyRewardTag, keyOwnerTag;
    private final Map<UUID, BossBar> bars = new HashMap<>();
    private UUID activeBossId;
    private UUID challengeOwner;
    private long challengeExpiresAt;
    private long nextChallengeAt;
    private UUID countdownPlayer;
    private long ticks;

    public BossChallengeManager(JavaPlugin plugin) {
        this.plugin = plugin;
        bossTag = new NamespacedKey(plugin, "boss_challenge_boss");
        tierTag = new NamespacedKey(plugin, "boss_challenge_tier");
        projectileTag = new NamespacedKey(plugin, "boss_challenge_projectile");
        keyTag = new NamespacedKey(plugin, "boss_challenge_reward_key");
        keyTierTag = new NamespacedKey(plugin, "boss_challenge_key_tier");
        keyRewardTag = new NamespacedKey(plugin, "boss_challenge_key_reward");
        keyOwnerTag = new NamespacedKey(plugin, "boss_challenge_key_owner");
        Bukkit.getPluginManager().registerEvents(this, plugin);
    }

    public void start() {
        nextChallengeAt = System.currentTimeMillis() + HOUR;
        NamespacedKey legacyBossTag = new NamespacedKey(plugin, "roaming_boss");
        for (World world : Bukkit.getWorlds()) {
            for (Entity entity : world.getEntities()) {
                if (entity instanceof LivingEntity living
                        && living.getPersistentDataContainer().has(legacyBossTag, PersistentDataType.BYTE)) {
                    living.remove();
                    continue;
                }
                if (entity instanceof LivingEntity living && isBoss(living)) {
                    if (activeBossId == null) activeBossId = living.getUniqueId();
                    else living.remove();
                    if (living.isValid()) track(living);
                }
            }
        }
        Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 20L, 20L);
    }

    public void shutdown() {
        for (BossBar bar : bars.values()) bar.removeAll();
        bars.clear();
    }

    private void tick() {
        ticks++;
        if (activeBossId != null) {
            Entity entity = Bukkit.getEntity(activeBossId);
            if (!(entity instanceof LivingEntity living) || living.isDead() || !living.isValid()) {
                removeBar(activeBossId);
                activeBossId = null;
            } else {
                updateBoss(living);
            }
        }
        if (challengeOwner != null && System.currentTimeMillis() >= challengeExpiresAt) {
            Bukkit.broadcastMessage("§d§lXZH BOSS CHALLENGE §8» §7Nobody accepted the challenge. It has expired.");
            challengeOwner = null;
        }
        if (challengeOwner == null && countdownPlayer == null && activeBossId == null
                && System.currentTimeMillis() >= nextChallengeAt) {
            announceChallenge();
        }
    }

    private void announceChallenge() {
        challengeExpiresAt = System.currentTimeMillis() + ACCEPT_WINDOW;
        nextChallengeAt = System.currentTimeMillis() + HOUR;
        Bukkit.broadcastMessage("§8§m----------------------------------------");
        Bukkit.broadcastMessage("§d§lXZH GLOBAL BOSS CHALLENGE");
        Bukkit.broadcastMessage("§fA boss is looking for a challenger! §7First player to accept gets the fight.");
        Bukkit.broadcastMessage("§7Entry fee: §e100 bank coins §8• §7Spawn distance: §e16 blocks");
        TextComponent line = new TextComponent("§a§l[ ACCEPT ]");
        line.setClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/xzhboss accept"));
        TextComponent gap = new TextComponent("     ");
        TextComponent reject = new TextComponent("§c§l[ REJECT ]");
        reject.setClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/xzhboss reject"));
        TextComponent row = new TextComponent("");
        row.addExtra(line); row.addExtra(gap); row.addExtra(reject);
        for (Player player : Bukkit.getOnlinePlayers()) player.spigot().sendMessage(row);
        Bukkit.broadcastMessage("§7Challenge closes in §e5 minutes§7. Only one player can claim it.");
        Bukkit.broadcastMessage("§8§m----------------------------------------");
    }

    @EventHandler
    public void onChallengeCommand(PlayerCommandPreprocessEvent event) {
        String[] parts = event.getMessage().trim().split("\\s+");
        if (parts.length == 0 || !parts[0].equalsIgnoreCase("/xzhboss")) return;
        event.setCancelled(true);
        Player player = event.getPlayer();
        if (parts.length != 2) {
            player.sendMessage("§d[XZH Boss] §7Use the global chat buttons, or /xzhboss accept|reject.");
            return;
        }
        if (parts[1].equalsIgnoreCase("reject")) {
            player.sendMessage("§7You rejected the boss challenge. You can still accept another challenge later.");
            return;
        }
        if (!parts[1].equalsIgnoreCase("accept")) return;
        accept(player);
    }

    private void accept(Player player) {
        long now = System.currentTimeMillis();
        if (challengeOwner == null || now >= challengeExpiresAt) {
            player.sendMessage("§cThere is no active boss challenge to accept.");
            return;
        }
        if (activeBossId != null || countdownPlayer != null) {
            player.sendMessage("§cA boss encounter is already in progress.");
            return;
        }
        if (!player.isOnline() || player.isDead() || player.getGameMode() == GameMode.SPECTATOR) {
            player.sendMessage("§cYou cannot accept a challenge in your current state.");
            return;
        }
        double bank = bankBalance(player.getUniqueId());
        if (!Double.isFinite(bank) || bank < 100.0) {
            player.sendMessage("§cYou need at least 100 coins in your bank to accept this challenge.");
            return;
        }
        // Reserve the challenge immediately so simultaneous clicks cannot claim it twice.
        challengeOwner = null;
        countdownPlayer = player.getUniqueId();
        Bukkit.broadcastMessage("§d§lXZH BOSS CHALLENGE §8» §e" + player.getName()
                + " §faccepted the challenge! §7A boss will arrive in 10 seconds.");
        if (!changeBank(player.getUniqueId(), -100.0)) {
            countdownPlayer = null;
            player.sendMessage("§cThe entry fee could not be charged. The challenge was not claimed.");
            Bukkit.broadcastMessage("§d§lXZH BOSS CHALLENGE §8» §7The claim failed; the challenge is available again.");
            announceRetryWindow();
            return;
        }
        player.sendMessage("§e100 coins were deducted from your bank. Prepare for the fight!");
        for (int second = 10; second >= 1; second--) {
            final int count = second;
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                if (!Objects.equals(countdownPlayer, player.getUniqueId())) return;
                if (!player.isOnline() || player.isDead()) {
                    refundEntry(player.getUniqueId());
                    countdownPlayer = null;
                    Bukkit.broadcastMessage("§d§lXZH BOSS CHALLENGE §8» §7" + player.getName()
                            + " could not begin the fight. The 100-coin entry fee was refunded.");
                    return;
                }
                if (count == 1) spawnEncounter(player);
                else Bukkit.broadcastMessage("§d§lBOSS ARRIVAL §8» §e" + count + "...");
            }, (long) (11 - second) * 20L);
        }
    }

    private void announceRetryWindow() {
        challengeExpiresAt = System.currentTimeMillis() + ACCEPT_WINDOW;
        challengeOwner = new UUID(0L, 0L);
    }

    private void spawnEncounter(Player player) {
        if (!player.isOnline() || player.isDead() || !Objects.equals(countdownPlayer, player.getUniqueId())) {
            refundEntry(player.getUniqueId());
            countdownPlayer = null;
            return;
        }
        Location spawn = findSpawn(player);
        if (spawn == null) {
            refundEntry(player.getUniqueId());
            countdownPlayer = null;
            player.sendMessage("§cNo safe boss spawn location was found. Your 100 coins were refunded.");
            Bukkit.broadcastMessage("§d§lXZH BOSS CHALLENGE §8» §7The encounter was postponed because no safe spawn point was found.");
            return;
        }
        LivingEntity boss = createBoss(spawn);
        if (boss == null) {
            refundEntry(player.getUniqueId());
            countdownPlayer = null;
            player.sendMessage("§cThe boss could not spawn. Your 100 coins were refunded.");
            return;
        }
        activeBossId = boss.getUniqueId();
        boss.getPersistentDataContainer().set(new NamespacedKey(plugin, "boss_challenge_owner"),
                PersistentDataType.STRING, player.getUniqueId().toString());
        countdownPlayer = null;
        track(boss);
        Bukkit.broadcastMessage("§d§lXZH BOSS CHALLENGE §8» §c" + tierName(tier(boss))
                + " Boss §fhas spawned 16 blocks from §e" + player.getName() + "§f!");
        player.sendMessage("§aThe boss fight has started. Defeat it to claim your glowing reward key.");
    }

    private Location findSpawn(Player player) {
        World world = player.getWorld();
        Location base = player.getLocation();
        for (int i = 0; i < 40; i++) {
            double angle = random.nextDouble() * Math.PI * 2.0;
            int x = base.getBlockX() + (int)Math.round(Math.cos(angle) * 16.0);
            int z = base.getBlockZ() + (int)Math.round(Math.sin(angle) * 16.0);
            int y = world.getHighestBlockYAt(x, z) + 1;
            if (y < world.getMinHeight() + 2 || y >= world.getMaxHeight() - 2) continue;
            Location loc = new Location(world, x + 0.5, y, z + 0.5);
            if (!world.getBlockAt(x, y - 1, z).getType().isSolid()
                    || !loc.getBlock().isPassable()
                    || !loc.clone().add(0, 1, 0).getBlock().isPassable()) continue;
            if (loc.distanceSquared(base) < 14.0 * 14.0 || loc.distanceSquared(base) > 18.0 * 18.0) continue;
            return loc;
        }
        return null;
    }

    private LivingEntity createBoss(Location spawn) {
        EntityType[] types = {EntityType.ZOMBIE, EntityType.SKELETON, EntityType.HUSK, EntityType.STRAY};
        Entity entity = spawn.getWorld().spawnEntity(spawn, types[random.nextInt(types.length)]);
        if (!(entity instanceof LivingEntity boss)) { entity.remove(); return null; }
        String tier = rollTier();
        double hp = tier.equals("legendary") ? 160.0 : tier.equals("rare") ? 100.0 : 60.0;
        double damage = tier.equals("legendary") ? 8.0 : tier.equals("rare") ? 6.0 : 4.0;
        if (boss.getAttribute(Attribute.MAX_HEALTH) != null) boss.getAttribute(Attribute.MAX_HEALTH).setBaseValue(hp);
        boss.setHealth(hp);
        if (boss.getAttribute(Attribute.ATTACK_DAMAGE) != null) boss.getAttribute(Attribute.ATTACK_DAMAGE).setBaseValue(damage);
        if (boss.getAttribute(Attribute.MOVEMENT_SPEED) != null) boss.getAttribute(Attribute.MOVEMENT_SPEED).setBaseValue(tier.equals("legendary") ? 0.32 : 0.29);
        boss.setCustomName(bossTitle(tier));
        boss.setCustomNameVisible(true);
        boss.setGlowing(true);
        boss.setRemoveWhenFarAway(false);
        boss.getPersistentDataContainer().set(bossTag, PersistentDataType.BYTE, (byte)1);
        boss.getPersistentDataContainer().set(tierTag, PersistentDataType.STRING, tier);
        equip(boss, tier);
        return boss;
    }

    private void equip(LivingEntity boss, String tier) {
        var equipment = boss.getEquipment();
        if (equipment == null) return;
        String metal = tier.equals("legendary") ? "NETHERITE" : tier.equals("rare") ? "DIAMOND" : "IRON";
        int protection = tier.equals("legendary") ? 4 : tier.equals("rare") ? 3 : 2;
        ItemStack[] armor = {
                new ItemStack(Material.valueOf(metal + "_HELMET")),
                new ItemStack(Material.valueOf(metal + "_CHESTPLATE")),
                new ItemStack(Material.valueOf(metal + "_LEGGINGS")),
                new ItemStack(Material.valueOf(metal + "_BOOTS"))
        };
        for (ItemStack item : armor) {
            item.addUnsafeEnchantment(Enchantment.PROTECTION, protection);
            item.addUnsafeEnchantment(Enchantment.UNBREAKING, 3);
        }
        equipment.setHelmet(armor[0]); equipment.setChestplate(armor[1]);
        equipment.setLeggings(armor[2]); equipment.setBoots(armor[3]);
        boolean ranged = boss.getType() == EntityType.SKELETON || boss.getType() == EntityType.STRAY;
        ItemStack weapon = new ItemStack(ranged ? Material.BOW :
                Material.valueOf(metal + "_SWORD"));
        weapon.addUnsafeEnchantment(ranged ? Enchantment.POWER : Enchantment.SHARPNESS, protection);
        if (!tier.equals("common")) weapon.addUnsafeEnchantment(ranged ? Enchantment.FLAME : Enchantment.FIRE_ASPECT, 1);
        equipment.setItemInMainHand(weapon);
        for (EquipmentSlot slot : List.of(EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET, EquipmentSlot.HAND, EquipmentSlot.OFF_HAND)) equipment.setDropChance(slot, 0f);
        boss.addPotionEffect(new PotionEffect(PotionEffectType.SPEED, Integer.MAX_VALUE, tier.equals("legendary") ? 1 : 0, true, false, false));
        if (tier.equals("legendary")) boss.addPotionEffect(new PotionEffect(PotionEffectType.RESISTANCE, Integer.MAX_VALUE, 0, true, false, false));
    }

    private void track(LivingEntity boss) {
        String tier = tier(boss);
        BossBar bar = Bukkit.createBossBar(bossTitle(tier), tier.equals("legendary") ? BarColor.RED : tier.equals("rare") ? BarColor.PURPLE : BarColor.BLUE, BarStyle.SOLID);
        bar.setVisible(true);
        bars.put(boss.getUniqueId(), bar);
    }

    private void updateBoss(LivingEntity boss) {
        BossBar bar = bars.get(boss.getUniqueId());
        if (bar == null) {
            track(boss);
            bar = bars.get(boss.getUniqueId());
        }
        double max = boss.getAttribute(Attribute.MAX_HEALTH) == null ? boss.getHealth() : boss.getAttribute(Attribute.MAX_HEALTH).getValue();
        bar.setProgress(Math.max(0.0, Math.min(1.0, boss.getHealth() / Math.max(1.0, max))));
        bar.setTitle(bossTitle(tier(boss)) + " §8• §c" + (int)Math.ceil(boss.getHealth()) + "§7/§c" + (int)max + " HP");
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (player.getWorld().equals(boss.getWorld()) && player.getLocation().distanceSquared(boss.getLocation()) <= 32.0 * 32.0) {
                if (!bar.getPlayers().contains(player)) bar.addPlayer(player);
            } else bar.removePlayer(player);
        }
        Player target = findTarget(boss, 40.0);
        if (target != null && ticks % 12L == 0L) flyBurst(boss, target);
        long interval = tier(boss).equals("legendary") ? 6L : tier(boss).equals("rare") ? 8L : 10L;
        if (target != null && ticks % interval == 0L) specialAttack(boss, target, tier(boss));
    }

    private void flyBurst(LivingEntity boss, Player target) {
        NamespacedKey flight = new NamespacedKey(plugin, "boss_flight_active");
        if (boss.getPersistentDataContainer().has(flight, PersistentDataType.BYTE)) return;
        boss.getPersistentDataContainer().set(flight, PersistentDataType.BYTE, (byte)1);
        boss.setGravity(false);
        Vector direction = target.getLocation().add(0, 1.0, 0).toVector().subtract(boss.getLocation().toVector());
        if (direction.lengthSquared() > 0.01) boss.setVelocity(direction.normalize().multiply(0.42).setY(0.28));
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (boss.isValid()) {
                boss.setGravity(true);
                boss.getPersistentDataContainer().remove(flight);
            }
        }, 80L);
    }

    private void specialAttack(LivingEntity boss, Player target, String tier) {
        Location origin = boss.getEyeLocation();
        Vector direction = target.getEyeLocation().toVector().subtract(origin.toVector()).normalize();
        int choice = random.nextInt(tier.equals("legendary") ? 4 : tier.equals("rare") ? 3 : 2);
        if (choice == 0) {
            SmallFireball fire = boss.getWorld().spawn(origin.add(direction.clone().multiply(1.2)), SmallFireball.class);
            fire.setDirection(direction); fire.setShooter(boss); fire.setIsIncendiary(false);
            fire.getPersistentDataContainer().set(projectileTag, PersistentDataType.BYTE, (byte)1);
        } else if (choice == 1) {
            Arrow arrow = boss.getWorld().spawnArrow(origin.add(direction.clone().multiply(1.2)), direction, 2.4f, 2.0f);
            arrow.setShooter(boss); arrow.setFireTicks(0); arrow.setDamage(tier.equals("legendary") ? 5.0 : 3.0);
            arrow.getPersistentDataContainer().set(projectileTag, PersistentDataType.BYTE, (byte)1);
        } else if (choice == 2) {
            Location center = target.getLocation().clone();
            boss.getWorld().spawnParticle(org.bukkit.Particle.FLAME, center.clone().add(0, 0.8, 0), 25, 0.8, 0.4, 0.8, 0.02);
            boss.getWorld().playSound(center, org.bukkit.Sound.ENTITY_CREEPER_PRIMED, 1f, 0.8f);
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                for (Player p : center.getWorld().getPlayers()) {
                    if (p.getLocation().distanceSquared(center) <= 3.0 * 3.0) {
                        p.damage(tier.equals("legendary") ? 7.0 : 4.0, boss);
                        Vector knock = p.getLocation().toVector().subtract(center.toVector()).normalize().multiply(0.7).setY(0.35);
                        p.setVelocity(knock);
                    }
                }
                center.getWorld().spawnParticle(org.bukkit.Particle.EXPLOSION, center, 1);
            }, 30L);
        } else {
            Fireball fire = boss.getWorld().spawn(origin.add(direction.clone().multiply(1.2)), Fireball.class);
            fire.setDirection(direction); fire.setShooter(boss); fire.setYield(0f); fire.setIsIncendiary(false);
            fire.getPersistentDataContainer().set(projectileTag, PersistentDataType.BYTE, (byte)1);
        }
    }

    @EventHandler
    public void onCountdownDeath(org.bukkit.event.entity.PlayerDeathEvent event) {
        UUID id = event.getEntity().getUniqueId();
        if (!Objects.equals(countdownPlayer, id)) return;
        countdownPlayer = null;
        refundEntry(id);
        Bukkit.broadcastMessage("§d§lXZH BOSS CHALLENGE §8» §7" + event.getEntity().getName()
                + " died before the boss arrived. The entry fee was refunded.");
    }

    @EventHandler(ignoreCancelled = true)
    public void onDamage(EntityDamageByEntityEvent event) {
        boolean bossVictim = event.getEntity() instanceof LivingEntity le && isBoss(le);
        boolean bossDamager = isBossDamager(event.getDamager());
        boolean taggedProjectile = event.getDamager() instanceof Projectile p
                && p.getPersistentDataContainer().has(projectileTag, PersistentDataType.BYTE);
        if (bossDamager && !(event.getEntity() instanceof Player)) {
            event.setCancelled(true);
            return;
        }
        if (taggedProjectile && !(event.getEntity() instanceof Player)) {
            event.setCancelled(true);
            return;
        }
        if (bossVictim && !(event.getDamager() instanceof Player)
                && !(event.getDamager() instanceof Projectile projectile && projectile.getShooter() instanceof Player)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onTarget(EntityTargetLivingEntityEvent event) {
        if (event.getEntity() instanceof LivingEntity source && isBoss(source)
                && !(event.getTarget() instanceof Player)) event.setCancelled(true);
        if (event.getTarget() instanceof LivingEntity target && isBoss(target)
                && !(event.getEntity() instanceof Player)) event.setCancelled(true);
    }

    @EventHandler
    public void onBossDeath(EntityDeathEvent event) {
        LivingEntity boss = event.getEntity();
        if (!isBoss(boss)) return;
        event.getDrops().clear();
        removeBar(boss.getUniqueId());
        activeBossId = null;
        String tier = tier(boss);
        int reward = rewardForTier(tier);
        String ownerText = boss.getPersistentDataContainer().get(new NamespacedKey(plugin, "boss_challenge_owner"), PersistentDataType.STRING);
        UUID owner;
        try { owner = UUID.fromString(ownerText); } catch (Exception ex) { owner = null; }
        ItemStack key = createRewardKey(tier, reward, owner);
        boss.getWorld().dropItemNaturally(boss.getLocation(), key);
        Player ownerPlayer = owner == null ? null : Bukkit.getPlayer(owner);
        if (ownerPlayer != null) ownerPlayer.sendMessage("§aBoss defeated! Your glowing " + tierName(tier) + " reward key has dropped at the boss location.");
        Bukkit.broadcastMessage("§d§lXZH BOSS CHALLENGE §8» §e" + tierName(tier) + " Boss defeated! §fA glowing reward key has dropped.");
    }

    private ItemStack createRewardKey(String tier, int reward, UUID owner) {
        ItemStack key = new ItemStack(Material.TRIAL_KEY);
        ItemMeta meta = key.getItemMeta();
        meta.setDisplayName("§d§l" + tierName(tier) + " Boss Reward Key");
        List<String> lore = new ArrayList<>();
        lore.add("§7Challenge boss: §f" + tierName(tier));
        lore.add("§7Reward: §6" + reward + " coins");
        lore.add("§7Entry fee: §e100 bank coins");
        lore.add("§7Accepted by: §f" + (owner == null ? "Unknown" : Bukkit.getOfflinePlayer(owner).getName()));
        lore.add("§eBring this key to an Armorer,");
        lore.add("§eWeaponsmith, or Toolsmith villager.");
        lore.add("§eRight-click the villager to redeem.");
        lore.add("§8One-time use • Bank payout");
        meta.setLore(lore);
        meta.addEnchant(Enchantment.UNBREAKING, 1, true);
        meta.addItemFlags(org.bukkit.inventory.ItemFlag.HIDE_ENCHANTS);
        meta.getPersistentDataContainer().set(keyTag, PersistentDataType.BYTE, (byte)1);
        meta.getPersistentDataContainer().set(keyTierTag, PersistentDataType.STRING, tier);
        meta.getPersistentDataContainer().set(keyRewardTag, PersistentDataType.INTEGER, reward);
        if (owner != null) meta.getPersistentDataContainer().set(keyOwnerTag, PersistentDataType.STRING, owner.toString());
        key.setItemMeta(meta);
        return key;
    }

    @EventHandler(ignoreCancelled = true)
    public void onRedeem(PlayerInteractEntityEvent event) {
        if (event.getHand() != EquipmentSlot.HAND || !(event.getRightClicked() instanceof Villager villager)) return;
        Villager.Profession profession = villager.getProfession();
        if (profession != Villager.Profession.ARMORER && profession != Villager.Profession.WEAPONSMITH
                && profession != Villager.Profession.TOOLSMITH) return;
        Player player = event.getPlayer();
        ItemStack held = player.getInventory().getItemInMainHand();
        if (held.getType().isAir() || !held.hasItemMeta()
                || !held.getItemMeta().getPersistentDataContainer().has(keyTag, PersistentDataType.BYTE)) return;
        event.setCancelled(true);
        var pdc = held.getItemMeta().getPersistentDataContainer();
        String ownerText = pdc.get(keyOwnerTag, PersistentDataType.STRING);
        if (ownerText != null && !ownerText.equals(player.getUniqueId().toString())) {
            player.sendMessage("§cThis reward key belongs to the player who accepted the challenge.");
            return;
        }
        int reward = pdc.getOrDefault(keyRewardTag, PersistentDataType.INTEGER, 0);
        if (reward <= 0 || !changeBank(player.getUniqueId(), reward)) {
            player.sendMessage("§cThe bank could not credit your reward. Your key has not been consumed.");
            return;
        }
        if (held.getAmount() <= 1) player.getInventory().setItemInMainHand(null);
        else held.setAmount(held.getAmount() - 1);
        player.sendMessage("§aReward key redeemed! §e" + reward + " coins §awere deposited into your bank.");
        Bukkit.broadcastMessage("§d[XZH Boss] §f" + player.getName() + " redeemed a " + tierName(pdc.getOrDefault(keyTierTag, PersistentDataType.STRING, "common")) + " Boss Reward Key for §e" + reward + " coins§f.");
    }

    private double bankBalance(UUID id) {
        Object manager = bankManager();
        if (manager == null) return -1;
        try { return ((Number)manager.getClass().getMethod("getBalance", UUID.class).invoke(manager, id)).doubleValue(); }
        catch (ReflectiveOperationException | RuntimeException ex) { return -1; }
    }

    private boolean changeBank(UUID id, double delta) {
        Object manager = bankManager();
        if (manager == null) return false;
        try {
            Method get = manager.getClass().getMethod("getBalance", UUID.class);
            Method set = manager.getClass().getMethod("setBalance", UUID.class, double.class);
            double balance = ((Number)get.invoke(manager, id)).doubleValue();
            if (!Double.isFinite(balance) || !Double.isFinite(delta) || balance + delta < 0) return false;
            set.invoke(manager, id, balance + delta);
            return true;
        } catch (ReflectiveOperationException | RuntimeException ex) {
            plugin.getLogger().warning("Boss challenge bank transaction failed: " + ex.getClass().getSimpleName());
            return false;
        }
    }

    private Object bankManager() {
        Plugin economy = Bukkit.getPluginManager().getPlugin("XZHEconomySystem");
        if (economy == null || !economy.isEnabled()) return null;
        try { return economy.getClass().getMethod("getBankManager").invoke(economy); }
        catch (ReflectiveOperationException | RuntimeException ex) { return null; }
    }

    private void refundEntry(UUID id) {
        if (!changeBank(id, 100.0)) plugin.getLogger().severe("Could not refund boss challenge entry fee to " + id);
    }

    private void removeBar(UUID id) {
        BossBar bar = bars.remove(id);
        if (bar != null) bar.removeAll();
    }

    private boolean isBoss(Entity entity) {
        return entity instanceof LivingEntity living
                && living.getPersistentDataContainer().has(bossTag, PersistentDataType.BYTE);
    }

    private boolean isBossDamager(Entity entity) {
        if (isBoss(entity)) return true;
        return entity instanceof Projectile p && p.getShooter() instanceof LivingEntity shooter && isBoss(shooter);
    }

    private String tier(LivingEntity boss) {
        return boss.getPersistentDataContainer().getOrDefault(tierTag, PersistentDataType.STRING, "common");
    }

    private Player findTarget(LivingEntity boss, double range) {
        Player best = null;
        double distanceBest = range * range;
        for (Player player : boss.getWorld().getPlayers()) {
            if (player.isDead() || player.getGameMode() == GameMode.SPECTATOR) continue;
            double d = player.getLocation().distanceSquared(boss.getLocation());
            if (d < distanceBest) { distanceBest = d; best = player; }
        }
        return best;
    }

    private String rollTier() {
        int roll = random.nextInt(100);
        return roll < 60 ? "common" : roll < 90 ? "rare" : "legendary";
    }

    private int rewardForTier(String tier) {
        return switch (tier) {
            case "legendary" -> 1500 + random.nextInt(1001);
            case "rare" -> 500 + random.nextInt(501);
            default -> 350 + random.nextInt(151);
        };
    }

    private String tierName(String tier) {
        return switch (tier.toLowerCase(Locale.ROOT)) {
            case "legendary" -> "Legendary";
            case "rare" -> "Rare";
            default -> "Common";
        };
    }

    private String bossTitle(String tier) {
        return switch (tier) {
            case "legendary" -> "§4§lLEGENDARY BOSS";
            case "rare" -> "§5§lRARE BOSS";
            default -> "§9§lCOMMON BOSS";
        };
    }
}
