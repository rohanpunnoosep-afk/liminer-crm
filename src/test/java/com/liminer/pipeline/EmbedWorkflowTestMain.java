package com.liminer.pipeline;

import com.liminer.core.ConnectionPoint;
import com.liminer.core.CRMSchemaConfig;
import com.liminer.core.SessionContext;
import com.liminer.core.UserAccount;
import com.liminer.embed.CanonicalProfile;
import com.liminer.embed.CanonicalProfileBuilder;
import com.liminer.embed.ProfileVectorEncoder;
import com.liminer.embed.ProfileVectorLayout;
import com.liminer.embed.VectorCodec;
import com.liminer.intake.ConnectionPointExtractor;
import com.liminer.sheets.SheetsIOPort;
import com.liminer.web.WorkflowRegistry;

import org.json.JSONArray;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Offline verification of ProfileEmbeddingProcessor (task 0176): a fake SheetsIOPort
 * stands in for the spreadsheet, and stub LlmSeam/EmbedderSeam implementations stand in
 * for OpenAI. No Sheets connection, no OpenAI call, no credentials.
 */
public class EmbedWorkflowTestMain
{
    private static int failures0 = 0;

    // Column layout for the fake sheet (header row 1, data starts row 2).
    private static final int COL_FUND_NAME0 = 1;
    private static final int COL_WEBSITE0 = 2;
    private static final int COL_INTELLIGENCE_JSON0 = 3;
    private static final int COL_MARKET_INTELLIGENCE_JSON0 = 4;
    private static final int COL_INVESTMENT_THESIS0 = 5;
    private static final int COL_PRIOR_BACKED_FUNDS0 = 6;
    private static final int COL_SECTOR_TAGS0 = 7;
    private static final int COL_MICROSECTOR_TAGS0 = 8;
    private static final int COL_TYPE_OF_INVESTOR0 = 9;
    private static final int COL_FUND_LINKEDIN_ABOUT0 = 10;
    private static final int COL_INTERACTION_RECORDS0 = 11;
    private static final int COL_CONNECTION_POINT0 = 12;
    private static final int COL_CONNECTION_POINT_JSON0 = 13;
    private static final int COL_CANONICAL_PROFILE_JSON0 = 14;
    private static final int COL_PROFILE_VECTOR0 = 15;
    private static final int COL_PROFILE_VECTOR_META0 = 16;

    private static final int ROW_NORMAL0 = 2;
    private static final int ROW_SKIP0 = 3;
    private static final int ROW_DOWNGRADE0 = 4;
    private static final int ROW_HUGE0 = 5;

    public static void main(String[] args0)
    {
        try
        {
            runWiringChecks();
            runWorkflowChecks();
        }
        catch (Exception exception0)
        {
            System.out.println("TEST FAILED: " + exception0.getMessage());
            exception0.printStackTrace();
            System.exit(1);
        }

        if (failures0 > 0)
        {
            System.out.println("EMBED_WORKFLOW_FAILED: " + failures0 + " check(s) failed");
            System.exit(1);
        }

        System.out.println("EMBED_WORKFLOW_OK");
    }

    // ---------- wiring checks ----------

    private static void runWiringChecks() throws Exception
    {
        WorkflowRegistry registry0 = WorkflowRegistry.buildProductionRegistry();
        WorkflowRegistry.WorkflowInfo info0 = registry0.get("embed-lps");

        check("embed-lps workflow registered", info0 != null);
        check("embed-lps available", info0 != null && info0.available);
        check("embed-lps handler non-null", info0 != null && info0.handler != null);

        String agentMainSource0 = new String(
            Files.readAllBytes(Paths.get("src/main/java/com/liminer/cli/AgentMain.java")),
            StandardCharsets.UTF_8);

        check("AgentMain banner contains \"embed lps\"", agentMainSource0.contains("embed lps"));
    }

    // ---------- row-level workflow checks ----------

