package com.liminer.pipeline;

import com.liminer.core.InvestorProfile;
import com.liminer.enrich.WebsiteCrawlerService;
import com.liminer.llm.OpenAIClient;
import com.liminer.scout.RawCandidateData;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import org.json.JSONArray;
import org.json.JSONObject;

/*
 * InvestorProfileExtractor is the reusable website -> InvestorProfile engine.
 *
 * It is intentionally independent from the CRM sheet.
 * It does not know about row statuses, CRM tabs, or batch updates.
 *
 * Used by:
 * 1. LPEnrichmentProcessor for current LPs already in the CRM.
 * 2. Pipeline 3 for candidate investor websites discovered by search.
 */
public class InvestorProfileExtractor
{
    public static final int MAX_TEXT_CHARS0 = 60000;

    public InvestorProfileExtractor()
    {
    }

    /*
     * Main reusable method.
     *
     * Input:
     *   key -> website
     *
     * Output:
     *   same key -> InvestorProfile
     *
     * For LPEnrichmentProcessor, key is the CRM row number.
     * For Pipeline 3, key can be the candidate's index in a list.
     */
    public HashMap<Integer, InvestorProfile> getInvestorProfiles(
        HashMap<Integer, String> websiteMap0) throws Exception
    {
        HashMap<Integer, InvestorProfile> profileMap0 = new HashMap<Integer, InvestorProfile>();

        if (websiteMap0 == null || websiteMap0.size() == 0)
        {
            return profileMap0;
        }

        for (Map.Entry<Integer, String> entry0 : websiteMap0.entrySet())
        {
            Integer key0 = entry0.getKey();
            String website0 = entry0.getValue();

            if (isBlank(website0))
            {
                System.out.println("Skipping key " + key0 + " because website is blank.");
                continue;
            }

            System.out.println(
                "Extracting investor profile for key "
                + key0
                + " | website: "
                + website0
            );

            InvestorProfile profile0 = getInvestorProfile(website0);
            profileMap0.put(key0, profile0);
        }

        return profileMap0;
    }

    /*
     * Single-website version used internally and useful for tests.
     */
    public InvestorProfile getInvestorProfile(String website0) throws Exception
    {
        String normalizedWebsite0 = WebsiteCrawlerService.normalizeRootUrl(website0);

        LinkedHashMap<String, String> scrapedPages0 =
            WebsiteCrawlerService.crawlWebsite(normalizedWebsite0);

        System.out.println("Website crawl complete. Pages scraped: " + scrapedPages0.size());

        String sourceText0 = buildOpenAiSourceText(scrapedPages0);

        System.out.println(
            "Sending "
            + sourceText0.length()
            + " characters to OpenAI for investor profile extraction..."
        );

        JSONObject intelligenceJson0 = analyzeWithOpenAI(
            normalizedWebsite0,
            sourceText0
        );

        String now0 = java.time.Instant.now().toString();

        forceMetadata(
            intelligenceJson0,
            now0,
            "completed",
            normalizedWebsite0
        );

        InvestorProfile profile0 = InvestorProfile.fromIntelligenceJson(intelligenceJson0);
        profile0.intelligenceJson = intelligenceJson0.toString();

        return profile0;
    }

    /*
     * Converts crawled HTML pages into clean visible text with source URLs.
     * This preserves the exact behavior your old LPEnrichmentProcessor had.
     */
    public static String buildOpenAiSourceText(
        LinkedHashMap<String, String> scrapedPages0)
    {
        StringBuilder builder0 = new StringBuilder();

        int pageCount0 = 0;

        for (String pageUrl0 : scrapedPages0.keySet())
        {
            String html0 = scrapedPages0.get(pageUrl0);
            String cleanText0 = WebsiteCrawlerService.extractVisibleText(html0);

            if (isBlank(cleanText0))
            {
                continue;
            }

            builder0.append("\n\n===== SOURCE PAGE ")
                .append(pageCount0 + 1)
                .append(" =====\n");

            builder0.append("URL: ")
                .append(pageUrl0)
                .append("\n\n");

            builder0.append(cleanText0);

            pageCount0++;

            if (builder0.length() >= MAX_TEXT_CHARS0)
            {
                break;
            }
        }

        String sourceText0 = builder0.toString();

        if (sourceText0.length() > MAX_TEXT_CHARS0)
        {
            sourceText0 = sourceText0.substring(0, MAX_TEXT_CHARS0);
        }

        return sourceText0;
    }

