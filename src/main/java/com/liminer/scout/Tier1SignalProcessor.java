package com.liminer.scout;

import com.liminer.core.CRMFieldRegistry;
import com.liminer.core.SessionContext;
import com.liminer.intake.InteractionSignalExtractor;
import com.liminer.sheets.SheetsApp;
import com.liminer.sheets.SnapshotStore;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.json.JSONArray;
import org.json.JSONObject;

/*
 * Tier1SignalProcessor — the unified, deterministic priority signal layer
 * (priorityscoringv2). It reads the MATERIALIZED outputs of existing workflows
 * (Market Intelligence JSON leaves, Interaction Records, identity ids, background
 * check, status) and writes:
 *
 *   Priority Signal JSON  (machine, right of divider)  — the full Tier-1 vector
 *   Priority Signal Date  (machine)                    — staleness/eligibility gate
 *   Strategic Value       (human)  0-100               — g(capacity, fit, identity)
 *   Action Urgency        (human)  0-100               — f(owesReply, commitments, stage, days, timing)
 *   Priority Reason        (human) text                — deterministic top-signal fallback
 *
 * No fresh LLM call at Tier 1. Orthogonal axes: value and urgency never multiply,
 * so a soft timing read can never zero a fund (priorityscoringv2 §5). Unknown maps
 * to NEUTRAL, never 0. Column-by-column reads/writes only — see README
 * "Sheets I/O" for why a rectangular range write is never safe here.
 */
public class Tier1SignalProcessor
{
    private static final int MAX_CRM_ROWS   = 500;
    private static final int MAX_COLUMNS    = 220;
    private static final int MAX_ROWS_BATCH = 100;
    private static final int JSON_MAX       = 49_000;

    private static final String SNAP_STRATEGIC = "strategicValue";
    private static final String SNAP_URGENCY    = "actionUrgency";
    private static final int    TIER_WIDTH      = 20;   // hysteresis tier band

    public static String runTier1Signals(SessionContext context0) throws Exception
    {
        return runTier1Signals(context0, MAX_ROWS_BATCH);
    }

