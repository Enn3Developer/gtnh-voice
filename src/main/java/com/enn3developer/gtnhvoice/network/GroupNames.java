package com.enn3developer.gtnhvoice.network;

import java.util.Objects;

import org.jetbrains.annotations.NotNull;

/**
 * The two names one voice group goes by in the {@link VoiceGroupTablePacket}: its internal registry name (the
 * server-side {@code IGroup#getName()} - stable, unique, what code keys on) and its display name (presentation text
 * for the HUD - may change, may collide). An immutable value shared by the server-side table builder, the packet
 * and the client's copy of the table.
 */
public record GroupNames(@NotNull String name, @NotNull String displayName) {

    public GroupNames {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(displayName, "displayName");
    }
}
