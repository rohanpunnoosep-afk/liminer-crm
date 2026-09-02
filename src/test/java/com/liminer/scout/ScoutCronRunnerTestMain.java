package com.liminer.scout;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.util.ArrayList;
import java.util.List;

/*
 * ScoutCronRunnerTestMain — fully offline test for ScoutCronRunner's job
 * routing, arg parsing, and failure exit paths (Phase 2 task 4 of 4, headless
 * cron/launchd entry path). Injects a stub JobHandlers so no network call or
 * Google Sheets access is ever made. Prints SCOUT_CRON_OK and exits 0 on
 * success; exits 1 on any assertion failure.
 */
public class ScoutCronRunnerTestMain
{
    private static int failures0 = 0;

    public static void main(String[] args0) throws Exception
    {
        testAdvRefreshRoutes();
        testEdgarDeltaRoutes();
        testClientRunRoutesWithArg();
        testClientRunMissingArgFailsWithNonzeroExit();
        testNonprofitRefreshRoutes();
        testProfilePrefetchRoutes();
        testUnknownJobFailsWithNonzeroExit();
        testNoArgsFailsWithNonzeroExit();
        testHandlerExceptionFailsWithNonzeroExit();
        testHeadlessFlagIsStrippedByMain();

        if (failures0 > 0)
        {
            System.err.println("ScoutCronRunnerTestMain: " + failures0 + " failure(s)");
            System.exit(1);
        }
        System.out.println("SCOUT_CRON_OK");
    }

    // -----------------------------------------------------------------------
    // Stub handlers — record every call, never touch the network.
    // -----------------------------------------------------------------------

    private static class RecordingJobHandlers implements ScoutCronRunner.JobHandlers
    {
        List<String> calls = new ArrayList<String>();
        boolean throwOnClientRun = false;

        @Override
        public String advRefresh(String[] jobArgs0)
        {
            calls.add("adv-refresh");
            return "STUB_ADV_REFRESH_OK";
        }

        @Override
        public String edgarDelta(String[] jobArgs0)
        {
            calls.add("edgar-delta");
            return "STUB_EDGAR_DELTA_OK";
        }

        @Override
        public String clientRun(String clientEmail0, String[] jobArgs0) throws Exception
        {
            calls.add("client-run:" + clientEmail0);
            if (throwOnClientRun)
            {
                throw new IllegalStateException("simulated client-run failure");
            }
            return "STUB_CLIENT_RUN_OK:" + clientEmail0;
        }

        @Override
        public String nonprofitRefresh(String[] jobArgs0)
        {
            calls.add("nonprofit-refresh");
            return "STUB_NONPROFIT_REFRESH_OK";
        }

        @Override
        public String profilePrefetch(String[] jobArgs0)
        {
            calls.add("profile-prefetch");
            return "STUB_PROFILE_PREFETCH_OK";
        }
    }

    // -----------------------------------------------------------------------
    // Routing tests
    // -----------------------------------------------------------------------

    private static void testAdvRefreshRoutes()
    {
        RecordingJobHandlers handlers0 = new RecordingJobHandlers();
        Captured captured0 = runDispatch(new String[] { "adv-refresh" }, handlers0);

        assertEquals(0, captured0.exitCode, "adv-refresh: expected exit code 0");
        assertEquals(1, handlers0.calls.size(), "adv-refresh: expected exactly one handler call");
        assertEquals("adv-refresh", handlers0.calls.get(0), "adv-refresh: wrong handler routed");
        assertContains(captured0.stdout, "STUB_ADV_REFRESH_OK", "adv-refresh: stdout should contain handler result");
    }

    private static void testEdgarDeltaRoutes()
    {
        RecordingJobHandlers handlers0 = new RecordingJobHandlers();
        Captured captured0 = runDispatch(new String[] { "edgar-delta" }, handlers0);

        assertEquals(0, captured0.exitCode, "edgar-delta: expected exit code 0");
        assertEquals("edgar-delta", handlers0.calls.get(0), "edgar-delta: wrong handler routed");
        assertContains(captured0.stdout, "STUB_EDGAR_DELTA_OK", "edgar-delta: stdout should contain handler result");
    }

    private static void testClientRunRoutesWithArg()
    {
        RecordingJobHandlers handlers0 = new RecordingJobHandlers();
        Captured captured0 = runDispatch(new String[] { "client-run", "gp@example.com" }, handlers0);

        assertEquals(0, captured0.exitCode, "client-run: expected exit code 0");
        assertEquals("client-run:gp@example.com", handlers0.calls.get(0), "client-run: clientEmail not passed through");
        assertContains(captured0.stdout, "STUB_CLIENT_RUN_OK:gp@example.com", "client-run: stdout should contain handler result");
    }

    private static void testClientRunMissingArgFailsWithNonzeroExit()
    {
        RecordingJobHandlers handlers0 = new RecordingJobHandlers();
        Captured captured0 = runDispatch(new String[] { "client-run" }, handlers0);

        assertEquals(1, captured0.exitCode, "client-run missing arg: expected nonzero exit code");
        assertEquals(0, handlers0.calls.size(), "client-run missing arg: handler should not be called");
        assertContains(captured0.stderr, "clientEmail", "client-run missing arg: stderr should mention missing clientEmail");
    }

