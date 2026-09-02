package com.liminer.core;

/*
 * NewsItem is one result from a news-mode SERP search.
 * publishedDate is ISO-8601 (e.g. 2026-06-10) when the source provides it;
 * blank otherwise — callers must still produce an asOfDate (parse from snippet
 * or fall back to today) before emitting an IndicatorResult.
 */
public class NewsItem
{
    public final String title;
    public final String url;
    public final String snippet;
    public final String publishedDate;

    public NewsItem(String title0, String url0, String snippet0, String publishedDate0)
    {
        title = safe(title0);
        url = safe(url0);
        snippet = safe(snippet0);
        publishedDate = safe(publishedDate0);
    }

    public boolean hasDate()
    {
        return publishedDate != null && !publishedDate.isBlank();
    }

    private static String safe(String v0)
    {
        return v0 == null ? "" : v0;
    }
}
