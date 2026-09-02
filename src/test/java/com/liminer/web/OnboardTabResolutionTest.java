package com.liminer.web;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

import org.json.JSONArray;
import org.json.JSONObject;

// Pure, no-I/O smoke test for OnboardService.normalizeTabName. Must never call Sheets
// or OpenAI. Builds the scanned-tabs JSONArray and schema result by hand.
// Prints ONBOARD_TAB_RESOLUTION_OK on success; exits 1 on any failure.
public class OnboardTabResolutionTest
{
    @Test
    void driftingCaseAndWhitespaceIsNormalized() throws Exception
    {
        JSONArray scannedTabs0 = scannedTabs("CRM", "Email Log");

        JSONObject schema0 = new JSONObject();
        schema0.put("mainTabName", "crm ");

        OnboardService.normalizeTabName(schema0, scannedTabs0, "mainTabName");

        check("drifted mainTabName snapped to scanned spelling",
            schema0.getString("mainTabName").equals("CRM"));
    }

    @Test
    void exactMatchIsUnchanged() throws Exception
    {
        JSONArray scannedTabs0 = scannedTabs("CRM", "Email Log");

        JSONObject schema0 = new JSONObject();
        schema0.put("mainTabName", "CRM");

        OnboardService.normalizeTabName(schema0, scannedTabs0, "mainTabName");

        check("exact match left unchanged", schema0.getString("mainTabName").equals("CRM"));
    }

    @Test
    void noMatchIsLeftAsIs() throws Exception
    {
        JSONArray scannedTabs0 = scannedTabs("CRM", "Email Log");

        JSONObject schema0 = new JSONObject();
        schema0.put("mainTabName", "Portfolio Companies");

        OnboardService.normalizeTabName(schema0, scannedTabs0, "mainTabName");

        check("no-match name left as-is",
            schema0.getString("mainTabName").equals("Portfolio Companies"));
    }

    @Test
    void intakeTabNameIsNormalized() throws Exception
    {
        JSONArray scannedTabs0 = scannedTabs("CRM", "Email Log");

        JSONObject schema0 = new JSONObject();
        schema0.put("intakeTabName", "  email log");

        OnboardService.normalizeTabName(schema0, scannedTabs0, "intakeTabName");

        check("intake tab name normalized by the same rule",
            schema0.getString("intakeTabName").equals("Email Log"));
    }

    private static JSONArray scannedTabs(String... tabNames)
    {
        JSONArray scannedTabs0 = new JSONArray();

        for (String tabName0 : tabNames)
        {
            JSONObject tab0 = new JSONObject();
            tab0.put("tabName", tabName0);
            tab0.put("headers", new JSONArray());
            scannedTabs0.put(tab0);
        }

        return scannedTabs0;
    }

    private static void check(String label, boolean condition)
    {
        assertTrue(condition, label);
    }
}
