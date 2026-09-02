package com.liminer.indicators;

import java.time.LocalDate;

/*
 * IndicatorResult is the single value object every market-intelligence
 * indicator "leaf" emits. It is the parent of BasicBackgroundChecker.ResolvedField
 * {value, confidence, sourceUrl, evidence} extended with the two fields the
 * LP market-intelligence component requires for point-in-time honesty:
 *
 *   asOfDate  ISO-8601 date the value was true as of (e.g. an ADV filing date,
 *             a 990 tax year, a news event date). "A 14-month-old AUM is not
 *             current" — a value with no asOfDate is a bug, not a result.
 *   theme     which axis / source theme this result feeds
 *             (RESOURCES / FIT / PROBABILITY_NOW, or a source Theme number).
 *
 * A leaf that finds nothing returns IndicatorResult.empty(theme) — confidence 0,
 * blank value/url, today's date — never null and never a thrown exception.
 *
 * Immutable by convention: instances are not mutated after construction, so they
 * are safe to read across the parallel row threads in LPScoreProcessor.
 */
public class IndicatorResult
{
    // Order is load-bearing for downstream leaves and the rollup; append new
    // fields only — never remove value/confidence/sourceUrl/asOfDate/theme.
    public String value = "";
    public double confidence = 0.0;
    public String sourceUrl = "";
    public String asOfDate = "";
    public String theme = "";
    public String evidence = "";

    public IndicatorResult() {}

    public IndicatorResult(String value0, double confidence0, String sourceUrl0,
                           String asOfDate0, String theme0, String evidence0)
    {
        this.value = safe(value0);
        this.confidence = confidence0;
        this.sourceUrl = safe(sourceUrl0);
        this.asOfDate = safe(asOfDate0);
        this.theme = safe(theme0);
        this.evidence = safe(evidence0);
    }

    /*
     * Empty result for a leaf that found nothing: confidence 0, blank
     * value/url/evidence, theme preserved, asOfDate stamped to today so the
     * result is never date-less.
     */
    public static IndicatorResult empty(String theme0)
    {
        IndicatorResult r = new IndicatorResult();
        r.confidence = 0.0;
        r.value = "";
        r.sourceUrl = "";
        r.evidence = "";
        r.theme = safe(theme0);
        r.asOfDate = LocalDate.now().toString();
        return r;
    }

    /*
     * A result is "present" only when it carries real signal: positive
     * confidence AND a non-blank value. Empty results read isPresent() == false.
     */
    public boolean isPresent()
    {
        return this.confidence > 0.0 && this.value != null && !this.value.trim().isEmpty();
    }

    private static String safe(String s)
    {
        return s == null ? "" : s;
    }
}
