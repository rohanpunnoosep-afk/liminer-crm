package com.liminer.scout;

import com.liminer.core.InvestorProfile;

import java.util.ArrayList;
import java.util.LinkedHashSet;

/*
 * SearchTermGenerator turns InvestorProfile tags into deterministic Google queries.
 *
 * Candidate discovery intentionally biases toward people:
 * - most generated queries target linkedin.com/in profiles
 * - fewer generated queries target linkedin.com/company pages
 */
public class SearchTermGenerator
{
    public static final int DEFAULT_MAX_QUERIES = 20;

    public SearchTermGenerator()
    {
    }

    public ArrayList<String> generateCandidateDiscoveryQueries(
        ArrayList<InvestorProfile> seedProfiles0)
    {
        return generateCandidateDiscoveryQueries(seedProfiles0, DEFAULT_MAX_QUERIES);
    }

    public ArrayList<String> generateCandidateDiscoveryQueries(
        ArrayList<InvestorProfile> seedProfiles0,
        int maxQueries0)
    {
        LinkedHashSet<String> personQueries0 = new LinkedHashSet<String>();
        LinkedHashSet<String> companyQueries0 = new LinkedHashSet<String>();

        if (seedProfiles0 == null || seedProfiles0.size() == 0)
        {
            return new ArrayList<String>();
        }

        for (InvestorProfile profile0 : seedProfiles0)
        {
            if (profile0 == null)
            {
                continue;
            }

            addQueriesFromTags(personQueries0, companyQueries0, profile0.microsectors, profile0.geographies, true);
            addQueriesFromTags(personQueries0, companyQueries0, profile0.sectors, profile0.geographies, false);
            addQueriesFromThesis(personQueries0, profile0.investmentThesis, profile0.geographies);
        }

        return combinePeopleThenCompanyQueries(personQueries0, companyQueries0, maxQueries0);
    }

    private ArrayList<String> combinePeopleThenCompanyQueries(
        LinkedHashSet<String> personQueries0,
        LinkedHashSet<String> companyQueries0,
        int maxQueries0)
    {
        ArrayList<String> result0 = new ArrayList<String>();

        if (maxQueries0 <= 0)
        {
            maxQueries0 = DEFAULT_MAX_QUERIES;
        }

        int personLimit0 = Math.max(1, (int) Math.ceil(maxQueries0 * 0.80));

        for (String query0 : personQueries0)
        {
            if (result0.size() >= personLimit0 || result0.size() >= maxQueries0)
            {
                break;
            }
            result0.add(query0);
        }

        for (String query0 : companyQueries0)
        {
            if (result0.size() >= maxQueries0)
            {
                break;
            }
            result0.add(query0);
        }

        for (String query0 : personQueries0)
        {
            if (result0.size() >= maxQueries0)
            {
                break;
            }
            if (!result0.contains(query0))
            {
                result0.add(query0);
            }
        }

        return result0;
    }

    public ArrayList<String> generateFundWebsiteQueries(String fundName0)
    {
        ArrayList<String> queries0 = new ArrayList<String>();

        if (isBlank(fundName0))
        {
            return queries0;
        }

        String q0 = quote(fundName0);
        queries0.add(q0 + " official website");
        queries0.add(q0 + " venture capital website");
        queries0.add(q0 + " investment firm website");
        queries0.add("site:linkedin.com/company " + q0);

        return queries0;
    }

    public ArrayList<String> generateCompanyLinkedInQueries(String fundName0)
    {
        ArrayList<String> queries0 = new ArrayList<String>();

        if (isBlank(fundName0))
        {
            return queries0;
        }

        String q0 = quote(fundName0);
        queries0.add("site:linkedin.com/company " + q0);
        queries0.add("site:linkedin.com/company " + q0 + " investment");
        queries0.add("site:linkedin.com/company " + q0 + " capital");

        return queries0;
    }

    /**
     * Domain-first company LinkedIn lookup: search by institutional email domain rather than fund name.
     * This is the most reliable approach when the LinkedIn company name may differ from the input fund name.
     * e.g. "northharboradvisors.com" → site:linkedin.com/company "northharboradvisors.com"
     */
    public ArrayList<String> generateCompanyLinkedInQueriesByDomain(String domain0)
    {
        ArrayList<String> queries0 = new ArrayList<String>();

        if (isBlank(domain0))
        {
            return queries0;
        }

        String cleanDomain0 = domain0.trim().toLowerCase().replace("www.", "");

        // Primary: search for domain string — most targeted
        queries0.add("site:linkedin.com/company " + quote(cleanDomain0));

        // Secondary: domain without TLD (e.g. "northharboradvisors.com" → "northharboradvisors")
        int dotIdx0 = cleanDomain0.lastIndexOf('.');
        if (dotIdx0 > 0)
        {
            String noTld0 = cleanDomain0.substring(0, dotIdx0);
            if (!isBlank(noTld0) && !noTld0.equals(cleanDomain0))
            {
                queries0.add("site:linkedin.com/company " + quote(noTld0));
            }
        }

        return queries0;
    }