    private static void testNonprofitRefreshRoutes()
    {
        RecordingJobHandlers handlers0 = new RecordingJobHandlers();
        Captured captured0 = runDispatch(new String[] { "nonprofit-refresh" }, handlers0);

        assertEquals(0, captured0.exitCode, "nonprofit-refresh: expected exit code 0");
        assertEquals("nonprofit-refresh", handlers0.calls.get(0), "nonprofit-refresh: wrong handler routed");
        assertContains(captured0.stdout, "STUB_NONPROFIT_REFRESH_OK", "nonprofit-refresh: stdout should contain handler result");
    }

    private static void testProfilePrefetchRoutes()
    {
        RecordingJobHandlers handlers0 = new RecordingJobHandlers();
        Captured captured0 = runDispatch(new String[] { "profile-prefetch" }, handlers0);

        assertEquals(0, captured0.exitCode, "profile-prefetch: expected exit code 0");
        assertEquals("profile-prefetch", handlers0.calls.get(0), "profile-prefetch: wrong handler routed");
        assertContains(captured0.stdout, "STUB_PROFILE_PREFETCH_OK", "profile-prefetch: stdout should contain handler result");
    }

    private static void testUnknownJobFailsWithNonzeroExit()
    {
        RecordingJobHandlers handlers0 = new RecordingJobHandlers();
        Captured captured0 = runDispatch(new String[] { "bogus-job" }, handlers0);

        assertEquals(1, captured0.exitCode, "unknown job: expected nonzero exit code");
        assertEquals(0, handlers0.calls.size(), "unknown job: no handler should be called");
        assertContains(captured0.stderr, "Unknown job", "unknown job: stderr should say Unknown job");
    }

    private static void testNoArgsFailsWithNonzeroExit()
    {
        RecordingJobHandlers handlers0 = new RecordingJobHandlers();
        Captured captured0 = runDispatch(new String[0], handlers0);

        assertEquals(1, captured0.exitCode, "no args: expected nonzero exit code");
        assertEquals(0, handlers0.calls.size(), "no args: no handler should be called");
        assertContains(captured0.stderr, "Usage", "no args: stderr should print usage");
    }

    private static void testHandlerExceptionFailsWithNonzeroExit()
    {
        RecordingJobHandlers handlers0 = new RecordingJobHandlers();
        handlers0.throwOnClientRun = true;
        Captured captured0 = runDispatch(new String[] { "client-run", "gp@example.com" }, handlers0);

        assertEquals(1, captured0.exitCode, "handler exception: expected nonzero exit code");
        assertContains(captured0.stderr, "simulated client-run failure", "handler exception: stderr should contain the exception message");
    }

    private static void testHeadlessFlagIsStrippedByMain()
    {
        // dispatch() itself doesn't strip --headless (that's main()'s job), so
        // exercise the strip logic the same way main() does and confirm the
        // resulting args route correctly.
        String[] rawArgs0 = { "--headless", "adv-refresh" };
        String[] effectiveArgs0 = rawArgs0;
        if (rawArgs0.length > 0 && "--headless".equals(rawArgs0[0]))
        {
            effectiveArgs0 = new String[rawArgs0.length - 1];
            System.arraycopy(rawArgs0, 1, effectiveArgs0, 0, effectiveArgs0.length);
        }

        RecordingJobHandlers handlers0 = new RecordingJobHandlers();
        Captured captured0 = runDispatch(effectiveArgs0, handlers0);

        assertEquals(0, captured0.exitCode, "--headless strip: expected exit code 0");
        assertEquals("adv-refresh", handlers0.calls.get(0), "--headless strip: adv-refresh should still route correctly");
    }

    // -----------------------------------------------------------------------
    // Harness helpers
    // -----------------------------------------------------------------------

    private static class Captured
    {
        int exitCode;
        String stdout;
        String stderr;
    }

    private static Captured runDispatch(String[] args0, ScoutCronRunner.JobHandlers handlers0)
    {
        ByteArrayOutputStream outBytes0 = new ByteArrayOutputStream();
        ByteArrayOutputStream errBytes0 = new ByteArrayOutputStream();

        Captured captured0 = new Captured();
        try (PrintStream out0 = new PrintStream(outBytes0); PrintStream err0 = new PrintStream(errBytes0))
        {
            captured0.exitCode = ScoutCronRunner.dispatch(args0, handlers0, out0, err0);
        }
        captured0.stdout = outBytes0.toString();
        captured0.stderr = errBytes0.toString();
        return captured0;
    }

    private static void assertEquals(int expected0, int actual0, String message0)
    {
        if (expected0 != actual0)
        {
            System.err.println("FAIL: " + message0 + " (expected=" + expected0 + ", actual=" + actual0 + ")");
            failures0++;
        }
    }

    private static void assertEquals(String expected0, String actual0, String message0)
    {
        if (expected0 == null ? actual0 != null : !expected0.equals(actual0))
        {
            System.err.println("FAIL: " + message0 + " (expected=" + expected0 + ", actual=" + actual0 + ")");
            failures0++;
        }
    }

    private static void assertContains(String haystack0, String needle0, String message0)
    {
        if (haystack0 == null || !haystack0.contains(needle0))
        {
            System.err.println("FAIL: " + message0 + " (expected to contain=" + needle0 + ", actual=" + haystack0 + ")");
            failures0++;
        }
    }
}
