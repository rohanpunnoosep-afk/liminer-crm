package com.liminer.scout;

import com.liminer.enrich.DiscoveredLinkedInTarget;
import com.liminer.enrich.EmailFinder;
import com.liminer.enrich.LinkedInScrapeResult;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/*
 * ScoutSerpMergeTestMain — fully offline test for Phase 2 task 3 of the
 * Investor Scout plan: merging Pipeline-3 SERP/LinkedIn discovery into the
 * Scout funnel as a gated secondary channel. Builds synthetic SERP/LinkedIn
 * fixtures (no network, no Sheets) and drives them through
 * ScoutSerpAdapter -> InvestorScoutProcessor.gateSecondarySerpCandidates,
 * the exact gate the real funnel applies before fit scoring/append. Prints
 * SCOUT_SERP_MERGE_OK and exits 0 on success; exits 1 on any assertion
 * failure.
 */
public class ScoutSerpMergeTestMain
{
    private static int failures0 = 0;

    public static void main(String[] args0) throws Exception
    {
        testFailingResourcesGateIsDropped();
        testFailingTimingGateIsDropped();
        testPassingCandidateFlowsThroughAndIsSerpSourced();
        testDuplicateAgainstCrmIsDropped();

        if (failures0 > 0)
        {
            System.err.println("ScoutSerpMergeTestMain: " + failures0 + " failure(s)");
            System.exit(1);
        }
        System.out.println("SCOUT_SERP_MERGE_OK");
    }

    // -----------------------------------------------------------------------
    // (a) Candidate failing the resources gate must not be appended.
    // -----------------------------------------------------------------------

    private static void testFailingResourcesGateIsDropped() throws Exception
    {
        DiscoveredLinkedInTarget target0 = companyTarget(
            "https://www.linkedin.com/company/no-resources-family-office", "No Resources Family Office"
        );
        LinkedInScrapeResult scrape0 = companyScrape("No Resources Family Office", "", "US", "New York");

        // No RAUM/employee signal resolved (0/0) -> resources score sits at
        // the zero-data floor of 5, below SERP_MIN_RESOURCES_THRESHOLD (15).
        ScoutUniverseRecord record0 = ScoutSerpAdapter.mapCompanyTarget(target0, scrape0);

        Map<ScoutUniverseRecord, ScoutTimingEvents> timingEvents0 = new HashMap<ScoutUniverseRecord, ScoutTimingEvents>();
        timingEvents0.put(record0, recentNewRegistrantEvents());

        List<ScoutUniverseRecord> kept0 = runGate(list(record0), timingEvents0, new ScoutDedupeIndex());

        assertTrue(kept0.isEmpty(), "Candidate failing the resources gate should not survive the SERP merge gate");
    }

    // -----------------------------------------------------------------------
    // (b) Candidate failing the timing gate must not be appended.
    // -----------------------------------------------------------------------

    private static void testFailingTimingGateIsDropped() throws Exception
    {
        DiscoveredLinkedInTarget target0 = companyTarget(
            "https://www.linkedin.com/company/well-resourced-no-timing", "Well Resourced No Timing Capital"
        );
        LinkedInScrapeResult scrape0 = companyScrape("Well Resourced No Timing Capital", "", "US", "Boston");

        ScoutUniverseRecord record0 = ScoutSerpAdapter.mapCompanyTarget(target0, scrape0, 500_000_000.0, 40);

        // No timing events at all -> probabilityNow stays 0, below
        // SERP_MIN_PROBABILITY_NOW_THRESHOLD (1).
        Map<ScoutUniverseRecord, ScoutTimingEvents> timingEvents0 = new HashMap<ScoutUniverseRecord, ScoutTimingEvents>();

        List<ScoutUniverseRecord> kept0 = runGate(list(record0), timingEvents0, new ScoutDedupeIndex());

        assertTrue(kept0.isEmpty(), "Candidate failing the timing gate should not survive the SERP merge gate");
    }

    // -----------------------------------------------------------------------
    // (c) Candidate passing both gates flows through, marked SERP-sourced.
    // -----------------------------------------------------------------------

    private static void testPassingCandidateFlowsThroughAndIsSerpSourced() throws Exception
    {
        DiscoveredLinkedInTarget target0 = companyTarget(
            "https://www.linkedin.com/company/qualified-family-office", "Qualified Family Office"
        );
        LinkedInScrapeResult scrape0 = companyScrape("Qualified Family Office", "https://qualifiedfo.example.com", "US", "Chicago");

        ScoutUniverseRecord record0 = ScoutSerpAdapter.mapCompanyTarget(target0, scrape0, 500_000_000.0, 40);

        Map<ScoutUniverseRecord, ScoutTimingEvents> timingEvents0 = new HashMap<ScoutUniverseRecord, ScoutTimingEvents>();
        timingEvents0.put(record0, recentNewRegistrantEvents());

        Map<ScoutUniverseRecord, ScoutSignalScore> scores0 = new HashMap<ScoutUniverseRecord, ScoutSignalScore>();
        List<ScoutUniverseRecord> kept0 = runGate(list(record0), timingEvents0, new ScoutDedupeIndex(), scores0);

        assertTrue(kept0.size() == 1, "Candidate passing both gates should survive the SERP merge gate");
        assertTrue(kept0.contains(record0), "Surviving record should be the same candidate passed in");
        assertEquals(ScoutSerpAdapter.SOURCE_REGISTER_SERP, record0.sourceRegister, "Surviving record should be marked SERP-sourced");
        assertTrue(scores0.containsKey(record0), "Surviving record should have an attached ScoutSignalScore");
        assertTrue(scores0.get(record0).resources >= 15, "Surviving record's resources score should clear the SERP floor");
        assertTrue(scores0.get(record0).probabilityNow >= 1, "Surviving record's probabilityNow score should clear the SERP floor");
    }

