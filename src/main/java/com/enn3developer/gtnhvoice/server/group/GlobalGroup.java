package com.enn3developer.gtnhvoice.server.group;

import org.jetbrains.annotations.NotNull;

import com.enn3developer.gtnhvoice.core.proto.packets.udp.clientbound.SourceAudioPacket;

/**
 * Server-admin announcement group: broadcasts a speaker to EVERY connected voice client - no dimension check, no
 * distance cutoff, no recipient-snapshot requirement - as flat/full-gain audio ({@link SourceAudioPacket#FLAG_FLAT}).
 * The first sender of that flag; {@link LocalGroup} always stays positional.
 * <p>
 * Unlike {@link LocalGroup}, a speaker with no position snapshot yet is NOT dropped: flat playback ignores
 * position entirely, and announcements must work the instant an admin joins, so the packet just carries
 * (0, 0, 0). Stateless (see {@link IGroup#route} for the threading contract).
 */
public final class GlobalGroup implements IGroup {

    /** The built-in {@link #getName} identity - the single source for every site that spells it out. */
    public static final String NAME = "global";

    @Override
    public @NotNull String getName() {
        return NAME;
    }

    @Override
    public @NotNull String getDisplayName() {
        return NAME;
    }

    @Override
    public void route(@NotNull RoutingContext context) {
        context.getAllSessions()
            .excludeSelf()
            .excludeNoAddress()
            .send(SourceAudioPacket.FLAG_FLAT);
    }
}
