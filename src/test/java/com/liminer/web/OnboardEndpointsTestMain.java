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
import java.util.ArrayList;

/**
 * Loopback test for WebServer's onboarding endpoints: starts a server on an ephemeral
 * test port with fake LoginPort and OnboardPort implementations (never touches Google
 * Sheets or OpenAI), then drives it over real HTTP to itself.
 * Prints ONBOARD_ENDPOINTS_OK on success; exits 1 on any failure.
 */
public class OnboardEndpointsTestMain
{
    private static final int TEST_PORT = 7998;
    private static final String BASE_URL = "http://127.0.0.1:" + TEST_PORT;

    private static boolean commitShouldThrow = false;
    private static JSONObject lastCommitSchema = null;

    public static void main(String[] args) throws Exception
    {
        WebServer.LoginPort fakeLogin = email -> { throw new Exception("no such user"); };

        WebServer.OnboardPort fakeOnboard = new WebServer.OnboardPort()
        {
            @Override
            public JSONObject detect(String spreadsheetId, String[] possibleTabNames) throws Exception
            {
                if (possibleTabNames.length == 0)
                {
                    throw new Exception("no tabs found");
                }

                if ("throw-me".equals(spreadsheetId))
                {
                    throw new Exception("simulated detect failure");
                }

                JSONObject schema = new JSONObject();
                schema.put("mainTabName", "Main");
                schema.put("intakeTabName", "Intake");

                JSONObject result = new JSONObject();
                result.put("schema", schema);
                result.put("tabs", new org.json.JSONArray().put("Main").put("Intake"));
                return result;
            }

            @Override
            public SessionContext commit(JSONObject input, JSONObject approvedSchema) throws Exception
            {
                lastCommitSchema = approvedSchema;

                if (commitShouldThrow)
                {
                    throw new Exception("simulated commit failure");
                }

                UserAccount user = new UserAccount(
                    "user_test", input.optString("email", ""), "Test Fund", "config_test",
                    new ArrayList<>(), new ArrayList<>(),
                    "", "", "", "", "", "", "", "", ""
                );

                CRMSchemaConfig config = new CRMSchemaConfig("config_test", "user_test", "TestCRM", "sheet_test");

                return new SessionContext(user, config);
            }

            @Override
            public JSONObject plan(JSONObject input, JSONObject approvedSchema) throws Exception
            {
                JSONObject plan = new JSONObject();
                plan.put("mainExisting", new org.json.JSONArray());
                plan.put("mainToAdd", new org.json.JSONArray().put("Fund Name"));
                plan.put("intakeExisting", new org.json.JSONArray());
                plan.put("intakeToAdd", new org.json.JSONArray().put("Processing Status"));
                plan.put("dividerAction", "append");
                return plan;
            }
        };

        WebServer server = new WebServer(fakeLogin, WorkflowRegistry.buildProductionRegistry(), fakeOnboard);
        server.start(TEST_PORT);

        try
        {
            String validDetectBody = "{"
                + "\"email\":\"gp@example.com\","
                + "\"fundName\":\"Example Fund\","
                + "\"spreadsheetId\":\"sheet123\","
                + "\"possibleTabNames\":\"Main|Intake\""
                + "}";

            String detectResp = post("/api/onboard/detect", validDetectBody);
            check("detect valid -> contains draftId", detectResp.contains("\"draftId\""));
            check("detect valid -> contains schema", detectResp.contains("\"schema\""));
            check("detect valid -> contains tabs", detectResp.contains("\"tabs\""));
            check("detect valid -> contains fields", detectResp.contains("\"fields\""));

            String draftId = new JSONObject(detectResp).optString("draftId", null);
            check("draftId non-empty", draftId != null && draftId.length() > 0);

            String blankEmailBody = "{"
                + "\"email\":\"\","
                + "\"fundName\":\"Example Fund\","
                + "\"spreadsheetId\":\"sheet123\","
                + "\"possibleTabNames\":\"Main|Intake\""
                + "}";
            int blankEmailStatus = postStatus("/api/onboard/detect", blankEmailBody);
            check("detect blank email -> 400", blankEmailStatus == 400);
            check("detect blank email -> body has errors", post("/api/onboard/detect", blankEmailBody).contains("errors"));

            String emptyTabsBody = "{"
                + "\"email\":\"gp@example.com\","
                + "\"fundName\":\"Example Fund\","
                + "\"spreadsheetId\":\"sheet123\","
                + "\"possibleTabNames\":\"\""
                + "}";
            int emptyTabsStatus = postStatus("/api/onboard/detect", emptyTabsBody);
            check("detect empty possibleTabNames -> 400", emptyTabsStatus == 400);

            String throwBody = "{"
                + "\"email\":\"gp@example.com\","
                + "\"fundName\":\"Example Fund\","
                + "\"spreadsheetId\":\"throw-me\","
                + "\"possibleTabNames\":\"Main|Intake\""
                + "}";
            int throwStatus = postStatus("/api/onboard/detect", throwBody);
            check("detect port throws -> 502", throwStatus == 502);

            int unknownDraftStatus = postStatus("/api/onboard/confirm",
                "{\"draftId\":\"does-not-exist\",\"schema\":{\"mainTabName\":\"Main\",\"intakeTabName\":\"Intake\"}}");
            check("confirm unknown draftId -> 404", unknownDraftStatus == 404);

            int blankMainTabStatus = postStatus("/api/onboard/confirm",
                "{\"draftId\":\"" + draftId + "\",\"schema\":{\"mainTabName\":\"\",\"intakeTabName\":\"Intake\"}}");
            check("confirm blank mainTabName -> 400", blankMainTabStatus == 400);

            commitShouldThrow = true;
            int commitThrowsStatus = postStatus("/api/onboard/confirm",
                "{\"draftId\":\"" + draftId + "\",\"schema\":{\"mainTabName\":\"Main\",\"intakeTabName\":\"Intake\"}}");
            check("confirm commit port throws -> 500", commitThrowsStatus == 500);
            commitShouldThrow = false;

            String editedSchema = "{\"mainTabName\":\"EditedMain\",\"intakeTabName\":\"EditedIntake\"}";
            String confirmBody = "{\"draftId\":\"" + draftId + "\",\"schema\":" + editedSchema + "}";
            String confirmResp = post("/api/onboard/confirm", confirmBody);
            check("confirm retry after failure -> 200 with token", confirmResp.contains("\"token\""));

            String token = new JSONObject(confirmResp).optString("token", null);
            check("confirm token non-empty", token != null && token.length() > 0);

            check("commit received EDITED schema, not original",
                lastCommitSchema != null
                    && "EditedMain".equals(lastCommitSchema.optString("mainTabName", null))
                    && "EditedIntake".equals(lastCommitSchema.optString("intakeTabName", null)));

            String sessionBody = getWithAuth("/api/session", token);
            check("GET /api/session with onboarded token", sessionBody.contains("gp@example.com"));

            int reconfirmStatus = postStatus("/api/onboard/confirm", confirmBody);
            check("re-confirming consumed draftId -> 404", reconfirmStatus == 404);

            System.out.println("ONBOARD_ENDPOINTS_OK");
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

    private static void check(String label, boolean condition) throws Exception
    {
        if (!condition)
        {
            throw new Exception("Check failed: " + label);
        }

        System.out.println("OK: " + label);
    }

    private static String get(String path, String authHeader) throws Exception
    {
        HttpURLConnection conn = (HttpURLConnection) new URL(BASE_URL + path).openConnection();
        conn.setRequestMethod("GET");

        if (authHeader != null)
        {
            conn.setRequestProperty("Authorization", authHeader);
        }

        return readBody(conn);
    }

    private static String getWithAuth(String path, String token) throws Exception
    {
        return get(path, "Bearer " + token);
    }

    private static String post(String path, String jsonBody) throws Exception
    {
        HttpURLConnection conn = openPost(path, jsonBody);
        return readBody(conn);
    }

    private static int postStatus(String path, String jsonBody) throws Exception
    {
        HttpURLConnection conn = openPost(path, jsonBody);
        readBody(conn);
        return conn.getResponseCode();
    }

    private static HttpURLConnection openPost(String path, String jsonBody) throws Exception
    {
        HttpURLConnection conn = (HttpURLConnection) new URL(BASE_URL + path).openConnection();
        conn.setRequestMethod("POST");
        conn.setDoOutput(true);
        conn.setRequestProperty("Content-Type", "application/json");

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
