package com.liminer.brief;

import com.liminer.enrich.SerpUrls;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/*
 * BriefCitations builds the numbered source registry an investor brief cites from.
 *
 * Liminer already collects a source URL for nearly every fact it asserts about an LP,
 * but until now those URLs died inside the JSON blob columns they were written to. This
 * class is the deterministic bridge: it walks the four blobs the brief carries, keeps
 * every usable URL exactly once, and hands back an ordered list whose 1-based index is
 * the [n] marker a GP clicks in the rendered brief.
 *
 * Two rules make this safe to put in front of a GP walking into a meeting:
 *
 *   1. The registry is built BEFORE either GPT pass, from data the model did not author.
 *      The model selects a number; it never supplies a URL. A citation therefore cannot
 *      be hallucinated into existence -- the worst case is a marker pointing at the
 *      wrong (but real, and already stored) source.
 *
 *   2. stripInvalidMarkers is the enforcement half. The prompt ASKS the model to use only
 *      numbers from the list; this GUARANTEES it, deleting any [n] outside the registry's
 *      range. Same principle as the fundingStatus backstop in InvestorBriefJsonProcessor:
 *      a prompt instruction alone is a request, not a guarantee.
 *
 * No citation anywhere in the codebase carries a title or a snippet -- only a bare URL --
 * so labels are DERIVED here from the fields that do exist (a market-intelligence leaf's
 * indicator + value, an enrichment evidence quote, a background-check field name).
 */
public final class BriefCitations
{
    // Bounds the prompt size and, with it, the 49,000-char Investor Brief JSON cell.
    private static final int MAX_CITATIONS = 40;
    private static final int MAX_LABEL     = 120;

    // A value alone can be a whole LinkedIn "about" blob, which would eat the entire
    // label and leave no room for the evidence line behind it. Cap the value so the
    // two always share the label.
    private static final int MAX_VALUE_IN_LABEL = 60;

    // Scout writes email provenance as "website-crawl:https://..." -- the prefix is
    // bookkeeping, not part of the destination.
    private static final String CRAWL_PREFIX = "website-crawl:";

    private BriefCitations() {}

    public static class Citation
    {
        public int    index    = 0;   // 1-based; this is the [n] the GP sees
        public String url      = "";
        public String label    = "";
        public String asOfDate = "";

        public Citation(int index0, String url0, String label0, String asOfDate0)
        {
            this.index    = index0;
            this.url      = safe(url0);
            this.label    = safe(label0);
            this.asOfDate = safe(asOfDate0);
        }
    }

    // -----------------------------------------------------------------------
    // Collection
    // -----------------------------------------------------------------------

    /*
     * Walk the four blobs in a FIXED order. The order is load-bearing: both GPT passes
     * must see identical numbering, and a re-render of a stored brief must resolve [3]
     * to the same source it did when the brief was written.
     *
     * Any argument may be null or empty -- an LP with no market intelligence and no
     * background check simply yields an empty registry, and the prompt then tells the
     * model there is nothing to cite.
     */
    public static List<Citation> collect(JSONObject contact0, JSONObject market0,
                                         JSONObject backgroundCheck0, JSONObject scoutEvidence0)
    {
        // Keyed by normalized URL so one destination is always one index.
        LinkedHashMap<String, Citation> byUrl0 = new LinkedHashMap<String, Citation>();

        collectMarketIntelligence(byUrl0, market0);
        collectEnrichmentEvidence(byUrl0, contact0);
        collectBackgroundCheck(byUrl0, backgroundCheck0);
        collectScoutEvidence(byUrl0, scoutEvidence0);

        // Number only now, once the final membership and order are known.
        ArrayList<Citation> out0 = new ArrayList<Citation>();
        int n0 = 1;
        for (Citation c0 : byUrl0.values())
        {
            c0.index = n0++;
            out0.add(c0);
        }
        return out0;
    }

    /*
     * Market-intelligence leaves: the richest source in the system. Every leaf is
     * contractually required to carry a sourceUrl and an asOfDate, so these make the
     * best citations and are collected first.
     */
    private static void collectMarketIntelligence(Map<String, Citation> acc0, JSONObject market0)
    {
        if (market0 == null) return;
        JSONObject intel0 = market0.optJSONObject("intelligence");
        if (intel0 == null) return;

        String[] axes0 = { "resources", "fit", "probability_now" };
        for (String axis0 : axes0)
        {
            JSONArray arr0 = intel0.optJSONArray(axis0);
            if (arr0 == null) continue;
            for (int i0 = 0; i0 < arr0.length(); i0++)
            {
                JSONObject leaf0 = arr0.optJSONObject(i0);
                if (leaf0 == null) continue;
                String label0 = label(humanize(leaf0.optString("indicator", "")),
                                      leaf0.optString("value", ""),
                                      leaf0.optString("evidence", ""));
                add(acc0, leaf0.optString("sourceUrl", ""), label0, leaf0.optString("asOfDate", ""));
            }
        }
    }

