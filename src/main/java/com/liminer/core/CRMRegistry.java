package com.liminer.core;

import com.liminer.sheets.SheetsApp;

import java.util.HashMap;
import java.util.ArrayList;

public class CRMRegistry
{
    public static final String CRM_USER_DATABASE_SPREADSHEET_ID = "1GUBAD6csIMjVY6MtWlTGJM98wT8hTb5nwmElxJBePFM";

    public static final String USERS_TAB = "Users";
    public static final String CONFIGS_TAB = "CRM Configs";

    public static final int MAX_ROWS = 1000;

    public static final String USERS_READ_RANGE0 = "A1:Z";
    public static final String CONFIGS_READ_RANGE0 = "A1:CZ";
    public static final String CONFIGS_HEADER_RANGE0 = "A1:CZ1";

    // USERS TAB HEADERS
    public static final String USER_ID_HEADER = "User ID";
    public static final String EMAIL_HEADER = "Email";
    public static final String FUND_NAME_HEADER = "Fund Name";
    public static final String CRM_CONFIG_ID_HEADER = "CRM Config ID";
    public static final String INTERNAL_NAMES_HEADER = "Internal Names";
    public static final String INTERNAL_EMAILS_HEADER = "Internal Emails";
    public static final String INTERNAL_FUND_NAME_HEADER = "Internal Fund Name";
    public static final String INTERNAL_WEBSITE_HEADER = "Internal Website";
    public static final String USER_EXTRA_DATA_HEADER = "Extra Data";
    public static final String CLIENT_SECTOR_TAGS_HEADER = "Client Sector Tags";
    public static final String CLIENT_MICROSECTOR_TAGS_HEADER = "Client Microsector Tags";
    public static final String CLIENT_GEOGRAPHY_HEADER = "Client Geography";
    public static final String CLIENT_STAGES_HEADER = "Client Stages";
    public static final String CLIENT_INVESTMENT_THESIS_HEADER = "Client Investment Thesis";
    public static final String CLIENT_PROFILE_JSON_HEADER = "Client Profile JSON";

    // CONFIG TAB HEADERS
    public static final String CONFIG_ID_HEADER = "Config ID";
    public static final String CONFIG_USER_ID_HEADER = "User ID";
    public static final String CRM_NAME_HEADER = "CRM Name";
    public static final String CLIENT_SPREADSHEET_ID_HEADER = "Client Spreadsheet ID";

    public static final String MAIN_TAB_NAME_HEADER = "mainTabName";
    public static final String INTAKE_TAB_NAME_HEADER = "intakeTabName";
    public static final String INTERACTIONS_TAB_NAME_HEADER = "interactionsTabName";
    public static final String TASKS_TAB_NAME_HEADER = "tasksTabName";

    public static final String MAIN_TAB_HEADER_ROW_HEADER = "mainTabHeaderRow";
    public static final String MAIN_TAB_DATA_START_ROW_HEADER = "mainTabDataStartRow";

    public static final String INTAKE_TAB_HEADER_ROW_HEADER = "intakeTabHeaderRow";
    public static final String INTAKE_TAB_DATA_START_ROW_HEADER = "intakeTabDataStartRow";

    public static final String CONFIG_EXTRA_DATA_HEADER = "Extra Data";

    public interface FakeRegistry
    {
        String delete() throws Exception;
    }

    private static FakeRegistry fakeRegistry = null;

    public static void setFakeRegistry(FakeRegistry registry)
    {
        fakeRegistry = registry;
    }

    public static void clearFakeRegistry()
    {
        fakeRegistry = null;
    }

    public static SessionContext login(String email) throws Exception
    {
        UserAccount user = loadUserByEmail(email);

        if (user == null)
        {
            System.out.println("No user found for email: " + email);
            return null;
        }

        CRMSchemaConfig config = loadConfigById(user.crmConfigId);

        if (config == null)
        {
            System.out.println("No CRM config found for user: " + email);
            return null;
        }

        SessionContext context0 = new SessionContext(user, config);

        ensureNewColumnsExist(context0);

        return context0;
    }

