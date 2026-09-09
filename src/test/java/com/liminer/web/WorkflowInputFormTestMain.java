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
 * Loopback test for the declarative workflow input-form mechanism (task 0202): the
 * registry declares which parameters a workflow needs, the frontend collects and posts
 * them, and the run request body actually carries them to the handler. Prints
 * WORKFLOW_INPUT_FORM_OK on success; exits 1 on any failure.
 */
public class WorkflowInputFormTestMain
{
    private static final int TEST_PORT = 7995;
    private static final String BASE_URL = "http://127.0.0.1:" + TEST_PORT;

    public static void main(String[] args) throws Exception
    {
        // (a) + (b): registry-level input declarations serialize correctly.
        WorkflowRegistry.WorkflowInfo investorBriefPdf =
            WorkflowRegistry.buildProductionRegistry().get("investor-brief-pdf");
        check("investor-brief-pdf exists", investorBriefPdf != null);

        JSONObject briefJson = investorBriefPdf.toJson();
        check("investor-brief-pdf toJson has inputs", briefJson.has("inputs"));

        JSONArray briefInputs = briefJson.getJSONArray("inputs");
        check("investor-brief-pdf has exactly 4 inputs", briefInputs.length() == 4);

        java.util.Set<String> keys = new java.util.HashSet<>();
        for (int i = 0; i < briefInputs.length(); i++)
        {
            keys.add(briefInputs.getJSONObject(i).getString("key"));
        }
        check("input keys match", keys.equals(new java.util.HashSet<>(
            java.util.Arrays.asList("firstName", "lastName", "fundName", "email"))));

        WorkflowRegistry.WorkflowInfo noInputsWorkflow = new WorkflowRegistry.WorkflowInfo(
            "no-inputs",
            "No Inputs",
            "Has no declared inputs.",
            true,
            null,
            (context, params) -> "ok");
        check("workflow with no inputs omits inputs key", !noInputsWorkflow.toJson().has("inputs"));

        // (c): the frontend no longer hardcodes an empty run body, and has an input-form
        // entry point wired up.
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

        fakeRegistry.add(new WorkflowRegistry.WorkflowInfo(
            "echo-inputs",
            "Echo Inputs",
            "Echoes back the params it was given.",
            true,
            null,
            (context, params) -> "params=" + params.toString())
            .withInputs(
                new WorkflowRegistry.InputField("firstName", "First name", "text", false),
                new WorkflowRegistry.InputField("email", "Email", "email", false)));

        WebServer server = new WebServer(fakeLogin, fakeRegistry);
        server.start(TEST_PORT);

        try
        {
            String appJs = get("/app.js");
            check("app.js run POST no longer hardcodes an empty body",
                appJs.contains("body: JSON.stringify(params || {})"));
            check("app.js has input-form entry point", appJs.contains("collectWorkflowInputs")
                && appJs.contains("workflowInputPanel"));

            String loginBody = post("/api/login", "{\"email\":\"test@example.com\"}");
            String token = new JSONObject(loginBody).optString("token", null);
            check("login token non-empty", token != null && token.length() > 0);

            // (e) existing login -> list -> run -> poll-to-DONE round trip still works.
            String workflowsBody = getWithAuth("/api/workflows", token);
            check("GET /api/workflows lists fake workflow", workflowsBody.contains("\"instant\""));

            String runBody = postWithAuth("/api/workflows/instant/run", "{}", token);
            String jobId = new JSONObject(runBody).optString("jobId", null);
            check("run instant workflow returns jobId", jobId != null && jobId.length() > 0);

            JSONObject job = pollUntilTerminal(jobId, token);
            check("instant job DONE", "DONE".equals(job.optString("status")));
            check("instant job output has done-A", job.optString("output").contains("done-A"));

            // (d) collected inputs actually reach the handler over real HTTP.
            String echoRunBody = postWithAuth(
                "/api/workflows/echo-inputs/run",
                "{\"firstName\":\"Ada\",\"email\":\"ada@example.com\"}",
                token);
            String echoJobId = new JSONObject(echoRunBody).optString("jobId", null);
            check("run echo-inputs workflow returns jobId", echoJobId != null && echoJobId.length() > 0);

            JSONObject echoJob = pollUntilTerminal(echoJobId, token);
            check("echo-inputs job DONE", "DONE".equals(echoJob.optString("status")));
            String echoOutput = echoJob.optString("output");
            check("echo-inputs output carries firstName", echoOutput.contains("Ada"));
            check("echo-inputs output carries email", echoOutput.contains("ada@example.com"));

            System.out.println("WORKFLOW_INPUT_FORM_OK");
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
