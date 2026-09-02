package com.liminer.web;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

import com.liminer.core.CRMSchemaConfig;
import com.liminer.core.SessionContext;
import com.liminer.core.UserAccount;
import com.liminer.pipeline.LPEnrichmentProcessor;
import com.liminer.sheets.CrmUpdater;
import com.liminer.sheets.SheetsIOPort;

import org.json.JSONObject;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;

/**
 * HTTP loopback test for the workflow plan/confirm dry-run gate (task 0150):
 * WorkflowRegistry.WorkflowPlanner, POST /api/workflows/:id/plan, and the
 * LPEnrichmentProcessor.planEnrichment / CrmUpdater.planCrmUpdate read-only seams.
 * Uses an in-memory FakeSheet (SheetsIOPort) — never touches live Google Sheets.
 * Prints WORKFLOW_PREVIEW_OK on success; exits 1 on any failure.
 */
public class WorkflowPreviewTest
{
    private static final int TEST_PORT = 7999;
    private static final String BASE_URL = "http://127.0.0.1:" + TEST_PORT;

    // ---------- fake in-memory sheet (never live Sheets) ----------

    private static class FakeSheet implements SheetsIOPort
    {
        private final Map<String, String[][]> tabData = new HashMap<>();

        void putTab(String tabName, String[][] data)
        {
            tabData.put(tabName, data);
        }

        @Override
        public String[][] readRangeMatrix(
            String spreadsheetId, String tabName, int row1, int col1, int row2, int col2)
        {
            String[][] data = tabData.getOrDefault(tabName, new String[0][0]);

            int rows = row2 - row1 + 1;
            int cols = col2 - col1 + 1;
            String[][] result = new String[rows][cols];

            for (int r = 0; r < rows; r++)
            {
                int sourceRow = row1 + r - 1;
                String[] sourceRowArray = (sourceRow >= 0 && sourceRow < data.length) ? data[sourceRow] : null;

                for (int c = 0; c < cols; c++)
                {
                    int sourceCol = col1 + c - 1;
                    String value = "";

                    if (sourceRowArray != null && sourceCol >= 0 && sourceCol < sourceRowArray.length
                        && sourceRowArray[sourceCol] != null)
                    {
                        value = sourceRowArray[sourceCol];
                    }

                    result[r][c] = value;
                }
            }

            return result;
        }

        @Override
        public void updateRangeMatrix(
            String spreadsheetId, String tabName, int row1, int col1, String[][] data) throws Exception
        {
            throw new Exception(
                "unexpected spreadsheet write during a plan-only test: tab=" + tabName + " row1=" + row1);
        }
    }

    // ---------- fixture builders ----------

    private static final String[] CRM_HEADERS = new String[]
    {
        "Fund Name", "Contact 1 First Name", "Contact 1 Last Name", "Contact 1 Email Address",
        "Contact 2 First Name", "Contact 2 Last Name", "Contact 2 Email Address",
        "Conversation Status", "Fund Website", "Last Contact Date",
        "Interaction History", "Interaction Records",
        "Type of Investor", "Sector Tags", "Microsector Tags", "Geography",
        "Prior Backed Funds", "Intelligence JSON", "Last Enriched At", "Enrichment Status"
    };

    private static final String[] INTAKE_HEADERS = new String[]
    {
        "Processing Status", "Cleaned Email", "Extracted First Name", "Extracted Last Name",
        "Extracted Fund Name", "Extracted Fund Website", "Conversation Label", "Conversation Summary",
        "Updated CRM", "Needs Review", "Timestamp", "Interaction Record JSON"
    };

