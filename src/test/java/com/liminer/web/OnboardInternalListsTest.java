package com.liminer.web;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import org.json.JSONArray;
import org.json.JSONObject;

public class OnboardInternalListsTest
{
    @Test
    void stringFormPipeSeparated() throws Exception
    {
        JSONObject input0 = new JSONObject();
        input0.put("internalNames", "Ada Lovelace | John Smith");

        ArrayList<String> result0 = OnboardService.readStringList(input0, "internalNames");

        if (result0.size() != 2)
        {
            throw new Exception("testStringFormPipeSeparated: expected size 2, got " + result0.size());
        }

        if (!result0.get(0).equals("Ada Lovelace"))
        {
            throw new Exception("testStringFormPipeSeparated: expected 'Ada Lovelace' at index 0, got '" + result0.get(0) + "'");
        }

        if (!result0.get(1).equals("John Smith"))
        {
            throw new Exception("testStringFormPipeSeparated: expected 'John Smith' at index 1, got '" + result0.get(1) + "'");
        }
    }

    @Test
    void jSONArrayForm() throws Exception
    {
        JSONObject input0 = new JSONObject();
        JSONArray array0 = new JSONArray();
        array0.put("a");
        array0.put("b");
        input0.put("internalEmails", array0);

        ArrayList<String> result0 = OnboardService.readStringList(input0, "internalEmails");

        if (result0.size() != 2)
        {
            throw new Exception("testJSONArrayForm: expected size 2, got " + result0.size());
        }

        if (!result0.get(0).equals("a"))
        {
            throw new Exception("testJSONArrayForm: expected 'a' at index 0, got '" + result0.get(0) + "'");
        }

        if (!result0.get(1).equals("b"))
        {
            throw new Exception("testJSONArrayForm: expected 'b' at index 1, got '" + result0.get(1) + "'");
        }
    }

    @Test
    void missingKey() throws Exception
    {
        JSONObject input0 = new JSONObject();

        ArrayList<String> result0 = OnboardService.readStringList(input0, "missingKey");

        if (result0.size() != 0)
        {
            throw new Exception("testMissingKey: expected size 0, got " + result0.size());
        }

        if (result0 == null)
        {
            throw new Exception("testMissingKey: expected empty list, not null");
        }
    }

    @Test
    void blankString() throws Exception
    {
        JSONObject input0 = new JSONObject();
        input0.put("internalNames", "");

        ArrayList<String> result0 = OnboardService.readStringList(input0, "internalNames");

        if (result0.size() != 0)
        {
            throw new Exception("testBlankString: expected size 0, got " + result0.size());
        }
    }

    @Test
    void trailingSeparator() throws Exception
    {
        JSONObject input0 = new JSONObject();
        input0.put("internalNames", "a | b |");

        ArrayList<String> result0 = OnboardService.readStringList(input0, "internalNames");

        if (result0.size() != 2)
        {
            throw new Exception("testTrailingSeparator: expected size 2, got " + result0.size());
        }

        if (!result0.get(0).equals("a"))
        {
            throw new Exception("testTrailingSeparator: expected 'a' at index 0, got '" + result0.get(0) + "'");
        }

        if (!result0.get(1).equals("b"))
        {
            throw new Exception("testTrailingSeparator: expected 'b' at index 1, got '" + result0.get(1) + "'");
        }
    }
}
