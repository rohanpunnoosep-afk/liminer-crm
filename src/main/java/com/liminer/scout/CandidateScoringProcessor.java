package com.liminer.scout;

import com.liminer.core.InvestorProfile;
import com.liminer.core.SessionContext;
import com.liminer.sheets.SheetsApp;

import java.util.ArrayList;
import java.util.HashMap;

/*
 * CandidateScoringProcessor is the runnable CRM workflow for Pipeline 3 scoring.
 *
 * It is designed to be called from AgentMain like the other workflows.
 *
 * What it does:
 * 1. Reads the user's CRM.
 * 2. Builds an average basis InvestorProfile from investors whose Conversation Status
 *    is First Interest, Meetings, or Prospective Close.
 * 3. Finds the first 10 CRM rows that:
 *      - have a usable Intelligence JSON / InvestorProfile
 *      - have not already been scored in InvestorProfileSimilarity
 * 4. Scores those rows with CandidateScorer.
 * 5. Writes the numeric score into InvestorProfileSimilarity and the explanation into Comments.
 *
 * This file intentionally does not discover candidates. It only scores candidates
 * that already exist in the CRM and already have profile data.
 */
public class CandidateScoringProcessor
{
    private static final int MAX_COLUMNS0 = 200;
    private static final int MAX_CRM_ROWS0 = 2000;
    private static final int DEFAULT_MAX_ROWS_TO_SCORE0 = 25;

    public static String scoreNextUnscoredCandidates(SessionContext context0) throws Exception
    {
        return scoreNextUnscoredCandidates(
            context0,
            DEFAULT_MAX_ROWS_TO_SCORE0
        );
    }

    public static String scoreNextUnscoredCandidates(
        SessionContext context0,
        int maxRowsToScore0) throws Exception
    {
        if (context0 == null || context0.config == null)
        {
            return "ERROR: Missing session context or config.";
        }

        if (maxRowsToScore0 <= 0)
        {
            maxRowsToScore0 = DEFAULT_MAX_ROWS_TO_SCORE0;
        }

        String spreadsheetId0 = context0.config.spreadsheetId;
        String crmTabName0 = context0.config.mainTabName;

        HashMap<String, Integer> crmHeaderMap0 = SheetsApp.buildHeaderMap(
            spreadsheetId0,
            crmTabName0,
            context0.config.mainTabHeaderRow,
            MAX_COLUMNS0
        );

        RequiredColumns requiredColumns0 = buildRequiredColumns(context0, crmHeaderMap0);

        if (requiredColumns0.hasMissingColumn())
        {
            return "ERROR: Missing required candidate scoring columns. Need Conversation Status, Intelligence JSON, InvestorProfileSimilarity, and Comments.";
        }

        int maxReadColumn0 = requiredColumns0.getMaxColumn();

        String[][] crmData0 = SheetsApp.readRangeMatrix(
            spreadsheetId0,
            crmTabName0,
            1,
            1,
            MAX_CRM_ROWS0,
            maxReadColumn0
        );

        CandidateScorer scorer0 = new CandidateScorer();
        InvestorProfile averageBasis0 = scorer0.buildAverageBasisProfileFromCrm(context0);

        if (!hasUsefulProfile(averageBasis0))
        {
            return "ERROR: Could not build basis profile. Need at least one CRM investor with status First Interest or better and a usable Intelligence JSON.";
        }

        ArrayList<Integer> rowNumbers0 = new ArrayList<Integer>();
        ArrayList<CandidateInvestor> candidates0 = new ArrayList<CandidateInvestor>();

        for (int rowNumber0 = context0.config.mainTabDataStartRow;
             rowNumber0 <= crmData0.length;
             rowNumber0++)
        {
            if (candidates0.size() >= maxRowsToScore0)
            {
                break;
            }

            String existingScore0 = getCell(
                crmData0,
                rowNumber0,
                requiredColumns0.investorProfileSimilarityCol
            );

            if (!isBlank(existingScore0))
            {
                continue;
            }

            String intelligenceJson0 = getCell(
                crmData0,
                rowNumber0,
                requiredColumns0.intelligenceJsonCol
            );

            InvestorProfile profile0 = parseProfileOrNull(intelligenceJson0);

            if (!hasUsefulProfile(profile0))
            {
                continue;
            }

            CandidateInvestor candidate0 = buildCandidateFromCrmRow(
                crmData0,
                rowNumber0,
                requiredColumns0,
                profile0
            );

            rowNumbers0.add(rowNumber0);
            candidates0.add(candidate0);
        }

        if (candidates0.size() == 0)
        {
            return "Candidate scoring complete. No unscored CRM rows with InvestorProfiles found.";
        }

        scorer0.scoreCandidates(averageBasis0, candidates0);

        int minRow0 = min(rowNumbers0);
        int maxRow0 = max(rowNumbers0);
        int rowCount0 = maxRow0 - minRow0 + 1;

        String[][] investorProfileSimilarityColumn0 = SheetsApp.readRangeMatrix(
            spreadsheetId0,
            crmTabName0,
            minRow0,
            requiredColumns0.investorProfileSimilarityCol,
            maxRow0,
            requiredColumns0.investorProfileSimilarityCol
        );

        String[][] commentsColumn0 = SheetsApp.readRangeMatrix(
            spreadsheetId0,
            crmTabName0,
            minRow0,
            requiredColumns0.commentsCol,
            maxRow0,
            requiredColumns0.commentsCol
        );

        for (int i = 0; i < candidates0.size(); i++)
        {
            CandidateInvestor candidate0 = candidates0.get(i);
            int rowNumber0 = rowNumbers0.get(i);
            int localRowIndex0 = rowNumber0 - minRow0;

            investorProfileSimilarityColumn0[localRowIndex0][0] = formatScoreForCrm(candidate0);
            commentsColumn0[localRowIndex0][0] = formatCommentsForCrm(candidate0);

            System.out.println(
                "Scored row "
                + rowNumber0
                + " | "
                + firstNonBlank(candidate0.fundName, candidate0.name, "Unknown")
                + " | score="
                + String.format("%.2f", candidate0.finalScore)
            );
        }

        SheetsApp.updateRangeMatrix(
            spreadsheetId0,
            crmTabName0,
            minRow0,
            requiredColumns0.investorProfileSimilarityCol,
            investorProfileSimilarityColumn0
        );

        SheetsApp.updateRangeMatrix(
            spreadsheetId0,
            crmTabName0,
            minRow0,
            requiredColumns0.commentsCol,
            commentsColumn0
        );

        return "Candidate scoring complete. Scored rows: " + candidates0.size() + ".";
    }

