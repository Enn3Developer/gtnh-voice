package com.enn3developer.gtnhvoice.server;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

/**
 * Per-player token-bucket rate limiter for the reliable-channel voice handshake. Each {@code
 * ClientHello} costs one token; a player starts with a full burst and refills at a slow steady rate,
 * so a legitimate handshake-retry burst passes while a flood is dropped cheaply - before any
 * ECDH/HKDF, {@code pendingSends} enqueue, or log on the Netty IO thread (see
 * {@link VoiceServerManager#handleClientHello}). Finding #9's flood froze the server ~34s because
 * every hello ran ECDH on the IO thread; this gate stops the flood at the door.
 * <p>
 * A single player's hellos all arrive on that connection's one IO thread, so per-bucket contention is
 * nil in practice; the {@code synchronized} block only guards the rare cross-thread case and stays a
 * cheap uncontended lock on the hot path. MC-free by design so it is unit-testable with an injected
 * clock.
 */
final class HelloRateLimiter {

    private final int capacity;
    private final double tokensPerMilli;
    private final LongSupplier clock;
    private final ConcurrentHashMap<UUID, Bucket> buckets = new ConcurrentHashMap<>();

    HelloRateLimiter(int capacity, long refillIntervalMillis) {
        this(capacity, refillIntervalMillis, System::currentTimeMillis);
    }

    HelloRateLimiter(int capacity, long refillIntervalMillis, LongSupplier clock) {
        this.capacity = capacity;
        this.tokensPerMilli = 1.0 / refillIntervalMillis;
        this.clock = clock;
    }

    /**
     * Charges one token for {@code player}. Returns {@code true} if a token was available (allow the
     * hello), {@code false} once the player has drained their burst and outrun the refill (drop it).
     */
    boolean tryAcquire(UUID player) {
        long now = clock.getAsLong();
        Bucket bucket = buckets.computeIfAbsent(player, key -> new Bucket(capacity, now));
        return bucket.tryAcquire(now, capacity, tokensPerMilli);
    }

    /** Drops a player's bucket on logout so the map does not grow with churned-through UUIDs. */
    void forget(UUID player) {
        buckets.remove(player);
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
