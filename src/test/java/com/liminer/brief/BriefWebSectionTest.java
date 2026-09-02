package com.liminer.brief;

import com.liminer.core.CRMSchemaConfig;
import com.liminer.core.SessionContext;
import com.liminer.core.UserAccount;
import com.liminer.web.WebServer;
import com.liminer.web.WorkflowRegistry;

import org.json.JSONArray;
import org.json.JSONObject;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/*
 * Offline verification of the web Documents section (task 0161). Never touches Google
 * Sheets: WebServer.BriefPort is swapped for a fake in-memory implementation holding two
 * rows with a stored brief and one row with none, so listing/reading/PDF rendering are
 * exercised without any Sheets read/write.
 */
public class BriefWebSectionTest
{
    private static final int TEST_PORT = 7995;
    private static final String BASE_URL = "http://127.0.0.1:" + TEST_PORT;
    private static WebServer server;
    private static String token;

    @BeforeAll
    static void startServer() throws Exception
    {
        Map<Integer, JSONObject> briefsByRow = new HashMap<>();
        briefsByRow.put(3, brief("Jane", "Doe", "Acme Ventures", "2026-06-01T00:00:00Z"));
        briefsByRow.put(5, brief("John", "Smith", "Beta Fund", "2026-06-10T00:00:00Z"));
        // Row 4 intentionally has no stored brief.

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

        WebServer.OnboardPort fakeOnboard = new WebServer.OnboardPort()
        {
            @Override
            public JSONObject detect(String spreadsheetId, String[] possibleTabNames) throws Exception
            {
                throw new Exception("not used in this test");
            }

            @Override
            public SessionContext commit(JSONObject input, JSONObject approvedSchema) throws Exception
            {
                throw new Exception("not used in this test");
            }

            @Override
            public JSONObject plan(JSONObject input, JSONObject approvedSchema) throws Exception
            {
                throw new Exception("not used in this test");
            }
        };

        WebServer.BriefPort fakeBriefPort = new WebServer.BriefPort()
        {
            @Override
            public JSONArray list(SessionContext context) throws Exception
            {
                JSONArray result = new JSONArray();
                for (Map.Entry<Integer, JSONObject> entry : briefsByRow.entrySet())
                {
                    JSONObject brief = entry.getValue();
                    JSONObject contact = brief.optJSONObject("contactAndFirmProfile");
                    JSONObject summary = new JSONObject();
                    summary.put("rowNumber", entry.getKey());
                    summary.put("contactName", contact.optString("firstName", "") + " " + contact.optString("lastName", ""));
                    summary.put("fundName", contact.optString("fundName", ""));
                    summary.put("asOfDate", brief.optString("asOfDate", ""));
                    summary.put("hasBrief", true);
                    result.put(summary);
                }
                return result;
            }

            @Override
            public JSONObject get(SessionContext context, int row) throws Exception
            {
                return briefsByRow.get(row);
            }
        };

        server = new WebServer(fakeLogin, new WorkflowRegistry(), fakeOnboard, fakeBriefPort);
        server.start(TEST_PORT);

        token = new JSONObject(post("/api/login", "{\"email\":\"test@example.com\"}"))
            .optString("token", null);
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
    void listingBriefsWithoutATokenIsUnauthorized() throws Exception
    {
        check("GET /api/briefs without a token -> 401", statusOf(openGet("/api/briefs", null)) == 401);
    }

    @Test
    void loginIssuesAToken()
    {
        check("login token non-empty", token != null && token.length() > 0);
    }

    @Test
    void onlyRowsWithAStoredBriefAreListed() throws Exception
    {
        JSONArray listedBriefs = new JSONObject(getWithAuth("/api/briefs", token)).optJSONArray("briefs");
        check("GET /api/briefs lists exactly 2 rows", listedBriefs != null && listedBriefs.length() == 2);

        boolean sawRow3 = false, sawRow5 = false, sawRow4 = false;
        for (int i = 0; listedBriefs != null && i < listedBriefs.length(); i++)
        {
            int row = listedBriefs.getJSONObject(i).optInt("rowNumber", -1);
            if (row == 3) sawRow3 = true;
            if (row == 5) sawRow5 = true;
            if (row == 4) sawRow4 = true;
        }
        check("listed rows are exactly {3, 5}", sawRow3 && sawRow5 && !sawRow4);
    }

    @Test
    void readingARowWithNoBriefIsNotFound() throws Exception
    {
        check("GET /api/briefs/4 (no brief) -> 404",
            openGetWithAuth("/api/briefs/4", token).getResponseCode() == 404);
    }

    @Test
    void renderingABriefReturnsAPdf() throws Exception
    {
        HttpURLConnection pdfConn = openGetWithAuth("/api/briefs/3/pdf", token);

        check("GET /api/briefs/3/pdf -> 200", pdfConn.getResponseCode() == 200);
        check("pdf Content-Type is application/pdf", "application/pdf".equals(pdfConn.getContentType()));

        byte[] pdfBytes = readBytes(pdfConn);
        check("pdf body starts with %PDF",
            pdfBytes.length >= 4
                && pdfBytes[0] == '%' && pdfBytes[1] == 'P' && pdfBytes[2] == 'D' && pdfBytes[3] == 'F');
    }

    @Test
    void renderingARowWithNoBriefIsNotFound() throws Exception
    {
        check("GET /api/briefs/4/pdf (no brief) -> 404",
            openGetWithAuth("/api/briefs/4/pdf", token).getResponseCode() == 404);
    }

    @Test
    void theDocumentsSectionIsServedAndTheFrontendStaysSelfContained() throws Exception
    {
        check("GET / contains the Documents section id", get("/").contains("id=\"documentsView\""));

        String appJs = get("/app.js");
        check("app.js has no absolute http:// URL", !appJs.contains("http://"));
        check("app.js has no absolute https:// URL", !appJs.contains("https://"));
        check("app.js has no cdn reference", !appJs.toLowerCase().contains("cdn"));
    }

    private static JSONObject brief(String first, String last, String fund, String asOfDate)
    {
        JSONObject contact = new JSONObject();
        contact.put("firstName", first);
        contact.put("lastName", last);
        contact.put("fundName", fund);
        contact.put("email", first.toLowerCase() + "@" + fund.toLowerCase().replace(" ", "") + ".com");

        JSONObject brief = new JSONObject();
        brief.put("asOfDate", asOfDate);
        brief.put("executiveSummary", "Executive summary for " + first + " " + last + ".");
        brief.put("contactAndFirmProfile", contact);
        brief.put("marketIntelligence", new JSONObject().put("fundingStatus", "Actively investing"));
        brief.put("relationshipSummary", new JSONObject().put("narrativeArc", "Warming relationship."));
        brief.put("callPreparation", new JSONObject().put("talkingPoints", new JSONArray().put("Discuss Fund II timeline.")));
        return brief;
    }

    private static void check(String label, boolean condition)
    {
        assertTrue(condition, label);
    }

    private static String get(String path) throws Exception
    {
        return readBody(openGet(path, null));
    }

    private static String getWithAuth(String path, String token) throws Exception
    {
        return readBody(openGetWithAuth(path, token));
    }

    private static HttpURLConnection openGet(String path, String token) throws Exception
    {
        HttpURLConnection conn = (HttpURLConnection) new URL(BASE_URL + path).openConnection();
        conn.setRequestMethod("GET");
        if (token != null)
        {
            conn.setRequestProperty("Authorization", "Bearer " + token);
        }
        return conn;
    }

    private static HttpURLConnection openGetWithAuth(String path, String token) throws Exception
    {
        return openGet(path, token);
    }

    private static int statusOf(HttpURLConnection conn) throws Exception
    {
        return conn.getResponseCode();
    }

    private static String post(String path, String jsonBody) throws Exception
    {
        HttpURLConnection conn = (HttpURLConnection) new URL(BASE_URL + path).openConnection();
        conn.setRequestMethod("POST");
        conn.setDoOutput(true);
        conn.setRequestProperty("Content-Type", "application/json");
        try (OutputStream os = conn.getOutputStream())
        {
            os.write(jsonBody.getBytes(StandardCharsets.UTF_8));
        }
        return readBody(conn);
    }

    private static String readBody(HttpURLConnection conn) throws Exception
    {
        int status = conn.getResponseCode();
        InputStream stream = status >= 400 ? conn.getErrorStream() : conn.getInputStream();
        if (stream == null) return "";

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

    private static byte[] readBytes(HttpURLConnection conn) throws Exception
    {
        int status = conn.getResponseCode();
        InputStream stream = status >= 400 ? conn.getErrorStream() : conn.getInputStream();
        if (stream == null) return new byte[0];

        java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
        try (InputStream is = stream)
        {
            byte[] buf = new byte[4096];
            int n;
            while ((n = is.read(buf)) != -1)
            {
                baos.write(buf, 0, n);
            }
        }
        return baos.toByteArray();
    }
}
