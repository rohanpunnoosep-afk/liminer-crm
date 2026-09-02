package com.liminer.scout;

import com.liminer.enrich.DiscoveredLinkedInTarget;
import com.liminer.enrich.LinkedInScrapeResult;

/*
 * ScoutSerpAdapter — pure mapper from Pipeline-3 SERP/LinkedIn discovery
 * output (DiscoveredLinkedInTarget + optional company LinkedInScrapeResult)
 * into the Scout funnel's ScoutUniverseRecord contract, for the segments
 * that don't file with a regulator (family offices, sovereigns, non-US
 * investors not otherwise covered by FcaRegisterClient/EsmaRegisterClient/
 * CompaniesHouseClient). No HTTP, no Bright Data calls, no Sheets access.
 *
 * crd stays 0 (no CRD concept for a SERP hit, same convention as the
 * external register clients). sourceRegister is set to SOURCE_REGISTER_SERP
 * so downstream stages (and InvestorScoutProcessor's gating) can identify
 * and treat this as the secondary channel it is. raumTotal/employees are
 * left at 0 (unresolved) unless the caller supplies resolved estimates from
 * a later enrichment stage - this mapper never fabricates a resources
 * signal from LinkedIn fields that aren't actually a resources measure
 * (e.g. follower counts are not a RAUM or headcount proxy).
 */
public class ScoutSerpAdapter
{
    public static final String SOURCE_REGISTER_SERP = "SERP_LINKEDIN";

    public static ScoutUniverseRecord mapCompanyTarget(
        DiscoveredLinkedInTarget target0,
        LinkedInScrapeResult companyScrape0)
    {
        ScoutUniverseRecord record0 = new ScoutUniverseRecord();
        if (target0 == null)
        {
            return record0;
        }

        record0.sourceRegister = SOURCE_REGISTER_SERP;
        record0.linkedinCompanyUrl = firstNonBlank(
            target0.url,
            companyScrape0 == null ? "" : companyScrape0.url
        );

        record0.firmName = firstNonBlank(
            companyScrape0 == null ? "" : companyScrape0.currentCompanyName,
            companyScrape0 == null ? "" : companyScrape0.name,
            cleanLinkedInTitle(target0.serpTitle)
        );

        if (companyScrape0 != null)
        {
            record0.website = safe(companyScrape0.companyWebsite);
            record0.country = safe(companyScrape0.country);
            record0.city = safe(companyScrape0.region);
        }

        return record0;
    }

    public static ScoutUniverseRecord mapCompanyTarget(
        DiscoveredLinkedInTarget target0,
        LinkedInScrapeResult companyScrape0,
        Double estimatedRaumTotal0,
        Integer estimatedEmployees0)
    {
        ScoutUniverseRecord record0 = mapCompanyTarget(target0, companyScrape0);
        if (estimatedRaumTotal0 != null)
        {
            record0.raumTotal = estimatedRaumTotal0;
        }
        if (estimatedEmployees0 != null)
        {
            record0.employees = estimatedEmployees0;
        }
        return record0;
    }

    private static String cleanLinkedInTitle(String title0)
    {
        if (title0 == null)
        {
            return "";
        }
        return title0
            .replace("| LinkedIn", "")
            .replace("LinkedIn", "")
            .trim();
    }

    private static String firstNonBlank(String a0, String b0)
    {
        if (a0 != null && a0.trim().length() > 0) return a0;
        if (b0 != null && b0.trim().length() > 0) return b0;
        return "";
    }

    private static String firstNonBlank(String a0, String b0, String c0)
    {
        if (a0 != null && a0.trim().length() > 0) return a0;
        if (b0 != null && b0.trim().length() > 0) return b0;
        if (c0 != null && c0.trim().length() > 0) return c0;
        return "";
    }

    private static String safe(String s0) { return s0 == null ? "" : s0; }
}
