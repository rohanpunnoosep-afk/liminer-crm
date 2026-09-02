package com.liminer.scout;

import com.liminer.enrich.LinkedInScrapeResult;
import com.liminer.enrich.SerpResult;

import java.util.HashSet;
import java.util.Arrays;

/*
 * IdentityResolutionScorer is the data-quality guardrail for background checking.
 *
 * Determines whether SERP or LinkedIn scrape results match a known person/fund identity
 * before any data is written to the CRM. Use isSafeToAutoWrite() to gate writes.
 */
public class IdentityResolutionScorer
{
    public static final double THRESHOLD_SAFE = 0.85;
    public static final double THRESHOLD_REVIEW = 0.65;

    private static final HashSet<String> PUBLIC_EMAIL_DOMAINS;

    static
    {
        PUBLIC_EMAIL_DOMAINS = new HashSet<>(Arrays.asList(
            "gmail.com", "yahoo.com", "aol.com", "icloud.com", "outlook.com",
            "hotmail.com", "protonmail.com", "me.com", "live.com", "msn.com",
            "yahoo.co.uk", "yahoo.fr", "yahoo.de", "yahoo.co.jp",
            "googlemail.com", "mac.com", "comcast.net", "att.net",
            "sbcglobal.net", "verizon.net", "bellsouth.net"
        ));
    }

    public static boolean isUsefulWorkEmail(String email0)
    {
        return !isBlank(extractUsefulEmailDomain(email0));
    }

    public static String extractUsefulEmailDomain(String email0)
    {
        if (isBlank(email0))
        {
            return "";
        }
        int atIdx0 = email0.lastIndexOf('@');
        if (atIdx0 < 0 || atIdx0 >= email0.length() - 1)
        {
            return "";
        }
        String domain0 = email0.substring(atIdx0 + 1).trim().toLowerCase();
        if (PUBLIC_EMAIL_DOMAINS.contains(domain0))
        {
            return "";
        }
        if (!domain0.contains("."))
        {
            return "";
        }
        return domain0;
    }

    public static String normalizeName(String value0)
    {
        if (isBlank(value0))
        {
            return "";
        }
        return value0.trim().toLowerCase().replaceAll("[^a-z ]", "").trim();
    }

    public static String normalizeCompanyName(String value0)
    {
        if (isBlank(value0))
        {
            return "";
        }
        String normalized0 = value0.trim().toLowerCase();
        normalized0 = normalized0.replaceAll(
            "\\b(llc|lp|inc\\.?|ltd\\.?|limited|management|capital|ventures|venture|fund|partners|group|investments|investment|advisors|advisory)\\b",
            " "
        );
        normalized0 = normalized0.replaceAll("[^a-z ]", " ");
        normalized0 = normalized0.replaceAll("\\s+", " ").trim();
        return normalized0;
    }

    public static double scorePersonSerpResult(
        SerpResult result0,
        String firstName0,
        String lastName0,
        String fundName0,
        String fundWebsite0,
        String emailDomain0)
    {
        if (result0 == null)
        {
            return 0.0;
        }

        String title0 = safe(result0.title).toLowerCase();
        String snippet0 = safe(result0.snippet).toLowerCase();
        String url0 = safe(result0.url).toLowerCase();
        String combined0 = title0 + " " + snippet0;

        double score0 = 0.0;

        String normFirst0 = normalizeName(firstName0);
        String normLast0 = normalizeName(lastName0);
        boolean hasFirst0 = !isBlank(normFirst0) && combined0.contains(normFirst0);
        boolean hasLast0 = !isBlank(normLast0) && combined0.contains(normLast0);

        if (hasFirst0 && hasLast0)
        {
            score0 += 0.35;
        }
        else if (hasFirst0 && !isBlank(normLast0))
        {
            score0 += 0.12;
        }
        else if (hasFirst0)
        {
            score0 += 0.08;
        }

        if (!isBlank(fundName0))
        {
            String normFund0 = normalizeCompanyName(fundName0);
            if (!isBlank(normFund0) && combined0.contains(normFund0))
            {
                score0 += 0.30;
            }
        }

        if (!isBlank(emailDomain0) && url0.contains(emailDomain0))
        {
            score0 += 0.20;
        }

        if (!isBlank(fundWebsite0))
        {
            String fundDomain0 = getDomainFromUrl(fundWebsite0).toLowerCase();
            if (!isBlank(fundDomain0) && (url0.contains(fundDomain0) || combined0.contains(fundDomain0)))
            {
                score0 += 0.15;
            }
        }

        if (combined0.contains("partner") || combined0.contains("investor")
            || combined0.contains("managing director") || combined0.contains("general partner"))
        {
            score0 += 0.05;
        }

        return Math.min(1.0, Math.round(score0 * 100.0) / 100.0);
    }

