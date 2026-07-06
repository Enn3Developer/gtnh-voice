package com.enn3developer.gtnhvoice.client.api;

/**
 * The one runtime enable/disable flag per registration bundle behind {@code IRegistration.setFilterEnabled} -
 * a single volatile boolean, flipped from any thread (typically the client game thread) and read on the
 * decode/capture thread every frame. Shared by reference across every one of the bundle's gated filters
 * ({@link GatedCaptureFilter}/{@link GatedPlaybackFilter}) and its {@link Registration} handle, so one flip
 * gates the whole bundle at once. Volatile is the entire synchronization story: the read is a plain field
 * access on the hot path, the write is rare, and a flip needs to be visible from the next frame, not atomic
 * with anything.
 */
final class FilterGate {

    private volatile boolean enabled;

    FilterGate(boolean enabled) {
        this.enabled = enabled;
    }

    boolean isEnabled() {
        return enabled;
    }

    void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }
}
