package com.xzh.survival;

import net.minecraft.core.Direction;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundAddEntityPacket;
import net.minecraft.network.protocol.game.ClientboundBundlePacket;
import net.minecraft.network.protocol.game.ClientboundRemoveEntitiesPacket;
import net.minecraft.network.protocol.game.ClientboundSetEntityDataPacket;
import net.minecraft.network.protocol.game.ClientboundTeleportEntityPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.monster.Shulker;
import org.bukkit.Location;
import org.bukkit.craftbukkit.CraftWorld;
import org.bukkit.craftbukkit.entity.CraftPlayer;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.Set;

/**
 * Creates the same kind of client-only low-clearance collision volume used by
 * modern crawl implementations. The fake Shulker is sent only to the crawling
 * player; it is never added to the real world and cannot affect other players.
 */
@SuppressWarnings("deprecation")
final class DownedCrawlVisual {
    private final Player player;
    private final ServerPlayer handle;
    private final Shulker crawlBox;
    private boolean spawned;

    DownedCrawlVisual(Player player) {
        this.player = player;
        this.handle = ((CraftPlayer) player).getHandle();
        this.crawlBox = new Shulker(EntityTypes.SHULKER, ((CraftWorld) player.getWorld()).getHandle());
        crawlBox.setPos(player.getLocation().getX(), player.getLocation().getY() + 0.5, player.getLocation().getZ());
        crawlBox.persist = false;
        crawlBox.setInvisible(true);
        crawlBox.setNoGravity(true);
        crawlBox.setPermanentlyInvulnerable(true);
        crawlBox.setNoAi(true);
        crawlBox.setSilent(true);
        crawlBox.setAttachFace(Direction.UP);
    }

    void start() {
        player.setSwimming(true);
        sendSpawn();
    }

    void update(Location location) {
        if (!spawned) {
            sendSpawn();
            return;
        }
        crawlBox.setPosRaw(location.getX(), location.getY() + 0.5, location.getZ());
        Packet<? super ClientGamePacketListener> metadata =
                new ClientboundSetEntityDataPacket(crawlBox.getId(), crawlBox.getEntityData().getNonDefaultValues());
        Packet<? super ClientGamePacketListener> teleport =
                new ClientboundTeleportEntityPacket(crawlBox.getId(),
                        net.minecraft.world.entity.PositionMoveRotation.of(crawlBox), Set.of(), false);
        handle.connection.send(new ClientboundBundlePacket(List.of(metadata, teleport)));
    }

    private void sendSpawn() {
        Location location = player.getLocation();
        crawlBox.setPos(location.getX(), location.getY() + 0.5, location.getZ());
        Packet<? super ClientGamePacketListener> add = new ClientboundAddEntityPacket(
                crawlBox.getId(), crawlBox.getUUID(), crawlBox.getX(), crawlBox.getY(), crawlBox.getZ(),
                crawlBox.getXRot(), crawlBox.getYRot(), crawlBox.getType(), 0,
                crawlBox.getDeltaMovement(), crawlBox.getYHeadRot());
        Packet<? super ClientGamePacketListener> metadata =
                new ClientboundSetEntityDataPacket(crawlBox.getId(), crawlBox.getEntityData().getNonDefaultValues());
        handle.connection.send(new ClientboundBundlePacket(List.of(add, metadata)));
        spawned = true;
    }

    void stop() {
        if (spawned) {
            handle.connection.send(new ClientboundRemoveEntitiesPacket(new int[]{crawlBox.getId()}));
            spawned = false;
        }
        player.setSwimming(false);
    }
}
