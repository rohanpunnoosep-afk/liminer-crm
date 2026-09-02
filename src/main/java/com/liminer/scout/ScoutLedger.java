package com.liminer.scout;

import com.liminer.sheets.SheetsApp;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.Map;

/*
 * ScoutLedger — per-client hidden tab ("Scout Ledger") tracking every Investor
 * Scout candidate this client has ever been scored against, keyed by
 * crdOrEin. Modeled on SnapshotStore's hidden-tab pattern (idempotent tab
 * creation, full-rectangle read for the internal storage tab is fine since
 * this is NOT the client's CRM main tab, which is the tab the no-rectangle
 * rule protects). Writes are append-for-new / column-by-column
 * single-cell update for existing rows — never a rectangle write.
 *
 * Three purposes (InvestorScoutProcessor is the only caller):
 *   (a) idempotent reruns    — an APPENDED entry is never eligible again, so a
 *                              rerun never re-appends the same candidate.
 *   (b) cost control         — a BELOW_THRESHOLD entry younger than 30 days is
 *                              not eligible, so the orchestrator skips
 *                              re-scoring/re-spending email lookups on it.
 *   (c) re-surfacing         — a BELOW_THRESHOLD entry becomes eligible again
 *                              immediately (regardless of age) once a fresh
 *                              PROBABILITY_NOW read is higher than the cached
 *                              one — a new timing event (e.g. a fresh Form D)
 *                              is exactly the thing that should override the
 *                              30-day cooldown.
 *
 * eligible(...) is a pure static method so it is offline-testable without any
 * Sheets access — see ScoutLedgerTestMain.
 */
public class ScoutLedger
{
    public static final String LEDGER_TAB_NAME = "Scout Ledger";

    public static final String OUTCOME_APPENDED = "APPENDED";
    public static final String OUTCOME_APPENDED_NO_CONTACT = "APPENDED_NO_CONTACT";
    public static final String OUTCOME_BELOW_THRESHOLD = "BELOW_THRESHOLD";
    public static final String OUTCOME_REJECTED_BY_USER = "REJECTED_BY_USER";

    private static final int RESCORE_COOLDOWN_DAYS = 30;

    private static final int COL_CRD_OR_EIN = 1;
    private static final int COL_FIRM_NAME = 2;
    private static final int COL_FIRST_SEEN = 3;
    private static final int COL_LAST_SCORED = 4;
    private static final int COL_RESOURCES = 5;
    private static final int COL_PROBABILITY_NOW = 6;
    private static final int COL_FIT = 7;
    private static final int COL_OUTCOME = 8;

    private static final String[] HEADER = {
        "crdOrEin", "firmName", "firstSeen", "lastScored", "resources", "probabilityNow", "fit", "outcome"
    };

    public static class LedgerEntry
    {
        public String crdOrEin;
        public String firmName;
        public LocalDate firstSeen;
        public LocalDate lastScored;
        public int resources;
        public int probabilityNow;
        public int fit;
        public String outcome;

        public LedgerEntry()
        {
            crdOrEin = "";
            firmName = "";
            firstSeen = null;
            lastScored = null;
            resources = 0;
            probabilityNow = 0;
            fit = 0;
            outcome = "";
        }

        public String[] toRow()
        {
            return new String[]
            {
                safe(crdOrEin),
                safe(firmName),
                firstSeen == null ? "" : firstSeen.toString(),
                lastScored == null ? "" : lastScored.toString(),
                String.valueOf(resources),
                String.valueOf(probabilityNow),
                String.valueOf(fit),
                safe(outcome)
            };
        }

        public static LedgerEntry fromRow(String[] row0)
        {
            LedgerEntry e0 = new LedgerEntry();
            if (row0 == null)
            {
                return e0;
            }

            e0.crdOrEin = cell(row0, 0);
            e0.firmName = cell(row0, 1);
            e0.firstSeen = parseDate(cell(row0, 2));
            e0.lastScored = parseDate(cell(row0, 3));
            e0.resources = parseIntOrZero(cell(row0, 4));
            e0.probabilityNow = parseIntOrZero(cell(row0, 5));
            e0.fit = parseIntOrZero(cell(row0, 6));
            e0.outcome = cell(row0, 7);
            return e0;
        }

        private static String cell(String[] row0, int idx0)
        {
            return idx0 < row0.length && row0[idx0] != null ? row0[idx0].trim() : "";
        }

        private static LocalDate parseDate(String value0)
        {
            if (value0 == null || value0.trim().length() == 0)
            {
                return null;
            }
            try
            {
                return LocalDate.parse(value0.trim());
            }
            catch (Exception exception0)
            {
                return null;
            }
        }

        private static int parseIntOrZero(String value0)
        {
            try
            {
                return Integer.parseInt(value0.trim());
            }
            catch (Exception exception0)
            {
                return 0;
            }
        }

        private static String safe(String s0) { return s0 == null ? "" : s0; }
    }

    // -----------------------------------------------------------------------
    // Pure eligibility logic — offline-testable, no Sheets access.
    // -----------------------------------------------------------------------

