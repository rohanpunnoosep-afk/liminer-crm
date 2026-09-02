package com.liminer.scout;

import com.liminer.enrich.EmailFinder;
import com.liminer.enrich.SerpResult;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;

/*
 * Fully offline test main for EmailFinder (task 0084-scout-serp-linkedin-contacts).
 * No HTTP, no OpenAI, no Bright Data network calls are ever made — every
 * external dependency is a hardcoded fixture or a fake with a call counter.
 * Prints SCOUT_EMAIL_OK and exits 0 on success; prints the failing assertion
 * and System.exit(1) on any failure.
 */
public class ScoutEmailFinderTestMain
{
    private static int failures0 = 0;

    public static void main(String[] args0) throws Exception
    {
        testExtractContactsFromHtml();
        testSerpLayerRanksManagingPartnerFirstAsLinkedinOnly();
        testAllowPaidFalseNeverCallsSerp();
        testWebsitePublishedEmailWinsOverSerp();
        testMaxContactsCapRespected();
        testNoUnverifiedEmailEverEmitted();

        if (failures0 > 0)
        {
            System.out.println("SCOUT_EMAIL_FAILURES: " + failures0);
            System.exit(1);
        }

        System.out.println("SCOUT_EMAIL_OK");
    }

    // -------------------------------------------------------------------
    // (a) Pure extraction: two mailto contacts + one obfuscated visible
    //     email, domain filtering drops a third-party mailto.
    // -------------------------------------------------------------------
    private static void testExtractContactsFromHtml()
    {
        String html0 =
            "<html><body>"
            + "<div class=\"team\">"
            + "<a href=\"mailto:jane@acmefof.com\">Jane Doe, Managing Partner</a>"
            + "<a href=\"mailto:bob@acmefof.com\">Bob Smith - Partner</a>"
            + "<a href=\"mailto:spam@thirdparty.com\">Spam Person, Marketing</a>"
            + "<p>Alice Johnson, Chief Financial Officer</p>"
            + "<p>alice [at] acmefof [dot] com</p>"
            + "</div>"
            + "</body></html>";

        List<ScoutContact> contacts0 = EmailFinder.extractContactsFromHtml(html0, "acmefof.com");

        assertTrue("expected 3 contacts after domain filter, got " + contacts0.size(), contacts0.size() == 3);

        ScoutContact jane0 = findByEmail(contacts0, "jane@acmefof.com");
        assertTrue("jane@acmefof.com missing", jane0 != null);
        assertTrue("jane name wrong: " + jane0.name, "Jane Doe".equals(jane0.name));
        assertTrue("jane title wrong: " + jane0.title, "Managing Partner".equals(jane0.title));
        assertTrue("jane confidence wrong: " + jane0.confidence, ScoutContact.CONFIDENCE_VERIFIED.equals(jane0.confidence));

        ScoutContact bob0 = findByEmail(contacts0, "bob@acmefof.com");
        assertTrue("bob@acmefof.com missing", bob0 != null);
        assertTrue("bob name wrong: " + bob0.name, "Bob Smith".equals(bob0.name));
        assertTrue("bob title wrong: " + bob0.title, "Partner".equals(bob0.title));

        ScoutContact alice0 = findByEmail(contacts0, "alice@acmefof.com");
        assertTrue("alice@acmefof.com (obfuscated) missing", alice0 != null);
        assertTrue("alice name wrong: " + alice0.name, "Alice Johnson".equals(alice0.name));
        assertTrue("alice title wrong: " + alice0.title, "Chief Financial Officer".equals(alice0.title));

        assertTrue("third-party domain email should have been filtered out", findByEmail(contacts0, "spam@thirdparty.com") == null);
    }

