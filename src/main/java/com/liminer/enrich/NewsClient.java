package com.liminer.enrich;

import com.liminer.core.NewsItem;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.json.JSONArray;
import org.json.JSONObject;

/*
 * NewsClient wraps Bright Data's SERP zone in news-search mode (tbm=nws).
 * It is intentionally dumb: it returns raw NewsItem structs; callers supply
 * the LLM reasoning. Route all calls through ScrapeCache.searchNews() so a
 * given query is fetched at most once per batch.
 *
 * Required env var: BRIGHT_DATA_API_TOKEN
 * Optional env var: BRIGHT_DATA_SERP_ZONE  (defaults to serp_api1)
 */
public class NewsClient
{
    private static final String API_TOKEN0 = System.getenv("BRIGHT_DATA_API_TOKEN");
    private static final String SERP_ZONE0 = getEnvOrDefault("BRIGHT_DATA_SERP_ZONE", "serp_api1");
    private static final HttpClient CLIENT0 = HttpClient.newHttpClient();

    // Patterns for extracting a date string from a news snippet.
    // Covers: "June 10, 2026", "10 Jun 2026", "2026-06-10", "Jun 10, 2026"
    private static final Pattern DATE_PATTERN0 = Pattern.compile(
        "(\\d{4}-\\d{2}-\\d{2})" +                         // ISO
        "|(\\w+ \\d{1,2},? \\d{4})" +                     // June 10 2026 / June 10, 2026
        "|(\\d{1,2} \\w+ \\d{4})",                         // 10 June 2026
        Pattern.CASE_INSENSITIVE);

    public NewsClient() {}

    /**
     * Search for news items matching query. Returns up to maxResults items,
     * each with a publishedDate when the source provides one.
     * Never returns null; on error returns an empty list.
     */
    public List<NewsItem> searchNews(String query0, int maxResults0)
    {
        if (isBlank(query0))
        {
            return new ArrayList<>();
        }
        try
        {
            return fetchNews(query0.trim(), Math.max(maxResults0, 5));
        }
        catch (Exception e0)
        {
            System.err.println("[NewsClient] searchNews failed for \"" + query0 + "\": " + e0.getMessage());
            return new ArrayList<>();
        }
    }

    private List<NewsItem> fetchNews(String query0, int maxResults0) throws Exception
    {
        if (isBlank(API_TOKEN0))
        {
            throw new RuntimeException("Missing BRIGHT_DATA_API_TOKEN environment variable.");
        }

        String googleUrl0 = "https://www.google.com/search?q="
            + URLEncoder.encode(query0, StandardCharsets.UTF_8)
            + "&tbm=nws&num=" + maxResults0;

        JSONObject body0 = new JSONObject();
        body0.put("zone", SERP_ZONE0);
        body0.put("url", googleUrl0);
        body0.put("format", "raw");

        HttpRequest request0 = HttpRequest.newBuilder()
            .uri(URI.create("https://api.brightdata.com/request"))
            .timeout(Duration.ofSeconds(20))
            .header("Content-Type", "application/json")
            .header("Authorization", "Bearer " + API_TOKEN0)
            .POST(HttpRequest.BodyPublishers.ofString(body0.toString()))
            .build();

        String responseBody0 = null;
        Exception lastError0 = null;
        for (int attempt0 = 1; attempt0 <= 2; attempt0++)
        {
            try
            {
                HttpResponse<String> response0 = CLIENT0.send(
                    request0, HttpResponse.BodyHandlers.ofString());
                int status0 = response0.statusCode();
                if (status0 == 502 || status0 == 503 || status0 == 429)
                {
                    lastError0 = new RuntimeException("transient news SERP status " + status0);
                    Thread.sleep(400L * attempt0);
                    continue;
                }
                if (status0 < 200 || status0 >= 300)
                {
                    throw new RuntimeException(
                        "Bright Data news SERP failed. Status: " + status0
                        + ". Body: " + response0.body());
                }
                responseBody0 = response0.body();
                break;
            }
            catch (java.net.http.HttpTimeoutException timeout0)
            {
                lastError0 = timeout0;
            }
        }

        if (responseBody0 == null)
        {
            throw new RuntimeException("News SERP request failed after retries.", lastError0);
        }

        List<NewsItem> items0 = tryParseJsonNews(responseBody0, maxResults0);
        if (!items0.isEmpty())
        {
            return items0;
        }
        return parseHtmlNews(responseBody0, maxResults0);
    }

