package com.liminer.scout;

import com.liminer.core.InvestorProfile;
import com.liminer.core.SessionContext;
import com.liminer.llm.OpenAIClient;
import com.liminer.sheets.SheetsApp;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import org.json.JSONArray;
import org.json.JSONObject;

/*
 * CandidateScorer is the OpenAI-first similarity scoring engine for Pipeline 3.
 *
 * It supports two modes:
 *
 * 1. Explicit basis mode:
 *      scoreCandidates(InvestorProfile basis, ArrayList<CandidateInvestor> candidates)
 *
 *    Use this when the caller already knows the profile to compare against.
 *
 * 2. CRM-derived basis mode:
 *      scoreCandidates(SessionContext context, ArrayList<CandidateInvestor> candidates)
 *
 *    Use this when the caller wants the system to build an average basis profile
 *    from CRM investors that have reached First Interest or better.
 *
 * The scorer mutates CandidateInvestor objects by filling:
 * - finalScore
 * - subscores
 * - scoreExplanation
 */
public class CandidateScorer
{
    private static final double SECTOR_WEIGHT = 0.30;
    private static final double MICROSECTOR_WEIGHT = 0.30;
    private static final double GEOGRAPHY_WEIGHT = 0.20;
    private static final double PRIOR_BACKED_FUNDS_WEIGHT = 0.10;
    private static final double INVESTMENT_THESIS_WEIGHT = 0.10;

    private static final int DEFAULT_TOP_TAG_LIMIT0 = 8;

    public CandidateScorer()
    {
    }

    /*
     * Scores candidates against an explicit basis InvestorProfile.
     */
    public void scoreCandidates(
        InvestorProfile basis0,
        ArrayList<CandidateInvestor> candidates0)
    {
        if (basis0 == null || candidates0 == null)
        {
            return;
        }

        for (CandidateInvestor candidate0 : candidates0)
        {
            scoreCandidate(basis0, candidate0);
        }
    }

    /*
     * Overloaded version.
     *
     * This builds the basis InvestorProfile from the average profile of CRM rows
     * with Conversation Status of First Interest or better, then scores candidates
     * against that generated basis.
     */
    public void scoreCandidates(
        SessionContext context0,
        ArrayList<CandidateInvestor> candidates0) throws Exception
    {
        InvestorProfile averageBasis0 = buildAverageBasisProfileFromCrm(context0);
        scoreCandidates(averageBasis0, candidates0);
    }

    public CandidateInvestor scoreCandidate(
        InvestorProfile basis0,
        CandidateInvestor candidate0)
    {
        if (basis0 == null || candidate0 == null || candidate0.ip == null)
        {
            return candidate0;
        }

        try
        {
            scoreCandidateWithOpenAI(basis0, candidate0);
            return candidate0;
        }
        catch (Exception exception0)
        {
            System.out.println(
                "OpenAI candidate scoring failed for "
                + safeCandidateName(candidate0)
                + ": "
                + exception0.getMessage()
            );

            scoreCandidateDeterministically(basis0, candidate0);

            candidate0.scoreExplanation = candidate0.scoreExplanation
                + " OpenAI scoring failed, so deterministic fallback scoring was used.";

            return candidate0;
        }
    }

    /*
     * Uses OpenAI as the primary similarity judge. It returns a double score,
     * component subscores, and a short explanation for the Comments column.
     */
    private void scoreCandidateWithOpenAI(
        InvestorProfile basis0,
        CandidateInvestor candidate0) throws Exception
    {
        String prompt0 = buildOpenAIScoringPrompt(basis0, candidate0);
        String aiText0 = OpenAIClient.getTextResponse(prompt0);
        JSONObject result0 = parseJsonObjectFromText(aiText0);

        double finalScore0 = result0.optDouble("final_score", -1.0);

        if (finalScore0 < 0.0)
        {
            throw new Exception("OpenAI response missing final_score.");
        }

        finalScore0 = clamp01(finalScore0);

        JSONObject subscores0 = result0.optJSONObject("subscores");

        candidate0.finalScore = finalScore0;
        candidate0.subscores.clear();
        candidate0.subscores.put("sector", getSubscore(subscores0, "sector"));
        candidate0.subscores.put("microsector", getSubscore(subscores0, "microsector"));
        candidate0.subscores.put("geography", getSubscore(subscores0, "geography"));
        candidate0.subscores.put("prior_backed_funds", getSubscore(subscores0, "prior_backed_funds"));
        candidate0.subscores.put("investment_thesis", getSubscore(subscores0, "investment_thesis"));
        candidate0.subscores.put("allocator_type", getSubscore(subscores0, "allocator_type"));

        candidate0.scoreExplanation = result0.optString(
            "score_explanation",
            buildExplanation(candidate0)
        );
    }

