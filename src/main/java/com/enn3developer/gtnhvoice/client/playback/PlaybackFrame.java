package com.enn3developer.gtnhvoice.client.playback;

import java.util.Objects;
import java.util.UUID;

import org.jetbrains.annotations.NotNull;

import com.enn3developer.gtnhvoice.api.client.IPlaybackFrame;
import com.enn3developer.gtnhvoice.network.GroupNames;

/**
 * The one {@link IPlaybackFrame} implementation: an immutable per-frame context built where a frame enters
 * playback (the source's decode thread) and passed with it through {@link PlaybackManager#submit} and every
 * playback filter.
 */
public record PlaybackFrame(@NotNull UUID sourceId, @NotNull String groupName, @NotNull String groupDisplayName,
    boolean positional) implements IPlaybackFrame {

    public PlaybackFrame {
        Objects.requireNonNull(sourceId, "sourceId");
        Objects.requireNonNull(groupName, "groupName");
        Objects.requireNonNull(groupDisplayName, "groupDisplayName");
    }

    /** A frame from {@code sourceId} routed by the group behind {@code names}. */
    public static PlaybackFrame of(@NotNull UUID sourceId, @NotNull GroupNames names, boolean positional) {
        return new PlaybackFrame(sourceId, names.name(), names.displayName(), positional);
    }
}