    private static void runWorkflowChecks() throws Exception
    {
        FakeSheetsPort fakePort0 = new FakeSheetsPort();
        SessionContext context0 = buildSessionContext(fakePort0);

        // ---- ROW_NORMAL: fresh row, no stored meta -> full embed. ----
        setHeader(fakePort0, context0);

        String normalProfileResponse0 = buildProfileResponseJson(
            "backed acme | health | seed | us", "FAMILY_OFFICE", 5_000_000.0);
        String normalExpectedHash0 = computeExpectedHash(
            "backed acme | health | seed | us", "FAMILY_OFFICE", 5_000_000.0);

        set(fakePort0, ROW_NORMAL0, COL_FUND_NAME0, "Acme Fund");
        set(fakePort0, ROW_NORMAL0, COL_INVESTMENT_THESIS0, "ROW_NORMAL_MARKER");
        set(fakePort0, ROW_NORMAL0, COL_INTERACTION_RECORDS0,
            buildInteractionRecordsJson("ROW_NORMAL_MARKER", "inbound"));
        set(fakePort0, ROW_NORMAL0, COL_CONNECTION_POINT_JSON0, "");
        set(fakePort0, ROW_NORMAL0, COL_PROFILE_VECTOR_META0, "");
        set(fakePort0, ROW_NORMAL0, COL_PROFILE_VECTOR0, "");
        set(fakePort0, ROW_NORMAL0, COL_CANONICAL_PROFILE_JSON0, "");
        set(fakePort0, ROW_NORMAL0, COL_CONNECTION_POINT0, "");

        // ---- ROW_SKIP: stored meta already matches -> zero embed/connection calls. ----
        String skipProfileResponse0 = buildProfileResponseJson(
            "SHOULD_NOT_EMBED_TEXT", "FOUNDATION", 2_000_000.0);
        String skipExpectedHash0 = computeExpectedHash(
            "SHOULD_NOT_EMBED_TEXT", "FOUNDATION", 2_000_000.0);

        JSONObject skipStoredMeta0 = new JSONObject();
        skipStoredMeta0.put("encoder_version", ProfileVectorLayout.ENCODER_VERSION);
        skipStoredMeta0.put("canonical_hash", skipExpectedHash0);

        String skipExistingVector0 = VectorCodec.encodeBase64(fill(ProfileVectorLayout.TOTAL_DIMS, 0.42f));
        String skipExistingCanonicalJson0 = "{\"placeholder\":\"unchanged\"}";
        String skipExistingConnectionPoint0 = "SHARED_INSTITUTION";
        ConnectionPointExtractor.Result skipStoredConnection0 = new ConnectionPointExtractor.Result();
        skipStoredConnection0.category = ConnectionPoint.SHARED_INSTITUTION;
        skipStoredConnection0.confidence = 0.5;
        String skipExistingConnectionJson0 = skipStoredConnection0.toJson().toString();

        set(fakePort0, ROW_SKIP0, COL_FUND_NAME0, "Skip Fund");
        set(fakePort0, ROW_SKIP0, COL_INVESTMENT_THESIS0, "ROW_SKIP_MARKER");
        set(fakePort0, ROW_SKIP0, COL_INTERACTION_RECORDS0,
            buildInteractionRecordsJson("SHOULD_NOT_EXTRACT_TEXT", "inbound"));
        set(fakePort0, ROW_SKIP0, COL_PROFILE_VECTOR_META0, skipStoredMeta0.toString());
        set(fakePort0, ROW_SKIP0, COL_PROFILE_VECTOR0, skipExistingVector0);
        set(fakePort0, ROW_SKIP0, COL_CANONICAL_PROFILE_JSON0, skipExistingCanonicalJson0);
        set(fakePort0, ROW_SKIP0, COL_CONNECTION_POINT0, skipExistingConnectionPoint0);
        set(fakePort0, ROW_SKIP0, COL_CONNECTION_POINT_JSON0, skipExistingConnectionJson0);

        // ---- ROW_DOWNGRADE: stored STRONG_REFERRAL, fresh extraction UNKNOWN -> never-downgrade. ----
        String downgradeProfileResponse0 = buildProfileResponseJson(
            "backed beta | fintech | growth | eu", "VENTURE_CAPITAL", 10_000_000.0);

        ConnectionPointExtractor.Result downgradeStoredConnection0 = new ConnectionPointExtractor.Result();
        downgradeStoredConnection0.category = ConnectionPoint.STRONG_REFERRAL;
        downgradeStoredConnection0.confidence = 0.9;

        set(fakePort0, ROW_DOWNGRADE0, COL_FUND_NAME0, "Downgrade Fund");
        set(fakePort0, ROW_DOWNGRADE0, COL_INVESTMENT_THESIS0, "ROW_DOWNGRADE_MARKER");
        set(fakePort0, ROW_DOWNGRADE0, COL_INTERACTION_RECORDS0,
            buildInteractionRecordsJson("ROW_DOWNGRADE_MARKER", "inbound"));
        set(fakePort0, ROW_DOWNGRADE0, COL_PROFILE_VECTOR_META0, "");
        set(fakePort0, ROW_DOWNGRADE0, COL_PROFILE_VECTOR0, "");
        set(fakePort0, ROW_DOWNGRADE0, COL_CANONICAL_PROFILE_JSON0, "");
        set(fakePort0, ROW_DOWNGRADE0, COL_CONNECTION_POINT0, "STRONG_REFERRAL");
        set(fakePort0, ROW_DOWNGRADE0, COL_CONNECTION_POINT_JSON0, downgradeStoredConnection0.toJson().toString());

        // ---- ROW_HUGE: an oversized atom -> cell-cap truncation. ----
        StringBuilder hugeAtom0 = new StringBuilder();
        for (int i0 = 0; i0 < 60_000; i0++)
        {
            hugeAtom0.append('x');
        }
        String hugeProfileResponse0 = buildProfileResponseJson(hugeAtom0.toString(), "CORPORATION", 1_000_000.0);

        set(fakePort0, ROW_HUGE0, COL_FUND_NAME0, "Huge Fund");
        set(fakePort0, ROW_HUGE0, COL_INVESTMENT_THESIS0, "ROW_HUGE_MARKER");
        set(fakePort0, ROW_HUGE0, COL_INTERACTION_RECORDS0,
            buildInteractionRecordsJson("ROW_HUGE_MARKER", "inbound"));
        set(fakePort0, ROW_HUGE0, COL_PROFILE_VECTOR_META0, "");
        set(fakePort0, ROW_HUGE0, COL_PROFILE_VECTOR0, "");
        set(fakePort0, ROW_HUGE0, COL_CANONICAL_PROFILE_JSON0, "");
        set(fakePort0, ROW_HUGE0, COL_CONNECTION_POINT0, "");
        set(fakePort0, ROW_HUGE0, COL_CONNECTION_POINT_JSON0, "");

        // ---- stub seams ----

        CanonicalProfileBuilder.LlmSeam profileLlmSeam0 = prompt0 ->
        {
            if (prompt0.contains("ROW_SKIP_MARKER")) return skipProfileResponse0;
            if (prompt0.contains("ROW_DOWNGRADE_MARKER")) return downgradeProfileResponse0;
            if (prompt0.contains("ROW_HUGE_MARKER")) return hugeProfileResponse0;
            return normalProfileResponse0;
        };

        AtomicInteger embedderCallCount0 = new AtomicInteger(0);
        ProfileVectorEncoder.EmbedderSeam embedderSeam0 = (texts0, dims0) ->
        {
            for (String text0 : texts0)
            {
                if (text0.contains("SHOULD_NOT_EMBED_TEXT"))
                {
                    throw new Exception("embedder called for a row that should have been skipped");
                }
            }

            embedderCallCount0.incrementAndGet();

            List<float[]> out0 = new ArrayList<>();
            for (String text0 : texts0)
            {
                out0.add(fill(dims0, 0.1f));
            }
            return out0;
        };

        ConnectionPointExtractor.LlmSeam connectionLlmSeam0 = prompt0 ->
        {
            if (prompt0.contains("SHOULD_NOT_EXTRACT_TEXT"))
            {
                throw new Exception("connection extractor called for a row that should have been skipped");
            }
            if (prompt0.contains("ROW_DOWNGRADE_MARKER"))
            {
                return "{\"category\":\"UNKNOWN\",\"confidence\":0.0,\"referrer_name\":\"\",\"referrer_relation_to_gp\":\"\",\"evidence_quote\":\"\"}";
            }
            if (prompt0.contains("ROW_HUGE_MARKER"))
            {
                return "{\"category\":\"EVENT_ENCOUNTER\",\"confidence\":0.5,\"referrer_name\":\"\",\"referrer_relation_to_gp\":\"\",\"evidence_quote\":\"\"}";
            }
            return "{\"category\":\"INBOUND\",\"confidence\":0.8,\"referrer_name\":\"\",\"referrer_relation_to_gp\":\"\",\"evidence_quote\":\"\"}";
        };

        ProfileEmbeddingProcessor.sheetsPort = fakePort0;
        ProfileEmbeddingProcessor.profileBuilder = new CanonicalProfileBuilder(profileLlmSeam0);
        ProfileEmbeddingProcessor.vectorEncoder = new ProfileVectorEncoder(embedderSeam0);
        ProfileEmbeddingProcessor.connectionExtractor = new ConnectionPointExtractor(connectionLlmSeam0);

        String result0 = ProfileEmbeddingProcessor.embedLpRows(context0, 10);

        check("workflow reports zero failures", result0.contains("Failed: 0"));
        check("workflow reports one skip", result0.contains("Skipped (unchanged): 1"));
        check("workflow reports three embeds", result0.contains("Embedded: 3"));
        check("embedder invoked exactly for the three non-skip rows", embedderCallCount0.get() == 3);

        // ---- column-by-column write checks ----

        java.util.Set<Integer> writtenColumns0 = new java.util.HashSet<>();
        for (WriteRecord write0 : fakePort0.writes)
        {
            writtenColumns0.add(write0.col1);
            check("write to col " + write0.col1 + " is exactly one column wide", write0.widthIsOne());
        }

        java.util.Set<Integer> expectedColumns0 = new java.util.HashSet<>(Arrays.asList(
            COL_CONNECTION_POINT0, COL_CONNECTION_POINT_JSON0, COL_CANONICAL_PROFILE_JSON0,
            COL_PROFILE_VECTOR0, COL_PROFILE_VECTOR_META0));

        check("only the five embedding columns were written", writtenColumns0.equals(expectedColumns0));

        // ---- ROW_NORMAL assertions ----

        String normalConnectionPoint0 = fakePort0.get(ROW_NORMAL0, COL_CONNECTION_POINT0);
        check("ROW_NORMAL connection point is INBOUND", "INBOUND".equals(normalConnectionPoint0));

        String normalVector0 = fakePort0.get(ROW_NORMAL0, COL_PROFILE_VECTOR0);
        float[] decodedNormalVector0 = VectorCodec.decodeBase64(normalVector0);
        check("ROW_NORMAL vector round-trips to TOTAL_DIMS floats",
            decodedNormalVector0.length == ProfileVectorLayout.TOTAL_DIMS);

        JSONObject normalMeta0 = new JSONObject(fakePort0.get(ROW_NORMAL0, COL_PROFILE_VECTOR_META0));
        check("ROW_NORMAL meta carries matching canonical_hash",
            normalExpectedHash0.equals(normalMeta0.optString("canonical_hash", "")));
        check("ROW_NORMAL meta carries encoder_version",
            ProfileVectorLayout.ENCODER_VERSION.equals(normalMeta0.optString("encoder_version", "")));

        // ---- ROW_SKIP assertions: carried through unchanged, zero calls. ----

        check("ROW_SKIP vector unchanged", skipExistingVector0.equals(fakePort0.get(ROW_SKIP0, COL_PROFILE_VECTOR0)));
        check("ROW_SKIP canonical profile json unchanged",
            skipExistingCanonicalJson0.equals(fakePort0.get(ROW_SKIP0, COL_CANONICAL_PROFILE_JSON0)));
        check("ROW_SKIP connection point unchanged",
            skipExistingConnectionPoint0.equals(fakePort0.get(ROW_SKIP0, COL_CONNECTION_POINT0)));
        check("ROW_SKIP connection point json unchanged",
            skipExistingConnectionJson0.equals(fakePort0.get(ROW_SKIP0, COL_CONNECTION_POINT_JSON0)));
        check("ROW_SKIP meta unchanged",
            skipStoredMeta0.toString().equals(fakePort0.get(ROW_SKIP0, COL_PROFILE_VECTOR_META0)));

        // ---- ROW_DOWNGRADE assertions: never-downgrade rule holds end to end. ----

        String downgradeConnectionPoint0 = fakePort0.get(ROW_DOWNGRADE0, COL_CONNECTION_POINT0);
        check("ROW_DOWNGRADE keeps STRONG_REFERRAL despite fresh UNKNOWN",
            "STRONG_REFERRAL".equals(downgradeConnectionPoint0));

        String downgradeConnectionJson0 = fakePort0.get(ROW_DOWNGRADE0, COL_CONNECTION_POINT_JSON0);
        check("ROW_DOWNGRADE connection point json also reflects STRONG_REFERRAL",
            downgradeConnectionJson0.contains("STRONG_REFERRAL"));

        // ---- ROW_HUGE assertion: cell cap. ----

        String hugeCanonicalJson0 = fakePort0.get(ROW_HUGE0, COL_CANONICAL_PROFILE_JSON0);
        check("ROW_HUGE canonical profile json is truncated to at most 49000 chars",
            hugeCanonicalJson0.length() <= 49_000);
        check("ROW_HUGE canonical profile json was actually oversized before truncation",
            hugeCanonicalJson0.length() == 49_000);
    }

