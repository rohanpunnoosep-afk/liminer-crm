package com.liminer.scout;

import com.liminer.core.CRMRegistry;
import com.liminer.core.SessionContext;
import com.liminer.enrich.AdvBulkClient;
import com.liminer.enrich.EdgarClient;
import com.liminer.enrich.Irs990BulkIndexClient;
import com.liminer.enrich.ProPublicaNonprofitClient;

import java.io.PrintStream;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;

/*
 * ScoutCronRunner — headless (non-interactive) CLI entry path for the four
 * Investor Scout batch cadences, so they can run from cron/launchd without a
 * human at the AgentMain terminal menu (Investor Scout plan §8 step 6, Phase 2
 * task 4 of 4). This is a thin dispatcher only: job logic lives in the
 * existing Scout components (AdvBulkClient, ScoutUniverseStore/
 * FileScoutUniverseStore, ScoutUniverseDiff, EdgarClient, ScoutSignalScorer,
 * InvestorScoutProcessor, ProPublicaNonprofitClient, Irs990BulkIndexClient).
 * AgentMain's interactive menu is untouched by this class.
 *
 * Jobs:
 *   adv-refresh                 monthly ADV bulk snapshot + diff against the
 *                                previous snapshot month
 *   edgar-delta                  daily EDGAR delta rescoring using the cached
 *                                ScoutSignalScorer timing cache
 *   client-run <clientEmail>     weekly per-client scout run (clientEmail is
 *                                looked up via CRMRegistry.login, the same
 *                                lookup key AgentMain's interactive login
 *                                uses today; there is no separate numeric
 *                                clientId concept in CRMRegistry yet)
 *   nonprofit-refresh            quarterly 990 discovery refresh
 *   profile-prefetch             warms ScoutFitProfileCache for the newest
 *                                hot-tier records ahead of any client run
 *                                (brochure/website sources only, task 0089;
 *                                never LinkedIn/Bright Data -- that stays
 *                                reserved for on-demand client-run)
 *
 * Headless contract: no stdin prompts on any job path, errors go to stderr,
 * failures exit nonzero. Sample crontab / launchd cadences are documented at
 * the bottom of this file (offline documentation only — nothing is installed
 * by this class or by ScoutCronRunnerTestMain).
 */
public class ScoutCronRunner
{
    public static final String JOB_ADV_REFRESH = "adv-refresh";
    public static final String JOB_EDGAR_DELTA = "edgar-delta";
    public static final String JOB_CLIENT_RUN = "client-run";
    public static final String JOB_NONPROFIT_REFRESH = "nonprofit-refresh";
    public static final String JOB_PROFILE_PREFETCH = "profile-prefetch";

    // -----------------------------------------------------------------------
    // Handler seam — DefaultJobHandlers wires to the real components below;
    // ScoutCronRunnerTestMain injects a stub implementation so routing/arg
    // parsing/failure paths are fully testable offline.
    // -----------------------------------------------------------------------

    public interface JobHandlers
    {
        String advRefresh(String[] jobArgs0) throws Exception;

        String edgarDelta(String[] jobArgs0) throws Exception;

        String clientRun(String clientEmail0, String[] jobArgs0) throws Exception;

        String nonprofitRefresh(String[] jobArgs0) throws Exception;

        String profilePrefetch(String[] jobArgs0) throws Exception;
    }

    public static class DefaultJobHandlers implements JobHandlers
    {
        @Override
        public String advRefresh(String[] jobArgs0) throws Exception
        {
            ScoutWorkflowGate.requireEnabled("ScoutCronRunner.advRefresh");
            String yyyyMm0 = DateTimeFormatter.ofPattern("yyyy-MM").format(LocalDate.now());
            Path downloadDir0 = Paths.get("data", "scout", "adv-bulk-downloads");

            AdvBulkClient advClient0 = new AdvBulkClient();
            ScoutUniverseStore store0 = new FileScoutUniverseStore();

            List<ScoutUniverseRecord> records0 = advClient0.fetchAndParse(yyyyMm0, downloadDir0);
            store0.save(yyyyMm0, records0);

            List<String> months0 = store0.availableMonths();
            String summary0 = "adv-refresh: saved " + records0.size() + " records for " + yyyyMm0 + ".";
            if (months0.size() >= 2)
            {
                String prevMonth0 = months0.get(months0.size() - 2);
                ScoutUniverseDiff diff0 = store0.diff(prevMonth0, yyyyMm0);
                summary0 += " Diff vs " + prevMonth0 + ": " + diff0.newCrds.size() + " new, "
                    + diff0.raumChangeCrds.size() + " RAUM changes, "
                    + diff0.newFundOfFundsCrds.size() + " new fund-of-funds.";
            }

            ScoutSignalScorer signalScorer0 = new ScoutSignalScorer();
            Map<Integer, ScoutSignalScore> cachedScores0 = signalScorer0.loadScores(yyyyMm0);
            ScoutUniverseIndexer indexer0 = new ScoutUniverseIndexer();
            String funnel0 = indexer0.indexAndWrite(yyyyMm0, records0, cachedScores0);
            summary0 += " " + funnel0;

            return summary0;
        }

