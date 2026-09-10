package com.liminer.enrich;

import java.util.ArrayList;
import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.Headers;
import okhttp3.MediaType;
import okhttp3.Protocol;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;
import okio.Timeout;

/*
 * Offline verification of task 0180 (Bright Data OkHttp transport). No network access,
 * no BRIGHT_DATA_API_TOKEN: BrightDataHttp.callFactory is swapped for a fake Call.Factory
 * that returns canned okhttp3.Response objects built via Headers.Builder.addLenient$okhttp,
 * the same lenient parsing path OkHttp uses for real wire responses -- so a header literally
 * named "Proxy Connection" (a space, illegal per strict HTTP/1.1 tokens, which is what made
 * java.net.http abort the whole response) round-trips exactly like it does over a real
 * OkHttp connection.
 */
public class BrightDataTransportTestMain
{
    private static int failures0 = 0;

    public static void main(String[] args0)
    {
        Call.Factory realFactory0 = BrightDataHttp.callFactory;

        try
        {
            // Each case resets the zone latch first: testZoneErrorHeaderRaisesException
            // deliberately latches a fault, and BrightDataHttp.post fails fast while one
            // is set, so without this every later case would fail on that stale fault.
            BrightDataZoneHealth.reset();
            testProxyConnectionHeaderIsLenientlyParsed();

            BrightDataZoneHealth.reset();
            testZoneErrorHeaderRaisesException();

            BrightDataZoneHealth.reset();
            testNoZoneErrorHeaderReturnsBodyUnchanged();

            BrightDataZoneHealth.reset();
            testSerpClientParsesThroughNewTransport();
        }
        catch (Exception exception0)
        {
            System.out.println("TEST FAILED: " + exception0.getMessage());
            exception0.printStackTrace();
            System.exit(1);
        }
        finally
        {
            BrightDataHttp.callFactory = realFactory0;
            BrightDataZoneHealth.reset();
        }

        if (failures0 > 0)
        {
            System.out.println("BRIGHT_DATA_TRANSPORT_FAILED: " + failures0 + " check(s) failed");
            System.exit(1);
        }

        System.out.println("BRIGHT_DATA_TRANSPORT_OK");
    }

    // ---------- (a) regression test for the reported bug ----------

    private static void testProxyConnectionHeaderIsLenientlyParsed() throws Exception
    {
        String canned0 = "{\"organic\":[]}";

        BrightDataHttp.callFactory = fakeFactory(200, "Proxy Connection: keep-alive", canned0);

        BrightDataHttp.Result result0 = BrightDataHttp.post(
            "https://api.brightdata.com/request", "{}", "token", "serp_api2");

        check("status 200 survives a 'Proxy Connection' response header",
            result0.status == 200);
        check("body is returned intact alongside a 'Proxy Connection' header",
            canned0.equals(result0.body));
    }

    // ---------- (b) dead-zone guard ----------

    private static void testZoneErrorHeaderRaisesException() throws Exception
    {
        BrightDataHttp.callFactory = fakeFactory(200, "x-brd-err-code: zone_not_found", "{}");

        boolean threw0 = false;
        String message0 = "";
        try
        {
            BrightDataHttp.post("https://api.brightdata.com/request", "{}", "token", "serp_api2");
        }
        catch (RuntimeException exception0)
        {
            threw0 = true;
            message0 = exception0.getMessage();
        }

        check("x-brd-err-code on a 200 response raises an exception", threw0);
        check("exception message contains the zone name", message0.contains("serp_api2"));
        check("exception message contains the error code", message0.contains("zone_not_found"));
    }

    // ---------- (c) no error header -> body unchanged ----------

    private static void testNoZoneErrorHeaderReturnsBodyUnchanged() throws Exception
    {
        String canned0 = "{\"organic\":[{\"title\":\"t\",\"link\":\"https://example.com\"}]}";

        BrightDataHttp.callFactory = fakeFactory(200, null, canned0);

        BrightDataHttp.Result result0 = BrightDataHttp.post(
            "https://api.brightdata.com/request", "{}", "token", "serp_api2");

        check("no x-brd-err-code header returns status unchanged", result0.status == 200);
        check("no x-brd-err-code header returns body unchanged", canned0.equals(result0.body));
    }

    // ---------- (d) BrightDataSerpClient.search(...) through the new transport ----------

    private static void testSerpClientParsesThroughNewTransport() throws Exception
    {
        String canned0 = "{\"organic\":["
            + "{\"title\":\"Acme Fund\",\"link\":\"https://acmefund.com\",\"snippet\":\"An LP profile\"},"
            + "{\"title\":\"Beta Capital\",\"link\":\"https://betacapital.com\",\"snippet\":\"Another LP\"}"
            + "]}";

        BrightDataHttp.callFactory = fakeFactory(200, "Proxy Connection: keep-alive", canned0);

        // BrightDataHttp.callFactory is swapped above, so this never touches the network
        // regardless of whether BRIGHT_DATA_API_TOKEN happens to be set in the environment.
        ArrayList<SerpResult> results0 = new BrightDataSerpClient().search("fintech seed funds", 5);

        check("SerpClient parses two organic results through the new transport",
            results0.size() == 2);
        check("first result title parsed", results0.size() > 0 && "Acme Fund".equals(results0.get(0).title));
        check("first result url parsed", results0.size() > 0 && "https://acmefund.com".equals(results0.get(0).url));
        check("second result title parsed", results0.size() > 1 && "Beta Capital".equals(results0.get(1).title));
    }

    // ---------- fake Call.Factory ----------

    private static Call.Factory fakeFactory(int status0, String rawHeaderLine0, String body0)
    {
        return request0 -> new FakeCall(request0, status0, rawHeaderLine0, body0);
    }

    private static final class FakeCall implements Call
    {
        private final Request request0;
        private final int status0;
        private final String rawHeaderLine0;
        private final String body0;

        FakeCall(Request request1, int status1, String rawHeaderLine1, String body1)
        {
            request0 = request1;
            status0 = status1;
            rawHeaderLine0 = rawHeaderLine1;
            body0 = body1;
        }

        @Override
        public Request request()
        {
            return request0;
        }

        @Override
        public Response execute()
        {
            Headers.Builder headersBuilder0 = new Headers.Builder();
            if (rawHeaderLine0 != null)
            {
                // addLenient$okhttp mirrors the raw-response-line parsing path OkHttp uses
                // for real wire responses, which is what makes a header name containing a
                // space (illegal per strict HTTP/1.1 tokens) survive intact.
                headersBuilder0.addLenient$okhttp(rawHeaderLine0);
            }

            return new Response.Builder()
                .request(request0)
                .protocol(Protocol.HTTP_1_1)
                .code(status0)
                .message("OK")
                .headers(headersBuilder0.build())
                .body(ResponseBody.create(body0, MediaType.get("application/json")))
                .build();
        }

        @Override
        public void enqueue(Callback responseCallback0)
        {
            throw new UnsupportedOperationException("async not used by BrightDataHttp");
        }

        @Override
        public void cancel()
        {
        }

        @Override
        public boolean isExecuted()
        {
            return false;
        }

        @Override
        public boolean isCanceled()
        {
            return false;
        }

        @Override
        public Timeout timeout()
        {
            return Timeout.NONE;
        }

        @Override
        public Call clone()
        {
            return new FakeCall(request0, status0, rawHeaderLine0, body0);
        }
    }

    private static void check(String label0, boolean condition0)
    {
        if (condition0)
        {
            System.out.println("  ok   " + label0);
            return;
        }

        System.out.println("  FAIL " + label0);
        failures0++;
    }
}
