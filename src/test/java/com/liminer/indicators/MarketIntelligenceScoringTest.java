package com.liminer.indicators;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/*
 * Regression tests for the market-intelligence scoring defects found on the
 * "Nelson Advisors" row: a $1.36B adviser scored 0 on RESOURCES and 0 on
 * PROBABILITY_NOW, and the brief reported an invented funding status.
 *
 * These lock in the two properties that fix is built on:
 *   1. score (magnitude) is a SEPARATE number from confidence (source trust), so
 *      capital size actually drives the RESOURCES axis.
 *   2. a figure that cannot be read is never silently scored as zero.
 */
public class MarketIntelligenceScoringTest
{
    // The GP-supplied calibration anchors. If someone retunes the curve, these are
    // the three points that must survive the retune.
    @Test
    public void resourceScaleHitsItsCalibrationAnchors()
    {
        assertEquals(75, round100(ResourceScale.scoreForDollars(50_000_000d)), 1,
            "$50M must score 75");
        assertEquals(95, round100(ResourceScale.scoreForDollars(250_000_000d)), 1,
            "$250M must score 95");
        assertEquals(100, round100(ResourceScale.scoreForDollars(1_000_000_000d)), 0.001,
            "$1B must score 100");
    }

    @Test
    public void resourceScaleIsMonotonicAndSaturates()
    {
        assertTrue(ResourceScale.scoreForDollars(10_000_000d)
                 < ResourceScale.scoreForDollars(100_000_000d),
            "a bigger balance sheet must never score lower");
        assertEquals(1.0, ResourceScale.scoreForDollars(50_000_000_000d), 0.0001,
            "everything at or above the $1B vertex saturates at 100");
    }

    // The Nelson figure, exactly as RaumIndicator formats it out of Item 5.F.
    @Test
    public void filedRaumOutOfFormAdvScoresFull()
    {
        String value = "RAUM $1,358,292,666; discretionary $0; "
            + "non-discretionary $1,358,292,666; accounts 3 (ADV filed 2026-02-21)";
        assertEquals(100, round100(ResourceScale.scoreForMoneyText(value)), 0.001,
            "a $1.358B filed RAUM must score 100, not 0");
    }

    /*
     * The heart of the original bug: RESOURCES rolled up CONFIDENCE, so two LPs with
     * equally clean filings scored identically no matter how much money they had.
     */
    @Test
    public void magnitudeNotConfidenceSeparatesLargeFromSmallLps
        ()
    {
        double small = ResourceScale.scoreForMoneyText("$12,000,000");
        double large = ResourceScale.scoreForMoneyText("$1,358,292,666");
        assertTrue(large > small + 0.4,
            "a $1.3B LP must score far above a $12M LP even though both figures come "
            + "from an equally trustworthy filing");
    }

    /*
     * "No readable figure" must be distinguishable from "a figure of zero", so a leaf
     * can return empty (blank cell) instead of a 0 that reads as "cannot spare a cent".
     */
    @Test
    public void unreadableFiguresAreNotScoredAsZero()
    {
        for (String noise : new String[]{"3 accounts", "2026", "no figure here", ""})
        {
            assertTrue(ResourceScale.scoreForMoneyText(noise) < 0.0,
                "\"" + noise + "\" carries no figure and must report absence, not 0");
        }
    }

    @Test
    public void moneyParsingHandlesTheShapesTheLeavesActuallySee()
    {
        assertEquals(1_358_292_666d, ResourceScale.parseMoney("$1,358,292,666"), 1d);
        assertEquals(1_200_000_000d, ResourceScale.parseMoney("$1.2 billion"), 1d);
        assertEquals(4_500_000_000d, ResourceScale.parseMoney("USD 4.5bn"), 1d);
        assertEquals(75_000_000d,    ResourceScale.parseMoney("approx. $75m"), 1d);
        assertEquals(203_483_842d,   ResourceScale.parseMoney(
            "Total assets 203483842; investment income 709671 (990 tax year 2023-12-31)"), 1d);
    }

    @Test
    public void headcountProxyCannotOutscoreARealFiling()
    {
        assertTrue(ResourceScale.scoreForHeadcount(100_000L)
                 < ResourceScale.scoreForDollars(1_000_000_000d),
            "a headcount guess must never reach the magnitude of a filed $1B RAUM");
    }

    /*
     * An empty leaf must not read as a present one — this is what lets the rollup
     * leave the cell blank instead of writing a fabricated 0.
     */
    @Test
    public void emptyResultIsNotPresentAndCarriesNoScore()
    {
        IndicatorResult empty = IndicatorResult.empty(Indicator.AXIS_RESOURCES);
        assertFalse(empty.isPresent(), "an empty result must never count as evidence");
        assertEquals(0.0, empty.score, 0.0001);
        assertEquals(0.0, empty.confidence, 0.0001);
    }

    @Test
    public void scoreAndConfidenceAreIndependentlySettable()
    {
        // A modest balance sheet read out of an authoritative filing: low score,
        // high confidence. Conflating the two is exactly what this guards against.
        IndicatorResult r = new IndicatorResult(
            "RAUM $12,000,000", 0.90, 0.47, "https://adviserinfo.sec.gov/firm/summary/1",
            "2026-02-21", Indicator.AXIS_RESOURCES, "test");
        assertEquals(0.90, r.confidence, 0.0001, "confidence must survive untouched");
        assertEquals(0.47, r.score, 0.0001, "score must survive untouched");
        assertTrue(r.isPresent());
    }

    private static double round100(double score01)
    {
        return Math.round(score01 * 100.0);
    }
}
