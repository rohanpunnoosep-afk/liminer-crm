package com.liminer.enrich;

import com.liminer.billing.CostMeter;
import com.liminer.core.CRMField;
import com.liminer.core.CRMFieldRegistry;
import com.liminer.core.SessionContext;
import com.liminer.scout.IdentityResolutionScorer;
import com.liminer.scout.SearchTermGenerator;
import com.liminer.sheets.SheetsApp;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.HashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import org.json.JSONArray;
import org.json.JSONObject;

/*
 * BasicBackgroundChecker fills LinkedIn, bio, and background fields for CRM contacts.
 *
 * Given partial contact data (name, email, fund, website), it:
 * 1. Runs scored SERP queries to find up to 3 company LinkedIn pages for the domain.
 * 2. For each company candidate, searches for the contact's LinkedIn profile.
 * 3. Scrapes and verifies profiles against the known identity.
 * 4. Crawls the fund website for a contact bio page.
 * 5. Writes only fields that pass the confidence threshold.
 * 6. Stores all evidence and rejected candidates in Background Check JSON.
 *
 * No hallucination: OpenAI may only summarize scraped source text.
 */
public class BasicBackgroundChecker
{
    private static final int MAX_PERSON_CANDIDATES_TO_SCRAPE = 3;
    private static final int MAX_SERP_RESULTS_PER_QUERY = 5;
    private static final int MAX_CRM_ROWS = 500;
    private static final int MAX_COLUMNS = 200;
    private static final int MAX_ROWS_TO_CHECK = 25;
    private static final int MAX_WEBSITE_TEXT_CHARS = 30000;
    private static final int MAX_COMPANY_CANDIDATES = 3;

    // Bright Data account allows high concurrency (20+); 12 is a safe, tunable
    // pool size for processing CRM rows in parallel. Lower this if you start
    // seeing HTTP 429 (rate limited) or frequent 502 errors.
    private static final int ROW_THREAD_POOL_SIZE = 12;

    // Shared, bounded pool for LinkedIn scrape fan-out (Phase 1 company scrapes and
    // Phase 2/5 profile scrapes). Routing ALL scrapes through one bounded pool caps
    // total in-flight scrapes regardless of how many rows are active, so intra-row
    // fan-out cannot blow past Bright Data's concurrency ceiling. Tasks here are
    // LEAVES (they never submit back to this pool), so there is no risk of pool
    // starvation/deadlock from caller threads blocking on results.
    private static final int SCRAPE_POOL_SIZE = 16;
    private static volatile ExecutorService SCRAPE_POOL = null;

    private static ExecutorService scrapePool()
    {
        if (SCRAPE_POOL == null)
        {
            synchronized (BasicBackgroundChecker.class)
            {
                if (SCRAPE_POOL == null)
                {
                    SCRAPE_POOL = Executors.newFixedThreadPool(SCRAPE_POOL_SIZE, r0 -> {
                        Thread t0 = new Thread(r0, "bd-scrape");
                        t0.setDaemon(true); // daemon → JVM can exit without explicit shutdown
                        return t0;
                    });
                }
            }
        }
        return SCRAPE_POOL;
    }

    // Scrape multiple LinkedIn profile URLs concurrently (bounded by SCRAPE_POOL).
    // Returns url -> scrape result; failures are logged and omitted. Cache dedups.
    private static java.util.Map<String, LinkedInScrapeResult> parallelScrapeProfiles(
        java.util.List<String> urls0, ScrapeCache cache0)
    {
        BrightDataLinkedInClient client0 = new BrightDataLinkedInClient();
        ConcurrentHashMap<String, LinkedInScrapeResult> out0 = new ConcurrentHashMap<>();
        ArrayList<Future<?>> futures0 = new ArrayList<>();
        for (String u0 : urls0)
        {
            final String url0 = u0;
            futures0.add(scrapePool().submit(CostMeter.wrap(() -> {
                try
                {
                    LinkedInScrapeResult r0 = cache0.scrapeProfile(client0, url0);
                    if (r0 != null) { out0.put(url0, r0); }
                }
                catch (Exception e0)
                {
                    System.out.println("  Profile scrape failed: " + e0.getMessage());
                }
            })));
        }
        joinBounded(futures0);
        return out0;
    }

    // Scrape multiple LinkedIn company URLs concurrently (bounded by SCRAPE_POOL).
    private static java.util.Map<String, LinkedInScrapeResult> parallelScrapeCompanies(
        java.util.List<String> urls0, ScrapeCache cache0)
    {
        BrightDataLinkedInClient client0 = new BrightDataLinkedInClient();
        ConcurrentHashMap<String, LinkedInScrapeResult> out0 = new ConcurrentHashMap<>();
        ArrayList<Future<?>> futures0 = new ArrayList<>();
        for (String u0 : urls0)
        {
            final String url0 = u0;
            futures0.add(scrapePool().submit(CostMeter.wrap(() -> {
                try
                {
                    LinkedInScrapeResult r0 = cache0.scrapeCompany(client0, url0);
                    if (r0 != null) { out0.put(url0, r0); }
                }
                catch (Exception e0)
                {
                    System.out.println("  Company scrape failed: " + e0.getMessage());
                }
            })));
        }
        joinBounded(futures0);
        return out0;
    }

    // Join scrape futures within the current row's remaining budget. The active
    // Deadline is read from ROW_DEADLINE (a ThreadLocal set at the top of check),
    // since all LinkedIn-scrape fan-out runs on the row thread -- this avoids
    // threading a Deadline param through every intermediate resolve* method.
    // Futures that don't finish in time are cancelled with interrupt; because
    // the Bright Data HttpClient.send() is interruptible, cancel(true) aborts the
    // in-flight request and returns the SCRAPE_POOL thread to the pool. Never
    // shut down SCRAPE_POOL here -- it is shared across all rows.
    private static void joinBounded(ArrayList<Future<?>> futures0)
    {
        Deadline deadline0 = ROW_DEADLINE.get();
        for (Future<?> f0 : futures0)
        {
            long rem0 = (deadline0 == null) ? Long.MAX_VALUE : Math.max(0, deadline0.remainingMs());
            try { f0.get(rem0, TimeUnit.MILLISECONDS); }
            catch (TimeoutException te0) { f0.cancel(true); }
            catch (Exception ignored0) { }
        }
    }

    private static final String STATUS_COMPLETED = "COMPLETED";
    private static final String STATUS_PARTIAL = "PARTIAL";
    private static final String STATUS_FAILED = "FAILED";
    private static final String STATUS_INSUFFICIENT_INPUT = "INSUFFICIENT_INPUT";
    private static final String STATUS_AMBIGUOUS = "AMBIGUOUS";

    // Per-row wall-clock ceiling. Under Bright Data throttling a single row's
    // ~19-27 scrapes can each sit near their response-timeout ceiling and stack,
    // so without a budget a row (and the whole batch) can hang for 30+ min. A
    // healthy row's critical path is well under a minute, so 8 min never trips a
    // good row but caps the pathological tail. Tunable via env (BD_ROW_BUDGET_MS)
    // without a recompile -- e.g. set low to exercise the budget path in tests.
    private static final long ROW_BUDGET_MS = getEnvLong("BD_ROW_BUDGET_MS", 8 * 60 * 1000L);
    // Extra grace for the hard backstop over the cooperative budget, so the
    // cooperative path (which writes a proper PARTIAL) almost always wins.
    private static final long ROW_HARD_GRACE_MS = getEnvLong("BD_ROW_HARD_GRACE_MS", 60 * 1000L);

    private static long getEnvLong(String name0, long default0)
    {
        String raw0 = System.getenv(name0);
        if (raw0 == null || raw0.trim().isEmpty()) { return default0; }
        try { return Long.parseLong(raw0.trim()); }
        catch (NumberFormatException e0) { return default0; }
    }

    // Immutable per-row wall-clock deadline. Passed into check() so every phase
    // and every internal Future.get shares ONE budget and cannot re-stack
    // latencies. remainingMs() drives bounded waits; expired() gates phases.
    private static final class Deadline
    {
        final long endMs;
        Deadline(long budgetMs0) { this.endMs = System.currentTimeMillis() + budgetMs0; }
        long remainingMs() { return endMs - System.currentTimeMillis(); }
        boolean expired() { return remainingMs() <= 0; }
    }

    // The current row's deadline, set at the top of check(). Each row runs on its
    // own ROW_THREAD_POOL thread and overwrites this before doing any scraping,
    // so the deep joinBounded() helper can read it without a param. Cleared in a
    // finally so a pooled thread never carries a stale deadline into idle time.
    private static final ThreadLocal<Deadline> ROW_DEADLINE = new ThreadLocal<>();

    // ============================================================
    // INNER CLASSES
    // ============================================================

    public static class BackgroundCheckInput
    {
        public String firstName = "";
        public String lastName = "";
        public String cleanedEmail = "";
        public String fundName = "";
        public String fundWebsite = "";
        public int crmRowNumber = -1;
    }

    public static class ResolvedField
    {
        public String value = "";
        public double confidence = 0.0;
        public String sourceUrl = "";
        public String evidence = "";

        public ResolvedField() {}

        public ResolvedField(String value0, double confidence0)
        {
            this.value = safe(value0);
            this.confidence = confidence0;
        }

        public ResolvedField(String value0, double confidence0, String sourceUrl0, String evidence0)
        {
            this.value = safe(value0);
            this.confidence = confidence0;
            this.sourceUrl = safe(sourceUrl0);
            this.evidence = safe(evidence0);
        }
    }

    public static class EvidenceItem
    {
        public String type = "";
        public String query = "";
        public String url = "";
        public double score = 0.0;
        public String notes = "";

        public EvidenceItem(String type0, String query0, String url0, double score0, String notes0)
        {
            this.type = safe(type0);
            this.query = safe(query0);
            this.url = safe(url0);
            this.score = score0;
            this.notes = safe(notes0);
        }
    }

    public static class BackgroundCheckResult
    {
        public BackgroundCheckInput input;
        public ResolvedField firstName = new ResolvedField();
        public ResolvedField lastName = new ResolvedField();
        public ResolvedField fundName = new ResolvedField();
        public ResolvedField fundWebsite = new ResolvedField();
        public ResolvedField contactLinkedInUrl = new ResolvedField();
        public ResolvedField contactLinkedInAbout = new ResolvedField();
        public ResolvedField contactPastWorkExperience = new ResolvedField();
        public ResolvedField fundLinkedInUrl = new ResolvedField();
        public ResolvedField fundLinkedInAbout = new ResolvedField();
        public ResolvedField contactWebsiteBioUrl = new ResolvedField();
        public ResolvedField contactWebsiteBioSummary = new ResolvedField();
        public ResolvedField contactLinkedInPostsSummary = new ResolvedField();
        public ResolvedField contactFollowerCount = new ResolvedField();
        public ResolvedField contactBioCareerSummary = new ResolvedField();
        public ResolvedField contactBioInstitutions = new ResolvedField();
        public ResolvedField contactBioEducation = new ResolvedField();
        public double overallConfidence = 0.0;
        public String status = STATUS_FAILED;
        public String backgroundCheckJson = "";
        public String lastCheckedAt = "";
        public ArrayList<EvidenceItem> evidence = new ArrayList<>();
        // Non-null when multiple distinct contacts were identified (e.g., two people with same first name)
        public ArrayList<BackgroundCheckResult> additionalContactCandidates = null;
    }

    private static class ScoredPersonCandidate
    {
        String url;
        String serpTitle;
        double serpScore;

        ScoredPersonCandidate(String url0, String serpTitle0, double serpScore0)
        {
            this.url = url0;
            this.serpTitle = serpTitle0;
            this.serpScore = serpScore0;
        }
    }

    // Holds a validated company LinkedIn candidate from Phase 1.
    private static class ScoredCompanyCandidate
    {
        String url = "";
        String resolvedName = "";  // real LinkedIn company name (scraped)
        double score = 0.0;
        String about = "";         // scraped About text (may be blank)
        String scrapedWebsite = ""; // company website URL from LinkedIn scrape

        ScoredCompanyCandidate(String url0, String resolvedName0, double score0, String about0)
        {
            this.url = safe(url0);
            this.resolvedName = safe(resolvedName0);
            this.score = score0;
            this.about = safe(about0);
        }

        ScoredCompanyCandidate(String url0, String resolvedName0, double score0, String about0, String scrapedWebsite0)
        {
            this.url = safe(url0);
            this.resolvedName = safe(resolvedName0);
            this.score = score0;
            this.about = safe(about0);
            this.scrapedWebsite = safe(scrapedWebsite0);
        }
    }

    // Holds the result of a single person LinkedIn resolution attempt.
    private static class PersonResolutionAttempt
    {
        String contactLinkedInUrl = "";
        String contactLinkedInAbout = "";
        String contactPastWorkExperienceJson = "[]";
        String resolvedLastName = "";
        String resolvedCompanyName = "";   // currentCompanyName from the accepted LinkedIn profile
        String fundLinkedInUrlFromProfile = "";
        String recentPostsJson = "[]";
        String followerCount = "";
        double confidence = 0.0;
    }

    // Holds one bio page candidate from Phase 4 (one per distinct person when ambiguous).
    private static class ContactBioCandidate
    {
        String bioUrl = "";
        String bioSummary = "";
        String bioText = "";
        String extractedLastName = "";
    }

    // ============================================================
    // COLUMN PROVISIONING
    // ============================================================

    // Ensures every output column that BasicBackgroundChecker writes exists in the
    // sheet header row. If a column is absent it is appended at the next empty column,
    // and both headerMap0 and context0.config are updated in-place so buildUpdateColumns
    // immediately sees the correct column numbers. This handles CRM sheets that were
    // created before a CRMFieldRegistry update added new columns.
    private static void ensureBackgroundCheckColumns(
        SessionContext context0,
        String spreadsheetId0,
        String mainTabName0,
        int headerRow0,
        HashMap<String, Integer> headerMap0) throws Exception
    {
        String[] requiredKeys0 = {
            "mainTabContactLinkedInCol",
            "mainTabContactLinkedInAboutCol",
            "mainTabContactPastWorkExperienceCol",
            "mainTabCompanyLinkedInCol",
            "mainTabFundLinkedInAboutCol",
            "mainTabContactWebsiteBioUrlCol",
            "mainTabContactWebsiteBioSummaryCol",
            "mainTabBackgroundCheckStatusCol",
            "mainTabBackgroundCheckConfidenceCol",
            "mainTabLastBackgroundCheckDateCol",
            "mainTabBackgroundCheckJsonCol",
            "mainTabContact1FirstNameCol",
            "mainTabContact1LastNameCol",
            "mainTabFundNameCol",
            "mainTabWebsiteCol",
            "mainTabContactLinkedInPostsSummaryCol",
            "mainTabContactFollowerCountCol",
            "mainTabContactBioCareerSummaryCol",
            "mainTabContactBioInstitutionsCol",
            "mainTabContactBioEducationCol"
        };

        int nextCol0 = 1;
        for (int col0 : headerMap0.values())
        {
            if (col0 >= nextCol0) nextCol0 = col0 + 1;
        }

        // Pass 1: collect missing columns (preserving order) without touching the sheet yet.
        ArrayList<String> missingKeys0 = new ArrayList<>();
        ArrayList<String> missingHeaders0 = new ArrayList<>();

        for (String key0 : requiredKeys0)
        {
            CRMField field0 = CRMFieldRegistry.getByKey(key0);
            if (field0 == null) continue;

            String configuredHeader0 = context0.config.getCol(key0);
            String headerToUse0 = !isBlank(configuredHeader0) ? configuredHeader0 : field0.columnName;

            if (headerMap0.containsKey(headerToUse0))
            {
                if (isBlank(configuredHeader0))
                {
                    context0.config.setCol(key0, headerToUse0);
                }
                continue;
            }

            missingKeys0.add(key0);
            missingHeaders0.add(headerToUse0);
        }

        if (missingHeaders0.isEmpty())
        {
            System.out.println("  All background check columns already present.");
            return;
        }

        // Expand the sheet to fit all new columns before writing any header cell.
        int neededCols0 = nextCol0 + missingHeaders0.size() - 1;
        SheetsApp.expandSheetColumnsIfNeeded(spreadsheetId0, mainTabName0, neededCols0);

        // Pass 2: write each missing header and update in-memory state.
        for (int i = 0; i < missingHeaders0.size(); i++)
        {
            String header0 = missingHeaders0.get(i);
            String key0 = missingKeys0.get(i);
            int col0 = nextCol0 + i;

            SheetsApp.updateCell(spreadsheetId0, mainTabName0, headerRow0, col0, header0);
            System.out.println("  Added missing column \"" + header0 + "\" at column " + col0);

            headerMap0.put(header0, col0);
            context0.config.setCol(key0, header0);
        }

        System.out.println("  Provisioned " + missingHeaders0.size() + " missing background check column(s).");
    }

    // ============================================================
    // CRM COLUMN WRITE INDICES
    // ============================================================

    private static final int WRITE_CONTACT_LINKEDIN_URL = 0;
    private static final int WRITE_CONTACT_LINKEDIN_ABOUT = 1;
    private static final int WRITE_CONTACT_PAST_WORK_EXP = 2;
    private static final int WRITE_FUND_LINKEDIN_URL = 3;
    private static final int WRITE_FUND_LINKEDIN_ABOUT = 4;
    private static final int WRITE_CONTACT_BIO_URL = 5;
    private static final int WRITE_CONTACT_BIO_SUMMARY = 6;
    private static final int WRITE_BG_STATUS = 7;
    private static final int WRITE_BG_CONFIDENCE = 8;
    private static final int WRITE_LAST_BG_DATE = 9;
    private static final int WRITE_BG_JSON = 10;
    private static final int WRITE_FIRST_NAME = 11;
    private static final int WRITE_LAST_NAME = 12;
    private static final int WRITE_FUND_NAME = 13;
    private static final int WRITE_FUND_WEBSITE = 14;
    private static final int WRITE_CONTACT_POSTS_SUMMARY = 15;
    private static final int WRITE_CONTACT_FOLLOWER_COUNT = 16;
    private static final int WRITE_CONTACT_BIO_CAREER_SUMMARY = 17;
    private static final int WRITE_CONTACT_BIO_INSTITUTIONS = 18;
    private static final int WRITE_CONTACT_BIO_EDUCATION = 19;
    private static final int WRITE_FIELD_COUNT = 20;   // was 15

    // ============================================================
    // MAIN WORKFLOW ENTRY POINTS
    // ============================================================

    public static String runBasicBackgroundCheckWorkflow(SessionContext context0, int maxRows0) throws Exception
    {
        return checkCrmRowsNeedingBackground(context0, maxRows0);
    }

    public static String runBasicBackgroundCheckWorkflow(SessionContext context0) throws Exception
    {
        return checkCrmRowsNeedingBackground(context0, MAX_ROWS_TO_CHECK);
    }

