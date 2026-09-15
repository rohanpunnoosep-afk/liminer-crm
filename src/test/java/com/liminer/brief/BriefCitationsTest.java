package com.liminer.brief;

import org.json.JSONArray;
import org.json.JSONObject;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/*
 * Offline verification of the investor-brief citation registry. Touches no network, no
 * Google Sheets and no OpenAI: every input is a hand-built blob of the exact shape the
 * four upstream processors write.
 */
public class BriefCitationsTest
{
    // ------------------------------------------------------------------
    // Collection
    // ------------------------------------------------------------------

    @Test
    public void collectsMarketIntelligenceLeavesInAxisOrder()
    {
        JSONObject market = market(
            leaf("ReportedAumIndicator", "$2.4B", "https://adviserinfo.sec.gov/firm/1234", "2025-03-01"),
            leaf("FitIndicator", "Healthcare overlap", "https://example-lp.org/strategy", "2025-01-15"),
            leaf("FundCloseIndicator", "Fund IV closed", "https://sec.gov/formd/9999", "2025-06-30"));

        List<BriefCitations.Citation> cites = BriefCitations.collect(null, market, null, null);

        assertEquals(3, cites.size());
        // Numbering is 1-based and follows resources -> fit -> probability_now.
        assertEquals(1, cites.get(0).index);
        assertTrue(cites.get(0).url.contains("adviserinfo.sec.gov"));
        assertTrue(cites.get(1).url.contains("example-lp.org"));
        assertTrue(cites.get(2).url.contains("sec.gov/formd"));

        // The label is derived from indicator + value, since no citation carries a title.
        assertTrue(cites.get(0).label.contains("Reported Aum Indicator"), cites.get(0).label);
        assertTrue(cites.get(0).label.contains("$2.4B"), cites.get(0).label);
        assertEquals("2025-03-01", cites.get(0).asOfDate);
    }

    @Test
    public void dedupesTheSameUrlAcrossDifferentBlobs()
    {
        String shared = "https://example-lp.org/team";

        JSONObject market = market(leaf("NewAllocatorIndicator", "New CIO", shared, "2025-02-02"));

        JSONObject bg = new JSONObject();
        JSONObject fields = new JSONObject();
        // Same destination, different spelling: trailing slash + mixed case.
        fields.put("contact_website_bio_url", field("Jane Doe bio", "https://Example-LP.org/team/"));
        bg.put("resolved_fields", fields);

        JSONObject scout = new JSONObject();
        scout.put("sources", new JSONArray().put(shared));

        List<BriefCitations.Citation> cites = BriefCitations.collect(null, market, bg, scout);

        assertEquals(1, cites.size(), "one destination must occupy exactly one index");
        // First writer wins, so the market-intelligence label is the one kept.
        assertTrue(cites.get(0).label.contains("New Allocator Indicator"), cites.get(0).label);
    }

    @Test
    public void rejectsUrlsTheSharedSerpGateRejects()
    {
        JSONObject market = market(
            leaf("A", "v", "javascript:alert(1)", ""),
            leaf("B", "v", "data:text/html;base64,PHNjcmlwdD4=", ""),
            leaf("C", "v", "/relative/path", ""),
            leaf("D", "v", "", ""),
            leaf("E", "v", "https://good-source.org/a", ""));

        List<BriefCitations.Citation> cites = BriefCitations.collect(null, market, null, null);

        assertEquals(1, cites.size(), "only the absolute http(s) URL may enter the registry");
        assertEquals("https://good-source.org/a", cites.get(0).url);
    }

    @Test
    public void stripsTheScoutWebsiteCrawlPrefix()
    {
        JSONObject scout = new JSONObject();
        scout.put("emailSources", new JSONArray().put("website-crawl:https://example-lp.org/contact"));

        List<BriefCitations.Citation> cites = BriefCitations.collect(null, null, null, scout);

        assertEquals(1, cites.size());
        assertEquals("https://example-lp.org/contact", cites.get(0).url);
    }

