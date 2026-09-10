package com.liminer.enrich;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import okhttp3.Call;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

/*
 * Resolves Google's /goto?url=<blob> redirect stubs to their real destination URL.
 *
 * The blob is encrypted protobuf with no plaintext URL inside -- it cannot be decoded.
 * It CAN be resolved: GET https://www.google.com/goto?url=<blob> with redirects
 * disabled and a desktop User-Agent returns an HTTP 3xx with the destination in the
 * Location header. This is the same mechanism SerpApi and DataForSEO use.
 *
 * This is a DIRECT call, never routed through Bright Data -- Bright Data refuses the
 * /goto path outright (invalid_path). That also means it costs nothing and consumes
 * no unlocker quota.
 */
final class GotoResolver
{
    private static final String USER_AGENT0 =
        "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 "
        + "(KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36";

    private static final OkHttpClient BASE_CLIENT0 = new OkHttpClient.Builder()
        .followRedirects(false)
        .followSslRedirects(false)
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build();

    // Package-visible seam: tests substitute a fake Call.Factory so resolve() runs
    // fully offline, with no network access. Mirrors BrightDataHttp.callFactory.
    static Call.Factory callFactory = BASE_CLIENT0;

    private static final ConcurrentHashMap<String, String> CACHE0 = new ConcurrentHashMap<String, String>();

    private GotoResolver()
    {
    }

    static String resolve(String link0)
    {
        if (isBlank(link0))
        {
            return "";
        }

        String value0 = link0.trim();

        String target0;
        if (value0.startsWith("/goto?"))
        {
            target0 = "https://www.google.com" + value0;
        }
        else if (value0.startsWith("https://www.google.com/goto?")
            || value0.startsWith("https://google.com/goto?"))
        {
            target0 = value0;
        }
        else
        {
            return value0;
        }

        String cached0 = CACHE0.get(target0);
        if (cached0 != null)
        {
            return cached0;
        }

        String resolved0 = resolveUncached(target0);
        CACHE0.put(target0, resolved0);
        return resolved0;
    }

    private static String resolveUncached(String target0)
    {
        try
        {
            Request request0 = new Request.Builder()
                .url(target0)
                .header("User-Agent", USER_AGENT0)
                .get()
                .build();

            Call.Factory factory0 = callFactory;

            try (Response response0 = factory0.newCall(request0).execute())
            {
                if (response0.code() < 300 || response0.code() >= 400)
                {
                    return "";
                }

                String location0 = response0.header("Location");
                if (location0 == null)
                {
                    return "";
                }

                String trimmed0 = location0.trim();
                if (!BrightDataSerpClient.isAbsoluteHttpUrl(trimmed0))
                {
                    return "";
                }

                return trimmed0;
            }
        }
        catch (Exception ignored0)
        {
            return "";
        }
    }

    private static boolean isBlank(String value0)
    {
        return value0 == null || value0.trim().length() == 0;
    }
}