    public static String checkCrmRowsNeedingBackground(SessionContext context0, int maxRows0) throws Exception
    {
        if (context0 == null || context0.config == null)
        {
            return "ERROR: Missing session context or config.";
        }

        String spreadsheetId0 = context0.config.spreadsheetId;
        String mainTabName0 = context0.config.mainTabName;
        int startRow0 = context0.config.mainTabDataStartRow;

        HashMap<String, Integer> headerMap0 = SheetsApp.buildHeaderMap(
            spreadsheetId0, mainTabName0, context0.config.mainTabHeaderRow, MAX_COLUMNS
        );

        int firstNameCol0 = SheetsApp.findColumnInHeaderMap(headerMap0, context0.config.getCol("mainTabContact1FirstNameCol"));
        int lastNameCol0 = SheetsApp.findColumnInHeaderMap(headerMap0, context0.config.getCol("mainTabContact1LastNameCol"));
        int emailCol0 = SheetsApp.findColumnInHeaderMap(headerMap0, context0.config.getCol("mainTabContact1EmailCol"));
        int fundNameCol0 = SheetsApp.findColumnInHeaderMap(headerMap0, context0.config.getCol("mainTabFundNameCol"));
        int fundWebsiteCol0 = SheetsApp.findColumnInHeaderMap(headerMap0, context0.config.getCol("mainTabWebsiteCol"));
        int bgStatusCol0 = SheetsApp.findColumnInHeaderMap(headerMap0, context0.config.getCol("mainTabBackgroundCheckStatusCol"));

        if (firstNameCol0 == -1 && lastNameCol0 == -1)
        {
            return "ERROR: No name columns found. Background check requires at least a first or last name column.";
        }

        int readEnd0 = startRow0 + MAX_CRM_ROWS - 1;

        String[][] firstNameData0 = firstNameCol0 != -1
            ? SheetsApp.readRangeMatrix(spreadsheetId0, mainTabName0, startRow0, firstNameCol0, readEnd0, firstNameCol0)
            : new String[0][1];

        String[][] lastNameData0 = lastNameCol0 != -1
            ? SheetsApp.readRangeMatrix(spreadsheetId0, mainTabName0, startRow0, lastNameCol0, readEnd0, lastNameCol0)
            : new String[0][1];

        String[][] emailData0 = emailCol0 != -1
            ? SheetsApp.readRangeMatrix(spreadsheetId0, mainTabName0, startRow0, emailCol0, readEnd0, emailCol0)
            : new String[0][1];

        String[][] fundNameData0 = fundNameCol0 != -1
            ? SheetsApp.readRangeMatrix(spreadsheetId0, mainTabName0, startRow0, fundNameCol0, readEnd0, fundNameCol0)
            : new String[0][1];

        String[][] fundWebsiteData0 = fundWebsiteCol0 != -1
            ? SheetsApp.readRangeMatrix(spreadsheetId0, mainTabName0, startRow0, fundWebsiteCol0, readEnd0, fundWebsiteCol0)
            : new String[0][1];

        String[][] statusData0 = bgStatusCol0 != -1
            ? SheetsApp.readRangeMatrix(spreadsheetId0, mainTabName0, startRow0, bgStatusCol0, readEnd0, bgStatusCol0)
            : new String[0][1];

        int maxDataRows0 = Math.max(firstNameData0.length, Math.max(lastNameData0.length, fundNameData0.length));
        ArrayList<BackgroundCheckInput> rowsToProcess0 = new ArrayList<>();

        for (int i = 0; i < maxDataRows0 && rowsToProcess0.size() < maxRows0; i++)
        {
            String bgStatus0 = getCell(statusData0, i);
            if (!isBlank(bgStatus0) && !bgStatus0.equalsIgnoreCase("QUEUED"))
            {
                continue;
            }

            String first0 = getCell(firstNameData0, i);
            String last0 = getCell(lastNameData0, i);
            String fund0 = getCell(fundNameData0, i);

            if (isBlank(first0) && isBlank(last0) && isBlank(fund0))
            {
                continue;
            }

            BackgroundCheckInput input0 = new BackgroundCheckInput();
            input0.firstName = first0;
            input0.lastName = last0;
            input0.cleanedEmail = getCell(emailData0, i);
            input0.fundName = fund0;
            input0.fundWebsite = getCell(fundWebsiteData0, i);
            input0.crmRowNumber = startRow0 + i;

            rowsToProcess0.add(input0);
        }

        if (rowsToProcess0.isEmpty())
        {
            return "Background check complete. No eligible rows found.";
        }

        System.out.println("Background check: processing " + rowsToProcess0.size() + " rows.");

        ensureBackgroundCheckColumns(context0, spreadsheetId0, mainTabName0,
            context0.config.mainTabHeaderRow, headerMap0);

        int[] updateCols0 = buildUpdateColumns(context0, headerMap0);

        // Process rows in parallel. check() is self-contained per row (no shared
        // mutable state), so the only cross-thread structure is the results map.
        ConcurrentHashMap<Integer, BackgroundCheckResult> rowResultsConcurrent0 =
            new ConcurrentHashMap<>();
        AtomicInteger completedCount0 = new AtomicInteger(0);
        AtomicInteger failedCount0 = new AtomicInteger(0);

        long batchStartMs0 = System.currentTimeMillis();
        // One cache for the whole batch so duplicate scrapes/crawls/LLM calls across
        // rows (e.g. several contacts at the same fund) are made only once.
        final ScrapeCache batchCache0 = new ScrapeCache();
        ExecutorService pool0 = Executors.newFixedThreadPool(ROW_THREAD_POOL_SIZE);
        ArrayList<Future<?>> futures0 = new ArrayList<>();

        for (BackgroundCheckInput input0 : rowsToProcess0)
        {
            final BackgroundCheckInput rowInput0 = input0;
            futures0.add(pool0.submit(CostMeter.wrap(() -> {
                System.out.println("\n--- Row " + rowInput0.crmRowNumber + ": "
                    + rowInput0.firstName + " " + rowInput0.lastName
                    + " @ " + rowInput0.fundName + " ---");
                try
                {
                    BackgroundCheckResult result0 = check(rowInput0, batchCache0);
                    rowResultsConcurrent0.put(rowInput0.crmRowNumber, result0);
                    completedCount0.incrementAndGet();
                }
                catch (Exception exception0)
                {
                    System.out.println("ERROR on row " + rowInput0.crmRowNumber
                        + ": " + exception0.getMessage());
                    BackgroundCheckResult failed0 = new BackgroundCheckResult();
                    failed0.input = rowInput0;
                    failed0.status = STATUS_FAILED;
                    failed0.lastCheckedAt = java.time.Instant.now().toString();
                    failed0.backgroundCheckJson = "{\"status\":\"FAILED\",\"error\":\""
                        + escapeJson(exception0.getMessage()) + "\"}";
                    rowResultsConcurrent0.put(rowInput0.crmRowNumber, failed0);
                    failedCount0.incrementAndGet();
                }
            })));
        }

        // Wait for every row task to finish before writing. Hard backstop: even
        // though check() enforces a cooperative per-row budget, bound the join at
        // ROW_BUDGET_MS + grace so a leaf that ignores interruption can never hang
        // the whole batch. On timeout, cancel(true) interrupts the row thread (and
        // its interruptible HttpClient.send()), and we record a synthetic FAILED.
        long hardCapMs0 = ROW_BUDGET_MS + ROW_HARD_GRACE_MS;
        for (int i0 = 0; i0 < futures0.size(); i0++)
        {
            Future<?> f0 = futures0.get(i0);
            try { f0.get(hardCapMs0, TimeUnit.MILLISECONDS); }
            catch (TimeoutException te0)
            {
                f0.cancel(true);
                BackgroundCheckInput timedOut0 = rowsToProcess0.get(i0);
                System.out.println("Row " + timedOut0.crmRowNumber
                    + " exceeded hard time budget; cancelled.");
                if (!rowResultsConcurrent0.containsKey(timedOut0.crmRowNumber))
                {
                    BackgroundCheckResult failed0 = new BackgroundCheckResult();
                    failed0.input = timedOut0;
                    failed0.status = STATUS_FAILED;
                    failed0.lastCheckedAt = java.time.Instant.now().toString();
                    failed0.backgroundCheckJson =
                        "{\"status\":\"FAILED\",\"error\":\"row time budget exceeded\"}";
                    rowResultsConcurrent0.put(timedOut0.crmRowNumber, failed0);
                    failedCount0.incrementAndGet();
                }
            }
            catch (Exception e0) { System.out.println("Row task error: " + e0.getMessage()); }
        }
        pool0.shutdown();

        long batchElapsedMs0 = System.currentTimeMillis() - batchStartMs0;
        System.out.println("Background check: " + rowResultsConcurrent0.size()
            + " rows processed in " + (batchElapsedMs0 / 1000.0) + "s.");

        // Copy into an ordered map (by row number) for a deterministic write.
        LinkedHashMap<Integer, BackgroundCheckResult> rowResults0 = new LinkedHashMap<>();
        ArrayList<Integer> sortedRowNums0 = new ArrayList<>(rowResultsConcurrent0.keySet());
        java.util.Collections.sort(sortedRowNums0);
        for (Integer rn0 : sortedRowNums0)
        {
            rowResults0.put(rn0, rowResultsConcurrent0.get(rn0));
        }

        // Reconcile duplicate rows (same person on multiple rows) so siblings agree.
        deduplicateResults(rowResults0);

        if (!rowResults0.isEmpty())
        {
            writeResultsToCrm(spreadsheetId0, mainTabName0, updateCols0, rowResults0);
        }

        return "Background check complete. Completed: " + completedCount0.get()
            + ", Failed: " + failedCount0.get() + ".";
    }

    // ============================================================
    // CORE CHECK METHOD
    // ============================================================

    // Backward-compatible entry point (e.g. BatchBackgroundCheckTest): creates a
    // throwaway per-call cache so single-row callers still work unchanged.
    public static BackgroundCheckResult check(BackgroundCheckInput input0)
    {
        return check(input0, new ScrapeCache());
    }

    public static BackgroundCheckResult check(BackgroundCheckInput input0, ScrapeCache cache0)
    {
        return check(input0, cache0, new Deadline(ROW_BUDGET_MS));
    }

    public static BackgroundCheckResult check(
        BackgroundCheckInput input0, ScrapeCache cache0, Deadline deadline0)
    {
        ROW_DEADLINE.set(deadline0);
        try { return checkInternal(input0, cache0, deadline0); }
        finally { ROW_DEADLINE.remove(); }
    }

