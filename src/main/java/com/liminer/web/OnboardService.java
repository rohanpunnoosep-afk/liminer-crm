package com.liminer.web;

import com.liminer.core.CRMField;
import com.liminer.core.CRMFieldRegistry;
import com.liminer.core.CRMRegistry;
import com.liminer.core.CRMSchemaConfig;
import com.liminer.core.SessionContext;
import com.liminer.core.UserAccount;
import com.liminer.sheets.SheetsApp;

import java.util.ArrayList;
import java.util.HashMap;
import org.json.JSONArray;
import org.json.JSONObject;

// Non-interactive detect/commit seam over CRMOnboard's single-shot flow. Detect scans
// the client spreadsheet and asks the AI for a proposed schema WITHOUT writing
// anything; commit takes an already-approved schema and performs the existing
// registration + column provisioning. This lets a web tier show the GP the proposed
// mapping and let them correct it before anything is written to their sheet.
public class OnboardService
{
    public static JSONObject detectSchemaProposal(
        String spreadsheetId0,
        String[] possibleTabNames0
    ) throws Exception
    {
        JSONArray scannedTabs0 = CRMOnboard.scanTabs(spreadsheetId0, possibleTabNames0);

        JSONObject schemaResult0 = CRMOnboard.detectSchemaWithAI(scannedTabs0);

        normalizeTabName(schemaResult0, scannedTabs0, "mainTabName");
        normalizeTabName(schemaResult0, scannedTabs0, "intakeTabName");

        JSONObject result0 = new JSONObject();
        result0.put("schema", schemaResult0);
        result0.put("tabs", scannedTabs0);
        return result0;
    }

    // The AI is only constrained to use exact HEADER names (CRMOnboard.detectSchemaWithAI's
    // prompt rule 3); nothing pins the TAB name it returns to the scanned list, so casing or
    // whitespace can drift. Snap it back onto the actual scanned spelling so downstream tab
    // lookups (which are exact-match) don't silently fail. Leaves the value untouched if it
    // matches nothing scanned.
    static void normalizeTabName(JSONObject schemaResult0, JSONArray scannedTabs0, String key0)
    {
        String tabName0 = schemaResult0.optString(key0, "");
        if (isBlank(tabName0))
        {
            return;
        }

        String needle0 = tabName0.trim().toLowerCase();

        for (int i = 0; i < scannedTabs0.length(); i++)
        {
            JSONObject tab0 = scannedTabs0.getJSONObject(i);
            String scannedName0 = tab0.optString("tabName", "");

            if (scannedName0.trim().toLowerCase().equals(needle0))
            {
                schemaResult0.put(key0, scannedName0);
                return;
            }
        }
    }

    public static JSONObject describeMappableFields()
    {
        JSONArray mainFields0 = new JSONArray();
        for (CRMField field0 : CRMFieldRegistry.getMainTabFields())
        {
            if (field0.includeInOnboarding)
            {
                mainFields0.put(fieldSummary(field0));
            }
        }

        JSONArray intakeFields0 = new JSONArray();
        for (CRMField field0 : CRMFieldRegistry.getIntakeTabFields())
        {
            if (!field0.includeInOnboarding)
            {
                intakeFields0.put(fieldSummary(field0));
            }
        }

        JSONObject result0 = new JSONObject();
        result0.put("mainFields", mainFields0);
        result0.put("intakeFields", intakeFields0);
        return result0;
    }

    private static JSONObject fieldSummary(CRMField field0)
    {
        JSONObject summary0 = new JSONObject();
        summary0.put("key", field0.key);
        summary0.put("displayName", field0.displayName);
        summary0.put("columnName", field0.columnName);
        return summary0;
    }

    public static ArrayList<String> validateOnboardingInput(JSONObject input0)
    {
        ArrayList<String> errors0 = new ArrayList<>();

        String email0 = input0.optString("email", "");
        if (isBlank(email0))
        {
            errors0.add("email is required");
        }
        else if (!email0.contains("@"))
        {
            errors0.add("email must contain @");
        }

        if (isBlank(input0.optString("fundName", "")))
        {
            errors0.add("fundName is required");
        }

        if (isBlank(input0.optString("spreadsheetId", "")))
        {
            errors0.add("spreadsheetId is required");
        }

        String[] possibleTabNames0 = parsePipeSeparatedArray(input0.optString("possibleTabNames", ""));
        if (possibleTabNames0.length == 0)
        {
            errors0.add("possibleTabNames is required");
        }

        return errors0;
    }

