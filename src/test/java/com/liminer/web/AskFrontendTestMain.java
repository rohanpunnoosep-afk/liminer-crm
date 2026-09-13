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
 * Loopback test for the "Ask your CRM" frontend added in 0232: starts a server on an
 * ephemeral test port with a fake LoginPort and a fake AskPort that records whether
 * apply() was ever called, then serves the real static files and drives them over
 * real HTTP to itself. Confirms the ask markup and script are present, that the
 * before/after table renders with textContent only (never innerHTML), that the
 * existing dashboard markup survives, and that the staged-proposal gate still holds
 * end to end. Prints ASK_FRONTEND_OK on success; exits 1 on any failure.
 */
public class AskFrontendTestMain
{
    private static final int TEST_PORT = 7993;
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
            String indexBody = get("/index.html");
            check("index.html has askPanel", indexBody.contains("id=\"askPanel\""));
            check("index.html has askPrompt", indexBody.contains("id=\"askPrompt\""));
            check("index.html has btnAsk", indexBody.contains("id=\"btnAsk\""));
            check("index.html has askProposalTable", indexBody.contains("id=\"askProposalTable\""));
            check("index.html has btnAskAccept", indexBody.contains("id=\"btnAskAccept\""));
            check("index.html has btnAskReject", indexBody.contains("id=\"btnAskReject\""));

            String[] headers = {
                "Fund Name", "Contact First Name", "Column Being Changed", "Before Value", "After Value"
            };
            int lastIndex = -1;
            for (String header : headers)
            {
                int idx = indexBody.indexOf(header);
                check("index.html contains header \"" + header + "\"", idx >= 0);
                check("header \"" + header + "\" appears after previous header", idx > lastIndex);
                lastIndex = idx;
            }

            check("index.html has menuView", indexBody.contains("id=\"menuView\""));
            check("index.html has askView", indexBody.contains("id=\"askView\""));
            check("menu has a Liminer Processes entry", indexBody.contains("id=\"btnMenuProcesses\"")
                && indexBody.contains("Liminer Processes"));
            check("menu has an Ask Liminer entry", indexBody.contains("id=\"btnMenuAsk\"")
                && indexBody.contains("Ask Liminer"));
            check("menu has a Documents entry", indexBody.contains("id=\"btnMenuDocuments\""));
            check("dashboard no longer carries its own Documents button",
                !indexBody.contains("id=\"btnShowDocuments\""));
            check("dashboard has a back-to-menu link", indexBody.contains("id=\"btnProcessesBack\""));
            check("ask view has a back-to-menu link", indexBody.contains("id=\"btnAskBack\""));
            check("index.html has the chat transcript", indexBody.contains("id=\"askThread\""));
            check("index.html has the carried-context bar", indexBody.contains("id=\"askContextBar\""));
            check("index.html has the brief follow-up button", indexBody.contains("id=\"btnOutputDocuments\""));

            int askViewStart = indexBody.indexOf("id=\"askView\"");
            int proposalIdx = indexBody.indexOf("id=\"askProposalPanel\"");
            int composerIdx = indexBody.indexOf("id=\"askPrompt\"");
            check("proposal table lives inside the ask view", proposalIdx > askViewStart);
            check("proposal table sits below the chat composer", proposalIdx > composerIdx);

            check("existing dashboard markup survives: process-grid", indexBody.contains("id=\"process-grid\""));
            check("existing dashboard markup survives: workflowPlanPanel", indexBody.contains("id=\"workflowPlanPanel\""));
            check("existing dashboard markup survives: workflowInputPanel", indexBody.contains("id=\"workflowInputPanel\""));
            check("existing dashboard markup survives: output-panel", indexBody.contains("output-panel"));
            check("existing dashboard markup survives: danger-panel", indexBody.contains("danger-panel"));

            String appJs = get("/app.js");
            check("app.js references /api/ask", appJs.contains("/api/ask"));
            check("app.js references /accept", appJs.contains("/accept"));
            check("app.js references /reject", appJs.contains("/reject"));
            check("app.js defines submitAsk", appJs.contains("submitAsk"));

            int askFnStart = appJs.indexOf("// ---- Ask Liminer ----");
            check("app.js has the ask section marker", askFnStart >= 0);
            int askFnEnd = appJs.indexOf("function executeWorkflowRun(wf, params)", askFnStart);
            check("app.js ask section has a bounded end", askFnEnd > askFnStart);
            String askRegion = appJs.substring(askFnStart, askFnEnd);
            check("ask rendering region does not use innerHTML on answer/proposal cells", !askRegion.contains(".innerHTML ="));
            check("ask rendering region uses textContent", askRegion.contains("textContent"));
            check("app.js builds a carried-context prompt", appJs.contains("buildAskPrompt"));
            check("app.js keeps one message per request", appJs.contains("JSON.stringify({ prompt })"));
            check("app.js routes views through showView", appJs.contains("function showView(")
                && appJs.contains("showMenuView") && appJs.contains("showAskView"));
            check("app.js reveals Documents after a brief job", appJs.contains("BRIEF_WORKFLOW_IDS")
                && appJs.contains("outputFollowup"));

            String stylesCss = get("/styles.css");
            check("styles.css has .ask-panel rule", stylesCss.contains(".ask-panel"));

            String tokenA = login("userA@example.com");
            check("login token non-empty", tokenA != null && tokenA.length() > 0);

            String readBody = postWithAuth("/api/ask", "{\"prompt\":\"read: what is the status\"}", tokenA);
            JSONObject readJson = new JSONObject(readBody);
            check("read-only ask proposalId is null", readJson.isNull("proposalId"));
            check("read-only ask proposals empty", readJson.optJSONArray("proposals").length() == 0);
            check("apply not called after read-only ask", applyCallCount.get() == 0);

            String writeBody = postWithAuth("/api/ask", "{\"prompt\":\"write: update the status\"}", tokenA);
            JSONObject writeJson = new JSONObject(writeBody);
            String proposalId = writeJson.optString("proposalId", null);
            check("write ask returns non-null proposalId", proposalId != null && proposalId.length() > 0);
            check("write ask returns one proposal", writeJson.optJSONArray("proposals").length() == 1);
            check("apply not called before accept", applyCallCount.get() == 0);

            String acceptBody = postWithAuth("/api/ask/" + proposalId + "/accept", "{}", tokenA);
            JSONObject acceptJson = new JSONObject(acceptBody);
            check("accept applied count == 1", acceptJson.optInt("applied") == 1);
            check("apply called exactly once", applyCallCount.get() == 1);

            System.out.println("ASK_FRONTEND_OK");
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
