package com.liminer.sheets;

import com.liminer.core.CRMRegistry;
import com.liminer.core.InteractionRecord;
import com.liminer.core.SessionContext;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import org.json.JSONArray;
import org.json.JSONObject;

public class CrmUpdater
{
    private static final int MAX_INTAKE_ROWS0 = 1000;
    private static final int MAX_CRM_ROWS0 = 2000;
    private static final int MAX_COLUMNS0 = 150;

    private static final String PROCESSED_STATUS0 = "PROCESSED";
    private static final String TRUE_STATUS0 = "TRUE";

    public static final int MaxInteractionHistory = 5;

    private static final int UPDATE_FIRST_NAME0 = 0;
    private static final int UPDATE_LAST_NAME0 = 1;
    private static final int UPDATE_FUND_NAME0 = 2;
    private static final int UPDATE_EMAIL0 = 3;
    private static final int UPDATE_CONVERSATION_LABEL0 = 4;
    private static final int UPDATE_FUND_WEBSITE0 = 5;
    private static final int UPDATE_TIMESTAMP0 = 6;
    private static final int UPDATE_CONVERSATION_SUMMARY0 = 7;
    private static final int UPDATE_INTERACTION_RECORD_JSON0 = 8;
    private static final int UPDATE_FIELD_COUNT0 = 9;

    private static final int CRM_FUND_NAME0 = 0;
    private static final int CRM_CONTACT_1_FIRST_NAME0 = 1;
    private static final int CRM_CONTACT_1_LAST_NAME0 = 2;
    private static final int CRM_CONTACT_1_EMAIL0 = 3;
    private static final int CRM_CONTACT_2_FIRST_NAME0 = 4;
    private static final int CRM_CONTACT_2_LAST_NAME0 = 5;
    private static final int CRM_CONTACT_2_EMAIL0 = 6;
    private static final int CRM_STATUS0 = 7;
    private static final int CRM_WEBSITE0 = 8;
    private static final int CRM_LAST_CONTACT_DATE0 = 9;
    private static final int CRM_INTERACTION_HISTORY0 = 10;
    private static final int CRM_INTERACTION_RECORDS0 = 11;
    private static final int CRM_FIELD_COUNT0 = 12;

    private static final int MAX_INTERACTION_RECORDS_CELL_LENGTH0 = 49000;

    // Test seam (task 0150): swappable so plan/run can be verified against an
    // in-memory fake sheet without ever touching live Google Sheets.
    public static SheetsIOPort sheetsPort = SheetsIOPort.live();

    private static class UpdatePlan
    {
        String spreadsheetId0;
        String intakeTabName0;
        String crmTabName0;
        int[] intakeCols0;
        int[] crmCols0;
        HashMap<Integer, String[]> existingUpdateList0;
        ArrayList<String[]> newRowsList0;
        ArrayList<Integer> intakeRowsToMarkUpdated0;
        int queuedExistingCount0;
        int queuedNewCount0;
        int needsReviewCount0;
        int skippedCount0;
        String blockingError0;
    }

    // PURE READ: builds the header maps, reads both tabs and classifies every
    // processed-but-not-yet-applied intake row into "merge into an existing CRM row"
    // vs "append as a new CRM row", without writing anything. Shared by planCrmUpdate
    // (dry run) and updateCrmFromProcessedIntakeRows (the real write).
    private static UpdatePlan buildUpdatePlan(SessionContext context0) throws Exception
    {
        UpdatePlan plan0 = new UpdatePlan();

        String spreadsheetId0 = context0.config.spreadsheetId;
        String intakeTabName0 = context0.config.intakeTabName;
        String crmTabName0 = context0.config.mainTabName;

        plan0.spreadsheetId0 = spreadsheetId0;
        plan0.intakeTabName0 = intakeTabName0;
        plan0.crmTabName0 = crmTabName0;

        HashMap<String, Integer> intakeHeaderMap0 = sheetsPort.buildHeaderMap(
            spreadsheetId0,
            intakeTabName0,
            context0.config.intakeTabHeaderRow,
            MAX_COLUMNS0
        );

        HashMap<String, Integer> crmHeaderMap0 = sheetsPort.buildHeaderMap(
            spreadsheetId0,
            crmTabName0,
            context0.config.mainTabHeaderRow,
            MAX_COLUMNS0
        );

        int[] intakeCols0 = buildRequiredIntakeColumns(context0, intakeHeaderMap0);
        int[] crmCols0 = buildRequiredCrmColumns(context0, crmHeaderMap0);

        plan0.intakeCols0 = intakeCols0;
        plan0.crmCols0 = crmCols0;

        if (hasMissingColumn(intakeCols0) || hasMissingColumn(crmCols0))
        {
            plan0.blockingError0 = "Missing required CRM updater columns.";
            plan0.existingUpdateList0 = new HashMap<Integer, String[]>();
            plan0.newRowsList0 = new ArrayList<String[]>();
            plan0.intakeRowsToMarkUpdated0 = new ArrayList<Integer>();
            return plan0;
        }

        int intakeWidth0 = getMaxColumn(intakeCols0);
        int crmWidth0 = getMaxColumn(crmCols0);

        System.out.println("Reading processed intake rows...");

        String[][] intakeData0 = sheetsPort.readRangeMatrix(
            spreadsheetId0,
            intakeTabName0,
            1,
            1,
            MAX_INTAKE_ROWS0,
            intakeWidth0
        );

        System.out.println("Reading CRM rows...");

        String[][] crmData0 = sheetsPort.readRangeMatrix(
            spreadsheetId0,
            crmTabName0,
            1,
            1,
            MAX_CRM_ROWS0,
            crmWidth0
        );

        HashMap<Integer, String[]> ExistingUpdateList = new HashMap<Integer, String[]>();
        ArrayList<String[]> NewRowsList = new ArrayList<String[]>();
        ArrayList<Integer> intakeRowsToMarkUpdated0 = new ArrayList<Integer>();

        int queuedExistingCount0 = 0;
        int queuedNewCount0 = 0;
        int needsReviewCount0 = 0;
        int skippedCount0 = 0;

        for (int intakeRowNumber0 = context0.config.intakeTabDataStartRow;
             intakeRowNumber0 <= intakeData0.length;
             intakeRowNumber0++)
        {
            String processingStatus0 = getCellValue(
                intakeData0,
                intakeRowNumber0,
                intakeCols0[0]
            );

            String updatedCrm0 = getCellValue(
                intakeData0,
                intakeRowNumber0,
                intakeCols0[8]
            );

            if (!processingStatus0.equalsIgnoreCase(PROCESSED_STATUS0))
            {
                continue;
            }

            if (updatedCrm0.equalsIgnoreCase(TRUE_STATUS0))
            {
                skippedCount0++;
                continue;
            }

            String needsReview0 = getCellValue(
                intakeData0,
                intakeRowNumber0,
                intakeCols0[9]
            );

            if (needsReview0.equalsIgnoreCase(TRUE_STATUS0))
            {
                needsReviewCount0++;
                skippedCount0++;
                continue;
            }

            String[] intakeUpdate0 = buildUpdateRowFromIntake(
                intakeData0,
                intakeRowNumber0,
                intakeCols0
            );

            if (!hasAnyIdentifier(intakeUpdate0))
            {
                skippedCount0++;
                continue;
            }

            int queuedNewIndex0 = findMatchingQueuedNewRow(
                NewRowsList,
                intakeUpdate0
            );

            if (queuedNewIndex0 != -1)
            {
                mergeQueuedUpdate(
                    NewRowsList.get(queuedNewIndex0),
                    intakeUpdate0
                );

                intakeRowsToMarkUpdated0.add(intakeRowNumber0);
                queuedExistingCount0++;
                continue;
            }

            int queuedExistingRow0 = findMatchingExistingUpdate(
                ExistingUpdateList,
                intakeUpdate0
            );

            if (queuedExistingRow0 != -1)
            {
                addOrMergeExistingUpdate(
                    ExistingUpdateList,
                    queuedExistingRow0,
                    intakeUpdate0
                );

                intakeRowsToMarkUpdated0.add(intakeRowNumber0);
                queuedExistingCount0++;
                continue;
            }

            int crmRowNumber0 = findMatchingCrmRow(
                crmData0,
                crmCols0,
                intakeUpdate0,
                context0.config.mainTabDataStartRow
            );

            if (crmRowNumber0 != -1)
            {
                addOrMergeExistingUpdate(
                    ExistingUpdateList,
                    crmRowNumber0,
                    intakeUpdate0
                );

                intakeRowsToMarkUpdated0.add(intakeRowNumber0);
                queuedExistingCount0++;
                continue;
            }

            NewRowsList.add(copyUpdateRow(intakeUpdate0));
            intakeRowsToMarkUpdated0.add(intakeRowNumber0);
            queuedNewCount0++;
        }

        plan0.existingUpdateList0 = ExistingUpdateList;
        plan0.newRowsList0 = NewRowsList;
        plan0.intakeRowsToMarkUpdated0 = intakeRowsToMarkUpdated0;
        plan0.queuedExistingCount0 = queuedExistingCount0;
        plan0.queuedNewCount0 = queuedNewCount0;
        plan0.needsReviewCount0 = needsReviewCount0;
        plan0.skippedCount0 = skippedCount0;

        return plan0;
    }