    private static void ensureNewColumnsExist(SessionContext context0)
    {
        try
        {
            if (isBlank(context0.config.getCol("mainTabInteractionRecordsCol")))
            {
                context0.config.setCol("mainTabInteractionRecordsCol", "Interaction Records");
            }

            ensureColumnInTab(
                context0.config.spreadsheetId,
                context0.config.mainTabName,
                context0.config.mainTabHeaderRow,
                "Interaction Records",
                context0.config.configId
            );

            if (isBlank(context0.config.getCol("intakeTabInteractionRecordCol")))
            {
                context0.config.setCol("intakeTabInteractionRecordCol", "Interaction Record JSON");
            }

            ensureColumnInTab(
                context0.config.spreadsheetId,
                context0.config.intakeTabName,
                context0.config.intakeTabHeaderRow,
                "Interaction Record JSON",
                context0.config.configId
            );

            ensureMainCol(context0, "mainTabCompanyLinkedInCol",            "Company LinkedIn");
            ensureMainCol(context0, "mainTabContactLinkedInAboutCol",        "Contact LinkedIn About");
            ensureMainCol(context0, "mainTabContactPastWorkExperienceCol",   "Contact Past Work Experience JSON");
            ensureMainCol(context0, "mainTabFundLinkedInAboutCol",           "Fund LinkedIn About");
            ensureMainCol(context0, "mainTabContactWebsiteBioUrlCol",        "Contact Website Bio URL");
            ensureMainCol(context0, "mainTabContactWebsiteBioSummaryCol",    "Contact Website Bio Summary");
            ensureMainCol(context0, "mainTabBackgroundCheckStatusCol",       "Background Check Status");
            ensureMainCol(context0, "mainTabBackgroundCheckConfidenceCol",   "Background Check Confidence");
            ensureMainCol(context0, "mainTabLastBackgroundCheckDateCol",     "Last Background Check Date");
            ensureMainCol(context0, "mainTabBackgroundCheckJsonCol",         "Background Check JSON");
            ensureMainCol(context0, "mainTabMarketIntelligenceJsonCol",      "Market Intelligence JSON");

            // Divider + priority-scoring-v2 backfill for sheets onboarded before
            // these features (dividerlists.md step 7, priorityscoringv2 §7).
            // Placement is header-anchored and idempotent: if the divider is already
            // in the sheet header, ensureDivider freezes it (durable in the sheet
            // itself, so it re-resolves by name on every login even if the config
            // row in the user DB never stored it). We deliberately do NOT persist
            // the config here — the sheet header is the durable source, and saving
            // on every login would add needless user-DB writes.
            ensureDividerAndPriorityColumns(context0);
        }
        catch (Exception e0)
        {
            System.out.println("WARNING: Could not ensure new columns exist: " + e0.getMessage());
        }
    }

    private static void ensureDividerAndPriorityColumns(SessionContext context0) throws Exception
    {
        String spreadsheetId0 = context0.config.spreadsheetId;
        String mainTab0       = context0.config.mainTabName;
        int    headerRow0     = context0.config.mainTabHeaderRow;

        if (isBlank(spreadsheetId0) || isBlank(mainTab0))
        {
            return;
        }

        HashMap<String, Integer> headerMap0 = SheetsApp.buildHeaderMap(
            spreadsheetId0, mainTab0, headerRow0, 250);

        // Place the divider after the GP's existing human columns (freeze if present),
        // then provision the Tier-1 priority-signal columns to its right.
        CRMFieldRegistry.ensureDivider(
            context0, spreadsheetId0, mainTab0, headerRow0, headerMap0);

        CRMFieldRegistry.ensurePrioritySignalColumns(
            context0, spreadsheetId0, mainTab0, headerRow0, headerMap0);
    }

