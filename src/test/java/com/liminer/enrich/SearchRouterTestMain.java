package com.liminer.enrich;

import java.util.ArrayList;
import java.util.Arrays;

/*
 * Offline verification of SearchRouter's ordered fallback chain and
 * SerpUrls's shared URL-validation rules. No network access, no env vars.
 */
public class SearchRouterTestMain
{
    private static int failures0 = 0;

    public static void main(String[] args0)
    {
        try
        {
            testFirstProviderResultsShortCircuit();
            testEmptyFirstProviderFallsThrough();
            testThrowingFirstProviderFallsThrough();
            testUnavailableProviderIsSkipped();
            testAllEmptyReturnsEmpty();
            testThreeConsecutiveEmptiesDemote();
            testNonEmptyResultResetsStreak();
            testResetReenablesDemotedProvider();
            testSerpUrlsRejectsGoogleGoto();
            testSerpUrlsAcceptsGenuineUrl();
            testExtraBlockedHostsOverload();
        }
        catch (Exception exception0)
        {
            System.out.println("TEST FAILED: " + exception0.getMessage());
            exception0.printStackTrace();
            System.exit(1);
        }

        if (failures0 > 0)
        {
            System.out.println("SEARCH_ROUTER_FAILED: " + failures0 + " check(s) failed");
            System.exit(1);
        }

        System.out.println("SEARCH_ROUTER_OK");
    }

    private static void testFirstProviderResultsShortCircuit() throws Exception
    {
        FakeProvider first0 = FakeProvider.returning("first", results("https://a.example.com"));
        FakeProvider second0 = FakeProvider.returning("second", results("https://b.example.com"));

        SearchRouter router0 = new SearchRouter(Arrays.asList(first0, second0));
        ArrayList<SerpResult> results0 = router0.search("q", 10);

        check("first provider's results are returned", results0.size() == 1
            && "https://a.example.com".equals(results0.get(0).url));
        check("second provider is never called", second0.callCount0 == 0);
    }

    private static void testEmptyFirstProviderFallsThrough() throws Exception
    {
        FakeProvider first0 = FakeProvider.returning("first", results());
        FakeProvider second0 = FakeProvider.returning("second", results("https://b.example.com"));

        SearchRouter router0 = new SearchRouter(Arrays.asList(first0, second0));
        ArrayList<SerpResult> results0 = router0.search("q", 10);

        check("empty first provider falls through to second",
            results0.size() == 1 && "https://b.example.com".equals(results0.get(0).url));
    }

    private static void testThrowingFirstProviderFallsThrough() throws Exception
    {
        FakeProvider first0 = FakeProvider.throwing("first");
        FakeProvider second0 = FakeProvider.returning("second", results("https://b.example.com"));

        SearchRouter router0 = new SearchRouter(Arrays.asList(first0, second0));
        ArrayList<SerpResult> results0 = router0.search("q", 10);

        check("a thrown exception does not propagate and falls through",
            results0.size() == 1 && "https://b.example.com".equals(results0.get(0).url));
    }

    private static void testUnavailableProviderIsSkipped() throws Exception
    {
        FakeProvider first0 = FakeProvider.returning("first", results("https://a.example.com"));
        first0.available0 = false;
        FakeProvider second0 = FakeProvider.returning("second", results("https://b.example.com"));

        SearchRouter router0 = new SearchRouter(Arrays.asList(first0, second0));
        ArrayList<SerpResult> results0 = router0.search("q", 10);

        check("unavailable provider is skipped without being called", first0.callCount0 == 0);
        check("second provider is used instead",
            results0.size() == 1 && "https://b.example.com".equals(results0.get(0).url));
    }

    private static void testAllEmptyReturnsEmpty() throws Exception
    {
        FakeProvider first0 = FakeProvider.returning("first", results());
        FakeProvider second0 = FakeProvider.returning("second", results());

        SearchRouter router0 = new SearchRouter(Arrays.asList(first0, second0));
        ArrayList<SerpResult> results0 = router0.search("q", 10);

        check("all providers empty yields an empty list, not an exception", results0.isEmpty());
    }

