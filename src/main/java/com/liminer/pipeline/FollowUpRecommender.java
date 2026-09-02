package com.liminer.pipeline;

import com.liminer.core.SessionContext;
import com.liminer.llm.OpenAIClient;
import com.liminer.sheets.SheetsApp;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.time.LocalDateTime;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import org.json.JSONArray;
import java.util.HashMap;

public class FollowUpRecommender
{
    private static final int MAX_ROWS0 = 1000;

    private static final int maxRecommendations0 = 5;
    private static final int maxEmailsPerInvestor0 = 8;

    public static ArrayList<String[]> generateRecommendations(
        SessionContext context0) throws Exception
    {
        ArrayList<RankedInvestor> rankedInvestors0 =
            getTopInvestorsToFollowUp(
                context0,
                maxRecommendations0
            );

        ArrayList<String[]> recommendations0 = new ArrayList<>();

        for (int i = 0; i < rankedInvestors0.size(); i++)
        {
            RankedInvestor investor0 = rankedInvestors0.get(i);

            String recommendation0 =
                generateRecommendationForInvestor(
                    context0,
                    investor0.email,
                    maxEmailsPerInvestor0,
                    investor0
                );

            String[] row0 = new String[4];

            row0[0] = investor0.email;
            row0[1] = investor0.latestLabel;
            row0[2] = String.valueOf(investor0.daysSinceLastContact);
            row0[3] = recommendation0;

            recommendations0.add(row0);
        }

        return recommendations0;
    }

    private static ArrayList<RankedInvestor> getTopInvestorsToFollowUp(
        SessionContext context0,
        int topN0) throws Exception
    {
        String spreadsheetId0 = context0.config.spreadsheetId;
        String intakeTabName0 = context0.config.intakeTabName;

        int cleanedEmailCol0 = findRequiredColumn(
            spreadsheetId0,
            intakeTabName0,
            context0.config.getCol("intakeTabCleanedEmailCol"),
            "intakeTabCleanedEmailCol"
        );

        int labelCol0 = findRequiredColumn(
            spreadsheetId0,
            intakeTabName0,
            context0.config.getCol("intakeTabConversationLabelCol"),
            "intakeTabConversationLabelCol"
        );

        int timestampCol0 = findRequiredColumn(
            spreadsheetId0,
            intakeTabName0,
            context0.config.getCol("intakeTabTimestampCol"),
            "intakeTabTimestampCol"
        );

        int needsReviewCol0 = findRequiredColumn(
            spreadsheetId0,
            intakeTabName0,
            context0.config.getCol("intakeTabNeedsReviewCol"),
            "intakeTabNeedsReviewCol"
        );

        int maxCol0 = max(
            cleanedEmailCol0,
            labelCol0,
            timestampCol0,
            needsReviewCol0
        );

        String[][] intakeData0 = SheetsApp.readRangeMatrix(
            spreadsheetId0,
            intakeTabName0,
            1,
            1,
            MAX_ROWS0,
            maxCol0
        );

        ArrayList<RankedInvestor> investors0 = new ArrayList<>();

        for (int rowNumber0 = context0.config.intakeTabDataStartRow;
             rowNumber0 <= intakeData0.length;
             rowNumber0++)
        {
            String email0 = getCellValue(
                intakeData0,
                rowNumber0,
                cleanedEmailCol0
            );

            String label0 = getCellValue(
                intakeData0,
                rowNumber0,
                labelCol0
            );

            String timestamp0 = getCellValue(
                intakeData0,
                rowNumber0,
                timestampCol0
            );

            String needsReview0 = getCellValue(
                intakeData0,
                rowNumber0,
                needsReviewCol0
            );

            if (isBlank(email0))
            {
                continue;
            }

            if (needsReview0.equalsIgnoreCase("TRUE"))
            {
                continue;
            }

            if (label0.equalsIgnoreCase("Rejected"))
            {
                continue;
            }

            RankedInvestor existing0 =
                findRankedInvestor(investors0, email0);

            if (existing0 == null)
            {
                RankedInvestor investor0 = new RankedInvestor();

                investor0.email = email0;
                investor0.latestLabel = label0;
                investor0.latestTimestamp = timestamp0;
                investor0.emailCount = 1;

                investors0.add(investor0);
            }
            else
            {
                existing0.emailCount++;

                if (isTimestampAfter(timestamp0, existing0.latestTimestamp))
                {
                    existing0.latestTimestamp = timestamp0;
                    existing0.latestLabel = label0;
                }
                else if (getStatusRank(label0) > getStatusRank(existing0.latestLabel))
                {
                    existing0.latestLabel = label0;
                }
            }
        }

        for (int i = 0; i < investors0.size(); i++)
        {
            RankedInvestor investor0 = investors0.get(i);

            investor0.daysSinceLastContact =
                daysSince(investor0.latestTimestamp);

            investor0.priorityScore =
                calculatePriorityScore(
                    investor0.latestLabel,
                    investor0.daysSinceLastContact,
                    investor0.emailCount
                );
        }

        investors0.removeIf(investor0 -> investor0.priorityScore <= 0);

        Collections.sort(
            investors0,
            new Comparator<RankedInvestor>()
            {
                public int compare(RankedInvestor a, RankedInvestor b)
                {
                    return Integer.compare(
                        b.priorityScore,
                        a.priorityScore
                    );
                }
            }
        );

        ArrayList<RankedInvestor> topInvestors0 =
            new ArrayList<>();

        for (int i = 0;
             i < investors0.size() && i < topN0;
             i++)
        {
            topInvestors0.add(investors0.get(i));
        }

        return topInvestors0;
    }

