package com.liminer.pipeline;

import com.liminer.core.SessionContext;
import com.liminer.llm.OpenAIClient;
import com.liminer.sheets.SheetsApp;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import org.json.JSONObject;

public class PriorityActionProcessor
{
    private static final int MAX_CRM_ROWS0 = 2000;
    private static final int MAX_COLUMNS0 = 220;
    private static final int DEFAULT_MAX_ROWS_TO_SCORE0 = 25;
    private static final int PRIORITY_REFRESH_DAYS0 = 4;

    private static final int READ_FUND_NAME0 = 0;
    private static final int READ_CONTACT_FIRST_NAME0 = 1;
    private static final int READ_CONTACT_LAST_NAME0 = 2;
    private static final int READ_CONTACT_POSITION0 = 3;
    private static final int READ_CONVERSATION_STATUS0 = 4;
    private static final int READ_LAST_CONTACT_DATE0 = 5;
    private static final int READ_INTERACTION_HISTORY0 = 6;
    private static final int READ_NOTES0 = 7;
    private static final int READ_COMMENTS0 = 8;
    private static final int READ_INVESTMENT_PROBABILITY0 = 9;
    private static final int READ_INTELLIGENCE_JSON0 = 10;
    private static final int READ_PRIORITY_LAST_CALCULATED_AT0 = 11;

    private static final int UPDATE_PRIORITY_SCORE0 = 0;
    private static final int UPDATE_PRIORITY_REASON0 = 1;
    private static final int UPDATE_NEXT_ACTION0 = 2;
    private static final int UPDATE_PRIORITY_LAST_CALCULATED_AT0 = 3;
    private static final int UPDATE_FIELD_COUNT0 = 4;

    public static String updatePriorityScores(SessionContext context0) throws Exception
    {
        return updatePriorityScores(context0, DEFAULT_MAX_ROWS_TO_SCORE0);
    }

    public static String updatePriorityScores(SessionContext context0, int maxRowsToScore0) throws Exception
    {
        if (context0 == null || context0.user == null || context0.config == null)
        {
            return "ERROR: Missing session context.";
        }

        String spreadsheetId0 = context0.config.spreadsheetId;
        String crmTabName0 = context0.config.mainTabName;

        HashMap<String, Integer> headerMap0 = SheetsApp.buildHeaderMap(
            spreadsheetId0,
            crmTabName0,
            context0.config.mainTabHeaderRow,
            MAX_COLUMNS0
        );

        int[] readColumns0 = buildReadColumns(context0, headerMap0);
        int width0 = getMaxColumn(readColumns0);

        if (width0 <= 0)
        {
            return "ERROR: Could not find any readable CRM columns.";
        }

        int[] updateColumns0 = buildUpdateColumns(context0, headerMap0);

        if (hasMissingColumn(updateColumns0))
        {
            return "ERROR: Missing priority output columns. Required: Priority Score, Priority Reason, Next Action, Priority Last Calculated At.";
        }

        String[][] crmData0 = SheetsApp.readRangeMatrix(
            spreadsheetId0,
            crmTabName0,
            1,
            1,
            MAX_CRM_ROWS0,
            width0
        );

        LinkedHashMap<Integer, String[]> rowUpdates0 = new LinkedHashMap<Integer, String[]>();

        int scoredCount0 = 0;
        int skippedCount0 = 0;
        int recentlyPrioritizedSkippedCount0 = 0;
        int failedCount0 = 0;

        System.out.println("Building priority update array...");

        for (int rowNumber0 = context0.config.mainTabDataStartRow;
             rowNumber0 <= crmData0.length;
             rowNumber0++)
        {
            if (scoredCount0 >= maxRowsToScore0)
            {
                break;
            }

            PriorityInput input0 = buildPriorityInput(context0, crmData0, rowNumber0, readColumns0);

            if (!input0.hasAnyUsefulIdentifier())
            {
                skippedCount0++;
                continue;
            }

            if (wasPrioritizedRecently(input0.priorityLastCalculatedAt, PRIORITY_REFRESH_DAYS0))
            {
                recentlyPrioritizedSkippedCount0++;
                System.out.println(
                    "Skipped row "
                    + rowNumber0
                    + " | "
                    + firstNonBlank(input0.fundName, input0.contactName, "Unknown")
                    + " | recently prioritized at "
                    + input0.priorityLastCalculatedAt
                );
                continue;
            }

            try
            {
                System.out.println(
                    "Analyzing row "
                    + rowNumber0
                    + " | "
                    + firstNonBlank(input0.fundName, input0.contactName, "Unknown")
                );

                PriorityRecommendation recommendation0 = generatePriorityRecommendation(input0);
                rowUpdates0.put(rowNumber0, recommendationToUpdateArray(recommendation0));

                System.out.println(
                    "Prioritized row "
                    + rowNumber0
                    + " | "
                    + firstNonBlank(input0.fundName, input0.contactName, "Unknown")
                    + " | priority="
                    + formatPriorityScore(recommendation0.priorityScore)
                );

                scoredCount0++;
            }
            catch (Exception exception0)
            {
                failedCount0++;
                System.out.println("Priority scoring failed for row " + rowNumber0 + ": " + exception0.getMessage());
            }
        }

        System.out.println("Priority update array built. Rows queued for update: " + rowUpdates0.size());

        if (rowUpdates0.size() == 0)
        {
            return "Priority scoring complete. No rows updated. Skipped: "
                + skippedCount0
                + ", Recently prioritized skipped: "
                + recentlyPrioritizedSkippedCount0
                + ", Failed: "
                + failedCount0
                + ".";
        }

        executeColumnBatchUpdates(spreadsheetId0, crmTabName0, updateColumns0, rowUpdates0);

        return "Priority scoring complete. Updated: "
            + rowUpdates0.size()
            + ", Skipped: "
            + skippedCount0
            + ", Recently prioritized skipped: "
            + recentlyPrioritizedSkippedCount0
            + ", Failed: "
            + failedCount0
            + ".";
    }

