package com.liminer.scout;

import com.liminer.enrich.EdgarClient;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/*
 * ScoutSignalScorerTestMain — fully offline verification for ScoutSignalScorer
 * (RESOURCES / PROBABILITY_NOW scoring, 12-month linear age decay, reasons,
 * score-cache round trip) and the new EdgarClient offline parsers
 * (parseDailyIndex, parseFormDRelatedPersons). Prints SCOUT_SIGNALS_OK on
 * success. No network or LLM calls.
 */
public class ScoutSignalScorerTestMain
{
    public static void main(String[] args0)
    {
        try
        {
            testFreshFormDScoresHigh();
            testStaleFormDScoresNearZero();
            testResourcesRespondsToRaumBands();
            testAxesMoveIndependently();
            testReasonsPopulated();
            testScoreCacheRoundTrip();
            testParseDailyIndex();
            testParseFormDRelatedPersons();

            System.out.println("SCOUT_SIGNALS_OK");
        }
        catch (Throwable t0)
        {
            System.err.println("SCOUT_SIGNALS_FAILED: " + t0);
            t0.printStackTrace();
            System.exit(1);
        }
    }

    // ---- (a) synthetic records + events -------------------------------------

    private static void testFreshFormDScoresHigh() throws Exception
    {
        LocalDate today0 = LocalDate.of(2026, 7, 5);
        ScoutUniverseRecord rec0 = buildRecord(3001, 500_000_000.0, 50);

        ScoutTimingEvents events0 = new ScoutTimingEvents();
        events0.events.add(ScoutTimingEvents.Event.formD(
            "Example Fund III LP", today0.minusDays(10), 250_000_000.0, 180_000_000.0, today0.minusDays(20)));

        ScoutSignalScorer scorer0 = new ScoutSignalScorer();
        ScoutSignalScore score0 = scorer0.score(rec0, events0, today0);

        assertTrue(score0.probabilityNow >= 60,
            "fresh Form D with raise gap should score probabilityNow high, got " + score0.probabilityNow);
    }

    private static void testStaleFormDScoresNearZero() throws Exception
    {
        LocalDate today0 = LocalDate.of(2026, 7, 5);
        ScoutUniverseRecord rec0 = buildRecord(3002, 500_000_000.0, 50);

        // Identical event to the fresh case above, but 14 months old.
        ScoutTimingEvents events0 = new ScoutTimingEvents();
        events0.events.add(ScoutTimingEvents.Event.formD(
            "Example Fund III LP", today0.minusMonths(14), 250_000_000.0, 180_000_000.0, today0.minusMonths(14)));

        ScoutSignalScorer scorer0 = new ScoutSignalScorer();
        ScoutSignalScore score0 = scorer0.score(rec0, events0, today0);

        assertTrue(score0.probabilityNow == 0,
            "14-month-old event should decay to ~0 probabilityNow, got " + score0.probabilityNow);
    }

    private static void testResourcesRespondsToRaumBands() throws Exception
    {
        LocalDate today0 = LocalDate.of(2026, 7, 5);
        ScoutSignalScorer scorer0 = new ScoutSignalScorer();

        ScoutSignalScore small0 = scorer0.score(buildRecord(3003, 5_000_000.0, 5), new ScoutTimingEvents(), today0);
        ScoutSignalScore mid0   = scorer0.score(buildRecord(3004, 500_000_000.0, 50), new ScoutTimingEvents(), today0);
        ScoutSignalScore big0   = scorer0.score(buildRecord(3005, 30_000_000_000.0, 800), new ScoutTimingEvents(), today0);

        assertTrue(small0.resources < mid0.resources,
            "smaller RAUM should score a lower resources band, got small=" + small0.resources + " mid=" + mid0.resources);
        assertTrue(mid0.resources < big0.resources,
            "larger RAUM should score a higher resources band, got mid=" + mid0.resources + " big=" + big0.resources);

        // 990 total assets and GLEIF parent flag nudge resources upward.
        ScoutUniverseRecord nonprofit0 = buildRecord(3006, 0.0, 5);
        nonprofit0.nonprofitTotalAssets990 = 2_000_000_000.0;
        ScoutSignalScore nonprofitScore0 = scorer0.score(nonprofit0, new ScoutTimingEvents(), today0);
        assertTrue(nonprofitScore0.resources > small0.resources,
            "990 total assets should lift resources band when RAUM is 0, got " + nonprofitScore0.resources);

        ScoutUniverseRecord gleifBacked0 = buildRecord(3007, 500_000_000.0, 50);
        gleifBacked0.gleifParentFlag = Boolean.TRUE;
        ScoutSignalScore gleifScore0 = scorer0.score(gleifBacked0, new ScoutTimingEvents(), today0);
        assertTrue(gleifScore0.resources > mid0.resources,
            "GLEIF parent flag should add a resources bonus, got " + gleifScore0.resources + " vs " + mid0.resources);
    }

