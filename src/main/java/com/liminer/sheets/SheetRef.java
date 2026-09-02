package com.liminer.sheets;

import java.util.List;

public class SheetRef
{
    public String sheetName0;
    public String spreadsheetId0;
    public List<String> allowedTabs0;

    public SheetRef(String sheetName0, String spreadsheetId0, List<String> allowedTabs0)
    {
        this.sheetName0 = sheetName0;
        this.spreadsheetId0 = spreadsheetId0;
        this.allowedTabs0 = allowedTabs0;
    }

    public boolean hasTab(String tabName0)
    {
        for (String allowedTab0 : allowedTabs0)
        {
            if (allowedTab0.equalsIgnoreCase(tabName0))
            {
                return true;
            }
        }

        return false;
    }
}