package com.liminer.pipeline;

import com.liminer.billing.CostMeter;
import com.liminer.core.CRMFieldRegistry;
import com.liminer.core.LpContext;
import com.liminer.core.SessionContext;
import com.liminer.enrich.ScrapeCache;
import com.liminer.indicators.Indicator;
import com.liminer.indicators.IndicatorRegistry;
import com.liminer.indicators.IndicatorResult;
import com.liminer.indicators.MacroContextModifier;
import com.liminer.scout.IdentityResolver;
import com.liminer.sheets.SheetsApp;
import com.liminer.sheets.SnapshotStore;

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
 * LPScoreProcessor — the market-intelligence rollup. Modeled EXACTLY on
 * LPEnrichmentProcessor.enrichLpRows: header map -> per-column reads ->
 * parallel row processing -> per-column writes.
 *
 * For each eligible LP row it:
 *   1. Resolves identity keys (CRD/CIK/LEI/EIN) once, then caches them.
 *   2. Runs all registered Indicator leaves (gated by confidence) in a
 *      fixed thread pool (BBC pattern).
 *   3. Rolls up per-leaf results into three axis scores:
 *        RESOURCES  — max confidence leaf (1A > 1B > 1C > 1D)
 *        FIT        — mean confidence, adjusted by GP profile alignment
 *        PROB_NOW   — mean confidence × MacroContextModifier multiplier
 *   4. Writes five score columns + the Intelligence JSON blob column-by-column.
 *   5. Flushes SnapshotStore queue single-threaded.
 *
 * Spreadsheet Rules: every write is column-by-column. No rectangle writes.
 * Cell values are truncated to 50,000 chars.
 */
public class LPScoreProcessor
{
    private static final int MAX_CRM_ROWS    = 500;
    private static final int MAX_COLUMNS     = 200;
    private static final int MAX_ROWS_BATCH  = 25;
    private static final int ROW_POOL_SIZE   = 8;
    private static final int INTEL_JSON_MAX  = 49_000;

    // Intel status values.
    private static final String STATUS_QUEUED    = "QUEUED";
    private static final String STATUS_RUNNING   = "RUNNING";
    private static final String STATUS_COMPLETE  = "COMPLETE";
    private static final String STATUS_FAILED    = "FAILED";

    // Column-update field indices (for the per-column write arrays).
    private static final int IDX_CRD         = 0;
    private static final int IDX_CIK         = 1;
    private static final int IDX_LEI         = 2;
    private static final int IDX_EIN         = 3;
    private static final int IDX_ID_STATUS   = 4;
    private static final int IDX_RESOURCES   = 5;
    private static final int IDX_FIT         = 6;
    private static final int IDX_PROB_NOW    = 7;
    private static final int IDX_LAST_DATE   = 8;
    private static final int IDX_STATUS      = 9;
    private static final int IDX_INTEL_JSON  = 10;
    private static final int FIELD_COUNT     = 11;

    // -----------------------------------------------------------------------
    // Public entry point
    // -----------------------------------------------------------------------

    public static String scoreLpRows(SessionContext context0) throws Exception
    {
        return scoreLpRows(context0, MAX_ROWS_BATCH);
    }

