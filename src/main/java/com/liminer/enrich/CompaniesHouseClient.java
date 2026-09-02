package com.liminer.enrich;

import com.liminer.scout.ScoutUniverseRecord;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;

/*
 * CompaniesHouseClient — UK Companies House REST API client
 * (api.company-information.service.gov.uk), used to enrich UK entity data
 * (registered address, company status) for firms already in the Investor
 * Scout universe. Mirrors AdvBulkClient's/FcaRegisterClient's split between a
 * network-fetch layer and a pure, offline-testable mapper.
 *
 * Companies House uses HTTP Basic auth with the API key as the username and
 * an empty password — the key is read from env var COMPANIES_HOUSE_API_KEY,
 * never hardcoded.
 *
 * UK company numbers are not always purely numeric (e.g. Scotland-registered
 * companies use an "SC" prefix), so identity is carried via the new
 * ScoutUniverseRecord.externalRegisterId field, not the numeric crd (left 0
 * unless the company number happens to parse as an integer).
 */
public class CompaniesHouseClient
{
    private static final HttpClient CLIENT0 = HttpClient.newHttpClient();
    private static final String USER_AGENT0 = HttpContact.USER_AGENT0;
    private static final int TIMEOUT_SECS0 = 30;
    private static final String BASE0 = "https://api.company-information.service.gov.uk";

    public CompaniesHouseClient() {}

    // -----------------------------------------------------------------------
    // Public API
    // -----------------------------------------------------------------------

    /** Live fetch: company search results JSON body for a free-text query. */
    public String fetchSearchResults(String query0) throws Exception
    {
        if (isBlank(query0)) throw new IllegalArgumentException("query0 is required");
        String enc0 = URLEncoder.encode(query0.trim(), StandardCharsets.UTF_8);
        return get(BASE0 + "/search/companies?q=" + enc0);
    }

    /**
     * Pure mapper: company-search JSON -> ScoutUniverseRecord list. Throws
     * IllegalArgumentException naming the missing field when a required
     * search-result field cannot be found.
     */
    public List<ScoutUniverseRecord> mapSearchResults(String searchJson0)
    {
        List<ScoutUniverseRecord> out0 = new ArrayList<ScoutUniverseRecord>();
        if (isBlank(searchJson0)) return out0;

        JSONObject root0 = new JSONObject(searchJson0);
        JSONArray items0 = root0.optJSONArray("items");
        if (items0 == null) return out0;

        for (int i0 = 0; i0 < items0.length(); i0++)
        {
            JSONObject item0 = items0.optJSONObject(i0);
            if (item0 == null) continue;

            String companyNumber0 = requireField(item0, "company_number");
            String title0 = requireField(item0, "title");
            String status0 = item0.optString("company_status", "");
            String addressSnippet0 = item0.optString("address_snippet", "");

            ScoutUniverseRecord rec0 = new ScoutUniverseRecord();
            rec0.crd = parseIntSafe(companyNumber0);
            rec0.externalRegisterId = companyNumber0;
            rec0.sourceRegister = "COMPANIES_HOUSE";
            rec0.firmName = title0;
            rec0.city = firstAddressComponent(addressSnippet0);
            rec0.country = "United Kingdom";
            rec0.clientTypes = new ArrayList<String>();
            if (!isBlank(status0)) rec0.clientTypes.add(status0);
            out0.add(rec0);
        }
        return out0;
    }

    // -----------------------------------------------------------------------
    // HTTP
    // -----------------------------------------------------------------------

    private String get(String url0) throws Exception
    {
        String key0 = System.getenv("COMPANIES_HOUSE_API_KEY");
        if (isBlank(key0))
            throw new IllegalStateException("CompaniesHouseClient: COMPANIES_HOUSE_API_KEY env var is required");

        String basicAuth0 = Base64.getEncoder().encodeToString((key0 + ":").getBytes(StandardCharsets.UTF_8));

        HttpRequest req0 = HttpRequest.newBuilder()
            .uri(URI.create(url0))
            .timeout(Duration.ofSeconds(TIMEOUT_SECS0))
            .header("User-Agent", USER_AGENT0)
            .header("Authorization", "Basic " + basicAuth0)
            .GET()
            .build();

        HttpResponse<String> resp0 = CLIENT0.send(req0, HttpResponse.BodyHandlers.ofString());
        int status0 = resp0.statusCode();
        if (status0 < 200 || status0 >= 300)
            throw new RuntimeException("HTTP " + status0 + " for " + url0);
        return resp0.body();
    }

    // -----------------------------------------------------------------------
    // Field resolution helpers
    // -----------------------------------------------------------------------

    private static String requireField(JSONObject obj0, String key0)
    {
        if (!obj0.has(key0) || obj0.isNull(key0))
            throw new IllegalArgumentException(
                "CompaniesHouseClient: missing required field \"" + key0 + "\"");
        return obj0.optString(key0, "");
    }

    // "address_snippet" is typically a comma-separated line, e.g.
    // "1 Example Street, London, EC1A 1AA" — first component is the street,
    // so the town/city is best-effort read as the second comma-separated part.
    private static String firstAddressComponent(String snippet0)
    {
        if (isBlank(snippet0)) return "";
        String[] parts0 = snippet0.split(",");
        return parts0.length > 1 ? parts0[1].trim() : parts0[0].trim();
    }

    private static int parseIntSafe(String s0)
    {
        if (isBlank(s0)) return 0;
        try { return Integer.parseInt(s0.trim()); }
        catch (Exception e0) { return 0; }
    }

    private static boolean isBlank(String s0) { return s0 == null || s0.trim().isEmpty(); }
}
