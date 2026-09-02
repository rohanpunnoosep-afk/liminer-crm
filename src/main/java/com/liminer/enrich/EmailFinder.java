package com.liminer.enrich;

import com.liminer.scout.ScoutContact;
import com.liminer.scout.ScoutUniverseRecord;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/*
 * EmailFinder — task 5 of 6 in the Investor Scout pipeline. Runs ONLY for the
 * final top candidates (the caller decides which/how many), cheapest layer
 * first, stopping at the first hit per contact slot:
 *
 *   Layer 1 (free)  - ADV Item 1.J compliance email already on the record.
 *                      A guaranteed-valid firm mailbox, not a decision maker.
 *                      Used only as a last-resort fallback (CONFIDENCE_FIRM_LEVEL).
 *   Layer 2 (free)  - website crawl -> extractContactsFromHtml (pure, regex
 *                      based, offline-testable): mailto: links + visible/
 *                      obfuscated emails, domain-filtered against the firm's
 *                      confirmed domain. CONFIDENCE_VERIFIED (published by the
 *                      firm itself).
 *   Layer 3 (cheap) - Bright Data SERP LinkedIn person discovery: confirm the
 *                      firm's LinkedIn company page (site:linkedin.com/company),
 *                      then find people (site:linkedin.com/in), ranked by title
 *                      priority (Managing Partner, CIO, Head of Fund
 *                      Investments, Partner). Emitted as name + title +
 *                      linkedinUrl with NO email (CONFIDENCE_LINKEDIN_ONLY).
 *
 * Hard rule: email verifier APIs and commercial enrichment (Hunter-class)
 * lookups are OUT of this waterfall entirely, and so is pattern-guessed email
 * generation — the only emails this class ever emits come from layer 1 (ADV)
 * or layer 2 (published on the firm's own website). LinkedIn URL is an
 * acceptable, preferred contact channel; layer 3 never emits an email.
 *
 * allowPaid=false stops after layer 2, WITHOUT ever calling the SERP client
 * (so the result set is limited to VERIFIED/FIRM_LEVEL contacts and zero
 * Bright Data spend is incurred).
 *
 * All external dependencies (website fetch, SERP search) are constructor-
 * injectable so tests can pass fakes with call counters and stay fully
 * offline. Every live SERP call goes through BrightDataThrottle (via
 * BrightDataSerpClient, which acquires/releases the global throttle itself).
 */
public class EmailFinder
{
    // Priority order for layer-3 person identification.
    private static final String[] TARGET_TITLES0 = new String[]
    {
        "managing partner",
        "chief investment officer",
        "cio",
        "head of fund investments",
        "partner"
    };

    private static final int SERP_MAX_RESULTS0 = 10;

    /*
     * Injectable seam for the website-crawl step. The default implementation
     * delegates to WebsiteCrawlerService.crawlWebsite (real Bright Data HTTP);
     * tests pass a fake that returns hardcoded HTML fixtures instead.
     */
    public interface WebsiteFetcher
    {
        LinkedHashMap<String, String> crawl(String rootUrl0) throws Exception;
    }

    public static class DefaultWebsiteFetcher implements WebsiteFetcher
    {
        @Override
        public LinkedHashMap<String, String> crawl(String rootUrl0) throws Exception
        {
            return WebsiteCrawlerService.crawlWebsite(WebsiteCrawlerService.normalizeRootUrl(rootUrl0));
        }
    }

    /*
     * Injectable seam for layer 3's SERP search. The default implementation
     * delegates to BrightDataSerpClient (real Bright Data HTTP, already
     * routed through BrightDataThrottle internally); tests pass a fake with a
     * call counter so the fully-offline contract can be verified.
     */
    public interface SerpSearcher
    {
        ArrayList<SerpResult> search(String query0, int maxResults0) throws Exception;
    }

    public static class DefaultSerpSearcher implements SerpSearcher
    {
        private final BrightDataSerpClient client0 = new BrightDataSerpClient();

