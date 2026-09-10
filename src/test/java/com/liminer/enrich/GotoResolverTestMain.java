package com.liminer.enrich;

import java.io.IOException;
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
 * Offline verification of GotoResolver. No network access: GotoResolver.callFactory
 * is swapped for a fake Call.Factory returning canned 3xx / 200 responses, the same
 * seam BrightDataHttp.callFactory uses in SerpUnusableUrlTestMain.
 */
public class GotoResolverTestMain
{
    private static int failures0 = 0;

    private static final String GOTO_ABSOLUTE0 =
        "https://www.google.com/goto?url=CAESawHrOzAVrwmrH85LQ_zY9B1uZF8VrwC5rXNi-0ZXhtJIxbxY";
    private static final String DESTINATION0 = "https://www.linkedin.com/company/example";

    // Each stub-based test below uses its own blob suffix so the resolver's static,
    // process-wide cache never lets one test's canned answer leak into another's.
    private static String gotoRelative0(String tag0)
    {
        return "/goto?url=CAESawHrOzAVDC800tTuvL6jyarGYD2iBIbzI_IHwtXLKweyYLi8ZA0AgVTHK3X2JhWXijBK_" + tag0;
    }

    public static void main(String[] args0)
    {
        Call.Factory realFactory0 = GotoResolver.callFactory;

        try
        {
            testRelativeGotoResolves();
            testAbsoluteGotoResolves();
            testNoLocationHeaderReturnsEmpty();
            testNonAbsoluteLocationReturnsEmpty();
            testIOExceptionReturnsEmpty();
            testNonStubUrlUnchangedNoCall();
            testCacheHitsOnce();
            testEndToEndCleanGoogleRedirectUrl();
            testUrlQPathNoHttpCall();
        }
        catch (Exception exception0)
        {
            System.out.println("TEST FAILED: " + exception0.getMessage());
            exception0.printStackTrace();
            System.exit(1);
        }
        finally
        {
            GotoResolver.callFactory = realFactory0;
        }

        if (failures0 > 0)
        {
            System.out.println("GOTO_RESOLVER_FAILED: " + failures0 + " check(s) failed");
            System.exit(1);
        }

        System.out.println("GOTO_RESOLVER_OK");
    }

    private static void testRelativeGotoResolves() throws Exception
    {
        GotoResolver.callFactory = fakeFactory(302, DESTINATION0);

        String result0 = GotoResolver.resolve(gotoRelative0("relative"));

        check("relative /goto stub resolves to Location", DESTINATION0.equals(result0));
    }

    private static void testAbsoluteGotoResolves() throws Exception
    {
        GotoResolver.callFactory = fakeFactory(302, DESTINATION0);

        String result0 = GotoResolver.resolve(GOTO_ABSOLUTE0);

        check("absolute google.com/goto stub resolves to Location", DESTINATION0.equals(result0));
    }

    private static void testNoLocationHeaderReturnsEmpty() throws Exception
    {
        GotoResolver.callFactory = fakeFactory(200, null);

        String result0 = GotoResolver.resolve(gotoRelative0("no-location"));

        check("200 with no Location returns empty", "".equals(result0));
    }

    private static void testNonAbsoluteLocationReturnsEmpty() throws Exception
    {
        GotoResolver.callFactory = fakeFactory(302, "/relative/path");

        String result0 = GotoResolver.resolve(gotoRelative0("non-absolute-location"));

        check("non-absolute Location returns empty", "".equals(result0));
    }

    private static void testIOExceptionReturnsEmpty() throws Exception
    {
        GotoResolver.callFactory = request0 -> new ThrowingCall(request0);

        String result0 = GotoResolver.resolve(gotoRelative0("io-exception"));

        check("thrown IOException returns empty and does not propagate", "".equals(result0));
    }

    private static void testNonStubUrlUnchangedNoCall() throws Exception
    {
        CountingFactory counting0 = new CountingFactory(fakeFactory(302, DESTINATION0));
        GotoResolver.callFactory = counting0;

        String result0 = GotoResolver.resolve("https://www.linkedin.com/company/x");

        check("non-stub URL returned unchanged", "https://www.linkedin.com/company/x".equals(result0));
        check("non-stub URL makes zero HTTP calls", counting0.count() == 0);
    }

    private static void testCacheHitsOnce() throws Exception
    {
        String stub0 = gotoRelative0("cache-hit");
        CountingFactory counting0 = new CountingFactory(fakeFactory(302, DESTINATION0));
        GotoResolver.callFactory = counting0;

        GotoResolver.resolve(stub0);
        GotoResolver.resolve(stub0);

        check("two resolves of the same stub make exactly one HTTP call", counting0.count() == 1);
    }

    private static void testEndToEndCleanGoogleRedirectUrl() throws Exception
    {
        GotoResolver.callFactory = fakeFactory(302, DESTINATION0);

        String result0 = BrightDataSerpClient.cleanGoogleRedirectUrl(gotoRelative0("end-to-end"));

        check("cleanGoogleRedirectUrl resolves /goto end-to-end", DESTINATION0.equals(result0));
    }

    private static void testUrlQPathNoHttpCall() throws Exception
    {
        CountingFactory counting0 = new CountingFactory(fakeFactory(302, DESTINATION0));
        GotoResolver.callFactory = counting0;

        String result0 = BrightDataSerpClient.cleanGoogleRedirectUrl(
            "/url?q=https%3A%2F%2Fexample.com");

        check("/url? path still resolves without regression", "https://example.com".equals(result0));
        check("/url? path makes no HTTP call", counting0.count() == 0);
    }

    // ---------- helpers ----------

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

    private static Call.Factory fakeFactory(int status0, String locationHeader0)
    {
        return request0 -> new FakeCall(request0, status0, locationHeader0);
    }

    private static final class CountingFactory implements Call.Factory
    {
        private final Call.Factory delegate0;
        private int count0 = 0;

        CountingFactory(Call.Factory delegate1)
        {
            delegate0 = delegate1;
        }

        int count()
        {
            return count0;
        }

        @Override
        public Call newCall(Request request0)
        {
            count0++;
            return delegate0.newCall(request0);
        }
    }

    private static final class ThrowingCall implements Call
    {
        private final Request request0;

        ThrowingCall(Request request1)
        {
            request0 = request1;
        }

        @Override
        public Request request()
        {
            return request0;
        }

        @Override
        public Response execute() throws IOException
        {
            throw new IOException("simulated network failure");
        }

        @Override
        public void enqueue(Callback responseCallback0)
        {
            throw new UnsupportedOperationException("async not used by GotoResolver");
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
            return new ThrowingCall(request0);
        }
    }

    private static final class FakeCall implements Call
    {
        private final Request request0;
        private final int status0;
        private final String locationHeader0;

        FakeCall(Request request1, int status1, String locationHeader1)
        {
            request0 = request1;
            status0 = status1;
            locationHeader0 = locationHeader1;
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
            if (locationHeader0 != null)
            {
                headersBuilder0.add("Location", locationHeader0);
            }

            return new Response.Builder()
                .request(request0)
                .protocol(Protocol.HTTP_1_1)
                .code(status0)
                .message("OK")
                .headers(headersBuilder0.build())
                .body(ResponseBody.create("", MediaType.get("text/html")))
                .build();
        }

        @Override
        public void enqueue(Callback responseCallback0)
        {
            throw new UnsupportedOperationException("async not used by GotoResolver");
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
            return new FakeCall(request0, status0, locationHeader0);
        }
    }
}