    /**
     * Read-only dry run (task 0150): performs the exact same header-map build, tab
     * reads and row classification as updateCrmFromProcessedIntakeRows, but never
     * writes to the spreadsheet.
     */
    public static JSONObject planCrmUpdate(SessionContext context0) throws Exception
    {
        JSONObject json0 = new JSONObject();

        if (context0 == null || context0.user == null || context0.config == null)
        {
            json0.put("blockingError", "Missing session context.");
            json0.put("eligibleRowCount", 0);
            json0.put("existingRowCount", 0);
            json0.put("newRowCount", 0);
            json0.put("rowNumbers", new JSONArray());
            json0.put("columnsToWrite", new JSONArray());
            return json0;
        }

        UpdatePlan plan0 = buildUpdatePlan(context0);

        if (plan0.blockingError0 != null)
        {
            json0.put("blockingError", plan0.blockingError0);
            json0.put("eligibleRowCount", 0);
            json0.put("existingRowCount", 0);
            json0.put("newRowCount", 0);
            json0.put("rowNumbers", new JSONArray());
            json0.put("columnsToWrite", new JSONArray());
            return json0;
        }

        int existingCount0 = plan0.existingUpdateList0.size();
        int newCount0 = plan0.newRowsList0.size();

        ArrayList<Integer> existingRowNumbers0 = new ArrayList<Integer>(plan0.existingUpdateList0.keySet());
        java.util.Collections.sort(existingRowNumbers0);

        json0.put("eligibleRowCount", existingCount0 + newCount0);
        json0.put("existingRowCount", existingCount0);
        json0.put("newRowCount", newCount0);
        json0.put("rowNumbers", new JSONArray(existingRowNumbers0));
        json0.put("columnsToWrite", new JSONArray(buildRequiredCrmColumnHeaders(context0)));

        return json0;
    }

    public static String updateCrmFromProcessedIntakeRows(SessionContext context0) throws Exception
    {
        if (context0 == null || context0.user == null || context0.config == null)
        {
            return "ERROR: Missing session context.";
        }

        UpdatePlan plan0 = buildUpdatePlan(context0);

        if (plan0.blockingError0 != null)
        {
            return "ERROR: " + plan0.blockingError0;
        }

        String spreadsheetId0 = plan0.spreadsheetId0;
        String intakeTabName0 = plan0.intakeTabName0;
        String crmTabName0 = plan0.crmTabName0;
        int[] crmCols0 = plan0.crmCols0;
        int[] intakeCols0 = plan0.intakeCols0;
        HashMap<Integer, String[]> ExistingUpdateList = plan0.existingUpdateList0;
        ArrayList<String[]> NewRowsList = plan0.newRowsList0;
        ArrayList<Integer> intakeRowsToMarkUpdated0 = plan0.intakeRowsToMarkUpdated0;

        ExistingUpdateResult existingUpdateResult0 = new ExistingUpdateResult();

        if (ExistingUpdateList.size() > 0)
        {
            System.out.println("Updating existing CRM rows by column batches...");

            existingUpdateResult0 = updateExistingRowsInBatch(
                spreadsheetId0,
                crmTabName0,
                crmCols0,
                ExistingUpdateList
            );
        }

        if (NewRowsList.size() > 0)
        {
            System.out.println("Writing new CRM rows by column batches...");

            int nextCrmAppendRow0 = findNextCrmAppendRow(
                spreadsheetId0,
                crmTabName0,
                crmCols0[CRM_CONTACT_1_EMAIL0],
                crmCols0[CRM_FUND_NAME0],
                context0.config.mainTabDataStartRow
            );

            writeNewRowsInBatch(
                spreadsheetId0,
                crmTabName0,
                crmCols0,
                nextCrmAppendRow0,
                NewRowsList
            );
        }

        if (intakeRowsToMarkUpdated0.size() > 0)
        {
            System.out.println("Marking intake rows updated in batch...");

            markIntakeRowsUpdatedInBatch(
                spreadsheetId0,
                intakeTabName0,
                intakeCols0[8],
                intakeRowsToMarkUpdated0
            );
        }

        return "CRM update complete. Existing queued: "
            + plan0.queuedExistingCount0
            + ", Existing changed: "
            + existingUpdateResult0.changedCount
            + ", CRM update not needed: "
            + existingUpdateResult0.notNeededCount
            + ", New rows added: "
            + plan0.queuedNewCount0
            + ", Needs review: "
            + plan0.needsReviewCount0
            + ", Skipped: "
            + plan0.skippedCount0
            + ".";
    }

    public static String updateCrmFromProcessedIntakeRows() throws Exception
    {
        SessionContext context0 = CRMRegistry.login("your_email_here@gmail.com");

        if (context0 == null)
        {
            return "ERROR: Could not create default session context.";
        }

        return updateCrmFromProcessedIntakeRows(context0);
    }

