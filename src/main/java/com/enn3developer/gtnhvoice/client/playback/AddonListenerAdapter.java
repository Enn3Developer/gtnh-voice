package com.enn3developer.gtnhvoice.client.playback;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import com.enn3developer.gtnhvoice.GtnhVoice;
import com.enn3developer.gtnhvoice.api.client.IAudioLifecycleListener;
import com.enn3developer.gtnhvoice.core.api.util.LogThrottle;

/**
 * Wraps one addon's public {@link IAudioLifecycleListener} as an internal {@link PlaybackLifecycleListener} -
 * the adapter {@link PlaybackManager#attachAddonListener} registers, and the opaque handle it returns. Lives
 * beside the internals it wraps so the internal listener type stays package-private.
 * <p>
 * Every callback is dispatched catch-Throwable with an error log attributed to the addon name and throttled
 * per adapter (one slot per {@value #ERROR_LOG_INTERVAL_MILLIS}ms each): a broken addon can neither starve
 * internal diagnostics nor eat other addons' log slots, and two broken addons each keep their own attribution.
 * {@link LifecycleEventDispatcher}'s generic isolation stays in place as a second net that never fires for
 * wrapped addons - nothing escapes this adapter.
 * <p>
 * Deliberately NOT {@code @Lwjgl3Aware}: this class never touches {@code org.lwjgl} - per the public contract
 * the wrapped listener's own class carries the annotation when it does.
 */
final class AddonListenerAdapter implements PlaybackLifecycleListener {

    private static final long ERROR_LOG_INTERVAL_MILLIS = 1_000L;

    private final String addonName;
    private final IAudioLifecycleListener delegate;
    private final AtomicLong lastErrorLogMillis = new AtomicLong();

    AddonListenerAdapter(String addonName, IAudioLifecycleListener delegate) {
        this.addonName = addonName;
        this.delegate = delegate;
    }

    @Override
    public void contextCreated(long deviceHandle) {
        try {
            delegate.contextCreated(deviceHandle);
        } catch (Throwable t) {
            logFailure("contextCreated", t);
        }
    }

    @Override
    public void contextDestroying() {
        try {
            delegate.contextDestroying();
        } catch (Throwable t) {
            logFailure("contextDestroying", t);
        }
    }

    @Override
    public void sourceCreated(UUID sourceId, int sourceHandle) {
        try {
            delegate.sourceCreated(sourceId, sourceHandle);
        } catch (Throwable t) {
            logFailure("sourceCreated", t);
        }
    }

    @Override
    public void sourceDestroying(UUID sourceId, int sourceHandle) {
        try {
            delegate.sourceDestroying(sourceId, sourceHandle);
        } catch (Throwable t) {
            logFailure("sourceDestroying", t);
        }
    }

    @Override
    public void audioTick() {
        try {
            delegate.audioTick();
        } catch (Throwable t) {
            logFailure("audioTick", t);
        }
    }

    private void logFailure(String event, Throwable t) {
        if (!LogThrottle.shouldLog(lastErrorLogMillis, ERROR_LOG_INTERVAL_MILLIS)) return;
        GtnhVoice.LOG.error("[Playback] Addon '{}' listener threw from {}; skipped", addonName, event, t);
    }

    @Override
    public String toString() {
        return "AddonListenerAdapter[" + addonName + "]";
    }
}
