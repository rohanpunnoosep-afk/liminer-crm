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
import java.util.ArrayList;

/**
 * Loopback test for the onboarding wizard frontend (index.html/app.js/styles.css)
 * served by WebServer: starts a server on an ephemeral test port with fake LoginPort
 * and OnboardPort implementations (never touching Google Sheets or OpenAI), then
 * drives it over real HTTP to itself to check both the static hosting of the wizard
 * markup/script and the full detect -> preview -> confirm -> session round trip.
 * Prints ONBOARD_FRONTEND_OK on success; exits 1 on any failure.
 */
public class OnboardFrontendTestMain
{
    private static final int TEST_PORT = 7997;
    private static final String BASE_URL = "http://127.0.0.1:" + TEST_PORT;

    public static void main(String[] args) throws Exception
    {
        WebServer.LoginPort fakeLogin = email -> { throw new Exception("no such user"); };

        WebServer.OnboardPort fakeOnboard = new WebServer.OnboardPort()
        {
            @Override
            public JSONObject detect(String spreadsheetId, String[] possibleTabNames) throws Exception
            {
                JSONObject schema = new JSONObject();
                schema.put("mainTabName", "Main");
                schema.put("intakeTabName", "Intake");
                schema.put("mainTabMappings", new JSONObject().put("fundName", "Fund Name"));
                schema.put("intakeTabMappings", new JSONObject());

                JSONObject mainTab = new JSONObject();
                mainTab.put("tabName", "Main");
                mainTab.put("headers", new JSONArray().put("Fund Name").put("Stage"));

                JSONObject intakeTab = new JSONObject();
                intakeTab.put("tabName", "Intake");
                intakeTab.put("headers", new JSONArray().put("Processing Status"));

                JSONObject result = new JSONObject();
                result.put("schema", schema);
                result.put("tabs", new JSONArray().put(mainTab).put(intakeTab));
                return result;
            }

            @Override
            public SessionContext commit(JSONObject input, JSONObject approvedSchema) throws Exception
            {
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
                plan.put("mainExisting", new JSONArray());
                plan.put("mainToAdd", new JSONArray().put("Fund Name"));
                plan.put("intakeExisting", new JSONArray());
                plan.put("intakeToAdd", new JSONArray().put("Processing Status"));
                plan.put("dividerAction", "append");
                return plan;
            }
        };

        WebServer server = new WebServer(fakeLogin, WorkflowRegistry.buildProductionRegistry(), fakeOnboard);
        server.start(TEST_PORT);

        try
        {
            String indexBody = get("/");
            check("GET / -> contains onboard-wizard", indexBody.contains("onboard-wizard"));
            check("GET / -> contains onboardStep1", indexBody.contains("onboardStep1"));
            check("GET / -> contains onboardStep2", indexBody.contains("onboardStep2"));
            check("GET / -> contains onboardStep3", indexBody.contains("onboardStep3"));
            check("GET / -> contains onboardStep4", indexBody.contains("onboardStep4"));
            check("GET / -> contains obReviewGrid", indexBody.contains("obReviewGrid"));
            check("GET / -> contains obPreviewMain", indexBody.contains("obPreviewMain"));
            check("GET / -> contains btnShowOnboard", indexBody.contains("btnShowOnboard"));

            String appJs = get("/app.js");
            check("app.js references /api/onboard/detect", appJs.contains("/api/onboard/detect"));
            check("app.js references /api/onboard/preview", appJs.contains("/api/onboard/preview"));
            check("app.js references /api/onboard/confirm", appJs.contains("/api/onboard/confirm"));
            check("app.js contains showOnboardView", appJs.contains("showOnboardView"));
            check("app.js has no absolute http:// URL", !appJs.contains("http://"));
            check("app.js has no absolute https:// URL", !appJs.contains("https://"));
            check("app.js has no cdn reference", !appJs.toLowerCase().contains("cdn"));

            String stylesCss = get("/styles.css");
            check("GET /styles.css non-empty", stylesCss.length() > 0);

            String detectBody = "{"
                + "\"email\":\"gp@example.com\","
                + "\"fundName\":\"Example Fund\","
                + "\"spreadsheetId\":\"sheet123\","
                + "\"possibleTabNames\":\"Main|Intake\""
                + "}";
            String detectResp = post("/api/onboard/detect", detectBody);
            check("detect -> contains draftId", detectResp.contains("\"draftId\""));
            check("detect -> contains tabs with tabName", detectResp.contains("\"tabName\""));
            check("detect -> contains fields", detectResp.contains("\"fields\""));

            String draftId = new JSONObject(detectResp).optString("draftId", null);
            check("draftId non-empty", draftId != null && draftId.length() > 0);

            String schema = "{\"mainTabName\":\"Main\",\"intakeTabName\":\"Intake\"}";

            String previewResp = post("/api/onboard/preview",
                "{\"draftId\":\"" + draftId + "\",\"schema\":" + schema + "}");
            check("preview -> contains mainToAdd", previewResp.contains("\"mainToAdd\""));
            check("preview -> contains dividerAction", previewResp.contains("\"dividerAction\""));

            String confirmResp = post("/api/onboard/confirm",
                "{\"draftId\":\"" + draftId + "\",\"schema\":" + schema + "}");
            check("confirm -> contains token", confirmResp.contains("\"token\""));

            String token = new JSONObject(confirmResp).optString("token", null);
            check("confirm token non-empty", token != null && token.length() > 0);

            String sessionBody = getWithAuth("/api/session", token);
            check("GET /api/session with onboarded token -> gp@example.com", sessionBody.contains("gp@example.com"));

            System.out.println("ONBOARD_FRONTEND_OK");
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
        HttpURLConnection conn = openPost(path, jsonBody);
        return readBody(conn);
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
