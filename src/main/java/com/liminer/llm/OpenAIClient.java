package com.liminer.llm;

import com.liminer.billing.CostMeter;

import java.util.concurrent.TimeUnit;
import okhttp3.*;
import org.json.JSONArray;
import org.json.JSONObject;

public class OpenAIClient
{
    private static final String API_KEY = System.getenv("OPENAI_API_KEY");

    private static final String TOOL_CALL_MODEL = "gpt-4.1-mini";

    public static JSONObject getToolCall(String prompt) throws Exception
    {
        CostMeter activeMeter = CostMeter.current();

        if (activeMeter != null)
        {
            activeMeter.checkCeiling();
        }

        OkHttpClient client = new OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(240, TimeUnit.SECONDS)
            .writeTimeout(240, TimeUnit.SECONDS)
            .callTimeout(240, TimeUnit.SECONDS)
            .build();

        JSONObject body = new JSONObject();
        body.put("model", TOOL_CALL_MODEL);
        body.put("input", prompt);
        body.put("tool_choice", "auto");

        JSONArray tools = new JSONArray();

        for (ToolSpec toolSpec : ToolRegistry.TOOLS)
        {
            JSONObject tool = new JSONObject();
            tool.put("type", "function");
            tool.put("name", toolSpec.name);
            tool.put("description", toolSpec.purpose);
            tool.put("strict", true);

            JSONObject parameters = new JSONObject();
            parameters.put("type", "object");

            JSONObject properties = new JSONObject();
            JSONArray required = new JSONArray();

            // -----------------------------
            // ADD SHEET ARGUMENTS FIRST
            // -----------------------------

            JSONObject sheetNameArg0 = new JSONObject();
            sheetNameArg0.put("type", "string");
            sheetNameArg0.put(
                "description",
                "The configured sheet name. Must be one of the allowed sheet names listed in the prompt (e.g., INTAKE, CRM)."
            );
            properties.put("sheetName", sheetNameArg0);
            required.put("sheetName");

            JSONObject tabNameArg0 = new JSONObject();
            tabNameArg0.put("type", "string");
            tabNameArg0.put(
                "description",
                "The tab name inside the selected sheet. Must match one of the allowed tabs for that sheet."
            );
            properties.put("tabName", tabNameArg0);
            required.put("tabName");

            // -----------------------------
            // ADD TOOL-SPECIFIC ARGUMENTS
            // -----------------------------

            for (ToolArgSpec arg : toolSpec.args)
            {
                JSONObject argObject = new JSONObject();
                argObject.put("type", arg.type);
                argObject.put("description", arg.description);

                properties.put(arg.name, argObject);
                required.put(arg.name);
            }

            parameters.put("properties", properties);
            parameters.put("required", required);
            parameters.put("additionalProperties", false);

            tool.put("parameters", parameters);
            tools.put(tool);
        }

        body.put("tools", tools);

        Request request = new Request.Builder()
            .url("https://api.openai.com/v1/responses")
            .addHeader("Authorization", "Bearer " + API_KEY)
            .addHeader("Content-Type", "application/json")
            .post(RequestBody.create(body.toString(), MediaType.get("application/json")))
            .build();

        Response response = client.newCall(request).execute();
        String responseBody = response.body().string();

        JSONObject json = new JSONObject(responseBody);
        recordUsage(json, TOOL_CALL_MODEL, activeMeter);

        return json;
    }

    /**
     * Parses the Responses API "usage" object (usage.prompt_tokens/completion_tokens,
     * falling back to usage.input_tokens/output_tokens) and records it on the active
     * meter. No-op when no meter is bound (terminal/AgentMain paths) or when the
     * response has no usage object.
     */
    private static void recordUsage(JSONObject json, String model, CostMeter meter)
    {
        if (meter == null || json == null)
        {
            return;
        }

        JSONObject usage = json.optJSONObject("usage");

        if (usage == null)
        {
            return;
        }

        long promptTokens = usage.has("prompt_tokens")
            ? usage.optLong("prompt_tokens", 0)
            : usage.optLong("input_tokens", 0);

        long completionTokens = usage.has("completion_tokens")
            ? usage.optLong("completion_tokens", 0)
            : usage.optLong("output_tokens", 0);

        meter.record(model, promptTokens, completionTokens);
    }

