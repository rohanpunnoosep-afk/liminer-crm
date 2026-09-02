package com.liminer.scout;

import com.liminer.core.CRMFieldRegistry;
import com.liminer.core.SessionContext;
import com.liminer.enrich.EmailFinder;
import com.liminer.sheets.SheetsApp;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.json.JSONArray;
import org.json.JSONObject;

/*
 * InvestorScoutProcessor — task 6 of 6, the orchestrator that wires the whole
 * Investor Scout pipeline together for one client run:
 *
 *   latest ScoutUniverseStore snapshot
 *     -> ScoutDedupeIndex (client CRM + Scout Ledger APPENDED entries)
 *     -> ScoutPrefilter.filter (allocator/resources-band/geography/dedupe)
 *     -> attach ScoutSignalScorer RESOURCES/PROBABILITY_NOW (cache-first)
 *     -> ScoutLedger eligibility gate (skip APPENDED/REJECTED_BY_USER, skip
 *        BELOW_THRESHOLD entries younger than 30 days unless probabilityNow
 *        rose)
 *     -> axis-minimum gate (drop candidates with a near-zero resources score
 *        before spending a Tier B LLM call on them)
 *     -> ScoutFitScorer.scoreFit (Tier A always, Tier B per allowLlm)
 *     -> rank by fit ONLY among candidates already clearing the axis minima
 *        above — resources / fit / probabilityNow are never collapsed into
 *        one number
 *     -> EmailFinder.findContacts for the top maxCandidates only
 *     -> CRM append (column-by-column, header-mapped, append-only, Cold)
 *     -> ScoutLedger upsert (APPENDED for what was written, BELOW_THRESHOLD
 *        for everything else that was actually scored this run)
 *
 * dryRun=true runs every read-only stage (including the ledger read, needed
 * for accurate would-be output) but performs zero Sheets writes: no CRM
 * append, no ledger upsert.
 */
public class InvestorScoutProcessor
{
    private static final int MAX_COLUMNS0 = 200;
    private static final int MAX_CRM_ROWS0 = 2000;

    // Axis-minimum floors applied BEFORE fit scoring (cost control: never spend
    // a Tier B LLM call on a candidate that can't plausibly fund anything).
    // probabilityNow has no floor here by design — 0 is a common, legitimate
    // state (no recent timing event yet); the ledger's re-surface rule is what
    // makes a newly-fresh probabilityNow visible again on a later run.
    private static final int MIN_RESOURCES_AXIS_THRESHOLD = 1;
    private static final int MIN_PROBABILITY_NOW_AXIS_THRESHOLD = 0;

    // SERP/LinkedIn is an explicitly SECONDARY channel (Phase 2 task 3): it
    // skips the ScoutPrefilter allocator/RAUM-band/geography gates entirely
    // (those need a regulatory filing this channel doesn't have), so the
    // RESOURCES and PROBABILITY_NOW axis gates below stand in for what the
    // prefilter would otherwise have enforced. Unlike the primary ADV funnel
    // (where a probabilityNow of 0 is a legitimate no-recent-event state), a
    // SERP hit must show a real resources signal above the score floor and a
    // non-fully-decayed timing event before it is worth the fit-scoring spend.
    private static final int SERP_MIN_RESOURCES_THRESHOLD = 15;
    private static final int SERP_MIN_PROBABILITY_NOW_THRESHOLD = 1;

    private static final int EMAIL_CONTACTS_PER_CANDIDATE = 2;

    // Contactless append bar (task 0085): a candidate with ZERO discovered
    // contacts is still worth a CRM row (as "contact pending") when it clears
    // BOTH axes at this high bar — the identity keys already written let a
    // later contact-backfill workflow find it. Below this bar, a contactless
    // candidate keeps the existing behavior (not appended).
    static final int NO_CONTACT_MIN_RESOURCES = 70;
    static final int NO_CONTACT_MIN_PROB_NOW = 60;

    static final String CONTACT_PENDING_TEXT = "contact pending";

    public static class RunParams
    {
        public int maxCandidates = 10;
        public int minScoreThreshold = 50;
        public boolean allowLlm = true;
        public boolean allowPaidEmail = false;
        public boolean dryRun = false;
        // Load ScoutUniverseIndexer's hot-tier file (records with zero exclusions
        // and resources/probabilityNow already clearing the hot-tier floors) by
        // default; a menu run that wants a deeper sweep sets this false to load
        // the full pre-scored/pre-tagged universe file instead (still an instant
        // read -- never re-derives scores per run).
        public boolean hotTierOnly = true;
    }

    private final ScoutUniverseStore universeStore0;
    private final ScoutUniverseIndexer universeIndexer0;
    private final ScoutSignalScorer signalScorer0;
    private final ScoutFitScorer fitScorer0;
    private final EmailFinder emailFinder0;
    private final ScoutLedger ledger0;

    public InvestorScoutProcessor()
    {
        this(new FileScoutUniverseStore(), new ScoutUniverseIndexer(), new ScoutSignalScorer(), new ScoutFitScorer(), new EmailFinder(), new ScoutLedger());
    }