    public static String runTier1Signals(SessionContext context0, int maxRows0) throws Exception
    {
        if (context0 == null || context0.config == null)
        {
            return "ERROR: Missing session context or config.";
        }

        String spreadsheetId = context0.config.spreadsheetId;
        String tabName       = context0.config.mainTabName;
        int    headerRow     = context0.config.mainTabHeaderRow;
        int    dataStartRow  = context0.config.mainTabDataStartRow;

        System.out.println("[Tier1SignalProcessor] Starting Tier-1 priority signal run...");

        HashMap<String, Integer> headerMap = SheetsApp.buildHeaderMap(
            spreadsheetId, tabName, headerRow, MAX_COLUMNS);

        CRMFieldRegistry.ensurePrioritySignalColumns(
            context0, spreadsheetId, tabName, headerRow, headerMap);

        headerMap = SheetsApp.buildHeaderMap(spreadsheetId, tabName, headerRow, MAX_COLUMNS);

        // Resolve columns via config header -> position (never hardcoded headers).
        int fundNameCol   = col(context0, headerMap, "mainTabFundNameCol");
        int miJsonCol     = col(context0, headerMap, "mainTabMarketIntelligenceJsonCol");
        int crdCol        = col(context0, headerMap, "mainTabCrdNumberCol");
        int cikCol        = col(context0, headerMap, "mainTabCikNumberCol");
        int leiCol        = col(context0, headerMap, "mainTabLeiCol");
        int einCol        = col(context0, headerMap, "mainTabEinCol");
        int idStatusCol   = col(context0, headerMap, "mainTabIdentityStatusCol");
        int interRecCol   = col(context0, headerMap, "mainTabInteractionRecordsCol");
        int convStatusCol = col(context0, headerMap, "mainTabStatusCol");
        int lastContactCol = col(context0, headerMap, "mainTabLastContactDateCol");
        int commitmentsCol = col(context0, headerMap, "mainTabOutstandingCommitmentsCol");
        int bgStatusCol   = col(context0, headerMap, "mainTabBackgroundCheckStatusCol");
        int prioDateCol   = col(context0, headerMap, "mainTabPrioritySignalDateCol");

        int outJsonCol      = col(context0, headerMap, "mainTabPrioritySignalJsonCol");
        int outDateCol      = col(context0, headerMap, "mainTabPrioritySignalDateCol");
        int outStrategicCol = col(context0, headerMap, "mainTabStrategicValueCol");
        int outUrgencyCol   = col(context0, headerMap, "mainTabActionUrgencyCol");
        int outReasonCol    = col(context0, headerMap, "mainTabPriorityReasonCol");

        if (fundNameCol < 1 || outJsonCol < 1 || outStrategicCol < 1
            || outUrgencyCol < 1 || outReasonCol < 1 || outDateCol < 1)
        {
            return "ERROR: One or more Tier-1 input/output columns could not be resolved.";
        }

        // Column-by-column reads.
        String[][] fundNameData   = readCol(spreadsheetId, tabName, dataStartRow, fundNameCol);
        String[][] miJsonData      = readCol(spreadsheetId, tabName, dataStartRow, miJsonCol);
        String[][] crdData         = readCol(spreadsheetId, tabName, dataStartRow, crdCol);
        String[][] cikData         = readCol(spreadsheetId, tabName, dataStartRow, cikCol);
        String[][] leiData         = readCol(spreadsheetId, tabName, dataStartRow, leiCol);
        String[][] einData         = readCol(spreadsheetId, tabName, dataStartRow, einCol);
        String[][] idStatusData    = readCol(spreadsheetId, tabName, dataStartRow, idStatusCol);
        String[][] interRecData    = readCol(spreadsheetId, tabName, dataStartRow, interRecCol);
        String[][] convStatusData  = readCol(spreadsheetId, tabName, dataStartRow, convStatusCol);
        String[][] lastContactData = readCol(spreadsheetId, tabName, dataStartRow, lastContactCol);
        String[][] commitmentsData = readCol(spreadsheetId, tabName, dataStartRow, commitmentsCol);
        String[][] bgStatusData    = readCol(spreadsheetId, tabName, dataStartRow, bgStatusCol);
        String[][] prioDateData    = readCol(spreadsheetId, tabName, dataStartRow, prioDateCol);

        // Eligible rows: non-blank fund name AND never-computed (blank Priority Signal Date).
        LinkedHashMap<Integer, Integer> eligible = new LinkedHashMap<>();
        int scan = Math.max(fundNameData.length, prioDateData.length);
        int selected = 0;
        for (int i = 0; i < scan && selected < maxRows0; i++)
        {
            if (isBlank(cell(fundNameData, i))) continue;
            if (!isBlank(cell(prioDateData, i))) continue;   // already computed
            eligible.put(dataStartRow + i, i);
            selected++;
        }

        if (eligible.isEmpty())
        {
            return "Tier-1 priority signals complete. No eligible rows found.";
        }
        System.out.println("[Tier1SignalProcessor] Eligible rows: " + eligible.size());

        // Snapshot store for hysteresis / EMA smoothing across runs.
        SnapshotStore snapshotStore = new SnapshotStore();
        try { snapshotStore.ensureSnapshotTab(spreadsheetId); }
        catch (Exception e) { System.err.println("[SnapshotStore] ensure failed: " + e.getMessage()); }

        LocalDate today = LocalDate.now();
        LinkedHashMap<Integer, RowSignal> results = new LinkedHashMap<>();

        for (Map.Entry<Integer, Integer> e : eligible.entrySet())
        {
            int sheetRow = e.getKey();
            int idx      = e.getValue();

            String fundName = cell(fundNameData, idx);

            RowSignal sig = computeRow(
                fundName,
                cell(miJsonData, idx),
                cell(crdData, idx), cell(cikData, idx), cell(leiData, idx), cell(einData, idx),
                cell(idStatusData, idx),
                cell(interRecData, idx), cell(convStatusData, idx),
                cell(lastContactData, idx), cell(commitmentsData, idx),
                cell(bgStatusData, idx),
                today);

            // Smoothing: blend with prior snapshot unless the raw crosses a tier band.
            String lpId = isBlank(fundName) ? ("row" + sheetRow) : fundName;
            sig.strategicValue = smooth(snapshotStore, spreadsheetId, lpId, SNAP_STRATEGIC, sig.strategicValueRaw);
            sig.actionUrgency  = smooth(snapshotStore, spreadsheetId, lpId, SNAP_URGENCY,   sig.actionUrgencyRaw);

            // Cache smoothed scores back into the JSON meta.
            sig.rebuildJson(today);

            snapshotStore.queue(lpId, SNAP_STRATEGIC, String.valueOf(sig.strategicValue), today.toString(), "");
            snapshotStore.queue(lpId, SNAP_URGENCY,   String.valueOf(sig.actionUrgency),  today.toString(), "");

            results.put(sheetRow, sig);
        }

        try { snapshotStore.flush(spreadsheetId); }
        catch (Exception ex) { System.err.println("[SnapshotStore] flush failed: " + ex.getMessage()); }

        writeResults(spreadsheetId, tabName, results,
            outJsonCol, outDateCol, outStrategicCol, outUrgencyCol, outReasonCol);

        return "Tier-1 priority signals complete. Rows processed: " + results.size() + ".";
    }