    private static void ensureMainCol(
        SessionContext context0,
        String key0,
        String defaultColumnName0) throws Exception
    {
        if (isBlank(context0.config.getCol(key0)))
        {
            context0.config.setCol(key0, defaultColumnName0);
        }

        ensureColumnInTab(
            context0.config.spreadsheetId,
            context0.config.mainTabName,
            context0.config.mainTabHeaderRow,
            context0.config.getCol(key0),
            context0.config.configId
        );
    }

    private static void ensureColumnInTab(
        String spreadsheetId0,
        String tabName0,
        int headerRow0,
        String columnHeader0,
        String configId0) throws Exception
    {
        if (isBlank(spreadsheetId0) || isBlank(tabName0) || isBlank(columnHeader0))
        {
            return;
        }

        HashMap<String, Integer> headerMap0 = SheetsApp.buildHeaderMap(
            spreadsheetId0,
            tabName0,
            headerRow0,
            250
        );

        if (SheetsApp.findColumnInHeaderMap(headerMap0, columnHeader0) != -1)
        {
            return;
        }

        int nextCol0 = 1;

        for (Integer col0 : headerMap0.values())
        {
            if (col0 != null && col0 >= nextCol0)
            {
                nextCol0 = col0 + 1;
            }
        }

        SheetsApp.expandSheetColumnsIfNeeded(spreadsheetId0, tabName0, nextCol0);

        SheetsApp.updateCell(
            spreadsheetId0,
            tabName0,
            headerRow0,
            nextCol0,
            columnHeader0
        );

        System.out.println(
            columnHeader0 + " column not found for client "
            + configId0 + " — created automatically."
        );
    }

    private static boolean isBlank(String value0)
    {
        return value0 == null || value0.trim().length() == 0;
    }

    public static UserAccount loadUserByEmail(String email) throws Exception
    {
        String[][] rows = SheetsApp.readRangeMatrixA1(
            CRM_USER_DATABASE_SPREADSHEET_ID,
            USERS_TAB,
            USERS_READ_RANGE0 + MAX_ROWS
        );

        if (rows == null || rows.length < 2)
        {
            return null;
        }

        HashMap<String, Integer> headerMap = buildHeaderMap(rows[0]);

        for (int i = 1; i < rows.length; i++)
        {
            String rowEmail = getCell(rows[i], headerMap, EMAIL_HEADER);

            if (rowEmail.equalsIgnoreCase(email))
            {
                return new UserAccount(
                    getCell(rows[i], headerMap, USER_ID_HEADER),
                    rowEmail,
                    getCell(rows[i], headerMap, FUND_NAME_HEADER),
                    getCell(rows[i], headerMap, CRM_CONFIG_ID_HEADER),
                    parsePipeSeparatedList(getCell(rows[i], headerMap, INTERNAL_NAMES_HEADER)),
                    parsePipeSeparatedList(getCell(rows[i], headerMap, INTERNAL_EMAILS_HEADER)),
                    getCell(rows[i], headerMap, INTERNAL_FUND_NAME_HEADER),
                    getCell(rows[i], headerMap, INTERNAL_WEBSITE_HEADER),
                    getCell(rows[i], headerMap, USER_EXTRA_DATA_HEADER),
                    getCell(rows[i], headerMap, CLIENT_SECTOR_TAGS_HEADER),
                    getCell(rows[i], headerMap, CLIENT_MICROSECTOR_TAGS_HEADER),
                    getCell(rows[i], headerMap, CLIENT_GEOGRAPHY_HEADER),
                    getCell(rows[i], headerMap, CLIENT_STAGES_HEADER),
                    getCell(rows[i], headerMap, CLIENT_INVESTMENT_THESIS_HEADER),
                    getCell(rows[i], headerMap, CLIENT_PROFILE_JSON_HEADER)
                );
            }
        }

        return null;
    }

