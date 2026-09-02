package com.liminer.intake;

import com.liminer.core.CRMField;
import com.liminer.core.CRMFieldRegistry;
import com.liminer.core.CRMRegistry;
import com.liminer.core.InteractionRecord;
import com.liminer.core.SessionContext;
import com.liminer.llm.OpenAIClient;
import com.liminer.sheets.SheetsApp;

import org.json.JSONArray;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.HashMap;
import java.time.LocalDate;

public class EmailIntakeProcessor
{
    private static final int MAX_ROWS0 = 1000;
    private static final int MAX_COLUMNS0 = 100;
    private static final int MAX_ROWS_PER_OPENAI_BATCH0 = 20;

    private static final String PROCESSED_STATUS0 = "PROCESSED";

    public static String processUnprocessedIntakeRows(SessionContext context0) throws Exception
    {
        if (context0 == null || context0.user == null || context0.config == null)
        {
            return "ERROR: Missing session context.";
        }

        String spreadsheetId0 = context0.config.spreadsheetId;
        String intakeTabName0 = context0.config.intakeTabName;

        String[] readHeaders0 = buildReadHeaders(context0);
        String[] updateHeaders0 = buildUpdateHeaders(context0);

        HashMap<String, Integer> sheetHeaderMap0 = SheetsApp.buildHeaderMap(
            spreadsheetId0,
            intakeTabName0,
            context0.config.intakeTabHeaderRow,
            MAX_COLUMNS0
        );

        HashMap<String, Integer> neededHeaderMap0 = buildNeededHeaderMap(
            sheetHeaderMap0,
            readHeaders0,
            updateHeaders0
        );

        int maxColumn0 = getMaxColumn(neededHeaderMap0);

        String[][] sheetData0 = SheetsApp.readRangeMatrix(
            spreadsheetId0,
            intakeTabName0,
            1,
            1,
            MAX_ROWS0,
            maxColumn0
        );

        int lastRow0 = SheetsApp.findLastRow(sheetData0);

        if (lastRow0 < context0.config.intakeTabDataStartRow)
        {
            return "No intake data found.";
        }

        int processingStatusCol0 = getSheetColumn(
            neededHeaderMap0,
            context0.config.getCol("intakeTabProcessingStatusCol")
        );

        int firstRow0 = findFirstUnprocessedRow(
            sheetData0,
            context0.config.intakeTabDataStartRow,
            lastRow0,
            processingStatusCol0
        );

        System.out.println("lastRow0 = " + lastRow0);
        System.out.println("firstRow0 = " + firstRow0);

        if (firstRow0 == -1)
        {
            return "No unprocessed intake rows found.";
        }

        JSONArray rowsToProcess0 = buildRowsToProcess(
            sheetData0,
            context0,
            neededHeaderMap0,
            firstRow0,
            lastRow0
        );

        System.out.println("Found " + rowsToProcess0.length() + " rows to process.");

        if (rowsToProcess0.length() == 0)
        {
            return "No unprocessed intake rows found.";
        }

        System.out.println("Sending data to OpenAI in batches (" + rowsToProcess0.length() + " rows)...");

        JSONArray extractedRows0 = processRowsInOpenAIBatches(
            rowsToProcess0,
            context0
        );

        if (extractedRows0.length() != rowsToProcess0.length())
        {
            return "ERROR: OpenAI returned the wrong number of rows. No sheet updates were made.";
        }

        String[][] processedInfo0 = buildInitialProcessedInfo(
            sheetData0,
            neededHeaderMap0,
            updateHeaders0,
            firstRow0,
            lastRow0
        );

        int successCount0 = 0;
        int errorCount0 = 0;

        for (int resultIndex0 = 0; resultIndex0 < extractedRows0.length(); resultIndex0++)
        {
            try
            {
                JSONObject resultObject0 = extractedRows0.getJSONObject(resultIndex0);

                int rowNumber0 = resultObject0.getInt("rowNumber");
                int processedInfoRowIndex0 = rowNumber0 - firstRow0;

                String cleanedEmail0 = cleanEmail(resultObject0.optString("cleanedEmail", ""));
                String firstName0 = resultObject0.optString("firstName", "");
                String lastName0 = resultObject0.optString("lastName", "");
                String fundName0 = resultObject0.optString("fundName", "");
                String fundWebsite0 = cleanWebsite(resultObject0.optString("fundWebsite", ""));
                String conversationLabel0 = resultObject0.optString("conversationLabel", "Reached Out");
                String oneSentenceSummary0 = resultObject0.optString("oneSentenceSummary", "");
                String interactionRecordJson0 = resultObject0.optString("interactionRecordJson", "");

                if (isBlank(firstName0))
                {
                    firstName0 = "Unknown";
                }

                if (!isAllowedConversationLabel(conversationLabel0))
                {
                    conversationLabel0 = "Reached Out";
                }

                String needsReview0 = "FALSE";

                if (isBlank(cleanedEmail0) ||
                    !isValidEmail(cleanedEmail0) ||
                    isInternalEmail(cleanedEmail0, context0.user.internalEmails))
                {
                    needsReview0 = "TRUE";
                }

                if (isInternalName(firstName0, lastName0, context0.user.internalNames))
                {
                    needsReview0 = "TRUE";
                    cleanedEmail0 = "";
                    firstName0 = "Unknown";
                    lastName0 = "";
                }

                if (isInternalFund(fundName0, context0.user.internalFundName))
                {
                    fundName0 = "";
                }

                if (isInternalWebsite(fundWebsite0, context0.user.internalWebsite))
                {
                    fundWebsite0 = "";
                }

                setProcessedInfoValue(
                    processedInfo0,
                    updateHeaders0,
                    processedInfoRowIndex0,
                    context0.config.getCol("intakeTabProcessingStatusCol"),
                    PROCESSED_STATUS0
                );

                setProcessedInfoValue(
                    processedInfo0,
                    updateHeaders0,
                    processedInfoRowIndex0,
                    context0.config.getCol("intakeTabCleanedEmailCol"),
                    cleanedEmail0
                );

                setProcessedInfoValue(
                    processedInfo0,
                    updateHeaders0,
                    processedInfoRowIndex0,
                    context0.config.getCol("intakeTabExtractedFirstNameCol"),
                    firstName0
                );

                setProcessedInfoValue(
                    processedInfo0,
                    updateHeaders0,
                    processedInfoRowIndex0,
                    context0.config.getCol("intakeTabExtractedLastNameCol"),
                    lastName0
                );

                setProcessedInfoValue(
                    processedInfo0,
                    updateHeaders0,
                    processedInfoRowIndex0,
                    context0.config.getCol("intakeTabExtractedFundNameCol"),
                    fundName0
                );

                setProcessedInfoValue(
                    processedInfo0,
                    updateHeaders0,
                    processedInfoRowIndex0,
                    context0.config.getCol("intakeTabExtractedFundWebsiteCol"),
                    fundWebsite0
                );

                setProcessedInfoValue(
                    processedInfo0,
                    updateHeaders0,
                    processedInfoRowIndex0,
                    context0.config.getCol("intakeTabConversationLabelCol"),
                    conversationLabel0
                );

                setProcessedInfoValue(
                    processedInfo0,
                    updateHeaders0,
                    processedInfoRowIndex0,
                    context0.config.getCol("intakeTabConversationSummaryCol"),
                    oneSentenceSummary0
                );

                setProcessedInfoValue(
                    processedInfo0,
                    updateHeaders0,
                    processedInfoRowIndex0,
                    context0.config.getCol("intakeTabUpdatedCrmCol"),
                    "FALSE"
                );

                setProcessedInfoValue(
                    processedInfo0,
                    updateHeaders0,
                    processedInfoRowIndex0,
                    context0.config.getCol("intakeTabNeedsReviewCol"),
                    needsReview0
                );

                setProcessedInfoValue(
                    processedInfo0,
                    updateHeaders0,
                    processedInfoRowIndex0,
                    context0.config.getCol("intakeTabInteractionRecordCol"),
                    interactionRecordJson0
                );

                successCount0++;
            }
            catch (Exception exception0)
            {
                errorCount0++;
                System.out.println("ERROR processing extracted row index " + resultIndex0);
                System.out.println(exception0.getMessage());
            }
        }

        System.out.println("Processed info array:");
        printMatrix(processedInfo0);

        String[][][] columnsToProcess0 = buildColumnsToProcess(processedInfo0, updateHeaders0);

        System.out.println("Updating sheet by columns...");

        for (int headerIndex0 = 0; headerIndex0 < updateHeaders0.length; headerIndex0++)
        {
            String header0 = updateHeaders0[headerIndex0];

            int sheetColumn0 = getSheetColumn(
                neededHeaderMap0,
                header0
            );

            SheetsApp.updateRangeMatrix(
                spreadsheetId0,
                intakeTabName0,
                firstRow0,
                sheetColumn0,
                columnsToProcess0[headerIndex0]
            );
        }

        return "Processed intake rows with column updates. Success: "
            + successCount0
            + ", Errors: "
            + errorCount0
            + ".";
    }

