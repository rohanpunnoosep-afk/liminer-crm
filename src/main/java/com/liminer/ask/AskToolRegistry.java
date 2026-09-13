package com.liminer.ask;

import java.util.List;
import java.util.Map;
import org.json.JSONArray;
import org.json.JSONObject;

// The four tools available to the session-scoped ask agent. Unlike the legacy
// ToolRegistry, there is no sheetName/tabName argument -- the agent has exactly
// one sheet, the logged-in user's own CRM tab, resolved via AskContext.
public class AskToolRegistry
{
    private static final int MAX_FIND_MATCHES0 = 25;
    private static final int MAX_READ_COLUMN_LINES0 = 200;

    public static final List<AskToolSpec> TOOLS = List.of(

        new AskToolSpec(
            "find_investor_rows",
            "Search the CRM for rows whose fund name or contact names contain the given query "
                + "(case-insensitive substring match). Returns at most 25 matching rows.",
            List.of(
                new AskToolArgSpec("query", "The text to search for, e.g. a fund name or a contact's name.", "string")
            ),
            new AskToolExecutor()
            {
                @Override
                public String execute(JSONObject args, AskContext context) throws Exception
                {
                    return findInvestorRows(args.optString("query", ""), context);
                }
            }
        ),

        new AskToolSpec(
            "read_row",
            "Read every non-empty column value for a single CRM row.",
            List.of(
                new AskToolArgSpec("row", "The 1-based sheet row number to read.", "integer")
            ),
            new AskToolExecutor()
            {
                @Override
                public String execute(JSONObject args, AskContext context) throws Exception
                {
                    return readRow(args.getInt("row"), context);
                }
            }
        ),

        new AskToolSpec(
            "read_column",
            "Read every non-empty value in a single CRM column, from the first data row to the last row.",
            List.of(
                new AskToolArgSpec("header", "The exact header text of the column to read.", "string")
            ),
            new AskToolExecutor()
            {
                @Override
                public String execute(JSONObject args, AskContext context) throws Exception
                {
                    return readColumn(args.optString("header", ""), context);
                }
            }
        ),

        new AskToolSpec(
            "propose_cell_update",
            "Stage a proposed update to a single CRM cell for human approval. This does NOT write "
                + "to the spreadsheet -- it only records a proposed before/after change.",
            List.of(
                new AskToolArgSpec("row", "The 1-based sheet row number to update.", "integer"),
                new AskToolArgSpec("header", "The exact header text of the column to update.", "string"),
                new AskToolArgSpec("newValue", "The proposed new value for that cell.", "string")
            ),
            new AskToolExecutor()
            {
                @Override
                public String execute(JSONObject args, AskContext context) throws Exception
                {
                    return proposeCellUpdate(
                        args.getInt("row"),
                        args.optString("header", ""),
                        args.optString("newValue", ""),
                        context
                    );
                }
            }
        )
    );

    public static AskToolSpec getToolByName(String toolName0)
    {
        for (AskToolSpec tool0 : TOOLS)
        {
            if (tool0.name.equals(toolName0))
            {
                return tool0;
            }
        }

        return null;
    }

    public static JSONArray toOpenAiToolsJson()
    {
        JSONArray tools0 = new JSONArray();

        for (AskToolSpec toolSpec0 : TOOLS)
        {
            JSONObject tool0 = new JSONObject();
            tool0.put("type", "function");
            tool0.put("name", toolSpec0.name);
            tool0.put("description", toolSpec0.purpose);
            tool0.put("strict", true);

            JSONObject parameters0 = new JSONObject();
            parameters0.put("type", "object");

            JSONObject properties0 = new JSONObject();
            JSONArray required0 = new JSONArray();

            for (AskToolArgSpec arg0 : toolSpec0.args)
            {
                JSONObject argObject0 = new JSONObject();
                argObject0.put("type", arg0.type);
                argObject0.put("description", arg0.description);

                properties0.put(arg0.name, argObject0);
                required0.put(arg0.name);
            }

            parameters0.put("properties", properties0);
            parameters0.put("required", required0);
            parameters0.put("additionalProperties", false);

            tool0.put("parameters", parameters0);
            tools0.put(tool0);
        }

        return tools0;
    }

    // ------------------------------------------------------------------------
    // TOOL IMPLEMENTATIONS
    // ------------------------------------------------------------------------