    private List<NewsItem> tryParseJsonNews(String body0, int maxResults0)
    {
        List<NewsItem> items0 = new ArrayList<>();
        try
        {
            JSONObject root0 = new JSONObject(body0);

            // Bright Data news JSON may come under "news", "news_results", "organic", or "organic_results"
            JSONArray arr0 = firstNonNullArray(root0,
                "news", "news_results", "organic", "organic_results", "results");
            if (arr0 == null)
            {
                return items0;
            }

            for (int i = 0; i < arr0.length() && items0.size() < maxResults0; i++)
            {
                JSONObject item0 = arr0.optJSONObject(i);
                if (item0 == null) continue;

                String url0 = firstNonBlank(
                    item0.optString("url", ""),
                    item0.optString("link", ""),
                    item0.optString("display_link", ""));
                if (isBlank(url0)) continue;

                String title0   = item0.optString("title", "");
                String snippet0 = firstNonBlank(
                    item0.optString("snippet", ""),
                    item0.optString("description", ""));

                String date0 = firstNonBlank(
                    item0.optString("date", ""),
                    item0.optString("published_date", ""),
                    item0.optString("publishedDate", ""),
                    item0.optString("time", ""));
                date0 = normalizeDate(date0);

                if (isBlank(date0))
                {
                    date0 = extractDateFromText(snippet0);
                }

                items0.add(new NewsItem(title0,
                    BrightDataSerpClient.cleanGoogleRedirectUrl(url0),
                    snippet0, date0));
            }
        }
        catch (Exception ignored0) {}
        return items0;
    }

    private List<NewsItem> parseHtmlNews(String html0, int maxResults0)
    {
        List<NewsItem> items0 = new ArrayList<>();
        if (isBlank(html0)) return items0;

        Pattern hrefPat0 = Pattern.compile("href=\\\"([^\\\"]+)\\\"", Pattern.CASE_INSENSITIVE);
        Matcher m0 = hrefPat0.matcher(html0);
        while (m0.find() && items0.size() < maxResults0)
        {
            String raw0 = unescapeHtml(m0.group(1));
            String clean0 = BrightDataSerpClient.cleanGoogleRedirectUrl(raw0);
            if (isUsefulNewsUrl(clean0))
            {
                items0.add(new NewsItem("", clean0, "", ""));
            }
        }
        return items0;
    }

    // -----------------------------------------------------------------------
    // Date helpers — exported so FundCloseIndicator / NewAllocatorIndicator
    // can call them directly for consistent asOfDate parsing.
    // -----------------------------------------------------------------------

    /**
     * Attempt to parse a date string into ISO-8601 (yyyy-MM-dd).
     * Returns the input unchanged if it already looks like ISO, or "" on failure.
     */
    public static String normalizeDate(String raw0)
    {
        if (isBlank(raw0)) return "";
        raw0 = raw0.trim();

        // Already ISO
        if (raw0.matches("\\d{4}-\\d{2}-\\d{2}.*"))
        {
            return raw0.substring(0, 10);
        }

        String[] formats0 = {
            "MMMM d, yyyy", "MMM d, yyyy", "MMMM d yyyy", "MMM d yyyy",
            "d MMMM yyyy", "d MMM yyyy",
            "MM/dd/yyyy", "M/d/yyyy"
        };

        for (String fmt0 : formats0)
        {
            try
            {
                LocalDate d0 = LocalDate.parse(raw0,
                    DateTimeFormatter.ofPattern(fmt0, java.util.Locale.ENGLISH));
                return d0.toString();
            }
            catch (Exception ignored0) {}
        }
        return "";
    }

    /**
     * Scan free text for any date pattern and return the first match normalized to ISO.
     * Useful when the API returns the date embedded in a snippet string.
     */
    public static String extractDateFromText(String text0)
    {
        if (isBlank(text0)) return "";
        Matcher m0 = DATE_PATTERN0.matcher(text0);
        while (m0.find())
        {
            String candidate0 = m0.group().trim();
            String normalized0 = normalizeDate(candidate0);
            if (!isBlank(normalized0))
            {
                return normalized0;
            }
        }
        return "";
    }

    // -----------------------------------------------------------------------
    // Utility
    // -----------------------------------------------------------------------

    private static boolean isUsefulNewsUrl(String url0)
    {
        if (isBlank(url0) || !url0.toLowerCase().startsWith("http")) return false;
        String low0 = url0.toLowerCase();
        return !low0.contains("google.com") && !low0.contains("gstatic.com");
    }

    private static JSONArray firstNonNullArray(JSONObject root0, String... keys0)
    {
        for (String k0 : keys0)
        {
            JSONArray a0 = root0.optJSONArray(k0);
            if (a0 != null) return a0;
        }
        return null;
    }

    private static String firstNonBlank(String... vals0)
    {
        for (String v0 : vals0)
        {
            if (!isBlank(v0)) return v0;
        }
        return "";
    }

    private static String unescapeHtml(String v0)
    {
        if (v0 == null) return "";
        return v0.replace("&amp;", "&").replace("&quot;", "\"").replace("&#39;", "'");
    }

    private static String getEnvOrDefault(String name0, String def0)
    {
        String v0 = System.getenv(name0);
        return isBlank(v0) ? def0 : v0;
    }

    private static boolean isBlank(String v0)
    {
        return v0 == null || v0.trim().isEmpty();
    }
}
