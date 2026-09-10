package com.liminer.enrich;

import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.json.JSONObject;

/*
 * DuckDuckGoUnlockerProvider runs DuckDuckGo HTML searches through Bright Data's
 * Web Unlocker zone, not the SERP zone. The SERP zone is what Google's late-July
 * 2026 /goto rollout broke (see BrightDataZoneHealth); the unlocker zone is a
 * separate, healthy product that simply fetches a page through Bright Data's
 * unblocking layer, with no SERP-specific parsing involved. DDG returns a 202
 * challenge page to an unproxied fetch, so the unlocker hop is mandatory.
 *
 * Registered last in SearchRouter.shared(): DDG is largely Bing's index under
 * its own ranking, not a Google-quality substitute for Liminer's site:-heavy
 * enrichment queries, so it is free fallback insurance rather than a primary.
 *
 * Required env var:
 *   BRIGHT_DATA_API_TOKEN
 *
 * Optional env var:
 *   BRIGHT_DATA_UNLOCKER_ZONE   defaults to web_unlocker2
 */
public class DuckDuckGoUnlockerProvider implements SearchProvider
{
    private static final String BRIGHT_DATA_API_TOKEN0 = System.getenv("BRIGHT_DATA_API_TOKEN");
    private static final String BRIGHT_DATA_UNLOCKER_ZONE0 =
        getEnvOrDefault("BRIGHT_DATA_UNLOCKER_ZONE", "web_unlocker2");

    private static final Pattern UDDG_PATTERN0 = Pattern.compile("uddg=([^&\"]+)");

    @Override
    public String name()
    {
        return "duckduckgo";
    }

    @Override
    public boolean isAvailable()
    {
        // Deliberately does not check BrightDataZoneHealth.isHealthy(): that latch
        // tracks the broken SERP zone, and this provider's whole point is to keep
        // working through the unlocker zone while the SERP zone stays latched dead.
        return !isBlank(BRIGHT_DATA_API_TOKEN0);
    }

    @Override
    public ArrayList<SerpResult> search(String query0, int maxResults0) throws Exception
    {
        if (isBlank(query0))
        {
            return new ArrayList<SerpResult>();
        }

        String ddgUrl0 = "https://html.duckduckgo.com/html/?q="
            + URLEncoder.encode(query0, StandardCharsets.UTF_8);

        JSONObject requestBody0 = new JSONObject();
        requestBody0.put("zone", BRIGHT_DATA_UNLOCKER_ZONE0);
        requestBody0.put("url", ddgUrl0);
        requestBody0.put("format", "raw");

        // Billed as "duckduckgo", not "brightdata", and not gated by (or able to latch)
        // the global SERP zone health check -- see BrightDataHttp.postIndependentOfZoneHealth.
        BrightDataHttp.Result response0 = BrightDataHttp.postIndependentOfZoneHealth(
            "https://api.brightdata.com/request",
            requestBody0.toString(),
            BRIGHT_DATA_API_TOKEN0,
            BRIGHT_DATA_UNLOCKER_ZONE0,
            "duckduckgo");

        if (response0.status < 200 || response0.status >= 300)
        {
            throw new RuntimeException("duckduckgo: HTTP status " + response0.status);
        }

        if (isBlank(response0.body))
        {
            throw new RuntimeException("duckduckgo: empty response body");
        }

        LinkedHashSet<String> seen0 = new LinkedHashSet<String>();
        ArrayList<SerpResult> results0 = new ArrayList<SerpResult>();

        Matcher matcher0 = UDDG_PATTERN0.matcher(response0.body);
        while (matcher0.find() && results0.size() < maxResults0)
        {
            String destination0 = URLDecoder.decode(matcher0.group(1), StandardCharsets.UTF_8);

            if (!SerpUrls.isAbsoluteHttpUrl(destination0) || !SerpUrls.isUsefulUrl(destination0, "duckduckgo.com"))
            {
                continue;
            }

            if (!seen0.add(destination0))
            {
                continue;
            }

            results0.add(new SerpResult("", destination0, "", results0.size() + 1, query0));
        }

        return results0;
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
