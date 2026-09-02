package com.liminer.indicators;

import com.liminer.core.LpContext;
import com.liminer.enrich.ScrapeCache;

/*
 * Indicator is the pluggable abstraction every market-intelligence "leaf"
 * implements. Adding a new signal is then a registration (one register() line in
 * IndicatorRegistry), not a rewrite — the LPScoreProcessor rollup just iterates
 * the list per axis.
 *
 * Contract:
 *   - fetch() returns the SAME shape every time: an IndicatorResult
 *     {value, confidence, sourceUrl, asOfDate, theme, evidence}.
 *   - A leaf that finds nothing returns IndicatorResult.empty(axis()), never null.
 *   - fetch() may throw (it can call ScrapeCache.crawl/llm which throw); the rollup
 *     wraps each call in try/catch and substitutes an empty result, so a throwing
 *     leaf never crashes the row.
 *   - All external I/O goes through the thread-safe ScrapeCache so the same page /
 *     SERP / LLM call is made at most once across rows.
 */
public interface Indicator
{
    // The three score axes a leaf can feed.
    String AXIS_RESOURCES = "RESOURCES";
    String AXIS_FIT = "FIT";
    String AXIS_PROBABILITY_NOW = "PROBABILITY_NOW";

    IndicatorResult fetch(LpContext ctx, ScrapeCache cache) throws Exception;

    // One of AXIS_RESOURCES / AXIS_FIT / AXIS_PROBABILITY_NOW.
    String axis();

    // Stable short name for logging / the Intelligence JSON blob.
    String name();
}
