package com.liminer.scout;

import com.liminer.enrich.BrightDataSerpClient;
import com.liminer.enrich.DiscoveredLinkedInTarget;
import com.liminer.enrich.LinkedInScrapeResult;
import com.liminer.enrich.SerpResult;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Investors hold several affiliations at once — the fund they run, an advisory seat, a
 * board seat, a professional-society membership. Bright Data reports whichever one sits at
 * the top of the profile as "current_company", which put "American College of Healthcare
 * Executives" in the Fund Name column for a physician investor whose fund is Sphere
 * Investments.
 *
 * These tests pin the two defences against that: scoring every affiliation rather than
 * taking the first, and cross-checking a weak pick against a plain search for the person.
 */
public class EmployerSelectionTest
{
    private static JSONObject buildSuntharProfile()
    {
        JSONObject profile0 = new JSONObject();
        profile0.put("name", "Dharmarraj Sunthar");
        profile0.put("first_name", "Dharmarraj");
        profile0.put("last_name", "Sunthar");
        profile0.put("headline", "Physician | Healthcare Investor");

        JSONObject currentCompany0 = new JSONObject();
        currentCompany0.put("name", "American College of Healthcare Executives");
        currentCompany0.put("url", "https://www.linkedin.com/company/ache");
        profile0.put("current_company", currentCompany0);

        JSONArray experience0 = new JSONArray();

        JSONObject membership0 = new JSONObject();
        membership0.put("company", "American College of Healthcare Executives");
        membership0.put("title", "Member");
        membership0.put("company_linkedin_url", "https://www.linkedin.com/company/ache");
        membership0.put("end_date", "Present");
        experience0.put(membership0);

        JSONObject fund0 = new JSONObject();
        fund0.put("company", "Sphere Investments");
        fund0.put("title", "Managing Partner");
        fund0.put("company_linkedin_url", "https://www.linkedin.com/company/sphere-investments");
        fund0.put("end_date", "Present");
        experience0.put(fund0);

        profile0.put("experience", experience0);

        return profile0;
    }

    @Test
    public void picksTheFundOverTheProfessionalSocietyAtTheTopOfTheProfile()
    {
        LinkedInScrapeResult scrape0 = LinkedInScrapeResult.fromJson(
            "https://www.linkedin.com/in/dharmarraj-sunthar",
            DiscoveredLinkedInTarget.TYPE_PERSON,
            buildSuntharProfile()
        );

        assertEquals(2, scrape0.affiliations.size());

        EmployerSelector.EmployerChoice choice0 = new EmployerSelector().selectEmployer(scrape0);

        assertNotNull(choice0.affiliation);
        assertEquals("Sphere Investments", choice0.getCompanyName());
        assertEquals("https://www.linkedin.com/company/sphere-investments", choice0.getCompanyLinkedInUrl());
        assertTrue(choice0.confident, "a current managing partner role at a named fund should not need a cross-check");
    }

    @Test
    public void aMembershipOnlyProfileIsNotTrustedAsAnEmployer()
    {
        JSONObject profile0 = new JSONObject();
        profile0.put("name", "Dharmarraj Sunthar");

        JSONArray experience0 = new JSONArray();
        JSONObject membership0 = new JSONObject();
        membership0.put("company", "American College of Healthcare Executives");
        membership0.put("title", "Member");
        membership0.put("end_date", "Present");
        experience0.put(membership0);
        profile0.put("experience", experience0);

        LinkedInScrapeResult scrape0 = LinkedInScrapeResult.fromJson(
            "https://www.linkedin.com/in/dharmarraj-sunthar",
            DiscoveredLinkedInTarget.TYPE_PERSON,
            profile0
        );

        EmployerSelector.EmployerChoice choice0 = new EmployerSelector().selectEmployer(scrape0);

        assertFalse(choice0.confident);
        assertTrue(EmployerSelector.needsCrossCheck(choice0));
        assertTrue(EmployerSelector.looksLikeMembershipBody(choice0.getCompanyName()));
    }

    private static class TeamPageSerp extends BrightDataSerpClient
    {
        @Override
        public ArrayList<SerpResult> search(String query0, int maxResults0)
        {
            ArrayList<SerpResult> results0 = new ArrayList<SerpResult>();
            results0.add(new SerpResult(
                "Dharmarraj Sunthar - LinkedIn",
                "https://www.linkedin.com/in/dharmarraj-sunthar",
                "Physician | Healthcare Investor",
                1,
                query0
            ));
            results0.add(new SerpResult(
                "Dharmarraj Sunthar | Sphere Investments",
                "https://sphereinvestments.com/team/dharmarraj-sunthar",
                "Dharmarraj Sunthar is a Managing Partner at Sphere Investments.",
                2,
                query0
            ));
            return results0;
        }
    }

