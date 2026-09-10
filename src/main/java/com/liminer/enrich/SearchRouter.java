package com.liminer.enrich;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/*
 * SearchRouter tries SearchProviders in order and falls back on failure, so
 * every existing search() call site gets multi-provider failover without
 * being edited -- BrightDataSerpClient.search() simply delegates here.
 *
 * Only a THROWN failure counts against a provider: after DEMOTE_STREAK_LIMIT0
 * consecutive throws it is demoted (skipped) for the rest of the run, and any
 * successful call clears the streak.
 *
 * An empty result deliberately does NOT count. The enrichment pipeline fires
 * long strings of narrow site: queries ("site:linkedin.com/in \"Goel\"
 * \"Scotiabank\""), and a run of those legitimately matching nothing is the
 * normal case, not a sick provider. Counting empties demoted every provider
 * partway through a background check, after which the rest of the run made no
 * searches at all and reported "no candidates found" as if it had looked.
 * "Provider is answering but its links are junk" is a different condition and
 * belongs to BrightDataZoneHealth, which inspects the links themselves.
 */
public class SearchRouter implements SearchProvider
{
    private static final int DEMOTE_STREAK_LIMIT0 = 3;

    private static final SearchRouter SHARED0 = new SearchRouter(defaultProviders0());

    private final List<SearchProvider> providers0;
    private final Map<SearchProvider, AtomicInteger> streaks0 = new ConcurrentHashMap<SearchProvider, AtomicInteger>();
    private final Set<SearchProvider> demoted0 = ConcurrentHashMap.newKeySet();

    public SearchRouter(List<SearchProvider> providers1)
    {
        providers0 = new ArrayList<SearchProvider>(providers1);
    }

    public static SearchRouter shared()
    {
        return SHARED0;
    }

    private static List<SearchProvider> defaultProviders0()
    {
        List<SearchProvider> providers0 = new ArrayList<SearchProvider>();
        providers0.add(new DataForSeoSerpProvider());
        providers0.add(new BrightDataSearchProvider());
        providers0.add(new DuckDuckGoUnlockerProvider());
        return providers0;
    }

    @Override
    public String name()
    {
        return "router";
    }

    @Override
    public boolean isAvailable()
    {
        return true;
    }

    @Override
    public ArrayList<SerpResult> search(String query0, int maxResults0) throws Exception
    {
        for (SearchProvider provider0 : providers0)
        {
            if (demoted0.contains(provider0))
            {
                continue;
            }

            if (!provider0.isAvailable())
            {
                System.out.println("SearchRouter: skipping " + provider0.name() + " (unavailable)");
                continue;
            }

            System.out.println("SearchRouter: attempting " + provider0.name());

            try
            {
                ArrayList<SerpResult> results0 = provider0.search(query0, maxResults0);
                if (results0 != null && !results0.isEmpty())
                {
                    resetStreak0(provider0);
                    return results0;
                }

                noteEmpty0(provider0);
                resetStreak0(provider0);
            }
            catch (Exception exception0)
            {
                noteFailure0(provider0, exception0);
            }
        }

        return new ArrayList<SerpResult>();
    }

    /* Clears all per-provider streaks and demotions. Call between test runs or workflow runs. */
    public void reset()
    {
        streaks0.clear();
        demoted0.clear();
    }

    private void resetStreak0(SearchProvider provider0)
    {
        streaks0.remove(provider0);
    }

    /*
     * An empty answer is a search miss, not a fault: log it so the fallback chain is
     * visible in the run output, but leave the provider's failure streak alone.
     */
    private void noteEmpty0(SearchProvider provider0)
    {
        System.out.println("SearchRouter: " + provider0.name() + " returned no usable results");
    }

    private void noteFailure0(SearchProvider provider0, Exception exception0)
    {
        System.out.println("SearchRouter: " + provider0.name() + " failed: " + exception0.getMessage());
        bumpStreak0(provider0);
    }

    private void bumpStreak0(SearchProvider provider0)
    {
        AtomicInteger streak0 = streaks0.computeIfAbsent(provider0, ignored0 -> new AtomicInteger(0));
        int count0 = streak0.incrementAndGet();

        if (count0 >= DEMOTE_STREAK_LIMIT0)
        {
            demoted0.add(provider0);
            System.out.println("SearchRouter: demoting " + provider0.name()
                + " after " + count0 + " consecutive empty/failed responses");
        }
    }
}
