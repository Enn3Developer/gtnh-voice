package com.enn3developer.gtnhvoice.server;

import java.util.UUID;

import org.jetbrains.annotations.NotNull;

/**
 * Immutable, off-thread-safe copy of one player's position/dimension as of the last server tick.
 * Built on the server thread by {@link VoiceServerManager}, read from the UDP/Netty thread during
 * proximity routing - the UDP path must never touch live {@code EntityPlayerMP}/world state
 * directly, only this snapshot.
 */
public final class PlayerSnapshot {

    private final UUID uuid;
    private final String name;
    private final double x;
    private final double y;
    private final double z;
    private final int dimensionId;

    public PlayerSnapshot(@NotNull UUID uuid, @NotNull String name, double x, double y, double z, int dimensionId) {
        this.uuid = uuid;
        this.name = name;
        this.x = x;
        this.y = y;
        this.z = z;
        this.dimensionId = dimensionId;
    }

    public UUID getUuid() {
        return uuid;
    }

    public String getName() {
        return name;
    }

    public double getX() {
        return x;
    }

    public double getY() {
        return y;
    }

    public double getZ() {
        return z;
    }

    public int getDimensionId() {
        return dimensionId;
    }

    public double distanceTo(@NotNull PlayerSnapshot other) {
        double dx = x - other.x;
        double dy = y - other.y;
        double dz = z - other.z;
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }
}