    // -------------------------------------------------------------------
    // (b) SERP layer: fake SERP returns two /in/ hits, "Managing Partner"
    //     and "Analyst" -> Managing Partner ranked first, emitted as
    //     LINKEDIN_ONLY with linkedinUrl set and email null/blank.
    // -------------------------------------------------------------------
    private static void testSerpLayerRanksManagingPartnerFirstAsLinkedinOnly() throws Exception
    {
        FakeWebsiteFetcher fetcher0 = new FakeWebsiteFetcher("<html><body>no team page</body></html>");
        FakeSerpSearcher serp0 = new FakeSerpSearcher();

        serp0.resultsByQuery.put(
            EmailFinder.peopleQuery(buildRecord("https://acmefof.com", "compliance@acmefof.com")),
            makeResults(
                new SerpResult("Ann Analyst - Analyst - Acme Fund of Funds | LinkedIn", "https://www.linkedin.com/in/ann-analyst", "", 1, "q"),
                new SerpResult("Jane Doe - Managing Partner - Acme Fund of Funds | LinkedIn", "https://www.linkedin.com/in/jane-doe", "", 2, "q")
            )
        );

        EmailFinder finder0 = new EmailFinder(fetcher0, serp0);

        ScoutUniverseRecord rec0 = buildRecord("https://acmefof.com", "compliance@acmefof.com");

        List<ScoutContact> contacts0 = finder0.findContacts(rec0, 2, true);

        assertTrue("expected 2 contacts, got " + contacts0.size(), contacts0.size() == 2);

        ScoutContact first0 = contacts0.get(0);
        assertTrue("expected Jane Doe ranked first (Managing Partner beats Analyst), got " + first0.name, "Jane Doe".equals(first0.name));
        assertTrue("expected Managing Partner title, got " + first0.title, "Managing Partner".equals(first0.title));
        assertTrue("expected LINKEDIN_ONLY confidence, got " + first0.confidence, ScoutContact.CONFIDENCE_LINKEDIN_ONLY.equals(first0.confidence));
        assertTrue("expected linkedinUrl set", "https://www.linkedin.com/in/jane-doe".equals(first0.linkedinUrl));
        assertTrue("expected no email on a LINKEDIN_ONLY contact, got " + first0.email, first0.email == null || first0.email.isEmpty());

        ScoutContact second0 = contacts0.get(1);
        assertTrue("expected Ann Analyst second, got " + second0.name, "Ann Analyst".equals(second0.name));
        assertTrue("expected LINKEDIN_ONLY confidence, got " + second0.confidence, ScoutContact.CONFIDENCE_LINKEDIN_ONLY.equals(second0.confidence));
    }

    // -------------------------------------------------------------------
    // (c) allowPaid=false must never invoke the SERP client, even when no
    //     website contacts were found -- falls back to FIRM_LEVEL.
    // -------------------------------------------------------------------
    private static void testAllowPaidFalseNeverCallsSerp() throws Exception
    {
        FakeWebsiteFetcher fetcher0 = new FakeWebsiteFetcher("<html><body>no team page</body></html>");
        FakeSerpSearcher serp0 = new FakeSerpSearcher();

        EmailFinder finder0 = new EmailFinder(fetcher0, serp0);

        ScoutUniverseRecord rec0 = buildRecord("https://acmefof.com", "compliance@acmefof.com");

        List<ScoutContact> contacts0 = finder0.findContacts(rec0, 2, false);

        assertTrue("SERP client must never be called when allowPaid=false, callCount=" + serp0.callCount, serp0.callCount == 0);

        assertTrue("expected exactly 1 fallback contact, got " + contacts0.size(), contacts0.size() == 1);
        assertTrue("expected FIRM_LEVEL fallback", ScoutContact.CONFIDENCE_FIRM_LEVEL.equals(contacts0.get(0).confidence));
    }

    // -------------------------------------------------------------------
    // (d) Waterfall order: a published website email (layer 2) satisfies
    //     maxContacts before layer 3 SERP is ever consulted.
    // -------------------------------------------------------------------
    private static void testWebsitePublishedEmailWinsOverSerp() throws Exception
    {
        String teamPageHtml0 = "<a href=\"mailto:jane@acmefof.com\">Jane Doe, Managing Partner</a>";

        FakeWebsiteFetcher fetcher0 = new FakeWebsiteFetcher(teamPageHtml0);
        FakeSerpSearcher serp0 = new FakeSerpSearcher();

        EmailFinder finder0 = new EmailFinder(fetcher0, serp0);

        ScoutUniverseRecord rec0 = buildRecord("https://acmefof.com", "compliance@acmefof.com");

        List<ScoutContact> contacts0 = finder0.findContacts(rec0, 1, true);

        assertTrue("expected exactly 1 contact, got " + contacts0.size(), contacts0.size() == 1);
        ScoutContact contact0 = contacts0.get(0);
        assertTrue("expected the website-published email, got " + contact0.email, "jane@acmefof.com".equals(contact0.email));
        assertTrue("expected VERIFIED confidence, got " + contact0.confidence, ScoutContact.CONFIDENCE_VERIFIED.equals(contact0.confidence));
        assertTrue("SERP must not be consulted once layer 2 already satisfied maxContacts, callCount=" + serp0.callCount, serp0.callCount == 0);
    }

