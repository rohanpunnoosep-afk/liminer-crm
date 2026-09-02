package com.liminer.indicators;

import com.liminer.core.LpContext;
import com.liminer.enrich.BasicBackgroundChecker;
import com.liminer.enrich.BrightDataSerpClient;
import com.liminer.enrich.NewsClient;
import com.liminer.enrich.ScrapeCache;
import com.liminer.enrich.SerpResult;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;

/*
 * NewAllocatorIndicator (Timing 3C) — detects a recently-appointed senior allocator
 * (CIO, Head of Private Markets, or equivalent) at the LP, and dates their tenure start.
 *
 * A new decision-maker re-underwrites the portfolio → fresh GP relationship window.
 *
 * Why bio-page fallback instead of raw LinkedIn person scrape:
 *   BrightData gd_l1viktl72bvl7bjuj0 (LinkedIn person dataset) returns experience:null
 *   in practice, leaving start dates unresolvable. BasicBackgroundChecker Phase 4 solved
 *   this by crawling the fund's own website bio page. This indicator calls
 *   BasicBackgroundChecker.resolveBioForAllocator() — NOT a raw person scrape.
 *
 * Thread-safety: stateless. All external calls go through ScrapeCache (thread-safe).
 * The bio-fallback helper is read-only against its own internal state.
 */
public class NewAllocatorIndicator implements Indicator
{
    // A recently-started senior allocator is a strong timing signal.
    private static final double HIGH_CONFIDENCE = 0.80;
    private static final double MEDIUM_CONFIDENCE = 0.60;
    // Start within this window → "new" (months).
    private static final int NEW_ALLOCATOR_MONTHS = 18;

    // Titles that qualify as senior decision-maker.
    private static final String[] SENIOR_TITLES = {
        "chief investment officer", "cio",
        "head of private markets", "head of alternatives",
        "head of private equity", "head of venture",
        "managing director", "director of investments",
        "chief investment", "investment director",
        "head of investment", "chief financial officer"
    };

    private final BrightDataSerpClient serpClient0 = new BrightDataSerpClient();

    @Override
    public String axis() { return AXIS_PROBABILITY_NOW; }

    @Override
    public String name() { return "NewAllocator"; }

    @Override
    public IndicatorResult fetch(LpContext ctx, ScrapeCache cache) throws Exception
    {
        if (ctx == null || isBlank(ctx.fundName)) return IndicatorResult.empty(AXIS_PROBABILITY_NOW);

        // Step 1: find a senior contact — from LpContext.contacts first, then SERP.
        PersonCandidate person = findSeniorAllocator(ctx, cache);
        if (person == null) return IndicatorResult.empty(AXIS_PROBABILITY_NOW);

        // Step 2: bio-page fallback for tenure start date.
        BasicBackgroundChecker.PersonBioResult bio =
            BasicBackgroundChecker.resolveBioForAllocator(
                person.firstName, person.lastName, ctx.website, ctx.fundName, cache);

        String startDate = "";
        if (bio.found && !isBlank(bio.workHistoryJson))
        {
            startDate = extractCurrentRoleStartDate(bio.workHistoryJson, ctx.fundName);
        }

        // Step 3: decide if this is a "new" allocator.
        boolean isNew = isRecentStart(startDate);
        double confidence = isNew ? HIGH_CONFIDENCE : MEDIUM_CONFIDENCE;
        // Watch for title inflation — only trust senior titles at high confidence.
        if (!isSeniorTitle(person.title)) confidence = Math.min(confidence, MEDIUM_CONFIDENCE);

        String asOf = isBlank(startDate) ? LocalDate.now().toString() : startDate;
        String value = buildValue(person, startDate, isNew);
        String sourceUrl = isBlank(bio.bioUrl) ? ctx.website : bio.bioUrl;
        String evidence = "Bio-page fallback resolved tenure for "
            + person.firstName + " " + person.lastName
            + " (" + person.title + ") at " + ctx.fundName + ".";

        return new IndicatorResult(value, confidence, sourceUrl, asOf,
            AXIS_PROBABILITY_NOW, evidence);
    }

    // -----------------------------------------------------------------------
    // Step 1: find a senior allocator
    // -----------------------------------------------------------------------

    private PersonCandidate findSeniorAllocator(LpContext ctx, ScrapeCache cache)
    {
        // First: check LpContext.contacts for a known senior title.
        for (LpContext.Contact c : ctx.contacts)
        {
            if (isSeniorTitle(c.position))
            {
                PersonCandidate p = new PersonCandidate();
                p.firstName = c.firstName;
                p.lastName = c.lastName;
                p.title = c.position;
                return p;
            }
        }

        // Fallback: SERP search for senior allocator at this LP.
        return serpSearchForAllocator(ctx, cache);
    }

