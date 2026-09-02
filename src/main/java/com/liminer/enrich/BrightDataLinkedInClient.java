package com.liminer.enrich;

import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import org.json.JSONArray;
import org.json.JSONObject;

/*
 * BrightDataLinkedInClient scrapes LinkedIn profile/company URLs through Bright Data.
 *
 * Bright Data's LinkedIn Scraper API uses:
 *   POST https://api.brightdata.com/datasets/v3/scrape?dataset_id=...&format=json
 *   Body: [{"url":"https://www.linkedin.com/..."}]
 *
 * Defaults:
 *   Profile dataset: gd_l1viktl72bvl7bjuj0
 *   Company dataset: gd_l1vikfnt1wgvvqz95w
 *
 * Required env var:
 *   BRIGHT_DATA_API_TOKEN
 *
 * Optional env vars if Bright Data changes your endpoint IDs:
 *   BRIGHT_DATA_LINKEDIN_PROFILE_DATASET_ID
 *   BRIGHT_DATA_LINKEDIN_COMPANY_DATASET_ID
 */
public class BrightDataLinkedInClient
{
    private static final String API_TOKEN0 = System.getenv("BRIGHT_DATA_API_TOKEN");
    private static final String PROFILE_DATASET_ID0 = getEnvOrDefault(
        "BRIGHT_DATA_LINKEDIN_PROFILE_DATASET_ID",
        "gd_l1viktl72bvl7bjuj0"
    );
    private static final String COMPANY_DATASET_ID0 = getEnvOrDefault(
        "BRIGHT_DATA_LINKEDIN_COMPANY_DATASET_ID",
        "gd_l1vikfnt1wgvvqz95w"
    );

    private static final HttpClient CLIENT0 = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(15))
            .build();

    public BrightDataLinkedInClient()
    {
    }

    public LinkedInScrapeResult scrape(DiscoveredLinkedInTarget target0) throws Exception
    {
        if (target0 == null || isBlank(target0.url))
        {
            return new LinkedInScrapeResult();
        }

        if (target0.isCompany())
        {
            return scrapeCompany(target0.url);
        }

        return scrapeProfile(target0.url);
    }

    public LinkedInScrapeResult scrapeProfile(String linkedinProfileUrl0) throws Exception
    {
        return scrapeUrl(
            linkedinProfileUrl0,
            DiscoveredLinkedInTarget.TYPE_PERSON,
            PROFILE_DATASET_ID0
        );
    }

    public LinkedInScrapeResult scrapeCompany(String linkedinCompanyUrl0) throws Exception
    {
        return scrapeUrl(
            linkedinCompanyUrl0,
            DiscoveredLinkedInTarget.TYPE_COMPANY,
            COMPANY_DATASET_ID0
        );
    }

    private LinkedInScrapeResult scrapeUrl(
        String linkedinUrl0,
        String targetType0,
        String datasetId0) throws Exception
    {
        if (isBlank(API_TOKEN0))
        {
            throw new RuntimeException("Missing BRIGHT_DATA_API_TOKEN environment variable.");
        }

        if (isBlank(linkedinUrl0))
        {
            return new LinkedInScrapeResult();
        }

        JSONArray inputArray0 = new JSONArray();
        inputArray0.put(new JSONObject().put("url", linkedinUrl0));

        String endpoint0 = "https://api.brightdata.com/datasets/v3/scrape?dataset_id="
            + datasetId0
            + "&format=json";

        HttpRequest request0 = HttpRequest.newBuilder()
            .uri(URI.create(endpoint0))
            .timeout(Duration.ofSeconds(45))
            .header("Content-Type", "application/json")
            .header("Authorization", "Bearer " + API_TOKEN0)
            .POST(HttpRequest.BodyPublishers.ofString(inputArray0.toString()))
            .build();

        // Up to 2 attempts: retry once on transient 502/503/429 or a timeout.
        // LinkedIn scrapes are genuinely slower than SERP, so the timeout is higher.
        String body0 = null;
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
                    lastError0 = new RuntimeException("transient LinkedIn status " + status0);
                    Thread.sleep(500L * attempt0);
                    continue;
                }
                if (status0 < 200 || status0 >= 300)
                {
                    throw new RuntimeException(
                        "Bright Data LinkedIn scrape failed. Status: " + status0
                        + ". Body: " + response0.body());
                }
                body0 = response0.body();
                break;
            }
            catch (java.net.http.HttpTimeoutException timeout0)
            {
                BrightDataThrottle.noteThrottle();
                lastError0 = timeout0; // retry
            }
        }

        if (body0 == null)
        {
            throw new RuntimeException("Bright Data LinkedIn scrape failed after retries.", lastError0);
        }
        JSONObject firstObject0 = extractFirstObject(body0);

        return LinkedInScrapeResult.fromJson(linkedinUrl0, targetType0, firstObject0);
    }

    private JSONObject extractFirstObject(String body0)
    {
        if (isBlank(body0))
        {
            return new JSONObject();
        }

        try
        {
            JSONArray array0 = new JSONArray(body0);
            if (array0.length() == 0)
            {
                return new JSONObject();
            }
            return array0.getJSONObject(0);
        }
        catch (Exception ignored0)
        {
        }

        try
        {
            JSONObject object0 = new JSONObject(body0);

            if (object0.has("data"))
            {
                JSONArray data0 = object0.optJSONArray("data");
                if (data0 != null && data0.length() > 0)
                {
                    return data0.getJSONObject(0);
                }
            }

            return object0;
        }
        catch (Exception ignored0)
        {
        }

        return new JSONObject().put("raw_response", body0);
    }

    public ArrayList<LinkedInScrapeResult> scrapeMany(ArrayList<DiscoveredLinkedInTarget> targets0)
    {
        ArrayList<LinkedInScrapeResult> results0 = new ArrayList<LinkedInScrapeResult>();

        if (targets0 == null)
        {
            return results0;
        }

        for (DiscoveredLinkedInTarget target0 : targets0)
        {
            try
            {
                results0.add(scrape(target0));
            }
            catch (Exception exception0)
            {
                System.out.println("LinkedIn scrape failed for " + target0.url + ": " + exception0.getMessage());
            }
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