    public ArrayList<String> generatePeopleAtFundQueries(String fundName0)
    {
        ArrayList<String> queries0 = new ArrayList<String>();

        if (isBlank(fundName0))
        {
            return queries0;
        }

        String q0 = quote(fundName0);
        queries0.add("site:linkedin.com/in " + q0 + " (\"Managing Partner\" OR \"General Partner\" OR Partner OR Founder)");
        queries0.add("site:linkedin.com/in " + q0 + " (\"Managing Director\" OR Principal OR Investor)");
        queries0.add("site:linkedin.com/in " + q0 + " (\"Investor Relations\" OR \"Head of Investments\")");

        return queries0;
    }

    public ArrayList<String> generatePersonLinkedInQueries(
        String firstName0,
        String lastName0,
        String fundName0)
    {
        return generatePersonLinkedInQueries(firstName0, lastName0, fundName0, "");
    }

    public ArrayList<String> generatePersonLinkedInQueries(
        String firstName0,
        String lastName0,
        String fundName0,
        String emailDomain0)
    {
        LinkedHashSet<String> queries0 = new LinkedHashSet<String>();

        String firstName0s = safeString(firstName0).trim();
        String lastName0s = safeString(lastName0).trim();
        String fullName0 = (firstName0s + " " + lastName0s).trim();
        boolean hasFullName0 = !isBlank(firstName0s) && !isBlank(lastName0s);
        boolean hasFirstName0 = !isBlank(firstName0s);
        boolean hasLastName0 = !isBlank(lastName0s);
        boolean hasEmailDomain0 = !isBlank(emailDomain0);

        if (!hasFirstName0 && !hasLastName0)
        {
            return new ArrayList<String>();
        }

        // Strong: full name + fund
        if (hasFullName0 && !isBlank(fundName0))
        {
            queries0.add("site:linkedin.com/in " + quote(fullName0) + " " + quote(fundName0));
        }

        // Strong: full name + institutional domain
        if (hasFullName0 && hasEmailDomain0)
        {
            queries0.add("site:linkedin.com/in " + quote(fullName0) + " " + quote(emailDomain0));
        }

        // First name + institutional domain when last name is not known
        if (hasFirstName0 && !hasLastName0 && hasEmailDomain0)
        {
            queries0.add("site:linkedin.com/in " + quote(firstName0s) + " " + quote(emailDomain0));
        }

        // Fallback only when neither fund nor domain is available to anchor the
        // search. When a fund or domain exists, the investor/partner keyword
        // variants are low-yield and just add SERP calls, so they are omitted.
        if (hasFullName0 && isBlank(fundName0) && !hasEmailDomain0)
        {
            queries0.add("site:linkedin.com/in " + quote(fullName0) + " investor");
        }

        // Medium: first name + fund (when last name is blank)
        if (hasFirstName0 && !hasLastName0 && !isBlank(fundName0))
        {
            queries0.add("site:linkedin.com/in " + quote(firstName0s) + " " + quote(fundName0));
        }

        // Medium: last name + fund (when first name is blank)
        if (hasLastName0 && !hasFirstName0 && !isBlank(fundName0))
        {
            queries0.add("site:linkedin.com/in " + quote(lastName0s) + " " + quote(fundName0));
        }

        // Also: last name + fund when BOTH names known — catches nickname/full-name mismatches
        // e.g. firstName="Ken" lastName="Chomitz" won't match "Kenneth Chomitz" as an exact phrase,
        // but "Chomitz" alone + fund finds "Kenneth Chomitz" and the SERP scorer handles the prefix match.
        if (hasLastName0 && hasFirstName0 && !isBlank(fundName0))
        {
            queries0.add("site:linkedin.com/in " + quote(lastName0s) + " " + quote(fundName0));
        }

        return new ArrayList<String>(queries0);
    }

    public ArrayList<String> generateContactWebsiteBioQueries(
        String firstName0,
        String lastName0,
        String fundWebsite0)
    {
        ArrayList<String> queries0 = new ArrayList<String>();

        if (isBlank(fundWebsite0))
        {
            return queries0;
        }

        String domain0 = extractDomainFromUrl(fundWebsite0);
        if (isBlank(domain0))
        {
            return queries0;
        }

        String firstName0s = safeString(firstName0).trim();
        String lastName0s = safeString(lastName0).trim();

        if (isBlank(firstName0s) && isBlank(lastName0s))
        {
            return queries0;
        }

        // Strongest: site:domain.com "First" "Last"
        if (!isBlank(firstName0s) && !isBlank(lastName0s))
        {
            queries0.add("site:" + domain0 + " " + quote(firstName0s) + " " + quote(lastName0s));
            queries0.add("site:" + domain0 + "/team " + quote(firstName0s) + " " + quote(lastName0s));
            queries0.add("site:" + domain0 + "/people " + quote(firstName0s) + " " + quote(lastName0s));
            queries0.add("site:" + domain0 + " " + quote(firstName0s) + " " + quote(lastName0s) + " bio");
        }

        // Fallback: first name only on domain
        if (!isBlank(firstName0s))
        {
            queries0.add("site:" + domain0 + " " + quote(firstName0s));
        }

        return queries0;
    }