    // -----------------------------------------------------------------------
    // Per-row deterministic computation
    // -----------------------------------------------------------------------

    private static RowSignal computeRow(
        String fundName0, String miJson0,
        String crd0, String cik0, String lei0, String ein0, String idStatus0,
        String interRec0, String convStatus0, String lastContact0, String commitmentsText0,
        String bgStatus0, LocalDate today0)
    {
        RowSignal r = new RowSignal();

        // --- Strategic-value inputs from MI JSON leaves ---
        Capacity cap = new Capacity();
        Fit fit = new Fit();
        Timing timing = new Timing();
        int presentLeafCount = 0;

        try
        {
            if (!isBlank(miJson0))
            {
                JSONObject mi = new JSONObject(miJson0.trim());
                presentLeafCount += analyzeCapacity(mi.optJSONArray("resources"), cap);
                presentLeafCount += analyzeFit(mi.optJSONArray("fit"), fit);
                presentLeafCount += analyzeTiming(mi.optJSONArray("probability_now"), timing);
                JSONObject macro = mi.optJSONObject("macro");
                if (macro != null) timing.macroRegime = macro.optString("regime", "");
            }
        }
        catch (Exception ignore) { }

        boolean lowEvidence = presentLeafCount <= 1;

        // identityStrength.
        boolean hasReg = !isBlank(crd0) || !isBlank(cik0) || !isBlank(lei0) || !isBlank(ein0);
        String identityStrength = hasReg ? "strong"
            : (idStatus0 != null && idStatus0.toLowerCase().contains("partial") ? "partial" : "none");

        // --- Relationship / urgency inputs ---
        InteractionSignalExtractor.InteractionSignals is =
            InteractionSignalExtractor.extract(interRec0, convStatus0, lastContact0, today0);

        // Outstanding GP Commitments column can carry commitments even without records.
        int openCommitments = is.openCommitmentCount;
        if (openCommitments == 0 && !isBlank(commitmentsText0))
        {
            openCommitments = countCommitmentsText(commitmentsText0);
        }

        // --- backgroundFlag ---
        String backgroundFlag = mapBackground(bgStatus0);

        // --- Strategic Value = g(capacityTier, fitTier, identityStrength) ---
        int capacityPoints = capacityPoints(cap.tier);
        int fitPoints      = fitPoints(fit.tier);
        int identityBonus  = "strong".equals(identityStrength) ? 10
                            : ("partial".equals(identityStrength) ? 5 : 0);
        double strategic = capacityPoints + fitPoints + identityBonus;
        if (lowEvidence)
        {
            // Shrink 30% toward the neutral midpoint (~47) — sparse rows can't be extreme.
            strategic = strategic + (47.0 - strategic) * 0.30;
        }
        r.strategicValueRaw = (int) Math.round(clamp(strategic, 0, 100));

        // --- Action Urgency = f(owesReply, commitments, stage, daysSince, deploying) ---
        int urgency;
        if (is.rejected)
        {
            urgency = 0;   // Rejected / Do Not Contact -> drop
        }
        else
        {
            int base = is.owesReply ? 35 : 0;
            int commitments = Math.min(openCommitments, 3) * 10;
            int stageWeight = stageWeight(is.stage);
            int staleBoost = staleBoost(is.stage, is.daysSinceLastContact);
            int timingModifier = "Deploying".equals(timing.deployingSignal) ? 10
                               : ("BetweenFunds".equals(timing.deployingSignal) ? -5 : 0);
            urgency = (int) Math.round(clamp(base + commitments + stageWeight + staleBoost + timingModifier, 0, 100));
        }
        r.actionUrgencyRaw = urgency;

        // --- top reasons -> Priority Reason fallback ---
        ArrayList<String> reasons = new ArrayList<>();
        if (is.owesReply)
        {
            reasons.add(is.daysSinceLastInbound >= 0
                ? ("Owes a reply (" + is.daysSinceLastInbound + "d)") : "Owes a reply");
        }
        if (openCommitments > 0) reasons.add(openCommitments + " open commitment" + (openCommitments == 1 ? "" : "s"));
        if ("High".equals(fit.tier)) reasons.add("High fit");
        if (!"Unknown".equals(cap.tier)) reasons.add(cap.tier + " capacity");
        if ("Deploying".equals(timing.deployingSignal)) reasons.add("Appears to be deploying");
        if ("BetweenFunds".equals(timing.deployingSignal)) reasons.add("May be between funds — re-check");
        if (is.rejected) reasons.add("Rejected / do not contact");
        if (reasons.isEmpty()) reasons.add("No strong signals yet");
        r.topReasons = reasons;
        r.priorityReason = joinTop(reasons, 3);

        // --- assemble the signal JSON (source of truth) ---
        r.capacity = cap;
        r.fit = fit;
        r.timing = timing;
        r.identityStrength = identityStrength;
        r.lowEvidence = lowEvidence;
        r.backgroundFlag = backgroundFlag;
        r.interaction = is;
        r.openCommitmentCount = openCommitments;
        // strategic/actionUrgency will be finalized after smoothing; seed with raw.
        r.strategicValue = r.strategicValueRaw;
        r.actionUrgency  = r.actionUrgencyRaw;
        r.rebuildJson(today0);
        return r;
    }

