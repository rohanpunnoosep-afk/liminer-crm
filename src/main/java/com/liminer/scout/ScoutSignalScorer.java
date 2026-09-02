package com.liminer.scout;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.json.JSONArray;

/*
 * ScoutSignalScorer — computes two client-independent, strictly-separate 0-100
 * scores for each ScoutUniverseRecord candidate in the Investor Scout pipeline:
 *
 *   RESOURCES        — can this allocator ever fund a fund. From local snapshot
 *                       RAUM (raumTotal, no HTTP), employee count, and the
 *                       optional nullable 990-assets / GLEIF-parent inputs on
 *                       ScoutUniverseRecord (pre-resolved by an earlier stage;
 *                       this scorer never fetches).
 *   PROBABILITY_NOW   — dry powder / capacity to fund NOW, from dated timing
 *                       events (ScoutTimingEvents). Every event's contribution
 *                       decays LINEARLY to 0 over 12 months from its own
 *                       asOfDate (ADV amendments bunch in Q1 — March data must
 *                       not read as fresh in November).
 *
 * The two axes are NEVER multiplied or averaged into one number — same
 * principle the Indicator AXIS_* split enforces elsewhere in this codebase —
 * so a candidate stays inspectable on which axis failed.
 *
 * Scores are computed once per universe refresh and cached under
 * data/scout/signal-scores/<YYYY-MM>.json keyed by crd, following the
 * persistence style of FileScoutUniverseStore (one JSON file per month).
 */
public class ScoutSignalScorer
{
    private static final int DECAY_MONTHS = 12;
    private static final double DECAY_DAYS = 365.0;

    // ---- RESOURCES bands (RAUM / 990-assets USD floor -> base score) --------
    private static final double[] RAUM_BAND_FLOORS =
        { 0, 10_000_000, 50_000_000, 250_000_000, 1_000_000_000, 5_000_000_000.0, 25_000_000_000.0 };
    private static final int[] RAUM_BAND_SCORES = { 5, 15, 30, 50, 70, 85, 95 };

    private static final int EMPLOYEES_BONUS_500 = 5;
    private static final int EMPLOYEES_BONUS_100 = 3;
    private static final int EMPLOYEES_BONUS_20 = 1;

    private static final int GLEIF_PARENT_BONUS = 10;

    // ---- PROBABILITY_NOW raw (undecayed) point contributions ----------------
    private static final int FORM_D_ACTIVELY_RAISING_POINTS = 45;
    private static final int FORM_D_FRESH_CAPITAL_BASE_POINTS = 30;
    private static final int FORM_D_FRESH_CAPITAL_BONUS_100M = 15;
    private static final int FORM_D_FRESH_CAPITAL_BONUS_25M = 8;
    private static final int PROGRAMMATIC_DEPLOYER_POINTS = 25;
    private static final int NEW_REGISTRANT_POINTS = 30;
    private static final int NEW_FOF_FUND_POINTS = 25;
    private static final int RAUM_JUMP_MAX_POINTS = 30;

    private static final Path DEFAULT_CACHE_DIR = Paths.get("data", "scout", "signal-scores");

    private final Path cacheDir0;

    public ScoutSignalScorer() { this(DEFAULT_CACHE_DIR); }

    public ScoutSignalScorer(Path cacheDir0) { this.cacheDir0 = cacheDir0; }

    // -----------------------------------------------------------------------
    // Pure scoring
    // -----------------------------------------------------------------------

    public ScoutSignalScore score(ScoutUniverseRecord rec0, ScoutTimingEvents events0, LocalDate today0)
    {
        ScoutSignalScore result0 = new ScoutSignalScore();
        if (rec0 == null) return result0;
        LocalDate todayEff0 = today0 == null ? LocalDate.now() : today0;

        result0.crd = rec0.crd;
        result0.resources = scoreResources(rec0, result0.reasons);
        result0.probabilityNow = scoreProbabilityNow(events0, todayEff0, result0.reasons);
        return result0;
    }

    // ---- RESOURCES ------------------------------------------------------------