    @Test
    public void openWebSearchFindsTheTeamPageUnderTheLinkedInResult()
    {
        OpenWebEmployerResolver resolver0 = new OpenWebEmployerResolver(new TeamPageSerp());

        OpenWebEmployerResolver.EmployerLead lead0 = resolver0.resolveEmployer(
            "Dharmarraj",
            "Sunthar",
            ""
        );

        assertNotNull(lead0);
        assertEquals("Sphere Investments", lead0.fundName);
        assertEquals("https://sphereinvestments.com", lead0.websiteUrl);
        assertEquals("https://sphereinvestments.com/team/dharmarraj-sunthar", lead0.sourceUrl);
    }

    @Test
    public void aCandidateWithNoLinkedInUrlsIsStillCrmReady()
    {
        CandidateInvestor candidate0 = new CandidateInvestor("Sphere Investments", "https://sphereinvestments.com");
        candidate0.contact1FirstName = "Dharmarraj";
        candidate0.contact1LastName = "Sunthar";
        candidate0.syncLegacyAliases();

        assertTrue(candidate0.isCrmReady(), "name + fund name + fund website is enough to run the workflows");
        assertEquals("", candidate0.getMissingCrmReadyFields());
        assertTrue(candidate0.getMissingOptionalFields().contains("Fund LinkedIn"));
    }

    @Test
    public void aCandidateWithNoFundWebsiteIsStillRejected()
    {
        CandidateInvestor candidate0 = new CandidateInvestor("Sphere Investments", "");
        candidate0.contact1FirstName = "Dharmarraj";
        candidate0.contact1LastName = "Sunthar";
        candidate0.syncLegacyAliases();

        assertFalse(candidate0.isCrmReady());
        assertTrue(candidate0.getMissingCrmReadyFields().contains("Fund Website"));
    }

    private static JSONObject buildExperienceRow(
        String company0,
        String title0,
        String startDate0,
        String endDate0,
        String duration0)
    {
        JSONObject row0 = new JSONObject();
        row0.put("company", company0);
        row0.put("title", title0);
        row0.put("start_date", startDate0);
        row0.put("end_date", endDate0);
        row0.put("duration", duration0);
        return row0;
    }

    private static LinkedInScrapeResult scrapeOf(JSONArray experience0, String headline0)
    {
        JSONObject profile0 = new JSONObject();
        profile0.put("name", "Test Person");
        profile0.put("headline", headline0);
        profile0.put("experience", experience0);

        return LinkedInScrapeResult.fromJson(
            "https://www.linkedin.com/in/test-person",
            DiscoveredLinkedInTarget.TYPE_PERSON,
            profile0
        );
    }

    @Test
    public void tenureIsReadFromTheStartAndEndDates()
    {
        JSONArray experience0 = new JSONArray();
        experience0.put(buildExperienceRow("Sphere Investments", "Managing Partner", "Jan 2015", "Present", "10 yrs 2 mos"));
        experience0.put(buildExperienceRow("Sequoia Capital", "Associate", "Jan 2011", "Dec 2014", "4 yrs"));

        LinkedInScrapeResult scrape0 = scrapeOf(experience0, "Investor");

        assertEquals(2, scrape0.affiliations.size());

        assertTrue(scrape0.affiliations.get(0).current, "end_date 'Present' marks an ongoing role");
        assertFalse(scrape0.affiliations.get(1).current, "a real end year marks a role that has ended");

        assertEquals(10.0, scrape0.affiliations.get(0).tenureYears(), 0.3);
        assertEquals(4.0, scrape0.affiliations.get(1).tenureYears(), 0.3);
    }

    @Test
    public void aPastEmployerNeverOutranksACurrentOneOnNameAlone()
    {
        // Sequoia Capital reads far more like a fund than "Sphere LLC", but it is a role the
        // person has left, so it must not be filed as their fund.
        JSONArray experience0 = new JSONArray();
        experience0.put(buildExperienceRow("Sequoia Capital", "Partner", "Jan 2011", "Dec 2014", "4 yrs"));
        experience0.put(buildExperienceRow("Sphere LLC", "Managing Partner", "Jan 2015", "Present", "10 yrs"));

        EmployerSelector.EmployerChoice choice0 = new EmployerSelector().selectEmployer(scrapeOf(experience0, "Investor"));

        assertEquals("Sphere LLC", choice0.getCompanyName());
        assertTrue(choice0.reason.contains("ongoing role"));
    }

