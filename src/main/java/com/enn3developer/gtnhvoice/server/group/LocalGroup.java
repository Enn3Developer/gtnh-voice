package com.enn3developer.gtnhvoice.server.group;

import org.jetbrains.annotations.NotNull;

import com.enn3developer.gtnhvoice.Config;
import com.enn3developer.gtnhvoice.api.server.SourceState;
import com.enn3developer.gtnhvoice.api.server.group.IGroup;
import com.enn3developer.gtnhvoice.api.server.group.RecipientSelection;
import com.enn3developer.gtnhvoice.api.server.group.RoutingContext;

/**
 * The default proximity group: same-dimension, distance-cutoff routing, always positional - the exact behavior
 * voice chat has always had. Every player is in this group unless {@link GroupManager} assigns them elsewhere.
 * <p>
 * Stateless: routing reads nothing beyond what {@link RoutingContext} hands it (see {@link IGroup#route} for the
 * threading contract). A speaker with no position snapshot yet is silently dropped - positional playback is
 * meaningless without one (the positional filters exclude everyone in that case).
 * <p>
 * Deliberately selects over ALL sessions, not just this group's members: recipients assigned to other groups must
 * keep hearing nearby local speakers. No explicit {@code sameDimension()} in the chain -
 * {@link RecipientSelection#cutoffDistance} is dimension-aware and carries the same-dimension check itself.
 */
public final class LocalGroup implements IGroup {

    /** The built-in {@link #getName} identity - the single source for every site that spells it out. */
    public static final String NAME = "local";

    @Override
    public @NotNull String getName() {
        return NAME;
    }

    @Override
    public @NotNull String getDisplayName() {
        // Exactly the label the HUD always hardcoded, so the default group renders unchanged.
        return NAME;
    }

    @Override
    public void route(@NotNull RoutingContext context) {
        context.getAllSessions()
            .excludeSelf()
            .excludeNoAddress()
            .cutoffDistance(Math.min(Config.distance, Config.maxDistance))
            .send(SourceState.POSITIONAL);
    }
}
