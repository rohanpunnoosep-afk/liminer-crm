package com.liminer.web;

import com.liminer.core.CRMField;
import com.liminer.core.CRMFieldRegistry;
import com.liminer.core.CRMSchemaConfig;
import com.liminer.core.SessionContext;
import com.liminer.llm.OpenAIClient;
import com.liminer.sheets.SheetsApp;

import java.util.ArrayList;
import java.util.HashMap;
import org.json.JSONArray;
import org.json.JSONObject;

public class CRMOnboard
{
    private static final int SCAN_HEADER_ROW0 = 1;
    private static final int SCAN_START_COL0 = 1;
    private static final int SCAN_END_COL0 = 150;

    public static SessionContext onboardUser(
        String userId0,
        String email0,
        String fundName0,
        String spreadsheetId0,
        ArrayList<String> internalNames0,
        ArrayList<String> internalEmails0,
        String internalFundName0,
        String internalWebsite0,
        String clientSectorTags0,
        String clientMicrosectorTags0,
        String clientGeography0,
        String clientStages0,
        String clientInvestmentThesis0,
        String clientProfileJson0,
        String[] possibleTabNames0
    ) throws Exception
    {
        JSONArray scannedTabs0 = scanTabs(spreadsheetId0, possibleTabNames0);

        JSONObject schemaResult0 = detectSchemaWithAI(scannedTabs0);

        JSONObject input0 = new JSONObject();
        input0.put("userId", userId0);
        input0.put("email", email0);
        input0.put("fundName", fundName0);
        input0.put("spreadsheetId", spreadsheetId0);
        input0.put("internalNames", new JSONArray(internalNames0));
        input0.put("internalEmails", new JSONArray(internalEmails0));
        input0.put("internalFundName", internalFundName0);
        input0.put("internalWebsite", internalWebsite0);
        input0.put("clientSectorTags", clientSectorTags0);
        input0.put("clientMicrosectorTags", clientMicrosectorTags0);
        input0.put("clientGeography", clientGeography0);
        input0.put("clientStages", clientStages0);
        input0.put("clientInvestmentThesis", clientInvestmentThesis0);
        input0.put("clientProfileJson", clientProfileJson0);

        return OnboardService.commitOnboarding(input0, schemaResult0);
    }

    static JSONArray scanTabs(
        String spreadsheetId0,
        String[] possibleTabNames0
    ) throws Exception
    {
        JSONArray tabsArray0 = new JSONArray();

        for (String tabName0 : possibleTabNames0)
        {
            String[][] headerData0 = SheetsApp.readRangeMatrix(
                spreadsheetId0,
                tabName0,
                SCAN_HEADER_ROW0,
                SCAN_START_COL0,
                SCAN_HEADER_ROW0,
                SCAN_END_COL0
            );

            JSONArray headers0 = new JSONArray();

            for (int colIndex0 = 0; colIndex0 < headerData0[0].length; colIndex0++)
            {
                String header0 = headerData0[0][colIndex0];

                if (header0 != null && header0.trim().length() > 0)
                {
                    headers0.put(header0.trim());
                }
            }

            JSONObject tabObject0 = new JSONObject();
            tabObject0.put("tabName", tabName0);
            tabObject0.put("headers", headers0);

            tabsArray0.put(tabObject0);
        }

        return tabsArray0;
    }

