package com.liminer.ask;

import com.liminer.core.SessionContext;

import java.util.HashMap;
import java.util.Map;
import org.json.JSONArray;
import org.json.JSONObject;

// Session-scoped natural-language CRM agent. Reads are unguarded; any write the
// model wants to make is staged as a ProposedChange via propose_cell_update and
// never actually written here -- see AskApplier for the separate, explicit apply
// step. Mirrors the loop structure of the legacy
// AgentMain.runNaturalLanguageSheetAgent (read output, dispatch first
// function_call, append result to the running prompt, repeat) but name-keyed,
// session-scoped, and returning cleanly instead of exiting the JVM.
public class AskAgent
{
    private static final int MAX_STEPS0 = 8;

    private final SessionContext session;
    private final AskSheetPort port;
    private final AskLlmPort llm;

    public AskAgent(SessionContext session, AskSheetPort port, AskLlmPort llm)
    {
        this.session = session;
        this.port = port;
        this.llm = llm;
    }

    public AskResult ask(String userPrompt) throws Exception
    {
        HashMap<String, Integer> headerMap0 = port.headerMap();

        String fundNameHeader0 = session.config.getCol("mainTabFundNameCol");
        String contactFirstNameHeader0 = session.config.getCol("mainTabContact1FirstNameCol");

        AskContext context0 = new AskContext(session, port, headerMap0, fundNameHeader0, contactFirstNameHeader0);

        String runningPrompt0 = buildSystemPreamble(session, headerMap0) + "\n\n" + userPrompt;

        JSONArray tools0 = AskToolRegistry.toOpenAiToolsJson();

        String lastToolName0 = null;
        String lastArgumentsString0 = null;

        for (int stepIndex0 = 1; stepIndex0 <= MAX_STEPS0; stepIndex0++)
        {
            JSONObject response0 = llm.respond(runningPrompt0, tools0);

            if (response0.has("error") && !response0.isNull("error"))
            {
                return new AskResult("Error from the language model: " + response0.getJSONObject("error").toString(), context0.proposals);
            }

            if (!response0.has("output"))
            {
                return new AskResult("No output returned by the language model.", context0.proposals);
            }

            JSONArray outputArray0 = response0.getJSONArray("output");

            JSONObject functionCall0 = null;

            for (int i0 = 0; i0 < outputArray0.length(); i0++)
            {
                JSONObject item0 = outputArray0.getJSONObject(i0);

                if ("function_call".equals(item0.optString("type", "")))
                {
                    functionCall0 = item0;
                    break;
                }
            }

            if (functionCall0 == null)
            {
                String answer0 = extractOutputText(response0, outputArray0);
                return new AskResult(answer0, context0.proposals);
            }

            String toolName0 = functionCall0.getString("name");
            String argumentsString0 = functionCall0.getString("arguments");
            JSONObject argumentsObject0 = new JSONObject(argumentsString0);

            AskToolSpec toolSpec0 = AskToolRegistry.getToolByName(toolName0);

            if (toolSpec0 == null)
            {
                return new AskResult("ERROR: Model called unknown tool: " + toolName0, context0.proposals);
            }

            String currentArgumentsString0 = buildArgumentFingerprint(argumentsObject0);

            if (toolName0.equals(lastToolName0) && currentArgumentsString0.equals(lastArgumentsString0))
            {
                return new AskResult("Stopped: the model repeated the same tool call twice in a row.", context0.proposals);
            }

            String toolResult0 = toolSpec0.executor.execute(argumentsObject0, context0);

            lastToolName0 = toolName0;
            lastArgumentsString0 = currentArgumentsString0;

            runningPrompt0 =
                runningPrompt0
                + "\n\nThe previous tool call was:"
                + "\nTool name: " + toolName0
                + "\nArguments: " + argumentsObject0.toString()
                + "\nTool result: " + toolResult0
                + "\nIf the user's full request is answered, do not call a function; respond with the final answer."
                + "\nIf more information or another staged change is needed, call the next function.";
        }

        return new AskResult("Stopped: reached the maximum number of steps.", context0.proposals);
    }

    private String extractOutputText(JSONObject response0, JSONArray outputArray0)
    {
        if (response0.has("output_text") && !response0.isNull("output_text"))
        {
            return response0.getString("output_text");
        }

        for (int i0 = 0; i0 < outputArray0.length(); i0++)
        {
            JSONObject item0 = outputArray0.getJSONObject(i0);

            if (!item0.has("content"))
            {
                continue;
            }

            JSONArray contentArray0 = item0.getJSONArray("content");

            for (int c0 = 0; c0 < contentArray0.length(); c0++)
            {
                JSONObject contentItem0 = contentArray0.getJSONObject(c0);

                if (contentItem0.has("text"))
                {
                    return contentItem0.getString("text");
                }
            }
        }

        return "";
    }

    private String buildArgumentFingerprint(JSONObject argumentsObject0)
    {
        StringBuilder fingerprint0 = new StringBuilder();

        for (String key0 : argumentsObject0.keySet())
        {
            Object value0 = argumentsObject0.isNull(key0) ? null : argumentsObject0.get(key0);
            fingerprint0.append(key0).append("=").append(value0 == null ? "null" : value0.toString()).append("|");
        }

        return fingerprint0.toString();
    }

    private String buildSystemPreamble(SessionContext session, HashMap<String, Integer> headerMap0)
    {
        StringBuilder preamble0 = new StringBuilder();

        preamble0.append("You are a CRM assistant for a venture capital fundraising spreadsheet.\n");
        preamble0.append("CRM tab name: ").append(session.config.mainTabName).append("\n");
        preamble0.append("Header row: ").append(session.config.mainTabHeaderRow).append("\n");
        preamble0.append("Data start row: ").append(session.config.mainTabDataStartRow).append("\n");
        preamble0.append("Columns in this sheet:\n");

        for (Map.Entry<String, Integer> entry0 : headerMap0.entrySet())
        {
            preamble0.append(entry0.getKey()).append(" (column ").append(entry0.getValue()).append(")\n");
        }

        preamble0.append(
            "\nYou may freely read data using find_investor_rows, read_row and read_column.\n"
            + "You may NEVER write to the spreadsheet directly. If the user asks you to change, "
            + "update, or set a value, call propose_cell_update, which only stages the change "
            + "for a human to review and approve -- it does not write anything.\n"
        );

        return preamble0.toString();
    }
}
