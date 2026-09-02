package com.liminer.web;

import com.liminer.core.CRMField;
import com.liminer.core.CRMFieldRegistry;
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
import java.util.HashMap;

/**
 * Pure-planner and HTTP loopback test for OnboardService.planColumnAdditions /
 * planFromApprovedSchema and POST /api/onboard/preview. Never touches Google Sheets or
 * OpenAI. Prints ONBOARD_PREVIEW_OK on success; exits 1 on any failure.
 */
public class OnboardPreviewTestMain
{
    private static final int TEST_PORT = 7996;
    private static final String BASE_URL = "http://127.0.0.1:" + TEST_PORT;

    public static void main(String[] args)
    {
        try
        {
            testEmptySheet();
            testFullyProvisionedSheet();
            testPartial();
            testNonMutating();
            testFieldParity();
            testHttp();

            System.out.println("ONBOARD_PREVIEW_OK");
        }
        catch (Throwable t)
        {
            System.out.println("TEST FAILED: " + t.getMessage());
            t.printStackTrace();
            System.exit(1);
        }
    }

    private static String expectedHeader(CRMSchemaConfig config0, CRMField field0)
    {
        String configured0 = config0.getCol(field0.key);
        return configured0 == null || configured0.trim().isEmpty() ? field0.columnName : configured0;
    }

    private static CRMSchemaConfig freshConfig()
    {
        CRMSchemaConfig config0 = new CRMSchemaConfig("config_preview_test", "user_x", "Test Fund", "sheet_x");
        CRMOnboard.setDefaultMainCrmHeaders(config0);
        for (CRMField field0 : CRMFieldRegistry.getIntakeTabFields())
        {
            if (field0.includeInOnboarding)
            {
                config0.setCol(field0.key, field0.columnName);
            }
        }
        return config0;
    }

    private static void testEmptySheet() throws Exception
    {
        CRMSchemaConfig config0 = freshConfig();
        HashMap<String, Integer> mainMap0 = new HashMap<>();
        HashMap<String, Integer> intakeMap0 = new HashMap<>();

        OnboardService.ColumnPlan plan0 = OnboardService.planColumnAdditions(config0, mainMap0, intakeMap0);

        check("empty sheet: mainExisting empty", plan0.mainExisting.isEmpty());
        check("empty sheet: intakeExisting empty", plan0.intakeExisting.isEmpty());
        check("empty sheet: dividerAction == append", "append".equals(plan0.dividerAction));

        for (CRMField field0 : CRMFieldRegistry.getMainHumanFields())
        {
            check("empty sheet: mainToAdd contains human header " + field0.columnName,
                plan0.mainToAdd.contains(config0.getCol(field0.key)));
        }
        for (CRMField field0 : CRMFieldRegistry.getMainMachineFields())
        {
            check("empty sheet: mainToAdd contains machine header " + field0.columnName,
                plan0.mainToAdd.contains(expectedHeader(config0, field0)));
        }
        for (CRMField field0 : CRMFieldRegistry.getIntakeTabFields())
        {
            if (field0.includeInOnboarding)
            {
                check("empty sheet: intakeToAdd contains " + field0.columnName,
                    plan0.intakeToAdd.contains(config0.getCol(field0.key)));
            }
        }
    }

    private static void testFullyProvisionedSheet() throws Exception
    {
        CRMSchemaConfig config0 = freshConfig();
        HashMap<String, Integer> mainMap0 = new HashMap<>();
        HashMap<String, Integer> intakeMap0 = new HashMap<>();

        int col0 = 1;
        for (CRMField field0 : CRMFieldRegistry.getMainHumanFields())
        {
            mainMap0.put(config0.getCol(field0.key), col0++);
        }
        mainMap0.put(CRMFieldRegistry.DIVIDER_HEADER, col0++);
        for (CRMField field0 : CRMFieldRegistry.getMainMachineFields())
        {
            mainMap0.put(expectedHeader(config0, field0), col0++);
        }
        int icol0 = 1;
        for (CRMField field0 : CRMFieldRegistry.getIntakeTabFields())
        {
            if (field0.includeInOnboarding)
            {
                intakeMap0.put(config0.getCol(field0.key), icol0++);
            }
        }

        OnboardService.ColumnPlan plan0 = OnboardService.planColumnAdditions(config0, mainMap0, intakeMap0);

        check("fully provisioned: mainToAdd empty", plan0.mainToAdd.isEmpty());
        check("fully provisioned: intakeToAdd empty", plan0.intakeToAdd.isEmpty());
        check("fully provisioned: mainExisting non-empty", !plan0.mainExisting.isEmpty());
        check("fully provisioned: intakeExisting non-empty", !plan0.intakeExisting.isEmpty());
        check("fully provisioned: dividerAction == present", "present".equals(plan0.dividerAction));
    }

    private static void testPartial() throws Exception
    {
        CRMSchemaConfig config0 = freshConfig();
        HashMap<String, Integer> mainMap0 = new HashMap<>();
        HashMap<String, Integer> intakeMap0 = new HashMap<>();

        ArrayList<CRMField> humanFields0 = CRMFieldRegistry.getMainHumanFields();
        String presentHeader0 = config0.getCol(humanFields0.get(0).key);
        String missingHeader0 = config0.getCol(humanFields0.get(1).key);
        mainMap0.put(presentHeader0, 1);

        OnboardService.ColumnPlan plan0 = OnboardService.planColumnAdditions(config0, mainMap0, intakeMap0);

        check("partial: toAdd contains the missing header", plan0.mainToAdd.contains(missingHeader0));
        check("partial: toAdd does NOT contain the present header", !plan0.mainToAdd.contains(presentHeader0));
        check("partial: existing contains the present header", plan0.mainExisting.contains(presentHeader0));
    }

