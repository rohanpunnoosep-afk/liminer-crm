package com.liminer.pipeline;

import com.liminer.core.CRMFieldRegistry;
import com.liminer.core.InteractionRecord;
import com.liminer.core.RelationshipSummary;
import com.liminer.core.SessionContext;
import com.liminer.llm.OpenAIClient;
import com.liminer.sheets.SheetsApp;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import org.json.JSONArray;
import org.json.JSONObject;

/*
 * RelationshipSummaryProcessor — the Relationship Summary rollup. Modeled EXACTLY on
 * LPScoreProcessor.scoreLpRows: header map -> per-column reads -> parallel row
 * processing -> per-column writes.
 *
 * For each eligible LP row it:
 *   1. Reads the full Interaction Records JSON for the row.
 *   2. Asks the LLM (OpenAIClient) to extract four relationship data points:
 *        - Aggregated LP interests and themes
 *        - LP sentiment changes over time (improving/flat/cooling + reasoning)
 *        - Narrative arc of the relationship
 *        - Outstanding GP commitments (commitments with no later OUTBOUND resolution;
 *          err on the side of flagging)
 *   3. Writes three columns column-by-column: the outstanding GP commitments, a JSON
 *      blob holding the full summary (interests, sentiment, arc, commitments, as-of date),
 *      and the analysis date. There is no status column: a blank analysis date is the
 *      signal that a row has not yet been summarized, so eligibility and re-runs are
 *      driven off that date.
 *
 * Decoupled from market intelligence on purpose: the GP<->LP relationship is a
 * fast-moving signal and is summarized on its own cadence.
 *
 * Spreadsheet Rules: every write is column-by-column. No rectangle writes. Cell
 * values are truncated to 50,000 chars.
 */
public class RelationshipSummaryProcessor
{
    private static final int MAX_CRM_ROWS   = 500;
    private static final int MAX_COLUMNS    = 200;
    private static final int MAX_ROWS_BATCH = 25;
    private static final int ROW_POOL_SIZE  = 8;
    private static final int SUMMARY_JSON_MAX = 49_000;
    private static final int CELL_MAX = 49_000;

    // Input column index constants. Eligibility is gated on the analysis-date column:
    // a blank date means the row has not yet been summarized (there is no status column).
    private static final int IN_DATE       = 0;
    private static final int IN_FUND_NAME  = 1;
    private static final int IN_INTER_REC  = 2;
    private static final int IN_FIELD_COUNT = 3;

    // Output column field indices (for the per-column write arrays).
    private static final int IDX_COMMITMENTS  = 0;
    private static final int IDX_JSON         = 1;
    private static final int IDX_DATE         = 2;
    private static final int FIELD_COUNT      = 3;

    // -----------------------------------------------------------------------
    // Public entry point
    // -----------------------------------------------------------------------

    public static String generateSummaries(SessionContext context0) throws Exception
    {
        return generateSummaries(context0, MAX_ROWS_BATCH);
    }