    private static BackgroundCheckResult checkInternal(
        BackgroundCheckInput input0, ScrapeCache cache0, Deadline deadline0)
    {
        BackgroundCheckResult result0 = new BackgroundCheckResult();
        result0.input = input0;
        result0.evidence = new ArrayList<>();

        String firstName0 = normalizeFirstName(safe(input0.firstName).trim());
        String lastName0 = safe(input0.lastName).trim();
        // Drop a last-name token that merely repeats the first name (e.g. "Dr. Joseph Joseph").
        if (!isBlank(lastName0) && lastName0.equalsIgnoreCase(firstName0))
        {
            lastName0 = "";
        }
        String fundName0 = safe(input0.fundName).trim();
        String fundWebsite0 = safe(input0.fundWebsite).trim();
        String cleanedEmail0 = safe(input0.cleanedEmail).trim();

        System.out.println("  firstName=" + firstName0
            + " lastName=" + lastName0
            + " fundName=" + fundName0
            + " fundWebsite=" + fundWebsite0
            + " email=" + cleanedEmail0);

        String emailDomain0 = IdentityResolutionScorer.extractUsefulEmailDomain(cleanedEmail0);
        System.out.println("  emailDomain=" + (isBlank(emailDomain0) ? "(public/none)" : emailDomain0));

        // Extract last name hint from email local part (e.g. "s.oosterhof@..." → "oosterhof")
        String emailLastNameHint0 = extractLastNameHintFromEmail(cleanedEmail0, firstName0);
        if (!isBlank(emailLastNameHint0) && isBlank(lastName0))
        {
            System.out.println("  Email last name hint: \"" + emailLastNameHint0 + "\"");
        }

        // Extract partial last name candidates from condensed emails with no dot (e.g. "kchomitz@..." → ["chomitz"])
        ArrayList<String> emailPartialCandidates0 =
            extractEmailPartialCandidates(cleanedEmail0, firstName0, emailLastNameHint0);
        if (!emailPartialCandidates0.isEmpty())
        {
            System.out.println("  Email partial candidates: " + emailPartialCandidates0);
        }

        // Infer fund website from institutional email domain
        if (isBlank(fundWebsite0) && !isBlank(emailDomain0))
        {
            fundWebsite0 = emailDomain0;
            System.out.println("  Inferred fundWebsite from email domain: " + fundWebsite0);
        }

        result0.firstName = new ResolvedField(firstName0, 1.0);
        result0.lastName = new ResolvedField(lastName0, 1.0);
        result0.fundName = new ResolvedField(fundName0, 1.0);
        result0.fundWebsite = new ResolvedField(fundWebsite0, isBlank(input0.fundWebsite) ? 0.75 : 1.0);

        // INSUFFICIENT_INPUT when there is nothing to search with: either no name at
        // all, or only a first name with no institutional anchor (no last name, no
        // fund name, no fund website, and only a public/blank email). This lets a GP
        // distinguish "you gave us too little" from a genuine search miss (FAILED).
        boolean noName0 = isBlank(firstName0) && isBlank(lastName0);
        boolean firstNameOnlyNoAnchor0 = !isBlank(firstName0) && isBlank(lastName0)
            && isBlank(fundName0) && isBlank(fundWebsite0) && isBlank(emailDomain0);
        if (noName0 || firstNameOnlyNoAnchor0)
        {
            result0.status = STATUS_INSUFFICIENT_INPUT;
            result0.lastCheckedAt = java.time.Instant.now().toString();
            result0.backgroundCheckJson = buildBackgroundCheckJson(result0);
            System.out.println("  Skipping: insufficient input ("
                + (noName0 ? "no name" : "first name only, no fund/website/email anchor") + ").");
            return result0;
        }

        // Resolve fund website via SERP if still missing
        if (isBlank(fundWebsite0) && !isBlank(fundName0))
        {
            String resolved0 = resolveFundWebsiteViaSERP(fundName0, emailDomain0, result0.evidence);
            if (!isBlank(resolved0))
            {
                fundWebsite0 = resolved0;
                result0.fundWebsite = new ResolvedField(fundWebsite0, 0.70, fundWebsite0, "resolved via SERP");
                System.out.println("  Resolved fundWebsite via SERP: " + fundWebsite0);
            }
        }

        // Detect fund website redirects (e.g., openphilanthropy.org → coefficientgiving.org).
        // If emailDomain matches the original fund domain, update it too so Phase 1 searches the right company.
        if (!isBlank(fundWebsite0))
        {
            String origFundDomain0 = WebsiteCrawlerService.getDomain(fundWebsite0);
            String effectiveDomain0 = WebsiteCrawlerService.resolveEffectiveDomain(fundWebsite0);
            if (!isBlank(effectiveDomain0) && !effectiveDomain0.equals(origFundDomain0))
            {
                System.out.println("  Fund website redirects to: " + effectiveDomain0);
                if (emailDomain0.equals(origFundDomain0))
                {
                    emailDomain0 = effectiveDomain0;
                }
                fundWebsite0 = effectiveDomain0;
                result0.fundWebsite = new ResolvedField(fundWebsite0, 0.80, fundWebsite0, "resolved via redirect");
            }
        }

        // Best domain to use: institutional email domain takes priority over fund website domain
        String domain0 = !isBlank(emailDomain0) ? emailDomain0 : WebsiteCrawlerService.getDomain(fundWebsite0);

        // ---- OPT-7: start the bio-discovery track (Phase 4) in parallel ----
        // The bio track is side-effect-free (returns a BioResult); the LinkedIn track
        // (Phases 1-3 below) runs on THIS thread and writes result0. They share no
        // mutable state, so they run concurrently; we join before Phase 5.
        final String firstNameForBio0 = firstName0;
        final String fundWebsiteForBio0 = fundWebsite0;
        final String fundNameForBio0 = fundName0;
        final ScrapeCache cacheForBio0 = cache0;
        // Bio track uses the pre-Phase-2 last name (input or email hint). If Phase 2
        // later resolves a better last name, Phase 5 handles the retry.
        final String bioTrackLastName0 = !isBlank(lastName0) ? lastName0
            : (!isBlank(emailLastNameHint0) ? emailLastNameHint0 : "");

        final Deadline deadlineForBio0 = deadline0;
        ExecutorService bioPool0 = Executors.newSingleThreadExecutor();
        Future<BioResult> bioFuture0 = bioPool0.submit(() -> {
            if (isBlank(fundWebsiteForBio0) || deadlineForBio0.expired()) { return new BioResult(); }
            System.out.println("  ---- PHASE 4 (parallel): Contact website bio ----");
            return resolveContactWebsiteBioTrack(
                firstNameForBio0, bioTrackLastName0, fundWebsiteForBio0, fundNameForBio0, cacheForBio0);
        });
        bioPool0.shutdown();

        // ---- PHASE 1: Company LinkedIn pre-resolution via domain (up to 3 candidates) ----
        // Companies often have multiple LinkedIn entities (e.g., different divisions).
        // Collect up to MAX_COMPANY_CANDIDATES validated company pages so Phase 2 can try
        // each one — avoiding the case where the wrong division name yields zero person results.
        ArrayList<ScoredCompanyCandidate> companyCandidates0 = new ArrayList<>();

        if (!isBlank(domain0))
        {
            System.out.println();
            System.out.println("  ---- PHASE 1: Company LinkedIn pre-resolution via domain ----");
            companyCandidates0 = preResolveCompanyLinkedIn(
                result0.evidence, domain0, fundName0, fundWebsite0, emailDomain0, cache0
            );
            System.out.println("  Phase 1: " + companyCandidates0.size() + " company candidate(s) found.");
        }

        // Phase 1 redirect fallback: if Phase 1 found nothing, check whether the domain
        // itself HTTP-redirects to a new domain (e.g., a.com → b.com after a rebrand)
        // and retry the company LinkedIn search with the forwarded domain.
        if (companyCandidates0.isEmpty() && !isBlank(domain0))
        {
            String redirectedDomain0 = WebsiteCrawlerService.resolveEffectiveDomain(domain0);
            if (!isBlank(redirectedDomain0) && !redirectedDomain0.equalsIgnoreCase(domain0))
            {
                System.out.println("  Phase 1: domain \"" + domain0 + "\" redirects to \""
                    + redirectedDomain0 + "\" — retrying company LinkedIn search.");
                companyCandidates0 = preResolveCompanyLinkedIn(
                    result0.evidence, redirectedDomain0, fundName0, fundWebsite0, emailDomain0, cache0
                );
                if (!companyCandidates0.isEmpty())
                {
                    emailDomain0 = redirectedDomain0;
                    fundWebsite0 = redirectedDomain0;
                }
                System.out.println("  Phase 1 redirect retry: " + companyCandidates0.size()
                    + " candidate(s) found.");
            }
        }

        // ---- PHASE 2: Contact LinkedIn — try each company candidate in score order ----
        // For each company, run the person search using that company's real LinkedIn name.
        // Keep the person result with the highest confidence across all attempts.
        PersonResolutionAttempt bestPersonAttempt0 = null;
        int bestCompanyCandidateIdx0 = -1;

        System.out.println();
        System.out.println("  ---- PHASE 2: Contact LinkedIn (multi-company) ----");

        // Pooled person resolution: one deduped candidate pool across ALL company
        // candidates, each unique profile scraped at most once. When at least one
        // company was pre-resolved (score >= REVIEW), use the lower threshold since
        // the domain→company link already corroborates identity.
        {
            double personThreshold0 = IdentityResolutionScorer.THRESHOLD_SAFE;
            for (ScoredCompanyCandidate c0 : companyCandidates0)
            {
                if (c0.score >= IdentityResolutionScorer.THRESHOLD_REVIEW)
                {
                    personThreshold0 = IdentityResolutionScorer.THRESHOLD_REVIEW;
                    break;
                }
            }

            if (companyCandidates0.isEmpty())
            {
                System.out.println("  No company candidates — searching with fund name: \""
                    + fundName0 + "\"");
            }

            bestPersonAttempt0 = resolveContactLinkedInPooled(
                result0.evidence, firstName0, lastName0, emailLastNameHint0,
                companyCandidates0, fundName0, fundWebsite0, emailDomain0,
                personThreshold0, emailPartialCandidates0, cache0
            );

            // Identify which Phase 1 company the matched person belongs to so the
            // "Apply" block attaches the right fund LinkedIn. Match the profile's
            // company name against the candidates; leave -1 (top candidate) if none.
            if (bestPersonAttempt0 != null && !isBlank(bestPersonAttempt0.resolvedCompanyName))
            {
                String normPerson0 = IdentityResolutionScorer.normalizeCompanyName(
                    bestPersonAttempt0.resolvedCompanyName);
                for (int ci0 = 0; ci0 < companyCandidates0.size(); ci0++)
                {
                    if (IdentityResolutionScorer.normalizeCompanyName(
                            companyCandidates0.get(ci0).resolvedName).equals(normPerson0))
                    {
                        bestCompanyCandidateIdx0 = ci0;
                        break;
                    }
                }
            }
        }

        // ---- Apply Phase 1 + Phase 2 results to result0 ----

        // Fund LinkedIn: prefer the company candidate that produced the best person match;
        // fall back to the highest-scoring candidate if no person was found.
        ScoredCompanyCandidate bestCompany0 = null;
        if (bestCompanyCandidateIdx0 >= 0)
        {
            bestCompany0 = companyCandidates0.get(bestCompanyCandidateIdx0);
        }
        else if (!companyCandidates0.isEmpty())
        {
            bestCompany0 = companyCandidates0.get(0); // sorted desc — highest score is first
        }

        if (bestCompany0 != null)
        {
            result0.fundLinkedInUrl = new ResolvedField(
                bestCompany0.url, bestCompany0.score, bestCompany0.url, "domain-based pre-resolution"
            );
            if (!isBlank(bestCompany0.about))
            {
                result0.fundLinkedInAbout = new ResolvedField(
                    bestCompany0.about, bestCompany0.score, bestCompany0.url, ""
                );
                System.out.println("  Fund LinkedIn about: " + bestCompany0.about.length() + " chars");
            }
            if (!isBlank(bestCompany0.resolvedName))
            {
                result0.fundName = new ResolvedField(
                    bestCompany0.resolvedName, bestCompany0.score, bestCompany0.url,
                    "updated from LinkedIn company page"
                );
                System.out.println("  Fund LinkedIn: " + bestCompany0.url
                    + " (\"" + bestCompany0.resolvedName + "\")");
            }
            // Fix 2: propagate company website URL found from LinkedIn scrape
            if (!isBlank(bestCompany0.scrapedWebsite) && isBlank(result0.fundWebsite.value))
            {
                result0.fundWebsite = new ResolvedField(
                    bestCompany0.scrapedWebsite, bestCompany0.score,
                    bestCompany0.url, "company website from LinkedIn scrape"
                );
                System.out.println("  Fund website from LinkedIn: " + bestCompany0.scrapedWebsite);
            }
        }

        // Apply person LinkedIn result
        if (bestPersonAttempt0 != null)
        {
            result0.contactLinkedInUrl = new ResolvedField(
                bestPersonAttempt0.contactLinkedInUrl, bestPersonAttempt0.confidence,
                bestPersonAttempt0.contactLinkedInUrl, "verified via profile scrape"
            );
            result0.contactLinkedInAbout = new ResolvedField(
                safe(bestPersonAttempt0.contactLinkedInAbout), bestPersonAttempt0.confidence,
                bestPersonAttempt0.contactLinkedInUrl, ""
            );
            result0.contactPastWorkExperience = new ResolvedField(
                truncate(safe(bestPersonAttempt0.contactPastWorkExperienceJson), 49000),
                bestPersonAttempt0.confidence, bestPersonAttempt0.contactLinkedInUrl, ""
            );
            String postsSummary0 = summarizePostsViaLLM(
                bestPersonAttempt0.recentPostsJson, firstName0, lastName0, cache0);
            if (!isBlank(postsSummary0)) {
                result0.contactLinkedInPostsSummary = new ResolvedField(
                    truncate(postsSummary0, 49000), bestPersonAttempt0.confidence,
                    bestPersonAttempt0.contactLinkedInUrl, "summarized from recent LinkedIn posts");
            }
            if (!isBlank(bestPersonAttempt0.followerCount)) {
                result0.contactFollowerCount = new ResolvedField(
                    bestPersonAttempt0.followerCount, bestPersonAttempt0.confidence,
                    bestPersonAttempt0.contactLinkedInUrl, "from LinkedIn profile");
            }
            if (!isBlank(bestPersonAttempt0.resolvedLastName))
            {
                result0.lastName = new ResolvedField(
                    bestPersonAttempt0.resolvedLastName, bestPersonAttempt0.confidence,
                    bestPersonAttempt0.contactLinkedInUrl, "from LinkedIn profile"
                );
                System.out.println("  Saved last name from LinkedIn: \""
                    + bestPersonAttempt0.resolvedLastName + "\"");
            }
            // Propagate fund LinkedIn URL from person profile only if not already resolved
            if (!isBlank(bestPersonAttempt0.fundLinkedInUrlFromProfile)
                && isBlank(result0.fundLinkedInUrl.value))
            {
                result0.fundLinkedInUrl = new ResolvedField(
                    bestPersonAttempt0.fundLinkedInUrlFromProfile,
                    bestPersonAttempt0.confidence * 0.95,
                    bestPersonAttempt0.contactLinkedInUrl,
                    "extracted from verified person profile"
                );
                System.out.println("  Fund LinkedIn from profile: "
                    + bestPersonAttempt0.fundLinkedInUrlFromProfile);
            }
        }

        // Fix 1B: if the contact's LinkedIn profile lists a different company division than Phase 1
        // resolved, override fundName and try to find a matching Phase 1 candidate for LinkedIn.
        if (bestPersonAttempt0 != null
            && !isBlank(bestPersonAttempt0.resolvedCompanyName)
            && !IdentityResolutionScorer.normalizeCompanyName(bestPersonAttempt0.resolvedCompanyName)
                .equals(IdentityResolutionScorer.normalizeCompanyName(result0.fundName.value)))
        {
            System.out.println("  Fund company mismatch: profile says \""
                + bestPersonAttempt0.resolvedCompanyName + "\" vs resolved \""
                + result0.fundName.value + "\"");
            // Try to find a Phase 1 candidate whose name matches the corrected company
            ScoredCompanyCandidate correctedCompany0 = null;
            for (ScoredCompanyCandidate sc0 : companyCandidates0)
            {
                if (IdentityResolutionScorer.normalizeCompanyName(sc0.resolvedName)
                    .equals(IdentityResolutionScorer.normalizeCompanyName(
                        bestPersonAttempt0.resolvedCompanyName)))
                {
                    if (correctedCompany0 == null || sc0.score > correctedCompany0.score)
                    {
                        correctedCompany0 = sc0;
                    }
                }
            }
            if (correctedCompany0 != null && !isBlank(correctedCompany0.url))
            {
                // Phase 1 confirms the corrected company — update fund LinkedIn and name.
                System.out.println("  Fix 1B: confirmed Phase 1 match for \"" + bestPersonAttempt0.resolvedCompanyName
                    + "\" — updating to " + correctedCompany0.url);
                result0.fundName = new ResolvedField(
                    bestPersonAttempt0.resolvedCompanyName, bestPersonAttempt0.confidence,
                    bestPersonAttempt0.contactLinkedInUrl, "updated from contact LinkedIn profile");
                result0.fundLinkedInUrl = new ResolvedField(
                    correctedCompany0.url, correctedCompany0.score,
                    correctedCompany0.url, "Phase 1 candidate matching corrected company");
                result0.fundLinkedInAbout = new ResolvedField(
                    correctedCompany0.about, correctedCompany0.score,
                    correctedCompany0.url, "");
            }
            else
            {
                // No Phase 1 candidate found for the corrected company name (person may be an
                // ex-employee whose LinkedIn shows a different current employer). Keep the
                // original Phase 1 fund LinkedIn — it was resolved via domain evidence and is
                // still the best known fund identifier.
                System.out.println("  Fix 1B: no Phase 1 match for \""
                    + bestPersonAttempt0.resolvedCompanyName
                    + "\" — keeping original fund LinkedIn.");
            }
        }

        // Derive working fund name for subsequent phases (post Phase 1+2 / Fix 1B).
        String workingFundName0 = !isBlank(result0.fundName.value) ? result0.fundName.value : fundName0;

        // ---- PHASE 3: Fund LinkedIn fallback ----
        // Runs only when fund LinkedIn is still blank. Note: when Phase 1 found any
        // company candidate, the "Apply Phase 1+2" block above already attached the
        // top one, so this is normally skipped without extra cost. It DOES run (with
        // candidates present) when Fix 1B cleared the wrong company — in that case a
        // fresh name-based search on the corrected fund name is the intended recovery.
        if (isBlank(result0.fundLinkedInUrl.value))
        {
            System.out.println();
            System.out.println("  ---- PHASE 3: Fund LinkedIn fallback ----");
            resolveFundLinkedIn(result0, workingFundName0, fundWebsite0, emailDomain0, cache0);
        }

        // ---- Join the parallel bio-discovery track (OPT-7) and apply its result ----
        // Bounded by the remaining row budget: if the bio crawl can't finish in
        // time, cancel(true) interrupts the (interruptible) HttpClient.send() so
        // the bio thread is freed, and we proceed with an empty bio result.
        BioResult bioResult0;
        try
        {
            bioResult0 = bioFuture0.get(Math.max(0, deadline0.remainingMs()), TimeUnit.MILLISECONDS);
        }
        catch (TimeoutException te0)
        {
            System.out.println("  Bio track exceeded row budget; cancelling.");
            bioFuture0.cancel(true);
            bioResult0 = new BioResult();
        }
        catch (Exception ex0)
        {
            System.out.println("  Bio track error: " + ex0.getMessage());
            bioResult0 = new BioResult();
        }

        // Merge bio-track evidence into result0 (single-threaded, post-join).
        if (bioResult0.evidence != null && !bioResult0.evidence.isEmpty())
        {
            result0.evidence.addAll(bioResult0.evidence);
        }

        // Fix 5: validate bio person against known last name signals to reject wrong-person pages.
        if (bioResult0.bioFound && !bioResult0.multiPerson)
        {
            ArrayList<String> lnSignals0 = new ArrayList<>();
            if (!isBlank(lastName0)) lnSignals0.add(lastName0.toLowerCase());
            if (!isBlank(emailLastNameHint0)) lnSignals0.add(emailLastNameHint0.toLowerCase());
            for (String p0 : emailPartialCandidates0)
            {
                if (!isBlank(p0)) lnSignals0.add(p0.toLowerCase());
            }
            if (!lnSignals0.isEmpty())
            {
                String bioLower0 = bioResult0.bioSummary.toLowerCase();
                boolean anyMatch0 = false;
                for (String sig0 : lnSignals0)
                {
                    if (bioLower0.contains(sig0)) { anyMatch0 = true; break; }
                }
                if (!anyMatch0)
                {
                    System.out.println("  Bio REJECTED: last name signals " + lnSignals0
                        + " absent from bio summary — likely wrong person.");
                    bioResult0.bioFound = false;
                    bioResult0.bioUrl = "";
                    bioResult0.bioSummary = "";
                    bioResult0.bioWorkHistoryJson = "[]";
                    bioResult0.candidates.clear();
                }
            }
        }

        ArrayList<ContactBioCandidate> bioCandidates0 = bioResult0.candidates != null
            ? bioResult0.candidates : new ArrayList<>();

        // Single-person bio: apply to result0. Bio work history fills in ONLY when the
        // LinkedIn track produced none (LinkedIn experience wins).
        if (!bioResult0.multiPerson && bioResult0.bioFound)
        {
            result0.contactWebsiteBioUrl = new ResolvedField(
                bioResult0.bioUrl, 0.88, bioResult0.bioUrl, "");
            result0.contactWebsiteBioSummary = new ResolvedField(
                truncate(bioResult0.bioSummary, 49000), 0.88, bioResult0.bioUrl, "");
            if (!isBlank(bioResult0.bioCareerSummary)) {
                result0.contactBioCareerSummary = new ResolvedField(
                    truncate(bioResult0.bioCareerSummary, 49000), 0.88, bioResult0.bioUrl, "");
            }
            if (!isBlank(bioResult0.bioInstitutionsJson) && !bioResult0.bioInstitutionsJson.equals("[]")) {
                result0.contactBioInstitutions = new ResolvedField(
                    truncate(bioResult0.bioInstitutionsJson, 49000), 0.88, bioResult0.bioUrl, "");
            }
            if (!isBlank(bioResult0.bioEducationJson) && !bioResult0.bioEducationJson.equals("[]")) {
                result0.contactBioEducation = new ResolvedField(
                    truncate(bioResult0.bioEducationJson, 49000), 0.88, bioResult0.bioUrl, "");
            }
            if ((isBlank(result0.contactPastWorkExperience.value)
                    || result0.contactPastWorkExperience.value.equals("[]"))
                && !isBlank(bioResult0.bioWorkHistoryJson)
                && !bioResult0.bioWorkHistoryJson.equals("[]"))
            {
                result0.contactPastWorkExperience = new ResolvedField(
                    truncate(bioResult0.bioWorkHistoryJson, 49000), 0.85,
                    bioResult0.bioUrl, "extracted from bio page(s)");
            }
        }

        // ---- PHASE 5: LinkedIn retry from bio-derived last name ----
        // When Phase 4 detected multiple distinct people (different last names in their bio URLs),
        // run per-candidate LinkedIn resolution and store additional contacts.
        // For a single person, run the existing retry using the extracted last name.
        // Fix (false AMBIGUOUS on large institutions): when the input already names
        // this person — via a provided last name or an email last-name hint — and that
        // name uniquely matches one bio candidate, collapse to that single person
        // instead of branching the row as AMBIGUOUS.
        String disambHint0 = !isBlank(safe(result0.input != null ? result0.input.lastName : ""))
            ? safe(result0.input.lastName).trim()
            : emailLastNameHint0;
        if (bioCandidates0.size() > 1 && !isBlank(disambHint0))
        {
            ArrayList<ContactBioCandidate> matched0 = new ArrayList<>();
            for (ContactBioCandidate c0 : bioCandidates0)
            {
                if (!isBlank(c0.extractedLastName)
                    && c0.extractedLastName.trim().equalsIgnoreCase(disambHint0))
                {
                    matched0.add(c0);
                }
            }
            if (matched0.size() == 1)
            {
                bioCandidates0 = matched0;
                System.out.println("  Disambiguated multi-candidate bio to input-named person: \""
                    + disambHint0 + "\"");
            }
        }

        boolean multiCandidate0 = bioCandidates0.size() > 1
            && countDistinctLastNames(bioCandidates0) > 1;

        if (deadline0.expired())
        {
            // Row budget exhausted: skip the Phase 5 LinkedIn-retry scrapes and
            // finalize with whatever resolved so far (downgraded to PARTIAL below).
            System.out.println("  Skipping PHASE 5: row time budget exhausted.");
        }
        else if (multiCandidate0)
        {
            System.out.println();
            System.out.println("  ---- PHASE 5: Multi-candidate bio resolution ("
                + bioCandidates0.size() + " candidates) ----");

            ArrayList<BackgroundCheckResult> candidateResults0 = new ArrayList<>();

            for (ContactBioCandidate cand0 : bioCandidates0)
            {
                String candLastName0 = cand0.extractedLastName;
                System.out.println("  Candidate: firstName=\"" + firstName0
                    + "\" lastName=\"" + candLastName0 + "\" bioUrl=\"" + cand0.bioUrl + "\"");

                BackgroundCheckResult candResult0 = new BackgroundCheckResult();
                candResult0.input = result0.input;
                candResult0.firstName = result0.firstName;
                candResult0.lastName = isBlank(candLastName0)
                    ? new ResolvedField()
                    : new ResolvedField(candLastName0, 0.70, cand0.bioUrl, "extracted from bio URL");
                candResult0.fundName = result0.fundName;
                candResult0.fundWebsite = result0.fundWebsite;
                candResult0.fundLinkedInUrl = result0.fundLinkedInUrl;
                candResult0.fundLinkedInAbout = result0.fundLinkedInAbout;
                candResult0.contactWebsiteBioUrl = new ResolvedField(cand0.bioUrl, 0.88, cand0.bioUrl, "");
                candResult0.contactWebsiteBioSummary = new ResolvedField(
                    truncate(cand0.bioSummary, 49000), 0.88, cand0.bioUrl, "");
                candResult0.evidence = new ArrayList<>(result0.evidence);

                if (!isBlank(candLastName0))
                {
                    PersonResolutionAttempt candAttempt0 = null;

                    for (ScoredCompanyCandidate company0 : companyCandidates0)
                    {
                        PersonResolutionAttempt attempt0 = resolveContactLinkedIn(
                            candResult0.evidence, firstName0, candLastName0, candLastName0,
                            company0.resolvedName, fundWebsite0, emailDomain0,
                            IdentityResolutionScorer.THRESHOLD_REVIEW, new ArrayList<>(), cache0
                        );
                        if (attempt0 != null
                            && (candAttempt0 == null || attempt0.confidence > candAttempt0.confidence))
                        {
                            candAttempt0 = attempt0;
                        }
                        if (candAttempt0 != null
                            && candAttempt0.confidence >= IdentityResolutionScorer.THRESHOLD_SAFE)
                        {
                            break;
                        }
                    }

                    if (candAttempt0 == null)
                    {
                        candAttempt0 = resolveContactLinkedIn(
                            candResult0.evidence, firstName0, candLastName0, candLastName0,
                            workingFundName0, fundWebsite0, emailDomain0,
                            IdentityResolutionScorer.THRESHOLD_REVIEW, new ArrayList<>(), cache0
                        );
                    }

                    if (candAttempt0 != null)
                    {
                        candResult0.contactLinkedInUrl = new ResolvedField(
                            candAttempt0.contactLinkedInUrl, candAttempt0.confidence,
                            candAttempt0.contactLinkedInUrl, "verified via bio-derived name");
                        candResult0.contactLinkedInAbout = new ResolvedField(
                            safe(candAttempt0.contactLinkedInAbout), candAttempt0.confidence,
                            candAttempt0.contactLinkedInUrl, "");
                        candResult0.contactPastWorkExperience = new ResolvedField(
                            truncate(safe(candAttempt0.contactPastWorkExperienceJson), 49000),
                            candAttempt0.confidence, candAttempt0.contactLinkedInUrl, "");
                        if (!isBlank(candAttempt0.resolvedLastName))
                        {
                            candResult0.lastName = new ResolvedField(
                                candAttempt0.resolvedLastName, candAttempt0.confidence,
                                candAttempt0.contactLinkedInUrl, "from LinkedIn via bio name");
                        }
                        System.out.println("  Phase 5 LinkedIn (candidate \"" + candLastName0
                            + "\"): " + candAttempt0.contactLinkedInUrl);
                    }
                }

                candResult0.overallConfidence = computeOverallConfidence(candResult0);
                candResult0.status = STATUS_AMBIGUOUS;
                candResult0.lastCheckedAt = java.time.Instant.now().toString();
                candResult0.backgroundCheckJson = buildBackgroundCheckJson(candResult0);
                candidateResults0.add(candResult0);
            }

            if (!candidateResults0.isEmpty())
            {
                // Prefer candidates with LinkedIn URL; then sort by confidence descending
                candidateResults0.sort((a0, b0) -> {
                    boolean aHasLinkedIn0 = !isBlank(a0.contactLinkedInUrl.value);
                    boolean bHasLinkedIn0 = !isBlank(b0.contactLinkedInUrl.value);
                    if (aHasLinkedIn0 != bHasLinkedIn0) return aHasLinkedIn0 ? -1 : 1;
                    return Double.compare(b0.overallConfidence, a0.overallConfidence);
                });

                BackgroundCheckResult primary0 = candidateResults0.get(0);
                result0.lastName = primary0.lastName;
                result0.contactLinkedInUrl = primary0.contactLinkedInUrl;
                result0.contactLinkedInAbout = primary0.contactLinkedInAbout;
                result0.contactPastWorkExperience = primary0.contactPastWorkExperience;
                result0.contactWebsiteBioUrl = primary0.contactWebsiteBioUrl;
                result0.contactWebsiteBioSummary = primary0.contactWebsiteBioSummary;

                if (candidateResults0.size() > 1)
                {
                    result0.additionalContactCandidates = new ArrayList<>(
                        candidateResults0.subList(1, candidateResults0.size()));
                }
            }
        }
        else
        {
            // Single person: use extracted last name from bio candidate (or summary fallback)
            String bioLastName0 = "";
            if (!bioCandidates0.isEmpty() && !isBlank(bioCandidates0.get(0).extractedLastName))
            {
                bioLastName0 = bioCandidates0.get(0).extractedLastName;
            }
            else if (!isBlank(result0.contactWebsiteBioSummary.value) && !isBlank(firstName0))
            {
                bioLastName0 = extractLastNameFromBioSummary(
                    result0.contactWebsiteBioSummary.value, firstName0);
            }

            if (!isBlank(bioLastName0) && isBlank(result0.contactLinkedInUrl.value))
            {
                System.out.println();
                System.out.println("  ---- PHASE 5: LinkedIn retry from bio (lastName=\""
                    + bioLastName0 + "\") ----");

                PersonResolutionAttempt bioAttempt0 = null;

                for (ScoredCompanyCandidate company0 : companyCandidates0)
                {
                    PersonResolutionAttempt attempt0 = resolveContactLinkedIn(
                        result0.evidence, firstName0, bioLastName0, bioLastName0,
                        company0.resolvedName, fundWebsite0, emailDomain0,
                        IdentityResolutionScorer.THRESHOLD_REVIEW, new ArrayList<>(), cache0
                    );
                    if (attempt0 != null
                        && (bioAttempt0 == null || attempt0.confidence > bioAttempt0.confidence))
                    {
                        bioAttempt0 = attempt0;
                    }
                    if (bioAttempt0 != null
                        && bioAttempt0.confidence >= IdentityResolutionScorer.THRESHOLD_SAFE)
                    {
                        break;
                    }
                }

                if (bioAttempt0 == null)
                {
                    bioAttempt0 = resolveContactLinkedIn(
                        result0.evidence, firstName0, bioLastName0, bioLastName0,
                        workingFundName0, fundWebsite0, emailDomain0,
                        IdentityResolutionScorer.THRESHOLD_REVIEW, new ArrayList<>(), cache0
                    );
                }

                if (bioAttempt0 != null)
                {
                    result0.contactLinkedInUrl = new ResolvedField(
                        bioAttempt0.contactLinkedInUrl, bioAttempt0.confidence,
                        bioAttempt0.contactLinkedInUrl, "verified via bio-derived name"
                    );
                    result0.contactLinkedInAbout = new ResolvedField(
                        safe(bioAttempt0.contactLinkedInAbout), bioAttempt0.confidence,
                        bioAttempt0.contactLinkedInUrl, ""
                    );
                    result0.contactPastWorkExperience = new ResolvedField(
                        truncate(safe(bioAttempt0.contactPastWorkExperienceJson), 49000),
                        bioAttempt0.confidence, bioAttempt0.contactLinkedInUrl, ""
                    );
                    if (isBlank(result0.contactLinkedInPostsSummary.value))
                    {
                        String bioPostsSummary0 = summarizePostsViaLLM(
                            bioAttempt0.recentPostsJson, firstName0, bioLastName0, cache0);
                        if (!isBlank(bioPostsSummary0)) {
                            result0.contactLinkedInPostsSummary = new ResolvedField(
                                truncate(bioPostsSummary0, 49000), bioAttempt0.confidence,
                                bioAttempt0.contactLinkedInUrl, "summarized from recent LinkedIn posts");
                        }
                    }
                    if (isBlank(result0.contactFollowerCount.value)
                        && !isBlank(bioAttempt0.followerCount)) {
                        result0.contactFollowerCount = new ResolvedField(
                            bioAttempt0.followerCount, bioAttempt0.confidence,
                            bioAttempt0.contactLinkedInUrl, "from LinkedIn profile");
                    }
                    if (!isBlank(bioAttempt0.resolvedLastName) && isBlank(result0.lastName.value))
                    {
                        result0.lastName = new ResolvedField(
                            bioAttempt0.resolvedLastName, bioAttempt0.confidence,
                            bioAttempt0.contactLinkedInUrl, "from LinkedIn via bio name"
                        );
                    }
                    if (!isBlank(bioAttempt0.fundLinkedInUrlFromProfile)
                        && isBlank(result0.fundLinkedInUrl.value))
                    {
                        result0.fundLinkedInUrl = new ResolvedField(
                            bioAttempt0.fundLinkedInUrlFromProfile,
                            bioAttempt0.confidence * 0.95,
                            bioAttempt0.contactLinkedInUrl,
                            "extracted from bio-retry profile"
                        );
                    }
                    System.out.println("  Phase 5 LinkedIn: " + bioAttempt0.contactLinkedInUrl);
                }
                else if (isBlank(result0.lastName.value))
                {
                    // Fix 2A: save bio-derived last name even when LinkedIn search fails
                    String bioSourceUrl0 = !bioCandidates0.isEmpty()
                        ? bioCandidates0.get(0).bioUrl
                        : result0.contactWebsiteBioUrl.value;
                    result0.lastName = new ResolvedField(
                        bioLastName0, 0.70, bioSourceUrl0, "extracted from bio, no LinkedIn found");
                    System.out.println("  Phase 5 fallback: saved lastName=\"" + bioLastName0
                        + "\" from bio (no LinkedIn found).");
                }
            }
        }

        result0.overallConfidence = computeOverallConfidence(result0);
        result0.status = computeStatus(result0);
        if (result0.additionalContactCandidates != null && !result0.additionalContactCandidates.isEmpty())
        {
            result0.status = STATUS_AMBIGUOUS;
        }
        // If the row's wall-clock budget was exhausted, we cut work short, so a
        // result is at best PARTIAL (never claim COMPLETED on a truncated row).
        // Record the truncation as evidence so it surfaces in the JSON.
        if (deadline0.expired())
        {
            if (STATUS_COMPLETED.equals(result0.status)) { result0.status = STATUS_PARTIAL; }
            result0.evidence.add(new EvidenceItem(
                "time_budget", "", "", 0.0,
                "row truncated: per-row wall-clock budget exhausted before all phases completed"));
        }
        result0.lastCheckedAt = java.time.Instant.now().toString();
        result0.backgroundCheckJson = buildBackgroundCheckJson(result0);

        printTerminalSummary(result0);
        return result0;
    }

