package com.liminer.indicators;

import com.liminer.core.LpContext;
import com.liminer.enrich.IapdClient;
import com.liminer.enrich.ScrapeCache;

/*
 * RaumIndicator (Resources 1A) — exact regulator-filed RAUM + discretionary split
 * from Form ADV Part 1 Item 5.F via IAPD, the highest value-per-dollar resource
 * signal when the LP is a US registered adviser.
 *
 * It is a KEYED lookup only: the CRD comes pre-resolved from the row
 * (IdentityResolver, Task 4). If the CRD is blank this leaf returns empty — it
 * never searches by name (all entity resolution funnels through IdentityResolver).
 *
 * Point-in-time honesty: asOfDate is the ADV FILING date (falling back to the
 * fiscal-year-end), never today — regulator filings can be 12-15 months stale and
 * that staleness must be visible to the GP. A RAUM figure with no usable filing
 * date is returned as empty, not silently stamped "current".
 *
 * Thread-safety: stateless; IapdClient uses a static thread-safe HttpClient.
 */
public class RaumIndicator implements Indicator
{
    // Regulator-filed exact figure — high confidence, above the SERP fallback (1C)
    // and far above the headcount proxy (1D).
    private static final double FILING_CONFIDENCE = 0.90;

    private final IapdClient iapdClient0 = new IapdClient();

    @Override
    public String axis() { return AXIS_RESOURCES; }

    @Override
    public String name() { return "Raum"; }

    @Override
    public IndicatorResult fetch(LpContext ctx, ScrapeCache cache) throws Exception
    {
        if (ctx == null || ctx.identityKeys == null || isBlank(ctx.identityKeys.crd))
        {
            return IndicatorResult.empty(AXIS_RESOURCES);
        }

        String crd0 = ctx.identityKeys.crd.trim();
        IapdClient.Part1Result part1 = iapdClient0.fetchPart1(crd0);
        if (part1 == null || isBlank(part1.raum))
        {
            return IndicatorResult.empty(AXIS_RESOURCES);
        }

        // The filing date is the honest asOfDate; fiscal year end is the fallback.
        // No usable date means no result — never present stale RAUM as current.
        String asOf0 = !isBlank(part1.filingDate) ? part1.filingDate.trim()
            : (!isBlank(part1.fiscalYearEnd) ? part1.fiscalYearEnd.trim() : "");
        if (isBlank(asOf0))
        {
            return IndicatorResult.empty(AXIS_RESOURCES);
        }

        StringBuilder value0 = new StringBuilder();
        value0.append("RAUM ").append(part1.raum.trim());
        if (!isBlank(part1.discretionaryRaum))
        {
            value0.append("; discretionary ").append(part1.discretionaryRaum.trim());
        }
        if (!isBlank(part1.numAccounts))
        {
            value0.append("; accounts ").append(part1.numAccounts.trim());
        }
        value0.append(" (ADV filed ").append(asOf0).append(")");

        return new IndicatorResult(value0.toString(), FILING_CONFIDENCE,
            "https://adviserinfo.sec.gov/firm/summary/" + crd0,
            asOf0, AXIS_RESOURCES,
            "Form ADV Part 1 Item 5.F via IAPD, keyed by pre-resolved CRD " + crd0 + ".");
    }

    private static boolean isBlank(String s0) { return s0 == null || s0.trim().isEmpty(); }
}
