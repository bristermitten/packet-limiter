package me.bristermitten.packetlimiter;

import com.comphenix.protocol.PacketType;
import com.comphenix.protocol.ProtocolLibrary;
import com.comphenix.protocol.ProtocolManager;
import com.comphenix.protocol.concurrency.ConcurrentPlayerMap;
import com.comphenix.protocol.events.ListenerPriority;
import com.comphenix.protocol.events.PacketAdapter;
import com.comphenix.protocol.events.PacketContainer;
import com.comphenix.protocol.events.PacketEvent;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.lang.reflect.InvocationTargetException;
import java.util.Collection;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

public final class PacketLimiter extends JavaPlugin {
    private static final int DEFAULT_MAX_PACKETS = 15;
    private static final String DEBUG_PREFIX = "[Debug] ";
    private final ConcurrentPlayerMap<Queue<PacketContainer>> packetQueue =
            new ConcurrentPlayerMap<>(ConcurrentPlayerMap.PlayerKey.NAME);
    private final ConcurrentPlayerMap<Set<PacketContainer>> packetsSentInTick = new ConcurrentPlayerMap<>(ConcurrentPlayerMap.PlayerKey.NAME);

    private void debugLog(String format, Object arg1, Object arg2) {
        if (getConfig().getBoolean("debug")) {
            var message = DEBUG_PREFIX + format;
            getSLF4JLogger().info(message, arg1, arg2);
        }
    }

    private Set<PacketContainer> getPacketsSentInTick(Player player) {
        return packetsSentInTick.computeIfAbsent(player, p -> ConcurrentHashMap.newKeySet());
    }

    private Queue<PacketContainer> getPacketQueue(Player player) {
        return packetQueue.computeIfAbsent(player, p -> new ConcurrentLinkedQueue<>());
    }

    @Override
    public void onEnable() {
        saveDefaultConfig();
        //noinspection ConstantConditions
        getCommand("paperlagreload").setExecutor(new ReloadCommand(this));

        final ProtocolManager protocolManager = ProtocolLibrary.getProtocolManager();
        protocolManager.addPacketListener(new PacketAdapter(this, ListenerPriority.HIGHEST, PacketType.Play.Server.MAP_CHUNK) {
            @Override
            public void onPacketSending(PacketEvent event) {
                debugLog("Intercepting packet send {} to player {}", event.getPacket(), event.getPlayer());

                final Set<PacketContainer> packets = getPacketsSentInTick(event.getPlayer());
                packets.add(event.getPacket());
                final int maxPackets = getConfig().getInt("max-packets-per-tick", DEFAULT_MAX_PACKETS);
                if (packets.size() >= maxPackets) {
                    event.setCancelled(true);
                    debugLog("Cancelling chunk packet to player {} {}", event.getPlayer(), event.getPacket());
                    getPacketQueue(event.getPlayer()).add(event.getPacket());
                }
            }
        });

        Bukkit.getScheduler().runTaskTimerAsynchronously(this, () -> {
            packetsSentInTick.values().forEach(Collection::clear);
            packetQueue.forEach((player, queue) -> {
                int count = queue.size();
                queue.forEach(packet -> {
                    try {
                        debugLog("Resending packet {} to player {}", player, packet);
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
        }, 0L, 1L);
    }
}