    /*
     * Sends the scraped source text to OpenAI and expects a JSON object.
     */
    public static JSONObject analyzeWithOpenAI(
        String websiteUrl0,
        String sourceText0) throws Exception
    {
        String prompt0 = buildExtractionPrompt(websiteUrl0, sourceText0);

        String aiText0 = OpenAIClient.getTextResponse(prompt0);

        System.out.println("OpenAI response received.");

        return parseJsonObjectFromText(aiText0);
    }

    /*
     * Updated prompt with Investment Thesis added.
     */
    public static String buildExtractionPrompt(
        String websiteUrl0,
        String sourceText0)
    {
        return "You are an LP intelligence extraction engine for a VC CRM.\n"
            + "Extract investor/allocator details from scraped website text.\n\n"
            + "Return ONLY valid JSON. No markdown. No explanation.\n\n"
            + "Use exactly this JSON structure:\n"
            + "{\n"
            + "  \"allocator_profile\": {\"fund_name\": {\"value\": \"\", \"confidence\": 0.0}, \"allocator_type\": {\"value\": \"\", \"confidence\": 0.0}},\n"
            + "  \"sector_focus\": {\"sector_tags\": [{\"value\": \"\", \"confidence\": 0.0}]},\n"
            + "  \"microsector_focus\": {\"microsector_tags\": [{\"value\": \"\", \"confidence\": 0.0}]},\n"
            + "  \"prior_relationships\": {\"prior_backed_funds\": [{\"name\": \"\", \"confidence\": 0.0}]},\n"
            + "  \"geography\": {\"locations\": [{\"value\": \"\", \"confidence\": 0.0}]},\n"
            + "  \"investment_thesis\": {\"summary\": \"\", \"confidence\": 0.0},\n"
            + "  \"evidence\": {\"allocator_type\": [], \"sector_focus\": [], \"microsector_focus\": [], \"prior_relationships\": [], \"geography\": [], \"investment_thesis\": []},\n"
            + "  \"search_generation\": {},\n"
            + "  \"metadata\": {\"analysis_version\": \"lp_intelligence_v2\", \"last_analyzed_at\": \"\", \"confidence_score\": 0.0, \"brightdata_snapshot_id\": \"\", \"analysis_status\": \"completed\", \"source_website\": \"\"}\n"
            + "}\n\n"
            + "Rules:\n"
            + "1. fund_name should be the name of the LP, allocator, family office, fund of funds, foundation, endowment, pension, or institutional investor.\n"
            + "2. allocator_type should use values like Family Office, Foundation, Corporation, Fund of Funds, Endowment, Pension, Nonprofit, Government, Unknown.\n"
            + "3. sector_tags should be broad categories like Healthcare, AI, Climate, Education, Fintech, Enterprise, Consumer, Impact, Deep Tech.\n"
            + "4. microsector_tags should be more specific investment themes.\n"
            + "5. prior_backed_funds should only include funds/managers clearly supported by the text.\n"
            + "6. geography should include locations or allocation regions supported by the text.\n"
            + "7. investment_thesis should be a concise 1-3 sentence summary of the allocator's strategy, preferences, and focus.\n"
            + "8. If unknown, return empty arrays or blank values with low confidence.\n"
            + "9. Evidence arrays should contain objects with source_url and quote fields when possible.\n\n"
            + "Website URL: " + websiteUrl0 + "\n\n"
            + "Scraped source text:\n"
            + sourceText0;
    }