    private static PriorityRecommendation generatePriorityRecommendation(PriorityInput input0) throws Exception
    {
        try
        {
            return generatePriorityRecommendationWithOpenAI(input0);
        }
        catch (Exception exception0)
        {
            System.out.println("OpenAI priority generation failed for " + input0.fundName + ": " + exception0.getMessage());
            return generatePriorityRecommendationDeterministically(input0);
        }
    }

    private static PriorityRecommendation generatePriorityRecommendationWithOpenAI(PriorityInput input0) throws Exception
    {
        String prompt0 = buildPriorityPrompt(input0);
        String aiText0 = OpenAIClient.getTextResponse(prompt0);
        JSONObject result0 = parseJsonObjectFromText(aiText0);

        PriorityRecommendation recommendation0 = new PriorityRecommendation();
        recommendation0.priorityScore = clampScore(result0.optDouble("priority_score", -1.0));
        recommendation0.priorityReason = result0.optString("priority_reason", "");
        recommendation0.nextAction = result0.optString("next_action", "");
        recommendation0.priorityLastCalculatedAt = java.time.Instant.now().toString();

        if (recommendation0.priorityScore < 0.0)
        {
            throw new Exception("OpenAI priority response missing priority_score.");
        }

        if (isBlank(recommendation0.priorityReason) || isBlank(recommendation0.nextAction))
        {
            throw new Exception("OpenAI priority response missing reason or next action.");
        }

        return recommendation0;
    }