    public static CRMSchemaConfig loadConfigById(String configId) throws Exception
    {
        String[][] rows = SheetsApp.readRangeMatrixA1(
            CRM_USER_DATABASE_SPREADSHEET_ID,
            CONFIGS_TAB,
            CONFIGS_READ_RANGE0 + MAX_ROWS
        );

        if (rows == null || rows.length < 2)
        {
            return null;
        }

        HashMap<String, Integer> headerMap = buildHeaderMap(rows[0]);

        for (int i = 1; i < rows.length; i++)
        {
            String rowConfigId = getCell(rows[i], headerMap, CONFIG_ID_HEADER);

            if (rowConfigId.equals(configId))
            {
                return rowToConfig(rows[i], headerMap);
            }
        }

        return null;
    }

    private static CRMSchemaConfig rowToConfig(
        String[] row,
        HashMap<String, Integer> headerMap)
    {
        CRMSchemaConfig config = new CRMSchemaConfig(
            getCell(row, headerMap, CONFIG_ID_HEADER),
            getCell(row, headerMap, CONFIG_USER_ID_HEADER),
            getCell(row, headerMap, CRM_NAME_HEADER),
            getCell(row, headerMap, CLIENT_SPREADSHEET_ID_HEADER)
        );

        config.mainTabName = getCell(row, headerMap, MAIN_TAB_NAME_HEADER);
        config.intakeTabName = getCell(row, headerMap, INTAKE_TAB_NAME_HEADER);
        config.interactionsTabName = getCell(row, headerMap, INTERACTIONS_TAB_NAME_HEADER);
        config.tasksTabName = getCell(row, headerMap, TASKS_TAB_NAME_HEADER);

        config.mainTabHeaderRow = parseIntOrDefault(getCell(row, headerMap, MAIN_TAB_HEADER_ROW_HEADER), 1);
        config.mainTabDataStartRow = parseIntOrDefault(getCell(row, headerMap, MAIN_TAB_DATA_START_ROW_HEADER), 2);
        config.intakeTabHeaderRow = parseIntOrDefault(getCell(row, headerMap, INTAKE_TAB_HEADER_ROW_HEADER), 1);
        config.intakeTabDataStartRow = parseIntOrDefault(getCell(row, headerMap, INTAKE_TAB_DATA_START_ROW_HEADER), 2);

        for (CRMField field : CRMFieldRegistry.getAllFields())
        {
            config.setCol(field.key, getCell(row, headerMap, field.key));
        }

        config.extraData = getCell(row, headerMap, CONFIG_EXTRA_DATA_HEADER);

        return config;
    }

    public static void registerUser(UserAccount user, CRMSchemaConfig config) throws Exception
    {
        saveUser(user);
        saveConfig(config);
    }

    public static void saveUser(UserAccount user) throws Exception
    {
        HashMap<String, Integer> headerMap0 = buildDatabaseHeaderMap(
            USERS_TAB,
            "A1:Z1"
        );

        String[] row0 = buildRowFromHeaderMap(headerMap0);

        putCell(row0, headerMap0, USER_ID_HEADER, user.userId);
        putCell(row0, headerMap0, EMAIL_HEADER, user.email);
        putCell(row0, headerMap0, FUND_NAME_HEADER, user.fundName);
        putCell(row0, headerMap0, CRM_CONFIG_ID_HEADER, user.crmConfigId);
        putCell(row0, headerMap0, INTERNAL_NAMES_HEADER, joinWithPipe(user.internalNames));
        putCell(row0, headerMap0, INTERNAL_EMAILS_HEADER, joinWithPipe(user.internalEmails));
        putCell(row0, headerMap0, INTERNAL_FUND_NAME_HEADER, user.internalFundName);
        putCell(row0, headerMap0, INTERNAL_WEBSITE_HEADER, user.internalWebsite);
        putCell(row0, headerMap0, USER_EXTRA_DATA_HEADER, user.extraData);
        putCell(row0, headerMap0, CLIENT_SECTOR_TAGS_HEADER, user.clientSectorTags);
        putCell(row0, headerMap0, CLIENT_MICROSECTOR_TAGS_HEADER, user.clientMicrosectorTags);
        putCell(row0, headerMap0, CLIENT_GEOGRAPHY_HEADER, user.clientGeography);
        putCell(row0, headerMap0, CLIENT_STAGES_HEADER, user.clientStages);
        putCell(row0, headerMap0, CLIENT_INVESTMENT_THESIS_HEADER, user.clientInvestmentThesis);
        putCell(row0, headerMap0, CLIENT_PROFILE_JSON_HEADER, user.clientProfileJson);

        SheetsApp.appendRow(
            CRM_USER_DATABASE_SPREADSHEET_ID,
            USERS_TAB,
            row0
        );
    }