    /*
     * Pipeline 3 Candidate Discovery version.
     *
     * This method extracts an InvestorProfile from combined evidence, not just a website.
     * The evidence can include:
     * - SERP title/snippet/query
     * - LinkedIn profile/company JSON
     * - website text scraped from the candidate's company website
     *
     * This is the method CandidateDiscoveryProcessor uses after it has assembled
     * RawCandidateData. The old website-only getInvestorProfile(String website0) remains
     * untouched for LPEnrichmentProcessor.
     */
    public InvestorProfile getInvestorProfile(RawCandidateData rawCandidate0) throws Exception
    {
        if (rawCandidate0 == null)
        {
            return new InvestorProfile();
        }

        String sourceName0 = rawCandidate0.websiteUrl;

        if (isBlank(sourceName0))
        {
            sourceName0 = rawCandidate0.linkedinCompanyUrl;
        }

        if (isBlank(sourceName0))
        {
            sourceName0 = rawCandidate0.linkedinProfileUrl;
        }

        if (isBlank(sourceName0))
        {
            sourceName0 = rawCandidate0.name;
        }

        String evidenceText0 = rawCandidate0.buildEvidenceTextForOpenAI();

        System.out.println(
            "Sending "
            + evidenceText0.length()
            + " characters to OpenAI for candidate InvestorProfile extraction..."
        );

        JSONObject intelligenceJson0 = analyzeCandidateWithOpenAI(
            sourceName0,
            evidenceText0
        );

        String now0 = java.time.Instant.now().toString();

        forceMetadata(
            intelligenceJson0,
            now0,
            "completed",
            sourceName0
        );

        JSONObject metadata0 = intelligenceJson0.optJSONObject("metadata");
        if (metadata0 != null)
        {
            metadata0.put("analysis_version", "candidate_discovery_v1");
            metadata0.put("linkedin_profile_url", rawCandidate0.linkedinProfileUrl);
            metadata0.put("linkedin_company_url", rawCandidate0.linkedinCompanyUrl);
            metadata0.put("discovery_query", rawCandidate0.discoveryQuery);
        }

        InvestorProfile profile0 = InvestorProfile.fromIntelligenceJson(intelligenceJson0);
        profile0.intelligenceJson = intelligenceJson0.toString();

        if (isBlank(profile0.fundName) && !isBlank(rawCandidate0.fundName))
        {
            profile0.fundName = rawCandidate0.fundName;
        }

        return profile0;
    }

    /*
     * Sends combined LinkedIn + website + SERP evidence to OpenAI and expects the same
     * intelligence JSON shape used by the website-only LP enrichment flow.
     */
    public static JSONObject analyzeCandidateWithOpenAI(
        String sourceName0,
        String evidenceText0) throws Exception
    {
        String prompt0 = buildCandidateExtractionPrompt(sourceName0, evidenceText0);

        String aiText0 = OpenAIClient.getTextResponse(prompt0);

        System.out.println("OpenAI candidate extraction response received.");

        return parseJsonObjectFromText(aiText0);
    }

    /*
     * Candidate Discovery prompt. It asks OpenAI to treat LinkedIn and SERP data as
     * supporting evidence and to avoid inventing details that are not supported.
     */
    public static String buildCandidateExtractionPrompt(
        String sourceName0,
        String evidenceText0)
    {
        return "You are an investor candidate intelligence extraction engine for a VC CRM.\n"
            + "Extract a structured InvestorProfile from combined SERP, LinkedIn, and website evidence.\n\n"
            + "Return ONLY valid JSON. No markdown. No explanation.\n\n"
            + "Use exactly this JSON structure:\n"
            + "{\n"
            + "  \"allocator_profile\": {\"fund_name\": {\"value\": \"\", \"confidence\": 0.0}, \"allocator_type\": {\"value\": \"\", \"confidence\": 0.0}},\n"
            + "  \"sector_focus\": {\"sector_tags\": [{\"value\": \"\", \"confidence\": 0.0}]},\n"
            + "  \"microsector_focus\": {\"microsector_tags\": [{\"value\": \"\", \"confidence\": 0.0}]},\n"
            + "  \"prior_relationships\": {\"prior_backed_funds\": [{\"name\": \"\", \"confidence\": 0.0}]},\n"
            + "  \"geography\": {\"locations\": [{\"value\": \"\", \"confidence\": 0.0}]},\n"
            + "  \"investment_thesis\": {\"summary\": \"\", \"confidence\": 0.0},\n"
            + "  \"evidence\": {\"allocator_type\": [], \"sector_focus\": [], \"microsector_focus\": [], \"prior_relationships\": [], \"geography\": [], \"investment_thesis\": []},\n"
            + "  \"search_generation\": {},\n"
            + "  \"metadata\": {\"analysis_version\": \"candidate_discovery_v1\", \"last_analyzed_at\": \"\", \"confidence_score\": 0.0, \"brightdata_snapshot_id\": \"\", \"analysis_status\": \"completed\", \"source_website\": \"\"}\n"
            + "}\n\n"
            + "Rules:\n"
            + "1. If this is a person, fund_name should usually be the current fund/company if supported by LinkedIn evidence.\n"
            + "2. If this is a company, fund_name should be the company/fund name.\n"
            + "3. allocator_type should use values like Venture Capital, Family Office, Fund of Funds, Angel Investor, Corporation, Foundation, Endowment, Pension, Unknown.\n"
            + "4. sector_tags should be broad categories like AI, Enterprise, B2B SaaS, Healthcare, Climate, Fintech, Consumer, Deep Tech.\n"
            + "5. microsector_tags should be more specific investment themes, such as AI CRM, sales automation, vertical SaaS, devtools, AI infrastructure.\n"
            + "6. prior_backed_funds should only include funds/managers clearly supported by the evidence.\n"
            + "7. geography should include investment geography or candidate location if that is the only supported geography.\n"
            + "8. investment_thesis should be a concise 1-3 sentence summary based only on the supplied evidence.\n"
            + "9. Do not invent emails, websites, investments, or sectors. If unknown, return empty arrays or blank values with low confidence.\n\n"
            + "Source Name or URL: " + sourceName0 + "\n\n"
            + "Candidate evidence:\n"
            + evidenceText0;
    }

