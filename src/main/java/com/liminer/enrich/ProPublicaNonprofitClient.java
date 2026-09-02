package com.liminer.enrich;

import com.liminer.scout.ScoutUniverseRecord;

import java.net.URI;
import java.net.URLEncoder;
import java.time.Duration;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;

/*
 * ProPublicaNonprofitClient — thin client for the free ProPublica Nonprofit Explorer
 * API (IRS Form 990 / 990-PF). Keys on EIN resolved by IdentityResolver.
 * Mirrors BrightDataSerpClient style: static shared HttpClient, stateless methods,
 * 20s timeout, one retry on 502/503/429/timeout.
 */
public class ProPublicaNonprofitClient
{
    private static final HttpClient CLIENT0 = HttpClient.newHttpClient();
    private static final String USER_AGENT0 = HttpContact.USER_AGENT0;
    private static final int TIMEOUT_SECS0  = 20;
    private static final String BASE0 = "https://projects.propublica.org/nonprofits/api/v2";

    public ProPublicaNonprofitClient() {}

    // Balance-sheet figures from the most recent 990 / 990-PF.
    public static class Form990Result
    {
        public String totalAssets      = "";   // totassetsend
        public String investments      = "";   // not in summary extract; left blank
        public String cash             = "";   // not in summary extract; left blank
        public String investmentIncome = "";   // invstmntinc
        public String taxYear          = "";   // tax_prd_yr formatted as YYYY-12-31
        public String filingDate       = "";   // updated field (ISO date prefix)
        public String url              = "";
    }

    // Resolve an organization name to an EIN. Returns "" when unresolved.
    public String lookupEinByName(String name0)
    {
        if (isBlank(name0)) return "";
        try
        {
            String enc0  = URLEncoder.encode(name0.trim(), StandardCharsets.UTF_8);
            String body0 = get(BASE0 + "/search.json?q=" + enc0);
            if (isBlank(body0)) return "";

            JSONArray orgs0 = new JSONObject(body0).optJSONArray("organizations");
            if (orgs0 == null || orgs0.length() == 0) return "";

            String normTarget0 = normName(name0);
            String bestEin0    = "";
            boolean strongMatch0 = false;

            for (int i0 = 0; i0 < orgs0.length(); i0++)
            {
                JSONObject org0 = orgs0.optJSONObject(i0);
                if (org0 == null) continue;
                long ein0 = org0.optLong("ein", -1L);
                if (ein0 <= 0) continue;

                boolean strong0 = nameStrong(normTarget0, normName(org0.optString("name", "")));
                if (strong0 && !strongMatch0)
                {
                    bestEin0    = String.valueOf(ein0);
                    strongMatch0 = true;
                }
                else if (isBlank(bestEin0))
                {
                    bestEin0 = String.valueOf(ein0);
                }
            }
            return bestEin0;
        }
        catch (Exception e0)
        {
            System.err.println("[ProPublica] lookupEinByName \"" + name0 + "\": " + e0.getMessage());
            return "";
        }
    }

    // Fetch the latest 990 balance-sheet figures for an EIN.
    public Form990Result fetch990(String ein0)
    {
        Form990Result r0 = new Form990Result();
        if (isBlank(ein0)) return r0;
        try
        {
            String clean0 = ein0.trim().replaceAll("[^0-9]", "");
            if (clean0.isEmpty()) return r0;

            String body0 = get(BASE0 + "/organizations/" + clean0 + ".json");
            if (isBlank(body0)) return r0;

            JSONArray filings0 = new JSONObject(body0).optJSONArray("filings_with_data");
            if (filings0 == null || filings0.length() == 0) return r0;

            JSONObject f0 = filings0.optJSONObject(0);
            if (f0 == null) return r0;

            if (f0.has("totassetsend"))
                r0.totalAssets = String.valueOf(f0.optLong("totassetsend", 0));

            if (f0.has("invstmntinc"))
            {
                long inc0 = f0.optLong("invstmntinc", 0);
                if (inc0 != 0) r0.investmentIncome = String.valueOf(inc0);
            }

            int yr0 = f0.optInt("tax_prd_yr", -1);
            if (yr0 > 1990) r0.taxYear = yr0 + "-12-31";

            String upd0 = f0.optString("updated", "");
            if (!isBlank(upd0) && upd0.length() >= 10)
                r0.filingDate = upd0.substring(0, 10);

            r0.url = "https://projects.propublica.org/nonprofits/organizations/" + clean0;
        }
        catch (Exception e0)
        {
            System.err.println("[ProPublica] fetch990 EIN=" + ein0 + ": " + e0.getMessage());
        }
        return r0;
    }

    // -----------------------------------------------------------------------
    // Discovery (NTEE-category + state search) — additive to the per-name
    // lookup above; used to populate the Scout universe with foundation /
    // endowment candidates instead of only resolving a known name to an EIN.
    // -----------------------------------------------------------------------

    /**
     * Live fetch: ProPublica /search.json filtered by NTEE major-group code
     * (e.g. "T" for philanthropy) and/or two-letter state, paginated via
     * page0 (0-based, ProPublica returns 25 results per page).
     */
    public String fetchDiscoverResults(String nteeCode0, String state0, int page0) throws Exception
    {
        StringBuilder url0 = new StringBuilder(BASE0 + "/search.json?q=");
        if (!isBlank(nteeCode0))
            url0.append("&ntee[id]=").append(URLEncoder.encode(nteeCode0.trim(), StandardCharsets.UTF_8));
        if (!isBlank(state0))
            url0.append("&state[id]=").append(URLEncoder.encode(state0.trim(), StandardCharsets.UTF_8));
        if (page0 > 0)
            url0.append("&page=").append(page0);
        return get(url0.toString());
    }

    /**
     * Pure mapper: /search.json response body -> discovery candidate
     * ScoutUniverseRecords (crd stays 0; externalRegisterId is the EIN;
     * sourceRegister is "IRS_990"). Organizations missing an EIN are skipped.
     * Empty/absent "organizations" -> empty list.
     */
    public List<ScoutUniverseRecord> mapDiscoveryResults(String searchJson0)
    {
        List<ScoutUniverseRecord> out0 = new ArrayList<ScoutUniverseRecord>();
        if (isBlank(searchJson0)) return out0;

        JSONArray orgs0 = new JSONObject(searchJson0).optJSONArray("organizations");
        if (orgs0 == null) return out0;

        for (int i0 = 0; i0 < orgs0.length(); i0++)
        {
            JSONObject org0 = orgs0.optJSONObject(i0);
            if (org0 == null) continue;
            long ein0 = org0.optLong("ein", -1L);
            if (ein0 <= 0) continue;

            ScoutUniverseRecord rec0 = new ScoutUniverseRecord();
            rec0.externalRegisterId = String.valueOf(ein0);
            rec0.sourceRegister = "IRS_990";
            rec0.firmName = org0.optString("name", "");
            rec0.city = org0.optString("city", "");
            rec0.state = org0.optString("state", "");
            rec0.country = "United States";
            String ntee0 = org0.has("ntee_code") ? String.valueOf(org0.get("ntee_code")) : "";
            rec0.nteeCode = "null".equals(ntee0) ? "" : ntee0;
            out0.add(rec0);
        }
        return out0;
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
                + "management|group|fund|trust|company|co\\.?)\\b", " ")
            .replaceAll("[^a-z0-9 ]", " ")
            .replaceAll("\\s+", " ")
            .trim();
    }

    // True if normalized names share ≥60% of the shorter name's tokens.
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