    private static String generateRecommendationForInvestor(
        SessionContext context0,
        String investorEmail0,
        int maxEmails0,
        RankedInvestor rankedInvestor0) throws Exception
    {
        ArrayList<EmailInteraction> recentEmails0 =
            getRecentEmailsForInvestor(
                context0,
                investorEmail0,
                maxEmails0
            );

        JSONArray emailsArray0 = new JSONArray();

        for (int i = 0; i < recentEmails0.size(); i++)
        {
            EmailInteraction email0 = recentEmails0.get(i);

            emailsArray0.put(
                "Timestamp: " + email0.timestamp
                + "\nDays Ago: " + daysSince(email0.timestamp)
                + "\nFrom: " + email0.from
                + "\nTo: " + email0.to
                + "\nSubject: " + email0.subject
                + "\nConversation Label: " + email0.conversationLabel
                + "\nBody: " + email0.body
            );
        }

        String prompt0 =
            "You are an AI investor relations assistant.\n\n"

            + "Investor Email: "
            + investorEmail0
            + "\n"

            + "Latest Relationship Stage: "
            + rankedInvestor0.latestLabel
            + "\n"

            + "Days Since Last Contact: "
            + rankedInvestor0.daysSinceLastContact
            + "\n"

            + "Number of Emails With Investor: "
            + rankedInvestor0.emailCount
            + "\n"

            + "Priority Score: "
            + rankedInvestor0.priorityScore
            + "\n\n"

            + "Recent Emails:\n"
            + emailsArray0.toString(2)
            + "\n\n"

            + "Return ONLY a short follow-up recommendation.\n"
            + "Do NOT write a full email.\n"
            + "Do NOT use markdown.\n"
            + "Do NOT explain reasoning.\n\n"

            + "The recommendation must consider:\n"
            + "- the latest conversation label\n"
            + "- how many days have passed since the last contact\n"
            + "- who appears to owe the next response\n"
            + "- whether the investor is warm, stale, active, or needs re-engagement\n\n"

            + "Examples:\n"
            + "- Follow up to schedule a diligence call.\n"
            + "- Re-engage investor with updated traction metrics.\n"
            + "- Send additional materials requested in the prior meeting.\n"
            + "- Follow up after investor showed initial interest but has not replied.\n";

        String aiOutput0 =
            OpenAIClient.getTextResponse(prompt0);

        return aiOutput0.trim();
    }