    public static String processUnprocessedIntakeRows() throws Exception
    {
        // Convenience overload for single-operator CLI runs: the account to
        // operate as comes from the environment, never a compiled-in address.
        String email0 = System.getenv("LIMINER_DEFAULT_USER");

        if (email0 == null || email0.trim().isEmpty())
        {
            return "ERROR: LIMINER_DEFAULT_USER is not set.";
        }

        SessionContext context0 = CRMRegistry.login(email0.trim());

        if (context0 == null)
        {
            return "ERROR: Could not create default session context.";
        }

        return processUnprocessedIntakeRows(context0);
    }

    private static String[] buildReadHeaders(SessionContext context0)
    {
        return new String[]
        {
            context0.config.getCol("intakeTabIntakeIdCol"),
            context0.config.getCol("intakeTabToCol"),
            context0.config.getCol("intakeTabFromCol"),
            context0.config.getCol("intakeTabSubjectCol"),
            context0.config.getCol("intakeTabBodyCol"),
            context0.config.getCol("intakeTabProcessingStatusCol"),
            context0.config.getCol("intakeTabTimestampCol")
        };
    }

    private static String[] buildUpdateHeaders(SessionContext context0)
    {
        ArrayList<CRMField> systemFields0 = new ArrayList<>();
        for (CRMField field0 : CRMFieldRegistry.getIntakeTabFields())
        {
            if (field0.includeInOnboarding)
            {
                systemFields0.add(field0);
            }
        }

        String[] headers0 = new String[systemFields0.size()];
        for (int i0 = 0; i0 < systemFields0.size(); i0++)
        {
            headers0[i0] = context0.config.getCol(systemFields0.get(i0).key);
        }

        return headers0;
    }