    private static void testThreeConsecutiveEmptiesDemote() throws Exception
    {
        FakeProvider first0 = FakeProvider.returning("first", results());
        FakeProvider second0 = FakeProvider.returning("second", results("https://b.example.com"));

        SearchRouter router0 = new SearchRouter(Arrays.asList(first0, second0));

        router0.search("q1", 10);
        router0.search("q2", 10);
        router0.search("q3", 10);
        check("first provider was called for the first three queries", first0.callCount0 == 3);

        router0.search("q4", 10);
        check("first provider is demoted and not invoked on the 4th call", first0.callCount0 == 3);
    }

    private static void testNonEmptyResultResetsStreak() throws Exception
    {
        FakeProvider first0 = new FakeProvider("first");
        first0.queue0.add(results());
        first0.queue0.add(results());
        first0.queue0.add(results("https://a.example.com"));
        first0.queue0.add(results());
        first0.queue0.add(results());

        FakeProvider second0 = FakeProvider.returning("second", results("https://b.example.com"));

        SearchRouter router0 = new SearchRouter(Arrays.asList(first0, second0));

        router0.search("q1", 10); // empty (streak 1)
        router0.search("q2", 10); // empty (streak 2)
        router0.search("q3", 10); // usable, resets streak
        router0.search("q4", 10); // empty (streak 1)
        router0.search("q5", 10); // empty (streak 2)

        check("a usable result before the 3rd empty resets the streak, provider still called",
            first0.callCount0 == 5);
    }

    private static void testResetReenablesDemotedProvider() throws Exception
    {
        FakeProvider first0 = FakeProvider.returning("first", results());
        FakeProvider second0 = FakeProvider.returning("second", results("https://b.example.com"));

        SearchRouter router0 = new SearchRouter(Arrays.asList(first0, second0));

        router0.search("q1", 10);
        router0.search("q2", 10);
        router0.search("q3", 10);
        check("provider demoted after 3 empties", first0.callCount0 == 3);

        router0.search("q4", 10);
        check("demoted provider not called before reset", first0.callCount0 == 3);

        router0.reset();
        router0.search("q5", 10);
        check("reset() re-enables the demoted provider", first0.callCount0 == 4);
    }

    private static void testSerpUrlsRejectsGoogleGoto() throws Exception
    {
        check("google.com/goto redirect stub is rejected",
            !SerpUrls.isUsefulUrl("https://www.google.com/goto?url=CAES..."));
    }

    private static void testSerpUrlsAcceptsGenuineUrl() throws Exception
    {
        check("a genuine linkedin URL is accepted",
            SerpUrls.isUsefulUrl("https://www.linkedin.com/company/example"));
    }

    private static void testExtraBlockedHostsOverload() throws Exception
    {
        check("extraBlockedHosts0 rejects a duckduckgo.com URL",
            !SerpUrls.isUsefulUrl("https://duckduckgo.com/?q=x", "duckduckgo.com"));
    }

    // ---------- helpers ----------

    private static ArrayList<SerpResult> results(String... urls0)
    {
        ArrayList<SerpResult> list0 = new ArrayList<SerpResult>();
        int rank0 = 1;
        for (String url0 : urls0)
        {
            list0.add(new SerpResult("title", url0, "snippet", rank0++, "query"));
        }
        return list0;
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

    private static final class FakeProvider implements SearchProvider
    {
        private final String name0;
        boolean available0 = true;
        boolean throwOnSearch0 = false;
        int callCount0 = 0;
        final ArrayList<ArrayList<SerpResult>> queue0 = new ArrayList<ArrayList<SerpResult>>();

        FakeProvider(String name1)
        {
            name0 = name1;
        }

        static FakeProvider returning(String name0, ArrayList<SerpResult> results0)
        {
            FakeProvider provider0 = new FakeProvider(name0);
            provider0.staticResult0 = results0;
            return provider0;
        }

        static FakeProvider throwing(String name0)
        {
            FakeProvider provider0 = new FakeProvider(name0);
            provider0.throwOnSearch0 = true;
            return provider0;
        }

        private ArrayList<SerpResult> staticResult0 = null;

        @Override
        public String name()
        {
            return name0;
        }

        @Override
        public boolean isAvailable()
        {
            return available0;
        }

        @Override
        public ArrayList<SerpResult> search(String query0, int maxResults0) throws Exception
        {
            callCount0++;

            if (throwOnSearch0)
            {
                throw new RuntimeException("simulated failure for " + name0);
            }

            if (!queue0.isEmpty())
            {
                return queue0.remove(0);
            }

            return staticResult0 != null ? staticResult0 : new ArrayList<SerpResult>();
        }
    }
}
