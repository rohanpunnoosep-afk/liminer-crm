package com.liminer.indicators;

/*
 * ResourceScale converts a capital figure into the 0..1 RESOURCES magnitude an
 * Indicator reports as its score.
 *
 * Why this exists: before it, the RESOURCES axis rolled up indicator CONFIDENCE,
 * which answers "how sure are we we read this figure correctly" — not "how much
 * can this LP actually write". A $50M family office and a $50B pension both
 * scored 90 off an equally clean filing. Score and confidence are now separate
 * fields on IndicatorResult and this class owns the score half for money.
 *
 * The curve is a downward parabola in log10(dollars) with its vertex pinned at
 * $1B = 100:
 *
 *     score100 = 100 - 14.5 * (9 - log10(dollars))^2      clamped to [0, 100]
 *
 * calibrated against the GP-supplied anchors:
 *
 *     $50M   -> 75      (the smallest LP still worth a real allocation slot)
 *     $250M  -> 95
 *     $1B+   -> 100     (vertex; everything above saturates)
 *
 * and yielding, for reference: $10M -> 42, $100M -> 86 (the Form ADV "large
 * advisory firm" threshold), $1.36B -> 100.
 *
 * The curve bottoms out at 0 near $2.4M. A 0 from THIS class is a real measured
 * verdict ("we read their balance sheet and it is tiny"), which is a different
 * claim from "we found nothing" — the latter never reaches a score at all,
 * because a leaf with no evidence returns IndicatorResult.empty() and the
 * LPScoreProcessor rollup writes a BLANK cell for an axis with no present leaf.
 * Keep that distinction intact: never substitute 0 for absent.
 *
 * Stateless and thread-safe — pure functions only, safe to call from the
 * parallel row threads in LPScoreProcessor.
 */
public class ResourceScale
{
    // Vertex of the curve: log10 of the dollar figure that scores a full 100.
    private static final double SATURATION_LOG10 = 9.0;      // $1,000,000,000
    // Curvature, fitted to the $50M -> 75 and $250M -> 95 anchors.
    private static final double CURVATURE = 14.5;

    private ResourceScale() {}

    /**
     * Magnitude score (0..1) for a dollar figure. Non-positive or non-finite
     * input scores 0.0 — callers must not pass "unknown" as 0; a leaf with no
     * figure returns IndicatorResult.empty() instead.
     */
    public static double scoreForDollars(double dollars0)
    {
        if (!(dollars0 > 0.0) || Double.isInfinite(dollars0)) return 0.0;

        double gap0 = SATURATION_LOG10 - Math.log10(dollars0);
        if (gap0 <= 0.0) return 1.0;                       // at or above the vertex

        double score100 = 100.0 - (CURVATURE * gap0 * gap0);
        return clamp01(score100 / 100.0);
    }

    /**
     * Convenience: parse then score. Returns -1.0 (NOT 0.0) when the text carries
     * no readable figure, so a caller can tell "no figure" from "a tiny figure"
     * and return empty rather than a fabricated zero.
     */
    public static double scoreForMoneyText(String text0)
    {
        double dollars0 = parseMoney(text0);
        return dollars0 > 0.0 ? scoreForDollars(dollars0) : -1.0;
    }

    /**
     * Parses a capital figure out of free text into whole dollars. Handles the
     * shapes the resource leaves actually see:
     *
     *     "$1,358,292,666"   "1358292666"    "$1.2 billion"    "USD 4.5bn"
     *     "£850 million"     "€2,3 mrd"->no  "approx. $75m"    "$1.2B AUM"
     *
     * Currency symbols are ignored rather than converted — a GBP/EUR figure is
     * within ~30% of its USD value at any plausible rate, which does not move a
     * log-scale band, and inventing an FX rate with no rate date would be a
     * point-in-time lie of exactly the kind IndicatorResult.asOfDate exists to
     * prevent.
     *
     * Returns 0.0 when nothing parseable is found.
     */
    public static double parseMoney(String text0)
    {
        if (isBlank(text0)) return 0.0;

        String t0 = text0.toLowerCase()
            .replace(",", "")            // 1,358,292,666 -> 1358292666
            .replace("$", " ")
            .replace("£", " ")
            .replace("€", " ")
            .replace("usd", " ")
            .replaceAll("\\s+", " ")
            .trim();

        // number, then an optional magnitude word/suffix (possibly after spaces).
        java.util.regex.Matcher m0 = java.util.regex.Pattern
            .compile("(\\d+(?:\\.\\d+)?)\\s*(trillion|billion|million|thousand|tn|bn|mm|m|b|k|t)?\\b")
            .matcher(t0);

        double best0 = 0.0;
        while (m0.find())
        {
            double n0;
            try { n0 = Double.parseDouble(m0.group(1)); }
            catch (Exception e0) { continue; }

            String unit0 = m0.group(2);
            double mult0 = multiplierFor(unit0);

            // A bare number with no magnitude word is only a money figure if it is
            // already large; otherwise it is a year, an account count, a page
            // number or similar noise ("3 accounts", "2026", "Item 5").
            if (mult0 == 1.0 && n0 < 100000.0) continue;

            double dollars0 = n0 * mult0;
            if (dollars0 > best0) best0 = dollars0;
        }
        return best0;
    }

    /**
     * Magnitude score for a LinkedIn headcount, the last-resort resource proxy.
     *
     * Deliberately compressed into the middle of the range and capped at 0.60:
     * headcount is not AUM (a 12-person fund-of-funds runs billions, a 400-person
     * wealth manager may run less), so this must never be able to out-score a
     * real filed figure on magnitude the way it already cannot on confidence.
     */
    public static double scoreForHeadcount(long headcount0)
    {
        if (headcount0 <= 0) return 0.0;
        if (headcount0 < 11)   return 0.20;
        if (headcount0 < 51)   return 0.30;
        if (headcount0 < 201)  return 0.40;
        if (headcount0 < 501)  return 0.48;
        if (headcount0 < 1001) return 0.54;
        return 0.60;
    }

    private static double multiplierFor(String unit0)
    {
        if (unit0 == null || unit0.isEmpty()) return 1.0;
        switch (unit0)
        {
            case "trillion": case "tn": case "t": return 1e12;
            case "billion":  case "bn": case "b": return 1e9;
            case "million":  case "mm": case "m": return 1e6;
            case "thousand": case "k":            return 1e3;
            default: return 1.0;
        }
    }

    private static double clamp01(double v0)
    {
        if (v0 < 0.0) return 0.0;
        if (v0 > 1.0) return 1.0;
        return v0;
    }

    private static boolean isBlank(String s0) { return s0 == null || s0.trim().isEmpty(); }
}
