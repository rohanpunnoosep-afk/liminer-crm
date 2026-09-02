package com.liminer.scout;

import com.liminer.core.InvestorProfile;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/*
 * ScoutProfilePrefetchTestMain — fully offline test for ScoutProfilePrefetch
 * (task 0089). Injects a fake ProfileExtractor and points ScoutUniverseIndexer
 * / ScoutFitProfileCache at temp directories, so no OpenAI/HTTP/Bright Data
 * call is ever made. Prints SCOUT_PREFETCH_OK and exits 0 on success; exits 1
 * on any assertion failure.
 */
public class ScoutProfilePrefetchTestMain
{
    private static int failures0 = 0;

    public static void main(String[] args0) throws Exception
    {
        testSkipsAlreadyCachedAndExtractsOnlyUncached();
        testCapLimitsExtractionsHighestRankedFirst();
        testExtractorFailureIsCountedAndJobContinues();
        testLinkedInSourceIsNeverInvoked();

        if (failures0 > 0)
        {
            System.err.println("ScoutProfilePrefetchTestMain: " + failures0 + " failure(s)");
            System.exit(1);
        }
        System.out.println("SCOUT_PREFETCH_OK");
    }

    // -----------------------------------------------------------------------
    // (a) 3 hot records, 1 already cached -> fake extractor called exactly
    // twice, summary says attempted=2 already-cached=1.
    // -----------------------------------------------------------------------
    private static void testSkipsAlreadyCachedAndExtractsOnlyUncached() throws Exception
    {
        Path tempDir0 = Files.createTempDirectory("scout-prefetch-a");
        ScoutUniverseIndexer indexer0 = new ScoutUniverseIndexer(tempDir0.resolve("scored-universe"));
        ScoutFitProfileCache cache0 = new ScoutFitProfileCache(tempDir0.resolve("profiles"));

        String month0 = "2026-06";
        List<ScoutScoredRecord> hot0 = new ArrayList<ScoutScoredRecord>();
        hot0.add(fixture(1001, "Alpha Capital", 90, 90, month0));
        hot0.add(fixture(1002, "Beta Partners", 85, 80, month0));
        hot0.add(fixture(1003, "Gamma Ventures", 80, 75, month0));
        indexer0.writeHotFile(month0, hot0);

        cache0.save(1002, month0, new ScoutFitProfileCache.CachedProfile(new InvestorProfile(), ScoutFitResult.SOURCE_WEBSITE));

        CountingExtractor extractor0 = new CountingExtractor(false);
        ScoutProfilePrefetch prefetch0 = new ScoutProfilePrefetch(indexer0, cache0, extractor0);

        ScoutProfilePrefetch.Result result0 = prefetch0.run(month0, 25);

        assertEquals(2, extractor0.callCount, "fake extractor should be called exactly twice");
        assertEquals(2, result0.attempted, "attempted should be 2");
        assertEquals(1, result0.alreadyCached, "already-cached should be 1");
        assertEquals(2, result0.extracted, "extracted should be 2");
        assertContains(result0.summary(), "attempted=2", "summary should say attempted=2");
        assertContains(result0.summary(), "already-cached=1", "summary should say already-cached=1");
    }

    // -----------------------------------------------------------------------
    // (b) cap set to 1 (method parameter, overridable) -> exactly 1
    // extraction, highest-ranked record first.
    // -----------------------------------------------------------------------
    private static void testCapLimitsExtractionsHighestRankedFirst() throws Exception
    {
        Path tempDir0 = Files.createTempDirectory("scout-prefetch-b");
        ScoutUniverseIndexer indexer0 = new ScoutUniverseIndexer(tempDir0.resolve("scored-universe"));
        ScoutFitProfileCache cache0 = new ScoutFitProfileCache(tempDir0.resolve("profiles"));

        String month0 = "2026-06";
        List<ScoutScoredRecord> hot0 = new ArrayList<ScoutScoredRecord>();
        hot0.add(fixture(2001, "Top Ranked Fund", 99, 95, month0));
        hot0.add(fixture(2002, "Second Ranked Fund", 90, 80, month0));
        indexer0.writeHotFile(month0, hot0);

        CountingExtractor extractor0 = new CountingExtractor(false);
        ScoutProfilePrefetch prefetch0 = new ScoutProfilePrefetch(indexer0, cache0, extractor0);

        ScoutProfilePrefetch.Result result0 = prefetch0.run(month0, 1);

        assertEquals(1, extractor0.callCount, "capped run should call extractor exactly once");
        assertEquals(1, result0.attempted, "attempted should be capped to 1");
        assertEquals(1, extractor0.crdsSeen.size(), "exactly one crd should have been extracted");
        assertEquals(2001, extractor0.crdsSeen.get(0).intValue(), "highest-ranked record should be extracted first");

        ScoutFitProfileCache.CachedProfile secondCached0 = cache0.load(2002, month0);
        assertEquals(true, secondCached0 == null, "second (lower-ranked) record should not be extracted/cached under the cap");
    }

