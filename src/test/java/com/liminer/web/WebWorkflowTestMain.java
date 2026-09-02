package com.liminer.web;

import com.liminer.core.CRMSchemaConfig;
import com.liminer.core.SessionContext;
import com.liminer.core.UserAccount;

import org.json.JSONObject;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/**
 * Loopback test for WebServer's workflow/job endpoints: starts a server on an ephemeral
 * test port with a fake LoginPort and a fake WorkflowRegistry (instant/slow/throwing
 * handlers only — never constructs or invokes a real processor), then drives it over
 * real HTTP to itself. Prints WEB_WORKFLOW_OK on success; exits 1 on any failure.
 */
public class WebWorkflowTestMain
{
    private static final int TEST_PORT = 7998;
    private static final String BASE_URL = "http://127.0.0.1:" + TEST_PORT;

    public static void main(String[] args) throws Exception
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

        WorkflowRegistry fakeRegistry = new WorkflowRegistry();

        fakeRegistry.add(new WorkflowRegistry.WorkflowInfo(
            "instant",
            "Instant Workflow",
            "Returns immediately.",
            true,
            null,
            (context, params) ->
            {
                System.out.println("MARKER-PRINTED-BY-INSTANT-HANDLER");
                return "done-A";
            }));

        fakeRegistry.add(new WorkflowRegistry.WorkflowInfo(
            "slow",
            "Slow Workflow",
            "Sleeps for a couple seconds.",
            true,
            null,
            (context, params) ->
            {
                Thread.sleep(2000);
                return "done-slow";
            }));

        fakeRegistry.add(new WorkflowRegistry.WorkflowInfo(
            "throwing",
            "Throwing Workflow",
            "Always throws.",
            true,
            null,
            (context, params) ->
            {
                throw new Exception("boom-expected-failure");
            }));

        WebServer server = new WebServer(fakeLogin, fakeRegistry);
        server.start(TEST_PORT);

