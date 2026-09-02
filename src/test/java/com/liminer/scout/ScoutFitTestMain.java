package com.liminer.scout;

import java.util.ArrayList;
import java.util.List;

/*
 * ScoutFitTestMain — fully offline verification for ScoutFitTierA and
 * ScoutFitScorer (allowLlm=false path): tag/keyword overlap ordering, matched
 * terms, top-N cutoff behavior, and fitScore falling back to tierAScore when
 * tierBScore is null. No network, no OpenAI, no Bright Data. Prints
 * SCOUT_FIT_OK on success; exits 1 on any assertion failure.
 */
public class ScoutFitTestMain
{
    public static void main(String[] args0)
    {
        try
        {
            testFoFOutranksGenericPE();
            testNoOverlapScoresNearZero();
            testGeographyMismatchLowersScore();
            testNteeCodeContributesMatch();
            testTierACutoffLimitsResultSize();
            testFitScoreFallsBackToTierAWhenAllowLlmFalse();
            testAllowLlmFalseIsFullyOffline();

            System.out.println("SCOUT_FIT_OK");
        }
        catch (Throwable t0)
        {
            System.err.println("SCOUT_FIT_FAILED: " + t0);
            t0.printStackTrace();
            System.exit(1);
        }
    }

    // ---- Tier A ordering ---------------------------------------------------

    private static void testFoFOutranksGenericPE() throws Exception
    {
        ScoutClientProfile client0 = buildClient();

        ScoutUniverseRecord fof0 = buildRecord(4001, "Fintech Venture FoF Partners", "www.fintechfof.com", "US");
        fof0.funds.add(buildFund("Fintech Venture Fund of Funds III", "Fund of Funds"));

        ScoutUniverseRecord genericPe0 = buildRecord(4002, "Acme Capital Partners", "www.acmecapital.com", "US");
        genericPe0.funds.add(buildFund("Acme Buyout Fund IV", "Private Equity Fund"));

        ScoutFitTierA tierA0 = new ScoutFitTierA();
        ScoutFitTierA.Result fofResult0 = tierA0.score(client0, fof0);
        ScoutFitTierA.Result peResult0 = tierA0.score(client0, genericPe0);

        assertTrue(fofResult0.score > peResult0.score,
            "FoF matching sector tags should outrank a generic PE adviser, got fof=" + fofResult0.score
                + " pe=" + peResult0.score);
        assertTrue(fofResult0.matchedTerms.contains("venture") || fofResult0.matchedTerms.contains("fintech"),
            "expected at least one matched sector tag, got " + fofResult0.matchedTerms);

        // End-to-end through the scorer (allowLlm=false) should preserve the ordering.
        List<ScoutUniverseRecord> candidates0 = new ArrayList<ScoutUniverseRecord>();
        candidates0.add(genericPe0);
        candidates0.add(fof0);
        ScoutFitScorer scorer0 = new ScoutFitScorer();
        List<ScoutFitResult> ranked0 = scorer0.scoreFit(candidates0, client0, 10, false);

        assertTrue(ranked0.get(0).record.crd == 4001,
            "expected the FoF (crd 4001) ranked first, got crd " + ranked0.get(0).record.crd);
    }

    private static void testNoOverlapScoresNearZero() throws Exception
    {
        ScoutClientProfile client0 = buildClient();

        ScoutUniverseRecord noOverlap0 = buildRecord(4003, "Random Overseas Holdings", "www.randomholdings.example", "DE");
        noOverlap0.funds.add(buildFund("Random Buyout Fund I", "Private Equity Fund"));

        ScoutFitTierA tierA0 = new ScoutFitTierA();
        ScoutFitTierA.Result result0 = tierA0.score(client0, noOverlap0);

        assertTrue(result0.score == 0, "record with no sector/geography overlap should score 0, got " + result0.score);
        assertTrue(result0.matchedTerms.isEmpty(), "expected no matched terms, got " + result0.matchedTerms);
    }

    private static void testGeographyMismatchLowersScore() throws Exception
    {
        ScoutClientProfile client0 = buildClient();

        ScoutUniverseRecord sameSectorWrongGeo0 = buildRecord(4004, "Fintech Venture Group", "www.fintechventuregroup.example", "DE");
        sameSectorWrongGeo0.funds.add(buildFund("Fintech Venture Fund II", "Venture Capital Fund"));

        ScoutUniverseRecord sameSectorRightGeo0 = buildRecord(4005, "Fintech Venture Group US", "www.fintechventureus.example", "US");
        sameSectorRightGeo0.funds.add(buildFund("Fintech Venture Fund II", "Venture Capital Fund"));

        ScoutFitTierA tierA0 = new ScoutFitTierA();
        ScoutFitTierA.Result wrongGeoResult0 = tierA0.score(client0, sameSectorWrongGeo0);
        ScoutFitTierA.Result rightGeoResult0 = tierA0.score(client0, sameSectorRightGeo0);

        assertTrue(rightGeoResult0.score > wrongGeoResult0.score,
            "matching geography should score higher than a mismatch with identical sector overlap, got right="
                + rightGeoResult0.score + " wrong=" + wrongGeoResult0.score);
    }

