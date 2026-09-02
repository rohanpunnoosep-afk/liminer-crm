package com.liminer.core;

import java.util.HashMap;
import java.util.ArrayList;

public class CRMSchemaConfig
{
    public String configId;
    public String userId;
    public String crmName;
    public String spreadsheetId;

    // TAB NAMES
    public String mainTabName;
    public String intakeTabName;
    public String interactionsTabName;
    public String tasksTabName;

    // MAIN CRM TAB STRUCTURE
    public int mainTabHeaderRow;
    public int mainTabDataStartRow;

    // EMAIL INTAKE TAB STRUCTURE
    public int intakeTabHeaderRow;
    public int intakeTabDataStartRow;

    public String extraData;

    private HashMap<String, String> columnMap;

    public CRMSchemaConfig(
        String configId,
        String userId,
        String crmName,
        String spreadsheetId
    )
    {
        this.configId = configId;
        this.userId = userId;
        this.crmName = crmName;
        this.spreadsheetId = spreadsheetId;

        this.mainTabName = "CRM";
        this.intakeTabName = "Email Intake";
        this.interactionsTabName = "Interactions";
        this.tasksTabName = "Tasks";

        this.mainTabHeaderRow = 1;
        this.mainTabDataStartRow = 2;

        this.intakeTabHeaderRow = 1;
        this.intakeTabDataStartRow = 2;

        this.extraData = "";
        this.columnMap = new HashMap<>();
    }

    public String getCol(String key)
    {
        if (key == null)
        {
            return "";
        }
        String value = columnMap.get(key);
        return value == null ? "" : value;
    }

    public void setCol(String key, String value)
    {
        if (key == null)
        {
            return;
        }
        columnMap.put(key, value == null ? "" : value);
    }

    // Convenience accessor for the onboarding divider header (dividerlists.md step 5).
    // Returns the persisted divider column header, or "" if the divider is unplaced.
    public String getDividerHeader()
    {
        return getCol("mainTabDividerCol");
    }

    public void printSummary()
    {
        System.out.println("===== CRM SCHEMA CONFIG =====");

        System.out.println("Config ID: " + configId);
        System.out.println("User ID: " + userId);
        System.out.println("CRM Name: " + crmName);
        System.out.println("Spreadsheet ID: " + spreadsheetId);

        System.out.println();

        System.out.println("----- TABS -----");

        System.out.println("Main Tab Name: " + mainTabName);
        System.out.println("Intake Tab Name: " + intakeTabName);
        System.out.println("Interactions Tab Name: " + interactionsTabName);
        System.out.println("Tasks Tab Name: " + tasksTabName);

        System.out.println();

        System.out.println("----- MAIN CRM TAB -----");

        System.out.println("Main Tab Header Row: " + mainTabHeaderRow);
        System.out.println("Main Tab Data Start Row: " + mainTabDataStartRow);

        System.out.println();

        ArrayList<CRMField> mainFields = CRMFieldRegistry.getMainTabFields();
        for (int i = 0; i < mainFields.size(); i++)
        {
            CRMField field = mainFields.get(i);
            if (field.includeInSummary)
            {
                System.out.println(field.displayName + " Header: " + getCol(field.key));
            }
        }

        System.out.println();

        System.out.println("----- EMAIL INTAKE TAB -----");

        System.out.println("Intake Tab Header Row: " + intakeTabHeaderRow);
        System.out.println("Intake Tab Data Start Row: " + intakeTabDataStartRow);

        System.out.println();

        ArrayList<CRMField> intakeFields = CRMFieldRegistry.getIntakeTabFields();
        for (int i = 0; i < intakeFields.size(); i++)
        {
            CRMField field = intakeFields.get(i);
            if (field.includeInSummary)
            {
                System.out.println(field.displayName + " Header: " + getCol(field.key));
            }
        }

        System.out.println();

        System.out.println("----- EXTRA -----");

        System.out.println("Extra Data: " + extraData);

        System.out.println("================================");
    }
}