    private static HashMap<String, Integer> buildNeededHeaderMap(
        HashMap<String, Integer> sheetHeaderMap0,
        String[] readHeaders0,
        String[] updateHeaders0) throws Exception
    {
        HashMap<String, Integer> neededHeaderMap0 = new HashMap<>();

        addHeadersToNeededHeaderMap(
            neededHeaderMap0,
            sheetHeaderMap0,
            readHeaders0
        );

        addHeadersToNeededHeaderMap(
            neededHeaderMap0,
            sheetHeaderMap0,
            updateHeaders0
        );

        return neededHeaderMap0;
    }

    private static void addHeadersToNeededHeaderMap(
        HashMap<String, Integer> neededHeaderMap0,
        HashMap<String, Integer> sheetHeaderMap0,
        String[] headers0) throws Exception
    {
        for (int i0 = 0; i0 < headers0.length; i0++)
        {
            String header0 = headers0[i0];

            if (isBlank(header0))
            {
                continue;
            }

            int column0 = SheetsApp.findColumnInHeaderMap(
                sheetHeaderMap0,
                header0
            );

            if (column0 == -1)
            {
                throw new Exception("Header not found: " + header0);
            }

            neededHeaderMap0.put(header0.trim(), column0);
        }
    }

    private static JSONArray buildRowsToProcess(
        String[][] sheetData0,
        SessionContext context0,
        HashMap<String, Integer> neededHeaderMap0,
        int firstRow0,
        int lastRow0) throws Exception
    {
        JSONArray rowsToProcess0 = new JSONArray();

        int intakeIdCol0 = getOptionalSheetColumn(
            neededHeaderMap0,
            context0.config.getCol("intakeTabIntakeIdCol")
        );

        int toCol0 = getSheetColumn(
            neededHeaderMap0,
            context0.config.getCol("intakeTabToCol")
        );

        int fromCol0 = getSheetColumn(
            neededHeaderMap0,
            context0.config.getCol("intakeTabFromCol")
        );

        int subjectCol0 = getSheetColumn(
            neededHeaderMap0,
            context0.config.getCol("intakeTabSubjectCol")
        );

        int bodyCol0 = getSheetColumn(
            neededHeaderMap0,
            context0.config.getCol("intakeTabBodyCol")
        );

        int processingStatusCol0 = getSheetColumn(
            neededHeaderMap0,
            context0.config.getCol("intakeTabProcessingStatusCol")
        );

        int timestampCol0 = getOptionalSheetColumn(
            neededHeaderMap0,
            context0.config.getCol("intakeTabTimestampCol")
        );

        System.out.println("Scanning for unprocessed rows from row " + firstRow0 + " to row " + lastRow0 + "...");

        for (int rowNumber0 = firstRow0; rowNumber0 <= lastRow0; rowNumber0++)
        {
            String intakeId0 = "";

            if (intakeIdCol0 != -1)
            {
                intakeId0 = getCellValue(sheetData0, rowNumber0, intakeIdCol0);
            }

            String to0 = getCellValue(sheetData0, rowNumber0, toCol0);
            String from0 = getCellValue(sheetData0, rowNumber0, fromCol0);
            String subject0 = getCellValue(sheetData0, rowNumber0, subjectCol0);
            String body0 = getCellValue(sheetData0, rowNumber0, bodyCol0);
            String processingStatus0 = getCellValue(sheetData0, rowNumber0, processingStatusCol0);

            String timestamp0 = "";

            if (timestampCol0 != -1)
            {
                timestamp0 = getCellValue(sheetData0, rowNumber0, timestampCol0);
            }

            if (isBlank(intakeId0) &&
                isBlank(to0) &&
                isBlank(from0) &&
                isBlank(subject0) &&
                isBlank(body0))
            {
                continue;
            }

            if (processingStatus0.equalsIgnoreCase(PROCESSED_STATUS0))
            {
                continue;
            }

            String crmCandidateEmail0 = chooseCrmCandidateEmail(
                to0,
                from0,
                context0.user.internalEmails
            );

            JSONObject rowObject0 = new JSONObject();
            rowObject0.put("rowNumber", rowNumber0);
            rowObject0.put("crmCandidateEmail", crmCandidateEmail0);
            rowObject0.put("to", to0);
            rowObject0.put("from", from0);
            rowObject0.put("subject", subject0);
            rowObject0.put("body", body0);
            rowObject0.put("timestamp", timestamp0);

            rowsToProcess0.put(rowObject0);
        }

        return rowsToProcess0;
    }

