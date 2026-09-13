package com.liminer.ask;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

public class AskApplier
{
    // Keeps every written value under the project's 50,000-character cell ceiling.
    private static final int MAX_CELL_LENGTH0 = 50000;

    public static class ApplyResult
    {
        public int appliedCount;
        public List<String> failures = new ArrayList<>();
    }

    public static ApplyResult apply(AskSheetPort port, List<ProposedChange> changes) throws Exception
    {
        ApplyResult result0 = new ApplyResult();

        if (changes == null || changes.isEmpty())
        {
            return result0;
        }

        HashMap<String, Integer> headerMap0 = port.headerMap();

        for (ProposedChange change0 : changes)
        {
            try
            {
                Integer col0 = headerMap0.get(change0.column);

                if (col0 == null)
                {
                    result0.failures.add("Unknown header \"" + change0.column + "\" for row " + change0.row);
                    continue;
                }

                String afterValue0 = change0.afterValue == null ? "" : change0.afterValue;

                if (afterValue0.length() > MAX_CELL_LENGTH0)
                {
                    afterValue0 = afterValue0.substring(0, MAX_CELL_LENGTH0);
                }

                port.writeCell(change0.row, col0, afterValue0);
                result0.appliedCount++;
            }
            catch (Exception e0)
            {
                result0.failures.add("Row " + change0.row + ", column \"" + change0.column + "\": " + e0.getMessage());
            }
        }

        return result0;
    }
}