    private static CRMSchemaConfig buildTestConfig()
    {
        CRMSchemaConfig config0 = new CRMSchemaConfig("config_preview_test", "user_preview_test", "Test Fund", "sheet_preview_test");

        config0.setCol("mainTabFundNameCol", "Fund Name");
        config0.setCol("mainTabContact1FirstNameCol", "Contact 1 First Name");
        config0.setCol("mainTabContact1LastNameCol", "Contact 1 Last Name");
        config0.setCol("mainTabContact1EmailCol", "Contact 1 Email Address");
        config0.setCol("mainTabContact2FirstNameCol", "Contact 2 First Name");
        config0.setCol("mainTabContact2LastNameCol", "Contact 2 Last Name");
        config0.setCol("mainTabContact2EmailCol", "Contact 2 Email Address");
        config0.setCol("mainTabStatusCol", "Conversation Status");
        config0.setCol("mainTabWebsiteCol", "Fund Website");
        config0.setCol("mainTabLastContactDateCol", "Last Contact Date");
        config0.setCol("mainTabInteractionHistoryCol", "Interaction History");
        config0.setCol("mainTabInteractionRecordsCol", "Interaction Records");
        config0.setCol("mainTabTypeOfInvestorCol", "Type of Investor");
        config0.setCol("mainTabSectorTagsCol", "Sector Tags");
        config0.setCol("mainTabMicrosectorTagsCol", "Microsector Tags");
        config0.setCol("mainTabGeographyCol", "Geography");
        config0.setCol("mainTabPriorBackedFundsCol", "Prior Backed Funds");
        config0.setCol("mainTabIntelligenceJsonCol", "Intelligence JSON");
        config0.setCol("mainTabLastEnrichedAtCol", "Last Enriched At");
        config0.setCol("mainTabEnrichmentStatusCol", "Enrichment Status");

        config0.setCol("intakeTabProcessingStatusCol", "Processing Status");
        config0.setCol("intakeTabCleanedEmailCol", "Cleaned Email");
        config0.setCol("intakeTabExtractedFirstNameCol", "Extracted First Name");
        config0.setCol("intakeTabExtractedLastNameCol", "Extracted Last Name");
        config0.setCol("intakeTabExtractedFundNameCol", "Extracted Fund Name");
        config0.setCol("intakeTabExtractedFundWebsiteCol", "Extracted Fund Website");
        config0.setCol("intakeTabConversationLabelCol", "Conversation Label");
        config0.setCol("intakeTabConversationSummaryCol", "Conversation Summary");
        config0.setCol("intakeTabUpdatedCrmCol", "Updated CRM");
        config0.setCol("intakeTabNeedsReviewCol", "Needs Review");
        config0.setCol("intakeTabTimestampCol", "Timestamp");
        config0.setCol("intakeTabInteractionRecordCol", "Interaction Record JSON");

        return config0;
    }

    private static SessionContext buildTestContext(FakeSheet fakeSheet, boolean blankWebsite)
    {
        CRMSchemaConfig config0 = buildTestConfig();

        String[][] crmData0 = new String[][]
        {
            CRM_HEADERS,
            row(CRM_HEADERS.length,
                "Existing Fund", "", "", "existing@example.com", "", "", "",
                "", blankWebsite ? "" : "https://existing.example", "", "", "",
                "", "", "", "", "", "", "", "")
        };

        String[][] intakeData0 = new String[][]
        {
            INTAKE_HEADERS,
            row(INTAKE_HEADERS.length,
                "PROCESSED", "test@example.com", "", "", "", "", "", "", "", "", "", "")
        };

        fakeSheet.putTab(config0.mainTabName, crmData0);
        fakeSheet.putTab(config0.intakeTabName, intakeData0);

        UserAccount user0 = new UserAccount(
            "user_preview_test", "gp@example.com", "Test Fund", "config_preview_test",
            new ArrayList<>(), new ArrayList<>(),
            "", "", "", "", "", "", "", "", ""
        );

        return new SessionContext(user0, config0);
    }

    private static String[] row(int width, String... values)
    {
        String[] result = new String[width];
        for (int i = 0; i < width; i++)
        {
            result[i] = i < values.length ? values[i] : "";
        }
        return result;
    }

    // ---------- tests ----------

    @Test
    void enrichPlanHttp() throws Exception
    {
        FakeSheet fakeSheet = new FakeSheet();
        SessionContext context0 = buildTestContext(fakeSheet, false);
        LPEnrichmentProcessor.sheetsPort = fakeSheet;
        CrmUpdater.sheetsPort = fakeSheet;

        WebServer server = startServerWithSession(context0);

        try
        {
            String token = loginAndGetToken(context0);

            String planResp = post("/api/workflows/enrich-lps/plan", "{}", token);
            JSONObject plan0 = new JSONObject(planResp);

            check("enrich-lps plan: has eligibleRowCount", plan0.has("eligibleRowCount"));
            check("enrich-lps plan: eligibleRowCount == 1", plan0.optInt("eligibleRowCount", -1) == 1);
            check("enrich-lps plan: no blockingError", !plan0.has("blockingError") || plan0.optString("blockingError", "").isEmpty());

            String planResp2 = post("/api/workflows/enrich-lps/plan", "{}", token);
            check("enrich-lps plan: repeat call identical", planResp.equals(planResp2));
        }
        finally
        {
            server.stop();
        }
    }