    private static JSONArray processRowsInOpenAIBatches(
        JSONArray rowsToProcess0,
        SessionContext context0) throws Exception
    {
        JSONArray allExtractedRows0 = new JSONArray();

        for (int startIndex0 = 0;
            startIndex0 < rowsToProcess0.length();
            startIndex0 += MAX_ROWS_PER_OPENAI_BATCH0)
        {
            int endIndexExclusive0 = Math.min(
                startIndex0 + MAX_ROWS_PER_OPENAI_BATCH0,
                rowsToProcess0.length()
            );

            JSONArray batchRows0 = new JSONArray();

            for (int i = startIndex0; i < endIndexExclusive0; i++)
            {
                batchRows0.put(rowsToProcess0.getJSONObject(i));
            }

            System.out.println(
                "Sending OpenAI batch rows "
                + (startIndex0 + 1)
                + "-"
                + endIndexExclusive0
                + " of "
                + rowsToProcess0.length()
                + "..."
            );

            String prompt0 = buildExtractionPrompt(
                batchRows0,
                context0.user.internalNames,
                context0.user.internalEmails,
                context0.user.internalFundName,
                context0.user.internalWebsite
            );

            String aiOutput0 = OpenAIClient.getTextResponse(prompt0);

            JSONArray extractedBatch0 = parseJsonArrayFromText(aiOutput0);

            if (extractedBatch0.length() != batchRows0.length())
            {
                throw new Exception(
                    "OpenAI returned "
                    + extractedBatch0.length()
                    + " rows, expected "
                    + batchRows0.length()
                    + " rows for batch starting at index "
                    + startIndex0
                );
            }

            String today0 = LocalDate.now().toString();

            for (int j = 0; j < extractedBatch0.length(); j++)
            {
                JSONObject row0 = extractedBatch0.getJSONObject(j);

                String timestamp0 = batchRows0.getJSONObject(j).optString("timestamp", "");

                InteractionRecord record0 = InteractionRecord.fromJSON(row0);
                record0.date = normalizeToIsoDate(timestamp0, today0);
                record0.type = "EMAIL";
                record0.conversationLabel = row0.optString("conversationLabel", "");

                row0.put("interactionRecordJson", record0.toJSON().toString());
                allExtractedRows0.put(row0);
            }
        }

        return allExtractedRows0;
    }

