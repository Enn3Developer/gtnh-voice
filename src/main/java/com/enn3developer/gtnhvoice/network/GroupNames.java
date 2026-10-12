package com.enn3developer.gtnhvoice.network;

import java.util.Objects;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * The names one voice group goes by in the {@link VoiceGroupTablePacket}: its internal registry name (the server-side
 * {@code IGroup#getName()} - stable, unique, what code keys on), its display name (presentation text for the HUD - may
 * change, may collide) and its display component (the server-side {@code IGroup#getDisplayComponent()} as
 * {@code IChatComponent.Serializer} JSON, which clients format in their own language and show instead of the display
 * name; {@code null} when the group has none). An immutable value shared by the server-side table builder, the packet
 * and the client's copy of the table.
 */
public record GroupNames(@NotNull String name, @NotNull String displayName, @Nullable String displayComponentJson) {

    /** The longest display component JSON the control packets carry, in UTF-8 bytes: FML's string length limit. */
    public static final int MAX_DISPLAY_COMPONENT_JSON_BYTES = 16383;

    public GroupNames {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(displayName, "displayName");
    }

    /** The names of a group without a display component. */
    public GroupNames(@NotNull String name, @NotNull String displayName) {
        this(name, displayName, null);
    }
}