    public InvestorScoutProcessor(
        ScoutUniverseStore universeStore0,
        ScoutUniverseIndexer universeIndexer0,
        ScoutSignalScorer signalScorer0,
        ScoutFitScorer fitScorer0,
        EmailFinder emailFinder0,
        ScoutLedger ledger0)
    {
        this.universeStore0 = universeStore0;
        this.universeIndexer0 = universeIndexer0;
        this.signalScorer0 = signalScorer0;
        this.fitScorer0 = fitScorer0;
        this.emailFinder0 = emailFinder0;
        this.ledger0 = ledger0;
    }

    public String run(SessionContext context0, RunParams params0) throws Exception
    {
        return run(context0, params0, new ArrayList<ScoutUniverseRecord>(), new HashMap<ScoutUniverseRecord, ScoutTimingEvents>());
    }

    /*
     * Overload that additionally merges pre-mapped SERP/LinkedIn secondary
     * candidates (see ScoutSerpAdapter) into the SAME funnel used for the
     * primary ADV universe: no separate append path, no bypass of the
     * RESOURCES/PROBABILITY_NOW gates (see gateSecondarySerpCandidates).
     * secondarySerpCandidates0 must already be mapped via ScoutSerpAdapter;
     * secondarySerpTimingEvents0 supplies each candidate's ScoutTimingEvents
     * by object identity (SERP records share crd=0, so they cannot be keyed
     * by crd).
     */
    public String run(
        SessionContext context0,
        RunParams params0,
        List<ScoutUniverseRecord> secondarySerpCandidates0,
        Map<ScoutUniverseRecord, ScoutTimingEvents> secondarySerpTimingEvents0) throws Exception
    {
        if (context0 == null || context0.config == null)
        {
            return "ERROR: Missing session context.";
        }

        if (!params0.dryRun)
        {
            ScoutWorkflowGate.requireEnabled("InvestorScoutProcessor run");
        }

        List<String> months0 = universeStore0.availableMonths();
        if (months0 == null || months0.isEmpty())
        {
            return "No ADV universe snapshot available. Run the monthly universe refresh first.";
        }

        String latestMonth0 = months0.get(months0.size() - 1);

        List<ScoutScoredRecord> scoredUniverse0 = params0.hotTierOnly
            ? universeIndexer0.loadHot(latestMonth0) : universeIndexer0.loadFull(latestMonth0);
        if (scoredUniverse0.isEmpty())
        {
            // Fall back to the full scored file (or, if that's also empty/missing,
            // to the raw snapshot) rather than silently running against nothing.
            scoredUniverse0 = universeIndexer0.loadFull(latestMonth0);
        }

        List<ScoutUniverseRecord> universe0 = new ArrayList<ScoutUniverseRecord>();
        if (scoredUniverse0.isEmpty())
        {
            universe0 = universeStore0.load(latestMonth0);
        }
        else
        {
            for (ScoutScoredRecord scored0 : scoredUniverse0)
            {
                if (scored0.exclusions == null || scored0.exclusions.isEmpty()) universe0.add(scored0.record);
            }
        }

        System.out.println("Loaded " + universe0.size() + " ADV universe records ("
            + (params0.hotTierOnly ? "hot tier" : "full scored universe") + ") for snapshot month " + latestMonth0 + ".");

        String spreadsheetId0 = context0.config.spreadsheetId;

        ScoutClientProfile clientProfile0 = ScoutClientProfile.fromUserAccount(context0.user);
        ScoutDedupeIndex dedupeIndex0 = buildDedupeIndexFromCrm(context0);

        ScoutPrefilter prefilter0 = new ScoutPrefilter();
        ScoutPrefilterResult prefilterResult0 = prefilter0.filter(universe0, clientProfile0, dedupeIndex0);

        System.out.println(prefilterResult0.toString());

        Map<Integer, ScoutSignalScore> cachedScores0 = signalScorer0.loadScores(latestMonth0);
        Map<String, ScoutLedger.LedgerEntry> ledgerEntries0 = ledger0.loadEntries(spreadsheetId0);

        LocalDate today0 = LocalDate.now();

        List<ScoutUniverseRecord> eligibleForFit0 = new ArrayList<ScoutUniverseRecord>();
        Map<ScoutUniverseRecord, ScoutSignalScore> signalScoreByRecord0 = new HashMap<ScoutUniverseRecord, ScoutSignalScore>();

        int skippedLedgerIneligible0 = 0;
        int skippedAxisMinimum0 = 0;

        for (ScoutUniverseRecord record0 : prefilterResult0.kept)
        {
            ScoutSignalScore score0 = cachedScores0.get(record0.crd);
            if (score0 == null)
            {
                score0 = signalScorer0.score(record0, new ScoutTimingEvents(), today0);
            }
            signalScoreByRecord0.put(record0, score0);

            ScoutLedger.LedgerEntry entry0 = ledgerEntries0.get(String.valueOf(record0.crd));
            if (!ScoutLedger.eligible(entry0, score0.probabilityNow, today0))
            {
                skippedLedgerIneligible0++;
                continue;
            }

            if (score0.resources < MIN_RESOURCES_AXIS_THRESHOLD || score0.probabilityNow < MIN_PROBABILITY_NOW_AXIS_THRESHOLD)
            {
                skippedAxisMinimum0++;
                continue;
            }

            eligibleForFit0.add(record0);
        }

        System.out.println(
            "Eligible for fit scoring: " + eligibleForFit0.size()
            + " | skipped (ledger cooldown/appended/rejected): " + skippedLedgerIneligible0
            + " | skipped (below resources/probabilityNow axis minimum): " + skippedAxisMinimum0
        );

        List<ScoutUniverseRecord> secondaryEligible0 = gateSecondarySerpCandidates(
            secondarySerpCandidates0, secondarySerpTimingEvents0, dedupeIndex0, signalScoreByRecord0, today0
        );
        eligibleForFit0.addAll(secondaryEligible0);

        System.out.println(
            "Secondary SERP candidates considered: " + (secondarySerpCandidates0 == null ? 0 : secondarySerpCandidates0.size())
            + " | eligible after resources/timing gates and dedupe: " + secondaryEligible0.size()
        );

        int tierBTopN0 = Math.max(params0.maxCandidates * 3, 20);
        List<ScoutFitResult> fitResults0 = fitScorer0.scoreFit(eligibleForFit0, clientProfile0, tierBTopN0, params0.allowLlm);

        List<ScoutFitResult> toAppend0 = new ArrayList<ScoutFitResult>();
        List<ScoutFitResult> belowThresholdOrCut0 = new ArrayList<ScoutFitResult>();

        for (ScoutFitResult result0 : fitResults0)
        {
            if (result0.fitScore >= params0.minScoreThreshold && toAppend0.size() < params0.maxCandidates)
            {
                toAppend0.add(result0);
            }
            else
            {
                belowThresholdOrCut0.add(result0);
            }
        }

        List<ScoutAppendRow> appendRows0 = new ArrayList<ScoutAppendRow>();
        int noContactAppendCount0 = 0;

        for (ScoutFitResult result0 : toAppend0)
        {
            ScoutSignalScore score0 = signalScoreByRecord0.get(result0.record);
            List<ScoutContact> contacts0 = emailFinder0.findContacts(result0.record, EMAIL_CONTACTS_PER_CANDIDATE, params0.allowPaidEmail);

            if (contacts0 == null || contacts0.isEmpty())
            {
                boolean canAppendWithoutContact0 = appendableWithoutContact(
                    score0 == null ? 0 : score0.resources,
                    score0 == null ? 0 : score0.probabilityNow
                );

                if (!canAppendWithoutContact0)
                {
                    belowThresholdOrCut0.add(result0);
                    continue;
                }

                ScoutAppendRow row0 = buildAppendRow(result0, score0, contacts0);
                row0.contact1Name = CONTACT_PENDING_TEXT;
                row0.noContact = true;
                row0.scoutEvidenceJson = buildScoutEvidenceJson(result0, score0, contacts0, true);
                appendRows0.add(row0);
                noContactAppendCount0++;
                continue;
            }

            appendRows0.add(buildAppendRow(result0, score0, contacts0));
        }

        if (params0.dryRun)
        {
            printDryRunPreview(appendRows0);
            return "DRY RUN complete. Would append " + appendRows0.size()
                + " candidates (" + noContactAppendCount0 + " contactless). Considered " + fitResults0.size() + " fit-scored candidates"
                + " (" + belowThresholdOrCut0.size() + " below threshold or beyond the cap). No Sheets writes performed.";
        }

        String appendResult0 = appendScoutCandidatesToCrm(context0, appendRows0);

        int appendedCount0 = 0;
        for (ScoutAppendRow row0 : appendRows0)
        {
            String outcome0 = row0.noContact ? ScoutLedger.OUTCOME_APPENDED_NO_CONTACT : ScoutLedger.OUTCOME_APPENDED;
            ledger0.upsert(spreadsheetId0, buildLedgerEntry(row0, today0, outcome0));
            if (!row0.noContact) appendedCount0++;
        }

        for (ScoutFitResult result0 : belowThresholdOrCut0)
        {
            ScoutSignalScore score0 = signalScoreByRecord0.get(result0.record);
            ledger0.upsert(spreadsheetId0, buildLedgerEntry(result0, score0, today0, ScoutLedger.OUTCOME_BELOW_THRESHOLD));
        }

        return appendResult0
            + " Ledger updated: " + appendedCount0 + " APPENDED, "
            + noContactAppendCount0 + " APPENDED_NO_CONTACT, "
            + belowThresholdOrCut0.size() + " BELOW_THRESHOLD.";
    }