    public static double scoreCompanySerpResult(
        SerpResult result0,
        String fundName0,
        String fundWebsite0,
        String emailDomain0)
    {
        if (result0 == null)
        {
            return 0.0;
        }

        String title0 = safe(result0.title).toLowerCase();
        String snippet0 = safe(result0.snippet).toLowerCase();
        String url0 = safe(result0.url).toLowerCase();
        String combined0 = title0 + " " + snippet0;

        double score0 = 0.0;

        if (!isBlank(fundName0))
        {
            String normFund0 = normalizeCompanyName(fundName0);
            String slugFund0 = normFund0.replace(" ", "");
            // Extract LinkedIn company slug for comparison: linkedin.com/company/northharbor-advisors → northharboradvisors
            String urlSlug0 = url0.replaceAll(".*/company/", "").replaceAll("[^a-z0-9]", "");

            if (!isBlank(normFund0) && title0.contains(normFund0))
            {
                score0 += 0.45;
            }
            else if (!isBlank(normFund0) && combined0.contains(normFund0))
            {
                score0 += 0.25;
            }
            else if (!isBlank(slugFund0) && !isBlank(urlSlug0)
                && (urlSlug0.contains(slugFund0) || slugFund0.contains(urlSlug0)))
            {
                // Handles "North Harbor" → "northharbor" matching slug "northharboradvisors"
                score0 += 0.30;
            }
        }

        if (!isBlank(fundWebsite0))
        {
            String fundDomain0 = getDomainFromUrl(fundWebsite0).toLowerCase();
            if (!isBlank(fundDomain0) && url0.contains(fundDomain0))
            {
                score0 += 0.35;
            }
        }

        // Email domain in URL or in snippet (company page may display the website URL)
        if (!isBlank(emailDomain0) && (url0.contains(emailDomain0) || combined0.contains(emailDomain0)))
        {
            score0 += 0.25;
        }

        return Math.min(1.0, Math.round(score0 * 100.0) / 100.0);
    }

    /**
     * Score a company SERP result that came from a domain-targeted query
     * (e.g. site:linkedin.com/company "northharboradvisors.com").
     * The search itself is evidence of association, so rank carries more weight.
     */
    public static double scoreCompanySerpResultByDomain(SerpResult result0, String domain0)
    {
        if (result0 == null || isBlank(domain0))
        {
            return 0.0;
        }

        String title0 = safe(result0.title).toLowerCase();
        String snippet0 = safe(result0.snippet).toLowerCase();
        String url0 = safe(result0.url).toLowerCase();
        String combined0 = title0 + " " + snippet0;
        String cleanDomain0 = domain0.toLowerCase().replace("www.", "");

        double score0 = 0.0;

        // Rank-based trust: this company appeared when we searched for the domain
        if (result0.rank == 1)
        {
            score0 += 0.65;
        }
        else if (result0.rank == 2)
        {
            score0 += 0.50;
        }
        else if (result0.rank <= 5)
        {
            score0 += 0.35;
        }

        // Domain appears in SERP snippet/title (SERP engine matched domain to this company)
        if (combined0.contains(cleanDomain0))
        {
            score0 += 0.20;
        }

        // Domain root appears in LinkedIn URL slug
        // e.g. "northharboradvisors.com" → root "northharbor"; URL "northharbor-advisors" → slug "northharboradvisors" contains "northharbor"
        String domainRoot0 = extractDomainRoot(cleanDomain0);
        if (!isBlank(domainRoot0))
        {
            String urlSlug0 = url0.replaceAll(".*/company/", "").replaceAll("[^a-z0-9]", "");
            if (!isBlank(urlSlug0) && urlSlug0.contains(domainRoot0))
            {
                score0 += 0.15;
            }
        }

        return Math.min(1.0, Math.round(score0 * 100.0) / 100.0);
    }

    private static String extractDomainRoot(String domain0)
    {
        if (isBlank(domain0))
        {
            return "";
        }
        String noTld0 = domain0;
        int dotIdx0 = domain0.lastIndexOf('.');
        if (dotIdx0 > 0)
        {
            noTld0 = domain0.substring(0, dotIdx0);
        }
        noTld0 = noTld0.toLowerCase();
        // Strip common suffixes that appear in domain names
        String[] suffixes0 = {"advisors", "advisory", "management", "capital", "ventures", "investments", "partners", "group", "associates", "consulting", "services"};
        for (String suffix0 : suffixes0)
        {
            if (noTld0.endsWith(suffix0))
            {
                noTld0 = noTld0.substring(0, noTld0.length() - suffix0.length());
                break;
            }
        }
        return noTld0.replaceAll("[^a-z0-9]", "");
    }