    private int scoreResources(ScoutUniverseRecord rec0, List<String> reasons0)
    {
        double raumForBand0 = rec0.raumTotal;
        boolean used990Instead0 = false;
        if (rec0.nonprofitTotalAssets990 != null && rec0.nonprofitTotalAssets990 > raumForBand0)
        {
            raumForBand0 = rec0.nonprofitTotalAssets990;
            used990Instead0 = true;
        }

        int score0 = raumBandScore(raumForBand0);
        if (used990Instead0)
        {
            reasons0.add("990 total assets " + formatUsd(rec0.nonprofitTotalAssets990)
                + " exceeds reported RAUM " + formatUsd(rec0.raumTotal) + "; used for resources band.");
        }
        reasons0.add("RAUM/asset band for " + formatUsd(raumForBand0) + " -> resources base score " + score0 + ".");

        int empBonus0 = employeesBonus(rec0.employees);
        if (empBonus0 > 0)
        {
            score0 += empBonus0;
            reasons0.add(rec0.employees + " employees adds +" + empBonus0 + " to resources.");
        }

        if (Boolean.TRUE.equals(rec0.gleifParentFlag))
        {
            score0 += GLEIF_PARENT_BONUS;
            reasons0.add("GLEIF-flagged parent entity adds +" + GLEIF_PARENT_BONUS
                + " to resources (backed by a larger regulated group).");
        }

        return clamp0to100(score0);
    }

    private static int raumBandScore(double raum0)
    {
        int score0 = RAUM_BAND_SCORES[0];
        for (int i0 = 0; i0 < RAUM_BAND_FLOORS.length; i0++)
        {
            if (raum0 >= RAUM_BAND_FLOORS[i0]) score0 = RAUM_BAND_SCORES[i0];
        }
        return score0;
    }

    // Human-readable RAUM band label reusing RAUM_BAND_FLOORS, for
    // ScoutUniverseIndexer's client-independent RAUM-band tag.
    private static final String[] RAUM_BAND_LABELS =
        { "<$10M", "$10M-$50M", "$50M-$250M", "$250M-$1B", "$1B-$5B", "$5B-$25B", ">=$25B" };

    public static String raumBandLabel(double raum0)
    {
        String label0 = RAUM_BAND_LABELS[0];
        for (int i0 = 0; i0 < RAUM_BAND_FLOORS.length; i0++)
        {
            if (raum0 >= RAUM_BAND_FLOORS[i0]) label0 = RAUM_BAND_LABELS[i0];
        }
        return label0;
    }

    private static int employeesBonus(int employees0)
    {
        if (employees0 >= 500) return EMPLOYEES_BONUS_500;
        if (employees0 >= 100) return EMPLOYEES_BONUS_100;
        if (employees0 >= 20) return EMPLOYEES_BONUS_20;
        return 0;
    }

    // ---- PROBABILITY_NOW --------------------------------------------------------

    private int scoreProbabilityNow(ScoutTimingEvents events0, LocalDate today0, List<String> reasons0)
    {
        if (events0 == null || events0.events == null || events0.events.isEmpty()) return 0;

        double total0 = 0.0;
        Map<String, LocalDate> formDFundDates0 = new HashMap<String, LocalDate>();

        for (ScoutTimingEvents.Event e0 : events0.events)
        {
            if (e0 == null || e0.type == null) continue;

            switch (e0.type)
            {
                case FORM_D:
                    total0 += scoreFormD(e0, today0, reasons0);
                    LocalDate asOf0 = formDAsOf(e0);
                    if (asOf0 != null && !isBlankStr(e0.fundName))
                    {
                        LocalDate existing0 = formDFundDates0.get(e0.fundName);
                        if (existing0 == null || asOf0.isAfter(existing0))
                            formDFundDates0.put(e0.fundName, asOf0);
                    }
                    break;

                case NEW_REGISTRANT:
                    total0 += scoreDatedFlatEvent(monthAsOf(e0.month), today0,
                        NEW_REGISTRANT_POINTS, "New registrant as of " + e0.month, reasons0);
                    break;

                case NEW_FOF_FUND:
                    total0 += scoreDatedFlatEvent(monthAsOf(e0.month), today0,
                        NEW_FOF_FUND_POINTS,
                        "New fund-of-funds vehicle" + (isBlankStr(e0.fundName) ? "" : (" \"" + e0.fundName + "\""))
                            + " reported as of " + e0.month,
                        reasons0);
                    break;

                case RAUM_JUMP:
                    total0 += scoreRaumJump(e0, today0, reasons0);
                    break;

                default:
                    break;
            }
        }

        total0 += scoreProgrammaticDeployer(formDFundDates0, today0, reasons0);

        return clamp0to100((int) Math.round(total0));
    }