    private static int[] buildRequiredIntakeColumns(
        SessionContext context0,
        HashMap<String, Integer> intakeHeaderMap0)
    {
        String[] headers0 = new String[]
        {
            context0.config.getCol("intakeTabProcessingStatusCol"),
            context0.config.getCol("intakeTabCleanedEmailCol"),
            context0.config.getCol("intakeTabExtractedFirstNameCol"),
            context0.config.getCol("intakeTabExtractedLastNameCol"),
            context0.config.getCol("intakeTabExtractedFundNameCol"),
            context0.config.getCol("intakeTabExtractedFundWebsiteCol"),
            context0.config.getCol("intakeTabConversationLabelCol"),
            context0.config.getCol("intakeTabConversationSummaryCol"),
            context0.config.getCol("intakeTabUpdatedCrmCol"),
            context0.config.getCol("intakeTabNeedsReviewCol"),
            context0.config.getCol("intakeTabTimestampCol"),
            context0.config.getCol("intakeTabInteractionRecordCol")
        };

        return buildColumnsFromHeaders(intakeHeaderMap0, headers0);
    }

    private static int[] buildRequiredCrmColumns(
        SessionContext context0,
        HashMap<String, Integer> crmHeaderMap0)
    {
        return buildColumnsFromHeaders(crmHeaderMap0, buildRequiredCrmColumnHeaders(context0));
    }

    private static String[] buildRequiredCrmColumnHeaders(SessionContext context0)
    {
        return new String[]
        {
            context0.config.getCol("mainTabFundNameCol"),
            context0.config.getCol("mainTabContact1FirstNameCol"),
            context0.config.getCol("mainTabContact1LastNameCol"),
            context0.config.getCol("mainTabContact1EmailCol"),
            context0.config.getCol("mainTabContact2FirstNameCol"),
            context0.config.getCol("mainTabContact2LastNameCol"),
            context0.config.getCol("mainTabContact2EmailCol"),
            context0.config.getCol("mainTabStatusCol"),
            context0.config.getCol("mainTabWebsiteCol"),
            context0.config.getCol("mainTabLastContactDateCol"),
            context0.config.getCol("mainTabInteractionHistoryCol"),
            context0.config.getCol("mainTabInteractionRecordsCol")
        };
    }

    private static int[] buildColumnsFromHeaders(
        HashMap<String, Integer> headerMap0,
        String[] headers0)
    {
        int[] columns0 = new int[headers0.length];

        for (int i = 0; i < headers0.length; i++)
        {
            columns0[i] = SheetsApp.findColumnInHeaderMap(
                headerMap0,
                headers0[i]
            );

            if (columns0[i] == -1)
            {
                System.out.println("Header not found: " + headers0[i]);
            }
        }

        return columns0;
    }

    private static boolean hasMissingColumn(int[] columns0)
    {
        if (columns0 == null)
        {
            return true;
        }

        for (int i = 0; i < columns0.length; i++)
        {
            if (columns0[i] == -1)
            {
                return true;
            }
        }

        return false;
    }

    private static String[] buildUpdateRowFromIntake(
        String[][] intakeData0,
        int intakeRowNumber0,
        int[] intakeCols0)
    {
        String[] update0 = new String[UPDATE_FIELD_COUNT0];

        update0[UPDATE_EMAIL0] = cleanEmail(getCellValue(intakeData0, intakeRowNumber0, intakeCols0[1]));
        update0[UPDATE_FIRST_NAME0] = getCellValue(intakeData0, intakeRowNumber0, intakeCols0[2]);
        update0[UPDATE_LAST_NAME0] = getCellValue(intakeData0, intakeRowNumber0, intakeCols0[3]);
        update0[UPDATE_FUND_NAME0] = getCellValue(intakeData0, intakeRowNumber0, intakeCols0[4]);
        update0[UPDATE_FUND_WEBSITE0] = cleanWebsite(getCellValue(intakeData0, intakeRowNumber0, intakeCols0[5]));
        update0[UPDATE_CONVERSATION_LABEL0] = getCellValue(intakeData0, intakeRowNumber0, intakeCols0[6]);
        update0[UPDATE_CONVERSATION_SUMMARY0] = getCellValue(intakeData0, intakeRowNumber0, intakeCols0[7]);
        update0[UPDATE_TIMESTAMP0] = getCellValue(intakeData0, intakeRowNumber0, intakeCols0[10]);

        String rawRecordJson0 = getCellValue(intakeData0, intakeRowNumber0, intakeCols0[11]);
        update0[UPDATE_INTERACTION_RECORD_JSON0] = wrapAsJsonArray(rawRecordJson0);

        return update0;
    }

    private static String wrapAsJsonArray(String jsonObjectStr0)
    {
        if (isBlank(jsonObjectStr0))
        {
            return "[]";
        }

        try
        {
            new JSONObject(jsonObjectStr0);
            return "[" + jsonObjectStr0 + "]";
        }
        catch (Exception e0)
        {
            return "[]";
        }
    }

    private static void addOrMergeExistingUpdate(
        HashMap<Integer, String[]> ExistingUpdateList,
        int crmRowNumber0,
        String[] incomingUpdate0)
    {
        if (!ExistingUpdateList.containsKey(crmRowNumber0))
        {
            ExistingUpdateList.put(crmRowNumber0, copyUpdateRow(incomingUpdate0));
            return;
        }

        mergeQueuedUpdate(
            ExistingUpdateList.get(crmRowNumber0),
            incomingUpdate0
        );
    }

    private static void mergeQueuedUpdate(
        String[] baseUpdate0,
        String[] incomingUpdate0)
    {
        if (baseUpdate0 == null || incomingUpdate0 == null)
        {
            return;
        }

        fillIfBlank(baseUpdate0, incomingUpdate0, UPDATE_FIRST_NAME0);
        fillIfBlank(baseUpdate0, incomingUpdate0, UPDATE_LAST_NAME0);
        fillIfBlank(baseUpdate0, incomingUpdate0, UPDATE_FUND_NAME0);
        fillIfBlank(baseUpdate0, incomingUpdate0, UPDATE_EMAIL0);
        fillIfBlank(baseUpdate0, incomingUpdate0, UPDATE_FUND_WEBSITE0);

        if (shouldUpdateStatus(
            baseUpdate0[UPDATE_CONVERSATION_LABEL0],
            incomingUpdate0[UPDATE_CONVERSATION_LABEL0]))
        {
            baseUpdate0[UPDATE_CONVERSATION_LABEL0] = incomingUpdate0[UPDATE_CONVERSATION_LABEL0];
        }

        if (isTimestampAfter(
            incomingUpdate0[UPDATE_TIMESTAMP0],
            baseUpdate0[UPDATE_TIMESTAMP0]))
        {
            baseUpdate0[UPDATE_TIMESTAMP0] = incomingUpdate0[UPDATE_TIMESTAMP0];
        }

        baseUpdate0[UPDATE_CONVERSATION_SUMMARY0] = mergeSummaries(
            baseUpdate0[UPDATE_CONVERSATION_SUMMARY0],
            incomingUpdate0[UPDATE_CONVERSATION_SUMMARY0]
        );

        baseUpdate0[UPDATE_INTERACTION_RECORD_JSON0] = mergeJsonArrayStrings(
            baseUpdate0[UPDATE_INTERACTION_RECORD_JSON0],
            incomingUpdate0[UPDATE_INTERACTION_RECORD_JSON0]
        );
    }