    /*
     * LP-enrichment evidence: {allocator_type:[{source_url,quote}], sector_focus:[...], ...}.
     * This contract is prompt-only upstream and ships empty more often than not, so treat
     * every level as optional rather than assuming the shape.
     */
    private static void collectEnrichmentEvidence(Map<String, Citation> acc0, JSONObject contact0)
    {
        if (contact0 == null) return;
        JSONObject intel0 = contact0.optJSONObject("intelligence");
        if (intel0 == null) return;
        JSONObject evidence0 = intel0.optJSONObject("evidence");
        if (evidence0 == null) return;

        String asOf0 = "";
        JSONObject meta0 = intel0.optJSONObject("metadata");
        if (meta0 != null) asOf0 = meta0.optString("last_analyzed_at", "");

        for (String key0 : jsonKeys(evidence0))
        {
            JSONArray arr0 = evidence0.optJSONArray(key0);
            if (arr0 == null) continue;
            for (int i0 = 0; i0 < arr0.length(); i0++)
            {
                JSONObject item0 = arr0.optJSONObject(i0);
                if (item0 == null) continue;
                String quote0 = item0.optString("quote", "");
                String label0 = isBlank(quote0) ? humanize(key0) : humanize(key0) + ": " + quote0;
                add(acc0, item0.optString("source_url", ""), label0, asOf0);
            }
        }
    }

    /*
     * Background check: resolved_fields carry the source behind each bio/career/LinkedIn
     * value the brief already shows, and the evidence[] array records the SERP hits that
     * were considered (including rejected ones, which is why the notes go in the label).
     */
    private static void collectBackgroundCheck(Map<String, Citation> acc0, JSONObject bg0)
    {
        if (bg0 == null) return;
        String asOf0 = bg0.optString("last_checked_at", "");

        JSONObject fields0 = bg0.optJSONObject("resolved_fields");
        if (fields0 != null)
        {
            for (String key0 : jsonKeys(fields0))
            {
                JSONObject f0 = fields0.optJSONObject(key0);
                if (f0 == null) continue;
                String label0 = label(humanize(key0),
                                      f0.optString("value", ""),
                                      f0.optString("evidence", ""));
                add(acc0, f0.optString("source_url", ""), label0, asOf0);
            }
        }

        JSONArray evidence0 = bg0.optJSONArray("evidence");
        if (evidence0 != null)
        {
            for (int i0 = 0; i0 < evidence0.length(); i0++)
            {
                JSONObject e0 = evidence0.optJSONObject(i0);
                if (e0 == null) continue;
                String label0 = humanize(e0.optString("type", ""));
                String notes0 = e0.optString("notes", "");
                if (isBlank(label0)) label0 = "Background evidence";
                if (!isBlank(notes0)) label0 = label0 + ": " + notes0;
                add(acc0, e0.optString("url", ""), label0, asOf0);
            }
        }
    }

    /*
     * Scout evidence is present only on scout-discovered rows and carries bare URL
     * strings with no accompanying description, so the labels here are fixed.
     */
    private static void collectScoutEvidence(Map<String, Citation> acc0, JSONObject scout0)
    {
        if (scout0 == null) return;
        addUrlArray(acc0, scout0.optJSONArray("sources"), "Firm website");
        addUrlArray(acc0, scout0.optJSONArray("emailSources"), "Email source");
    }

    private static void addUrlArray(Map<String, Citation> acc0, JSONArray arr0, String label0)
    {
        if (arr0 == null) return;
        for (int i0 = 0; i0 < arr0.length(); i0++)
        {
            add(acc0, arr0.optString(i0, ""), label0, "");
        }
    }

    /*
     * Single admission point. A URL enters the registry only if it survives the same
     * "is this actually a usable destination" gate every SearchProvider uses -- which
     * also means nothing but an absolute http(s) URL can ever reach an <a href> or a
     * PDF Anchor downstream.
     */
    private static void add(Map<String, Citation> acc0, String rawUrl0, String label0, String asOfDate0)
    {
        if (acc0.size() >= MAX_CITATIONS) return;

        String url0 = stripCrawlPrefix(rawUrl0);
        if (!SerpUrls.isAbsoluteHttpUrl(url0)) return;

        String key0 = normalize(url0);
        if (acc0.containsKey(key0)) return;

        acc0.put(key0, new Citation(0, url0.trim(), truncate(clean(label0), MAX_LABEL), asOfDate0));
    }