    private double scoreFormD(ScoutTimingEvents.Event e0, LocalDate today0, List<String> reasons0)
    {
        double total0 = 0.0;

        LocalDate filingAsOf0 = formDAsOf(e0);
        double gap0 = e0.offeringAmount - e0.soldAmount;
        if (gap0 > 0)
        {
            double decay0 = decayFactor(filingAsOf0, today0);
            double pts0 = FORM_D_ACTIVELY_RAISING_POINTS * decay0;
            if (pts0 > 0)
            {
                total0 += pts0;
                reasons0.add("Filed Form D for " + safeName(e0.fundName) + " on "
                    + (e0.filedDate != null ? e0.filedDate : "?") + "; " + formatUsd(e0.soldAmount)
                    + " of " + formatUsd(e0.offeringAmount) + " raised (+" + Math.round(pts0) + ").");
            }
        }

        if (e0.firstSaleDate != null && e0.soldAmount > 0)
        {
            double base0 = FORM_D_FRESH_CAPITAL_BASE_POINTS;
            if (e0.soldAmount >= 100_000_000) base0 += FORM_D_FRESH_CAPITAL_BONUS_100M;
            else if (e0.soldAmount >= 25_000_000) base0 += FORM_D_FRESH_CAPITAL_BONUS_25M;

            double decay0 = decayFactor(e0.firstSaleDate, today0);
            double pts0 = base0 * decay0;
            if (pts0 > 0)
            {
                total0 += pts0;
                reasons0.add("Fresh committed capital for " + safeName(e0.fundName) + ": first sale "
                    + e0.firstSaleDate + ", " + formatUsd(e0.soldAmount) + " sold (+" + Math.round(pts0) + ").");
            }
        }

        return total0;
    }

    private double scoreRaumJump(ScoutTimingEvents.Event e0, LocalDate today0, List<String> reasons0)
    {
        LocalDate asOf0 = monthAsOf(e0.month);
        double decay0 = decayFactor(asOf0, today0);
        double pts0 = Math.min(RAUM_JUMP_MAX_POINTS, Math.abs(e0.pctChange) * 100.0) * decay0;
        if (pts0 <= 0.0) return 0.0;

        reasons0.add("RAUM change of " + String.format("%+.0f%%", e0.pctChange * 100.0)
            + " as of " + e0.month + " (+" + Math.round(pts0) + ").");
        return pts0;
    }

    private double scoreDatedFlatEvent(LocalDate asOf0, LocalDate today0, int basePoints0,
        String label0, List<String> reasons0)
    {
        double decay0 = decayFactor(asOf0, today0);
        double pts0 = basePoints0 * decay0;
        if (pts0 <= 0.0) return 0.0;
        reasons0.add(label0 + " (+" + Math.round(pts0) + ").");
        return pts0;
    }

    // "Programmatic deployer": >=2 distinct Form D vehicles filed within the
    // trailing 12 months of today. Its own decay is keyed to the most recent
    // qualifying filing, same age-decay contract as every other signal.
    private double scoreProgrammaticDeployer(Map<String, LocalDate> formDFundDates0, LocalDate today0,
        List<String> reasons0)
    {
        LocalDate windowStart0 = today0.minusMonths(DECAY_MONTHS);
        int vehiclesWithinWindow0 = 0;
        LocalDate mostRecent0 = null;
        for (Map.Entry<String, LocalDate> entry0 : formDFundDates0.entrySet())
        {
            LocalDate d0 = entry0.getValue();
            if (d0 != null && !d0.isBefore(windowStart0))
            {
                vehiclesWithinWindow0++;
                if (mostRecent0 == null || d0.isAfter(mostRecent0)) mostRecent0 = d0;
            }
        }
        if (vehiclesWithinWindow0 < 2) return 0.0;

        double decay0 = decayFactor(mostRecent0, today0);
        double pts0 = PROGRAMMATIC_DEPLOYER_POINTS * decay0;
        if (pts0 <= 0.0) return 0.0;

        reasons0.add(vehiclesWithinWindow0 + " distinct Form D vehicles filed within 12 months -> "
            + "programmatic deployer (+" + Math.round(pts0) + ").");
        return pts0;
    }