    // -----------------------------------------------------------------------
    // SERP/LinkedIn secondary channel gate (Phase 2 task 3): ScoutPrefilter's
    // allocator/RAUM-band/geography gates are skipped for this channel (they
    // assume a regulatory filing this channel doesn't have), but dedupe still
    // applies and RESOURCES/PROBABILITY_NOW must both clear the SERP-specific
    // floors above before a candidate is allowed to reach fit scoring/append.
    // -----------------------------------------------------------------------

    public List<ScoutUniverseRecord> gateSecondarySerpCandidates(
        List<ScoutUniverseRecord> serpCandidates0,
        Map<ScoutUniverseRecord, ScoutTimingEvents> timingEventsByRecord0,
        ScoutDedupeIndex dedupeIndex0,
        Map<ScoutUniverseRecord, ScoutSignalScore> signalScoreByRecordOut0,
        LocalDate today0)
    {
        List<ScoutUniverseRecord> kept0 = new ArrayList<ScoutUniverseRecord>();
        if (serpCandidates0 == null)
        {
            return kept0;
        }

        for (ScoutUniverseRecord record0 : serpCandidates0)
        {
            if (record0 == null || isDuplicateInDedupeIndex(record0, dedupeIndex0))
            {
                continue;
            }

            ScoutTimingEvents events0 = timingEventsByRecord0 == null ? null : timingEventsByRecord0.get(record0);
            if (events0 == null)
            {
                events0 = new ScoutTimingEvents();
            }

            ScoutSignalScore score0 = signalScorer0.score(record0, events0, today0);

            if (score0.resources < SERP_MIN_RESOURCES_THRESHOLD || score0.probabilityNow < SERP_MIN_PROBABILITY_NOW_THRESHOLD)
            {
                continue;
            }

            if (signalScoreByRecordOut0 != null)
            {
                signalScoreByRecordOut0.put(record0, score0);
            }
            kept0.add(record0);
        }

        return kept0;
    }

