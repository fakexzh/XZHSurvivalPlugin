package com.xzh.survival;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;

import java.lang.reflect.Method;

/** Optional GSit integration; reflection keeps GSit a soft dependency. */
final class GsitCrawlSupport {
    private GsitCrawlSupport() {}

    static Object start(Player player, JavaPlugin owner) {
        Plugin gsit = Bukkit.getPluginManager().getPlugin("GSit");
        if (gsit != null && gsit.isEnabled()) {
            try {
                ClassLoader loader = gsit.getClass().getClassLoader();
                Class<?> api = loader.loadClass("dev.geco.gsit.api.GSitAPI");
                Class<?> crawlType = loader.loadClass("dev.geco.gsit.model.CrawlType");
                @SuppressWarnings({"unchecked", "rawtypes"})
                Object commandType = Enum.valueOf((Class<? extends Enum>) crawlType.asSubclass(Enum.class), "COMMAND");
                Method start = api.getMethod("startCrawl", Player.class, crawlType);
                Object crawl = start.invoke(null, player, commandType);
                if (crawl != null) return crawl;
                owner.getLogger().warning("GSit declined the knockdown crawl request; using vanilla crawl state.");
            } catch (ReflectiveOperationException | LinkageError ex) {
                owner.getLogger().warning("GSit crawl API integration failed; using vanilla crawl state: " + ex.getClass().getSimpleName());
            }
        }
        player.setSwimming(true);
        return null;
    }

    static void stop(Player player, Object crawl, JavaPlugin owner) {
        if (crawl != null) {
            Plugin gsit = Bukkit.getPluginManager().getPlugin("GSit");
            if (gsit != null && gsit.isEnabled()) {
                try {
                    ClassLoader loader = gsit.getClass().getClassLoader();
                    Class<?> api = loader.loadClass("dev.geco.gsit.api.GSitAPI");
                    Class<?> stopReason = loader.loadClass("dev.geco.gsit.model.StopReason");
                    @SuppressWarnings({"unchecked", "rawtypes"})
                    Object pluginReason = Enum.valueOf((Class<? extends Enum>) stopReason.asSubclass(Enum.class), "PLUGIN");
                    Method stop = api.getMethod("stopCrawl", crawl.getClass(), stopReason);
                    stop.invoke(null, crawl, pluginReason);
                    return;
                } catch (ReflectiveOperationException | LinkageError ex) {
                    owner.getLogger().warning("Could not cleanly stop GSit crawl session: " + ex.getClass().getSimpleName());
                }
            }
        }
        if (player != null && player.isOnline()) player.setSwimming(false);
    }
}