    // ---------- fixtures ----------

    private static SessionContext buildSessionContext(FakeSheetsPort fakePort0)
    {
        UserAccount user0 = new UserAccount(
            "user_test", "gp@example.com", "Test Fund", "config_test",
            new ArrayList<>(), new ArrayList<>(),
            "", "", "", "", "", "", "", "", "");

        CRMSchemaConfig config0 = new CRMSchemaConfig("config_test", "user_test", "TestCRM", "sheet_test");

        return new SessionContext(user0, config0);
    }

    private static void setHeader(FakeSheetsPort fakePort0, SessionContext context0)
    {
        setHeaderColumn(fakePort0, context0, "mainTabFundNameCol", COL_FUND_NAME0, "Fund Name");
        setHeaderColumn(fakePort0, context0, "mainTabWebsiteCol", COL_WEBSITE0, "Fund Website");
        setHeaderColumn(fakePort0, context0, "mainTabIntelligenceJsonCol", COL_INTELLIGENCE_JSON0, "Intelligence JSON");
        setHeaderColumn(fakePort0, context0, "mainTabMarketIntelligenceJsonCol", COL_MARKET_INTELLIGENCE_JSON0, "Market Intelligence JSON");
        setHeaderColumn(fakePort0, context0, "mainTabInvestmentThesisCol", COL_INVESTMENT_THESIS0, "Investment Thesis");
        setHeaderColumn(fakePort0, context0, "mainTabPriorBackedFundsCol", COL_PRIOR_BACKED_FUNDS0, "Prior Backed Funds");
        setHeaderColumn(fakePort0, context0, "mainTabSectorTagsCol", COL_SECTOR_TAGS0, "Sector Tags");
        setHeaderColumn(fakePort0, context0, "mainTabMicrosectorTagsCol", COL_MICROSECTOR_TAGS0, "Microsector Tags");
        setHeaderColumn(fakePort0, context0, "mainTabTypeOfInvestorCol", COL_TYPE_OF_INVESTOR0, "Type of Investor");
        setHeaderColumn(fakePort0, context0, "mainTabFundLinkedInAboutCol", COL_FUND_LINKEDIN_ABOUT0, "Fund LinkedIn About");
        setHeaderColumn(fakePort0, context0, "mainTabInteractionRecordsCol", COL_INTERACTION_RECORDS0, "Interaction Records");
        setHeaderColumn(fakePort0, context0, "mainTabConnectionPointCol", COL_CONNECTION_POINT0, "Connection Point");
        setHeaderColumn(fakePort0, context0, "mainTabConnectionPointJsonCol", COL_CONNECTION_POINT_JSON0, "Connection Point JSON");
        setHeaderColumn(fakePort0, context0, "mainTabCanonicalProfileJsonCol", COL_CANONICAL_PROFILE_JSON0, "Canonical Profile JSON");
        setHeaderColumn(fakePort0, context0, "mainTabProfileVectorCol", COL_PROFILE_VECTOR0, "Profile Vector");
        setHeaderColumn(fakePort0, context0, "mainTabProfileVectorMetaCol", COL_PROFILE_VECTOR_META0, "Profile Vector Meta");
    }