    // -----------------------------------------------------------------------
    // Serialization
    // -----------------------------------------------------------------------

    public static JSONArray toJson(List<Citation> cites0)
    {
        JSONArray arr0 = new JSONArray();
        if (cites0 == null) return arr0;
        for (Citation c0 : cites0)
        {
            if (c0 == null) continue;
            JSONObject o0 = new JSONObject();
            o0.put("index", c0.index);
            o0.put("url", c0.url);
            o0.put("label", c0.label);
            o0.put("asOfDate", c0.asOfDate);
            arr0.put(o0);
        }
        return arr0;
    }

    /*
     * The block appended to both GPT prompts. Deliberately lists the label and date but
     * frames the URL as reference only -- the model's job is to pick a number, never to
     * reproduce a link.
     */
    public static String toPromptBlock(List<Citation> cites0)
    {
        StringBuilder sb0 = new StringBuilder();
        if (cites0 == null || cites0.isEmpty())
        {
            sb0.append("Sources available for citation: NONE.\n");
            sb0.append("Because there are no sources, do NOT write any [n] citation markers at all.\n");
            return sb0.toString();
        }

        sb0.append("Sources available for citation (cite by number only):\n");
        for (Citation c0 : cites0)
        {
            sb0.append("[").append(c0.index).append("] ").append(c0.label);
            if (!isBlank(c0.asOfDate)) sb0.append(" (as of ").append(c0.asOfDate).append(")");
            sb0.append(" — ").append(c0.url).append("\n");
        }
        sb0.append("\nCitation rules:\n");
        sb0.append("- Append the matching marker, e.g. [2], immediately after any claim you drew from that source.\n");
        sb0.append("- Use ONLY numbers 1 to ").append(cites0.size()).append(" from the list above.\n");
        sb0.append("- NEVER invent a marker, a number outside that range, or a URL of your own.\n");
        sb0.append("- Do not write URLs in your output at all; the marker is the citation.\n");
        sb0.append("- An uncited claim is acceptable. A fabricated citation is not.\n");
        return sb0.toString();
    }

    // -----------------------------------------------------------------------
    // Marker validation — the enforcement half of the contract
    // -----------------------------------------------------------------------

    /*
     * Delete every [n] the registry cannot back. maxIndex0 <= 0 (an empty registry)
     * strips all markers, which is what makes the "NONE" prompt branch a guarantee
     * rather than a request.
     *
     * Leading whitespace before a dropped marker goes with it, so removing a marker
     * mid-sentence does not leave a double space behind.
     */
    public static String stripInvalidMarkers(String text0, int maxIndex0)
    {
        if (text0 == null || text0.isEmpty()) return safe(text0);
        if (text0.indexOf('[') < 0) return text0;

        StringBuilder out0 = new StringBuilder(text0.length());
        int i0 = 0;
        while (i0 < text0.length())
        {
            char ch0 = text0.charAt(i0);
            if (ch0 != '[') { out0.append(ch0); i0++; continue; }

            int close0 = text0.indexOf(']', i0 + 1);
            if (close0 < 0) { out0.append(ch0); i0++; continue; }

            String inner0 = text0.substring(i0 + 1, close0).trim();
            if (!isDigits(inner0)) { out0.append(ch0); i0++; continue; }

            int n0;
            try { n0 = Integer.parseInt(inner0); }
            catch (Exception ignored0) { out0.append(ch0); i0++; continue; }

            if (n0 >= 1 && n0 <= maxIndex0)
            {
                out0.append(text0, i0, close0 + 1);
            }
            else
            {
                // Drop the marker and the space that introduced it.
                while (out0.length() > 0 && out0.charAt(out0.length() - 1) == ' ')
                {
                    out0.setLength(out0.length() - 1);
                }
            }
            i0 = close0 + 1;
        }
        return out0.toString();
    }

    /*
     * Recursively strip markers through every string in a model-authored object, so a
     * fabricated marker cannot survive by hiding inside a nested array or sub-object
     * (e.g. anticipatedObjections[].navigation).
     */
    public static JSONObject stripInvalidMarkersDeep(JSONObject obj0, int maxIndex0)
    {
        if (obj0 == null) return null;
        for (String key0 : jsonKeys(obj0))
        {
            obj0.put(key0, stripValue(obj0.opt(key0), maxIndex0));
        }
        return obj0;
    }

