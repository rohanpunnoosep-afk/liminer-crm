package com.liminer.indicators;

import com.liminer.core.LpContext;
import com.liminer.enrich.ScrapeCache;
import com.liminer.sheets.SnapshotStore;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;

/*
 * DealVelocityIndicator (Timing 3B) — trailing-window momentum + RAUM YoY delta.
 *
 * Sources: SnapshotStore history for this LP's indicator series.
 *   - RAUM YoY delta: compare the most recent RAUM snapshot to the one ~12 months
 *     earlier. Growing RAUM = active fundraising = stronger probability-now.
 *   - Deal/close event count: count dated fund-close events in the trailing 12 months.
 *     More recent events = higher velocity confidence.
 *
 * On first run (no history): returns empty — the SnapshotStore builds up over time.
 * On each run: queues the latest RAUM and close events into SnapshotStore for future
 * delta computation. The actual queue.flush() is called by LPScoreProcessor AFTER
 * all row threads join (never from inside this fetch() call).
 *
 * Thread-safety: readSeries is called single-threaded in the write phase.
 * queue() on SnapshotStore is ConcurrentLinkedQueue — thread-safe.
 * No other shared mutable state.
 */
public class DealVelocityIndicator implements Indicator
{
    private static final int TRAILING_MONTHS = 12;
    private static final double HIGH_CONFIDENCE  = 0.78;
    private static final double MEDIUM_CONFIDENCE = 0.55;

    // Indicator keys used in SnapshotStore.
    public static final String SNAP_RAUM = "raum";
    public static final String SNAP_FUND_CLOSE = "fund_close";

    @Override
    public String axis() { return AXIS_PROBABILITY_NOW; }

    @Override
    public String name() { return "DealVelocity"; }