    static JSONObject detectSchemaWithAI(JSONArray scannedTabs0) throws Exception
    {
        StringBuilder mainMappingsJson0 = new StringBuilder();
        mainMappingsJson0.append("  \"mainTabMappings\": {\n");
        ArrayList<CRMField> mainOnboardingFields0 = new ArrayList<>();
        for (CRMField field0 : CRMFieldRegistry.getMainTabFields())
        {
            if (field0.includeInOnboarding)
            {
                mainOnboardingFields0.add(field0);
            }
        }
        for (int i0 = 0; i0 < mainOnboardingFields0.size(); i0++)
        {
            mainMappingsJson0.append("    \"").append(mainOnboardingFields0.get(i0).key).append("\": \"\"");
            if (i0 < mainOnboardingFields0.size() - 1)
            {
                mainMappingsJson0.append(",");
            }
            mainMappingsJson0.append("\n");
        }
        mainMappingsJson0.append("  }");

        StringBuilder intakeMappingsJson0 = new StringBuilder();
        intakeMappingsJson0.append("  \"intakeTabMappings\": {\n");
        ArrayList<CRMField> intakeSourceFields0 = new ArrayList<>();
        for (CRMField field0 : CRMFieldRegistry.getIntakeTabFields())
        {
            if (!field0.includeInOnboarding)
            {
                intakeSourceFields0.add(field0);
            }
        }
        for (int i0 = 0; i0 < intakeSourceFields0.size(); i0++)
        {
            intakeMappingsJson0.append("    \"").append(intakeSourceFields0.get(i0).key).append("\": \"\"");
            if (i0 < intakeSourceFields0.size() - 1)
            {
                intakeMappingsJson0.append(",");
            }
            intakeMappingsJson0.append("\n");
        }
        intakeMappingsJson0.append("  }");

        String prompt0 =
            "You are configuring an AI CRM system for a Venture Capital fund.\n"
            + "Given spreadsheet tabs and headers, identify the main CRM tab, email intake tab, and column mappings.\n\n"
            + "Return ONLY valid JSON. No markdown. No explanation.\n\n"
            + "Return exactly this JSON structure:\n"
            + "{\n"
            + "  \"mainTabName\": \"\",\n"
            + "  \"intakeTabName\": \"\",\n"
            + mainMappingsJson0.toString() + ",\n"
            + intakeMappingsJson0.toString() + "\n"
            + "}\n\n"
            + "Rules:\n"
            + "1. mainTabName should be the tab that looks like the fund's CRM/investor database.\n"
            + "2. intakeTabName should be the tab that looks like raw email or communication intake.\n"
            + "3. Use exact header names from the input.\n"
            + "4. If a field is not found, return an empty string for that field.\n"
            + "5. Do not invent headers.\n"
            + "6. mainTabTypeOfInvestorCol is used as the allocator type column.\n\n"
            + "Tabs and headers:\n"
            + scannedTabs0.toString(2);

        String aiText0 = OpenAIClient.getTextResponse(prompt0);

        return parseJsonObjectFromText(aiText0);
    }

    static CRMSchemaConfig buildConfigFromSchema(
        String configId0,
        String userId0,
        String crmName0,
        String spreadsheetId0,
        JSONObject schemaResult0
    )
    {
        CRMSchemaConfig config0 = new CRMSchemaConfig(
            configId0,
            userId0,
            crmName0,
            spreadsheetId0
        );

        config0.mainTabName = schemaResult0.optString("mainTabName", "");
        config0.intakeTabName = schemaResult0.optString("intakeTabName", "");

        JSONObject main0 = schemaResult0.optJSONObject("mainTabMappings");
        JSONObject intake0 = schemaResult0.optJSONObject("intakeTabMappings");

        config0.mainTabHeaderRow = 1;
        config0.mainTabDataStartRow = 2;
        config0.intakeTabHeaderRow = 1;
        config0.intakeTabDataStartRow = 2;

        for (CRMField field0 : CRMFieldRegistry.getMainTabFields())
        {
            if (field0.includeInOnboarding && main0 != null)
            {
                config0.setCol(field0.key, main0.optString(field0.key, ""));
            }
        }

        for (CRMField field0 : CRMFieldRegistry.getIntakeTabFields())
        {
            if (field0.includeInOnboarding)
            {
                config0.setCol(field0.key, field0.columnName);
            }
            else if (intake0 != null)
            {
                config0.setCol(field0.key, intake0.optString(field0.key, ""));
            }
        }

        return config0;
    }

    static void ensureSystemIntakeColumnsExist(
        String spreadsheetId0,
        CRMSchemaConfig config0
    ) throws Exception
    {
        ArrayList<String> requiredHeaders0 = new ArrayList<>();
        for (CRMField field0 : CRMFieldRegistry.getIntakeTabFields())
        {
            if (field0.includeInOnboarding)
            {
                String headerValue0 = config0.getCol(field0.key);
                if (!isBlank(headerValue0))
                {
                    requiredHeaders0.add(headerValue0);
                }
            }
        }

        ensureHeadersExist(
            spreadsheetId0,
            config0.intakeTabName,
            config0.intakeTabHeaderRow,
            requiredHeaders0.toArray(new String[0]),
            150
        );
    }

