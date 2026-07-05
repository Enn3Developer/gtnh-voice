package com.enn3developer.gtnhvoice.api.server.group;

import java.util.UUID;
import java.util.function.Predicate;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import com.enn3developer.gtnhvoice.api.server.IVoiceSession;
import com.enn3developer.gtnhvoice.api.server.PlayerSnapshot;
import com.enn3developer.gtnhvoice.api.server.SourceState;

/**
 * Fluent recipient selection for one routed audio frame, created only by
 * {@link RoutingContext#getAllSessions()}/{@link RoutingContext#getSessionsForGroup()}. Filters compose into a
 * single predicate and the session source is iterated exactly once, inside {@link #send} - no intermediate lists,
 * since this runs per audio frame on the UDP/Netty thread (see {@link IGroup#route} for the threading contract).
 * <p>
 * Single-use, and enforced: chain filters, terminate with {@link #send}, and never let the selection escape the
 * {@link IGroup#route} call it was created in - it captures the per-frame context. Any filter or {@link #send}
 * after the terminal {@link #send} throws {@link IllegalStateException}, so an accidental double-send fails
 * loudly instead of silently duplicating every frame on the wire.
 */
public final class RecipientSelection {

    private final RoutingContext context;
    private Predicate<IVoiceSession> predicate = session -> true;
    private boolean consumed;

    RecipientSelection(@NotNull RoutingContext context) {
        this.context = context;
    }

    /** Drops the speaker themselves. Works whether or not the speaker has a position snapshot. */
    public RecipientSelection excludeSelf() {
        UUID speakerUuid = context.getSpeakerSession()
            .getPlayerUuid();
        return filter(
            session -> !session.getPlayerUuid()
                .equals(speakerUuid));
    }

    /** Drops sessions with no UDP address yet. Works whether or not the speaker has a position snapshot. */
    public RecipientSelection excludeNoAddress() {
        return filter(IVoiceSession::hasUdpAddress);
    }

    /**
     * Drops recipients without a position snapshot or in a different dimension than the speaker. When the
     * speaker has no snapshot, EVERYONE is dropped - positional routing is meaningless without one.
     */
    public RecipientSelection sameDimension() {
        PlayerSnapshot speakerPos = speakerSnapshot();
        if (speakerPos == null) return filter(session -> false);

        return filter(context.inDimension(speakerPos.getDimensionId()));
    }

    /**
     * Drops recipients without a position snapshot, in a different dimension than the speaker, or farther than
     * {@code cutoff} blocks from the speaker (exactly-at-cutoff is kept). Dimension-aware by definition, like
     * every distance filter: cross-dimension Euclidean distance selects nothing legitimate. When the speaker has
     * no snapshot, EVERYONE is dropped - positional routing is meaningless without one.
     */
    public RecipientSelection cutoffDistance(double cutoff) {
        PlayerSnapshot speakerPos = speakerSnapshot();
        if (speakerPos == null) return filter(session -> false);

        return filter(
            context.withinDistanceOf(
                speakerPos.getX(),
                speakerPos.getY(),
                speakerPos.getZ(),
                speakerPos.getDimensionId(),
                cutoff));
    }

    /**
     * Escape hatch for custom rules: keeps only sessions matching {@code recipientFilter}. The predicate runs on
     * the UDP/Netty thread and must obey the {@link IGroup#route} contract - read-only, non-blocking, no world
     * state. Works whether or not the speaker has a position snapshot.
     */
    public RecipientSelection filter(@NotNull Predicate<IVoiceSession> recipientFilter) {
        requireNotConsumed();
        predicate = predicate.and(recipientFilter);
        return this;
    }

    /**
     * Terminal: sends the context's audio frame to each remaining session via {@link RoutingContext#sendTo},
     * stamped with {@code sourceState} (see {@link SourceState}) and the speaker's coordinates. Speaker
     * coordinates come from the speaker's position snapshot, or (0, 0, 0) when absent - send() itself never
     * drops recipients over a missing speaker snapshot. Consumes the selection: a second send() (or any further
     * filter) throws {@link IllegalStateException}.
     */
    public void send(byte sourceState) {
        requireNotConsumed();
        consumed = true;

        PlayerSnapshot speakerPos = speakerSnapshot();
        double x = speakerPos == null ? 0 : speakerPos.getX();
        double y = speakerPos == null ? 0 : speakerPos.getY();
        double z = speakerPos == null ? 0 : speakerPos.getZ();

        for (IVoiceSession session : context.getSessionsByPlayerUuid()
            .values()) {
            if (!predicate.test(session)) continue;

            context.sendTo(session, sourceState, x, y, z);
        }
    }

    private void requireNotConsumed() {
        if (consumed) throw new IllegalStateException(
            "RecipientSelection is single-use and was already sent - build a fresh one from the RoutingContext");
    }

    private @Nullable PlayerSnapshot speakerSnapshot() {
        return context.getPositionSnapshot()
            .get(
                context.getSpeakerSession()
                    .getPlayerUuid());
    }
}