        try
        {
            int workflowsNoAuthStatus = getStatus("/api/workflows", null);
            check("GET /api/workflows without token -> 401", workflowsNoAuthStatus == 401);

            String loginBody = post("/api/login", "{\"email\":\"test@example.com\"}");
            String token = new JSONObject(loginBody).optString("token", null);
            check("login token non-empty", token != null && token.length() > 0);

            String workflowsBody = getWithAuth("/api/workflows", token);
            check("GET /api/workflows with token lists fake workflows",
                workflowsBody.contains("\"instant\"")
                    && workflowsBody.contains("\"slow\"")
                    && workflowsBody.contains("\"throwing\""));

            // (b) instant workflow run -> poll job until DONE
            String runBody = postWithAuth("/api/workflows/instant/run", "{}", token);
            String jobId = new JSONObject(runBody).optString("jobId", null);
            check("run instant workflow returns jobId", jobId != null && jobId.length() > 0);

            JSONObject job = pollUntilTerminal(jobId, token);
            check("instant job DONE", "DONE".equals(job.optString("status")));
            check("instant job output has done-A", job.optString("output").contains("done-A"));
            check("instant job output has printed marker",
                job.optString("output").contains("MARKER-PRINTED-BY-INSTANT-HANDLER"));

            // (c) slow workflow then immediate second run -> 409, then accepted after completion
            String slowRunBody = postWithAuth("/api/workflows/slow/run", "{}", token);
            String slowJobId = new JSONObject(slowRunBody).optString("jobId", null);
            check("run slow workflow returns jobId", slowJobId != null && slowJobId.length() > 0);

            int secondRunStatus = postStatusWithAuth("/api/workflows/instant/run", "{}", token);
            check("second run while slow job active -> 409", secondRunStatus == 409);

            JSONObject slowJob = pollUntilTerminal(slowJobId, token);
            check("slow job DONE", "DONE".equals(slowJob.optString("status")));

            String afterSlowRunBody = postWithAuth("/api/workflows/instant/run", "{}", token);
            String afterSlowJobId = new JSONObject(afterSlowRunBody).optString("jobId", null);
            check("run accepted after slow job completes", afterSlowJobId != null && afterSlowJobId.length() > 0);
            pollUntilTerminal(afterSlowJobId, token);

            // (d) throwing handler -> job FAILED with exception message
            String throwRunBody = postWithAuth("/api/workflows/throwing/run", "{}", token);
            String throwJobId = new JSONObject(throwRunBody).optString("jobId", null);
            JSONObject throwJob = pollUntilTerminal(throwJobId, token);
            check("throwing job FAILED", "FAILED".equals(throwJob.optString("status")));
            check("throwing job output has exception message",
                throwJob.optString("output").contains("boom-expected-failure"));

            // (e) unknown workflow id -> 404; unknown job id -> 404
            int unknownWorkflowStatus = postStatusWithAuth("/api/workflows/does-not-exist/run", "{}", token);
            check("unknown workflow id -> 404", unknownWorkflowStatus == 404);

            int unknownJobStatus = getStatusWithAuth("/api/jobs/does-not-exist", token);
            check("unknown job id -> 404", unknownJobStatus == 404);

            System.out.println("WEB_WORKFLOW_OK");
        }
        catch (Throwable t)
        {
            System.out.println("TEST FAILED: " + t.getMessage());
            t.printStackTrace();
            System.exit(1);
        }
        finally
        {
            server.stop();
        }
    }

    private static JSONObject pollUntilTerminal(String jobId, String token) throws Exception
    {
        for (int i = 0; i < 100; i++)
        {
            String body = getWithAuth("/api/jobs/" + jobId, token);
            JSONObject job = new JSONObject(body);
            String status = job.optString("status");

            if ("DONE".equals(status) || "FAILED".equals(status))
            {
                return job;
            }

            Thread.sleep(100);
        }

        throw new Exception("job " + jobId + " did not reach a terminal state in time");
    }

    private static void check(String label, boolean condition) throws Exception
    {
        if (!condition)
        {
            throw new Exception("Check failed: " + label);
        }

        System.out.println("OK: " + label);
    }

    private static String getWithAuth(String path, String token) throws Exception
    {
        HttpURLConnection conn = (HttpURLConnection) new URL(BASE_URL + path).openConnection();
        conn.setRequestMethod("GET");
        conn.setRequestProperty("Authorization", "Bearer " + token);
        return readBody(conn);
    }

    private static int getStatusWithAuth(String path, String token) throws Exception
    {
        HttpURLConnection conn = (HttpURLConnection) new URL(BASE_URL + path).openConnection();
        conn.setRequestMethod("GET");
        conn.setRequestProperty("Authorization", "Bearer " + token);
        readBody(conn);
        return conn.getResponseCode();
    }

    private static int getStatus(String path, String token) throws Exception
    {
        HttpURLConnection conn = (HttpURLConnection) new URL(BASE_URL + path).openConnection();
        conn.setRequestMethod("GET");

        if (token != null)
        {
            conn.setRequestProperty("Authorization", "Bearer " + token);
        }

        readBody(conn);
        return conn.getResponseCode();
    }

    private static String post(String path, String jsonBody) throws Exception
    {
        HttpURLConnection conn = openPost(path, jsonBody, null);
        return readBody(conn);
    }

    private static String postWithAuth(String path, String jsonBody, String token) throws Exception
    {
        HttpURLConnection conn = openPost(path, jsonBody, token);
        return readBody(conn);
    }

    private static int postStatusWithAuth(String path, String jsonBody, String token) throws Exception
    {
        HttpURLConnection conn = openPost(path, jsonBody, token);
        readBody(conn);
        return conn.getResponseCode();
    }

    private static HttpURLConnection openPost(String path, String jsonBody, String token) throws Exception
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

        return conn;
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
        try (InputStream is = stream)
        {
            byte[] buf = new byte[4096];
            int n;

            while ((n = is.read(buf)) != -1)
            {
                sb.append(new String(buf, 0, n, StandardCharsets.UTF_8));
            }
        }

        return sb.toString();
    }
}