    private static void fillIfBlank(
        String[] baseUpdate0,
        String[] incomingUpdate0,
        int index0)
    {
        if (isBlank(baseUpdate0[index0]) && !isBlank(incomingUpdate0[index0]))
        {
            baseUpdate0[index0] = incomingUpdate0[index0];
        }
    }

    private static ExistingUpdateResult updateExistingRowsInBatch(
        String spreadsheetId0,
        String crmTabName0,
        int[] crmCols0,
        HashMap<Integer, String[]> ExistingUpdateList) throws Exception
    {
        ExistingUpdateResult result0 = new ExistingUpdateResult();

        int minRow0 = findMinKey(ExistingUpdateList);
        int maxRow0 = findMaxKey(ExistingUpdateList);

        if (minRow0 == -1 || maxRow0 == -1)
        {
            return result0;
        }

        int rowCount0 = maxRow0 - minRow0 + 1;
        String[][] updateData0 = new String[rowCount0][CRM_FIELD_COUNT0];

        for (int fieldIndex0 = 0; fieldIndex0 < CRM_FIELD_COUNT0; fieldIndex0++)
        {
            String[][] columnData0 = SheetsApp.readRangeMatrix(
                spreadsheetId0,
                crmTabName0,
                minRow0,
                crmCols0[fieldIndex0],
                maxRow0,
                crmCols0[fieldIndex0]
            );

            for (int rowIndex0 = 0; rowIndex0 < rowCount0; rowIndex0++)
            {
                updateData0[rowIndex0][fieldIndex0] = getLocalCellValue(
                    columnData0,
                    rowIndex0,
                    0
                );
            }
        }

        for (Map.Entry<Integer, String[]> entry0 : ExistingUpdateList.entrySet())
        {
            int crmRowNumber0 = entry0.getKey();
            String[] incomingUpdate0 = entry0.getValue();
            int localRowIndex0 = crmRowNumber0 - minRow0;

            boolean changed0 = applyIncomingUpdateToCrmRow(
                updateData0[localRowIndex0],
                incomingUpdate0
            );

            if (changed0)
            {
                result0.changedCount++;
            }
            else
            {
                result0.notNeededCount++;
            }
        }

        for (int fieldIndex0 = 0; fieldIndex0 < CRM_FIELD_COUNT0; fieldIndex0++)
        {
            String[][] column0 = extractColumn(updateData0, fieldIndex0);

            SheetsApp.updateRangeMatrix(
                spreadsheetId0,
                crmTabName0,
                minRow0,
                crmCols0[fieldIndex0],
                column0
            );
        }

        return result0;
    }

    private static boolean applyIncomingUpdateToCrmRow(
        String[] crmRow0,
        String[] incomingUpdate0)
    {
        boolean changed0 = false;

        changed0 = setIfBlank(crmRow0, CRM_FUND_NAME0, incomingUpdate0[UPDATE_FUND_NAME0]) || changed0;
        changed0 = setIfBlank(crmRow0, CRM_WEBSITE0, incomingUpdate0[UPDATE_FUND_WEBSITE0]) || changed0;

        changed0 = applyContactInfo(crmRow0, incomingUpdate0) || changed0;

        if (shouldUpdateStatus(
            crmRow0[CRM_STATUS0],
            incomingUpdate0[UPDATE_CONVERSATION_LABEL0]))
        {
            crmRow0[CRM_STATUS0] = incomingUpdate0[UPDATE_CONVERSATION_LABEL0];
            changed0 = true;
        }

        if (isTimestampAfter(
            incomingUpdate0[UPDATE_TIMESTAMP0],
            crmRow0[CRM_LAST_CONTACT_DATE0]))
        {
            crmRow0[CRM_LAST_CONTACT_DATE0] = incomingUpdate0[UPDATE_TIMESTAMP0];
            changed0 = true;
        }

        String oneLiner0 = extractOneLinerFromInteractionRecords(
            incomingUpdate0[UPDATE_INTERACTION_RECORD_JSON0],
            incomingUpdate0[UPDATE_CONVERSATION_SUMMARY0]
        );

        String updatedHistory0 = prependInteractionHistory(
            crmRow0[CRM_INTERACTION_HISTORY0],
            incomingUpdate0[UPDATE_TIMESTAMP0],
            oneLiner0
        );

        if (!safeEquals(crmRow0[CRM_INTERACTION_HISTORY0], updatedHistory0))
        {
            crmRow0[CRM_INTERACTION_HISTORY0] = updatedHistory0;
            changed0 = true;
        }

        String updatedRecords0 = appendInteractionRecords(
            crmRow0[CRM_INTERACTION_RECORDS0],
            incomingUpdate0[UPDATE_INTERACTION_RECORD_JSON0]
        );

        if (!safeEquals(crmRow0[CRM_INTERACTION_RECORDS0], updatedRecords0))
        {
            crmRow0[CRM_INTERACTION_RECORDS0] = updatedRecords0;
            changed0 = true;
        }

        return changed0;
    }

    private static boolean applyContactInfo(
        String[] crmRow0,
        String[] incomingUpdate0)
    {
        boolean changed0 = false;

        String incomingEmail0 = incomingUpdate0[UPDATE_EMAIL0];
        String incomingFirstName0 = incomingUpdate0[UPDATE_FIRST_NAME0];
        String incomingLastName0 = incomingUpdate0[UPDATE_LAST_NAME0];

        String contact1Email0 = crmRow0[CRM_CONTACT_1_EMAIL0];
        String contact2Email0 = crmRow0[CRM_CONTACT_2_EMAIL0];

        if (!isBlank(incomingEmail0) && sameEmail(incomingEmail0, contact1Email0))
        {
            changed0 = setIfBlank(crmRow0, CRM_CONTACT_1_FIRST_NAME0, incomingFirstName0) || changed0;
            changed0 = setIfBlank(crmRow0, CRM_CONTACT_1_LAST_NAME0, incomingLastName0) || changed0;
            return changed0;
        }

        if (!isBlank(incomingEmail0) && sameEmail(incomingEmail0, contact2Email0))
        {
            changed0 = setIfBlank(crmRow0, CRM_CONTACT_2_FIRST_NAME0, incomingFirstName0) || changed0;
            changed0 = setIfBlank(crmRow0, CRM_CONTACT_2_LAST_NAME0, incomingLastName0) || changed0;
            return changed0;
        }

        if (isBlank(contact1Email0) && isBlank(crmRow0[CRM_CONTACT_1_FIRST_NAME0]) && isBlank(crmRow0[CRM_CONTACT_1_LAST_NAME0]))
        {
            changed0 = setIfBlank(crmRow0, CRM_CONTACT_1_EMAIL0, incomingEmail0) || changed0;
            changed0 = setIfBlank(crmRow0, CRM_CONTACT_1_FIRST_NAME0, incomingFirstName0) || changed0;
            changed0 = setIfBlank(crmRow0, CRM_CONTACT_1_LAST_NAME0, incomingLastName0) || changed0;
            return changed0;
        }

        if (isSamePerson(
            incomingFirstName0,
            incomingLastName0,
            crmRow0[CRM_CONTACT_1_FIRST_NAME0],
            crmRow0[CRM_CONTACT_1_LAST_NAME0]))
        {
            changed0 = setIfBlank(crmRow0, CRM_CONTACT_1_EMAIL0, incomingEmail0) || changed0;
            return changed0;
        }

        if (isSamePerson(
            incomingFirstName0,
            incomingLastName0,
            crmRow0[CRM_CONTACT_2_FIRST_NAME0],
            crmRow0[CRM_CONTACT_2_LAST_NAME0]))
        {
            changed0 = setIfBlank(crmRow0, CRM_CONTACT_2_EMAIL0, incomingEmail0) || changed0;
            return changed0;
        }

        if (isBlank(contact2Email0) && isBlank(crmRow0[CRM_CONTACT_2_FIRST_NAME0]) && isBlank(crmRow0[CRM_CONTACT_2_LAST_NAME0]))
        {
            changed0 = setIfBlank(crmRow0, CRM_CONTACT_2_EMAIL0, incomingEmail0) || changed0;
            changed0 = setIfBlank(crmRow0, CRM_CONTACT_2_FIRST_NAME0, incomingFirstName0) || changed0;
            changed0 = setIfBlank(crmRow0, CRM_CONTACT_2_LAST_NAME0, incomingLastName0) || changed0;
        }

        return changed0;
    }