    private static boolean isDuplicateInDedupeIndex(ScoutUniverseRecord record0, ScoutDedupeIndex dedupe0)
    {
        if (dedupe0 == null)
        {
            return false;
        }
        if (record0.crd > 0 && dedupe0.containsCrd(record0.crd))
        {
            return true;
        }
        if (dedupe0.containsFirmName(record0.firmName))
        {
            return true;
        }
        if (dedupe0.containsWebsite(record0.website))
        {
            return true;
        }
        return false;
    }

    // -----------------------------------------------------------------------
    // Dedupe index from the client CRM (identity keys + names + domains).
    // -----------------------------------------------------------------------

    private static ScoutDedupeIndex buildDedupeIndexFromCrm(SessionContext context0) throws Exception
    {
        ScoutDedupeIndex index0 = new ScoutDedupeIndex();

        if (context0 == null || context0.config == null)
        {
            return index0;
        }

        String spreadsheetId0 = context0.config.spreadsheetId;
        String crmTabName0 = context0.config.mainTabName;

        HashMap<String, Integer> headerMap0 = SheetsApp.buildHeaderMap(
            spreadsheetId0, crmTabName0, context0.config.mainTabHeaderRow, MAX_COLUMNS0
        );

        int fundCol0 = getColumn(headerMap0, context0.config.getCol("mainTabFundNameCol"));
        int websiteCol0 = getColumn(headerMap0, context0.config.getCol("mainTabWebsiteCol"));
        int crdCol0 = getColumn(headerMap0, context0.config.getCol("mainTabCrdNumberCol"));

        int startRow0 = context0.config.mainTabDataStartRow;
        int endRow0 = MAX_CRM_ROWS0;

        addColumnValuesToFirmNames(index0, spreadsheetId0, crmTabName0, startRow0, endRow0, fundCol0);
        addColumnValuesToWebsites(index0, spreadsheetId0, crmTabName0, startRow0, endRow0, websiteCol0);
        addColumnValuesToCrds(index0, spreadsheetId0, crmTabName0, startRow0, endRow0, crdCol0);

        return index0;
    }

    private static void addColumnValuesToFirmNames(
        ScoutDedupeIndex index0, String spreadsheetId0, String tabName0, int startRow0, int endRow0, int col0) throws Exception
    {
        if (col0 <= 0) return;
        String[][] data0 = SheetsApp.readRangeMatrix(spreadsheetId0, tabName0, startRow0, col0, endRow0, col0);
        if (data0 == null) return;
        for (String[] row0 : data0)
        {
            if (row0 != null && row0.length > 0 && row0[0] != null && row0[0].trim().length() > 0)
            {
                index0.addFirmName(row0[0].trim());
            }
        }
    }

    private static void addColumnValuesToWebsites(
        ScoutDedupeIndex index0, String spreadsheetId0, String tabName0, int startRow0, int endRow0, int col0) throws Exception
    {
        if (col0 <= 0) return;
        String[][] data0 = SheetsApp.readRangeMatrix(spreadsheetId0, tabName0, startRow0, col0, endRow0, col0);
        if (data0 == null) return;
        for (String[] row0 : data0)
        {
            if (row0 != null && row0.length > 0 && row0[0] != null && row0[0].trim().length() > 0)
            {
                index0.addWebsite(row0[0].trim());
            }
        }
    }

    private static void addColumnValuesToCrds(
        ScoutDedupeIndex index0, String spreadsheetId0, String tabName0, int startRow0, int endRow0, int col0) throws Exception
    {
        if (col0 <= 0) return;
        String[][] data0 = SheetsApp.readRangeMatrix(spreadsheetId0, tabName0, startRow0, col0, endRow0, col0);
        if (data0 == null) return;
        for (String[] row0 : data0)
        {
            if (row0 != null && row0.length > 0 && row0[0] != null && row0[0].trim().length() > 0)
            {
                try
                {
                    index0.addCrd(Integer.parseInt(row0[0].trim()));
                }
                catch (Exception ignored0)
                {
                    // non-numeric CRD cell; skip.
                }
            }
        }
    }