    private static String buildPriorityPrompt(PriorityInput input0)
    {
        JSONObject row0 = new JSONObject();
        row0.put("fund_name", input0.fundName);
        row0.put("contact_name", input0.contactName);
        row0.put("contact_position", input0.contactPosition);
        row0.put("conversation_status", input0.conversationStatus);
        row0.put("last_contact_date", input0.lastContactDate);
        row0.put("days_since_last_contact", input0.daysSinceLastContact);
        row0.put("investor_profile_similarity", input0.investorProfileSimilarity);
        row0.put("interaction_history", input0.interactionHistory);
        row0.put("notes", input0.notes);
        row0.put("comments", input0.comments);
        row0.put("investor_profile", input0.investorProfileJson);

        JSONObject client0 = new JSONObject();
        client0.put("client_sector_tags", input0.clientSectorTags);
        client0.put("client_microsector_tags", input0.clientMicrosectorTags);
        client0.put("client_geography", input0.clientGeography);
        client0.put("client_investment_thesis", input0.clientInvestmentThesis);
        client0.put("client_profile_json", input0.clientProfileJson);

        return "You are a fundraising relationship intelligence agent for a VC fund.\n"
            + "Return ONLY valid JSON. No markdown.\n\n"
            + "Score priority from 0 to 100 based on fit, relationship momentum, timing, and conversation status.\n"
            + "Rejected, Do Not Contact, or Not Interested should be low priority.\n"
            + "The next_action must be specific and contextual.\n\n"
            + "Return exactly this JSON structure:\n"
            + "{\n"
            + "  \"priority_score\": 0,\n"
            + "  \"priority_reason\": \"1-2 sentences explaining why this is the right priority.\",\n"
            + "  \"next_action\": \"A specific recommended action.\"\n"
            + "}\n\n"
            + "Client profile JSON:\n" + client0.toString(2)
            + "\n\nInvestor CRM row JSON:\n" + row0.toString(2);
    }

    private static PriorityRecommendation generatePriorityRecommendationDeterministically(PriorityInput input0)
    {
        PriorityRecommendation recommendation0 = new PriorityRecommendation();

        double similarityComponent0 = clamp01(input0.investorProfileSimilarity) * 40.0;
        double statusComponent0 = getStatusComponent(input0.conversationStatus);
        double timingComponent0 = getTimingComponent(input0.daysSinceLastContact);
        double momentumComponent0 = getMomentumComponent(input0.interactionHistory, input0.notes, input0.comments);

        recommendation0.priorityScore = clampScore(similarityComponent0 + statusComponent0 + timingComponent0 + momentumComponent0);

        recommendation0.priorityReason = "Priority is based on investor profile similarity="
            + round1(input0.investorProfileSimilarity * 100.0)
            + ", conversation status='"
            + input0.conversationStatus
            + "', and days since last contact="
            + input0.daysSinceLastContact
            + ".";

        recommendation0.nextAction = buildDeterministicNextAction(input0);
        recommendation0.priorityLastCalculatedAt = java.time.Instant.now().toString();

        return recommendation0;
    }

    private static String buildDeterministicNextAction(PriorityInput input0)
    {
        String thesis0 = firstNonBlank(input0.investorInvestmentThesis, input0.clientInvestmentThesis, "the fund's investment focus");
        String contact0 = firstNonBlank(input0.contactName, "the investor");

        if (isRejected(input0.conversationStatus))
        {
            return "Do not prioritize outreach unless new information changes the relationship. Keep the row for tracking and avoid immediate follow-up.";
        }

        if (input0.daysSinceLastContact >= 0 && input0.daysSinceLastContact <= 2)
        {
            return "Wait before following up. Prepare a concise follow-up that references the last interaction and connects " + contact0 + " to " + thesis0 + ".";
        }

        if (input0.daysSinceLastContact >= 3 && input0.daysSinceLastContact <= 14)
        {
            return "Follow up now. Reference the most recent conversation and connect their profile to " + thesis0 + ". Ask for the next concrete step.";
        }

        if (input0.daysSinceLastContact >= 15 && input0.daysSinceLastContact <= 45)
        {
            return "Send a re-engagement follow-up. Remind them of the prior discussion, share one relevant update, and tie it to " + thesis0 + ".";
        }

        return "Send a light-touch reactivation note. Acknowledge that time has passed, share a short fund update, and connect it to " + thesis0 + ".";
    }