    // ============================================================
    // COMPANY LINKEDIN PRE-RESOLUTION (DOMAIN-BASED, MULTI-CANDIDATE)
    // ============================================================

    /**
     * Phase 1: Searches for company LinkedIn pages by institutional email domain.
     * Collects up to MAX_COMPANY_CANDIDATES validated candidates sorted by score descending.
     * Returns an empty list if no candidates pass THRESHOLD_REVIEW.
     *
     * Using the domain (not the fund name) finds the real LinkedIn company name even when the
     * input fund name differs from what LinkedIn shows (e.g., input "North Harbor Advisors" →
     * LinkedIn "Northharbor"). Collecting multiple candidates handles organizations with several
     * LinkedIn entities (e.g., different divisions under the same domain).
     */
    private static ArrayList<ScoredCompanyCandidate> preResolveCompanyLinkedIn(
        ArrayList<EvidenceItem> evidence0,
        String domain0, String fundName0, String fundWebsite0, String emailDomain0,
        ScrapeCache cache0)
    {
        ArrayList<ScoredCompanyCandidate> candidates0 = new ArrayList<>();

        SearchTermGenerator stg0 = new SearchTermGenerator();
        BrightDataSerpClient serp0 = new BrightDataSerpClient();

        ArrayList<String> queries0 = stg0.generateCompanyLinkedInQueriesByDomain(domain0);
        System.out.println("  Domain-based company queries: " + queries0.size());

        // Stage 1: DISCOVERY — run SERP queries and collect candidate company URLs that
        // pass THRESHOLD_REVIEW (no scraping yet), keyed by URL with their best score.
        LinkedHashMap<String, Double> urlScore0 = new LinkedHashMap<>();
        for (String query0 : queries0)
        {
            System.out.println("  SERP company (domain): " + query0);
            try
            {
                ArrayList<SerpResult> results0 = serp0.search(query0, MAX_SERP_RESULTS_PER_QUERY);

                for (SerpResult sr0 : results0)
                {
                    if (sr0 == null)
                    {
                        continue;
                    }
                    String type0 = LinkedInUrlExtractor.classify(sr0.url);
                    if (!DiscoveredLinkedInTarget.TYPE_COMPANY.equals(type0))
                    {
                        continue;
                    }

                    String normalized0 = LinkedInUrlExtractor.normalizeLinkedInUrl(sr0.url);
                    if (isBlank(normalized0) || urlScore0.containsKey(normalized0))
                    {
                        continue;
                    }

                    double domainScore0 = IdentityResolutionScorer.scoreCompanySerpResultByDomain(sr0, domain0);
                    double nameScore0 = IdentityResolutionScorer.scoreCompanySerpResult(
                        sr0, fundName0, fundWebsite0, emailDomain0
                    );
                    double bestScore0 = Math.max(domainScore0, nameScore0);

                    evidence0.add(new EvidenceItem(
                        "serp_company_domain", query0, normalized0, bestScore0,
                        "domainScore=" + String.format("%.2f", domainScore0)
                        + " nameScore=" + String.format("%.2f", nameScore0)
                        + " | " + safe(sr0.title)
                    ));
                    System.out.println("    Company: " + normalized0
                        + " | domainScore=" + String.format("%.2f", domainScore0)
                        + " nameScore=" + String.format("%.2f", nameScore0)
                        + " best=" + String.format("%.2f", bestScore0));

                    if (bestScore0 < IdentityResolutionScorer.THRESHOLD_REVIEW)
                    {
                        continue;
                    }
                    urlScore0.put(normalized0, bestScore0);
                }
            }
            catch (Exception ex0)
            {
                System.out.println("  SERP error (domain): " + ex0.getMessage());
            }
        }

        if (urlScore0.isEmpty())
        {
            System.out.println("  Phase 1: no company candidates resolved from domain queries.");
            return candidates0;
        }

        // Stage 2: order by score desc and scrape the top few in PARALLEL. Scrape one
        // beyond the cap so a validation reject doesn't starve the candidate list.
        ArrayList<String> orderedUrls0 = new ArrayList<>(urlScore0.keySet());
        orderedUrls0.sort((a0, b0) -> Double.compare(urlScore0.get(b0), urlScore0.get(a0)));
        int scrapeLimit0 = Math.min(orderedUrls0.size(), MAX_COMPANY_CANDIDATES + 1);
        ArrayList<String> toScrape0 = new ArrayList<>(orderedUrls0.subList(0, scrapeLimit0));
        System.out.println("  Scraping " + toScrape0.size() + " company page(s) in parallel...");
        java.util.Map<String, LinkedInScrapeResult> companyScrapes0 =
            parallelScrapeCompanies(toScrape0, cache0);

        // Stage 3: validate + build candidates in score order, capped at MAX_COMPANY_CANDIDATES.
        for (String normalized0 : toScrape0)
        {
            if (candidates0.size() >= MAX_COMPANY_CANDIDATES)
            {
                break;
            }
            double bestScore0 = urlScore0.get(normalized0);
            LinkedInScrapeResult companyScrape0 = companyScrapes0.get(normalized0);

            if (companyScrape0 == null)
            {
                // Scrape failed/absent — still trust the SERP URL; use fund name as fallback.
                candidates0.add(new ScoredCompanyCandidate(normalized0, fundName0, bestScore0, ""));
                System.out.println("  Phase 1 candidate [" + candidates0.size()
                    + "] (no name — scrape failed): " + normalized0);
                continue;
            }

            String scrapedName0 = !isBlank(companyScrape0.name)
                ? companyScrape0.name.trim()
                : (!isBlank(companyScrape0.currentCompanyName)
                    ? companyScrape0.currentCompanyName.trim()
                    : "");

            // Validate: the scraped company must actually belong to our domain.
            // Skip this check when confidence is already high (>= THRESHOLD_SAFE).
            if (bestScore0 < IdentityResolutionScorer.THRESHOLD_SAFE)
            {
                String scrapedWebDomain0 = WebsiteCrawlerService.getDomain(
                    safe(companyScrape0.companyWebsite)
                );
                boolean websiteMatches0 = !isBlank(scrapedWebDomain0)
                    && scrapedWebDomain0.equalsIgnoreCase(domain0);

                String normScraped0 = scrapedName0.toLowerCase().replaceAll("[^a-z0-9]", "");
                String normFund0 = fundName0.toLowerCase().replaceAll("[^a-z0-9]", "");
                String normDomain0 = domain0.replaceAll("\\.[a-z]+$", "")
                    .toLowerCase().replaceAll("[^a-z0-9]", "");
                boolean nameOverlap0 = (!isBlank(normScraped0) && !isBlank(normFund0)
                    && (normScraped0.contains(normFund0.substring(0, Math.min(5, normFund0.length())))
                        || normFund0.contains(normScraped0.substring(0, Math.min(5, normScraped0.length())))))
                    || (!isBlank(normDomain0) && !isBlank(normScraped0)
                        && (normScraped0.contains(normDomain0.substring(0, Math.min(5, normDomain0.length())))
                            || normDomain0.contains(normScraped0.substring(0, Math.min(5, normScraped0.length())))));

                // Only reject when scrape returned usable data that contradicts our domain/fund.
                // If scrape returned blank (Bright Data restriction), trust the SERP score.
                if (!isBlank(normScraped0) && !websiteMatches0 && !nameOverlap0)
                {
                    System.out.println("  Phase 1 VALIDATION FAIL: scraped company \""
                        + scrapedName0 + "\" website=\"" + scrapedWebDomain0
                        + "\" doesn't match domain=\"" + domain0
                        + "\" or fund name=\"" + fundName0 + "\" — skipping.");
                    continue;
                }
            }

            String resolvedName0 = !isBlank(scrapedName0) ? scrapedName0 : fundName0;
            String about0 = safe(companyScrape0.about);
            candidates0.add(new ScoredCompanyCandidate(normalized0, resolvedName0, bestScore0, about0,
                safe(companyScrape0.companyWebsite)));
            System.out.println("  Phase 1 candidate [" + candidates0.size() + "]: \""
                + resolvedName0 + "\" @ " + normalized0
                + " (score=" + String.format("%.2f", bestScore0) + ")");
        }

        // Sort by score descending so the best candidate is tried first in Phase 2
        candidates0.sort((a0, b0) -> {
            int c0 = Double.compare(b0.score, a0.score);
            return c0 != 0 ? c0 : safe(a0.url).compareTo(safe(b0.url));
        });

        if (candidates0.isEmpty())
        {
            System.out.println("  Phase 1: no company candidates resolved from domain queries.");
        }

        return candidates0;
    }

    // ============================================================
    // CONTACT LINKEDIN RESOLUTION
    // ============================================================

