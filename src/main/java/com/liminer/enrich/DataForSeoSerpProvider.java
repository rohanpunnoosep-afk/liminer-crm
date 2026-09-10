package com.liminer.enrich;

import com.liminer.billing.CostMeter;

import java.util.ArrayList;
import java.util.Base64;
import java.util.concurrent.TimeUnit;
import okhttp3.Call;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.json.JSONArray;
import org.json.JSONObject;

/*
 * DataForSeoSerpProvider runs Google searches through DataForSEO's "live advanced"
 * SERP endpoint. It exists because Bright Data's SERP zone has, since Google's
 * late-July 2026 rollout, answered every query with /goto redirect stubs instead
 * of destination URLs; DataForSEO publicly reported 99.99% /goto resolution on
 * 2026-08-27, and is registered ahead of BrightDataSearchProvider in
 * SearchRouter.shared() for that reason (see ExtraDocuments/webAccessPlan.md).
 *
 * Required env vars:
 *   DATAFORSEO_LOGIN
 *   DATAFORSEO_PASSWORD
 *
 * Contract (see SearchProvider): only absolute http(s) URLs off DataForSEO's own
 * domain are returned; a /goto stub or any other unusable link is dropped rather
 * than passed downstream, and every failure throws so SearchRouter fails over
 * instead of treating junk as a successful answer.
 */
public class DataForSeoSerpProvider implements SearchProvider
{
    private static final String ENDPOINT0 = "https://api.dataforseo.com/v3/serp/google/organic/live/advanced";
    private static final int LOCATION_CODE_US0 = 2840;

    private static final String LOGIN0 = System.getenv("DATAFORSEO_LOGIN");
    private static final String PASSWORD0 = System.getenv("DATAFORSEO_PASSWORD");

    private static final OkHttpClient BASE_CLIENT0 = new OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .writeTimeout(20, TimeUnit.SECONDS)
        .build();

    // Package-visible seam, mirroring BrightDataHttp.callFactory: tests substitute a
    // fake Call.Factory so search() runs fully offline, with no network access and
    // no DataForSEO credentials needed.
    static Call.Factory callFactory = BASE_CLIENT0;

    @Override
    public String name()
    {
        return "dataforseo";
    }

    @Override
    public boolean isAvailable()
    {
        return !isBlank(LOGIN0) && !isBlank(PASSWORD0);
    }

    @Override
    public ArrayList<SerpResult> search(String query0, int maxResults0) throws Exception
    {
        if (isBlank(query0))
        {
            return new ArrayList<SerpResult>();
        }

        JSONObject task0 = new JSONObject();
        task0.put("keyword", query0);
        task0.put("location_code", LOCATION_CODE_US0);
        task0.put("language_code", "en");
        task0.put("depth", maxResults0);

        JSONArray requestBody0 = new JSONArray();
        requestBody0.put(task0);

        Request request0 = new Request.Builder()
            .url(ENDPOINT0)
            .addHeader("Content-Type", "application/json")
            .addHeader("Authorization", "Basic " + basicAuth0())
            .post(RequestBody.create(requestBody0.toString(), MediaType.get("application/json")))
            .build();

        int status0;
        String body0;

        CostMeter meter0 = CostMeter.current();
        if (meter0 != null)
        {
            meter0.recordSearch("dataforseo");
        }

        try (Response response0 = callFactory.newCall(request0).execute())
        {
            status0 = response0.code();
            body0 = response0.body() == null ? "" : response0.body().string();
        }

        if (status0 < 200 || status0 >= 300)
        {
            throw new RuntimeException("dataforseo: HTTP status " + status0);
        }

        if (isBlank(body0))
        {
            throw new RuntimeException("dataforseo: empty response body");
        }

        JSONObject root0;
        JSONObject firstTask0;
        try
        {
            root0 = new JSONObject(body0);
            firstTask0 = root0.getJSONArray("tasks").getJSONObject(0);
        }
        catch (Exception parseError0)
        {
            throw new RuntimeException("dataforseo: malformed response body");
        }

        int taskStatusCode0 = firstTask0.optInt("status_code", 0);
        if (taskStatusCode0 != 0)
        {
            throw new RuntimeException("dataforseo: task status_code " + taskStatusCode0);
        }

        ArrayList<SerpResult> results0 = new ArrayList<SerpResult>();

        JSONArray resultArray0 = firstTask0.optJSONArray("result");
        if (resultArray0 == null || resultArray0.isEmpty())
        {
            return results0;
        }

        JSONArray items0 = resultArray0.getJSONObject(0).optJSONArray("items");
        if (items0 == null)
        {
            return results0;
        }

        for (int i = 0; i < items0.length() && results0.size() < maxResults0; i++)
        {
            JSONObject item0 = items0.getJSONObject(i);

            if (!"organic".equals(item0.optString("type", "")))
            {
                continue;
            }

            String url0 = item0.optString("url", "");

            if (!SerpUrls.isAbsoluteHttpUrl(url0) || !SerpUrls.isUsefulUrl(url0))
            {
                continue;
            }

            results0.add(new SerpResult(
                item0.optString("title", ""),
                url0,
                item0.optString("description", ""),
                item0.optInt("rank_group", 0),
                query0));
        }

        return results0;
    }

    private static String basicAuth0()
    {
        String credentials0 = LOGIN0 + ":" + PASSWORD0;
        return Base64.getEncoder().encodeToString(credentials0.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    private static boolean isBlank(String value0)
    {
        return value0 == null || value0.trim().length() == 0;
    }
}