    private String buildOpenAIScoringPrompt(
        InvestorProfile basis0,
        CandidateInvestor candidate0)
    {
        JSONObject basisJson0 = profileToJson(basis0);
        JSONObject candidateJson0 = profileToJson(candidate0.ip);

        JSONObject metadata0 = new JSONObject();
        metadata0.put("candidate_type", safe(candidate0.candidateType));
        metadata0.put("name", safe(candidate0.name));
        metadata0.put("fund_name", safe(candidate0.fundName));
        metadata0.put("position", safe(candidate0.position));
        metadata0.put("website", safe(candidate0.website));
        metadata0.put("linkedin_url", safe(candidate0.linkedInUrl));
        metadata0.put("conversation_status", safe(candidate0.conversationStatus));

        return "You are scoring investor fit for a VC fundraising CRM.\n"
            + "Compare the candidate investor profile to the basis investor profile.\n"
            + "The basis profile represents the investor pattern we want more of.\n"
            + "Return ONLY valid JSON. No markdown. No explanation outside JSON.\n\n"
            + "Scoring rules:\n"
            + "1. final_score must be a double from 0.0 to 1.0.\n"
            + "2. 1.0 means extremely strong fit. 0.0 means no meaningful fit.\n"
            + "3. Judge semantic similarity, not just exact keyword overlap.\n"
            + "4. Microsector and investment thesis should matter heavily.\n"
            + "5. Geography matters, but broad/global investors should not be over-penalized.\n"
            + "6. Prior backed funds are useful when present, but missing data should not automatically crush the score.\n"
            + "7. Be conservative. Do not give high scores unless the fit is clearly supported.\n\n"
            + "Return exactly this JSON structure:\n"
            + "{\n"
            + "  \"final_score\": 0.0,\n"
            + "  \"subscores\": {\n"
            + "    \"sector\": 0.0,\n"
            + "    \"microsector\": 0.0,\n"
            + "    \"geography\": 0.0,\n"
            + "    \"prior_backed_funds\": 0.0,\n"
            + "    \"investment_thesis\": 0.0,\n"
            + "    \"allocator_type\": 0.0\n"
            + "  },\n"
            + "  \"score_explanation\": \"1-3 sentences explaining the score and strongest reasons.\"\n"
            + "}\n\n"
            + "Basis InvestorProfile JSON:\n"
            + basisJson0.toString(2)
            + "\n\nCandidate Metadata JSON:\n"
            + metadata0.toString(2)
            + "\n\nCandidate InvestorProfile JSON:\n"
            + candidateJson0.toString(2);
    }

    private JSONObject profileToJson(InvestorProfile profile0)
    {
        JSONObject object0 = new JSONObject();

        if (profile0 == null)
        {
            return object0;
        }

        object0.put("fund_name", safe(profile0.fundName));
        object0.put("allocator_type", safe(profile0.allocatorType));
        object0.put("sectors", arrayToJson(profile0.sectors));
        object0.put("microsectors", arrayToJson(profile0.microsectors));
        object0.put("geographies", arrayToJson(profile0.geographies));
        object0.put("prior_backed_funds", arrayToJson(profile0.priorBackedFunds));
        object0.put("investment_thesis", safe(profile0.investmentThesis));

        return object0;
    }

    private JSONArray arrayToJson(String[] values0)
    {
        JSONArray array0 = new JSONArray();

        if (values0 == null)
        {
            return array0;
        }

        for (String value0 : values0)
        {
            if (!isBlank(value0))
            {
                array0.put(value0.trim());
            }
        }

        return array0;
    }

