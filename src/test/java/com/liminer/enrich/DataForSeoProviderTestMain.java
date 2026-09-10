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
 * Offline verification of DataForSeoSerpProvider and the CostMeter per-provider
 * pricing it depends on. No network access and no DATAFORSEO_LOGIN/PASSWORD
 * needed: DataForSeoSerpProvider.callFactory is swapped for a fake Call.Factory
 * returning canned bodies, the same seam pattern as SerpUnusableUrlTestMain uses
 * for BrightDataHttp.callFactory.
 */
public class DataForSeoProviderTestMain
{
    private static int failures0 = 0;

    private static final String GOTO_STUB0 =
        "https://www.google.com/goto?url=CAESawHrOzAVDC800tTuvL6jyarGYD2iBIbzI_IHwtXLKweyYLi8ZA0";

    public static void main(String[] args0)
    {
        Call.Factory realFactory0 = DataForSeoSerpProvider.callFactory;

        try
        {
            testSuccessfulResponseMapsThreeResults();
            testNonOrganicItemsAreFiltered();
            testGotoStubIsDropped();
            testDepthCapsReturnedList();
            testNon2xxStatusThrows();
            testNonZeroTaskStatusCodeThrows();
            testMalformedJsonThrows();
            testIsAvailableRequiresBothCredentials();
            testBlankQueryReturnsEmptyWithNoHttpCall();
            testCostMeterArithmetic();
            testNoMessageLeaksPassword();
        }
        catch (Exception exception0)
        {
            System.out.println("TEST FAILED: " + exception0.getMessage());
            exception0.printStackTrace();
            System.exit(1);
        }
        finally
        {
            DataForSeoSerpProvider.callFactory = realFactory0;
            CostMeter.unbind();
        }

        if (failures0 > 0)
        {
            System.out.println("DATAFORSEO_FAILED: " + failures0 + " check(s) failed");
            System.exit(1);
        }

        System.out.println("DATAFORSEO_OK");
    }

    // ---------- successful mapping ----------

    private static void testSuccessfulResponseMapsThreeResults() throws Exception
    {
        String body0 = tasksBody0(0, items0(
            organicItem0("https://www.linkedin.com/company/a", "A", "desc a", 1),
            organicItem0("https://www.linkedin.com/company/b", "B", "desc b", 2),
            organicItem0("https://www.linkedin.com/company/c", "C", "desc c", 3)));

        DataForSeoSerpProvider.callFactory = fakeFactory(200, body0);

        ArrayList<SerpResult> results0 = new DataForSeoSerpProvider().search("test query", 10);

        check("three organic items map to three results", results0.size() == 3);
        check("url mapped from url", "https://www.linkedin.com/company/a".equals(results0.get(0).url));
        check("title mapped from title", "A".equals(results0.get(0).title));
        check("snippet mapped from description", "desc a".equals(results0.get(0).snippet));
        check("rank mapped from rank_group", results0.get(0).rank == 1);
    }

    // ---------- filtering ----------

    private static void testNonOrganicItemsAreFiltered() throws Exception
    {
        String body0 = tasksBody0(0, items0(
            organicItem0("https://www.linkedin.com/company/a", "A", "desc a", 1),
            nonOrganicItem0("https://www.example.com/ad", "featured_snippet"),
            nonOrganicItem0("https://www.example.com/ad2", "paid")));

        DataForSeoSerpProvider.callFactory = fakeFactory(200, body0);

        ArrayList<SerpResult> results0 = new DataForSeoSerpProvider().search("test query", 10);

        check("non-organic items are filtered out", results0.size() == 1);
        check("the surviving item is the organic one",
            results0.size() == 1 && "https://www.linkedin.com/company/a".equals(results0.get(0).url));
    }

    private static void testGotoStubIsDropped() throws Exception
    {
        String body0 = tasksBody0(0, items0(
            organicItem0("https://www.linkedin.com/company/a", "A", "desc a", 1),
            organicItem0(GOTO_STUB0, "Stub", "desc stub", 2)));

        DataForSeoSerpProvider.callFactory = fakeFactory(200, body0);

        ArrayList<SerpResult> results0 = new DataForSeoSerpProvider().search("test query", 10);

        check("a goto stub is dropped, only the genuine URL survives", results0.size() == 1);
        check("the genuine URL is the one kept",
            results0.size() == 1 && "https://www.linkedin.com/company/a".equals(results0.get(0).url));
    }

    private static void testDepthCapsReturnedList() throws Exception
    {
        String body0 = tasksBody0(0, items0(
            organicItem0("https://www.linkedin.com/company/a", "A", "desc a", 1),
            organicItem0("https://www.linkedin.com/company/b", "B", "desc b", 2),
            organicItem0("https://www.linkedin.com/company/c", "C", "desc c", 3)));

        DataForSeoSerpProvider.callFactory = fakeFactory(200, body0);

        ArrayList<SerpResult> results0 = new DataForSeoSerpProvider().search("test query", 2);

        check("maxResults0 caps the returned list", results0.size() == 2);
    }

    // ---------- failure handling ----------

    private static void testNon2xxStatusThrows() throws Exception
    {
        DataForSeoSerpProvider.callFactory = fakeFactory(500, "{}");

        boolean threw0 = false;
        try
        {
            new DataForSeoSerpProvider().search("test query", 10);
        }
        catch (Exception expected0)
        {
            threw0 = true;
        }

        check("a non-2xx status throws rather than returning junk", threw0);
    }