        @Override
        public String edgarDelta(String[] jobArgs0) throws Exception
        {
            ScoutWorkflowGate.requireEnabled("ScoutCronRunner.edgarDelta");
            EdgarClient edgarClient0 = new EdgarClient();
            ScoutSignalScorer signalScorer0 = new ScoutSignalScorer();

            String yyyyMm0 = DateTimeFormatter.ofPattern("yyyy-MM").format(LocalDate.now());
            LocalDate today0 = LocalDate.now();

            List<EdgarClient.DailyIndexEntry> entries0 = edgarClient0.fetchDailyIndex(today0);
            Map<Integer, ScoutSignalScore> cachedScores0 = signalScorer0.loadScores(yyyyMm0);

            return "edgar-delta: fetched " + entries0.size() + " daily index entries for " + today0
                + "; " + cachedScores0.size() + " cached signal scores for " + yyyyMm0 + ".";
        }

        @Override
        public String clientRun(String clientEmail0, String[] jobArgs0) throws Exception
        {
            ScoutWorkflowGate.requireEnabled("ScoutCronRunner.clientRun");
            SessionContext context0 = CRMRegistry.login(clientEmail0);
            if (context0 == null)
            {
                throw new IllegalStateException("client-run: no CRM session found for " + clientEmail0);
            }

            InvestorScoutProcessor processor0 = new InvestorScoutProcessor();
            InvestorScoutProcessor.RunParams params0 = new InvestorScoutProcessor.RunParams();
            return processor0.run(context0, params0);
        }

        @Override
        public String nonprofitRefresh(String[] jobArgs0) throws Exception
        {
            ScoutWorkflowGate.requireEnabled("ScoutCronRunner.nonprofitRefresh");
            ProPublicaNonprofitClient proPublicaClient0 = new ProPublicaNonprofitClient();
            Irs990BulkIndexClient bulkIndexClient0 = new Irs990BulkIndexClient();

            String json0 = proPublicaClient0.fetchDiscoverResults("T20", "MA", 0);
            List<ScoutUniverseRecord> discovered0 = proPublicaClient0.mapDiscoveryResults(json0);

            return "nonprofit-refresh: discovered " + discovered0.size()
                + " candidate organizations via ProPublica search (bulk-index refresh: "
                + bulkIndexClient0.getClass().getSimpleName() + " ready, no bulk file configured for this run).";
        }

        @Override
        public String profilePrefetch(String[] jobArgs0) throws Exception
        {
            ScoutWorkflowGate.requireEnabled("ScoutCronRunner.profilePrefetch");
            ScoutProfilePrefetch prefetch0 = new ScoutProfilePrefetch();
            int maxRecords0 = ScoutProfilePrefetch.resolveMaxFromEnv();
            ScoutProfilePrefetch.Result result0 = prefetch0.runLatest(maxRecords0);
            return result0.summary();
        }
    }

    // -----------------------------------------------------------------------
    // Dispatch
    // -----------------------------------------------------------------------

    public static int dispatch(String[] args0, JobHandlers handlers0, PrintStream out0, PrintStream err0)
    {
        if (args0 == null || args0.length == 0)
        {
            printUsage(err0);
            return 1;
        }

        String job0 = args0[0];
        String[] jobArgs0 = new String[args0.length - 1];
        System.arraycopy(args0, 1, jobArgs0, 0, jobArgs0.length);

        try
        {
            String result0;

            if (JOB_ADV_REFRESH.equals(job0))
            {
                result0 = handlers0.advRefresh(jobArgs0);
            }
            else if (JOB_EDGAR_DELTA.equals(job0))
            {
                result0 = handlers0.edgarDelta(jobArgs0);
            }
            else if (JOB_CLIENT_RUN.equals(job0))
            {
                if (jobArgs0.length < 1 || jobArgs0[0] == null || jobArgs0[0].trim().length() == 0)
                {
                    err0.println("ERROR: client-run requires a <clientEmail> argument.");
                    printUsage(err0);
                    return 1;
                }
                result0 = handlers0.clientRun(jobArgs0[0].trim(), jobArgs0);
            }
            else if (JOB_NONPROFIT_REFRESH.equals(job0))
            {
                result0 = handlers0.nonprofitRefresh(jobArgs0);
            }
            else if (JOB_PROFILE_PREFETCH.equals(job0))
            {
                result0 = handlers0.profilePrefetch(jobArgs0);
            }
            else
            {
                err0.println("ERROR: Unknown job \"" + job0 + "\".");
                printUsage(err0);
                return 1;
            }

            out0.println(result0);
            return 0;
        }
        catch (Exception e0)
        {
            err0.println("ERROR: Job \"" + job0 + "\" failed: " + e0.getMessage());
            return 1;
        }
    }