    private static void testAxesMoveIndependently() throws Exception
    {
        LocalDate today0 = LocalDate.of(2026, 7, 5);
        ScoutSignalScorer scorer0 = new ScoutSignalScorer();

        // High resources, no timing events -> probabilityNow should be 0 while resources is high.
        ScoutUniverseRecord richButQuiet0 = buildRecord(3008, 20_000_000_000.0, 300);
        ScoutSignalScore richQuietScore0 = scorer0.score(richButQuiet0, new ScoutTimingEvents(), today0);
        assertTrue(richQuietScore0.resources > 80, "expected high resources, got " + richQuietScore0.resources);
        assertTrue(richQuietScore0.probabilityNow == 0, "expected zero probabilityNow with no events, got "
            + richQuietScore0.probabilityNow);

        // Low resources, fresh new-registrant event -> probabilityNow high while resources stays low.
        ScoutUniverseRecord smallButActive0 = buildRecord(3009, 5_000_000.0, 3);
        ScoutTimingEvents events0 = new ScoutTimingEvents();
        events0.events.add(ScoutTimingEvents.Event.newRegistrant(yyyyMm(today0.minusDays(5))));
        ScoutSignalScore smallActiveScore0 = scorer0.score(smallButActive0, events0, today0);
        assertTrue(smallActiveScore0.resources < 20, "expected low resources, got " + smallActiveScore0.resources);
        assertTrue(smallActiveScore0.probabilityNow > 0, "expected nonzero probabilityNow, got "
            + smallActiveScore0.probabilityNow);
    }

    private static void testReasonsPopulated() throws Exception
    {
        LocalDate today0 = LocalDate.of(2026, 7, 5);
        ScoutUniverseRecord rec0 = buildRecord(3010, 500_000_000.0, 50);

        ScoutTimingEvents events0 = new ScoutTimingEvents();
        events0.events.add(ScoutTimingEvents.Event.formD(
            "Example Fund III LP", today0.minusDays(10), 250_000_000.0, 180_000_000.0, today0.minusDays(20)));

        ScoutSignalScorer scorer0 = new ScoutSignalScorer();
        ScoutSignalScore score0 = scorer0.score(rec0, events0, today0);

        assertTrue(!score0.reasons.isEmpty(), "reasons list should be populated");
        boolean foundFormDReason0 = false;
        for (String reason0 : score0.reasons)
        {
            if (reason0.contains("Form D") && reason0.contains("raised")) foundFormDReason0 = true;
        }
        assertTrue(foundFormDReason0, "expected a human-readable Form D reason, got " + score0.reasons);
    }

    private static void testScoreCacheRoundTrip() throws Exception
    {
        LocalDate today0 = LocalDate.of(2026, 7, 5);
        Path tmpDir0 = Files.createTempDirectory("scout-signal-scores-test");
        ScoutSignalScorer scorer0 = new ScoutSignalScorer(tmpDir0);

        ScoutUniverseRecord rec0 = buildRecord(3011, 500_000_000.0, 50);
        ScoutTimingEvents events0 = new ScoutTimingEvents();
        events0.events.add(ScoutTimingEvents.Event.newFoFFund(yyyyMm(today0.minusDays(1)), "New FoF Vehicle"));
        ScoutSignalScore score0 = scorer0.score(rec0, events0, today0);

        java.util.Map<Integer, ScoutSignalScore> toSave0 = new java.util.HashMap<Integer, ScoutSignalScore>();
        toSave0.put(rec0.crd, score0);
        scorer0.saveScores("2026-07", toSave0);

        Map<Integer, ScoutSignalScore> loaded0 = scorer0.loadScores("2026-07");
        assertTrue(loaded0.containsKey(3011), "cache round trip should contain crd 3011");
        ScoutSignalScore roundTripped0 = loaded0.get(3011);
        assertTrue(roundTripped0.resources == score0.resources,
            "round-tripped resources should match, got " + roundTripped0.resources + " vs " + score0.resources);
        assertTrue(roundTripped0.probabilityNow == score0.probabilityNow,
            "round-tripped probabilityNow should match, got " + roundTripped0.probabilityNow + " vs " + score0.probabilityNow);
        assertTrue(roundTripped0.reasons.size() == score0.reasons.size(),
            "round-tripped reasons should match count, got " + roundTripped0.reasons.size() + " vs " + score0.reasons.size());

        // Loading a month with no file should return empty, not throw.
        Map<Integer, ScoutSignalScore> missing0 = scorer0.loadScores("2019-01");
        assertTrue(missing0.isEmpty(), "loading a missing month should return empty map");

        deleteRecursively(tmpDir0);
    }

    // ---- (b) EDGAR daily-index parsing ---------------------------------------