    private static PriorityInput buildPriorityInput(SessionContext context0, String[][] crmData0, int rowNumber0, int[] columns0)
    {
        PriorityInput input0 = new PriorityInput();

        input0.rowNumber = rowNumber0;
        input0.fundName = getCell(crmData0, rowNumber0, columns0[READ_FUND_NAME0]);
        input0.contactFirstName = getCell(crmData0, rowNumber0, columns0[READ_CONTACT_FIRST_NAME0]);
        input0.contactLastName = getCell(crmData0, rowNumber0, columns0[READ_CONTACT_LAST_NAME0]);
        input0.contactName = (input0.contactFirstName + " " + input0.contactLastName).trim();
        input0.contactPosition = getCell(crmData0, rowNumber0, columns0[READ_CONTACT_POSITION0]);
        input0.conversationStatus = getCell(crmData0, rowNumber0, columns0[READ_CONVERSATION_STATUS0]);
        input0.lastContactDate = getCell(crmData0, rowNumber0, columns0[READ_LAST_CONTACT_DATE0]);
        input0.interactionHistory = getCell(crmData0, rowNumber0, columns0[READ_INTERACTION_HISTORY0]);
        input0.notes = getCell(crmData0, rowNumber0, columns0[READ_NOTES0]);
        input0.comments = getCell(crmData0, rowNumber0, columns0[READ_COMMENTS0]);
        input0.investorProfileSimilarity = parseScore(getCell(crmData0, rowNumber0, columns0[READ_INVESTMENT_PROBABILITY0]));
        input0.investorProfileJson = getCell(crmData0, rowNumber0, columns0[READ_INTELLIGENCE_JSON0]);
        input0.priorityLastCalculatedAt = getCell(crmData0, rowNumber0, columns0[READ_PRIORITY_LAST_CALCULATED_AT0]);

        input0.daysSinceLastContact = calculateDaysSince(input0.lastContactDate);

        input0.clientSectorTags = getObjectStringField(context0.user, "clientSectorTags", "");
        input0.clientMicrosectorTags = getObjectStringField(context0.user, "clientMicrosectorTags", "");
        input0.clientGeography = getObjectStringField(context0.user, "clientGeography", "");
        input0.clientInvestmentThesis = getObjectStringField(context0.user, "clientInvestmentThesis", "");
        input0.clientProfileJson = getObjectStringField(context0.user, "clientProfileJson", "");

        input0.investorInvestmentThesis = extractInvestmentThesis(input0.investorProfileJson);

        return input0;
    }

    private static int[] buildReadColumns(SessionContext context0, HashMap<String, Integer> headerMap0)
    {
        return new int[]
        {
            findConfiguredColumn(context0.config, headerMap0, "mainTabFundNameCol", "Fund Name"),
            findConfiguredColumn(context0.config, headerMap0, "mainTabContact1FirstNameCol", "Contact 1 First Name"),
            findConfiguredColumn(context0.config, headerMap0, "mainTabContact1LastNameCol", "Contact 1 Last Name"),
            findConfiguredColumn(context0.config, headerMap0, "mainTabContact1PositionCol", "Contact 1 Position"),
            findConfiguredColumn(context0.config, headerMap0, "mainTabStatusCol", "Conversation Status"),
            findConfiguredColumn(context0.config, headerMap0, "mainTabLastContactDateCol", "Last Contact Date"),
            findConfiguredColumn(context0.config, headerMap0, "mainTabInteractionHistoryCol", "Interaction History"),
            findConfiguredColumn(context0.config, headerMap0, "mainTabNotesCol", "Notes"),
            findConfiguredColumn(context0.config, headerMap0, "mainTabCommentsCol", "Comments"),
            findConfiguredColumn(context0.config, headerMap0, "mainTabInvestmentProbabilityCol", "Investment Probability"),
            findConfiguredColumn(context0.config, headerMap0, "mainTabIntelligenceJsonCol", "Investor Profile"),
            findConfiguredColumn(context0.config, headerMap0, "mainTabPriorityLastCalculatedAtCol", "Priority Last Calculated At")
        };
    }

    private static int[] buildUpdateColumns(SessionContext context0, HashMap<String, Integer> headerMap0)
    {
        return new int[]
        {
            findConfiguredColumn(context0.config, headerMap0, "mainTabPriorityScoreCol", "Priority Score"),
            findConfiguredColumn(context0.config, headerMap0, "mainTabPriorityReasonCol", "Priority Reason"),
            findConfiguredColumn(context0.config, headerMap0, "mainTabNextActionCol", "Next Action"),
            findConfiguredColumn(context0.config, headerMap0, "mainTabPriorityLastCalculatedAtCol", "Priority Last Calculated At")
        };
    }

