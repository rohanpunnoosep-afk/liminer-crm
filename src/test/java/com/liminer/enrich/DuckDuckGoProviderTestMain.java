package com.liminer.enrich;

import com.liminer.billing.CostMeter;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.Headers;
import okhttp3.MediaType;
import okhttp3.Protocol;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;
import okio.Timeout;
import org.json.JSONObject;

/*
 * Offline verification of DuckDuckGoUnlockerProvider. No network access and no
 * BRIGHT_DATA_API_TOKEN needed: BrightDataHttp.callFactory is swapped for a fake
 * Call.Factory, the same seam SerpUnusableUrlTestMain and BrightDataTransportTestMain
 * use, and restored in a finally block.
 */
public class DuckDuckGoProviderTestMain
{
    private static int failures0 = 0;

    public static void main(String[] args0)
    {
        Call.Factory realFactory0 = BrightDataHttp.callFactory;

        try
        {
            BrightDataZoneHealth.reset();
            testWrappedResultsDecodeInPageOrder();

            BrightDataZoneHealth.reset();
            testPercentEncodedCharactersDecode();

            BrightDataZoneHealth.reset();
            testDuplicateDestinationsDeduped();

            BrightDataZoneHealth.reset();
            testDuckDuckGoDestinationDropped();

            BrightDataZoneHealth.reset();
            testGoogleDestinationDropped();

            BrightDataZoneHealth.reset();
            testNonAbsoluteDestinationDropped();

            BrightDataZoneHealth.reset();
            testMaxResultsCaps();

            BrightDataZoneHealth.reset();
            testNoMatchesReturnsEmpty();

            BrightDataZoneHealth.reset();
            testEmptyBodyThrows();

            BrightDataZoneHealth.reset();
            testNon2xxStatusThrows();

            testIsAvailableFalseWhenTokenBlank();

            BrightDataZoneHealth.reset();
            testBlankQueryMakesNoHttpCalls();

            BrightDataZoneHealth.reset();
            testRequestTargetsDuckDuckGoThroughUnlockerZone();

            BrightDataZoneHealth.reset();
            testCostMeterBillsDuckDuckGoOnly();

            testSurvivesLatchedSerpFault();
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
            CostMeter.unbind();
        }

        if (failures0 > 0)
        {
            System.out.println("DDG_PROVIDER_FAILED: " + failures0 + " check(s) failed");
            System.exit(1);
        }

        System.out.println("DDG_PROVIDER_OK");
    }

    // ---------- decoding ----------

    private static void testWrappedResultsDecodeInPageOrder() throws Exception
    {
        String html0 = ddgHtml(
            "https://www.linkedin.com/company/first",
            "https://www.linkedin.com/company/second",
            "https://www.linkedin.com/company/third");

        ArrayList<SerpResult> results0 = search(html0, 10);

        check("three wrapped results decoded", results0.size() == 3);
        check("first result in page order",
            results0.size() > 0 && "https://www.linkedin.com/company/first".equals(results0.get(0).url));
        check("second result in page order",
            results0.size() > 1 && "https://www.linkedin.com/company/second".equals(results0.get(1).url));
        check("third result in page order",
            results0.size() > 2 && "https://www.linkedin.com/company/third".equals(results0.get(2).url));
        check("ranks assigned from 1",
            results0.size() > 0 && results0.get(0).rank == 1 && results0.get(2).rank == 3);
    }

    private static void testPercentEncodedCharactersDecode() throws Exception
    {
        // Hand-built wrapper so %3A%2F%2F (the "://" that URLEncoder would otherwise
        // produce as a single escaped run) is exercised explicitly.
        String wrapped0 = "uddg=https%3A%2F%2Fwww.linkedin.com%2Fcompany%2Fglobal-partnerships";
        String html0 = "<a href=\"//duckduckgo.com/l/?" + wrapped0 + "&amp;rut=x\">link</a>";

        ArrayList<SerpResult> results0 = search(html0, 10);

        check("percent-encoded '://' decodes correctly",
            results0.size() == 1
            && "https://www.linkedin.com/company/global-partnerships".equals(results0.get(0).url));
    }

    private static void testDuplicateDestinationsDeduped() throws Exception
    {
        String html0 = ddgHtml(
            "https://www.linkedin.com/company/dup",
            "https://www.linkedin.com/company/dup",
            "https://www.linkedin.com/company/unique");

        ArrayList<SerpResult> results0 = search(html0, 10);

        check("duplicate destinations emitted once", results0.size() == 2);
        check("first kept occurrence is the duplicate",
            results0.size() > 0 && "https://www.linkedin.com/company/dup".equals(results0.get(0).url));
        check("unique destination still present",
            results0.size() > 1 && "https://www.linkedin.com/company/unique".equals(results0.get(1).url));
    }

