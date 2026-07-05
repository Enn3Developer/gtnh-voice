package com.enn3developer.gtnhvoice.server.group;

import java.util.UUID;

import org.jetbrains.annotations.NotNull;

import com.enn3developer.gtnhvoice.core.proto.packets.udp.clientbound.SourceAudioPacket;
import com.enn3developer.gtnhvoice.core.proto.packets.udp.serverbound.PlayerAudioPacket;
import com.enn3developer.gtnhvoice.server.VoiceServerSession;

/**
 * One voice routing group: owns recipient selection AND builds the outgoing {@link SourceAudioPacket} per
 * recipient - that packet's sourceState flags are where positional-vs-flat playback is decided, so each group
 * fully controls both who hears a speaker and how.
 * <p>
 * Zero LWJGL/OpenAL usage anywhere in this package - groups run on the server and must load on a dedicated one.
 */
public interface IGroup {

    /** Identity of this group, for logs and (later) UI. Never {@code null}. */
    @NotNull
    String getName();

    /**
     * Display name shown inside the [] of the HUD self row, synced to the member via
     * {@link com.enn3developer.gtnhvoice.network.VoiceGroupUpdatePacket}. Distinct from {@link #getName()},
     * which stays the internal identity. Never {@code null} - it is captured on assignment and written to the
     * wire unchecked from the server tick.
     */
    @NotNull
    String getDisplayName();

    /**
     * Routes one inbound frame of speaker audio to this group's recipients.
     * <p>
     * Threading contract: called on the UDP/Netty thread. Implementations may only read what {@code context}
     * hands them - the immutable per-tick position snapshot and the read-only session view - and send through
     * {@link RoutingContext#sendTo}. They must never touch {@code EntityPlayerMP}/world state and must never
     * block.
     */
    void route(@NotNull VoiceServerSession speakerSession, @NotNull PlayerAudioPacket audio,
        @NotNull RoutingContext context);

    /**
     * Drops any per-player state held for {@code playerUuid}. Called on logout and from the stale-session
     * reaper, mirroring how the server manager cleans its own per-player maps. {@link GroupManager} notifies
     * every group regardless of the player's current assignment (state may predate a reassignment), so
     * implementations must be cheap, non-blocking map removals that tolerate players they never saw.
     */
    default void onPlayerRemoved(@NotNull UUID playerUuid) {}

    /** Drops all per-player state. Called when the voice server shuts down; must be idempotent. */
    default void clear() {}
}