    /**
     * Attempts to find and verify a person's LinkedIn profile using the given company name.
     * Returns a PersonResolutionAttempt if a match passes personThreshold0, otherwise null.
     * Evidence items are added to evidence0 regardless of outcome.
     *
     * Email last name bonus: when the email encodes a last name hint and the scraped
     * LinkedIn profile's last name exactly matches that hint, the confidence is boosted
     * by 0.20 — this compound corroboration (domain + first name + email-derived last name)
     * justifies a higher effective confidence.
     */
    private static PersonResolutionAttempt resolveContactLinkedIn(
        ArrayList<EvidenceItem> evidence0,
        String firstName0, String lastName0, String emailLastNameHint0,
        String workingFundName0, String fundWebsite0, String emailDomain0,
        double personThreshold0, ArrayList<String> emailPartialCandidates0,
        ScrapeCache cache0)
    {
        SearchTermGenerator stg0 = new SearchTermGenerator();
        BrightDataSerpClient serp0 = new BrightDataSerpClient();

        // When no last name was provided as input but the email encodes one, use that hint
        // for queries and scoring. The scraped LinkedIn name remains the authoritative source.
        String effectiveLastName0 = !isBlank(lastName0) ? lastName0 : emailLastNameHint0;

        ArrayList<String> queries0 = stg0.generatePersonLinkedInQueries(
            firstName0, effectiveLastName0, workingFundName0, emailDomain0
        );

        // Extra queries for condensed email partial candidates (e.g. "kchomitz" → search with "chomitz")
        for (String partial0 : emailPartialCandidates0)
        {
            if (!isBlank(partial0) && !isBlank(firstName0))
            {
                if (!isBlank(workingFundName0))
                {
                    queries0.add("site:linkedin.com/in \"" + firstName0 + "\" \""
                        + partial0 + "\" \"" + workingFundName0 + "\"");
                }
                if (!isBlank(emailDomain0))
                {
                    queries0.add("site:linkedin.com/in \"" + firstName0 + "\" \""
                        + partial0 + "\" \"" + emailDomain0 + "\"");
                }
                // Fix 4c: for distinctive names (7+ chars), also search without fund constraint —
                // catches ex-employees whose profile no longer mentions the fund company.
                if (partial0.length() >= 7)
                {
                    queries0.add("site:linkedin.com/in \"" + firstName0 + "\" \"" + partial0 + "\"");
                }
            }
        }

        System.out.println("  Contact LinkedIn queries: " + queries0.size());
        if (!isBlank(emailLastNameHint0) && isBlank(lastName0))
        {
            System.out.println("  Using email last name hint for search: \"" + emailLastNameHint0 + "\"");
        }

        LinkedHashMap<String, ScoredPersonCandidate> candidateMap0 = new LinkedHashMap<>();

        for (String query0 : queries0)
        {
            System.out.println("  SERP: " + query0);
            try
            {
                ArrayList<SerpResult> results0 = serp0.search(query0, MAX_SERP_RESULTS_PER_QUERY);

                for (SerpResult sr0 : results0)
                {
                    if (sr0 == null)
                    {
                        continue;
                    }
                    String type0 = LinkedInUrlExtractor.classify(sr0.url);
                    if (!DiscoveredLinkedInTarget.TYPE_PERSON.equals(type0))
                    {
                        continue;
                    }

                    String normalized0 = LinkedInUrlExtractor.normalizeLinkedInUrl(sr0.url);
                    if (isBlank(normalized0) || candidateMap0.containsKey(normalized0))
                    {
                        continue;
                    }

                    double serpScore0 = IdentityResolutionScorer.scorePersonSerpResult(
                        sr0, firstName0, effectiveLastName0, workingFundName0, fundWebsite0, emailDomain0
                    );

                    evidence0.add(new EvidenceItem(
                        "serp_person", query0, normalized0, serpScore0, safe(sr0.title)
                    ));
                    System.out.println("    Candidate: " + normalized0
                        + " | score=" + String.format("%.2f", serpScore0)
                        + " | " + safe(sr0.title));

                    candidateMap0.put(normalized0, new ScoredPersonCandidate(normalized0, sr0.title, serpScore0));
                }
            }
            catch (Exception exception0)
            {
                System.out.println("  SERP error: " + exception0.getMessage());
            }
        }

        if (candidateMap0.isEmpty())
        {
            System.out.println("  No person LinkedIn candidates found.");
            return null;
        }

        ArrayList<ScoredPersonCandidate> sorted0 = new ArrayList<>(candidateMap0.values());
        sorted0.sort((a0, b0) -> {
            int c0 = Double.compare(b0.serpScore, a0.serpScore);
            return c0 != 0 ? c0 : safe(a0.url).compareTo(safe(b0.url));
        });

        // Select top candidates, scrape them in PARALLEL (bounded pool), then accept
        // the first (by SERP order) that passes threshold.
        ArrayList<ScoredPersonCandidate> toScrape0 = new ArrayList<>();
        for (ScoredPersonCandidate c0 : sorted0)
        {
            if (toScrape0.size() >= MAX_PERSON_CANDIDATES_TO_SCRAPE) { break; }
            if (c0.serpScore < 0.05) { break; }
            toScrape0.add(c0);
        }
        ArrayList<String> scrapeUrls0 = new ArrayList<>();
        for (ScoredPersonCandidate c0 : toScrape0) { scrapeUrls0.add(c0.url); }
        System.out.println("  Scraping " + scrapeUrls0.size() + " profile(s) in parallel...");
        java.util.Map<String, LinkedInScrapeResult> profileScrapes0 =
            parallelScrapeProfiles(scrapeUrls0, cache0);

        for (ScoredPersonCandidate candidate0 : toScrape0)
        {
            try
            {
                LinkedInScrapeResult scrape0 = profileScrapes0.get(candidate0.url);
                if (scrape0 == null) { continue; }

                // Fix 3: hard-reject profiles whose scraped first name is completely different
                if (!isBlank(scrape0.firstName) && !isBlank(firstName0))
                {
                    String expF0 = firstName0.toLowerCase();
                    String gotF0 = scrape0.firstName.toLowerCase().trim().split("[,\\s]+")[0];
                    int pfxLen0 = Math.min(3, Math.min(expF0.length(), gotF0.length()));
                    boolean firstNameMatch0 = gotF0.equals(expF0)
                        || expF0.startsWith(gotF0.substring(0, pfxLen0))
                        || gotF0.startsWith(expF0.substring(0, pfxLen0));
                    if (!firstNameMatch0)
                    {
                        System.out.println("  HARD REJECT: scraped firstName=\"" + scrape0.firstName
                            + "\" doesn't match expected=\"" + firstName0 + "\"");
                        evidence0.add(new EvidenceItem("linkedin_person", "", candidate0.url, 0.0,
                            "HARD REJECT: name mismatch — " + scrape0.firstName + " != " + firstName0));
                        continue;
                    }
                }

                double profileScore0 = IdentityResolutionScorer.scorePersonLinkedInResult(
                    scrape0, firstName0, effectiveLastName0, workingFundName0, fundWebsite0, emailDomain0
                );

                evidence0.add(new EvidenceItem(
                    "linkedin_person", "", candidate0.url, profileScore0,
                    "Name: " + scrape0.firstName + " " + scrape0.lastName
                        + " | Company: " + scrape0.currentCompanyName
                ));
                System.out.println("  Profile score=" + String.format("%.2f", profileScore0)
                    + " | " + scrape0.firstName + " " + scrape0.lastName
                    + " @ " + scrape0.currentCompanyName);

                double bestScore0 = Math.max(candidate0.serpScore, profileScore0);

                // URL slug name bonus: when the LinkedIn URL slug contains both the known first
                // name and the email-derived last name, this is structural evidence independent
                // of what the profile scrape returns for company (which Bright Data sometimes omits).
                if (!isBlank(emailLastNameHint0) && isBlank(lastName0) && !isBlank(firstName0))
                {
                    String urlSlug0 = candidate0.url.toLowerCase();
                    if (urlSlug0.contains(firstName0.toLowerCase())
                        && urlSlug0.contains(emailLastNameHint0.toLowerCase()))
                    {
                        double slugBonus0 = 0.15;
                        bestScore0 = Math.min(1.0, bestScore0 + slugBonus0);
                        System.out.println("  URL slug name bonus +" + String.format("%.2f", slugBonus0)
                            + " (slug contains first+last hint) "
                            + String.format("%.2f", bestScore0 - slugBonus0)
                            + " -> " + String.format("%.2f", bestScore0));
                    }
                }

                // Fix 4a: URL slug bonus for partial email candidates
                if (!emailPartialCandidates0.isEmpty() && isBlank(emailLastNameHint0) && !isBlank(firstName0))
                {
                    String urlSlugP0 = candidate0.url.toLowerCase();
                    for (String partial0 : emailPartialCandidates0)
                    {
                        if (!isBlank(partial0)
                            && urlSlugP0.contains(firstName0.toLowerCase())
                            && urlSlugP0.contains(partial0.toLowerCase()))
                        {
                            double slugBonusP0 = 0.15;
                            bestScore0 = Math.min(1.0, bestScore0 + slugBonusP0);
                            System.out.println("  Partial-email slug bonus +" + String.format("%.2f", slugBonusP0)
                                + " (slug contains first+partial) "
                                + String.format("%.2f", bestScore0 - slugBonusP0)
                                + " -> " + String.format("%.2f", bestScore0));
                            break;
                        }
                    }
                }

                // Email last name confirmation bonus:
                // When the email encodes a last name and the scraped profile's last name matches,
                // the three-way corroboration (domain + first name + last name) confirms identity.
                // Apply the full bonus (0.30) when the scraped company doesn't conflict with the
                // expected fund (or is blank due to Bright Data limitations). Apply a reduced
                // bonus (0.05) when the scraped company clearly belongs to a different organization,
                // preventing false positives where the right name appears at the wrong company.
                // Strip credentials from the scraped last name before comparing (Bright Data sometimes
                // returns "Oosterhof, CFA" or "Smith MBA" — split on comma or space to isolate the name).
                String scrapedLastBase0 = isBlank(scrape0.lastName) ? ""
                    : scrape0.lastName.trim().split("[,\\s]+")[0].toLowerCase();
                if (!isBlank(emailLastNameHint0) && isBlank(lastName0)
                    && !isBlank(scrapedLastBase0)
                    && emailLastNameHint0.toLowerCase().equals(scrapedLastBase0))
                {
                    boolean companyConflicts0 = !isBlank(workingFundName0)
                        && !isBlank(scrape0.currentCompanyName)
                        && !scrape0.currentCompanyName.toLowerCase().contains(
                            workingFundName0.toLowerCase()
                                .substring(0, Math.min(5, workingFundName0.length()))
                        );
                    double bonus0 = companyConflicts0 ? 0.05 : 0.30;
                    double boosted0 = Math.min(1.0, bestScore0 + bonus0);
                    System.out.println("  Email last name match bonus +" + String.format("%.2f", bonus0)
                        + " (companyConflicts=" + companyConflicts0 + ")"
                        + " " + String.format("%.2f", bestScore0)
                        + " -> " + String.format("%.2f", boosted0));
                    bestScore0 = boosted0;
                }

                // Fix 4b: partial email match bonus — differentiated by exact vs prefix match,
                // distinctive name length, and whether fund appears in past work experience.
                if (!emailPartialCandidates0.isEmpty() && !isBlank(scrapedLastBase0) && isBlank(lastName0))
                {
                    for (String partial0 : emailPartialCandidates0)
                    {
                        if (!isBlank(partial0) && scrapedLastBase0.startsWith(partial0.toLowerCase()))
                        {
                            boolean exactMatchP0 = scrapedLastBase0.equals(partial0.toLowerCase());
                            boolean distinctiveP0 = partial0.length() >= 7;
                            boolean companyConflictsP0 = !isBlank(workingFundName0)
                                && !isBlank(scrape0.currentCompanyName)
                                && !scrape0.currentCompanyName.toLowerCase().contains(
                                    workingFundName0.toLowerCase()
                                        .substring(0, Math.min(5, workingFundName0.length())));
                            boolean fundInHistoryP0 = !isBlank(scrape0.pastWorkExperienceJson)
                                && !isBlank(workingFundName0)
                                && scrape0.pastWorkExperienceJson.toLowerCase().contains(
                                    workingFundName0.toLowerCase()
                                        .substring(0, Math.min(5, workingFundName0.length())));
                            double partialBonus0;
                            if (exactMatchP0 && distinctiveP0 && (!companyConflictsP0 || fundInHistoryP0))
                                partialBonus0 = 0.35;
                            else if (exactMatchP0 && distinctiveP0)
                                partialBonus0 = 0.15;
                            else if (!companyConflictsP0)
                                partialBonus0 = 0.20;
                            else
                                partialBonus0 = 0.05;
                            double boostedP0 = Math.min(1.0, bestScore0 + partialBonus0);
                            System.out.println("  Email partial bonus +" + String.format("%.2f", partialBonus0)
                                + " (exact=" + exactMatchP0 + " distinctive=" + distinctiveP0
                                + " conflict=" + companyConflictsP0 + " inHistory=" + fundInHistoryP0 + ")"
                                + " " + String.format("%.2f", bestScore0)
                                + " -> " + String.format("%.2f", boostedP0));
                            bestScore0 = boostedP0;
                            break;
                        }
                    }
                }

                if (bestScore0 >= personThreshold0)
                {
                    // When Phase 1 pre-resolved the company and we're using the adaptive (lower) threshold,
                    // boost the stored confidence to pass the CRM write gate.
                    double writeConfidence0 = bestScore0;
                    if (personThreshold0 < IdentityResolutionScorer.THRESHOLD_SAFE
                        && bestScore0 < IdentityResolutionScorer.THRESHOLD_SAFE)
                    {
                        writeConfidence0 = IdentityResolutionScorer.THRESHOLD_SAFE;
                    }

                    System.out.println("  Contact LinkedIn ACCEPTED (score=" + String.format("%.2f", bestScore0)
                        + " writeConf=" + String.format("%.2f", writeConfidence0) + ")");

                    PersonResolutionAttempt attempt0 = new PersonResolutionAttempt();
                    attempt0.contactLinkedInUrl = candidate0.url;
                    attempt0.contactLinkedInAbout = safe(scrape0.about);
                    attempt0.recentPostsJson = safe(scrape0.recentPostsJson);
                    attempt0.followerCount = safe(scrape0.followerCount);
                    attempt0.contactPastWorkExperienceJson = truncate(safe(scrape0.pastWorkExperienceJson), 49000);
                    attempt0.confidence = writeConfidence0;

                    // Save last name from LinkedIn when not provided in original input,
                    // or when we only had an unverified email hint.
                    if (!isBlank(scrape0.lastName) && (isBlank(lastName0) || !isBlank(emailLastNameHint0)))
                    {
                        attempt0.resolvedLastName = sanitizeLastName(scrape0.lastName);
                    }

                    // Capture the contact's actual company name for mismatch detection (Fix 1B)
                    attempt0.resolvedCompanyName = safe(scrape0.currentCompanyName);
                    // Bright Data sometimes leaves currentCompanyName blank; fall back to first work exp.
                    // pastWorkExperienceJson is a JSON array of strings formatted as "Title at Company".
                    if (isBlank(attempt0.resolvedCompanyName) && !isBlank(scrape0.pastWorkExperienceJson))
                    {
                        try
                        {
                            org.json.JSONArray workArr0 = new org.json.JSONArray(scrape0.pastWorkExperienceJson);
                            if (workArr0.length() > 0)
                            {
                                String firstEntry0 = workArr0.optString(0, "");
                                int atIdx0 = firstEntry0.lastIndexOf(" at ");
                                if (atIdx0 > 0)
                                {
                                    String co0 = firstEntry0.substring(atIdx0 + 4).trim();
                                    if (!isBlank(co0))
                                    {
                                        attempt0.resolvedCompanyName = co0;
                                    }
                                }
                            }
                        }
                        catch (Exception e0) { /* ignore JSON parse errors */ }
                    }

                    // Capture fund LinkedIn URL from person profile
                    if (!isBlank(scrape0.currentCompanyLinkedInUrl))
                    {
                        attempt0.fundLinkedInUrlFromProfile = scrape0.currentCompanyLinkedInUrl;
                    }

                    return attempt0;
                }
                else if (bestScore0 >= IdentityResolutionScorer.THRESHOLD_REVIEW)
                {
                    System.out.println("  Contact LinkedIn REVIEW (conf=" + String.format("%.2f", bestScore0)
                        + ") - in JSON only");
                }
                else
                {
                    System.out.println("  Contact LinkedIn REJECTED (conf=" + String.format("%.2f", bestScore0) + ")");
                }
            }
            catch (Exception exception0)
            {
                System.out.println("  Profile scrape failed: " + exception0.getMessage());
                evidence0.add(new EvidenceItem(
                    "linkedin_person", "", candidate0.url, 0.0, "scrape error: " + exception0.getMessage()
                ));
            }
        }

        return null;
    }

    /**
     * Phase 2 (pooled): finds and verifies the contact's LinkedIn profile across ALL
     * company candidates at once. Unlike resolveContactLinkedIn (which is re-run per
     * company and re-scrapes the same URLs), this builds one deduped candidate pool,
     * scrapes each unique profile URL AT MOST ONCE, and scores each scrape against
     * every company candidate (taking the best). This eliminates the ~3x redundant
     * SERP + profile-scrape work that dominated per-row latency.
     *
     * Scoring/bonus logic is kept identical to resolveContactLinkedIn so accuracy does
     * not change; the company-conflict checks use the best-matching company name.
     */
    private static PersonResolutionAttempt resolveContactLinkedInPooled(
        ArrayList<EvidenceItem> evidence0,
        String firstName0, String lastName0, String emailLastNameHint0,
        ArrayList<ScoredCompanyCandidate> companies0,
        String fundName0Fallback,
        String fundWebsite0, String emailDomain0,
        double personThreshold0, ArrayList<String> emailPartialCandidates0,
        ScrapeCache cache0)
    {
        SearchTermGenerator stg0 = new SearchTermGenerator();
        BrightDataSerpClient serp0 = new BrightDataSerpClient();

        String effectiveLastName0 = !isBlank(lastName0) ? lastName0 : emailLastNameHint0;

        // Collect the distinct company names to search/score against.
        ArrayList<String> companyNames0 = new ArrayList<>();
        if (companies0 != null)
        {
            for (ScoredCompanyCandidate c0 : companies0)
            {
                if (!isBlank(c0.resolvedName) && !companyNames0.contains(c0.resolvedName))
                {
                    companyNames0.add(c0.resolvedName);
                }
            }
        }
        if (companyNames0.isEmpty())
        {
            // No Phase 1 companies: fall back to the raw fund name (may be blank;
            // generatePersonLinkedInQueries guards fund-dependent queries on it).
            companyNames0.add(safe(fundName0Fallback));
        }

        // Build the union of person SERP queries ONCE across all company names.
        java.util.LinkedHashSet<String> queries0 = new java.util.LinkedHashSet<>();
        for (String cn0 : companyNames0)
        {
            queries0.addAll(stg0.generatePersonLinkedInQueries(
                firstName0, effectiveLastName0, cn0, emailDomain0));
        }
        for (String partial0 : emailPartialCandidates0)
        {
            if (!isBlank(partial0) && !isBlank(firstName0))
            {
                for (String cn0 : companyNames0)
                {
                    if (!isBlank(cn0))
                    {
                        queries0.add("site:linkedin.com/in \"" + firstName0 + "\" \""
                            + partial0 + "\" \"" + cn0 + "\"");
                    }
                }
                if (!isBlank(emailDomain0))
                {
                    queries0.add("site:linkedin.com/in \"" + firstName0 + "\" \""
                        + partial0 + "\" \"" + emailDomain0 + "\"");
                }
                // Fix 4c: for distinctive names (7+ chars), also search without fund constraint —
                // catches ex-employees whose profile no longer mentions the fund company.
                if (partial0.length() >= 7)
                {
                    queries0.add("site:linkedin.com/in \"" + firstName0 + "\" \"" + partial0 + "\"");
                }
            }
        }

        System.out.println("  Contact LinkedIn pooled queries: " + queries0.size()
            + " across " + companyNames0.size() + " company name(s)");

        // Run all queries, building ONE deduped candidate pool. The SERP score for a
        // candidate is the best score across whichever company name matched.
        LinkedHashMap<String, ScoredPersonCandidate> pool0 = new LinkedHashMap<>();
        for (String query0 : queries0)
        {
            System.out.println("  SERP: " + query0);
            try
            {
                ArrayList<SerpResult> results0 = serp0.search(query0, MAX_SERP_RESULTS_PER_QUERY);
                for (SerpResult sr0 : results0)
                {
                    if (sr0 == null)
                    {
                        continue;
                    }
                    if (!DiscoveredLinkedInTarget.TYPE_PERSON.equals(
                            LinkedInUrlExtractor.classify(sr0.url)))
                    {
                        continue;
                    }
                    String normalized0 = LinkedInUrlExtractor.normalizeLinkedInUrl(sr0.url);
                    if (isBlank(normalized0))
                    {
                        continue;
                    }

                    double serpScore0 = -1.0;
                    for (String cn0 : companyNames0)
                    {
                        double s0 = IdentityResolutionScorer.scorePersonSerpResult(
                            sr0, firstName0, effectiveLastName0, cn0, fundWebsite0, emailDomain0);
                        if (s0 > serpScore0)
                        {
                            serpScore0 = s0;
                        }
                    }

                    ScoredPersonCandidate existing0 = pool0.get(normalized0);
                    if (existing0 == null || serpScore0 > existing0.serpScore)
                    {
                        pool0.put(normalized0, new ScoredPersonCandidate(normalized0, sr0.title, serpScore0));
                    }
                    evidence0.add(new EvidenceItem(
                        "serp_person", query0, normalized0, serpScore0, safe(sr0.title)));
                    System.out.println("    Candidate: " + normalized0
                        + " | score=" + String.format("%.2f", serpScore0)
                        + " | " + safe(sr0.title));
                }
            }
            catch (Exception exception0)
            {
                System.out.println("  SERP error (pooled): " + exception0.getMessage());
            }
        }

        if (pool0.isEmpty())
        {
            System.out.println("  No person LinkedIn candidates found.");
            return null;
        }

        ArrayList<ScoredPersonCandidate> sorted0 = new ArrayList<>(pool0.values());
        sorted0.sort((a0, b0) -> {
            int c0 = Double.compare(b0.serpScore, a0.serpScore);
            return c0 != 0 ? c0 : safe(a0.url).compareTo(safe(b0.url));
        });

        // Select the top candidates, then scrape them in PARALLEL (bounded pool).
        ArrayList<ScoredPersonCandidate> toScrape0 = new ArrayList<>();
        for (ScoredPersonCandidate c0 : sorted0)
        {
            if (toScrape0.size() >= MAX_PERSON_CANDIDATES_TO_SCRAPE) { break; }
            if (c0.serpScore < 0.05) { break; }
            toScrape0.add(c0);
        }
        ArrayList<String> scrapeUrls0 = new ArrayList<>();
        for (ScoredPersonCandidate c0 : toScrape0) { scrapeUrls0.add(c0.url); }
        System.out.println("  Scraping " + scrapeUrls0.size() + " profile(s) in parallel...");
        java.util.Map<String, LinkedInScrapeResult> profileScrapes0 =
            parallelScrapeProfiles(scrapeUrls0, cache0);

        PersonResolutionAttempt best0 = null;
        for (ScoredPersonCandidate candidate0 : toScrape0)
        {
            try
            {
                LinkedInScrapeResult scrape0 = profileScrapes0.get(candidate0.url);
                if (scrape0 == null) { continue; }

                // Fix 3: hard-reject profiles whose scraped first name is completely different
                if (!isBlank(scrape0.firstName) && !isBlank(firstName0))
                {
                    String expF0 = firstName0.toLowerCase();
                    String gotF0 = scrape0.firstName.toLowerCase().trim().split("[,\\s]+")[0];
                    int pfxLen0 = Math.min(3, Math.min(expF0.length(), gotF0.length()));
                    boolean firstNameMatch0 = gotF0.equals(expF0)
                        || expF0.startsWith(gotF0.substring(0, pfxLen0))
                        || gotF0.startsWith(expF0.substring(0, pfxLen0));
                    if (!firstNameMatch0)
                    {
                        System.out.println("  HARD REJECT: scraped firstName=\"" + scrape0.firstName
                            + "\" doesn't match expected=\"" + firstName0 + "\"");
                        evidence0.add(new EvidenceItem("linkedin_person", "", candidate0.url, 0.0,
                            "HARD REJECT: name mismatch — " + scrape0.firstName + " != " + firstName0));
                        continue;
                    }
                }

                // Score the scrape against every company; keep the best and the
                // company name that produced it (used for conflict checks below).
                double profileScore0 = -1.0;
                String bestCompanyName0 = safe(fundName0Fallback);
                for (String cn0 : companyNames0)
                {
                    double ps0 = IdentityResolutionScorer.scorePersonLinkedInResult(
                        scrape0, firstName0, effectiveLastName0, cn0, fundWebsite0, emailDomain0);
                    if (ps0 > profileScore0)
                    {
                        profileScore0 = ps0;
                        bestCompanyName0 = cn0;
                    }
                }

                evidence0.add(new EvidenceItem(
                    "linkedin_person", "", candidate0.url, profileScore0,
                    "Name: " + scrape0.firstName + " " + scrape0.lastName
                        + " | Company: " + scrape0.currentCompanyName));
                System.out.println("  Profile score=" + String.format("%.2f", profileScore0)
                    + " | " + scrape0.firstName + " " + scrape0.lastName
                    + " @ " + scrape0.currentCompanyName);

                double bestScore0 = Math.max(candidate0.serpScore, profileScore0);

                // URL slug name bonus (identical to resolveContactLinkedIn)
                if (!isBlank(emailLastNameHint0) && isBlank(lastName0) && !isBlank(firstName0))
                {
                    String urlSlug0 = candidate0.url.toLowerCase();
                    if (urlSlug0.contains(firstName0.toLowerCase())
                        && urlSlug0.contains(emailLastNameHint0.toLowerCase()))
                    {
                        bestScore0 = Math.min(1.0, bestScore0 + 0.15);
                    }
                }

                // Fix 4a: URL slug bonus for partial email candidates
                if (!emailPartialCandidates0.isEmpty() && isBlank(emailLastNameHint0) && !isBlank(firstName0))
                {
                    String urlSlugP0 = candidate0.url.toLowerCase();
                    for (String partial0 : emailPartialCandidates0)
                    {
                        if (!isBlank(partial0)
                            && urlSlugP0.contains(firstName0.toLowerCase())
                            && urlSlugP0.contains(partial0.toLowerCase()))
                        {
                            double slugBonusP0 = 0.15;
                            bestScore0 = Math.min(1.0, bestScore0 + slugBonusP0);
                            System.out.println("  Partial-email slug bonus +" + String.format("%.2f", slugBonusP0)
                                + " (slug contains first+partial) "
                                + String.format("%.2f", bestScore0 - slugBonusP0)
                                + " -> " + String.format("%.2f", bestScore0));
                            break;
                        }
                    }
                }

                // Strip credentials from scraped last name before comparing.
                String scrapedLastBase0 = isBlank(scrape0.lastName) ? ""
                    : scrape0.lastName.trim().split("[,\\s]+")[0].toLowerCase();

                // Email last name confirmation bonus (uses best-matching company name)
                if (!isBlank(emailLastNameHint0) && isBlank(lastName0)
                    && !isBlank(scrapedLastBase0)
                    && emailLastNameHint0.toLowerCase().equals(scrapedLastBase0))
                {
                    boolean companyConflicts0 = !isBlank(bestCompanyName0)
                        && !isBlank(scrape0.currentCompanyName)
                        && !scrape0.currentCompanyName.toLowerCase().contains(
                            bestCompanyName0.toLowerCase()
                                .substring(0, Math.min(5, bestCompanyName0.length())));
                    double bonus0 = companyConflicts0 ? 0.05 : 0.30;
                    bestScore0 = Math.min(1.0, bestScore0 + bonus0);
                }

                // Fix 4b: partial email match bonus — differentiated by exact vs prefix match,
                // distinctive name length, and whether fund appears in past work experience.
                if (!emailPartialCandidates0.isEmpty() && !isBlank(scrapedLastBase0) && isBlank(lastName0))
                {
                    for (String partial0 : emailPartialCandidates0)
                    {
                        if (!isBlank(partial0) && scrapedLastBase0.startsWith(partial0.toLowerCase()))
                        {
                            boolean exactMatchP0 = scrapedLastBase0.equals(partial0.toLowerCase());
                            boolean distinctiveP0 = partial0.length() >= 7;
                            boolean companyConflictsP0 = !isBlank(bestCompanyName0)
                                && !isBlank(scrape0.currentCompanyName)
                                && !scrape0.currentCompanyName.toLowerCase().contains(
                                    bestCompanyName0.toLowerCase()
                                        .substring(0, Math.min(5, bestCompanyName0.length())));
                            boolean fundInHistoryP0 = !isBlank(scrape0.pastWorkExperienceJson)
                                && !isBlank(bestCompanyName0)
                                && scrape0.pastWorkExperienceJson.toLowerCase().contains(
                                    bestCompanyName0.toLowerCase()
                                        .substring(0, Math.min(5, bestCompanyName0.length())));
                            double partialBonus0;
                            if (exactMatchP0 && distinctiveP0 && (!companyConflictsP0 || fundInHistoryP0))
                                partialBonus0 = 0.35;
                            else if (exactMatchP0 && distinctiveP0)
                                partialBonus0 = 0.15;
                            else if (!companyConflictsP0)
                                partialBonus0 = 0.20;
                            else
                                partialBonus0 = 0.05;
                            double boostedP0 = Math.min(1.0, bestScore0 + partialBonus0);
                            System.out.println("  Email partial bonus +" + String.format("%.2f", partialBonus0)
                                + " (exact=" + exactMatchP0 + " distinctive=" + distinctiveP0
                                + " conflict=" + companyConflictsP0 + " inHistory=" + fundInHistoryP0 + ")"
                                + " " + String.format("%.2f", bestScore0)
                                + " -> " + String.format("%.2f", boostedP0));
                            bestScore0 = boostedP0;
                            break;
                        }
                    }
                }

                if (bestScore0 >= personThreshold0)
                {
                    double writeConfidence0 = bestScore0;
                    if (personThreshold0 < IdentityResolutionScorer.THRESHOLD_SAFE
                        && bestScore0 < IdentityResolutionScorer.THRESHOLD_SAFE)
                    {
                        writeConfidence0 = IdentityResolutionScorer.THRESHOLD_SAFE;
                    }

                    System.out.println("  Contact LinkedIn ACCEPTED (score="
                        + String.format("%.2f", bestScore0)
                        + " writeConf=" + String.format("%.2f", writeConfidence0) + ")");

                    PersonResolutionAttempt attempt0 = new PersonResolutionAttempt();
                    attempt0.contactLinkedInUrl = candidate0.url;
                    attempt0.contactLinkedInAbout = safe(scrape0.about);
                    attempt0.recentPostsJson = safe(scrape0.recentPostsJson);
                    attempt0.followerCount = safe(scrape0.followerCount);
                    attempt0.contactPastWorkExperienceJson =
                        truncate(safe(scrape0.pastWorkExperienceJson), 49000);
                    attempt0.confidence = writeConfidence0;

                    if (!isBlank(scrape0.lastName) && (isBlank(lastName0) || !isBlank(emailLastNameHint0)))
                    {
                        attempt0.resolvedLastName = sanitizeLastName(scrape0.lastName);
                    }

                    attempt0.resolvedCompanyName = safe(scrape0.currentCompanyName);
                    if (isBlank(attempt0.resolvedCompanyName) && !isBlank(scrape0.pastWorkExperienceJson))
                    {
                        try
                        {
                            org.json.JSONArray workArr0 = new org.json.JSONArray(scrape0.pastWorkExperienceJson);
                            if (workArr0.length() > 0)
                            {
                                String firstEntry0 = workArr0.optString(0, "");
                                int atIdx0 = firstEntry0.lastIndexOf(" at ");
                                if (atIdx0 > 0)
                                {
                                    String co0 = firstEntry0.substring(atIdx0 + 4).trim();
                                    if (!isBlank(co0))
                                    {
                                        attempt0.resolvedCompanyName = co0;
                                    }
                                }
                            }
                        }
                        catch (Exception e0) { /* ignore JSON parse errors */ }
                    }

                    if (!isBlank(scrape0.currentCompanyLinkedInUrl))
                    {
                        attempt0.fundLinkedInUrlFromProfile = scrape0.currentCompanyLinkedInUrl;
                    }

                    // Keep the highest-confidence accepted attempt; stop once strong.
                    if (best0 == null || attempt0.confidence > best0.confidence)
                    {
                        best0 = attempt0;
                    }
                    if (best0.confidence >= IdentityResolutionScorer.THRESHOLD_SAFE)
                    {
                        System.out.println("  Strong match — stopping profile iteration.");
                        break;
                    }
                }
                else if (bestScore0 >= IdentityResolutionScorer.THRESHOLD_REVIEW)
                {
                    System.out.println("  Contact LinkedIn REVIEW (conf="
                        + String.format("%.2f", bestScore0) + ") - in JSON only");
                }
                else
                {
                    System.out.println("  Contact LinkedIn REJECTED (conf="
                        + String.format("%.2f", bestScore0) + ")");
                }
            }
            catch (Exception exception0)
            {
                System.out.println("  Profile scrape failed: " + exception0.getMessage());
                evidence0.add(new EvidenceItem(
                    "linkedin_person", "", candidate0.url, 0.0,
                    "scrape error: " + exception0.getMessage()));
            }
        }

        return best0;
    }

