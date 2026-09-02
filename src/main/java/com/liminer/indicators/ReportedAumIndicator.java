package com.liminer.indicators;

import com.liminer.core.LpContext;
import com.liminer.enrich.BrightDataSerpClient;
import com.liminer.enrich.ScrapeCache;
import com.liminer.enrich.SerpResult;

import java.util.ArrayList;
import org.json.JSONObject;

/*
 * ReportedAumIndicator (Resources 1C) — the universal SERP+LLM fallback: the only
 * resource read that works for ANY entity type (family office, foreign LP, SWF,
 * pension). It is the consistency backstop when the authoritative filing leaves
 * (1A RAUM / 1B 990) miss.
 *
 * Deliberately MEDIUM confidence, capped BELOW the filing leaves (0.90) so the
 * rollup prefers regulator-filed figures whenever both exist. Behind a hard
 * confidence gate: a figure is emitted only when the LLM reconciliation is
 * confident, cites a source URL, and dates the figure — otherwise empty
 * (hallucination / wrong-entity risk).
 *
 * Wrong-entity protection: the prompt embeds the LP name AND its website domain
 * as an anchor (which also keeps two LPs from colliding on the LLM cache key),
 * and a citation URL is required.
 *
 * Thread-safety: all external calls go through the thread-safe ScrapeCache.
 */
public class ReportedAumIndicator implements Indicator
{
    // Cap below FILING_CONFIDENCE (0.90) in 1A/1B so filings always outrank this.
    private static final double MAX_CONFIDENCE = 0.65;
    // Reject LLM reconciliations below this floor (the confidence gate).
    private static final double CONFIDENCE_GATE = 0.50;
    private static final int MAX_SERP_RESULTS = 8;

    private final BrightDataSerpClient serpClient0 = new BrightDataSerpClient();

    @Override
    public String axis() { return AXIS_RESOURCES; }

    @Override
    public String name() { return "ReportedAum"; }

    @Override
    public IndicatorResult fetch(LpContext ctx, ScrapeCache cache) throws Exception
    {
        if (ctx == null || cache == null || isBlank(ctx.fundName))
        {
            return IndicatorResult.empty(AXIS_RESOURCES);
        }

        String lpName0 = ctx.fundName.trim();
        String domain0 = extractDomain(ctx.website);

        ArrayList<SerpResult> results0 = cache.search(serpClient0,
            lpName0 + " assets under management", MAX_SERP_RESULTS);
        String snippets0 = collectSnippets(results0);
        if (isBlank(snippets0))
        {
            return IndicatorResult.empty(AXIS_RESOURCES);
        }

        String llmOut0 = cache.llm(buildPrompt(lpName0, domain0, snippets0));
        JSONObject json0 = parseJsonObject(llmOut0);
        if (json0 == null)
        {
            return IndicatorResult.empty(AXIS_RESOURCES);
        }

        String aum0 = json0.optString("aum", "").trim();
        String asOf0 = json0.optString("asOfDate", "").trim();
        String sourceUrl0 = json0.optString("sourceUrl", "").trim();
        double llmConfidence0 = json0.optDouble("confidence", 0.0);

        // The gate: confident, cited, and dated — or nothing. Tune the threshold,
        // never remove the gate.
        if (isBlank(aum0) || isBlank(asOf0) || isBlank(sourceUrl0)
            || llmConfidence0 < CONFIDENCE_GATE)
        {
            return IndicatorResult.empty(AXIS_RESOURCES);
        }

        // CURRENT_WEBSITE = undated figure from the LP's OWN live site; a live-site
        // claim is an as-of-now claim, so today is the honest stamp. Only valid
        // when the citation really is on the LP's domain (anti-hallucination check).
        String reportedNote0;
        if ("CURRENT_WEBSITE".equalsIgnoreCase(asOf0))
        {
            if (isBlank(domain0) || !extractDomain(sourceUrl0).endsWith(domain0))
            {
                return IndicatorResult.empty(AXIS_RESOURCES);
            }
            asOf0 = java.time.LocalDate.now().toString();
            reportedNote0 = "per LP's current website, read " + asOf0;
        }
        else
        {
            reportedNote0 = "as reported " + asOf0;
        }

        double confidence0 = Math.min(llmConfidence0, MAX_CONFIDENCE);
        return new IndicatorResult("Reported AUM " + aum0 + " (" + reportedNote0 + ")",
            confidence0, sourceUrl0, asOf0, AXIS_RESOURCES,
            "SERP+LLM reconciled public AUM figure for " + lpName0
            + (isBlank(domain0) ? "" : " anchored on domain " + domain0)
            + "; universal fallback, outranked by filings.");
    }

