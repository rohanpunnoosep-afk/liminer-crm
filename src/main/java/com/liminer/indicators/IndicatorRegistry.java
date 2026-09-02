package com.liminer.indicators;

import java.util.ArrayList;
import java.util.List;

/*
 * IndicatorRegistry holds the registered Indicator leaves grouped by axis. It is
 * built ONCE in the static initializer (before any batch runs) and then only READ
 * during parallel row processing — treat it as effectively immutable after init.
 * Do NOT register indicators from inside row threads.
 *
 * Adding any future leaf = implement Indicator + add one register() line in the
 * static block below. The rollup stays list-driven (getByAxis), so no axis-specific
 * branching leaks into LPScoreProcessor.
 */
public class IndicatorRegistry
{
    private static final List<Indicator> INDICATORS = new ArrayList<Indicator>();

    static
    {
        // Indicators are wired in here as they are built (Tasks 6+).
        // CrmRelationshipIndicator (Fit 2A) intentionally NOT registered: the GP<->LP
        // relationship is a fast-moving signal (can shift on a single email) and is now
        // handled by the standalone Relationship Summary workflow, not folded into the
        // slow-moving market-intelligence FIT score. The class is kept for possible reuse.
        register(new ThesisFitIndicator());         // FIT  (Task 7)
        register(new HeadcountProxyIndicator());    // RESOURCES (Task 8)
        register(new RaumIndicator());              // RESOURCES (Task 10)
        register(new NonprofitAssetsIndicator());   // RESOURCES (Task 11)
        register(new ReportedAumIndicator());       // RESOURCES (Task 12)
        register(new FundCloseIndicator());         // PROBABILITY_NOW (Task 14)
        register(new NewAllocatorIndicator());      // PROBABILITY_NOW (Task 15)
        register(new ADVStrategyIndicator());       // FIT (Task 16)
        register(new DealVelocityIndicator());      // PROBABILITY_NOW (Task 18)
        //   ...
    }

    // Package-private-by-convention registration, invoked only from the static
    // block above so registration stays single-threaded and pre-batch.
    private static void register(Indicator indicator0)
    {
        if (indicator0 != null) INDICATORS.add(indicator0);
    }

    // All indicators registered for an axis (RESOURCES / FIT / PROBABILITY_NOW).
    // Returns a fresh list (never null); empty when nothing is registered for it.
    public static List<Indicator> getByAxis(String axis0)
    {
        List<Indicator> out0 = new ArrayList<Indicator>();
        if (axis0 == null) return out0;
        for (Indicator indicator0 : INDICATORS)
        {
            if (axis0.equals(indicator0.axis())) out0.add(indicator0);
        }
        return out0;
    }

    // Every registered indicator across all axes (fresh copy).
    public static List<Indicator> getAll()
    {
        return new ArrayList<Indicator>(INDICATORS);
    }
}