    // -----------------------------------------------------------------------
    // Append row assembly.
    // -----------------------------------------------------------------------

    private static class ScoutAppendRow
    {
        ScoutUniverseRecord record;
        ScoutSignalScore score;
        int fitScore;
        String contact1Name = "";
        String contact1Title = "";
        String contact1Email = "";
        String contact1LinkedIn = "";
        String contact2Name = "";
        String contact2Title = "";
        String contact2Email = "";
        String contact2LinkedIn = "";
        String whyNow = "";
        String intelligenceJson = "";
        String scoutEvidenceJson = "";
        boolean noContact = false;
    }

    /*
     * Pure gate (task 0085): a contactless candidate is still appended to the
     * CRM only when it clears BOTH axes at this high bar. See
     * NO_CONTACT_MIN_RESOURCES / NO_CONTACT_MIN_PROB_NOW.
     */
    static boolean appendableWithoutContact(int resources0, int probabilityNow0)
    {
        return resources0 >= NO_CONTACT_MIN_RESOURCES && probabilityNow0 >= NO_CONTACT_MIN_PROB_NOW;
    }

    private static ScoutAppendRow buildAppendRow(ScoutFitResult fitResult0, ScoutSignalScore score0, List<ScoutContact> contacts0)
    {
        ScoutAppendRow row0 = new ScoutAppendRow();
        row0.record = fitResult0.record;
        row0.score = score0;
        row0.fitScore = fitResult0.fitScore;

        if (contacts0 != null && contacts0.size() > 0)
        {
            ScoutContact c1 = contacts0.get(0);
            row0.contact1Name = c1.name;
            row0.contact1Title = c1.title;
            row0.contact1Email = formatContactEmail(c1);
            row0.contact1LinkedIn = c1.linkedinUrl;
        }

        if (contacts0 != null && contacts0.size() > 1)
        {
            ScoutContact c2 = contacts0.get(1);
            row0.contact2Name = c2.name;
            row0.contact2Title = c2.title;
            row0.contact2Email = formatContactEmail(c2);
            row0.contact2LinkedIn = c2.linkedinUrl;
        }

        row0.whyNow = buildWhyNow(score0);
        row0.intelligenceJson = buildIntelligenceJson(fitResult0, score0);
        row0.scoutEvidenceJson = buildScoutEvidenceJson(fitResult0, score0, contacts0);

        return row0;
    }

    private static String formatContactEmail(ScoutContact contact0)
    {
        if (contact0 == null || contact0.email == null || contact0.email.trim().length() == 0)
        {
            return "";
        }

        if (ScoutContact.CONFIDENCE_FIRM_LEVEL.equals(contact0.confidence))
        {
            return contact0.email.trim() + " (firm-level)";
        }

        return contact0.email.trim();
    }

    private static String buildWhyNow(ScoutSignalScore score0)
    {
        if (score0 == null || score0.reasons == null || score0.reasons.isEmpty())
        {
            return "";
        }

        StringBuilder sb0 = new StringBuilder();
        for (int i0 = 0; i0 < score0.reasons.size(); i0++)
        {
            if (i0 > 0) sb0.append(" ");
            sb0.append(score0.reasons.get(i0));
        }
        return sb0.toString();
    }

    private static String buildIntelligenceJson(ScoutFitResult fitResult0, ScoutSignalScore score0)
    {
        JSONObject root0 = fitResult0.record.toJson();
        JSONObject scout0 = new JSONObject();
        scout0.put("fitScore", fitResult0.fitScore);
        scout0.put("tierAScore", fitResult0.tierAScore);
        scout0.put("tierBScore", fitResult0.tierBScore == null ? JSONObject.NULL : fitResult0.tierBScore);
        scout0.put("matchedTerms", new JSONArray(fitResult0.matchedTerms == null ? new ArrayList<String>() : fitResult0.matchedTerms));
        scout0.put("rationale", fitResult0.rationale == null ? "" : fitResult0.rationale);
        scout0.put("profileSource", fitResult0.profileSource);
        scout0.put("resources", score0 == null ? 0 : score0.resources);
        scout0.put("probabilityNow", score0 == null ? 0 : score0.probabilityNow);
        root0.put("scout", scout0);
        return root0.toString();
    }

    private static String buildScoutEvidenceJson(ScoutFitResult fitResult0, ScoutSignalScore score0, List<ScoutContact> contacts0)
    {
        return buildScoutEvidenceJson(fitResult0, score0, contacts0, false);
    }

