package com.xzh.survival;

import net.md_5.bungee.api.ChatMessageType;
import net.md_5.bungee.api.chat.ClickEvent;
import net.md_5.bungee.api.chat.TextComponent;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.boss.BarColor;
import org.bukkit.boss.BarStyle;
import org.bukkit.boss.BossBar;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.*;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.entity.EntityChangeBlockEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.entity.EntityTargetLivingEntityEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.vehicle.VehicleMoveEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Vector;

import java.lang.reflect.Method;
import java.util.*;

public final class BossChallengeManager implements Listener, CommandExecutor, TabCompleter {
    private static final long HOUR_MS = 60L * 60L * 1000L;
    private static final long ACCEPT_WINDOW_MS = 5L * 60L * 1000L;
    private static final long COOLDOWN_MS = 3L * 60L * 60L * 1000L;
    private static final long EMPTY_ARENA_MS = 5L * 60L * 1000L;
    private static final int ENTRY_FEE = 100;
    private static final int ARENA_RADIUS = 32;
    private static final int ARENA_HEIGHT = 32;
    private static final long ENTRY_WINDOW_TICKS = 100L;

    private static final Map<String, List<String>> BOSS_NAMES = Map.of(
            "common", List.of("Dreadfang", "Grimjaw", "Ashborn", "Rotfang"),
            "rare", List.of("Nightveil", "Bloodbane", "Soulrender", "Darkthorn"),
            "legendary", List.of("Oblivion", "Doombringer", "Netherborn", "Ruin")
    );

    private final JavaPlugin plugin;
    private final Random random = new Random();
    private final NamespacedKey bossTag, tierTag, nameTag, projectileTag;
    private final NamespacedKey challengerIdTag, challengerNameTag, entryFeeTag, manualTag;
    private final NamespacedKey arenaXTag, arenaZTag, arenaMinYTag, arenaMaxYTag;
    private final NamespacedKey keyTag, keyIdTag, keyTierTag, keyNameTag, keyRewardTag;
    private final NamespacedKey keyAcceptedTag, keyDefeatedTag, keyEntryFeeTag;

    private final Map<UUID, BossBar> bossBars = new HashMap<>();
    private Chunk bossChunkTicket;
    private final Map<UUID, Long> cooldowns = new HashMap<>();
    private final Set<String> redeemedKeyIds = new HashSet<>();
    private final Map<String, String> previousNameByTier = new HashMap<>();

    private boolean challengeAvailable;
    private long challengeExpiresAt;
    private long nextChallengeAt;
    private String offeredTier;
    private String offeredName;

    private UUID countdownPlayer;
    private String countdownTier;
    private String countdownName;
    private int countdownSeconds;
    private BukkitTask countdownTask;

    private UUID activeBossId;
    private UUID challengerId;
    private String challengerName;
    private String acceptedByName;
    private int activeEntryFee;
    private boolean manualSpawn;
    private Arena arena;
    private long emptySince;
    private long ticks;
    private long combatTicks;
    private long nextFlightTick;
    private long flightEndsTick;
    private long nextMeleeTick;
    private long nextPillarTick;
    private boolean flightMode;
    private Location lastBossPosition;
    private int bossStuckTicks;
    private final Set<Location> temporaryArenaBlocks = new HashSet<>();

    private static final class Arena {
        private final World world;
        private final double centerX;
        private final double centerZ;
        private final int minY;
        private final int maxY;
        private boolean locked;
        private long lockAt;

        private Arena(World world, double centerX, double centerZ, int minY, int maxY) {
            this.world = world;
            this.centerX = centerX;
            this.centerZ = centerZ;
            this.minY = minY;
            this.maxY = maxY;
        }
    }

    public BossChallengeManager(JavaPlugin plugin) {
        this.plugin = plugin;
        bossTag = new NamespacedKey(plugin, "boss_challenge_boss");
        tierTag = new NamespacedKey(plugin, "boss_challenge_tier");
        nameTag = new NamespacedKey(plugin, "boss_challenge_name");
        projectileTag = new NamespacedKey(plugin, "boss_challenge_projectile");
        challengerIdTag = new NamespacedKey(plugin, "boss_challenge_challenger_id");
        challengerNameTag = new NamespacedKey(plugin, "boss_challenge_challenger_name");
        entryFeeTag = new NamespacedKey(plugin, "boss_challenge_entry_fee");
        manualTag = new NamespacedKey(plugin, "boss_challenge_manual");
        arenaXTag = new NamespacedKey(plugin, "boss_challenge_arena_x");
        arenaZTag = new NamespacedKey(plugin, "boss_challenge_arena_z");
        arenaMinYTag = new NamespacedKey(plugin, "boss_challenge_arena_min_y");
        arenaMaxYTag = new NamespacedKey(plugin, "boss_challenge_arena_max_y");
        keyTag = new NamespacedKey(plugin, "boss_challenge_reward_key");
        keyIdTag = new NamespacedKey(plugin, "boss_challenge_key_id");
        keyTierTag = new NamespacedKey(plugin, "boss_challenge_key_tier");
        keyNameTag = new NamespacedKey(plugin, "boss_challenge_key_name");
        keyRewardTag = new NamespacedKey(plugin, "boss_challenge_key_reward");
        keyAcceptedTag = new NamespacedKey(plugin, "boss_challenge_key_accepted_by");
        keyDefeatedTag = new NamespacedKey(plugin, "boss_challenge_key_defeated_by");
        keyEntryFeeTag = new NamespacedKey(plugin, "boss_challenge_key_entry_fee");

        Bukkit.getPluginManager().registerEvents(this, plugin);
        if (plugin.getCommand("boss") != null) {
            plugin.getCommand("boss").setExecutor(this);
            plugin.getCommand("boss").setTabCompleter(this);
        }
        if (plugin.getCommand("sorry") != null) plugin.getCommand("sorry").setExecutor(this);
        if (plugin.getCommand("xzhboss") != null) {
            plugin.getCommand("xzhboss").setExecutor(this);
            plugin.getCommand("xzhboss").setTabCompleter(this);
        }
    }