    private static int findConfiguredColumn(Object config0, HashMap<String, Integer> headerMap0, String configFieldName0, String fallbackHeader0)
    {
        String configuredHeader0 = getObjectStringField(config0, configFieldName0, "");

        int column0 = -1;

        if (!isBlank(configuredHeader0))
        {
            column0 = SheetsApp.findColumnInHeaderMap(headerMap0, configuredHeader0);
        }

        if (column0 == -1 && !isBlank(fallbackHeader0))
        {
            column0 = SheetsApp.findColumnInHeaderMap(headerMap0, fallbackHeader0);
        }

        if (column0 == -1)
        {
            System.out.println("Header not found for " + configFieldName0 + " / " + fallbackHeader0);
        }

        return column0;
    }

    private static String getObjectStringField(Object object0, String fieldName0, String fallback0)
    {
        try
        {
            Object value0 = object0.getClass().getField(fieldName0).get(object0);
            return value0 == null ? fallback0 : value0.toString();
        }
        catch (Exception exception0)
        {
            return fallback0;
        }
    }

    private static void executeColumnBatchUpdates(String spreadsheetId0, String crmTabName0, int[] updateCols0, LinkedHashMap<Integer, String[]> rowUpdates0) throws Exception
    {
        int minRow0 = findMinKey(rowUpdates0);
        int maxRow0 = findMaxKey(rowUpdates0);
        int rowCount0 = maxRow0 - minRow0 + 1;

        String[][][] columnUpdateData0 = new String[UPDATE_FIELD_COUNT0][rowCount0][1];

        for (int columnIndex0 = 0; columnIndex0 < UPDATE_FIELD_COUNT0; columnIndex0++)
        {
            columnUpdateData0[columnIndex0] = SheetsApp.readRangeMatrix(
                spreadsheetId0,
                crmTabName0,
                minRow0,
                updateCols0[columnIndex0],
                maxRow0,
                updateCols0[columnIndex0]
            );
        }

        for (Map.Entry<Integer, String[]> entry0 : rowUpdates0.entrySet())
        {
            int rowNumber0 = entry0.getKey();
            String[] updateValues0 = entry0.getValue();
            int localRowIndex0 = rowNumber0 - minRow0;

            System.out.println("Building internal update array for row " + rowNumber0);

            for (int fieldIndex0 = 0; fieldIndex0 < updateValues0.length; fieldIndex0++)
            {
                columnUpdateData0[fieldIndex0][localRowIndex0][0] =
                    updateValues0[fieldIndex0] == null ? "" : updateValues0[fieldIndex0];
            }
        }

        for (int columnIndex0 = 0; columnIndex0 < UPDATE_FIELD_COUNT0; columnIndex0++)
        {
            SheetsApp.updateRangeMatrix(
                spreadsheetId0,
                crmTabName0,
                minRow0,
                updateCols0[columnIndex0],
                columnUpdateData0[columnIndex0]
            );
        }
    }

    private static String[] recommendationToUpdateArray(PriorityRecommendation recommendation0)
    {
        String[] values0 = new String[UPDATE_FIELD_COUNT0];
        values0[UPDATE_PRIORITY_SCORE0] = formatPriorityScore(recommendation0.priorityScore);
        values0[UPDATE_PRIORITY_REASON0] = recommendation0.priorityReason;
        values0[UPDATE_NEXT_ACTION0] = recommendation0.nextAction;
        values0[UPDATE_PRIORITY_LAST_CALCULATED_AT0] = recommendation0.priorityLastCalculatedAt;
        return values0;
    }

    private static boolean wasPrioritizedRecently(String priorityLastCalculatedAt0, int days0)
    {
        LocalDate parsedDate0 = parseDate(priorityLastCalculatedAt0);

        if (parsedDate0 == null)
        {
            return false;
        }

        long daysSincePriority0 = java.time.temporal.ChronoUnit.DAYS.between(parsedDate0, LocalDate.now());

        return daysSincePriority0 >= 0 && daysSincePriority0 < days0;
    }