    private static String buildScoutEvidenceJson(ScoutFitResult fitResult0, ScoutSignalScore score0, List<ScoutContact> contacts0, boolean noContact0)
    {
        JSONObject evidence0 = new JSONObject();
        evidence0.put("finalScore", fitResult0.fitScore);
        if (noContact0)
        {
            evidence0.put("contactStatus", "NONE_FOUND");
        }

        JSONObject subscores0 = new JSONObject();
        subscores0.put("resources", score0 == null ? 0 : score0.resources);
        subscores0.put("fit", fitResult0.fitScore);
        subscores0.put("probabilityNow", score0 == null ? 0 : score0.probabilityNow);
        evidence0.put("subscores", subscores0);

        evidence0.put("whyNow", buildWhyNow(score0));

        JSONArray sources0 = new JSONArray();
        if (fitResult0.record != null && fitResult0.record.website != null && fitResult0.record.website.trim().length() > 0)
        {
            sources0.put(fitResult0.record.website.trim());
        }
        evidence0.put("sources", sources0);

        JSONArray emailSources0 = new JSONArray();
        if (contacts0 != null)
        {
            for (ScoutContact contact0 : contacts0)
            {
                if (contact0.source != null && contact0.source.trim().length() > 0)
                {
                    emailSources0.put(contact0.source.trim());
                }
            }
        }
        evidence0.put("emailSources", emailSources0);

        return limitForGoogleSheetsCell(evidence0.toString());
    }

    // -----------------------------------------------------------------------
    // CRM append — header map first, next append row via Fund Name column,
    // column-by-column writes only: a rectangular write spanning the append
    // range would overwrite untouched GP columns inside it.
    // -----------------------------------------------------------------------

    private static String appendScoutCandidatesToCrm(SessionContext context0, List<ScoutAppendRow> rows0) throws Exception
    {
        if (rows0 == null || rows0.size() == 0)
        {
            return "Investor Scout run complete. No new CRM-ready candidates to append.";
        }

        String spreadsheetId0 = context0.config.spreadsheetId;
        String crmTabName0 = context0.config.mainTabName;

        HashMap<String, Integer> headerMap0 = SheetsApp.buildHeaderMap(
            spreadsheetId0, crmTabName0, context0.config.mainTabHeaderRow, MAX_COLUMNS0
        );

        CRMFieldRegistry.ensureMarketIntelligenceColumns(context0, spreadsheetId0, crmTabName0, context0.config.mainTabHeaderRow, headerMap0);
        CRMFieldRegistry.ensureScoutEvidenceColumns(context0, spreadsheetId0, crmTabName0, context0.config.mainTabHeaderRow, headerMap0);

        int fundCol0 = getColumn(headerMap0, context0.config.getCol("mainTabFundNameCol"));
        if (fundCol0 <= 0)
        {
            return "ERROR: Could not find Fund Name column for CRM append.";
        }

        int nextRow0 = SheetsApp.findLastRow(spreadsheetId0, crmTabName0, fundCol0, fundCol0, MAX_CRM_ROWS0) + 1;
        if (nextRow0 < context0.config.mainTabDataStartRow)
        {
            nextRow0 = context0.config.mainTabDataStartRow;
        }

        int endRow0 = nextRow0 + rows0.size() - 1;
        String timestamp0 = java.time.Instant.now().toString();

        writeColumn(spreadsheetId0, crmTabName0, nextRow0, endRow0, headerMap0, context0.config.getCol("mainTabFundNameCol"), rows0, "fundName");
        writeColumn(spreadsheetId0, crmTabName0, nextRow0, endRow0, headerMap0, context0.config.getCol("mainTabWebsiteCol"), rows0, "website");
        writeColumn(spreadsheetId0, crmTabName0, nextRow0, endRow0, headerMap0, context0.config.getCol("mainTabCompanyLinkedInCol"), rows0, "fundLinkedInUrl");
        writeColumn(spreadsheetId0, crmTabName0, nextRow0, endRow0, headerMap0, context0.config.getCol("mainTabCountryCol"), rows0, "country");
        writeColumn(spreadsheetId0, crmTabName0, nextRow0, endRow0, headerMap0, context0.config.getCol("mainTabCityCol"), rows0, "region");
        writeColumn(spreadsheetId0, crmTabName0, nextRow0, endRow0, headerMap0, context0.config.getCol("mainTabStatusCol"), rows0, "conversationStatus");
        writeColumn(spreadsheetId0, crmTabName0, nextRow0, endRow0, headerMap0, context0.config.getCol("mainTabNotesCol"), rows0, "whyNow");
        writeColumn(spreadsheetId0, crmTabName0, nextRow0, endRow0, headerMap0, context0.config.getCol("mainTabIntelligenceJsonCol"), rows0, "intelligenceJson");
        writeColumn(spreadsheetId0, crmTabName0, nextRow0, endRow0, headerMap0, context0.config.getCol("mainTabScoutEvidenceCol"), rows0, "scoutEvidenceJson");
        writeColumn(spreadsheetId0, crmTabName0, nextRow0, endRow0, headerMap0, context0.config.getCol("mainTabCrdNumberCol"), rows0, "crdNumber");
        writeColumn(spreadsheetId0, crmTabName0, nextRow0, endRow0, headerMap0, context0.config.getCol("mainTabResourcesScoreCol"), rows0, "resourcesScore");
        writeColumn(spreadsheetId0, crmTabName0, nextRow0, endRow0, headerMap0, context0.config.getCol("mainTabFitScoreCol"), rows0, "fitScore");
        writeColumn(spreadsheetId0, crmTabName0, nextRow0, endRow0, headerMap0, context0.config.getCol("mainTabProbabilityNowCol"), rows0, "probabilityNowScore");
        writeColumn(spreadsheetId0, crmTabName0, nextRow0, endRow0, headerMap0, context0.config.getCol("mainTabLastIntelDateCol"), rows0, "lastIntelDate", timestamp0);
        writeColumn(spreadsheetId0, crmTabName0, nextRow0, endRow0, headerMap0, context0.config.getCol("mainTabIntelStatusCol"), rows0, "intelStatus");
        writeColumn(spreadsheetId0, crmTabName0, nextRow0, endRow0, headerMap0, context0.config.getCol("mainTabContact1FirstNameCol"), rows0, "contact1FirstName");
        writeColumn(spreadsheetId0, crmTabName0, nextRow0, endRow0, headerMap0, context0.config.getCol("mainTabContact1LastNameCol"), rows0, "contact1LastName");
        writeColumn(spreadsheetId0, crmTabName0, nextRow0, endRow0, headerMap0, context0.config.getCol("mainTabContact1PositionCol"), rows0, "contact1Title");
        writeColumn(spreadsheetId0, crmTabName0, nextRow0, endRow0, headerMap0, context0.config.getCol("mainTabContact1EmailCol"), rows0, "contact1Email");
        writeColumn(spreadsheetId0, crmTabName0, nextRow0, endRow0, headerMap0, context0.config.getCol("mainTabContactLinkedInCol"), rows0, "contact1LinkedIn");
        writeColumn(spreadsheetId0, crmTabName0, nextRow0, endRow0, headerMap0, context0.config.getCol("mainTabContact2FirstNameCol"), rows0, "contact2FirstName");
        writeColumn(spreadsheetId0, crmTabName0, nextRow0, endRow0, headerMap0, context0.config.getCol("mainTabContact2LastNameCol"), rows0, "contact2LastName");
        writeColumn(spreadsheetId0, crmTabName0, nextRow0, endRow0, headerMap0, context0.config.getCol("mainTabContact2PositionCol"), rows0, "contact2Title");
        writeColumn(spreadsheetId0, crmTabName0, nextRow0, endRow0, headerMap0, context0.config.getCol("mainTabContact2EmailCol"), rows0, "contact2Email");
        writeColumn(spreadsheetId0, crmTabName0, nextRow0, endRow0, headerMap0, context0.config.getCol("mainTabLinkedIn2Col"), rows0, "contact2LinkedIn");

        return "Investor Scout run complete. New cold CRM rows added: " + rows0.size() + ".";
    }

