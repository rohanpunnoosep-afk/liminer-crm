package com.liminer.enrich;

import java.util.ArrayList;

/*
 * SearchProvider is the seam behind SearchRouter's ordered fallback chain.
 *
 * Contract: search() must return only absolute http(s) URLs pointing off the
 * search engine's own domain (see SerpUrls.isUsefulUrl), and must return an
 * empty list -- never a redirect stub or other unfetchable placeholder -- when
 * it cannot produce a usable destination. That is precisely the contract
 * Bright Data's SERP zone violated: it answered normally but returned results
 * whose links were all unusable, which read downstream as "found nothing"
 * instead of "this provider is broken" (see BrightDataZoneHealth).
 */
public interface SearchProvider
{
    String name();

    boolean isAvailable();

    ArrayList<SerpResult> search(String query0, int maxResults0) throws Exception;
}