    private static ArrayList<EmailInteraction>
    getRecentEmailsForInvestor(
        SessionContext context0,
        String investorEmail0,
        int maxEmails0) throws Exception
    {
        String spreadsheetId0 = context0.config.spreadsheetId;
        String intakeTabName0 = context0.config.intakeTabName;

        int cleanedEmailCol0 = findRequiredColumn(
            spreadsheetId0,
            intakeTabName0,
            context0.config.getCol("intakeTabCleanedEmailCol"),
            "intakeTabCleanedEmailCol"
        );

        int timestampCol0 = findRequiredColumn(
            spreadsheetId0,
            intakeTabName0,
            context0.config.getCol("intakeTabTimestampCol"),
            "intakeTabTimestampCol"
        );

        int toCol0 = findRequiredColumn(
            spreadsheetId0,
            intakeTabName0,
            context0.config.getCol("intakeTabToCol"),
            "intakeTabToCol"
        );

        int fromCol0 = findRequiredColumn(
            spreadsheetId0,
            intakeTabName0,
            context0.config.getCol("intakeTabFromCol"),
            "intakeTabFromCol"
        );

        int subjectCol0 = findRequiredColumn(
            spreadsheetId0,
            intakeTabName0,
            context0.config.getCol("intakeTabSubjectCol"),
            "intakeTabSubjectCol"
        );

        int bodyCol0 = findRequiredColumn(
            spreadsheetId0,
            intakeTabName0,
            context0.config.getCol("intakeTabBodyCol"),
            "intakeTabBodyCol"
        );

        int labelCol0 = findRequiredColumn(
            spreadsheetId0,
            intakeTabName0,
            context0.config.getCol("intakeTabConversationLabelCol"),
            "intakeTabConversationLabelCol"
        );

        int maxCol0 = max(
            cleanedEmailCol0,
            timestampCol0,
            toCol0,
            fromCol0,
            subjectCol0,
            bodyCol0,
            labelCol0
        );

        String[][] intakeData0 = SheetsApp.readRangeMatrix(
            spreadsheetId0,
            intakeTabName0,
            1,
            1,
            MAX_ROWS0,
            maxCol0
        );

        ArrayList<EmailInteraction> emails0 =
            new ArrayList<>();

        for (int rowNumber0 =
                context0.config.intakeTabDataStartRow;
             rowNumber0 <= intakeData0.length;
             rowNumber0++)
        {
            String cleanedEmail0 =
                getCellValue(
                    intakeData0,
                    rowNumber0,
                    cleanedEmailCol0
                );

            if (!cleanedEmail0.equalsIgnoreCase(investorEmail0))
            {
                continue;
            }

            EmailInteraction email0 =
                new EmailInteraction();

            email0.timestamp =
                getCellValue(
                    intakeData0,
                    rowNumber0,
                    timestampCol0
                );

            email0.to =
                getCellValue(
                    intakeData0,
                    rowNumber0,
                    toCol0
                );

            email0.from =
                getCellValue(
                    intakeData0,
                    rowNumber0,
                    fromCol0
                );

            email0.subject =
                getCellValue(
                    intakeData0,
                    rowNumber0,
                    subjectCol0
                );

            email0.body =
                getCellValue(
                    intakeData0,
                    rowNumber0,
                    bodyCol0
                );

            email0.conversationLabel =
                getCellValue(
                    intakeData0,
                    rowNumber0,
                    labelCol0
                );

            emails0.add(email0);
        }

        Collections.sort(
            emails0,
            new Comparator<EmailInteraction>()
            {
                public int compare(
                    EmailInteraction a,
                    EmailInteraction b)
                {
                    return compareTimestamps(
                        a.timestamp,
                        b.timestamp
                    );
                }
            }
        );

        ArrayList<EmailInteraction> recent0 =
            new ArrayList<>();

        int startIndex0 =
            Math.max(
                0,
                emails0.size() - maxEmails0
            );

        for (int i = startIndex0;
             i < emails0.size();
             i++)
        {
            recent0.add(emails0.get(i));
        }

        return recent0;
    }

