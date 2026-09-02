package com.liminer.enrich;

/*
 * SerpResult is one organic search result returned by BrightDataSerpClient.
 *
 * Purpose in Pipeline 3 Candidate Discovery:
 * 1. Keep the original search result metadata.
 * 2. Preserve which generated query found the result.
 * 3. Give LinkedInUrlExtractor enough context to decide whether the URL is useful.
 */
public class SerpResult
{
    public String title;
    public String url;
    public String snippet;
    public int rank;
    public String queryUsed;

    public SerpResult(String title0, String url0, String snippet0, int rank0, String queryUsed0)
    {
        title = safeString(title0);
        url = safeString(url0);
        snippet = safeString(snippet0);
        rank = rank0;
        queryUsed = safeString(queryUsed0);
    }

    public void printSummary()
    {
        System.out.println("SERP #" + rank + " | " + title + " | " + url);
        System.out.println("Query: " + queryUsed);
        System.out.println("Snippet: " + snippet);
    }

    private static String safeString(String value0)
    {
        return value0 == null ? "" : value0;
    }
}
