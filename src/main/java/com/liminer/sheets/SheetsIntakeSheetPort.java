package com.liminer.sheets;

import com.liminer.core.CRMField;
import com.liminer.core.CRMFieldRegistry;
import com.liminer.core.SessionContext;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

// Production IntakeSheetPort. Every read/write here is header-map-first and
// column-by-column: no broad row rectangles, and no touching columns outside
// the ones this class is explicitly asked for. A rectangular range write
// silently overwrites the GP's own columns inside the rectangle — see README
// "Sheets I/O".
public class SheetsIntakeSheetPort implements IntakeSheetPort
{
    private static final int MAX_COLUMNS0 = 100;
    private static final int MAX_ROWS0 = 5000;

    // Fixed write order for a newly appended intake row. Processing Status and every
    // downstream AI-extraction column are intentionally left out so
    // EmailIntakeProcessor.processUnprocessedIntakeRows treats the row as unprocessed.
    private static final String[] APPEND_FIELD_KEYS0 = new String[]
    {
        "intakeTabIntakeIdCol",
        "intakeTabGmailMessageIdCol",
        "intakeTabGmailThreadIdCol",
        "intakeTabIntakeTypeCol",
        "intakeTabTimestampCol",
        "intakeTabToCol",
        "intakeTabFromCol",
        "intakeTabSubjectCol",
        "intakeTabBodyCol"
    };

    private final SessionContext context;

    public SheetsIntakeSheetPort(SessionContext context0)
    {
        this.context = context0;
    }

    @Override
    public List<String> readColumnValues(String fieldKey0) throws Exception
    {
        CRMField field0 = CRMFieldRegistry.getByKey(fieldKey0);

        if (field0 == null)
        {
            throw new Exception("Unknown CRM field key: " + fieldKey0);
        }

        String tabName0 = tabNameFor(field0.tabGroup);
        int headerRow0 = headerRowFor(field0.tabGroup);

        HashMap<String, Integer> headerMap0 = SheetsApp.buildHeaderMap(
            context.config.spreadsheetId,
            tabName0,
            headerRow0,
            MAX_COLUMNS0
        );

        int column0 = SheetsApp.findColumnInHeaderMap(
            headerMap0,
            context.config.getCol(fieldKey0)
        );

        List<String> values0 = new ArrayList<>();

        if (column0 == -1)
        {
            return values0;
        }

        int lastRow0 = SheetsApp.findLastRow(
            context.config.spreadsheetId,
            tabName0,
            column0,
            column0,
            MAX_ROWS0
        );

        int dataStartRow0 = dataStartRowFor(field0.tabGroup);

        if (lastRow0 < dataStartRow0)
        {
            return values0;
        }

        String[][] columnData0 = SheetsApp.readRangeMatrix(
            context.config.spreadsheetId,
            tabName0,
            dataStartRow0,
            column0,
            lastRow0,
            column0
        );

        for (String[] row0 : columnData0)
        {
            String value0 = row0[0] == null ? "" : row0[0].trim();

            if (value0.length() > 0)
            {
                values0.add(value0);
            }
        }

        return values0;
    }

    @Override
    public void appendIntakeRows(List<Map<String, String>> rows0) throws Exception
    {
        if (rows0 == null || rows0.isEmpty())
        {
            return;
        }

        String tabName0 = context.config.intakeTabName;

        HashMap<String, Integer> headerMap0 = SheetsApp.buildHeaderMap(
            context.config.spreadsheetId,
            tabName0,
            context.config.intakeTabHeaderRow,
            MAX_COLUMNS0
        );

        int lastRow0 = SheetsApp.findLastRow(
            context.config.spreadsheetId,
            tabName0,
            1,
            MAX_COLUMNS0,
            MAX_ROWS0
        );

        int startRow0 = Math.max(lastRow0 + 1, context.config.intakeTabDataStartRow);

        for (String fieldKey0 : APPEND_FIELD_KEYS0)
        {
            String header0 = context.config.getCol(fieldKey0);

            int column0 = SheetsApp.findColumnInHeaderMap(headerMap0, header0);

            if (column0 == -1)
            {
                throw new Exception("Header not found in intake tab: " + header0);
            }

            String[][] columnMatrix0 = new String[rows0.size()][1];

            for (int rowIndex0 = 0; rowIndex0 < rows0.size(); rowIndex0++)
            {
                String value0 = rows0.get(rowIndex0).get(fieldKey0);
                columnMatrix0[rowIndex0][0] = value0 == null ? "" : value0;
            }

            SheetsApp.updateRangeMatrix(
                context.config.spreadsheetId,
                tabName0,
                startRow0,
                column0,
                columnMatrix0
            );
        }
    }

    private String tabNameFor(String tabGroup0)
    {
        if ("main".equals(tabGroup0))
        {
            return context.config.mainTabName;
        }

        return context.config.intakeTabName;
    }

    private int headerRowFor(String tabGroup0)
    {
        if ("main".equals(tabGroup0))
        {
            return context.config.mainTabHeaderRow;
        }

        return context.config.intakeTabHeaderRow;
    }

    private int dataStartRowFor(String tabGroup0)
    {
        if ("main".equals(tabGroup0))
        {
            return context.config.mainTabDataStartRow;
        }

        return context.config.intakeTabDataStartRow;
    }
}