    public static JSONObject parseJsonObjectFromText(String text0)
    {
        String trimmedText0 = text0 == null ? "" : text0.trim();

        try
        {
            return new JSONObject(trimmedText0);
        }
        catch (Exception exception0)
        {
            int startIndex0 = trimmedText0.indexOf("{");
            int endIndex0 = trimmedText0.lastIndexOf("}");

            if (startIndex0 == -1 || endIndex0 == -1 || endIndex0 <= startIndex0)
            {
                throw exception0;
            }

            return new JSONObject(trimmedText0.substring(startIndex0, endIndex0 + 1));
        }
    }

    public static void forceMetadata(
        JSONObject intelligenceJson0,
        String now0,
        String status0,
        String website0)
    {
        JSONObject metadata0 = intelligenceJson0.optJSONObject("metadata");

        if (metadata0 == null)
        {
            metadata0 = new JSONObject();
            intelligenceJson0.put("metadata", metadata0);
        }

        metadata0.put("analysis_version", "lp_intelligence_v2");
        metadata0.put("last_analyzed_at", now0);
        metadata0.put("analysis_status", status0 == null ? "" : status0.toLowerCase());
        metadata0.put("source_website", website0 == null ? "" : website0);
    }

    public static JSONObject buildFailureJson(String website0, String errorMessage0)
    {
        JSONObject root0 = new JSONObject();

        root0.put("allocator_profile", new JSONObject()
            .put("fund_name", new JSONObject().put("value", "").put("confidence", 0.0))
            .put("allocator_type", new JSONObject().put("value", "").put("confidence", 0.0)));

        root0.put("sector_focus", new JSONObject().put("sector_tags", new JSONArray()));
        root0.put("microsector_focus", new JSONObject().put("microsector_tags", new JSONArray()));
        root0.put("prior_relationships", new JSONObject().put("prior_backed_funds", new JSONArray()));
        root0.put("geography", new JSONObject().put("locations", new JSONArray()));
        root0.put("investment_thesis", new JSONObject().put("summary", "").put("confidence", 0.0));

        root0.put("evidence", new JSONObject()
            .put("allocator_type", new JSONArray())
            .put("sector_focus", new JSONArray())
            .put("microsector_focus", new JSONArray())
            .put("prior_relationships", new JSONArray())
            .put("geography", new JSONArray())
            .put("investment_thesis", new JSONArray()));

        root0.put("search_generation", new JSONObject());

        root0.put("metadata", new JSONObject()
            .put("analysis_version", "lp_intelligence_v2")
            .put("last_analyzed_at", java.time.Instant.now().toString())
            .put("confidence_score", 0.0)
            .put("brightdata_snapshot_id", "")
            .put("analysis_status", "failed")
            .put("source_website", website0 == null ? "" : website0)
            .put("error_message", errorMessage0 == null ? "" : errorMessage0));

        return root0;
    }

    public static boolean isBlank(String value0)
    {
        return value0 == null || value0.trim().length() == 0;
    }
}
