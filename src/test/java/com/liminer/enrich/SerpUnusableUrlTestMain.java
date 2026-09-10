package com.liminer.enrich;

import com.liminer.billing.CostMeter;

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
 * Offline verification of the /goto defence and Bright Data cost metering. No network
 * access and no BRIGHT_DATA_API_TOKEN needed: BrightDataHttp.callFactory is swapped for
 * a fake Call.Factory returning canned bodies, the same seam BrightDataTransportTestMain
 * uses.
 *
 * The canned bodies are the real shapes observed from both zones on 2026-09-09:
 * serp_api2 (data_format parsed_light) returns the redirect stub as a relative path,
 * serp_api3 (data_format parsed) returns the same stub absolutised onto google.com.
 */
public class SerpUnusableUrlTestMain
{
    private static int failures0 = 0;

    // The stub shapes, straight from the live responses.
    private static final String GOTO_RELATIVE0 =
        "/goto?url=CAESawHrOzAVDC800tTuvL6jyarGYD2iBIbzI_IHwtXLKweyYLi8ZA0AgVTHK3X2JhWXijBK";
    private static final String GOTO_ABSOLUTE0 =
        "https://www.google.com/goto?url=CAESawHrOzAVrwmrH85LQ_zY9B1uZF8VrwC5rXNi-0ZXhtJIxbxY";

    public static void main(String[] args0)
    {
        Call.Factory realFactory0 = BrightDataHttp.callFactory;

        try
        {
            testRelativeGotoIsRejected();
            testAbsoluteGotoIsRejected();
            testGenuineUrlsStillParse();
            testNoResultsIsNotAFault();
            testStreakLatchesFault();
            testUsableResultBreaksStreak();
            testFaultSummaryNamesTheParsingProblem();
            testBrightDataCallsAreMetered();
            testZoneErrorResponseIsStillMetered();
            testCeilingCountsBrightDataSpend();
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
            System.out.println("SERP_UNUSABLE_URL_FAILED: " + failures0 + " check(s) failed");
            System.exit(1);
        }

        System.out.println("SERP_UNUSABLE_URL_OK");
    }

    // ---------- (a) both stub shapes are rejected ----------

    private static void testRelativeGotoIsRejected() throws Exception
    {
        BrightDataZoneHealth.reset();
        ArrayList<SerpResult> results0 = searchWithOrganic(organic(GOTO_RELATIVE0, GOTO_RELATIVE0));

        check("parsed_light relative /goto stubs are all discarded", results0.isEmpty());
    }

    private static void testAbsoluteGotoIsRejected() throws Exception
    {
        BrightDataZoneHealth.reset();
        ArrayList<SerpResult> results0 = searchWithOrganic(organic(GOTO_ABSOLUTE0, GOTO_ABSOLUTE0));

        // This is the case isAbsoluteHttpUrl alone let through, at the cost of a Web
        // Unlocker request per stub to discover the page was unfetchable.
        check("parsed absolute google.com/goto stubs are all discarded", results0.isEmpty());
    }

    // ---------- (b) real URLs are untouched ----------

    private static void testGenuineUrlsStillParse() throws Exception
    {
        BrightDataZoneHealth.reset();
        ArrayList<SerpResult> results0 = searchWithOrganic(
            organic("https://www.linkedin.com/company/global-partnerships", GOTO_ABSOLUTE0));

        check("a genuine URL alongside a stub still parses", results0.size() == 1);
        check("the genuine URL is the one kept",
            results0.size() == 1
            && "https://www.linkedin.com/company/global-partnerships".equals(results0.get(0).url));
        check("a query with a usable result raises no fault", BrightDataZoneHealth.isHealthy());
    }

    // ---------- (c) an empty result set is a search miss, not a fault ----------

    private static void testNoResultsIsNotAFault() throws Exception
    {
        BrightDataZoneHealth.reset();

        for (int i = 0; i < 10; i++)
        {
            searchWithOrganic("");
        }

        check("genuinely empty result sets never latch a fault",
            BrightDataZoneHealth.isHealthy());
    }

    // ---------- (d) the streak threshold ----------

    private static void testStreakLatchesFault() throws Exception
    {
        BrightDataZoneHealth.reset();

        searchWithOrganic(organic(GOTO_ABSOLUTE0, GOTO_ABSOLUTE0));
        check("one all-unusable query does not latch a fault", BrightDataZoneHealth.isHealthy());

        searchWithOrganic(organic(GOTO_ABSOLUTE0, GOTO_ABSOLUTE0));
        check("two all-unusable queries do not latch a fault", BrightDataZoneHealth.isHealthy());

        searchWithOrganic(organic(GOTO_ABSOLUTE0, GOTO_ABSOLUTE0));
        check("three all-unusable queries latch a fault", !BrightDataZoneHealth.isHealthy());

        // Fail-fast: once latched, further calls must not reach the network at all.
        boolean threw0 = false;
        try
        {
            BrightDataHttp.post("https://api.brightdata.com/request", "{}", "token", "serp_api3");
        }
        catch (BrightDataZoneException expected0)
        {
            threw0 = true;
        }
        check("a latched parser fault makes later posts fail fast", threw0);
    }