    private static LocalDate formDAsOf(ScoutTimingEvents.Event e0)
    {
        if (e0.filedDate != null) return e0.filedDate;
        return e0.firstSaleDate;
    }

    private static LocalDate monthAsOf(String yyyyMm0)
    {
        if (isBlankStr(yyyyMm0)) return null;
        try { return YearMonth.parse(yyyyMm0.trim()).atEndOfMonth(); }
        catch (Exception ignored0) { return null; }
    }

    // Linear decay to 0 over DECAY_MONTHS (12) months from asOfDate. A future-
    // dated event (days < 0, e.g. clock skew) is treated as fully fresh.
    private static double decayFactor(LocalDate asOf0, LocalDate today0)
    {
        if (asOf0 == null || today0 == null) return 0.0;
        long days0 = ChronoUnit.DAYS.between(asOf0, today0);
        if (days0 < 0) days0 = 0;
        if (days0 >= DECAY_DAYS) return 0.0;
        return 1.0 - (days0 / DECAY_DAYS);
    }

    private static int clamp0to100(int v0) { return Math.max(0, Math.min(100, v0)); }

    private static String formatUsd(double v0)
    {
        if (v0 >= 1_000_000_000) return "$" + trimTrailingZero(v0 / 1_000_000_000) + "B";
        if (v0 >= 1_000_000) return "$" + trimTrailingZero(v0 / 1_000_000) + "M";
        if (v0 >= 1_000) return "$" + trimTrailingZero(v0 / 1_000) + "K";
        return "$" + (long) v0;
    }

    private static String trimTrailingZero(double v0)
    {
        String s0 = String.format("%.1f", v0);
        return s0.endsWith(".0") ? s0.substring(0, s0.length() - 2) : s0;
    }

    private static String safeName(String s0) { return isBlankStr(s0) ? "the fund" : s0.trim(); }

    private static boolean isBlankStr(String s0) { return s0 == null || s0.trim().isEmpty(); }

    // -----------------------------------------------------------------------
    // Per-month file cache, keyed by crd (persistence style of FileScoutUniverseStore)
    // -----------------------------------------------------------------------

    public void saveScores(String yyyyMm0, Map<Integer, ScoutSignalScore> scores0) throws IOException
    {
        Files.createDirectories(cacheDir0);
        JSONArray arr0 = new JSONArray();
        if (scores0 != null)
        {
            for (ScoutSignalScore s0 : scores0.values()) arr0.put(s0.toJson());
        }
        Files.writeString(monthFile(yyyyMm0), arr0.toString(), StandardCharsets.UTF_8);
    }

    public Map<Integer, ScoutSignalScore> loadScores(String yyyyMm0) throws IOException
    {
        Map<Integer, ScoutSignalScore> out0 = new HashMap<Integer, ScoutSignalScore>();
        Path file0 = monthFile(yyyyMm0);
        if (!Files.exists(file0)) return out0;

        String json0 = Files.readString(file0, StandardCharsets.UTF_8);
        JSONArray arr0 = new JSONArray(json0);
        for (int i0 = 0; i0 < arr0.length(); i0++)
        {
            ScoutSignalScore s0 = ScoutSignalScore.fromJson(arr0.optJSONObject(i0));
            out0.put(s0.crd, s0);
        }
        return out0;
    }

    private Path monthFile(String yyyyMm0) { return cacheDir0.resolve(yyyyMm0 + ".json"); }
}
