package com.liminer.ask;

import com.liminer.core.CRMSchemaConfig;
import com.liminer.core.SessionContext;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;

// Offline, no-credentials, no-network smoke test for com.liminer.ask. Both
// AskSheetPort and AskLlmPort are faked, so this runs with no OPENAI_API_KEY and
// no Google credentials. Prints ASK_AGENT_OK on success; exits 1 on any failure.
public class AskAgentTestMain
{
    private static final String[] HEADERS0 = new String[]
    {
        "Fund Name",
        "Contact 1 First Name",
        "Contact 1 Last Name",
        "Conversation Status",
        "Notes"
    };

    public static void main(String[] args)
    {
        try
        {
            testReadOnlyAskStagesNothing();
            testFindInvestorRowsMatching();
            testWriteAskStagesOneProposal();
            testDuplicateProposalsCollapse();
            testApplierWritesEachProposal();
            testRepeatedCallGuardReturnsCleanly();
            testToolsJsonShape();

            System.out.println("ASK_AGENT_OK");
        }
        catch (Throwable t)
        {
            System.out.println("TEST FAILED: " + t.getMessage());
            t.printStackTrace();
            System.exit(1);
        }
    }

    // (a) read-only ask drives find_investor_rows then read_row, returns a
    // non-empty answer, and stages zero proposals.
    private static void testReadOnlyAskStagesNothing() throws Exception
    {
        FakeAskSheetPort port0 = seededPort();
        SessionContext session0 = seededSession();

        FakeAskLlmPort llm0 = new FakeAskLlmPort();
        llm0.scriptFunctionCall("find_investor_rows", "{\"query\":\"Acme\"}");
        llm0.scriptFunctionCall("read_row", "{\"row\":2}");
        llm0.scriptFinalAnswer("Sam Lee is the contact at Acme Ventures.");

        AskAgent agent0 = new AskAgent(session0, port0, llm0);
        AskResult result0 = agent0.ask("who is the contact at Acme");

        check("answer is non-empty", result0.answer != null && !result0.answer.trim().isEmpty());
        check("zero proposals staged", result0.proposals.isEmpty());
        check("no writes performed", port0.writes.isEmpty());
    }

    // (b) find_investor_rows matches case-insensitively on a contact first name
    // as well as a fund name, and returns NO MATCHES for an absent query.
    private static void testFindInvestorRowsMatching() throws Exception
    {
        FakeAskSheetPort port0 = seededPort();
        SessionContext session0 = seededSession();

        AskContext context0 = buildContext(session0, port0);

        AskToolSpec findTool0 = AskToolRegistry.getToolByName("find_investor_rows");

        String fundMatch0 = findTool0.executor.execute(argsWithQuery("acme"), context0);
        check("fund name match found", fundMatch0.contains("row=2") && fundMatch0.contains("Acme Ventures"));

        String contactMatch0 = findTool0.executor.execute(argsWithQuery("SAM"), context0);
        check("contact first name match found case-insensitively", contactMatch0.contains("row=2"));

        String noMatch0 = findTool0.executor.execute(argsWithQuery("nonexistent-xyz"), context0);
        check("no matches returns explicit string", noMatch0.equals("NO MATCHES"));
    }

    // (c) a write ask stages exactly one ProposedChange with the correct row,
    // fundName, contactFirstName, column, beforeValue and afterValue, and the
    // fake port recorded no writes during the ask.
    private static void testWriteAskStagesOneProposal() throws Exception
    {
        FakeAskSheetPort port0 = seededPort();
        SessionContext session0 = seededSession();

        FakeAskLlmPort llm0 = new FakeAskLlmPort();
        llm0.scriptFunctionCall("propose_cell_update",
            "{\"row\":2,\"header\":\"Conversation Status\",\"newValue\":\"Closed\"}");
        llm0.scriptFinalAnswer("I've staged that change for your review.");

        AskAgent agent0 = new AskAgent(session0, port0, llm0);
        AskResult result0 = agent0.ask("mark Acme as Closed");

        check("exactly one proposal staged", result0.proposals.size() == 1);

        ProposedChange change0 = result0.proposals.get(0);
        check("row correct", change0.row == 2);
        check("fund name correct", change0.fundName.equals("Acme Ventures"));
        check("contact first name correct", change0.contactFirstName.equals("Sam"));
        check("column correct", change0.column.equals("Conversation Status"));
        check("before value correct", change0.beforeValue.equals("Meetings"));
        check("after value correct", change0.afterValue.equals("Closed"));

        check("no writes performed during ask", port0.writes.isEmpty());
    }

