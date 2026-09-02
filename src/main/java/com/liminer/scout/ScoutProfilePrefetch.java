package com.liminer.scout;

import com.liminer.core.InvestorProfile;

import java.io.IOException;
import java.util.List;

/*
 * ScoutProfilePrefetch — refresh-time job (task 0089) that warms
 * ScoutFitProfileCache for the newest HOT-TIER records (task 0088's
 * ScoutUniverseIndexer output) ahead of any per-client run, so the slow
 * Tier-B extraction step (ScoutFitTierB) is already cached when a client
 * asks for new funds. It reuses ScoutFitProfileCache's exact key (crd +
 * snapshotMonth) and ScoutNonLinkedInProfileSource's exact brochure/website
 * waterfall — no second cache, no duplicated extraction logic.
 *
 * LinkedIn/Bright Data is intentionally unreachable from this job: only the
 * brochure and website sources run unattended, so a cron invocation can never
 * burn Bright Data quota. That source stays reserved for on-demand,
 * human-in-the-loop client runs (ScoutFitTierB.applyTierB).
 *
 * Bounded by maxRecords (SCOUT_PREFETCH_MAX env var in production, default
 * DEFAULT_MAX), counted only against records that actually needed extraction
 * -- already-cached hot records are free and don't count against the cap.
 * Hot-file order is already highest resources/probabilityNow first
 * (ScoutUniverseIndexer's sort), so no re-sorting is needed here.
 */
public class ScoutProfilePrefetch
{
    public static final int DEFAULT_MAX = 25;
    public static final String ENV_MAX = "SCOUT_PREFETCH_MAX";

    public interface ProfileExtractor
    {
        ScoutFitProfileCache.CachedProfile extract(ScoutUniverseRecord record0) throws Exception;
    }

    public static class DefaultProfileExtractor implements ProfileExtractor
    {
        private final ScoutNonLinkedInProfileSource source0;

        public DefaultProfileExtractor() { this(new ScoutNonLinkedInProfileSource()); }

        public DefaultProfileExtractor(ScoutNonLinkedInProfileSource source0) { this.source0 = source0; }

        @Override
        public ScoutFitProfileCache.CachedProfile extract(ScoutUniverseRecord record0) throws Exception
        {
            ScoutFitProfileCache.CachedProfile acquired0 = source0.acquire(record0);
            return acquired0 == null
                ? new ScoutFitProfileCache.CachedProfile(new InvestorProfile(), ScoutFitResult.SOURCE_NONE)
                : acquired0;
        }
    }

    public static class Result
    {
        public String snapshotMonth;
        public int attempted;
        public int extracted;
        public int alreadyCached;
        public int failed;

        public String summary()
        {
            return "profile-prefetch: month=" + (snapshotMonth == null ? "none" : snapshotMonth)
                + " attempted=" + attempted + " extracted=" + extracted
                + " already-cached=" + alreadyCached + " failed=" + failed;
        }
    }

    private final ScoutUniverseIndexer indexer0;
    private final ScoutFitProfileCache cache0;
    private final ProfileExtractor extractor0;

    public ScoutProfilePrefetch()
    {
        this(new ScoutUniverseIndexer(), new ScoutFitProfileCache(), new DefaultProfileExtractor());
    }

    public ScoutProfilePrefetch(ScoutUniverseIndexer indexer0, ScoutFitProfileCache cache0, ProfileExtractor extractor0)
    {
        this.indexer0 = indexer0;
        this.cache0 = cache0;
        this.extractor0 = extractor0;
    }

    // Resolves the newest hot-tier month present and prefetches it.
    public Result runLatest(int maxRecords0) throws IOException
    {
        List<String> months0 = indexer0.availableHotMonths();
        Result result0 = new Result();
        if (months0.isEmpty())
        {
            return result0;
        }

        String newestMonth0 = months0.get(months0.size() - 1);
        return run(newestMonth0, maxRecords0);
    }

    public Result run(String yyyyMm0, int maxRecords0) throws IOException
    {
        Result result0 = new Result();
        result0.snapshotMonth = yyyyMm0;

        List<ScoutScoredRecord> hot0 = indexer0.loadHot(yyyyMm0);

        for (ScoutScoredRecord scored0 : hot0)
        {
            if (result0.attempted >= maxRecords0)
            {
                break;
            }

            ScoutUniverseRecord record0 = scored0 == null ? null : scored0.record;
            if (record0 == null)
            {
                continue;
            }

            ScoutFitProfileCache.CachedProfile cached0 = cache0.load(record0.crd, record0.snapshotMonth);
            if (cached0 != null)
            {
                result0.alreadyCached++;
                continue;
            }

            result0.attempted++;
            try
            {
                ScoutFitProfileCache.CachedProfile extracted0 = extractor0.extract(record0);
                cache0.save(record0.crd, record0.snapshotMonth, extracted0);
                result0.extracted++;
            }
            catch (Exception exception0)
            {
                result0.failed++;
            }
        }

        return result0;
    }

    public static int resolveMaxFromEnv()
    {
        String raw0 = System.getenv(ENV_MAX);
        if (raw0 == null || raw0.trim().length() == 0)
        {
            return DEFAULT_MAX;
        }

        try
        {
            return Integer.parseInt(raw0.trim());
        }
        catch (NumberFormatException exception0)
        {
            return DEFAULT_MAX;
        }
    }
}
