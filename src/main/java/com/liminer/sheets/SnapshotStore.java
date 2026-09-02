package com.liminer.sheets;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;

/*
 * SnapshotStore — append-only hidden-tab trend history for LP intelligence snapshots.
 *
 * Architecture:
 *   - One hidden tab ("LP Intelligence Snapshots") per spreadsheet, keyed by
 *     (lpId, indicator, asOfDate). Created idempotently on first use.
 *   - Rows from PARALLEL row processing are queued (thread-safe) and flushed in
 *     ONE single-threaded write phase by LPScoreProcessor — never written from inside
 *     row threads. Each flush call appends all queued rows in one batch.
 *   - readSeries is called from the row thread but only READS; no write race.
 *   - All cell values are capped at 50,000 chars, below the Google Sheets
 *     per-cell limit, so a long evidence blob can never fail a whole batch.
 *
 * Thread-safety: The queue is ConcurrentLinkedQueue. flush() and readSeries() must
 * be called from the single-threaded write phase ONLY.
 *
 * Tab columns: lpId | indicator | value | asOfDate | sourceUrl | recordedAt
 */
public class SnapshotStore
{
    public static final String SNAPSHOT_TAB_NAME = "LP Intelligence Snapshots";
    private static final int MAX_CELL_CHARS = 49_000;

    // Column indices (1-based) in the snapshot tab.
    private static final int COL_LP_ID      = 1;
    private static final int COL_INDICATOR  = 2;
    private static final int COL_VALUE      = 3;
    private static final int COL_AS_OF      = 4;
    private static final int COL_SOURCE_URL = 5;
    private static final int COL_RECORDED   = 6;
    private static final String[] HEADER = {
        "lpId", "indicator", "value", "asOfDate", "sourceUrl", "recordedAt"
    };

    // Rows queued during parallel row processing; flushed single-threaded.
    private final ConcurrentLinkedQueue<String[]> pendingRows0 = new ConcurrentLinkedQueue<>();

    // -----------------------------------------------------------------------
    // Public API
    // -----------------------------------------------------------------------

    /**
     * Create the hidden snapshot tab + header row if missing. Idempotent — safe to
     * call multiple times. Call this ONCE, single-threaded, before any row processing.
     */
    public void ensureSnapshotTab(String spreadsheetId0) throws Exception
    {
        if (isBlank(spreadsheetId0)) return;
        if (SheetsApp.tabExists(spreadsheetId0, SNAPSHOT_TAB_NAME)) return;
        SheetsApp.createTab(spreadsheetId0, SNAPSHOT_TAB_NAME, true);
        SheetsApp.appendRow(spreadsheetId0, SNAPSHOT_TAB_NAME, HEADER);
        System.out.println("[SnapshotStore] Created hidden tab: " + SNAPSHOT_TAB_NAME);
    }

    /**
     * Queue a snapshot row for later flush (called from parallel row threads).
     * Thread-safe — uses ConcurrentLinkedQueue.
     */
    public void queue(String lpId0, String indicator0, String value0,
                      String asOfDate0, String sourceUrl0)
    {
        String[] row0 = {
            safe(lpId0),
            safe(indicator0),
            truncate(safe(value0), MAX_CELL_CHARS),
            safe(asOfDate0),
            safe(sourceUrl0),
            LocalDate.now().toString()
        };
        pendingRows0.add(row0);
    }

    /**
     * Append all queued rows to the snapshot tab. Call ONCE, single-threaded,
     * in the write phase AFTER all row threads have joined. Never call from a row thread.
     */
    public void flush(String spreadsheetId0) throws Exception
    {
        if (isBlank(spreadsheetId0) || pendingRows0.isEmpty()) return;

        List<String[]> toFlush0 = new ArrayList<>();
        String[] row0;
        while ((row0 = pendingRows0.poll()) != null)
        {
            toFlush0.add(row0);
        }

        for (String[] r0 : toFlush0)
        {
            SheetsApp.appendRow(spreadsheetId0, SNAPSHOT_TAB_NAME, r0);
        }
        System.out.println("[SnapshotStore] Flushed " + toFlush0.size() + " snapshot row(s).");
    }

    /**
     * Read prior dated values for a given (lpId, indicator) pair.
     * Returns a list of SnapshotEntry sorted by asOfDate ascending.
     * Call from the single-threaded read/write phase only.
     */
    public List<SnapshotEntry> readSeries(String spreadsheetId0,
                                          String lpId0, String indicator0) throws Exception
    {
        List<SnapshotEntry> series0 = new ArrayList<>();
        if (isBlank(spreadsheetId0) || isBlank(lpId0) || isBlank(indicator0)) return series0;

        try
        {
            // Read all rows from the snapshot tab; filter for this LP+indicator.
            java.util.HashMap<String, Integer> headerMap0 =
                SheetsApp.buildHeaderMap(spreadsheetId0, SNAPSHOT_TAB_NAME, 1, HEADER.length);
            int totalRows0 = estimateRowCount(headerMap0);
            if (totalRows0 < 2) return series0;

            String[][] data0 = SheetsApp.readRangeMatrix(
                spreadsheetId0, SNAPSHOT_TAB_NAME, 2, 1, totalRows0, HEADER.length);
            if (data0 == null) return series0;

            for (String[] r0 : data0)
            {
                if (r0 == null || r0.length < 4) continue;
                String rowLp0 = safeCell(r0, 0);
                String rowInd0 = safeCell(r0, 1);
                if (lpId0.equals(rowLp0) && indicator0.equals(rowInd0))
                {
                    SnapshotEntry e0 = new SnapshotEntry();
                    e0.lpId = rowLp0;
                    e0.indicator = rowInd0;
                    e0.value = safeCell(r0, 2);
                    e0.asOfDate = safeCell(r0, 3);
                    e0.sourceUrl = safeCell(r0, 4);
                    series0.add(e0);
                }
            }
        }
        catch (Exception e0)
        {
            System.err.println("[SnapshotStore] readSeries failed: " + e0.getMessage());
        }

        series0.sort((a0, b0) -> a0.asOfDate.compareTo(b0.asOfDate));
        return series0;
    }

    // -----------------------------------------------------------------------
    // Value object for a single history entry
    // -----------------------------------------------------------------------

    public static class SnapshotEntry
    {
        public String lpId = "";
        public String indicator = "";
        public String value = "";
        public String asOfDate = "";
        public String sourceUrl = "";
    }

    // -----------------------------------------------------------------------
    // Private helpers
    // -----------------------------------------------------------------------

    /** Rough row-count estimate from header map (SheetsApp doesn't expose a direct count). */
    private static int estimateRowCount(java.util.HashMap<String, Integer> headerMap0)
    {
        // A safe upper bound: read up to 10,000 rows in the snapshot tab.
        return 10_000;
    }

    private static String safeCell(String[] row0, int idx0)
    {
        return (row0 != null && idx0 < row0.length && row0[idx0] != null) ? row0[idx0] : "";
    }

    private static String safe(String s0) { return s0 == null ? "" : s0; }

    private static String truncate(String s0, int max0)
    {
        if (s0 == null) return "";
        return s0.length() <= max0 ? s0 : s0.substring(0, max0);
    }

    private static boolean isBlank(String s0) { return s0 == null || s0.trim().isEmpty(); }
}
