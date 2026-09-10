package com.liminer.web;

import org.junit.jupiter.api.Test;

import org.json.JSONArray;
import org.json.JSONObject;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Discovery used to find twenty candidates no matter what: maxCandidates existed as a
 * handler param but no run form ever collected it. These cover the declaration the UI
 * reads to build that form.
 */
public class DiscoverCandidatesInputTest
{
    private static JSONObject discoveryJson()
    {
        WorkflowRegistry registry = WorkflowRegistry.buildProductionRegistry();
        WorkflowRegistry.WorkflowInfo info = registry.get("discover-candidates");
        assertNotNull(info, "discover-candidates workflow is registered");
        return info.toJson();
    }

    @Test
    public void discoveryAsksHowManyCandidates()
    {
        JSONObject json = discoveryJson();
        assertTrue(json.has("inputs"), "discover-candidates declares a run form");

        JSONArray inputs = json.getJSONArray("inputs");
        assertEquals(1, inputs.length());

        JSONObject field = inputs.getJSONObject(0);
        assertEquals("maxCandidates", field.getString("key"));
        assertEquals("number", field.getString("type"));
        assertFalse(field.getBoolean("required"), "leaving it blank falls back to the default");
    }

    @Test
    public void theFormShowsTwentyAsTheDefault()
    {
        JSONObject field = discoveryJson().getJSONArray("inputs").getJSONObject(0);
        assertEquals("20", field.getString("defaultValue"),
            "the box is pre-filled, so the default is visible rather than implied");
    }

    @Test
    public void aFieldWithoutADefaultOmitsTheKey()
    {
        WorkflowRegistry.InputField plain =
            new WorkflowRegistry.InputField("firstName", "First name", "text", false);

        assertFalse(plain.toJson().has("defaultValue"),
            "only fields that declare a default carry one");
    }
}
