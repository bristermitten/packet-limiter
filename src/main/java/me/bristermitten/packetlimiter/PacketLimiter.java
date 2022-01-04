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
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.lang.reflect.InvocationTargetException;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;

public final class PacketLimiter extends JavaPlugin implements Listener {
    private static final int DEFAULT_MAX_PACKETS = 15;
    private static final String DEBUG_PREFIX = "[Debug] ";
    private final ConcurrentPlayerMap<Queue<PacketContainer>> packetQueue =
            new ConcurrentPlayerMap<>(ConcurrentPlayerMap.PlayerKey.NAME);
    private final ConcurrentPlayerMap<AtomicInteger> packetsSentInTick =
            new ConcurrentPlayerMap<>(ConcurrentPlayerMap.PlayerKey.NAME);

    private boolean debugEnabled = false;
    private int maxPackets = DEFAULT_MAX_PACKETS;

    private void debugLog(String format, Object arg1, Object arg2) {
        if (debugEnabled) {
            var message = DEBUG_PREFIX + format;
            getSLF4JLogger().info(message, arg1, arg2);
        }
    }

    private void debugLog(String format, Object arg1) {
        if (debugEnabled) {
            var message = DEBUG_PREFIX + format;
            getSLF4JLogger().info(message, arg1);
        }
    }

    private AtomicInteger getPacketsSentInTick(Player player) {
        return packetsSentInTick.computeIfAbsent(player, p -> new AtomicInteger());
    }

    private Queue<PacketContainer> getPacketQueue(Player player) {
        return packetQueue.computeIfAbsent(player, p -> new ConcurrentLinkedQueue<>());
    }

    @Override
    public void onEnable() {
        saveDefaultConfig();

        reloadConfig();

        //noinspection ConstantConditions
        getCommand("paperlagreload").setExecutor(new ReloadCommand(this));

        final ProtocolManager protocolManager = ProtocolLibrary.getProtocolManager();
        registerPacketListener(protocolManager);

        Bukkit.getScheduler().runTaskTimerAsynchronously(this, () ->
                packetQueue.forEach((player, queue) -> {
                    getPacketsSentInTick(player).set(0); // Reset packet counter for this tick
                    int i;
                    // re-send no more than the maximum packets
                    for (i = 0; i < maxPackets; i++) {
                        final var packet = queue.poll();
                        if (packet == null) {
                            break;
                        }
                        debugLog("Resending packet {} to player {}", player);
                        try {
                            protocolManager.sendServerPacket(player, packet);
                        } catch (InvocationTargetException e) {
                            e.printStackTrace();
                        }
                    }
                    if (i != 0) {
                        debugLog("Resent {} packets to {}", i, player.getName());
                    }
                }), 0L, 1L);

        Bukkit.getPluginManager().registerEvents(this, this);
    }

    @Override
    public void reloadConfig() {
        super.reloadConfig();

        var config = getConfig();

        debugEnabled = config.getBoolean("debug");

        var $maxPackets = config.getInt("max-packets-per-tick", DEFAULT_MAX_PACKETS);

        if ($maxPackets < 1) {
            maxPackets = DEFAULT_MAX_PACKETS;
            getSLF4JLogger().warn("Illegal value for option `max-packets-per-tick`: {}. Using default value of {}", $maxPackets, DEFAULT_MAX_PACKETS);
        } else {
            maxPackets = $maxPackets;
        }
    }

    private void registerPacketListener(ProtocolManager protocolManager) {
        protocolManager.addPacketListener(new PacketAdapter(this, ListenerPriority.HIGHEST, PacketType.Play.Server.MAP_CHUNK) {
            @Override
            public void onPacketSending(PacketEvent event) {
                final var packet = event.getPacket();

                final int sentInTick = getPacketsSentInTick(event.getPlayer()).incrementAndGet();
                if (sentInTick > maxPackets) {
                    event.setCancelled(true);
                    debugLog("Cancelling chunk packet to player {}", event.getPlayer());
                    getPacketQueue(event.getPlayer()).add(packet);
                }
            }
        });
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent e) {
        var player = e.getPlayer();
        packetQueue.remove(player);
        packetsSentInTick.remove(player);
    }
}
