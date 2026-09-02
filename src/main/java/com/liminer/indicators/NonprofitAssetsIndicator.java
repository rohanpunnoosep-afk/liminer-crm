package com.liminer.indicators;

import com.liminer.core.LpContext;
import com.liminer.enrich.ProPublicaNonprofitClient;
import com.liminer.enrich.ScrapeCache;

/*
 * NonprofitAssetsIndicator (Resources 1B) — exact balance-sheet assets for the
 * endowment / foundation / nonprofit class that IAPD misses, from the free
 * ProPublica Nonprofit Explorer API (IRS 990 / 990-PF) keyed by EIN.
 *
 * It is a KEYED lookup only: the EIN comes pre-resolved from the row
 * (IdentityResolver, Task 4). If the EIN is blank this leaf returns empty — it
 * never searches by name.
 *
 * Point-in-time honesty: 990s lag 1-2 years, so asOfDate is the 990 TAX YEAR
 * (filing-date fallback), never today — the lag must be visible to the GP. A
 * figure with no usable tax-year/filing date is returned as empty.
 *
 * Thread-safety: stateless; ProPublicaNonprofitClient uses a static thread-safe
 * HttpClient.
 */
public class NonprofitAssetsIndicator implements Indicator
{
    // IRS-filed exact balance sheet — high confidence, on par with RAUM (1A) and
    // above the SERP fallback (1C) / headcount proxy (1D).
    private static final double FILING_CONFIDENCE = 0.90;

    private final ProPublicaNonprofitClient nonprofitClient0 = new ProPublicaNonprofitClient();

    @Override
    public String axis() { return AXIS_RESOURCES; }

    @Override
    public String name() { return "NonprofitAssets"; }

    @Override
    public IndicatorResult fetch(LpContext ctx, ScrapeCache cache) throws Exception
    {
        if (ctx == null || ctx.identityKeys == null || isBlank(ctx.identityKeys.ein))
        {
            return IndicatorResult.empty(AXIS_RESOURCES);
        }

        String ein0 = ctx.identityKeys.ein.trim();
        ProPublicaNonprofitClient.Form990Result form990 = nonprofitClient0.fetch990(ein0);
        if (form990 == null || (isBlank(form990.totalAssets) && isBlank(form990.investments)))
        {
            return IndicatorResult.empty(AXIS_RESOURCES);
        }

        // Tax year is the honest asOfDate; filing date is the fallback. No usable
        // date means no result — never present a lagged 990 as current.
        String asOf0 = !isBlank(form990.taxYear) ? form990.taxYear.trim()
            : (!isBlank(form990.filingDate) ? form990.filingDate.trim() : "");
        if (isBlank(asOf0))
        {
            return IndicatorResult.empty(AXIS_RESOURCES);
        }

        StringBuilder value0 = new StringBuilder();
        if (!isBlank(form990.totalAssets))
        {
            value0.append("Total assets ").append(form990.totalAssets.trim());
        }
        if (!isBlank(form990.investments))
        {
            if (value0.length() > 0) value0.append("; ");
            value0.append("investments ").append(form990.investments.trim());
        }
        if (!isBlank(form990.cash))
        {
            value0.append("; cash ").append(form990.cash.trim());
        }
        if (!isBlank(form990.investmentIncome))
        {
            value0.append("; investment income ").append(form990.investmentIncome.trim());
        }
        value0.append(" (990 tax year ").append(asOf0).append(")");

        String sourceUrl0 = !isBlank(form990.url) ? form990.url.trim()
            : "https://projects.propublica.org/nonprofits/organizations/" + ein0;

        return new IndicatorResult(value0.toString(), FILING_CONFIDENCE, sourceUrl0,
            asOf0, AXIS_RESOURCES,
            "IRS 990/990-PF balance sheet via ProPublica, keyed by pre-resolved EIN " + ein0 + ".");
    }

    private static boolean isBlank(String s0) { return s0 == null || s0.trim().isEmpty(); }
}
