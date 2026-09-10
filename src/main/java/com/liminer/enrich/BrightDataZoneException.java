package com.liminer.enrich;

/*
 * Raised when Bright Data reports a zone-level fault via x-brd-err-code (a dead,
 * renamed or misconfigured zone, a bad token, an exhausted account).
 *
 * This is deliberately distinct from an ordinary per-query failure. A query that
 * finds nothing is normal; a zone fault means the whole SERP/Unlocker subsystem is
 * down, so every remaining query in the run will fail the same way. Call sites that
 * swallow ordinary SERP errors must rethrow this one, otherwise a dead zone is
 * indistinguishable from "no results" and a run reports Failed: 0 while having
 * enriched nothing -- which is exactly how the serp_api1 outage stayed invisible.
 */
public class BrightDataZoneException extends RuntimeException
{
    private final String zone;
    private final String errorCode;

    public BrightDataZoneException(String zone0, String errorCode0)
    {
        super("Bright Data zone error [" + zone0 + "]: " + errorCode0);
        zone = zone0;
        errorCode = errorCode0;
    }

    public String getZone()
    {
        return zone;
    }

    public String getErrorCode()
    {
        return errorCode;
    }
}