    private static void testDuckDuckGoDestinationDropped() throws Exception
    {
        String html0 = ddgHtml("https://duckduckgo.com/about", "https://www.linkedin.com/company/kept");

        ArrayList<SerpResult> results0 = search(html0, 10);

        check("a duckduckgo.com destination is dropped",
            results0.size() == 1 && "https://www.linkedin.com/company/kept".equals(results0.get(0).url));
    }

    private static void testGoogleDestinationDropped() throws Exception
    {
        String html0 = ddgHtml("https://www.google.com/search?q=x", "https://www.linkedin.com/company/kept");

        ArrayList<SerpResult> results0 = search(html0, 10);

        check("a google.com destination is dropped",
            results0.size() == 1 && "https://www.linkedin.com/company/kept".equals(results0.get(0).url));
    }

    private static void testNonAbsoluteDestinationDropped() throws Exception
    {
        String html0 = ddgHtml("/relative/path", "https://www.linkedin.com/company/kept");

        ArrayList<SerpResult> results0 = search(html0, 10);

        check("a non-absolute destination is dropped",
            results0.size() == 1 && "https://www.linkedin.com/company/kept".equals(results0.get(0).url));
    }

    private static void testMaxResultsCaps() throws Exception
    {
        String html0 = ddgHtml(
            "https://www.linkedin.com/company/a",
            "https://www.linkedin.com/company/b",
            "https://www.linkedin.com/company/c");

        ArrayList<SerpResult> results0 = search(html0, 2);

        check("maxResults0 caps the result list", results0.size() == 2);
    }

    private static void testNoMatchesReturnsEmpty() throws Exception
    {
        ArrayList<SerpResult> results0 = search("<html><body>no results here</body></html>", 10);

        check("HTML with no uddg matches returns an empty list, no exception", results0.isEmpty());
    }

    // ---------- failure propagation ----------

    private static void testEmptyBodyThrows() throws Exception
    {
        BrightDataHttp.callFactory = fakeFactory(200, null, "");

        boolean threw0 = false;
        try
        {
            new DuckDuckGoUnlockerProvider().search("test query", 10);
        }
        catch (Exception expected0)
        {
            threw0 = true;
        }

        check("an empty response body throws", threw0);
    }

    private static void testNon2xxStatusThrows() throws Exception
    {
        BrightDataHttp.callFactory = fakeFactory(503, null, "service unavailable");

        boolean threw0 = false;
        try
        {
            new DuckDuckGoUnlockerProvider().search("test query", 10);
        }
        catch (Exception expected0)
        {
            threw0 = true;
        }

        check("a non-2xx status throws", threw0);
    }

    // ---------- availability ----------

    private static void testIsAvailableFalseWhenTokenBlank()
    {
        // BRIGHT_DATA_API_TOKEN is read once into a static final at class-load time,
        // so this test can only assert against whatever environment this JVM actually
        // has -- the verify command runs with no env vars set, so isAvailable() must
        // be false in that case.
        String token0 = System.getenv("BRIGHT_DATA_API_TOKEN");
        boolean tokenSet0 = token0 != null && !token0.trim().isEmpty();

        check("isAvailable() is false when BRIGHT_DATA_API_TOKEN is blank",
            new DuckDuckGoUnlockerProvider().isAvailable() == tokenSet0);
    }

    // ---------- blank query ----------

    private static void testBlankQueryMakesNoHttpCalls() throws Exception
    {
        AtomicInteger callCount0 = new AtomicInteger(0);
        BrightDataHttp.callFactory = request0 ->
        {
            callCount0.incrementAndGet();
            return fakeFactory(200, null, "{}").newCall(request0);
        };

        ArrayList<SerpResult> results0 = new DuckDuckGoUnlockerProvider().search("   ", 10);

        check("a blank query returns an empty list", results0.isEmpty());
        check("a blank query makes zero HTTP calls", callCount0.get() == 0);
    }

    // ---------- request shape ----------

