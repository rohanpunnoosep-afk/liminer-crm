package com.liminer.scout;

import com.liminer.enrich.LinkedInAffiliation;
import com.liminer.enrich.LinkedInScrapeResult;

import java.util.ArrayList;

/*
 * EmployerSelector picks which of a person's LinkedIn affiliations is the investing firm
 * they actually work for.
 *
 * Investors normally carry several concurrent affiliations: the fund they run, an advisory
 * seat at a second fund, a board seat, and membership in a professional body. Bright Data
 * reports whichever one sits at the top of the profile as "current_company", so taking that
 * field at face value picked "American College of Healthcare Executives" for a physician
 * investor whose fund is Sphere Investments.
 *
 * Scoring combines three independent signals:
 * - the company name (does it read like a fund, or like an association?)
 * - the title (is it an operating role, or a membership/advisory seat?)
 * - the role's recency and position in the list.
 *
 * A selection whose score falls below CONFIDENT_SCORE0 is worth cross-checking against an
 * open-web search before it is written to the CRM.
 *
 * The harder case, measured against the live API on 2026-09-11, is that Bright Data often
 * returns "experience": null and nothing but the topcard. Ranking then has a single input
 * and rubber-stamps whatever LinkedIn happened to put on top - which is how an operating
 * company outranked the fund for an investor whose headline named both. On those profiles
 * the headline is the only place the second affiliation survives, so parseHeadlineAffiliations
 * recovers it, and a pick that still rests on the topcard alone is reported as not confident
 * however well it scores.
 */
public class EmployerSelector
{
    /** A pick at or above this score is trusted without an open-web cross-check. */
    public static final double CONFIDENT_SCORE0 = 3.0;

    /** Below this, the affiliation is more likely a membership than an employer. */
    public static final double MINIMUM_SCORE0 = 0.0;

    private static final String[] INVESTING_FIRM_TOKENS0 = {
        "capital", "ventures", "venture", "partners", "fund", "funds", "investment",
        "investments", "investors", "asset management", "advisors", "advisers", "equity",
        "holdings", "family office", "management", "wealth", "securities", "endowment",
        "trust", "financial"
    };

    private static final String[] MEMBERSHIP_BODY_TOKENS0 = {
        "association", "college of", "american college", "society", "institute",
        "academy", "council", "chapter", "alumni", "university", "school of",
        "chamber of", "committee", "board of directors", "club", "coalition",
        "federation", "guild", "fellowship", "network of", "forum", "ministry",
        "hospital", "medical center", "health system", "clinic", "nonprofit",
        "charity", "volunteer"
    };

    private static final String[] OPERATING_TITLE_TOKENS0 = {
        "managing partner", "general partner", "founding partner", "co-founder",
        "cofounder", "founder", "managing director", "managing member", "partner",
        "principal", "chief investment officer", "cio", "chief executive", "ceo",
        "portfolio manager", "head of", "director of investments", "investment director",
        "president", "owner", "vice president"
    };

    /*
     * Titles that only exist inside an investing firm. An investor who also runs an
     * operating company shows both roles in one headline ("President & CEO @VedaBio //
     * Venture Partner @ OMX Ventures"); both read as operating titles, so the investing
     * role needs its own weight for the fund to win the row the CRM is about.
     */
    private static final String[] INVESTING_ROLE_TOKENS0 = {
        "venture partner", "general partner", "managing partner", "limited partner",
        "investment partner", "operating partner", "investment director",
        "chief investment officer", "portfolio manager", "investor", "angel investor",
        "venture capital", "managing director, investments", "head of investments"
    };

    private static final String[] PERIPHERAL_TITLE_TOKENS0 = {
        "member", "fellow", "advisor", "adviser", "advisory board", "board member",
        "board of directors", "trustee", "mentor", "volunteer", "ambassador",
        "speaker", "contributor", "committee", "delegate", "affiliate", "diplomate"
    };