    @Test
    public void collectsBackgroundCheckAndEnrichmentEvidence()
    {
        JSONObject contact = new JSONObject();
        JSONObject intel = new JSONObject();
        JSONObject evidence = new JSONObject();
        evidence.put("sector_focus", new JSONArray().put(
            new JSONObject().put("source_url", "https://example-lp.org/focus").put("quote", "We back climate founders")));
        intel.put("evidence", evidence);
        contact.put("intelligence", intel);

        JSONObject bg = new JSONObject();
        bg.put("resolved_fields", new JSONObject().put(
            "contact_linkedin_url", field("linkedin.com/in/jane", "https://linkedin.com/in/jane")));
        bg.put("evidence", new JSONArray().put(new JSONObject()
            .put("type", "serp_hit").put("url", "https://news.example.com/jane").put("notes", "profile match")));

        List<BriefCitations.Citation> cites = BriefCitations.collect(contact, null, bg, null);

        assertEquals(3, cites.size());
        // Enrichment evidence is collected before background check.
        assertTrue(cites.get(0).label.contains("We back climate founders"), cites.get(0).label);
        assertTrue(cites.get(1).label.contains("Contact Linkedin Url"), cites.get(1).label);
        assertTrue(cites.get(2).label.contains("Serp Hit"), cites.get(2).label);
    }

    @Test
    public void toleratesNullAndMalformedBlobs()
    {
        assertEquals(0, BriefCitations.collect(null, null, null, null).size());
        assertEquals(0, BriefCitations.collect(new JSONObject(), new JSONObject(),
                                               new JSONObject(), new JSONObject()).size());
        // "intelligence" present but not an object (parseBlob returns the raw string).
        JSONObject market = new JSONObject().put("intelligence", "not json at all");
        assertEquals(0, BriefCitations.collect(null, market, null, null).size());
    }

    // ------------------------------------------------------------------
    // Marker validation — the guarantee half of the contract
    // ------------------------------------------------------------------

    @Test
    public void keepsInRangeMarkersAndDropsOutOfRangeOnes()
    {
        String text = "Deploying now [1] and fit is strong [3], per filings [0] and [99].";
        String out = BriefCitations.stripInvalidMarkers(text, 3);

        assertTrue(out.contains("[1]"));
        assertTrue(out.contains("[3]"));
        assertFalse(out.contains("[0]"), out);
        assertFalse(out.contains("[99]"), out);
        // The space introducing a dropped marker goes with it.
        assertFalse(out.contains("  "), out);
    }

    @Test
    public void stripsEveryMarkerWhenTheRegistryIsEmpty()
    {
        String out = BriefCitations.stripInvalidMarkers("A claim [1] and another [2].", 0);
        assertEquals("A claim and another.", out);
    }

    @Test
    public void leavesNonMarkerBracketsAlone()
    {
        String text = "Allocation range [redacted] and a list [a, b] stay put [2].";
        String out = BriefCitations.stripInvalidMarkers(text, 2);

        assertTrue(out.contains("[redacted]"));
        assertTrue(out.contains("[a, b]"));
        assertTrue(out.contains("[2]"));
    }

    @Test
    public void stripsMarkersNestedInsideCallPreparation()
    {
        JSONObject callPrep = new JSONObject();
        callPrep.put("talkingPoints", new JSONArray().put("Strong climate overlap [1]").put("Fabricated [42]"));
        callPrep.put("anticipatedObjections", new JSONArray().put(new JSONObject()
            .put("objection", "Too early [7]")
            .put("navigation", "Point to the co-invest [2]")));

        BriefCitations.stripInvalidMarkersDeep(callPrep, 2);

        JSONArray points = callPrep.getJSONArray("talkingPoints");
        assertTrue(points.getString(0).contains("[1]"));
        assertFalse(points.getString(1).contains("[42]"), points.getString(1));

        JSONObject objection = callPrep.getJSONArray("anticipatedObjections").getJSONObject(0);
        assertFalse(objection.getString("objection").contains("[7]"), objection.toString());
        assertTrue(objection.getString("navigation").contains("[2]"));
    }

    // ------------------------------------------------------------------
    // Serialization
    // ------------------------------------------------------------------

    @Test
    public void promptBlockForbidsCitingWhenThereAreNoSources()
    {
        String block = BriefCitations.toPromptBlock(BriefCitations.collect(null, null, null, null));
        assertTrue(block.contains("NONE"), block);
        assertTrue(block.contains("do NOT write any [n] citation markers"), block);
    }

    @Test
    public void promptBlockNumbersSourcesAndBoundsTheRange()
    {
        JSONObject market = market(
            leaf("ReportedAumIndicator", "$2.4B", "https://adviserinfo.sec.gov/firm/1234", "2025-03-01"));
        String block = BriefCitations.toPromptBlock(BriefCitations.collect(null, market, null, null));

        assertTrue(block.contains("[1] Reported Aum Indicator: $2.4B"), block);
        assertTrue(block.contains("(as of 2025-03-01)"), block);
        assertTrue(block.contains("ONLY numbers 1 to 1"), block);
    }

