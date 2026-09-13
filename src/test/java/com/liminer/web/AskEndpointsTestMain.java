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
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Loopback test for the /api/ask, /api/ask/{id}/accept and /api/ask/{id}/reject
 * endpoints: starts a server on an ephemeral test port with a fake LoginPort and a
 * fake AskPort that records whether apply() was ever called, then drives it over
 * real HTTP to itself. Confirms the staged-proposal gate -- a write ask never
 * touches the sheet until an explicit accept -- and that ownership/one-shot-apply
 * rules hold. Also re-runs the existing login -> list -> run -> poll-to-DONE round
 * trip on the same server instance to confirm asking a question does not disturb
 * the workflow machinery. Prints ASK_ENDPOINTS_OK on success; exits 1 on any
 * failure.
 */
public class AskEndpointsTestMain
{
    private static final int TEST_PORT = 7994;
    private static final String BASE_URL = "http://127.0.0.1:" + TEST_PORT;

    public static void main(String[] args) throws Exception
    {
        WebServer.LoginPort fakeLogin = email ->
        {
            UserAccount user = new UserAccount(
                "user_" + email, email, "Test Fund", "config_" + email,
                new java.util.ArrayList<>(), new java.util.ArrayList<>(),
                "", "", "", "", "", "", "", "", ""
            );

            CRMSchemaConfig config = new CRMSchemaConfig("config_" + email, "user_" + email, "TestCRM", "sheet_" + email);

            return new SessionContext(user, config);
        };

        WorkflowRegistry fakeRegistry = new WorkflowRegistry();

        fakeRegistry.add(new WorkflowRegistry.WorkflowInfo(
            "instant",
            "Instant Workflow",
            "Returns immediately.",
            true,
            null,
            (context, params) -> "done-A"));

        AtomicInteger applyCallCount = new AtomicInteger(0);

        WebServer.AskPort fakeAskPort = new WebServer.AskPort()
        {
            @Override
            public JSONObject ask(SessionContext context, String prompt) throws Exception
            {
                JSONObject result = new JSONObject();

                if (prompt.startsWith("write:"))
                {
                    result.put("answer", "I will update that field.");
                    JSONArray proposals = new JSONArray();
                    JSONObject change = new JSONObject();
                    change.put("row", 5);
                    change.put("fundName", "Acme Fund");
                    change.put("contactFirstName", "Jane");
                    change.put("column", "Status");
                    change.put("beforeValue", "Old");
                    change.put("afterValue", "New");
                    proposals.put(change);
                    result.put("proposals", proposals);
                }
                else
                {
                    result.put("answer", "Here is the answer to your read-only question.");
                    result.put("proposals", new JSONArray());
                }

                return result;
            }

            @Override
            public JSONObject apply(SessionContext context, JSONArray proposals) throws Exception
            {
                applyCallCount.incrementAndGet();
                JSONObject response = new JSONObject();
                response.put("applied", proposals.length());
                response.put("failed", 0);
                response.put("errors", new JSONArray());
                return response;
            }
        };

        WebServer server = new WebServer(fakeLogin, fakeRegistry, fakeOnboardPort(), fakeBriefPort(), fakeAskPort);
        server.start(TEST_PORT);

        try
        {
            // (a) no bearer token -> 401
            HttpURLConnection noAuthConn = openPost("/api/ask", "{\"prompt\":\"hello\"}", null);
            check("POST /api/ask without token -> 401", noAuthConn.getResponseCode() == 401);

            String tokenA = login("userA@example.com");
            check("login token A non-empty", tokenA != null && tokenA.length() > 0);

            String tokenB = login("userB@example.com");
            check("login token B non-empty", tokenB != null && tokenB.length() > 0);

            // (h) blank prompt -> 400
            HttpURLConnection blankConn = openPost("/api/ask", "{\"prompt\":\"   \"}", tokenA);
            check("blank prompt -> 400", blankConn.getResponseCode() == 400);

            // (b) read-only ask
            String readBody = postWithAuth("/api/ask", "{\"prompt\":\"read: what is the status\"}", tokenA);
            JSONObject readJson = new JSONObject(readBody);
            check("read-only ask has non-empty answer", readJson.optString("answer", "").length() > 0);
            check("read-only ask proposalId is null", readJson.isNull("proposalId"));
            check("read-only ask proposals empty", readJson.optJSONArray("proposals").length() == 0);
            check("apply not called after read-only ask", applyCallCount.get() == 0);

            // (c) write ask
            String writeBody = postWithAuth("/api/ask", "{\"prompt\":\"write: update the status\"}", tokenA);
            JSONObject writeJson = new JSONObject(writeBody);
            String proposalId = writeJson.optString("proposalId", null);
            check("write ask returns non-null proposalId", proposalId != null && proposalId.length() > 0);
            JSONArray proposals = writeJson.optJSONArray("proposals");
            check("write ask returns one proposal", proposals != null && proposals.length() == 1);
            JSONObject firstProposal = proposals.getJSONObject(0);
            java.util.Set<String> keys = firstProposal.keySet();
            check("proposal has exactly the expected keys", keys.size() == 6
                && keys.contains("row") && keys.contains("fundName") && keys.contains("contactFirstName")
                && keys.contains("column") && keys.contains("beforeValue") && keys.contains("afterValue"));
            check("apply not called after write ask (gate holds)", applyCallCount.get() == 0);

            // (f) accept with a DIFFERENT session's token -> 403, apply not called
            HttpURLConnection wrongOwnerConn = openPost("/api/ask/" + proposalId + "/accept", "{}", tokenB);
            check("accept with wrong session token -> 403", wrongOwnerConn.getResponseCode() == 403);
            check("apply not called after wrong-owner accept attempt", applyCallCount.get() == 0);

            // (d) accept with correct token
            String acceptBody = postWithAuth("/api/ask/" + proposalId + "/accept", "{}", tokenA);
            JSONObject acceptJson = new JSONObject(acceptBody);
            check("accept applied count == 1", acceptJson.optInt("applied") == 1);
            check("apply called exactly once", applyCallCount.get() == 1);

            // (e) second accept on same id -> 404
            HttpURLConnection secondAcceptConn = openPost("/api/ask/" + proposalId + "/accept", "{}", tokenA);
            check("second accept on same id -> 404", secondAcceptConn.getResponseCode() == 404);
            check("apply still called exactly once after second accept attempt", applyCallCount.get() == 1);

            // (g) reject flow
            String writeBody2 = postWithAuth("/api/ask", "{\"prompt\":\"write: another change\"}", tokenA);
            JSONObject writeJson2 = new JSONObject(writeBody2);
            String proposalId2 = writeJson2.optString("proposalId", null);
            check("second write ask returns proposalId", proposalId2 != null && proposalId2.length() > 0);

            String rejectBody = postWithAuth("/api/ask/" + proposalId2 + "/reject", "{}", tokenA);
            JSONObject rejectJson = new JSONObject(rejectBody);
            check("reject returns discarded true", rejectJson.optBoolean("discarded", false));

            HttpURLConnection acceptAfterRejectConn = openPost("/api/ask/" + proposalId2 + "/accept", "{}", tokenA);
            check("accept after reject -> 404", acceptAfterRejectConn.getResponseCode() == 404);
            check("apply still called exactly once overall", applyCallCount.get() == 1);

            // (i) existing workflow round trip still works
            String workflowsBody = getWithAuth("/api/workflows", tokenA);
            check("GET /api/workflows lists fake workflow", workflowsBody.contains("\"instant\""));

            String runBody = postWithAuth("/api/workflows/instant/run", "{}", tokenA);
            String jobId = new JSONObject(runBody).optString("jobId", null);
            check("run instant workflow returns jobId", jobId != null && jobId.length() > 0);

            JSONObject job = pollUntilTerminal(jobId, tokenA);
            check("instant job DONE", "DONE".equals(job.optString("status")));
            check("instant job output has done-A", job.optString("output").contains("done-A"));

            System.out.println("ASK_ENDPOINTS_OK");
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

    private static WebServer.OnboardPort fakeOnboardPort()
    {
        return new WebServer.OnboardPort()
        {
            @Override
            public JSONObject detect(String spreadsheetId, String[] possibleTabNames)
            {
                return new JSONObject();
            }

            @Override
            public SessionContext commit(JSONObject input, JSONObject approvedSchema)
            {
                return null;
            }

            @Override
            public JSONObject plan(JSONObject input, JSONObject approvedSchema)
            {
                return new JSONObject();
            }
        };
    }

    private static WebServer.BriefPort fakeBriefPort()
    {
        return new WebServer.BriefPort()
        {
            @Override
            public JSONArray list(SessionContext context)
            {
                return new JSONArray();
            }

            @Override
            public JSONObject get(SessionContext context, int row)
            {
                return null;
            }
        };
    }

    private static String login(String email) throws Exception
    {
        String loginBody = post("/api/login", "{\"email\":\"" + email + "\"}");
        return new JSONObject(loginBody).optString("token", null);
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