    /**
     * One affiliation and why it scored the way it did. Returned rather than a bare name so
     * the caller can log the reasoning and decide whether to cross-check the pick.
     */
    public static class EmployerChoice
    {
        public LinkedInAffiliation affiliation;
        public double score;
        public String reason;
        public boolean confident;

        /**
         * True when the pick rests on the profile topcard alone, because Bright Data
         * returned no experience rows and the headline named no other firm. Nothing was
         * ranked, so the score means nothing and the pick is never confident.
         */
        public boolean topcardOnly;

        public EmployerChoice(LinkedInAffiliation affiliation0, double score0, String reason0)
        {
            affiliation = affiliation0;
            score = score0;
            reason = reason0 == null ? "" : reason0;
            confident = affiliation0 != null && score0 >= CONFIDENT_SCORE0;
            topcardOnly = false;
        }

        public void markTopcardOnly()
        {
            topcardOnly = true;
            confident = false;
            reason = isBlank(reason) ? "topcard only" : reason + ", topcard only";
        }

        public String getCompanyName()
        {
            return affiliation == null ? "" : affiliation.companyName;
        }

        public String getCompanyLinkedInUrl()
        {
            return affiliation == null ? "" : affiliation.companyLinkedInUrl;
        }
    }

    public EmployerChoice selectEmployer(LinkedInScrapeResult scrape0)
    {
        return selectEmployer(scrape0, "");
    }

    /**
     * Picks the investing employer, widening the candidate pool with the headline when
     * Bright Data returned no experience rows.
     *
     * headlineText0 is the person's headline as the SERP reported it, which is where the
     * headline survives when the profile payload omits it: the LinkedIn result for a person
     * renders as "&lt;headline&gt; - Experience: X - Location: Y". It is the only remaining
     * record of a second concurrent affiliation on a profile whose experience array is null.
     */
    public EmployerChoice selectEmployer(LinkedInScrapeResult scrape0, String headlineText0)
    {
        if (scrape0 == null)
        {
            return new EmployerChoice(null, 0.0, "no LinkedIn scrape");
        }

        String headline0 = firstNonBlankText(scrape0.headline, headlineText0);

        ArrayList<LinkedInAffiliation> pool0 = new ArrayList<LinkedInAffiliation>();

        if (scrape0.affiliations != null)
        {
            pool0.addAll(scrape0.affiliations);
        }

        if (scrape0.experienceSectionMissing)
        {
            pool0.addAll(parseHeadlineAffiliations(headline0, pool0.size()));
        }

        EmployerChoice choice0 = selectEmployer(pool0, headline0);

        if (scrape0.experienceSectionMissing
            && choice0.affiliation != null
            && LinkedInAffiliation.SOURCE_TOPCARD.equals(choice0.affiliation.source))
        {
            choice0.markTopcardOnly();
        }

        return choice0;
    }

