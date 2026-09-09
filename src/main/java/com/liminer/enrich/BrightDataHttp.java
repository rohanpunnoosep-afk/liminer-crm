package com.liminer.enrich;

import java.io.IOException;
import java.util.concurrent.TimeUnit;
import okhttp3.Call;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/*
 * Shared OkHttp transport for every Bright Data call site. OkHttp parses response
 * headers leniently, so the "Proxy Connection" header Bright Data's proxy layer
 * emits (a space, not a hyphen -- illegal per the JDK's strict HTTP/1.1 token
 * check) no longer aborts the response the way it did under java.net.http.
 *
 * Also centralizes the x-brd-err-code guard: Bright Data returns HTTP 200 for a
 * dead/misconfigured zone and signals it only via that header, so every caller
 * gets the guard for free instead of silently parsing an empty-results body.
 */
final class BrightDataHttp
{
    private static final String HEADER_ERR_CODE0 = "x-brd-err-code";

    private static final OkHttpClient BASE_CLIENT0 = new OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .writeTimeout(20, TimeUnit.SECONDS)
        .build();

    // Package-visible seam: tests substitute a fake Call.Factory so post() runs
    // fully offline, with no network access and no BRIGHT_DATA_API_TOKEN needed.
    static Call.Factory callFactory = BASE_CLIENT0;

    private BrightDataHttp()
    {
    }

    static final class Result
    {
        final int status;
        final String body;

        Result(int status0, String body0)
        {
            status = status0;
            body = body0;
        }
    }

    static Result post(String url0, String jsonBody0, String bearerToken0, String zoneLabel0) throws IOException
    {
        return post(url0, jsonBody0, bearerToken0, zoneLabel0, 20L);
    }

    static Result post(
        String url0, String jsonBody0, String bearerToken0, String zoneLabel0, long timeoutSeconds0)
        throws IOException
    {
        Request request0 = new Request.Builder()
            .url(url0)
            .addHeader("Content-Type", "application/json")
            .addHeader("Authorization", "Bearer " + bearerToken0)
            .post(RequestBody.create(jsonBody0, MediaType.get("application/json")))
            .build();

        Call.Factory factory0 = callFactory;

        // Real OkHttpClient: clone with the caller's per-request timeout (mirrors the
        // per-request HttpRequest.timeout(...) each client used before). Fake test
        // factories aren't OkHttpClient instances, so they pass through untouched.
        if (factory0 instanceof OkHttpClient)
        {
            factory0 = ((OkHttpClient) factory0).newBuilder()
                .callTimeout(timeoutSeconds0, TimeUnit.SECONDS)
                .build();
        }

        try (Response response0 = factory0.newCall(request0).execute())
        {
            int status0 = response0.code();
            String body0 = response0.body() == null ? "" : response0.body().string();
            String errCode0 = response0.header(HEADER_ERR_CODE0);

            if (errCode0 != null && !errCode0.trim().isEmpty())
            {
                throw new RuntimeException("Bright Data zone error [" + zoneLabel0 + "]: " + errCode0);
            }

            return new Result(status0, body0);
        }
    }
}
