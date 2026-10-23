package com.xzh.survival;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

public final class TpaManager implements CommandExecutor {
    private final XZHSurvivalPlugin plugin;
    private final Map<UUID, Prepared> prepared = new HashMap<>();
    private final Map<UUID, Request> incoming = new HashMap<>();
    private final Map<UUID, BukkitTask> expiryTasks = new HashMap<>();

    private record Quote(int distance, int price, boolean netherFixed, String distanceLabel) {}
    private record Prepared(UUID targetId, Quote quote) {}
    private record Request(UUID requesterId, UUID targetId, Quote quote) {}

    public TpaManager(XZHSurvivalPlugin plugin) {
        this.plugin = plugin;
    }

    public void start() {
        for (String command : new String[]{"tpa", "tpasend", "tpacancel", "tpaccept", "tpdeny"}) {
            if (plugin.getCommand(command) != null) plugin.getCommand(command).setExecutor(this);
        }
    }

    public void shutdown() {
        for (BukkitTask task : expiryTasks.values()) task.cancel();
        expiryTasks.clear();
        prepared.clear();
        incoming.clear();
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        String name = command.getName().toLowerCase(Locale.ROOT);
        if (!(sender instanceof Player player)) {
            sender.sendMessage("Only players can use teleport requests.");
            return true;
        }
        switch (name) {
            case "tpa" -> preview(player, args);
            case "tpasend" -> sendPrepared(player);
            case "tpacancel" -> {
                prepared.remove(player.getUniqueId());
                player.sendMessage(Component.text("Teleport request cancelled.", NamedTextColor.RED));
            }
            case "tpaccept" -> accept(player);
            case "tpdeny" -> deny(player);
            default -> { return false; }
        }
        return true;
    }

    private void preview(Player requester, String[] args) {
        if (args.length != 1) {
            requester.sendMessage(Component.text("Usage: /tpa <player>", NamedTextColor.RED));
            return;
        }
        Player target = Bukkit.getPlayerExact(args[0]);
        if (target == null || target.equals(requester)) {
            requester.sendMessage(Component.text("Choose another online player.", NamedTextColor.RED));
            return;
        }
        Quote quote = quote(requester, target);
        if (quote == null) return;
        if (!hasFunds(requester, quote.price())) {
            requester.sendMessage(Component.text("You need " + quote.price()
                    + " coins for this teleport. Your available pocket + bank balance is too low.", NamedTextColor.RED));
            return;
        }
        prepared.put(requester.getUniqueId(), new Prepared(target.getUniqueId(), quote));
        showPreview(requester, target, quote);
    }

    private void showPreview(Player requester, Player target, Quote quote) {
        Component line = Component.text("Teleport to ", NamedTextColor.GOLD)
                .append(Component.text("@" + target.getName(), NamedTextColor.LIGHT_PURPLE))
                .append(Component.text("  |  Distance: ", NamedTextColor.DARK_GRAY))
                .append(Component.text(quote.distanceLabel(), NamedTextColor.AQUA))
                .append(Component.text("  |  Travel cost: ", NamedTextColor.YELLOW))
                .append(Component.text("✦ " + quote.price() + " coins", NamedTextColor.GOLD));
        Component buttons = Component.text("[ SEND ]", NamedTextColor.GREEN)
                .clickEvent(ClickEvent.runCommand("/tpasend"))
                .append(Component.text("      ", NamedTextColor.GRAY))
                .append(Component.text("[ DECLINE ]", NamedTextColor.RED)
                        .clickEvent(ClickEvent.runCommand("/tpacancel")));
        requester.sendMessage(line);
        requester.sendMessage(buttons);
    }

