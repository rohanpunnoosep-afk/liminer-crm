package com.liminer.indicators;

import com.liminer.enrich.BrightDataSerpClient;
import com.liminer.enrich.ScrapeCache;
import com.liminer.enrich.SerpResult;

import java.time.LocalDate;
import java.util.ArrayList;
import org.json.JSONObject;

/*
 * MacroContextModifier (Timing 3D) — one global bull/bear + rate-trend read that
 * becomes a cheap multiplier on every LP's PROBABILITY_NOW score. It is NOT an
 * Indicator: LPScoreProcessor calls computeOnce(cache) ONCE on the main thread
 * before the row loop, and the resulting immutable MacroContext is then read by
 * all row threads. Never recompute per row.
 *
 * It is a MODIFIER, never a primary per-LP score: the multiplier is hard-clamped
 * to [0.8, 1.2] so a regime read can nudge — but never dominate — any LP's
 * probability-now. On ANY failure (SERP down, LLM unparseable) it returns a
 * neutral 1.0 so the batch is never blocked.
 */
public class MacroContextModifier
{
    // Hard clamp so the same global read can never swing every LP's score wildly.
    private static final double MULTIPLIER_MIN = 0.8;
    private static final double MULTIPLIER_MAX = 1.2;
    private static final int MAX_SERP_RESULTS = 8;

    /*
     * Immutable-by-convention result struct. Built once per batch on the main
     * thread, then only read by parallel row threads — do not mutate after
     * construction.
     */
    public static class MacroContext
    {
        public String regimeTag = "NEUTRAL";
        public double multiplier = 1.0;
        public String asOfDate = "";
        public String sourceUrl = "";
        public String evidence = "";

        public static MacroContext neutral()
        {
            MacroContext ctx0 = new MacroContext();
            ctx0.regimeTag = "NEUTRAL";
            ctx0.multiplier = 1.0;
            ctx0.asOfDate = LocalDate.now().toString();
            ctx0.sourceUrl = "";
            ctx0.evidence = "Neutral default (macro read unavailable or failed).";
            return ctx0;
        }
    }

    /*
     * Compute the global macro regime ONCE per batch: pull index level/trend
     * (S&P 500 / NASDAQ) and rate-trend snippets via SERP, classify the regime
     * via the LLM (through the thread-safe ScrapeCache), and map the
     * classification to a clamped multiplier. Any failure returns neutral 1.0.
     */
    public static MacroContext computeOnce(ScrapeCache cache0)
    {
        if (cache0 == null)
        {
            return MacroContext.neutral();
        }

        try
        {
            BrightDataSerpClient serp0 = new BrightDataSerpClient();
            ArrayList<SerpResult> indexResults0 = safeSearch(serp0,
                "S&P 500 NASDAQ index level and trend this month");
            ArrayList<SerpResult> rateResults0 = safeSearch(serp0,
                "federal funds rate trend Fed interest rate outlook");

            String snippets0 = collectSnippets(indexResults0, rateResults0);
            if (isBlank(snippets0))
            {
                return MacroContext.neutral();
            }

            String llmOut0 = cache0.llm(buildPrompt(snippets0));
            JSONObject json0 = parseJsonObject(llmOut0);
            if (json0 == null)
            {
                return MacroContext.neutral();
            }

            String regime0 = normalizeTag(json0.optString("regime", "NEUTRAL"),
                new String[]{"BULL", "BEAR", "NEUTRAL"});
            String rateTrend0 = normalizeTag(json0.optString("rateTrend", "FLAT"),
                new String[]{"RISING", "FALLING", "FLAT"});

            MacroContext out0 = new MacroContext();
            out0.regimeTag = regime0 + "/" + rateTrend0;
            out0.multiplier = multiplierFor(regime0, rateTrend0);
            // Snapshot read of current market state — today is the honest stamp.
            out0.asOfDate = LocalDate.now().toString();
            out0.sourceUrl = firstUrl(indexResults0, rateResults0);
            out0.evidence = truncate(json0.optString("reasoning", ""), 500);
            return out0;
        }
        catch (Exception e0)
        {
            // Never block the batch on a macro failure — neutral means "no nudge".
            return MacroContext.neutral();
        }
    }

