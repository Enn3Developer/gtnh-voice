package com.enn3developer.gtnhvoice.server.group;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

import org.jetbrains.annotations.NotNull;

import com.enn3developer.gtnhvoice.core.api.util.LogThrottle;

/**
 * Per-speaker log throttling for group routing: one timestamp slot per speaker UUID, gated by
 * {@link LogThrottle} at a fixed interval. Owning groups must forward {@link IGroup#onPlayerRemoved} and
 * {@link IGroup#clear} here - this map is exactly the per-player state those lifecycle hooks exist to clean up.
 * <p>
 * {@link #shouldLog} runs on the UDP/Netty thread (from {@link IGroup#route}); removal runs on the server and
 * reaper threads. The map is concurrent, so no further synchronization is needed.
 */
final class SpeakerLogThrottle {

    private final Map<UUID, AtomicLong> lastLogMillis = new ConcurrentHashMap<>();
    private final long throttleMillis;

    SpeakerLogThrottle(long throttleMillis) {
        this.throttleMillis = throttleMillis;
    }

    /** Whether {@code speakerUuid}'s next log line is due, claiming the slot if so. */
    boolean shouldLog(@NotNull UUID speakerUuid) {
        AtomicLong last = lastLogMillis.computeIfAbsent(speakerUuid, id -> new AtomicLong());
        return LogThrottle.shouldLog(last, throttleMillis);
    }

    void onPlayerRemoved(@NotNull UUID playerUuid) {
        lastLogMillis.remove(playerUuid);
    }

    void clear() {
        lastLogMillis.clear();
    }
}