    @Test
    public void filteringToOngoingRolesAloneDoesNotSettleTheMotivatingCase()
    {
        // Both the society membership and the fund are ongoing, so the date filter leaves
        // both standing and the title/name scoring is what has to pick the fund. This is the
        // reason the selector cannot be replaced by a "date - Present" filter.
        LinkedInScrapeResult scrape0 = LinkedInScrapeResult.fromJson(
            "https://www.linkedin.com/in/dharmarraj-sunthar",
            DiscoveredLinkedInTarget.TYPE_PERSON,
            buildSuntharProfile()
        );

        ArrayList<com.liminer.enrich.LinkedInAffiliation> ongoing0 =
            EmployerSelector.filterToCurrent(scrape0.affiliations);

        assertEquals(2, ongoing0.size(), "both the membership and the fund are current");

        assertEquals("Sphere Investments", new EmployerSelector().selectEmployer(scrape0).getCompanyName());
    }

    @Test
    public void everyRoleEndedStillYieldsAnEmployerRatherThanNothing()
    {
        // An unreadable date shape must not empty the candidate set and leave the person
        // with no employer at all.
        JSONArray experience0 = new JSONArray();
        experience0.put(buildExperienceRow("Sphere Investments", "Managing Partner", "Jan 2011", "Dec 2014", "4 yrs"));

        EmployerSelector.EmployerChoice choice0 = new EmployerSelector().selectEmployer(scrapeOf(experience0, "Investor"));

        assertEquals("Sphere Investments", choice0.getCompanyName());
    }

    // ================================================================
    // What Bright Data actually returns
    //
    // Sampled live on 2026-09-11 against the people dataset
    // (gd_l1viktl72bvl7bjuj0): three of five profiles came back with
    // "experience": null while still carrying a topcard current_company.
    // Both investors this was traced from were in that group, so the
    // fixtures above - which assume a populated experience array - describe
    // a payload the pipeline frequently never sees. These pin the real one.
    // ================================================================

    /*
     * The observed payload: a topcard, and no experience rows at all.
     */
    private static JSONObject buildProfileWithNoExperience(
        String name0,
        String topcardCompany0,
        String topcardCompanyUrl0)
    {
        JSONObject profile0 = new JSONObject();
        profile0.put("name", name0);
        profile0.put("experience", JSONObject.NULL);
        profile0.put("position", JSONObject.NULL);
        profile0.put("current_company_name", topcardCompany0);

        JSONObject currentCompany0 = new JSONObject();
        currentCompany0.put("name", topcardCompany0);
        currentCompany0.put("link", topcardCompanyUrl0);
        profile0.put("current_company", currentCompany0);

        return profile0;
    }

    @Test
    public void aProfileWithNoExperienceRowsIsReportedAsTopcardOnly()
    {
        LinkedInScrapeResult scrape0 = LinkedInScrapeResult.fromJson(
            "https://www.linkedin.com/in/dsunthar",
            DiscoveredLinkedInTarget.TYPE_PERSON,
            buildProfileWithNoExperience(
                "Dharmarraj Sunthar MD MBA",
                "American College of Healthcare Executives",
                "https://www.linkedin.com/company/american-college-of-healthcare-executives")
        );

        assertTrue(scrape0.experienceSectionMissing, "Bright Data sent no experience rows");
        assertEquals(1, scrape0.affiliations.size(), "only the topcard is left to choose from");

        EmployerSelector.EmployerChoice choice0 =
            new EmployerSelector().selectEmployer(scrape0, "Managing Director, Strategic Partnerships & Medical Affairs");

        // Nothing was ranked, so the pick must not be presented as a decision. Before this,
        // a single-candidate pool was returned as the winner and written straight to the CRM.
        assertTrue(choice0.topcardOnly);
        assertFalse(choice0.confident);
        assertTrue(EmployerSelector.needsCrossCheck(choice0));
    }