    // -----------------------------------------------------------------------
    // MI-leaf analysis (read leaves, weight by provenance/confidence; §3, §5)
    // -----------------------------------------------------------------------

    private static int analyzeCapacity(JSONArray resources0, Capacity cap0)
    {
        cap0.tier = "Unknown";
        if (resources0 == null) return 0;
        int present = 0;
        JSONObject best = null;
        boolean bestRegulatory = false;
        double bestConf = -1;
        for (int i = 0; i < resources0.length(); i++)
        {
            JSONObject leaf = resources0.optJSONObject(i);
            if (leaf == null) continue;
            double conf = leaf.optDouble("confidence", 0);
            String val = leaf.optString("value", "");
            if (conf <= 0 || isBlank(val)) continue;
            present++;
            boolean reg = isRegulatory(leaf.optString("sourceUrl", ""));
            // Provenance ordering: regulatory beats non-regulatory; then higher confidence.
            if (best == null
                || (reg && !bestRegulatory)
                || (reg == bestRegulatory && conf > bestConf))
            {
                best = leaf; bestRegulatory = reg; bestConf = conf;
            }
        }
        if (best != null)
        {
            double amount = parseMoney(best.optString("value", ""));
            cap0.value = amount > 0 ? String.valueOf((long) amount) : "";
            cap0.tier = bucketCapacity(amount);
            cap0.confidence = bestConf;
            cap0.asOf = best.optString("asOfDate", "");
            cap0.source = best.optString("sourceUrl", "");
            cap0.provenance = bestRegulatory ? "regulatory_filing"
                : (isBlank(cap0.source) ? "inference" : "website");
        }
        return present;
    }

