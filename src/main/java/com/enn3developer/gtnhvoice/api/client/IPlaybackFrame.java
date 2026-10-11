package com.enn3developer.gtnhvoice.api.client;

import java.util.UUID;

import org.jetbrains.annotations.NotNull;

/**
 * What one decoded playback frame IS, beyond its samples: whose voice it carries and how the server routed it to
 * this client. Handed to every {@link IPlaybackFrameFilter} alongside the PCM, so an effect can depend on the
 * frame's own routing - a radio crackle only on frames a radio group delivered, a phone band-pass only on a call
 * group - instead of on whatever the speaker happens to be doing by the time the frame plays.
 * <p>
 * Per frame, not per source: a speaker in several groups can reach you through a different group from one frame
 * to the next (group priorities decide, frame by frame, which membership delivers to which listener). The
 * context travels WITH the frame through the jitter buffer, so a filter always sees the group that routed the
 * frame it is processing - never one that only routed a newer frame still waiting in the buffer, and never an
 * older one that arrived out of order.
 * <p>
 * Packet-loss concealment: a frame synthesized to cover a lost packet carries the context of the source's most
 * recent REAL frame - the frame it continues - so a group-keyed effect stays on across a network gap instead of
 * flickering off for the concealed stretch. (Filters still cannot tell concealment from real audio, by design -
 * see {@link IPlaybackPcmFilter}.)
 * <p>
 * Attribution fallback: a frame whose group cannot be attributed resolves to the built-in local group
 * ({@code groupName()} and {@code groupDisplayName()} both {@code "local"}). That happens when the group table
 * has not reached the client yet (UDP audio can outrun the reliable channel for the first frames of a session),
 * or when the server routed the frame through a group it never registered with its group manager - the server
 * stamps those with local's wire id itself. The mic monitor (the settings screen playing your own processed mic
 * back) also passes through playback filters: its frames carry the nil UUID ({@code new UUID(0, 0)}), the local
 * group and {@code positional() == false}.
 * <p>
 * Instances are immutable values supplied by the mod: safe to keep, compare or hand to another thread. Addons
 * read them and never need to implement this interface outside of tests.
 */
public interface IPlaybackFrame {

    /** The voice source this frame belongs to - the speaking player's UUID (nil for the mic monitor). */
    @NotNull
    UUID sourceId();

    /**
     * The internal name of the group that routed this frame to this client - the server-side
     * {@code IGroup#getName()}: {@code "local"} for proximity chat, {@code "global"} for the built-in global group,
     * or whatever name an addon registered its group under. Stable and unique per server, so this is what to key
     * logic on.
     */
    @NotNull
    String groupName();

    /**
     * The display name of the group that routed this frame - presentation text, the label the HUD shows. It may
     * change while the group lives and two groups may share one; never key logic on it, use {@link #groupName()}.
     */
    @NotNull
    String groupDisplayName();

    /**
     * Whether this frame plays positionally (proximity/3D) or flat (full gain, no spatialization) - the routing
     * group's decision for this frame. The mod applies it to the source's OpenAL state as the frame is handed to
     * playback, so it tracks the audio it belongs to rather than packet arrival.
     */
    boolean positional();
}