    /**
     * NOTE: This indicator reads SnapshotStore and queues new snapshots.
     * LPScoreProcessor passes the shared SnapshotStore instance via LpContext.snapshotStore.
     * The spreadsheetId used for readSeries is in LpContext.spreadsheetId.
     */
    @Override
    public IndicatorResult fetch(LpContext ctx, ScrapeCache cache) throws Exception
    {
        if (ctx == null) return IndicatorResult.empty(AXIS_PROBABILITY_NOW);
        if (ctx.snapshotStore == null || isBlank(ctx.spreadsheetId))
        {
            return IndicatorResult.empty(AXIS_PROBABILITY_NOW);
        }

        String lpId = buildLpId(ctx);
        SnapshotStore store = ctx.snapshotStore;
        String spreadsheetId = ctx.spreadsheetId;

        // --- Queue new snapshots from this run (flushed later by LPScoreProcessor) ---
        queueCurrentSnapshots(ctx, store, lpId);

        // --- Read historical series ---
        List<SnapshotStore.SnapshotEntry> raumSeries = store.readSeries(
            spreadsheetId, lpId, SNAP_RAUM);
        List<SnapshotStore.SnapshotEntry> closeSeries = store.readSeries(
            spreadsheetId, lpId, SNAP_FUND_CLOSE);

        // --- RAUM YoY delta ---
        double raumDelta = computeRaumYoyDelta(raumSeries);

        // --- Deal/close event velocity (trailing TRAILING_MONTHS) ---
        int recentCloseCount = countRecentEvents(closeSeries, TRAILING_MONTHS);

        // No history yet → empty (first run populates; next run computes).
        if (raumSeries.size() < 2 && recentCloseCount == 0)
        {
            return IndicatorResult.empty(AXIS_PROBABILITY_NOW);
        }

        StringBuilder value = new StringBuilder();
        if (raumSeries.size() >= 2)
        {
            value.append("RAUM YoY delta: ").append(formatDelta(raumDelta)).append("; ");
        }
        value.append("fund-close events (trailing 12m): ").append(recentCloseCount);

        double confidence = deriveConfidence(raumDelta, recentCloseCount);
        String asOf = LocalDate.now().toString();

        return new IndicatorResult(value.toString().trim(), confidence, "",
            asOf, AXIS_PROBABILITY_NOW,
            "DealVelocity from SnapshotStore series ("
            + raumSeries.size() + " RAUM snapshots, "
            + closeSeries.size() + " close events).");
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private void queueCurrentSnapshots(LpContext ctx, SnapshotStore store, String lpId)
    {
        // Queue any fresh RAUM value from the RaumIndicator result in LpContext.
        if (!isBlank(ctx.latestRaumValue) && !isBlank(ctx.latestRaumDate))
        {
            store.queue(lpId, SNAP_RAUM, ctx.latestRaumValue, ctx.latestRaumDate,
                ctx.latestRaumSourceUrl);
        }
        // Queue any fresh fund-close event from FundCloseIndicator.
        if (!isBlank(ctx.latestFundCloseValue) && !isBlank(ctx.latestFundCloseDate))
        {
            store.queue(lpId, SNAP_FUND_CLOSE, ctx.latestFundCloseValue,
                ctx.latestFundCloseDate, ctx.latestFundCloseSourceUrl);
        }
    }

    private double computeRaumYoyDelta(List<SnapshotStore.SnapshotEntry> series)
    {
        if (series == null || series.size() < 2) return 0.0;

        SnapshotStore.SnapshotEntry latest = series.get(series.size() - 1);
        // Find the snapshot closest to 12 months before the latest.
        LocalDate latestDate = parseDate(latest.asOfDate);
        if (latestDate == null) return 0.0;
        LocalDate targetPrior = latestDate.minusMonths(TRAILING_MONTHS);

        SnapshotStore.SnapshotEntry best = null;
        long bestGap = Long.MAX_VALUE;
        for (int i = 0; i < series.size() - 1; i++)
        {
            LocalDate d = parseDate(series.get(i).asOfDate);
            if (d == null) continue;
            long gap = Math.abs(ChronoUnit.DAYS.between(d, targetPrior));
            if (gap < bestGap) { bestGap = gap; best = series.get(i); }
        }

        if (best == null || bestGap > 200) return 0.0; // no usable prior snapshot

        double latestNum = extractNumber(latest.value);
        double priorNum  = extractNumber(best.value);
        if (priorNum == 0.0) return 0.0;
        return (latestNum - priorNum) / priorNum;
    }

    private int countRecentEvents(List<SnapshotStore.SnapshotEntry> series, int months)
    {
        if (series == null || series.isEmpty()) return 0;
        LocalDate cutoff = LocalDate.now().minusMonths(months);
        int count = 0;
        for (SnapshotStore.SnapshotEntry e : series)
        {
            LocalDate d = parseDate(e.asOfDate);
            if (d != null && !d.isBefore(cutoff)) count++;
        }
        return count;
    }

    private double deriveConfidence(double raumDelta, int closeCount)
    {
        double c = 0.0;
        if (raumDelta > 0.10) c += 0.35;  // growing RAUM
        else if (raumDelta > 0) c += 0.15;
        if (closeCount >= 2) c += 0.40;
        else if (closeCount == 1) c += 0.25;
        return Math.min(Math.max(c, 0.0), HIGH_CONFIDENCE);
    }

    private static String buildLpId(LpContext ctx)
    {
        if (!isBlank(ctx.identityKeys.crd)) return "crd:" + ctx.identityKeys.crd.trim();
        if (!isBlank(ctx.identityKeys.cik)) return "cik:" + ctx.identityKeys.cik.trim();
        if (!isBlank(ctx.identityKeys.ein)) return "ein:" + ctx.identityKeys.ein.trim();
        return "name:" + safe(ctx.fundName).replaceAll("[^a-zA-Z0-9]", "").toLowerCase();
    }

    private static String formatDelta(double delta)
    {
        if (delta == 0.0) return "n/a";
        return String.format("%+.0f%%", delta * 100);
    }

    private static double extractNumber(String value)
    {
        if (isBlank(value)) return 0.0;
        // Rough: strip non-numeric except "." and "-"; handle M/B suffixes.
        String v = value.replaceAll("[^0-9.\\-BMKbmk]", "");
        try
        {
            double d = Double.parseDouble(v.replaceAll("[BMKbmk]", ""));
            String last = v.replaceAll("[^BMKbmk]", "").toLowerCase();
            if (last.contains("b")) d *= 1_000_000_000;
            else if (last.contains("m")) d *= 1_000_000;
            else if (last.contains("k")) d *= 1_000;
            return d;
        }
        catch (Exception ignored) { return 0.0; }
    }

    private static LocalDate parseDate(String s)
    {
        if (isBlank(s)) return null;
        try { return LocalDate.parse(s.trim().substring(0, 10)); }
        catch (Exception ignored) { return null; }
    }

    private static String safe(String s) { return s == null ? "" : s; }
    private static boolean isBlank(String s) { return s == null || s.trim().isEmpty(); }
}
