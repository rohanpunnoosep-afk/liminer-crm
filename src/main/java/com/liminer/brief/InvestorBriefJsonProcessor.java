package com.liminer.brief;

import com.liminer.billing.CostMeter;
import com.liminer.core.CRMFieldRegistry;
import com.liminer.core.SessionContext;
import com.liminer.core.UserAccount;
import com.liminer.llm.OpenAIClient;
import com.liminer.pipeline.InvestorProfileExtractor;
import com.liminer.sheets.SheetsApp;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import org.json.JSONArray;
import org.json.JSONObject;

/*
 * InvestorBriefJsonProcessor — the Investor Brief assembly workflow. Modeled EXACTLY on
 * RelationshipSummaryProcessor: build header map -> ensure output columns -> resolve
 * column numbers -> read each input column separately (no rectangles) -> select eligible
 * rows -> parallel per-row work -> write each output column separately.
 *
 * For each eligible LP row it collects the already-computed outputs of the four upstream
 * workflows (LP Enrichment, Basic Background Check, Market Intelligence, Relationship
 * Summary) into one structured brief, runs two GPT synthesis passes (call preparation +
 * executive summary, plus a GPT-inferred fundingStatus), and writes the serialized brief
 * to the Investor Brief JSON column with a Last Brief Generated date.
 *
 * Eligibility is gated on a blank Last Brief Generated date (a blank date keeps failed
 * rows eligible for retry); a Fund Name is also required. Assembly is defensive: rows
 * missing upstream data still produce a partial brief, blank sources become empty fields.
 *
 * Spreadsheet Rules: every read and write is column-by-column. No rectangle writes. Cell
 * values are truncated to 49,000 chars.
 */
public class InvestorBriefJsonProcessor
{
    private static final int MAX_CRM_ROWS   = 500;
    private static final int MAX_COLUMNS    = 200;
    private static final int MAX_ROWS_BATCH = 10;   // briefs are GPT-heavy
    private static final int ROW_POOL_SIZE  = 8;
    private static final int BRIEF_JSON_MAX = 49_000;
    private static final int CELL_MAX       = 49_000;

    // Output column field indices (for the per-column write arrays).
    private static final int IDX_BRIEF_JSON = 0;
    private static final int IDX_DATE       = 1;
    private static final int FIELD_COUNT    = 2;

    // Input registry keys, grouped by brief section. Each is read into its own
    // single-column matrix (no rectangles) and looked up per-row by key.
    private static final String[] CONTACT_KEYS = {
        "mainTabContact1FirstNameCol", "mainTabContact1LastNameCol", "mainTabContact1EmailCol",
        "mainTabFundNameCol", "mainTabWebsiteCol", "mainTabTypeOfInvestorCol",
        "mainTabSectorTagsCol", "mainTabMicrosectorTagsCol", "mainTabGeographyCol",
        "mainTabPriorBackedFundsCol", "mainTabInvestmentThesisCol", "mainTabIntelligenceJsonCol",
        "mainTabContactLinkedInAboutCol", "mainTabContactPastWorkExperienceCol",
        "mainTabFundLinkedInAboutCol", "mainTabContactWebsiteBioSummaryCol",
        "mainTabContactBioCareerSummaryCol", "mainTabContactBioInstitutionsCol",
        "mainTabContactBioEducationCol"
    };

    private static final String[] MI_KEYS = {
        "mainTabResourcesScoreCol", "mainTabFitScoreCol", "mainTabProbabilityNowCol",
        "mainTabCrdNumberCol", "mainTabCikNumberCol", "mainTabLeiCol", "mainTabEinCol",
        "mainTabIdentityStatusCol", "mainTabMarketIntelligenceJsonCol"
    };

    private static final String[] REL_KEYS = {
        "mainTabOutstandingCommitmentsCol", "mainTabRelationshipSummaryJsonCol",
        "mainTabRelationshipSummaryDateCol"
    };