    private PersonCandidate serpSearchForAllocator(LpContext ctx, ScrapeCache cache)
    {
        try
        {
            String query = ctx.fundName.trim()
                + " CIO OR \"head of private markets\" OR \"head of investments\" OR \"chief investment officer\"";
            List<SerpResult> results = cache.search(serpClient0, query, 5);
            if (results == null || results.isEmpty()) return null;

            // Ask LLM to identify the senior allocator from snippets.
            StringBuilder snippets = new StringBuilder();
            for (SerpResult r : results)
            {
                snippets.append("Title: ").append(r.title).append("\n");
                snippets.append("Snippet: ").append(r.snippet).append("\n\n");
            }

            String prompt = "From these search results about \"" + ctx.fundName
                + "\", identify the current senior investment decision-maker (CIO, Head of Private Markets, "
                + "Head of Investments, or equivalent). "
                + "Return ONLY JSON: {\"found\":true/false,\"first_name\":\"\",\"last_name\":\"\",\"title\":\"\"}\n\n"
                + snippets;

            String llmOut = cache.llm(prompt);
            if (isBlank(llmOut)) return null;

            String foundStr = extractJson(llmOut, "found");
            if (!"true".equalsIgnoreCase(foundStr.trim())) return null;

            PersonCandidate p = new PersonCandidate();
            p.firstName = extractJson(llmOut, "first_name");
            p.lastName = extractJson(llmOut, "last_name");
            p.title = extractJson(llmOut, "title");

            if (isBlank(p.firstName) && isBlank(p.lastName)) return null;
            return p;
        }
        catch (Exception e0)
        {
            return null;
        }
    }

    // -----------------------------------------------------------------------
    // Step 2: extract current-role start date from work history JSON
    // -----------------------------------------------------------------------

    private String extractCurrentRoleStartDate(String workHistoryJson, String fundName)
    {
        try
        {
            JSONArray arr = new JSONArray(workHistoryJson);
            String fundSlug = fundName.toLowerCase().replaceAll("[^a-z0-9]", "");

            for (int i = 0; i < arr.length(); i++)
            {
                JSONObject job = arr.optJSONObject(i);
                if (job == null) continue;

                String company = job.optString("company", "").toLowerCase();
                String dateRange = job.optString("date_range", "");

                // Match to the current fund.
                boolean atCurrentFund = !isBlank(fundSlug)
                    && (company.contains(fundSlug.substring(0, Math.min(5, fundSlug.length())))
                    || (!isBlank(fundName) && company.contains(fundName.toLowerCase().split(" ")[0])));

                // "Present" in date range means it's the current role.
                boolean isCurrent = dateRange.toLowerCase().contains("present")
                    || dateRange.toLowerCase().contains("current")
                    || dateRange.toLowerCase().contains("now");

                if ((atCurrentFund || i == 0) && isCurrent && !isBlank(dateRange))
                {
                    // Extract start portion: "Jan 2024 - Present" → "Jan 2024"
                    String start = dateRange.split("[-–—]")[0].trim();
                    String normalized = NewsClient.normalizeDate(start);
                    if (!isBlank(normalized)) return normalized;
                    // Try just the year portion.
                    if (start.matches(".*\\d{4}.*"))
                    {
                        return start.replaceAll(".*(\\d{4}).*", "$1-01-01");
                    }
                }
            }
        }
        catch (Exception ignored) {}
        return "";
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private static boolean isRecentStart(String startDate)
    {
        if (isBlank(startDate)) return false;
        try
        {
            String iso = startDate.length() >= 10 ? startDate.substring(0, 10) : startDate;
            LocalDate start = LocalDate.parse(iso);
            long months = java.time.temporal.ChronoUnit.MONTHS.between(start, LocalDate.now());
            return months <= NEW_ALLOCATOR_MONTHS;
        }
        catch (Exception ignored) { return false; }
    }

    private static boolean isSeniorTitle(String title)
    {
        if (isBlank(title)) return false;
        String low = title.toLowerCase();
        for (String t : SENIOR_TITLES) { if (low.contains(t)) return true; }
        return false;
    }

    private static String buildValue(PersonCandidate p, String startDate, boolean isNew)
    {
        StringBuilder sb = new StringBuilder();
        if (!isBlank(p.firstName)) sb.append(p.firstName).append(" ");
        if (!isBlank(p.lastName)) sb.append(p.lastName);
        if (!isBlank(p.title)) sb.append(", ").append(p.title);
        if (!isBlank(startDate)) sb.append("; started ").append(startDate);
        if (isNew) sb.append(" (new)");
        return sb.toString().trim();
    }

    /** Minimal JSON string-field extractor — avoids pulling a JSON lib for a narrow LLM response. */
    private static String extractJson(String json, String key)
    {
        if (isBlank(json) || isBlank(key)) return "";
        String search = "\"" + key + "\"";
        int idx = json.indexOf(search);
        if (idx < 0) return "";
        int colon = json.indexOf(':', idx + search.length());
        if (colon < 0) return "";
        int start = colon + 1;
        while (start < json.length() && Character.isWhitespace(json.charAt(start))) start++;
        if (start >= json.length()) return "";
        char first = json.charAt(start);
        if (first == '"')
        {
            int end = json.indexOf('"', start + 1);
            return end > start ? json.substring(start + 1, end) : "";
        }
        int end = start;
        while (end < json.length() && json.charAt(end) != ',' && json.charAt(end) != '}') end++;
        return json.substring(start, end).trim();
    }

    private static boolean isBlank(String s) { return s == null || s.trim().isEmpty(); }

    private static class PersonCandidate
    {
        String firstName = "";
        String lastName = "";
        String title = "";
    }
}
