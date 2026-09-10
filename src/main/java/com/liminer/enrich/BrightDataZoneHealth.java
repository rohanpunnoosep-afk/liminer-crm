package com.liminer.enrich;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/*
 * Process-wide latch recording whether Bright Data has reported a zone-level fault
 * during the current workflow run.
 *
 * Why a latch and not just an exception: the enrichment code has ~110 catch(Exception)
 * sites that deliberately swallow per-query failures so one bad query cannot abort a
 * row. That is right for "no results" but wrong for a dead zone -- it let the
 * serp_api1 outage report "Completed: 4, Failed: 0" while every single SERP call
 * failed. A latch survives being swallowed, so run-level reporting can tell the
 * difference no matter which catch block ate the exception.
 *
 * Secondary benefit: once a zone is known dead, post() fails fast instead of making
 * hundreds more calls that cannot possibly succeed.
 *
 * Lifecycle: reset() at the start of each workflow run, check() when reporting the
 * result. Faults are per-run, not permanent, so fixing the zone config and re-running
 * clears the condition.
 */
public final class BrightDataZoneHealth
{
    private static final AtomicReference<BrightDataZoneException> FAULT0 =
        new AtomicReference<>(null);

    /*
     * Error code marking the second failure mode this class guards: the zone answers
     * normally but the parsed results are unusable. Distinct from a zone-level error
     * code, which always comes from Bright Data's own x-brd-err-code header.
     */
    static final String ERR_UNUSABLE_URLS0 = "parser_returned_no_usable_urls";

    /*
     * How many consecutive queries must come back with results-but-none-usable before
     * we call it a fault. One such query is ordinary -- a search can legitimately
     * return nothing but Google's own properties. Three in a row is a broken parser.
     */
    private static final int UNUSABLE_STREAK_LIMIT0 = 3;

    private static final AtomicInteger UNUSABLE_STREAK0 = new AtomicInteger(0);
    private static final AtomicReference<String> UNUSABLE_SAMPLE0 =
        new AtomicReference<>(null);

    private BrightDataZoneHealth()
    {
    }

    /* Clear any recorded fault. Call at the start of every workflow run. */
    public static void reset()
    {
        FAULT0.set(null);
        UNUSABLE_STREAK0.set(0);
        UNUSABLE_SAMPLE0.set(null);
    }

    /*
     * Report that a query returned organic results but every one was discarded as
     * unusable (a relative path, a google.com redirect stub, no host). Latches a
     * fault once this has happened UNUSABLE_STREAK_LIMIT0 times in a row.
     *
     * This is the gap the zone latch alone could not see: a zone can be perfectly
     * healthy -- answering, billing, no error header -- and still return nothing the
     * pipeline can follow, which reads downstream as "found nothing" and reports a
     * clean no-op while every row silently fails.
     */
    static void noteResultsUnusable(String zoneLabel0, String sampleUrl0)
    {
        UNUSABLE_SAMPLE0.set(sampleUrl0);

        if (UNUSABLE_STREAK0.incrementAndGet() < UNUSABLE_STREAK_LIMIT0)
        {
            return;
        }

        recordFault(new BrightDataZoneException(zoneLabel0, ERR_UNUSABLE_URLS0));
    }

    /* Report that a query yielded at least one usable URL, clearing the streak. */
    static void noteResultsUsable()
    {
        UNUSABLE_STREAK0.set(0);
    }

    /* Record the first zone fault seen in this run; later ones do not overwrite it. */
    static void recordFault(BrightDataZoneException exception0)
    {
        FAULT0.compareAndSet(null, exception0);
    }

    /* The first zone fault seen in this run, or null if the zone is healthy. */
    public static BrightDataZoneException fault()
    {
        return FAULT0.get();
    }

    public static boolean isHealthy()
    {
        return FAULT0.get() == null;
    }

    /*
     * Human-readable explanation for run-level reporting, or null when healthy.
     * Names the env var so the operator can act on it without reading the source.
     */
    public static String faultSummary()
    {
        BrightDataZoneException fault0 = FAULT0.get();
        if (fault0 == null)
        {
            return null;
        }

        if (ERR_UNUSABLE_URLS0.equals(fault0.getErrorCode()))
        {
            String sample0 = UNUSABLE_SAMPLE0.get();
            return "Bright Data zone '" + fault0.getZone() + "' is answering, but its"
                + " parsed search results carry no usable destination URLs -- "
                + UNUSABLE_STREAK_LIMIT0 + " consecutive queries returned results whose"
                + " links were all unusable"
                + (sample0 == null ? "" : " (e.g. " + sample0 + ")")
                + ". This is a Bright Data parsing problem, not a search miss, so no"
                + " enrichment was performed. Check the SERP zone's parsing settings and"
                + " whether Bright Data is resolving Google's /goto redirect wrapper.";
        }

        return "Bright Data zone '" + fault0.getZone() + "' rejected every request ("
            + fault0.getErrorCode() + "). No enrichment was performed. Check that the zone"
            + " exists in the Bright Data console and that BRIGHT_DATA_SERP_ZONE /"
            + " BRIGHT_DATA_UNLOCKER_ZONE point at a live zone.";
    }
}