    private static void testParseDailyIndex() throws Exception
    {
        String sample0 =
            "Description:           Master Index of EDGAR Dailies\n"
            + "Last Data Received:    March 15, 2026\n"
            + "Comments:              webmaster@sec.gov\n"
            + "Anonymous FTP:         ftp://ftp.sec.gov/edgar/\n"
            + "Cloud HTTP:            https://www.sec.gov/Archives/edgar/\n"
            + "CIK|Company Name|Form Type|Date Filed|Filename\n"
            + "--------------------------------------------------------------------------------\n"
            + "1234567|Example Fund III LP|D|2026-03-15|edgar/data/1234567/0001234567-26-000001.txt\n"
            + "7654321|Some Other Corp|8-K|2026-03-15|edgar/data/7654321/0007654321-26-000002.txt\n"
            + "1112223|Example Fund IV LP|D/A|2026-03-15|edgar/data/1112223/0001112223-26-000003.txt\n"
            + "9998887|Random Filer|10-Q|2026-03-15|edgar/data/9998887/0009998887-26-000004.txt\n";

        EdgarClient edgar0 = new EdgarClient();
        List<EdgarClient.DailyIndexEntry> entries0 = edgar0.parseDailyIndex(sample0);

        assertTrue(entries0.size() == 2, "expected 2 D/D-A entries, got " + entries0.size());
        boolean sawD0 = false, sawDA0 = false;
        for (EdgarClient.DailyIndexEntry e0 : entries0)
        {
            if ("D".equals(e0.formType) && "1234567".equals(e0.cik)) sawD0 = true;
            if ("D/A".equals(e0.formType) && "1112223".equals(e0.cik)) sawDA0 = true;
            assertTrue(!"8-K".equals(e0.formType) && !"10-Q".equals(e0.formType),
                "non-D form types should be filtered out, got " + e0.formType);
        }
        assertTrue(sawD0, "expected the D row to be parsed");
        assertTrue(sawDA0, "expected the D/A row to be parsed");
    }

    // ---- (c) Form D Related-Persons parsing ----------------------------------

    private static void testParseFormDRelatedPersons() throws Exception
    {
        String xml0 =
            "<?xml version=\"1.0\"?>\n"
            + "<edgarSubmission>\n"
            + "  <primaryIssuer><entityName>Example Fund III LP</entityName></primaryIssuer>\n"
            + "  <offeringData>\n"
            + "    <relatedPersonsList>\n"
            + "      <relatedPersonInfo>\n"
            + "        <relatedPersonName><firstName>Jane</firstName><lastName>Smith</lastName></relatedPersonName>\n"
            + "        <relatedPersonRelationshipList>\n"
            + "          <relationship>Executive Officer</relationship>\n"
            + "          <relationship>Director</relationship>\n"
            + "        </relatedPersonRelationshipList>\n"
            + "      </relatedPersonInfo>\n"
            + "      <relatedPersonInfo>\n"
            + "        <relatedPersonName><firstName>John</firstName><lastName>Doe</lastName></relatedPersonName>\n"
            + "        <relatedPersonRelationshipList>\n"
            + "          <relationship>Promoter</relationship>\n"
            + "        </relatedPersonRelationshipList>\n"
            + "      </relatedPersonInfo>\n"
            + "    </relatedPersonsList>\n"
            + "  </offeringData>\n"
            + "</edgarSubmission>\n";

        EdgarClient edgar0 = new EdgarClient();
        List<EdgarClient.RelatedPerson> people0 = edgar0.parseFormDRelatedPersons(xml0);

        assertTrue(people0.size() == 2, "expected 2 related persons, got " + people0.size());

        boolean sawJane0 = false, sawJohn0 = false;
        for (EdgarClient.RelatedPerson p0 : people0)
        {
            if ("Jane Smith".equals(p0.name))
            {
                sawJane0 = true;
                assertTrue(p0.relationship.contains("Executive Officer") && p0.relationship.contains("Director"),
                    "Jane Smith should have both relationships, got " + p0.relationship);
            }
            if ("John Doe".equals(p0.name))
            {
                sawJohn0 = true;
                assertTrue(p0.relationship.equals("Promoter"), "John Doe should be Promoter, got " + p0.relationship);
            }
        }
        assertTrue(sawJane0, "expected Jane Smith to be parsed");
        assertTrue(sawJohn0, "expected John Doe to be parsed");
    }

    // ---- helpers ---------------------------------------------------------------

    private static ScoutUniverseRecord buildRecord(int crd0, double raumTotal0, int employees0)
    {
        ScoutUniverseRecord r0 = new ScoutUniverseRecord();
        r0.crd = crd0;
        r0.firmName = "Test Adviser " + crd0;
        r0.raumTotal = raumTotal0;
        r0.raumDiscretionary = raumTotal0;
        r0.employees = employees0;
        r0.snapshotMonth = "2026-06";
        return r0;
    }

    private static String yyyyMm(LocalDate d0)
    {
        return String.format("%04d-%02d", d0.getYear(), d0.getMonthValue());
    }

    private static void deleteRecursively(Path dir0) throws IOException
    {
        if (!Files.exists(dir0)) return;
        Files.walk(dir0)
            .sorted(java.util.Comparator.reverseOrder())
            .forEach(p0 -> { try { Files.delete(p0); } catch (IOException ignored0) {} });
    }

    private static void assertTrue(boolean condition0, String message0)
    {
        if (!condition0) throw new AssertionError(message0);
    }
}