        @Override
        public ArrayList<SerpResult> search(String query0, int maxResults0) throws Exception
        {
            return client0.search(query0, maxResults0);
        }
    }

    private final WebsiteFetcher websiteFetcher0;
    private final SerpSearcher serpSearcher0;
    private final LinkedInUrlExtractor linkedInUrlExtractor0;

    public EmailFinder()
    {
        this(new DefaultWebsiteFetcher(), new DefaultSerpSearcher());
    }

    public EmailFinder(WebsiteFetcher websiteFetcher0, SerpSearcher serpSearcher0)
    {
        this.websiteFetcher0 = websiteFetcher0;
        this.serpSearcher0 = serpSearcher0;
        this.linkedInUrlExtractor0 = new LinkedInUrlExtractor();
    }

    public List<ScoutContact> findContacts(ScoutUniverseRecord rec0, boolean allowPaid) throws Exception
    {
        return findContacts(rec0, 2, allowPaid);
    }

    public List<ScoutContact> findContacts(ScoutUniverseRecord rec0, int maxContacts0, boolean allowPaid) throws Exception
    {
        List<ScoutContact> contacts0 = new ArrayList<ScoutContact>();

        if (rec0 == null || maxContacts0 <= 0)
        {
            return contacts0;
        }

        String domain0 = resolveExpectedDomain(rec0);

        // Layer 2: website crawl.
        if (!isBlank(rec0.website))
        {
            LinkedHashMap<String, String> pages0 = websiteFetcher0.crawl(rec0.website);

            if (pages0 != null)
            {
                for (String pageUrl0 : pages0.keySet())
                {
                    String html0 = pages0.get(pageUrl0);

                    for (ScoutContact c0 : extractContactsFromHtml(html0, domain0))
                    {
                        c0.source = "website-crawl:" + pageUrl0;
                        addIfNewEmail(contacts0, c0);
                    }
                }
            }
        }

        if (contacts0.size() >= maxContacts0)
        {
            return capAndReturn(contacts0, maxContacts0);
        }

        // Layer 3: Bright Data SERP LinkedIn person discovery. allowPaid=false
        // stops here without ever invoking the SERP client (zero spend).
        if (allowPaid && contacts0.size() < maxContacts0)
        {
            String companyQuery0 = companyQuery(rec0);

            if (!isBlank(companyQuery0))
            {
                // Confirms the firm's LinkedIn company page; used as a signal
                // only, not required to gate the people search below.
                linkedInUrlExtractor0.extractBestCompany(serpSearcher0.search(companyQuery0, SERP_MAX_RESULTS0));
            }

            String peopleQuery0 = peopleQuery(rec0);

            if (!isBlank(peopleQuery0))
            {
                ArrayList<SerpResult> peopleResults0 = serpSearcher0.search(peopleQuery0, SERP_MAX_RESULTS0);
                List<DiscoveredLinkedInTarget> people0 = linkedInUrlExtractor0.extractPeople(peopleResults0);
                List<DiscoveredLinkedInTarget> ranked0 = rankPeopleByTitlePriority(people0);

                for (DiscoveredLinkedInTarget person0 : ranked0)
                {
                    if (contacts0.size() >= maxContacts0)
                    {
                        break;
                    }

                    String[] nameAndTitle0 = parseNameTitleFromSerpTitle(person0.serpTitle);
                    String name0 = nameAndTitle0[0];
                    String title0 = nameAndTitle0[1];

                    if (isBlank(name0) || hasEmailForName(contacts0, name0) || hasLinkedinUrl(contacts0, person0.url))
                    {
                        continue;
                    }

                    contacts0.add(new ScoutContact(
                        name0,
                        title0,
                        "",
                        person0.url,
                        ScoutContact.CONFIDENCE_LINKEDIN_ONLY,
                        "serp:site:linkedin.com/in " + peopleQuery0 + " " + person0.url
                    ));
                }
            }
        }

        // Final fallback: if nothing else was found for this firm at all,
        // fall back to the ADV Item 1.J compliance mailbox.
        if (contacts0.isEmpty() && !isBlank(rec0.contactEmail))
        {
            contacts0.add(new ScoutContact(
                "",
                "",
                rec0.contactEmail,
                "",
                ScoutContact.CONFIDENCE_FIRM_LEVEL,
                "adv-item-1j"
            ));
        }

        return capAndReturn(contacts0, maxContacts0);
    }

