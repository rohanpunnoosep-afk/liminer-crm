package com.liminer.scout;

import com.liminer.enrich.BrightDataSerpClient;
import com.liminer.enrich.SerpResult;

import java.util.ArrayList;

/*
 * OpenWebEmployerResolver finds the firm a person actually works for by searching the open
 * web for their name, rather than trusting the first company listed on their LinkedIn.
 *
 * The motivating case: a physician investor's LinkedIn leads with a professional-society
 * membership, but a plain Google search for his name returns his team page on his fund's
 * website directly under the LinkedIn result. That team page carries both the correct fund
 * name and the fund website, which are exactly the two fields a candidate needs to be
 * usable in the CRM.
 */
public class OpenWebEmployerResolver
{
    private static final int RESULTS_PER_QUERY0 = 6;

    /*
     * Domains that will mention a person but never be their employer's site. Everything in
     * this list is a directory, a social network, or a news outlet.
     */
    private static final String[] BLOCKED_DOMAINS0 = {
        "linkedin.com", "facebook.com", "twitter.com", "x.com", "instagram.com",
        "youtube.com", "tiktok.com", "reddit.com", "wikipedia.org", "crunchbase.com",
        "pitchbook.com", "zoominfo.com", "bloomberg.com", "forbes.com", "reuters.com",
        "wsj.com", "nytimes.com", "medium.com", "substack.com", "glassdoor.com",
        "indeed.com", "rocketreach.co", "signalhire.com", "apollo.io", "lusha.com",
        "zippia.com", "leadiq.com", "sec.gov", "adviserinfo.sec.gov", "prnewswire.com",
        "businesswire.com", "google.com", "yelp.com", "doximity.com", "healthgrades.com",
        "webmd.com", "vitals.com", "ratemds.com", "sharecare.com"
    };

    /*
     * Path fragments that mark a page as a firm's own roster page. A hit here is the
     * strongest evidence that the domain belongs to the person's employer.
     */
    private static final String[] TEAM_PATH_TOKENS0 = {
        "/team", "/our-team", "/people", "/our-people", "/leadership", "/professionals",
        "/partners", "/about", "/who-we-are", "/bio", "/staff", "/management", "/founders",
        "/advisors"
    };

    private static final String[] GENERIC_TITLE_WORDS0 = {
        "team", "our team", "people", "our people", "leadership", "about", "about us",
        "bio", "biography", "profile", "staff", "management", "home", "meet the team",
        "professionals", "partners page", "who we are", "founders", "advisors", "company"
    };

    /**
     * One employer found on the open web, with the page it came from so the evidence trail
     * survives into the candidate row.
     */
    public static class EmployerLead
    {
        public String fundName;
        public String websiteUrl;
        public String sourceUrl;
        public String sourceTitle;
        public String queryUsed;

        public EmployerLead()
        {
            fundName = "";
            websiteUrl = "";
            sourceUrl = "";
            sourceTitle = "";
            queryUsed = "";
        }

        public boolean hasFundName()
        {
            return !isBlank(fundName);
        }

        public boolean hasWebsite()
        {
            return !isBlank(websiteUrl);
        }

        public boolean isUsable()
        {
            return hasWebsite();
        }
    }

    private BrightDataSerpClient serpClient;

    public OpenWebEmployerResolver(BrightDataSerpClient serpClient0)
    {
        serpClient = serpClient0;
    }

    /**
     * Searches for the person by name and returns the first result that looks like their
     * firm's own site. Returns null when nothing usable is found; callers keep whatever
     * LinkedIn gave them in that case.
     */
    public EmployerLead resolveEmployer(
        String firstName0,
        String lastName0,
        String knownFundNameHint0)
    {
        String person0 = (safe(firstName0) + " " + safe(lastName0)).trim();

        if (isBlank(person0))
        {
            return null;
        }

        for (String query0 : buildQueries(person0, knownFundNameHint0))
        {
            ArrayList<SerpResult> results0;

            try
            {
                results0 = serpClient.search(query0, RESULTS_PER_QUERY0);
            }
            catch (Exception exception0)
            {
                System.out.println("Open-web employer search failed for " + person0 + ": " + exception0.getMessage());
                continue;
            }

            EmployerLead lead0 = pickBestLead(results0, person0, query0);

            if (lead0 != null)
            {
                return lead0;
            }
        }

        return null;
    }

    ArrayList<String> buildQueries(String person0, String knownFundNameHint0)
    {
        ArrayList<String> queries0 = new ArrayList<String>();

        queries0.add("\"" + person0 + "\" investor");
        queries0.add("\"" + person0 + "\" fund partner team");
        queries0.add("\"" + person0 + "\"");

        if (!isBlank(knownFundNameHint0) && !EmployerSelector.looksLikeMembershipBody(knownFundNameHint0))
        {
            queries0.add(1, "\"" + person0 + "\" \"" + knownFundNameHint0.trim() + "\"");
        }

        return queries0;
    }