    private static void writeColumn(
        String spreadsheetId0, String crmTabName0, int startRow0, int endRow0,
        HashMap<String, Integer> headerMap0, String header0, List<ScoutAppendRow> rows0, String fieldName0) throws Exception
    {
        writeColumn(spreadsheetId0, crmTabName0, startRow0, endRow0, headerMap0, header0, rows0, fieldName0, "");
    }

    private static void writeColumn(
        String spreadsheetId0, String crmTabName0, int startRow0, int endRow0,
        HashMap<String, Integer> headerMap0, String header0, List<ScoutAppendRow> rows0, String fieldName0, String timestamp0) throws Exception
    {
        int col0 = getColumn(headerMap0, header0);
        if (col0 <= 0)
        {
            return;
        }

        int rowCount0 = endRow0 - startRow0 + 1;
        String[][] columnData0 = new String[rowCount0][1];
        for (int i0 = 0; i0 < rowCount0; i0++)
        {
            columnData0[i0][0] = i0 < rows0.size() ? limitForGoogleSheetsCell(getRowValue(rows0.get(i0), fieldName0, timestamp0)) : "";
        }

        SheetsApp.updateRangeMatrix(spreadsheetId0, crmTabName0, startRow0, col0, columnData0);
    }

    private static String getRowValue(ScoutAppendRow row0, String fieldName0, String timestamp0)
    {
        if (row0 == null) return "";

        if (fieldName0.equals("fundName")) return safe(row0.record.firmName);
        if (fieldName0.equals("website")) return safe(row0.record.website);
        if (fieldName0.equals("fundLinkedInUrl")) return safe(row0.record.linkedinCompanyUrl);
        if (fieldName0.equals("country")) return safe(row0.record.country);
        if (fieldName0.equals("region")) return buildRegion(row0.record);
        if (fieldName0.equals("conversationStatus")) return "Cold";
        if (fieldName0.equals("whyNow")) return row0.whyNow;
        if (fieldName0.equals("intelligenceJson")) return row0.intelligenceJson;
        if (fieldName0.equals("scoutEvidenceJson")) return row0.scoutEvidenceJson;
        if (fieldName0.equals("crdNumber")) return row0.record.crd > 0 ? String.valueOf(row0.record.crd) : "";
        if (fieldName0.equals("resourcesScore")) return row0.score == null ? "" : String.valueOf(row0.score.resources);
        if (fieldName0.equals("fitScore")) return String.valueOf(row0.fitScore);
        if (fieldName0.equals("probabilityNowScore")) return row0.score == null ? "" : String.valueOf(row0.score.probabilityNow);
        if (fieldName0.equals("lastIntelDate")) return timestamp0;
        if (fieldName0.equals("intelStatus")) return "SCOUT_DISCOVERED";
        if (fieldName0.equals("contact1FirstName")) return firstNameOf(row0.contact1Name);
        if (fieldName0.equals("contact1LastName")) return lastNameOf(row0.contact1Name);
        if (fieldName0.equals("contact1Title")) return row0.contact1Title;
        if (fieldName0.equals("contact1Email")) return row0.contact1Email;
        if (fieldName0.equals("contact1LinkedIn")) return row0.contact1LinkedIn;
        if (fieldName0.equals("contact2FirstName")) return firstNameOf(row0.contact2Name);
        if (fieldName0.equals("contact2LastName")) return lastNameOf(row0.contact2Name);
        if (fieldName0.equals("contact2Title")) return row0.contact2Title;
        if (fieldName0.equals("contact2Email")) return row0.contact2Email;
        if (fieldName0.equals("contact2LinkedIn")) return row0.contact2LinkedIn;

        return "";
    }

