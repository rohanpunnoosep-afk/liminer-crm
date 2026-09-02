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
 * Loopback test for the static frontend (index.html/app.js/styles.css) served by
 * WebServer: starts a server on an ephemeral test port with a fake LoginPort and a
 * fake WorkflowRegistry, then drives it over real HTTP to itself to check both the
 * static hosting and that the full API round-trip (login -> list -> run -> poll to
 * DONE) still works with the static file handler in place. Prints WEB_FRONTEND_OK on
 * success; exits 1 on any failure.
 */
public class WebFrontendTestMain
{
    private static final int TEST_PORT = 7999;
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
            (context, params) -> "done-A"));

        WebServer server = new WebServer(fakeLogin, fakeRegistry);
        server.start(TEST_PORT);

        try
        {
            String indexBody = get("/");
            check("GET / -> contains workflow-grid", indexBody.contains("workflow-grid"));
            check("GET / -> references app.js", indexBody.contains("app.js"));
            check("GET / -> references styles.css", indexBody.contains("styles.css"));

            String appJs = get("/app.js");
            check("GET /app.js non-empty", appJs.length() > 0);
            check("app.js references /api/login", appJs.contains("/api/login"));
            check("app.js references /api/workflows", appJs.contains("/api/workflows"));
            check("app.js references /api/jobs/", appJs.contains("/api/jobs/"));

            String stylesCss = get("/styles.css");
            check("GET /styles.css non-empty", stylesCss.length() > 0);

            String loginBody = post("/api/login", "{\"email\":\"test@example.com\"}");
            String token = new JSONObject(loginBody).optString("token", null);
            check("login token non-empty", token != null && token.length() > 0);

            String workflowsBody = getWithAuth("/api/workflows", token);
            check("GET /api/workflows lists fake workflow", workflowsBody.contains("\"instant\""));

            String runBody = postWithAuth("/api/workflows/instant/run", "{}", token);
            String jobId = new JSONObject(runBody).optString("jobId", null);
            check("run instant workflow returns jobId", jobId != null && jobId.length() > 0);

            JSONObject job = pollUntilTerminal(jobId, token);
            check("instant job DONE", "DONE".equals(job.optString("status")));
            check("instant job output has done-A", job.optString("output").contains("done-A"));

            System.out.println("WEB_FRONTEND_OK");
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