    private static void writeNewRowsInBatch(
        String spreadsheetId0,
        String crmTabName0,
        int[] crmCols0,
        int startRow0,
        ArrayList<String[]> NewRowsList) throws Exception
    {
        String[][] newCrmData0 = new String[NewRowsList.size()][CRM_FIELD_COUNT0];

        for (int rowIndex0 = 0; rowIndex0 < NewRowsList.size(); rowIndex0++)
        {
            String[] source0 = NewRowsList.get(rowIndex0);

            newCrmData0[rowIndex0][CRM_FUND_NAME0] = source0[UPDATE_FUND_NAME0];
            newCrmData0[rowIndex0][CRM_CONTACT_1_FIRST_NAME0] = source0[UPDATE_FIRST_NAME0];
            newCrmData0[rowIndex0][CRM_CONTACT_1_LAST_NAME0] = source0[UPDATE_LAST_NAME0];
            newCrmData0[rowIndex0][CRM_CONTACT_1_EMAIL0] = source0[UPDATE_EMAIL0];
            newCrmData0[rowIndex0][CRM_CONTACT_2_FIRST_NAME0] = "";
            newCrmData0[rowIndex0][CRM_CONTACT_2_LAST_NAME0] = "";
            newCrmData0[rowIndex0][CRM_CONTACT_2_EMAIL0] = "";
            newCrmData0[rowIndex0][CRM_STATUS0] = source0[UPDATE_CONVERSATION_LABEL0];
            newCrmData0[rowIndex0][CRM_WEBSITE0] = source0[UPDATE_FUND_WEBSITE0];
            newCrmData0[rowIndex0][CRM_LAST_CONTACT_DATE0] = source0[UPDATE_TIMESTAMP0];
            String oneLiner0 = extractOneLinerFromInteractionRecords(
                source0[UPDATE_INTERACTION_RECORD_JSON0],
                source0[UPDATE_CONVERSATION_SUMMARY0]
            );

            newCrmData0[rowIndex0][CRM_INTERACTION_HISTORY0] = prependInteractionHistory(
                "",
                source0[UPDATE_TIMESTAMP0],
                oneLiner0
            );

            newCrmData0[rowIndex0][CRM_INTERACTION_RECORDS0] = appendInteractionRecords(
                "",
                source0[UPDATE_INTERACTION_RECORD_JSON0]
            );
        }

        for (int fieldIndex0 = 0; fieldIndex0 < CRM_FIELD_COUNT0; fieldIndex0++)
        {
            SheetsApp.updateRangeMatrix(
                spreadsheetId0,
                crmTabName0,
                startRow0,
                crmCols0[fieldIndex0],
                extractColumn(newCrmData0, fieldIndex0)
            );
        }
    }

    private static void markIntakeRowsUpdatedInBatch(
        String spreadsheetId0,
        String intakeTabName0,
        int intakeUpdatedCrmCol0,
        ArrayList<Integer> intakeRowsToMarkUpdated0) throws Exception
    {
        int minRow0 = findMinInt(intakeRowsToMarkUpdated0);
        int maxRow0 = findMaxInt(intakeRowsToMarkUpdated0);

        if (minRow0 == -1 || maxRow0 == -1)
        {
            return;
        }

        String[][] existingUpdatedColumn0 = SheetsApp.readRangeMatrix(
            spreadsheetId0,
            intakeTabName0,
            minRow0,
            intakeUpdatedCrmCol0,
            maxRow0,
            intakeUpdatedCrmCol0
        );

        for (int i = 0; i < intakeRowsToMarkUpdated0.size(); i++)
        {
            int rowNumber0 = intakeRowsToMarkUpdated0.get(i);
            int localRowIndex0 = rowNumber0 - minRow0;

            existingUpdatedColumn0[localRowIndex0][0] = TRUE_STATUS0;
        }

        SheetsApp.updateRangeMatrix(
            spreadsheetId0,
            intakeTabName0,
            minRow0,
            intakeUpdatedCrmCol0,
            existingUpdatedColumn0
        );
    }

    private static int findMatchingQueuedNewRow(
        ArrayList<String[]> NewRowsList,
        String[] incomingUpdate0)
    {
        for (int i = 0; i < NewRowsList.size(); i++)
        {
            String[] queued0 = NewRowsList.get(i);

            if (matchesUpdateRows(queued0, incomingUpdate0))
            {
                return i;
            }
        }

        return -1;
    }

    private static int findMatchingExistingUpdate(
        HashMap<Integer, String[]> ExistingUpdateList,
        String[] incomingUpdate0)
    {
        for (Map.Entry<Integer, String[]> entry0 : ExistingUpdateList.entrySet())
        {
            if (matchesUpdateRows(entry0.getValue(), incomingUpdate0))
            {
                return entry0.getKey();
            }
        }

        return -1;
    }

    private static boolean matchesUpdateRows(
        String[] existing0,
        String[] incoming0)
    {
        if (!isBlank(incoming0[UPDATE_EMAIL0]) &&
            sameEmail(incoming0[UPDATE_EMAIL0], existing0[UPDATE_EMAIL0]))
        {
            return true;
        }

        if (!isBlank(incoming0[UPDATE_FUND_NAME0]) &&
            normalizeText(incoming0[UPDATE_FUND_NAME0]).equals(normalizeText(existing0[UPDATE_FUND_NAME0])))
        {
            return true;
        }

        if (isSamePerson(
            incoming0[UPDATE_FIRST_NAME0],
            incoming0[UPDATE_LAST_NAME0],
            existing0[UPDATE_FIRST_NAME0],
            existing0[UPDATE_LAST_NAME0]))
        {
            if (!isBlank(incoming0[UPDATE_FUND_NAME0]) &&
                !isBlank(existing0[UPDATE_FUND_NAME0]) &&
                !normalizeText(incoming0[UPDATE_FUND_NAME0]).equals(normalizeText(existing0[UPDATE_FUND_NAME0])))
            {
                return false;
            }

            return true;
        }

        return false;
    }