    public ArrayList<String> generateOpenWebCareerQueries(
        String firstName0, String lastName0, String fundName0)
    {
        ArrayList<String> queries0 = new ArrayList<String>();
        String fn0 = safeString(firstName0).trim();
        String ln0 = safeString(lastName0).trim();
        String fund0 = safeString(fundName0).trim();
        if ((isBlank(fn0) && isBlank(ln0)) || isBlank(fund0)) { return queries0; }
        String person0 = (fn0 + " " + ln0).trim();
        queries0.add(quote(person0) + " " + quote(fund0) + " career background");
        return queries0;
    }

    private String extractDomainFromUrl(String url0)
    {
        if (isBlank(url0))
        {
            return "";
        }
        try
        {
            String normalized0 = url0.trim();
            if (!normalized0.startsWith("http"))
            {
                normalized0 = "https://" + normalized0;
            }
            java.net.URI uri0 = java.net.URI.create(normalized0);
            String host0 = uri0.getHost();
            if (host0 == null)
            {
                return "";
            }
            host0 = host0.toLowerCase();
            if (host0.startsWith("www."))
            {
                host0 = host0.substring(4);
            }
            return host0;
        }
        catch (Exception exception0)
        {
            return "";
        }
    }

    private void addQueriesFromTags(
        LinkedHashSet<String> personQueries0,
        LinkedHashSet<String> companyQueries0,
        String[] tags0,
        String[] geographies0,
        boolean microsector0)
    {
        if (tags0 == null)
        {
            return;
        }

        for (String tag0 : tags0)
        {
            if (isBlank(tag0))
            {
                continue;
            }

            String quotedTag0 = quote(tag0);
            String geography0 = firstNonBlank(geographies0);

            personQueries0.add("site:linkedin.com/in " + quotedTag0 + " (investor OR partner OR founder OR \"managing director\")");
            personQueries0.add("site:linkedin.com/in " + quotedTag0 + " \"venture partner\"");
            personQueries0.add("site:linkedin.com/in " + quotedTag0 + " \"seed investor\"");
            personQueries0.add("site:linkedin.com/in " + quotedTag0 + " \"general partner\"");

            if (!isBlank(geography0))
            {
                personQueries0.add("site:linkedin.com/in " + quotedTag0 + " investor " + quote(geography0));
                personQueries0.add("site:linkedin.com/in " + quotedTag0 + " partner " + quote(geography0));
            }

            companyQueries0.add("site:linkedin.com/company " + quotedTag0 + " \"venture capital\"");
            companyQueries0.add("site:linkedin.com/company " + quotedTag0 + " \"seed fund\"");

            if (!isBlank(geography0))
            {
                companyQueries0.add("site:linkedin.com/company " + quotedTag0 + " fund " + quote(geography0));
            }

            if (!microsector0)
            {
                personQueries0.add("site:linkedin.com/in " + quotedTag0 + " \"investment partner\"");
            }
        }
    }

    private void addQueriesFromThesis(
        LinkedHashSet<String> personQueries0,
        String thesis0,
        String[] geographies0)
    {
        if (isBlank(thesis0))
        {
            return;
        }

        String[] usefulPhrases0 = extractUsefulPhrases(thesis0);
        String geography0 = firstNonBlank(geographies0);

        for (String phrase0 : usefulPhrases0)
        {
            if (isBlank(phrase0))
            {
                continue;
            }

            personQueries0.add("site:linkedin.com/in " + quote(phrase0) + " investor");
            personQueries0.add("site:linkedin.com/in " + quote(phrase0) + " partner");

            if (!isBlank(geography0))
            {
                personQueries0.add("site:linkedin.com/in " + quote(phrase0) + " investor " + quote(geography0));
            }
        }
    }

    private String[] extractUsefulPhrases(String thesis0)
    {
        ArrayList<String> phrases0 = new ArrayList<String>();
        String lower0 = thesis0.toLowerCase();

        String[] candidates0 = new String[]
        {
            "ai", "artificial intelligence", "b2b saas", "enterprise software",
            "vertical saas", "developer tools", "sales automation", "crm",
            "fintech", "healthcare", "climate", "deep tech", "seed"
        };

        for (String candidate0 : candidates0)
        {
            if (lower0.contains(candidate0))
            {
                phrases0.add(candidate0);
            }
        }

        return phrases0.toArray(new String[0]);
    }

    private String firstNonBlank(String[] values0)
    {
        if (values0 == null)
        {
            return "";
        }

        for (String value0 : values0)
        {
            if (!isBlank(value0))
            {
                return value0.trim();
            }
        }

        return "";
    }

    private String quote(String value0)
    {
        return "\"" + safeString(value0).replace("\"", "") + "\"";
    }

    private static boolean isBlank(String value0)
    {
        return value0 == null || value0.trim().length() == 0;
    }

    private static String safeString(String value0)
    {
        return value0 == null ? "" : value0;
    }
}
