package com.liminer.enrich;

import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Process-wide coordinator for ALL Bright Data HTTP calls (website crawl, SERP,
 * LinkedIn). Two mechanisms address the throttling/hang issue at its source:
 *
 *  1) A single global Semaphore caps the TOTAL number of concurrent Bright Data
 *     calls across every client. Only LinkedIn scrapes were bounded before (via
 *     SCRAPE_POOL); SERP + website crawls were unbounded, so total in-flight
 *     calls could spike and trigger 429/503 throttling. One shared permit count
 *     keeps us under Bright Data's concurrency ceiling regardless of caller.
 *
 *  2) An adaptive global cooldown: when any client sees a 429/503/timeout it
 *     calls noteThrottle(), which pushes a short "everybody back off" window.
 *     Threads check this window in acquire() and sleep through it together,
 *     instead of each request independently hammering the API.
 *
 * Both acquire() (Semaphore.acquire) and the cooldown Thread.sleep are
 * INTERRUPTIBLE, so the per-row deadline cancellation (Future.cancel(true) in
 * BasicBackgroundChecker) still frees threads parked here.
 *
 * Leaf scrape tasks never submit back into a pool while holding a permit, so
 * there is no deadlock risk -- at worst, heavy throttling serializes calls.
 */
public final class BrightDataThrottle
{
    // Total concurrent Bright Data calls across all clients. Bright Data allows
    // 20+; 16 leaves headroom. Tunable via env without a recompile.
    private static final int GLOBAL_MAX_INFLIGHT =
        getEnvInt("BD_MAX_INFLIGHT", 16);

    private static final Semaphore PERMITS =
        new Semaphore(Math.max(1, GLOBAL_MAX_INFLIGHT), true);

    // Adaptive cooldown bounds. On a throttle signal, all threads pause until
    // cooldownUntilMs; repeated signals extend (but cap) the window.
    private static final long COOLDOWN_STEP_MS =
        getEnvLong("BD_COOLDOWN_STEP_MS", 1500L);
    private static final long COOLDOWN_MAX_MS =
        getEnvLong("BD_COOLDOWN_MAX_MS", 15000L);

    private static final AtomicLong cooldownUntilMs = new AtomicLong(0L);

    private BrightDataThrottle() { }

    /**
     * Acquire a global slot before a Bright Data send(). Waits out any active
     * cooldown window first, then takes a permit. Must be paired with release()
     * in a finally block. Throws InterruptedException so cancellation propagates.
     */
    public static void acquire() throws InterruptedException
    {
        long waitMs0 = cooldownUntilMs.get() - System.currentTimeMillis();
        if (waitMs0 > 0)
        {
            Thread.sleep(Math.min(waitMs0, COOLDOWN_MAX_MS)); // interruptible
        }
        PERMITS.acquire(); // interruptible
    }

    /** Release the global slot. Always call from a finally block. */
    public static void release()
    {
        PERMITS.release();
    }

    /**
     * Signal that Bright Data is throttling (HTTP 429/502/503 or a timeout).
     * Extends the shared cooldown window so every thread backs off together,
     * capped at COOLDOWN_MAX_MS from now.
     */
    public static void noteThrottle()
    {
        long now0 = System.currentTimeMillis();
        long ceiling0 = now0 + COOLDOWN_MAX_MS;
        cooldownUntilMs.accumulateAndGet(now0 + COOLDOWN_STEP_MS,
            (existing0, proposed0) -> Math.min(ceiling0, Math.max(existing0, proposed0)));
    }

    private static int getEnvInt(String name0, int default0)
    {
        return (int) getEnvLong(name0, default0);
    }

    private static long getEnvLong(String name0, long default0)
    {
        String raw0 = System.getenv(name0);
        if (raw0 == null || raw0.trim().isEmpty()) { return default0; }
        try { return Long.parseLong(raw0.trim()); }
        catch (NumberFormatException e0) { return default0; }
    }
}