    private static int findMatchingCrmRow(
        String[][] crmData0,
        int[] crmCols0,
        String[] incomingUpdate0,
        int dataStartRow0)
    {
        int nameMatchRow0 = -1;

        for (int rowNumber0 = dataStartRow0; rowNumber0 <= crmData0.length; rowNumber0++)
        {
            String contact1Email0 = getCellValue(crmData0, rowNumber0, crmCols0[CRM_CONTACT_1_EMAIL0]);
            String contact2Email0 = getCellValue(crmData0, rowNumber0, crmCols0[CRM_CONTACT_2_EMAIL0]);

            if (!isBlank(incomingUpdate0[UPDATE_EMAIL0]) &&
                (sameEmail(incomingUpdate0[UPDATE_EMAIL0], contact1Email0) ||
                 sameEmail(incomingUpdate0[UPDATE_EMAIL0], contact2Email0)))
            {
                return rowNumber0;
            }
        }

        if (!isBlank(incomingUpdate0[UPDATE_FUND_NAME0]))
        {
            for (int rowNumber0 = dataStartRow0; rowNumber0 <= crmData0.length; rowNumber0++)
            {
                String sheetFundName0 = getCellValue(crmData0, rowNumber0, crmCols0[CRM_FUND_NAME0]);

                if (!isBlank(sheetFundName0) &&
                    normalizeText(sheetFundName0).equals(normalizeText(incomingUpdate0[UPDATE_FUND_NAME0])))
                {
                    return rowNumber0;
                }
            }
        }

        for (int rowNumber0 = dataStartRow0; rowNumber0 <= crmData0.length; rowNumber0++)
        {
            String c1First0 = getCellValue(crmData0, rowNumber0, crmCols0[CRM_CONTACT_1_FIRST_NAME0]);
            String c1Last0 = getCellValue(crmData0, rowNumber0, crmCols0[CRM_CONTACT_1_LAST_NAME0]);
            String c2First0 = getCellValue(crmData0, rowNumber0, crmCols0[CRM_CONTACT_2_FIRST_NAME0]);
            String c2Last0 = getCellValue(crmData0, rowNumber0, crmCols0[CRM_CONTACT_2_LAST_NAME0]);

            if (isSamePerson(incomingUpdate0[UPDATE_FIRST_NAME0], incomingUpdate0[UPDATE_LAST_NAME0], c1First0, c1Last0) ||
                isSamePerson(incomingUpdate0[UPDATE_FIRST_NAME0], incomingUpdate0[UPDATE_LAST_NAME0], c2First0, c2Last0))
            {
                String sheetFundName0 = getCellValue(crmData0, rowNumber0, crmCols0[CRM_FUND_NAME0]);

                if (!isBlank(incomingUpdate0[UPDATE_FUND_NAME0]) &&
                    !isBlank(sheetFundName0) &&
                    !normalizeText(incomingUpdate0[UPDATE_FUND_NAME0]).equals(normalizeText(sheetFundName0)))
                {
                    continue;
                }

                nameMatchRow0 = rowNumber0;
                break;
            }
        }

        return nameMatchRow0;
    }

    private static int findNextCrmAppendRow(
        String spreadsheetId0,
        String crmTabName0,
        int crmEmailCol0,
        int crmFundNameCol0,
        int dataStartRow0) throws Exception
    {
        int firstCol0 = Math.min(crmEmailCol0, crmFundNameCol0);
        int secondCol0 = Math.max(crmEmailCol0, crmFundNameCol0);

        int lastRow0 = SheetsApp.findLastRow(
            spreadsheetId0,
            crmTabName0,
            firstCol0,
            secondCol0,
            MAX_CRM_ROWS0
        );

        if (lastRow0 < dataStartRow0)
        {
            return dataStartRow0;
        }

        return lastRow0 + 1;
    }

    private static boolean hasAnyIdentifier(String[] update0)
    {
        if (update0 == null)
        {
            return false;
        }

        return !isBlank(update0[UPDATE_EMAIL0]) ||
            !isBlank(update0[UPDATE_FUND_NAME0]) ||
            (!isBlank(update0[UPDATE_FIRST_NAME0]) && !isBlank(update0[UPDATE_LAST_NAME0]));
    }

    private static boolean setIfBlank(
        String[] row0,
        int index0,
        String value0)
    {
        if (isBlank(row0[index0]) && !isBlank(value0))
        {
            row0[index0] = value0;
            return true;
        }

        return false;
    }

    private static String prependInteractionHistory(
        String currentHistory0,
        String timestamp0,
        String conversationSummary0)
    {
        if (isBlank(conversationSummary0))
        {
            return currentHistory0 == null ? "" : currentHistory0;
        }

        String date0 = normalizeDisplayDate(timestamp0);
        String newEntry0 = date0 + ": " + conversationSummary0.trim();

        ArrayList<String> entries0 = new ArrayList<String>();
        entries0.add(newEntry0);

        if (!isBlank(currentHistory0))
        {
            String[] existingLines0 = currentHistory0.split("\\r?\\n");

            for (int i = 0; i < existingLines0.length; i++)
            {
                String line0 = existingLines0[i] == null ? "" : existingLines0[i].trim();

                if (isBlank(line0))
                {
                    continue;
                }

                if (line0.equalsIgnoreCase(newEntry0))
                {
                    continue;
                }

                entries0.add(line0);

                if (entries0.size() >= MaxInteractionHistory)
                {
                    break;
                }
            }
        }

        return joinLines(entries0);
    }

    private static String mergeSummaries(
        String currentSummary0,
        String incomingSummary0)
    {
        if (isBlank(incomingSummary0))
        {
            return currentSummary0 == null ? "" : currentSummary0;
        }

        if (isBlank(currentSummary0))
        {
            return incomingSummary0;
        }

        if (currentSummary0.toLowerCase().contains(incomingSummary0.toLowerCase()))
        {
            return currentSummary0;
        }

        return incomingSummary0 + " | " + currentSummary0;
    }

    private static String joinLines(ArrayList<String> lines0)
    {
        String result0 = "";

        for (int i = 0; i < lines0.size(); i++)
        {
            if (i > 0)
            {
                result0 += "\n";
            }

            result0 += lines0.get(i);
        }

        return result0;
    }

    private static String normalizeDisplayDate(String timestamp0)
    {
        LocalDateTime parsed0 = parseTimestamp(timestamp0);

        if (parsed0 != null)
        {
            return parsed0.toLocalDate().toString();
        }

        if (!isBlank(timestamp0))
        {
            return timestamp0.trim();
        }

        return LocalDate.now().toString();
    }

    private static boolean shouldUpdateStatus(String currentStatus0, String newStatus0)
    {
        if (isBlank(newStatus0))
        {
            return false;
        }

        if (newStatus0.equals("Rejected"))
        {
            return true;
        }

        int currentRank0 = getStatusRank(currentStatus0);
        int newRank0 = getStatusRank(newStatus0);

        return newRank0 > currentRank0;
    }

