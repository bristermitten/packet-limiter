package net.developerden.packetlimiter;

import com.comphenix.protocol.PacketType;
import com.comphenix.protocol.ProtocolLibrary;
import com.comphenix.protocol.ProtocolManager;
import com.comphenix.protocol.concurrency.ConcurrentPlayerMap;
import com.comphenix.protocol.events.ListenerPriority;
import com.comphenix.protocol.events.PacketAdapter;
import com.comphenix.protocol.events.PacketContainer;
import com.comphenix.protocol.events.PacketEvent;
import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;

import java.lang.reflect.InvocationTargetException;
import java.util.Collection;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

public final class PacketLimiter extends JavaPlugin {

    private final ConcurrentPlayerMap<ConcurrentLinkedQueue<PacketContainer>> packetQueue =
            new ConcurrentPlayerMap<>(ConcurrentPlayerMap.PlayerKey.NAME);

    private final ConcurrentPlayerMap<Set<PacketContainer>> packetsSentInTick =
            new ConcurrentPlayerMap<>(ConcurrentPlayerMap.PlayerKey.NAME);


    @Override
    public void onEnable() {
        final ProtocolManager protocolManager = ProtocolLibrary.getProtocolManager();
        protocolManager.addPacketListener(new PacketAdapter(
                this, ListenerPriority.HIGHEST, PacketType.Play.Server.MAP_CHUNK
        ) {
            @Override
            public void onPacketSending(PacketEvent event) {
                getSLF4JLogger().debug("Intercepting packet send {} to player {}",
                        event.getPacket(), event.getPlayer());

                final Set<PacketContainer> packets =
                        packetsSentInTick.computeIfAbsent(event.getPlayer(),
                                $ -> ConcurrentHashMap.newKeySet());
                packets.add(event.getPacket());
                if (packets.size() > 12) {
                    event.setCancelled(true);
                    getSLF4JLogger().debug("Cancelling chunk packet to player {} {}",
                            event.getPlayer(), event.getPacket());
                    packetQueue.computeIfAbsent(event.getPlayer(), $ -> new ConcurrentLinkedQueue<>())
                            .add(event.getPacket());
                }
            }
        });

        Bukkit.getScheduler().runTaskTimerAsynchronously(this,
                () -> {
                    packetsSentInTick.values().forEach(Collection::clear);
                    packetQueue.forEach((player, queue) -> {
                        int count = queue.size();
                        queue.forEach(packet -> {
                            try {
                                getSLF4JLogger().debug("Resending packet {} to player {}", player,
                                        packet);
                                protocolManager.sendServerPacket(player, packet);
                            } catch (InvocationTargetException e) {
                                e.printStackTrace();
                            }
                        });
                        if (count != 0) {
                            getSLF4JLogger().info("Resent {} packets to {}", count, player.getName());
                        }
                        queue.clear();
                    });
                },
                0L, 1L);
    }

    @Override
    public void onDisable() {
        // Plugin shutdown logic
    }
}
