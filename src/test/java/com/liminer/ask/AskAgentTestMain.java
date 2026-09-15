package com.liminer.ask;

import com.liminer.core.CRMSchemaConfig;
import com.liminer.core.InteractionRecord;
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
        "Notes",
        "Interaction History",
        "Interaction Records",
        "Last Contact Date"
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
            testProposeCellUpdateRejectsManagedColumns();
            testRecordInteractionStagesProgrammaticValues();
            testRecordInteractionForcesAPredesignedLabel();
            testRecordInteractionThroughAgentWritesNothing();

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
            "{\"row\":2,\"header\":\"Notes\",\"newValue\":\"Follow up next week\"}");
        llm0.scriptFinalAnswer("I've staged that change for your review.");

        AskAgent agent0 = new AskAgent(session0, port0, llm0);
        AskResult result0 = agent0.ask("mark Acme as Closed");

        check("exactly one proposal staged", result0.proposals.size() == 1);

        ProposedChange change0 = result0.proposals.get(0);
        check("row correct", change0.row == 2);
        check("fund name correct", change0.fundName.equals("Acme Ventures"));
        check("contact first name correct", change0.contactFirstName.equals("Sam"));
        check("column correct", change0.column.equals("Notes"));
        check("before value correct", change0.beforeValue.equals("note1"));
        check("after value correct", change0.afterValue.equals("Follow up next week"));

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
            argsForPropose(2, "Notes", "Closed"), context0);
        proposeTool0.executor.execute(
            argsForPropose(2, "Notes", "Passed"), context0);

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

        check("exactly 5 tools", tools0.length() == 5);

        List<String> expectedNames0 = List.of(
            "find_investor_rows", "read_row", "read_column", "propose_cell_update",
            "record_interaction");

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

    // (h) propose_cell_update refuses the four columns a recorded interaction owns,
    // so the model can never hand-write a conversation status or history line.
    private static void testProposeCellUpdateRejectsManagedColumns() throws Exception
    {
        FakeAskSheetPort port0 = seededPort();
        SessionContext session0 = seededSession();
        AskContext context0 = buildContext(session0, port0);

        AskToolSpec proposeTool0 = AskToolRegistry.getToolByName("propose_cell_update");

        String[] managed0 = new String[]
        {
            "Conversation Status", "Interaction History", "Interaction Records", "Last Contact Date"
        };

        for (int i0 = 0; i0 < managed0.length; i0++)
        {
            String result0 = proposeTool0.executor.execute(
                argsForPropose(2, managed0[i0], "Meeting held - interested in Fund II"), context0);

            check("managed column rejected: " + managed0[i0], result0.startsWith("ERROR:"));
            check("rejection points at record_interaction: " + managed0[i0],
                result0.contains("record_interaction"));
        }

        check("nothing staged for managed columns", context0.proposals.isEmpty());
        check("no writes performed", port0.writes.isEmpty());
    }

    // (i) record_interaction derives all four cells programmatically: the status
    // becomes the pre-designed label the analysis chose, the history gains one dated
    // line above the existing ones, the records wrapper gains one appended record,
    // and the last contact date advances. Nothing is written.
    private static void testRecordInteractionStagesProgrammaticValues() throws Exception
    {
        FakeAskSheetPort port0 = seededPort();
        SessionContext session0 = seededSession();

        FakeAnalysisPort analysis0 = new FakeAnalysisPort("Meetings", "Held a first call with Jordan about Fund II.");
        AskContext context0 = buildContext(session0, port0, analysis0);

        AskToolSpec recordTool0 = AskToolRegistry.getToolByName("record_interaction");

        String result0 = recordTool0.executor.execute(
            argsForRecord(4, "Had a call with Jordan today, he wants the deck.", "2026-03-04"), context0);

        check("tool reports the staged label", result0.contains("Meetings"));
        check("analysis ran once", analysis0.callCount == 1);
        check("the user's own words were passed through",
            analysis0.lastText.equals("Had a call with Jordan today, he wants the deck."));

        check("four columns staged", context0.proposals.size() == 4);

        ProposedChange status0 = proposalFor(context0, 4, "Conversation Status");
        check("status staged", status0 != null);
        check("status is exactly a pre-designed label", status0.afterValue.equals("Meetings"));
        check("status before value preserved", status0.beforeValue.equals("Reached Out"));

        ProposedChange history0 = proposalFor(context0, 4, "Interaction History");
        check("history staged", history0 != null);
        check("history line is date-prefixed",
            history0.afterValue.startsWith("2026-03-04: Held a first call with Jordan about Fund II."));

        ProposedChange records0 = proposalFor(context0, 4, "Interaction Records");
        check("records staged", records0 != null);

        JSONObject wrapper0 = new JSONObject(records0.afterValue);
        JSONArray recordArray0 = wrapper0.getJSONArray(InteractionRecord.RECORDS_KEY);
        check("exactly one record appended", recordArray0.length() == 1);

        InteractionRecord appended0 = InteractionRecord.fromJSON(recordArray0.getJSONObject(0));
        check("record carries the interaction date", appended0.date.equals("2026-03-04"));
        check("record carries the pre-designed label", appended0.conversationLabel.equals("Meetings"));
        check("record carries the summary",
            appended0.oneSentenceSummary.equals("Held a first call with Jordan about Fund II."));

        ProposedChange lastContact0 = proposalFor(context0, 4, "Last Contact Date");
        check("last contact staged", lastContact0 != null);
        check("last contact is the interaction date", lastContact0.afterValue.equals("2026-03-04"));

        check("no writes performed while staging", port0.writes.isEmpty());

        // The existing history line survives above the size cap, below the new one.
        FakeAskSheetPort port1 = seededPort();
        AskContext context1 = buildContext(session0, port1, new FakeAnalysisPort("Meetings", "Second call held."));
        recordTool0.executor.execute(argsForRecord(2, "Second call with Sam.", "2026-03-04"), context1);

        ProposedChange history1 = proposalFor(context1, 2, "Interaction History");
        check("existing history retained", history1 != null
            && history1.afterValue.startsWith("2026-03-04: Second call held.")
            && history1.afterValue.contains("2026-01-05: Intro email sent"));
    }

    // (j) a free-text "label" from the analysis (the old failure mode: a status cell
    // reading "Meeting held - interested in...") never reaches a staged value; it
    // falls back to the same default intake uses.
    private static void testRecordInteractionForcesAPredesignedLabel() throws Exception
    {
        FakeAskSheetPort port0 = seededPort();
        SessionContext session0 = seededSession();

        FakeAnalysisPort analysis0 = new FakeAnalysisPort(
            "Meeting held - interested in Fund II", "Call held with Jordan.");

        AskContext context0 = buildContext(session0, port0, analysis0);

        AskToolSpec recordTool0 = AskToolRegistry.getToolByName("record_interaction");
        recordTool0.executor.execute(argsForRecord(4, "Call with Jordan.", "2026-03-04"), context0);

        for (ProposedChange change0 : context0.proposals)
        {
            check("invented label never staged into " + change0.column,
                !change0.afterValue.contains("Meeting held - interested in Fund II"));
        }

        ProposedChange status0 = proposalFor(context0, 4, "Conversation Status");
        check("status not promoted by an invalid label", status0 == null);

        ProposedChange records0 = proposalFor(context0, 4, "Interaction Records");
        check("records staged with the fallback label", records0 != null);

        JSONArray recordArray0 = InteractionRecord.extractRecordsArray(records0.afterValue);
        InteractionRecord appended0 = InteractionRecord.fromJSON(recordArray0.getJSONObject(0));
        check("record label fell back to Reached Out", appended0.conversationLabel.equals("Reached Out"));
    }

    // (k) the same path driven through the whole agent: one record_interaction call
    // returns staged proposals and performs zero writes.
    private static void testRecordInteractionThroughAgentWritesNothing() throws Exception
    {
        FakeAskSheetPort port0 = seededPort();
        SessionContext session0 = seededSession();

        FakeAskLlmPort llm0 = new FakeAskLlmPort();
        llm0.scriptFunctionCall("record_interaction",
            "{\"row\":4,\"interactionText\":\"Met Jordan for coffee, he asked for the data room.\",\"date\":\"2026-03-04\"}");
        llm0.scriptFinalAnswer("I've staged the interaction for your review.");

        AskAgent agent0 = new AskAgent(
            session0, port0, llm0, new FakeAnalysisPort("Meetings", "Met Jordan and he asked for the data room."));

        AskResult result0 = agent0.ask("record that I met Jordan for coffee");

        check("four proposals returned", result0.proposals.size() == 4);
        check("no writes performed", port0.writes.isEmpty());
    }

    private static ProposedChange proposalFor(AskContext context0, int row0, String column0)
    {
        for (ProposedChange change0 : context0.proposals)
        {
            if (change0.row == row0 && change0.column.equals(column0))
            {
                return change0;
            }
        }

        return null;
    }

    // ------------------------------------------------------------------------
    // FIXTURES
    // ------------------------------------------------------------------------

    private static FakeAskSheetPort seededPort()
    {
        FakeAskSheetPort port0 = new FakeAskSheetPort(HEADERS0);

        port0.addRow("Acme Ventures", "Sam", "Lee", "Meetings", "note1",
            "2026-01-05: Intro email sent", "", "2026-01-05");
        port0.addRow("Beta Capital", "Dana", "Kim", "Rejected", "note2", "", "", "");
        port0.addRow("Gamma Fund", "Jordan", "Wu", "Reached Out", "note3", "", "", "");

        return port0;
    }

    private static SessionContext seededSession()
    {
        CRMSchemaConfig config0 = new CRMSchemaConfig("cfg1", "user1", "Test Fund", "sheet1");
        config0.setCol("mainTabFundNameCol", "Fund Name");
        config0.setCol("mainTabContact1FirstNameCol", "Contact 1 First Name");
        config0.setCol("mainTabStatusCol", "Conversation Status");
        config0.setCol("mainTabInteractionHistoryCol", "Interaction History");
        config0.setCol("mainTabInteractionRecordsCol", "Interaction Records");
        config0.setCol("mainTabLastContactDateCol", "Last Contact Date");

        return new SessionContext(null, config0);
    }

    private static AskContext buildContext(SessionContext session0, FakeAskSheetPort port0) throws Exception
    {
        return buildContext(session0, port0, null);
    }

    private static AskContext buildContext(
        SessionContext session0,
        FakeAskSheetPort port0,
        InteractionAnalysisPort analysisPort0) throws Exception
    {
        HashMap<String, Integer> headerMap0 = port0.headerMap();

        return new AskContext(
            session0,
            port0,
            headerMap0,
            session0.config.getCol("mainTabFundNameCol"),
            session0.config.getCol("mainTabContact1FirstNameCol"),
            analysisPort0
        );
    }

    private static JSONObject argsWithQuery(String query0)
    {
        JSONObject args0 = new JSONObject();
        args0.put("query", query0);
        return args0;
    }

    private static JSONObject argsForRecord(int row0, String interactionText0, String date0)
    {
        JSONObject args0 = new JSONObject();
        args0.put("row", row0);
        args0.put("interactionText", interactionText0);
        args0.put("date", date0);
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

    // ------------------------------------------------------------------------
    // FAKE INTERACTION ANALYSIS PORT (stands in for the intake OpenAI call)
    // ------------------------------------------------------------------------

    private static class FakeAnalysisPort implements InteractionAnalysisPort
    {
        private final String label;
        private final String summary;

        int callCount = 0;
        String lastText = "";

        FakeAnalysisPort(String label0, String summary0)
        {
            this.label = label0;
            this.summary = summary0;
        }

        @Override
        public JSONObject analyze(String interactionText0)
        {
            callCount++;
            lastText = interactionText0;

            JSONObject result0 = new JSONObject();
            result0.put("conversationLabel", label);
            result0.put("oneSentenceSummary", summary);
            result0.put("direction", "OUTBOUND");
            result0.put("type", "CALL");
            result0.put("keyTopicsDiscussed", new JSONArray().put("Fund II"));
            result0.put("lpQuestionsAsked", new JSONArray());
            result0.put("commitmentsMadeByGP", new JSONArray().put("Send the deck"));
            result0.put("lpSentiment", "POSITIVE");
            result0.put("relationshipSignals", new JSONArray());

            return result0;
        }
    }
}
