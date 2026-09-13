package com.liminer.ask;

import java.util.HashMap;

// Seam over the logged-in user's main CRM tab for the ask agent. Production
// implementation is SheetsAskSheetPort (SheetsApp-backed, header-map-first,
// column-by-column, single-cell writes only); tests use an in-memory fake.
public interface AskSheetPort
{
    HashMap<String, Integer> headerMap() throws Exception;

    String readCell(int row, int col) throws Exception;

    String[] readColumn(int col, int fromRow, int toRow) throws Exception;

    String[][] readRow(int row, int fromCol, int toCol) throws Exception;

    int lastRow() throws Exception;

    void writeCell(int row, int col, String value) throws Exception;
}