    private JSONObject parseJsonObjectFromText(String text0)
    {
        String trimmedText0 = text0 == null ? "" : text0.trim();

        try
        {
            return new JSONObject(trimmedText0);
        }
        catch (Exception exception0)
        {
            int startIndex0 = trimmedText0.indexOf("{");
            int endIndex0 = trimmedText0.lastIndexOf("}");

            if (startIndex0 == -1 || endIndex0 == -1 || endIndex0 <= startIndex0)
            {
                throw exception0;
            }

            return new JSONObject(trimmedText0.substring(startIndex0, endIndex0 + 1));
        }
    }

    private double getSubscore(JSONObject subscores0, String key0)
    {
        if (subscores0 == null)
        {
            return 0.0;
        }

        return clamp01(subscores0.optDouble(key0, 0.0));
    }

    private double clamp01(double value0)
    {
        if (Double.isNaN(value0) || Double.isInfinite(value0))
        {
            return 0.0;
        }

        if (value0 < 0.0)
        {
            return 0.0;
        }

        if (value0 > 1.0)
        {
            return 1.0;
        }

        return value0;
    }

    private CandidateInvestor scoreCandidateDeterministically(
        InvestorProfile basis0,
        CandidateInvestor candidate0)
    {
        if (basis0 == null || candidate0 == null || candidate0.ip == null)
        {
            return candidate0;
        }

        double sectorScore0 = scoreListOverlap(
            basis0.sectors,
            candidate0.ip.sectors
        );

        double microsectorScore0 = scoreListOverlap(
            basis0.microsectors,
            candidate0.ip.microsectors
        );

        double geographyScore0 = scoreListOverlap(
            basis0.geographies,
            candidate0.ip.geographies
        );

        double priorBackedFundsScore0 = scoreListOverlap(
            basis0.priorBackedFunds,
            candidate0.ip.priorBackedFunds
        );

        double investmentThesisScore0 = scoreTextOverlap(
            basis0.investmentThesis,
            candidate0.ip.investmentThesis
        );

        double finalScore0 =
            (sectorScore0 * SECTOR_WEIGHT)
            + (microsectorScore0 * MICROSECTOR_WEIGHT)
            + (geographyScore0 * GEOGRAPHY_WEIGHT)
            + (priorBackedFundsScore0 * PRIOR_BACKED_FUNDS_WEIGHT)
            + (investmentThesisScore0 * INVESTMENT_THESIS_WEIGHT);

        candidate0.finalScore = finalScore0;
        candidate0.subscores.clear();
        candidate0.subscores.put("sector", sectorScore0);
        candidate0.subscores.put("microsector", microsectorScore0);
        candidate0.subscores.put("geography", geographyScore0);
        candidate0.subscores.put("prior_backed_funds", priorBackedFundsScore0);
        candidate0.subscores.put("investment_thesis", investmentThesisScore0);
        candidate0.scoreExplanation = buildExplanation(candidate0);

        return candidate0;
    }

    public ArrayList<CandidateInvestor> prioritizeCandidates(
        InvestorProfile basis0,
        ArrayList<CandidateInvestor> candidates0,
        double minimumScore0)
    {
        scoreCandidates(basis0, candidates0);

        ArrayList<CandidateInvestor> filtered0 = filterByMinimumScore(
            candidates0,
            minimumScore0
        );

        sortCandidatesDescending(filtered0);

        return filtered0;
    }

    public ArrayList<CandidateInvestor> prioritizeCandidates(
        SessionContext context0,
        ArrayList<CandidateInvestor> candidates0,
        double minimumScore0) throws Exception
    {
        scoreCandidates(context0, candidates0);

        ArrayList<CandidateInvestor> filtered0 = filterByMinimumScore(
            candidates0,
            minimumScore0
        );

        sortCandidatesDescending(filtered0);

        return filtered0;
    }

    public ArrayList<CandidateInvestor> filterByMinimumScore(
        ArrayList<CandidateInvestor> candidates0,
        double minimumScore0)
    {
        ArrayList<CandidateInvestor> filtered0 = new ArrayList<CandidateInvestor>();

        if (candidates0 == null)
        {
            return filtered0;
        }

        for (CandidateInvestor candidate0 : candidates0)
        {
            if (candidate0 != null && candidate0.finalScore >= minimumScore0)
            {
                filtered0.add(candidate0);
            }
        }

        return filtered0;
    }