    private static int getStatusRank(String status0)
    {
        if (status0 == null)
        {
            return 0;
        }

        if (status0.equals("Reached Out")) return 1;
        if (status0.equals("First Interest")) return 2;
        if (status0.equals("Meetings")) return 3;
        if (status0.equals("Prospective Close")) return 4;
        if (status0.equals("Rejected")) return 5;

        return 0;
    }

    private static boolean isTimestampAfter(
        String timestampA0,
        String timestampB0)
    {
        LocalDateTime a0 = parseTimestamp(timestampA0);
        LocalDateTime b0 = parseTimestamp(timestampB0);

        if (a0 == null)
        {
            return false;
        }

        if (b0 == null)
        {
            return true;
        }

        return a0.isAfter(b0);
    }

    private static LocalDateTime parseTimestamp(String timestamp0)
    {
        if (isBlank(timestamp0))
        {
            return null;
        }

        String cleaned0 = timestamp0.trim();

        DateTimeFormatter[] dateTimeFormatters0 = new DateTimeFormatter[]
        {
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"),
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"),
            DateTimeFormatter.ofPattern("M/d/yyyy H:mm:ss"),
            DateTimeFormatter.ofPattern("M/d/yyyy H:mm"),
            DateTimeFormatter.ISO_LOCAL_DATE_TIME
        };

        for (int i = 0; i < dateTimeFormatters0.length; i++)
        {
            try
            {
                return LocalDateTime.parse(cleaned0, dateTimeFormatters0[i]);
            }
            catch (Exception exception0)
            {
            }
        }

        DateTimeFormatter[] dateFormatters0 = new DateTimeFormatter[]
        {
            DateTimeFormatter.ISO_LOCAL_DATE,
            DateTimeFormatter.ofPattern("M/d/yyyy"),
            DateTimeFormatter.ofPattern("MM/dd/yyyy")
        };

        for (int i = 0; i < dateFormatters0.length; i++)
        {
            try
            {
                return LocalDate.parse(cleaned0, dateFormatters0[i]).atStartOfDay();
            }
            catch (Exception exception0)
            {
            }
        }

        return null;
    }

    private static boolean isSamePerson(
        String firstA0,
        String lastA0,
        String firstB0,
        String lastB0)
    {
        return !isBlank(firstA0) &&
            !isBlank(lastA0) &&
            !isBlank(firstB0) &&
            !isBlank(lastB0) &&
            normalizeText(firstA0).equals(normalizeText(firstB0)) &&
            normalizeText(lastA0).equals(normalizeText(lastB0));
    }

    private static boolean sameEmail(String emailA0, String emailB0)
    {
        return !isBlank(emailA0) &&
            !isBlank(emailB0) &&
            cleanEmail(emailA0).equalsIgnoreCase(cleanEmail(emailB0));
    }

    private static String cleanEmail(String value0)
    {
        if (isBlank(value0))
        {
            return "";
        }

        String trimmed0 = value0.trim();
        int start0 = trimmed0.indexOf("<");
        int end0 = trimmed0.indexOf(">");

        if (start0 != -1 && end0 != -1 && end0 > start0)
        {
            trimmed0 = trimmed0.substring(start0 + 1, end0);
        }

        return trimmed0.trim().toLowerCase();
    }

    private static String cleanWebsite(String value0)
    {
        if (isBlank(value0))
        {
            return "";
        }

        String cleaned0 = value0.trim();
        cleaned0 = cleaned0.replaceAll("[,.;)\\]]+$", "");

        if (cleaned0.startsWith("http://"))
        {
            cleaned0 = cleaned0.substring(7);
        }

        if (cleaned0.startsWith("https://"))
        {
            cleaned0 = cleaned0.substring(8);
        }

        if (cleaned0.startsWith("www."))
        {
            cleaned0 = cleaned0.substring(4);
        }

        return cleaned0.toLowerCase();
    }

    private static String normalizeText(String value0)
    {
        if (value0 == null)
        {
            return "";
        }

        return value0.trim().toLowerCase().replaceAll("\\s+", " ");
    }

    private static boolean safeEquals(String a0, String b0)
    {
        if (a0 == null)
        {
            a0 = "";
        }

        if (b0 == null)
        {
            b0 = "";
        }

        return a0.equals(b0);
    }

    private static String[] copyUpdateRow(String[] source0)
    {
        String[] copy0 = new String[UPDATE_FIELD_COUNT0];

        for (int i = 0; i < UPDATE_FIELD_COUNT0; i++)
        {
            copy0[i] = source0[i] == null ? "" : source0[i];
        }

        return copy0;
    }

    private static String[][] extractColumn(
        String[][] data0,
        int sourceColumnIndex0)
    {
        String[][] column0 = new String[data0.length][1];

        for (int rowIndex0 = 0; rowIndex0 < data0.length; rowIndex0++)
        {
            column0[rowIndex0][0] = data0[rowIndex0][sourceColumnIndex0] == null
                ? ""
                : data0[rowIndex0][sourceColumnIndex0];
        }

        return column0;
    }

    private static int findMinKey(HashMap<Integer, String[]> map0)
    {
        if (map0 == null || map0.size() == 0)
        {
            return -1;
        }

        int min0 = Integer.MAX_VALUE;

        for (Integer key0 : map0.keySet())
        {
            if (key0 != null && key0 < min0)
            {
                min0 = key0;
            }
        }

        return min0 == Integer.MAX_VALUE ? -1 : min0;
    }

    private static int findMaxKey(HashMap<Integer, String[]> map0)
    {
        if (map0 == null || map0.size() == 0)
        {
            return -1;
        }

        int max0 = -1;

        for (Integer key0 : map0.keySet())
        {
            if (key0 != null && key0 > max0)
            {
                max0 = key0;
            }
        }

        return max0;
    }

    private static int findMinInt(ArrayList<Integer> list0)
    {
        if (list0 == null || list0.size() == 0)
        {
            return -1;
        }

        int min0 = Integer.MAX_VALUE;

        for (int i = 0; i < list0.size(); i++)
        {
            Integer value0 = list0.get(i);

            if (value0 != null && value0 < min0)
            {
                min0 = value0;
            }
        }

        return min0 == Integer.MAX_VALUE ? -1 : min0;
    }

    private static int findMaxInt(ArrayList<Integer> list0)
    {
        if (list0 == null || list0.size() == 0)
        {
            return -1;
        }

        int max0 = -1;

        for (int i = 0; i < list0.size(); i++)
        {
            Integer value0 = list0.get(i);

            if (value0 != null && value0 > max0)
            {
                max0 = value0;
            }
        }

        return max0;
    }

    private static String getLocalCellValue(
        String[][] data0,
        int rowIndex0,
        int columnIndex0)
    {
        if (data0 == null ||
            rowIndex0 < 0 ||
            rowIndex0 >= data0.length ||
            columnIndex0 < 0 ||
            columnIndex0 >= data0[rowIndex0].length ||
            data0[rowIndex0][columnIndex0] == null)
        {
            return "";
        }

        return data0[rowIndex0][columnIndex0].trim();
    }