    // ============================================================
    // FUND LINKEDIN RESOLUTION
    // ============================================================

    private static void resolveFundLinkedIn(
        BackgroundCheckResult result0,
        String fundName0, String fundWebsite0, String emailDomain0,
        ScrapeCache cache0)
    {
        // Already resolved from verified person profile
        if (!isBlank(result0.fundLinkedInUrl.value)
            && IdentityResolutionScorer.isSafeToAutoWrite(result0.fundLinkedInUrl.confidence))
        {
            System.out.println("  Fund LinkedIn already resolved: " + result0.fundLinkedInUrl.value);
            scrapeFundLinkedIn(result0, cache0);
            return;
        }

        if (isBlank(fundName0))
        {
            System.out.println("  Skipping fund LinkedIn: no fund name.");
            return;
        }

        SearchTermGenerator stg0 = new SearchTermGenerator();
        BrightDataSerpClient serp0 = new BrightDataSerpClient();

        ArrayList<String> queries0 = stg0.generateCompanyLinkedInQueries(fundName0);
        System.out.println("  Fund LinkedIn queries: " + queries0.size());

        for (String query0 : queries0)
        {
            System.out.println("  SERP company: " + query0);
            try
            {
                ArrayList<SerpResult> results0 = serp0.search(query0, MAX_SERP_RESULTS_PER_QUERY);

                for (SerpResult sr0 : results0)
                {
                    if (sr0 == null)
                    {
                        continue;
                    }
                    String type0 = LinkedInUrlExtractor.classify(sr0.url);
                    if (!DiscoveredLinkedInTarget.TYPE_COMPANY.equals(type0))
                    {
                        continue;
                    }

                    String normalized0 = LinkedInUrlExtractor.normalizeLinkedInUrl(sr0.url);
                    if (isBlank(normalized0))
                    {
                        continue;
                    }

                    double serpScore0 = IdentityResolutionScorer.scoreCompanySerpResult(
                        sr0, fundName0, fundWebsite0, emailDomain0
                    );

                    result0.evidence.add(new EvidenceItem(
                        "serp_company", query0, normalized0, serpScore0, safe(sr0.title)
                    ));
                    System.out.println("  Company candidate: " + normalized0
                        + " | score=" + String.format("%.2f", serpScore0));

                    if (serpScore0 >= IdentityResolutionScorer.THRESHOLD_REVIEW)
                    {
                        result0.fundLinkedInUrl = new ResolvedField(
                            normalized0, serpScore0, normalized0, "from SERP"
                        );
                        System.out.println("  Fund LinkedIn: " + normalized0);
                        scrapeFundLinkedIn(result0, cache0);
                        return;
                    }
                }
            }
            catch (Exception exception0)
            {
                System.out.println("  Company SERP error: " + exception0.getMessage());
            }
        }
    }

    private static void scrapeFundLinkedIn(BackgroundCheckResult result0, ScrapeCache cache0)
    {
        if (isBlank(result0.fundLinkedInUrl.value))
        {
            return;
        }

        System.out.println("  Scraping fund LinkedIn: " + result0.fundLinkedInUrl.value);
        try
        {
            BrightDataLinkedInClient linkedIn0 = new BrightDataLinkedInClient();
            LinkedInScrapeResult scrape0 = cache0.scrapeCompany(linkedIn0, result0.fundLinkedInUrl.value);

            if (!isBlank(scrape0.about))
            {
                result0.fundLinkedInAbout = new ResolvedField(
                    scrape0.about,
                    result0.fundLinkedInUrl.confidence,
                    result0.fundLinkedInUrl.value,
                    ""
                );
                System.out.println("  Fund LinkedIn about: " + scrape0.about.length() + " chars");
            }

            // Update fund name if LinkedIn gives a different name and we don't already have a good one
            String scrapedName0 = !isBlank(scrape0.name)
                ? scrape0.name.trim()
                : (!isBlank(scrape0.currentCompanyName) ? scrape0.currentCompanyName.trim() : "");
            if (!isBlank(scrapedName0) && isBlank(result0.fundName.value))
            {
                result0.fundName = new ResolvedField(
                    scrapedName0, result0.fundLinkedInUrl.confidence, result0.fundLinkedInUrl.value, "from LinkedIn"
                );
                System.out.println("  Fund name from LinkedIn: \"" + scrapedName0 + "\"");
            }
            // Fix 2: propagate company website from Phase 3 scrape
            if (!isBlank(scrape0.companyWebsite) && isBlank(result0.fundWebsite.value))
            {
                result0.fundWebsite = new ResolvedField(
                    scrape0.companyWebsite, result0.fundLinkedInUrl.confidence,
                    result0.fundLinkedInUrl.value, "from LinkedIn company page"
                );
                System.out.println("  Fund website from LinkedIn (Phase 3): " + scrape0.companyWebsite);
            }
        }
        catch (Exception exception0)
        {
            System.out.println("  Fund LinkedIn scrape failed: " + exception0.getMessage());
        }
    }

    // ============================================================
    // CONTACT WEBSITE BIO RESOLUTION
    // ============================================================

    // Side-effect-free bio-discovery track (OPT-7). Performs Phase 4 entirely on its
    // own state and returns a BioResult; the caller applies it to result0 AFTER the
    // parallel LinkedIn track has joined. This avoids any concurrent read/write of
    // result0 (notably contactPastWorkExperience) between the two tracks.
    private static BioResult resolveContactWebsiteBioTrack(
        String firstName0, String lastName0, String fundWebsite0, String fundName0,
        ScrapeCache cache0)
    {
        BioResult out0 = new BioResult();
        if (isBlank(fundWebsite0) || (isBlank(firstName0) && isBlank(lastName0)))
        {
            return out0;
        }

        SearchTermGenerator stg0 = new SearchTermGenerator();
        BrightDataSerpClient serp0 = new BrightDataSerpClient();

        ArrayList<String> queries0 = stg0.generateContactWebsiteBioQueries(firstName0, lastName0, fundWebsite0);
        ArrayList<String> openWebQueries0 =
            stg0.generateOpenWebCareerQueries(firstName0, lastName0, fundName0);
        java.util.HashSet<String> openWebSet0 = new java.util.HashSet<>(openWebQueries0);
        queries0.addAll(openWebQueries0);
        System.out.println("  Contact bio queries: " + queries0.size());

        String fundDomain0 = WebsiteCrawlerService.getDomain(fundWebsite0);

        // Collect all unique candidates first, then sort by URL preference.
        LinkedHashMap<String, String> candidateQueryMap0 = new LinkedHashMap<>();
        LinkedHashMap<String, Double> candidateScoreMap0 = new LinkedHashMap<>();

        for (String query0 : queries0)
        {
            System.out.println("  Bio SERP: " + query0);
            try
            {
                ArrayList<SerpResult> results0 = serp0.search(query0, 5);

                for (SerpResult sr0 : results0)
                {
                    if (sr0 == null || isBlank(sr0.url))
                    {
                        continue;
                    }
                    // Reject non-bio pages (LinkedIn posts/activity/feeds, etc.). A post
                    // slug is a headline, not a person — accepting one both stores the
                    // wrong "bio" URL and corrupts the extracted last name.
                    if (!isUsableBioUrl(sr0.url))
                    {
                        continue;
                    }
                    String resultDomain0 = WebsiteCrawlerService.getDomain(sr0.url);
                    boolean isOpenWeb0 = openWebSet0.contains(query0);
                    if (!isOpenWeb0 && !fundDomain0.equals(resultDomain0))
                    {
                        continue;
                    }
                    if (candidateQueryMap0.containsKey(sr0.url))
                    {
                        continue;
                    }

                    double urlScore0 = scoreBioUrlPreference(sr0.url, firstName0, lastName0);
                    candidateQueryMap0.put(sr0.url, query0);
                    candidateScoreMap0.put(sr0.url, urlScore0);
                    System.out.println("    Bio candidate: " + sr0.url + " | urlScore=" + String.format("%.1f", urlScore0));
                }
            }
            catch (Exception exception0)
            {
                System.out.println("  Bio SERP error: " + exception0.getMessage());
            }
        }

        if (candidateQueryMap0.isEmpty())
        {
            System.out.println("  No bio page candidates found.");
            return out0;
        }

        ArrayList<String> sortedUrls0 = new ArrayList<>(candidateQueryMap0.keySet());
        sortedUrls0.sort((a0, b0) -> Double.compare(
            candidateScoreMap0.getOrDefault(b0, 0.0),
            candidateScoreMap0.getOrDefault(a0, 0.0)
        ));

        final int MAX_BIO_CANDIDATES_TO_SCRAPE0 = 3;
        ArrayList<ContactBioCandidate> foundCandidates0 = new ArrayList<>();

        System.out.println("  Crawling up to " + MAX_BIO_CANDIDATES_TO_SCRAPE0
            + " bio candidate page(s)...");

        // Crawl candidate pages WITHOUT per-page LLM calls. Keep a page when it
        // plausibly mentions the person (text contains the first/last name, or the
        // URL slug yields a last name like "/team/chris-smith"). The expensive
        // summary + last-name + work-history extraction happens once below via a
        // single consolidated LLM call (extractBioBundle), not per page.
        for (String url0 : sortedUrls0)
        {
            if (foundCandidates0.size() >= MAX_BIO_CANDIDATES_TO_SCRAPE0)
            {
                break;
            }

            System.out.println("  Scraping bio page: " + url0);
            try
            {
                String html0 = cache0.crawl(url0);
                String text0 = WebsiteCrawlerService.extractVisibleText(html0);

                if (isBlank(text0) || text0.length() < 100)
                {
                    System.out.println("    Skipping: page text too short ("
                        + (text0 == null ? 0 : text0.length()) + " chars)");
                    continue;
                }

                String urlLast0 = extractLastNameFromUrl(url0, firstName0);
                boolean mentionsPerson0 =
                    (!isBlank(firstName0) && text0.toLowerCase().contains(firstName0.toLowerCase()))
                    || (!isBlank(lastName0) && text0.toLowerCase().contains(lastName0.toLowerCase()))
                    || !isBlank(urlLast0);
                if (!mentionsPerson0)
                {
                    System.out.println("    Skipping: person not mentioned on page.");
                    continue;
                }

                ContactBioCandidate cand0 = new ContactBioCandidate();
                cand0.bioUrl = url0;
                cand0.bioText = text0;
                cand0.extractedLastName = urlLast0; // summary-based fallback filled later
                foundCandidates0.add(cand0);
                System.out.println("    Bio candidate kept: " + url0
                    + " | urlLast=\"" + urlLast0 + "\"");
            }
            catch (Exception exception0)
            {
                System.out.println("  Bio page scrape failed: " + exception0.getMessage());
            }
        }

        if (foundCandidates0.isEmpty())
        {
            System.out.println("  No bio page with person content found.");
            return out0;
        }

        int distinctCount0 = countDistinctLastNames(foundCandidates0);

        if (distinctCount0 > 1)
        {
            // Multiple distinct people — one consolidated LLM call PER distinct person
            // to fill that page's summary; defer per-person LinkedIn to Phase 5.
            System.out.println("  Multiple distinct people found (" + distinctCount0
                + " last names) — deferring to Phase 5 for per-person resolution.");
            LinkedHashMap<String, ContactBioCandidate> bestPerName0 = new LinkedHashMap<>();
            for (ContactBioCandidate c0 : foundCandidates0)
            {
                if (isBlank(c0.extractedLastName)) { continue; }
                String key0 = c0.extractedLastName.toLowerCase();
                if (!bestPerName0.containsKey(key0))
                {
                    bestPerName0.put(key0, c0);
                }
            }
            if (bestPerName0.isEmpty())
            {
                out0.candidates = foundCandidates0;
                out0.multiPerson = true;
                return out0;
            }
            ArrayList<ContactBioCandidate> distinctList0 = new ArrayList<>(bestPerName0.values());
            for (ContactBioCandidate c0 : distinctList0)
            {
                try
                {
                    BioBundle b0 = extractBioBundle(
                        java.util.Collections.singletonList(c0.bioText),
                        firstName0, c0.extractedLastName, cache0);
                    c0.bioSummary = b0.summary;
                    if (isBlank(c0.extractedLastName) && !isBlank(b0.lastName))
                    {
                        c0.extractedLastName = b0.lastName;
                    }
                }
                catch (Exception ex0)
                {
                    System.out.println("  Bio bundle (multi) failed for " + c0.bioUrl
                        + ": " + ex0.getMessage());
                }
            }
            out0.candidates = distinctList0;
            out0.multiPerson = true;
            return out0;
        }

        // Single person — ONE consolidated LLM call over all crawled page texts,
        // returning found + summary + last_name + work_history together.
        String canonicalUrl0 = foundCandidates0.get(0).bioUrl;
        ArrayList<String> foundTexts0 = new ArrayList<>();
        for (ContactBioCandidate c0 : foundCandidates0)
        {
            foundTexts0.add(c0.bioText);
        }

        BioBundle bundle0;
        try
        {
            bundle0 = extractBioBundle(foundTexts0, firstName0, lastName0, cache0);
        }
        catch (Exception ex0)
        {
            System.out.println("  Bio bundle extraction failed: " + ex0.getMessage());
            return out0;
        }

        if (!bundle0.found || isBlank(bundle0.summary))
        {
            System.out.println("  Person not found in bio page text (consolidated check).");
            return out0;
        }

        // Populate the BioResult (no result0 writes — caller applies after join).
        out0.bioFound = true;
        out0.bioUrl = canonicalUrl0;
        out0.bioSummary = bundle0.summary;
        out0.bioWorkHistoryJson = bundle0.workHistoryJson;
        out0.bioCareerSummary = bundle0.careerSummary;
        out0.bioInstitutionsJson = bundle0.institutionsJson;
        out0.bioEducationJson = bundle0.educationJson;
        out0.bioQuery = candidateQueryMap0.getOrDefault(canonicalUrl0, "");
        out0.evidence.add(new EvidenceItem(
            "website_bio", out0.bioQuery, canonicalUrl0, 0.88,
            "consolidated from " + foundCandidates0.size() + " page(s): "
                + String.join(", ", foundCandidates0.stream()
                    .map(c0 -> c0.bioUrl).collect(java.util.stream.Collectors.toList()))
        ));

        // Fill the single candidate's last name + summary from the bundle.
        if (isBlank(foundCandidates0.get(0).extractedLastName))
        {
            foundCandidates0.get(0).extractedLastName = !isBlank(bundle0.lastName)
                ? bundle0.lastName
                : extractLastNameFromBioSummary(bundle0.summary, firstName0);
        }
        foundCandidates0.get(0).bioSummary = bundle0.summary;

        out0.candidates = foundCandidates0;
        return out0;
    }

