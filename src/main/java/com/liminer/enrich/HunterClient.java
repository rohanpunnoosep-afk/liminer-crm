package com.liminer.enrich;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import org.json.JSONObject;

/*
 * HunterClient — thin HTTP client for a Hunter.io/Apollo-class domain-search
 * commercial email-enrichment API. Same env-var configuration pattern as
 * EmailVerifierClient/BrightDataLinkedInClient (HUNTER_API_KEY / optional
 * HUNTER_URL override). Skips silently (returns "") when unconfigured, so
 * EmailFinder's layer 4 is a no-op rather than a failure when no key is set.
 *
 * findPersonEmail() is not final so tests can subclass and override it with a
 * fake, without ever hitting the network or requiring an API key.
 */
public class HunterClient
{
    private static final String API_KEY0 = System.getenv("HUNTER_API_KEY");
    private static final String BASE_URL0 = getEnvOrDefault("HUNTER_URL", "https://api.hunter.io/v2/email-finder");

    private static final HttpClient CLIENT0 = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(15))
        .build();

    public boolean isConfigured()
    {
        return !isBlank(API_KEY0);
    }

    /*
     * Looks up a single person's email at domain0 by first/last name. Returns
     * "" when unconfigured, when the domain/names are blank, or when Hunter
     * has no confident match — never throws for a normal "not found" outcome.
     */
    public String findPersonEmail(String domain0, String firstName0, String lastName0) throws Exception
    {
        if (!isConfigured() || isBlank(domain0) || isBlank(firstName0) || isBlank(lastName0))
        {
            return "";
        }

        String endpoint0 = BASE_URL0
            + "?domain=" + java.net.URLEncoder.encode(domain0.trim(), "UTF-8")
            + "&first_name=" + java.net.URLEncoder.encode(firstName0.trim(), "UTF-8")
            + "&last_name=" + java.net.URLEncoder.encode(lastName0.trim(), "UTF-8")
            + "&api_key=" + java.net.URLEncoder.encode(API_KEY0, "UTF-8");

        HttpRequest request0 = HttpRequest.newBuilder()
            .uri(URI.create(endpoint0))
            .timeout(Duration.ofSeconds(20))
            .GET()
            .build();

        HttpResponse<String> response0 = CLIENT0.send(request0, HttpResponse.BodyHandlers.ofString());

        if (response0.statusCode() < 200 || response0.statusCode() >= 300)
        {
            return "";
        }

        return parseEmail(response0.body());
    }

    private String parseEmail(String body0)
    {
        if (isBlank(body0))
        {
            return "";
        }

        try
        {
            JSONObject json0 = new JSONObject(body0);
            JSONObject data0 = json0.optJSONObject("data");
            if (data0 == null)
            {
                return "";
            }
            return data0.optString("email", "");
        }
        catch (Exception ignored0)
        {
            return "";
        }
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