    private static RequiredColumns buildRequiredColumns(
        SessionContext context0,
        HashMap<String, Integer> crmHeaderMap0)
    {
        RequiredColumns columns0 = new RequiredColumns();

        columns0.fundNameCol = SheetsApp.findColumnInHeaderMap(
            crmHeaderMap0,
            context0.config.getCol("mainTabFundNameCol")
        );

        columns0.firstNameCol = SheetsApp.findColumnInHeaderMap(
            crmHeaderMap0,
            context0.config.getCol("mainTabContact1FirstNameCol")
        );

        columns0.lastNameCol = SheetsApp.findColumnInHeaderMap(
            crmHeaderMap0,
            context0.config.getCol("mainTabContact1LastNameCol")
        );

        columns0.positionCol = SheetsApp.findColumnInHeaderMap(
            crmHeaderMap0,
            context0.config.getCol("mainTabContact1PositionCol")
        );

        columns0.statusCol = SheetsApp.findColumnInHeaderMap(
            crmHeaderMap0,
            context0.config.getCol("mainTabStatusCol")
        );

        columns0.websiteCol = SheetsApp.findColumnInHeaderMap(
            crmHeaderMap0,
            context0.config.getCol("mainTabWebsiteCol")
        );

        columns0.linkedInCol = SheetsApp.findColumnInHeaderMap(
            crmHeaderMap0,
            context0.config.getCol("mainTabContactLinkedInCol")
        );

        columns0.intelligenceJsonCol = SheetsApp.findColumnInHeaderMap(
            crmHeaderMap0,
            context0.config.getCol("mainTabIntelligenceJsonCol")
        );

        columns0.investorProfileSimilarityCol = SheetsApp.findColumnInHeaderMap(
            crmHeaderMap0,
            context0.config.getCol("mainTabInvestorProfileSimilarityCol")
        );

        columns0.commentsCol = SheetsApp.findColumnInHeaderMap(
            crmHeaderMap0,
            context0.config.getCol("mainTabCommentsCol")
        );

        return columns0;
    }