    private static int calculatePriorityScore(
        String label0,
        int daysSinceLastContact0,
        int emailCount0)
    {
        int base0 = getStatusRank(label0) * 10;

        int staleBonus0 = 0;

        if (label0.equals("Prospective Close"))
        {
            staleBonus0 =
                daysSinceLastContact0 >= 2 ? 30 : 10;
        }
        else if (label0.equals("Meetings"))
        {
            staleBonus0 =
                daysSinceLastContact0 >= 7 ? 25 : 5;
        }
        else if (label0.equals("First Interest"))
        {
            staleBonus0 =
                daysSinceLastContact0 >= 5 ? 20 : 5;
        }
        else if (label0.equals("Reached Out"))
        {
            staleBonus0 =
                daysSinceLastContact0 >= 3 ? 15 : 0;
        }

        int relationshipDepthBonus0 =
            Math.min(emailCount0, 5);

        return base0
            + staleBonus0
            + relationshipDepthBonus0;
    }

    private static int getStatusRank(String status0)
    {
        if (status0.equals("Reached Out")) return 1;
        if (status0.equals("First Interest")) return 2;
        if (status0.equals("Meetings")) return 3;
        if (status0.equals("Prospective Close")) return 4;
        return 0;
    }

    private static RankedInvestor findRankedInvestor(
        ArrayList<RankedInvestor> investors0,
        String email0)
    {
        for (int i = 0; i < investors0.size(); i++)
        {
            if (investors0.get(i).email.equalsIgnoreCase(email0))
            {
                return investors0.get(i);
            }
        }

        return null;
    }

    private static int findRequiredColumn(
        String spreadsheetId0,
        String tabName0,
        String header0,
        String configFieldName0) throws Exception
    {
        int col0 =
            SheetsApp.findColumnByHeader(
                spreadsheetId0,
                tabName0,
                header0
            );

        if (col0 == -1)
        {
            System.out.println(
                "Missing column for "
                + configFieldName0
            );
        }

        return col0;
    }

    private static String getCellValue(
        String[][] data0,
        int rowNumber0,
        int oneBasedColumn0)
    {
        int rowIndex0 = rowNumber0 - 1;
        int columnIndex0 = oneBasedColumn0 - 1;

        if (rowIndex0 < 0
            || rowIndex0 >= data0.length)
        {
            return "";
        }

        if (columnIndex0 < 0
            || columnIndex0 >= data0[rowIndex0].length)
        {
            return "";
        }

        if (data0[rowIndex0][columnIndex0] == null)
        {
            return "";
        }

        return data0[rowIndex0][columnIndex0].trim();
    }

    private static boolean isBlank(String value0)
    {
        return value0 == null
            || value0.trim().length() == 0;
    }

    private static int max(int... values0)
    {
        int max0 = -1;

        for (int i = 0; i < values0.length; i++)
        {
            if (values0[i] > max0)
            {
                max0 = values0[i];
            }
        }

        return max0;
    }

    private static int daysSince(String timestamp0)
    {
        LocalDateTime parsed0 =
            parseTimestamp(timestamp0);

        if (parsed0 == null)
        {
            return 999;
        }

        return (int)
            java.time.temporal.ChronoUnit.DAYS.between(
                parsed0.toLocalDate(),
                LocalDate.now()
            );
    }

    private static boolean isTimestampAfter(
        String timestampA0,
        String timestampB0)
    {
        LocalDateTime a0 =
            parseTimestamp(timestampA0);

        LocalDateTime b0 =
            parseTimestamp(timestampB0);

        if (a0 == null) return false;
        if (b0 == null) return true;

        return a0.isAfter(b0);
    }

    private static int compareTimestamps(
        String timestampA0,
        String timestampB0)
    {
        LocalDateTime a0 =
            parseTimestamp(timestampA0);

        LocalDateTime b0 =
            parseTimestamp(timestampB0);

        if (a0 == null && b0 == null) return 0;
        if (a0 == null) return -1;
        if (b0 == null) return 1;

        return a0.compareTo(b0);
    }

