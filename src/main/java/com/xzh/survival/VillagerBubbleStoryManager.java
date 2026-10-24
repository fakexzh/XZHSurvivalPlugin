package com.xzh.survival;

import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.entity.Villager;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Vector;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Villager-only floating Banglish dialogue. TextDisplay entities are spawned
 * above villagers and are never created for players or sent to server chat.
 */
public final class VillagerBubbleStoryManager implements Listener {
    private final JavaPlugin plugin;
    private final Random random = new Random();
    private final Map<UUID, StorySession> activeByVillager = new HashMap<>();
    private final Map<UUID, Long> villagerCooldownUntil = new HashMap<>();
    private final Map<UUID, Long> playerReplyCooldownUntil = new HashMap<>();
    private final List<List<String>> stories = new ArrayList<>();
    private final List<Integer> storyOrder = new ArrayList<>();
    private int storyOrderIndex;
    private int lastStoryIndex = -1;
    private final List<String> ambientLines = new ArrayList<>();
    private final List<String> playerOffers = new ArrayList<>();
    private final Map<String, List<String>> keywordReplies = new HashMap<>();
    private BukkitTask ambientTask;

    public VillagerBubbleStoryManager(JavaPlugin plugin) {
        this.plugin = plugin;
        loadDialogue();
    }

    public void start() {
        if (!plugin.getConfig().getBoolean("villager-bubble-chat.enabled", true)) {
            plugin.getLogger().info("Villager bubble chat disabled by configuration.");
            return;
        }
        Bukkit.getPluginManager().registerEvents(this, plugin);
        long interval = Math.max(20L, plugin.getConfig().getLong("villager-bubble-chat.ambient-check-interval-ticks", 60L));
        ambientTask = Bukkit.getScheduler().runTaskTimer(plugin, this::ambientCheck, interval, interval);
        plugin.getLogger().info("Villager bubble chat loaded " + stories.size() + " stories / "
                + stories.stream().mapToInt(List::size).sum() + " unique-story lines, "
                + ambientLines.size() + " ambient lines and " + playerOffers.size() + " offers.");
    }

    public void shutdown() {
        if (ambientTask != null) ambientTask.cancel();
        for (StorySession session : new HashSet<>(activeByVillager.values())) session.stop();
        activeByVillager.clear();
        for (World world : Bukkit.getWorlds()) {
            for (TextDisplay display : world.getEntitiesByClass(TextDisplay.class)) {
                if (display.getScoreboardTags().contains("xzh-villager-bubble")) display.remove();
            }
        }
    }

    private void loadDialogue() {
        try (InputStream input = plugin.getResource("villager-dialogue.yml")) {
            if (input == null) {
                plugin.getLogger().severe("Missing villager-dialogue.yml; villager bubble chat will have no dialogue.");
                return;
            }
            YamlConfiguration yaml = YamlConfiguration.loadConfiguration(
                    new InputStreamReader(input, StandardCharsets.UTF_8));
            addUniqueDialogue(ambientLines, yaml.getStringList("ambient-lines"), true);
            addUniqueDialogue(playerOffers, yaml.getStringList("player-offers"), true);

            ConfigurationSection keywordSection = yaml.getConfigurationSection("keyword-replies");
            if (keywordSection != null) {
                for (String key : keywordSection.getKeys(false)) {
                    List<String> replies = new ArrayList<>();
                    addUniqueDialogue(replies, keywordSection.getStringList(key), true);
                    if (!replies.isEmpty()) keywordReplies.put(normalize(key), replies);
                }
            }

            Set<String> uniqueStories = new HashSet<>();
            for (Map<?, ?> entry : yaml.getMapList("stories")) {
                Object rawLines = entry.get("lines");
                if (!(rawLines instanceof List<?> rawList)) continue;
                List<String> lines = new ArrayList<>();
                for (Object rawLine : rawList) {
                    if (rawLine instanceof String line && !line.isBlank()) lines.add(line);
                }
                if (lines.size() >= 4) {
                    String storyKey = String.join("\u001f", lines).toLowerCase(Locale.ROOT);
                    if (uniqueStories.add(storyKey)) stories.add(Collections.unmodifiableList(lines));
                }
            }
            refillStoryOrder();
        } catch (Exception exception) {
            plugin.getLogger().severe("Could not load villager dialogue: " + exception.getMessage());
        }
    }