    // Holder for the side-effect-free bio-discovery track (OPT-7). The LinkedIn track
    // and this track run in parallel; the caller merges this into result0 after join.
    private static class BioResult
    {
        ArrayList<ContactBioCandidate> candidates = new ArrayList<>();
        boolean multiPerson = false;     // true when distinct people found (AMBIGUOUS)
        boolean bioFound = false;        // single-person bio resolved
        String bioUrl = "";
        String bioSummary = "";
        String bioWorkHistoryJson = "[]";
        String bioCareerSummary = "";
        String bioInstitutionsJson = "[]";
        String bioEducationJson = "[]";
        String bioQuery = "";
        ArrayList<EvidenceItem> evidence = new ArrayList<>();
    }

    // Holder for the single consolidated bio-extraction LLM call.
    private static class BioBundle
    {
        boolean found = false;
        String summary = "";
        String lastName = "";
        String workHistoryJson = "[]";
        String careerSummary = "";
        String institutionsJson = "[]";
        String educationJson = "[]";
    }

    /**
     * Single consolidated OpenAI call that replaces the former per-page summarize +
     * synthesize + work-history-extraction calls. Given the scraped text of one or
     * more bio pages for the SAME person, returns whether the person is present, a
     * 2-4 sentence summary, their last name, and a work-history JSON array — all from
     * the source text only (no outside knowledge).
     */
    private static BioBundle extractBioBundle(
        java.util.List<String> pageTexts0, String firstName0, String lastName0,
        ScrapeCache cache0) throws Exception
    {
        BioBundle out0 = new BioBundle();
        if (pageTexts0 == null || pageTexts0.isEmpty())
        {
            return out0;
        }

        // Combine page texts under a total character budget (per-page = total / count).
        int perPageBudget0 = MAX_WEBSITE_TEXT_CHARS / Math.max(1, pageTexts0.size());
        StringBuilder combined0 = new StringBuilder();
        for (String pt0 : pageTexts0)
        {
            if (isBlank(pt0)) { continue; }
            String chunk0 = pt0.length() > perPageBudget0 ? pt0.substring(0, perPageBudget0) : pt0;
            combined0.append(chunk0).append("\n\n---\n\n");
        }
        if (combined0.length() == 0)
        {
            return out0;
        }

        String name0 = (safe(firstName0) + " " + safe(lastName0)).trim();

        String prompt0 =
            "You are extracting structured information about " + name0
            + " from one or more pages on their firm's website.\n\n"
            + "Use ONLY the provided source text. Do not use any outside knowledge.\n"
            + "Return ONLY a single JSON object (no markdown, no code fences) with these keys:\n"
            + "{\n"
            + "  \"found\": true or false,\n"
            + "  \"summary\": \"2-4 sentence bio, or empty string\",\n"
            + "  \"last_name\": \"the person's last name, or empty string\",\n"
            + "  \"work_history\": [ {\"title\":\"\",\"company\":\"\",\"date_range\":\"\",\"description\":\"\"} ],\n"
            + "  \"career_summary\": \"one paragraph on their career background, or empty string\",\n"
            + "  \"institutions\": [ \"names of firms/organizations/schools they have been affiliated with\" ],\n"
            + "  \"education\": [ {\"institution\":\"\",\"degree\":\"\",\"field\":\"\",\"year\":\"\"} ]\n"
            + "}\n\n"
            + "Rules:\n"
            + "1. Set found=true only if " + name0 + " is clearly described in the text. "
            + "If not clearly present, set found=false, summary=\"\", last_name=\"\", work_history=[].\n"
            + "2. summary: 2-4 sentences on their role, background, and expertise, from the text only.\n"
            + "3. work_history: jobs only (no education); empty array if none found.\n"
            + "4. institutions: a flat list of organization/school NAMES only (for relationship "
            + "graphing); empty array if none.\n"
            + "5. education: formal education entries only; empty array if none.\n"
            + "6. Everything from the source text only — no outside knowledge.\n"
            + "7. Do not infer or hallucinate anything not in the source text.\n\n"
            + "Source text:\n" + combined0;

        String response0 = cache0.llm(prompt0);
        if (isBlank(response0))
        {
            return out0;
        }

        String cleaned0 = response0.trim();
        if (cleaned0.startsWith("```"))
        {
            cleaned0 = cleaned0.replaceAll("```[a-z]*\\n?", "").trim();
        }
        int braceStart0 = cleaned0.indexOf('{');
        int braceEnd0 = cleaned0.lastIndexOf('}');
        if (braceStart0 < 0 || braceEnd0 <= braceStart0)
        {
            return out0; // not JSON → treat as not found
        }
        cleaned0 = cleaned0.substring(braceStart0, braceEnd0 + 1);

        try
        {
            JSONObject obj0 = new JSONObject(cleaned0);
            out0.found = obj0.optBoolean("found", false);
            out0.summary = safe(obj0.optString("summary", "")).trim();
            out0.lastName = safe(obj0.optString("last_name", "")).trim();
            JSONArray work0 = obj0.optJSONArray("work_history");
            out0.workHistoryJson = (work0 != null) ? work0.toString() : "[]";
            out0.careerSummary = safe(obj0.optString("career_summary", "")).trim();
            JSONArray inst0 = obj0.optJSONArray("institutions");
            out0.institutionsJson = (inst0 != null) ? inst0.toString() : "[]";
            JSONArray edu0 = obj0.optJSONArray("education");
            out0.educationJson = (edu0 != null) ? edu0.toString() : "[]";
        }
        catch (Exception e0)
        {
            System.out.println("  Bio bundle JSON parse failed: " + e0.getMessage());
            return out0;
        }

        return out0;
    }

    private static String summarizePostsViaLLM(
        String recentPostsJson0, String firstName0, String lastName0, ScrapeCache cache0)
    {
        if (isBlank(recentPostsJson0) || recentPostsJson0.equals("[]")) { return ""; }
        try {
            String name0 = (safe(firstName0) + " " + safe(lastName0)).trim();
            String prompt0 =
                "Summarize the recurring themes, topics, and professional interests in "
                + name0 + "'s recent LinkedIn posts in 2-3 sentences. "
                + "Use ONLY the provided posts. Do not invent anything. "
                + "Return plain text only (no JSON, no markdown).\n\nPosts JSON:\n"
                + recentPostsJson0;
            String out0 = cache0.llm(prompt0);
            return out0 == null ? "" : out0.trim();
        } catch (Exception e0) {
            System.out.println("  Posts summary failed: " + e0.getMessage());
            return "";
        }
    }

    private static double scoreBioUrlPreference(String url0, String firstName0, String lastName0)
    {
        if (isBlank(url0))
        {
            return 0.0;
        }
        String path0 = url0.toLowerCase().replaceAll("https?://[^/]+", "");

        boolean hasPersonName0 = false;
        if (!isBlank(firstName0) && path0.contains(firstName0.toLowerCase()))
        {
            hasPersonName0 = true;
        }
        if (!isBlank(lastName0) && path0.contains(lastName0.toLowerCase()))
        {
            hasPersonName0 = true;
        }

        boolean isTeamPage0 = path0.contains("/team") || path0.contains("/people")
            || path0.contains("/staff") || path0.contains("/our-team")
            || path0.contains("/about/team") || path0.contains("/who-we-are")
            || path0.contains("/leadership") || path0.contains("/our-people")
            || path0.contains("/meet") || path0.contains("/members");

        if (isTeamPage0 && hasPersonName0)
        {
            return 0.9;
        }
        if (hasPersonName0)
        {
            return 0.8;
        }
        if (isTeamPage0)
        {
            // Generic team page without person name — useful but scrape after individual pages
            return 0.5;
        }
        return 0.2;
    }

    // ============================================================
    // FUND WEBSITE SERP RESOLUTION
    // ============================================================

    private static String resolveFundWebsiteViaSERP(
        String fundName0, String emailDomain0,
        ArrayList<EvidenceItem> evidence0)
    {
        SearchTermGenerator stg0 = new SearchTermGenerator();
        BrightDataSerpClient serp0 = new BrightDataSerpClient();

        ArrayList<String> queries0 = stg0.generateFundWebsiteQueries(fundName0);

        for (String query0 : queries0)
        {
            try
            {
                ArrayList<SerpResult> results0 = serp0.search(query0, 5);

                for (SerpResult sr0 : results0)
                {
                    if (sr0 == null || isBlank(sr0.url))
                    {
                        continue;
                    }

                    String urlLower0 = sr0.url.toLowerCase();
                    if (urlLower0.contains("linkedin.com")
                        || urlLower0.contains("crunchbase.com")
                        || urlLower0.contains("bloomberg.com")
                        || urlLower0.contains("pitchbook.com"))
                    {
                        continue;
                    }

                    String domain0 = WebsiteCrawlerService.getDomain(sr0.url);
                    if (isBlank(domain0))
                    {
                        continue;
                    }

                    double score0 = IdentityResolutionScorer.scoreCompanySerpResult(
                        sr0, fundName0, "", emailDomain0
                    );
                    evidence0.add(new EvidenceItem(
                        "serp_website", query0, sr0.url, score0, "fund website candidate"
                    ));

                    if (score0 >= 0.30 || (!isBlank(emailDomain0) && domain0.equals(emailDomain0)))
                    {
                        return sr0.url;
                    }
                }
            }
            catch (Exception exception0)
            {
                // skip
            }
        }

        return "";
    }

    // ============================================================
    // CRM WRITE-BACK
    // ============================================================

    private static int[] buildUpdateColumns(
        SessionContext context0,
        HashMap<String, Integer> headerMap0)
    {
        String[] headerNames0 = new String[]
        {
            context0.config.getCol("mainTabContactLinkedInCol"),
            context0.config.getCol("mainTabContactLinkedInAboutCol"),
            context0.config.getCol("mainTabContactPastWorkExperienceCol"),
            context0.config.getCol("mainTabCompanyLinkedInCol"),
            context0.config.getCol("mainTabFundLinkedInAboutCol"),
            context0.config.getCol("mainTabContactWebsiteBioUrlCol"),
            context0.config.getCol("mainTabContactWebsiteBioSummaryCol"),
            context0.config.getCol("mainTabBackgroundCheckStatusCol"),
            context0.config.getCol("mainTabBackgroundCheckConfidenceCol"),
            context0.config.getCol("mainTabLastBackgroundCheckDateCol"),
            context0.config.getCol("mainTabBackgroundCheckJsonCol"),
            context0.config.getCol("mainTabContact1FirstNameCol"),
            context0.config.getCol("mainTabContact1LastNameCol"),
            context0.config.getCol("mainTabFundNameCol"),
            context0.config.getCol("mainTabWebsiteCol"),
            context0.config.getCol("mainTabContactLinkedInPostsSummaryCol"),  // 15
            context0.config.getCol("mainTabContactFollowerCountCol"),         // 16
            context0.config.getCol("mainTabContactBioCareerSummaryCol"),      // 17
            context0.config.getCol("mainTabContactBioInstitutionsCol"),       // 18
            context0.config.getCol("mainTabContactBioEducationCol")           // 19
        };

        int[] cols0 = new int[headerNames0.length];
        for (int i = 0; i < headerNames0.length; i++)
        {
            cols0[i] = SheetsApp.findColumnInHeaderMap(headerMap0, headerNames0[i]);
            if (cols0[i] == -1)
            {
                System.out.println("WARNING: Column not found: " + headerNames0[i]);
            }
        }
        return cols0;
    }

    private static void writeResultsToCrm(
        String spreadsheetId0,
        String mainTabName0,
        int[] updateCols0,
        LinkedHashMap<Integer, BackgroundCheckResult> rowResults0) throws Exception
    {
        int minRow0 = findMinKey(rowResults0);
        int maxRow0 = findMaxKey(rowResults0);
        int rowCount0 = maxRow0 - minRow0 + 1;

        String[][][] columnData0 = new String[WRITE_FIELD_COUNT][rowCount0][1];

        for (int colIdx0 = 0; colIdx0 < WRITE_FIELD_COUNT; colIdx0++)
        {
            if (updateCols0[colIdx0] == -1)
            {
                for (int r = 0; r < rowCount0; r++)
                {
                    columnData0[colIdx0][r][0] = "";
                }
                continue;
            }
            columnData0[colIdx0] = SheetsApp.readRangeMatrix(
                spreadsheetId0, mainTabName0,
                minRow0, updateCols0[colIdx0],
                maxRow0, updateCols0[colIdx0]
            );
        }

        for (java.util.Map.Entry<Integer, BackgroundCheckResult> entry0 : rowResults0.entrySet())
        {
            int rowNumber0 = entry0.getKey();
            BackgroundCheckResult result0 = entry0.getValue();
            int localIdx0 = rowNumber0 - minRow0;

            String[] values0 = buildWriteValues(result0);
            for (int colIdx0 = 0; colIdx0 < WRITE_FIELD_COUNT; colIdx0++)
            {
                // null means "keep existing cell value" (used for input field write-back
                // when no improved value was found). Non-null (including "") is written.
                if (localIdx0 < columnData0[colIdx0].length && values0[colIdx0] != null)
                {
                    columnData0[colIdx0][localIdx0][0] = values0[colIdx0];
                }
            }
        }

        for (int colIdx0 = 0; colIdx0 < WRITE_FIELD_COUNT; colIdx0++)
        {
            if (updateCols0[colIdx0] == -1)
            {
                continue;
            }
            SheetsApp.updateRangeMatrix(
                spreadsheetId0, mainTabName0,
                minRow0, updateCols0[colIdx0],
                columnData0[colIdx0]
            );
        }
    }

    private static String[] buildWriteValues(BackgroundCheckResult result0)
    {
        String[] v0 = new String[WRITE_FIELD_COUNT];

        v0[WRITE_CONTACT_LINKEDIN_URL] = IdentityResolutionScorer.isSafeToAutoWrite(result0.contactLinkedInUrl.confidence)
            ? result0.contactLinkedInUrl.value : "";
        v0[WRITE_CONTACT_LINKEDIN_ABOUT] = IdentityResolutionScorer.isSafeToAutoWrite(result0.contactLinkedInAbout.confidence)
            ? truncate(result0.contactLinkedInAbout.value, 49000) : "";
        v0[WRITE_CONTACT_PAST_WORK_EXP] = IdentityResolutionScorer.isSafeToAutoWrite(result0.contactPastWorkExperience.confidence)
            ? truncate(result0.contactPastWorkExperience.value, 49000) : "";
        v0[WRITE_FUND_LINKEDIN_URL] = IdentityResolutionScorer.isSafeToAutoWrite(result0.fundLinkedInUrl.confidence)
            ? result0.fundLinkedInUrl.value : "";
        v0[WRITE_FUND_LINKEDIN_ABOUT] = IdentityResolutionScorer.isSafeToAutoWrite(result0.fundLinkedInAbout.confidence)
            ? truncate(result0.fundLinkedInAbout.value, 49000) : "";
        v0[WRITE_CONTACT_BIO_URL] = IdentityResolutionScorer.isSafeToAutoWrite(result0.contactWebsiteBioUrl.confidence)
            ? result0.contactWebsiteBioUrl.value : "";
        v0[WRITE_CONTACT_BIO_SUMMARY] = IdentityResolutionScorer.isSafeToAutoWrite(result0.contactWebsiteBioSummary.confidence)
            ? truncate(result0.contactWebsiteBioSummary.value, 49000) : "";
        v0[WRITE_BG_STATUS] = safe(result0.status);
        v0[WRITE_BG_CONFIDENCE] = String.format("%.2f", result0.overallConfidence);
        v0[WRITE_LAST_BG_DATE] = safe(result0.lastCheckedAt);
        v0[WRITE_BG_JSON] = truncate(safe(result0.backgroundCheckJson), 49000);

        // Fix 1: write back input fields when improved during the check.
        // null = keep existing cell; only write when a non-blank improved value was found.
        String inputFirst0 = result0.input != null ? safe(result0.input.firstName).trim() : "";
        String resolvedFirst0 = result0.firstName != null ? safe(result0.firstName.value).trim() : "";
        v0[WRITE_FIRST_NAME] = (!isBlank(resolvedFirst0) && !resolvedFirst0.equalsIgnoreCase(inputFirst0))
            ? resolvedFirst0 : null;

        String inputLast0 = result0.input != null ? safe(result0.input.lastName).trim() : "";
        // Final backstop: never write a malformed last name (URL hash fragments, file
        // extensions, post-headline word salad) into the CRM's identity column.
        String resolvedLast0 = sanitizeLastName(result0.lastName != null ? safe(result0.lastName.value) : "");
        v0[WRITE_LAST_NAME] = (!isBlank(resolvedLast0) && !resolvedLast0.equalsIgnoreCase(inputLast0))
            ? resolvedLast0 : null;

        String inputFundName0 = result0.input != null ? safe(result0.input.fundName).trim() : "";
        String resolvedFundName0 = result0.fundName != null ? safe(result0.fundName.value).trim() : "";
        v0[WRITE_FUND_NAME] = (!isBlank(resolvedFundName0)
            && (isBlank(inputFundName0) || !resolvedFundName0.equalsIgnoreCase(inputFundName0))
            && IdentityResolutionScorer.isSafeToAutoWrite(
                result0.fundName != null ? result0.fundName.confidence : 0.0))
            ? resolvedFundName0 : null;

        String inputFundWebsite0 = result0.input != null ? safe(result0.input.fundWebsite).trim() : "";
        String resolvedFundWebsite0 = result0.fundWebsite != null ? safe(result0.fundWebsite.value).trim() : "";
        v0[WRITE_FUND_WEBSITE] = (!isBlank(resolvedFundWebsite0)
            && isBlank(inputFundWebsite0)
            && (result0.fundWebsite != null ? result0.fundWebsite.confidence : 0.0) >= 0.70)
            ? resolvedFundWebsite0 : null;

        v0[WRITE_CONTACT_POSTS_SUMMARY] =
            IdentityResolutionScorer.isSafeToAutoWrite(result0.contactLinkedInPostsSummary.confidence)
            ? truncate(result0.contactLinkedInPostsSummary.value, 49000) : "";
        v0[WRITE_CONTACT_FOLLOWER_COUNT] =
            IdentityResolutionScorer.isSafeToAutoWrite(result0.contactFollowerCount.confidence)
            ? result0.contactFollowerCount.value : "";
        v0[WRITE_CONTACT_BIO_CAREER_SUMMARY] =
            IdentityResolutionScorer.isSafeToAutoWrite(result0.contactBioCareerSummary.confidence)
            ? truncate(result0.contactBioCareerSummary.value, 49000) : "";
        v0[WRITE_CONTACT_BIO_INSTITUTIONS] =
            IdentityResolutionScorer.isSafeToAutoWrite(result0.contactBioInstitutions.confidence)
            ? truncate(result0.contactBioInstitutions.value, 49000) : "";
        v0[WRITE_CONTACT_BIO_EDUCATION] =
            IdentityResolutionScorer.isSafeToAutoWrite(result0.contactBioEducation.confidence)
            ? truncate(result0.contactBioEducation.value, 49000) : "";

        return v0;
    }

    // ============================================================
    // CONFIDENCE AND STATUS HELPERS
    // ============================================================

    // A field counts toward status/confidence only when it is actually write-eligible
    // (non-blank AND above the auto-write threshold). This keeps computeStatus,
    // computeOverallConfidence, and buildWriteValues in agreement, so a row never
    // reports PARTIAL/AMBIGUOUS while every output column is blank.
    private static boolean isFieldPresent(ResolvedField field0)
    {
        return field0 != null && !isBlank(field0.value)
            && IdentityResolutionScorer.isSafeToAutoWrite(field0.confidence);
    }

    // Confidence reflects how sure we are of the fields we DID resolve — not how many of
    // the three we found (completeness is conveyed by status: PARTIAL vs COMPLETED). It is
    // a weighted average over the PRESENT fields only (normalized by their own weights), so
    // a confidently resolved contact LinkedIn scores high on its own, and the contact field
    // dominates when several are present. Only write-eligible fields count, which keeps a
    // sub-threshold-only row from reporting non-zero confidence (no phantom PARTIAL).
    private static double computeOverallConfidence(BackgroundCheckResult result0)
    {
        final double wContact0 = 0.50;
        final double wBio0 = 0.30;
        final double wFund0 = 0.20;

        double num0 = 0.0;
        double den0 = 0.0;
        if (isFieldPresent(result0.contactLinkedInUrl))
        {
            num0 += wContact0 * result0.contactLinkedInUrl.confidence;
            den0 += wContact0;
        }
        if (isFieldPresent(result0.contactWebsiteBioUrl))
        {
            num0 += wBio0 * result0.contactWebsiteBioUrl.confidence;
            den0 += wBio0;
        }
        if (isFieldPresent(result0.fundLinkedInUrl))
        {
            num0 += wFund0 * result0.fundLinkedInUrl.confidence;
            den0 += wFund0;
        }
        if (den0 == 0.0)
        {
            return 0.0;
        }
        return num0 / den0;
    }