    private static LocalDateTime parseTimestamp(
        String timestamp0)
    {
        if (isBlank(timestamp0))
        {
            return null;
        }

        DateTimeFormatter[] formatters0 =
        {
            DateTimeFormatter.ofPattern(
                "yyyy-MM-dd HH:mm:ss"
            ),

            DateTimeFormatter.ofPattern(
                "yyyy-MM-dd HH:mm"
            ),

            DateTimeFormatter.ISO_LOCAL_DATE_TIME
        };

        for (int i = 0; i < formatters0.length; i++)
        {
            try
            {
                return LocalDateTime.parse(
                    timestamp0,
                    formatters0[i]
                );
            }
            catch (Exception e)
            {
            }
        }

        return null;
    }

    private static class RankedInvestor
    {
        public String email;
        public String latestLabel;
        public String latestTimestamp;

        public int daysSinceLastContact;
        public int emailCount;
        public int priorityScore;
    }

    private static class EmailInteraction
    {
        public String timestamp;
        public String to;
        public String from;
        public String subject;
        public String body;
        public String conversationLabel;
    }

    public static String writeRecommendationsToCrm(
        SessionContext context0,
        ArrayList<String[]> recommendations0
    ) throws Exception
    {
        if (context0 == null || context0.user == null || context0.config == null)
        {
            return "ERROR: Missing session context.";
        }

        if (recommendations0 == null || recommendations0.size() == 0)
        {
            return "No recommendations to write.";
        }

        String spreadsheetId0 = context0.config.spreadsheetId;
        String crmTabName0 = context0.config.mainTabName;

        int crmEmailCol0 = SheetsApp.findColumnByHeader(
            spreadsheetId0,
            crmTabName0,
            context0.config.getCol("mainTabContact1EmailCol")
        );

        int followUpRecommendationCol0 = SheetsApp.findColumnByHeader(
            spreadsheetId0,
            crmTabName0,
            context0.config.getCol("mainTabFollowUpRecommendationCol")
        );

        if (crmEmailCol0 == -1)
        {
            return "ERROR: Could not find CRM email column: "
                + context0.config.getCol("mainTabContact1EmailCol");
        }

        if (followUpRecommendationCol0 == -1)
        {
            return "ERROR: Could not find Follow Up Recommendation column: "
                + context0.config.getCol("mainTabFollowUpRecommendationCol");
        }

        String[][] crmEmailColumnData0 = SheetsApp.readRangeMatrix(
            spreadsheetId0,
            crmTabName0,
            1,
            crmEmailCol0,
            2000,
            crmEmailCol0
        );

        int successCount0 = 0;
        int notFoundCount0 = 0;
        int skippedCount0 = 0;

        for (int i = 0; i < recommendations0.size(); i++)
        {
            String[] recommendationRow0 = recommendations0.get(i);

            if (recommendationRow0 == null || recommendationRow0.length < 4)
            {
                skippedCount0++;
                continue;
            }

            String email0 = recommendationRow0[0];
            String recommendationText0 = recommendationRow0[3];

            if (isBlank(email0) || isBlank(recommendationText0))
            {
                skippedCount0++;
                continue;
            }

            int crmRowNumber0 = findEmailInColumnData(
                crmEmailColumnData0,
                email0,
                context0.config.mainTabDataStartRow
            );

            if (crmRowNumber0 == -1)
            {
                notFoundCount0++;
                continue;
            }

            SheetsApp.updateCell(
                spreadsheetId0,
                crmTabName0,
                crmRowNumber0,
                followUpRecommendationCol0,
                recommendationText0
            );

            successCount0++;
        }

        return "Follow-up recommendations written. Success: "
            + successCount0
            + ", Not found: "
            + notFoundCount0
            + ", Skipped: "
            + skippedCount0
            + ".";
    }

    private static int findEmailInColumnData(
        String[][] columnData0,
        String email0,
        int dataStartRow0)
    {
        for (int rowNumber0 = dataStartRow0; rowNumber0 <= columnData0.length; rowNumber0++)
        {
            String currentEmail0 = getCellValue(
                columnData0,
                rowNumber0,
                1
            );

            if (!isBlank(currentEmail0) && currentEmail0.equalsIgnoreCase(email0))
            {
                return rowNumber0;
            }
        }

        return -1;
    }
}