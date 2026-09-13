package com.liminer.ask;

import com.liminer.llm.OpenAIClient;

import org.json.JSONArray;
import org.json.JSONObject;

public class OpenAiAskLlmPort implements AskLlmPort
{
    @Override
    public JSONObject respond(String prompt, JSONArray tools) throws Exception
    {
        return OpenAIClient.getToolCall(prompt, tools);
    }
}