    private List<String> nonBlank(List<String> source) {
        List<String> result = new ArrayList<>();
        for (String line : source) if (line != null && !line.isBlank()) result.add(line);
        return result;
    }

    private void addUniqueDialogue(List<String> destination, List<String> source, boolean stripNumberSuffix) {
        Set<String> seen = new HashSet<>();
        for (String raw : source) {
            if (raw == null || raw.isBlank()) continue;
            String line = raw.trim();
            String key = line;
            if (stripNumberSuffix) key = key.replaceFirst("\\s+\\d+$", "");
            key = key.toLowerCase(Locale.ROOT);
            if (seen.add(key)) destination.add(stripNumberSuffix
                    ? line.replaceFirst("\\s+\\d+$", "") : line);
        }
    }

    private void refillStoryOrder() {
        storyOrder.clear();
        for (int i = 0; i < stories.size(); i++) storyOrder.add(i);
        Collections.shuffle(storyOrder, random);
        if (storyOrder.size() > 1 && storyOrder.get(0) == lastStoryIndex) {
            int swapIndex = 1 + random.nextInt(storyOrder.size() - 1);
            Collections.swap(storyOrder, 0, swapIndex);
        }
        storyOrderIndex = 0;
    }

    private List<String> nextStory() {
        if (storyOrderIndex >= storyOrder.size()) refillStoryOrder();
        if (storyOrder.isEmpty()) return List.of();
        lastStoryIndex = storyOrder.get(storyOrderIndex++);
        return stories.get(lastStoryIndex);
    }

    private void ambientCheck() {
        if (ambientLines.isEmpty() && stories.isEmpty() && playerOffers.isEmpty()) return;
        long now = System.currentTimeMillis();
        double range = Math.max(2.0, plugin.getConfig().getDouble("villager-bubble-chat.nearby-range-blocks", 6.0));
        double chance = clamp(plugin.getConfig().getDouble("villager-bubble-chat.ambient-chance-per-check", 0.12), 0.0, 1.0);

        for (World world : Bukkit.getWorlds()) {
            for (Villager villager : world.getEntitiesByClass(Villager.class)) {
                if (!villager.isValid() || villager.isDead() || villager.isSleeping()
                        || isBusy(villager.getUniqueId()) || isConversationAreaBusy(villager, range)) continue;
                if (now < villagerCooldownUntil.getOrDefault(villager.getUniqueId(), 0L)) continue;
                if (random.nextDouble() >= chance) continue;

                Player nearbyPlayer = nearestPlayer(villager, range);
                boolean nearConversationBlock = hasConversationBlockNearby(villager);
                if (nearbyPlayer == null && !nearConversationBlock) continue;

                Villager partner = nearestVillager(villager, range);
                if (partner != null && !partner.isSleeping() && !isConversationAreaBusy(partner, range)
                        && now >= villagerCooldownUntil.getOrDefault(partner.getUniqueId(), 0L)) {
                    if (!stories.isEmpty()) {
                        startSession(List.of(villager, partner), nextStory(), true);
                    } else if (!ambientLines.isEmpty()) {
                        startSession(List.of(villager, partner), List.of(randomLine(ambientLines)), true);
                    }
                    continue;
                }

                if (nearbyPlayer != null) {
                    List<String> lines = random.nextDouble() < 0.65 ? playerOffers : ambientLines;
                    if (!lines.isEmpty()) startSession(List.of(villager), List.of(randomLine(lines)), false);
                }
            }
        }
    }

