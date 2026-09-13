package com.liminer.ask;

import com.liminer.core.SessionContext;
import com.liminer.sheets.SheetsApp;

import java.util.HashMap;

// Production AskSheetPort. Delegates to SheetsApp against the session's main CRM
// tab only. writeCell always calls SheetsApp.updateCell (single cell) -- never
// updateRangeMatrix or updateRange over more than one cell (project spreadsheet rules).
public class SheetsAskSheetPort implements AskSheetPort
{
    private static final int MAX_COLUMNS0 = 100;
    private static final int MAX_ROWS0 = 5000;

    private final SessionContext context;
    private HashMap<String, Integer> cachedHeaderMap;

    public SheetsAskSheetPort(SessionContext context0)
    {
        this.context = context0;
    }

    @Override
    public HashMap<String, Integer> headerMap() throws Exception
    {
        if (cachedHeaderMap == null)
        {
            cachedHeaderMap = SheetsApp.buildHeaderMap(
                context.config.spreadsheetId,
                context.config.mainTabName,
                context.config.mainTabHeaderRow,
                MAX_COLUMNS0
            );
        }

        return cachedHeaderMap;
    }

    @Override
    public String readCell(int row, int col) throws Exception
    {
        return SheetsApp.readCell(
            context.config.spreadsheetId,
            context.config.mainTabName,
            row,
            col
        );
    }

    @Override
    public String[] readColumn(int col, int fromRow, int toRow) throws Exception
    {
        if (toRow < fromRow)
        {
            return new String[0];
        }

        String[][] matrix0 = SheetsApp.readRangeMatrix(
            context.config.spreadsheetId,
            context.config.mainTabName,
            fromRow,
            col,
            toRow,
            col
        );

        String[] values0 = new String[matrix0.length];

        for (int i0 = 0; i0 < matrix0.length; i0++)
        {
            values0[i0] = matrix0[i0][0] == null ? "" : matrix0[i0][0];
        }

        return values0;
    }

    @Override
    public String[][] readRow(int row, int fromCol, int toCol) throws Exception
    {
        return SheetsApp.readRangeMatrix(
            context.config.spreadsheetId,
            context.config.mainTabName,
            row,
            fromCol,
            row,
            toCol
        );
    }

    @Override
    public int lastRow() throws Exception
    {
        return SheetsApp.findLastRow(
            context.config.spreadsheetId,
            context.config.mainTabName,
            1,
            MAX_COLUMNS0,
            MAX_ROWS0
        );
    }

    @Override
    public void writeCell(int row, int col, String value) throws Exception
    {
        SheetsApp.updateCell(
            context.config.spreadsheetId,
            context.config.mainTabName,
            row,
            col,
            value
        );
    }
}
