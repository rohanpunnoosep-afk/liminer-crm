package com.liminer.enrich;

import java.net.URI;
import java.net.URLEncoder;
import java.time.Duration;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.json.JSONArray;
import org.json.JSONObject;

/*
 * BrightDataSerpClient runs Google searches through Bright Data's SERP zone.
 *
 * Bright Data's docs show SERP requests going through POST https://api.brightdata.com/request
 * with a SERP zone, target Google URL, and raw/JSON format. This client supports both:
 * 1. parsed JSON when your SERP zone returns it
 * 2. raw HTML fallback parsing when the zone returns plain Google HTML
 *
 * Required env var:
 *   BRIGHT_DATA_API_TOKEN
 *
 * Optional env var:
 *   BRIGHT_DATA_SERP_ZONE   defaults to serp_api1
 */
public class BrightDataSerpClient
{
    private static final String BRIGHT_DATA_API_TOKEN0 = System.getenv("BRIGHT_DATA_API_TOKEN");
    private static final String BRIGHT_DATA_SERP_ZONE0 = getEnvOrDefault("BRIGHT_DATA_SERP_ZONE", "serp_api1");
    private static final HttpClient CLIENT0 = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(15))
            .build();

    public BrightDataSerpClient()
    {
    }

    public ArrayList<SerpResult> search(String query0, int maxResults0) throws Exception
    {
        if (isBlank(BRIGHT_DATA_API_TOKEN0))
        {
            throw new RuntimeException("Missing BRIGHT_DATA_API_TOKEN environment variable.");
        }

        if (isBlank(query0))
        {
            return new ArrayList<SerpResult>();
        }

        String googleUrl0 = "https://www.google.com/search?q="
            + URLEncoder.encode(query0, StandardCharsets.UTF_8)
            + "&num="
            + Math.max(maxResults0, 10);

        JSONObject body0 = new JSONObject();
        body0.put("zone", BRIGHT_DATA_SERP_ZONE0);
        body0.put("url", googleUrl0);
        body0.put("format", "raw");

        HttpRequest request0 = HttpRequest.newBuilder()
            .uri(URI.create("https://api.brightdata.com/request"))
            .timeout(Duration.ofSeconds(20))
            .header("Content-Type", "application/json")
            .header("Authorization", "Bearer " + BRIGHT_DATA_API_TOKEN0)
            .POST(HttpRequest.BodyPublishers.ofString(body0.toString()))
            .build();

        // Up to 2 attempts: retry once on transient 502/503/429 or a timeout.
        // A short timeout keeps a stuck call from blowing the per-row budget.
        String responseBody0 = null;
        Exception lastError0 = null;
        for (int attempt0 = 1; attempt0 <= 2; attempt0++)
        {
            try
            {
                HttpResponse<String> response0;
                BrightDataThrottle.acquire();
                try { response0 = CLIENT0.send(request0, HttpResponse.BodyHandlers.ofString()); }
                finally { BrightDataThrottle.release(); }
                int status0 = response0.statusCode();
                if (status0 == 502 || status0 == 503 || status0 == 429)
                {
                    BrightDataThrottle.noteThrottle();
                    lastError0 = new RuntimeException("transient SERP status " + status0);
                    Thread.sleep(400L * attempt0);
                    continue;
                }
                if (status0 < 200 || status0 >= 300)
                {
                    throw new RuntimeException(
                        "Bright Data SERP request failed. Status: " + status0
                        + ". Body: " + response0.body());
                }
                responseBody0 = response0.body();
                break;
            }
            catch (java.net.http.HttpTimeoutException timeout0)
            {
                BrightDataThrottle.noteThrottle();
                lastError0 = timeout0; // retry
            }
        }

        if (responseBody0 == null)
        {
            throw new RuntimeException("Bright Data SERP request failed after retries.", lastError0);
        }

        ArrayList<SerpResult> parsed0 = tryParseJsonResults(responseBody0, query0, maxResults0);

        if (parsed0.size() > 0)
        {
            return parsed0;
        }

        return parseHtmlResults(responseBody0, query0, maxResults0);
    }

    private ArrayList<SerpResult> tryParseJsonResults(
        String responseBody0,
        String query0,
        int maxResults0)
    {
        ArrayList<SerpResult> results0 = new ArrayList<SerpResult>();

        try
        {
            JSONObject root0 = new JSONObject(responseBody0);

            JSONArray organic0 = root0.optJSONArray("organic");
            if (organic0 == null)
            {
                organic0 = root0.optJSONArray("organic_results");
            }
            if (organic0 == null)
            {
                organic0 = root0.optJSONArray("results");
            }

            if (organic0 == null)
            {
                return results0;
            }

            for (int i = 0; i < organic0.length() && results0.size() < maxResults0; i++)
            {
                JSONObject item0 = organic0.optJSONObject(i);
                if (item0 == null)
                {
                    continue;
                }

                String url0 = firstNonBlank(
                    item0.optString("url", ""),
                    item0.optString("link", ""),
                    item0.optString("display_link", "")
                );

                if (isBlank(url0))
                {
                    continue;
                }

                results0.add(new SerpResult(
                    item0.optString("title", ""),
                    cleanGoogleRedirectUrl(url0),
                    firstNonBlank(item0.optString("description", ""), item0.optString("snippet", ""), ""),
                    item0.optInt("global_rank", results0.size() + 1),
                    query0
                ));
            }
        }
        catch (Exception ignored0)
        {
        }

        return results0;
    }

    private ArrayList<SerpResult> parseHtmlResults(
        String html0,
        String query0,
        int maxResults0)
    {
        ArrayList<SerpResult> results0 = new ArrayList<SerpResult>();

        if (html0 == null)
        {
            return results0;
        }

        Pattern hrefPattern0 = Pattern.compile("href=\\\"([^\\\"]+)\\\"", Pattern.CASE_INSENSITIVE);
        Matcher matcher0 = hrefPattern0.matcher(html0);

        while (matcher0.find() && results0.size() < maxResults0)
        {
            String rawUrl0 = unescapeHtml(matcher0.group(1));
            String cleanedUrl0 = cleanGoogleRedirectUrl(rawUrl0);

            if (isUsefulUrl(cleanedUrl0))
            {
                results0.add(new SerpResult(
                    "",
                    cleanedUrl0,
                    "",
                    results0.size() + 1,
                    query0
                ));
            }
        }

        return results0;
    }

    private boolean isUsefulUrl(String url0)
    {
        if (isBlank(url0))
        {
            return false;
        }

        String lower0 = url0.toLowerCase();

        if (!lower0.startsWith("http"))
        {
            return false;
        }

        if (lower0.contains("google.com") || lower0.contains("gstatic.com"))
        {
            return false;
        }

        return true;
    }

    public static String cleanGoogleRedirectUrl(String url0)
    {
        if (url0 == null)
        {
            return "";
        }

        String value0 = url0.trim();

        if (value0.startsWith("/url?"))
        {
            int qIndex0 = value0.indexOf("q=");
            if (qIndex0 != -1)
            {
                String rest0 = value0.substring(qIndex0 + 2);
                int ampIndex0 = rest0.indexOf("&");
                if (ampIndex0 != -1)
                {
                    rest0 = rest0.substring(0, ampIndex0);
                }
                try
                {
                    return java.net.URLDecoder.decode(rest0, StandardCharsets.UTF_8);
                }
                catch (Exception ignored0)
                {
                    return rest0;
                }
            }
        }

        return value0;
    }

    private static String firstNonBlank(String a0, String b0, String c0)
    {
        if (!isBlank(a0)) return a0;
        if (!isBlank(b0)) return b0;
        if (!isBlank(c0)) return c0;
        return "";
    }

    private static String unescapeHtml(String value0)
    {
        if (value0 == null)
        {
            return "";
        }

        return value0
            .replace("&amp;", "&")
            .replace("&quot;", "\"")
            .replace("&#39;", "'");
    }

    private static String getEnvOrDefault(String name0, String defaultValue0)
    {
        String value0 = System.getenv(name0);
        return isBlank(value0) ? defaultValue0 : value0;
    }

    private static boolean isBlank(String value0)
    {
        return value0 == null || value0.trim().length() == 0;
    }
}
