package com.liminer.scout;

import java.util.ArrayList;
import java.util.List;

/*
 * ScoutFitResult — output of ScoutFitScorer.scoreFit() for one candidate: the
 * deterministic Tier A score (always populated), the optional LLM Tier B score
 * (null when Tier B never ran for this candidate), the effective fitScore used
 * for ranking (tierB when present, else tierA), the matched tag/keyword terms
 * from Tier A, an optional rationale string from Tier B, and which source Tier
 * B's InvestorProfile extraction used (or NONE when Tier B never ran).
 */
public class ScoutFitResult
{
    public static final String SOURCE_BROCHURE = "BROCHURE";
    public static final String SOURCE_WEBSITE = "WEBSITE";
    public static final String SOURCE_LINKEDIN = "LINKEDIN";
    public static final String SOURCE_NONE = "NONE";

    public ScoutUniverseRecord record;
    public int tierAScore;
    public Integer tierBScore;
    public int fitScore;
    public List<String> matchedTerms;
    public String rationale;
    public String profileSource;

    public ScoutFitResult()
    {
        record = null;
        tierAScore = 0;
        tierBScore = null;
        fitScore = 0;
        matchedTerms = new ArrayList<String>();
        rationale = null;
        profileSource = SOURCE_NONE;
    }
}