    private static String[][] buildInitialProcessedInfo(
        String[][] sheetData0,
        HashMap<String, Integer> neededHeaderMap0,
        String[] updateHeaders0,
        int firstRow0,
        int lastRow0) throws Exception
    {
        String[][] processedInfo0 = new String[lastRow0 - firstRow0 + 1][updateHeaders0.length];

        for (int rowNumber0 = firstRow0; rowNumber0 <= lastRow0; rowNumber0++)
        {
            int processedInfoRowIndex0 = rowNumber0 - firstRow0;

            for (int headerIndex0 = 0; headerIndex0 < updateHeaders0.length; headerIndex0++)
            {
                String header0 = updateHeaders0[headerIndex0];

                int sheetColumn0 = getSheetColumn(
                    neededHeaderMap0,
                    header0
                );

                processedInfo0[processedInfoRowIndex0][headerIndex0] = getCellValue(
                    sheetData0,
                    rowNumber0,
                    sheetColumn0
                );
            }
        }

        return processedInfo0;
    }

    private static String[][][] buildColumnsToProcess(
        String[][] processedInfo0,
        String[] updateHeaders0)
    {
        String[][][] columnsToProcess0 = new String[updateHeaders0.length][processedInfo0.length][1];

        for (int headerIndex0 = 0; headerIndex0 < updateHeaders0.length; headerIndex0++)
        {
            for (int rowIndex0 = 0; rowIndex0 < processedInfo0.length; rowIndex0++)
            {
                columnsToProcess0[headerIndex0][rowIndex0][0] = processedInfo0[rowIndex0][headerIndex0];
            }
        }

        return columnsToProcess0;
    }

    private static void setProcessedInfoValue(
        String[][] processedInfo0,
        String[] updateHeaders0,
        int rowIndex0,
        String header0,
        String value0) throws Exception
    {
        int headerIndex0 = getHeaderIndex(
            updateHeaders0,
            header0
        );

        if (headerIndex0 == -1)
        {
            throw new Exception("Header not found in update header list: " + header0);
        }

        processedInfo0[rowIndex0][headerIndex0] = value0 == null ? "" : value0;
    }

    private static int getHeaderIndex(
        String[] headers0,
        String header0)
    {
        if (headers0 == null || header0 == null)
        {
            return -1;
        }

        for (int i0 = 0; i0 < headers0.length; i0++)
        {
            if (headers0[i0] != null && headers0[i0].trim().equals(header0.trim()))
            {
                return i0;
            }
        }

        return -1;
    }

    private static int getSheetColumn(
        HashMap<String, Integer> headerMap0,
        String header0) throws Exception
    {
        if (headerMap0 == null || isBlank(header0))
        {
            throw new Exception("Cannot find sheet column for blank header.");
        }

        Integer column0 = headerMap0.get(header0.trim());

        if (column0 == null)
        {
            throw new Exception("Header missing from neededHeaderMap0: " + header0);
        }

        return column0;
    }

    private static int getOptionalSheetColumn(
        HashMap<String, Integer> headerMap0,
        String header0)
    {
        if (headerMap0 == null || isBlank(header0))
        {
            return -1;
        }

        Integer column0 = headerMap0.get(header0.trim());

        if (column0 == null)
        {
            return -1;
        }

        return column0;
    }