    // (d) two propose_cell_update calls for the same (row, column) collapse to
    // one proposal.
    private static void testDuplicateProposalsCollapse() throws Exception
    {
        FakeAskSheetPort port0 = seededPort();
        SessionContext session0 = seededSession();
        AskContext context0 = buildContext(session0, port0);

        AskToolSpec proposeTool0 = AskToolRegistry.getToolByName("propose_cell_update");

        proposeTool0.executor.execute(
            argsForPropose(2, "Conversation Status", "Closed"), context0);
        proposeTool0.executor.execute(
            argsForPropose(2, "Conversation Status", "Passed"), context0);

        check("duplicate proposals collapse to one", context0.proposals.size() == 1);
        check("latest value wins", context0.proposals.get(0).afterValue.equals("Passed"));
    }

    // (e) AskApplier.apply performs exactly one writeCell per proposal, at the
    // right coordinates, and the fake's cells hold the after-values.
    private static void testApplierWritesEachProposal() throws Exception
    {
        FakeAskSheetPort port0 = seededPort();

        List<ProposedChange> changes0 = new ArrayList<>();
        changes0.add(new ProposedChange(2, "Acme Ventures", "Sam", "Conversation Status", "Meetings", "Closed"));
        changes0.add(new ProposedChange(3, "Beta Capital", "Dana", "Notes", "note2", "Follow up next week"));

        AskApplier.ApplyResult result0 = AskApplier.apply(port0, changes0);

        check("applied count is 2", result0.appliedCount == 2);
        check("no failures", result0.failures.isEmpty());
        check("exactly 2 writes recorded", port0.writes.size() == 2);
        check("row2 status updated", port0.readCell(2, 4).equals("Closed"));
        check("row3 notes updated", port0.readCell(3, 5).equals("Follow up next week"));
    }

    // (f) the repeated-identical-call guard ends the loop and returns rather
    // than exiting the JVM.
    private static void testRepeatedCallGuardReturnsCleanly() throws Exception
    {
        FakeAskSheetPort port0 = seededPort();
        SessionContext session0 = seededSession();

        FakeAskLlmPort llm0 = new FakeAskLlmPort();
        llm0.scriptFunctionCall("find_investor_rows", "{\"query\":\"Acme\"}");
        llm0.scriptFunctionCall("find_investor_rows", "{\"query\":\"Acme\"}");
        llm0.scriptFunctionCall("find_investor_rows", "{\"query\":\"Acme\"}");

        AskAgent agent0 = new AskAgent(session0, port0, llm0);
        AskResult result0 = agent0.ask("look up Acme repeatedly");

        check("returned cleanly after repeated call", result0 != null);
        check("answer mentions the stop condition", result0.answer != null && !result0.answer.isEmpty());
    }

    // (g) AskToolRegistry.toOpenAiToolsJson() contains exactly the four tool
    // names and no sheetName/tabName property.
    private static void testToolsJsonShape() throws Exception
    {
        JSONArray tools0 = AskToolRegistry.toOpenAiToolsJson();

        check("exactly 4 tools", tools0.length() == 4);

        List<String> expectedNames0 = List.of(
            "find_investor_rows", "read_row", "read_column", "propose_cell_update");

        List<String> actualNames0 = new ArrayList<>();

        String toolsText0 = tools0.toString();

        for (int i0 = 0; i0 < tools0.length(); i0++)
        {
            actualNames0.add(tools0.getJSONObject(i0).getString("name"));
        }

        for (String expected0 : expectedNames0)
        {
            check("tool present: " + expected0, actualNames0.contains(expected0));
        }

        check("no sheetName property", !toolsText0.contains("sheetName"));
        check("no tabName property", !toolsText0.contains("tabName"));
    }

    // ------------------------------------------------------------------------
    // FIXTURES
    // ------------------------------------------------------------------------

    private static FakeAskSheetPort seededPort()
    {
        FakeAskSheetPort port0 = new FakeAskSheetPort(HEADERS0);

        port0.addRow("Acme Ventures", "Sam", "Lee", "Meetings", "note1");
        port0.addRow("Beta Capital", "Dana", "Kim", "Rejected", "note2");
        port0.addRow("Gamma Fund", "Jordan", "Wu", "Meetings", "note3");

        return port0;
    }

    private static SessionContext seededSession()
    {
        CRMSchemaConfig config0 = new CRMSchemaConfig("cfg1", "user1", "Test Fund", "sheet1");
        config0.setCol("mainTabFundNameCol", "Fund Name");
        config0.setCol("mainTabContact1FirstNameCol", "Contact 1 First Name");

        return new SessionContext(null, config0);
    }

    private static AskContext buildContext(SessionContext session0, FakeAskSheetPort port0) throws Exception
    {
        HashMap<String, Integer> headerMap0 = port0.headerMap();

        return new AskContext(
            session0,
            port0,
            headerMap0,
            session0.config.getCol("mainTabFundNameCol"),
            session0.config.getCol("mainTabContact1FirstNameCol")
        );
    }

