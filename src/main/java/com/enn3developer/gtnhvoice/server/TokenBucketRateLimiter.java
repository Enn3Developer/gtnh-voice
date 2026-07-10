package com.enn3developer.gtnhvoice.server;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

/**
 * Per-key token-bucket rate limiter. Each event costs one token; a key starts with a full burst and
 * refills at a slow steady rate, so a legitimate burst passes while a flood is dropped cheaply -
 * before any expensive work (ECDH/HKDF and enqueue on the handshake path, or audio fan-out on the
 * UDP path). Two instances gate the voice server: one on the reliable-channel {@code ClientHello}
 * handshake (finding #9 - a flood froze the server ~34s running per-hello ECDH on the IO thread), one
 * on the serverbound UDP audio relay (a flood was amplified out to every in-range player).
 * <p>
 * A single player's events all arrive on that connection's one IO/UDP thread, so per-bucket contention
 * is nil in practice; the {@code synchronized} block only guards the rare cross-thread case and stays a
 * cheap uncontended lock on the hot path. MC-free by design so it is unit-testable with an injected
 * clock.
 */
final class TokenBucketRateLimiter {

    private final int capacity;
    private final double tokensPerMilli;
    private final LongSupplier clock;
    private final ConcurrentHashMap<UUID, Bucket> buckets = new ConcurrentHashMap<>();

    TokenBucketRateLimiter(int capacity, long refillIntervalMillis) {
        this(capacity, refillIntervalMillis, System::currentTimeMillis);
    }

    TokenBucketRateLimiter(int capacity, long refillIntervalMillis, LongSupplier clock) {
        this.capacity = capacity;
        this.tokensPerMilli = 1.0 / refillIntervalMillis;
        this.clock = clock;
    }

    /**
     * Charges one token for {@code key}. Returns {@code true} if a token was available (allow the
     * event), {@code false} once the key has drained its burst and outrun the refill (drop it).
     */
    boolean tryAcquire(UUID key) {
        long now = clock.getAsLong();
        Bucket bucket = buckets.computeIfAbsent(key, k -> new Bucket(capacity, now));
        return bucket.tryAcquire(now, capacity, tokensPerMilli);
    }

    /** Drops a key's bucket on logout so the map does not grow with churned-through UUIDs. */
    void forget(UUID key) {
        buckets.remove(key);
    }

    private static final class Bucket {

        private double tokens;
        private long lastRefillMillis;

        Bucket(double tokens, long now) {
            this.tokens = tokens;
            this.lastRefillMillis = now;
        }

        synchronized boolean tryAcquire(long now, int capacity, double tokensPerMilli) {
            long elapsed = now - lastRefillMillis;
            if (elapsed > 0) {
                tokens = Math.min(capacity, tokens + elapsed * tokensPerMilli);
                lastRefillMillis = now;
            }
            if (tokens < 1.0) return false;

            tokens -= 1.0;
            return true;
        }
    }
}