    private void sendPrepared(Player requester) {
        Prepared selection = prepared.remove(requester.getUniqueId());
        if (selection == null) {
            requester.sendMessage(Component.text("There is no prepared teleport request. Use /tpa <player> first.", NamedTextColor.RED));
            return;
        }
        Player target = Bukkit.getPlayer(selection.targetId());
        if (target == null || !target.isOnline()) {
            requester.sendMessage(Component.text("That player is no longer online.", NamedTextColor.RED));
            return;
        }
        Quote current = quote(requester, target);
        if (current == null) return;
        if (current.price() != selection.quote().price() || current.distance() != selection.quote().distance()
                || current.netherFixed() != selection.quote().netherFixed()) {
            prepared.put(requester.getUniqueId(), new Prepared(target.getUniqueId(), current));
            requester.sendMessage(Component.text("The distance changed. Please review the updated cost before sending.", NamedTextColor.YELLOW));
            showPreview(requester, target, current);
            return;
        }
        if (!hasFunds(requester, current.price())) {
            requester.sendMessage(Component.text("You no longer have enough available coins for this teleport.", NamedTextColor.RED));
            return;
        }
        if (incoming.containsKey(target.getUniqueId())) {
            requester.sendMessage(Component.text("That player already has a pending teleport request.", NamedTextColor.RED));
            return;
        }
        Request request = new Request(requester.getUniqueId(), target.getUniqueId(), current);
        incoming.put(target.getUniqueId(), request);
        long timeout = Math.max(10L, plugin.getConfig().getLong("tpa.timeout-seconds", 60L));
        BukkitTask task = Bukkit.getScheduler().runTaskLater(plugin, () -> {
            Request active = incoming.get(target.getUniqueId());
            if (active == request) {
                incoming.remove(target.getUniqueId());
                expiryTasks.remove(target.getUniqueId());
                Player onlineRequester = Bukkit.getPlayer(requester.getUniqueId());
                if (onlineRequester != null) onlineRequester.sendMessage(Component.text("Your teleport request expired.", NamedTextColor.RED));
                if (target.isOnline()) target.sendMessage(Component.text("Teleport request expired.", NamedTextColor.GRAY));
            }
        }, timeout * 20L);
        expiryTasks.put(target.getUniqueId(), task);

        target.sendMessage(Component.text("Teleport request from ", NamedTextColor.GOLD)
                .append(Component.text(requester.getName(), NamedTextColor.LIGHT_PURPLE))
                .append(Component.text("  |  Distance: ", NamedTextColor.DARK_GRAY))
                .append(Component.text(current.distanceLabel(), NamedTextColor.AQUA))
                .append(Component.text("  |  Travel cost: ", NamedTextColor.YELLOW))
                .append(Component.text("✦ " + current.price() + " coins", NamedTextColor.GOLD)));
        target.sendMessage(Component.text("[ ACCEPT ]", NamedTextColor.GREEN)
                .clickEvent(ClickEvent.runCommand("/tpaccept"))
                .append(Component.text("      ", NamedTextColor.GRAY))
                .append(Component.text("[ DECLINE ]", NamedTextColor.RED)
                        .clickEvent(ClickEvent.runCommand("/tpdeny")));
        requester.sendMessage(Component.text("Request sent to " + target.getName() + ".", NamedTextColor.GREEN));
    }

    private void accept(Player target) {
        Request request = incoming.remove(target.getUniqueId());
        cancelExpiry(target.getUniqueId());
        if (request == null) {
            target.sendMessage(Component.text("You have no pending teleport request.", NamedTextColor.RED));
            return;
        }
        Player requester = Bukkit.getPlayer(request.requesterId());
        if (requester == null || !requester.isOnline()) {
            target.sendMessage(Component.text("The requester is no longer online.", NamedTextColor.RED));
            return;
        }
        Quote current = quote(requester, target);
        if (current == null) return;
        if (current.price() != request.quote().price()) {
            requester.sendMessage(Component.text("The travel cost changed; please submit a new /tpa request.", NamedTextColor.YELLOW));
            target.sendMessage(Component.text("The travel cost changed. Ask the player to send a new request.", NamedTextColor.YELLOW));
            return;
        }
        if (!spendFunds(requester, current.price())) {
            requester.sendMessage(Component.text("Teleport failed: you no longer have enough pocket + bank coins.", NamedTextColor.RED));
            target.sendMessage(Component.text("Teleport cancelled because the requester no longer has enough coins.", NamedTextColor.RED));
            return;
        }
        Location destination = target.getLocation().clone();
        requester.teleport(destination);
        requester.sendMessage(Component.text("Teleported to " + target.getName() + ". Cost: "
                + current.price() + " coins.", NamedTextColor.GREEN));
        target.sendMessage(Component.text(requester.getName() + " teleported to you. Cost: "
                + current.price() + " coins.", NamedTextColor.GREEN));
    }

    private void deny(Player target) {
        Request request = incoming.remove(target.getUniqueId());
        cancelExpiry(target.getUniqueId());
        if (request == null) {
            target.sendMessage(Component.text("You have no pending teleport request.", NamedTextColor.RED));
            return;
        }
        Player requester = Bukkit.getPlayer(request.requesterId());
        target.sendMessage(Component.text("Teleport request declined.", NamedTextColor.RED));
        if (requester != null) requester.sendMessage(Component.text(target.getName() + " declined your teleport request.", NamedTextColor.RED));
    }

    private void cancelExpiry(UUID targetId) {
        BukkitTask task = expiryTasks.remove(targetId);
        if (task != null) task.cancel();
    }

    private Quote quote(Player requester, Player target) {
        if (requester.getWorld() != target.getWorld()) {
            if (requester.getWorld().getEnvironment() == World.Environment.NETHER
                    || target.getWorld().getEnvironment() == World.Environment.NETHER) {
                return new Quote(-1, plugin.getConfig().getInt("tpa.nether-fixed-cost", 66), true, "cross-dimension");
            }
            requester.sendMessage(Component.text("Teleport requests across different worlds are not supported (except Nether travel).", NamedTextColor.RED));
            return null;
        }
        double exact = Math.sqrt(requester.getLocation().distanceSquared(target.getLocation()));
        int distance = (int) Math.round(exact);
        boolean nether = requester.getWorld().getEnvironment() == World.Environment.NETHER;
        if (nether) return new Quote(distance, plugin.getConfig().getInt("tpa.nether-fixed-cost", 66), true, distance + " blocks");
        int price;
        if (distance <= 250) price = plugin.getConfig().getInt("tpa.prices.up-to-250", 10);
        else if (distance <= 1000) price = plugin.getConfig().getInt("tpa.prices.up-to-1000", 20);
        else if (distance <= 3000) price = plugin.getConfig().getInt("tpa.prices.up-to-3000", 30);
        else if (distance <= 6000) price = plugin.getConfig().getInt("tpa.prices.up-to-6000", 40);
        else if (distance <= 10000) price = plugin.getConfig().getInt("tpa.prices.up-to-10000", 50);
        else price = plugin.getConfig().getInt("tpa.prices.over-10000", 75);
        return new Quote(distance, price, false, String.format(Locale.US, "%,d blocks", distance));
    }

    private boolean hasFunds(Player player, int amount) {
        try {
            Object economy = economyPlugin();
            Method getCombined = economy.getClass().getMethod("getCombinedBalance", UUID.class);
            double balance = ((Number) getCombined.invoke(economy, player.getUniqueId())).doubleValue();
            return balance + 1e-9 >= amount;
        } catch (ReflectiveOperationException | RuntimeException ex) {
            player.sendMessage(Component.text("XZHEconomySystem is unavailable; teleport requests require the XZH economy plugin.", NamedTextColor.RED));
            return false;
        }
    }

    private boolean spendFunds(Player player, int amount) {
        try {
            Object economy = economyPlugin();
            Method spend = economy.getClass().getMethod("spendAvailableFunds", UUID.class, double.class);
            return Boolean.TRUE.equals(spend.invoke(economy, player.getUniqueId(), (double) amount));
        } catch (ReflectiveOperationException | RuntimeException ex) {
            plugin.getLogger().warning("Could not charge TPA travel cost: " + ex.getClass().getSimpleName());
            player.sendMessage(Component.text("Teleport failed because the XZH economy charge could not be completed.", NamedTextColor.RED));
            return false;
        }
    }

    private Object economyPlugin() throws ReflectiveOperationException {
        Plugin economy = Bukkit.getPluginManager().getPlugin("XZHEconomySystem");
        if (economy == null || !economy.isEnabled()) throw new ReflectiveOperationException("Economy plugin is unavailable");
        return economy;
    }
}
