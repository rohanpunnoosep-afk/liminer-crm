package com.liminer.enrich;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import org.json.JSONObject;

/*
 * EmailVerifierClient — thin HTTP client for a MillionVerifier/ZeroBounce-class
 * single-email verification API. Reads its API key + base URL from environment
 * variables (EMAIL_VERIFIER_API_KEY / EMAIL_VERIFIER_URL), same env-var
 * configuration pattern as BrightDataLinkedInClient/OpenAIClient. Returns
 * UNKNOWN (never throws) when unconfigured, so EmailFinder's waterfall falls
 * through cleanly rather than failing the whole candidate.
 *
 * Deliberately does NOT perform raw SMTP handshake probing from our own
 * infrastructure (sender-reputation risk) — verification is always delegated
 * to the external API.
 *
 * verify() is not final so tests can subclass and override it with a fake,
 * without ever hitting the network or requiring an API key.
 */
public class EmailVerifierClient
{
    private static final String API_KEY0 = System.getenv("EMAIL_VERIFIER_API_KEY");
    private static final String BASE_URL0 = System.getenv("EMAIL_VERIFIER_URL");

    private static final HttpClient CLIENT0 = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(15))
        .build();

    public static class VerifyResult
    {
        public static final String OK = "OK";
        public static final String BAD = "BAD";
        public static final String UNKNOWN = "UNKNOWN";

        public String status;

        public VerifyResult(String status0)
        {
            status = status0;
        }

        public static VerifyResult unknown()
        {
            return new VerifyResult(UNKNOWN);
        }
    }

    public boolean isConfigured()
    {
        return !isBlank(API_KEY0) && !isBlank(BASE_URL0);
    }

    public VerifyResult verify(String email0) throws Exception
    {
        if (isBlank(email0) || !isConfigured())
        {
            return VerifyResult.unknown();
        }

        String endpoint0 = BASE_URL0
            + (BASE_URL0.contains("?") ? "&" : "?")
            + "email=" + java.net.URLEncoder.encode(email0.trim(), "UTF-8")
            + "&key=" + java.net.URLEncoder.encode(API_KEY0, "UTF-8");

        HttpRequest request0 = HttpRequest.newBuilder()
            .uri(URI.create(endpoint0))
            .timeout(Duration.ofSeconds(20))
            .GET()
            .build();

        HttpResponse<String> response0 = CLIENT0.send(request0, HttpResponse.BodyHandlers.ofString());

        if (response0.statusCode() < 200 || response0.statusCode() >= 300)
        {
            return VerifyResult.unknown();
        }

        return parseResponse(response0.body());
    }

    private VerifyResult parseResponse(String body0)
    {
        if (isBlank(body0))
        {
            return VerifyResult.unknown();
        }

        try
        {
            JSONObject json0 = new JSONObject(body0);
            String rawResult0 = json0.optString("result", json0.optString("status", "")).trim().toLowerCase();

            if (rawResult0.equals("ok") || rawResult0.equals("deliverable") || rawResult0.equals("valid"))
            {
                return new VerifyResult(VerifyResult.OK);
            }

            if (rawResult0.equals("invalid") || rawResult0.equals("undeliverable")
                || rawResult0.equals("disposable") || rawResult0.equals("bad"))
            {
                return new VerifyResult(VerifyResult.BAD);
            }

            return VerifyResult.unknown();
        }
        catch (Exception ignored0)
        {
            return VerifyResult.unknown();
        }
    }

    private static boolean isBlank(String value0)
    {
        return value0 == null || value0.trim().length() == 0;
    }
}
