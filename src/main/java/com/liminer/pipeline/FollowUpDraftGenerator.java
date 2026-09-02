package com.liminer.pipeline;

import com.liminer.core.SessionContext;
import com.liminer.llm.OpenAIClient;
import com.liminer.sheets.SheetsApp;

import java.util.ArrayList;
import org.json.JSONObject;
import org.json.JSONArray;
import java.util.HashMap;

public class FollowUpDraftGenerator
{
    // ============================================================
    // PURPOSE
    //
    // Takes follow-up recommendations and generates actual
    // investor follow-up email drafts.
    //
    // INPUT:
    // ArrayList<String[]>
    //
    // // recommendation row format:
    // [0] investor email
    // [1] latest conversation label
    // [2] days since last contact
    // [3] recommendation
    //
    // draft row format:
    // [0] investor email
    // [1] latest conversation label
    // [2] days since last contact
    // [3] recommendation
    // [4] email subject
    // [5] email body
    // ============================================================

    private static final int MAX_ROWS0 = 1000;

    public static ArrayList<String[]> generateDrafts(
        SessionContext context0,
        ArrayList<String[]> recommendations0) throws Exception
    {
        ArrayList<String[]> drafts0 = new ArrayList<>();

        if (recommendations0 == null)
        {
            return drafts0;
        }

        for (int i = 0; i < recommendations0.size(); i++)
        {
            String[] recommendationRow0 = recommendations0.get(i);

            if (recommendationRow0 == null)
            {
                continue;
            }

            String investorEmail0 = recommendationRow0[0];
            String latestLabel0 = recommendationRow0[1];
            String daysSinceLastContact0 = recommendationRow0[2];
            String recommendation0 = recommendationRow0[3];

            if (isBlank(investorEmail0) || isBlank(recommendation0))
            {
                continue;
            }

            String draftJson0 = generateDraftForInvestor(
                context0,
                investorEmail0,
                latestLabel0,
                daysSinceLastContact0,
                recommendation0,
                5
            );

            JSONObject draftObject0 =
                parseJsonObjectFromText(draftJson0);

            String subject0 =
                draftObject0.optString(
                    "subject",
                    ""
                );

            String body0 =
                draftObject0.optString(
                    "body",
                    ""
                );

            String[] draftRow0 = new String[6];

            draftRow0[0] = investorEmail0;
            draftRow0[1] = latestLabel0;
            draftRow0[2] = daysSinceLastContact0;
            draftRow0[3] = recommendation0;
            draftRow0[4] = subject0;
            draftRow0[5] = body0;

            drafts0.add(draftRow0);
        }

        return drafts0;
    }

    // ============================================================
    // GENERATE DRAFT FOR ONE INVESTOR
    // ============================================================

    private static String generateDraftForInvestor(
    SessionContext context0,
    String investorEmail0,
    String latestLabel0,
    String daysSinceLastContact0,
    String recommendation0,
    int maxEmails0) throws Exception
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

            JSONObject emailObject0 = new JSONObject();

            emailObject0.put(
                "timestamp",
                email0.timestamp
            );

            emailObject0.put(
                "to",
                email0.to
            );

            emailObject0.put(
                "from",
                email0.from
            );

            emailObject0.put(
                "subject",
                email0.subject
            );

            emailObject0.put(
                "body",
                email0.body
            );

            emailObject0.put(
                "conversationLabel",
                email0.conversationLabel
            );

