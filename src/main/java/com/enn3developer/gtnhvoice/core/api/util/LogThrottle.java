package com.enn3developer.gtnhvoice.core.api.util;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Shared gate for rate-limited logging from hot paths (per-packet/per-frame code). Thread-safe and lock-free:
 * concurrent callers race on a single CAS and at most one of them wins the log slot per interval.
 */
public final class LogThrottle {

    private LogThrottle() {}

    /**
     * Returns {@code true} at most once per {@code intervalMillis}: whether this caller both found the interval
     * elapsed since {@code lastLogMillis} and won the CAS to claim the new timestamp.
     */
    public static boolean shouldLog(AtomicLong lastLogMillis, long intervalMillis) {
        long now = System.currentTimeMillis();
        long last = lastLogMillis.get();
        return now - last >= intervalMillis && lastLogMillis.compareAndSet(last, now);
    }
}