    // -----------------------------------------------------------------------
    // Public entry points
    // -----------------------------------------------------------------------

    public static String generateBriefs(SessionContext context0) throws Exception
    {
        return generateBriefs(context0, MAX_ROWS_BATCH);
    }

    public static String generateBriefs(SessionContext context0, int maxRows0) throws Exception
    {
        if (context0 == null || context0.config == null)
        {
            return "ERROR: Missing session context or config.";
        }

        String spreadsheetId = context0.config.spreadsheetId;
        String tabName       = context0.config.mainTabName;
        int    headerRow     = context0.config.mainTabHeaderRow;
        int    dataStartRow  = context0.config.mainTabDataStartRow;

        System.out.println("[InvestorBriefJsonProcessor] Starting investor brief run...");

        // Step 1: build header map and ensure output columns exist.
        HashMap<String, Integer> headerMap = SheetsApp.buildHeaderMap(
            spreadsheetId, tabName, headerRow, MAX_COLUMNS);

        CRMFieldRegistry.ensureInvestorBriefColumns(
            context0, spreadsheetId, tabName, headerRow, headerMap);

        // Re-read after provisioning (new columns may have shifted).
        headerMap = SheetsApp.buildHeaderMap(
            spreadsheetId, tabName, headerRow, MAX_COLUMNS);

        // Step 2: resolve output column numbers.
        int[] outputCols = resolveOutputColumns(context0, headerMap);
        if (hasMissingColumn(outputCols))
        {
            return "ERROR: One or more investor brief output columns could not be resolved.";
        }

        // Step 3: read each input column separately (no rectangles) into a per-key map.
        Map<String, String[][]> inputs = new HashMap<>();
        for (String key : CONTACT_KEYS) readInto(inputs, context0, headerMap, spreadsheetId, tabName, dataStartRow, key);
        for (String key : MI_KEYS)      readInto(inputs, context0, headerMap, spreadsheetId, tabName, dataStartRow, key);
        for (String key : REL_KEYS)     readInto(inputs, context0, headerMap, spreadsheetId, tabName, dataStartRow, key);

        // The Last Brief Generated date is both an output column and the eligibility gate.
        String[][] lastBriefCol = readColRangeFromStart(
            spreadsheetId, tabName, dataStartRow, outputCols[IDX_DATE]);
        String[][] fundNameCol  = inputs.get("mainTabFundNameCol");

        // Step 4: select eligible rows (has a Fund Name AND a blank Last Brief Generated).
        LinkedHashMap<Integer, Integer> eligibleRows = selectEligibleRows(
            lastBriefCol, fundNameCol, dataStartRow, maxRows0);

        if (eligibleRows.isEmpty())
        {
            return "Investor brief complete. No eligible rows found.";
        }

        System.out.println("[InvestorBriefJsonProcessor] Eligible rows: " + eligibleRows.size());

        // The GP's own fund profile is the same for every row.
        final JSONObject gpProfile = buildGpProfile(context0);

        // Step 5: run rows in parallel.
        ConcurrentHashMap<Integer, InvestorBriefJson> resultsConcurrent = new ConcurrentHashMap<>();
        AtomicInteger completedCount = new AtomicInteger(0);
        AtomicInteger failedCount    = new AtomicInteger(0);

        ExecutorService pool = Executors.newFixedThreadPool(ROW_POOL_SIZE);
        ArrayList<Future<?>> futures = new ArrayList<>();

        for (Map.Entry<Integer, Integer> entry : eligibleRows.entrySet())
        {
            final int sheetRow = entry.getKey();
            final int localIdx = entry.getValue();

            futures.add(pool.submit(CostMeter.wrap(() ->
            {
                try
                {
                    InvestorBriefJson brief = buildBriefForRow(inputs, localIdx, gpProfile);
                    resultsConcurrent.put(sheetRow, brief);
                    completedCount.incrementAndGet();
                }
                catch (Exception e)
                {
                    System.err.println("[InvestorBriefJsonProcessor] Row " + sheetRow
                        + " failed: " + e.getMessage());
                    resultsConcurrent.put(sheetRow, failedBrief());
                    failedCount.incrementAndGet();
                }
            })));
        }

        for (Future<?> f : futures)
        {
            try { f.get(); }
            catch (Exception e) { System.err.println("[InvestorBriefJsonProcessor] Future error: " + e.getMessage()); }
        }
        pool.shutdown();

        // Step 6: sort results by row.
        LinkedHashMap<Integer, InvestorBriefJson> rowResults = new LinkedHashMap<>();
        ArrayList<Integer> sortedRows = new ArrayList<>(resultsConcurrent.keySet());
        java.util.Collections.sort(sortedRows);
        for (int rn : sortedRows) { rowResults.put(rn, resultsConcurrent.get(rn)); }

        // Step 7: write results column-by-column (no rectangles).
        writeResultsToSheet(spreadsheetId, tabName, outputCols, rowResults);

        return "Investor brief complete. Completed: " + completedCount.get()
            + ", Failed: " + failedCount.get() + ".";
    }