    private static void testNteeCodeContributesMatch() throws Exception
    {
        ScoutClientProfile client0 = new ScoutClientProfile();
        client0.targetFundSize = 50_000_000.0;
        client0.geography = "";
        client0.sectorTags = new ArrayList<String>();
        client0.sectorTags.add("philanthropy");

        ScoutUniverseRecord nonprofit0 = buildRecord(4006, "Community Foundation of Example County", "www.examplecf.org", "US");
        nonprofit0.nteeCode = "T20";

        ScoutUniverseRecord nonprofitNoCode0 = buildRecord(4007, "Community Foundation No Code", "www.nocode.org", "US");

        ScoutFitTierA tierA0 = new ScoutFitTierA();
        ScoutFitTierA.Result withCode0 = tierA0.score(client0, nonprofit0);
        ScoutFitTierA.Result withoutCode0 = tierA0.score(client0, nonprofitNoCode0);

        assertTrue(withCode0.score > withoutCode0.score,
            "NTEE code 'T' (philanthropy) should contribute a match the uncoded record lacks, got with="
                + withCode0.score + " without=" + withoutCode0.score);
        assertTrue(withCode0.matchedTerms.contains("philanthropy"),
            "expected 'philanthropy' matched via NTEE keyword mapping, got " + withCode0.matchedTerms);
    }

    // ---- ScoutFitScorer: cutoff + allowLlm=false fallback -------------------

    private static void testTierACutoffLimitsResultSize() throws Exception
    {
        ScoutClientProfile client0 = buildClient();
        List<ScoutUniverseRecord> candidates0 = new ArrayList<ScoutUniverseRecord>();
        for (int i0 = 0; i0 < 8; i0++)
        {
            ScoutUniverseRecord rec0 = buildRecord(5000 + i0, "Candidate " + i0, "www.candidate" + i0 + ".example", "US");
            if (i0 < 3)
            {
                rec0.funds.add(buildFund("Fintech Venture Fund " + i0, "Venture Capital Fund"));
            }
            candidates0.add(rec0);
        }

        ScoutFitScorer scorer0 = new ScoutFitScorer(3);
        List<ScoutFitResult> ranked0 = scorer0.scoreFit(candidates0, client0, 10, false);

        assertTrue(ranked0.size() == 3, "expected the tierACutoff of 3 to cap the result size, got " + ranked0.size());
        for (ScoutFitResult r0 : ranked0)
        {
            assertTrue(r0.tierAScore > 0, "expected only the sector-matching candidates to survive the cutoff, got crd "
                + r0.record.crd + " score " + r0.tierAScore);
        }
        // Descending order check.
        for (int i0 = 1; i0 < ranked0.size(); i0++)
        {
            assertTrue(ranked0.get(i0 - 1).fitScore >= ranked0.get(i0).fitScore,
                "expected descending fitScore order, got " + ranked0);
        }
    }

    private static void testFitScoreFallsBackToTierAWhenAllowLlmFalse() throws Exception
    {
        ScoutClientProfile client0 = buildClient();
        ScoutUniverseRecord rec0 = buildRecord(6001, "Fintech Venture Fund Partners", "www.fintechfundpartners.example", "US");
        rec0.funds.add(buildFund("Fintech Venture Fund I", "Venture Capital Fund"));

        List<ScoutUniverseRecord> candidates0 = new ArrayList<ScoutUniverseRecord>();
        candidates0.add(rec0);

        ScoutFitScorer scorer0 = new ScoutFitScorer();
        List<ScoutFitResult> ranked0 = scorer0.scoreFit(candidates0, client0, 10, false);

        assertTrue(ranked0.size() == 1, "expected exactly one result, got " + ranked0.size());
        ScoutFitResult result0 = ranked0.get(0);
        assertTrue(result0.tierBScore == null, "tierBScore should be null when allowLlm is false, got " + result0.tierBScore);
        assertTrue(result0.fitScore == result0.tierAScore,
            "fitScore should fall back to tierAScore when tierBScore is null, got fitScore="
                + result0.fitScore + " tierAScore=" + result0.tierAScore);
        assertTrue(ScoutFitResult.SOURCE_NONE.equals(result0.profileSource),
            "profileSource should default to NONE when Tier B never ran, got " + result0.profileSource);
    }

    private static void testAllowLlmFalseIsFullyOffline() throws Exception
    {
        // Sanity check that the offline path doesn't depend on any credentials
        // being configured -- if it made a network/LLM call, a missing
        // OPENAI_API_KEY / BRIGHT_DATA_API_TOKEN in this environment would
        // surface as an exception here.
        ScoutClientProfile client0 = buildClient();
        List<ScoutUniverseRecord> candidates0 = new ArrayList<ScoutUniverseRecord>();
        candidates0.add(buildRecord(7001, "Some Adviser", "www.someadviser.example", "US"));

        ScoutFitScorer scorer0 = new ScoutFitScorer();
        List<ScoutFitResult> ranked0 = scorer0.scoreFit(candidates0, client0, 10, false);
        assertTrue(ranked0.size() == 1, "expected exactly one result in the offline smoke test, got " + ranked0.size());
    }

    // ---- helpers -------------------------------------------------------------

    private static ScoutClientProfile buildClient()
    {
        ScoutClientProfile client0 = new ScoutClientProfile();
        client0.targetFundSize = 100_000_000.0;
        client0.geography = "US";
        client0.sectorTags = new ArrayList<String>();
        client0.sectorTags.add("venture");
        client0.sectorTags.add("fintech");
        return client0;
    }

    private static ScoutUniverseRecord buildRecord(int crd0, String firmName0, String website0, String country0)
    {
        ScoutUniverseRecord r0 = new ScoutUniverseRecord();
        r0.crd = crd0;
        r0.firmName = firmName0;
        r0.website = website0;
        r0.country = country0;
        r0.city = "";
        r0.state = "";
        r0.snapshotMonth = "2026-06";
        return r0;
    }

    private static ScoutFundRecord buildFund(String name0, String type0)
    {
        ScoutFundRecord f0 = new ScoutFundRecord();
        f0.name = name0;
        f0.type = type0;
        return f0;
    }

    private static void assertTrue(boolean condition0, String message0)
    {
        if (!condition0) throw new AssertionError(message0);
    }
}