    private static String getCellValue(
        String[][] data0,
        int rowNumber0,
        int oneBasedColumn0)
    {
        int rowIndex0 = rowNumber0 - 1;
        int columnIndex0 = oneBasedColumn0 - 1;

        if (rowIndex0 < 0 || rowIndex0 >= data0.length)
        {
            return "";
        }

        if (columnIndex0 < 0 || columnIndex0 >= data0[rowIndex0].length)
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
        return value0 == null || value0.trim().length() == 0;
    }

    private static int getMaxColumn(int[] columns0)
    {
        int max0 = -1;

        for (int i = 0; i < columns0.length; i++)
        {
            if (columns0[i] > max0)
            {
                max0 = columns0[i];
            }
        }

        return max0;
    }

    private static String extractOneLinerFromInteractionRecords(
        String incomingRecordsJson0,
        String fallbackSummary0)
    {
        if (!isBlank(incomingRecordsJson0) && !incomingRecordsJson0.equals("[]"))
        {
            try
            {
                JSONArray arr0 = InteractionRecord.extractRecordsArray(incomingRecordsJson0);

                if (arr0.length() > 0)
                {
                    String summary0 = arr0.getJSONObject(0).optString("oneSentenceSummary", "");

                    if (!isBlank(summary0))
                    {
                        return summary0;
                    }
                }
            }
            catch (Exception e0)
            {
                // fall through to fallback
            }
        }

        return fallbackSummary0 == null ? "" : fallbackSummary0;
    }

    // Merges the incoming interaction record(s) into the stored "Full Interaction
    // Record" wrapper: { asOfDate, records:[...] }. Tolerates a legacy bare-array
    // cell (auto-migrated on read) and refreshes asOfDate to today on every append.
    private static String appendInteractionRecords(
        String existingRecordsJson0,
        String incomingRecordsJson0)
    {
        JSONArray records0 = InteractionRecord.extractRecordsArray(existingRecordsJson0);

        // Seed a content-signature set from the records already in the cell so an
        // incoming record that is byte-for-byte identical (same date, summary,
        // topics, etc.) is not appended a second time. This keeps re-runs
        // idempotent even when the intake row's UpdatedCrm flag is reset to FALSE.
        Set<String> seenSignatures0 = new HashSet<>();

        for (int i = 0; i < records0.length(); i++)
        {
            JSONObject existing0 = records0.optJSONObject(i);

            if (existing0 != null)
            {
                seenSignatures0.add(recordSignature(existing0));
            }
        }

        if (!isBlank(incomingRecordsJson0) && !incomingRecordsJson0.equals("[]"))
        {
            JSONArray incomingArray0 = InteractionRecord.extractRecordsArray(incomingRecordsJson0);

            for (int i = 0; i < incomingArray0.length(); i++)
            {
                JSONObject obj0 = incomingArray0.optJSONObject(i);

                if (obj0 != null && seenSignatures0.add(recordSignature(obj0)))
                {
                    records0.put(obj0);
                }
            }
        }

        records0 = sortRecordsByDate(records0);

        String asOfDate0 = LocalDate.now().toString();
        String result0 = InteractionRecord.buildWrapper(records0, asOfDate0).toString();

        // Keep the cell valid JSON and under the size cap by dropping the oldest
        // records (index 0 after the ascending date sort) rather than truncating
        // the serialized string mid-character.
        while (result0.length() > MAX_INTERACTION_RECORDS_CELL_LENGTH0 && records0.length() > 0)
        {
            records0.remove(0);
            result0 = InteractionRecord.buildWrapper(records0, asOfDate0).toString();
        }

        return result0;
    }

    // Builds a stable content signature for an interaction record covering every
    // field, so two records are treated as duplicates only when they are exactly
    // the same (same date, direction, type, summary, label, lists and sentiment).
    // Parsing through InteractionRecord gives a fixed field order independent of
    // the JSON key ordering in the stored cell.
    private static String recordSignature(JSONObject record0)
    {
        InteractionRecord r0 = InteractionRecord.fromJSON(record0);

        // Field separator and list-item separator are control characters that
        // cannot appear in normal text, so distinct field layouts never collide.
        String fieldSep0 = "\u0001";
        String listSep0 = "\u0002";

        StringBuilder sb0 = new StringBuilder();
        sb0.append(r0.date).append(fieldSep0);
        sb0.append(r0.direction).append(fieldSep0);
        sb0.append(r0.type).append(fieldSep0);
        sb0.append(r0.oneSentenceSummary).append(fieldSep0);
        sb0.append(r0.conversationLabel).append(fieldSep0);
        sb0.append(String.join(listSep0, r0.keyTopicsDiscussed)).append(fieldSep0);
        sb0.append(String.join(listSep0, r0.lpQuestionsAsked)).append(fieldSep0);
        sb0.append(String.join(listSep0, r0.commitmentsMadeByGP)).append(fieldSep0);
        sb0.append(r0.lpSentiment).append(fieldSep0);
        sb0.append(String.join(listSep0, r0.relationshipSignals));

        return sb0.toString();
    }

    // Returns a new array sorted ascending by the record's interaction "date" (YYYY-MM-DD).
    // Records with a blank/unparseable date sort last; equal dates keep arrival order (stable).
    private static JSONArray sortRecordsByDate(JSONArray records0)
    {
        ArrayList<JSONObject> list0 = new ArrayList<>();

        for (int i = 0; i < records0.length(); i++)
        {
            JSONObject obj0 = records0.optJSONObject(i);

            if (obj0 != null)
            {
                list0.add(obj0);
            }
        }

        list0.sort(Comparator.comparing(
            obj0 -> obj0.optString("date", ""),
            CrmUpdater::compareDatesBlankLast
        ));

        JSONArray sorted0 = new JSONArray();

        for (JSONObject obj0 : list0)
        {
            sorted0.put(obj0);
        }

        return sorted0;
    }

    private static int compareDatesBlankLast(String a0, String b0)
    {
        boolean aBlank0 = isBlank(a0);
        boolean bBlank0 = isBlank(b0);

        if (aBlank0 && bBlank0)
        {
            return 0;
        }

        if (aBlank0)
        {
            return 1;
        }

        if (bBlank0)
        {
            return -1;
        }

        return a0.compareTo(b0);
    }

    private static String mergeJsonArrayStrings(
        String baseJson0,
        String incomingJson0)
    {
        JSONArray merged0 = new JSONArray();

        if (!isBlank(baseJson0))
        {
            try
            {
                JSONArray base0 = new JSONArray(baseJson0);
                for (int i = 0; i < base0.length(); i++)
                {
                    merged0.put(base0.getJSONObject(i));
                }
            }
            catch (Exception e0)
            {
                // skip malformed
            }
        }

        if (!isBlank(incomingJson0))
        {
            try
            {
                JSONArray incoming0 = new JSONArray(incomingJson0);
                for (int i = 0; i < incoming0.length(); i++)
                {
                    merged0.put(incoming0.getJSONObject(i));
                }
            }
            catch (Exception e0)
            {
                // skip malformed
            }
        }

        return merged0.toString();
    }

    private static class ExistingUpdateResult
    {
        public int changedCount = 0;
        public int notNeededCount = 0;
    }
}