    // -----------------------------------------------------------------------
    // Per-row assembly
    // -----------------------------------------------------------------------

    private static InvestorBriefJson buildBriefForRow(
        Map<String, String[][]> in, int idx, JSONObject gpProfile) throws Exception
    {
        InvestorBriefJson brief = new InvestorBriefJson();

        brief.contactAndFirmProfile = assembleContact(in, idx);
        brief.marketIntelligence    = assembleMarketIntelligence(in, idx);
        brief.relationshipSummary   = assembleRelationship(in, idx);

        // GPT pass 1 — strategy synthesis (derives fundingStatus, then call prep).
        JSONObject pass1 = runStrategySynthesis(brief, gpProfile);
        String fundingStatus = pass1.optString("fundingStatus", "");
        // Hoist fundingStatus to the top level of marketIntelligence (explicit field).
        brief.marketIntelligence.put("fundingStatus", fundingStatus);
        brief.callPreparation = pass1.optJSONObject("callPreparation") != null
            ? pass1.optJSONObject("callPreparation") : new JSONObject();

        // GPT pass 2 — executive summary over the fully assembled brief.
        brief.executiveSummary = runExecutiveSummary(brief);

        brief.asOfDate = Instant.now().toString();
        brief.status   = InvestorBriefJson.STATUS_COMPLETE;

        String json = brief.toJSON().toString();
        brief.briefJson = json.length() > BRIEF_JSON_MAX ? json.substring(0, BRIEF_JSON_MAX) : json;
        return brief;
    }

    private static JSONObject assembleContact(Map<String, String[][]> in, int idx)
    {
        JSONObject c = new JSONObject();
        c.put("firstName", val(in, "mainTabContact1FirstNameCol", idx));
        c.put("lastName",  val(in, "mainTabContact1LastNameCol", idx));
        c.put("email",     val(in, "mainTabContact1EmailCol", idx));
        c.put("fundName",  val(in, "mainTabFundNameCol", idx));
        c.put("website",   val(in, "mainTabWebsiteCol", idx));
        c.put("typeOfInvestor", val(in, "mainTabTypeOfInvestorCol", idx));

        // Pipe-delimited enrichment tags -> arrays.
        c.put("sectorTags",      InvestorBriefJson.pipeToJsonArray(val(in, "mainTabSectorTagsCol", idx)));
        c.put("microsectorTags", InvestorBriefJson.pipeToJsonArray(val(in, "mainTabMicrosectorTagsCol", idx)));
        c.put("geography",       InvestorBriefJson.pipeToJsonArray(val(in, "mainTabGeographyCol", idx)));
        c.put("priorBackedFunds",InvestorBriefJson.pipeToJsonArray(val(in, "mainTabPriorBackedFundsCol", idx)));
        c.put("investmentThesis", val(in, "mainTabInvestmentThesisCol", idx));

        // Embed the parsed enrichment Intelligence JSON.
        c.put("intelligence", InvestorBriefJson.parseBlob(val(in, "mainTabIntelligenceJsonCol", idx)));

        // Background-check bio / career fields.
        c.put("contactLinkedInAbout",     val(in, "mainTabContactLinkedInAboutCol", idx));
        c.put("contactPastWorkExperience",InvestorBriefJson.parseBlob(val(in, "mainTabContactPastWorkExperienceCol", idx)));
        c.put("fundLinkedInAbout",        val(in, "mainTabFundLinkedInAboutCol", idx));
        c.put("contactWebsiteBioSummary", val(in, "mainTabContactWebsiteBioSummaryCol", idx));
        c.put("contactBioCareerSummary",  val(in, "mainTabContactBioCareerSummaryCol", idx));
        c.put("contactAffiliatedInstitutions", InvestorBriefJson.parseBlob(val(in, "mainTabContactBioInstitutionsCol", idx)));
        c.put("contactBioEducation",      InvestorBriefJson.parseBlob(val(in, "mainTabContactBioEducationCol", idx)));
        return c;
    }

