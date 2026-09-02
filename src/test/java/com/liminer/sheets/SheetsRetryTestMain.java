package com.liminer.sheets;

import com.google.api.client.googleapis.json.GoogleJsonError;
import com.google.api.client.googleapis.json.GoogleJsonResponseException;
import com.google.api.client.http.HttpHeaders;
import com.google.api.client.http.HttpResponseException;

import java.io.IOException;
import java.net.SocketTimeoutException;
import java.util.ArrayList;
import java.util.List;

/**
 * Offline verification of SheetsApp.executeWithRetry (task 0099).
 * Builds GoogleJsonResponseException fixtures in code — no live Google Sheets calls,
 * no credentials, no network. Sleeps are captured via the RetrySleeper seam.
 */
public class SheetsRetryTestMain
{
    private static int failures0 = 0;
    private static final List<Long> sleeps0 = new ArrayList<>();

    public static void main(String[] args0) throws Exception
    {
        SheetsApp.retrySleeper = millis0 -> sleeps0.add(millis0);

        testSucceedsFirstAttempt();
        testRetriesThen429Succeeds();
        testRateLimit403IsRetried();
        testPermission403FailsFast();
        testNotFound404FailsFast();
        testSocketTimeoutIsRetried();
        testExhaustsAttemptsAndRethrows();
        testBackoffSchedule();
        testAuthFailureFailsFast();

        if (failures0 > 0)
        {
            System.out.println("SHEETS_RETRY_FAILED: " + failures0 + " check(s) failed");
            System.exit(1);
        }

        System.out.println("SHEETS_RETRY_OK");
    }

    // ---------- checks ----------

    private static void testSucceedsFirstAttempt() throws Exception
    {
        sleeps0.clear();
        int[] calls0 = { 0 };

        String result0 = SheetsApp.executeWithRetry(() -> {
            calls0[0]++;
            return "ok";
        });

        check("success returns value", "ok".equals(result0));
        check("success calls once", calls0[0] == 1);
        check("success never sleeps", sleeps0.isEmpty());
    }

    private static void testRetriesThen429Succeeds() throws Exception
    {
        sleeps0.clear();
        int[] calls0 = { 0 };

        String result0 = SheetsApp.executeWithRetry(() -> {
            calls0[0]++;
            if (calls0[0] < 3) throw googleError(429, "rateLimitExceeded");
            return "recovered";
        });

        check("429 eventually succeeds", "recovered".equals(result0));
        check("429 retried twice", calls0[0] == 3);
        check("429 slept twice", sleeps0.size() == 2);
    }

    private static void testRateLimit403IsRetried() throws Exception
    {
        sleeps0.clear();
        int[] calls0 = { 0 };

        String result0 = SheetsApp.executeWithRetry(() -> {
            calls0[0]++;
            if (calls0[0] < 2) throw googleError(403, "userRateLimitExceeded");
            return "recovered";
        });

        check("403 userRateLimitExceeded is retried", "recovered".equals(result0));
        check("403 rate-limit retried once", calls0[0] == 2);
    }

    private static void testPermission403FailsFast()
    {
        sleeps0.clear();
        int[] calls0 = { 0 };

        try
        {
            SheetsApp.executeWithRetry(() -> {
                calls0[0]++;
                throw googleError(403, "forbidden");
            });
            check("403 forbidden throws", false);
        }
        catch (Exception exception0)
        {
            check("403 forbidden propagates", exception0 instanceof GoogleJsonResponseException);
        }

        check("403 forbidden not retried", calls0[0] == 1);
        check("403 forbidden never sleeps", sleeps0.isEmpty());
    }

    private static void testNotFound404FailsFast()
    {
        int[] calls0 = { 0 };

        try
        {
            SheetsApp.executeWithRetry(() -> {
                calls0[0]++;
                throw googleError(404, "notFound");
            });
            check("404 throws", false);
        }
        catch (Exception exception0)
        {
            check("404 propagates", exception0 instanceof GoogleJsonResponseException);
        }

        // tabExists() relies on 404 surfacing on the first attempt.
        check("404 not retried", calls0[0] == 1);
    }

    private static void testSocketTimeoutIsRetried() throws Exception
    {
        sleeps0.clear();
        int[] calls0 = { 0 };

        String result0 = SheetsApp.executeWithRetry(() -> {
            calls0[0]++;
            if (calls0[0] < 3) throw new SocketTimeoutException("Read timed out");
            return "recovered";
        });

        check("socket timeout is retried", "recovered".equals(result0));
        check("socket timeout retried twice", calls0[0] == 3);

        int[] ioCalls0 = { 0 };
        String ioResult0 = SheetsApp.executeWithRetry(() -> {
            ioCalls0[0]++;
            if (ioCalls0[0] < 2) throw new IOException("Connection reset");
            return "recovered";
        });

        check("connection reset is retried", "recovered".equals(ioResult0));
    }