    private static void setHeaderColumn(
        FakeSheetsPort fakePort0, SessionContext context0, String key0, int col0, String header0)
    {
        context0.config.setCol(key0, header0);
        set(fakePort0, 1, col0, header0);
    }

    private static void set(FakeSheetsPort fakePort0, int row0, int col0, String value0)
    {
        fakePort0.grid.computeIfAbsent(row0, k0 -> new HashMap<>()).put(col0, value0);
    }

    private static String buildInteractionRecordsJson(String marker0, String direction0)
    {
        JSONObject record0 = new JSONObject();
        record0.put("date", "2024-01-01");
        record0.put("direction", direction0);
        record0.put("type", "EMAIL");
        record0.put("oneSentenceSummary", marker0);
        record0.put("conversationLabel", "");
        record0.put("keyTopicsDiscussed", new JSONArray());
        record0.put("lpQuestionsAsked", new JSONArray());
        record0.put("commitmentsMadeByGP", new JSONArray());
        record0.put("lpSentiment", "");
        record0.put("relationshipSignals", new JSONArray());

        JSONArray records0 = new JSONArray();
        records0.put(record0);

        JSONObject wrapper0 = new JSONObject();
        wrapper0.put("asOfDate", "2024-01-02");
        wrapper0.put("records", records0);

        return wrapper0.toString();
    }