            emailsArray0.put(emailObject0);
        }

        String prompt0 =
            "You are an AI investor relations assistant for a venture capital fund.\n\n"

            + "Fund name: "
            + context0.user.fundName
            + "\n\n"

            + "Investor email: "
            + investorEmail0
            + "\n\n"

            + "Follow-up recommendation:\n"
            + recommendation0
            + "\n\n"

            + "Recent email history:\n"
            + emailsArray0.toString(2)
            + "\n\n"

            + "Latest relationship label: " + latestLabel0 + "\n"
            + "Days since last contact: " + daysSinceLastContact0 + "\n\n"

            + "Return ONLY valid JSON.\n"
            + "No markdown.\n"
            + "No explanation.\n\n"

            + "Return exactly this format:\n"

            + "{\n"
            + "  \"subject\": \"\",\n"
            + "  \"body\": \"\"\n"
            + "}\n\n"

            + "Rules:\n"
            + "1. Write a concise professional investor follow-up email.\n"
            + "2. Do not invent facts.\n"
            + "3. Use the recommendation and email history.\n"
            + "4. The email should feel natural and realistic.\n"
            + "5. Do not mention CRM_TEST.\n"
            + "6. Avoid sounding robotic.\n"
            + "7. Avoid overexplaining.\n"
            + "8. The email should be ready to send.\n";

        return OpenAIClient.getTextResponse(prompt0);
    }

    // ============================================================
    // GET RECENT EMAIL HISTORY
    // ============================================================

    private static ArrayList<EmailInteraction>
    getRecentEmailsForInvestor(
        SessionContext context0,
        String investorEmail0,
        int maxEmails0) throws Exception
    {
        String spreadsheetId0 =
            context0.config.spreadsheetId;

        String intakeTabName0 =
            context0.config.intakeTabName;

        int cleanedEmailCol0 =
            SheetsApp.findColumnByHeader(
                spreadsheetId0,
                intakeTabName0,
                context0.config.getCol("intakeTabCleanedEmailCol")
            );

        int timestampCol0 =
            SheetsApp.findColumnByHeader(
                spreadsheetId0,
                intakeTabName0,
                context0.config.getCol("intakeTabTimestampCol")
            );

        int toCol0 =
            SheetsApp.findColumnByHeader(
                spreadsheetId0,
                intakeTabName0,
                context0.config.getCol("intakeTabToCol")
            );

        int fromCol0 =
            SheetsApp.findColumnByHeader(
                spreadsheetId0,
                intakeTabName0,
                context0.config.getCol("intakeTabFromCol")
            );

        int subjectCol0 =
            SheetsApp.findColumnByHeader(
                spreadsheetId0,
                intakeTabName0,
                context0.config.getCol("intakeTabSubjectCol")
            );

        int bodyCol0 =
            SheetsApp.findColumnByHeader(
                spreadsheetId0,
                intakeTabName0,
                context0.config.getCol("intakeTabBodyCol")
            );

        int labelCol0 =
            SheetsApp.findColumnByHeader(
                spreadsheetId0,
                intakeTabName0,
                context0.config.getCol("intakeTabConversationLabelCol")
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

        String[][] intakeData0 =
            SheetsApp.readRangeMatrix(
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

        if (emails0.size() <= maxEmails0)
        {
            return emails0;
        }

        ArrayList<EmailInteraction> recent0 =
            new ArrayList<>();

        int startIndex0 =
            emails0.size() - maxEmails0;

        for (int i = startIndex0;
             i < emails0.size();
             i++)
        {
            recent0.add(emails0.get(i));
        }

        return recent0;
    }

    // ============================================================
    // HELPERS
    // ============================================================

    private static JSONObject parseJsonObjectFromText(
        String text0)
    {
        String trimmedText0 =
            text0.trim();

        try
        {
            return new JSONObject(trimmedText0);
        }
        catch (Exception exception0)
        {
            int startIndex0 =
                trimmedText0.indexOf("{");

            int endIndex0 =
                trimmedText0.lastIndexOf("}");

            if (startIndex0 == -1
                || endIndex0 == -1
                || endIndex0 <= startIndex0)
            {
                throw exception0;
            }

            String jsonObjectText0 =
                trimmedText0.substring(
                    startIndex0,
                    endIndex0 + 1
                );

            return new JSONObject(jsonObjectText0);
        }
    }

    private static String getCellValue(
        String[][] data0,
        int rowNumber0,
        int oneBasedColumn0)
    {
        int rowIndex0 =
            rowNumber0 - 1;

        int columnIndex0 =
            oneBasedColumn0 - 1;

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

        return data0[rowIndex0][columnIndex0]
            .trim();
    }

    private static boolean isBlank(
        String value0)
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

    // ============================================================
    // INTERNAL EMAIL OBJECT
    // ============================================================

    private static class EmailInteraction
    {
        public String timestamp;
        public String to;
        public String from;
        public String subject;
        public String body;
        public String conversationLabel;
    }
}