    /**
     * Reads the concurrent affiliations out of a LinkedIn headline.
     *
     * A headline packs the roles a person wants to be known by into one line, separated by
     * "//", "|" or a bullet, each written as "&lt;title&gt; @ &lt;firm&gt;" or
     * "&lt;title&gt; at &lt;firm&gt;". When the experience array is null this is the only
     * place the fund appears at all: "President &amp; CEO @VedaBio // Venture Partner @ OMX
     * Ventures" yields both firms, and scoring then prefers the investing one.
     *
     * SERP text is accepted as-is, so the trailing "- Experience: ... - Location: ..."
     * LinkedIn appends to a search result is trimmed off first; those segments describe the
     * result, not the person's roles, and "Experience: VedaBio" would otherwise re-add the
     * operating company as a third affiliation.
     */
    public static ArrayList<LinkedInAffiliation> parseHeadlineAffiliations(
        String headlineText0,
        int startOrder0)
    {
        ArrayList<LinkedInAffiliation> parsed0 = new ArrayList<LinkedInAffiliation>();

        String text0 = stripSerpTail(headlineText0);

        if (isBlank(text0))
        {
            return parsed0;
        }

        for (String segment0 : text0.split("//|\\||;|\u00b7|\u2022"))
        {
            String chunk0 = segment0.trim();

            if (isBlank(chunk0))
            {
                continue;
            }

            String title0 = "";
            String company0 = "";

            int at0 = chunk0.indexOf('@');

            if (at0 >= 0)
            {
                title0 = chunk0.substring(0, at0).trim();
                company0 = chunk0.substring(at0 + 1).trim();
            }
            else
            {
                java.util.regex.Matcher match0 = java.util.regex.Pattern
                    .compile("(?i)^(.{2,60}?)\\s+at\\s+(.{2,60})$")
                    .matcher(chunk0);

                if (!match0.find())
                {
                    continue;
                }

                title0 = match0.group(1).trim();
                company0 = match0.group(2).trim();
            }

            company0 = cleanCompanyFragment(company0);

            if (isBlank(company0) || company0.length() > 60)
            {
                continue;
            }

            LinkedInAffiliation affiliation0 = new LinkedInAffiliation(
                company0,
                "",
                title0,
                "Present",
                true,
                startOrder0 + parsed0.size()
            );

            affiliation0.source = LinkedInAffiliation.SOURCE_HEADLINE;

            parsed0.add(affiliation0);
        }

        return parsed0;
    }

    /*
     * Drops the metadata LinkedIn appends to a search result, plus anything after a
     * sentence break, leaving only the headline itself.
     */
    private static String stripSerpTail(String text0)
    {
        String value0 = text0 == null ? "" : text0.trim();

        for (String marker0 : new String[]{
            "Experience:", "Location:", "Education:", "connections on LinkedIn",
            "followers on LinkedIn", "View ", "Read more"})
        {
            int index0 = value0.indexOf(marker0);

            if (index0 >= 0)
            {
                value0 = value0.substring(0, index0);
            }
        }

        return value0.trim();
    }

    /*
     * Trims a company fragment taken off the end of a headline segment: trailing separators
     * and connector words ("and", "&") that belong to the sentence rather than the name.
     */
    private static String cleanCompanyFragment(String company0)
    {
        String value0 = company0 == null ? "" : company0.trim();

        value0 = value0.replaceAll("^[\\-\\u2013\\u2014:,.\\s]+", "");
        value0 = value0.replaceAll("[\\-\\u2013\\u2014:,.\\s]+$", "");

        if (value0.toLowerCase().endsWith(" and"))
        {
            value0 = value0.substring(0, value0.length() - 4).trim();
        }

        return value0;
    }

    private static String firstNonBlankText(String first0, String second0)
    {
        return isBlank(first0) ? (second0 == null ? "" : second0.trim()) : first0.trim();
    }

    public EmployerChoice selectEmployer(
        ArrayList<LinkedInAffiliation> affiliations0,
        String headline0)
    {
        if (affiliations0 == null || affiliations0.isEmpty())
        {
            return new EmployerChoice(null, 0.0, "no affiliations on profile");
        }

        // A role someone has left is never the fund to file them under, however strong its
        // name reads, so ongoing roles are a hard filter rather than a scoring bonus. This
        // does not settle a profile like the motivating one, where the society membership
        // and the fund are BOTH ongoing - the name and title scoring still has to rank the
        // survivors - but it stops a past employer outranking a current one on name alone.
        ArrayList<LinkedInAffiliation> considered0 = filterToCurrent(affiliations0);
        boolean currentOnly0 = considered0.size() < affiliations0.size();

        LinkedInAffiliation best0 = null;
        double bestScore0 = -Double.MAX_VALUE;
        String bestReason0 = "";

        for (LinkedInAffiliation affiliation0 : considered0)
        {
            if (affiliation0 == null || !affiliation0.hasCompanyName())
            {
                continue;
            }

            StringBuilder reason0 = new StringBuilder();
            double score0 = scoreAffiliation(affiliation0, headline0, reason0);

            if (score0 > bestScore0)
            {
                bestScore0 = score0;
                best0 = affiliation0;
                bestReason0 = reason0.toString();
            }
        }

        if (best0 == null)
        {
            return new EmployerChoice(null, 0.0, "no named company on any affiliation");
        }

        if (currentOnly0)
        {
            bestReason0 = isBlank(bestReason0) ? "ongoing role" : bestReason0 + ", ongoing role";
        }

        return new EmployerChoice(best0, bestScore0, bestReason0);
    }