    private static double getStatusComponent(String status0)
    {
        if (status0 == null) return 0.0;

        String cleaned0 = status0.trim().toLowerCase();

        if (cleaned0.contains("do not contact") || cleaned0.contains("rejected") || cleaned0.contains("not interested")) return 0.0;
        if (cleaned0.contains("prospective close")) return 30.0;
        if (cleaned0.contains("meeting")) return 26.0;
        if (cleaned0.contains("first interest")) return 22.0;
        if (cleaned0.contains("responded") || cleaned0.contains("warm")) return 18.0;
        if (cleaned0.contains("reached out")) return 12.0;
        if (cleaned0.contains("cold")) return 7.0;

        return 8.0;
    }

    private static double getTimingComponent(int days0)
    {
        if (days0 < 0) return 8.0;
        if (days0 <= 2) return 4.0;
        if (days0 <= 14) return 20.0;
        if (days0 <= 45) return 13.0;
        if (days0 <= 120) return 7.0;
        return 3.0;
    }

    private static double getMomentumComponent(String interactionHistory0, String notes0, String comments0)
    {
        String text0 = (safe(interactionHistory0) + " " + safe(notes0) + " " + safe(comments0)).toLowerCase();

        if (text0.contains("asked") || text0.contains("interested") || text0.contains("meeting") || text0.contains("intro") || text0.contains("follow up"))
        {
            return 10.0;
        }

        if (text0.trim().length() > 0)
        {
            return 5.0;
        }

        return 0.0;
    }

    private static String extractInvestmentThesis(String intelligenceJson0)
    {
        try
        {
            if (isBlank(intelligenceJson0)) return "";

            JSONObject object0 = new JSONObject(intelligenceJson0);

            String[] keys0 = new String[]
            {
                "investment_thesis",
                "investmentThesis",
                "thesis",
                "focus",
                "strategy",
                "summary"
            };

            for (int i = 0; i < keys0.length; i++)
            {
                String value0 = object0.optString(keys0[i], "");
                if (!isBlank(value0)) return value0;
            }

            return "";
        }
        catch (Exception exception0)
        {
            return "";
        }
    }

    private static double parseScore(String value0)
    {
        if (isBlank(value0)) return 0.0;

        String cleaned0 = value0.trim().replace("%", "");

        try
        {
            double score0 = Double.parseDouble(cleaned0);
            if (score0 > 1.0) score0 = score0 / 100.0;
            return clamp01(score0);
        }
        catch (Exception exception0)
        {
            return 0.0;
        }
    }

    private static int calculateDaysSince(String dateText0)
    {
        LocalDate parsed0 = parseDate(dateText0);

        if (parsed0 == null)
        {
            return -1;
        }

        return (int) java.time.temporal.ChronoUnit.DAYS.between(parsed0, LocalDate.now());
    }

    private static LocalDate parseDate(String value0)
    {
        if (isBlank(value0)) return null;

        String cleaned0 = value0.trim();

        try
        {
            return LocalDateTime.parse(cleaned0, DateTimeFormatter.ISO_LOCAL_DATE_TIME).toLocalDate();
        }
        catch (Exception exception0) {}

        try
        {
            return java.time.Instant.parse(cleaned0).atZone(java.time.ZoneId.systemDefault()).toLocalDate();
        }
        catch (Exception exception0) {}

        try
        {
            return LocalDate.parse(cleaned0, DateTimeFormatter.ISO_LOCAL_DATE);
        }
        catch (Exception exception0) {}

        DateTimeFormatter[] dateFormatters0 = new DateTimeFormatter[]
        {
            DateTimeFormatter.ofPattern("M/d/yyyy"),
            DateTimeFormatter.ofPattern("MM/dd/yyyy")
        };

        for (int i = 0; i < dateFormatters0.length; i++)
        {
            try
            {
                return LocalDate.parse(cleaned0, dateFormatters0[i]);
            }
            catch (Exception exception0) {}
        }

        DateTimeFormatter[] dateTimeFormatters0 = new DateTimeFormatter[]
        {
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"),
            DateTimeFormatter.ofPattern("M/d/yyyy H:mm:ss"),
            DateTimeFormatter.ofPattern("M/d/yyyy H:mm")
        };

        for (int i = 0; i < dateTimeFormatters0.length; i++)
        {
            try
            {
                return LocalDateTime.parse(cleaned0, dateTimeFormatters0[i]).toLocalDate();
            }
            catch (Exception exception0) {}
        }

        return null;
    }

