package com.liminer.enrich;

import com.liminer.core.NewsItem;
import com.liminer.llm.OpenAIClient;

import java.util.concurrent.ConcurrentHashMap;

/*
 * ScrapeCache deduplicates the slow, read-only external calls made during a single
 * background-check batch run: LinkedIn profile scrapes, LinkedIn company scrapes,
 * website crawls, and OpenAI text completions.
 *
 * Why it is safe to cache: every cached call's result depends ONLY on its key (a URL
 * or the full prompt text), not on any per-row mutable state. The same company page
 * is scraped once even when 20 rows share a fund; the same profile URL surfaced in
 * Phase 2 is not re-scraped in Phase 5.
 *
 * Thread-safety: rows run in parallel (see ROW_THREAD_POOL_SIZE in
 * BasicBackgroundChecker), so all maps are ConcurrentHashMap. A given URL may be
 * fetched by two threads at once before either caches it; that is acceptable (one
 * redundant call, never a wrong result).
 *
 * Scope: create ONE instance per batch run and pass it through the phases. Do NOT
 * make it static/persistent — LinkedIn and website content changes between runs, so
 * a long-lived cache would serve stale data.
 */
public class ScrapeCache
{
    private final ConcurrentHashMap<String, LinkedInScrapeResult> profileCache0 = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, LinkedInScrapeResult> companyCache0 = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, String> crawlCache0 = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, String> llmCache0 = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, java.util.ArrayList<SerpResult>> serpCache0 = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, java.util.List<NewsItem>> newsCache0 = new ConcurrentHashMap<>();

    public LinkedInScrapeResult scrapeProfile(BrightDataLinkedInClient client0, String url0) throws Exception
    {
        if (url0 == null || url0.trim().isEmpty())
        {
            return new LinkedInScrapeResult();
        }
        LinkedInScrapeResult cached0 = profileCache0.get(url0);
        if (cached0 != null)
        {
            return cached0;
        }
        LinkedInScrapeResult result0 = client0.scrapeProfile(url0);
        if (result0 != null)
        {
            profileCache0.put(url0, result0);
        }
        return result0;
    }

    public LinkedInScrapeResult scrapeCompany(BrightDataLinkedInClient client0, String url0) throws Exception
    {
        if (url0 == null || url0.trim().isEmpty())
        {
            return new LinkedInScrapeResult();
        }
        LinkedInScrapeResult cached0 = companyCache0.get(url0);
        if (cached0 != null)
        {
            return cached0;
        }
        LinkedInScrapeResult result0 = client0.scrapeCompany(url0);
        if (result0 != null)
        {
            companyCache0.put(url0, result0);
        }
        return result0;
    }

    public String crawl(String url0) throws Exception
    {
        if (url0 == null || url0.trim().isEmpty())
        {
            return "";
        }
        String cached0 = crawlCache0.get(url0);
        if (cached0 != null)
        {
            return cached0;
        }
        String html0 = WebsiteCrawlerService.scrapeUrl(url0);
        if (html0 != null)
        {
            crawlCache0.put(url0, html0);
        }
        return html0;
    }

    /**
     * Cached Google SERP search. Keyed by query + result count, so repeated
     * searches (e.g. the same macro/news query across rows) hit Bright Data once
     * per batch. Returns a defensive copy so callers can't mutate the cached list.
     */
    public java.util.ArrayList<SerpResult> search(BrightDataSerpClient client0, String query0, int maxResults0) throws Exception
    {
        if (query0 == null || query0.trim().isEmpty())
        {
            return new java.util.ArrayList<SerpResult>();
        }
        String key0 = maxResults0 + "|" + query0.trim();
        java.util.ArrayList<SerpResult> cached0 = serpCache0.get(key0);
        if (cached0 != null)
        {
            return new java.util.ArrayList<SerpResult>(cached0);
        }
        java.util.ArrayList<SerpResult> results0 = client0.search(query0.trim(), maxResults0);
        if (results0 != null)
        {
            serpCache0.put(key0, results0);
            return new java.util.ArrayList<SerpResult>(results0);
        }
        return new java.util.ArrayList<SerpResult>();
    }

    /**
     * Cached news SERP search. Keyed by query so the same news query (e.g. fund-close
     * or new-allocator search) is fetched at most once per batch.
     */
    public java.util.List<NewsItem> searchNews(NewsClient client0, String query0, int maxResults0)
    {
        if (query0 == null || query0.trim().isEmpty())
        {
            return new java.util.ArrayList<>();
        }
        String key0 = maxResults0 + "|NEWS|" + query0.trim();
        java.util.List<NewsItem> cached0 = newsCache0.get(key0);
        if (cached0 != null)
        {
            return new java.util.ArrayList<>(cached0);
        }
        java.util.List<NewsItem> results0 = client0.searchNews(query0.trim(), maxResults0);
        if (results0 != null)
        {
            newsCache0.put(key0, results0);
            return new java.util.ArrayList<>(results0);
        }
        return new java.util.ArrayList<>();
    }

    /**
     * Cached OpenAI text completion. Keyed by a digest of the FULL prompt, so two
     * different people (whose prompts embed different names + page text) never collide.
     */
    public String llm(String prompt0) throws Exception
    {
        if (prompt0 == null || prompt0.trim().isEmpty())
        {
            return "";
        }
        String key0 = digest(prompt0);
        String cached0 = llmCache0.get(key0);
        if (cached0 != null)
        {
            return cached0;
        }
        String out0 = OpenAIClient.getTextResponse(prompt0);
        if (out0 != null)
        {
            llmCache0.put(key0, out0);
        }
        return out0;
    }

    private static String digest(String text0)
    {
        try
        {
            java.security.MessageDigest md0 = java.security.MessageDigest.getInstance("SHA-256");
            byte[] hash0 = md0.digest(text0.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder sb0 = new StringBuilder();
            for (byte b0 : hash0)
            {
                sb0.append(String.format("%02x", b0));
            }
            return sb0.toString();
        }
        catch (Exception e0)
        {
            // Fallback: length-qualified hashCode (collision-resistant enough as a fallback)
            return Integer.toHexString(text0.hashCode()) + ":" + text0.length();
        }
    }
}