    @Test
    public void toJsonCarriesIndexUrlLabelAndDate()
    {
        JSONObject market = market(
            leaf("ReportedAumIndicator", "$2.4B", "https://adviserinfo.sec.gov/firm/1234", "2025-03-01"));
        JSONArray arr = BriefCitations.toJson(BriefCitations.collect(null, market, null, null));

        assertEquals(1, arr.length());
        JSONObject c = arr.getJSONObject(0);
        assertEquals(1, c.getInt("index"));
        assertEquals("https://adviserinfo.sec.gov/firm/1234", c.getString("url"));
        assertEquals("2025-03-01", c.getString("asOfDate"));
        assertTrue(c.getString("label").length() > 0);
    }

    // ------------------------------------------------------------------
    // Fixtures — the exact shapes the upstream processors write
    // ------------------------------------------------------------------

    // One leaf per axis, in resources / fit / probability_now order.
    @Test
    public void carriesLeafEvidenceIntoTheLabel()
    {
        JSONObject market = market(leafWithEvidence(
            "ReportedAumIndicator", "$2.4B", "https://adviserinfo.sec.gov/firm/1234",
            "2025-03-01", "ADV Part 1 Item 5.F regulatory AUM"));

        List<BriefCitations.Citation> cites = BriefCitations.collect(null, market, null, null);

        assertEquals(1, cites.size());
        assertTrue(cites.get(0).label.contains("$2.4B"), cites.get(0).label);
        assertTrue(cites.get(0).label.contains("ADV Part 1 Item 5.F"), cites.get(0).label);
    }

    @Test
    public void evidenceSurvivesAValueTooLongToFitTheLabel()
    {
        StringBuilder huge = new StringBuilder();
        for (int i = 0; i < 40; i++) huge.append("about text ");

        JSONObject bg = new JSONObject();
        JSONObject fields = new JSONObject();
        fields.put("contact_linkedin_about",
                   field(huge.toString(), "https://linkedin.com/in/jane")
                       .put("evidence", "from LinkedIn profile"));
        bg.put("resolved_fields", fields);

        List<BriefCitations.Citation> cites = BriefCitations.collect(null, null, bg, null);

        assertEquals(1, cites.size());
        // The value is clipped rather than allowed to crowd the evidence out.
        assertTrue(cites.get(0).label.contains("from LinkedIn profile"), cites.get(0).label);
    }

    @Test
    public void labelIsUnchangedWhenNoEvidenceIsPresent()
    {
        JSONObject market = market(
            leaf("ReportedAumIndicator", "$2.4B", "https://adviserinfo.sec.gov/firm/1234", "2025-03-01"));

        List<BriefCitations.Citation> cites = BriefCitations.collect(null, market, null, null);

        assertEquals("Reported Aum Indicator: $2.4B", cites.get(0).label);
    }

    private static JSONObject market(JSONObject... leaves)
    {
        JSONObject intel = new JSONObject();
        String[] axes = { "resources", "fit", "probability_now" };
        for (int i = 0; i < axes.length; i++)
        {
            JSONArray arr = new JSONArray();
            if (i < leaves.length) arr.put(leaves[i]);
            intel.put(axes[i], arr);
        }
        // More leaves than axes: pile the remainder onto the resources axis.
        if (leaves.length > axes.length)
        {
            JSONArray resources = intel.getJSONArray("resources");
            for (int i = axes.length; i < leaves.length; i++) resources.put(leaves[i]);
        }
        return new JSONObject().put("intelligence", intel);
    }

    private static JSONObject leaf(String indicator, String value, String sourceUrl, String asOfDate)
    {
        return new JSONObject()
            .put("indicator", indicator)
            .put("value", value)
            .put("score", 0.5)
            .put("confidence", 0.8)
            .put("sourceUrl", sourceUrl)
            .put("asOfDate", asOfDate)
            .put("theme", "RESOURCES");
    }

    private static JSONObject leafWithEvidence(String indicator, String value, String sourceUrl,
                                               String asOfDate, String evidence)
    {
        return leaf(indicator, value, sourceUrl, asOfDate).put("evidence", evidence);
    }

    private static JSONObject field(String value, String sourceUrl)
    {
        return new JSONObject()
            .put("value", value)
            .put("confidence", 0.9)
            .put("source_url", sourceUrl);
    }
}
