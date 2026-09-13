package com.liminer.ask;

import org.json.JSONArray;
import org.json.JSONObject;

// Seam over the LLM call the ask agent makes each loop step. Production
// implementation is OpenAiAskLlmPort; tests use a scripted fake so the ask
// agent's loop logic can be exercised with no network and no API key.
public interface AskLlmPort
{
    JSONObject respond(String prompt, JSONArray tools) throws Exception;
}
