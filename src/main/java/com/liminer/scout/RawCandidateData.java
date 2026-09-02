package com.liminer.scout;

import com.liminer.pipeline.InvestorProfileExtractor;

import org.json.JSONObject;

/*
 * RawCandidateData is the messy evidence object for Pipeline 3 Candidate Discovery.
 *
 * This class sits between scraping and clean candidate creation:
 * SERP result + LinkedIn scrape + website scrape -> RawCandidateData -> InvestorProfile -> CandidateInvestor.
 */
public class RawCandidateData
{
    public String candidateType; // PERSON or COMPANY

    public String name;
    public String firstName;
    public String lastName;
    public String position;
    public String fundName;

    public String linkedinProfileUrl;
    public String linkedinCompanyUrl;
    public String websiteUrl;

    public String country;
    public String region;

    public String contact2FirstName;
    public String contact2LastName;
    public String contact2Position;
    public String contact2LinkedInUrl;

    public String serpTitle;
    public String serpSnippet;
    public String discoveryQuery;
    public int serpRank;

    public String rawLinkedInJson;
    public String rawCompanyLinkedInJson;
    public String rawContactLinkedInJson;
    public String rawWebsiteText;
    public String rawWebsiteJson;
    public String scrapedAt;

    public RawCandidateData()
    {
        candidateType = "";
        name = "";
        firstName = "";
        lastName = "";
        position = "";
        fundName = "";
        linkedinProfileUrl = "";
        linkedinCompanyUrl = "";
        websiteUrl = "";
        country = "";
        region = "";
        contact2FirstName = "";
        contact2LastName = "";
        contact2Position = "";
        contact2LinkedInUrl = "";
        serpTitle = "";
        serpSnippet = "";
        discoveryQuery = "";
        serpRank = 0;
        rawLinkedInJson = "";
        rawCompanyLinkedInJson = "";
        rawContactLinkedInJson = "";
        rawWebsiteText = "";
        rawWebsiteJson = "";
        scrapedAt = java.time.Instant.now().toString();
    }

    public String buildEvidenceTextForOpenAI()
    {
        StringBuilder builder0 = new StringBuilder();

        builder0.append("===== DISCOVERY CONTEXT =====\n");
        builder0.append("Candidate Type: ").append(candidateType).append("\n");
        builder0.append("Discovery Query: ").append(discoveryQuery).append("\n");
        builder0.append("SERP Rank: ").append(serpRank).append("\n");
        builder0.append("SERP Title: ").append(serpTitle).append("\n");
        builder0.append("SERP Snippet: ").append(serpSnippet).append("\n\n");

        builder0.append("===== BASIC FIELDS =====\n");
        builder0.append("Name: ").append(name).append("\n");
        builder0.append("First Name: ").append(firstName).append("\n");
        builder0.append("Last Name: ").append(lastName).append("\n");
        builder0.append("Position: ").append(position).append("\n");
        builder0.append("Fund Name: ").append(fundName).append("\n");
        builder0.append("Website: ").append(websiteUrl).append("\n");
        builder0.append("Country: ").append(country).append("\n");
        builder0.append("Region: ").append(region).append("\n");
        builder0.append("LinkedIn Profile URL: ").append(linkedinProfileUrl).append("\n");
        builder0.append("LinkedIn Company URL: ").append(linkedinCompanyUrl).append("\n");
        builder0.append("Contact 2 First Name: ").append(contact2FirstName).append("\n");
        builder0.append("Contact 2 Last Name: ").append(contact2LastName).append("\n");
        builder0.append("Contact 2 Position: ").append(contact2Position).append("\n");
        builder0.append("Contact 2 LinkedIn URL: ").append(contact2LinkedInUrl).append("\n\n");

        if (!isBlank(rawLinkedInJson))
        {
            builder0.append("===== ORIGINAL LINKEDIN JSON =====\n");
            builder0.append(limit(rawLinkedInJson, 30000)).append("\n\n");
        }

        if (!isBlank(rawContactLinkedInJson) && !rawContactLinkedInJson.equals(rawLinkedInJson))
        {
            builder0.append("===== CONTACT LINKEDIN JSON =====\n");
            builder0.append(limit(rawContactLinkedInJson, 30000)).append("\n\n");
        }

        if (!isBlank(rawCompanyLinkedInJson) && !rawCompanyLinkedInJson.equals(rawLinkedInJson))
        {
            builder0.append("===== COMPANY LINKEDIN JSON =====\n");
            builder0.append(limit(rawCompanyLinkedInJson, 30000)).append("\n\n");
        }

        if (!isBlank(rawWebsiteText))
        {
            builder0.append("===== WEBSITE TEXT =====\n");
            builder0.append(limit(rawWebsiteText, 30000)).append("\n\n");
        }

        return limit(builder0.toString(), InvestorProfileExtractor.MAX_TEXT_CHARS0);
    }

    public JSONObject buildEvidenceJson()
    {
        JSONObject object0 = new JSONObject();
        object0.put("candidate_type", candidateType);
        object0.put("name", name);
        object0.put("first_name", firstName);
        object0.put("last_name", lastName);
        object0.put("position", position);
        object0.put("fund_name", fundName);
        object0.put("website", websiteUrl);
        object0.put("country", country);
        object0.put("region", region);
        object0.put("linkedin_profile_url", linkedinProfileUrl);
        object0.put("linkedin_company_url", linkedinCompanyUrl);
        object0.put("contact2_first_name", contact2FirstName);
        object0.put("contact2_last_name", contact2LastName);
        object0.put("contact2_position", contact2Position);
        object0.put("contact2_linkedin_url", contact2LinkedInUrl);
        object0.put("serp_title", serpTitle);
        object0.put("serp_snippet", serpSnippet);
        object0.put("discovery_query", discoveryQuery);
        object0.put("serp_rank", serpRank);
        object0.put("scraped_at", scrapedAt);
        object0.put("raw_linkedin_json", rawLinkedInJson);
        object0.put("raw_contact_linkedin_json", rawContactLinkedInJson);
        object0.put("raw_company_linkedin_json", rawCompanyLinkedInJson);
        object0.put("raw_website_json", rawWebsiteJson);
        return object0;
    }

    public void printSummary()
    {
        System.out.println("===== RAW CANDIDATE DATA =====");
        System.out.println("Type: " + candidateType);
        System.out.println("Name: " + name);
        System.out.println("Fund Name: " + fundName);
        System.out.println("Position: " + position);
        System.out.println("Website: " + websiteUrl);
        System.out.println("Country: " + country);
        System.out.println("Region: " + region);
        System.out.println("LinkedIn Profile: " + linkedinProfileUrl);
        System.out.println("LinkedIn Company: " + linkedinCompanyUrl);
        System.out.println("Discovery Query: " + discoveryQuery);
        System.out.println("SERP Rank: " + serpRank);
    }

    public static boolean isBlank(String value0)
    {
        return value0 == null || value0.trim().length() == 0;
    }

    private static String limit(String value0, int maxChars0)
    {
        if (value0 == null)
        {
            return "";
        }

        if (value0.length() <= maxChars0)
        {
            return value0;
        }

        return value0.substring(0, maxChars0);
    }
}
