package com.liminer.indicators;

import com.liminer.brief.DocumentSectionExtractor;
import com.liminer.core.LpContext;
import com.liminer.enrich.EdgarClient;
import com.liminer.enrich.ScrapeCache;

import java.time.LocalDate;

/*
 * ADVStrategyIndicator (Fit 2C) — extracts the LP's stated investment strategy from
 * the SEC ADV Part 2 brochure when the LP is a registered investment adviser AND
 * the website-thesis (ThesisFitIndicator, 2B) returned thin coverage.
 *
 * Why: For advisers with no public website, or with a sparse public profile, the
 * regulator-filed Part 2 brochure is often the only structured strategy disclosure.
 * Item 8 ("Methods of Analysis, Investment Strategies, and Risk of Loss") is the
 * key section — but in the Rock Creek brochure it runs pp.14-58. DocumentSectionExtractor
 * isolates only that span before passing it to the LLM.
 *
 * Gate: only runs when CRD or CIK is resolved AND ThesisFitIndicator confidence <
 * THESIS_FIT_GATE_THRESHOLD. This keeps it as a targeted fallback, not a default.
 *
 * Raw brochure text NEVER leaves local variables — only the LLM summary lands in
 * the IndicatorResult, capped at 50k chars.
 *
 * Thread-safety: stateless. LLM call goes through the thread-safe ScrapeCache.
 */
public class ADVStrategyIndicator implements Indicator
{
    // Only run this expensive leaf when the website-thesis is thin.
    private static final double THESIS_FIT_GATE_THRESHOLD = 0.45;
    private static final double FILING_CONFIDENCE = 0.80;
    private static final int MAX_SUMMARY_CHARS = 49_000;

    private final EdgarClient edgarClient0 = new EdgarClient();

    @Override
    public String axis() { return AXIS_FIT; }

    @Override
    public String name() { return "ADVStrategy"; }

    @Override
    public IndicatorResult fetch(LpContext ctx, ScrapeCache cache) throws Exception
    {
        if (ctx == null) return IndicatorResult.empty(AXIS_FIT);

        // Gate 1: identity must be resolved.
        boolean hasIdentity = ctx.identityKeys != null
            && (!isBlank(ctx.identityKeys.crd) || !isBlank(ctx.identityKeys.cik));
        if (!hasIdentity) return IndicatorResult.empty(AXIS_FIT);

        // Gate 2: only run when website-thesis confidence is thin.
        // LPScoreProcessor passes websiteThesisConfidence via LpContext (if available).
        // We check the sectorTags density as a proxy when the score is not pre-computed.
        if (websiteThesisCoverageStrong(ctx)) return IndicatorResult.empty(AXIS_FIT);

        // Fetch the ADV Part 2 brochure text (via EdgarClient — stub until live).
        String crdOrCik = !isBlank(ctx.identityKeys.crd)
            ? ctx.identityKeys.crd.trim() : ctx.identityKeys.cik.trim();
        String brochureText = fetchBrochureText(crdOrCik, ctx.identityKeys.crd);
        if (isBlank(brochureText)) return IndicatorResult.empty(AXIS_FIT);

        // Extract only Item 8, not the whole brochure.
        String item8Text = DocumentSectionExtractor.extractSection(brochureText, "Item 8");
        if (isBlank(item8Text))
        {
            // If section extraction fails, fall back to a brief head-of-document summary.
            item8Text = brochureText.length() > 8000
                ? brochureText.substring(0, 8000) : brochureText;
        }

        // Summarize via LLM (through cache for deduplication).
        String summary = summarizeItem8(item8Text, ctx.fundName, cache);
        if (isBlank(summary)) return IndicatorResult.empty(AXIS_FIT);

        // asOfDate from the brochure filing date (sourced from EdgarClient submissions).
        String filingDate = fetchBrochureFilingDate(crdOrCik, ctx.identityKeys.crd);
        String asOf = isBlank(filingDate) ? LocalDate.now().toString() : filingDate;

        String sourceUrl = buildAdviserUrl(ctx.identityKeys.crd, crdOrCik);
        String truncSummary = summary.length() > MAX_SUMMARY_CHARS
            ? summary.substring(0, MAX_SUMMARY_CHARS) : summary;

        return new IndicatorResult(truncSummary, FILING_CONFIDENCE, sourceUrl, asOf,
            AXIS_FIT,
            "ADV Part 2 Item 8 strategy summary (section-extracted, " + item8Text.length()
            + " chars extracted from brochure). CRD/CIK=" + crdOrCik + ".");
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private boolean websiteThesisCoverageStrong(LpContext ctx)
    {
        // Proxy: if LP has 3+ sector tags, website-thesis probably ran well.
        if (isBlank(ctx.sectorTags) && isBlank(ctx.microsectorTags)) return false;
        int tagCount = countTags(ctx.sectorTags) + countTags(ctx.microsectorTags);
        return tagCount >= 3;
    }

    private static int countTags(String tags)
    {
        if (isBlank(tags)) return 0;
        return tags.split("[,;]").length;
    }

    private String fetchBrochureText(String crdOrCik, String crd)
    {
        // TODO: when EdgarClient goes live, fetch Part 2 brochure text via CIK/CRD.
        // For now (stub), return empty — indicator returns empty correctly.
        // Expected call: edgarClient0.fetchSubmissions(crdOrCik) -> find Part 2A filing URL
        //                -> WebsiteCrawlerService.scrapeUrl(brochureUrl) -> extractVisibleText
        return "";
    }

    private String fetchBrochureFilingDate(String crdOrCik, String crd)
    {
        // TODO: read the filing date from the most recent Part 2A submission entry.
        return "";
    }

    private String summarizeItem8(String item8Text, String lpName, ScrapeCache cache)
    {
        if (isBlank(item8Text)) return "";
        try
        {
            // Cap input to LLM prompt to avoid context overflow.
            String capped = item8Text.length() > 30_000
                ? item8Text.substring(0, 30_000) : item8Text;

            String prompt = "You are an LP intelligence analyst. The following text is Item 8 "
                + "(\"Methods of Analysis, Investment Strategies, and Risk of Loss\") from the "
                + "SEC ADV Part 2 brochure filed by \"" + lpName + "\".\n\n"
                + "Summarize ONLY from the provided text (no outside knowledge):\n"
                + "1. The primary asset classes and investment strategies.\n"
                + "2. Stage, sector, and geographic focus.\n"
                + "3. Any stated constraints or restrictions.\n\n"
                + "Keep the summary under 500 words. Do not include raw legal disclaimers.\n\n"
                + "Source text:\n" + capped;

            return cache.llm(prompt);
        }
        catch (Exception e0)
        {
            return "";
        }
    }

    private static String buildAdviserUrl(String crd, String fallback)
    {
        if (!isBlank(crd))
        {
            return "https://adviserinfo.sec.gov/firm/summary/" + crd.trim();
        }
        return "https://www.sec.gov/cgi-bin/browse-edgar?company=" + fallback;
    }

    private static boolean isBlank(String s) { return s == null || s.trim().isEmpty(); }
}
