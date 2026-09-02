package com.liminer.indicators;

import com.liminer.core.LpContext;
import com.liminer.core.NewsItem;
import com.liminer.enrich.EdgarClient;
import com.liminer.enrich.NewsClient;
import com.liminer.enrich.ScrapeCache;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;

/*
 * FundCloseIndicator (Timing 3A) — detects and dates a fund close event via two
 * complementary sources in descending authority:
 *
 *   1. EDGAR Form D  (CIK pre-resolved by IdentityResolver)
 *      A Form D is filed within 15 days of the first sale, making it the most
 *      timely "fund is live and deploying" signal. Requires a resolved CIK.
 *
 *   2. News search   (NewsClient + LLM via ScrapeCache)
 *      SERP news mode for "<LP name> fund close OR final close".  The LLM extracts
 *      and dates the event; the query is anchored on the LP's domain to reduce
 *      wrong-entity hits. Applied when Form D is absent or when news is more recent.
 *
 * Recency gate: an event older than RECENCY_MONTHS_GATE months scores with reduced
 * confidence — staleness is visible to the GP.
 *
 * Thread-safety: stateless; EdgarClient and NewsClient both use static shared
 * HttpClients. All external calls go through the thread-safe ScrapeCache.
 */
public class FundCloseIndicator implements Indicator
{
    // "Just closed / actively deploying" is the strongest PROBABILITY_NOW signal.
    private static final double FORM_D_CONFIDENCE = 0.90;
    private static final double NEWS_CONFIRMED_CONFIDENCE = 0.72;
    // Events older than this receive a confidence penalty.
    private static final int RECENCY_MONTHS_GATE = 12;
    private static final double STALE_CONFIDENCE_PENALTY = 0.25;

    private final EdgarClient edgarClient0 = new EdgarClient();
    private final NewsClient newsClient0 = new NewsClient();

    @Override
    public String axis() { return AXIS_PROBABILITY_NOW; }

    @Override
    public String name() { return "FundClose"; }

    @Override
    public IndicatorResult fetch(LpContext ctx, ScrapeCache cache) throws Exception
    {
        if (ctx == null) return IndicatorResult.empty(AXIS_PROBABILITY_NOW);

        IndicatorResult formDResult = tryFormD(ctx);
        IndicatorResult newsResult  = tryNews(ctx, cache);

        // Prefer whichever has a valid date AND higher adjusted confidence.
        IndicatorResult best = chooseBest(formDResult, newsResult);
        return best != null ? best : IndicatorResult.empty(AXIS_PROBABILITY_NOW);
    }

    // -----------------------------------------------------------------------
    // Source 1: EDGAR Form D keyed by pre-resolved CIK
    // -----------------------------------------------------------------------

    private IndicatorResult tryFormD(LpContext ctx)
    {
        try
        {
            if (ctx.identityKeys == null || isBlank(ctx.identityKeys.cik))
            {
                return null;
            }

            String cik0 = ctx.identityKeys.cik.trim();
            EdgarClient.FormDResult formD = edgarClient0.latestFormD(cik0);
            if (formD == null || isBlank(formD.filingDate))
            {
                return null;
            }

            String asOf0 = NewsClient.normalizeDate(formD.filingDate);
            if (isBlank(asOf0)) asOf0 = formD.filingDate.trim();

            StringBuilder value0 = new StringBuilder();
            value0.append("Form D filed ").append(asOf0);
            if (!isBlank(formD.firstSaleDate))
            {
                value0.append("; first sale ").append(formD.firstSaleDate.trim());
            }
            if (!isBlank(formD.totalAmountSold))
            {
                value0.append("; amount sold ").append(formD.totalAmountSold.trim());
            }
            if (!isBlank(formD.totalOfferingAmount))
            {
                value0.append("; offering ").append(formD.totalOfferingAmount.trim());
            }

            double conf0 = applyRecencyPenalty(FORM_D_CONFIDENCE, asOf0);
            String sourceUrl0 = isBlank(formD.url)
                ? "https://www.sec.gov/cgi-bin/browse-edgar?action=getcompany&CIK=" + cik0 + "&type=D&dateb=&owner=include&count=10"
                : formD.url;

            return new IndicatorResult(value0.toString(), conf0, sourceUrl0, asOf0,
                AXIS_PROBABILITY_NOW,
                "EDGAR Form D (private offering notice filed within 15 days of first sale), CIK=" + cik0 + ".");
        }
        catch (Exception e0)
        {
            return null;
        }
    }