    private static CandidateInvestor buildCandidateFromCrmRow(
        String[][] crmData0,
        int rowNumber0,
        RequiredColumns columns0,
        InvestorProfile profile0)
    {
        String fundName0 = getCell(crmData0, rowNumber0, columns0.fundNameCol);
        String website0 = getCell(crmData0, rowNumber0, columns0.websiteCol);
        String linkedIn0 = getCell(crmData0, rowNumber0, columns0.linkedInCol);

        CandidateInvestor candidate0 = new CandidateInvestor(
            fundName0,
            website0,
            linkedIn0
        );

        candidate0.firstName = getCell(crmData0, rowNumber0, columns0.firstNameCol);
        candidate0.lastName = getCell(crmData0, rowNumber0, columns0.lastNameCol);
        candidate0.name = (candidate0.firstName + " " + candidate0.lastName).trim();
        candidate0.position = getCell(crmData0, rowNumber0, columns0.positionCol);
        candidate0.conversationStatus = getCell(crmData0, rowNumber0, columns0.statusCol);
        candidate0.ip = profile0;

        return candidate0;
    }

    private static String formatScoreForCrm(CandidateInvestor candidate0)
    {
        if (candidate0 == null)
        {
            return "0.00";
        }

        return String.format("%.2f", candidate0.finalScore);
    }

    private static String formatCommentsForCrm(CandidateInvestor candidate0)
    {
        if (candidate0 == null)
        {
            return "";
        }

        return candidate0.scoreExplanation == null ? "" : candidate0.scoreExplanation;
    }

    private static InvestorProfile parseProfileOrNull(String intelligenceJson0)
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

    private static boolean hasUsefulProfile(InvestorProfile profile0)
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

    private static boolean hasAny(String[] values0)
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

    private static int min(ArrayList<Integer> values0)
    {
        int min0 = Integer.MAX_VALUE;

        for (Integer value0 : values0)
        {
            if (value0 != null && value0 < min0)
            {
                min0 = value0;
            }
        }

        return min0;
    }

    private static int max(ArrayList<Integer> values0)
    {
        int max0 = -1;

        for (Integer value0 : values0)
        {
            if (value0 != null && value0 > max0)
            {
                max0 = value0;
            }
        }

        return max0;
    }

    private static String getCell(String[][] data0, int rowNumber0, int oneBasedColumn0)
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

    private static String firstNonBlank(String a0, String b0, String c0)
    {
        if (!isBlank(a0)) return a0;
        if (!isBlank(b0)) return b0;
        if (!isBlank(c0)) return c0;
        return "";
    }

    private static boolean isBlank(String value0)
    {
        return value0 == null || value0.trim().length() == 0;
    }

    private static class RequiredColumns
    {
        public int fundNameCol = -1;
        public int firstNameCol = -1;
        public int lastNameCol = -1;
        public int positionCol = -1;
        public int statusCol = -1;
        public int websiteCol = -1;
        public int linkedInCol = -1;
        public int intelligenceJsonCol = -1;
        public int investorProfileSimilarityCol = -1;
        public int commentsCol = -1;

        public boolean hasMissingColumn()
        {
            return statusCol == -1
                || intelligenceJsonCol == -1
                || investorProfileSimilarityCol == -1
                || commentsCol == -1;
        }

        public int getMaxColumn()
        {
            int max0 = 0;

            max0 = Math.max(max0, fundNameCol);
            max0 = Math.max(max0, firstNameCol);
            max0 = Math.max(max0, lastNameCol);
            max0 = Math.max(max0, positionCol);
            max0 = Math.max(max0, statusCol);
            max0 = Math.max(max0, websiteCol);
            max0 = Math.max(max0, linkedInCol);
            max0 = Math.max(max0, intelligenceJsonCol);
            max0 = Math.max(max0, investorProfileSimilarityCol);
            max0 = Math.max(max0, commentsCol);

            return max0;
        }
    }
}