    private static String computeStatus(BackgroundCheckResult result0)
    {
        boolean hasPersonLinkedIn0 = isFieldPresent(result0.contactLinkedInUrl);
        boolean hasFundLinkedIn0 = isFieldPresent(result0.fundLinkedInUrl);
        boolean hasBio0 = isFieldPresent(result0.contactWebsiteBioUrl);

        if (hasPersonLinkedIn0 && hasFundLinkedIn0 && hasBio0)
        {
            return STATUS_COMPLETED;
        }
        if (hasPersonLinkedIn0 || hasFundLinkedIn0 || hasBio0)
        {
            return STATUS_PARTIAL;
        }
        return STATUS_FAILED;
    }

    // ============================================================
    // JSON BUILDER
    // ============================================================

    private static String buildBackgroundCheckJson(BackgroundCheckResult result0)
    {
        JSONObject json0 = new JSONObject();
        json0.put("status", safe(result0.status));
        json0.put("overall_confidence", result0.overallConfidence);
        json0.put("last_checked_at", safe(result0.lastCheckedAt));

        JSONObject fields0 = new JSONObject();
        fields0.put("contact_linkedin_url", fieldToJson(result0.contactLinkedInUrl));
        fields0.put("contact_linkedin_about", fieldToJson(result0.contactLinkedInAbout));
        fields0.put("contact_past_work_experience", fieldToJson(result0.contactPastWorkExperience));
        fields0.put("fund_linkedin_url", fieldToJson(result0.fundLinkedInUrl));
        fields0.put("fund_linkedin_about", fieldToJson(result0.fundLinkedInAbout));
        fields0.put("contact_website_bio_url", fieldToJson(result0.contactWebsiteBioUrl));
        fields0.put("contact_website_bio_summary", fieldToJson(result0.contactWebsiteBioSummary));
        json0.put("resolved_fields", fields0);

        JSONArray evidenceArr0 = new JSONArray();
        for (EvidenceItem ei0 : result0.evidence)
        {
            JSONObject e0 = new JSONObject();
            e0.put("type", ei0.type);
            e0.put("query", ei0.query);
            e0.put("url", ei0.url);
            e0.put("score", ei0.score);
            e0.put("notes", ei0.notes);
            evidenceArr0.put(e0);
        }
        json0.put("evidence", evidenceArr0);

        return json0.toString();
    }

    private static JSONObject fieldToJson(ResolvedField field0)
    {
        JSONObject obj0 = new JSONObject();
        if (field0 == null)
        {
            obj0.put("value", "");
            obj0.put("confidence", 0.0);
            obj0.put("source_url", "");
            return obj0;
        }
        obj0.put("value", truncate(safe(field0.value), 5000));
        obj0.put("confidence", field0.confidence);
        obj0.put("source_url", safe(field0.sourceUrl));
        return obj0;
    }

    // ============================================================
    // TERMINAL SUMMARY
    // ============================================================

    private static void printTerminalSummary(BackgroundCheckResult result0)
    {
        System.out.println("  ===== BACKGROUND CHECK RESULT =====");
        System.out.println("  Status: " + result0.status);
        System.out.println("  Overall Confidence: " + String.format("%.2f", result0.overallConfidence));
        printField("  Contact LinkedIn URL", result0.contactLinkedInUrl);
        printField("  Contact LinkedIn About", result0.contactLinkedInAbout);
        printField("  Contact Past Work Exp", result0.contactPastWorkExperience);
        printField("  Fund LinkedIn URL", result0.fundLinkedInUrl);
        printField("  Fund LinkedIn About", result0.fundLinkedInAbout);
        printField("  Contact Bio URL", result0.contactWebsiteBioUrl);
        printField("  Contact Bio Summary", result0.contactWebsiteBioSummary);
        System.out.println("  Evidence items: " + result0.evidence.size());
    }

    private static void printField(String label0, ResolvedField field0)
    {
        if (field0 == null || isBlank(field0.value))
        {
            System.out.println(label0 + ": (not found)");
        }
        else
        {
            String preview0 = field0.value.length() > 80
                ? field0.value.substring(0, 80) + "..."
                : field0.value;
            System.out.println(label0 + ": " + preview0
                + " [conf=" + String.format("%.2f", field0.confidence) + "]");
        }
    }

    // ============================================================
    // UTILITIES
    // ============================================================

    private static int findMinKey(LinkedHashMap<Integer, ?> map0)
    {
        int min0 = Integer.MAX_VALUE;
        for (int key0 : map0.keySet())
        {
            if (key0 < min0) min0 = key0;
        }
        return min0;
    }

    private static int findMaxKey(LinkedHashMap<Integer, ?> map0)
    {
        int max0 = Integer.MIN_VALUE;
        for (int key0 : map0.keySet())
        {
            if (key0 > max0) max0 = key0;
        }
        return max0;
    }

    private static String getCell(String[][] matrix0, int rowIndex0)
    {
        if (matrix0 == null || rowIndex0 >= matrix0.length)
        {
            return "";
        }
        if (matrix0[rowIndex0] == null || matrix0[rowIndex0].length == 0)
        {
            return "";
        }
        String val0 = matrix0[rowIndex0][0];
        return val0 == null ? "" : val0.trim();
    }

    private static String truncate(String value0, int maxLen0)
    {
        if (isBlank(value0))
        {
            return "";
        }
        if (value0.length() <= maxLen0)
        {
            return value0;
        }
        return value0.substring(0, maxLen0);
    }

    private static String escapeJson(String value0)
    {
        if (value0 == null)
        {
            return "";
        }
        return value0.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static String safe(String value0)
    {
        return value0 == null ? "" : value0;
    }

    private static boolean isBlank(String value0)
    {
        return value0 == null || value0.trim().isEmpty();
    }

    /**
     * Tries to extract a last name from the email local part.
     * Handles formats like "s.oosterhof@..." → "oosterhof" and "simon.oosterhof@..." → "oosterhof".
     * Returns empty string if no hint can be reliably extracted.
     */
    private static String extractLastNameHintFromEmail(String email0, String firstName0)
    {
        if (isBlank(email0))
        {
            return "";
        }
        int atIdx0 = email0.lastIndexOf('@');
        if (atIdx0 < 1)
        {
            return "";
        }
        String local0 = email0.substring(0, atIdx0).toLowerCase().trim();
        int dotIdx0 = local0.indexOf('.');
        if (dotIdx0 < 0)
        {
            return "";
        }
        String before0 = local0.substring(0, dotIdx0).trim();
        String after0 = local0.substring(dotIdx0 + 1).trim();

        // Only extract when after-dot part looks like a last name (>= 3 chars, alpha only)
        if (after0.length() < 3 || !after0.matches("[a-z]+"))
        {
            return "";
        }

        // Format: initial.lastname (e.g. "s.oosterhof") — before is 1-3 chars
        if (before0.length() <= 3)
        {
            // Make sure "after" isn't the first name itself
            if (!isBlank(firstName0) && after0.equalsIgnoreCase(firstName0.trim()))
            {
                return "";
            }
            return after0;
        }

        // Format: first.last (e.g. "simon.oosterhof") — before matches input first name
        if (!isBlank(firstName0) && before0.equalsIgnoreCase(firstName0.trim().toLowerCase()))
        {
            return after0;
        }

        return "";
    }

    private static ArrayList<String> extractEmailPartialCandidates(
        String email0, String firstName0, String existingHint0)
    {
        ArrayList<String> candidates0 = new ArrayList<>();

        // Only needed when full hint extraction produced nothing
        if (!isBlank(existingHint0))
        {
            return candidates0;
        }
        if (isBlank(email0) || isBlank(firstName0))
        {
            return candidates0;
        }

        int atIdx0 = email0.lastIndexOf('@');
        if (atIdx0 < 1)
        {
            return candidates0;
        }

        // Strip everything except lowercase letters
        String alphaOnly0 = email0.substring(0, atIdx0).toLowerCase().replaceAll("[^a-z]", "");

        if (alphaOnly0.length() < 4)
        {
            return candidates0;
        }

        String firstLower0 = firstName0.toLowerCase().trim();

        // Try stripping full first name prefix (e.g. "kenchomitz" → "chomitz" when firstName="Ken")
        if (!isBlank(firstLower0)
            && alphaOnly0.startsWith(firstLower0)
            && alphaOnly0.length() > firstLower0.length() + 2)
        {
            String remainder0 = alphaOnly0.substring(firstLower0.length());
            if (remainder0.length() >= 4 && remainder0.matches("[a-z]+"))
            {
                candidates0.add(remainder0);
            }
        }

        // Try stripping first initial (e.g. "kchomitz" → "chomitz" when firstName="Ken")
        if (!isBlank(firstLower0)
            && alphaOnly0.charAt(0) == firstLower0.charAt(0)
            && alphaOnly0.length() >= 4)
        {
            String remainder0 = alphaOnly0.substring(1);
            if (remainder0.length() >= 4
                && remainder0.matches("[a-z]+")
                && !candidates0.contains(remainder0))
            {
                candidates0.add(remainder0);
            }
        }

        return candidates0;
    }

    private static String extractLastNameFromUrl(String url0, String firstName0)
    {
        if (isBlank(url0))
        {
            return "";
        }
        try
        {
            String normalized0 = url0.trim();
            if (!normalized0.startsWith("http"))
            {
                normalized0 = "https://" + normalized0;
            }
            java.net.URI uri0 = java.net.URI.create(normalized0);
            String path0 = uri0.getPath();
            if (isBlank(path0))
            {
                return "";
            }
            String[] segments0 = path0.split("/");
            String lastSeg0 = "";
            for (int i = segments0.length - 1; i >= 0; i--)
            {
                if (!isBlank(segments0[i]))
                {
                    lastSeg0 = segments0[i].toLowerCase();
                    break;
                }
            }
            if (isBlank(lastSeg0))
            {
                return "";
            }
            // Expect "firstname-lastname" or "firstname-middle-lastname"
            String[] parts0 = lastSeg0.split("[-_]");
            if (parts0.length < 2)
            {
                return "";
            }
            String normFirst0 = firstName0 == null ? "" : firstName0.trim().toLowerCase();
            if (!isBlank(normFirst0) && parts0[0].equals(normFirst0))
            {
                StringBuilder lastName0sb = new StringBuilder();
                for (int i = 1; i < parts0.length; i++)
                {
                    if (!isBlank(parts0[i]))
                    {
                        if (lastName0sb.length() > 0) lastName0sb.append(" ");
                        lastName0sb.append(Character.toUpperCase(parts0[i].charAt(0)));
                        lastName0sb.append(parts0[i].substring(1));
                    }
                }
                return sanitizeLastName(lastName0sb.toString().trim());
            }
        }
        catch (Exception e0)
        {
            // ignore parse errors
        }
        return "";
    }

    private static int countDistinctLastNames(ArrayList<ContactBioCandidate> candidates0)
    {
        java.util.HashSet<String> seen0 = new java.util.HashSet<>();
        for (ContactBioCandidate c0 : candidates0)
        {
            if (!isBlank(c0.extractedLastName))
            {
                seen0.add(c0.extractedLastName.trim().toLowerCase());
            }
        }
        return seen0.size();
    }

    private static String extractLastNameFromBioSummary(String bioSummary0, String firstName0)
    {
        if (isBlank(bioSummary0) || isBlank(firstName0))
        {
            return "";
        }
        java.util.regex.Pattern pat0 = java.util.regex.Pattern.compile(
            "\\b" + java.util.regex.Pattern.quote(firstName0.trim())
            + "\\s+([\\p{Lu}][\\p{L}'\\-]+)"
        );
        java.util.regex.Matcher mat0 = pat0.matcher(bioSummary0);
        if (mat0.find())
        {
            return sanitizeLastName(mat0.group(1));
        }
        return "";
    }

    // ============================================================
    // NAME / URL SANITIZERS AND DEDUP HELPERS
    // ============================================================

    // Tokens at which a surname clearly ends: connectors and trailing credentials.
    private static final java.util.Set<String> NAME_STOP_WORDS = new java.util.HashSet<>(
        java.util.Arrays.asList(
            "at", "of", "in", "on", "the", "and", "for", "to", "a", "an", "is", "was",
            "with", "from", "by", "as", "or", "activity", "impact", "report",
            "cfa", "cpa", "caia", "mba", "phd", "md", "msc", "bsc", "ba", "ma",
            "jr", "sr", "ii", "iii", "iv", "esq", "frm", "cfp"));

    // Legitimate surname particles that must NOT terminate parsing (e.g. "van der Berg").
    private static final java.util.Set<String> NAME_PARTICLES = new java.util.HashSet<>(
        java.util.Arrays.asList(
            "van", "von", "de", "del", "della", "der", "den", "la", "le", "di", "da",
            "du", "bin", "al", "mac", "mc", "st"));

    // Single guard for every last name before it reaches the CRM, regardless of source
    // (URL slug, bio summary, or LinkedIn scrape). Strips file extensions, stops at the
    // first hash/ID or connector/credential token, caps to 3 tokens (to allow particles),
    // and rejects anything still malformed (dot, digit, single char, over-long).
    private static String sanitizeLastName(String raw0)
    {
        if (isBlank(raw0))
        {
            return "";
        }
        String s0 = raw0.trim().replaceAll("(?i)\\.(html?|php|aspx|asp|jsp)$", "");
        s0 = s0.replace("_", " ");
        String[] toks0 = s0.split("\\s+");
        StringBuilder out0 = new StringBuilder();
        int kept0 = 0;
        for (String t0 : toks0)
        {
            String rawTok0 = t0.trim();
            if (rawTok0.isEmpty())
            {
                continue;
            }
            if (rawTok0.matches(".*\\d.*"))
            {
                break; // LinkedIn hash / activity ID — never a name part (check before stripping)
            }
            String tok0 = rawTok0.replaceAll("^[^\\p{L}]+", "").replaceAll("[^\\p{L}']+$", "").trim();
            if (tok0.isEmpty())
            {
                continue;
            }
            String low0 = tok0.toLowerCase();
            if (NAME_STOP_WORDS.contains(low0) && !NAME_PARTICLES.contains(low0))
            {
                break;
            }
            if (kept0 >= 3)
            {
                break;
            }
            if (out0.length() > 0)
            {
                out0.append(" ");
            }
            out0.append(tok0);
            kept0++;
        }
        String result0 = out0.toString().trim();
        if (result0.length() < 2 || result0.contains(".") || result0.length() > 40)
        {
            return "";
        }
        return result0;
    }

    // Strip leading honorifics from a first name ("Dr. Joseph" -> "Joseph").
    private static String normalizeFirstName(String raw0)
    {
        if (isBlank(raw0))
        {
            return "";
        }
        return raw0.trim().replaceAll("^(?i)(dr|mr|mrs|ms|prof|sir|miss|mx)\\.?\\s+", "").trim();
    }

    // A URL is usable as a contact bio source only if it is an actual profile/bio page,
    // not a LinkedIn post/activity/feed (whose slug is a headline, not a person).
    private static boolean isUsableBioUrl(String url0)
    {
        if (isBlank(url0))
        {
            return false;
        }
        String low0 = url0.toLowerCase();
        if (low0.contains("/posts/") || low0.contains("/activity")
            || low0.contains("/pulse/") || low0.contains("/feed/"))
        {
            return false;
        }
        // On LinkedIn, only individual profiles (/in/) or company pages are bio-worthy.
        if (low0.contains("linkedin.com") && !low0.contains("/in/") && !low0.contains("/company/"))
        {
            return false;
        }
        return true;
    }

    // ---- Duplicate-row reconciliation (same person appearing on multiple CRM rows) ----

    // Identity key for de-duplication: prefer the (normalized) email; otherwise fall back
    // to first+last+fund. Returns "" when there is not enough signal to safely dedup.
    private static String dedupKey(BackgroundCheckResult result0)
    {
        if (result0 == null || result0.input == null)
        {
            return "";
        }
        String email0 = safe(result0.input.cleanedEmail).trim().toLowerCase();
        if (!isBlank(email0))
        {
            return "email:" + email0;
        }
        String first0 = safe(result0.input.firstName).trim().toLowerCase();
        String last0 = safe(result0.input.lastName).trim().toLowerCase();
        String fund0 = safe(result0.input.fundName).trim().toLowerCase();
        if ((isBlank(first0) && isBlank(last0)) || isBlank(fund0))
        {
            return "";
        }
        return "nf:" + first0 + "|" + last0 + "|" + fund0;
    }

    private static int statusRank(String status0)
    {
        if (STATUS_COMPLETED.equals(status0)) return 4;
        if (STATUS_AMBIGUOUS.equals(status0)) return 3;
        if (STATUS_PARTIAL.equals(status0)) return 2;
        if (STATUS_FAILED.equals(status0)) return 1;
        return 0;
    }

    private static boolean isBetterResult(BackgroundCheckResult a0, BackgroundCheckResult b0)
    {
        int ra0 = statusRank(a0.status);
        int rb0 = statusRank(b0.status);
        if (ra0 != rb0)
        {
            return ra0 > rb0;
        }
        return a0.overallConfidence > b0.overallConfidence;
    }

    // When the same person occupies several rows, the parallel pipeline can resolve them
    // slightly differently (e.g. "Uttawar" vs "Uttarwar", PARTIAL vs COMPLETED). Pick the
    // single best result per identity and apply it to every sibling row so the CRM is
    // internally consistent and re-runs converge.
    private static void deduplicateResults(LinkedHashMap<Integer, BackgroundCheckResult> rowResults0)
    {
        java.util.HashMap<String, BackgroundCheckResult> bestByKey0 = new java.util.HashMap<>();
        for (java.util.Map.Entry<Integer, BackgroundCheckResult> e0 : rowResults0.entrySet())
        {
            String key0 = dedupKey(e0.getValue());
            if (isBlank(key0))
            {
                continue;
            }
            BackgroundCheckResult cur0 = bestByKey0.get(key0);
            if (cur0 == null || isBetterResult(e0.getValue(), cur0))
            {
                bestByKey0.put(key0, e0.getValue());
            }
        }
        for (java.util.Map.Entry<Integer, BackgroundCheckResult> e0 : rowResults0.entrySet())
        {
            String key0 = dedupKey(e0.getValue());
            if (isBlank(key0))
            {
                continue;
            }
            BackgroundCheckResult best0 = bestByKey0.get(key0);
            if (best0 != null && best0 != e0.getValue())
            {
                System.out.println("  Dedup: row " + e0.getKey()
                    + " adopts best result for identity [" + key0 + "]");
                e0.setValue(best0);
            }
        }
    }

    // -----------------------------------------------------------------------
    // Public bio-fallback accessor for NewAllocatorIndicator (Task 15).
    // Wraps the private Phase-4 bio-discovery track so external callers can
    // reuse it without duplicating the scrape logic.
    // -----------------------------------------------------------------------

    /** Lightweight output from the bio-page fallback, enough for start-date extraction. */
    public static class PersonBioResult
    {
        public boolean found = false;
        public String bioUrl = "";
        public String workHistoryJson = "[]";
        public String careerSummary = "";
    }

    /**
     * Resolve a bio page for a named person at a fund website and return their
     * work history (for start-date extraction by NewAllocatorIndicator).
     * Returns an empty PersonBioResult on any failure — never null.
     */
    public static PersonBioResult resolveBioForAllocator(
        String firstName0, String lastName0, String fundWebsite0,
        String fundName0, ScrapeCache cache0)
    {
        PersonBioResult out0 = new PersonBioResult();
        try
        {
            BioResult bio0 = resolveContactWebsiteBioTrack(
                firstName0, lastName0, fundWebsite0, fundName0, cache0);
            if (bio0 != null && bio0.bioFound)
            {
                out0.found = true;
                out0.bioUrl = bio0.bioUrl;
                out0.workHistoryJson = bio0.bioWorkHistoryJson;
                out0.careerSummary = bio0.bioCareerSummary;
            }
        }
        catch (Exception e0)
        {
            System.err.println("[BasicBackgroundChecker.resolveBioForAllocator] " + e0.getMessage());
        }
        return out0;
    }
}