    public static void saveConfig(CRMSchemaConfig config) throws Exception
    {
        HashMap<String, Integer> headerMap0 = buildDatabaseHeaderMap(
            CONFIGS_TAB,
            CONFIGS_HEADER_RANGE0
        );

        String[] row0 = buildConfigRow(config, headerMap0);

        SheetsApp.appendRow(
            CRM_USER_DATABASE_SPREADSHEET_ID,
            CONFIGS_TAB,
            row0
        );
    }

    // Rewrite an existing config row in place, located by Config ID. Used after
    // onboarding provisions columns (divider placement / collision disambiguation
    // can change mappings after the initial registration). Falls back to appending
    // if the config row does not exist yet. The single-row write covers only
    // config fields this method owns and sets — no unrelated data is in the range.
    public static void updateConfig(CRMSchemaConfig config) throws Exception
    {
        HashMap<String, Integer> headerMap0 = buildDatabaseHeaderMap(
            CONFIGS_TAB,
            CONFIGS_HEADER_RANGE0
        );

        int rowNumber0 = -1;
        Integer configIdIndex0 = headerMap0.get(CONFIG_ID_HEADER);

        if (configIdIndex0 != null && config.configId != null)
        {
            rowNumber0 = SheetsApp.findValueInCol(
                CRM_USER_DATABASE_SPREADSHEET_ID,
                CONFIGS_TAB,
                configIdIndex0 + 1,
                config.configId
            );
        }

        if (rowNumber0 == -1)
        {
            saveConfig(config);
            return;
        }

        String[] row0 = buildConfigRow(config, headerMap0);

        SheetsApp.updateRangeMatrix(
            CRM_USER_DATABASE_SPREADSHEET_ID,
            CONFIGS_TAB,
            rowNumber0,
            1,
            new String[][] { row0 }
        );
    }

    private static String[] buildConfigRow(
        CRMSchemaConfig config,
        HashMap<String, Integer> headerMap0)
    {
        String[] row0 = buildRowFromHeaderMap(headerMap0);

        putCell(row0, headerMap0, CONFIG_ID_HEADER, config.configId);
        putCell(row0, headerMap0, CONFIG_USER_ID_HEADER, config.userId);
        putCell(row0, headerMap0, CRM_NAME_HEADER, config.crmName);
        putCell(row0, headerMap0, CLIENT_SPREADSHEET_ID_HEADER, config.spreadsheetId);

        putCell(row0, headerMap0, MAIN_TAB_NAME_HEADER, config.mainTabName);
        putCell(row0, headerMap0, INTAKE_TAB_NAME_HEADER, config.intakeTabName);
        putCell(row0, headerMap0, INTERACTIONS_TAB_NAME_HEADER, config.interactionsTabName);
        putCell(row0, headerMap0, TASKS_TAB_NAME_HEADER, config.tasksTabName);

        putCell(row0, headerMap0, MAIN_TAB_HEADER_ROW_HEADER, config.mainTabHeaderRow);
        putCell(row0, headerMap0, MAIN_TAB_DATA_START_ROW_HEADER, config.mainTabDataStartRow);
        putCell(row0, headerMap0, INTAKE_TAB_HEADER_ROW_HEADER, config.intakeTabHeaderRow);
        putCell(row0, headerMap0, INTAKE_TAB_DATA_START_ROW_HEADER, config.intakeTabDataStartRow);

        for (CRMField field : CRMFieldRegistry.getAllFields())
        {
            putCell(row0, headerMap0, field.key, config.getCol(field.key));
        }

        putCell(row0, headerMap0, CONFIG_EXTRA_DATA_HEADER, config.extraData);

        return row0;
    }

