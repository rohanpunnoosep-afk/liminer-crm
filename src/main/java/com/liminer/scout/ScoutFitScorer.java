package com.liminer.scout;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/*
 * ScoutFitScorer — single entry point for the per-client FIT axis (task 4 of
 * the 6-task Investor Scout chain). Wires ScoutFitTierA (deterministic, $0,
 * runs on every candidate) and ScoutFitTierB (LLM, only on Tier A survivors)
 * behind one call:
 *
 *   scoreFit(candidates, client, tierBTopN, allowLlm)
 *
 * Ordering, strictly cost-first:
 *   1. Score every candidate with Tier A.
 *   2. Sort descending by score, keep the top tierACutoff (default 75,
 *      configurable via constructor) -- this is the "Caller keeps the top
 *      ~50-100" cutoff. Everything past it is dropped from the result.
 *   3. If allowLlm is true, run Tier B on the top tierBTopN of what's left
 *      (tierBTopN <= tierACutoff in practice) and re-sort by the updated
 *      fitScore. If allowLlm is false, this is a pure Tier-A ranking with
 *      zero network/LLM calls -- what the offline test and any future
 *      dry-run mode use.
 */
public class ScoutFitScorer
{
    public static final int DEFAULT_TIER_A_CUTOFF = 75;

    private final int tierACutoff0;
    private final ScoutFitTierA tierA0;
    private final ScoutFitTierB tierB0;

    public ScoutFitScorer()
    {
        this(DEFAULT_TIER_A_CUTOFF);
    }

    public ScoutFitScorer(int tierACutoff0)
    {
        this.tierACutoff0 = tierACutoff0;
        this.tierA0 = new ScoutFitTierA();
        this.tierB0 = new ScoutFitTierB();
    }

    public List<ScoutFitResult> scoreFit(
        List<ScoutUniverseRecord> candidates0,
        ScoutClientProfile client0,
        int tierBTopN0,
        boolean allowLlm0) throws Exception
    {
        List<ScoutFitResult> results0 = new ArrayList<ScoutFitResult>();
        if (candidates0 == null)
        {
            return results0;
        }

        for (ScoutUniverseRecord record0 : candidates0)
        {
            ScoutFitTierA.Result tierAResult0 = tierA0.score(client0, record0);

            ScoutFitResult result0 = new ScoutFitResult();
            result0.record = record0;
            result0.tierAScore = tierAResult0.score;
            result0.matchedTerms = tierAResult0.matchedTerms;
            result0.fitScore = tierAResult0.score;
            results0.add(result0);
        }

        sortDescendingByFitScore(results0);

        List<ScoutFitResult> cut0 = results0.size() > tierACutoff0
            ? new ArrayList<ScoutFitResult>(results0.subList(0, tierACutoff0))
            : results0;

        if (allowLlm0)
        {
            int tierBCount0 = Math.min(tierBTopN0, cut0.size());
            for (int i0 = 0; i0 < tierBCount0; i0++)
            {
                tierB0.applyTierB(cut0.get(i0), client0);
            }
            sortDescendingByFitScore(cut0);
        }

        return cut0;
    }

    private static void sortDescendingByFitScore(List<ScoutFitResult> results0)
    {
        Collections.sort(results0, new Comparator<ScoutFitResult>()
        {
            public int compare(ScoutFitResult a0, ScoutFitResult b0)
            {
                return Integer.compare(b0.fitScore, a0.fitScore);
            }
        });
    }
}