    public static String generateSummaries(SessionContext context0, int maxRows0) throws Exception
    {
        if (context0 == null || context0.config == null)
        {
            return "ERROR: Missing session context or config.";
        }

        String spreadsheetId = context0.config.spreadsheetId;
        String tabName       = context0.config.mainTabName;
        int    headerRow     = context0.config.mainTabHeaderRow;
        int    dataStartRow  = context0.config.mainTabDataStartRow;

        System.out.println("[RelationshipSummaryProcessor] Starting relationship summary run...");

        // Step 1: build header map and ensure output columns exist.
        HashMap<String, Integer> headerMap = SheetsApp.buildHeaderMap(
            spreadsheetId, tabName, headerRow, MAX_COLUMNS);

        CRMFieldRegistry.ensureRelationshipSummaryColumns(
            context0, spreadsheetId, tabName, headerRow, headerMap);

        // Re-read after provisioning (new columns may have shifted).
        headerMap = SheetsApp.buildHeaderMap(
            spreadsheetId, tabName, headerRow, MAX_COLUMNS);

        // Step 2: resolve needed column numbers.
        int[] inputCols  = resolveInputColumns(context0, headerMap);
        int[] outputCols = resolveOutputColumns(context0, headerMap);

        if (hasMissingColumn(outputCols))
        {
            return "ERROR: One or more relationship summary output columns could not be resolved.";
        }

        // Step 3: read each input column separately (no rectangles).
        String[][] dateCol     = readCol(spreadsheetId, tabName, dataStartRow, inputCols[IN_DATE]);
        String[][] fundNameCol = readCol(spreadsheetId, tabName, dataStartRow, inputCols[IN_FUND_NAME]);
        String[][] interRecCol = readCol(spreadsheetId, tabName, dataStartRow, inputCols[IN_INTER_REC]);

        // Step 4: select eligible rows.
        LinkedHashMap<Integer, Integer> eligibleRows = selectEligibleRows(
            dateCol, fundNameCol, interRecCol, dataStartRow, maxRows0);

        if (eligibleRows.isEmpty())
        {
            return "Relationship summary complete. No eligible rows found.";
        }

        System.out.println("[RelationshipSummaryProcessor] Eligible rows: " + eligibleRows.size());

        // Step 5: run rows in parallel (BBC pattern).
        ConcurrentHashMap<Integer, RelationshipSummary> resultsConcurrent = new ConcurrentHashMap<>();
        AtomicInteger completedCount = new AtomicInteger(0);
        AtomicInteger failedCount    = new AtomicInteger(0);

        ExecutorService pool = Executors.newFixedThreadPool(ROW_POOL_SIZE);
        ArrayList<Future<?>> futures = new ArrayList<>();

        for (Map.Entry<Integer, Integer> entry : eligibleRows.entrySet())
        {
            final int sheetRow = entry.getKey();
            final int localIdx = entry.getValue();
            final String fundName = cell(fundNameCol, localIdx);
            final String recordsJson = cell(interRecCol, localIdx);

            futures.add(pool.submit(() ->
            {
                try
                {
                    RelationshipSummary summary = summarizeRow(fundName, recordsJson);
                    resultsConcurrent.put(sheetRow, summary);
                    completedCount.incrementAndGet();
                }
                catch (Exception e)
                {
                    System.err.println("[RelationshipSummaryProcessor] Row " + sheetRow
                        + " failed: " + e.getMessage());
                    resultsConcurrent.put(sheetRow, failedSummary());
                    failedCount.incrementAndGet();
                }
            }));
        }

        for (Future<?> f : futures)
        {
            try { f.get(); }
            catch (Exception e) { System.err.println("[RelationshipSummaryProcessor] Future error: " + e.getMessage()); }
        }
        pool.shutdown();

        // Step 6: sort results by row.
        LinkedHashMap<Integer, RelationshipSummary> rowResults = new LinkedHashMap<>();
        ArrayList<Integer> sortedRows = new ArrayList<>(resultsConcurrent.keySet());
        java.util.Collections.sort(sortedRows);
        for (int rn : sortedRows) { rowResults.put(rn, resultsConcurrent.get(rn)); }

        // Step 7: write results column-by-column (no rectangles).
        writeResultsToSheet(spreadsheetId, tabName, outputCols, rowResults);

        return "Relationship summary complete. Completed: " + completedCount.get()
            + ", Failed: " + failedCount.get() + ".";
    }

    // -----------------------------------------------------------------------
    // Per-row LLM summarization
    // -----------------------------------------------------------------------

    private static RelationshipSummary summarizeRow(String fundName, String recordsJson) throws Exception
    {
        // Parse the stored Interaction Records into structured records.
        List<InteractionRecord> records = parseInteractionRecords(recordsJson);

        String prompt = buildPrompt(fundName, records);
        String aiText = OpenAIClient.getTextResponse(prompt);

        JSONObject parsed = InvestorProfileExtractor.parseJsonObjectFromText(aiText);
        RelationshipSummary summary = RelationshipSummary.fromOpenAiJson(parsed);

        summary.analysisDate = LocalDate.now().toString();
        summary.status = RelationshipSummary.STATUS_COMPLETE;

        String json = summary.toJSON().toString();
        summary.summaryJson = json.length() > SUMMARY_JSON_MAX ? json.substring(0, SUMMARY_JSON_MAX) : json;
        return summary;
    }

    private static List<InteractionRecord> parseInteractionRecords(String recordsJson)
    {
        List<InteractionRecord> records = new ArrayList<>();
        if (isBlank(recordsJson)) return records;
        try
        {
            JSONArray arr = InteractionRecord.extractRecordsArray(recordsJson);
            for (int i = 0; i < arr.length(); i++)
            {
                JSONObject obj = arr.optJSONObject(i);
                if (obj != null) records.add(InteractionRecord.fromJSON(obj));
            }
        }
        catch (Exception e)
        {
            System.err.println("[RelationshipSummaryProcessor] Could not parse interaction records: "
                + e.getMessage());
        }
        return records;
    }