    public static double scorePersonLinkedInResult(
        LinkedInScrapeResult result0,
        String firstName0,
        String lastName0,
        String fundName0,
        String fundWebsite0,
        String emailDomain0)
    {
        if (result0 == null)
        {
            return 0.0;
        }

        double score0 = 0.0;

        String resultFirst0 = normalizeName(result0.firstName);
        String resultLast0 = normalizeName(result0.lastName);
        String expFirst0 = normalizeName(firstName0);
        String expLast0 = normalizeName(lastName0);
        boolean hasLastNameInput0 = !isBlank(expLast0);

        boolean firstMatch0 = !isBlank(expFirst0) && !isBlank(resultFirst0)
            && (resultFirst0.equals(expFirst0)
                || (expFirst0.length() >= 3 && resultFirst0.startsWith(expFirst0))
                || (resultFirst0.length() >= 3 && expFirst0.startsWith(resultFirst0)));
        // Compare both with and without spaces — handles "AL-DUAIJ" (input) vs "AL DUAIJ" (LinkedIn)
        boolean lastMatch0 = hasLastNameInput0 && !isBlank(resultLast0)
            && (resultLast0.equals(expLast0)
                || resultLast0.replace(" ", "").equals(expLast0.replace(" ", "")));

        double nameScore0 = 0.0;
        if (firstMatch0 && lastMatch0)
        {
            nameScore0 = 0.35;
        }
        else if (firstMatch0 && !hasLastNameInput0)
        {
            // No last name was provided as input — credit first name match more generously
            nameScore0 = 0.25;
        }
        else if (firstMatch0)
        {
            nameScore0 = 0.15;
        }
        else if (lastMatch0)
        {
            nameScore0 = 0.08;
        }
        score0 += nameScore0;

        double companyScore0 = 0.0;
        if (!isBlank(fundName0))
        {
            String normFund0 = normalizeCompanyName(fundName0);
            String normCurrent0 = normalizeCompanyName(result0.currentCompanyName);
            String slugFund0 = normFund0.replace(" ", "");
            String slugCurrent0 = normCurrent0.replace(" ", "");

            if (!isBlank(normFund0) && !isBlank(normCurrent0) && normCurrent0.contains(normFund0))
            {
                companyScore0 = 0.30;
            }
            else if (!isBlank(slugFund0) && !isBlank(slugCurrent0)
                && (slugCurrent0.contains(slugFund0) || slugFund0.contains(slugCurrent0)))
            {
                // Handles cases like "North Harbor" vs "Northharbor" (same after removing spaces)
                companyScore0 = 0.25;
            }
            else if (!isBlank(normFund0) && result0.headline.toLowerCase().contains(normFund0))
            {
                companyScore0 = 0.15;
            }

            if (!isBlank(normFund0) && result0.hasWorkedAt(fundName0))
            {
                companyScore0 = Math.max(companyScore0, 0.20);
            }
        }
        score0 += companyScore0;

        // Combination bonus: name AND company both independently agree → stronger evidence
        if (nameScore0 >= 0.15 && companyScore0 >= 0.20)
        {
            score0 += 0.15;
        }

        if (!isBlank(emailDomain0))
        {
            String compWebDomain0 = getDomainFromUrl(result0.companyWebsite).toLowerCase();
            if (!isBlank(compWebDomain0) && compWebDomain0.equals(emailDomain0))
            {
                score0 += 0.20;
            }
        }

        if (!isBlank(fundWebsite0))
        {
            String fundDomain0 = getDomainFromUrl(fundWebsite0).toLowerCase();
            String compWebDomain0 = getDomainFromUrl(result0.companyWebsite).toLowerCase();
            if (!isBlank(fundDomain0) && !isBlank(compWebDomain0) && compWebDomain0.equals(fundDomain0))
            {
                score0 += 0.15;
            }
        }

        return Math.min(1.0, Math.round(score0 * 100.0) / 100.0);
    }

    public static double scoreCompanyLinkedInResult(
        LinkedInScrapeResult result0,
        String fundName0,
        String fundWebsite0,
        String emailDomain0)
    {
        if (result0 == null)
        {
            return 0.0;
        }

        double score0 = 0.0;

        if (!isBlank(fundName0))
        {
            String normFund0 = normalizeCompanyName(fundName0);
            String normName0 = normalizeCompanyName(result0.name);

            if (!isBlank(normFund0) && !isBlank(normName0) && normName0.contains(normFund0))
            {
                score0 += 0.50;
            }
            else if (!isBlank(normFund0) && !isBlank(normName0) && normFund0.contains(normName0))
            {
                score0 += 0.35;
            }
        }

        if (!isBlank(fundWebsite0))
        {
            String fundDomain0 = getDomainFromUrl(fundWebsite0).toLowerCase();
            String compWebDomain0 = getDomainFromUrl(result0.companyWebsite).toLowerCase();
            if (!isBlank(fundDomain0) && !isBlank(compWebDomain0) && compWebDomain0.equals(fundDomain0))
            {
                score0 += 0.35;
            }
        }

        if (!isBlank(emailDomain0))
        {
            String compWebDomain0 = getDomainFromUrl(result0.companyWebsite).toLowerCase();
            if (!isBlank(compWebDomain0) && compWebDomain0.equals(emailDomain0))
            {
                score0 += 0.25;
            }
        }

        return Math.min(1.0, Math.round(score0 * 100.0) / 100.0);
    }

    public static boolean isSafeToAutoWrite(double confidence0)
    {
        return confidence0 >= THRESHOLD_SAFE;
    }

    static String getDomainFromUrl(String url0)
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
        catch (Exception e0)
        {
            return "";
        }
    }

    private static String safe(String value0)
    {
        return value0 == null ? "" : value0;
    }

    private static boolean isBlank(String value0)
    {
        return value0 == null || value0.trim().isEmpty();
    }
}
