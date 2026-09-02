package com.liminer.scout;

import java.util.ArrayList;
import java.util.List;

/*
 * ScoutPrefilter — the $0, CPU-only, local-data-only reduction stage of the
 * Investor Scout pipeline. Cuts the full SEC ADV universe (~20k advisers) down
 * to the plausible allocators for one client via four ordered filters: (1)
 * allocator test, (2) resources band derived from the client's check size, (3)
 * a coarse/loose geography gate, (4) dedupe against the client's CRM / prior
 * scout runs. Never makes an HTTP or LLM call.
 */
public class ScoutPrefilter
{
    public static final double ABSOLUTE_FLOOR_RAUM = 50_000_000.0;
    public static final double ABSOLUTE_CAP_RAUM = 50_000_000_000.0;
    private static final double ASSUMED_ALTERNATIVES_FRACTION_OF_RAUM = 0.25;
    private static final double MIN_CHECK_FRACTION_OF_ALTERNATIVES = 0.01;
    private static final double MAX_CHECK_FRACTION_OF_ALTERNATIVES = 0.10;

    private static final String[] ALLOCATOR_CLIENT_TYPE_KEYWORDS = new String[]
    {
        "pooled investment", "other investment adviser", "investment compan"
    };

    private static final String[] AMBIGUOUS_GEOGRAPHY_KEYWORDS = new String[]
    {
        "global", "worldwide", "anywhere", "international", "all region", "no preference"
    };

    private static final String[] US_GEOGRAPHY_ALIASES = new String[]
    {
        "us", "usa", "u.s.", "u.s.a.", "united states", "america", "north america"
    };

    private static final String[] US_COUNTRY_ALIASES = new String[]
    {
        "us", "usa", "u.s.", "u.s.a.", "united states", "united states of america"
    };

    // Small alias map of other single-country geography hints -> country aliases,
    // used only to catch obvious mismatches (this gate is intentionally loose).
    private static final String[][] OTHER_COUNTRY_ALIAS_GROUPS = new String[][]
    {
        { "united kingdom", "uk", "u.k.", "britain", "england" },
        { "canada" },
        { "australia" },
        { "germany" },
        { "france" },
        { "japan" },
        { "singapore" },
        { "switzerland" }
    };

    public ScoutPrefilterResult filter(
        List<ScoutUniverseRecord> universe0,
        ScoutClientProfile client0,
        ScoutDedupeIndex dedupe0)
    {
        ScoutPrefilterResult result0 = new ScoutPrefilterResult();
        if (universe0 == null)
        {
            return result0;
        }

        result0.totalInput = universe0.size();

        double minRaum0 = 0.0;
        double maxRaum0 = 0.0;
        if (client0 != null)
        {
            double[] band0 = computeResourcesBand(client0.effectiveCheckSize());
            minRaum0 = band0[0];
            maxRaum0 = band0[1];
        }

        String geography0 = client0 == null ? "" : client0.geography;

        List<ScoutUniverseRecord> survivors0 = new ArrayList<ScoutUniverseRecord>();

        for (ScoutUniverseRecord record0 : universe0)
        {
            if (record0 == null)
            {
                continue;
            }

            if (!passesAllocatorTest(record0))
            {
                result0.droppedAllocatorTest++;
                continue;
            }

            if (!passesResourcesBand(record0, minRaum0, maxRaum0))
            {
                result0.droppedResourcesBand++;
                continue;
            }

            if (!passesGeographyGate(record0, geography0))
            {
                result0.droppedGeography++;
                continue;
            }

            survivors0.add(record0);
        }

        List<ScoutUniverseRecord> deduped0 = new ArrayList<ScoutUniverseRecord>();
        for (ScoutUniverseRecord record0 : survivors0)
        {
            if (isDuplicate(record0, dedupe0))
            {
                result0.droppedDedupe++;
                continue;
            }

            deduped0.add(record0);
        }

        result0.kept = deduped0;
        return result0;
    }

    // ---- Filter 1: allocator test -------------------------------------------