    private static Object stripValue(Object value0, int maxIndex0)
    {
        if (value0 instanceof String)     return stripInvalidMarkers((String) value0, maxIndex0);
        if (value0 instanceof JSONObject) return stripInvalidMarkersDeep((JSONObject) value0, maxIndex0);
        if (value0 instanceof JSONArray)
        {
            JSONArray arr0 = (JSONArray) value0;
            for (int i0 = 0; i0 < arr0.length(); i0++)
            {
                arr0.put(i0, stripValue(arr0.opt(i0), maxIndex0));
            }
            return arr0;
        }
        return value0;
    }

    // -----------------------------------------------------------------------
    // Utilities
    // -----------------------------------------------------------------------

    private static String stripCrawlPrefix(String url0)
    {
        if (url0 == null) return "";
        String t0 = url0.trim();
        if (t0.regionMatches(true, 0, CRAWL_PREFIX, 0, CRAWL_PREFIX.length()))
        {
            t0 = t0.substring(CRAWL_PREFIX.length()).trim();
        }
        return t0;
    }

    // Dedupe key only — never rendered. Case-folded with any trailing slash removed so
    // "https://Example.com/x/" and "https://example.com/x" are one citation.
    private static String normalize(String url0)
    {
        String t0 = safe(url0).trim().toLowerCase();
        while (t0.endsWith("/")) t0 = t0.substring(0, t0.length() - 1);
        return t0;
    }

    // "contact_linkedin_url" / "ReportedAumIndicator" -> "Contact Linkedin Url" / "Reported Aum Indicator"
    private static String humanize(String raw0)
    {
        String t0 = safe(raw0).trim();
        if (t0.isEmpty()) return "";
        t0 = t0.replace('_', ' ').replace('-', ' ');
        t0 = t0.replaceAll("(?<=[a-z0-9])(?=[A-Z])", " ");
        String[] parts0 = t0.split("\\s+");
        StringBuilder sb0 = new StringBuilder();
        for (String p0 : parts0)
        {
            if (p0.isEmpty()) continue;
            if (sb0.length() > 0) sb0.append(' ');
            sb0.append(Character.toUpperCase(p0.charAt(0)));
            if (p0.length() > 1) sb0.append(p0.substring(1));
        }
        return sb0.toString();
    }

    // Collapse whitespace and drop bracket characters, so a label can never smuggle a
    // [n]-looking sequence into the prompt's source list.
    private static String clean(String s0)
    {
        return safe(s0).replaceAll("[\\[\\]]", "").replaceAll("\\s+", " ").trim();
    }

    /*
     * "<what it is>: <what we read> — <why we believe it>".
     *
     * The evidence half is the sentence the upstream leaf or resolver wrote about its
     * own finding ("ADV Item 5.F, filed 2025-03-01", "from LinkedIn company page"). It
     * is what turns a citation label from an identifier into something a GP can judge,
     * so it is kept even when the value has to be clipped to make room. Any part may be
     * blank; the separators appear only between parts that exist.
     */
    private static String label(String base0, String value0, String evidence0)
    {
        StringBuilder sb0 = new StringBuilder(clean(base0));

        String v0 = truncate(clean(value0), MAX_VALUE_IN_LABEL);
        if (!isBlank(v0))
        {
            if (sb0.length() > 0) sb0.append(": ");
            sb0.append(v0);
        }

        String e0 = clean(evidence0);
        if (!isBlank(e0))
        {
            if (sb0.length() > 0) sb0.append(" — ");
            sb0.append(e0);
        }
        return sb0.toString();
    }

    private static String truncate(String s0, int max0)
    {
        String t0 = safe(s0);
        return t0.length() <= max0 ? t0 : t0.substring(0, max0 - 1).trim() + "…";
    }

    private static boolean isDigits(String s0)
    {
        if (s0 == null || s0.isEmpty()) return false;
        for (int i0 = 0; i0 < s0.length(); i0++)
        {
            if (!Character.isDigit(s0.charAt(i0))) return false;
        }
        return true;
    }

    private static List<String> jsonKeys(JSONObject obj0)
    {
        ArrayList<String> keys0 = new ArrayList<String>();
        if (obj0 == null) return keys0;
        for (Object k0 : obj0.keySet()) { keys0.add(String.valueOf(k0)); }
        return keys0;
    }

    private static boolean isBlank(String s0) { return s0 == null || s0.trim().isEmpty(); }

    private static String safe(String s0) { return s0 == null ? "" : s0; }
}