    public static String scoreLpRows(SessionContext context0, int maxRows0) throws Exception
    {
        if (context0 == null || context0.config == null)
        {
            return "ERROR: Missing session context or config.";
        }

        String spreadsheetId = context0.config.spreadsheetId;
        String tabName       = context0.config.mainTabName;
        int    headerRow     = context0.config.mainTabHeaderRow;
        int    dataStartRow  = context0.config.mainTabDataStartRow;

        System.out.println("[LPScoreProcessor] Starting LP market intelligence run...");

        // Step 1: build header map and ensure output columns exist.
        HashMap<String, Integer> headerMap = SheetsApp.buildHeaderMap(
            spreadsheetId, tabName, headerRow, MAX_COLUMNS);

        CRMFieldRegistry.ensureMarketIntelligenceColumns(
            context0, spreadsheetId, tabName, headerRow, headerMap);

        // Re-read after provisioning (new columns may have shifted).
        headerMap = SheetsApp.buildHeaderMap(
            spreadsheetId, tabName, headerRow, MAX_COLUMNS);

        // Step 2: resolve all needed column numbers.
        int[] inputCols  = resolveInputColumns(context0, headerMap);
        int[] outputCols = resolveOutputColumns(context0, headerMap);

        if (hasMissingOutputColumn(outputCols))
        {
            return "ERROR: One or more score output columns could not be resolved.";
        }

        // Step 3: read each input column separately (no rectangles).
        String[][] intelStatusCol = readCol(spreadsheetId, tabName,
            dataStartRow, inputCols[IN_INTEL_STATUS]);
        String[][] fundNameCol    = readCol(spreadsheetId, tabName,
            dataStartRow, inputCols[IN_FUND_NAME]);
        String[][] websiteCol     = readCol(spreadsheetId, tabName,
            dataStartRow, inputCols[IN_WEBSITE]);
        String[][] cityCol        = readCol(spreadsheetId, tabName,
            dataStartRow, inputCols[IN_CITY]);
        String[][] countryCol     = readCol(spreadsheetId, tabName,
            dataStartRow, inputCols[IN_COUNTRY]);
        String[][] c1FirstCol     = readCol(spreadsheetId, tabName,
            dataStartRow, inputCols[IN_C1_FIRST]);
        String[][] c1LastCol      = readCol(spreadsheetId, tabName,
            dataStartRow, inputCols[IN_C1_LAST]);
        String[][] c1PositionCol  = readCol(spreadsheetId, tabName,
            dataStartRow, inputCols[IN_C1_POSITION]);
        String[][] c1LinkedInCol  = readCol(spreadsheetId, tabName,
            dataStartRow, inputCols[IN_C1_LINKEDIN]);
        String[][] c2FirstCol     = readCol(spreadsheetId, tabName,
            dataStartRow, inputCols[IN_C2_FIRST]);
        String[][] c2LastCol      = readCol(spreadsheetId, tabName,
            dataStartRow, inputCols[IN_C2_LAST]);
        String[][] c2PositionCol  = readCol(spreadsheetId, tabName,
            dataStartRow, inputCols[IN_C2_POSITION]);
        String[][] compLinkedInCol = readCol(spreadsheetId, tabName,
            dataStartRow, inputCols[IN_COMP_LINKEDIN]);
        String[][] sectorTagsCol  = readCol(spreadsheetId, tabName,
            dataStartRow, inputCols[IN_SECTOR_TAGS]);
        String[][] microsectorCol = readCol(spreadsheetId, tabName,
            dataStartRow, inputCols[IN_MICROSECTOR]);
        String[][] geographyCol   = readCol(spreadsheetId, tabName,
            dataStartRow, inputCols[IN_GEOGRAPHY]);
        String[][] allocTypeCol   = readCol(spreadsheetId, tabName,
            dataStartRow, inputCols[IN_ALLOC_TYPE]);
        String[][] priorFundsCol  = readCol(spreadsheetId, tabName,
            dataStartRow, inputCols[IN_PRIOR_FUNDS]);
        String[][] lastEnrichedCol = readCol(spreadsheetId, tabName,
            dataStartRow, inputCols[IN_LAST_ENRICHED]);
        String[][] interHistCol   = readCol(spreadsheetId, tabName,
            dataStartRow, inputCols[IN_INTER_HIST]);
        String[][] interRecCol    = readCol(spreadsheetId, tabName,
            dataStartRow, inputCols[IN_INTER_REC]);
        String[][] convStatusCol  = readCol(spreadsheetId, tabName,
            dataStartRow, inputCols[IN_CONV_STATUS]);
        String[][] lastContactCol = readCol(spreadsheetId, tabName,
            dataStartRow, inputCols[IN_LAST_CONTACT]);
        String[][] crdCol         = readCol(spreadsheetId, tabName,
            dataStartRow, inputCols[IN_CRD]);
        String[][] cikCol         = readCol(spreadsheetId, tabName,
            dataStartRow, inputCols[IN_CIK]);
        String[][] leiCol         = readCol(spreadsheetId, tabName,
            dataStartRow, inputCols[IN_LEI]);
        String[][] einCol         = readCol(spreadsheetId, tabName,
            dataStartRow, inputCols[IN_EIN]);

        // Step 4: select eligible rows.
        LinkedHashMap<Integer, Integer> eligibleRows = selectEligibleRows(
            intelStatusCol, fundNameCol, dataStartRow, maxRows0);

        if (eligibleRows.isEmpty())
        {
            return "LP market intelligence complete. No eligible rows found.";
        }

        System.out.println("[LPScoreProcessor] Eligible rows: " + eligibleRows.size());

        // Step 5: compute MacroContextModifier ONCE before row loop.
        final ScrapeCache batchCache = new ScrapeCache();
        MacroContextModifier.MacroContext macro =
            MacroContextModifier.computeOnce(batchCache);
        System.out.println("[LPScoreProcessor] Macro modifier: "
            + macro.regimeTag + " / " + macro.multiplier);

        // Step 6: initialize shared SnapshotStore.
        SnapshotStore snapshotStore = new SnapshotStore();
        try { snapshotStore.ensureSnapshotTab(spreadsheetId); }
        catch (Exception e) { System.err.println("[SnapshotStore] ensure failed: " + e.getMessage()); }

        // Step 7: build GP profile from user account fields set at onboarding.
        LpContext.GpProfile gpProfile = new LpContext.GpProfile();
        if (context0.user != null)
        {
            gpProfile.fundName        = safe(context0.user.fundName);
            gpProfile.sectors         = safe(context0.user.clientSectorTags);
            gpProfile.microsectorTags = safe(context0.user.clientMicrosectorTags);
            gpProfile.geographies     = safe(context0.user.clientGeography);
            gpProfile.stages          = safe(context0.user.clientStages);
            gpProfile.investmentThesis = safe(context0.user.clientInvestmentThesis);
        }

        // Step 8: run rows in parallel (BBC pattern).
        ConcurrentHashMap<Integer, RowResult> resultsConcurrent = new ConcurrentHashMap<>();
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
                    LpContext ctx = buildLpContext(
                        sheetRow, localIdx,
                        fundNameCol, websiteCol, cityCol, countryCol,
                        c1FirstCol, c1LastCol, c1PositionCol, c1LinkedInCol,
                        c2FirstCol, c2LastCol, c2PositionCol,
                        compLinkedInCol, sectorTagsCol, microsectorCol,
                        geographyCol, allocTypeCol, priorFundsCol, lastEnrichedCol,
                        interHistCol, interRecCol, convStatusCol, lastContactCol,
                        crdCol, cikCol, leiCol, einCol,
                        gpProfile, snapshotStore, spreadsheetId);

                    RowResult rowResult = runIndicatorsForRow(ctx, batchCache, macro);
                    resultsConcurrent.put(sheetRow, rowResult);
                    completedCount.incrementAndGet();
                }
                catch (Exception e)
                {
                    System.err.println("[LPScoreProcessor] Row " + sheetRow + " failed: " + e.getMessage());
                    resultsConcurrent.put(sheetRow, failedRowResult());
                    failedCount.incrementAndGet();
                }
            })));
        }

        for (Future<?> f : futures)
        {
            try { f.get(); }
            catch (Exception e) { System.err.println("[LPScoreProcessor] Future error: " + e.getMessage()); }
        }
        pool.shutdown();

        // Step 9: sort results and flush snapshot queue (single-threaded).
        LinkedHashMap<Integer, RowResult> rowResults = new LinkedHashMap<>();
        ArrayList<Integer> sortedRows = new ArrayList<>(resultsConcurrent.keySet());
        java.util.Collections.sort(sortedRows);
        for (int rn : sortedRows) { rowResults.put(rn, resultsConcurrent.get(rn)); }

        try { snapshotStore.flush(spreadsheetId); }
        catch (Exception e) { System.err.println("[SnapshotStore] flush failed: " + e.getMessage()); }

        // Step 10: write results column-by-column (no rectangles).
        writeResultsToSheet(spreadsheetId, tabName, outputCols, rowResults, dataStartRow);

        return "LP market intelligence complete. Completed: " + completedCount.get()
            + ", Failed: " + failedCount.get() + ".";
    }

    // -----------------------------------------------------------------------
    // Row-level indicator run + rollup
    // -----------------------------------------------------------------------

    private static RowResult runIndicatorsForRow(
        LpContext ctx, ScrapeCache cache, MacroContextModifier.MacroContext macro)
    {
        RowResult result = new RowResult();
        result.identityKeys = ctx.identityKeys;

        List<Indicator> resources    = IndicatorRegistry.getByAxis(Indicator.AXIS_RESOURCES);
        List<Indicator> fit          = IndicatorRegistry.getByAxis(Indicator.AXIS_FIT);
        List<Indicator> probNow      = IndicatorRegistry.getByAxis(Indicator.AXIS_PROBABILITY_NOW);

        // Confidence gate: skip a row's leaves only when it is genuinely
        // low-information (no website AND no resolved identity). Resources
        // (ProPublica/SEC AUM/RAUM) and CRM-relationship Fit are identity-driven,
        // so gating them on website alone silently zeroed identity-only rows.
        boolean hasWebsite  = !isBlank(ctx.website);
        boolean hasIdentity = !isBlank(ctx.identityKeys.crd)
            || !isBlank(ctx.identityKeys.cik) || !isBlank(ctx.identityKeys.ein);
        boolean hasInfo = hasWebsite || hasIdentity;

        List<IndicatorResult> resourcesResults = runAxis(ctx, cache, resources, hasInfo);
        List<IndicatorResult> fitResults       = runAxis(ctx, cache, fit, hasInfo);
        List<IndicatorResult> probNowResults   = runAxis(ctx, cache, probNow, hasInfo);

        // Carry latest RAUM and FundClose into ctx for DealVelocity queuing.
        for (IndicatorResult r : resourcesResults)
        {
            if (r.isPresent() && "Raum".equals(indicatorNameOf(r, resources)))
            {
                ctx.latestRaumValue = r.value;
                ctx.latestRaumDate  = r.asOfDate;
                ctx.latestRaumSourceUrl = r.sourceUrl;
            }
        }
        for (IndicatorResult r : probNowResults)
        {
            if (r.isPresent() && isFundCloseResult(r))
            {
                ctx.latestFundCloseValue    = r.value;
                ctx.latestFundCloseDate     = r.asOfDate;
                ctx.latestFundCloseSourceUrl = r.sourceUrl;
            }
        }

        // Rollup.
        result.resourcesScore   = rollupAxis(resourcesResults);
        result.fitScore         = rollupAxis(fitResults);
        result.probabilityNow   = rollupAxis(probNowResults) * macro.multiplier;
        result.probabilityNow   = Math.min(1.0, Math.max(0.0, result.probabilityNow));

        // Build the Intelligence JSON blob (<50k).
        result.intelligenceJson = buildIntelligenceJson(
            resourcesResults, fitResults, probNowResults, macro);

        result.lastIntelDate = LocalDate.now().toString();
        result.intelStatus = STATUS_COMPLETE;
        return result;
    }

    private static List<IndicatorResult> runAxis(
        LpContext ctx, ScrapeCache cache, List<Indicator> indicators, boolean run)
    {
        List<IndicatorResult> out = new ArrayList<>();
        if (!run) return out;
        for (Indicator ind : indicators)
        {
            try
            {
                IndicatorResult r = ind.fetch(ctx, cache);
                if (r == null) r = IndicatorResult.empty(ind.axis());
                out.add(r);
            }
            catch (Exception e)
            {
                System.err.println("[LPScoreProcessor] " + ind.name() + " failed: " + e.getMessage());
                out.add(IndicatorResult.empty(ind.axis()));
            }
        }
        return out;
    }

    private static double rollupAxis(List<IndicatorResult> results)
    {
        if (results == null || results.isEmpty()) return 0.0;
        double sum = 0.0;
        int count = 0;
        for (IndicatorResult r : results)
        {
            if (r != null && r.isPresent()) { sum += r.confidence; count++; }
        }
        return count > 0 ? sum / count : 0.0;
    }

    private static String buildIntelligenceJson(
        List<IndicatorResult> resources, List<IndicatorResult> fit,
        List<IndicatorResult> probNow, MacroContextModifier.MacroContext macro)
    {
        JSONObject root = new JSONObject();
        root.put("macro", macroToJson(macro));
        root.put("resources", leafsToJson(resources));
        root.put("fit", leafsToJson(fit));
        root.put("probability_now", leafsToJson(probNow));
        String json = root.toString();
        return json.length() > INTEL_JSON_MAX ? json.substring(0, INTEL_JSON_MAX) : json;
    }

    private static JSONObject macroToJson(MacroContextModifier.MacroContext m)
    {
        JSONObject o = new JSONObject();
        o.put("regime", safe(m.regimeTag));
        o.put("multiplier", m.multiplier);
        o.put("asOfDate", safe(m.asOfDate));
        return o;
    }

    private static JSONArray leafsToJson(List<IndicatorResult> results)
    {
        JSONArray arr = new JSONArray();
        for (IndicatorResult r : results)
        {
            if (r == null) continue;
            JSONObject o = new JSONObject();
            o.put("value", truncate(safe(r.value), 2000));
            o.put("confidence", r.confidence);
            o.put("sourceUrl", safe(r.sourceUrl));
            o.put("asOfDate", safe(r.asOfDate));
            o.put("theme", safe(r.theme));
            arr.put(o);
        }
        return arr;
    }

    // -----------------------------------------------------------------------
    // Column-by-column write
    // -----------------------------------------------------------------------

    private static void writeResultsToSheet(
        String spreadsheetId, String tabName, int[] outputCols,
        LinkedHashMap<Integer, RowResult> rowResults, int dataStartRow) throws Exception
    {
        if (rowResults.isEmpty()) return;

        int minRow = rowResults.keySet().stream().mapToInt(Integer::intValue).min().getAsInt();
        int maxRow = rowResults.keySet().stream().mapToInt(Integer::intValue).max().getAsInt();
        int span   = maxRow - minRow + 1;

        // Allocate per-column arrays.
        String[][] crdData     = readColRange(spreadsheetId, tabName, minRow, maxRow, outputCols[IDX_CRD]);
        String[][] cikData     = readColRange(spreadsheetId, tabName, minRow, maxRow, outputCols[IDX_CIK]);
        String[][] leiData     = readColRange(spreadsheetId, tabName, minRow, maxRow, outputCols[IDX_LEI]);
        String[][] einData     = readColRange(spreadsheetId, tabName, minRow, maxRow, outputCols[IDX_EIN]);
        String[][] idStatData  = readColRange(spreadsheetId, tabName, minRow, maxRow, outputCols[IDX_ID_STATUS]);
        String[][] resData     = new String[span][1];
        String[][] fitData     = new String[span][1];
        String[][] probData    = new String[span][1];
        String[][] dateData    = new String[span][1];
        String[][] statusData  = new String[span][1];
        String[][] jsonData    = new String[span][1];

        for (Map.Entry<Integer, RowResult> entry : rowResults.entrySet())
        {
            int idx = entry.getKey() - minRow;
            RowResult r = entry.getValue();

            if (r.identityKeys != null)
            {
                if (!isBlank(r.identityKeys.crd)) crdData[idx][0] = r.identityKeys.crd;
                if (!isBlank(r.identityKeys.cik)) cikData[idx][0] = r.identityKeys.cik;
                if (!isBlank(r.identityKeys.lei)) leiData[idx][0] = r.identityKeys.lei;
                if (!isBlank(r.identityKeys.ein)) einData[idx][0] = r.identityKeys.ein;
                if (!isBlank(r.identityKeys.status)) idStatData[idx][0] = r.identityKeys.status;
            }

            resData[idx][0]    = String.format("%.0f", r.resourcesScore * 100);
            fitData[idx][0]    = String.format("%.0f", r.fitScore * 100);
            probData[idx][0]   = String.format("%.0f", r.probabilityNow * 100);
            dateData[idx][0]   = safe(r.lastIntelDate);
            statusData[idx][0] = safe(r.intelStatus);
            jsonData[idx][0]   = truncate(safe(r.intelligenceJson), INTEL_JSON_MAX);
        }

        SheetsApp.updateRangeMatrix(spreadsheetId, tabName, minRow, outputCols[IDX_CRD], crdData);
        SheetsApp.updateRangeMatrix(spreadsheetId, tabName, minRow, outputCols[IDX_CIK], cikData);
        SheetsApp.updateRangeMatrix(spreadsheetId, tabName, minRow, outputCols[IDX_LEI], leiData);
        SheetsApp.updateRangeMatrix(spreadsheetId, tabName, minRow, outputCols[IDX_EIN], einData);
        SheetsApp.updateRangeMatrix(spreadsheetId, tabName, minRow, outputCols[IDX_ID_STATUS], idStatData);
        SheetsApp.updateRangeMatrix(spreadsheetId, tabName, minRow, outputCols[IDX_RESOURCES], resData);
        SheetsApp.updateRangeMatrix(spreadsheetId, tabName, minRow, outputCols[IDX_FIT], fitData);
        SheetsApp.updateRangeMatrix(spreadsheetId, tabName, minRow, outputCols[IDX_PROB_NOW], probData);
        SheetsApp.updateRangeMatrix(spreadsheetId, tabName, minRow, outputCols[IDX_LAST_DATE], dateData);
        SheetsApp.updateRangeMatrix(spreadsheetId, tabName, minRow, outputCols[IDX_STATUS], statusData);
        SheetsApp.updateRangeMatrix(spreadsheetId, tabName, minRow, outputCols[IDX_INTEL_JSON], jsonData);
    }

    // -----------------------------------------------------------------------
    // LpContext construction
    // -----------------------------------------------------------------------

    private static LpContext buildLpContext(
        int sheetRow, int localIdx,
        String[][] fundNameCol, String[][] websiteCol, String[][] cityCol, String[][] countryCol,
        String[][] c1FirstCol, String[][] c1LastCol, String[][] c1PosCol, String[][] c1LiCol,
        String[][] c2FirstCol, String[][] c2LastCol, String[][] c2PosCol,
        String[][] compLiCol, String[][] sectorCol, String[][] microsectorCol,
        String[][] geoCol, String[][] allocTypeCol, String[][] priorFundsCol,
        String[][] lastEnrichedCol, String[][] interHistCol, String[][] interRecCol,
        String[][] convStatusCol, String[][] lastContactCol,
        String[][] crdCol, String[][] cikCol, String[][] leiCol, String[][] einCol,
        LpContext.GpProfile gpProfile, SnapshotStore snapshotStore, String spreadsheetId)
    {
        LpContext ctx = new LpContext();
        ctx.crmRowNumber = sheetRow;
        ctx.fundName = cell(fundNameCol, localIdx);
        ctx.website  = cell(websiteCol, localIdx);
        ctx.address  = cell(cityCol, localIdx) + " " + cell(countryCol, localIdx);

        // Contacts.
        String c1First = cell(c1FirstCol, localIdx);
        String c1Last  = cell(c1LastCol, localIdx);
        String c1Pos   = cell(c1PosCol, localIdx);
        String c1Li    = cell(c1LiCol, localIdx);
        if (!isBlank(c1First) || !isBlank(c1Last))
        {
            ctx.contacts.add(new LpContext.Contact(c1First, c1Last, c1Pos, c1Li));
        }
        String c2First = cell(c2FirstCol, localIdx);
        String c2Last  = cell(c2LastCol, localIdx);
        String c2Pos   = cell(c2PosCol, localIdx);
        if (!isBlank(c2First) || !isBlank(c2Last))
        {
            ctx.contacts.add(new LpContext.Contact(c2First, c2Last, c2Pos, ""));
        }

        ctx.companyLinkedInUrl = cell(compLiCol, localIdx);
        ctx.sectorTags         = cell(sectorCol, localIdx);
        ctx.microsectorTags    = cell(microsectorCol, localIdx);
        ctx.geography          = cell(geoCol, localIdx);
        ctx.allocatorType      = cell(allocTypeCol, localIdx);
        ctx.priorBackedFunds   = cell(priorFundsCol, localIdx);
        ctx.lastEnrichedAt     = cell(lastEnrichedCol, localIdx);
        ctx.interactionHistory = cell(interHistCol, localIdx);
        ctx.interactionRecordsJson = cell(interRecCol, localIdx);
        ctx.conversationStatus = cell(convStatusCol, localIdx);
        ctx.lastContactDate    = cell(lastContactCol, localIdx);

        // Pre-resolved identity keys (if already on the row — resolve-once).
        ctx.identityKeys = new IdentityResolver.IdentityKeys();
        ctx.identityKeys.crd = cell(crdCol, localIdx);
        ctx.identityKeys.cik = cell(cikCol, localIdx);
        ctx.identityKeys.lei = cell(leiCol, localIdx);
        ctx.identityKeys.ein = cell(einCol, localIdx);

        // Resolve identity if not yet done.
        if (isBlank(ctx.identityKeys.crd) && isBlank(ctx.identityKeys.cik)
            && isBlank(ctx.identityKeys.lei) && isBlank(ctx.identityKeys.ein))
        {
            try
            {
                ScrapeCache rowCache = new ScrapeCache(); // lightweight for identity
                IdentityResolver.IdentityKeys resolved =
                    new IdentityResolver().resolve(
                        ctx.fundName, ctx.website, ctx.address, rowCache);
                if (resolved != null) ctx.identityKeys = resolved;
            }
            catch (Exception e)
            {
                System.err.println("[LPScoreProcessor] Identity resolution failed for row "
                    + sheetRow + ": " + e.getMessage());
            }
        }

        ctx.gpProfile      = gpProfile;
        ctx.snapshotStore  = snapshotStore;
        ctx.spreadsheetId  = spreadsheetId;
        return ctx;
    }

    // -----------------------------------------------------------------------
    // Row selection
    // -----------------------------------------------------------------------

    private static LinkedHashMap<Integer, Integer> selectEligibleRows(
        String[][] intelStatusCol, String[][] fundNameCol, int dataStartRow, int maxRowsCap)
    {
        LinkedHashMap<Integer, Integer> rows = new LinkedHashMap<>();
        int maxRows = Math.max(
            intelStatusCol == null ? 0 : intelStatusCol.length,
            fundNameCol == null ? 0 : fundNameCol.length);
        int selected = 0;
        for (int i = 0; i < maxRows && selected < maxRowsCap; i++)
        {
            String fundName = cell(fundNameCol, i);
            if (isBlank(fundName)) continue;
            String status = cell(intelStatusCol, i);
            if (isBlank(status) || STATUS_QUEUED.equalsIgnoreCase(status.trim()))
            {
                rows.put(dataStartRow + i, i);
                selected++;
            }
        }
        return rows;
    }

    // -----------------------------------------------------------------------
    // Input column index constants
    // -----------------------------------------------------------------------

    private static final int IN_INTEL_STATUS = 0;
    private static final int IN_FUND_NAME    = 1;
    private static final int IN_WEBSITE      = 2;
    private static final int IN_CITY         = 3;
    private static final int IN_COUNTRY      = 4;
    private static final int IN_C1_FIRST     = 5;
    private static final int IN_C1_LAST      = 6;
    private static final int IN_C1_POSITION  = 7;
    private static final int IN_C1_LINKEDIN  = 8;
    private static final int IN_C2_FIRST     = 9;
    private static final int IN_C2_LAST      = 10;
    private static final int IN_C2_POSITION  = 11;
    private static final int IN_COMP_LINKEDIN = 12;
    private static final int IN_SECTOR_TAGS  = 13;
    private static final int IN_MICROSECTOR  = 14;
    private static final int IN_GEOGRAPHY    = 15;
    private static final int IN_ALLOC_TYPE   = 16;
    private static final int IN_PRIOR_FUNDS  = 17;
    private static final int IN_LAST_ENRICHED = 18;
    private static final int IN_INTER_HIST   = 19;
    private static final int IN_INTER_REC    = 20;
    private static final int IN_CONV_STATUS  = 21;
    private static final int IN_LAST_CONTACT = 22;
    private static final int IN_CRD          = 23;
    private static final int IN_CIK          = 24;
    private static final int IN_LEI          = 25;
    private static final int IN_EIN          = 26;
    private static final int IN_FIELD_COUNT  = 27;

    private static int[] resolveInputColumns(
        SessionContext ctx, HashMap<String, Integer> headerMap)
    {
        String[] keys = {
            ctx.config.getCol("mainTabIntelStatusCol"),
            ctx.config.getCol("mainTabFundNameCol"),
            ctx.config.getCol("mainTabWebsiteCol"),
            ctx.config.getCol("mainTabCityCol"),
            ctx.config.getCol("mainTabCountryCol"),
            ctx.config.getCol("mainTabContact1FirstNameCol"),
            ctx.config.getCol("mainTabContact1LastNameCol"),
            ctx.config.getCol("mainTabContact1PositionCol"),
            ctx.config.getCol("mainTabContactLinkedInCol"),
            ctx.config.getCol("mainTabContact2FirstNameCol"),
            ctx.config.getCol("mainTabContact2LastNameCol"),
            ctx.config.getCol("mainTabContact2PositionCol"),
            ctx.config.getCol("mainTabCompanyLinkedInCol"),
            ctx.config.getCol("mainTabSectorTagsCol"),
            ctx.config.getCol("mainTabMicrosectorTagsCol"),
            ctx.config.getCol("mainTabGeographyCol"),
            ctx.config.getCol("mainTabTypeOfInvestorCol"),
            ctx.config.getCol("mainTabPriorBackedFundsCol"),
            ctx.config.getCol("mainTabLastEnrichedAtCol"),
            ctx.config.getCol("mainTabInteractionHistoryCol"),
            ctx.config.getCol("mainTabInteractionRecordsCol"),
            ctx.config.getCol("mainTabStatusCol"),
            ctx.config.getCol("mainTabLastContactDateCol"),
            ctx.config.getCol("mainTabCrdNumberCol"),
            ctx.config.getCol("mainTabCikNumberCol"),
            ctx.config.getCol("mainTabLeiCol"),
            ctx.config.getCol("mainTabEinCol")
        };
        int[] cols = new int[IN_FIELD_COUNT];
        for (int i = 0; i < keys.length; i++)
        {
            cols[i] = keys[i] != null ? SheetsApp.findColumnInHeaderMap(headerMap, keys[i]) : -1;
        }
        return cols;
    }

    private static int[] resolveOutputColumns(
        SessionContext ctx, HashMap<String, Integer> headerMap)
    {
        String[] keys = {
            ctx.config.getCol("mainTabCrdNumberCol"),
            ctx.config.getCol("mainTabCikNumberCol"),
            ctx.config.getCol("mainTabLeiCol"),
            ctx.config.getCol("mainTabEinCol"),
            ctx.config.getCol("mainTabIdentityStatusCol"),
            ctx.config.getCol("mainTabResourcesScoreCol"),
            ctx.config.getCol("mainTabFitScoreCol"),
            ctx.config.getCol("mainTabProbabilityNowCol"),
            ctx.config.getCol("mainTabLastIntelDateCol"),
            ctx.config.getCol("mainTabIntelStatusCol"),
            ctx.config.getCol("mainTabMarketIntelligenceJsonCol")
        };
        int[] cols = new int[FIELD_COUNT];
        for (int i = 0; i < keys.length; i++)
        {
            cols[i] = keys[i] != null ? SheetsApp.findColumnInHeaderMap(headerMap, keys[i]) : -1;
        }
        return cols;
    }

    private static boolean hasMissingOutputColumn(int[] cols)
    {
        for (int c : cols) { if (c < 0) return true; }
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
        String spreadsheetId, String tabName,
        int minRow, int maxRow, int col) throws Exception
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
    // Helper data classes
    // -----------------------------------------------------------------------

    private static class RowResult
    {
        IdentityResolver.IdentityKeys identityKeys;
        double resourcesScore  = 0.0;
        double fitScore        = 0.0;
        double probabilityNow  = 0.0;
        String lastIntelDate   = LocalDate.now().toString();
        String intelStatus     = STATUS_FAILED;
        String intelligenceJson = "{}";
    }

    private static RowResult failedRowResult()
    {
        RowResult r = new RowResult();
        r.intelStatus = STATUS_FAILED;
        r.intelligenceJson = "{\"error\":\"row processing failed\"}";
        return r;
    }

    // -----------------------------------------------------------------------
    // Minor utilities
    // -----------------------------------------------------------------------

    private static String indicatorNameOf(IndicatorResult r, List<Indicator> indicators)
    {
        for (Indicator ind : indicators)
        {
            if (r.theme != null && r.theme.contains(ind.name())) return ind.name();
        }
        return "";
    }

    private static boolean isFundCloseResult(IndicatorResult r)
    {
        return r.evidence != null && r.evidence.toLowerCase().contains("fund")
            && r.evidence.toLowerCase().contains("close");
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