    // -------------------------------------------------------------------
    // (e) maxContacts cap is respected even when more VERIFIED contacts
    //     were found on the team page.
    // -------------------------------------------------------------------
    private static void testMaxContactsCapRespected() throws Exception
    {
        String teamPageHtml0 =
            "<html><body>"
            + "<a href=\"mailto:a@x.com\">Name A, Partner</a>"
            + "<a href=\"mailto:b@x.com\">Name B, Partner</a>"
            + "<a href=\"mailto:c@x.com\">Name C, Partner</a>"
            + "</body></html>";

        FakeWebsiteFetcher fetcher0 = new FakeWebsiteFetcher(teamPageHtml0);
        FakeSerpSearcher serp0 = new FakeSerpSearcher();

        EmailFinder finder0 = new EmailFinder(fetcher0, serp0);

        ScoutUniverseRecord rec0 = buildRecord("https://x.com", "compliance@x.com");

        List<ScoutContact> contacts0 = finder0.findContacts(rec0, 2, true);

        assertTrue("expected exactly 2 contacts (cap), got " + contacts0.size(), contacts0.size() == 2);
        for (ScoutContact c0 : contacts0)
        {
            assertTrue("expected VERIFIED confidence, got " + c0.confidence, ScoutContact.CONFIDENCE_VERIFIED.equals(c0.confidence));
        }
        assertTrue("no SERP calls needed once layer 2 already satisfied maxContacts", serp0.callCount == 0);
    }

    // -------------------------------------------------------------------
    // (f) No code path may emit an email that did not come from layer 1
    //     (ADV) or layer 2 (website). A SERP hit never carries an email.
    // -------------------------------------------------------------------
    private static void testNoUnverifiedEmailEverEmitted() throws Exception
    {
        FakeWebsiteFetcher fetcher0 = new FakeWebsiteFetcher("<html><body>no team page</body></html>");
        FakeSerpSearcher serp0 = new FakeSerpSearcher();

        ScoutUniverseRecord rec0 = buildRecord("https://acmefof.com", "");

        serp0.resultsByQuery.put(
            EmailFinder.peopleQuery(rec0),
            makeResults(new SerpResult("Jane Doe - Managing Partner - Acme Fund of Funds | LinkedIn", "https://www.linkedin.com/in/jane-doe", "", 1, "q"))
        );

        EmailFinder finder0 = new EmailFinder(fetcher0, serp0);

        List<ScoutContact> contacts0 = finder0.findContacts(rec0, 2, true);

        for (ScoutContact c0 : contacts0)
        {
            assertTrue(
                "no code path may emit a non VERIFIED/LINKEDIN_ONLY/FIRM_LEVEL confidence",
                ScoutContact.CONFIDENCE_VERIFIED.equals(c0.confidence)
                    || ScoutContact.CONFIDENCE_LINKEDIN_ONLY.equals(c0.confidence)
                    || ScoutContact.CONFIDENCE_FIRM_LEVEL.equals(c0.confidence)
            );
            if (ScoutContact.CONFIDENCE_LINKEDIN_ONLY.equals(c0.confidence))
            {
                assertTrue("LINKEDIN_ONLY contact must never carry an email", c0.email == null || c0.email.isEmpty());
            }
        }
    }

    // -------------------------------------------------------------------
    // Fixtures / fakes
    // -------------------------------------------------------------------

    private static ScoutUniverseRecord buildRecord(String website0, String contactEmail0)
    {
        ScoutUniverseRecord rec0 = new ScoutUniverseRecord();
        rec0.crd = 100001;
        rec0.firmName = "Acme Fund of Funds";
        rec0.website = website0;
        rec0.contactEmail = contactEmail0;
        rec0.snapshotMonth = "2026-07";
        return rec0;
    }

    private static ScoutContact findByEmail(List<ScoutContact> contacts0, String email0)
    {
        for (ScoutContact c0 : contacts0)
        {
            if (c0.email.equalsIgnoreCase(email0))
            {
                return c0;
            }
        }
        return null;
    }

    private static ArrayList<SerpResult> makeResults(SerpResult... results0)
    {
        ArrayList<SerpResult> list0 = new ArrayList<SerpResult>();
        for (SerpResult r0 : results0)
        {
            list0.add(r0);
        }
        return list0;
    }

    private static class FakeWebsiteFetcher implements EmailFinder.WebsiteFetcher
    {
        private final String html0;

        FakeWebsiteFetcher(String html1)
        {
            html0 = html1;
        }

        @Override
        public LinkedHashMap<String, String> crawl(String rootUrl0)
        {
            LinkedHashMap<String, String> pages0 = new LinkedHashMap<String, String>();
            pages0.put(rootUrl0 + "/team", html0);
            return pages0;
        }
    }

    private static class FakeSerpSearcher implements EmailFinder.SerpSearcher
    {
        int callCount = 0;
        java.util.Map<String, ArrayList<SerpResult>> resultsByQuery = new java.util.HashMap<String, ArrayList<SerpResult>>();

        @Override
        public ArrayList<SerpResult> search(String query0, int maxResults0)
        {
            callCount++;
            ArrayList<SerpResult> results0 = resultsByQuery.get(query0);
            return results0 == null ? new ArrayList<SerpResult>() : results0;
        }
    }

    private static void assertTrue(String message0, boolean condition0)
    {
        if (!condition0)
        {
            failures0++;
            System.out.println("ASSERTION FAILED: " + message0);
        }
    }
}