    private static int analyzeFit(JSONArray fit0, Fit out0)
    {
        out0.tier = "Unknown";
        if (fit0 == null) return 0;
        double sum = 0; int present = 0;
        for (int i = 0; i < fit0.length(); i++)
        {
            JSONObject leaf = fit0.optJSONObject(i);
            if (leaf == null) continue;
            double conf = leaf.optDouble("confidence", 0);
            if (conf <= 0 || isBlank(leaf.optString("value", ""))) continue;
            sum += conf; present++;
        }
        if (present > 0)
        {
            double mean = sum / present;
            out0.confidence = mean;
            out0.tier = mean >= 0.66 ? "High" : (mean >= 0.33 ? "Med" : "Low");
        }
        return present;
    }

    private static int analyzeTiming(JSONArray prob0, Timing out0)
    {
        out0.deployingSignal = "Unknown";
        if (prob0 == null) return 0;
        double sum = 0; int present = 0;
        for (int i = 0; i < prob0.length(); i++)
        {
            JSONObject leaf = prob0.optJSONObject(i);
            if (leaf == null) continue;
            double conf = leaf.optDouble("confidence", 0);
            if (conf <= 0 || isBlank(leaf.optString("value", ""))) continue;
            sum += conf; present++;
        }
        out0.corroboratingLeafCount = present;
        // Corroboration gate: only emit Deploying/BetweenFunds with >=2 leaves; never a hard 0.
        if (present >= 2)
        {
            double mean = sum / present;
            out0.deployingConfidence = mean;
            if (mean >= 0.5) out0.deployingSignal = "Deploying";
            else if (mean < 0.3) out0.deployingSignal = "BetweenFunds";
            else out0.deployingSignal = "Neutral";
        }
        return present;
    }

    // -----------------------------------------------------------------------
    // Composition weights (priorityscoringv2 §4)
    // -----------------------------------------------------------------------

    private static int capacityPoints(String tier)
    {
        if ("Mega".equals(tier)) return 50;
        if ("Large".equals(tier)) return 40;
        if ("Mid".equals(tier)) return 28;
        if ("Small".equals(tier)) return 15;
        return 22;   // Unknown = neutral, NOT 0
    }

    private static int fitPoints(String tier)
    {
        if ("High".equals(tier)) return 40;
        if ("Med".equals(tier)) return 24;
        if ("Low".equals(tier)) return 8;
        return 20;   // Unknown = neutral
    }

    private static int stageWeight(String stage)
    {
        if (InteractionSignalExtractor.STAGE_PROSPECTIVE_CLOSE.equalsIgnoreCase(nz(stage))) return 25;
        if (InteractionSignalExtractor.STAGE_MEETINGS.equalsIgnoreCase(nz(stage))) return 18;
        if (InteractionSignalExtractor.STAGE_FIRST_INTEREST.equalsIgnoreCase(nz(stage))) return 12;
        if (InteractionSignalExtractor.STAGE_REACHED_OUT.equalsIgnoreCase(nz(stage))) return 6;
        return 0;
    }