    private static HashMap<String, Integer> buildHeaderMap(String[] headerRow)
    {
        HashMap<String, Integer> headerMap = new HashMap<>();

        for (int i = 0; i < headerRow.length; i++)
        {
            if (headerRow[i] != null)
            {
                String header = headerRow[i].trim();

                if (!header.equals(""))
                {
                    headerMap.put(header, i);
                }
            }
        }

        return headerMap;
    }

    private static String getCell(
        String[] row,
        HashMap<String, Integer> headerMap,
        String header)
    {
        if (!headerMap.containsKey(header))
        {
            return "";
        }

        int index = headerMap.get(header);

        if (index < 0 || index >= row.length || row[index] == null)
        {
            return "";
        }

        return row[index].trim();
    }

    private static int parseIntOrDefault(String value, int defaultValue)
    {
        try
        {
            if (value == null || value.trim().equals(""))
            {
                return defaultValue;
            }

            return Integer.parseInt(value.trim());
        }
        catch (Exception e)
        {
            return defaultValue;
        }
    }

    private static ArrayList<String> parsePipeSeparatedList(String value)
    {
        ArrayList<String> list = new ArrayList<>();

        if (value == null)
        {
            return list;
        }

        value = value.trim();

        if (value.equals(""))
        {
            return list;
        }

        String[] split = value.split("\\|");

        for (String item : split)
        {
            String trimmed = item.trim();

            if (!trimmed.equals(""))
            {
                list.add(trimmed);
            }
        }

        return list;
    }

    private static String joinWithPipe(ArrayList<String> list)
    {
        if (list == null || list.size() == 0)
        {
            return "";
        }

        String result = "";

        for (int i = 0; i < list.size(); i++)
        {
            if (i > 0)
            {
                result += "|";
            }

            result += list.get(i);
        }

        return result;
    }

    private static HashMap<String, Integer> buildDatabaseHeaderMap(
        String tabName0,
        String range0) throws Exception
    {
        String[][] rows0 = SheetsApp.readRangeMatrixA1(
            CRM_USER_DATABASE_SPREADSHEET_ID,
            tabName0,
            range0
        );

        if (rows0 == null || rows0.length == 0)
        {
            return new HashMap<String, Integer>();
        }

        return buildHeaderMap(rows0[0]);
    }

    private static String[] buildRowFromHeaderMap(
        HashMap<String, Integer> headerMap0)
    {
        int maxIndex0 = -1;

        for (Integer index0 : headerMap0.values())
        {
            if (index0 != null && index0 > maxIndex0)
            {
                maxIndex0 = index0;
            }
        }

        String[] row0 = new String[maxIndex0 + 1];

        for (int i = 0; i < row0.length; i++)
        {
            row0[i] = "";
        }

        return row0;
    }

    private static void putCell(
        String[] row0,
        HashMap<String, Integer> headerMap0,
        String header0,
        Object value0)
    {
        if (!headerMap0.containsKey(header0))
        {
            return;
        }

        int index0 = headerMap0.get(header0);

        if (index0 < 0 || index0 >= row0.length)
        {
            return;
        }

        row0[index0] = value0 == null ? "" : value0.toString();
    }