    // -----------------------------------------------------------------------
    // Layer 2: pure, offline-testable HTML extraction.
    // -----------------------------------------------------------------------

    private static final Pattern MAILTO_ANCHOR_PATTERN0 = Pattern.compile(
        "<a\\s+[^>]*href\\s*=\\s*[\"']mailto:([^\"'?]+)[\"'][^>]*>(.*?)</a>",
        Pattern.CASE_INSENSITIVE | Pattern.DOTALL
    );

    private static final Pattern OBFUSCATED_EMAIL_PATTERN0 = Pattern.compile(
        "([\\w.+-]+)\\s*(?:\\[at\\]|\\(at\\)|\\bat\\b)\\s*([\\w-]+(?:\\s*(?:\\[dot\\]|\\(dot\\)|\\bdot\\b)\\s*[\\w-]+)*)\\s*(?:\\[dot\\]|\\(dot\\)|\\bdot\\b)\\s*([A-Za-z]{2,})",
        Pattern.CASE_INSENSITIVE
    );

    /*
     * Pure function: extracts {name, email, title} triples from one page's raw
     * HTML. Two sources: (1) mailto: anchors, using the anchor's own inner
     * text as "Name, Title" or "Name - Title"; (2) obfuscated visible emails
     * (e.g. "jane [at] acmefof [dot] com"), using the preceding line of
     * visible text as "Name, Title". When expectedDomain0 is non-blank, only
     * emails at that domain are kept (filters out unrelated third-party
     * addresses picked up from page chrome/plugins).
     */
    public static List<ScoutContact> extractContactsFromHtml(String html0, String expectedDomain0)
    {
        List<ScoutContact> found0 = new ArrayList<ScoutContact>();

        if (isBlank(html0))
        {
            return found0;
        }

        java.util.Set<String> seenEmails0 = new java.util.LinkedHashSet<String>();

        Matcher mailtoMatcher0 = MAILTO_ANCHOR_PATTERN0.matcher(html0);
        StringBuffer remaining0 = new StringBuffer();

        while (mailtoMatcher0.find())
        {
            String email0 = decodeMailtoAddress(mailtoMatcher0.group(1));
            String anchorText0 = WebsiteCrawlerService.extractVisibleText(mailtoMatcher0.group(2)).trim();

            mailtoMatcher0.appendReplacement(remaining0, "");

            if (isBlank(email0) || !seenEmails0.add(email0.toLowerCase()))
            {
                continue;
            }

            if (!domainMatches(email0, expectedDomain0))
            {
                continue;
            }

            String[] nameAndTitle0 = splitNameAndTitle(anchorText0, email0);
            found0.add(new ScoutContact(nameAndTitle0[0], nameAndTitle0[1], email0, "", ScoutContact.CONFIDENCE_VERIFIED, ""));
        }
        mailtoMatcher0.appendTail(remaining0);

        String visibleText0 = WebsiteCrawlerService.extractVisibleText(remaining0.toString());
        String[] lines0 = visibleText0.split("\\n");

        for (int lineIndex0 = 0; lineIndex0 < lines0.length; lineIndex0++)
        {
            Matcher obfMatcher0 = OBFUSCATED_EMAIL_PATTERN0.matcher(lines0[lineIndex0]);

            if (!obfMatcher0.find())
            {
                continue;
            }

            String domainRest0 = obfMatcher0.group(2).replaceAll("(?i)\\s*(?:\\[dot\\]|\\(dot\\)|\\bdot\\b)\\s*", ".").trim();
            String email0 = obfMatcher0.group(1).trim() + "@" + domainRest0 + "." + obfMatcher0.group(3).trim();
            email0 = email0.toLowerCase();

            if (!seenEmails0.add(email0))
            {
                continue;
            }

            if (!domainMatches(email0, expectedDomain0))
            {
                continue;
            }

            String precedingLine0 = findPrecedingNonBlankLine(lines0, lineIndex0);
            String[] nameAndTitle0 = splitNameAndTitle(precedingLine0, email0);
            found0.add(new ScoutContact(nameAndTitle0[0], nameAndTitle0[1], email0, "", ScoutContact.CONFIDENCE_VERIFIED, ""));
        }

        return found0;
    }