    public void sortCandidatesDescending(ArrayList<CandidateInvestor> candidates0)
    {
        if (candidates0 == null)
        {
            return;
        }

        Collections.sort(
            candidates0,
            new Comparator<CandidateInvestor>()
            {
                public int compare(CandidateInvestor a0, CandidateInvestor b0)
                {
                    return Double.compare(b0.finalScore, a0.finalScore);
                }
            }
        );
    }

    /*
     * Reads the CRM and returns an average basis profile from rows that have
     * Conversation Status >= First Interest and a usable Intelligence JSON.
     */
    public InvestorProfile buildAverageBasisProfileFromCrm(SessionContext context0) throws Exception
    {
        ArrayList<InvestorProfile> successfulProfiles0 = readSuccessfulInvestorProfilesFromCrm(context0);
        return averageProfiles(successfulProfiles0);
    }

    public ArrayList<InvestorProfile> readSuccessfulInvestorProfilesFromCrm(SessionContext context0) throws Exception
    {
        ArrayList<InvestorProfile> profiles0 = new ArrayList<InvestorProfile>();

        if (context0 == null || context0.config == null)
        {
            return profiles0;
        }

        String spreadsheetId0 = context0.config.spreadsheetId;
        String crmTabName0 = context0.config.mainTabName;

        HashMap<String, Integer> crmHeaderMap0 = SheetsApp.buildHeaderMap(
            spreadsheetId0,
            crmTabName0,
            context0.config.mainTabHeaderRow,
            200
        );

        int statusCol0 = SheetsApp.findColumnInHeaderMap(
            crmHeaderMap0,
            context0.config.getCol("mainTabStatusCol")
        );

        int intelligenceJsonCol0 = SheetsApp.findColumnInHeaderMap(
            crmHeaderMap0,
            context0.config.getCol("mainTabIntelligenceJsonCol")
        );

        if (statusCol0 == -1 || intelligenceJsonCol0 == -1)
        {
            System.out.println("CandidateScorer could not find status or intelligence JSON columns.");
            return profiles0;
        }

        int maxCol0 = Math.max(statusCol0, intelligenceJsonCol0);

        String[][] crmData0 = SheetsApp.readRangeMatrix(
            spreadsheetId0,
            crmTabName0,
            1,
            1,
            2000,
            maxCol0
        );

        for (int rowNumber0 = context0.config.mainTabDataStartRow;
             rowNumber0 <= crmData0.length;
             rowNumber0++)
        {
            String status0 = getCell(crmData0, rowNumber0, statusCol0);

            if (!isFirstInterestOrBetter(status0))
            {
                continue;
            }

            String intelligenceJson0 = getCell(crmData0, rowNumber0, intelligenceJsonCol0);

            InvestorProfile profile0 = parseProfileOrNull(intelligenceJson0);

            if (hasUsefulProfile(profile0))
            {
                profiles0.add(profile0);
            }
        }

        return profiles0;
    }

    /*
     * Creates an average profile by keeping the most frequent tags across
     * successful CRM investors.
     */
    public InvestorProfile averageProfiles(ArrayList<InvestorProfile> profiles0)
    {
        InvestorProfile average0 = new InvestorProfile();

        if (profiles0 == null || profiles0.size() == 0)
        {
            return average0;
        }

        HashMap<String, Integer> sectorCounts0 = new HashMap<String, Integer>();
        HashMap<String, Integer> microsectorCounts0 = new HashMap<String, Integer>();
        HashMap<String, Integer> geographyCounts0 = new HashMap<String, Integer>();
        HashMap<String, Integer> priorBackedFundsCounts0 = new HashMap<String, Integer>();

        String thesis0 = "";

        for (InvestorProfile profile0 : profiles0)
        {
            if (profile0 == null)
            {
                continue;
            }

            addArrayCounts(sectorCounts0, profile0.sectors);
            addArrayCounts(microsectorCounts0, profile0.microsectors);
            addArrayCounts(geographyCounts0, profile0.geographies);
            addArrayCounts(priorBackedFundsCounts0, profile0.priorBackedFunds);

            if (!isBlank(profile0.investmentThesis))
            {
                if (thesis0.length() > 0)
                {
                    thesis0 += " ";
                }

                thesis0 += profile0.investmentThesis.trim();
            }
        }

        average0.fundName = "Average First Interest Or Better Investor";
        average0.allocatorType = "Average";
        average0.sectors = topValues(sectorCounts0, DEFAULT_TOP_TAG_LIMIT0);
        average0.microsectors = topValues(microsectorCounts0, DEFAULT_TOP_TAG_LIMIT0);
        average0.geographies = topValues(geographyCounts0, DEFAULT_TOP_TAG_LIMIT0);
        average0.priorBackedFunds = topValues(priorBackedFundsCounts0, DEFAULT_TOP_TAG_LIMIT0);
        average0.investmentThesis = thesis0;
        average0.intelligenceJson = "";

        return average0;
    }