    /*
     * Whether this candidate is eligible to be re-scored / re-considered on
     * this run:
     *   - null entry (never scored before)      -> eligible.
     *   - APPENDED                               -> never eligible again.
     *   - APPENDED_NO_CONTACT                    -> never eligible again (treated like APPENDED).
     *   - REJECTED_BY_USER                       -> never eligible again.
     *   - BELOW_THRESHOLD:
     *       - currentProbabilityNow > entry.probabilityNow -> eligible now
     *         (re-surface rule; a fresh timing event overrides the cooldown).
     *       - else eligible only once >= 30 days have passed since lastScored.
     */
    public static boolean eligible(LedgerEntry entry, int currentProbabilityNow, LocalDate today)
    {
        if (entry == null)
        {
            return true;
        }

        if (OUTCOME_APPENDED.equals(entry.outcome) || OUTCOME_APPENDED_NO_CONTACT.equals(entry.outcome)
            || OUTCOME_REJECTED_BY_USER.equals(entry.outcome))
        {
            return false;
        }

        if (currentProbabilityNow > entry.probabilityNow)
        {
            return true;
        }

        if (entry.lastScored == null || today == null)
        {
            return true;
        }

        long daysSinceScored0 = ChronoUnit.DAYS.between(entry.lastScored, today);
        return daysSinceScored0 >= RESCORE_COOLDOWN_DAYS;
    }

    // -----------------------------------------------------------------------
    // Sheets I/O
    // -----------------------------------------------------------------------

    public void ensureLedgerTab(String spreadsheetId0) throws Exception
    {
        if (isBlank(spreadsheetId0))
        {
            return;
        }
        if (SheetsApp.tabExists(spreadsheetId0, LEDGER_TAB_NAME))
        {
            return;
        }
        SheetsApp.createTab(spreadsheetId0, LEDGER_TAB_NAME, true);
        SheetsApp.appendRow(spreadsheetId0, LEDGER_TAB_NAME, HEADER);
        System.out.println("[ScoutLedger] Created hidden tab: " + LEDGER_TAB_NAME);
    }

    /*
     * Read every ledger row into a map keyed by crdOrEin. Reading the whole
     * rectangle at once (rather than column-by-column) is acceptable here —
     * this is an internal Liminer storage tab, not the client's CRM main tab
     * that the no-rectangle rule protects (same precedent as
     * SnapshotStore.readSeries). See README "Sheets I/O".
     */
    public Map<String, LedgerEntry> loadEntries(String spreadsheetId0) throws Exception
    {
        Map<String, LedgerEntry> entries0 = new HashMap<String, LedgerEntry>();

        if (isBlank(spreadsheetId0) || !SheetsApp.tabExists(spreadsheetId0, LEDGER_TAB_NAME))
        {
            return entries0;
        }

        int totalRows0 = 10_000;
        String[][] data0 = SheetsApp.readRangeMatrix(
            spreadsheetId0,
            LEDGER_TAB_NAME,
            2,
            1,
            totalRows0,
            HEADER.length
        );

        if (data0 == null)
        {
            return entries0;
        }

        for (String[] row0 : data0)
        {
            if (row0 == null || row0.length == 0 || isBlank(row0[0]))
            {
                continue;
            }

            LedgerEntry entry0 = LedgerEntry.fromRow(row0);
            entries0.put(entry0.crdOrEin, entry0);
        }

        return entries0;
    }

    /*
     * Insert a new ledger row, or update the mutable columns (lastScored,
     * resources, probabilityNow, fit, outcome) of an existing row in place.
     * Single-cell updates only — never a rectangle write.
     */
    public void upsert(String spreadsheetId0, LedgerEntry entry0) throws Exception
    {
        if (isBlank(spreadsheetId0) || entry0 == null || isBlank(entry0.crdOrEin))
        {
            return;
        }

        ensureLedgerTab(spreadsheetId0);

        int existingRow0 = SheetsApp.findValueInCol(spreadsheetId0, LEDGER_TAB_NAME, COL_CRD_OR_EIN, entry0.crdOrEin);

        if (existingRow0 <= 0)
        {
            if (entry0.firstSeen == null)
            {
                entry0.firstSeen = entry0.lastScored;
            }
            SheetsApp.appendRow(spreadsheetId0, LEDGER_TAB_NAME, entry0.toRow());
            return;
        }

        SheetsApp.updateCell(spreadsheetId0, LEDGER_TAB_NAME, existingRow0, COL_LAST_SCORED,
            entry0.lastScored == null ? "" : entry0.lastScored.toString());
        SheetsApp.updateCell(spreadsheetId0, LEDGER_TAB_NAME, existingRow0, COL_RESOURCES,
            String.valueOf(entry0.resources));
        SheetsApp.updateCell(spreadsheetId0, LEDGER_TAB_NAME, existingRow0, COL_PROBABILITY_NOW,
            String.valueOf(entry0.probabilityNow));
        SheetsApp.updateCell(spreadsheetId0, LEDGER_TAB_NAME, existingRow0, COL_FIT,
            String.valueOf(entry0.fit));
        SheetsApp.updateCell(spreadsheetId0, LEDGER_TAB_NAME, existingRow0, COL_OUTCOME,
            safeOutcome(entry0.outcome));
    }

    private static String safeOutcome(String outcome0) { return outcome0 == null ? "" : outcome0; }

    private static boolean isBlank(String value0) { return value0 == null || value0.trim().length() == 0; }
}