    private static String findPrecedingNonBlankLine(String[] lines0, int fromIndex0)
    {
        for (int i0 = fromIndex0 - 1; i0 >= 0; i0--)
        {
            String candidate0 = lines0[i0].trim();
            if (!candidate0.isEmpty())
            {
                return candidate0;
            }
        }
        return "";
    }

    // Splits "Jane Doe, Managing Partner" / "Jane Doe - Managing Partner" into
    // {name, title}. Falls back to {"", ""} when the text is empty/just the
    // email itself (no name context available).
    private static String[] splitNameAndTitle(String text0, String email0)
    {
        if (isBlank(text0) || text0.trim().equalsIgnoreCase(email0))
        {
            return new String[] { "", "" };
        }

        String cleaned0 = text0.trim();
        String[] parts0 = cleaned0.split("\\s*[,–-]\\s*", 2);

        if (parts0.length == 2)
        {
            return new String[] { parts0[0].trim(), parts0[1].trim() };
        }

        return new String[] { cleaned0, "" };
    }

    private static String decodeMailtoAddress(String raw0)
    {
        if (isBlank(raw0))
        {
            return "";
        }
        // mailto: addresses can carry a trailing query string handled by the
        // regex's [^"'?] exclusion already; just trim whitespace/entities.
        return raw0.trim().replace("&amp;", "&");
    }

    private static boolean domainMatches(String email0, String expectedDomain0)
    {
        if (isBlank(expectedDomain0))
        {
            return true;
        }

        String emailDomain0 = emailDomainOf(email0);
        return !emailDomain0.isEmpty() && emailDomain0.equalsIgnoreCase(expectedDomain0);
    }

    private static String emailDomainOf(String email0)
    {
        if (isBlank(email0))
        {
            return "";
        }
        int at0 = email0.indexOf('@');
        if (at0 == -1 || at0 == email0.length() - 1)
        {
            return "";
        }
        String d0 = email0.substring(at0 + 1).trim().toLowerCase();
        return d0.startsWith("www.") ? d0.substring(4) : d0;
    }

    // -----------------------------------------------------------------------
    // Layer 3: Bright Data SERP LinkedIn person discovery.
    // -----------------------------------------------------------------------

    // Pure, testable query builder: confirms the firm's LinkedIn company page.
    public static String companyQuery(ScoutUniverseRecord rec0)
    {
        if (rec0 == null || isBlank(rec0.firmName))
        {
            return "";
        }

        String query0 = "site:linkedin.com/company \"" + rec0.firmName.trim() + "\"";

        String domain0 = resolveExpectedDomain(rec0);
        if (!isBlank(domain0))
        {
            query0 += " \"" + domain0 + "\"";
        }

        return query0;
    }

    // Pure, testable query builder: finds people at the firm, biased toward
    // the target titles in TARGET_TITLES0 priority order.
    public static String peopleQuery(ScoutUniverseRecord rec0)
    {
        if (rec0 == null || isBlank(rec0.firmName))
        {
            return "";
        }

        return "site:linkedin.com/in \"" + rec0.firmName.trim()
            + "\" (\"Managing Partner\" OR \"Chief Investment Officer\" OR CIO OR \"Head of Fund Investments\" OR Partner)";
    }