    @EventHandler
    public void onPlayerChat(AsyncPlayerChatEvent event) {
        // Preserve the player's normal chat untouched. Only inspect the message and
        // schedule a possible response bubble above a nearby villager on the main thread.
        final Player player = event.getPlayer();
        final String message = event.getMessage();
        Bukkit.getScheduler().runTask(plugin, () -> respondToPlayer(player, message));
    }

    private void respondToPlayer(Player player, String message) {
        if (!plugin.getConfig().getBoolean("villager-bubble-chat.player-keyword-replies-enabled", true)
                || !player.isOnline()) return;
        long now = System.currentTimeMillis();
        if (now < playerReplyCooldownUntil.getOrDefault(player.getUniqueId(), 0L)) return;
        if (random.nextDouble() > clamp(plugin.getConfig().getDouble(
                "villager-bubble-chat.keyword-response-chance", 0.80), 0.0, 1.0)) return;

        String normalizedMessage = " " + normalize(message) + " ";
        List<String> matches = new ArrayList<>();
        for (String keyword : keywordReplies.keySet()) {
            if (!keyword.isBlank() && normalizedMessage.contains(" " + keyword + " ")) matches.add(keyword);
        }
        if (matches.isEmpty()) return;
        matches.sort(Comparator.comparingInt(String::length).reversed());
        List<String> replies = keywordReplies.get(matches.get(0));
        if (replies == null || replies.isEmpty()) return;

        double range = Math.max(2.0, plugin.getConfig().getDouble("villager-bubble-chat.player-response-range-blocks", 7.0));
        Villager villager = nearestVillager(player, range);
        if (villager == null || isBusy(villager.getUniqueId())) return;

        startSession(List.of(villager), List.of(randomLine(replies)), false);
        long cooldown = Math.max(1L, plugin.getConfig().getLong("villager-bubble-chat.player-reply-cooldown-seconds", 12L));
        playerReplyCooldownUntil.put(player.getUniqueId(), now + cooldown * 1000L);
    }

    private void startSession(List<Villager> speakers, List<String> lines, boolean story) {
        if (speakers.isEmpty() || lines.isEmpty()) return;
        for (Villager speaker : speakers) {
            if (!speaker.isValid() || speaker.isDead() || speaker.isSleeping()
                    || isBusy(speaker.getUniqueId()) || isConversationAreaBusy(speaker,
                    Math.max(2.0, plugin.getConfig().getDouble("villager-bubble-chat.conversation-silence-range-blocks", 8.0)))) return;
        }
        StorySession session = new StorySession(speakers, lines, story);
        for (Villager speaker : speakers) activeByVillager.put(speaker.getUniqueId(), session);
        session.beginConversation();
        session.showLine();
    }

    private final class StorySession {
        private final List<Villager> speakers;
        private final List<String> lines;
        private final boolean story;
        private int index;
        private TextDisplay currentDisplay;
        private BukkitTask followTask;
        private BukkitTask sequenceTask;
        private BukkitTask conversationTask;
        private final Map<UUID, Boolean> originalAi = new HashMap<>();
        private long conversationStartedAt;
        private boolean stopped;

        private StorySession(List<Villager> speakers, List<String> lines, boolean story) {
            this.speakers = new ArrayList<>(speakers);
            this.lines = new ArrayList<>(lines);
            this.story = story;
            for (Villager speaker : speakers) originalAi.put(speaker.getUniqueId(), speaker.hasAI());
        }