    private static JSONObject assembleMarketIntelligence(Map<String, String[][]> in, int idx)
    {
        JSONObject m = new JSONObject();
        putNumberOrText(m, "resourcesScore",  val(in, "mainTabResourcesScoreCol", idx));
        putNumberOrText(m, "fitScore",        val(in, "mainTabFitScoreCol", idx));
        putNumberOrText(m, "probabilityNow",  val(in, "mainTabProbabilityNowCol", idx));

        m.put("crdNumber",      val(in, "mainTabCrdNumberCol", idx));
        m.put("cikNumber",      val(in, "mainTabCikNumberCol", idx));
        m.put("lei",            val(in, "mainTabLeiCol", idx));
        m.put("ein",            val(in, "mainTabEinCol", idx));
        m.put("identityStatus", val(in, "mainTabIdentityStatusCol", idx));

        // The MI JSON blob carries the macro/resources/fit/probability_now indicator arrays.
        m.put("intelligence", InvestorBriefJson.parseBlob(val(in, "mainTabMarketIntelligenceJsonCol", idx)));
        // fundingStatus is added by GPT pass 1 after this assembly.
        return m;
    }

    private static JSONObject assembleRelationship(Map<String, String[][]> in, int idx)
    {
        JSONObject r = new JSONObject();
        JSONObject blob = InvestorBriefJson.parseBlobObject(val(in, "mainTabRelationshipSummaryJsonCol", idx));

        r.put("aggregatedInterests", blob.optJSONArray("aggregatedInterests") != null
            ? blob.optJSONArray("aggregatedInterests") : new JSONArray());
        r.put("sentimentChangesOverTime", blob.optString("sentimentChangesOverTime", ""));
        r.put("narrativeArc", blob.optString("narrativeArc", ""));
        r.put("analysisDate", blob.optString("analysisDate",
            val(in, "mainTabRelationshipSummaryDateCol", idx)));

        // Lift outstandingCommitments into a dedicated top-level scannable array. Prefer
        // the structured JSON array from the blob; fall back to the newline-delimited
        // Outstanding GP Commitments column when the blob has none.
        JSONArray commitments = blob.optJSONArray("outstandingCommitments");
        if (commitments == null || commitments.length() == 0)
        {
            commitments = InvestorBriefJson.newlineToJsonArray(val(in, "mainTabOutstandingCommitmentsCol", idx));
        }
        r.put("outstandingCommitments", commitments);
        return r;
    }

    // -----------------------------------------------------------------------
    // GPT passes
    // -----------------------------------------------------------------------