    // Parses a Google SERP title for a linkedin.com/in result, typically
    // "Name - Title - Company | LinkedIn" or "Name | LinkedIn", into
    // {name, title}. Falls back to {cleaned title, ""} when no title segment
    // is present.
    private static String[] parseNameTitleFromSerpTitle(String serpTitle0)
    {
        if (isBlank(serpTitle0))
        {
            return new String[] { "", "" };
        }

        String cleaned0 = serpTitle0.replaceAll("(?i)\\s*\\|\\s*linkedin\\s*$", "").trim();
        String[] parts0 = cleaned0.split("\\s*-\\s*");

        if (parts0.length >= 2 && !isBlank(parts0[0]) && !isBlank(parts0[1]))
        {
            return new String[] { parts0[0].trim(), parts0[1].trim() };
        }

        return new String[] { cleaned0, "" };
    }

    private static int titlePriority(String title0)
    {
        if (isBlank(title0))
        {
            return TARGET_TITLES0.length;
        }
        String lower0 = title0.toLowerCase();
        for (int i0 = 0; i0 < TARGET_TITLES0.length; i0++)
        {
            if (lower0.contains(TARGET_TITLES0[i0]))
            {
                return i0;
            }
        }
        return TARGET_TITLES0.length;
    }

    private static List<DiscoveredLinkedInTarget> rankPeopleByTitlePriority(List<DiscoveredLinkedInTarget> people0)
    {
        List<DiscoveredLinkedInTarget> sorted0 = new ArrayList<DiscoveredLinkedInTarget>(people0);
        sorted0.sort((a0, b0) -> Integer.compare(
            titlePriority(parseNameTitleFromSerpTitle(a0.serpTitle)[1]),
            titlePriority(parseNameTitleFromSerpTitle(b0.serpTitle)[1])
        ));
        return sorted0;
    }

    private static boolean hasEmailForName(List<ScoutContact> contacts0, String name0)
    {
        if (isBlank(name0))
        {
            return false;
        }
        for (ScoutContact c0 : contacts0)
        {
            if (name0.equalsIgnoreCase(c0.name) && !isBlank(c0.email))
            {
                return true;
            }
        }
        return false;
    }

    private static boolean hasLinkedinUrl(List<ScoutContact> contacts0, String linkedinUrl0)
    {
        if (isBlank(linkedinUrl0))
        {
            return false;
        }
        for (ScoutContact c0 : contacts0)
        {
            if (linkedinUrl0.equalsIgnoreCase(c0.linkedinUrl))
            {
                return true;
            }
        }
        return false;
    }

    // -----------------------------------------------------------------------
    // Shared helpers.
    // -----------------------------------------------------------------------

    private static String resolveExpectedDomain(ScoutUniverseRecord rec0)
    {
        if (!isBlank(rec0.website))
        {
            String domain0 = WebsiteCrawlerService.getDomain(rec0.website);
            if (!isBlank(domain0))
            {
                return domain0;
            }
        }

        if (!isBlank(rec0.contactEmail) && rec0.contactEmail.contains("@"))
        {
            return rec0.contactEmail.substring(rec0.contactEmail.indexOf('@') + 1).trim().toLowerCase();
        }

        return "";
    }

    private static void addIfNewEmail(List<ScoutContact> contacts0, ScoutContact candidate0)
    {
        if (candidate0 == null || isBlank(candidate0.email))
        {
            return;
        }
        for (ScoutContact existing0 : contacts0)
        {
            if (existing0.email.equalsIgnoreCase(candidate0.email))
            {
                return;
            }
        }
        contacts0.add(candidate0);
    }

    private static List<ScoutContact> capAndReturn(List<ScoutContact> contacts0, int maxContacts0)
    {
        if (contacts0.size() <= maxContacts0)
        {
            return contacts0;
        }
        return new ArrayList<ScoutContact>(contacts0.subList(0, maxContacts0));
    }

    private static boolean isBlank(String value0)
    {
        return value0 == null || value0.trim().length() == 0;
    }
}