    private static String buildProfileResponseJson(String thesisAtom0, String allocatorType0, Double aumUsd0)
    {
        JSONObject o0 = new JSONObject();
        o0.put("thesis", new JSONArray(java.util.Collections.singletonList(thesisAtom0)));
        o0.put("pastInvestments", new JSONArray());
        o0.put("newInvestmentAreas", new JSONArray());
        o0.put("allocatorType", allocatorType0);
        o0.put("aumUsd", aumUsd0 == null ? JSONObject.NULL : aumUsd0);
        o0.put("capitalAllocatableUsd", JSONObject.NULL);
        o0.put("pastInvestmentAmountUsd", JSONObject.NULL);
        o0.put("timingMonthsSinceLastClose", JSONObject.NULL);
        return o0.toString();
    }

    // Mirrors CanonicalProfileBuilder.build()'s parsing + canonicalize(), so the hash
    // computed here matches exactly what the processor computes from the stub response.
    private static String computeExpectedHash(String thesisAtom0, String allocatorType0, Double aumUsd0)
    {
        CanonicalProfile profile0 = new CanonicalProfile();
        profile0.thesis = new ArrayList<>(java.util.Collections.singletonList(thesisAtom0));
        profile0.allocatorType = allocatorType0;
        profile0.aumUsd = aumUsd0 == null ? Double.NaN : aumUsd0;
        profile0.canonicalize();
        return profile0.canonicalHash();
    }