    /*
     * Keeps only the roles that are still ongoing. Falls back to the full list when none is
     * marked current: an unparsed date shape must not empty the candidate set and leave the
     * person with no employer at all.
     */
    static ArrayList<LinkedInAffiliation> filterToCurrent(ArrayList<LinkedInAffiliation> affiliations0)
    {
        ArrayList<LinkedInAffiliation> current0 = new ArrayList<LinkedInAffiliation>();

        for (LinkedInAffiliation affiliation0 : affiliations0)
        {
            if (affiliation0 != null && affiliation0.current && affiliation0.hasCompanyName())
            {
                current0.add(affiliation0);
            }
        }

        return current0.isEmpty() ? affiliations0 : current0;
    }

    /*
     * Scores one affiliation. Positive means "looks like the investing firm this person
     * works at"; negative means "looks like a membership or honorary seat".
     */
    public double scoreAffiliation(
        LinkedInAffiliation affiliation0,
        String headline0,
        StringBuilder reason0)
    {
        double score0 = 0.0;

        String company0 = lower(affiliation0.companyName);
        String title0 = lower(affiliation0.title);

        if (containsAny(company0, INVESTING_FIRM_TOKENS0))
        {
            score0 += 2.5;
            note(reason0, "investing firm name");
        }

        if (containsAny(company0, MEMBERSHIP_BODY_TOKENS0))
        {
            score0 -= 3.0;
            note(reason0, "membership body name");
        }

        if (containsAny(title0, OPERATING_TITLE_TOKENS0))
        {
            score0 += 2.0;
            note(reason0, "operating title");
        }

        // An investing role is what makes the row belong in a fundraising CRM at all. It
        // stacks on the operating bonus so that, between two operating roles held at once,
        // the fund beats the portfolio company.
        if (containsAny(title0, INVESTING_ROLE_TOKENS0))
        {
            score0 += 2.0;
            note(reason0, "investing role");
        }

        // A peripheral title only counts against the affiliation when no operating title is
        // present: "Managing Partner and board member" is still an operating role.
        if (!containsAny(title0, OPERATING_TITLE_TOKENS0) && containsAny(title0, PERIPHERAL_TITLE_TOKENS0))
        {
            score0 -= 2.0;
            note(reason0, "membership/advisory title");
        }

        if (affiliation0.current)
        {
            score0 += 1.0;
            note(reason0, "current role");
        }

        // Tenure breaks ties between two ongoing roles: a decade at a firm reads more like a
        // job than a nominal seat. Capped low so a fund founded last year still wins on its
        // name and title.
        double tenureYears0 = affiliation0.tenureYears();

        if (tenureYears0 >= 3.0)
        {
            score0 += Math.min(tenureYears0 / 10.0, 0.5);
            note(reason0, String.format("%.0f yr tenure", tenureYears0));
        }

        // The headline is what the person calls themselves, so a company they name there is
        // almost always the one they consider their employer.
        String headlineLower0 = lower(headline0);
        if (!isBlank(headlineLower0) && !isBlank(company0) && headlineLower0.contains(stripSuffixes(company0)))
        {
            score0 += 1.5;
            note(reason0, "named in headline");
        }

        if (!isBlank(affiliation0.companyLinkedInUrl))
        {
            score0 += 0.5;
            note(reason0, "has company LinkedIn");
        }

        // Earlier rows break ties only, never outrank a substantive signal.
        score0 -= affiliation0.listOrder * 0.1;

        return score0;
    }