    private static void testExhaustsAttemptsAndRethrows()
    {
        sleeps0.clear();
        int[] calls0 = { 0 };

        try
        {
            SheetsApp.executeWithRetry(() -> {
                calls0[0]++;
                throw googleError(503, "backendError");
            });
            check("exhausted attempts throws", false);
        }
        catch (Exception exception0)
        {
            check("exhausted rethrows original", exception0 instanceof GoogleJsonResponseException);
        }

        check("exhausted uses all attempts", calls0[0] == SheetsApp.RETRY_MAX_ATTEMPTS);
        check("exhausted slept attempts-1 times",
            sleeps0.size() == SheetsApp.RETRY_MAX_ATTEMPTS - 1);
    }

    private static void testBackoffSchedule()
    {
        // Jitter of 1.0 isolates the schedule: 2s, 4s, 8s, 16s, 32s, then capped.
        check("backoff attempt 1", SheetsApp.computeBackoffMs(1, 1.0) == 2000L);
        check("backoff attempt 2", SheetsApp.computeBackoffMs(2, 1.0) == 4000L);
        check("backoff attempt 3", SheetsApp.computeBackoffMs(3, 1.0) == 8000L);
        check("backoff attempt 4", SheetsApp.computeBackoffMs(4, 1.0) == 16000L);
        check("backoff attempt 5", SheetsApp.computeBackoffMs(5, 1.0) == 32000L);
        check("backoff caps at max", SheetsApp.computeBackoffMs(6, 1.0) == SheetsApp.RETRY_MAX_DELAY_MS);
        check("backoff caps at attempt 20", SheetsApp.computeBackoffMs(20, 1.0) == SheetsApp.RETRY_MAX_DELAY_MS);

        // Jitter stays within +/-20%.
        check("jitter low bound", SheetsApp.computeBackoffMs(3, 0.8) == 6400L);
        check("jitter high bound", SheetsApp.computeBackoffMs(3, 1.2) == 9600L);

        check("504 retryable", SheetsApp.isRetryableStatus(504));
        check("429 retryable", SheetsApp.isRetryableStatus(429));
        check("400 not retryable", !SheetsApp.isRetryableStatus(400));
        check("403 not retryable by status alone", !SheetsApp.isRetryableStatus(403));
    }

    private static void testAuthFailureFailsFast()
    {
        int[] calls0 = { 0 };

        try
        {
            SheetsApp.executeWithRetry(() -> {
                calls0[0]++;
                // A non-Google-JSON HttpResponseException (the shape an auth/token
                // failure takes), covering the plain-status branch of isRetryable.
                throw new FixtureHttpResponseException(400);
            });
            check("auth 400 throws", false);
        }
        catch (Exception exception0)
        {
            check("auth 400 propagates", exception0 instanceof HttpResponseException);
        }

        check("auth 400 not retried", calls0[0] == 1);
    }

    // ---------- fixtures ----------

    // HttpResponseException's builder constructor is protected; a subclass reaches it
    // via super(), which is the only way to build one outside the library's package.
    private static class FixtureHttpResponseException extends HttpResponseException
    {
        FixtureHttpResponseException(int statusCode0)
        {
            super(new HttpResponseException.Builder(statusCode0, "fixture", new HttpHeaders()));
        }
    }

    private static GoogleJsonResponseException googleError(int statusCode0, String reason0)
    {
        GoogleJsonError.ErrorInfo errorInfo0 = new GoogleJsonError.ErrorInfo();
        errorInfo0.setReason(reason0);
        errorInfo0.setMessage("fixture " + statusCode0 + " " + reason0);

        GoogleJsonError error0 = new GoogleJsonError();
        error0.setCode(statusCode0);
        error0.setMessage("fixture " + statusCode0 + " " + reason0);
        error0.setErrors(java.util.Collections.singletonList(errorInfo0));

        HttpResponseException.Builder builder0 =
            new HttpResponseException.Builder(statusCode0, "fixture", new HttpHeaders());

        return new GoogleJsonResponseException(builder0, error0);
    }

    private static void check(String label0, boolean condition0)
    {
        if (condition0)
        {
            System.out.println("  ok   " + label0);
            return;
        }

        System.out.println("  FAIL " + label0);
        failures0++;
    }
}
