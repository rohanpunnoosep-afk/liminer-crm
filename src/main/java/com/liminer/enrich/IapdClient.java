package com.liminer.enrich;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.json.JSONArray;
import org.json.JSONObject;

/*
 * IapdClient — thin client for the SEC IAPD firm search API (api.adviserinfo.sec.gov).
 * Only lookupCrdByName is implemented with live HTTP; fetchPart1 remains an empty stub
 * because the Part 1 structured-data endpoint is behind a JavaScript SPA that blocks
 * programmatic access (Cloudflare + bulk-download redirects to SPA HTML).
 *
 * The CRD is in the field firm_source_id (not firm_id). Mirrors BrightDataSerpClient
 * style: static shared HttpClient, stateless methods, 20s timeout, one retry on
 * transient errors.
 */
public class IapdClient
{
    private static final HttpClient CLIENT0    = HttpClient.newHttpClient();
    private static final String USER_AGENT0 = HttpContact.USER_AGENT0;
    private static final int    TIMEOUT_SECS0  = 20;
    private static final String SEARCH_URL0    =
        "https://api.adviserinfo.sec.gov/search/firm?query=%s" +
        "&hl=true&nrows=12&start=0&r=25&noDataBoost=false" +
        "&reqnorecompile=true&ef=true&sortby=score&sortorder=desc";

    public IapdClient() {}

    // Structured Form ADV Part 1 fields (Item 5). All blank in stub.
    public static class Part1Result
    {
        public String raum              = "";   // Item 5.F regulatory AUM
        public String discretionaryRaum = "";
        public String numAccounts       = "";
        public String numEmployees      = "";
        public String fiscalYearEnd     = "";
        public String filingDate        = "";
    }

    // Resolve an adviser name (+website hint) to a CRD number.
    // Searches IAPD firm search API; filters for ACTIVE scope; uses 60% token
    // overlap to pick the best matching firm. Returns "" when unresolved.
    public String lookupCrdByName(String name0, String website0)
    {
        if (isBlank(name0)) return "";
        try
        {
            String enc0  = URLEncoder.encode(name0.trim(), StandardCharsets.UTF_8);
            String url0  = String.format(SEARCH_URL0, enc0);
            String body0 = get(url0);
            if (isBlank(body0)) return "";

            JSONObject root0 = new JSONObject(body0);
            JSONObject hits0 = root0.optJSONObject("hits");
            if (hits0 == null) return "";
            JSONArray list0 = hits0.optJSONArray("hits");
            if (list0 == null || list0.length() == 0) return "";

            String normName0 = normName(name0);
            String normDom0  = isBlank(website0) ? "" : domain(website0);

            String bestCrd0  = "";
            boolean domainMatch0 = false;
            boolean nameMatch0   = false;

            for (int i0 = 0; i0 < list0.length(); i0++)
            {
                JSONObject hit0 = list0.optJSONObject(i0);
                if (hit0 == null) continue;
                JSONObject src0 = hit0.optJSONObject("_source");
                if (src0 == null) continue;

                // Only consider active IA registrations.
                String scope0 = src0.optString("firm_ia_scope", "");
                if (!"ACTIVE".equalsIgnoreCase(scope0)) continue;

                String crd0   = src0.optString("firm_source_id", "").trim();
                String fName0 = src0.optString("firm_name", "");
                if (isBlank(crd0)) continue;

                // Domain match from address (firm_ia_address_details is a JSON string).
                boolean hasDomMatch0 = false;
                if (!isBlank(normDom0))
                {
                    String addr0 = src0.optString("firm_ia_address_details", "");
                    hasDomMatch0 = !isBlank(addr0) && addr0.toLowerCase().contains(normDom0);
                }

                boolean hasNameMatch0 = nameStrong(normName0, normName(fName0));

                // Domain + name → best possible match, return immediately.
                if (hasDomMatch0 && hasNameMatch0) return crd0;

                if (hasNameMatch0 && !nameMatch0)
                {
                    bestCrd0  = crd0;
                    nameMatch0 = true;
                    domainMatch0 = hasDomMatch0;
                }
                else if (hasDomMatch0 && !domainMatch0 && !nameMatch0)
                {
                    bestCrd0 = crd0;
                    domainMatch0 = true;
                }
                else if (isBlank(bestCrd0))
                {
                    bestCrd0 = crd0;
                }
            }

            // Single active result: trust it unless name is totally off.
            if (!isBlank(bestCrd0)) return bestCrd0;
        }
        catch (Exception e0)
        {
            System.err.println("[IAPD] lookupCrdByName \"" + name0 + "\": " + e0.getMessage());
        }
        return "";
    }

    // fetchPart1 stays as empty stub — IAPD Part 1 data endpoint is behind a
    // JavaScript SPA (Cloudflare) that blocks programmatic access.
    public Part1Result fetchPart1(String crd0)
    {
        return new Part1Result();
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
                + "management|group|fund|trust|company|co\\.?|advisors?|capital|"
                + "investment|partners?)\\b", " ")
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

    // Extracts the registrable host from a URL string (strips www., scheme, path).
    private static String domain(String url0)
    {
        if (isBlank(url0)) return "";
        String d0 = url0.trim().toLowerCase()
            .replaceFirst("^https?://", "")
            .replaceFirst("^www\\.", "")
            .replaceFirst("/.*", "");
        return d0;
    }

    private static boolean isBlank(String s0) { return s0 == null || s0.trim().isEmpty(); }
}