    private static String buildRegion(ScoutUniverseRecord record0)
    {
        if (record0 == null) return "";
        String city0 = safe(record0.city);
        String state0 = safe(record0.state);
        if (city0.length() > 0 && state0.length() > 0) return city0 + ", " + state0;
        if (city0.length() > 0) return city0;
        return state0;
    }

    private static String firstNameOf(String fullName0)
    {
        if (fullName0 == null || fullName0.trim().length() == 0) return "";
        String[] parts0 = fullName0.trim().split("\\s+");
        return parts0.length > 0 ? parts0[0] : "";
    }

    private static String lastNameOf(String fullName0)
    {
        if (fullName0 == null || fullName0.trim().length() == 0) return "";
        String[] parts0 = fullName0.trim().split("\\s+");
        return parts0.length > 1 ? parts0[parts0.length - 1] : "";
    }

    // -----------------------------------------------------------------------
    // Ledger bookkeeping.
    // -----------------------------------------------------------------------

    private static ScoutLedger.LedgerEntry buildLedgerEntry(ScoutAppendRow row0, LocalDate today0, String outcome0)
    {
        ScoutLedger.LedgerEntry entry0 = new ScoutLedger.LedgerEntry();
        entry0.crdOrEin = String.valueOf(row0.record.crd);
        entry0.firmName = row0.record.firmName;
        entry0.lastScored = today0;
        entry0.resources = row0.score == null ? 0 : row0.score.resources;
        entry0.probabilityNow = row0.score == null ? 0 : row0.score.probabilityNow;
        entry0.fit = row0.fitScore;
        entry0.outcome = outcome0;
        return entry0;
    }

    private static ScoutLedger.LedgerEntry buildLedgerEntry(ScoutFitResult fitResult0, ScoutSignalScore score0, LocalDate today0, String outcome0)
    {
        ScoutLedger.LedgerEntry entry0 = new ScoutLedger.LedgerEntry();
        entry0.crdOrEin = String.valueOf(fitResult0.record.crd);
        entry0.firmName = fitResult0.record.firmName;
        entry0.lastScored = today0;
        entry0.resources = score0 == null ? 0 : score0.resources;
        entry0.probabilityNow = score0 == null ? 0 : score0.probabilityNow;
        entry0.fit = fitResult0.fitScore;
        entry0.outcome = outcome0;
        return entry0;
    }

    // -----------------------------------------------------------------------
    // Dry-run preview.
    // -----------------------------------------------------------------------

    private static void printDryRunPreview(List<ScoutAppendRow> rows0)
    {
        System.out.println();
        System.out.println("===== DRY RUN: WOULD-BE SCOUT CANDIDATES =====");

        for (int i0 = 0; i0 < rows0.size(); i0++)
        {
            ScoutAppendRow row0 = rows0.get(i0);
            System.out.println();
            System.out.println("Candidate " + (i0 + 1) + ": " + row0.record.firmName);
            System.out.println("  Website: " + row0.record.website);
            System.out.println("  CRD: " + row0.record.crd);
            System.out.println("  Resources: " + (row0.score == null ? "?" : row0.score.resources)
                + " | Fit: " + row0.fitScore
                + " | ProbabilityNow: " + (row0.score == null ? "?" : row0.score.probabilityNow));
            System.out.println("  Why now: " + row0.whyNow);
            System.out.println("  Contact 1: " + row0.contact1Name + " (" + row0.contact1Title + ") " + row0.contact1Email);
            System.out.println("  Contact 2: " + row0.contact2Name + " (" + row0.contact2Title + ") " + row0.contact2Email);
        }

        System.out.println();
    }

    // -----------------------------------------------------------------------
    // Shared helpers.
    // -----------------------------------------------------------------------

    private static int getColumn(HashMap<String, Integer> headerMap0, String header0)
    {
        if (headerMap0 == null || header0 == null || header0.trim().length() == 0)
        {
            return -1;
        }
        Integer col0 = headerMap0.get(header0.trim());
        return col0 == null ? -1 : col0;
    }

    private static String limitForGoogleSheetsCell(String value0)
    {
        if (value0 == null) return "";
        int maxChars0 = 49000;
        if (value0.length() <= maxChars0) return value0;
        return value0.substring(0, maxChars0) + "\n\n[TRUNCATED: exceeded Google Sheets 50000 character cell limit]";
    }

    private static String safe(String s0) { return s0 == null ? "" : s0; }
}
