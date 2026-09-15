package com.liminer.indicators;

/*
 * ProbabilityCalibration — the display calibration for the PROBABILITY_NOW axis
 * (the "Probability Now" CRM column). Same idea, and same monotone piecewise-linear
 * shape, as LPScoreProcessor's FIT curve: it is applied at the WRITE BOUNDARY only,
 * so every raw leaf score, the Intelligence JSON blob and the Tier-1 signal layer
 * keep the uncalibrated numbers and a retune needs no re-run of the indicators.
 *
 * WHY THIS EXISTS
 *
 * The raw axis is a confidence-weighted mean of timing leaves — FundClose,
 * NewAllocator, DealVelocity — and every one of them is an event detector. A leaf
 * that finds nothing contributes nothing, and the two that usually DO fire settle
 * low by construction: NewAllocatorIndicator scores an established allocator 0.35,
 * and DealVelocityIndicator returns 0.0 until SnapshotStore has accumulated a
 * second RAUM point. A perfectly ordinary LP therefore rolled up around 0.2-0.3,
 * got multiplied by a macro modifier under 1.0, and reached the GP as a "22".
 *
 * That number was wrong, not merely pessimistic. Funds do not raise on a public
 * schedule: the absence of a posted fund close or a named new allocator is a gap
 * in OUR sources, not evidence the LP will not write a cheque this quarter. The
 * raw scale treated silence as a near-zero probability, so the column ranked LPs
 * correctly but described all of them as hopeless, and a GP reading it straight
 * would skip calls worth making.
 *
 * WHAT THE CURVE DOES
 *
 * It re-anchors the scale without reordering anyone:
 *   - the bottom is lifted off zero (raw 0.00 -> 30), because "no timing signal
 *     found" is an unknown, not a no;
 *   - the ordinary middle is pulled to the middle (raw ~0.25 -> ~52), which is the
 *     "no strong signal either way" reading the GP should get by default;
 *   - genuinely strong, corroborated timing evidence — a recent close plus a new
 *     allocator plus rising RAUM — lands at 95 (raw 0.85), with the last stretch
 *     to 100 reserved for the unambiguous case.
 *
 * The knots are strictly increasing in BOTH coordinates, so the mapping is strictly
 * monotone: it can never swap the order of two LPs, only respace them. Retune by
 * editing the two arrays; nothing else reads them.
 */
public final class ProbabilityCalibration
{
    private static final double[] CURVE_RAW     = { 0.00, 0.10, 0.30, 0.60, 0.85, 1.00 };
    private static final double[] CURVE_DISPLAY = { 0.30, 0.42, 0.55, 0.78, 0.95, 1.00 };

    private ProbabilityCalibration() { }

    /**
     * Map a raw 0..1 PROBABILITY_NOW score onto the display curve.
     * Input is clamped to 0..1; output is always within 0..1.
     */
    public static double curve(double rawScore0)
    {
        double raw0 = clamp01(rawScore0);

        for (int i0 = 1; i0 < CURVE_RAW.length; i0++)
        {
            if (raw0 > CURVE_RAW[i0]) continue;

            double rawLo0 = CURVE_RAW[i0 - 1];
            double rawHi0 = CURVE_RAW[i0];
            double span0  = rawHi0 - rawLo0;
            // Degenerate knot spacing would divide by zero; snap to the lower knot.
            if (span0 <= 0.0) return CURVE_DISPLAY[i0 - 1];

            double t0 = (raw0 - rawLo0) / span0;
            return clamp01(CURVE_DISPLAY[i0 - 1]
                + t0 * (CURVE_DISPLAY[i0] - CURVE_DISPLAY[i0 - 1]));
        }

        return clamp01(CURVE_DISPLAY[CURVE_DISPLAY.length - 1]);
    }

    /**
     * Convenience for callers that already hold a 0..100 integer score (the Scout
     * path scores in points, not in 0..1). Returns the calibrated 0..100 value.
     */
    public static int curve0to100(int rawScore0)
    {
        return (int) Math.round(curve(rawScore0 / 100.0) * 100.0);
    }

    private static double clamp01(double v0)
    {
        if (v0 < 0.0) return 0.0;
        if (v0 > 1.0) return 1.0;
        return v0;
    }
}
