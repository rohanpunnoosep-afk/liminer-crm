package com.liminer.indicators;

import com.liminer.core.LpContext;
import com.liminer.enrich.ScrapeCache;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

/*
 * ThesisFitIndicator (Fit 2B) — derives a fit signal by matching the LP's existing
 * LPEnrichmentProcessor tags (sector / microsector / geography) against THIS GP's
 * own sector / stage / geo profile. Zero new scraping; read-only over data already
 * on the row + the GP profile in LpContext. Thread-safe.
 *
 * Confidence = normalized overlap strength, sector-dominant (exact sector overlap
 * outweighs geo-only overlap). asOfDate is the LP's last-enriched date — never
 * today — so marketing-tag staleness stays honest. Tags normalized (case /
 * punctuation / substring) before comparison so near-matches are not missed.
 *
 * Returns empty when the LP has no enrichment tags, or when the GP profile carries
 * nothing to compare against.
 */
public class ThesisFitIndicator implements Indicator
{
    // Sector alignment dominates; geography is a weaker corroborator.
    private static final double SECTOR_WEIGHT = 0.70;
    private static final double GEO_WEIGHT = 0.30;

    @Override
    public String axis() { return AXIS_FIT; }

    @Override
    public String name() { return "ThesisFit"; }

    @Override
    public IndicatorResult fetch(LpContext ctx, ScrapeCache cache)
    {
        if (ctx == null) return IndicatorResult.empty(AXIS_FIT);

        // LP side: sector tags (+ microsector) and geography from enrichment.
        List<String> lpSectors0 = tokens(ctx.sectorTags);
        addAll(lpSectors0, tokens(ctx.microsectorTags));
        List<String> lpGeos0 = tokens(ctx.geography);

        boolean lpHasTags0 = !lpSectors0.isEmpty() || !lpGeos0.isEmpty();
        if (!lpHasTags0) return IndicatorResult.empty(AXIS_FIT);

        // GP side: this fund's own thesis.
        GpHandle gp0 = (ctx.gpProfile == null) ? new GpHandle() : new GpHandle(ctx.gpProfile);
        if (gp0.sectors.isEmpty() && gp0.geos.isEmpty())
        {
            // Nothing to compare against — cannot score fit.
            return IndicatorResult.empty(AXIS_FIT);
        }

        List<String> sectorMatches0 = matches(lpSectors0, gp0.sectors);
        List<String> geoMatches0 = matches(lpGeos0, gp0.geos);

        double sectorOverlap0 = ratio(sectorMatches0.size(), lpSectors0.size());
        double geoOverlap0 = ratio(geoMatches0.size(), lpGeos0.size());

        // Weighted only over the axes the LP actually has tags for, so an LP with
        // sectors-but-no-geo is not penalized for missing geography.
        double confidence0 = weighted(sectorOverlap0, lpSectors0.isEmpty(),
                                      geoOverlap0, lpGeos0.isEmpty());

        String asOf0 = isBlank(ctx.lastEnrichedAt) ? LocalDate.now().toString() : ctx.lastEnrichedAt.trim();
        String value0 = summarize(sectorMatches0, geoMatches0);

        return new IndicatorResult(value0, confidence0, "enrichment-tags", asOf0, AXIS_FIT,
            "Overlap of LP enrichment tags vs GP thesis (sector-weighted).");
    }

    // Normalized GP thesis fields.
    private static class GpHandle
    {
        List<String> sectors = new ArrayList<String>();
        List<String> geos = new ArrayList<String>();
        GpHandle() {}
        GpHandle(LpContext.GpProfile p0)
        {
            sectors = tokens(p0.sectors);
            addAll(sectors, tokens(p0.microsectorTags));
            geos = tokens(p0.geographies);
        }
    }

    // Phrases in lp0 that align with any phrase in gp0 (exact-normalized or
    // substring either direction → catches "fintech" vs "financial technology").
    private List<String> matches(List<String> lp0, List<String> gp0)
    {
        List<String> out0 = new ArrayList<String>();
        for (String a0 : lp0)
        {
            for (String b0 : gp0)
            {
                if (a0.equals(b0) || a0.contains(b0) || b0.contains(a0))
                {
                    out0.add(a0);
                    break;
                }
            }
        }
        return out0;
    }

    private double ratio(int matched0, int total0)
    {
        if (total0 <= 0) return 0.0;
        double r0 = (double) matched0 / (double) total0;
        return r0 > 1.0 ? 1.0 : r0;
    }

    private double weighted(double sector0, boolean noSector0, double geo0, boolean noGeo0)
    {
        if (noSector0 && noGeo0) return 0.0;
        if (noSector0) return geo0;     // geo-only LP
        if (noGeo0) return sector0;     // sector-only LP
        double c0 = (SECTOR_WEIGHT * sector0) + (GEO_WEIGHT * geo0);
        return c0 > 1.0 ? 1.0 : c0;
    }

    private String summarize(List<String> sectorMatches0, List<String> geoMatches0)
    {
        if (sectorMatches0.isEmpty() && geoMatches0.isEmpty())
        {
            return "No sector/geo overlap with GP thesis";
        }
        StringBuilder sb0 = new StringBuilder();
        if (!sectorMatches0.isEmpty())
        {
            sb0.append("Sector overlap: ").append(String.join(", ", sectorMatches0));
        }
        if (!geoMatches0.isEmpty())
        {
            if (sb0.length() > 0) sb0.append("; ");
            sb0.append("Geo overlap: ").append(String.join(", ", geoMatches0));
        }
        return sb0.toString();
    }

    // Split a tag string into normalized, de-duplicated phrases.
    private static List<String> tokens(String raw0)
    {
        List<String> out0 = new ArrayList<String>();
        if (isBlank(raw0)) return out0;
        LinkedHashSet<String> seen0 = new LinkedHashSet<String>();
        String[] parts0 = raw0.split("[,;|/]+");
        for (String p0 : parts0)
        {
            String norm0 = normalize(p0);
            if (!norm0.isEmpty()) seen0.add(norm0);
        }
        out0.addAll(seen0);
        return out0;
    }

    private static String normalize(String s0)
    {
        if (s0 == null) return "";
        String n0 = s0.trim().toLowerCase();
        n0 = n0.replaceAll("[^a-z0-9 ]", " ");
        n0 = n0.replaceAll("\\s+", " ").trim();
        return n0;
    }

    private static void addAll(List<String> dest0, List<String> src0)
    {
        for (String s0 : src0)
        {
            if (!dest0.contains(s0)) dest0.add(s0);
        }
    }

    private static boolean isBlank(String s0) { return s0 == null || s0.trim().isEmpty(); }
}
