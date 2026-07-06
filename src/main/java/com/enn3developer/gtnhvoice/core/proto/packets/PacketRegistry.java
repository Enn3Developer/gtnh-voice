/*
 * Adapted from Plasmo Voice (su.plo.voice), licensed under LGPL-3.0.
 * See THIRD-PARTY-NOTICES.md for details.
 */
package com.enn3developer.gtnhvoice.core.proto.packets;

import java.util.function.Supplier;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;

public class PacketRegistry {

    private static final Logger LOGGER = LogManager.getLogger(PacketRegistry.class);

    private final Int2ObjectOpenHashMap<Supplier<? extends Packet<?>>> packetFactoryById = new Int2ObjectOpenHashMap<>();
    private final Object2IntOpenHashMap<Class<? extends Packet<?>>> packetIdByType = new Object2IntOpenHashMap<>();
    private final Int2ObjectOpenHashMap<PacketDirection> packetDirectionById = new Int2ObjectOpenHashMap<>();

    public void register(int packetId, PacketDirection direction, Class<? extends Packet<?>> clazz,
        Supplier<? extends Packet<?>> factory) {
        packetFactoryById.put(packetId, factory);
        packetIdByType.put(clazz, packetId);
        packetDirectionById.put(packetId, direction);
    }

    public @Nullable Packet<?> byType(int type, @NotNull PacketDirection direction) {
        PacketDirection packetDirection = packetDirectionById.get(type);
        if (packetDirection == null || !packetDirection.accepts(direction)) return null;

        Supplier<? extends Packet<?>> packetFactory = packetFactoryById.get(type);
        if (packetFactory == null) return null;

        try {
            return packetFactory.get();
        } catch (Exception e) {
            LOGGER.error("Packet factory for type {} threw", type, e);
            return null;
        }
    }

    public int getType(Packet<?> packet) {
        return packetIdByType.getOrDefault(packet.getClass(), -1);
    }
}
