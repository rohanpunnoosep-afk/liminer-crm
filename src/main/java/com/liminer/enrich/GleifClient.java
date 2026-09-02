package com.liminer.enrich;

import java.net.URI;
import java.net.URLEncoder;
import java.time.Duration;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import org.json.JSONArray;
import org.json.JSONObject;

/*
 * GleifClient — thin client for the free GLEIF LEI registry API (Legal Entity
 * Identifiers + ultimate-parent graph). Mirrors BrightDataSerpClient style:
 * static shared HttpClient, stateless methods, 20s timeout, one retry on
 * 502/503/429/timeout.
 *
 * Accepts the JSON:API content type that GLEIF requires.
 */
public class GleifClient
{
    private static final HttpClient CLIENT0    = HttpClient.newHttpClient();
    private static final String USER_AGENT0 = HttpContact.USER_AGENT0;
    private static final int    TIMEOUT_SECS0  = 20;
    private static final String BASE0          = "https://api.gleif.org/api/v1";

    public GleifClient() {}

    // Ultimate-parent relationship for an LEI.
    public static class ParentResult
    {
        public String parentLei         = "";
        public String parentName        = "";
        public String relationshipType  = "";
        public String lastUpdateDate    = "";   // asOfDate source
    }

    // Resolve an entity name to its LEI. Returns "" when unresolved.
    public String lookupLei(String name0)
    {
        if (isBlank(name0)) return "";
        try
        {
            String enc0  = URLEncoder.encode(name0.trim(), StandardCharsets.UTF_8);
            String url0  = BASE0 + "/lei-records?filter[entity.legalName]=" + enc0 + "&page[size]=5";
            String body0 = get(url0);
            if (isBlank(body0)) return "";

            JSONObject root0 = new JSONObject(body0);
            JSONArray  data0 = root0.optJSONArray("data");
            if (data0 == null || data0.length() == 0) return "";

            String normTarget0 = normName(name0);

            for (int i0 = 0; i0 < data0.length(); i0++)
            {
                JSONObject rec0 = data0.optJSONObject(i0);
                if (rec0 == null) continue;
                String lei0 = rec0.optString("id", "");
                if (isBlank(lei0)) continue;

                String legalName0 = extractLegalName(rec0);
                if (nameStrong(normTarget0, normName(legalName0)))
                    return lei0;
            }

            // Single-result fallback — less risk of wrong entity.
            if (data0.length() == 1)
            {
                JSONObject only0 = data0.optJSONObject(0);
                if (only0 != null) return only0.optString("id", "");
            }
        }
        catch (Exception e0)
        {
            System.err.println("[GLEIF] lookupLei \"" + name0 + "\": " + e0.getMessage());
        }
        return "";
    }

    // Fetch the ultimate-parent relationship for an LEI.
    public ParentResult ultimateParent(String lei0)
    {
        ParentResult r0 = new ParentResult();
        if (isBlank(lei0)) return r0;
        try
        {
            String url0  = BASE0 + "/lei-records/" + lei0.trim() + "/ultimate-parent-relationship";
            String body0 = get(url0);
            if (isBlank(body0)) return r0;   // 404 → no parent on record

            JSONObject root0  = new JSONObject(body0);
            JSONObject data0  = root0.optJSONObject("data");
            if (data0 == null) return r0;

            JSONObject attrs0 = data0.optJSONObject("attributes");
            if (attrs0 == null) return r0;

            JSONObject rel0 = attrs0.optJSONObject("relationship");
            if (rel0 != null)
            {
                JSONObject end0 = rel0.optJSONObject("endNode");
                if (end0 != null) r0.parentLei = end0.optString("id", "");
                r0.relationshipType = rel0.optString("relationshipType", "");
            }

            JSONObject reg0 = attrs0.optJSONObject("registration");
            if (reg0 != null)
            {
                String upd0 = reg0.optString("lastUpdateDate", "");
                if (!isBlank(upd0) && upd0.length() >= 10)
                    r0.lastUpdateDate = upd0.substring(0, 10);
            }

            // Resolve parent name with a second call.
            if (!isBlank(r0.parentLei))
            {
                String parentBody0 = get(BASE0 + "/lei-records/" + r0.parentLei);
                if (!isBlank(parentBody0))
                {
                    JSONObject pRoot0 = new JSONObject(parentBody0);
                    JSONObject pData0 = pRoot0.optJSONObject("data");
                    if (pData0 != null) r0.parentName = extractLegalName(pData0);
                }
            }
        }
        catch (Exception e0)
        {
            System.err.println("[GLEIF] ultimateParent lei=" + lei0 + ": " + e0.getMessage());
        }
        return r0;
    }