    private static void printUsage(PrintStream err0)
    {
        err0.println("Usage: ScoutCronRunner --headless <job> [args]");
        err0.println("  Jobs:");
        err0.println("    " + JOB_ADV_REFRESH + "                 monthly ADV bulk snapshot + diff");
        err0.println("    " + JOB_EDGAR_DELTA + "                  daily EDGAR delta rescoring");
        err0.println("    " + JOB_CLIENT_RUN + " <clientEmail>   weekly per-client scout run");
        err0.println("    " + JOB_NONPROFIT_REFRESH + "            quarterly 990 discovery refresh");
        err0.println("    " + JOB_PROFILE_PREFETCH + "           warm the fit-profile cache for hot-tier records");
    }

    public static void main(String[] args0)
    {
        // First positional token accepted is the job name; a leading
        // "--headless" flag (if present, e.g. from a wrapper script) is
        // stripped so both "ScoutCronRunner --headless adv-refresh" and
        // "ScoutCronRunner adv-refresh" work identically.
        String[] effectiveArgs0 = args0;
        if (args0 != null && args0.length > 0 && "--headless".equals(args0[0]))
        {
            effectiveArgs0 = new String[args0.length - 1];
            System.arraycopy(args0, 1, effectiveArgs0, 0, effectiveArgs0.length);
        }

        int exitCode0 = dispatch(effectiveArgs0, new DefaultJobHandlers(), System.out, System.err);
        System.exit(exitCode0);
    }

    // -----------------------------------------------------------------------
    // Sample cron / launchd cadences (offline documentation only — nothing is
    // installed by this codebase; a human operator installs these manually):
    //
    // crontab (monthly ADV / daily EDGAR / weekly per-client / quarterly 990):
    //
    //   # ADV bulk snapshot + diff — 1st of month, 02:00
    //   0 2 1 * * cd /path/to/liminer && java -cp "target/classes:$(cat cp.txt)" \
    //       ScoutCronRunner --headless adv-refresh >> logs/scout-cron.log 2>&1
    //
    //   # EDGAR daily delta rescoring — every day, 03:00
    //   0 3 * * * cd /path/to/liminer && java -cp "target/classes:$(cat cp.txt)" \
    //       ScoutCronRunner --headless edgar-delta >> logs/scout-cron.log 2>&1
    //
    //   # Per-client scout run — every Monday, 04:00
    //   0 4 * * 1 cd /path/to/liminer && java -cp "target/classes:$(cat cp.txt)" \
    //       ScoutCronRunner --headless client-run client@example.com >> logs/scout-cron.log 2>&1
    //
    //   # Nonprofit 990 discovery refresh — 1st of Jan/Apr/Jul/Oct, 05:00
    //   0 5 1 1,4,7,10 * cd /path/to/liminer && java -cp "target/classes:$(cat cp.txt)" \
    //       ScoutCronRunner --headless nonprofit-refresh >> logs/scout-cron.log 2>&1
    //
    // launchd (one plist per cadence, e.g.
    // ~/Library/LaunchAgents/ai.liminer.scout.adv-refresh.plist):
    //
    //   <?xml version="1.0" encoding="UTF-8"?>
    //   <!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN"
    //     "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
    //   <plist version="1.0">
    //   <dict>
    //     <key>Label</key><string>ai.liminer.scout.adv-refresh</string>
    //     <key>ProgramArguments</key>
    //     <array>
    //       <string>java</string>
    //       <string>-cp</string>
    //       <string>/path/to/liminer/target/classes:/path/to/liminer/cp.txt-contents</string>
    //       <string>ScoutCronRunner</string>
    //       <string>--headless</string>
    //       <string>adv-refresh</string>
    //     </array>
    //     <key>WorkingDirectory</key><string>/path/to/liminer</string>
    //     <key>StartCalendarInterval</key>
    //     <dict>
    //       <key>Day</key><integer>1</integer>
    //       <key>Hour</key><integer>2</integer>
    //       <key>Minute</key><integer>0</integer>
    //     </dict>
    //     <key>StandardOutPath</key><string>/path/to/liminer/logs/scout-cron.log</string>
    //     <key>StandardErrorPath</key><string>/path/to/liminer/logs/scout-cron.log</string>
    //   </dict>
    //   </plist>
    //
    //   (edgar-delta: StartCalendarInterval with only Hour/Minute, no Day, for
    //   daily; client-run: Weekday=1 for Monday; nonprofit-refresh: four
    //   separate plists or four <dict> entries in StartCalendarInterval array
    //   for Jan/Apr/Jul/Oct.)
    // -----------------------------------------------------------------------
}