    private void addArrayCounts(HashMap<String, Integer> counts0, String[] values0)
    {
        if (counts0 == null || values0 == null)
        {
            return;
        }

        for (String value0 : values0)
        {
            String cleaned0 = cleanTag(value0);

            if (isBlank(cleaned0))
            {
                continue;
            }

            Integer count0 = counts0.get(cleaned0);
            counts0.put(cleaned0, count0 == null ? 1 : count0 + 1);
        }
    }

    private String[] topValues(HashMap<String, Integer> counts0, int limit0)
    {
        if (counts0 == null || counts0.size() == 0)
        {
            return new String[0];
        }

        ArrayList<Map.Entry<String, Integer>> entries0 =
            new ArrayList<Map.Entry<String, Integer>>(counts0.entrySet());

        Collections.sort(
            entries0,
            new Comparator<Map.Entry<String, Integer>>()
            {
                public int compare(Map.Entry<String, Integer> a0, Map.Entry<String, Integer> b0)
                {
                    int countCompare0 = Integer.compare(b0.getValue(), a0.getValue());
                    if (countCompare0 != 0)
                    {
                        return countCompare0;
                    }

                    return a0.getKey().compareTo(b0.getKey());
                }
            }
        );

        int size0 = Math.min(limit0, entries0.size());
        String[] values0 = new String[size0];

        for (int i = 0; i < size0; i++)
        {
            values0[i] = entries0.get(i).getKey();
        }

        return values0;
    }

    private double scoreListOverlap(String[] basisValues0, String[] candidateValues0)
    {
        if (basisValues0 == null || candidateValues0 == null)
        {
            return 0.0;
        }

        if (basisValues0.length == 0 || candidateValues0.length == 0)
        {
            return 0.0;
        }

        HashSet<String> candidateSet0 = new HashSet<String>();

        for (String candidateValue0 : candidateValues0)
        {
            String normalizedCandidate0 = normalize(candidateValue0);

            if (normalizedCandidate0.length() > 0)
            {
                candidateSet0.add(normalizedCandidate0);
            }
        }

        int validBasisCount0 = 0;
        int matchCount0 = 0;

        for (String basisValue0 : basisValues0)
        {
            String normalizedBasis0 = normalize(basisValue0);

            if (normalizedBasis0.length() == 0)
            {
                continue;
            }

            validBasisCount0++;

            if (candidateSet0.contains(normalizedBasis0))
            {
                matchCount0++;
            }
        }

        if (validBasisCount0 == 0)
        {
            return 0.0;
        }

        return (double) matchCount0 / validBasisCount0;
    }

    /*
     * Simple thesis overlap for version 1.
     *
     * This is intentionally cheap and deterministic. Later, replace this with
     * an OpenAI semantic similarity call.
     */
    private double scoreTextOverlap(String basisText0, String candidateText0)
    {
        if (basisText0 == null || candidateText0 == null)
        {
            return 0.0;
        }

        String normalizedCandidateText0 = normalizeForTextContains(candidateText0);

        if (normalizedCandidateText0.length() == 0)
        {
            return 0.0;
        }

        String[] words0 = basisText0.toLowerCase().split("\\s+");

        int usefulWordCount0 = 0;
        int matchCount0 = 0;

        for (String word0 : words0)
        {
            String normalizedWord0 = normalize(word0);

            if (normalizedWord0.length() < 4)
            {
                continue;
            }

            usefulWordCount0++;

            if (normalizedCandidateText0.contains(normalizedWord0))
            {
                matchCount0++;
            }
        }

        if (usefulWordCount0 == 0)
        {
            return 0.0;
        }

        return Math.min(1.0, (double) matchCount0 / Math.min(10, usefulWordCount0));
    }