    private static String findInvestorRows(String query0, AskContext context) throws Exception
    {
        String needle0 = query0 == null ? "" : query0.trim().toLowerCase();

        if (needle0.isEmpty())
        {
            return "NO MATCHES";
        }

        String[] searchHeaders0 = new String[]
        {
            "Fund Name",
            "Contact 1 First Name",
            "Contact 1 Last Name",
            "Contact 2 First Name",
            "Contact 2 Last Name"
        };

        int fromRow0 = context.session.config.mainTabDataStartRow;
        int toRow0 = context.port.lastRow();

        if (toRow0 < fromRow0)
        {
            return "NO MATCHES";
        }

        int rowCount0 = toRow0 - fromRow0 + 1;

        // Read the five search columns ONE COLUMN AT A TIME (project rule: column-by-column).
        String[][] columns0 = new String[searchHeaders0.length][];

        for (int h0 = 0; h0 < searchHeaders0.length; h0++)
        {
            Integer col0 = context.headerMap.get(searchHeaders0[h0]);

            if (col0 == null)
            {
                columns0[h0] = new String[rowCount0];
                continue;
            }

            columns0[h0] = context.port.readColumn(col0, fromRow0, toRow0);
        }

        StringBuilder result0 = new StringBuilder();
        int matchCount0 = 0;

        for (int i0 = 0; i0 < rowCount0; i0++)
        {
            boolean matched0 = false;

            for (int h0 = 0; h0 < searchHeaders0.length; h0++)
            {
                String value0 = (columns0[h0] != null && i0 < columns0[h0].length) ? columns0[h0][i0] : null;

                if (value0 != null && value0.trim().toLowerCase().contains(needle0))
                {
                    matched0 = true;
                    break;
                }
            }

            if (!matched0)
            {
                continue;
            }

            int row0 = fromRow0 + i0;

            String fundName0 = valueAt(columns0[0], i0);
            String contact1First0 = valueAt(columns0[1], i0);
            String contact1Last0 = valueAt(columns0[2], i0);

            result0
                .append("row=").append(row0)
                .append(" | fund=").append(fundName0)
                .append(" | contact=").append(contact1First0).append(" ").append(contact1Last0)
                .append("\n");

            matchCount0++;

            if (matchCount0 >= MAX_FIND_MATCHES0)
            {
                break;
            }
        }

        if (matchCount0 == 0)
        {
            return "NO MATCHES";
        }

        return result0.toString().trim();
    }

    private static String valueAt(String[] column0, int index0)
    {
        if (column0 == null || index0 >= column0.length || column0[index0] == null)
        {
            return "";
        }

        return column0[index0];
    }

    private static String readRow(int row0, AskContext context) throws Exception
    {
        int maxCol0 = 1;

        for (Integer col0 : context.headerMap.values())
        {
            if (col0 > maxCol0)
            {
                maxCol0 = col0;
            }
        }

        String[][] rowData0 = context.port.readRow(row0, 1, maxCol0);

        StringBuilder result0 = new StringBuilder();

        for (Map.Entry<String, Integer> entry0 : context.headerMap.entrySet())
        {
            int col0 = entry0.getValue();

            if (col0 < 1 || col0 > rowData0[0].length)
            {
                continue;
            }

            String value0 = rowData0[0][col0 - 1];

            if (value0 != null && !value0.trim().isEmpty())
            {
                result0.append(entry0.getKey()).append(": ").append(value0.trim()).append("\n");
            }
        }

        if (result0.length() == 0)
        {
            return "Row " + row0 + " is empty.";
        }

        return result0.toString().trim();
    }

    private static String readColumn(String header0, AskContext context) throws Exception
    {
        Integer col0 = context.headerMap.get(header0);

        if (col0 == null)
        {
            return "ERROR: Unknown header: " + header0;
        }

        int fromRow0 = context.session.config.mainTabDataStartRow;
        int toRow0 = context.port.lastRow();

        if (toRow0 < fromRow0)
        {
            return "Column is empty.";
        }

        String[] values0 = context.port.readColumn(col0, fromRow0, toRow0);

        StringBuilder result0 = new StringBuilder();
        int lineCount0 = 0;

        for (int i0 = 0; i0 < values0.length; i0++)
        {
            String value0 = values0[i0] == null ? "" : values0[i0].trim();

            if (value0.isEmpty())
            {
                continue;
            }

            result0.append("row=").append(fromRow0 + i0).append(" | ").append(value0).append("\n");
            lineCount0++;

            if (lineCount0 >= MAX_READ_COLUMN_LINES0)
            {
                break;
            }
        }

        if (lineCount0 == 0)
        {
            return "Column is empty.";
        }

        return result0.toString().trim();
    }

    private static String proposeCellUpdate(
        int row0,
        String header0,
        String newValue0,
        AskContext context) throws Exception
    {
        Integer col0 = context.headerMap.get(header0);

        if (col0 == null)
        {
            return "ERROR: Unknown header: " + header0;
        }

        if (row0 < context.session.config.mainTabDataStartRow)
        {
            return "ERROR: Row " + row0 + " is above the data start row.";
        }

        String beforeValue0 = context.port.readCell(row0, col0);
        beforeValue0 = beforeValue0 == null ? "" : beforeValue0;

        String fundName0 = "";
        String contactFirstName0 = "";

        Integer fundNameCol0 = context.headerMap.get(context.fundNameHeader);

        if (fundNameCol0 != null)
        {
            String value0 = context.port.readCell(row0, fundNameCol0);
            fundName0 = value0 == null ? "" : value0;
        }

        Integer contactFirstNameCol0 = context.headerMap.get(context.contactFirstNameHeader);

        if (contactFirstNameCol0 != null)
        {
            String value0 = context.port.readCell(row0, contactFirstNameCol0);
            contactFirstName0 = value0 == null ? "" : value0;
        }

        ProposedChange change0 = new ProposedChange(row0, fundName0, contactFirstName0, header0, beforeValue0, newValue0);

        // Replace an existing staged change for the same (row, column) rather than
        // appending a duplicate.
        boolean replaced0 = false;

        for (int i0 = 0; i0 < context.proposals.size(); i0++)
        {
            ProposedChange existing0 = context.proposals.get(i0);

            if (existing0.row == row0 && existing0.column.equals(header0))
            {
                context.proposals.set(i0, change0);
                replaced0 = true;
                break;
            }
        }

        if (!replaced0)
        {
            context.proposals.add(change0);
        }

        return "STAGED: row " + row0 + ", column \"" + header0 + "\", before=\"" + beforeValue0
            + "\", after=\"" + newValue0 + "\". This change has NOT been written yet; it is "
            + "pending human approval.";
    }
}