    // Advanced stage that has gone stale past its threshold -> re-engage boost.
    private static int staleBoost(String stage, int daysSince)
    {
        if (daysSince < 0) return 0;
        int rank = InteractionSignalExtractor.stageRank(stage);
        if (rank >= 3 && daysSince > 14) return 10;   // Meetings / Prospective Close gone quiet
        return 0;
    }

    private static String bucketCapacity(double amount)
    {
        if (amount <= 0) return "Unknown";
        if (amount >= 1_000_000_000d) return "Mega";
        if (amount >= 250_000_000d) return "Large";
        if (amount >= 50_000_000d) return "Mid";
        return "Small";
    }

    private static String mapBackground(String status)
    {
        if (isBlank(status)) return "unknown";
        String s = status.toLowerCase();
        if (s.contains("clear")) return "clear";
        if (s.contains("flag")) return "flagged";
        if (s.contains("review")) return "review";
        return "unknown";
    }

    // -----------------------------------------------------------------------
    // Smoothing (priorityscoringv2 §5.5)
    // -----------------------------------------------------------------------

    private static int smooth(SnapshotStore store, String ss, String lpId, String indicator, int raw)
    {
        try
        {
            List<SnapshotStore.SnapshotEntry> series = store.readSeries(ss, lpId, indicator);
            if (series == null || series.isEmpty()) return raw;
            String prevStr = series.get(series.size() - 1).value;
            if (isBlank(prevStr)) return raw;
            double prev = Double.parseDouble(prevStr.trim());
            // Decisive tier crossing -> adopt raw; else EMA 0.6*prev + 0.4*raw.
            if (Math.abs(raw - prev) >= TIER_WIDTH) return raw;
            return (int) Math.round(0.6 * prev + 0.4 * raw);
        }
        catch (Exception e)
        {
            return raw;
        }
    }

    // -----------------------------------------------------------------------
    // Column-by-column write (preserves unprocessed rows in the span)
    // -----------------------------------------------------------------------

    private static void writeResults(
        String ss, String tab, LinkedHashMap<Integer, RowSignal> results,
        int jsonCol, int dateCol, int strategicCol, int urgencyCol, int reasonCol) throws Exception
    {
        if (results.isEmpty()) return;
        int minRow = results.keySet().stream().mapToInt(Integer::intValue).min().getAsInt();
        int maxRow = results.keySet().stream().mapToInt(Integer::intValue).max().getAsInt();

        String[][] jsonData      = readColRange(ss, tab, minRow, maxRow, jsonCol);
        String[][] dateData      = readColRange(ss, tab, minRow, maxRow, dateCol);
        String[][] strategicData = readColRange(ss, tab, minRow, maxRow, strategicCol);
        String[][] urgencyData   = readColRange(ss, tab, minRow, maxRow, urgencyCol);
        String[][] reasonData    = readColRange(ss, tab, minRow, maxRow, reasonCol);

        for (Map.Entry<Integer, RowSignal> e : results.entrySet())
        {
            int i = e.getKey() - minRow;
            RowSignal r = e.getValue();
            jsonData[i][0]      = truncate(r.json, JSON_MAX);
            dateData[i][0]      = r.computedDate;
            strategicData[i][0] = String.valueOf(r.strategicValue);
            urgencyData[i][0]   = String.valueOf(r.actionUrgency);
            reasonData[i][0]    = truncate(r.priorityReason, 2000);
        }

        SheetsApp.updateRangeMatrix(ss, tab, minRow, jsonCol, jsonData);
        SheetsApp.updateRangeMatrix(ss, tab, minRow, dateCol, dateData);
        SheetsApp.updateRangeMatrix(ss, tab, minRow, strategicCol, strategicData);
        SheetsApp.updateRangeMatrix(ss, tab, minRow, urgencyCol, urgencyData);
        SheetsApp.updateRangeMatrix(ss, tab, minRow, reasonCol, reasonData);
    }