        private void beginConversation() {
            if (speakers.size() != 2) return;
            conversationStartedAt = System.currentTimeMillis();
            conversationTask = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
                if (stopped) return;
                Villager first = speakers.get(0);
                Villager second = speakers.get(1);
                if (!first.isValid() || first.isDead() || !second.isValid() || second.isDead()) {
                    stop();
                    return;
                }
                double distanceSquared = first.getLocation().distanceSquared(second.getLocation());
                boolean arrived = distanceSquared <= 2.8 * 2.8;
                boolean timedOut = System.currentTimeMillis() - conversationStartedAt >= 8000L;
                if (!arrived && !timedOut) {
                    first.getPathfinder().moveTo(second.getLocation(), 0.85);
                    second.getPathfinder().moveTo(first.getLocation(), 0.85);
                } else {
                    for (Villager villager : speakers) {
                        villager.getPathfinder().stopPathfinding();
                        villager.setVelocity(new Vector(0, 0, 0));
                        villager.setAI(false);
                    }
                }
                faceAt(first, second.getLocation());
                faceAt(second, first.getLocation());
            }, 1L, 2L);
        }

        private void faceAt(Villager villager, Location target) {
            Location current = villager.getLocation();
            double dx = target.getX() - current.getX();
            double dz = target.getZ() - current.getZ();
            if (dx * dx + dz * dz < 1.0e-6) return;
            float yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
            villager.setRotation(yaw, 0.0f);
        }

        private void showLine() {
            if (stopped) return;
            if (index >= lines.size()) {
                finish();
                return;
            }
            for (Villager speaker : speakers) {
                if (!speaker.isValid() || speaker.isDead()) {
                    stop();
                    return;
                }
            }

            Villager speaker = speakers.get(index % speakers.size());
            String line = lines.get(index);
            currentDisplay = speaker.getWorld().spawn(
                    speaker.getLocation().add(0.0, speaker.getHeight() + 0.38, 0.0),
                    TextDisplay.class, display -> {
                        display.addScoreboardTag("xzh-villager-bubble");
                        display.setText(line);
                        display.setBillboard(Display.Billboard.CENTER);
                        display.setAlignment(TextDisplay.TextAlignment.CENTER);
                        display.setLineWidth(Math.max(100, plugin.getConfig().getInt(
                                "villager-bubble-chat.bubble-line-width", 240)));
                        display.setBackgroundColor(Color.fromARGB(185, 18, 18, 18));
                        display.setDefaultBackground(false);
                        display.setSeeThrough(true);
                        display.setShadowed(true);
                        display.setGravity(false);
                        display.setInvulnerable(true);
                    });

            followTask = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
                if (stopped || currentDisplay == null || !currentDisplay.isValid()
                        || !speaker.isValid() || speaker.isDead()) {
                    if (followTask != null) followTask.cancel();
                    return;
                }
                Location target = speaker.getLocation().add(0.0, speaker.getHeight() + 0.38, 0.0);
                currentDisplay.teleport(target);
            }, 1L, 1L);

            long duration = readingTicks(line);
            sequenceTask = Bukkit.getScheduler().runTaskLater(plugin, () -> {
                removeCurrentBubble();
                index++;
                long gap = Math.max(2L, plugin.getConfig().getLong("villager-bubble-chat.line-gap-ticks", 8L));
                sequenceTask = Bukkit.getScheduler().runTaskLater(plugin, this::showLine, gap);
            }, duration);
        }

        private long readingTicks(String line) {
            double minimum = Math.max(1.0, plugin.getConfig().getDouble("villager-bubble-chat.minimum-display-seconds", 2.4));
            double perCharacter = Math.max(0.0, plugin.getConfig().getDouble("villager-bubble-chat.seconds-per-character", 0.055));
            double maximum = Math.max(minimum, plugin.getConfig().getDouble("villager-bubble-chat.maximum-display-seconds", 7.0));
            double seconds = Math.min(maximum, Math.max(minimum, minimum + line.length() * perCharacter));
            return Math.max(20L, Math.round(seconds * 20.0));
        }

        private void removeCurrentBubble() {
            if (followTask != null) {
                followTask.cancel();
                followTask = null;
            }
            if (currentDisplay != null) {
                currentDisplay.remove();
                currentDisplay = null;
            }
        }

        private void finish() {
            stop();
            long cooldown = Math.max(1L, plugin.getConfig().getLong(
                    story ? "villager-bubble-chat.story-cooldown-seconds" : "villager-bubble-chat.line-cooldown-seconds",
                    story ? 24L : 5L));
            long until = System.currentTimeMillis() + cooldown * 1000L;
            for (Villager speaker : speakers) villagerCooldownUntil.put(speaker.getUniqueId(), until);
        }

        private void stop() {
            if (stopped) return;
            stopped = true;
            if (sequenceTask != null) sequenceTask.cancel();
            if (conversationTask != null) {
                conversationTask.cancel();
                conversationTask = null;
            }
            removeCurrentBubble();
            for (Villager speaker : speakers) {
                activeByVillager.remove(speaker.getUniqueId(), this);
                Boolean wasAiEnabled = originalAi.get(speaker.getUniqueId());
                if (wasAiEnabled != null && speaker.isValid() && !speaker.isDead()) {
                    speaker.getPathfinder().stopPathfinding();
                    speaker.setAI(wasAiEnabled);
                }
            }
        }
    }

    private boolean isBusy(UUID villagerId) {
        return activeByVillager.containsKey(villagerId);
    }

    private boolean isConversationAreaBusy(Villager villager, double range) {
        double radius = Math.max(2.0, plugin.getConfig().getDouble(
                "villager-bubble-chat.conversation-silence-range-blocks", 8.0));
        for (Entity nearby : villager.getNearbyEntities(radius, radius, radius)) {
            if (nearby instanceof Villager other && other != villager
                    && activeByVillager.containsKey(other.getUniqueId())) return true;
        }
        return false;
    }

    private boolean hasConversationBlockNearby(Villager villager) {
        int radius = Math.max(1, plugin.getConfig().getInt("villager-bubble-chat.activity-block-radius", 3));
        Location center = villager.getLocation();
        for (int x = -radius; x <= radius; x++) {
            for (int y = -1; y <= 1; y++) {
                for (int z = -radius; z <= radius; z++) {
                    Material type = center.getBlock().getRelative(x, y, z).getType();
                    if (type == Material.COMPOSTER || type == Material.LECTERN
                            || type == Material.CAMPFIRE || type == Material.BELL
                            || type == Material.BREWING_STAND || type == Material.CAULDRON
                            || type == Material.BARREL || type == Material.SMOKER
                            || type == Material.BLAST_FURNACE || type == Material.FLETCHING_TABLE
                            || type == Material.CARTOGRAPHY_TABLE || type == Material.LOOM
                            || type == Material.STONECUTTER || type == Material.GRINDSTONE
                            || type == Material.SMITHING_TABLE || type == Material.FARMLAND) return true;
                }
            }
        }
        return false;
    }

    private Villager nearestVillager(Entity origin, double range) {
        Villager nearest = null;
        double best = range * range;
        for (Entity entity : origin.getNearbyEntities(range, range, range)) {
            if (!(entity instanceof Villager villager) || villager == origin || !villager.isValid()
                    || villager.isDead() || isBusy(villager.getUniqueId())) continue;
            double distance = origin.getLocation().distanceSquared(villager.getLocation());
            if (distance <= best) {
                best = distance;
                nearest = villager;
            }
        }
        return nearest;
    }

    private Villager nearestVillager(Player player, double range) {
        Villager nearest = null;
        double best = range * range;
        for (Entity entity : player.getNearbyEntities(range, range, range)) {
            if (!(entity instanceof Villager villager) || !villager.isValid()
                    || villager.isDead() || isBusy(villager.getUniqueId())) continue;
            double distance = player.getLocation().distanceSquared(villager.getLocation());
            if (distance <= best) {
                best = distance;
                nearest = villager;
            }
        }
        return nearest;
    }

    private Player nearestPlayer(Villager villager, double range) {
        Player nearest = null;
        double best = range * range;
        for (Entity entity : villager.getNearbyEntities(range, range, range)) {
            if (!(entity instanceof Player player) || !player.isOnline()) continue;
            double distance = villager.getLocation().distanceSquared(player.getLocation());
            if (distance <= best) {
                best = distance;
                nearest = player;
            }
        }
        return nearest;
    }

    private String randomLine(List<String> lines) {
        return lines.get(random.nextInt(lines.size()));
    }

    private static String normalize(String value) {
        return value.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", " ").trim().replaceAll("\\s+", " ");
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }
}
