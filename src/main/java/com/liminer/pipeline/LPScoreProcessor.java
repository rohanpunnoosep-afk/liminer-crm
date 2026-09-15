package com.liminer.pipeline;

import com.liminer.billing.CostMeter;
import com.liminer.core.CRMFieldRegistry;
import com.liminer.core.LpContext;
import com.liminer.core.SessionContext;
import com.liminer.enrich.BrightDataZoneHealth;
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
 *   3. Rolls up per-leaf SCORES (magnitude) weighted by CONFIDENCE (trust) into
 *      three axis scores — never the confidences themselves, which measure source
 *      quality and say nothing about the LP:
 *        RESOURCES  — best-evidenced leaf wins outright (1A > 1B > 1C > 1D)
 *        FIT        — confidence-weighted mean of leaf scores
 *        PROB_NOW   — confidence-weighted mean × MacroContextModifier multiplier
 *      An axis where no leaf found anything is written BLANK, not 0, and the Intel
 *      Status column reports the coverage (COMPLETE / PARTIAL / NO_EVIDENCE).
 *   4. Writes five score columns + the Intelligence JSON blob column-by-column.
 *      The FIT cell passes through curveFit() on the way out — a display-only
 *      calibration that respaces the compressed raw range without reordering
 *      anything (see FIT_CURVE_RAW / FIT_CURVE_DISPLAY).
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

    /*
     * FIT display calibration curve.
     *
     * The raw FIT axis is a confidence-weighted mean of leaf scores, and those
     * leaves compress hard into the bottom of the 0..1 range: ThesisFitIndicator
     * divides matched tags by the LP's OWN tag count, so a well-enriched LP with
     * eleven sector tags that your thesis hits once scores 0.09 — and
     * ADVStrategyIndicator's deliberately neutral 0.50 drags any blend toward the
     * middle-low. The result was a Fit Score column where a genuinely good LP read
     * as a 9 and a strong one as a 34, which is unreadable as a 0-100 grade.
     *
     * These knots stretch the raw range the leaves actually occupy across the
     * range a GP reads. Interpolation between them is linear and the knots are
     * strictly increasing in both coordinates, so the curve is MONOTONE: it never
     * reorders two LPs, it only respaces them. Both ends are pinned (0 -> 0,
     * 1 -> 1) so "no alignment" can never present as partial fit.
     *
     * FIT_CURVE_RAW[i] maps to FIT_CURVE_DISPLAY[i]. Retune by editing these two
     * arrays — nothing else reads them, and because the curve is applied at the
     * write boundary only (see writeResultsToSheet), the Intelligence JSON keeps
     * the raw leaf scores and a retune needs no re-run of the indicators.
     */
    private static final double[] FIT_CURVE_RAW     = { 0.00, 0.09, 0.34, 1.00 };
    private static final double[] FIT_CURVE_DISPLAY = { 0.00, 0.45, 0.90, 1.00 };

    // Intel status values.
    private static final String STATUS_QUEUED    = "QUEUED";
    private static final String STATUS_RUNNING   = "RUNNING";
    private static final String STATUS_COMPLETE  = "COMPLETE";
    private static final String STATUS_FAILED    = "FAILED";
    // Coverage-bearing statuses: the run finished, but not every axis found evidence.
    // A score cell is left BLANK for an axis with no evidence, and these say so.
    private static final String STATUS_PARTIAL     = "PARTIAL";
    private static final String STATUS_NO_EVIDENCE = "NO_EVIDENCE";

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

        // A dead Bright Data zone yields scores built on no evidence at all. Writing
        // them would stamp the rows as scored and make them ineligible for a retry, so
        // leave the CRM alone and report the real cause instead.
        String zoneFault = BrightDataZoneHealth.faultSummary();
        if (zoneFault != null)
        {
            System.out.println("Skipping CRM write: " + zoneFault);
            return "LP market intelligence FAILED. " + zoneFault
                + " CRM left unchanged so these rows remain eligible for a retry.";
        }

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

        // ORDER IS LOAD-BEARING. RESOURCES must run before PROBABILITY_NOW, because
        // DealVelocityIndicator (a PROBABILITY_NOW leaf) queues this run's RAUM into
        // SnapshotStore for next run's year-over-year delta, and it reads that figure
        // off ctx. The carry-over below used to sit AFTER all three axes had run, so
        // ctx was still empty when DealVelocity looked — the RAUM series never
        // accumulated a single point and the leaf could never fire on any row, ever.
        List<IndicatorResult> resourcesResults = runAxis(ctx, cache, resources, hasInfo);
        carryResourcesIntoContext(ctx, resourcesResults);

        List<IndicatorResult> fitResults     = runAxis(ctx, cache, fit, hasInfo);
        List<IndicatorResult> probNowResults = runAxis(ctx, cache, probNow, hasInfo);
        carryFundCloseIntoContext(ctx, probNowResults);

        // Rollup. RESOURCES asks "how much capital is there", a single-fact question
        // with a best answer, so the best-evidenced leaf wins outright and the weaker
        // ones do not dilute it (a filed RAUM must not be averaged down by a headcount
        // guess). FIT and PROBABILITY_NOW are genuinely multi-signal, so they blend
        // every leaf, weighted by how much each is trusted.
        AxisRollup resourcesRollup = rollupBestSource(resourcesResults);
        AxisRollup fitRollup       = rollupWeightedMean(fitResults);
        AxisRollup probRollup      = rollupWeightedMean(probNowResults);

        result.hasResources = resourcesRollup.hasEvidence;
        result.hasFit       = fitRollup.hasEvidence;
        result.hasProbNow   = probRollup.hasEvidence;

        result.resourcesScore = resourcesRollup.score;
        result.fitScore       = fitRollup.score;
        result.probabilityNow = clamp01(probRollup.score * macro.multiplier);

        // Build the Intelligence JSON blob (<50k).
        result.intelligenceJson = buildIntelligenceJson(
            resourcesResults, fitResults, probNowResults, macro);

        result.lastIntelDate = LocalDate.now().toString();
        result.intelStatus = statusFor(result);
        return result;
    }

    /*
     * Intel status now reports COVERAGE, not just "the code finished". A row where
     * every leaf came back empty used to be written as COMPLETE with three zeros,
     * which reads as a confident verdict of "no money, no fit, no timing" when the
     * truth was "we found nothing". The score cells are left blank in that case and
     * this status says why.
     */
    private static String statusFor(RowResult r)
    {
        int axes = (r.hasResources ? 1 : 0) + (r.hasFit ? 1 : 0) + (r.hasProbNow ? 1 : 0);
        if (axes == 3) return STATUS_COMPLETE;
        if (axes == 0) return STATUS_NO_EVIDENCE;
        return STATUS_PARTIAL;
    }

    // Hand this run's RAUM to DealVelocityIndicator via ctx, for its snapshot series.
    private static void carryResourcesIntoContext(LpContext ctx, List<IndicatorResult> results)
    {
        for (IndicatorResult r : results)
        {
            if (r != null && r.isPresent() && "Raum".equals(r.indicator))
            {
                ctx.latestRaumValue     = r.value;
                ctx.latestRaumDate      = r.asOfDate;
                ctx.latestRaumSourceUrl = r.sourceUrl;
            }
        }
    }

    private static void carryFundCloseIntoContext(LpContext ctx, List<IndicatorResult> results)
    {
        for (IndicatorResult r : results)
        {
            if (r != null && r.isPresent() && "FundClose".equals(r.indicator))
            {
                ctx.latestFundCloseValue     = r.value;
                ctx.latestFundCloseDate      = r.asOfDate;
                ctx.latestFundCloseSourceUrl = r.sourceUrl;
            }
        }
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
                r.indicator = ind.name();
                out.add(r);
            }
            catch (Exception e)
            {
                System.err.println("[LPScoreProcessor] " + ind.name() + " failed: " + e.getMessage());
                IndicatorResult failed = IndicatorResult.empty(ind.axis());
                failed.indicator = ind.name();
                out.add(failed);
            }
        }
        return out;
    }

    /*
     * The outcome of rolling up one axis.
     *
     * hasEvidence is the field that fixes the "everything is 0" complaint: it
     * distinguishes "we measured this and it is low" from "no leaf found anything".
     * Only the first deserves a number in the sheet. Callers must check it before
     * treating score as meaningful.
     */
    private static class AxisRollup
    {
        boolean hasEvidence = false;
        double score = 0.0;        // 0..1 magnitude, meaningless unless hasEvidence
        double confidence = 0.0;   // trust in the leaf/leaves behind that score
    }

    /*
     * Single-fact rollup: the best-evidenced leaf wins outright.
     *
     * Used for RESOURCES, where the leaves are ranked sources answering ONE question
     * (1A filed RAUM > 1B filed 990 > 1C reported AUM > 1D headcount proxy). A firm
     * has one balance sheet; averaging a regulator filing against a LinkedIn size
     * band does not produce a better estimate of it, just a worse one.
     */
    private static AxisRollup rollupBestSource(List<IndicatorResult> results)
    {
        AxisRollup out = new AxisRollup();
        if (results == null) return out;

        IndicatorResult best = null;
        for (IndicatorResult r : results)
        {
            if (r == null || !r.isPresent()) continue;
            if (best == null
                || r.confidence > best.confidence
                || (r.confidence == best.confidence && r.score > best.score))
            {
                best = r;
            }
        }
        if (best == null) return out;

        out.hasEvidence = true;
        out.score = best.score;
        out.confidence = best.confidence;
        return out;
    }

    /*
     * Multi-signal rollup: confidence-weighted mean of the leaf SCORES.
     *
     * Used for FIT and PROBABILITY_NOW, where each leaf is an independent piece of a
     * composite judgement and more corroboration genuinely means more signal.
     *
     * The weighting is the crux of the original bug. This used to average the
     * CONFIDENCE values and write that as the score, so the axis reported how
     * trustworthy its sources were rather than what they said — a $50M LP and a $50B
     * LP with equally clean filings scored the same 90. Confidence now only decides
     * how loudly each leaf speaks; the score is what it says.
     */
    private static AxisRollup rollupWeightedMean(List<IndicatorResult> results)
    {
        AxisRollup out = new AxisRollup();
        if (results == null) return out;

        double weightedSum = 0.0;
        double weightTotal = 0.0;
        double confSum = 0.0;
        int count = 0;

        for (IndicatorResult r : results)
        {
            if (r == null || !r.isPresent()) continue;
            weightedSum += r.score * r.confidence;
            weightTotal += r.confidence;
            confSum += r.confidence;
            count++;
        }

        if (count == 0 || weightTotal <= 0.0) return out;

        out.hasEvidence = true;
        out.score = clamp01(weightedSum / weightTotal);
        out.confidence = confSum / count;
        return out;
    }

    private static double clamp01(double v)
    {
        if (v < 0.0) return 0.0;
        if (v > 1.0) return 1.0;
        return v;
    }

    /*
     * Map a raw 0..1 FIT axis score onto the display curve defined by
     * FIT_CURVE_RAW / FIT_CURVE_DISPLAY (piecewise-linear, monotone). Applied only
     * where the Fit Score cell is written — the rollup, the Intelligence JSON and
     * every leaf keep their raw values.
     */
    static double curveFit(double rawScore)
    {
        double raw = clamp01(rawScore);

        for (int i = 1; i < FIT_CURVE_RAW.length; i++)
        {
            if (raw > FIT_CURVE_RAW[i]) continue;

            double rawLo = FIT_CURVE_RAW[i - 1];
            double rawHi = FIT_CURVE_RAW[i];
            double span  = rawHi - rawLo;
            // Degenerate knot spacing would divide by zero; snap to the lower knot.
            if (span <= 0.0) return FIT_CURVE_DISPLAY[i - 1];

            double t = (raw - rawLo) / span;
            return clamp01(FIT_CURVE_DISPLAY[i - 1]
                + t * (FIT_CURVE_DISPLAY[i] - FIT_CURVE_DISPLAY[i - 1]));
        }

        return clamp01(FIT_CURVE_DISPLAY[FIT_CURVE_DISPLAY.length - 1]);
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
            // "indicator" says WHICH leaf produced (or missed) this. Without it an
            // all-empty axis is a wall of identical blank objects and there is no way
            // to tell which source failed.
            o.put("indicator", safe(r.indicator));
            o.put("value", truncate(safe(r.value), 2000));
            o.put("score", r.score);
            o.put("confidence", r.confidence);
            o.put("sourceUrl", safe(r.sourceUrl));
            o.put("asOfDate", safe(r.asOfDate));
            o.put("theme", safe(r.theme));
            // The leaf's own sentence about WHY it read the value it did. Dropping it
            // here was throwing away the only human-readable provenance the leaf
            // carries: the brief's citation labels are built from these fields, and
            // "Reported Aum Indicator: $2.4B" is a much weaker label than the same line
            // with "ADV Item 5.F, filed 2025-03-01" behind it. Bounded tightly because
            // every leaf pays into the one 49,000-char intelligence JSON cell.
            o.put("evidence", truncate(safe(r.evidence), 400));
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

            // Blank, not zero, for an axis with no evidence behind it. Writing 0
            // told the GP "this LP cannot spare a cent" when the truth was "no leaf
            // returned anything for this LP".
            resData[idx][0]  = r.hasResources ? String.format("%.0f", r.resourcesScore * 100) : "";
            fitData[idx][0]  = r.hasFit       ? String.format("%.0f", curveFit(r.fitScore) * 100) : "";
            probData[idx][0] = r.hasProbNow   ? String.format("%.0f", r.probabilityNow * 100) : "";
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
        int lastPopulated = lastPopulatedIndex(fundNameCol, intelStatusCol);
        for (int i = 0; i < maxRows && selected < maxRowsCap; i++)
        {
            String fundName = cell(fundNameCol, i);
            if (isBlank(fundName))
            {
                noteSkippedNoFundName(dataStartRow + i, i, lastPopulated);
                continue;
            }
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
        // Whether each axis actually found anything. False means the corresponding
        // score is meaningless and its cell must be written BLANK, not 0 — 0 is a
        // measured verdict ("this LP has almost nothing"), absence is not.
        boolean hasResources   = false;
        boolean hasFit         = false;
        boolean hasProbNow     = false;
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

    /*
     * Fund Name is this workflow's join key, so a row without one is passed over. It
     * used to happen silently, which read downstream as "that row had nothing to do"
     * -- an intake-created row whose fund could not be named simply vanished from the
     * run's counts. Name it in the output instead so the operator can fill the cell.
     *
     * Only rows inside the populated block are reported: the column arrays run to the
     * sheet's full height, and announcing every blank row past the data would bury the
     * one line that matters under hundreds of empty ones.
     */
    private static void noteSkippedNoFundName(int sheetRow0, int rowIndex0, int lastPopulatedIndex0)
    {
        if (rowIndex0 > lastPopulatedIndex0)
        {
            return;
        }

        System.out.println("  Skipping row " + sheetRow0 + ": no Fund Name (this workflow keys on it).");
    }

    /* Index of the last row with a value in any of the given columns, or -1 if none. */
    private static int lastPopulatedIndex(String[][]... columns0)
    {
        int last0 = -1;

        for (String[][] column0 : columns0)
        {
            if (column0 == null)
            {
                continue;
            }

            for (int i = 0; i < column0.length; i++)
            {
                if (!isBlank(cell(column0, i)))
                {
                    last0 = Math.max(last0, i);
                }
            }
        }

        return last0;
    }
}