    @Test
    public void theFundIsRecoveredFromTheHeadlineWhenExperienceIsNull()
    {
        // Sweeney's profile carries only "VedaBio" on the topcard - his operating company -
        // while the fund he invests through appears solely in his headline. Taking the
        // topcard filed him under VedaBio; the headline is where OMX Ventures survives.
        LinkedInScrapeResult scrape0 = LinkedInScrapeResult.fromJson(
            "https://www.linkedin.com/in/frederic-d-sweeney-8492372",
            DiscoveredLinkedInTarget.TYPE_PERSON,
            buildProfileWithNoExperience(
                "Frederic D. Sweeney",
                "VedaBio",
                "https://www.linkedin.com/company/vedabioinc")
        );

        EmployerSelector.EmployerChoice choice0 = new EmployerSelector().selectEmployer(
            scrape0,
            "President & CEO @VedaBio // Venture Partner @ OMX Ventures \u00b7 Experience: VedaBio "
            + "\u00b7 Location: San Diego \u00b7 500+ connections on LinkedIn. View Frederic D.");

        assertEquals("OMX Ventures", choice0.getCompanyName());
        assertFalse(choice0.topcardOnly, "the headline supplied a real alternative to rank");
        assertTrue(choice0.reason.contains("investing role"));
    }

    @Test
    public void headlineParsingReadsBothRolesAndIgnoresTheSearchResultTail()
    {
        ArrayList<com.liminer.enrich.LinkedInAffiliation> parsed0 =
            EmployerSelector.parseHeadlineAffiliations(
                "President & CEO @VedaBio // Venture Partner @ OMX Ventures \u00b7 Experience: VedaBio "
                + "\u00b7 Location: San Diego \u00b7 500+ connections on LinkedIn.",
                0);

        // "Experience: VedaBio" and "Location: San Diego" describe the search result, not
        // the person's roles, so they must not become affiliations.
        assertEquals(2, parsed0.size());
        assertEquals("VedaBio", parsed0.get(0).companyName);
        assertEquals("OMX Ventures", parsed0.get(1).companyName);
        assertEquals("Venture Partner", parsed0.get(1).title);
    }

    @Test
    public void aHeadlineNamingNoFirmAddsNothing()
    {
        // Sunthar's headline is a job title only. Inventing an affiliation from it would be
        // worse than falling through to the cross-check.
        assertEquals(
            0,
            EmployerSelector.parseHeadlineAffiliations(
                "Managing Director, Strategic Partnerships & Medical Affairs", 0).size());
    }

    @Test
    public void theFirmNameIsReadOutOfSearchResultText()
    {
        // Sunthar's fund never appears on his LinkedIn at all; it appears in the text of the
        // results for his name. These are the real titles and snippets that search returned.
        assertEquals(
            "SPHERE Investments",
            EmployerSelector.extractInvestingFirmName(
                "SPHERE Investments Email Formats. Dharmarraj Sunthar. Managing Director, "
                + "Strategic Partnerships & Medical Affairs. @sphereinvestments.com."));

        // A firm-type word that is part of another company's name must not be harvested.
        assertEquals(
            "",
            EmployerSelector.extractInvestingFirmName(
                "CEO & Executives - Investment Property Group Leadership. Dharmarraj Sunthar, M.D."));

        assertEquals(
            "",
            EmployerSelector.extractInvestingFirmName(
                "Dharma heads SPHERE's Strategic Partnerships and Medical Affairs division."));
    }

    private static class AssociationSerp extends BrightDataSerpClient
    {
        @Override
        public ArrayList<SerpResult> search(String query0, int maxResults0)
        {
            ArrayList<SerpResult> results0 = new ArrayList<SerpResult>();
            results0.add(new SerpResult(
                "Senior Housing & Healthcare Association",
                "https://www.shha.international/about",
                "An industry initiative supporting investors and operators in senior housing.",
                1,
                query0
            ));
            return results0;
        }
    }

    @Test
    public void aCrossCheckWillNotSwapOneProfessionalBodyForAnother()
    {
        // The cross-check exists to get off "American College of Healthcare Executives".
        // Returning another association instead left the row just as wrong, which is how a
        // fund that appears nowhere on the profile ended up in the CRM.
        OpenWebEmployerResolver resolver0 = new OpenWebEmployerResolver(new AssociationSerp());

        OpenWebEmployerResolver.EmployerLead lead0 = resolver0.resolveEmployer(
            "Dharmarraj",
            "Sunthar",
            "");

        assertNull(lead0, "an association is not an acceptable replacement for an association");
    }
}