    // Reduces an intake Timestamp to YYYY-MM-DD. Accepts ISO (2026-06-09[ T]...)
    // and US-locale Google Sheets timestamps (M/D/YYYY[ H:MM:SS], the sheet default).
    // Returns fallback0 when the value is blank or in an unrecognized format.
    private static String normalizeToIsoDate(String raw0, String fallback0)
    {
        if (isBlank(raw0))
        {
            return fallback0;
        }

        String trimmed0 = raw0.trim();
        String datePart0 = trimmed0.split("[T ]", 2)[0];

        if (datePart0.matches("\\d{4}-\\d{2}-\\d{2}"))
        {
            return datePart0;
        }

        // US locale month/day/year, e.g. 6/9/2026 or 06/09/2026.
        if (datePart0.matches("\\d{1,2}/\\d{1,2}/\\d{4}"))
        {
            String[] parts0 = datePart0.split("/");
            int month0 = Integer.parseInt(parts0[0]);
            int day0 = Integer.parseInt(parts0[1]);
            int year0 = Integer.parseInt(parts0[2]);

            if (month0 >= 1 && month0 <= 12 && day0 >= 1 && day0 <= 31)
            {
                return String.format("%04d-%02d-%02d", year0, month0, day0);
            }
        }

        return fallback0;
    }

    private static int findFirstUnprocessedRow(
        String[][] sheetData0,
        int startRow0,
        int lastRow0,
        int processingStatusCol0)
    {
        for (int rowNumber0 = startRow0; rowNumber0 <= lastRow0; rowNumber0++)
        {
            String processingStatus0 = getCellValue(
                sheetData0,
                rowNumber0,
                processingStatusCol0
            );

            if (!processingStatus0.equalsIgnoreCase(PROCESSED_STATUS0))
            {
                return rowNumber0;
            }
        }

        return -1;
    }

    private static int getMaxColumn(HashMap<String, Integer> headerMap0)
    {
        int maxColumn0 = -1;

        for (Integer column0 : headerMap0.values())
        {
            if (column0 != null && column0 > maxColumn0)
            {
                maxColumn0 = column0;
            }
        }

        return maxColumn0;
    }

    private static void printMatrix(String[][] matrix0)
    {
        for (int rowIndex0 = 0; rowIndex0 < matrix0.length; rowIndex0++)
        {
            String line0 = "";

            for (int colIndex0 = 0; colIndex0 < matrix0[rowIndex0].length; colIndex0++)
            {
                if (colIndex0 > 0)
                {
                    line0 += " | ";
                }

                line0 += matrix0[rowIndex0][colIndex0];
            }

            System.out.println(line0);
        }
    }

    private static String getCellValue(
        String[][] sheetData0,
        int rowNumber0,
        int oneBasedColumn0)
    {
        int rowIndex0 = rowNumber0 - 1;
        int columnIndex0 = oneBasedColumn0 - 1;

        if (rowIndex0 < 0 || rowIndex0 >= sheetData0.length)
        {
            return "";
        }

        if (columnIndex0 < 0 || columnIndex0 >= sheetData0[rowIndex0].length)
        {
            return "";
        }

        if (sheetData0[rowIndex0][columnIndex0] == null)
        {
            return "";
        }

        return sheetData0[rowIndex0][columnIndex0].trim();
    }

    private static boolean isBlank(String value0)
    {
        return value0 == null || value0.trim().length() == 0;
    }

    private static boolean isAllowedConversationLabel(String label0)
    {
        return label0.equals("Reached Out")
            || label0.equals("First Interest")
            || label0.equals("Meetings")
            || label0.equals("Prospective Close")
            || label0.equals("Rejected");
    }

    private static String chooseCrmCandidateEmail(
        String to0,
        String from0,
        ArrayList<String> internalEmails0)
    {
        String fromEmail0 = extractFirstEmail(from0);
        String toEmail0 = extractFirstExternalEmail(to0, internalEmails0);

        if (!isBlank(fromEmail0) && !isInternalEmail(fromEmail0, internalEmails0))
        {
            return fromEmail0;
        }

        if (!isBlank(toEmail0) && !isInternalEmail(toEmail0, internalEmails0))
        {
            return toEmail0;
        }

        return "";
    }