    EmployerLead pickBestLead(ArrayList<SerpResult> results0, String person0, String query0)
    {
        if (results0 == null)
        {
            return null;
        }

        EmployerLead fallback0 = null;

        for (SerpResult result0 : results0)
        {
            if (result0 == null || isBlank(result0.url) || isBlockedDomain(result0.url))
            {
                continue;
            }

            String root0 = rootUrl(result0.url);

            if (isBlank(root0))
            {
                continue;
            }

            EmployerLead lead0 = new EmployerLead();
            lead0.websiteUrl = root0;
            lead0.sourceUrl = result0.url;
            lead0.sourceTitle = safe(result0.title);
            lead0.queryUsed = query0;
            lead0.fundName = deriveFundName(result0.title, person0, root0);

            // The title is often a page name rather than a firm name ("Hidden Assets",
            // "Email Formats"), while the firm itself is written out in the title or the
            // snippet text. Reading it from there recovers "SPHERE Investments" for a
            // person whose LinkedIn carries no experience rows at all.
            if (isBlank(lead0.fundName)
                || isGenericTitleWord(lead0.fundName)
                || !EmployerSelector.looksLikeInvestingFirm(lead0.fundName))
            {
                String fromText0 = EmployerSelector.extractInvestingFirmName(
                    safe(result0.title) + ". " + safe(result0.snippet));

                if (!isBlank(fromText0))
                {
                    lead0.fundName = fromText0;
                }
            }

            if (EmployerSelector.looksLikeMembershipBody(lead0.fundName))
            {
                continue;
            }

            boolean rosterPage0 = hasTeamPath(result0.url);
            boolean mentionsPerson0 = mentionsPerson(result0.title, person0)
                || mentionsPerson(result0.snippet, person0);

            if (rosterPage0 && mentionsPerson0)
            {
                return lead0;
            }

            if (fallback0 == null && mentionsPerson0)
            {
                fallback0 = lead0;
            }
        }

        return fallback0;
    }

    /*
     * Turns a result title into a fund name. Team-page titles read like
     * "Dharmarraj Sunthar - Sphere Investments" or "Our Team | Sphere Investments", so the
     * fund is whatever is left once the person's name and the generic page words are gone.
     * Falls back to the domain when the title yields nothing.
     */
    String deriveFundName(String title0, String person0, String rootUrl0)
    {
        String cleaned0 = safe(title0);

        for (String piece0 : cleaned0.split("[|\\-–—·:]"))
        {
            String chunk0 = piece0.trim();

            if (isBlank(chunk0) || isGenericTitleWord(chunk0) || mentionsPerson(chunk0, person0))
            {
                continue;
            }

            if (chunk0.length() > 60)
            {
                continue;
            }

            return chunk0;
        }

        return fundNameFromDomain(rootUrl0);
    }

    String fundNameFromDomain(String rootUrl0)
    {
        String host0 = host(rootUrl0);

        if (isBlank(host0))
        {
            return "";
        }

        String bare0 = host0.replace("www.", "");
        int dot0 = bare0.indexOf('.');

        if (dot0 > 0)
        {
            bare0 = bare0.substring(0, dot0);
        }

        if (isBlank(bare0))
        {
            return "";
        }

        return bare0.substring(0, 1).toUpperCase() + bare0.substring(1);
    }

    private boolean isGenericTitleWord(String chunk0)
    {
        String lower0 = chunk0.trim().toLowerCase();

        for (String generic0 : GENERIC_TITLE_WORDS0)
        {
            if (lower0.equals(generic0))
            {
                return true;
            }
        }

        return false;
    }

    private boolean mentionsPerson(String text0, String person0)
    {
        if (isBlank(text0) || isBlank(person0))
        {
            return false;
        }

        String lower0 = text0.toLowerCase();
        String[] pieces0 = person0.toLowerCase().split("\\s+");

        // The surname is the discriminating part; a first-name-only match is too loose.
        if (pieces0.length == 0)
        {
            return false;
        }

        String surname0 = pieces0[pieces0.length - 1];

        return surname0.length() > 2 && lower0.contains(surname0);
    }

    private boolean hasTeamPath(String url0)
    {
        String lower0 = safe(url0).toLowerCase();

        for (String token0 : TEAM_PATH_TOKENS0)
        {
            if (lower0.contains(token0))
            {
                return true;
            }
        }

        return false;
    }

    static boolean isBlockedDomain(String url0)
    {
        String host0 = host(url0);

        if (isBlank(host0))
        {
            return true;
        }

        for (String blocked0 : BLOCKED_DOMAINS0)
        {
            if (host0.equals(blocked0) || host0.endsWith("." + blocked0))
            {
                return true;
            }
        }

        return false;
    }

    static String host(String url0)
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

            return host0 == null ? "" : host0.toLowerCase();
        }
        catch (Exception exception0)
        {
            return "";
        }
    }

    static String rootUrl(String url0)
    {
        String host0 = host(url0);

        if (isBlank(host0))
        {
            return "";
        }

        return "https://" + host0;
    }

    private static String safe(String value0)
    {
        return value0 == null ? "" : value0.trim();
    }

    private static boolean isBlank(String value0)
    {
        return value0 == null || value0.trim().length() == 0;
    }
}
