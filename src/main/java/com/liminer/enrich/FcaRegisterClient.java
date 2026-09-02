package com.liminer.enrich;

import com.liminer.scout.ScoutUniverseRecord;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;

/*
 * FcaRegisterClient — UK Financial Conduct Authority Register API client
 * (register.fca.org.uk, "FCA Register API"). Mirrors AdvBulkClient's split
 * between a network-fetch layer and a pure, offline-testable mapper.
 *
 * The Register API requires a free API key (X-Auth-Email/X-Auth-Key headers),
 * never hardcoded — read from env vars FCA_API_EMAIL/FCA_API_KEY. Firm detail
 * and firm permissions are two separate endpoints
 * (/Firm/{FRN} and /Firm/{FRN}/Permissions); mapFirm merges both responses
 * into one ScoutUniverseRecord, filtering to firms whose permissions include
 * "managing an AIF" (case-insensitive) — non-AIF-manager firms are skipped
 * (mapFirm returns null), not an error.
 *
 * Field names/shape follow the documented FCA Register API v0.1 response
 * envelope ({"Data": [...]}, case-preserved keys like "FRN",
 * "Organisation Name"); like AdvBulkClient's ADV CSV headers, exact key
 * casing/spelling can vary, so lookups are case-insensitive substring
 * matches on key names, throwing loudly (naming the missing field) when a
 * required key cannot be found.
 */
public class FcaRegisterClient
{
    private static final HttpClient CLIENT0 = HttpClient.newHttpClient();
    private static final String USER_AGENT0 = HttpContact.USER_AGENT0;
    private static final int TIMEOUT_SECS0 = 30;
    private static final String BASE0 = "https://register.fca.org.uk/services/V0.1";

    public FcaRegisterClient() {}

    // -----------------------------------------------------------------------
    // Public API
    // -----------------------------------------------------------------------

    /** Live fetch: firm detail JSON body for a Firm Reference Number (FRN). */
    public String fetchFirmDetails(String frn0) throws Exception
    {
        if (isBlank(frn0)) throw new IllegalArgumentException("frn0 is required");
        return get(BASE0 + "/Firm/" + frn0.trim());
    }

    /** Live fetch: firm permissions JSON body for a Firm Reference Number (FRN). */
    public String fetchFirmPermissions(String frn0) throws Exception
    {
        if (isBlank(frn0)) throw new IllegalArgumentException("frn0 is required");
        return get(BASE0 + "/Firm/" + frn0.trim() + "/Permissions");
    }

    /**
     * Pure mapper: firm-detail JSON + firm-permissions JSON -> a
     * ScoutUniverseRecord, or null when the firm does not hold a "managing an
     * AIF" permission. Throws IllegalArgumentException naming the missing
     * field when a required firm-detail field cannot be found.
     */
    public ScoutUniverseRecord mapFirm(String firmJson0, String permissionsJson0)
    {
        JSONObject firmRoot0 = new JSONObject(firmJson0);
        JSONArray firmData0 = firmRoot0.optJSONArray("Data");
        if (firmData0 == null || firmData0.isEmpty())
            throw new IllegalArgumentException("FcaRegisterClient: firm JSON has no \"Data\" entries");
        JSONObject firm0 = firmData0.getJSONObject(0);

        String frn0 = requireField(firm0, "frn");
        String orgName0 = requireField(firm0, "organisation name");
        String website0 = optionalField(firm0, "website");
        String town0 = optionalField(firm0, "town");
        String country0 = optionalField(firm0, "country");
        if (isBlank(country0)) country0 = "United Kingdom";

        List<String> permissions0 = parsePermissions(permissionsJson0);
        boolean managesAif0 = false;
        for (String p0 : permissions0)
        {
            String lower0 = p0.toLowerCase();
            if (lower0.contains("managing") && lower0.contains("aif")) { managesAif0 = true; break; }
        }
        if (!managesAif0) return null;

        ScoutUniverseRecord rec0 = new ScoutUniverseRecord();
        rec0.crd = parseIntSafe(frn0);
        rec0.externalRegisterId = frn0;
        rec0.sourceRegister = "FCA";
        rec0.firmName = orgName0;
        rec0.website = website0;
        rec0.city = town0;
        rec0.country = country0;
        rec0.clientTypes = permissions0;
        return rec0;
    }

    /**
     * Pure parser: firm-permissions JSON -> list of permission label strings.
     * Throws IllegalArgumentException naming the missing field when a
     * permission entry has no "permission" key. Empty/absent "Data" -> empty list.
     */
    public List<String> parsePermissions(String permissionsJson0)
    {
        List<String> out0 = new ArrayList<String>();
        if (isBlank(permissionsJson0)) return out0;

        JSONObject root0 = new JSONObject(permissionsJson0);
        JSONArray data0 = root0.optJSONArray("Data");
        if (data0 == null) return out0;

        for (int i0 = 0; i0 < data0.length(); i0++)
        {
            JSONObject entry0 = data0.optJSONObject(i0);
            if (entry0 == null) continue;
            out0.add(requireField(entry0, "permission"));
        }
        return out0;
    }

    // -----------------------------------------------------------------------
    // HTTP
    // -----------------------------------------------------------------------

    private String get(String url0) throws Exception
    {
        String email0 = System.getenv("FCA_API_EMAIL");
        String key0 = System.getenv("FCA_API_KEY");
        if (isBlank(email0) || isBlank(key0))
            throw new IllegalStateException("FcaRegisterClient: FCA_API_EMAIL/FCA_API_KEY env vars are required");

        HttpRequest req0 = HttpRequest.newBuilder()
            .uri(URI.create(url0))
            .timeout(Duration.ofSeconds(TIMEOUT_SECS0))
            .header("User-Agent", USER_AGENT0)
            .header("X-Auth-Email", email0)
            .header("X-Auth-Key", key0)
            .GET()
            .build();

        HttpResponse<String> resp0 = CLIENT0.send(req0, HttpResponse.BodyHandlers.ofString());
        int status0 = resp0.statusCode();
        if (status0 < 200 || status0 >= 300)
            throw new RuntimeException("HTTP " + status0 + " for " + url0);
        return resp0.body();
    }

    // -----------------------------------------------------------------------
    // Field resolution helpers (case-insensitive key match)
    // -----------------------------------------------------------------------

    private static String findField(JSONObject obj0, String keyword0)
    {
        String kw0 = keyword0.toLowerCase();
        for (String key0 : obj0.keySet())
        {
            if (key0 != null && key0.toLowerCase().contains(kw0)) return obj0.optString(key0, "");
        }
        return null;
    }

    private static String requireField(JSONObject obj0, String keyword0)
    {
        String val0 = findField(obj0, keyword0);
        if (val0 == null)
            throw new IllegalArgumentException(
                "FcaRegisterClient: missing required field matching \"" + keyword0 + "\"");
        return val0;
    }

    private static String optionalField(JSONObject obj0, String keyword0)
    {
        String val0 = findField(obj0, keyword0);
        return val0 == null ? "" : val0;
    }

    private static int parseIntSafe(String s0)
    {
        if (isBlank(s0)) return 0;
        try
        {
            String cleaned0 = s0.replaceAll("[^0-9\\-]", "");
            if (isBlank(cleaned0)) return 0;
            return Integer.parseInt(cleaned0);
        }
        catch (Exception e0) { return 0; }
    }

    private static boolean isBlank(String s0) { return s0 == null || s0.trim().isEmpty(); }
}
