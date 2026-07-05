package com.enn3developer.gtnhvoice.client;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;

import com.enn3developer.gtnhvoice.GtnhVoice;
import com.enn3developer.gtnhvoice.core.api.util.LogThrottle;

/**
 * Ordered registry of {@link CapturePcmFilter}s on the outgoing capture path. Owned by
 * {@link VoiceClientManager} for its whole singleton lifetime and handed to each per-session
 * {@link CaptureSendWorker}, so registrations survive disconnect/reconnect cycles. CopyOnWrite because
 * {@link #apply} runs per mic frame on the capture-send thread while registration happens from arbitrary
 * threads - snapshot iteration is free and registration churn is rare. Chain order is registration order,
 * deterministically - a priority scheme is deliberately not provided until a real consumer needs one.
 */
final class CapturePcmFilterChain {

    private static final int FRAME_SAMPLES = 960; // 20ms @ 48kHz mono
    private static final long FILTER_ERROR_LOG_INTERVAL_MILLIS = 1_000L;

    private final List<CapturePcmFilter> filters = new CopyOnWriteArrayList<>();
    private final AtomicLong lastFilterErrorLogMillis = new AtomicLong();

    /** Registers a capture PCM filter - see {@link CapturePcmFilter} for the threading and failure contract. */
    void add(CapturePcmFilter filter) {
        Objects.requireNonNull(filter, "filter");
        filters.add(filter);
    }

    /** Unregisters a previously added filter; no-op if it was never registered. */
    void remove(CapturePcmFilter filter) {
        filters.remove(filter);
    }

    /**
     * Runs {@code frame} through the filter chain in registration order, feeding each filter's output to the
     * next. A filter that throws, returns {@code null}, or returns a wrong-length array is skipped for this
     * frame - the current frame continues unfiltered into the rest of the chain - with an error log throttled to
     * one per {@value #FILTER_ERROR_LOG_INTERVAL_MILLIS}ms, mirroring {@code PlaybackManager}'s filter isolation
     * (minus its AL error drain, which has no business on the capture thread). With no filters registered this
     * is a single {@code isEmpty} check.
     */
    short[] apply(short[] frame) {
        if (filters.isEmpty()) return frame;

        for (CapturePcmFilter filter : filters) {
            try {
                short[] processed = filter.process(frame);
                if (processed != null && processed.length == FRAME_SAMPLES) {
                    frame = processed;
                    continue;
                }
                logFilterFailure(filter, "returned " + describeBadOutput(processed), null);
            } catch (Throwable t) {
                logFilterFailure(filter, "threw", t);
            }
        }
        return frame;
    }

    private static String describeBadOutput(short[] processed) {
        return processed == null ? "null" : processed.length + " samples instead of " + FRAME_SAMPLES;
    }

    private void logFilterFailure(CapturePcmFilter filter, String what, Throwable cause) {
        if (!LogThrottle.shouldLog(lastFilterErrorLogMillis, FILTER_ERROR_LOG_INTERVAL_MILLIS)) return;
        GtnhVoice.LOG.error("[Voice] Capture PCM filter {} {}; skipped for this frame", filter, what, cause);
    }
}