    static ArrayList<String> readStringList(JSONObject input0, String key0)
    {
        JSONArray array0 = input0.optJSONArray(key0);
        if (array0 != null)
        {
            return toStringList(array0);
        }

        String stringValue0 = input0.optString(key0, "");
        return parsePipeSeparatedList(stringValue0);
    }

    public static ArrayList<String> parsePipeSeparatedList(String value0)
    {
        ArrayList<String> list0 = new ArrayList<>();

        if (value0 == null || value0.trim().equals(""))
        {
            return list0;
        }

        String[] split0 = value0.split("\\|");

        for (int i = 0; i < split0.length; i++)
        {
            String item0 = split0[i].trim();

            if (!item0.equals(""))
            {
                list0.add(item0);
            }
        }

        return list0;
    }

    public static String[] parsePipeSeparatedArray(String value0)
    {
        ArrayList<String> list0 = parsePipeSeparatedList(value0);

        String[] array0 = new String[list0.size()];

        for (int i = 0; i < list0.size(); i++)
        {
            array0[i] = list0.get(i);
        }

        return array0;
    }

    public static String buildClientProfileJson(
        String sectorTags0,
        String microsectorTags0,
        String geography0,
        String investmentThesis0)
    {
        JSONObject object0 = new JSONObject();
        object0.put("client_sector_tags", sectorTags0 == null ? "" : sectorTags0);
        object0.put("client_microsector_tags", microsectorTags0 == null ? "" : microsectorTags0);
        object0.put("client_geography", geography0 == null ? "" : geography0);
        object0.put("client_investment_thesis", investmentThesis0 == null ? "" : investmentThesis0);
        return object0.toString();
    }

    // Read-only dry run result for POST /api/onboard/preview: the headers that WOULD
    // be added to the main CRM tab and the intake tab under an approved schema, without
    // writing anything. Mirrors the selection logic of CRMFieldRegistry.ensureHumanFacingColumns
    // / ensureDivider / ensureMachineFacingColumns and CRMOnboard.ensureSystemIntakeColumnsExist,
    // but intentionally skips the machine-column "(Liminer)" collision-disambiguation guard —
    // this is a preview of the common case, not a full re-implementation of the write path.
    public static class ColumnPlan
    {
        public final ArrayList<String> mainExisting;
        public final ArrayList<String> mainToAdd;
        public final ArrayList<String> intakeExisting;
        public final ArrayList<String> intakeToAdd;
        public final String dividerAction;

        public ColumnPlan(
            ArrayList<String> mainExisting0,
            ArrayList<String> mainToAdd0,
            ArrayList<String> intakeExisting0,
            ArrayList<String> intakeToAdd0,
            String dividerAction0)
        {
            this.mainExisting = mainExisting0;
            this.mainToAdd = mainToAdd0;
            this.intakeExisting = intakeExisting0;
            this.intakeToAdd = intakeToAdd0;
            this.dividerAction = dividerAction0;
        }

        public JSONObject toJson()
        {
            JSONObject json0 = new JSONObject();
            json0.put("mainExisting", new JSONArray(mainExisting));
            json0.put("mainToAdd", new JSONArray(mainToAdd));
            json0.put("intakeExisting", new JSONArray(intakeExisting));
            json0.put("intakeToAdd", new JSONArray(intakeToAdd));
            json0.put("dividerAction", dividerAction);
            return json0;
        }
    }