    // Provision the main CRM tab in three ordered phases (dividerlists.md step 5):
    // human-facing columns first, then the divider, then machine-facing columns —
    // so the GP's daily columns stay grouped on the left and every machine-generated
    // column lands to the right of a single, durable, bordered divider. Each phase
    // appends its missing headers in one batched header-row write and is idempotent.
    static void ensureRequiredMainCrmColumnsExist(
        String spreadsheetId0,
        CRMSchemaConfig config0
    ) throws Exception
    {
        SessionContext context0 = new SessionContext(null, config0);
        String mainTab0 = config0.mainTabName;
        int headerRow0 = config0.mainTabHeaderRow;

        HashMap<String, Integer> headerMap0 = SheetsApp.buildHeaderMap(
            spreadsheetId0, mainTab0, headerRow0, 200);

        // Phase 1: human-facing columns (left of divider).
        CRMFieldRegistry.ensureHumanFacingColumns(
            context0, spreadsheetId0, mainTab0, headerRow0, headerMap0);

        // Phase 2: place + border the divider.
        CRMFieldRegistry.ensureDivider(
            context0, spreadsheetId0, mainTab0, headerRow0, headerMap0);

        // Phase 3: machine-facing columns (right of divider, with collision guard).
        CRMFieldRegistry.ensureMachineFacingColumns(
            context0, spreadsheetId0, mainTab0, headerRow0, headerMap0);
    }

    private static void ensureHeadersExist(
        String spreadsheetId0,
        String tabName0,
        int headerRow0,
        String[] requiredHeaders0,
        int scanEndColumn0
    ) throws Exception
    {
        String[][] headerData0 = SheetsApp.readRangeMatrix(
            spreadsheetId0,
            tabName0,
            headerRow0,
            1,
            headerRow0,
            scanEndColumn0
        );

        int lastHeaderCol0 = 0;

        for (int i = 0; i < headerData0[0].length; i++)
        {
            if (headerData0[0][i] != null &&
                headerData0[0][i].trim().length() > 0)
            {
                lastHeaderCol0 = i + 1;
            }
        }

        // Pass 1: collect missing headers without writing.
        ArrayList<String> missingHeaders0 = new ArrayList<>();

        for (int i = 0; i < requiredHeaders0.length; i++)
        {
            String requiredHeader0 = requiredHeaders0[i];

            if (isBlank(requiredHeader0))
            {
                continue;
            }

            if (!headerExists(headerData0[0], requiredHeader0))
            {
                missingHeaders0.add(requiredHeader0);
            }
        }

        if (missingHeaders0.isEmpty())
        {
            return;
        }

        // Expand the grid once so every new header has an allocated column.
        // Without this, writing past the grid throws "exceeds grid limits".
        int neededCols0 = lastHeaderCol0 + missingHeaders0.size();
        SheetsApp.expandSheetColumnsIfNeeded(spreadsheetId0, tabName0, neededCols0);

        // Pass 2: write ALL missing headers in one header-row call. The range
        // starts past the last used column, so it contains no existing data;
        // one write instead of one per header keeps onboarding under the
        // Sheets 60-writes/min quota.
        String[][] newHeaderRow0 = new String[1][missingHeaders0.size()];

        for (int i = 0; i < missingHeaders0.size(); i++)
        {
            newHeaderRow0[0][i] = missingHeaders0.get(i);
        }

        SheetsApp.updateRangeMatrix(
            spreadsheetId0,
            tabName0,
            headerRow0,
            lastHeaderCol0 + 1,
            newHeaderRow0
        );
    }

    private static boolean headerExists(String[] headers0, String target0)
    {
        if (isBlank(target0))
        {
            return false;
        }

        for (int i = 0; i < headers0.length; i++)
        {
            if (headers0[i] != null &&
                headers0[i].trim().equalsIgnoreCase(target0.trim()))
            {
                return true;
            }
        }

        return false;
    }

    private static JSONObject parseJsonObjectFromText(String text0)
    {
        String trimmedText0 = text0.trim();

        try
        {
            return new JSONObject(trimmedText0);
        }
        catch (Exception exception0)
        {
            int startIndex0 = trimmedText0.indexOf("{");
            int endIndex0 = trimmedText0.lastIndexOf("}");

            if (startIndex0 == -1 || endIndex0 == -1 || endIndex0 <= startIndex0)
            {
                throw exception0;
            }

            String jsonObjectText0 = trimmedText0.substring(startIndex0, endIndex0 + 1);

            return new JSONObject(jsonObjectText0);
        }
    }

    static void setDefaultMainCrmHeaders(CRMSchemaConfig config0)
    {
        for (CRMField field0 : CRMFieldRegistry.getMainTabFields())
        {
            if (field0.includeInOnboarding && isBlank(config0.getCol(field0.key)))
            {
                config0.setCol(field0.key, field0.columnName);
            }
        }
    }

    private static boolean isBlank(String value0)
    {
        return value0 == null || value0.trim().length() == 0;
    }
}