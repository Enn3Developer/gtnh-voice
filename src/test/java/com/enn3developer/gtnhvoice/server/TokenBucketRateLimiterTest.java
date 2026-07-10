package com.enn3developer.gtnhvoice.server;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.Test;

/**
 * Contract for the per-player ClientHello rate limiter (finding #9): a legitimate handshake burst plus
 * its slow retry cadence must pass, while an overload is dropped once the burst is drained and outruns the
 * refill. Uses an injected clock so the token maths is exercised without wall-clock timing.
 */
class TokenBucketRateLimiterTest {

    private static final int BURST = 5;
    private static final long REFILL_INTERVAL_MILLIS = 500; // one token every 500ms => 2/sec

    private final AtomicLong clock = new AtomicLong();

    private TokenBucketRateLimiter limiter() {
        return new TokenBucketRateLimiter(BURST, REFILL_INTERVAL_MILLIS, clock::get);
    }

    @Test
    void allowsTheFullInitialBurstThenDropsTheOverflow() {
        TokenBucketRateLimiter limiter = limiter();
        UUID player = UUID.randomUUID();

        for (int i = 0; i < BURST; i++) {
            assertTrue(limiter.tryAcquire(player), "hello " + i + " within the burst must pass");
        }
        assertFalse(limiter.tryAcquire(player), "the hello past the burst (no time elapsed) must be dropped");
    }

    @Test
    void refillsOneTokenPerInterval() {
        TokenBucketRateLimiter limiter = limiter();
        UUID player = UUID.randomUUID();

        for (int i = 0; i < BURST; i++) limiter.tryAcquire(player);
        assertFalse(limiter.tryAcquire(player), "burst drained");

        // Half an interval is not enough for a whole token.
        clock.addAndGet(REFILL_INTERVAL_MILLIS / 2);
        assertFalse(limiter.tryAcquire(player), "less than one interval must not refill a token");

        // Completing the interval yields exactly one token.
        clock.addAndGet(REFILL_INTERVAL_MILLIS / 2);
        assertTrue(limiter.tryAcquire(player), "one full interval refills one token");
        assertFalse(limiter.tryAcquire(player), "and only one");
    }

    @Test
    void doesNotRefillBeyondTheBurstCapacity() {
        TokenBucketRateLimiter limiter = limiter();
        UUID player = UUID.randomUUID();

        // Idle a long time - tokens must cap at BURST, not accumulate unbounded.
        clock.addAndGet(REFILL_INTERVAL_MILLIS * 1000);
        for (int i = 0; i < BURST; i++) {
            assertTrue(limiter.tryAcquire(player), "capped burst hello " + i + " passes");
        }
        assertFalse(limiter.tryAcquire(player), "tokens must not accumulate above the burst cap while idle");
    }

    @Test
    void aLegitimateOncePerSecondRetryNeverDrops() {
        TokenBucketRateLimiter limiter = limiter();
        UUID player = UUID.randomUUID();

        // The real client retries at most once/sec for 10 attempts; 2/sec refill keeps it always above water.
        for (int attempt = 0; attempt < 10; attempt++) {
            assertTrue(limiter.tryAcquire(player), "legitimate 1/sec retry #" + attempt + " must pass");
            clock.addAndGet(1000);
        }
    }

    @Test
    void tracksEachPlayerIndependently() {
        TokenBucketRateLimiter limiter = limiter();
        UUID sender = UUID.randomUUID();
        UUID bystander = UUID.randomUUID();

        for (int i = 0; i < BURST; i++) limiter.tryAcquire(sender);
        assertFalse(limiter.tryAcquire(sender), "sender is throttled");
        assertTrue(limiter.tryAcquire(bystander), "a different player is unaffected by the sender");
    }

    @Test
    void forgottenPlayerStartsWithAFreshBurst() {
        TokenBucketRateLimiter limiter = limiter();
        UUID player = UUID.randomUUID();

        for (int i = 0; i < BURST; i++) limiter.tryAcquire(player);
        assertFalse(limiter.tryAcquire(player), "burst drained before logout");

        limiter.forget(player); // logout
        assertTrue(limiter.tryAcquire(player), "a relogged player gets a fresh burst");
    }
}