    @Test
    void updateCrmPlanNoWriteOnRepeat() throws Exception
    {
        FakeSheet fakeSheet = new FakeSheet();
        SessionContext context0 = buildTestContext(fakeSheet, false);
        LPEnrichmentProcessor.sheetsPort = fakeSheet;
        CrmUpdater.sheetsPort = fakeSheet;

        WebServer server = startServerWithSession(context0);

        try
        {
            String token = loginAndGetToken(context0);

            String planResp = post("/api/workflows/update-crm/plan", "{}", token);
            JSONObject plan0 = new JSONObject(planResp);

            check("update-crm plan: has eligibleRowCount", plan0.has("eligibleRowCount"));
            check("update-crm plan: eligibleRowCount == 1", plan0.optInt("eligibleRowCount", -1) == 1);

            // Calling plan again must return the same result and never write
            // (FakeSheet.updateRangeMatrix throws if a write is attempted).
            String planResp2 = post("/api/workflows/update-crm/plan", "{}", token);
            check("update-crm plan: repeat call identical", planResp.equals(planResp2));
        }
        finally
        {
            server.stop();
        }
    }

    @Test
    void noPlannerWorkflow() throws Exception
    {
        FakeSheet fakeSheet = new FakeSheet();
        SessionContext context0 = buildTestContext(fakeSheet, false);
        LPEnrichmentProcessor.sheetsPort = fakeSheet;
        CrmUpdater.sheetsPort = fakeSheet;

        WebServer server = startServerWithSession(context0);

        try
        {
            String token = loginAndGetToken(context0);

            int status0 = postStatus("/api/workflows/process-intake/plan", "{}", token);
            check("no-planner workflow plan -> 400", status0 == 400);
        }
        finally
        {
            server.stop();
        }
    }

    @Test
    void unknownWorkflow() throws Exception
    {
        FakeSheet fakeSheet = new FakeSheet();
        SessionContext context0 = buildTestContext(fakeSheet, false);
        LPEnrichmentProcessor.sheetsPort = fakeSheet;
        CrmUpdater.sheetsPort = fakeSheet;

        WebServer server = startServerWithSession(context0);

        try
        {
            String token = loginAndGetToken(context0);

            int status0 = postStatus("/api/workflows/does-not-exist/plan", "{}", token);
            check("unknown workflow plan -> 404", status0 == 404);
        }
        finally
        {
            server.stop();
        }
    }

    @Test
    void noAuth() throws Exception
    {
        FakeSheet fakeSheet = new FakeSheet();
        SessionContext context0 = buildTestContext(fakeSheet, false);
        LPEnrichmentProcessor.sheetsPort = fakeSheet;
        CrmUpdater.sheetsPort = fakeSheet;

        WebServer server = startServerWithSession(context0);

        try
        {
            int status0 = postStatus("/api/workflows/enrich-lps/plan", "{}", null);
            check("plan without auth token -> 401", status0 == 401);
        }
        finally
        {
            server.stop();
        }
    }

    @Test
    void blankWebsiteYieldsZeroEligible() throws Exception
    {
        FakeSheet fakeSheet = new FakeSheet();
        SessionContext context0 = buildTestContext(fakeSheet, true);
        LPEnrichmentProcessor.sheetsPort = fakeSheet;
        CrmUpdater.sheetsPort = fakeSheet;

        WebServer server = startServerWithSession(context0);

        try
        {
            String token = loginAndGetToken(context0);

            String planResp = post("/api/workflows/enrich-lps/plan", "{}", token);
            JSONObject plan0 = new JSONObject(planResp);

            check("blank website: eligibleRowCount == 0", plan0.optInt("eligibleRowCount", -1) == 0);
            check("blank website: still 200 (not blockingError)",
                !plan0.has("blockingError") || plan0.optString("blockingError", "").isEmpty());
        }
        finally
        {
            server.stop();
        }
    }

    // ---------- server/http helpers ----------

    private static WebServer startServerWithSession(SessionContext context0)
    {
        WebServer.LoginPort loginPort = email -> context0;
        WebServer server = new WebServer(loginPort, WorkflowRegistry.buildProductionRegistry());
        server.start(TEST_PORT);
        return server;
    }

    private static String loginAndGetToken(SessionContext context0) throws Exception
    {
        HttpURLConnection conn = openPost("/api/login", "{\"email\":\"" + context0.user.email + "\"}");
        String body = readBody(conn);

        if (conn.getResponseCode() != 200)
        {
            throw new Exception("login failed: " + conn.getResponseCode() + " " + body);
        }

        return new JSONObject(body).optString("token", null);
    }

    private static String post(String path, String jsonBody, String token) throws Exception
    {
        HttpURLConnection conn = openPost(path, jsonBody, token);
        return readBody(conn);
    }

    private static int postStatus(String path, String jsonBody, String token) throws Exception
    {
        HttpURLConnection conn = openPost(path, jsonBody, token);
        readBody(conn);
        return conn.getResponseCode();
    }

    private static HttpURLConnection openPost(String path, String jsonBody) throws Exception
    {
        return openPost(path, jsonBody, null);
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

    private static void check(String label, boolean condition)
    {
        assertTrue(condition, label);
    }
}