    private static String buildPrompt(String fundName, List<InteractionRecord> records)
    {
        // Serialize records (already chronological as appended) for the model. Each record
        // carries date, direction (INBOUND/OUTBOUND), and the structured commitmentsMadeByGP
        // array used for the outstanding-commitments analysis.
        JSONArray recordsArr = new JSONArray();
        for (InteractionRecord r : records)
        {
            if (r != null) recordsArr.put(r.toJSON());
        }

        StringBuilder sb = new StringBuilder();
        sb.append("You are a fundraising relationship analyst for a venture-capital GP.\n");
        sb.append("Analyze the full interaction history between the GP and the LP \"")
          .append(safe(fundName)).append("\".\n\n");

        sb.append("Return ONLY valid JSON. No markdown, no code fences, no explanation.\n");
        sb.append("Use exactly this JSON structure with these keys:\n");
        sb.append("{\n");
        sb.append("  \"aggregatedInterests\": [\"...\"],\n");
        sb.append("  \"sentimentChangesOverTime\": \"...\",\n");
        sb.append("  \"narrativeArc\": \"...\",\n");
        sb.append("  \"outstandingCommitments\": [\"...\"]\n");
        sb.append("}\n\n");

        sb.append("Field definitions:\n");
        sb.append("1. aggregatedInterests: an array of the distinct interests, topics, and themes the LP raised across all conversations (e.g. specific sectors, check sizes, co-investment, reporting cadence). Deduplicate and keep each item short.\n");
        sb.append("2. sentimentChangesOverTime: ONE narrative string describing how the LP's sentiment moved over time. Classify the overall trajectory as improving, flat, or cooling, and explain the reasoning with reference to specific moments/dates.\n");
        sb.append("3. narrativeArc: ONE narrative string describing how the relationship started, where it is now, and the key moments in between.\n");
        sb.append("4. outstandingCommitments: THE MOST IMPORTANT FIELD. Review every commitment the GP made — both the structured commitmentsMadeByGP arrays AND any promise visible in the record text. A commitment is OUTSTANDING unless a LATER OUTBOUND record clearly fulfills or resolves it. List each outstanding commitment as a short string (include what was promised and roughly when). When you are unsure whether a commitment was resolved, FLAG IT. Err strongly on the side of flagging rather than omitting.\n\n");

        sb.append("Records are ordered chronologically. direction is INBOUND (from the LP) or OUTBOUND (from the GP).\n");
        sb.append("Interaction records JSON:\n");
        sb.append(recordsArr.toString());
        return sb.toString();
    }

    // -----------------------------------------------------------------------
    // Column-by-column write
    // -----------------------------------------------------------------------

    private static void writeResultsToSheet(
        String spreadsheetId, String tabName, int[] outputCols,
        LinkedHashMap<Integer, RelationshipSummary> rowResults) throws Exception
    {
        if (rowResults.isEmpty()) return;

        int minRow = rowResults.keySet().stream().mapToInt(Integer::intValue).min().getAsInt();
        int maxRow = rowResults.keySet().stream().mapToInt(Integer::intValue).max().getAsInt();
        int span   = maxRow - minRow + 1;

        // Pre-read each output column so untouched rows in the span keep their values.
        String[][] commitmentsData = readColRange(spreadsheetId, tabName, minRow, maxRow, outputCols[IDX_COMMITMENTS]);
        String[][] jsonData        = readColRange(spreadsheetId, tabName, minRow, maxRow, outputCols[IDX_JSON]);
        String[][] dateData        = readColRange(spreadsheetId, tabName, minRow, maxRow, outputCols[IDX_DATE]);

        for (Map.Entry<Integer, RelationshipSummary> entry : rowResults.entrySet())
        {
            int idx = entry.getKey() - minRow;
            RelationshipSummary s = entry.getValue();

            commitmentsData[idx][0] = truncate(RelationshipSummary.joinList(s.outstandingCommitments), CELL_MAX);
            jsonData[idx][0]        = truncate(safe(s.summaryJson), CELL_MAX);
            // A blank analysis date is the "not yet summarized" signal, so failed rows leave
            // the date empty (and stay eligible). Only COMPLETE rows stamp a date.
            dateData[idx][0]        = safe(s.analysisDate);
        }

        SheetsApp.updateRangeMatrix(spreadsheetId, tabName, minRow, outputCols[IDX_COMMITMENTS], commitmentsData);
        SheetsApp.updateRangeMatrix(spreadsheetId, tabName, minRow, outputCols[IDX_JSON], jsonData);
        SheetsApp.updateRangeMatrix(spreadsheetId, tabName, minRow, outputCols[IDX_DATE], dateData);
    }