    private static JSONObject runStrategySynthesis(InvestorBriefJson brief, JSONObject gpProfile) throws Exception
    {
        StringBuilder sb = new StringBuilder();
        sb.append("You are a fundraising strategy analyst preparing a venture-capital GP for a meeting with a prospective LP.\n\n");
        sb.append("Return ONLY valid JSON. No markdown, no code fences, no explanation.\n");
        sb.append("Use exactly this JSON structure with these keys:\n");
        sb.append("{\n");
        sb.append("  \"fundingStatus\": \"...\",\n");
        sb.append("  \"callPreparation\": {\n");
        sb.append("    \"talkingPoints\": [\"...\"],\n");
        sb.append("    \"suggestedQuestions\": [\"...\"],\n");
        sb.append("    \"anticipatedObjections\": [{\"objection\":\"...\",\"navigation\":\"...\"}],\n");
        sb.append("    \"relationshipBuildingOpportunities\": [\"...\"],\n");
        sb.append("    \"recommendedNextSteps\": [\"...\"]\n");
        sb.append("  }\n");
        sb.append("}\n\n");

        sb.append("Instructions:\n");
        sb.append("1. FIRST derive fundingStatus from the LP's Probability Now indicators in marketIntelligence. ");
        sb.append("It must be a short plain-language label such as \"Actively Deploying\", \"Between Funds\", or \"Fundraising\".\n");
        sb.append("2. THEN condition the rest of the synthesis (talking points, questions, objections, opportunities, next steps) on that fundingStatus and on how the GP's fund profile fits this LP.\n\n");

        sb.append("GP fund profile (the fund being raised):\n");
        sb.append(gpProfile.toString()).append("\n\n");
        sb.append("LP contact and firm profile:\n");
        sb.append(brief.contactAndFirmProfile.toString()).append("\n\n");
        sb.append("LP market intelligence (scores + indicators):\n");
        sb.append(brief.marketIntelligence.toString()).append("\n\n");
        sb.append("LP relationship summary:\n");
        sb.append(brief.relationshipSummary.toString()).append("\n");

        String aiText = OpenAIClient.getTextResponse(sb.toString());
        JSONObject parsed = InvestorProfileExtractor.parseJsonObjectFromText(aiText);
        return parsed != null ? parsed : new JSONObject();
    }

    private static String runExecutiveSummary(InvestorBriefJson brief) throws Exception
    {
        StringBuilder sb = new StringBuilder();
        sb.append("You are a fundraising analyst writing the executive summary at the top of an LP meeting brief.\n");
        sb.append("Write a single tight paragraph of 5 to 7 sentences that a GP can read in under a minute before the call.\n");
        sb.append("Cover who the LP is, their funding status and fit, the state of the relationship, and the most important things to do on the call.\n");
        sb.append("Return ONLY the paragraph as plain text. No markdown, no headings, no JSON.\n\n");
        sb.append("Brief so far:\n");
        sb.append(brief.toJSON().toString());

        String aiText = OpenAIClient.getTextResponse(sb.toString());
        return aiText == null ? "" : aiText.trim();
    }

    private static JSONObject buildGpProfile(SessionContext ctx)
    {
        JSONObject gp = new JSONObject();
        UserAccount u = ctx.user;
        if (u != null)
        {
            gp.put("internalFundName", safe(u.internalFundName));
            gp.put("sectorTags",      InvestorBriefJson.pipeToJsonArray(u.clientSectorTags));
            gp.put("microsectorTags", InvestorBriefJson.pipeToJsonArray(u.clientMicrosectorTags));
            gp.put("geography",       InvestorBriefJson.pipeToJsonArray(u.clientGeography));
            gp.put("stages",          InvestorBriefJson.pipeToJsonArray(u.clientStages));
            gp.put("investmentThesis", safe(u.clientInvestmentThesis));
        }
        return gp;
    }

    // -----------------------------------------------------------------------
    // Column-by-column write
    // -----------------------------------------------------------------------