    // -----------------------------------------------------------------------
    // Signal value objects
    // -----------------------------------------------------------------------

    private static class Capacity
    {
        String tier = "Unknown";
        String value = "";
        double confidence = 0;
        String asOf = "";
        String source = "";
        String provenance = "inference";
    }

    private static class Fit
    {
        String tier = "Unknown";
        double confidence = 0;
    }

    private static class Timing
    {
        String deployingSignal = "Unknown";
        double deployingConfidence = 0;
        String macroRegime = "";
        int corroboratingLeafCount = 0;
    }

    private static class RowSignal
    {
        int strategicValueRaw = 0;
        int actionUrgencyRaw  = 0;
        int strategicValue    = 0;
        int actionUrgency     = 0;
        String priorityReason = "";
        ArrayList<String> topReasons = new ArrayList<>();
        Capacity capacity;
        Fit fit;
        Timing timing;
        String identityStrength = "none";
        boolean lowEvidence = false;
        String backgroundFlag = "unknown";
        InteractionSignalExtractor.InteractionSignals interaction;
        int openCommitmentCount = 0;
        String json = "{}";
        String computedDate = LocalDate.now().toString();

        void rebuildJson(LocalDate today)
        {
            this.computedDate = today.toString();
            JSONObject root = new JSONObject();
            root.put("asOfDate", today.toString());

            JSONObject sv = new JSONObject();
            sv.put("capacityTier", capacity != null ? capacity.tier : "Unknown");
            if (capacity != null && !isBlank(capacity.value)) sv.put("capacityValue", capacity.value);
            if (capacity != null) sv.put("capacityConfidence", round2(capacity.confidence));
            if (capacity != null && !isBlank(capacity.asOf)) sv.put("capacityAsOf", capacity.asOf);
            if (capacity != null && !isBlank(capacity.source)) sv.put("capacitySource", capacity.source);
            if (capacity != null) sv.put("capacityProvenance", capacity.provenance);
            sv.put("fitTier", fit != null ? fit.tier : "Unknown");
            if (fit != null) sv.put("fitConfidence", round2(fit.confidence));
            sv.put("identityStrength", identityStrength);
            sv.put("lowEvidence", lowEvidence);
            root.put("strategicValue", sv);

            JSONObject tm = new JSONObject();
            tm.put("deployingSignal", timing != null ? timing.deployingSignal : "Unknown");
            if (timing != null) tm.put("deployingConfidence", round2(timing.deployingConfidence));
            if (timing != null && !isBlank(timing.macroRegime)) tm.put("macroRegime", timing.macroRegime);
            if (timing != null) tm.put("corroboratingLeafCount", timing.corroboratingLeafCount);
            root.put("timing", tm);

            JSONObject rel = new JSONObject();
            if (interaction != null)
            {
                if (!isBlank(interaction.stage)) rel.put("stage", interaction.stage);
                rel.put("owesReply", interaction.owesReply);
                rel.put("daysSinceLastContact", interaction.daysSinceLastContact);
                rel.put("daysSinceLastInbound", interaction.daysSinceLastInbound);
                rel.put("openCommitmentCount", openCommitmentCount);
                if (!isBlank(interaction.lastSentiment)) rel.put("lastSentiment", interaction.lastSentiment);
                rel.put("rejected", interaction.rejected);
            }
            root.put("relationship", rel);

            JSONObject risk = new JSONObject();
            risk.put("backgroundFlag", backgroundFlag);
            root.put("risk", risk);

            JSONObject meta = new JSONObject();
            meta.put("strategicValueScore", strategicValue);
            meta.put("actionUrgencyScore", actionUrgency);
            JSONArray tr = new JSONArray();
            for (String s : topReasons) tr.put(s);
            meta.put("topReasons", tr);
            meta.put("computedAt", today.toString());
            root.put("meta", meta);

            String s = root.toString();
            this.json = s.length() > JSON_MAX ? s.substring(0, JSON_MAX) : s;
        }
    }