    // -----------------------------------------------------------------------
    // Row selection
    // -----------------------------------------------------------------------

    private static LinkedHashMap<Integer, Integer> selectEligibleRows(
        String[][] dateCol, String[][] fundNameCol, String[][] interRecCol,
        int dataStartRow, int maxRowsCap)
    {
        LinkedHashMap<Integer, Integer> rows = new LinkedHashMap<>();
        int maxRows = Math.max(
            fundNameCol == null ? 0 : fundNameCol.length,
            interRecCol == null ? 0 : interRecCol.length);
        int selected = 0;
        for (int i = 0; i < maxRows && selected < maxRowsCap; i++)
        {
            String fundName = cell(fundNameCol, i);
            if (isBlank(fundName)) continue;
            String records = cell(interRecCol, i);
            if (isBlank(records)) continue;
            // A blank analysis date means this row has not been summarized yet.
            String analysisDate = cell(dateCol, i);
            if (isBlank(analysisDate))
            {
                rows.put(dataStartRow + i, i);
                selected++;
            }
        }
        return rows;
    }

    // -----------------------------------------------------------------------
    // Column resolution
    // -----------------------------------------------------------------------

    private static int[] resolveInputColumns(SessionContext ctx, HashMap<String, Integer> headerMap)
    {
        String[] keys = {
            ctx.config.getCol("mainTabRelationshipSummaryDateCol"),
            ctx.config.getCol("mainTabFundNameCol"),
            ctx.config.getCol("mainTabInteractionRecordsCol")
        };
        return resolve(keys, IN_FIELD_COUNT, headerMap);
    }

    private static int[] resolveOutputColumns(SessionContext ctx, HashMap<String, Integer> headerMap)
    {
        String[] keys = {
            ctx.config.getCol("mainTabOutstandingCommitmentsCol"),
            ctx.config.getCol("mainTabRelationshipSummaryJsonCol"),
            ctx.config.getCol("mainTabRelationshipSummaryDateCol")
        };
        return resolve(keys, FIELD_COUNT, headerMap);
    }

    private static int[] resolve(String[] keys, int count, HashMap<String, Integer> headerMap)
    {
        int[] cols = new int[count];
        for (int i = 0; i < keys.length; i++)
        {
            cols[i] = keys[i] != null ? SheetsApp.findColumnInHeaderMap(headerMap, keys[i]) : -1;
        }
        return cols;
    }

    private static boolean hasMissingColumn(int[] cols)
    {
        for (int c : cols) { if (c < 1) return true; }
        return false;
    }

    // -----------------------------------------------------------------------
    // SheetsApp helpers
    // -----------------------------------------------------------------------

    private static String[][] readCol(
        String spreadsheetId, String tabName, int startRow, int col) throws Exception
    {
        if (col < 1) return new String[0][1];
        return SheetsApp.readRangeMatrix(
            spreadsheetId, tabName, startRow, col, startRow + MAX_CRM_ROWS - 1, col);
    }

    private static String[][] readColRange(
        String spreadsheetId, String tabName, int minRow, int maxRow, int col) throws Exception
    {
        int span = maxRow - minRow + 1;
        if (col < 1)
        {
            String[][] empty = new String[span][1];
            for (String[] r : empty) r[0] = "";
            return empty;
        }
        return SheetsApp.readRangeMatrix(spreadsheetId, tabName, minRow, col, maxRow, col);
    }

    // -----------------------------------------------------------------------
    // Minor utilities
    // -----------------------------------------------------------------------

    private static RelationshipSummary failedSummary()
    {
        RelationshipSummary s = new RelationshipSummary();
        s.status = RelationshipSummary.STATUS_FAILED;
        // Leave analysisDate blank: a blank date keeps the row eligible for a re-run,
        // since the date column is the sole "has this been summarized?" signal.
        s.analysisDate = "";
        s.summaryJson = "{\"error\":\"row processing failed\"}";
        return s;
    }

    private static String cell(String[][] col, int idx)
    {
        if (col == null || idx >= col.length || col[idx] == null || col[idx].length == 0) return "";
        return safe(col[idx][0]);
    }

    private static String truncate(String s, int max)
    {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max);
    }

    private static String safe(String s) { return s == null ? "" : s; }

    private static boolean isBlank(String s) { return s == null || s.trim().isEmpty(); }
}