    public static boolean passesAllocatorTest(ScoutUniverseRecord record0)
    {
        if (record0.funds != null)
        {
            for (ScoutFundRecord fund0 : record0.funds)
            {
                if (fund0 != null && fund0.type != null
                    && fund0.type.toLowerCase().contains("fund of funds"))
                {
                    return true;
                }
            }
        }

        if (record0.clientTypes != null)
        {
            for (String clientType0 : record0.clientTypes)
            {
                if (clientType0 == null)
                {
                    continue;
                }

                String lower0 = clientType0.toLowerCase();
                for (String keyword0 : ALLOCATOR_CLIENT_TYPE_KEYWORDS)
                {
                    if (lower0.contains(keyword0))
                    {
                        return true;
                    }
                }
            }
        }

        return false;
    }

    // ---- Filter 2: resources band --------------------------------------------

    /*
     * Returns { minRaum, maxRaum } given the client's check size. An LP check
     * is assumed to be 1-10% of an allocator's alternatives allocation, and
     * alternatives are assumed to be ~25% of total RAUM. Clamped to an
     * absolute floor of $50M and cap of $50B.
     */
    public static double[] computeResourcesBand(double checkSize0)
    {
        double minRaum0 = checkSize0 / (MAX_CHECK_FRACTION_OF_ALTERNATIVES * ASSUMED_ALTERNATIVES_FRACTION_OF_RAUM);
        double maxRaum0 = checkSize0 / (MIN_CHECK_FRACTION_OF_ALTERNATIVES * ASSUMED_ALTERNATIVES_FRACTION_OF_RAUM);

        if (minRaum0 < ABSOLUTE_FLOOR_RAUM)
        {
            minRaum0 = ABSOLUTE_FLOOR_RAUM;
        }

        if (maxRaum0 > ABSOLUTE_CAP_RAUM)
        {
            maxRaum0 = ABSOLUTE_CAP_RAUM;
        }

        if (maxRaum0 < minRaum0)
        {
            maxRaum0 = minRaum0;
        }

        return new double[] { minRaum0, maxRaum0 };
    }

    private boolean passesResourcesBand(ScoutUniverseRecord record0, double minRaum0, double maxRaum0)
    {
        return record0.raumTotal >= minRaum0 && record0.raumTotal <= maxRaum0;
    }

    // ---- Filter 3: geography gate ---------------------------------------------

    /*
     * Coarse and loose: drop only obvious country-level mismatches. Blank,
     * ambiguous, or global client geography keeps everything. Fine-grained
     * (state/region) geography fit is scored later, not here.
     */
    private boolean passesGeographyGate(ScoutUniverseRecord record0, String clientGeography0)
    {
        if (clientGeography0 == null || clientGeography0.trim().length() == 0)
        {
            return true;
        }

        String geo0 = clientGeography0.toLowerCase().trim();

        for (String ambiguous0 : AMBIGUOUS_GEOGRAPHY_KEYWORDS)
        {
            if (geo0.contains(ambiguous0))
            {
                return true;
            }
        }

        String recordCountry0 = record0.country == null ? "" : record0.country.toLowerCase().trim();
        if (recordCountry0.length() == 0)
        {
            return true;
        }

        boolean impliesUs0 = containsAnyAlias(geo0, US_GEOGRAPHY_ALIASES);
        if (impliesUs0)
        {
            return matchesAnyAlias(recordCountry0, US_COUNTRY_ALIASES);
        }

        for (String[] aliasGroup0 : OTHER_COUNTRY_ALIAS_GROUPS)
        {
            if (containsAnyAlias(geo0, aliasGroup0))
            {
                return matchesAnyAlias(recordCountry0, aliasGroup0);
            }
        }

        // Geography string doesn't resolve to a specific country we recognize
        // (e.g. a US state or a city name) - stay loose and keep the record.
        return true;
    }

    private boolean containsAnyAlias(String haystack0, String[] aliases0)
    {
        for (String alias0 : aliases0)
        {
            if (haystack0.contains(alias0))
            {
                return true;
            }
        }
        return false;
    }

    private boolean matchesAnyAlias(String country0, String[] aliases0)
    {
        for (String alias0 : aliases0)
        {
            if (country0.equals(alias0) || country0.contains(alias0))
            {
                return true;
            }
        }
        return false;
    }

    // ---- Filter 4: dedupe -------------------------------------------------------

    private boolean isDuplicate(ScoutUniverseRecord record0, ScoutDedupeIndex dedupe0)
    {
        if (dedupe0 == null)
        {
            return false;
        }

        if (dedupe0.containsCrd(record0.crd))
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
}
