package com.enn3developer.gtnhvoice.client;

import java.util.concurrent.atomic.AtomicLong;

import com.enn3developer.gtnhvoice.GtnhVoice;
import com.enn3developer.gtnhvoice.api.client.ICapturePcmFilter;
import com.enn3developer.gtnhvoice.api.client.VoiceFormat;
import com.enn3developer.gtnhvoice.core.api.util.LogThrottle;

/**
 * Wraps one addon's public {@link ICapturePcmFilter} as an internal {@link CapturePcmFilter} - the adapter
 * {@link VoiceClientManager#attachAddonCaptureFilter} registers on the durable {@link CapturePcmFilterChain},
 * and the opaque handle it returns. Lives beside the internals it wraps so the internal filter type stays
 * package-private; the capture twin of {@code AddonFilterAdapter} in the playback package.
 * <p>
 * Delivers the public contract's failure isolation with per-addon attribution: a delegate that throws, returns
 * {@code null}, or returns a wrong-length array is skipped for that frame - the input frame passes through
 * unchanged - with an error log attributed to the addon name and throttled per adapter (one slot per
 * {@value #ERROR_LOG_INTERVAL_MILLIS}ms each), so broken addons never share or starve a log slot. Because
 * nothing escapes and the output is always a valid frame, {@link CapturePcmFilterChain}'s own isolation stays
 * a second net that never fires for wrapped addons.
 */
final class AddonCaptureFilterAdapter implements CapturePcmFilter {

    private static final long ERROR_LOG_INTERVAL_MILLIS = 1_000L;

    private final String addonName;
    private final ICapturePcmFilter delegate;
    private final AtomicLong lastErrorLogMillis = new AtomicLong();

    AddonCaptureFilterAdapter(String addonName, ICapturePcmFilter delegate) {
        this.addonName = addonName;
        this.delegate = delegate;
    }

    @Override
    public short[] process(short[] frame) {
        try {
            short[] processed = delegate.process(frame);
            if (processed != null && processed.length == VoiceFormat.FRAME_SAMPLES) return processed;
            logFailure("returned " + describeBadOutput(processed), null);
        } catch (Throwable t) {
            logFailure("threw", t);
        }
        return frame;
    }

    private static String describeBadOutput(short[] processed) {
        return processed == null ? "null" : processed.length + " samples instead of " + VoiceFormat.FRAME_SAMPLES;
    }

    private void logFailure(String what, Throwable cause) {
        if (!LogThrottle.shouldLog(lastErrorLogMillis, ERROR_LOG_INTERVAL_MILLIS)) return;
        GtnhVoice.LOG
            .error("[Voice] Addon '{}' capture filter {}; frame passed through unchanged", addonName, what, cause);
    }

    @Override
    public String toString() {
        return "AddonCaptureFilterAdapter[" + addonName + "]";
    }
}