    // -----------------------------------------------------------------------
    // Source 2: News + LLM extraction
    // -----------------------------------------------------------------------

    private IndicatorResult tryNews(LpContext ctx, ScrapeCache cache)
    {
        try
        {
            if (isBlank(ctx.fundName)) return null;

            String query0 = buildNewsQuery(ctx);
            List<NewsItem> items0 = cache.searchNews(newsClient0, query0, 5);
            if (items0 == null || items0.isEmpty()) return null;

            // Anchor on LP domain to reduce wrong-entity hits.
            String domain0 = extractDomain(ctx.website);
            StringBuilder snippets0 = new StringBuilder();
            String bestUrl0 = "";
            for (NewsItem item0 : items0)
            {
                if (domainMatches(item0.url, domain0, ctx.fundName))
                {
                    snippets0.append("Title: ").append(item0.title).append("\n");
                    snippets0.append("URL: ").append(item0.url).append("\n");
                    snippets0.append("Date: ").append(item0.publishedDate).append("\n");
                    snippets0.append("Snippet: ").append(item0.snippet).append("\n\n");
                    if (isBlank(bestUrl0)) bestUrl0 = item0.url;
                }
            }
            // If no domain-anchored hits, use all items.
            if (snippets0.length() == 0)
            {
                for (NewsItem item0 : items0)
                {
                    snippets0.append("Title: ").append(item0.title).append("\n");
                    snippets0.append("URL: ").append(item0.url).append("\n");
                    snippets0.append("Date: ").append(item0.publishedDate).append("\n");
                    snippets0.append("Snippet: ").append(item0.snippet).append("\n\n");
                    if (isBlank(bestUrl0)) bestUrl0 = item0.url;
                }
            }

            if (snippets0.length() == 0) return null;

            String prompt0 = buildLlmPrompt(ctx.fundName, snippets0.toString());
            String llmOut0 = cache.llm(prompt0);
            if (isBlank(llmOut0)) return null;

            // Parse JSON response from LLM: {"found":true,"date":"2026-03-15","description":"..."}
            String eventDate0 = extractJsonField(llmOut0, "date");
            String description0 = extractJsonField(llmOut0, "description");
            String foundStr0   = extractJsonField(llmOut0, "found");

            if (!"true".equalsIgnoreCase(foundStr0.trim())) return null;
            if (isBlank(description0)) return null;

            // Normalize the date; fall back to extracting from the snippet text.
            String asOf0 = NewsClient.normalizeDate(eventDate0);
            if (isBlank(asOf0)) asOf0 = NewsClient.extractDateFromText(llmOut0);
            if (isBlank(asOf0) && !items0.isEmpty()) asOf0 = items0.get(0).publishedDate;
            if (isBlank(asOf0)) asOf0 = LocalDate.now().toString();

            double conf0 = applyRecencyPenalty(NEWS_CONFIRMED_CONFIDENCE, asOf0);
            return new IndicatorResult(description0, conf0, bestUrl0, asOf0,
                AXIS_PROBABILITY_NOW,
                "News fund-close event extracted via SERP+LLM for LP: " + ctx.fundName + ".");
        }
        catch (Exception e0)
        {
            return null;
        }
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private String buildNewsQuery(LpContext ctx)
    {
        String name0 = ctx.fundName.trim();
        String domain0 = extractDomain(ctx.website);
        if (!isBlank(domain0))
        {
            return name0 + " fund close OR \"final close\" site:" + domain0
                + " OR " + name0 + " fund close OR \"final close\"";
        }
        return name0 + " fund close OR \"final close\" OR \"announces close\"";
    }

    private String buildLlmPrompt(String lpName0, String snippets0)
    {
        return "You are an LP intelligence analyst. Given the news snippets below, determine "
            + "whether there is evidence that \"" + lpName0 + "\" recently announced a fund "
            + "close (initial close, final close, or any capital raise milestone).\n\n"
            + "Snippets:\n" + snippets0 + "\n"
            + "Respond ONLY with a JSON object with these exact keys:\n"
            + "  found (boolean): true if a fund close event for this specific LP is mentioned\n"
            + "  date (string): the ISO-8601 date of the event (e.g. 2026-03-15), or \"\" if unknown\n"
            + "  description (string): one concise sentence describing the event (fund name, type, size if available)\n"
            + "Do not include any text outside the JSON object. If unsure, set found=false.";
    }

    /** Apply a confidence reduction when the event is older than RECENCY_MONTHS_GATE. */
    private double applyRecencyPenalty(double baseConf0, String asOfDate0)
    {
        if (isBlank(asOfDate0)) return baseConf0;
        try
        {
            LocalDate eventDate0 = LocalDate.parse(asOfDate0.trim().substring(0, 10));
            long monthsAgo0 = ChronoUnit.MONTHS.between(eventDate0, LocalDate.now());
            if (monthsAgo0 > RECENCY_MONTHS_GATE)
            {
                return Math.max(0.0, baseConf0 - STALE_CONFIDENCE_PENALTY);
            }
        }
        catch (Exception ignored0) {}
        return baseConf0;
    }

    /** Pick the IndicatorResult with higher confidence; null-safe. */
    private IndicatorResult chooseBest(IndicatorResult a0, IndicatorResult b0)
    {
        if (a0 == null && b0 == null) return null;
        if (a0 == null) return b0;
        if (b0 == null) return a0;
        return a0.confidence >= b0.confidence ? a0 : b0;
    }

    /** Extract the LP's domain for entity anchoring. */
    private static String extractDomain(String website0)
    {
        if (isBlank(website0)) return "";
        String s0 = website0.toLowerCase().trim()
            .replaceFirst("^https?://", "").replaceFirst("^www\\.", "");
        int slash0 = s0.indexOf('/');
        return slash0 > 0 ? s0.substring(0, slash0) : s0;
    }

    /** True when the URL belongs to the LP's domain or the LP name appears in the URL. */
    private static boolean domainMatches(String url0, String domain0, String fundName0)
    {
        if (isBlank(url0)) return false;
        String low0 = url0.toLowerCase();
        if (!isBlank(domain0) && low0.contains(domain0)) return true;
        if (!isBlank(fundName0))
        {
            // Match slug-style: "rockwood" in "rockwood-capital-closes-fund"
            String slug0 = fundName0.toLowerCase().replaceAll("[^a-z0-9]", "");
            if (slug0.length() >= 4 && low0.replace("-", "").contains(slug0.substring(0, Math.min(6, slug0.length()))))
            {
                return true;
            }
        }
        return false;
    }

    /** Minimal JSON field extractor (no external JSON lib required for a narrow LLM response). */
    private static String extractJsonField(String json0, String key0)
    {
        if (isBlank(json0) || isBlank(key0)) return "";
        String search0 = "\"" + key0 + "\"";
        int idx0 = json0.indexOf(search0);
        if (idx0 < 0) return "";
        int colon0 = json0.indexOf(':', idx0 + search0.length());
        if (colon0 < 0) return "";
        int start0 = colon0 + 1;
        while (start0 < json0.length() && Character.isWhitespace(json0.charAt(start0))) start0++;
        if (start0 >= json0.length()) return "";
        char first0 = json0.charAt(start0);
        if (first0 == '"')
        {
            int end0 = json0.indexOf('"', start0 + 1);
            return end0 > start0 ? json0.substring(start0 + 1, end0) : "";
        }
        // boolean / number
        int end0 = start0;
        while (end0 < json0.length() && json0.charAt(end0) != ',' && json0.charAt(end0) != '}') end0++;
        return json0.substring(start0, end0).trim();
    }

    private static boolean isBlank(String s0) { return s0 == null || s0.trim().isEmpty(); }
}
