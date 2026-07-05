package com.enn3developer.gtnhvoice.server.group;

import java.util.Map;
import java.util.UUID;
import java.util.function.Predicate;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import com.enn3developer.gtnhvoice.core.proto.packets.udp.clientbound.SourceAudioPacket;
import com.enn3developer.gtnhvoice.core.proto.packets.udp.serverbound.PlayerAudioPacket;
import com.enn3developer.gtnhvoice.server.PlayerSnapshot;
import com.enn3developer.gtnhvoice.server.VoiceServerSession;

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
    private Predicate<VoiceServerSession> predicate = session -> true;
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
        return filter(session -> session.getLastAddress() != null);
    }

    /**
     * Drops recipients without a position snapshot or in a different dimension than the speaker. When the
     * speaker has no snapshot, EVERYONE is dropped - positional routing is meaningless without one.
     */
    public RecipientSelection sameDimension() {
        PlayerSnapshot speakerPos = speakerSnapshot();
        if (speakerPos == null) return filter(session -> false);

        Map<UUID, PlayerSnapshot> snapshot = context.getPositionSnapshot();
        return filter(session -> {
            PlayerSnapshot recipientPos = snapshot.get(session.getPlayerUuid());
            if (recipientPos == null) return false;

            return recipientPos.getDimensionId() == speakerPos.getDimensionId();
        });
    }

    /**
     * Drops recipients without a position snapshot or farther than {@code cutoff} blocks from the speaker
     * (exactly-at-cutoff is kept). When the speaker has no snapshot, EVERYONE is dropped - positional routing is
     * meaningless without one.
     */
    public RecipientSelection cutoffDistance(double cutoff) {
        PlayerSnapshot speakerPos = speakerSnapshot();
        if (speakerPos == null) return filter(session -> false);

        Map<UUID, PlayerSnapshot> snapshot = context.getPositionSnapshot();
        return filter(session -> {
            PlayerSnapshot recipientPos = snapshot.get(session.getPlayerUuid());
            if (recipientPos == null) return false;

            return speakerPos.distanceTo(recipientPos) <= cutoff;
        });
    }

    /**
     * Escape hatch for custom rules: keeps only sessions matching {@code recipientFilter}. The predicate runs on
     * the UDP/Netty thread and must obey the {@link IGroup#route} contract - read-only, non-blocking, no world
     * state. Works whether or not the speaker has a position snapshot.
     */
    public RecipientSelection filter(@NotNull Predicate<VoiceServerSession> recipientFilter) {
        requireNotConsumed();
        predicate = predicate.and(recipientFilter);
        return this;
    }

    /**
     * Terminal: builds a {@link SourceAudioPacket} (frame sequence, {@code sourceState}, frame data, speaker
     * UUID, speaker coordinates) for each remaining session and sends it via {@link RoutingContext#sendTo}.
     * Speaker coordinates come from the speaker's position snapshot, or (0, 0, 0) when absent - send() itself
     * never drops recipients over a missing speaker snapshot. Consumes the selection: a second send() (or any
     * further filter) throws {@link IllegalStateException}.
     */
    public void send(byte sourceState) {
        requireNotConsumed();
        consumed = true;

        PlayerAudioPacket audio = context.getAudio();
        UUID speakerUuid = context.getSpeakerSession()
            .getPlayerUuid();
        PlayerSnapshot speakerPos = speakerSnapshot();
        double x = speakerPos == null ? 0 : speakerPos.getX();
        double y = speakerPos == null ? 0 : speakerPos.getY();
        double z = speakerPos == null ? 0 : speakerPos.getZ();

        for (VoiceServerSession session : context.getSessionsByPlayerUuid()
            .values()) {
            if (!predicate.test(session)) continue;

            SourceAudioPacket forward = new SourceAudioPacket(
                audio.getSequenceNumber(),
                sourceState,
                audio.getData(),
                speakerUuid,
                x,
                y,
                z);
            context.sendTo(session, forward);
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