    // PURE: no Sheets/OpenAI/network calls, and never mutates config0, mainHeaderMap0,
    // or intakeHeaderMap0. Mirrors the header-selection logic of the real provisioning
    // phases in CRMOnboard/CRMFieldRegistry: main human fields, then the divider, then
    // main machine fields, then the onboarding-required intake fields. Each header is
    // resolved as config0.getCol(field.key) when non-blank, else field.columnName.
    public static ColumnPlan planColumnAdditions(
        CRMSchemaConfig config0,
        HashMap<String, Integer> mainHeaderMap0,
        HashMap<String, Integer> intakeHeaderMap0)
    {
        ArrayList<String> mainExisting0 = new ArrayList<>();
        ArrayList<String> mainToAdd0 = new ArrayList<>();
        ArrayList<String> intakeExisting0 = new ArrayList<>();
        ArrayList<String> intakeToAdd0 = new ArrayList<>();

        // Local copy so the caller's header map is never mutated. Columns are assigned
        // to simulated additions only to reproduce ensureDivider's placement decision
        // (append vs. insert), which depends on where the last human column lands.
        HashMap<String, Integer> simulatedMainMap0 = new HashMap<>(mainHeaderMap0);

        // Phase 1: human-facing columns (left of divider).
        for (CRMField field0 : CRMFieldRegistry.getMainHumanFields())
        {
            String header0 = resolveHeader(config0, field0);

            if (simulatedMainMap0.containsKey(header0))
            {
                addUnique(mainExisting0, header0);
            }
            else
            {
                addUnique(mainToAdd0, header0);
                simulatedMainMap0.put(header0, nextEmptyColumn(simulatedMainMap0));
            }
        }

        // Phase 2: divider placement.
        String dividerHeader0 = resolveDividerHeader(config0);
        String dividerAction0;

        if (simulatedMainMap0.containsKey(dividerHeader0))
        {
            dividerAction0 = "present";
            addUnique(mainExisting0, dividerHeader0);
        }
        else
        {
            int lastHumanCol0 = 0;
            for (CRMField field0 : CRMFieldRegistry.getMainHumanFields())
            {
                Integer col0 = simulatedMainMap0.get(resolveHeader(config0, field0));
                if (col0 != null && col0 > lastHumanCol0)
                {
                    lastHumanCol0 = col0;
                }
            }

            int nextEmpty0 = nextEmptyColumn(simulatedMainMap0);

            if (lastHumanCol0 == 0 || lastHumanCol0 + 1 >= nextEmpty0)
            {
                dividerAction0 = "append";
                simulatedMainMap0.put(dividerHeader0, nextEmpty0);
            }
            else
            {
                dividerAction0 = "insert";
                simulatedMainMap0.put(dividerHeader0, lastHumanCol0 + 1);
            }
            addUnique(mainToAdd0, dividerHeader0);
        }

        // Phase 3: machine-facing columns (right of divider).
        for (CRMField field0 : CRMFieldRegistry.getMainMachineFields())
        {
            String header0 = resolveHeader(config0, field0);

            if (simulatedMainMap0.containsKey(header0))
            {
                addUnique(mainExisting0, header0);
            }
            else
            {
                addUnique(mainToAdd0, header0);
                simulatedMainMap0.put(header0, nextEmptyColumn(simulatedMainMap0));
            }
        }

        // Intake tab: only the onboarding-required (system-generated) fields.
        for (CRMField field0 : CRMFieldRegistry.getIntakeTabFields())
        {
            if (!field0.includeInOnboarding)
            {
                continue;
            }

            String header0 = resolveHeader(config0, field0);

            if (intakeHeaderMap0.containsKey(header0))
            {
                addUnique(intakeExisting0, header0);
            }
            else
            {
                addUnique(intakeToAdd0, header0);
            }
        }

        return new ColumnPlan(mainExisting0, mainToAdd0, intakeExisting0, intakeToAdd0, dividerAction0);
    }

    // I/O wrapper: builds the CRMSchemaConfig exactly as commit does, reads both tabs'
    // header maps (READ ONLY), and hands off to the pure planner. No writes of any kind.
    public static ColumnPlan planFromApprovedSchema(
        String spreadsheetId0,
        JSONObject approvedSchema0
    ) throws Exception
    {
        CRMSchemaConfig config0 = CRMOnboard.buildConfigFromSchema(
            "preview_config",
            "",
            "",
            spreadsheetId0,
            approvedSchema0
        );

        CRMOnboard.setDefaultMainCrmHeaders(config0);

        HashMap<String, Integer> mainHeaderMap0 = SheetsApp.buildHeaderMap(
            spreadsheetId0, config0.mainTabName, config0.mainTabHeaderRow, 200);

        HashMap<String, Integer> intakeHeaderMap0 = SheetsApp.buildHeaderMap(
            spreadsheetId0, config0.intakeTabName, config0.intakeTabHeaderRow, 200);

        return planColumnAdditions(config0, mainHeaderMap0, intakeHeaderMap0);
    }