    private static String extractFirstExternalEmail(
        String commaList0,
        ArrayList<String> internalEmails0)
    {
        if (isBlank(commaList0))
        {
            return "";
        }

        String[] splitList0 = commaList0.split(",");

        for (int i0 = 0; i0 < splitList0.length; i0++)
        {
            String email0 = cleanEmail(splitList0[i0]);

            if (!isBlank(email0) && !isInternalEmail(email0, internalEmails0))
            {
                return email0;
            }
        }

        return "";
    }

    private static String extractFirstEmail(String value0)
    {
        if (isBlank(value0))
        {
            return "";
        }

        String[] splitList0 = value0.split(",");

        if (splitList0.length == 0)
        {
            return "";
        }

        return cleanEmail(splitList0[0]);
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

    private static boolean isInternalEmail(
        String email0,
        ArrayList<String> internalEmails0)
    {
        if (isBlank(email0))
        {
            return false;
        }

        String cleanedEmail0 = cleanEmail(email0);

        for (int i0 = 0; i0 < internalEmails0.size(); i0++)
        {
            String internalEmail0 = cleanEmail(internalEmails0.get(i0));

            if (cleanedEmail0.equalsIgnoreCase(internalEmail0))
            {
                return true;
            }
        }

        return false;
    }

    private static boolean isInternalName(
        String firstName0,
        String lastName0,
        ArrayList<String> internalNames0)
    {
        if (internalNames0 == null)
        {
            return false;
        }

        String candidate0 = normalizeText(firstName0 + " " + lastName0);

        if (isBlank(candidate0))
        {
            return false;
        }

        for (int i0 = 0; i0 < internalNames0.size(); i0++)
        {
            String internalName0 = normalizeText(internalNames0.get(i0));

            if (!isBlank(internalName0) && candidate0.equals(internalName0))
            {
                return true;
            }
        }

        return false;
    }

    private static boolean isInternalFund(
        String fundName0,
        String internalFundName0)
    {
        if (isBlank(fundName0) || isBlank(internalFundName0))
        {
            return false;
        }

        return normalizeText(fundName0).equals(normalizeText(internalFundName0));
    }

    private static boolean isInternalWebsite(
        String website0,
        String internalWebsite0)
    {
        if (isBlank(website0) || isBlank(internalWebsite0))
        {
            return false;
        }

        return cleanWebsite(website0).equals(cleanWebsite(internalWebsite0));
    }

    private static String normalizeText(String value0)
    {
        if (value0 == null)
        {
            return "";
        }

        return value0.trim().toLowerCase().replaceAll("\\s+", " ");
    }

    private static boolean isValidEmail(String email0)
    {
        if (isBlank(email0))
        {
            return false;
        }

        String cleanedEmail0 = cleanEmail(email0);

        return cleanedEmail0.matches("^[A-Za-z0-9+_.-]+@[A-Za-z0-9.-]+$");
    }

    private static JSONArray parseJsonArrayFromText(String text0)
    {
        String trimmedText0 = text0.trim();

        try
        {
            return new JSONArray(trimmedText0);
        }
        catch (Exception exception0)
        {
            int startIndex0 = trimmedText0.indexOf("[");
            int endIndex0 = trimmedText0.lastIndexOf("]");

            if (startIndex0 == -1 || endIndex0 == -1 || endIndex0 <= startIndex0)
            {
                throw exception0;
            }

            String jsonArrayText0 = trimmedText0.substring(startIndex0, endIndex0 + 1);

            return new JSONArray(jsonArrayText0);
        }
    }

    private static String buildExtractionPrompt(
        JSONArray rowsToProcess0,
        ArrayList<String> internalNames0,
        ArrayList<String> internalEmails0,
        String internalFundName0,
        String internalWebsite0)
    {
        ArrayList<CRMField> aiFields0 = CRMFieldRegistry.getAIExtractionFields();

        StringBuilder fieldList0 = new StringBuilder();
        fieldList0.append("- rowNumber\n");
        for (int i0 = 0; i0 < aiFields0.size(); i0++)
        {
            fieldList0.append("- ").append(aiFields0.get(i0).extractionJsonKey).append("\n");
        }

        StringBuilder rulesSections0 = new StringBuilder();
        for (int i0 = 0; i0 < aiFields0.size(); i0++)
        {
            CRMField field0 = aiFields0.get(i0);
            if (!isBlank(field0.aiExtractionInstruction))
            {
                rulesSections0.append(field0.aiExtractionInstruction).append("\n\n");
            }
        }

        String interactionRecordFields0 =
            "- direction\n"
            + "- keyTopicsDiscussed\n"
            + "- lpQuestionsAsked\n"
            + "- commitmentsMadeByGP\n"
            + "- lpSentiment\n"
            + "- relationshipSignals\n";

        String interactionRecordRules0 =
            "DIRECTION RULES:\n"
            + "direction must be INBOUND or OUTBOUND.\n"
            + "Use the To and From fields as the primary signal.\n"
            + "Then examine the email body: if the greeting addresses an external/LP person "
            + "(e.g. \"Hi [LP name]\") and the sign-off is from an internal/GP team member "
            + "(e.g. \"Kind regards, [GP name]\"), the email is OUTBOUND.\n"
            + "If the greeting addresses the internal GP and the sign-off is from an external LP, it is INBOUND.\n"
            + "When in doubt, use To/From: if the From field is internal, it is OUTBOUND; if the From field is external, it is INBOUND.\n\n"
            + "INTERACTION INTELLIGENCE RULES:\n"
            + "- keyTopicsDiscussed: list the main subjects discussed in the email. Empty array if none.\n"
            + "- lpQuestionsAsked: only include questions clearly asked by the LP. Empty array if none.\n"
            + "- commitmentsMadeByGP: only include explicit promises or action items the GP stated. Empty array if none.\n"
            + "- lpSentiment: assess the LP's tone in this email. Must be POSITIVE, NEUTRAL, CAUTIOUS, or NEGATIVE.\n"
            + "- relationshipSignals: notable signals about LP intent, timing, or allocation "
            + "(e.g. \"LP mentioned evaluating other funds\", \"LP indicated timing constraints\"). Empty array if none.\n\n";

        String prompt0 =
            "You are extracting investor CRM fields from email intake rows for a venture capital fundraising CRM.\n\n"
            + "The CRM candidate is the external investor or external fund contact, not the internal team.\n"
            + "Be conservative. Do not guess. If a value is not directly supported by the email headers or body, return an empty string.\n\n"

            + "INTERNAL PEOPLE - DO NOT EXTRACT THESE PEOPLE AS INVESTORS:\n";

        for (int i0 = 0; i0 < internalNames0.size(); i0++)
        {
            prompt0 += "- " + internalNames0.get(i0) + "\n";
        }

        prompt0 += "\nINTERNAL EMAILS - DO NOT RETURN THESE EMAILS:\n";

        for (int i0 = 0; i0 < internalEmails0.size(); i0++)
        {
            prompt0 += "- " + internalEmails0.get(i0) + "\n";
        }

        prompt0 +=
            "\nINTERNAL FUND / COMPANY - DO NOT EXTRACT THIS AS THE INVESTOR FUND:\n"
            + "- " + internalFundName0 + "\n\n"

            + "INTERNAL WEBSITE - DO NOT EXTRACT THIS AS THE INVESTOR WEBSITE:\n"
            + "- " + internalWebsite0 + "\n\n"

            + "Return ONLY valid JSON. Do not include markdown. Do not explain anything.\n\n"

            + "Return a JSON array. Each object must have exactly these fields:\n"
            + fieldList0.toString()
            + interactionRecordFields0 + "\n"

            + "GENERAL RULES:\n"
            + "1. Return exactly one JSON object for every input row. Do not skip rows.\n"
            + "2. Every returned object must use the same rowNumber as the input row.\n"
            + "3. Do not extract internal people, internal emails, the internal fund, or the internal website.\n"
            + "4. Do not guess. Empty string is better than a guessed value.\n"
            + "5. If a field is uncertain, return an empty string for that field.\n\n"

            + rulesSections0.toString()
            + interactionRecordRules0

            + "Rows to process:\n"
            + rowsToProcess0.toString(2);

        return prompt0;
    }
}