    private static void testRequestTargetsDuckDuckGoThroughUnlockerZone() throws Exception
    {
        String[] capturedBody0 = new String[1];
        BrightDataHttp.callFactory = request0 ->
        {
            try
            {
                capturedBody0[0] = bodyOf(request0);
            }
            catch (Exception exception0)
            {
                throw new RuntimeException(exception0);
            }
            return fakeFactory(200, null, ddgHtml("https://www.linkedin.com/company/x")).newCall(request0);
        };

        new DuckDuckGoUnlockerProvider().search("site:linkedin.com/company test", 10);

        JSONObject body0 = new JSONObject(capturedBody0[0]);

        check("request body targets html.duckduckgo.com", body0.getString("url").contains("html.duckduckgo.com"));
        check("request body carries the unlocker zone, not the SERP zone",
            "web_unlocker2".equals(body0.getString("zone")));
    }

    // ---------- billing ----------

    private static void testCostMeterBillsDuckDuckGoOnly() throws Exception
    {
        CostMeter meter0 = new CostMeter(10.00);
        CostMeter.bind(meter0);

        try
        {
            BrightDataHttp.callFactory =
                fakeFactory(200, null, ddgHtml("https://www.linkedin.com/company/x"));

            DuckDuckGoUnlockerProvider provider0 = new DuckDuckGoUnlockerProvider();
            provider0.search("query one", 10);
            provider0.search("query two", 10);
        }
        finally
        {
            CostMeter.unbind();
        }

        check("two duckduckgo requests cost $0.0030 total", Math.abs(meter0.usd() - 0.0030) < 1e-9);
        check("duckduckgo spend is not double-booked under the legacy brightdata bucket",
            meter0.toJson().getLong("brightDataCalls") == 0);
        check("duckduckgo calls are tracked under their own provider name",
            meter0.toJson().getJSONObject("searchProviders").getJSONObject("duckduckgo").getLong("calls") == 2);
    }

    // ---------- independence from the SERP zone latch ----------

    private static void testSurvivesLatchedSerpFault() throws Exception
    {
        BrightDataZoneHealth.reset();

        try
        {
            BrightDataZoneHealth.recordFault(new BrightDataZoneException("serp_api2", "zone_not_found"));
            check("a latched SERP fault blocks plain BrightDataHttp.post callers", blocksOnLatch());

            BrightDataHttp.callFactory =
                fakeFactory(200, null, ddgHtml("https://www.linkedin.com/company/x"));

            ArrayList<SerpResult> results0 = new DuckDuckGoUnlockerProvider().search("query", 10);

            check("DuckDuckGoUnlockerProvider still works while the SERP zone is latched dead",
                results0.size() == 1);
        }
        finally
        {
            BrightDataZoneHealth.reset();
        }
    }

    private static boolean blocksOnLatch()
    {
        try
        {
            BrightDataHttp.post("https://api.brightdata.com/request", "{}", "t", "serp_api2");
            return false;
        }
        catch (BrightDataZoneException expected0)
        {
            return true;
        }
        catch (Exception other0)
        {
            return false;
        }
    }

    // ---------- helpers ----------

    private static ArrayList<SerpResult> search(String html0, int maxResults0) throws Exception
    {
        BrightDataHttp.callFactory = fakeFactory(200, null, html0);
        return new DuckDuckGoUnlockerProvider().search("test query", maxResults0);
    }

    /* Builds canned DDG results HTML wrapping each destination as a //duckduckgo.com/l/?uddg= link. */
    private static String ddgHtml(String... destinations0)
    {
        StringBuilder html0 = new StringBuilder("<html><body>");
        for (String destination0 : destinations0)
        {
            String encoded0 = URLEncoder.encode(destination0, StandardCharsets.UTF_8);
            html0.append("<a class=\"result__a\" href=\"//duckduckgo.com/l/?uddg=")
                .append(encoded0)
                .append("&amp;rut=abc123\">title</a>");
        }
        html0.append("</body></html>");
        return html0.toString();
    }

    private static String bodyOf(Request request0) throws Exception
    {
        okhttp3.RequestBody requestBody0 = request0.body();
        okio.Buffer buffer0 = new okio.Buffer();
        requestBody0.writeTo(buffer0);
        return buffer0.readUtf8();
    }

    private static void check(String label0, boolean condition0)
    {
        if (condition0)
        {
            System.out.println("  ok   " + label0);
            return;
        }

        failures0++;
        System.out.println("  FAIL " + label0);
    }

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
                headersBuilder0.addLenient$okhttp(rawHeaderLine0);
            }

            return new Response.Builder()
                .request(request0)
                .protocol(Protocol.HTTP_1_1)
                .code(status0)
                .message("OK")
                .headers(headersBuilder0.build())
                .body(ResponseBody.create(body0, MediaType.get("text/html")))
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
}