    // -----------------------------------------------------------------------
    // Parsing helpers
    // -----------------------------------------------------------------------

    private static String extractLegalName(JSONObject record0)
    {
        try
        {
            JSONObject attrs0 = record0.optJSONObject("attributes");
            if (attrs0 == null) return "";
            JSONObject entity0 = attrs0.optJSONObject("entity");
            if (entity0 == null) return "";
            JSONObject ln0 = entity0.optJSONObject("legalName");
            if (ln0 != null) return ln0.optString("name", "");
        }
        catch (Exception ignored0) {}
        return "";
    }

    // -----------------------------------------------------------------------
    // HTTP
    // -----------------------------------------------------------------------

    private String get(String url0) throws Exception
    {
        HttpRequest req0 = HttpRequest.newBuilder()
            .uri(URI.create(url0))
            .timeout(Duration.ofSeconds(TIMEOUT_SECS0))
            .header("User-Agent", USER_AGENT0)
            .header("Accept", "application/vnd.api+json")
            .GET()
            .build();

        Exception last0 = null;
        for (int attempt0 = 1; attempt0 <= 2; attempt0++)
        {
            try
            {
                HttpResponse<String> resp0 = CLIENT0.send(req0, HttpResponse.BodyHandlers.ofString());
                int status0 = resp0.statusCode();
                if (status0 == 502 || status0 == 503 || status0 == 429)
                {
                    last0 = new RuntimeException("transient HTTP " + status0);
                    Thread.sleep(400L * attempt0);
                    continue;
                }
                if (status0 == 404) return "";
                if (status0 < 200 || status0 >= 300)
                    throw new RuntimeException("HTTP " + status0 + " for " + url0);
                return resp0.body();
            }
            catch (java.net.http.HttpTimeoutException te0) { last0 = te0; }
        }
        if (last0 != null) throw last0;
        return "";
    }

    // -----------------------------------------------------------------------
    // Name matching
    // -----------------------------------------------------------------------

    private static String normName(String s0)
    {
        if (isBlank(s0)) return "";
        return s0.toLowerCase()
            .replaceAll("\\b(inc\\.?|llc\\.?|lp\\.?|llp\\.?|corp\\.?|ltd\\.?|foundation|"
                + "management|group|fund|trust|company|co\\.?)\\b", " ")
            .replaceAll("[^a-z0-9 ]", " ")
            .replaceAll("\\s+", " ")
            .trim();
    }

    private static boolean nameStrong(String a0, String b0)
    {
        if (isBlank(a0) || isBlank(b0)) return false;
        if (a0.equals(b0)) return true;
        String[] ta0 = a0.split(" ");
        String[] tb0 = b0.split(" ");
        int common0 = 0;
        for (String x0 : ta0)
            for (String y0 : tb0)
                if (x0.equals(y0)) { common0++; break; }
        int min0 = Math.min(ta0.length, tb0.length);
        return min0 > 0 && (double) common0 / min0 >= 0.6;
    }

    private static boolean isBlank(String s0) { return s0 == null || s0.trim().isEmpty(); }
}