    private String buildPrompt(String lpName0, String domain0, String snippets0)
    {
        return "You are reconciling public reports of an investor's assets under management.\n"
            + "Entity: \"" + lpName0 + "\""
            + (isBlank(domain0) ? "" : " (official website domain: " + domain0 + ")") + "\n"
            + "Rules:\n"
            + "- Use ONLY the search snippets below. Ignore figures about any OTHER entity with a "
            + "similar name — when the snippet's subject or its URL does not plausibly match this "
            + "entity" + (isBlank(domain0) ? "" : " or its domain") + ", discard it.\n"
            + "- Prefer AUM over AUA or committed capital; if only AUA/committed is available, "
            + "label it as such in the figure.\n"
            + "- Prefer the freshest dated figure. asOfDate must be a date that EXPLICITLY appears "
            + "in the chosen snippet (stated as-of or article date), ISO-8601 where possible. NEVER "
            + "invent or estimate a date. If no explicit date appears but the figure comes from the "
            + "entity's OWN website domain, set asOfDate to exactly \"CURRENT_WEBSITE\". Otherwise, "
            + "if you cannot date the figure, set confidence to 0.\n"
            + "- sourceUrl must be the URL of the snippet the figure came from.\n"
            + "- confidence is 0..1: your certainty this figure is current, correctly attributed "
            + "to this exact entity, and correctly read from the snippets.\n"
            + "Respond with ONLY a JSON object, no markdown:\n"
            + "{\"aum\": \"$X\", \"asOfDate\": \"YYYY-MM-DD\", \"sourceUrl\": \"...\", "
            + "\"confidence\": 0.0, \"reasoning\": \"one short sentence\"}\n\n"
            + "Search snippets:\n" + snippets0;
    }

    private String collectSnippets(ArrayList<SerpResult> results0)
    {
        if (results0 == null) return "";
        StringBuilder sb0 = new StringBuilder();
        for (SerpResult result0 : results0)
        {
            if (result0 == null) continue;
            String line0 = (safe(result0.title) + " — " + safe(result0.snippet)
                + " [" + safe(result0.url) + "]").trim();
            if (line0.length() < 10) continue;
            sb0.append("- ").append(truncate(line0, 400)).append("\n");
        }
        return sb0.toString().trim();
    }

    // Pull the first {...} JSON object out of an LLM reply (tolerates code fences).
    private static JSONObject parseJsonObject(String text0)
    {
        if (isBlank(text0)) return null;
        try
        {
            String t0 = text0.trim();
            int start0 = t0.indexOf('{');
            int end0 = t0.lastIndexOf('}');
            if (start0 < 0 || end0 <= start0) return null;
            return new JSONObject(t0.substring(start0, end0 + 1));
        }
        catch (Exception e0)
        {
            return null;
        }
    }

    private static String extractDomain(String website0)
    {
        if (isBlank(website0)) return "";
        String s0 = website0.trim().toLowerCase()
            .replaceFirst("^https?://", "")
            .replaceFirst("^www\\.", "");
        int slash0 = s0.indexOf('/');
        return slash0 > 0 ? s0.substring(0, slash0) : s0;
    }

    private static String truncate(String s0, int max0)
    {
        String v0 = safe(s0);
        return v0.length() <= max0 ? v0 : v0.substring(0, max0);
    }

    private static String safe(String s0) { return s0 == null ? "" : s0; }
    private static boolean isBlank(String s0) { return s0 == null || s0.trim().isEmpty(); }
}