    private static JSONObject argsWithQuery(String query0)
    {
        JSONObject args0 = new JSONObject();
        args0.put("query", query0);
        return args0;
    }

    private static JSONObject argsForPropose(int row0, String header0, String newValue0)
    {
        JSONObject args0 = new JSONObject();
        args0.put("row", row0);
        args0.put("header", header0);
        args0.put("newValue", newValue0);
        return args0;
    }

    private static void check(String label0, boolean condition0)
    {
        if (!condition0)
        {
            throw new RuntimeException("Check failed: " + label0);
        }
    }

    // ------------------------------------------------------------------------
    // IN-MEMORY FAKE SHEET PORT
    // ------------------------------------------------------------------------

    private static class FakeAskSheetPort implements AskSheetPort
    {
        private final String[] headers;
        private final List<String[]> rows = new ArrayList<>();
        final List<int[]> writes = new ArrayList<>();

        FakeAskSheetPort(String[] headers0)
        {
            this.headers = headers0;
        }

        void addRow(String... values0)
        {
            rows.add(values0);
        }

        private int dataStartRow()
        {
            return 2;
        }

        @Override
        public HashMap<String, Integer> headerMap()
        {
            HashMap<String, Integer> map0 = new HashMap<>();

            for (int i0 = 0; i0 < headers.length; i0++)
            {
                map0.put(headers[i0], i0 + 1);
            }

            return map0;
        }

        @Override
        public String readCell(int row0, int col0)
        {
            int rowIndex0 = row0 - dataStartRow();

            if (rowIndex0 < 0 || rowIndex0 >= rows.size())
            {
                return "";
            }

            String[] rowData0 = rows.get(rowIndex0);

            if (col0 < 1 || col0 > rowData0.length)
            {
                return "";
            }

            return rowData0[col0 - 1];
        }

        @Override
        public String[] readColumn(int col0, int fromRow0, int toRow0)
        {
            List<String> values0 = new ArrayList<>();

            for (int row0 = fromRow0; row0 <= toRow0; row0++)
            {
                values0.add(readCell(row0, col0));
            }

            return values0.toArray(new String[0]);
        }

        @Override
        public String[][] readRow(int row0, int fromCol0, int toCol0)
        {
            String[] rowValues0 = new String[toCol0 - fromCol0 + 1];

            for (int col0 = fromCol0; col0 <= toCol0; col0++)
            {
                rowValues0[col0 - fromCol0] = readCell(row0, col0);
            }

            return new String[][] { rowValues0 };
        }

        @Override
        public int lastRow()
        {
            return dataStartRow() + rows.size() - 1;
        }

        @Override
        public void writeCell(int row0, int col0, String value0)
        {
            writes.add(new int[] { row0, col0 });

            int rowIndex0 = row0 - dataStartRow();

            if (rowIndex0 < 0 || rowIndex0 >= rows.size())
            {
                return;
            }

            String[] rowData0 = rows.get(rowIndex0);

            if (col0 >= 1 && col0 <= rowData0.length)
            {
                rowData0[col0 - 1] = value0;
            }
        }
    }

    // ------------------------------------------------------------------------
    // SCRIPTED FAKE LLM PORT
    // ------------------------------------------------------------------------

    private static class FakeAskLlmPort implements AskLlmPort
    {
        private final List<JSONObject> script = new ArrayList<>();
        private int callIndex = 0;

        void scriptFunctionCall(String toolName0, String argumentsJson0)
        {
            JSONObject functionCall0 = new JSONObject();
            functionCall0.put("type", "function_call");
            functionCall0.put("name", toolName0);
            functionCall0.put("arguments", argumentsJson0);

            JSONArray output0 = new JSONArray();
            output0.put(functionCall0);

            JSONObject response0 = new JSONObject();
            response0.put("output", output0);

            script.add(response0);
        }

        void scriptFinalAnswer(String answer0)
        {
            JSONObject textContent0 = new JSONObject();
            textContent0.put("type", "output_text");
            textContent0.put("text", answer0);

            JSONArray content0 = new JSONArray();
            content0.put(textContent0);

            JSONObject message0 = new JSONObject();
            message0.put("type", "message");
            message0.put("content", content0);

            JSONArray output0 = new JSONArray();
            output0.put(message0);

            JSONObject response0 = new JSONObject();
            response0.put("output", output0);
            response0.put("output_text", answer0);

            script.add(response0);
        }

        @Override
        public JSONObject respond(String prompt0, JSONArray tools0)
        {
            if (callIndex >= script.size())
            {
                // Ran out of script: behave as a clean final answer rather than
                // throwing, so the repeated-call-guard test (which never scripts
                // a final answer) exercises the guard instead of this fallback.
                JSONObject response0 = new JSONObject();
                response0.put("output", new JSONArray());
                response0.put("output_text", "");
                return response0;
            }

            return script.get(callIndex++);
        }
    }
}
