package com.liminer.scout;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/*
 * ScoutUniverseIndexerTestMain — fully offline verification for
 * ScoutUniverseIndexer (task 0088): builds a small synthetic fixture in code
 * (no HTTP, no cached-score file dependency beyond an in-memory map), indexes
 * it into a temp base dir, and asserts tagging/exclusion/sort/hot-tier/funnel
 * behavior. Prints SCOUT_INDEXER_OK on success, exits 1 on any failure.
 */
public class ScoutUniverseIndexerTestMain
{
    public static void main(String[] args0)
    {
        try
        {
            run();
            System.out.println("SCOUT_INDEXER_OK");
        }
        catch (Throwable t0)
        {
            System.err.println("SCOUT_INDEXER_FAILED: " + t0);
            t0.printStackTrace();
            System.exit(1);
        }
    }

    private static void run() throws Exception
    {
        // (a) $5B-RAUM FoF allocator, cached scores 85/60 -> hot tier, correct tags, no exclusions.
        ScoutUniverseRecord fof0 = new ScoutUniverseRecord();
        fof0.crd = 2001;
        fof0.firmName = "Vast Allocators LLC";
        fof0.country = "United States";
        fof0.state = "NY";
        fof0.raumTotal = 5_000_000_000.0;
        fof0.clientTypes.add("Pooled Investment Vehicles");
        ScoutFundRecord fofFund0 = new ScoutFundRecord();
        fofFund0.name = "Vast Fund of Funds I";
        fofFund0.type = "Fund of Funds";
        fof0.funds.add(fofFund0);

        // (b) $10M firm -> RAUM_BELOW_FLOOR, excluded from hot tier but present in full file.
        ScoutUniverseRecord tiny0 = new ScoutUniverseRecord();
        tiny0.crd = 2002;
        tiny0.firmName = "Tiny Advisers LLC";
        tiny0.country = "United States";
        tiny0.state = "CA";
        tiny0.raumTotal = 10_000_000.0;
        tiny0.clientTypes.add("Pooled Investment Vehicles");

        // (e) record with no cached score -> survives with null scores, not hot.
        ScoutUniverseRecord noScore0 = new ScoutUniverseRecord();
        noScore0.crd = 2003;
        noScore0.firmName = "Unscored Capital LLC";
        noScore0.country = "United States";
        noScore0.state = "TX";
        noScore0.raumTotal = 6_000_000_000.0;
        noScore0.clientTypes.add("Pooled Investment Vehicles");

        // Also a non-allocator record for the allocator-gate funnel count.
        ScoutUniverseRecord nonAllocator0 = new ScoutUniverseRecord();
        nonAllocator0.crd = 2004;
        nonAllocator0.firmName = "Retail Wealth Shop LLC";
        nonAllocator0.country = "United States";
        nonAllocator0.state = "FL";
        nonAllocator0.raumTotal = 6_000_000_000.0;
        nonAllocator0.clientTypes.add("High Net Worth Individuals");

        // (c) sort-order fixture: same allocator type, different resources/probabilityNow.
        ScoutUniverseRecord midA0 = new ScoutUniverseRecord();
        midA0.crd = 2005;
        midA0.firmName = "Mid Resources High Prob LLC";
        midA0.country = "United States";
        midA0.raumTotal = 300_000_000.0;
        midA0.clientTypes.add("Pooled Investment Vehicles");

        ScoutUniverseRecord midB0 = new ScoutUniverseRecord();
        midB0.crd = 2006;
        midB0.firmName = "Mid Resources Low Prob LLC";
        midB0.country = "United States";
        midB0.raumTotal = 300_000_000.0;
        midB0.clientTypes.add("Pooled Investment Vehicles");

        List<ScoutUniverseRecord> universe0 = new ArrayList<ScoutUniverseRecord>();
        universe0.add(fof0);
        universe0.add(tiny0);
        universe0.add(noScore0);
        universe0.add(nonAllocator0);
        universe0.add(midA0);
        universe0.add(midB0);

        Map<Integer, ScoutSignalScore> scores0 = new HashMap<Integer, ScoutSignalScore>();
        scores0.put(fof0.crd, score(85, 60));
        scores0.put(tiny0.crd, score(10, 5));
        scores0.put(nonAllocator0.crd, score(90, 90));
        scores0.put(midA0.crd, score(50, 80));
        scores0.put(midB0.crd, score(50, 20));
        // noScore0 intentionally absent from scores0.

        Path tempDir0 = Files.createTempDirectory("scout-scored-universe-test");
        ScoutUniverseIndexer indexer0 = new ScoutUniverseIndexer(tempDir0);

        List<ScoutScoredRecord> all0 = indexer0.index(universe0, scores0);
        assertTrue(all0.size() == universe0.size(),
            "full scored list must contain every input record (tag-don't-drop), expected " + universe0.size() + " got " + all0.size());

        ScoutScoredRecord fofScored0 = findByCrd(all0, fof0.crd);
        assertTrue(fofScored0.exclusions.isEmpty(), "FoF allocator should have zero exclusions, got " + fofScored0.exclusions);
        assertTrue(fofScored0.tags.contains("HAS_FOF_FUND"), "FoF allocator should carry HAS_FOF_FUND tag, got " + fofScored0.tags);
        assertTrue(fofScored0.tags.contains("ALLOCATOR_TYPE:Pooled Investment Vehicles"),
            "FoF allocator should carry allocator-type tag, got " + fofScored0.tags);
        assertTrue(fofScored0.tags.contains("COUNTRY:United States"), "FoF allocator should carry country tag, got " + fofScored0.tags);
        assertTrue(fofScored0.tags.contains("RAUM_BAND:$5B-$25B"), "FoF allocator RAUM band mislabeled, got " + fofScored0.tags);
        assertTrue(fofScored0.resources != null && fofScored0.resources == 85, "FoF allocator resources should be 85");
        assertTrue(fofScored0.probabilityNow != null && fofScored0.probabilityNow == 60, "FoF allocator probabilityNow should be 60");

        ScoutScoredRecord tinyScored0 = findByCrd(all0, tiny0.crd);
        assertTrue(tinyScored0.exclusions.contains(ScoutUniverseIndexer.EXCLUSION_RAUM_BELOW_FLOOR),
            "Tiny firm should be tagged RAUM_BELOW_FLOOR, got " + tinyScored0.exclusions);

        ScoutScoredRecord nonAllocatorScored0 = findByCrd(all0, nonAllocator0.crd);
        assertTrue(nonAllocatorScored0.exclusions.contains(ScoutUniverseIndexer.EXCLUSION_NOT_ALLOCATOR_CLIENT_TYPE),
            "Non-allocator record should be tagged NOT_ALLOCATOR_CLIENT_TYPE, got " + nonAllocatorScored0.exclusions);

        ScoutScoredRecord noScoreScored0 = findByCrd(all0, noScore0.crd);
        assertTrue(noScoreScored0.resources == null && noScoreScored0.probabilityNow == null,
            "Record with no cached score should have null resources/probabilityNow");
        assertTrue(!noScoreScored0.isHotEligible(ScoutUniverseIndexer.HOT_TIER_MIN_RESOURCES, ScoutUniverseIndexer.HOT_TIER_MIN_PROBABILITY_NOW),
            "Record with null scores must not be hot-eligible");

        // (c) sort order: resources desc, then probabilityNow desc.
        int idxFof0 = indexOfCrd(all0, fof0.crd);
        int idxMidA0 = indexOfCrd(all0, midA0.crd);
        int idxMidB0 = indexOfCrd(all0, midB0.crd);
        int idxNonAllocator0 = indexOfCrd(all0, nonAllocator0.crd);
        assertTrue(idxNonAllocator0 < idxFof0, "resources=90 record should sort before resources=85 record");
        assertTrue(idxFof0 < idxMidA0, "resources=85 record should sort before resources=50 records");
        assertTrue(idxMidA0 < idxMidB0,
            "at equal resources=50, probabilityNow=80 (midA) should sort before probabilityNow=20 (midB)");

        // (d) hot tier + funnel counts match the fixture.
        List<ScoutScoredRecord> hot0 = indexer0.hotTier(all0);
        assertTrue(hot0.size() == 1, "expected exactly 1 hot-tier record, got " + hot0.size());
        assertTrue(hot0.get(0).record.crd == fof0.crd, "hot-tier record should be the FoF allocator, got crd=" + hot0.get(0).record.crd);

        String funnel0 = indexer0.funnelReport(all0, hot0);
        assertTrue(funnel0.contains("6 total records"), "funnel report should state 6 total records, got:\n" + funnel0);
        assertTrue(funnel0.contains(ScoutUniverseIndexer.EXCLUSION_NOT_ALLOCATOR_CLIENT_TYPE + ": 1 excluded"),
            "funnel report should count 1 NOT_ALLOCATOR_CLIENT_TYPE exclusion, got:\n" + funnel0);
        assertTrue(funnel0.contains(ScoutUniverseIndexer.EXCLUSION_RAUM_BELOW_FLOOR + ": 1 excluded"),
            "funnel report should count 1 RAUM_BELOW_FLOOR exclusion, got:\n" + funnel0);
        assertTrue(funnel0.contains(ScoutUniverseIndexer.EXCLUSION_RAUM_ABOVE_CAP + ": 0 excluded"),
            "funnel report should count 0 RAUM_ABOVE_CAP exclusions, got:\n" + funnel0);
        assertTrue(funnel0.contains("HOT_TIER") && funnel0.contains(": 1 records"),
            "funnel report should count 1 hot-tier record, got:\n" + funnel0);

        // File writes + round-trip via indexAndWrite/loadFull/loadHot.
        String yyyyMm0 = "2026-07";
        String writtenFunnel0 = indexer0.indexAndWrite(yyyyMm0, universe0, scores0);
        assertTrue(writtenFunnel0.equals(funnel0), "indexAndWrite's returned funnel report should match the direct funnelReport call");

        Path fullFile0 = tempDir0.resolve(yyyyMm0 + ".json");
        Path hotFile0 = tempDir0.resolve(yyyyMm0 + "-hot.json");
        assertTrue(Files.exists(fullFile0), "full scored file should exist at " + fullFile0);
        assertTrue(Files.exists(hotFile0), "hot-tier file should exist at " + hotFile0);

        List<ScoutScoredRecord> reloadedFull0 = indexer0.loadFull(yyyyMm0);
        assertTrue(reloadedFull0.size() == universe0.size(),
            "reloaded full file should contain every input record, expected " + universe0.size() + " got " + reloadedFull0.size());

        List<ScoutScoredRecord> reloadedHot0 = indexer0.loadHot(yyyyMm0);
        assertTrue(reloadedHot0.size() == 1, "reloaded hot file should contain exactly 1 record, got " + reloadedHot0.size());
        assertTrue(reloadedHot0.get(0).record.crd == fof0.crd, "reloaded hot record should be the FoF allocator");
        assertTrue(reloadedHot0.get(0).resources != null && reloadedHot0.get(0).resources == 85,
            "reloaded hot record's resources score should round-trip as 85");
    }

    private static ScoutSignalScore score(int resources0, int probabilityNow0)
    {
        ScoutSignalScore s0 = new ScoutSignalScore();
        s0.resources = resources0;
        s0.probabilityNow = probabilityNow0;
        return s0;
    }

    private static ScoutScoredRecord findByCrd(List<ScoutScoredRecord> records0, int crd0)
    {
        for (ScoutScoredRecord s0 : records0) if (s0.record.crd == crd0) return s0;
        throw new AssertionError("no scored record found for CRD " + crd0);
    }

    private static int indexOfCrd(List<ScoutScoredRecord> records0, int crd0)
    {
        for (int i0 = 0; i0 < records0.size(); i0++) if (records0.get(i0).record.crd == crd0) return i0;
        throw new AssertionError("no scored record found for CRD " + crd0);
    }

    private static void assertTrue(boolean condition0, String message0)
    {
        if (!condition0) throw new AssertionError(message0);
    }
}
