package com.liminer.sheets;

import java.util.HashMap;

/**
 * Test seam (task 0150) for the header-map/column-read/column-write primitives that
 * LPEnrichmentProcessor and CrmUpdater use to build their read-only workflow plans.
 * Production code always uses {@link #live()}; tests can swap in an in-memory fake so
 * plan endpoints can be verified without touching live Google Sheets.
 */
public interface SheetsIOPort
{
    String[][] readRangeMatrix(
        String spreadsheetId, String tabName, int row1, int col1, int row2, int col2) throws Exception;

    void updateRangeMatrix(
        String spreadsheetId, String tabName, int row1, int col1, String[][] data) throws Exception;

    // Built on readRangeMatrix so real and fake implementations share identical
    // header-map semantics (mirrors SheetsApp.buildHeaderMap).
    default HashMap<String, Integer> buildHeaderMap(
        String spreadsheetId, String tabName, int headerRow, int maxColumns) throws Exception
    {
        String[][] headerData = readRangeMatrix(spreadsheetId, tabName, headerRow, 1, headerRow, maxColumns);

        HashMap<String, Integer> headerMap = new HashMap<>();

        if (headerData == null || headerData.length == 0)
        {
            return headerMap;
        }

        for (int colIndex = 0; colIndex < headerData[0].length; colIndex++)
        {
            String header = headerData[0][colIndex];

            if (header != null && header.trim().length() > 0)
            {
                headerMap.put(header.trim(), colIndex + 1);
            }
        }

        return headerMap;
    }

    static SheetsIOPort live()
    {
        return new SheetsIOPort()
        {
            @Override
            public String[][] readRangeMatrix(
                String spreadsheetId, String tabName, int row1, int col1, int row2, int col2) throws Exception
            {
                return SheetsApp.readRangeMatrix(spreadsheetId, tabName, row1, col1, row2, col2);
            }

            @Override
            public void updateRangeMatrix(
                String spreadsheetId, String tabName, int row1, int col1, String[][] data) throws Exception
            {
                SheetsApp.updateRangeMatrix(spreadsheetId, tabName, row1, col1, data);
            }
        };
    }
}