    private static void testNonMutating() throws Exception
    {
        CRMSchemaConfig config0 = freshConfig();
        HashMap<String, Integer> mainMap0 = new HashMap<>();
        HashMap<String, Integer> intakeMap0 = new HashMap<>();

        CRMField sampleField0 = CRMFieldRegistry.getMainHumanFields().get(0);
        mainMap0.put(config0.getCol(sampleField0.key), 1);

        int mainSizeBefore0 = mainMap0.size();
        int intakeSizeBefore0 = intakeMap0.size();
        String colBefore0 = config0.getCol(sampleField0.key);

        OnboardService.planColumnAdditions(config0, mainMap0, intakeMap0);

        check("non-mutating: mainHeaderMap size unchanged", mainMap0.size() == mainSizeBefore0);
        check("non-mutating: intakeHeaderMap size unchanged", intakeMap0.size() == intakeSizeBefore0);
        check("non-mutating: config.getCol unchanged", colBefore0.equals(config0.getCol(sampleField0.key)));
    }

    private static void testFieldParity() throws Exception
    {
        CRMSchemaConfig config0 = freshConfig();
        HashMap<String, Integer> mainMap0 = new HashMap<>();
        HashMap<String, Integer> intakeMap0 = new HashMap<>();

        OnboardService.ColumnPlan plan0 = OnboardService.planColumnAdditions(config0, mainMap0, intakeMap0);

        int expectedMain0 = CRMFieldRegistry.getMainHumanFields().size()
            + CRMFieldRegistry.getMainMachineFields().size()
            + 1;

        int expectedIntake0 = 0;
        for (CRMField field0 : CRMFieldRegistry.getIntakeTabFields())
        {
            if (field0.includeInOnboarding)
            {
                expectedIntake0++;
            }
        }

        check("field parity: mainToAdd count == human+machine+divider ("
            + expectedMain0 + " vs " + plan0.mainToAdd.size() + ")",
            plan0.mainToAdd.size() == expectedMain0);

        check("field parity: intakeToAdd count == onboarding intake fields ("
            + expectedIntake0 + " vs " + plan0.intakeToAdd.size() + ")",
            plan0.intakeToAdd.size() == expectedIntake0);
    }

    private static void testHttp() throws Exception
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

                JSONObject result = new JSONObject();
                result.put("schema", schema);
                result.put("tabs", new org.json.JSONArray().put("Main").put("Intake"));
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
                if ("throw-me".equals(approvedSchema.optString("mainTabName", "")))
                {
                    throw new Exception("simulated plan failure");
                }

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
            String detectBody = "{"
                + "\"email\":\"gp@example.com\","
                + "\"fundName\":\"Example Fund\","
                + "\"spreadsheetId\":\"sheet123\","
                + "\"possibleTabNames\":\"Main|Intake\""
                + "}";
            String detectResp = post("/api/onboard/detect", detectBody);
            String draftId = new JSONObject(detectResp).optString("draftId", null);
            check("http: draftId non-empty", draftId != null && draftId.length() > 0);

            String previewBody = "{\"draftId\":\"" + draftId
                + "\",\"schema\":{\"mainTabName\":\"Main\",\"intakeTabName\":\"Intake\"}}";
            String previewResp = post("/api/onboard/preview", previewBody);
            check("http: preview 200 has mainToAdd", previewResp.contains("\"mainToAdd\""));

            String previewResp2 = post("/api/onboard/preview", previewBody);
            check("http: preview again (not consumed) has mainToAdd", previewResp2.contains("\"mainToAdd\""));

            String confirmBody = "{\"draftId\":\"" + draftId
                + "\",\"schema\":{\"mainTabName\":\"Main\",\"intakeTabName\":\"Intake\"}}";
            String confirmResp = post("/api/onboard/confirm", confirmBody);
            check("http: confirm 200 has token", confirmResp.contains("\"token\""));

            int unknownStatus0 = postStatus("/api/onboard/preview",
                "{\"draftId\":\"does-not-exist\",\"schema\":{\"mainTabName\":\"Main\",\"intakeTabName\":\"Intake\"}}");
            check("http: preview unknown draftId -> 404", unknownStatus0 == 404);

            String detectResp2 = post("/api/onboard/detect", detectBody);
            String draftId2 = new JSONObject(detectResp2).optString("draftId", null);
            int blankStatus0 = postStatus("/api/onboard/preview",
                "{\"draftId\":\"" + draftId2 + "\",\"schema\":{\"mainTabName\":\"\",\"intakeTabName\":\"Intake\"}}");
            check("http: preview blank mainTabName -> 400", blankStatus0 == 400);

            int throwStatus0 = postStatus("/api/onboard/preview",
                "{\"draftId\":\"" + draftId2 + "\",\"schema\":{\"mainTabName\":\"throw-me\",\"intakeTabName\":\"Intake\"}}");
            check("http: plan port throws -> 502", throwStatus0 == 502);
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
