package com.liminer.web;

import com.liminer.core.CRMField;
import com.liminer.core.CRMFieldRegistry;
import com.liminer.core.CRMSchemaConfig;

import java.util.ArrayList;
import org.json.JSONArray;
import org.json.JSONObject;

// Pure, no-I/O smoke test for OnboardService. Must never call Sheets or OpenAI.
// Prints ONBOARD_SERVICE_OK on success; exits 1 on any failure.
public class OnboardServiceTestMain
{
    public static void main(String[] args)
    {
        try
        {
            testParsePipeSeparatedList();
            testValidateOnboardingInput();
            testDescribeMappableFields();
            testBuildConfigFromSchema();
            testBuildClientProfileJson();

            System.out.println("ONBOARD_SERVICE_OK");
        }
        catch (Throwable t)
        {
            System.out.println("TEST FAILED: " + t.getMessage());
            t.printStackTrace();
            System.exit(1);
        }
    }

    private static void testParsePipeSeparatedList() throws Exception
    {
        ArrayList<String> list0 = OnboardService.parsePipeSeparatedList("Don Smith | Jace Wang |");
        check("size 2", list0.size() == 2);
        check("first trimmed", list0.get(0).equals("Don Smith"));
        check("second trimmed", list0.get(1).equals("Jace Wang"));

        ArrayList<String> empty0 = OnboardService.parsePipeSeparatedList("");
        check("empty list", empty0.isEmpty());
    }

    private static void testValidateOnboardingInput() throws Exception
    {
        JSONObject blankEmail0 = validInput();
        blankEmail0.put("email", "");
        check("blank email fails", !OnboardService.validateOnboardingInput(blankEmail0).isEmpty());

        JSONObject badEmail0 = validInput();
        badEmail0.put("email", "not-an-email");
        check("email without @ fails", !OnboardService.validateOnboardingInput(badEmail0).isEmpty());

        JSONObject missingFund0 = validInput();
        missingFund0.remove("fundName");
        check("missing fundName fails", !OnboardService.validateOnboardingInput(missingFund0).isEmpty());

        JSONObject missingSpreadsheet0 = validInput();
        missingSpreadsheet0.remove("spreadsheetId");
        check("missing spreadsheetId fails", !OnboardService.validateOnboardingInput(missingSpreadsheet0).isEmpty());

        JSONObject emptyTabs0 = validInput();
        emptyTabs0.put("possibleTabNames", "");
        check("empty possibleTabNames fails", !OnboardService.validateOnboardingInput(emptyTabs0).isEmpty());

        check("valid input passes", OnboardService.validateOnboardingInput(validInput()).isEmpty());
    }

    private static JSONObject validInput()
    {
        JSONObject input0 = new JSONObject();
        input0.put("email", "gp@fund.com");
        input0.put("fundName", "Test Fund");
        input0.put("spreadsheetId", "sheet_123");
        input0.put("possibleTabNames", "Investors | Email Log");
        return input0;
    }

    private static void testDescribeMappableFields() throws Exception
    {
        JSONObject described0 = OnboardService.describeMappableFields();
        JSONArray mainFields0 = described0.getJSONArray("mainFields");
        JSONArray intakeFields0 = described0.getJSONArray("intakeFields");

        check("mainFields non-empty", mainFields0.length() > 0);
        check("intakeFields non-empty", intakeFields0.length() > 0);

        for (int i = 0; i < mainFields0.length(); i++)
        {
            String key0 = mainFields0.getJSONObject(i).optString("key", "");
            check("mainFields[" + i + "] key non-blank", key0.trim().length() > 0);
        }

        for (int i = 0; i < intakeFields0.length(); i++)
        {
            String key0 = intakeFields0.getJSONObject(i).optString("key", "");
            check("intakeFields[" + i + "] key non-blank", key0.trim().length() > 0);
        }
    }

    private static void testBuildConfigFromSchema() throws Exception
    {
        CRMField firstMainField0 = null;
        for (CRMField field0 : CRMFieldRegistry.getMainTabFields())
        {
            if (field0.includeInOnboarding)
            {
                firstMainField0 = field0;
                break;
            }
        }
        check("found a mappable main field", firstMainField0 != null);

        JSONObject mainMappings0 = new JSONObject();
        mainMappings0.put(firstMainField0.key, "Fund Header");

        JSONObject schema0 = new JSONObject();
        schema0.put("mainTabName", "Investors");
        schema0.put("intakeTabName", "Email Log");
        schema0.put("mainTabMappings", mainMappings0);
        schema0.put("intakeTabMappings", new JSONObject());

        CRMSchemaConfig config0 = CRMOnboard.buildConfigFromSchema(
            "config_test", "user_test", "Test Fund", "sheet_test", schema0);

        check("mainTabName matches", config0.mainTabName.equals("Investors"));
        check("intakeTabName matches", config0.intakeTabName.equals("Email Log"));
        check("mainTabHeaderRow == 1", config0.mainTabHeaderRow == 1);
        check("mainTabDataStartRow == 2", config0.mainTabDataStartRow == 2);
        check("mapped column round-trips", config0.getCol(firstMainField0.key).equals("Fund Header"));
    }

    private static void testBuildClientProfileJson() throws Exception
    {
        String json0 = OnboardService.buildClientProfileJson("AI|Fintech", "LLM", "US", "thesis");
        check("profile json non-empty", json0.trim().length() > 0);

        JSONObject parsed0 = new JSONObject(json0);
        check("profile json has sector tags", parsed0.getString("client_sector_tags").equals("AI|Fintech"));
    }

    private static void check(String label, boolean condition) throws Exception
    {
        if (!condition)
        {
            throw new Exception("Check failed: " + label);
        }

        System.out.println("OK: " + label);
    }
}
