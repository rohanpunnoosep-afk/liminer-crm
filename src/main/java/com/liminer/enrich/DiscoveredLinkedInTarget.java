package com.liminer.enrich;

/*
 * DiscoveredLinkedInTarget is the cleaned LinkedIn URL object created from SERP results.
 *
 * It exists because SERP returns many URLs, but Pipeline 3 only wants LinkedIn profile
 * and company URLs. This class also remembers the SERP title/snippet/query so later
 * OpenAI prompts can understand why the candidate was discovered.
 */
public class DiscoveredLinkedInTarget
{
    public static final String TYPE_PERSON = "PERSON";
    public static final String TYPE_COMPANY = "COMPANY";

    public String url;
    public String targetType;
    public String queryUsed;
    public String serpTitle;
    public String serpSnippet;
    public int serpRank;

    public DiscoveredLinkedInTarget(
        String url0,
        String targetType0,
        String queryUsed0,
        String serpTitle0,
        String serpSnippet0,
        int serpRank0)
    {
        url = safeString(url0);
        targetType = safeString(targetType0);
        queryUsed = safeString(queryUsed0);
        serpTitle = safeString(serpTitle0);
        serpSnippet = safeString(serpSnippet0);
        serpRank = serpRank0;
    }

    public boolean isPerson()
    {
        return TYPE_PERSON.equals(targetType);
    }

    public boolean isCompany()
    {
        return TYPE_COMPANY.equals(targetType);
    }

    public void printSummary()
    {
        System.out.println("===== DISCOVERED LINKEDIN TARGET =====");
        System.out.println("Type: " + targetType);
        System.out.println("URL: " + url);
        System.out.println("Rank: " + serpRank);
        System.out.println("Query: " + queryUsed);
        System.out.println("Title: " + serpTitle);
        System.out.println("Snippet: " + serpSnippet);
    }

    private static String safeString(String value0)
    {
        return value0 == null ? "" : value0;
    }
}