    private static float[] fill(int dims0, float value0)
    {
        float[] out0 = new float[dims0];
        Arrays.fill(out0, value0);
        return out0;
    }

    private static void check(String label0, boolean condition0)
    {
        if (condition0)
        {
            System.out.println("  ok   " + label0);
            return;
        }

        System.out.println("  FAIL " + label0);
        failures0++;
    }

    // ---------- fake sheet port ----------

    private static class WriteRecord
    {
        final int row1;
        final int col1;
        final String[][] data;

        WriteRecord(int row10, int col10, String[][] data0)
        {
            row1 = row10;
            col1 = col10;
            data = data0;
        }

        boolean widthIsOne()
        {
            for (String[] rowData0 : data)
            {
                if (rowData0.length != 1)
                {
                    return false;
                }
            }
            return true;
        }
    }

    private static class FakeSheetsPort implements SheetsIOPort
    {
        final Map<Integer, Map<Integer, String>> grid = new HashMap<>();
        final List<WriteRecord> writes = new ArrayList<>();

        String get(int row0, int col0)
        {
            Map<Integer, String> rowMap0 = grid.get(row0);
            if (rowMap0 == null) return "";
            String value0 = rowMap0.get(col0);
            return value0 == null ? "" : value0;
        }

        @Override
        public String[][] readRangeMatrix(
            String spreadsheetId0, String tabName0, int row10, int col10, int row20, int col20)
        {
            int rows0 = row20 - row10 + 1;
            int colsN0 = col20 - col10 + 1;
            String[][] out0 = new String[rows0][colsN0];

            for (int r0 = 0; r0 < rows0; r0++)
            {
                Map<Integer, String> rowMap0 = grid.get(row10 + r0);
                for (int c0 = 0; c0 < colsN0; c0++)
                {
                    String value0 = rowMap0 == null ? null : rowMap0.get(col10 + c0);
                    out0[r0][c0] = value0 == null ? "" : value0;
                }
            }

            return out0;
        }

        @Override
        public void updateRangeMatrix(
            String spreadsheetId0, String tabName0, int row10, int col10, String[][] data0)
        {
            writes.add(new WriteRecord(row10, col10, data0));

            for (int r0 = 0; r0 < data0.length; r0++)
            {
                Map<Integer, String> rowMap0 = grid.computeIfAbsent(row10 + r0, k0 -> new HashMap<>());
                for (int c0 = 0; c0 < data0[r0].length; c0++)
                {
                    rowMap0.put(col10 + c0, data0[r0][c0]);
                }
            }
        }
    }
}