    private static String resolveHeader(CRMSchemaConfig config0, CRMField field0)
    {
        String configured0 = config0.getCol(field0.key);
        return isBlank(configured0) ? field0.columnName : configured0;
    }

    private static String resolveDividerHeader(CRMSchemaConfig config0)
    {
        String configured0 = config0.getCol("mainTabDividerCol");
        return isBlank(configured0) ? CRMFieldRegistry.DIVIDER_HEADER : configured0;
    }

    private static void addUnique(ArrayList<String> list0, String value0)
    {
        if (!list0.contains(value0))
        {
            list0.add(value0);
        }
    }

    private static int nextEmptyColumn(HashMap<String, Integer> headerMap0)
    {
        int nextCol0 = 1;
        for (int col0 : headerMap0.values())
        {
            if (col0 >= nextCol0)
            {
                nextCol0 = col0 + 1;
            }
        }
        return nextCol0;
    }

    public static SessionContext commitOnboarding(
        JSONObject input0,
        JSONObject approvedSchema0
    ) throws Exception
    {
        String userId0 = input0.optString("userId", "");
        if (isBlank(userId0))
        {
            userId0 = CRMRegistry.generateUserId();
        }

        String email0 = input0.optString("email", "");
        String fundName0 = input0.optString("fundName", "");
        String spreadsheetId0 = input0.optString("spreadsheetId", "");
        ArrayList<String> internalNames0 = readStringList(input0, "internalNames");
        ArrayList<String> internalEmails0 = readStringList(input0, "internalEmails");
        String internalFundName0 = input0.optString("internalFundName", "");
        String internalWebsite0 = input0.optString("internalWebsite", "");
        String clientSectorTags0 = input0.optString("clientSectorTags", "");
        String clientMicrosectorTags0 = input0.optString("clientMicrosectorTags", "");
        String clientGeography0 = input0.optString("clientGeography", "");
        String clientStages0 = input0.optString("clientStages", "");
        String clientInvestmentThesis0 = input0.optString("clientInvestmentThesis", "");
        String clientProfileJson0 = input0.optString("clientProfileJson", "");

        String configId0 = "config_" + userId0;

        CRMSchemaConfig config0 = CRMOnboard.buildConfigFromSchema(
            configId0,
            userId0,
            fundName0,
            spreadsheetId0,
            approvedSchema0
        );

        CRMOnboard.setDefaultMainCrmHeaders(config0);

        UserAccount user0 = new UserAccount(
            userId0,
            email0,
            fundName0,
            configId0,
            internalNames0,
            internalEmails0,
            internalFundName0,
            internalWebsite0,
            "",
            clientSectorTags0,
            clientMicrosectorTags0,
            clientGeography0,
            clientStages0,
            clientInvestmentThesis0,
            clientProfileJson0
        );

        // Register FIRST so a failure while provisioning columns (e.g. a Sheets
        // rate limit) never loses the info the user just typed in.
        CRMRegistry.registerUser(user0, config0);

        CRMOnboard.ensureSystemIntakeColumnsExist(spreadsheetId0, config0);
        CRMOnboard.ensureRequiredMainCrmColumnsExist(spreadsheetId0, config0);

        // Provisioning can adjust column mappings (divider placement, collision
        // disambiguation) — refresh the already-registered config row in place.
        CRMRegistry.updateConfig(config0);

        return new SessionContext(user0, config0);
    }

    private static ArrayList<String> toStringList(JSONArray array0)
    {
        ArrayList<String> list0 = new ArrayList<>();

        if (array0 == null)
        {
            return list0;
        }

        for (int i = 0; i < array0.length(); i++)
        {
            list0.add(array0.optString(i, ""));
        }

        return list0;
    }

    private static boolean isBlank(String value0)
    {
        return value0 == null || value0.trim().length() == 0;
    }
}