    public static String deleteUser(
        String email0,
        String userId0) throws Exception
    {
        if (fakeRegistry != null)
        {
            return fakeRegistry.delete();
        }

        if (email0 == null || email0.trim().equals(""))
        {
            return "ERROR: Missing email.";
        }

        if (userId0 == null || userId0.trim().equals(""))
        {
            return "ERROR: Missing user ID.";
        }

        email0 = email0.trim();
        userId0 = userId0.trim();

        int userRowNumber0 = findUserRowByEmailAndUserId(email0, userId0);

        if (userRowNumber0 == -1)
        {
            return "ERROR: No matching user found for email "
                + email0
                + " and user ID "
                + userId0
                + ".";
        }

        int configRowNumber0 = findConfigRowByUserId(userId0);

        if (configRowNumber0 == -1)
        {
            return "ERROR: Matching user found, but no config found for user ID "
                + userId0
                + ". No rows were deleted.";
        }

        SheetsApp.deleteRow(
            CRM_USER_DATABASE_SPREADSHEET_ID,
            CONFIGS_TAB,
            configRowNumber0
        );

        SheetsApp.deleteRow(
            CRM_USER_DATABASE_SPREADSHEET_ID,
            USERS_TAB,
            userRowNumber0
        );

        return "Deleted user and config for email "
            + email0
            + " and user ID "
            + userId0
            + ".";
    }

    private static int findUserRowByEmailAndUserId(
        String email0,
        String userId0) throws Exception
    {
        String[][] rows0 = SheetsApp.readRangeMatrixA1(
            CRM_USER_DATABASE_SPREADSHEET_ID,
            USERS_TAB,
            USERS_READ_RANGE0 + MAX_ROWS
        );

        if (rows0 == null || rows0.length < 2)
        {
            return -1;
        }

        HashMap<String, Integer> headerMap0 = buildHeaderMap(rows0[0]);

        for (int rowIndex0 = 1; rowIndex0 < rows0.length; rowIndex0++)
        {
            String rowEmail0 = getCell(rows0[rowIndex0], headerMap0, EMAIL_HEADER);
            String rowUserId0 = getCell(rows0[rowIndex0], headerMap0, USER_ID_HEADER);

            if (rowEmail0.equalsIgnoreCase(email0) &&
                rowUserId0.equals(userId0))
            {
                return rowIndex0 + 1;
            }
        }

        return -1;
    }

    private static int findConfigRowByUserId(
        String userId0) throws Exception
    {
        String[][] rows0 = SheetsApp.readRangeMatrixA1(
            CRM_USER_DATABASE_SPREADSHEET_ID,
            CONFIGS_TAB,
            CONFIGS_READ_RANGE0 + MAX_ROWS
        );

        if (rows0 == null || rows0.length < 2)
        {
            return -1;
        }

        HashMap<String, Integer> headerMap0 = buildHeaderMap(rows0[0]);

        for (int rowIndex0 = 1; rowIndex0 < rows0.length; rowIndex0++)
        {
            String rowUserId0 = getCell(
                rows0[rowIndex0],
                headerMap0,
                CONFIG_USER_ID_HEADER
            );

            if (rowUserId0.equals(userId0))
            {
                return rowIndex0 + 1;
            }
        }

        return -1;
    }

    public static String generateUserId() throws Exception
    {
        String userId0 = "";

        do
        {
            userId0 = "user_" + java.util.UUID.randomUUID()
                .toString()
                .replace("-", "")
                .substring(0, 12);
        }
        while (userIdExists(userId0));

        return userId0;
    }

    private static boolean userIdExists(String userId0) throws Exception
    {
        String[][] rows0 = SheetsApp.readRangeMatrixA1(
            CRM_USER_DATABASE_SPREADSHEET_ID,
            USERS_TAB,
            USERS_READ_RANGE0 + MAX_ROWS
        );

        if (rows0 == null || rows0.length < 2)
        {
            return false;
        }

        HashMap<String, Integer> headerMap0 = buildHeaderMap(rows0[0]);

        for (int rowIndex0 = 1; rowIndex0 < rows0.length; rowIndex0++)
        {
            String rowUserId0 = getCell(
                rows0[rowIndex0],
                headerMap0,
                USER_ID_HEADER
            );

            if (rowUserId0.equals(userId0))
            {
                return true;
            }
        }

        return false;
    }
}