    /*
     * Deterministic regime -> multiplier mapping (the LLM only classifies; it
     * never picks the number). Bear markets trigger the denominator effect so
     * they weigh more than bull markets lift; rate trend is a smaller nudge.
     * Always clamped to [MULTIPLIER_MIN, MULTIPLIER_MAX].
     */
    private static double multiplierFor(String regime0, String rateTrend0)
    {
        double adj0 = 0.0;
        if ("BULL".equals(regime0)) adj0 += 0.10;
        if ("BEAR".equals(regime0)) adj0 -= 0.15;
        if ("FALLING".equals(rateTrend0)) adj0 += 0.05;
        if ("RISING".equals(rateTrend0)) adj0 -= 0.05;
        return clamp(1.0 + adj0, MULTIPLIER_MIN, MULTIPLIER_MAX);
    }

    private static String buildPrompt(String snippets0)
    {
        return "You are classifying the CURRENT market regime for private-market fundraising.\n"
            + "Based ONLY on the search-result snippets below, classify:\n"
            + "1. regime: BULL (equities trending up), BEAR (trending down), or NEUTRAL (mixed/flat).\n"
            + "2. rateTrend: RISING, FALLING, or FLAT for US interest rates.\n"
            + "Respond with ONLY a JSON object, no markdown, in this exact shape:\n"
            + "{\"regime\": \"BULL|BEAR|NEUTRAL\", \"rateTrend\": \"RISING|FALLING|FLAT\", "
            + "\"reasoning\": \"one short sentence citing the snippets\"}\n\n"
            + "Search snippets:\n" + snippets0;
    }

    private static ArrayList<SerpResult> safeSearch(BrightDataSerpClient serp0, String query0)
    {
        try
        {
            ArrayList<SerpResult> results0 = serp0.search(query0, MAX_SERP_RESULTS);
            return results0 == null ? new ArrayList<SerpResult>() : results0;
        }
        catch (Exception e0)
        {
            return new ArrayList<SerpResult>();
        }
    }

    private static String collectSnippets(ArrayList<SerpResult> a0, ArrayList<SerpResult> b0)
    {
        StringBuilder sb0 = new StringBuilder();
        appendSnippets(sb0, a0);
        appendSnippets(sb0, b0);
        return sb0.toString().trim();
    }

    private static void appendSnippets(StringBuilder sb0, ArrayList<SerpResult> results0)
    {
        if (results0 == null) return;
        for (SerpResult result0 : results0)
        {
            if (result0 == null) continue;
            String line0 = (safe(result0.title) + " — " + safe(result0.snippet)).trim();
            if (line0.length() < 5) continue;
            sb0.append("- ").append(truncate(line0, 300)).append("\n");
        }
    }

    private static String firstUrl(ArrayList<SerpResult> a0, ArrayList<SerpResult> b0)
    {
        for (ArrayList<SerpResult> list0 : new ArrayList[]{a0, b0})
        {
            if (list0 == null) continue;
            for (Object o0 : list0)
            {
                SerpResult result0 = (SerpResult) o0;
                if (result0 != null && !isBlank(result0.url)) return result0.url;
            }
        }
        return "";
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

    private static String normalizeTag(String raw0, String[] allowed0)
    {
        String upper0 = safe(raw0).trim().toUpperCase();
        for (String tag0 : allowed0)
        {
            if (upper0.contains(tag0)) return tag0;
        }
        return allowed0[allowed0.length - 1];
    }

    private static double clamp(double v0, double min0, double max0)
    {
        return Math.max(min0, Math.min(max0, v0));
    }

    private static String truncate(String s0, int max0)
    {
        String v0 = safe(s0);
        return v0.length() <= max0 ? v0 : v0.substring(0, max0);
    }

    private static String safe(String s0) { return s0 == null ? "" : s0; }
    private static boolean isBlank(String s0) { return s0 == null || s0.trim().isEmpty(); }
}