    private static JSONObject parseJsonObjectFromText(String text0)
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

    private static String getCell(String[][] data0, int rowNumber0, int columnNumber0)
    {
        if (data0 == null || rowNumber0 <= 0 || columnNumber0 <= 0) return "";

        int rowIndex0 = rowNumber0 - 1;
        int colIndex0 = columnNumber0 - 1;

        if (rowIndex0 < 0 || rowIndex0 >= data0.length) return "";
        if (colIndex0 < 0 || colIndex0 >= data0[rowIndex0].length) return "";
        if (data0[rowIndex0][colIndex0] == null) return "";

        return data0[rowIndex0][colIndex0].trim();
    }

    private static int getMaxColumn(int[] columns0)
    {
        int max0 = 0;

        for (int i = 0; i < columns0.length; i++)
        {
            if (columns0[i] > max0) max0 = columns0[i];
        }

        return max0;
    }

    private static boolean hasMissingColumn(int[] columns0)
    {
        if (columns0 == null) return true;

        for (int i = 0; i < columns0.length; i++)
        {
            if (columns0[i] <= 0) return true;
        }

        return false;
    }

    private static int findMinKey(LinkedHashMap<Integer, String[]> map0)
    {
        int min0 = Integer.MAX_VALUE;

        for (Integer key0 : map0.keySet())
        {
            if (key0 < min0) min0 = key0;
        }

        return min0;
    }

    private static int findMaxKey(LinkedHashMap<Integer, String[]> map0)
    {
        int max0 = -1;

        for (Integer key0 : map0.keySet())
        {
            if (key0 > max0) max0 = key0;
        }

        return max0;
    }

    private static boolean isRejected(String status0)
    {
        if (status0 == null) return false;

        String cleaned0 = status0.toLowerCase();

        return cleaned0.contains("rejected")
            || cleaned0.contains("do not contact")
            || cleaned0.contains("not interested");
    }

    private static double clamp01(double value0)
    {
        if (Double.isNaN(value0) || Double.isInfinite(value0)) return 0.0;
        if (value0 < 0.0) return 0.0;
        if (value0 > 1.0) return 1.0;
        return value0;
    }

    private static double clampScore(double value0)
    {
        if (Double.isNaN(value0) || Double.isInfinite(value0)) return 0.0;
        if (value0 < 0.0) return 0.0;
        if (value0 > 100.0) return 100.0;
        return value0;
    }

    private static String formatPriorityScore(double value0)
    {
        return String.format(java.util.Locale.US, "%.1f", clampScore(value0));
    }

    private static String round1(double value0)
    {
        return String.format(java.util.Locale.US, "%.1f", value0);
    }

    private static String firstNonBlank(String a0, String fallback0)
    {
        if (!isBlank(a0)) return a0;
        return fallback0;
    }

    private static String firstNonBlank(String a0, String b0, String fallback0)
    {
        if (!isBlank(a0)) return a0;
        if (!isBlank(b0)) return b0;
        return fallback0;
    }

    private static String safe(String value0)
    {
        return value0 == null ? "" : value0;
    }

    private static boolean isBlank(String value0)
    {
        return value0 == null || value0.trim().length() == 0;
    }

    private static class PriorityInput
    {
        public int rowNumber;
        public String fundName;
        public String contactFirstName;
        public String contactLastName;
        public String contactName;
        public String contactPosition;
        public String conversationStatus;
        public String lastContactDate;
        public int daysSinceLastContact;
        public String interactionHistory;
        public String notes;
        public String comments;
        public double investorProfileSimilarity;
        public String investorProfileJson;
        public String investorInvestmentThesis;
        public String priorityLastCalculatedAt;
        public String clientSectorTags;
        public String clientMicrosectorTags;
        public String clientGeography;
        public String clientInvestmentThesis;
        public String clientProfileJson;

        public boolean hasAnyUsefulIdentifier()
        {
            return !isBlank(fundName) || !isBlank(contactName) || !isBlank(investorProfileJson);
        }
    }

    private static class PriorityRecommendation
    {
        public double priorityScore;
        public String priorityReason;
        public String nextAction;
        public String priorityLastCalculatedAt;
    }
}