    // -----------------------------------------------------------------------
    // Small helpers
    // -----------------------------------------------------------------------

    private static int col(SessionContext ctx, HashMap<String, Integer> headerMap, String key)
    {
        String header = ctx.config.getCol(key);
        return isBlank(header) ? -1 : SheetsApp.findColumnInHeaderMap(headerMap, header);
    }

    private static boolean isRegulatory(String url)
    {
        if (url == null) return false;
        String u = url.toLowerCase();
        return u.contains("sec.gov") || u.contains("adviserinfo") || u.contains("gleif")
            || u.contains("propublica") || u.contains("irs.gov");
    }

    // Parse a money amount from a free-text leaf value ("$1.2B", "310,000,000", "AUM 45 million").
    private static double parseMoney(String s)
    {
        if (isBlank(s)) return 0;
        String t = s.toLowerCase().replace(",", "");
        java.util.regex.Matcher m = java.util.regex.Pattern
            .compile("([0-9]+(?:\\.[0-9]+)?)\\s*(billion|bn|b|million|mm|m|thousand|k)?")
            .matcher(t);
        double best = 0;
        while (m.find())
        {
            double num;
            try { num = Double.parseDouble(m.group(1)); } catch (Exception e) { continue; }
            String unit = m.group(2);
            double mult = 1;
            if (unit != null)
            {
                if (unit.startsWith("b")) mult = 1_000_000_000d;
                else if (unit.equals("million") || unit.equals("mm") || unit.equals("m")) mult = 1_000_000d;
                else if (unit.equals("thousand") || unit.equals("k")) mult = 1_000d;
            }
            double v = num * mult;
            if (v > best) best = v;
        }
        return best;
    }

    private static int countCommitmentsText(String text)
    {
        if (isBlank(text)) return 0;
        String[] parts = text.split("[\\n;,]+");
        int n = 0;
        for (String p : parts) { if (!p.trim().isEmpty()) n++; }
        return n;
    }

    private static String joinTop(List<String> reasons, int max)
    {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < reasons.size() && i < max; i++)
        {
            if (sb.length() > 0) sb.append("; ");
            sb.append(reasons.get(i));
        }
        return sb.toString();
    }

    private static String[][] readCol(String ss, String tab, int startRow, int col) throws Exception
    {
        if (col < 1) return new String[0][1];
        return SheetsApp.readRangeMatrix(ss, tab, startRow, col, startRow + MAX_CRM_ROWS - 1, col);
    }

    private static String[][] readColRange(String ss, String tab, int minRow, int maxRow, int col) throws Exception
    {
        int span = maxRow - minRow + 1;
        if (col < 1)
        {
            String[][] empty = new String[span][1];
            for (String[] r : empty) r[0] = "";
            return empty;
        }
        String[][] data = SheetsApp.readRangeMatrix(ss, tab, minRow, col, maxRow, col);
        // Normalize to full span with non-null cells.
        String[][] out = new String[span][1];
        for (int i = 0; i < span; i++)
        {
            out[i][0] = (data != null && i < data.length && data[i] != null && data[i].length > 0 && data[i][0] != null)
                ? data[i][0] : "";
        }
        return out;
    }

    private static double round2(double d) { return Math.round(d * 100.0) / 100.0; }
    private static double clamp(double v, double lo, double hi) { return Math.max(lo, Math.min(hi, v)); }
    private static String nz(String s) { return s == null ? "" : s; }
    private static String cell(String[][] col, int idx)
    {
        if (col == null || idx >= col.length || col[idx] == null || col[idx].length == 0) return "";
        return col[idx][0] == null ? "" : col[idx][0];
    }
    private static String truncate(String s, int max)
    {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max);
    }
    private static boolean isBlank(String s) { return s == null || s.trim().isEmpty(); }
}