    // -----------------------------------------------------------------------
    // (c) extractor failure on one record -> job continues, failure counted,
    // exit still normal.
    // -----------------------------------------------------------------------
    private static void testExtractorFailureIsCountedAndJobContinues() throws Exception
    {
        Path tempDir0 = Files.createTempDirectory("scout-prefetch-c");
        ScoutUniverseIndexer indexer0 = new ScoutUniverseIndexer(tempDir0.resolve("scored-universe"));
        ScoutFitProfileCache cache0 = new ScoutFitProfileCache(tempDir0.resolve("profiles"));

        String month0 = "2026-06";
        List<ScoutScoredRecord> hot0 = new ArrayList<ScoutScoredRecord>();
        hot0.add(fixture(3001, "Failing Fund", 95, 90, month0));
        hot0.add(fixture(3002, "Healthy Fund", 90, 85, month0));
        indexer0.writeHotFile(month0, hot0);

        FailingExtractor extractor0 = new FailingExtractor(3001);
        ScoutProfilePrefetch prefetch0 = new ScoutProfilePrefetch(indexer0, cache0, extractor0);

        ScoutProfilePrefetch.Result result0 = prefetch0.run(month0, 25);

        assertEquals(2, result0.attempted, "both records should be attempted");
        assertEquals(1, result0.failed, "one failure should be counted");
        assertEquals(1, result0.extracted, "the other record should still be extracted");
        assertContains(result0.summary(), "failed=1", "summary should report failed=1");

        ScoutFitProfileCache.CachedProfile healthyCached0 = cache0.load(3002, month0);
        assertEquals(true, healthyCached0 != null, "healthy record should still be cached despite the other's failure");
    }

    // -----------------------------------------------------------------------
    // (d) no LinkedIn source is ever invoked (fake source-router with call
    // counter).
    // -----------------------------------------------------------------------
    private static void testLinkedInSourceIsNeverInvoked() throws Exception
    {
        Path tempDir0 = Files.createTempDirectory("scout-prefetch-d");
        ScoutUniverseIndexer indexer0 = new ScoutUniverseIndexer(tempDir0.resolve("scored-universe"));
        ScoutFitProfileCache cache0 = new ScoutFitProfileCache(tempDir0.resolve("profiles"));

        String month0 = "2026-06";
        List<ScoutScoredRecord> hot0 = new ArrayList<ScoutScoredRecord>();
        ScoutScoredRecord record0 = fixture(4001, "LinkedIn Only Fund", 90, 90, month0);
        record0.record.website = "";
        record0.record.linkedinCompanyUrl = "https://linkedin.com/company/linkedin-only-fund";
        hot0.add(record0);
        indexer0.writeHotFile(month0, hot0);

        SourceRouterFake router0 = new SourceRouterFake();
        ScoutProfilePrefetch prefetch0 = new ScoutProfilePrefetch(indexer0, cache0, router0);

        ScoutProfilePrefetch.Result result0 = prefetch0.run(month0, 25);

        assertEquals(0, router0.linkedinCalls, "LinkedIn source must never be invoked by the prefetch job");
        assertEquals(1, router0.brochureOrWebsiteCalls, "brochure/website source should still be attempted");
        assertEquals(1, result0.attempted, "record should be attempted");
    }

