package com.liminer.sheets;

import java.util.List;

public class SheetRegistry
{
    public static final List<SheetRef> SHEETS = List.of(
        new SheetRef(
            "INTAKE",
            "1WFLCOoYqwQtz17GNqAt91I6i2PpdQxhkZRHeWRE0Kf8",
            List.of("EmailData","EmailData2")
        ),

        new SheetRef(
            "CRM",
            "1H_jXL8BA_GqCHizKDgcurkPIeXPLRWAzQ_p1epZ-d1o",
            List.of("CRMTest")
        )
    );

    public static SheetRef getSheetByName(String sheetName0)
    {
        for (SheetRef sheetRef0 : SHEETS)
        {
            if (sheetRef0.sheetName0.equalsIgnoreCase(sheetName0))
            {
                return sheetRef0;
            }
        }

        return null;
    }

    /** Prefer session-based sheets when user is logged in. */

   

    /** Uses the logged-in user's CRM/intake tab names when session is present. */
    

    /**
     * Maps AI/tool tab names to the user's configured tab when needed
     * (e.g. legacy default CRMTest → actual mainTabName from signup).
     */

}