package com.liminer.scout;

import java.util.HashSet;
import java.util.Set;

/*
 * ScoutDedupeIndex — plain data holder for the "already known" allocators to
 * drop from a scout run: normalized firm names, website domains, and CRD
 * numbers. Populated by a later orchestrator task from the client's CRM
 * (extending CandidateDiscoveryProcessor's PreEnrichmentCrmIndex) plus prior
 * scout-run output. ScoutPrefilter only reads this; it never touches Sheets.
 */
public class ScoutDedupeIndex
{
    private static final String[] LEGAL_SUFFIX_TOKENS = new String[]
    {
        "llc", "lp", "inc", "ltd", "advisors", "advisers", "capital", "management", "partners"
    };

    public Set<String> normalizedFirmNames;
    public Set<String> websiteDomains;
    public Set<Integer> crdNumbers;

    public ScoutDedupeIndex()
    {
        normalizedFirmNames = new HashSet<String>();
        websiteDomains = new HashSet<String>();
        crdNumbers = new HashSet<Integer>();
    }

    public void addFirmName(String firmName0)
    {
        String normalized0 = normalizeFirmName(firmName0);
        if (normalized0.length() > 0)
        {
            normalizedFirmNames.add(normalized0);
        }
    }

    public void addWebsite(String website0)
    {
        String domain0 = normalizeDomain(website0);
        if (domain0.length() > 0)
        {
            websiteDomains.add(domain0);
        }
    }

    public void addCrd(int crd0)
    {
        crdNumbers.add(crd0);
    }

    public boolean containsFirmName(String firmName0)
    {
        return normalizedFirmNames.contains(normalizeFirmName(firmName0));
    }

    public boolean containsWebsite(String website0)
    {
        return websiteDomains.contains(normalizeDomain(website0));
    }

    public boolean containsCrd(int crd0)
    {
        return crdNumbers.contains(crd0);
    }

    /*
     * Lowercase, strip punctuation, then repeatedly drop trailing legal-suffix
     * tokens (LLC/LP/Inc/Ltd/Advisors/Advisers/Capital/Management/Partners),
     * leaving at least one token, and join what remains with no separator.
     */
    public static String normalizeFirmName(String firmName0)
    {
        if (firmName0 == null)
        {
            return "";
        }

        String cleaned0 = firmName0.toLowerCase().replaceAll("[^a-z0-9\\s]", " ").trim();
        if (cleaned0.length() == 0)
        {
            return "";
        }

        String[] tokens0 = cleaned0.split("\\s+");
        int endExclusive0 = tokens0.length;

        while (endExclusive0 > 1 && isLegalSuffixToken(tokens0[endExclusive0 - 1]))
        {
            endExclusive0--;
        }

        StringBuilder sb0 = new StringBuilder();
        for (int i0 = 0; i0 < endExclusive0; i0++)
        {
            sb0.append(tokens0[i0]);
        }

        return sb0.toString();
    }

    private static boolean isLegalSuffixToken(String token0)
    {
        for (String suffix0 : LEGAL_SUFFIX_TOKENS)
        {
            if (suffix0.equals(token0))
            {
                return true;
            }
        }
        return false;
    }

    /*
     * Host without protocol, "www.", path/query, or trailing slash.
     */
    public static String normalizeDomain(String website0)
    {
        if (website0 == null)
        {
            return "";
        }

        String value0 = website0.trim().toLowerCase();
        if (value0.length() == 0)
        {
            return "";
        }

        value0 = value0.replaceFirst("^https?://", "");
        value0 = value0.replaceFirst("^www\\.", "");
        int slashIndex0 = value0.indexOf('/');
        if (slashIndex0 >= 0)
        {
            value0 = value0.substring(0, slashIndex0);
        }

        return value0.trim();
    }
}