    private static void writeResultsToSheet(
        String spreadsheetId, String tabName, int[] outputCols,
        LinkedHashMap<Integer, InvestorBriefJson> rowResults) throws Exception
    {
        if (rowResults.isEmpty()) return;

        int minRow = rowResults.keySet().stream().mapToInt(Integer::intValue).min().getAsInt();
        int maxRow = rowResults.keySet().stream().mapToInt(Integer::intValue).max().getAsInt();

        // Pre-read each output column so untouched rows in the span keep their values.
        String[][] briefData = readColRange(spreadsheetId, tabName, minRow, maxRow, outputCols[IDX_BRIEF_JSON]);
        String[][] dateData  = readColRange(spreadsheetId, tabName, minRow, maxRow, outputCols[IDX_DATE]);

        for (Map.Entry<Integer, InvestorBriefJson> entry : rowResults.entrySet())
        {
            int idx = entry.getKey() - minRow;
            InvestorBriefJson b = entry.getValue();

            briefData[idx][0] = truncate(safe(b.briefJson), CELL_MAX);
            // A blank Last Brief Generated date is the "not yet briefed" signal, so failed
            // rows leave the date empty (and stay eligible). Only COMPLETE rows stamp a date.
            dateData[idx][0]  = InvestorBriefJson.STATUS_COMPLETE.equals(b.status)
                ? java.time.LocalDate.now().toString() : "";
        }

        SheetsApp.updateRangeMatrix(spreadsheetId, tabName, minRow, outputCols[IDX_BRIEF_JSON], briefData);
        SheetsApp.updateRangeMatrix(spreadsheetId, tabName, minRow, outputCols[IDX_DATE], dateData);
    }

    // -----------------------------------------------------------------------
    // Row selection
    // -----------------------------------------------------------------------

    private static LinkedHashMap<Integer, Integer> selectEligibleRows(
        String[][] lastBriefCol, String[][] fundNameCol, int dataStartRow, int maxRowsCap)
    {
        LinkedHashMap<Integer, Integer> rows = new LinkedHashMap<>();
        int maxRows = fundNameCol == null ? 0 : fundNameCol.length;
        int selected = 0;
        for (int i = 0; i < maxRows && selected < maxRowsCap; i++)
        {
            String fundName = cell(fundNameCol, i);
            if (isBlank(fundName)) continue;
            // A blank Last Brief Generated date means this row has not been briefed yet.
            String lastBrief = cell(lastBriefCol, i);
            if (isBlank(lastBrief))
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

    private static int[] resolveOutputColumns(SessionContext ctx, HashMap<String, Integer> headerMap)
    {
        String[] keys = {
            ctx.config.getCol("mainTabInvestorBriefJsonCol"),
            ctx.config.getCol("mainTabLastBriefGeneratedCol")
        };
        int[] cols = new int[FIELD_COUNT];
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

    private static void readInto(
        Map<String, String[][]> inputs, SessionContext ctx, HashMap<String, Integer> headerMap,
        String spreadsheetId, String tabName, int startRow, String key) throws Exception
    {
        String header = ctx.config.getCol(key);
        int col = header != null ? SheetsApp.findColumnInHeaderMap(headerMap, header) : -1;
        inputs.put(key, readColRangeFromStart(spreadsheetId, tabName, startRow, col));
    }

    private static String[][] readColRangeFromStart(
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

    private static InvestorBriefJson failedBrief()
    {
        InvestorBriefJson b = new InvestorBriefJson();
        b.status = InvestorBriefJson.STATUS_FAILED;
        // Leave the date blank: a blank date keeps the row eligible for a re-run.
        b.briefJson = "{\"error\":\"row processing failed\"}";
        return b;
    }

    // Store a numeric cell as a JSON number when it parses, otherwise as its raw text
    // (so a non-numeric or blank score is still represented, never dropped).
    private static void putNumberOrText(JSONObject obj, String key, String raw)
    {
        Double n = InvestorBriefJson.parseNumber(raw);
        if (n != null) obj.put(key, (double) n);
        else obj.put(key, safe(raw));
    }

    private static String val(Map<String, String[][]> in, String key, int idx)
    {
        return cell(in.get(key), idx);
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
