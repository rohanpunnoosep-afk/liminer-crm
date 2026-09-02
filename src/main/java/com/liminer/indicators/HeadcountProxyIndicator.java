package com.liminer.indicators;

import com.liminer.core.LpContext;
import com.liminer.enrich.BrightDataLinkedInClient;
import com.liminer.enrich.LinkedInScrapeResult;
import com.liminer.enrich.ScrapeCache;

import org.json.JSONArray;
import org.json.JSONObject;
import java.time.LocalDate;

/*
 * HeadcountProxyIndicator (Resources 1D) — a free, near-universal FLOOR estimate of
 * size from the LinkedIn company page already in our stack. Deliberately LOW
 * confidence: headcount != AUM (a 20-person fund-of-funds runs billions), so this
 * only fills the gap when the authoritative resource leaves (1A RAUM / 1B 990 / 1C
 * reported-AUM) miss. Do NOT raise its confidence.
 *
 * All scraping routes through the shared, thread-safe ScrapeCache so the same
 * company page is fetched at most once across rows. Returns empty when there is no
 * company LinkedIn URL or no parseable headcount.
 */
public class HeadcountProxyIndicator implements Indicator
{
    // Intentional low ceiling so a proxy never outranks a real AUM figure.
    private static final double PROXY_CONFIDENCE = 0.30;

    private final BrightDataLinkedInClient liClient0 = new BrightDataLinkedInClient();

    @Override
    public String axis() { return AXIS_RESOURCES; }

    @Override
    public String name() { return "HeadcountProxy"; }

    @Override
    public IndicatorResult fetch(LpContext ctx, ScrapeCache cache) throws Exception
    {
        if (ctx == null || isBlank(ctx.companyLinkedInUrl) || cache == null)
        {
            return IndicatorResult.empty(AXIS_RESOURCES);
        }

        LinkedInScrapeResult result0 = cache.scrapeCompany(liClient0, ctx.companyLinkedInUrl.trim());
        if (result0 == null || isBlank(result0.rawJson))
        {
            return IndicatorResult.empty(AXIS_RESOURCES);
        }

        JSONObject json0 = parseObject(result0.rawJson);
        if (json0 == null) return IndicatorResult.empty(AXIS_RESOURCES);

        String band0 = headcountBand(json0);
        if (isBlank(band0)) return IndicatorResult.empty(AXIS_RESOURCES);

        String industries0 = firstNonBlank(
            joinArray(json0, "industries"),
            json0.optString("industry", ""),
            joinArray(json0, "specialties"));

        StringBuilder value0 = new StringBuilder();
        value0.append("Headcount band: ").append(band0).append(" (LinkedIn size proxy, not AUM)");
        if (!isBlank(industries0))
        {
            value0.append("; industries: ").append(industries0);
        }

        // Snapshot signal — the page has no "as of" date, so today is the honest stamp.
        String asOf0 = LocalDate.now().toString();
        return new IndicatorResult(value0.toString(), PROXY_CONFIDENCE,
            isBlank(result0.url) ? ctx.companyLinkedInUrl.trim() : result0.url,
            asOf0, AXIS_RESOURCES,
            "LinkedIn company headcount proxy via ScrapeCache; floor estimate only.");
    }

    // Derive a headcount band from whichever Bright Data company-size key is present.
    private String headcountBand(JSONObject json0)
    {
        // Range arrays, e.g. company_size: [51, 200].
        for (String key0 : new String[]{"company_size", "employees_range"})
        {
            JSONArray arr0 = json0.optJSONArray(key0);
            if (arr0 != null && arr0.length() >= 2)
            {
                String lo0 = arr0.optString(0, "").trim();
                String hi0 = arr0.optString(1, "").trim();
                if (!lo0.isEmpty() && !hi0.isEmpty()) return lo0 + "-" + hi0;
            }
        }

        // Pre-formatted range strings, e.g. "51-200 employees".
        for (String key0 : new String[]{"company_size", "size", "employees_range"})
        {
            String s0 = json0.optString(key0, "").trim();
            if (!s0.isEmpty() && s0.matches(".*\\d.*")) return s0;
        }

        // Raw employee counts → bucket into a band.
        for (String key0 : new String[]{"employees_in_linkedin", "employees", "staff_count", "employee_count"})
        {
            long n0 = optLong(json0, key0);
            if (n0 > 0) return bandFor(n0) + " (~" + n0 + ")";
        }
        return "";
    }

    private String bandFor(long n0)
    {
        if (n0 < 11) return "1-10";
        if (n0 < 51) return "11-50";
        if (n0 < 201) return "51-200";
        if (n0 < 501) return "201-500";
        if (n0 < 1001) return "501-1000";
        if (n0 < 5001) return "1001-5000";
        if (n0 < 10001) return "5001-10000";
        return "10000+";
    }

    private long optLong(JSONObject json0, String key0)
    {
        try
        {
            if (!json0.has(key0)) return -1;
            Object v0 = json0.get(key0);
            if (v0 instanceof Number) return ((Number) v0).longValue();
            String s0 = String.valueOf(v0).replaceAll("[^0-9]", "");
            return s0.isEmpty() ? -1 : Long.parseLong(s0);
        }
        catch (Exception e0) { return -1; }
    }

    private String joinArray(JSONObject json0, String key0)
    {
        JSONArray arr0 = json0.optJSONArray(key0);
        if (arr0 == null) return "";
        StringBuilder sb0 = new StringBuilder();
        for (int i = 0; i < arr0.length() && i < 6; i++)
        {
            String s0 = arr0.optString(i, "").trim();
            if (s0.isEmpty()) continue;
            if (sb0.length() > 0) sb0.append(", ");
            sb0.append(s0);
        }
        return sb0.toString();
    }

    private JSONObject parseObject(String json0)
    {
        try
        {
            String t0 = json0.trim();
            if (!t0.startsWith("{")) return null;
            return new JSONObject(t0);
        }
        catch (Exception e0) { return null; }
    }

    private String firstNonBlank(String... vals0)
    {
        for (String v0 : vals0) if (!isBlank(v0)) return v0;
        return "";
    }

    private static boolean isBlank(String s0) { return s0 == null || s0.trim().isEmpty(); }
    private static String safe(String s0) { return s0 == null ? "" : s0; }
}