    private static void testNonZeroTaskStatusCodeThrows() throws Exception
    {
        String body0 = tasksBody0(40501, items0());

        DataForSeoSerpProvider.callFactory = fakeFactory(200, body0);

        boolean threw0 = false;
        try
        {
            new DataForSeoSerpProvider().search("test query", 10);
        }
        catch (Exception expected0)
        {
            threw0 = true;
        }

        check("a non-zero task status_code throws", threw0);
    }

    private static void testMalformedJsonThrows() throws Exception
    {
        DataForSeoSerpProvider.callFactory = fakeFactory(200, "{not json");

        boolean threw0 = false;
        try
        {
            new DataForSeoSerpProvider().search("test query", 10);
        }
        catch (Exception expected0)
        {
            threw0 = true;
        }

        check("malformed JSON throws", threw0);
    }

    // ---------- availability ----------

    private static void testIsAvailableRequiresBothCredentials() throws Exception
    {
        // Env vars are read once into static finals at class-load time, so this test
        // can only assert against whatever environment this JVM actually has -- the
        // verify command runs with neither DATAFORSEO_LOGIN nor DATAFORSEO_PASSWORD
        // set, so isAvailable() must be false in that case.
        String login0 = System.getenv("DATAFORSEO_LOGIN");
        String password0 = System.getenv("DATAFORSEO_PASSWORD");

        boolean bothSet0 = login0 != null && !login0.trim().isEmpty()
            && password0 != null && !password0.trim().isEmpty();

        boolean available0 = new DataForSeoSerpProvider().isAvailable();

        check("isAvailable() is false unless both credentials are set",
            available0 == bothSet0);
    }

    // ---------- blank query ----------

    private static void testBlankQueryReturnsEmptyWithNoHttpCall() throws Exception
    {
        DataForSeoSerpProvider.callFactory = request0 ->
        {
            throw new AssertionError("no HTTP call should be made for a blank query");
        };

        ArrayList<SerpResult> results0 = new DataForSeoSerpProvider().search("   ", 10);

        check("a blank query returns an empty list", results0.isEmpty());
    }

    // ---------- CostMeter arithmetic ----------

    private static void testCostMeterArithmetic() throws Exception
    {
        CostMeter meter0 = new CostMeter(10.00);
        CostMeter.bind(meter0);

        try
        {
            for (int i = 0; i < 4; i++)
            {
                meter0.recordSearch("dataforseo");
            }

            // $2.00 CPM -> $0.0020 per request -> 4 requests = $0.0080
            check("four dataforseo requests cost $0.0080 at $2.00 CPM",
                Math.abs(meter0.usd() - 0.0080) < 1e-9);

            meter0.recordBrightData("serp_api2");

            // $1.50 CPM -> $0.0015 per request, on top of the $0.0080 above.
            check("recordBrightData still prices at $0.0015 per call",
                Math.abs(meter0.usd() - 0.0095) < 1e-9);

            check("legacy brightDataCalls counter still increments",
                meter0.toJson().getLong("brightDataCalls") == 1);
        }
        finally
        {
            CostMeter.unbind();
        }
    }

    // ---------- no credential leakage ----------

    private static void testNoMessageLeaksPassword() throws Exception
    {
        String secretPassword0 = "super-secret-dataforseo-password";

        DataForSeoSerpProvider.callFactory = fakeFactory(500, "{}");

        String message0 = "";
        try
        {
            new DataForSeoSerpProvider().search("test query", 10);
        }
        catch (Exception exception0)
        {
            message0 = String.valueOf(exception0.getMessage());
        }

        check("failure message never contains a credential value",
            !message0.contains(secretPassword0) && !message0.contains("test-login"));
    }

    // ---------- helpers ----------

    private static String tasksBody0(int taskStatusCode0, String itemsArrayJson0)
    {
        return "{\"tasks\":[{\"status_code\":" + taskStatusCode0
            + ",\"result\":[{\"items\":" + itemsArrayJson0 + "}]}]}";
    }

    private static String items0(String... items1)
    {
        StringBuilder builder0 = new StringBuilder("[");
        for (int i = 0; i < items1.length; i++)
        {
            if (i > 0)
            {
                builder0.append(",");
            }
            builder0.append(items1[i]);
        }
        builder0.append("]");
        return builder0.toString();
    }

    private static String organicItem0(String url0, String title0, String description0, int rankGroup0)
    {
        return "{\"type\":\"organic\",\"url\":\"" + url0 + "\",\"title\":\"" + title0
            + "\",\"description\":\"" + description0 + "\",\"rank_group\":" + rankGroup0 + "}";
    }

    private static String nonOrganicItem0(String url0, String type0)
    {
        return "{\"type\":\"" + type0 + "\",\"url\":\"" + url0 + "\",\"title\":\"ad\","
            + "\"description\":\"ad\",\"rank_group\":1}";
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

    private static Call.Factory fakeFactory(int status0, String body0)
    {
        return request0 -> new FakeCall(request0, status0, body0);
    }

    private static final class FakeCall implements Call
    {
        private final Request request0;
        private final int status0;
        private final String body0;

        FakeCall(Request request1, int status1, String body1)
        {
            request0 = request1;
            status0 = status1;
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
            return new Response.Builder()
                .request(request0)
                .protocol(Protocol.HTTP_1_1)
                .code(status0)
                .message("OK")
                .headers(new Headers.Builder().build())
                .body(ResponseBody.create(body0, MediaType.get("application/json")))
                .build();
        }

        @Override
        public void enqueue(Callback responseCallback0)
        {
            throw new UnsupportedOperationException("async not used by DataForSeoSerpProvider");
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
            return new FakeCall(request0, status0, body0);
        }
    }
}
