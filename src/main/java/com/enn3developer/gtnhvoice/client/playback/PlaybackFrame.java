package com.enn3developer.gtnhvoice.client.playback;

import java.util.Objects;
import java.util.UUID;

import org.jetbrains.annotations.NotNull;

import com.enn3developer.gtnhvoice.api.client.IPlaybackFrame;
import com.enn3developer.gtnhvoice.network.GroupNames;

/**
 * The one {@link IPlaybackFrame} implementation: an immutable per-frame context built where a frame enters
 * playback (the source's decode thread) and passed with it through {@link PlaybackManager#submit} and every
 * playback filter. A flat frame's position is NaN whatever it was built with, as {@link IPlaybackFrame#x()} promises.
 * The constructor and factory without a position are kept from 0.10.1 for code built against it, and leave the
 * position NaN.
 */
public record PlaybackFrame(@NotNull UUID sourceId, @NotNull String groupName, @NotNull String groupDisplayName,
    boolean positional, double x, double y, double z) implements IPlaybackFrame {

    public PlaybackFrame {
        Objects.requireNonNull(sourceId, "sourceId");
        Objects.requireNonNull(groupName, "groupName");
        Objects.requireNonNull(groupDisplayName, "groupDisplayName");
        if (!positional) {
            x = Double.NaN;
            y = Double.NaN;
            z = Double.NaN;
        }
    }

    /** A frame without a position, kept from 0.10.1: its position is NaN even when {@code positional}. */
    public PlaybackFrame(@NotNull UUID sourceId, @NotNull String groupName, @NotNull String groupDisplayName,
        boolean positional) {
        this(sourceId, groupName, groupDisplayName, positional, Double.NaN, Double.NaN, Double.NaN);
    }

    /**
     * A frame from {@code sourceId} routed by the group behind {@code names}, playing from (x, y, z) when
     * {@code positional}.
     */
    public static PlaybackFrame of(@NotNull UUID sourceId, @NotNull GroupNames names, boolean positional, double x,
        double y, double z) {
        return new PlaybackFrame(sourceId, names.name(), names.displayName(), positional, x, y, z);
    }

    /** A frame without a position, kept from 0.10.1: its position is NaN even when {@code positional}. */
    public static PlaybackFrame of(@NotNull UUID sourceId, @NotNull GroupNames names, boolean positional) {
        return of(sourceId, names, positional, Double.NaN, Double.NaN, Double.NaN);
    }
}