    public void start() {
        loadPersistentState();
        if (plugin.getConfig().contains("boss-challenge.next-challenge-at")) {
            nextChallengeAt = plugin.getConfig().getLong("boss-challenge.next-challenge-at");
        } else {
            nextChallengeAt = System.currentTimeMillis() + HOUR_MS;
            plugin.getConfig().set("boss-challenge.next-challenge-at", nextChallengeAt);
            plugin.saveConfig();
        }
        String savedWorldName = plugin.getConfig().getString("boss-challenge.active-boss-world");
        if (savedWorldName != null) {
            World savedWorld = Bukkit.getWorld(savedWorldName);
            if (savedWorld != null) {
                int chunkX = plugin.getConfig().getInt("boss-challenge.active-boss-chunk-x");
                int chunkZ = plugin.getConfig().getInt("boss-challenge.active-boss-chunk-z");
                savedWorld.getChunkAt(chunkX, chunkZ);
            }
        }

        NamespacedKey legacyBossTag = new NamespacedKey(plugin, "roaming_boss");
        for (World world : Bukkit.getWorlds()) {
            for (Entity entity : world.getEntities()) {
                if (!(entity instanceof LivingEntity living)) continue;
                if (living.getPersistentDataContainer().has(legacyBossTag, PersistentDataType.BYTE)) {
                    living.remove();
                    continue;
                }
                if (!isBoss(living)) continue;
                if (activeBossId == null) {
                    restoreEncounter(living);
                } else if (!activeBossId.equals(living.getUniqueId())) {
                    living.remove();
                } else {
                    trackBoss(living);
                }
            }
        }

        Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 20L, 20L);
        Bukkit.getScheduler().runTaskTimer(plugin, this::combatTick, 1L, 1L);
        Bukkit.getScheduler().runTaskTimer(plugin, this::drawArenaBoundary, 10L, 10L);
    }

    public void shutdown() {
        if (countdownTask != null) countdownTask.cancel();
        for (BossBar bar : bossBars.values()) bar.removeAll();
        bossBars.clear();
        if (bossChunkTicket != null) {
            bossChunkTicket.removePluginChunkTicket(plugin);
            bossChunkTicket = null;
        }
        cleanupTemporaryArenaBlocks();
        savePersistentState();
    }

    private void loadPersistentState() {
        cooldowns.clear();
        var section = plugin.getConfig().getConfigurationSection("boss-challenge.cooldowns");
        if (section != null) {
            for (String id : section.getKeys(false)) {
                long until = section.getLong(id, 0L);
                if (until > System.currentTimeMillis()) {
                    try { cooldowns.put(UUID.fromString(id), until); } catch (IllegalArgumentException ignored) {}
                }
            }
        }
        redeemedKeyIds.clear();
        redeemedKeyIds.addAll(plugin.getConfig().getStringList("boss-challenge.redeemed-key-ids"));
    }

    private void savePersistentState() {
        plugin.getConfig().set("boss-challenge.cooldowns", null);
        for (Map.Entry<UUID, Long> entry : cooldowns.entrySet()) {
            plugin.getConfig().set("boss-challenge.cooldowns." + entry.getKey(), entry.getValue());
        }
        plugin.getConfig().set("boss-challenge.redeemed-key-ids", new ArrayList<>(redeemedKeyIds));
        plugin.getConfig().set("boss-challenge.next-challenge-at", nextChallengeAt);
        if (activeBossId != null && arena != null) {
            plugin.getConfig().set("boss-challenge.active-boss-id", activeBossId.toString());
            plugin.getConfig().set("boss-challenge.active-boss-world", arena.world.getName());
            Chunk chunk = bossChunkTicket;
            if (chunk == null) {
                Entity active = Bukkit.getEntity(activeBossId);
                if (active != null) chunk = active.getChunk();
            }
            if (chunk != null) {
                plugin.getConfig().set("boss-challenge.active-boss-chunk-x", chunk.getX());
                plugin.getConfig().set("boss-challenge.active-boss-chunk-z", chunk.getZ());
            }
        } else {
            plugin.getConfig().set("boss-challenge.active-boss-id", null);
            plugin.getConfig().set("boss-challenge.active-boss-world", null);
            plugin.getConfig().set("boss-challenge.active-boss-chunk-x", null);
            plugin.getConfig().set("boss-challenge.active-boss-chunk-z", null);
        }
        plugin.saveConfig();
    }

    private void restoreEncounter(LivingEntity boss) {
        if (challengeAvailable) {
            challengeAvailable = false;
            Bukkit.broadcastMessage("§eAn existing boss encounter was restored; the pending global challenge has been postponed.");
        }
        if (countdownPlayer != null) {
            UUID pending = countdownPlayer;
            if (countdownTask != null) countdownTask.cancel();
            countdownTask = null;
            countdownPlayer = null;
            startCooldown(pending);
            Player pendingPlayer = Bukkit.getPlayer(pending);
            if (pendingPlayer != null) {
                showPrivateResult(pendingPlayer, "§cCHALLENGE CANCELLED", "An existing boss encounter was restored • 3-hour cooldown", Sound.ENTITY_VILLAGER_NO);
            }
            Bukkit.broadcastMessage("§cA pending boss challenge was cancelled because an existing encounter was restored. The entry fee was not refunded.");
        }
        activeBossId = boss.getUniqueId();
        PersistentDataContainer pdc = boss.getPersistentDataContainer();
        String tier = pdc.getOrDefault(tierTag, PersistentDataType.STRING, "common");
        String name = pdc.getOrDefault(nameTag, PersistentDataType.STRING, "Unknown");
        challengerName = pdc.getOrDefault(challengerNameTag, PersistentDataType.STRING, "Unknown");
        acceptedByName = challengerName;
        String challengerText = pdc.get(challengerIdTag, PersistentDataType.STRING);
        try { challengerId = challengerText == null || challengerText.isBlank() ? null : UUID.fromString(challengerText); }
        catch (IllegalArgumentException ex) { challengerId = null; }
        activeEntryFee = pdc.getOrDefault(entryFeeTag, PersistentDataType.INTEGER, 0);
        manualSpawn = pdc.getOrDefault(manualTag, PersistentDataType.BYTE, (byte)0) == (byte)1;

        double centerX = pdc.getOrDefault(arenaXTag, PersistentDataType.DOUBLE, boss.getLocation().getX());
        double centerZ = pdc.getOrDefault(arenaZTag, PersistentDataType.DOUBLE, boss.getLocation().getZ());
        int minY = pdc.getOrDefault(arenaMinYTag, PersistentDataType.INTEGER, boss.getLocation().getBlockY() - 2);
        int maxY = pdc.getOrDefault(arenaMaxYTag, PersistentDataType.INTEGER, minY + ARENA_HEIGHT);
        arena = new Arena(boss.getWorld(), centerX, centerZ, minY, maxY);
        arena.locked = true;
        arena.lockAt = 0L;
        boss.removePotionEffect(PotionEffectType.SPEED);
        if (boss instanceof Mob mob) {
            mob.setAware(true);
            mob.setAI(true);
        }
        emptySince = hasPlayersInsideArena() || (challengerId != null && Bukkit.getPlayer(challengerId) == null)
                ? 0L : System.currentTimeMillis();
        trackBoss(boss);
        plugin.getLogger().info("Restored active boss encounter: " + name + " (" + tier + ").");
    }

    private void tick() {
        ticks++;
        long now = System.currentTimeMillis();

        if (arena != null && !arena.locked && now >= arena.lockAt) {
            arena.locked = true;
            emptySince = hasPlayersInsideArena() ? 0L : now;
            Entity active = activeBossId == null ? null : Bukkit.getEntity(activeBossId);
            if (active instanceof Mob mob) {
                mob.setAware(true);
                mob.setAI(true);
            }
            Bukkit.broadcastMessage("§b§lBOSS FIGHT STARTED §8» §7The arena is now locked.");
        }

        if (activeBossId != null) {
            Entity entity = Bukkit.getEntity(activeBossId);
            if (!(entity instanceof LivingEntity boss) || boss.isDead() || !boss.isValid()) {
                removeBossBar(activeBossId);
                activeBossId = null;
                finishWithoutReward("The boss encounter ended unexpectedly.");
            } else {
                if (arena != null && arena.locked) {
                    if (hasPlayersInsideArena()
                            || (challengerId != null && Bukkit.getPlayer(challengerId) == null)) {
                        emptySince = 0L;
                    } else if (emptySince == 0L) {
                        emptySince = now;
                    } else if (now - emptySince >= EMPTY_ARENA_MS) {
                        despawnForEmptyArena(boss);
                    }
                }
            }
        }

        if (plugin.getConfig().getBoolean("boss-challenge.enabled", true)
                && challengeAvailable && now >= challengeExpiresAt) {
            challengeAvailable = false;
            Bukkit.broadcastMessage("§8§m----------------------------------------");
            Bukkit.broadcastMessage("§c§lBOSS CHALLENGE EXPIRED");
            Bukkit.broadcastMessage("§7Nobody accepted " + bossDisplay(offeredName, offeredTier) + ".");
            Bukkit.broadcastMessage("§8§m----------------------------------------");
        }

        if (plugin.getConfig().getBoolean("boss-challenge.enabled", true)
                && !challengeAvailable && countdownPlayer == null && activeBossId == null && now >= nextChallengeAt) {
            announceChallenge();
        }

        cooldowns.entrySet().removeIf(entry -> entry.getValue() <= now);
    }

    private void announceChallenge() {
        offeredTier = rollTier();
        offeredName = randomBossName(offeredTier);
        challengeAvailable = true;
        challengeExpiresAt = System.currentTimeMillis() + ACCEPT_WINDOW_MS;
        nextChallengeAt = System.currentTimeMillis() + HOUR_MS;
        plugin.getConfig().set("boss-challenge.next-challenge-at", nextChallengeAt);
        plugin.saveConfig();

        Bukkit.broadcastMessage("§8§m------------------------------------------------");
        Bukkit.broadcastMessage("§6§l⚔ GLOBAL BOSS CHALLENGE ⚔");
        Bukkit.broadcastMessage("§fA boss challenge has appeared: " + bossDisplay(offeredName, offeredTier) + "§f!");
        Bukkit.broadcastMessage("§7Entry fee: §e100 bank coins §8• §7Possible reward: " + rewardRange(offeredTier));
        Bukkit.broadcastMessage("§7First player to accept claims the challenge. Expires in §e5 minutes§7.");
        TextComponent accept = new TextComponent("§a§l[ ACCEPT ]");
        accept.setClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/xzhboss accept"));
        accept.setHoverEvent(new net.md_5.bungee.api.chat.HoverEvent(
                net.md_5.bungee.api.chat.HoverEvent.Action.SHOW_TEXT,
                TextComponent.fromLegacyText("§aAccept this boss challenge and pay 100 bank coins.")));
        TextComponent spacer = new TextComponent("     ");
        TextComponent reject = new TextComponent("§c§l[ REJECT ]");
        reject.setClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/xzhboss reject"));
        reject.setHoverEvent(new net.md_5.bungee.api.chat.HoverEvent(
                net.md_5.bungee.api.chat.HoverEvent.Action.SHOW_TEXT,
                TextComponent.fromLegacyText("§cDismiss this invitation. It remains available to other players.")));
        TextComponent row = new TextComponent("");
        row.addExtra(accept);
        row.addExtra(spacer);
        row.addExtra(reject);
        for (Player player : Bukkit.getOnlinePlayers()) player.spigot().sendMessage(row);
        Bukkit.broadcastMessage("§8§m------------------------------------------------");
    }

    private void handleAccept(Player player) {
        long now = System.currentTimeMillis();
        if (!challengeAvailable || now >= challengeExpiresAt) {
            player.sendMessage("§cThere is no active boss challenge to accept.");
            return;
        }
        if (activeBossId != null || countdownPlayer != null) {
            player.sendMessage("§cA boss encounter is already in progress.");
            return;
        }
        if (player.isDead() || player.getGameMode() == GameMode.SPECTATOR
                || player.getGameMode() == GameMode.CREATIVE) {
            player.sendMessage("§cYou must be in Survival or Adventure mode to accept a challenge.");
            return;
        }
        long cooldownUntil = cooldowns.getOrDefault(player.getUniqueId(), 0L);
        if (cooldownUntil > now) {
            player.sendMessage("§cYou are on boss cooldown for another " + formatDuration(cooldownUntil - now) + ".");
            return;
        }
        double balance = bankBalance(player.getUniqueId());
        if (!Double.isFinite(balance) || balance < ENTRY_FEE) {
            player.sendMessage("§cYou need at least 100 coins in your bank to accept this challenge.");
            return;
        }

        // Reserve the challenge before performing the bank write; all command handling runs on the server thread.
        challengeAvailable = false;
        countdownPlayer = player.getUniqueId();
        countdownTier = offeredTier;
        countdownName = offeredName;
        countdownSeconds = 0;

        if (!changeBank(player.getUniqueId(), -ENTRY_FEE)) {
            countdownPlayer = null;
            challengeAvailable = true;
            player.sendMessage("§cThe bank transaction failed. The challenge is still available; no fee was charged.");
            return;
        }

        Bukkit.broadcastMessage("§8§m----------------------------------------");
        Bukkit.broadcastMessage("§6§lBOSS CHALLENGE CLAIMED");
        Bukkit.broadcastMessage("§e" + player.getName() + " §faccepted " + bossDisplay(countdownName, countdownTier) + "§f!");
        Bukkit.broadcastMessage("§7Entry fee paid: §e100 bank coins §8• §7Arena entry window: §e10 seconds§7.");
        Bukkit.broadcastMessage("§8§m----------------------------------------");
        player.sendMessage("§a100 coins were deducted from your bank. Prepare for the fight!");
        player.sendTitle("§6BOSS CHALLENGE", "§f" + countdownName + " (" + tierName(countdownTier) + ") is preparing", 5, 50, 10);
        player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_PLING, 0.8f, 1.2f);

        countdownTask = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            if (countdownPlayer == null || !countdownPlayer.equals(player.getUniqueId())) {
                if (countdownTask != null) countdownTask.cancel();
                return;
            }
            Player currentPlayer = Bukkit.getPlayer(countdownPlayer);
            if (currentPlayer == null || !currentPlayer.isOnline()) return;
            if (currentPlayer.isDead()) {
                abortCountdown(currentPlayer.getUniqueId(), currentPlayer.getName(), "died before the boss spawned");
                return;
            }
            if (countdownSeconds <= 0) {
                if (countdownTask != null) countdownTask.cancel();
                countdownTask = null;
                spawnGlobalEncounter(currentPlayer);
                return;
            }
            if (countdownSeconds <= 5) {
                Bukkit.broadcastMessage("§6§lBOSS ARRIVAL §8» §e" + countdownSeconds + "...");
            }
            currentPlayer.spigot().sendMessage(ChatMessageType.ACTION_BAR,
                    TextComponent.fromLegacyText("§6" + countdownName + " §7arrives in §e" + countdownSeconds + "s"));
            countdownSeconds--;
        }, 0L, 20L);
    }

    private void abortCountdown(UUID playerId, String playerName, String reason) {
        if (countdownTask != null) countdownTask.cancel();
        countdownTask = null;
        countdownPlayer = null;
        startCooldown(playerId);
        Bukkit.broadcastMessage("§c" + playerName + " " + reason + ". The 100-coin entry fee was not refunded.");
        Player player = Bukkit.getPlayer(playerId);
        if (player != null) showPrivateResult(player, "§cCHALLENGE FORFEITED", "§e3-hour cooldown started", Sound.ENTITY_VILLAGER_NO);
    }

    private void spawnGlobalEncounter(Player player) {
        if (!player.isOnline() || player.isDead()) {
            abortCountdown(player.getUniqueId(), player.getName(), "could not enter the boss fight");
            return;
        }
        Location safeChallengerLocation = findSpawnNear(player.getLocation().clone(), 0.0, 8.0);
        if (safeChallengerLocation == null) {
            countdownPlayer = null;
            startCooldown(player.getUniqueId());
            player.sendMessage("§cA safe, open fight location could not be found. Your entry fee was not refunded.");
            showPrivateResult(player, "§cCHALLENGE FAILED", "§e3-hour cooldown started", Sound.ENTITY_VILLAGER_NO);
            Bukkit.broadcastMessage("§cThe boss challenge could not start because no valid fight location was available.");
            return;
        }
        player.teleport(safeChallengerLocation);
        Location challengerLocation = safeChallengerLocation.clone();
        Location bossLocation = findSpawnNear(challengerLocation, 14.0, 18.0);
        if (bossLocation == null) {
            countdownPlayer = null;
            startCooldown(player.getUniqueId());
            player.sendMessage("§cNo safe boss spawn location was found. Your entry fee was not refunded.");
            showPrivateResult(player, "§cCHALLENGE FAILED", "§e3-hour cooldown started", Sound.ENTITY_VILLAGER_NO);
            Bukkit.broadcastMessage("§cThe boss challenge could not start because no safe spawn location was found. The entry fee was not refunded.");
            return;
        }
        Location center = midpoint(challengerLocation, bossLocation);
        Arena newArena = createArena(center, challengerLocation.getY(), bossLocation.getY());
        teleportNearbyPlayersOutside(newArena, player);
        spawnBoss(bossLocation, countdownTier, countdownName, player.getUniqueId(), player.getName(),
                ENTRY_FEE, false, newArena);
        countdownPlayer = null;
    }

    private Location findSpawnNear(Location base, double min, double max) {
        World world = base.getWorld();
        if (world == null) return null;
        for (int attempt = 0; attempt < 100; attempt++) {
            double angle = random.nextDouble() * Math.PI * 2.0;
            double distance = min + random.nextDouble() * (max - min);
            int x = base.getBlockX() + (int)Math.round(Math.cos(angle) * distance);
            int z = base.getBlockZ() + (int)Math.round(Math.sin(angle) * distance);
            int y = world.getHighestBlockYAt(x, z) + 1;
            if (y < world.getMinHeight() + 2 || y >= world.getMaxHeight() - 2) continue;
            Location loc = new Location(world, x + 0.5, y, z + 0.5);
            if (!isSafeSpawn(loc)) continue;
            double actual = horizontalDistanceSquared(base, loc);
            if (actual < min * min || actual > max * max) continue;
            return loc;
        }
        return null;
    }

    private Location findManualSpawn(Location origin) {
        Location base = origin.clone();
        Vector direction = base.getDirection().setY(0);
        if (direction.lengthSquared() < 0.01) direction = new Vector(0, 0, 1);
        direction.normalize();
        for (int distance = 3; distance <= 8; distance++) {
            int x = base.getBlockX() + (int)Math.round(direction.getX() * distance);
            int z = base.getBlockZ() + (int)Math.round(direction.getZ() * distance);
            int y = base.getWorld().getHighestBlockYAt(x, z) + 1;
            Location candidate = new Location(base.getWorld(), x + 0.5, y, z + 0.5);
            if (isSafeSpawn(candidate)) return candidate;
        }
        return null;
    }

    private boolean isSafeSpawn(Location loc) {
        World world = loc.getWorld();
        if (world == null) return false;
        int x = loc.getBlockX(), y = loc.getBlockY(), z = loc.getBlockZ();
        Material ground = world.getBlockAt(x, y - 1, z).getType();
        Material feet = world.getBlockAt(x, y, z).getType();
        Material head = world.getBlockAt(x, y + 1, z).getType();
        if (!ground.isSolid() || world.getBlockAt(x, y - 1, z).isLiquid() || isHazardous(ground)) return false;
        if (!world.getBlockAt(x, y, z).isPassable()
                || !world.getBlockAt(x, y + 1, z).isPassable()
                || !world.getBlockAt(x, y + 2, z).isPassable()) return false;
        Material third = world.getBlockAt(x, y + 2, z).getType();
        if (world.getBlockAt(x, y, z).isLiquid() || world.getBlockAt(x, y + 1, z).isLiquid()
                || world.getBlockAt(x, y + 2, z).isLiquid()
                || isHazardous(feet) || isHazardous(head) || isHazardous(third)) return false;
        return true;
    }

    private boolean isHazardous(Material material) {
        return material == Material.MAGMA_BLOCK
                || material == Material.CACTUS
                || material == Material.CAMPFIRE
                || material == Material.SOUL_CAMPFIRE
                || material == Material.FIRE
                || material == Material.SOUL_FIRE
                || material == Material.SWEET_BERRY_BUSH
                || material == Material.WITHER_ROSE;
    }

    private Location midpoint(Location a, Location b) {
        return new Location(a.getWorld(), (a.getX() + b.getX()) / 2.0,
                (a.getY() + b.getY()) / 2.0, (a.getZ() + b.getZ()) / 2.0);
    }

    private double horizontalDistanceSquared(Location a, Location b) {
        double dx = a.getX() - b.getX();
        double dz = a.getZ() - b.getZ();
        return dx * dx + dz * dz;
    }

    private Arena createArena(Location center, double firstY, double secondY) {
        int low = (int)Math.floor(Math.min(firstY, secondY)) - 2;
        int high = Math.max(low + ARENA_HEIGHT, (int)Math.ceil(Math.max(firstY, secondY)) + 3);
        if (high - low > 48) high = low + 48;
        return new Arena(center.getWorld(), center.getX(), center.getZ(), low, high);
    }

    private void spawnBoss(Location location, String tier, String name, UUID acceptedId,
                           String acceptedName, int fee, boolean manual, Arena newArena) {
        // Vex supplies the visible wings and native flying model; movement and attack phases are controlled below.
        EntityType model = switch (tier) {
            case "rare" -> EntityType.PIGLIN_BRUTE;
            case "legendary" -> EntityType.WITHER_SKELETON;
            default -> EntityType.SKELETON;
        };
        Entity entity = location.getWorld().spawnEntity(location, model);
        if (!(entity instanceof LivingEntity boss)) {
            entity.remove();
            if (acceptedId != null) {
                startCooldown(acceptedId);
                Player player = Bukkit.getPlayer(acceptedId);
                if (player != null) showPrivateResult(player, "§cCHALLENGE FAILED", "§e3-hour cooldown started", Sound.ENTITY_VILLAGER_NO);
            }
            Bukkit.broadcastMessage("§cThe boss could not be spawned. No entry-fee refund is available.");
            return;
        }

        double hp = tier.equals("legendary") ? 150.0 : tier.equals("rare") ? 90.0 : 50.0;
        double damage = tier.equals("legendary") ? 7.0 : tier.equals("rare") ? 5.0 : 3.0;
        if (boss.getAttribute(Attribute.MAX_HEALTH) != null) boss.getAttribute(Attribute.MAX_HEALTH).setBaseValue(hp);
        boss.setHealth(hp);
        if (boss.getAttribute(Attribute.ATTACK_DAMAGE) != null) boss.getAttribute(Attribute.ATTACK_DAMAGE).setBaseValue(damage);
        if (boss.getAttribute(Attribute.MOVEMENT_SPEED) != null) boss.getAttribute(Attribute.MOVEMENT_SPEED).setBaseValue(tier.equals("legendary") ? 0.27 : tier.equals("rare") ? 0.25 : 0.22);
        boss.setCustomName(bossDisplay(name, tier));
        boss.setCustomNameVisible(true);
        boss.setGlowing(true);
        boss.setRemoveWhenFarAway(false);
        if (boss instanceof Mob mob) {
            mob.setAware(true);
            mob.setAI(false); // Enabled after the arena entry countdown.
        }
        boss.setGravity(true);

        PersistentDataContainer pdc = boss.getPersistentDataContainer();
        pdc.set(bossTag, PersistentDataType.BYTE, (byte)1);
        pdc.set(tierTag, PersistentDataType.STRING, tier);
        pdc.set(nameTag, PersistentDataType.STRING, name);
        pdc.set(challengerNameTag, PersistentDataType.STRING, acceptedName == null ? "Unknown" : acceptedName);
        if (acceptedId != null) pdc.set(challengerIdTag, PersistentDataType.STRING, acceptedId.toString());
        pdc.set(entryFeeTag, PersistentDataType.INTEGER, fee);
        pdc.set(manualTag, PersistentDataType.BYTE, manual ? (byte)1 : (byte)0);
        pdc.set(arenaXTag, PersistentDataType.DOUBLE, newArena.centerX);
        pdc.set(arenaZTag, PersistentDataType.DOUBLE, newArena.centerZ);
        pdc.set(arenaMinYTag, PersistentDataType.INTEGER, newArena.minY);
        pdc.set(arenaMaxYTag, PersistentDataType.INTEGER, newArena.maxY);

        equipBoss(boss, tier);
        activeBossId = boss.getUniqueId();
        challengerId = acceptedId;
        challengerName = acceptedName;
        acceptedByName = acceptedName;
        activeEntryFee = fee;
        manualSpawn = manual;
        arena = newArena;
        arena.locked = false;
        arena.lockAt = System.currentTimeMillis() + 10000L;
        emptySince = 0L;
        flightMode = false;
        combatTicks = 0L;
        nextFlightTick = 100L + random.nextInt(61);
        flightEndsTick = 0L;
        nextMeleeTick = 0L;
        nextPillarTick = 0L;
        lastBossPosition = boss.getLocation().clone();
        bossStuckTicks = 0;
        trackBoss(boss);
        savePersistentState();
        startArenaEntryCountdown(newArena);

        if (manual) {
            Bukkit.broadcastMessage("§6§lADMIN BOSS SPAWN §8» §f" + bossDisplay(name, tier) + " §7was spawned by §e" + acceptedName + "§7.");
        } else {
            Bukkit.broadcastMessage("§6§lBOSS CHALLENGE §8» §f" + bossDisplay(name, tier)
                    + " §7has spawned in the boss arena near §e" + acceptedName + "§7.");
        }
        Bukkit.broadcastMessage("§bThe arena is forming. §eNearby players have 10 seconds to enter before the fight starts.");
        Player acceptedPlayer = acceptedId == null ? null : Bukkit.getPlayer(acceptedId);
        if (acceptedPlayer != null) {
            acceptedPlayer.sendMessage("§aThe boss fight has started. Anyone inside the arena can help.");
            acceptedPlayer.playSound(acceptedPlayer.getLocation(), Sound.BLOCK_BEACON_ACTIVATE, 0.8f, 1.1f);
        }
    }

    private void equipBoss(LivingEntity boss, String tier) {
        var equipment = boss.getEquipment();
        if (equipment == null) return;
        String metal = tier.equals("legendary") ? "NETHERITE" : tier.equals("rare") ? "DIAMOND" : "IRON";
        int level = tier.equals("legendary") ? 3 : tier.equals("rare") ? 2 : 1;
        ItemStack[] armor = {
                new ItemStack(Material.valueOf(metal + "_HELMET")),
                new ItemStack(Material.valueOf(metal + "_CHESTPLATE")),
                new ItemStack(Material.valueOf(metal + "_LEGGINGS")),
                new ItemStack(Material.valueOf(metal + "_BOOTS"))
        };
        for (ItemStack item : armor) {
            item.addUnsafeEnchantment(Enchantment.PROTECTION, level);
            item.addUnsafeEnchantment(Enchantment.UNBREAKING, 3);
        }
        equipment.setHelmet(armor[0]);
        equipment.setChestplate(armor[1]);
        equipment.setLeggings(armor[2]);
        equipment.setBoots(armor[3]);
        ItemStack weapon = new ItemStack(Material.valueOf(metal + "_SWORD"));
        weapon.addUnsafeEnchantment(Enchantment.SHARPNESS, level);
        equipment.setItemInMainHand(weapon);
        for (EquipmentSlot slot : List.of(EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS,
                EquipmentSlot.FEET, EquipmentSlot.HAND, EquipmentSlot.OFF_HAND)) {
            equipment.setDropChance(slot, 0.0f);
        }
        if (tier.equals("legendary")) {
            boss.addPotionEffect(new PotionEffect(PotionEffectType.RESISTANCE, Integer.MAX_VALUE, 0, true, false, false));
        }
    }

    private void trackBoss(LivingEntity boss) {
        removeBossBar(boss.getUniqueId());
        updateBossChunkTicket(boss);
        String tier = tierOf(boss);
        BossBar bar = Bukkit.createBossBar(bossDisplay(nameOf(boss), tier),
                tier.equals("legendary") ? BarColor.RED : tier.equals("rare") ? BarColor.PURPLE : BarColor.BLUE,
                BarStyle.SOLID);
        bar.setVisible(true);
        bossBars.put(boss.getUniqueId(), bar);
    }

    private void combatTick() {
        if (activeBossId == null) return;
        Entity entity = Bukkit.getEntity(activeBossId);
        if (!(entity instanceof LivingEntity boss) || !boss.isValid() || boss.isDead()) return;
        combatTicks++;
        updateBoss(boss);
    }

    private void updateBoss(LivingEntity boss) {
        updateBossChunkTicket(boss);
        BossBar bar = bossBars.get(boss.getUniqueId());
        if (bar == null) {
            trackBoss(boss);
            bar = bossBars.get(boss.getUniqueId());
        }
        if (bar != null && combatTicks % 10L == 0L) {
            double max = boss.getAttribute(Attribute.MAX_HEALTH) == null
                    ? boss.getHealth() : boss.getAttribute(Attribute.MAX_HEALTH).getValue();
            bar.setProgress(Math.max(0.0, Math.min(1.0, boss.getHealth() / Math.max(1.0, max))));
            bar.setTitle(bossDisplay(nameOf(boss), tierOf(boss)) + " §8• §c"
                    + (int)Math.ceil(boss.getHealth()) + "§7/§c" + (int)max + " HP");
            for (Player player : Bukkit.getOnlinePlayers()) {
                if (player.getWorld().equals(boss.getWorld())
                        && player.getLocation().distanceSquared(boss.getLocation()) <= 48.0 * 48.0) {
                    if (!bar.getPlayers().contains(player)) bar.addPlayer(player);
                } else {
                    bar.removePlayer(player);
                }
            }
        }

        if (arena != null && !arena.locked) {
            if (boss instanceof Mob mob) mob.setAI(false);
            return;
        }
        Player target = findTargetInsideArena(boss, 80.0);
        if (target == null) {
            if (boss instanceof Mob mob) mob.setTarget(null);
            return;
        }
        if (boss instanceof Mob mob) {
            mob.setAware(true);
            mob.setAI(true);
            mob.setTarget(target);
        }
        if (combatTicks % 10L == 0L) {
            if (lastBossPosition != null
                    && boss.getLocation().distanceSquared(lastBossPosition) < 0.04
                    && boss.getLocation().distanceSquared(target.getLocation()) > 16.0) {
                bossStuckTicks += 10;
            } else {
                bossStuckTicks = 0;
            }
            lastBossPosition = boss.getLocation().clone();
            if (bossStuckTicks >= 30) {
                Vector escape = target.getLocation().toVector().subtract(boss.getLocation().toVector());
                escape.setY(0.0);
                if (escape.lengthSquared() > 0.001) {
                    boss.setVelocity(escape.normalize().multiply(0.48).setY(0.48));
                }
                bossStuckTicks = 0;
            }
        }

        if (!flightMode && combatTicks >= nextFlightTick) {
            flightMode = true;
            flightEndsTick = combatTicks + 65L + random.nextInt(26);
            boss.setGravity(false);
            boss.getWorld().playSound(boss.getLocation(), Sound.ENTITY_VEX_CHARGE, 0.9f, 1.15f);
            boss.getWorld().spawnParticle(org.bukkit.Particle.CLOUD, boss.getLocation(), 12, 0.35, 0.25, 0.35, 0.04);
        } else if (flightMode && combatTicks >= flightEndsTick) {
            flightMode = false;
            boss.setGravity(true);
            nextFlightTick = combatTicks + (tierOf(boss).equals("common") ? 240L : 180L) + random.nextInt(101);
            boss.getWorld().playSound(boss.getLocation(), Sound.ENTITY_VEX_DEATH, 0.55f, 1.5f);
        }

        Vector toward = target.getEyeLocation().toVector().subtract(boss.getLocation().toVector());
        if (toward.lengthSquared() < 0.001) return;
        Vector direction = toward.clone().normalize();

        if (flightMode) {
            boss.setGravity(false);
            Vector tangent = new Vector(-direction.getZ(), 0.0, direction.getX())
                    .multiply(0.16 * Math.sin(combatTicks / 12.0));
            Vector velocity = direction.multiply(0.34).add(tangent);
            velocity.setY(Math.max(-0.16, Math.min(0.28, toward.getY() * 0.09)));
            boss.setVelocity(velocity);
            long flightAttackInterval = tierOf(boss).equals("legendary") ? 24L : tierOf(boss).equals("rare") ? 30L : 38L;
            if (combatTicks % flightAttackInterval == 0L) shootFlightFireball(boss, target);
            return;
        }

        // Ground phase: native mob pathfinding handles sword pursuit; velocity helps it escape obstacles.
        boss.setGravity(true);
        Vector horizontal = new Vector(direction.getX(), 0.0, direction.getZ());
        if (horizontal.lengthSquared() > 0.001) horizontal.normalize().multiply(0.24);
        double lift = target.getLocation().getY() > boss.getLocation().getY() + 2.0 ? 0.13 : -0.06;
        boss.setVelocity(new Vector(horizontal.getX(), lift, horizontal.getZ()));

        if (boss.getType() == EntityType.SKELETON
                && boss.getLocation().distanceSquared(target.getLocation()) <= 4.0
                && combatTicks >= nextMeleeTick) {
            target.damage(3.0, boss);
            Vector knockback = target.getLocation().toVector().subtract(boss.getLocation().toVector());
            if (knockback.lengthSquared() > 0.001) {
                target.setVelocity(knockback.normalize().multiply(0.28).setY(0.16));
            }
            nextMeleeTick = combatTicks + 25L;
        }

        long attackInterval = tierOf(boss).equals("legendary") ? 50L
                : tierOf(boss).equals("rare") ? 65L : 90L;
        if (combatTicks % attackInterval == 0L) specialAttack(boss, target, tierOf(boss));
        if (target.getLocation().getY() > boss.getLocation().getY() + 3.0
                && combatTicks >= nextPillarTick) {
            buildTemporaryStep(boss, target);
            nextPillarTick = combatTicks + 24L;
        }
    }

    private void shootFlightFireball(LivingEntity boss, Player target) {
        Location origin = boss.getEyeLocation();
        Vector direction = target.getEyeLocation().toVector().subtract(origin.toVector());
        if (direction.lengthSquared() < 0.001) return;
        direction.normalize();
        SmallFireball fire = boss.getWorld().spawn(
                origin.clone().add(direction.clone().multiply(1.1)), SmallFireball.class);
        fire.setDirection(direction);
        fire.setShooter(boss);
        fire.setIsIncendiary(false);
        fire.getPersistentDataContainer().set(projectileTag, PersistentDataType.BYTE, (byte)1);
        boss.getWorld().playSound(origin, Sound.ENTITY_BLAZE_SHOOT, 0.7f, 1.2f);
    }

    private void buildTemporaryStep(LivingEntity boss, Player target) {
        if (arena == null || boss.getWorld() != arena.world) return;
        Location at = boss.getLocation();
        Vector toward = target.getLocation().toVector().subtract(at.toVector());
        Vector horizontal = new Vector(toward.getX(), 0.0, toward.getZ());
        if (horizontal.lengthSquared() < 0.001) return;
        horizontal.normalize();
        int x = at.getBlockX() + (int)Math.round(horizontal.getX());
        int y = at.getBlockY();
        int z = at.getBlockZ() + (int)Math.round(horizontal.getZ());
        Location blockLocation = new Location(arena.world, x, y, z);
        Location center = blockLocation.clone().add(0.5, 0.5, 0.5);
        if (!isInsideArena(center) || y < arena.minY || y >= arena.maxY) return;
        org.bukkit.block.Block block = arena.world.getBlockAt(x, y, z);
        if (!block.getType().isAir()) return;
        block.setType(Material.COBBLESTONE, false);
        temporaryArenaBlocks.add(blockLocation);
        boss.setVelocity(boss.getVelocity().setY(0.38));
        arena.world.spawnParticle(org.bukkit.Particle.BLOCK,
                center, 8, 0.25, 0.2, 0.25, Material.COBBLESTONE.createBlockData());
    }

    @EventHandler(ignoreCancelled = true)
    public void onTemporaryArenaBlockBreak(BlockBreakEvent event) {
        Location key = new Location(event.getBlock().getWorld(),
                event.getBlock().getX(), event.getBlock().getY(), event.getBlock().getZ());
        temporaryArenaBlocks.remove(key);
    }

    private void cleanupTemporaryArenaBlocks() {
        for (Location location : new HashSet<>(temporaryArenaBlocks)) {
            if (location.getWorld() == null) continue;
            org.bukkit.block.Block block = location.getBlock();
            if (block.getType() == Material.COBBLESTONE) block.setType(Material.AIR, false);
        }
        temporaryArenaBlocks.clear();
    }

    private void specialAttack(LivingEntity boss, Player target, String tier) {
        Location origin = boss.getEyeLocation();
        Vector direction = target.getEyeLocation().toVector().subtract(origin.toVector());
        if (direction.lengthSquared() < 0.001) return;
        direction.normalize();
        int attack = random.nextInt(4);

        // 0: Blaze-style fire shot.
        if (attack == 0) {
            SmallFireball fire = boss.getWorld().spawn(
                    origin.clone().add(direction.clone().multiply(1.2)), SmallFireball.class);
            fire.setDirection(direction);
            fire.setShooter(boss);
            fire.setIsIncendiary(false);
            fire.getPersistentDataContainer().set(projectileTag, PersistentDataType.BYTE, (byte)1);
            boss.getWorld().playSound(origin, Sound.ENTITY_BLAZE_SHOOT, 0.8f, 1.0f);
            return;
        }

        // 1: Aimed arrow volley. The damage scales by tier, not by fire enchantments.
        if (attack == 1) {
            Arrow arrow = boss.getWorld().spawnArrow(
                    origin.clone().add(direction.clone().multiply(1.2)), direction, 2.2f, 1.5f);
            arrow.setShooter(boss);
            arrow.setFireTicks(0);
            arrow.setDamage(tier.equals("legendary") ? 5.0 : tier.equals("rare") ? 3.5 : 2.5);
            arrow.getPersistentDataContainer().set(projectileTag, PersistentDataType.BYTE, (byte)1);
            boss.getWorld().playSound(origin, Sound.ENTITY_SKELETON_SHOOT, 0.8f, 0.9f);
            return;
        }

        // 2: Telegraph a TNT blast, then apply controlled damage without terrain griefing.
        if (attack == 2) {
            Location blastCenter = target.getLocation().clone();
            TNTPrimed tnt = boss.getWorld().spawn(blastCenter.clone().add(0.0, 0.2, 0.0), TNTPrimed.class);
            tnt.setFuseTicks(30);
            tnt.setYield(0.0f);
            tnt.setIsIncendiary(false);
            tnt.getPersistentDataContainer().set(projectileTag, PersistentDataType.BYTE, (byte)1);
            boss.getWorld().playSound(blastCenter, Sound.ENTITY_TNT_PRIMED, 0.9f, 1.0f);
            boss.getWorld().spawnParticle(org.bukkit.Particle.FLAME, blastCenter, 16, 0.65, 0.25, 0.65, 0.01);
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                if (!tnt.isValid()) return;
                tnt.remove();
                if (arena == null || blastCenter.getWorld() != arena.world) return;
                double damage = tier.equals("legendary") ? 7.0 : tier.equals("rare") ? 5.0 : 3.0;
                for (Player player : blastCenter.getWorld().getPlayers()) {
                    if (player.isDead() || !isInsideArena(player.getLocation())
                            || player.getLocation().distanceSquared(blastCenter) > 16.0) continue;
                    player.damage(damage, boss);
                    Vector push = player.getLocation().toVector().subtract(blastCenter.toVector());
                    if (push.lengthSquared() > 0.001) {
                        player.setVelocity(push.normalize().multiply(0.55).setY(0.28));
                    }
                }
                blastCenter.getWorld().spawnParticle(org.bukkit.Particle.EXPLOSION, blastCenter, 1);
                blastCenter.getWorld().playSound(blastCenter, Sound.ENTITY_GENERIC_EXPLODE, 0.8f, 1.1f);
            }, 30L);
            return;
        }

        // 3: Close the gap with a sword charge.
        Vector charge = target.getLocation().toVector().subtract(boss.getLocation().toVector());
        if (charge.lengthSquared() > 0.001) {
            boss.setVelocity(charge.normalize().multiply(tier.equals("legendary") ? 0.72
                    : tier.equals("rare") ? 0.62 : 0.52).setY(0.18));
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                if (!boss.isValid() || boss.isDead() || !target.isOnline()
                        || !isInsideArena(target.getLocation())) return;
                if (boss.getLocation().distanceSquared(target.getLocation()) <= 9.0) {
                    target.damage(tier.equals("legendary") ? 5.0 : tier.equals("rare") ? 3.5 : 2.5, boss);
                }
            }, 8L);
        }
    }

    private void drawArenaBoundary() {
        if (arena == null) return;
        if (activeBossId == null) return;
        World world = arena.world;
        double radius = ARENA_RADIUS;
        int ringPoints = 48;
        for (int i = 0; i < ringPoints; i++) {
            double angle = (Math.PI * 2.0 * i) / ringPoints;
            double x = arena.centerX + Math.cos(angle) * radius;
            double z = arena.centerZ + Math.sin(angle) * radius;
            world.spawnParticle(org.bukkit.Particle.SOUL_FIRE_FLAME,
                    new Location(world, x, arena.minY + 0.15, z), 1, 0, 0, 0, 0);
            world.spawnParticle(org.bukkit.Particle.SOUL_FIRE_FLAME,
                    new Location(world, x, arena.maxY - 0.15, z), 1, 0, 0, 0, 0);
        }
        for (int i = 0; i < 16; i++) {
            double angle = (Math.PI * 2.0 * i) / 16.0;
            double x = arena.centerX + Math.cos(angle) * radius;
            double z = arena.centerZ + Math.sin(angle) * radius;
            for (int y = arena.minY + 2; y < arena.maxY; y += 2) {
                world.spawnParticle(org.bukkit.Particle.SOUL_FIRE_FLAME,
                        new Location(world, x, y, z), 1, 0, 0, 0, 0);
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onArenaMove(PlayerMoveEvent event) {
        if (arena == null || event.getTo() == null) return;
        boolean fromInside = isInsideArena(event.getFrom());
        boolean toInside = isInsideArena(event.getTo());
        boolean acceptedPlayer = challengerId != null
                && challengerId.equals(event.getPlayer().getUniqueId());
        if (fromInside && !toInside && acceptedPlayer) {
            event.setTo(event.getFrom());
            return;
        }
        if (arena.locked && fromInside != toInside) {
            event.setTo(event.getFrom());
        }
    }

    private boolean isInsideArena(Location location) {
        if (arena == null || location == null || location.getWorld() == null
                || !location.getWorld().equals(arena.world)) return false;
        if (location.getY() < arena.minY || location.getY() > arena.maxY) return false;
        double dx = location.getX() - arena.centerX;
        double dz = location.getZ() - arena.centerZ;
        return dx * dx + dz * dz <= ARENA_RADIUS * ARENA_RADIUS;
    }

    private boolean hasPlayersInsideArena() {
        if (arena == null) return false;
        for (Player player : arena.world.getPlayers()) {
            if (player.isOnline() && !player.isDead() && player.getGameMode() != GameMode.SPECTATOR
                    && player.getGameMode() != GameMode.CREATIVE
                    && isInsideArena(player.getLocation())) return true;
        }
        return false;
    }

    @EventHandler
    public void onChunkLoad(ChunkLoadEvent event) {
        NamespacedKey legacyBossTag = new NamespacedKey(plugin, "roaming_boss");
        for (Entity entity : event.getChunk().getEntities()) {
            if (!(entity instanceof LivingEntity living)) continue;
            if (living.getPersistentDataContainer().has(legacyBossTag, PersistentDataType.BYTE)) {
                living.remove();
                continue;
            }
            if (!isBoss(living)) continue;
            if (activeBossId == null) {
                restoreEncounter(living);
            } else if (!activeBossId.equals(living.getUniqueId())) {
                living.remove();
            }
        }
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        Player joining = event.getPlayer();
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (activeBossId == null || !joining.isOnline()) return;
            Entity active = Bukkit.getEntity(activeBossId);
            if (!(active instanceof LivingEntity boss) || !boss.isValid()) return;
            if (joining.getWorld().equals(boss.getWorld())
                    && joining.getLocation().distanceSquared(boss.getLocation()) <= 48.0 * 48.0) {
                BossBar bar = bossBars.get(activeBossId);
                if (bar == null) {
                    trackBoss(boss);
                    bar = bossBars.get(activeBossId);
                }
                if (bar != null && !bar.getPlayers().contains(joining)) bar.addPlayer(joining);
            }
        });
        Long cooldownUntil = cooldowns.get(event.getPlayer().getUniqueId());
        if (cooldownUntil != null && cooldownUntil > System.currentTimeMillis()) {
            Player player = event.getPlayer();
            player.sendMessage("§eYour boss challenge cooldown has " + formatDuration(cooldownUntil - System.currentTimeMillis()) + " remaining.");
            player.sendTitle("§eBOSS COOLDOWN", "§7" + formatDuration(cooldownUntil - System.currentTimeMillis()) + " remaining", 10, 60, 20);
            player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_PLING, 0.8f, 0.8f);
        }
        if (challengerId != null && challengerId.equals(event.getPlayer().getUniqueId())) {
            emptySince = 0L;
            event.getPlayer().sendMessage("§eYour boss encounter is still active. Use /sorry to surrender.");
            Bukkit.getScheduler().runTask(plugin, () -> {
                Player rejoined = Bukkit.getPlayer(event.getPlayer().getUniqueId());
                if (rejoined == null || !rejoined.isOnline() || arena == null) return;
                if (!isInsideArena(rejoined.getLocation())) {
                    Location safe = findSafeInsideArena();
                    if (safe != null) {
                        rejoined.teleport(safe);
                        rejoined.sendMessage("§aYou have rejoined your active boss fight.");
                    }
                }
            });
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        UUID id = event.getPlayer().getUniqueId();
        if (Objects.equals(countdownPlayer, id)) {
            Bukkit.broadcastMessage("§e" + event.getPlayer().getName()
                    + " disconnected during the boss countdown. It will resume when they reconnect.");
            return;
        }
        if (activeBossId != null && Objects.equals(challengerId, id)) {
            emptySince = 0L;
            Bukkit.broadcastMessage("§e" + event.getPlayer().getName()
                    + " disconnected during the boss fight. The boss will keep fighting anyone inside the arena.");
        }
    }

    @EventHandler
    public void onCountdownDeath(PlayerDeathEvent event) {
        UUID id = event.getEntity().getUniqueId();
        if (activeBossId != null && Objects.equals(challengerId, id)) {
            defeatChallenger(event.getEntity().getName(), "died during the boss fight");
            return;
        }
        if (Objects.equals(countdownPlayer, id)) {
            abortCountdown(id, event.getEntity().getName(), "died before the boss spawned");
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onChallengerTeleport(PlayerTeleportEvent event) {
        if (activeBossId == null || challengerId == null || arena == null) return;
        if (!challengerId.equals(event.getPlayer().getUniqueId())) return;
        if (isInsideArena(event.getFrom()) && (event.getTo() == null || !isInsideArena(event.getTo()))) {
            UUID id = challengerId;
            String name = event.getPlayer().getName();
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (activeBossId != null && Objects.equals(challengerId, id)) {
                    defeatChallenger(name, "left the boss arena by teleport");
                }
            });
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onChallengerTeleportCommand(PlayerCommandPreprocessEvent event) {
        if (activeBossId == null || challengerId == null || arena == null || !arena.locked) return;
        Player player = event.getPlayer();
        if (!challengerId.equals(player.getUniqueId()) || !isInsideArena(player.getLocation())) return;
        String line = event.getMessage().toLowerCase(Locale.ROOT).trim();
        String root = line.startsWith("/") ? line.substring(1).split("\\s+", 2)[0] : line;
        root = root.contains(":") ? root.substring(root.indexOf(':') + 1) : root;
        if (Set.of("home", "spawn", "warp", "warps", "tp", "teleport", "tpa",
                "tpaccept", "back", "rtp", "wild", "hub", "lobby", "tphere").contains(root)) {
            defeatChallenger(player.getName(), "attempted to leave the boss arena using /" + root);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onVehicleMove(VehicleMoveEvent event) {
        if (arena == null) return;
        boolean crossing = isInsideArena(event.getFrom()) != isInsideArena(event.getTo());
        if (!crossing) return;
        boolean carryingAcceptor = challengerId != null && event.getVehicle().getPassengers().stream()
                .anyMatch(passenger -> passenger instanceof Player player
                        && player.getUniqueId().equals(challengerId));
        if (arena.locked || carryingAcceptor) {
            event.getVehicle().setVelocity(new Vector(0, 0, 0));
            event.getVehicle().teleport(event.getFrom());
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onDamage(EntityDamageByEntityEvent event) {
        boolean bossVictim = event.getEntity() instanceof LivingEntity living && isBoss(living);
        boolean bossDamager = isBossDamager(event.getDamager());

        if (bossDamager) {
            if (!(event.getEntity() instanceof Player player) || !isInsideArena(player.getLocation())) {
                event.setCancelled(true);
                return;
            }
        }
        if (bossVictim) {
            Player attacker = playerAttacker(event.getDamager());
            if (attacker == null || !isInsideArena(attacker.getLocation())) {
                event.setCancelled(true);
            }
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onBossTarget(EntityTargetLivingEntityEvent event) {
        if (!(event.getEntity() instanceof LivingEntity source) || !isBoss(source)) return;
        if (!(event.getTarget() instanceof Player target)
                || target.getGameMode() == GameMode.SPECTATOR
                || target.getGameMode() == GameMode.CREATIVE
                || !isInsideArena(target.getLocation())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onBossExplosion(EntityExplodeEvent event) {
        if (isBoss(event.getEntity()) || isTaggedBossProjectile(event.getEntity())) {
            event.blockList().clear();
            event.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onBossBlockChange(EntityChangeBlockEvent event) {
        if (isBoss(event.getEntity())) event.setCancelled(true);
    }

    private boolean isTaggedBossProjectile(Entity entity) {
        return entity.getPersistentDataContainer().has(projectileTag, PersistentDataType.BYTE);
    }

    private boolean isBossDamager(Entity entity) {
        if (isBoss(entity)) return true;
        if (isTaggedBossProjectile(entity)) return true;
        return entity instanceof Projectile projectile
                && projectile.getShooter() instanceof LivingEntity shooter && isBoss(shooter);
    }

    private Player playerAttacker(Entity damager) {
        if (damager instanceof Player player) return player;
        if (damager instanceof Projectile projectile && projectile.getShooter() instanceof Player player) return player;
        return null;
    }

    @EventHandler
    public void onBossDeath(EntityDeathEvent event) {
        LivingEntity boss = event.getEntity();
        if (!isBoss(boss)) return;
        event.getDrops().clear();
        event.setDroppedExp(0);
        removeBossBar(boss.getUniqueId());
        activeBossId = null;

        Player killer = validFinalHitter(boss);
        if (isLavaDeathLocation(boss.getLocation())) killer = null;
        String tier = tierOf(boss);
        String name = nameOf(boss);
        if (killer == null) {
            Bukkit.broadcastMessage("§8§m------------------------------------------------");
            Bukkit.broadcastMessage("§c§lBOSS CHALLENGE DRAW");
            Bukkit.broadcastMessage("§f" + bossDisplay(name, tier) + " §7died without a player's final hit.");
            Bukkit.broadcastMessage("§cNo reward key. Entry fee is not refunded.");
            Bukkit.broadcastMessage("§8§m------------------------------------------------");
            finishEncounter("§cDRAW", "No player final hit • no reward", Sound.ENTITY_VILLAGER_NO);
            return;
        }

        int reward = rewardForTier(tier);
        ItemStack key = createRewardKey(tier, name, reward, acceptedByName,
                killer.getName(), activeEntryFee);
        Item dropped = boss.getWorld().dropItemNaturally(boss.getLocation(), key);
        dropped.setInvulnerable(true);
        dropped.setFireTicks(0);
        dropped.setPickupDelay(20);

        Bukkit.broadcastMessage("§8§m------------------------------------------------");
        Bukkit.broadcastMessage("§a§lBOSS DEFEATED!");
        Bukkit.broadcastMessage("§f" + bossDisplay(name, tier) + " §7was defeated by §e" + killer.getName() + "§7!");
        Bukkit.broadcastMessage("§6A glowing Boss Key dropped at the boss's death location.");
        Bukkit.broadcastMessage("§8§m------------------------------------------------");
        killer.sendMessage("§aYou delivered the final hit! The glowing Boss Key has dropped where the boss died.");
        finishEncounter("§aVICTORY!", "Defeated by " + killer.getName() + " • key dropped", Sound.UI_TOAST_CHALLENGE_COMPLETE);
    }

    private boolean isLavaDeathLocation(Location location) {
        if (location == null || location.getWorld() == null) return false;
        if (location.getBlock().getType() == Material.LAVA) return true;
        if (location.clone().add(0.0, 1.0, 0.0).getBlock().getType() == Material.LAVA) return true;
        return location.clone().subtract(0.0, 1.0, 0.0).getBlock().getType() == Material.LAVA;
    }

    private Player validFinalHitter(LivingEntity boss) {
        if (!(boss.getLastDamageCause() instanceof EntityDamageByEntityEvent damage)) return null;
        Player attacker = playerAttacker(damage.getDamager());
        if (attacker == null) return null;
        if (arena != null && !isInsideArena(attacker.getLocation())) return null;
        return attacker;
    }

    private ItemStack createRewardKey(String tier, String name, int reward,
                                      String acceptedBy, String defeatedBy, int entryFee) {
        ItemStack key = new ItemStack(Material.TRIAL_KEY);
        ItemMeta meta = key.getItemMeta();
        meta.setDisplayName("§6§l" + name + " §7(" + tierName(tier) + ") §6§lBoss Key");
        meta.setLore(List.of(
                "§7Boss: §f" + name + " (" + tierName(tier) + ")",
                "§7Reward: §6" + reward + " coins",
                "§7Entry fee: §e" + entryFee + " bank coins",
                "§7Accepted by: §f" + (acceptedBy == null ? "Admin Spawn" : acceptedBy),
                "§7Defeated by: §f" + defeatedBy,
                "§eRight-click an Armorer, Weaponsmith,",
                "§eor Toolsmith villager to redeem.",
                "§8One-time use • Reward goes to bank"
        ));
        meta.addEnchant(Enchantment.UNBREAKING, 1, true);
        meta.addItemFlags(ItemFlag.HIDE_ENCHANTS);
        PersistentDataContainer pdc = meta.getPersistentDataContainer();
        pdc.set(keyTag, PersistentDataType.BYTE, (byte)1);
        pdc.set(keyIdTag, PersistentDataType.STRING, UUID.randomUUID().toString());
        pdc.set(keyTierTag, PersistentDataType.STRING, tier);
        pdc.set(keyNameTag, PersistentDataType.STRING, name);
        pdc.set(keyRewardTag, PersistentDataType.INTEGER, reward);
        pdc.set(keyAcceptedTag, PersistentDataType.STRING, acceptedBy == null ? "Admin Spawn" : acceptedBy);
        pdc.set(keyDefeatedTag, PersistentDataType.STRING, defeatedBy);
        pdc.set(keyEntryFeeTag, PersistentDataType.INTEGER, entryFee);
        key.setItemMeta(meta);
        return key;
    }

    @EventHandler(ignoreCancelled = true)
    public void onRedeemKey(PlayerInteractEntityEvent event) {
        if (event.getHand() != EquipmentSlot.HAND || !(event.getRightClicked() instanceof Villager villager)) return;
        Villager.Profession profession = villager.getProfession();
        if (profession != Villager.Profession.ARMORER
                && profession != Villager.Profession.WEAPONSMITH
                && profession != Villager.Profession.TOOLSMITH) return;

        Player player = event.getPlayer();
        ItemStack held = player.getInventory().getItemInMainHand();
        if (held.getType().isAir() || !held.hasItemMeta()
                || !held.getItemMeta().getPersistentDataContainer().has(keyTag, PersistentDataType.BYTE)) return;
        event.setCancelled(true);

        PersistentDataContainer pdc = held.getItemMeta().getPersistentDataContainer();
        String keyId = pdc.get(keyIdTag, PersistentDataType.STRING);
        int reward = pdc.getOrDefault(keyRewardTag, PersistentDataType.INTEGER, 0);
        String tier = pdc.getOrDefault(keyTierTag, PersistentDataType.STRING, "common");
        String name = pdc.getOrDefault(keyNameTag, PersistentDataType.STRING, "Unknown");
        if (keyId == null || reward <= 0) {
            player.sendMessage("§cThis boss key is invalid. It has not been consumed.");
            return;
        }
        if (redeemedKeyIds.contains(keyId)) {
            player.sendMessage("§cThis boss key has already been redeemed.");
            consumeHeldKey(player, held);
            return;
        }

        // Persist the one-time claim before paying, preventing duplicated keys from paying twice.
        redeemedKeyIds.add(keyId);
        savePersistentState();
        if (!changeBank(player.getUniqueId(), reward)) {
            redeemedKeyIds.remove(keyId);
            savePersistentState();
            player.sendMessage("§cThe bank could not credit the reward. Your key has not been consumed.");
            return;
        }

        consumeHeldKey(player, held);
        player.sendMessage("§a" + name + " (" + tierName(tier) + ") key redeemed! §e"
                + reward + " coins §awere deposited into your bank.");
        Bukkit.broadcastMessage("§6" + player.getName() + " redeemed a " + name + " (" + tierName(tier)
                + ") Boss Key for §e" + reward + " coins§6.");
    }

    private void consumeHeldKey(Player player, ItemStack held) {
        if (held.getAmount() <= 1) player.getInventory().setItemInMainHand(null);
        else held.setAmount(held.getAmount() - 1);
    }

    private void despawnForEmptyArena(LivingEntity boss) {
        String display = bossDisplay(nameOf(boss), tierOf(boss));
        boss.remove();
        removeBossBar(boss.getUniqueId());
        activeBossId = null;
        Bukkit.broadcastMessage("§c" + display + " §7despawned because nobody remained inside the arena for 5 minutes. No reward was awarded.");
        finishEncounter("§cCHALLENGE ENDED", "Arena empty • no reward", Sound.ENTITY_VILLAGER_NO);
    }

    private void finishWithoutReward(String reason) {
        Bukkit.broadcastMessage("§c" + reason + " No reward key was awarded.");
        finishEncounter("§cCHALLENGE ENDED", "No reward key was awarded", Sound.ENTITY_VILLAGER_NO);
    }

    private void defeatChallenger(String playerName, String reason) {
        if (activeBossId == null) return;
        Entity boss = Bukkit.getEntity(activeBossId);
        if (boss != null) boss.remove();
        removeBossBar(activeBossId);
        activeBossId = null;
        Bukkit.broadcastMessage("§c§lBOSS CHALLENGE DEFEAT");
        Bukkit.broadcastMessage("§e" + playerName + " §7" + reason + ". The boss despawned immediately; no reward key was awarded.");
        finishEncounter("§cDEFEAT", "Boss fight lost • no reward", Sound.ENTITY_VILLAGER_NO);
    }

    private void finishEncounter(String title, String subtitle, Sound sound) {
        UUID completedChallenger = challengerId;
        Player player = completedChallenger == null ? null : Bukkit.getPlayer(completedChallenger);
        if (completedChallenger != null) {
            cooldowns.put(completedChallenger, System.currentTimeMillis() + COOLDOWN_MS);
        }
        clearEncounterState();
        savePersistentState();
        if (player != null && player.isOnline()) {
            String cooldownText = completedChallenger == null ? "" : " • 3-hour cooldown started";
            showPrivateResult(player, title, subtitle + cooldownText, sound);
            player.sendMessage("§eYour 3-hour boss challenge cooldown has started.");
        }
    }

    private void showPrivateResult(Player player, String title, String subtitle, Sound sound) {
        player.sendTitle(title, subtitle, 10, 80, 20);
        player.playSound(player.getLocation(), sound, 1.0f, 1.0f);
    }

    private void clearEncounterState() {
        cleanupTemporaryArenaBlocks();
        flightMode = false;
        if (activeBossId != null) removeBossBar(activeBossId);
        activeBossId = null;
        challengerId = null;
        challengerName = null;
        acceptedByName = null;
        activeEntryFee = 0;
        manualSpawn = false;
        arena = null;
        emptySince = 0L;
    }

    private void startCooldown(UUID id) {
        cooldowns.put(id, System.currentTimeMillis() + COOLDOWN_MS);
        savePersistentState();
    }

    private double bankBalance(UUID id) {
        Object manager = bankManager();
        if (manager == null) return -1.0;
        try {
            Method method = manager.getClass().getMethod("getBalance", UUID.class);
            return ((Number)method.invoke(manager, id)).doubleValue();
        } catch (ReflectiveOperationException | RuntimeException ex) {
            plugin.getLogger().warning("Could not read bank balance for boss challenge: " + ex.getClass().getSimpleName());
            return -1.0;
        }
    }

    private boolean changeBank(UUID id, double delta) {
        Object manager = bankManager();
        if (manager == null) return false;
        try {
            Method get = manager.getClass().getMethod("getBalance", UUID.class);
            Method set = manager.getClass().getMethod("setBalance", UUID.class, double.class);
            double balance = ((Number)get.invoke(manager, id)).doubleValue();
            if (!Double.isFinite(balance) || !Double.isFinite(delta) || balance + delta < 0.0) return false;
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
        try {
            return economy.getClass().getMethod("getBankManager").invoke(economy);
        } catch (ReflectiveOperationException | RuntimeException ex) {
            plugin.getLogger().warning("XZHEconomySystem bank manager is unavailable: " + ex.getClass().getSimpleName());
            return null;
        }
    }

    private void updateBossChunkTicket(LivingEntity boss) {
        Chunk current = boss.getChunk();
        if (bossChunkTicket != null && !bossChunkTicket.equals(current)) {
            bossChunkTicket.removePluginChunkTicket(plugin);
            bossChunkTicket = null;
        }
        if (bossChunkTicket == null) {
            current.addPluginChunkTicket(plugin);
            bossChunkTicket = current;
            if (activeBossId != null && arena != null) {
                plugin.getConfig().set("boss-challenge.active-boss-chunk-x", current.getX());
                plugin.getConfig().set("boss-challenge.active-boss-chunk-z", current.getZ());
                plugin.saveConfig();
            }
        }
    }

    private void removeBossBar(UUID id) {
        BossBar bar = bossBars.remove(id);
        if (bar != null) bar.removeAll();
        if (bossChunkTicket != null) {
            bossChunkTicket.removePluginChunkTicket(plugin);
            bossChunkTicket = null;
        }
    }

    private boolean isBoss(Entity entity) {
        return entity instanceof LivingEntity living
                && living.getPersistentDataContainer().has(bossTag, PersistentDataType.BYTE);
    }

    private String tierOf(LivingEntity boss) {
        return boss.getPersistentDataContainer().getOrDefault(tierTag, PersistentDataType.STRING, "common");
    }

    private String nameOf(LivingEntity boss) {
        return boss.getPersistentDataContainer().getOrDefault(nameTag, PersistentDataType.STRING, "Unknown");
    }

    private void teleportNearbyPlayersOutside(Arena targetArena, Player acceptedPlayer) {
        int index = 0;
        for (Player nearby : targetArena.world.getPlayers()) {
            if (nearby.equals(acceptedPlayer)) continue;
            double dx = nearby.getLocation().getX() - targetArena.centerX;
            double dz = nearby.getLocation().getZ() - targetArena.centerZ;
            if (dx * dx + dz * dz > 48.0 * 48.0) continue;
            Location outside = findSafeOutsideArena(targetArena, index++);
            if (outside != null) nearby.teleport(outside);
        }
    }

    private Location findSafeOutsideArena(Arena targetArena, int index) {
        for (int attempt = 0; attempt < 32; attempt++) {
            double angle = (index * 2.399963229728653 + attempt * 0.41) % (Math.PI * 2.0);
            double distance = 35.0 + (attempt % 4) * 1.5;
            int x = (int)Math.floor(targetArena.centerX + Math.cos(angle) * distance);
            int z = (int)Math.floor(targetArena.centerZ + Math.sin(angle) * distance);
            int y = targetArena.world.getHighestBlockYAt(x, z) + 1;
            Location candidate = new Location(targetArena.world, x + 0.5, y, z + 0.5);
            if (isSafeSpawn(candidate)) return candidate;
        }
        return null;
    }

    private Location findSafeInsideArena() {
        if (arena == null) return null;
        for (int attempt = 0; attempt < 100; attempt++) {
            double angle = random.nextDouble() * Math.PI * 2.0;
            double distance = random.nextDouble() * 22.0;
            int x = (int)Math.floor(arena.centerX + Math.cos(angle) * distance);
            int z = (int)Math.floor(arena.centerZ + Math.sin(angle) * distance);
            int y = arena.world.getHighestBlockYAt(x, z) + 1;
            Location candidate = new Location(arena.world, x + 0.5, y, z + 0.5);
            if (isInsideArena(candidate) && isSafeSpawn(candidate)) return candidate;
        }
        return null;
    }

    private void startArenaEntryCountdown(Arena targetArena) {
        final int[] seconds = {10};
        Bukkit.getScheduler().runTaskTimer(plugin, task -> {
            if (arena != targetArena || activeBossId == null) {
                task.cancel();
                return;
            }
            for (Player player : targetArena.world.getPlayers()) {
                double dx = player.getLocation().getX() - targetArena.centerX;
                double dz = player.getLocation().getZ() - targetArena.centerZ;
                if (dx * dx + dz * dz > 48.0 * 48.0) continue;
                player.spigot().sendMessage(ChatMessageType.ACTION_BAR,
                        TextComponent.fromLegacyText("§6BOSS FIGHT §8• §eEnter the arena: " + seconds[0] + "s"));
                if (seconds[0] == 10 || seconds[0] == 5 || seconds[0] <= 3) {
                    player.sendTitle("§6BOSS FIGHT", "§eEnter the arena • starts in " + seconds[0] + "s", 0, 20, 0);
                }
            }
            if (seconds[0] <= 0) {
                targetArena.locked = true;
                Entity entity = Bukkit.getEntity(activeBossId);
                if (entity instanceof Mob mob) {
                    mob.setAware(true);
                    mob.setAI(true);
                }
                Bukkit.broadcastMessage("§b§lBOSS FIGHT STARTED §8» §7The arena is now locked.");
                task.cancel();
                return;
            }
            seconds[0]--;
        }, 0L, 20L);
    }

    private Player findTargetInsideArena(LivingEntity boss, double range) {
        if (arena == null) return null;
        Player best = null;
        double bestDistance = range * range;
        for (Player player : boss.getWorld().getPlayers()) {
            if (player.isDead() || player.getGameMode() == GameMode.SPECTATOR
                    || player.getGameMode() == GameMode.CREATIVE
                    || !isInsideArena(player.getLocation())) continue;
            double distance = player.getLocation().distanceSquared(boss.getLocation());
            if (distance < bestDistance) {
                bestDistance = distance;
                best = player;
            }
        }
        return best;
    }

    private String rollTier() {
        int roll = random.nextInt(100);
        return roll < 60 ? "common" : roll < 90 ? "rare" : "legendary";
    }

    private String randomBossName(String tier) {
        List<String> names = BOSS_NAMES.getOrDefault(tier, BOSS_NAMES.get("common"));
        String previous = previousNameByTier.get(tier);
        List<String> choices = names.stream().filter(name -> !name.equals(previous)).toList();
        if (choices.isEmpty()) choices = names;
        String selected = choices.get(random.nextInt(choices.size()));
        previousNameByTier.put(tier, selected);
        return selected;
    }

    private String tierName(String tier) {
        return switch (tier.toLowerCase(Locale.ROOT)) {
            case "legendary" -> "Legendary";
            case "rare" -> "Rare";
            default -> "Common";
        };
    }

    private String bossDisplay(String name, String tier) {
        String color = switch (tier) {
            case "legendary" -> "§4§l";
            case "rare" -> "§5§l";
            default -> "§9§l";
        };
        return color + name + " §7(" + tierName(tier) + ")";
    }

    private String rewardRange(String tier) {
        return switch (tier) {
            case "legendary" -> "§e1,500–2,500 coins";
            case "rare" -> "§e500–1,000 coins";
            default -> "§e350–500 coins";
        };
    }

    private int rewardForTier(String tier) {
        return switch (tier) {
            case "legendary" -> 1500 + random.nextInt(1001);
            case "rare" -> 500 + random.nextInt(501);
            default -> 350 + random.nextInt(151);
        };
    }

    private String formatDuration(long millis) {
        long totalSeconds = Math.max(0L, millis / 1000L);
        long hours = totalSeconds / 3600L;
        long minutes = (totalSeconds % 3600L) / 60L;
        long seconds = totalSeconds % 60L;
        if (hours > 0) return hours + "h " + minutes + "m";
        if (minutes > 0) return minutes + "m " + seconds + "s";
        return seconds + "s";
    }

    private String canonicalTier(String value) {
        return value.toLowerCase(Locale.ROOT);
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        String commandName = command.getName().toLowerCase(Locale.ROOT);
        if (commandName.equals("boss")) {
            if (!sender.hasPermission("xzhsurvival.boss.admin")) {
                sender.sendMessage("§cYou do not have permission to spawn bosses.");
                return true;
            }
            if (args.length == 1 && (args[0].equalsIgnoreCase("enable")
                    || args[0].equalsIgnoreCase("disable"))) {
                boolean enabled = args[0].equalsIgnoreCase("enable");
                plugin.getConfig().set("boss-challenge.enabled", enabled);
                if (!enabled && challengeAvailable) {
                    challengeAvailable = false;
                    nextChallengeAt = System.currentTimeMillis() + HOUR_MS;
                    plugin.getConfig().set("boss-challenge.next-challenge-at", nextChallengeAt);
                }
                plugin.saveConfig();
                sender.sendMessage(enabled
                        ? "§aGlobal boss challenges enabled. The hourly schedule will resume."
                        : "§eGlobal boss challenges disabled. No new scheduled challenges will appear until re-enabled.");
                return true;
            }
            if (args.length != 1) {
                sender.sendMessage("§eUsage: /boss <bossname|common|rare|legendary|enable|disable>");
                return true;
            }
            if (activeBossId != null || countdownPlayer != null || challengeAvailable) {
                sender.sendMessage("§cA boss challenge or encounter is already active. Wait until it finishes.");
                return true;
            }

            String requested = args[0];
            String tier = null;
            String name = null;
            if (BOSS_NAMES.containsKey(requested.toLowerCase(Locale.ROOT))) {
                tier = requested.toLowerCase(Locale.ROOT);
                name = randomBossName(tier);
            } else {
                for (Map.Entry<String, List<String>> entry : BOSS_NAMES.entrySet()) {
                    for (String candidate : entry.getValue()) {
                        if (candidate.equalsIgnoreCase(requested)) {
                            tier = entry.getKey();
                            name = candidate;
                            break;
                        }
                    }
                    if (tier != null) break;
                }
            }
            if (tier == null || name == null) {
                sender.sendMessage("§cUnknown boss name. Use /boss <bossname|common|rare|legendary>.");
                return true;
            }

            Location origin;
            String adminName;
            if (sender instanceof Player admin) {
                origin = admin.getLocation().clone();
                adminName = admin.getName();
            } else {
                if (Bukkit.getWorlds().isEmpty()) {
                    sender.sendMessage("§cNo world is loaded.");
                    return true;
                }
                origin = Bukkit.getWorlds().get(0).getSpawnLocation();
                adminName = "Console";
            }
            Location bossLocation = findManualSpawn(origin);
            if (bossLocation == null) {
                sender.sendMessage("§cCould not find a safe boss spawn location near you.");
                return true;
            }
            Arena manualArena = createArena(midpoint(origin, bossLocation), origin.getY(), bossLocation.getY());
            spawnBoss(bossLocation, tier, name, null, adminName, 0, true, manualArena);
            sender.sendMessage("§aSpawned " + bossDisplay(name, tier) + "§a.");
            return true;
        }

        if (commandName.equals("sorry")) {
            if (!(sender instanceof Player player)) {
                sender.sendMessage("Only the accepting player can use /sorry.");
                return true;
            }
            if (activeBossId == null || challengerId == null || !challengerId.equals(player.getUniqueId())) {
                if (Objects.equals(countdownPlayer, player.getUniqueId())) {
                    abortCountdown(player.getUniqueId(), player.getName(), "used /sorry before the boss spawned");
                    player.sendMessage("§eYour challenge was surrendered. The entry fee was not refunded.");
                    return true;
                }
                player.sendMessage("§cOnly the player who accepted this challenge can use /sorry.");
                return true;
            }
            Entity entity = Bukkit.getEntity(activeBossId);
            if (entity != null) entity.remove();
            removeBossBar(activeBossId);
            activeBossId = null;
            Bukkit.broadcastMessage("§c§lBOSS CHALLENGE SURRENDERED");
            Bukkit.broadcastMessage("§e" + player.getName() + " §7used /sorry and surrendered the boss challenge. No key or reward was awarded; the entry fee was not refunded.");
            finishEncounter("§cSURRENDERED", "No reward • entry fee forfeited", Sound.ENTITY_VILLAGER_NO);
            return true;
        }

        if (commandName.equals("xzhboss")) {
            if (!(sender instanceof Player player)) {
                sender.sendMessage("Only players can accept or reject a boss challenge.");
                return true;
            }
            if (args.length != 1) {
                player.sendMessage("§eUsage: /xzhboss accept|reject");
                return true;
            }
            if (args[0].equalsIgnoreCase("accept")) {
                handleAccept(player);
            } else if (args[0].equalsIgnoreCase("reject")) {
                if (!challengeAvailable) player.sendMessage("§cThere is no active challenge to reject.");
                else player.sendMessage("§7You dismissed the invitation. Other players can still accept it.");
            } else {
                player.sendMessage("§eUsage: /xzhboss accept|reject");
            }
            return true;
        }
        return false;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (command.getName().equalsIgnoreCase("boss") && args.length == 1) {
            List<String> options = new ArrayList<>(List.of("common", "rare", "legendary", "enable", "disable"));
            for (List<String> names : BOSS_NAMES.values()) options.addAll(names);
            String prefix = args[0].toLowerCase(Locale.ROOT);
            return options.stream().filter(option -> option.toLowerCase(Locale.ROOT).startsWith(prefix)).toList();
        }
        if (command.getName().equalsIgnoreCase("xzhboss") && args.length == 1) {
            return List.of("accept", "reject").stream()
                    .filter(option -> option.startsWith(args[0].toLowerCase(Locale.ROOT))).toList();
        }
        return Collections.emptyList();
    }
}
