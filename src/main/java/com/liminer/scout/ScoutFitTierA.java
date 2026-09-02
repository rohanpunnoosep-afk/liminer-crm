package com.liminer.scout;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/*
 * ScoutFitTierA — deterministic, $0, no-external-call fit scoring (Tier A of the
 * per-client FIT axis). Compares a ScoutClientProfile's sectorTags/geography
 * against what the filing record already says: Schedule D fund-type/fund-name
 * strings, firm-name tokens, website-domain keywords, plus an optional NTEE
 * code (mapped to a small keyword set) for nonprofit records.
 *
 * Pure function: no I/O, no randomness. Caller (ScoutFitScorer) sorts
 * descending by score and keeps the top-N (default 75) before any Tier B LLM
 * call ever runs.
 */
public class ScoutFitTierA
{
    // Sector/keyword overlap dominates; geography is a smaller nudge, not a gate
    // (ScoutPrefilter already applied the coarse geography gate upstream).
    private static final double SECTOR_WEIGHT = 80.0;
    private static final double GEOGRAPHY_WEIGHT = 20.0;

    // IRS NTEE major-group letter -> a few keywords that stand in for its focus
    // area, so a nonprofit's NTEE code can match against client sectorTags like
    // "philanthropy" or "education" even when no other text mentions them.
    private static final Map<Character, String> NTEE_MAJOR_GROUP_KEYWORDS = buildNteeKeywords();

    public static class Result
    {
        public int score;
        public List<String> matchedTerms;

        public Result()
        {
            score = 0;
            matchedTerms = new ArrayList<String>();
        }
    }

    public Result score(ScoutClientProfile client0, ScoutUniverseRecord record0)
    {
        Result result0 = new Result();
        if (record0 == null)
        {
            return result0;
        }

        List<String> sectorTags0 = (client0 == null || client0.sectorTags == null)
            ? new ArrayList<String>() : client0.sectorTags;

        String normalizedCorpus0 = normalize(buildCorpusBlob(record0));

        int nonBlankTagCount0 = 0;
        int matchedTagCount0 = 0;
        for (String tag0 : sectorTags0)
        {
            String normTag0 = normalize(tag0);
            if (normTag0.length() == 0)
            {
                continue;
            }
            nonBlankTagCount0++;
            if (normalizedCorpus0.contains(normTag0))
            {
                matchedTagCount0++;
                result0.matchedTerms.add(tag0.trim());
            }
        }

        double sectorFraction0 = nonBlankTagCount0 == 0
            ? 0.0 : (double) matchedTagCount0 / nonBlankTagCount0;

        double geographyScore0 = scoreGeography(client0, record0, result0.matchedTerms);

        int score0 = (int) Math.round(sectorFraction0 * SECTOR_WEIGHT + geographyScore0 * GEOGRAPHY_WEIGHT);
        result0.score = clamp0to100(score0);

        return result0;
    }

    private double scoreGeography(ScoutClientProfile client0, ScoutUniverseRecord record0, List<String> matchedTerms0)
    {
        String geography0 = client0 == null ? "" : client0.geography;
        if (isBlank(geography0))
        {
            // No geography constraint on the client side -> treat as a full match
            // rather than penalizing every candidate for missing information.
            return 1.0;
        }

        String normGeo0 = normalize(geography0);
        if (normGeo0.length() == 0)
        {
            return 1.0;
        }

        String geoBlob0 = normalize(safe(record0.city) + " " + safe(record0.state) + " " + safe(record0.country));
        if (geoBlob0.contains(normGeo0))
        {
            matchedTerms0.add(geography0.trim());
            return 1.0;
        }

        return 0.0;
    }

    private String buildCorpusBlob(ScoutUniverseRecord record0)
    {
        StringBuilder sb0 = new StringBuilder();
        sb0.append(safe(record0.firmName)).append(" ");
        sb0.append(safe(record0.website)).append(" ");

        if (record0.funds != null)
        {
            for (ScoutFundRecord fund0 : record0.funds)
            {
                if (fund0 == null) continue;
                sb0.append(safe(fund0.type)).append(" ");
                sb0.append(safe(fund0.name)).append(" ");
            }
        }

        if (record0.clientTypes != null)
        {
            for (String clientType0 : record0.clientTypes)
            {
                sb0.append(safe(clientType0)).append(" ");
            }
        }

        String nteeKeywords0 = nteeKeywordsFor(record0.nteeCode);
        if (nteeKeywords0.length() > 0)
        {
            sb0.append(nteeKeywords0).append(" ");
        }

        return sb0.toString();
    }

    private static String nteeKeywordsFor(String nteeCode0)
    {
        if (isBlank(nteeCode0))
        {
            return "";
        }
        char major0 = Character.toUpperCase(nteeCode0.trim().charAt(0));
        String keywords0 = NTEE_MAJOR_GROUP_KEYWORDS.get(major0);
        return keywords0 == null ? "" : keywords0;
    }

    private static Map<Character, String> buildNteeKeywords()
    {
        Map<Character, String> map0 = new HashMap<Character, String>();
        map0.put('A', "arts culture humanities");
        map0.put('B', "education");
        map0.put('C', "environment conservation");
        map0.put('D', "animal related");
        map0.put('E', "health");
        map0.put('F', "mental health");
        map0.put('G', "disease disorders");
        map0.put('H', "medical research");
        map0.put('I', "crime legal");
        map0.put('J', "employment");
        map0.put('K', "food agriculture");
        map0.put('L', "housing shelter");
        map0.put('M', "public safety");
        map0.put('N', "recreation sports");
        map0.put('O', "youth development");
        map0.put('P', "human services");
        map0.put('Q', "international foreign affairs");
        map0.put('R', "civil rights");
        map0.put('S', "community improvement");
        map0.put('T', "philanthropy foundation grantmaking");
        map0.put('U', "science technology");
        map0.put('V', "social science");
        map0.put('W', "public societal benefit");
        map0.put('X', "religion");
        map0.put('Y', "mutual membership benefit");
        return map0;
    }

    private static int clamp0to100(int v0)
    {
        return Math.max(0, Math.min(100, v0));
    }

    // Mirrors CandidateScorer.normalize: lowercase and strip everything but
    // letters/digits (spaces removed too), so multi-word tags/terms can be
    // found as a substring inside concatenated corpus text.
    private static String normalize(String value0)
    {
        if (value0 == null)
        {
            return "";
        }
        return value0.toLowerCase().replaceAll("[^a-z0-9]", "").trim();
    }

    private static boolean isBlank(String value0)
    {
        return value0 == null || value0.trim().length() == 0;
    }

    private static String safe(String value0)
    {
        return value0 == null ? "" : value0;
    }
}
