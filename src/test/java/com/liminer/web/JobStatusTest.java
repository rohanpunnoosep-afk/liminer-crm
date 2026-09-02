package com.liminer.web;

import com.liminer.core.CRMSchemaConfig;
import com.liminer.core.SessionContext;
import com.liminer.core.UserAccount;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/**
 * Loopback test for WebServer job status classification (DONE/NOOP/FAILED): starts a
 * server on an ephemeral test port with a fake LoginPort and a WorkflowRegistry of fake
 * workflows returning canned strings, then drives it over real HTTP to itself. Never
 * touches Google Sheets or OpenAI.
 */
public class JobStatusTest
{
    private static final int TEST_PORT = 7998;
    private static final String BASE_URL = "http://127.0.0.1:" + TEST_PORT;
    private static WebServer server;
    private static String token;

    @BeforeAll
    static void startServer() throws Exception
    {
        WebServer.LoginPort fakeLogin = email ->
        {
            if ("test@example.com".equals(email))
            {
                UserAccount user = new UserAccount(
                    "user_test", email, "Test Fund", "config_test",
                    new java.util.ArrayList<>(), new java.util.ArrayList<>(),
                    "", "", "", "", "", "", "", "", ""
                );

                CRMSchemaConfig config = new CRMSchemaConfig("config_test", "user_test", "TestCRM", "sheet_test");

                return new SessionContext(user, config);
            }

            throw new Exception("no such user");
        };

        WorkflowRegistry registry = new WorkflowRegistry();

        registry.add(new WorkflowRegistry.WorkflowInfo(
            "fails-error",
            "Fails Error",
            "Returns an ERROR: string.",
            true,
            null,
            (context, params) -> "ERROR: something broke"));

        registry.add(new WorkflowRegistry.WorkflowInfo(
            "noop-no-rows",
            "Noop No Rows",
            "Returns a no-eligible-rows string.",
            true,
            null,
            (context, params) -> "LP enrichment complete. No eligible rows found."));

        registry.add(new WorkflowRegistry.WorkflowInfo(
            "done-updated",
            "Done Updated",
            "Returns a real success string.",
            true,
            null,
            (context, params) -> "Updated 12 rows."));

        registry.add(new WorkflowRegistry.WorkflowInfo(
            "throws",
            "Throws",
            "Throws an exception.",
            true,
            null,
            (context, params) -> { throw new Exception("boom"); }));

        server = new WebServer(fakeLogin, registry);
        server.start(TEST_PORT);

        token = extractJsonField(post("/api/login", "{\"email\":\"test@example.com\"}"), "token");
    }

    @AfterAll
    static void stopServer()
    {
        if (server != null)
        {
            server.stop();
        }
    }

    @Test
    void loginIssuesAToken()
    {
        check("token non-empty", token != null && token.length() > 0);
    }

    @Test
    void workflowReturningAnErrorStringIsFailed() throws Exception
    {
        checkJob(token, "fails-error", "FAILED");
    }

    @Test
    void workflowFindingNoEligibleRowsIsNoop() throws Exception
    {
        checkJob(token, "noop-no-rows", "NOOP");
    }

    @Test
    void workflowThatUpdatedRowsIsDone() throws Exception
    {
        checkJob(token, "done-updated", "DONE");
    }

    @Test
    void workflowThrowingAnExceptionIsFailed() throws Exception
    {
        checkJob(token, "throws", "FAILED");
    }

    private static void checkJob(String token, String workflowId, String expectedStatus) throws Exception
    {
        String runBody = post("/api/workflows/" + workflowId + "/run", "{}", token);
        String jobId = extractJsonField(runBody, "jobId");
        check(workflowId + " jobId non-empty", jobId != null && jobId.length() > 0);

        String jobJson = null;

        for (int i = 0; i < 100; i++)
        {
            jobJson = getWithAuth("/api/jobs/" + jobId, token);
            String status = extractJsonField(jobJson, "status");

            if ("DONE".equals(status) || "NOOP".equals(status) || "FAILED".equals(status))
            {
                break;
            }

            Thread.sleep(50);
        }

        String status = extractJsonField(jobJson, "status");
        check(workflowId + " status -> " + expectedStatus, expectedStatus.equals(status));

        String summary = extractJsonField(jobJson, "summary");
        check(workflowId + " summary non-empty", summary != null && summary.length() > 0);
    }

    private static void check(String label, boolean condition)
    {
        assertTrue(condition, label);
    }

    private static String post(String path, String jsonBody) throws Exception
    {
        return post(path, jsonBody, null);
    }

    private static String post(String path, String jsonBody, String token) throws Exception
    {
        HttpURLConnection conn = (HttpURLConnection) new URL(BASE_URL + path).openConnection();
        conn.setRequestMethod("POST");
        conn.setDoOutput(true);
        conn.setRequestProperty("Content-Type", "application/json");

        if (token != null)
        {
            conn.setRequestProperty("Authorization", "Bearer " + token);
        }

        try (OutputStream os = conn.getOutputStream())
        {
            os.write(jsonBody.getBytes(StandardCharsets.UTF_8));
        }

        return readBody(conn);
    }

    private static String getWithAuth(String path, String token) throws Exception
    {
        HttpURLConnection conn = (HttpURLConnection) new URL(BASE_URL + path).openConnection();
        conn.setRequestMethod("GET");
        conn.setRequestProperty("Authorization", "Bearer " + token);
        return readBody(conn);
    }

    private static String readBody(HttpURLConnection conn) throws Exception
    {
        int status = conn.getResponseCode();
        InputStream stream = status >= 400 ? conn.getErrorStream() : conn.getInputStream();

        if (stream == null)
        {
            return "";
        }

        StringBuilder sb = new StringBuilder();

        try (InputStream in = stream)
        {
            byte[] buf = new byte[4096];
            int n;

            while ((n = in.read(buf)) != -1)
            {
                sb.append(new String(buf, 0, n, StandardCharsets.UTF_8));
            }
        }

        return sb.toString();
    }

    private static String extractJsonField(String json, String field)
    {
        if (json == null)
        {
            return null;
        }

        String needle = "\"" + field + "\":\"";
        int idx = json.indexOf(needle);

        if (idx == -1)
        {
            return null;
        }

        int start = idx + needle.length();
        int end = json.indexOf("\"", start);

        if (end == -1)
        {
            return null;
        }

        return json.substring(start, end);
    }
}