    // Cheap/small model used by default for extraction-style calls (candidate
    // profile extraction, tag scoring). BETTER_MODEL0 is reserved for the few
    // calls that justify the extra cost, e.g. a final ranked-candidate
    // rationale, via the getTextResponse(prompt, model) overload below.
    public static final String CHEAP_MODEL0 = "gpt-4.1-mini";
    public static final String BETTER_MODEL0 = "gpt-4.1";

    public static String getTextResponse(String prompt0) throws Exception
    {
        return getTextResponse(prompt0, CHEAP_MODEL0);
    }

    /*
     * Additive overload: same request shape as getTextResponse(prompt), but lets
     * the caller pick the model (e.g. BETTER_MODEL0 for a final rationale).
     * Existing call sites using getTextResponse(prompt) are unaffected.
     */
    public static String getTextResponse(String prompt0, String model0) throws Exception
    {
        CostMeter activeMeter0 = CostMeter.current();

        if (activeMeter0 != null)
        {
            activeMeter0.checkCeiling();
        }

        String effectiveModel0 = model0 == null || model0.trim().isEmpty() ? CHEAP_MODEL0 : model0;

        OkHttpClient client0 = new OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(240, TimeUnit.SECONDS)
            .writeTimeout(240, TimeUnit.SECONDS)
            .callTimeout(240, TimeUnit.SECONDS)
            .build();

        JSONObject body0 = new JSONObject();
        body0.put("model", effectiveModel0);
        body0.put("input", prompt0);

        Request request0 = new Request.Builder()
            .url("https://api.openai.com/v1/responses")
            .addHeader("Authorization", "Bearer " + API_KEY)
            .addHeader("Content-Type", "application/json")
            .post(RequestBody.create(body0.toString(), MediaType.get("application/json")))
            .build();

        try (Response response0 = client0.newCall(request0).execute())
        {
            String responseBody0 = response0.body().string();

            JSONObject json0 = new JSONObject(responseBody0);

            if (!response0.isSuccessful())
            {
                System.out.println("OpenAI API Error:");
                System.out.println(json0.toString(2));
                throw new Exception("OpenAI API request failed with status: " + response0.code());
            }

            if (json0.has("error") && !json0.isNull("error"))
            {
                System.out.println("OpenAI API Error:");
                System.out.println(json0.toString(2));
                throw new Exception("OpenAI API returned an error.");
            }

            recordUsage(json0, effectiveModel0, activeMeter0);

            if (json0.has("output_text"))
            {
                return json0.getString("output_text");
            }

            if (!json0.has("output"))
            {
                System.out.println("Unexpected OpenAI response:");
                System.out.println(json0.toString(2));
                throw new Exception("OpenAI response missing output.");
            }

            JSONArray outputArray0 = json0.getJSONArray("output");

            for (int outputIndex0 = 0; outputIndex0 < outputArray0.length(); outputIndex0++)
            {
                JSONObject outputItem0 = outputArray0.getJSONObject(outputIndex0);

                if (outputItem0.has("content"))
                {
                    JSONArray contentArray0 = outputItem0.getJSONArray("content");

                    for (int contentIndex0 = 0; contentIndex0 < contentArray0.length(); contentIndex0++)
                    {
                        JSONObject contentItem0 = contentArray0.getJSONObject(contentIndex0);

                        if (contentItem0.has("text"))
                        {
                            return contentItem0.getString("text");
                        }
                    }
                }
            }

            System.out.println("Could not extract text from OpenAI response:");
            System.out.println(json0.toString(2));

            throw new Exception("Could not extract text from OpenAI response.");
        }
    }
}