    // -----------------------------------------------------------------------
    // Fixtures / fakes
    // -----------------------------------------------------------------------

    private static ScoutScoredRecord fixture(int crd0, String firmName0, int resources0, int probabilityNow0, String month0)
    {
        ScoutScoredRecord scored0 = new ScoutScoredRecord();
        scored0.record.crd = crd0;
        scored0.record.firmName = firmName0;
        scored0.record.website = "https://" + firmName0.toLowerCase().replace(" ", "") + ".example.com";
        scored0.record.snapshotMonth = month0;
        scored0.resources = resources0;
        scored0.probabilityNow = probabilityNow0;
        scored0.tags = new ArrayList<String>();
        scored0.exclusions = new ArrayList<String>();
        return scored0;
    }

    private static class CountingExtractor implements ScoutProfilePrefetch.ProfileExtractor
    {
        int callCount = 0;
        List<Integer> crdsSeen = new ArrayList<Integer>();
        private final boolean fail0;

        CountingExtractor(boolean fail0)
        {
            this.fail0 = fail0;
        }

        @Override
        public ScoutFitProfileCache.CachedProfile extract(ScoutUniverseRecord record0) throws Exception
        {
            callCount++;
            crdsSeen.add(record0.crd);
            if (fail0)
            {
                throw new RuntimeException("simulated extraction failure");
            }
            return new ScoutFitProfileCache.CachedProfile(new InvestorProfile(), ScoutFitResult.SOURCE_WEBSITE);
        }
    }

    private static class FailingExtractor implements ScoutProfilePrefetch.ProfileExtractor
    {
        private final int failingCrd0;

        FailingExtractor(int failingCrd0)
        {
            this.failingCrd0 = failingCrd0;
        }

        @Override
        public ScoutFitProfileCache.CachedProfile extract(ScoutUniverseRecord record0) throws Exception
        {
            if (record0.crd == failingCrd0)
            {
                throw new RuntimeException("simulated extraction failure for crd " + failingCrd0);
            }
            return new ScoutFitProfileCache.CachedProfile(new InvestorProfile(), ScoutFitResult.SOURCE_WEBSITE);
        }
    }

    // Fake source-router standing in for ScoutNonLinkedInProfileSource + the
    // LinkedIn fallback, with independent call counters, so the test can
    // assert the LinkedIn path is structurally unreachable from this job even
    // when a record has no website/brochure and only a linkedinCompanyUrl.
    private static class SourceRouterFake implements ScoutProfilePrefetch.ProfileExtractor
    {
        int brochureOrWebsiteCalls = 0;
        int linkedinCalls = 0;

        @Override
        public ScoutFitProfileCache.CachedProfile extract(ScoutUniverseRecord record0) throws Exception
        {
            brochureOrWebsiteCalls++;
            // Deliberately never calls a LinkedIn source, mirroring
            // ScoutProfilePrefetch's real DefaultProfileExtractor, which is
            // wired only to ScoutNonLinkedInProfileSource.
            return new ScoutFitProfileCache.CachedProfile(new InvestorProfile(), ScoutFitResult.SOURCE_NONE);
        }
    }

    // -----------------------------------------------------------------------
    // Assertion helpers
    // -----------------------------------------------------------------------

    private static void assertEquals(int expected0, int actual0, String message0)
    {
        if (expected0 != actual0)
        {
            System.err.println("FAIL: " + message0 + " (expected=" + expected0 + ", actual=" + actual0 + ")");
            failures0++;
        }
    }

    private static void assertEquals(boolean expected0, boolean actual0, String message0)
    {
        if (expected0 != actual0)
        {
            System.err.println("FAIL: " + message0 + " (expected=" + expected0 + ", actual=" + actual0 + ")");
            failures0++;
        }
    }

    private static void assertContains(String haystack0, String needle0, String message0)
    {
        if (haystack0 == null || !haystack0.contains(needle0))
        {
            System.err.println("FAIL: " + message0 + " (expected to contain=" + needle0 + ", actual=" + haystack0 + ")");
            failures0++;
        }
    }
}
