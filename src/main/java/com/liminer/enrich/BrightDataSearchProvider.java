package com.liminer.enrich;

import java.util.ArrayList;

/*
 * BrightDataSearchProvider wraps the existing Bright Data SERP implementation
 * behind the SearchProvider seam. It calls searchBrightData0 directly (never
 * search()) so SearchRouter's delegation from BrightDataSerpClient.search()
 * cannot recurse back into this provider.
 */
public class BrightDataSearchProvider implements SearchProvider
{
    private static final String BRIGHT_DATA_API_TOKEN0 = System.getenv("BRIGHT_DATA_API_TOKEN");

    private final BrightDataSerpClient client0 = new BrightDataSerpClient();

    @Override
    public String name()
    {
        return "brightdata";
    }

    @Override
    public boolean isAvailable()
    {
        return !isBlank(BRIGHT_DATA_API_TOKEN0) && BrightDataZoneHealth.isHealthy();
    }

    @Override
    public ArrayList<SerpResult> search(String query0, int maxResults0) throws Exception
    {
        return client0.searchBrightData0(query0, maxResults0);
    }

    private static boolean isBlank(String value0)
    {
        return value0 == null || value0.trim().length() == 0;
    }
}