    private String buildExplanation(CandidateInvestor candidate0)
    {
        return "Candidate scored "
            + roundScore(candidate0.finalScore)
            + " based on sector="
            + roundScore(candidate0.subscores.get("sector"))
            + ", microsector="
            + roundScore(candidate0.subscores.get("microsector"))
            + ", geography="
            + roundScore(candidate0.subscores.get("geography"))
            + ", prior_backed_funds="
            + roundScore(candidate0.subscores.get("prior_backed_funds"))
            + ", investment_thesis="
            + roundScore(candidate0.subscores.get("investment_thesis"))
            + ".";
    }

    private InvestorProfile parseProfileOrNull(String intelligenceJson0)
    {
        try
        {
            if (isBlank(intelligenceJson0))
            {
                return null;
            }

            return InvestorProfile.fromIntelligenceJsonString(intelligenceJson0);
        }
        catch (Exception exception0)
        {
            return null;
        }
    }

    private boolean hasUsefulProfile(InvestorProfile profile0)
    {
        if (profile0 == null)
        {
            return false;
        }

        return hasAny(profile0.sectors)
            || hasAny(profile0.microsectors)
            || hasAny(profile0.geographies)
            || hasAny(profile0.priorBackedFunds)
            || !isBlank(profile0.investmentThesis);
    }

    private boolean hasAny(String[] values0)
    {
        if (values0 == null)
        {
            return false;
        }

        for (String value0 : values0)
        {
            if (!isBlank(value0))
            {
                return true;
            }
        }

        return false;
    }

    private boolean isFirstInterestOrBetter(String status0)
    {
        int rank0 = statusRank(status0);
        return rank0 >= statusRank("First Interest") && rank0 < statusRank("Rejected");
    }

    private int statusRank(String status0)
    {
        if (status0 == null)
        {
            return 0;
        }

        String normalized0 = status0.trim().toLowerCase();

        if (normalized0.equals("cold")) return 0;
        if (normalized0.equals("reached out")) return 1;
        if (normalized0.equals("first interest")) return 2;
        if (normalized0.equals("meetings")) return 3;
        if (normalized0.equals("prospective close")) return 4;
        if (normalized0.equals("rejected")) return 5;

        return 0;
    }

    private String getCell(String[][] data0, int rowNumber0, int oneBasedColumn0)
    {
        int rowIndex0 = rowNumber0 - 1;
        int colIndex0 = oneBasedColumn0 - 1;

        if (data0 == null || rowIndex0 < 0 || rowIndex0 >= data0.length)
        {
            return "";
        }

        if (colIndex0 < 0 || colIndex0 >= data0[rowIndex0].length)
        {
            return "";
        }

        return data0[rowIndex0][colIndex0] == null ? "" : data0[rowIndex0][colIndex0].trim();
    }

    private String cleanTag(String value0)
    {
        if (value0 == null)
        {
            return "";
        }

        return value0.trim();
    }

    private String normalize(String value0)
    {
        if (value0 == null)
        {
            return "";
        }

        return value0
            .toLowerCase()
            .replaceAll("[^a-z0-9]", "")
            .trim();
    }

    private String normalizeForTextContains(String value0)
    {
        if (value0 == null)
        {
            return "";
        }

        return value0
            .toLowerCase()
            .replaceAll("[^a-z0-9\\s]", " ")
            .replaceAll("\\s+", " ")
            .trim();
    }

    private boolean isBlank(String value0)
    {
        return value0 == null || value0.trim().length() == 0;
    }

    private String safe(String value0)
    {
        return value0 == null ? "" : value0;
    }

    private String safeCandidateName(CandidateInvestor candidate0)
    {
        if (candidate0 == null)
        {
            return "Unknown";
        }

        if (!isBlank(candidate0.fundName)) return candidate0.fundName;
        if (!isBlank(candidate0.name)) return candidate0.name;
        if (!isBlank(candidate0.website)) return candidate0.website;
        return "Unknown";
    }

    private String roundScore(Double score0)
    {
        if (score0 == null)
        {
            return "0.00";
        }

        return String.format("%.2f", score0);
    }
}
