package com.liminer.web;

import com.liminer.core.CRMSchemaConfig;
import com.liminer.core.SessionContext;
import com.liminer.core.UserAccount;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/**
 * Loopback test for the six-process-module dashboard UI (task 0201): checks that the
 * flat 12-card workflow grid has been replaced by process modules in index.html/app.js/
 * styles.css, that the /api/workflows contract carries the processes array the new UI
 * reads, and that the existing login -> list -> run -> poll-to-DONE round trip still
 * works with the new markup in place. Uses a different test port from
 * WebFrontendTestMain (7999) so the two can run back-to-back. Prints
 * PROCESS_MODULE_UI_OK on success; exits 1 on any failure.
 */
public class ProcessModuleFrontendTestMain
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

        // WorkflowRegistry.processes() groups by the six fixed production process ids
        // (task 0200), so a "fake registry with two processes" must assign its members
        // to two of those real ids rather than made-up ones.
        fakeRegistry.add(new WorkflowRegistry.WorkflowInfo(
            "step-a",
            "Step A",
            "First step.",
            true,
            null,
            (context, params) -> "done-A")
            .inProcess("refresh-crm", 0));

        fakeRegistry.add(new WorkflowRegistry.WorkflowInfo(
            "step-b",
            "Step B",
            "Second step.",
            true,
            null,
            (context, params) -> "done-B")
            .inProcess("refresh-crm", 1));

        fakeRegistry.add(new WorkflowRegistry.WorkflowInfo(
            "step-c",
            "Step C",
            "Only step of the other process.",
            true,
            null,
            (context, params) -> "done-C")
            .inProcess("discover", 0));

        fakeRegistry.add(new WorkflowRegistry.WorkflowInfo(
            "instant",
            "Instant Workflow",
            "Returns immediately.",
            true,
            null,
            (context, params) -> "done-instant"));

        WebServer server = new WebServer(fakeLogin, fakeRegistry);
        server.start(TEST_PORT);

        try
        {
            String indexBody = get("/index.html");
            check("GET /index.html -> contains process-grid", indexBody.contains("id=\"process-grid\""));
            check("GET /index.html -> no longer contains workflow-grid", !indexBody.contains("id=\"workflow-grid\""));

            String appJs = get("/app.js");
            check("app.js references renderProcesses", appJs.contains("renderProcesses"));
            check("app.js references btn-run-process", appJs.contains("btn-run-process"));
            check("app.js references process-step-select", appJs.contains("process-step-select"));
            check("app.js references runProcess", appJs.contains("runProcess"));

            String stylesCss = get("/styles.css");
            check("styles.css references .process-card", stylesCss.contains(".process-card"));

            String loginBody = post("/api/login", "{\"email\":\"test@example.com\"}");
            String token = new JSONObject(loginBody).optString("token", null);
            check("login token non-empty", token != null && token.length() > 0);

            String workflowsBody = getWithAuth("/api/workflows", token);
            JSONObject workflowsJson = new JSONObject(workflowsBody);
            JSONArray processesJson = workflowsJson.getJSONArray("processes");

            JSONObject refreshCrm = findProcess(processesJson, "refresh-crm");
            check("refresh-crm process is present", refreshCrm != null);
            JSONArray refreshCrmIds = refreshCrm.getJSONArray("workflowIds");
            check("refresh-crm has two members in registered order", refreshCrmIds.length() == 2
                && "step-a".equals(refreshCrmIds.getString(0))
                && "step-b".equals(refreshCrmIds.getString(1)));

            JSONObject discover = findProcess(processesJson, "discover");
            check("discover process is present", discover != null);
            JSONArray discoverIds = discover.getJSONArray("workflowIds");
            check("discover has one member", discoverIds.length() == 1
                && "step-c".equals(discoverIds.getString(0)));

            String runBody = postWithAuth("/api/workflows/instant/run", "{}", token);
            String jobId = new JSONObject(runBody).optString("jobId", null);
            check("run instant workflow returns jobId", jobId != null && jobId.length() > 0);

            JSONObject job = pollUntilTerminal(jobId, token);
            check("instant job DONE", "DONE".equals(job.optString("status")));
            check("instant job output has done-instant", job.optString("output").contains("done-instant"));

            System.out.println("PROCESS_MODULE_UI_OK");
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

    private static JSONObject findProcess(JSONArray processesJson, String id)
    {
        for (int i = 0; i < processesJson.length(); i++)
        {
            JSONObject proc = processesJson.getJSONObject(i);
            if (id.equals(proc.optString("id")))
            {
                return proc;
            }
        }
        return null;
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

    private static String get(String path) throws Exception
    {
        HttpURLConnection conn = (HttpURLConnection) new URL(BASE_URL + path).openConnection();
        conn.setRequestMethod("GET");
        return readBody(conn);
    }

    private static String getWithAuth(String path, String token) throws Exception
    {
        HttpURLConnection conn = (HttpURLConnection) new URL(BASE_URL + path).openConnection();
        conn.setRequestMethod("GET");
        conn.setRequestProperty("Authorization", "Bearer " + token);
        return readBody(conn);
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