    /**
     * True when the selected employer is weak enough that an open-web search for the person
     * is worth running before the fund name is written to the CRM.
     */
    public static boolean needsCrossCheck(EmployerChoice choice0)
    {
        return choice0 == null || choice0.affiliation == null || choice0.score < CONFIDENT_SCORE0;
    }

    /**
     * True when a company name reads like a professional body, hospital or school rather
     * than an investing firm. Used to reject an open-web or SERP-derived fund name too.
     */
    public static boolean looksLikeMembershipBody(String companyName0)
    {
        return containsAny(lower(companyName0), MEMBERSHIP_BODY_TOKENS0);
    }

    /**
     * True when a company name carries a word investing firms use. Lets callers prefer a
     * cross-checked name over a LinkedIn pick that reads like a society.
     */
    public static boolean looksLikeInvestingFirm(String companyName0)
    {
        return containsAny(lower(companyName0), INVESTING_FIRM_TOKENS0);
    }

    /**
     * Pulls a firm name out of free text by finding a capitalised phrase that ends in a word
     * investing firms use ("SPHERE Investments", "OMX Ventures", "Flagler Investment").
     *
     * This is the last line of defence for a profile whose experience array is null and
     * whose headline names no firm. The person's name still returns pages that say where
     * they work - "Dharma heads SPHERE's Strategic Partnerships division", "SPHERE
     * Investments Email Formats" - so the firm is recoverable from the search text even
     * when the result link itself is unusable.
     */
    public static String extractInvestingFirmName(String text0)
    {
        if (isBlank(text0))
        {
            return "";
        }

        StringBuilder alternatives0 = new StringBuilder();

        for (String token0 : new String[]{
            "Capital", "Ventures", "Partners", "Investments", "Investment", "Advisors",
            "Advisers", "Asset Management", "Equity", "Holdings", "Fund", "Funds"})
        {
            if (alternatives0.length() > 0)
            {
                alternatives0.append("|");
            }

            alternatives0.append(java.util.regex.Pattern.quote(token0));
        }

        // One to three leading words, each starting upper-case (so "SPHERE Investments" and
        // "OMX Ventures" match but "the investments" does not), then the firm-type word.
        java.util.regex.Matcher match0 = java.util.regex.Pattern
            .compile("((?:[A-Z][\\w&.'-]*\\s+){1,3}(?:" + alternatives0 + "))\\b")
            .matcher(text0);

        while (match0.find())
        {
            String candidate0 = match0.group(1).trim();

            if (looksLikeMembershipBody(candidate0))
            {
                continue;
            }

            return candidate0;
        }

        return "";
    }

    private static String stripSuffixes(String company0)
    {
        String cleaned0 = company0;

        for (String suffix0 : new String[]{", llc", " llc", ", inc", " inc.", " inc", " lp", " l.p.", " ltd", " limited"})
        {
            if (cleaned0.endsWith(suffix0))
            {
                cleaned0 = cleaned0.substring(0, cleaned0.length() - suffix0.length()).trim();
            }
        }

        return cleaned0;
    }

    private static void note(StringBuilder reason0, String text0)
    {
        if (reason0 == null)
        {
            return;
        }

        if (reason0.length() > 0)
        {
            reason0.append(", ");
        }

        reason0.append(text0);
    }

    private static boolean containsAny(String value0, String[] tokens0)
    {
        if (isBlank(value0))
        {
            return false;
        }

        for (String token0 : tokens0)
        {
            if (value0.contains(token0))
            {
                return true;
            }
        }

        return false;
    }

    private static String lower(String value0)
    {
        return value0 == null ? "" : value0.trim().toLowerCase();
    }

    private static boolean isBlank(String value0)
    {
        return value0 == null || value0.trim().length() == 0;
    }
}
