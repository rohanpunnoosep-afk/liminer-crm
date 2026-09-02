package com.liminer.llm;

import com.liminer.sheets.SheetCall;
import com.liminer.sheets.SheetsApp;

import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;

public class ToolRegistry
{
    public static final List<ToolSpec> TOOLS = List.of(
        new ToolSpec(
            "update_by_header_and_lookup",
            "Find the row where one header matches a value, then update a different header in that same row.",
            List.of(
                new ToolArgSpec("lookupHeader", "The header used to identify the row.", "string"),
                new ToolArgSpec("lookupVal", "The value to find in the lookup header column.", "string"),
                new ToolArgSpec("targetHeader", "The header of the field to update.", "string"),
                new ToolArgSpec("newValue", "The new value to write into the target field.", "string")
            ),
            new ToolExecutor()
            {
                public String execute(ToolCall toolCall0, SheetCall sheetCall0) throws Exception
                {
                    return SheetsApp.updateByHeaderAndLookup(
                        sheetCall0.spreadsheetId0,
                        sheetCall0.tabName0,
                        toolCall0.arg1,
                        toolCall0.arg2,
                        toolCall0.arg3,
                        toolCall0.arg4
                    );
                }
            }
        ),

        new ToolSpec(
            "read_by_header_and_lookup",
            "Find the row where one header matches a value, then read the value under a different header in that same row.",
            List.of(
                new ToolArgSpec("lookupHeader", "The header used to identify the row.", "string"),
                new ToolArgSpec("lookupVal", "The value to find in the lookup header column.", "string"),
                new ToolArgSpec("targetHeader", "The header of the field to read.", "string")
            ),
            new ToolExecutor()
            {
                public String execute(ToolCall toolCall0, SheetCall sheetCall0) throws Exception
                {
                    return SheetsApp.readByHeaderAndLookup(
                        sheetCall0.spreadsheetId0,
                        sheetCall0.tabName0,
                        toolCall0.arg1,
                        toolCall0.arg2,
                        toolCall0.arg3
                    );
                }
            }
        ),

        new ToolSpec(
            "update_cell_by_header_and_find",
            "Find a value within a specific header column and replace that same matching cell with a new value.",
            List.of(
                new ToolArgSpec("header", "The header of the column to search in.", "string"),
                new ToolArgSpec("lookupVal", "The value to find in that column.", "string"),
                new ToolArgSpec("updateVal", "The replacement value for the matching cell.", "string")
            ),
            new ToolExecutor()
            {
                public String execute(ToolCall toolCall0, SheetCall sheetCall0) throws Exception
                {
                    return SheetsApp.updateCellByHeaderandFind(
                        sheetCall0.spreadsheetId0,
                        sheetCall0.tabName0,
                        toolCall0.arg1,
                        toolCall0.arg2,
                        toolCall0.arg3
                    );
                }
            }
        ),

        new ToolSpec(
            "read_range_a1",
            "Read a rectangular range from the spreadsheet using A1 notation, such as A1:C10. Use this when the user asks to read multiple cells, rows, columns, or a block of spreadsheet data.",
            List.of(
                new ToolArgSpec("range", "The A1 notation range to read, such as A1:C10.", "string")
            ),
            new ToolExecutor()
            {
                public String execute(ToolCall toolCall0, SheetCall sheetCall0) throws Exception
                {
                    String[][] matrix0 = SheetsApp.readRangeMatrixA1(
                        sheetCall0.spreadsheetId0,
                        sheetCall0.tabName0,
                        toolCall0.arg1
                    );

                    StringBuilder result0 = new StringBuilder();

                    result0.append("{\n");

                    for (int rowIndex0 = 0; rowIndex0 < matrix0.length; rowIndex0++)
                    {
                        result0.append("{");

                        for (int colIndex0 = 0; colIndex0 < matrix0[rowIndex0].length; colIndex0++)
                        {
                            result0.append(matrix0[rowIndex0][colIndex0]);

                            if (colIndex0 < matrix0[rowIndex0].length - 1)
                            {
                                result0.append(",");
                            }
                        }

                        result0.append("}");

                        if (rowIndex0 < matrix0.length - 1)
                        {
                            result0.append("\n");
                        }
                    }

                    result0.append("\n}");

                    return result0.toString();
                }
            }
        ),

        new ToolSpec(
            "update_range_matrix",
            "Update a rectangular spreadsheet range starting at a given row and column using a 2D matrix of values.",
            List.of(
                new ToolArgSpec(
                    "row1",
                    "The starting row number where the matrix should be written.",
                    "integer"
                ),
                new ToolArgSpec(
                    "col1",
                    "The starting column number where the matrix should be written.",
                    "integer"
                ),
                new ToolArgSpec(
                    "matrixText",
                    "A 2D array of values in JSON string format. Example 2x3 matrix: [[\"Alice\",\"Meetings\",\"Fund A\"],[\"Bob\",\"Rejected\",\"Fund B\"]].",
                    "string"
                )
            ),
            new ToolExecutor()
            {
                public String execute(ToolCall toolCall0, SheetCall sheetCall0) throws Exception
                {
                    String[][] matrix0 = parseMatrixJson(toolCall0.arg1);

                    SheetsApp.updateRangeMatrix(
                        sheetCall0.spreadsheetId0,
                        sheetCall0.tabName0,
                        toolCall0.arg11,
                        toolCall0.arg12,
                        matrix0
                    );

                    return "SUCCESS: Updated matrix in sheet "
                        + sheetCall0.sheetName0
                        + ", tab "
                        + sheetCall0.tabName0
                        + ", starting at row "
                        + toolCall0.arg11
                        + ", column "
                        + toolCall0.arg12
                        + ".";
                }
            }
        )
    );

    public static ToolSpec getToolByName(String toolName)
    {
        for (ToolSpec tool : TOOLS)
        {
            if (tool.name.equals(toolName))
            {
                return tool;
            }
        }

        return null;
    }

    private static String[][] parseMatrixJson(String matrixText0)
    {
        JSONArray outerArray0 = new JSONArray(matrixText0);

        String[][] matrix0 = new String[outerArray0.length()][];

        for (int rowIndex0 = 0; rowIndex0 < outerArray0.length(); rowIndex0++)
        {
            JSONArray rowArray0 = outerArray0.getJSONArray(rowIndex0);

            matrix0[rowIndex0] = new String[rowArray0.length()];

            for (int colIndex0 = 0; colIndex0 < rowArray0.length(); colIndex0++)
            {
                Object value0 = rowArray0.get(colIndex0);
                matrix0[rowIndex0][colIndex0] = value0 == JSONObject.NULL ? "" : value0.toString();
            }
        }

        return matrix0;
    }
}