    private static void testUsableResultBreaksStreak() throws Exception
    {
        BrightDataZoneHealth.reset();

        searchWithOrganic(organic(GOTO_ABSOLUTE0, GOTO_ABSOLUTE0));
        searchWithOrganic(organic(GOTO_ABSOLUTE0, GOTO_ABSOLUTE0));
        searchWithOrganic(organic("https://www.linkedin.com/in/someone", GOTO_ABSOLUTE0));
        searchWithOrganic(organic(GOTO_ABSOLUTE0, GOTO_ABSOLUTE0));

        check("a usable result resets the streak so an isolated bad query is tolerated",
            BrightDataZoneHealth.isHealthy());
    }

    // ---------- (e) the operator-facing message ----------

    private static void testFaultSummaryNamesTheParsingProblem() throws Exception
    {
        BrightDataZoneHealth.reset();

        for (int i = 0; i < 3; i++)
        {
            searchWithOrganic(organic(GOTO_ABSOLUTE0, GOTO_ABSOLUTE0));
        }

        String summary0 = BrightDataZoneHealth.faultSummary();

        check("fault summary is produced", summary0 != null);
        check("fault summary distinguishes parsing from a dead zone",
            summary0 != null && summary0.contains("no usable destination URLs"));
        check("fault summary says it is not a search miss",
            summary0 != null && summary0.contains("not a search miss"));
        check("fault summary names the /goto wrapper for the operator",
            summary0 != null && summary0.contains("/goto"));
        check("fault summary quotes an offending sample URL",
            summary0 != null && summary0.contains("google.com/goto"));
    }

    // ---------- (f) Bright Data cost metering ----------

    private static void testBrightDataCallsAreMetered() throws Exception
    {
        BrightDataZoneHealth.reset();

        CostMeter meter0 = new CostMeter(10.00);
        CostMeter.bind(meter0);

        try
        {
            BrightDataHttp.callFactory = fakeFactory(200, null, "{}");
            for (int i = 0; i < 4; i++)
            {
                BrightDataHttp.post("https://api.brightdata.com/request", "{}", "t", "serp_api3");
            }
        }
        finally
        {
            CostMeter.unbind();
        }

        // $1.50 CPM -> $0.0015 per request -> 4 requests = $0.0060
        check("four Bright Data requests are counted",
            meter0.toJson().getLong("brightDataCalls") == 4);
        check("four Bright Data requests cost $0.0060 at $1.50 CPM",
            Math.abs(meter0.usd() - 0.0060) < 1e-9);
        check("Bright Data spend is reported separately as well as in the total",
            Math.abs(meter0.toJson().getDouble("brightDataUsd") - 0.0060) < 1e-9);
        check("Bright Data requests do not inflate the LLM call count",
            meter0.toJson().getLong("calls") == 0);
    }

    private static void testZoneErrorResponseIsStillMetered() throws Exception
    {
        BrightDataZoneHealth.reset();

        CostMeter meter0 = new CostMeter(10.00);
        CostMeter.bind(meter0);

        try
        {
            BrightDataHttp.callFactory = fakeFactory(200, "x-brd-err-code: zone_not_found", "{}");
            BrightDataHttp.post("https://api.brightdata.com/request", "{}", "t", "serp_api3");
        }
        catch (BrightDataZoneException expected0)
        {
            // The request still reached Bright Data, so it still counts.
        }
        finally
        {
            CostMeter.unbind();
        }

        check("a request that comes back with a zone error is still billed",
            meter0.toJson().getLong("brightDataCalls") == 1);
    }

    private static void testCeilingCountsBrightDataSpend() throws Exception
    {
        BrightDataZoneHealth.reset();

        // Ceiling low enough that Bright Data spend alone must trip it: 3 requests
        // = $0.0045, over a $0.0030 ceiling.
        CostMeter meter0 = new CostMeter(0.0030);
        CostMeter.bind(meter0);

        try
        {
            BrightDataHttp.callFactory = fakeFactory(200, null, "{}");
            for (int i = 0; i < 3; i++)
            {
                BrightDataHttp.post("https://api.brightdata.com/request", "{}", "t", "serp_api3");
            }
        }
        finally
        {
            CostMeter.unbind();
        }

        boolean threw0 = false;
        try
        {
            meter0.checkCeiling();
        }
        catch (Exception expected0)
        {
            threw0 = true;
        }

        check("Bright Data spend alone can trip the run cost ceiling", threw0);
    }

    // ---------- helpers ----------

    /* Runs one search against a canned organic array, with no network access. */
    private static ArrayList<SerpResult> searchWithOrganic(String organicJson0) throws Exception
    {
        BrightDataHttp.callFactory =
            fakeFactory(200, null, "{\"organic\":[" + organicJson0 + "]}");

        return new BrightDataSerpClient().search("site:linkedin.com/company \"Test\"", 10);
    }

    private static String organic(String firstLink0, String secondLink0)
    {
        return "{\"title\":\"First\",\"link\":\"" + firstLink0 + "\",\"description\":\"d\"},"
             + "{\"title\":\"Second\",\"link\":\"" + secondLink0 + "\",\"description\":\"d\"}";
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
}