    // -----------------------------------------------------------------------
    // (d) Candidate already in the client's CRM/dedupe index must be dropped
    //     even though it would otherwise pass both axis gates.
    // -----------------------------------------------------------------------

    private static void testDuplicateAgainstCrmIsDropped() throws Exception
    {
        DiscoveredLinkedInTarget target0 = companyTarget(
            "https://www.linkedin.com/company/already-in-crm-capital", "Already In CRM Capital"
        );
        LinkedInScrapeResult scrape0 = companyScrape("Already In CRM Capital", "https://alreadyincrm.example.com", "US", "Denver");

        ScoutUniverseRecord record0 = ScoutSerpAdapter.mapCompanyTarget(target0, scrape0, 500_000_000.0, 40);

        Map<ScoutUniverseRecord, ScoutTimingEvents> timingEvents0 = new HashMap<ScoutUniverseRecord, ScoutTimingEvents>();
        timingEvents0.put(record0, recentNewRegistrantEvents());

        ScoutDedupeIndex dedupe0 = new ScoutDedupeIndex();
        dedupe0.addWebsite("https://alreadyincrm.example.com");

        List<ScoutUniverseRecord> kept0 = runGate(list(record0), timingEvents0, dedupe0);

        assertTrue(kept0.isEmpty(), "Candidate already present in the CRM dedupe index should not survive the SERP merge gate");
    }

    // -----------------------------------------------------------------------
    // Fixture builders
    // -----------------------------------------------------------------------

    private static DiscoveredLinkedInTarget companyTarget(String url0, String serpTitle0)
    {
        return new DiscoveredLinkedInTarget(
            url0, DiscoveredLinkedInTarget.TYPE_COMPANY, "site:linkedin.com/company " + serpTitle0,
            serpTitle0 + " | LinkedIn", "Family office based in the US.", 1
        );
    }

    private static LinkedInScrapeResult companyScrape(String name0, String website0, String country0, String city0)
    {
        org.json.JSONObject json0 = new org.json.JSONObject();
        json0.put("company_name", name0);
        json0.put("website", website0);
        json0.put("country", country0);
        json0.put("city", city0);
        return LinkedInScrapeResult.fromJson("https://www.linkedin.com/company/x", DiscoveredLinkedInTarget.TYPE_COMPANY, json0);
    }

    private static ScoutTimingEvents recentNewRegistrantEvents()
    {
        ScoutTimingEvents events0 = new ScoutTimingEvents();
        String thisMonth0 = LocalDate.now().minusMonths(1).toString().substring(0, 7);
        events0.events.add(ScoutTimingEvents.Event.newRegistrant(thisMonth0));
        return events0;
    }

    private static List<ScoutUniverseRecord> list(ScoutUniverseRecord record0)
    {
        List<ScoutUniverseRecord> list0 = new ArrayList<ScoutUniverseRecord>();
        list0.add(record0);
        return list0;
    }

    private static List<ScoutUniverseRecord> runGate(
        List<ScoutUniverseRecord> candidates0,
        Map<ScoutUniverseRecord, ScoutTimingEvents> timingEvents0,
        ScoutDedupeIndex dedupeIndex0)
    {
        return runGate(candidates0, timingEvents0, dedupeIndex0, new HashMap<ScoutUniverseRecord, ScoutSignalScore>());
    }

    private static List<ScoutUniverseRecord> runGate(
        List<ScoutUniverseRecord> candidates0,
        Map<ScoutUniverseRecord, ScoutTimingEvents> timingEvents0,
        ScoutDedupeIndex dedupeIndex0,
        Map<ScoutUniverseRecord, ScoutSignalScore> scoresOut0)
    {
        InvestorScoutProcessor processor0 = new InvestorScoutProcessor(
            new FileScoutUniverseStore(), new ScoutUniverseIndexer(), new ScoutSignalScorer(), new ScoutFitScorer(), new EmailFinder(), new ScoutLedger()
        );
        return processor0.gateSecondarySerpCandidates(candidates0, timingEvents0, dedupeIndex0, scoresOut0, LocalDate.now());
    }

    // -----------------------------------------------------------------------
    // Assertion helpers
    // -----------------------------------------------------------------------

    private static void assertTrue(boolean cond0, String msg0)
    {
        if (!cond0)
        {
            System.err.println("FAIL: " + msg0);
            failures0++;
        }
    }

    private static void assertEquals(Object expected0, Object actual0, String msg0)
    {
        boolean eq0 = expected0 == null ? actual0 == null : expected0.equals(actual0);
        if (!eq0)
        {
            System.err.println("FAIL: " + msg0 + " (expected=" + expected0 + ", actual=" + actual0 + ")");
            failures0++;
        }
    }
}
