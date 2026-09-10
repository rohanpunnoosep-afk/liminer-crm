package com.liminer.harness;

import com.liminer.core.CRMField;
import com.liminer.core.CRMFieldRegistry;
import com.liminer.core.CRMRegistry;
import com.liminer.core.SessionContext;
import com.liminer.sheets.SheetsApp;
import com.liminer.web.WorkflowRegistry;

import org.json.JSONObject;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/**
 * Non-interactive test harness for driving Liminer against a live CRM spreadsheet.
 *
 * It exists so an end-to-end run can be set up, inspected and re-run from a shell
 * without going through AgentMain's interactive menu: resolve the SessionContext from
 * a spreadsheet ID alone, look at individual cells, clear the machine-written columns
 * for a row so a workflow can be redone from scratch, snapshot a tab to CSV, and run
 * any workflow from WorkflowRegistry (the same entry points AgentMain and the web UI
 * use). AgentMain itself is untouched.
 *
 * Every write goes through one column at a time, per the project spreadsheet rules:
 * a header map is built first, the affected row window is computed, and each column is
 * read, modified and written on its own. No row rectangle is ever written.
 *
 *   mvn -q dependency:build-classpath -Dmdep.outputFile=/tmp/cp.txt
 *   java -cp target/classes:$(cat /tmp/cp.txt) \
 *        com.liminer.harness.LiminerHarness &lt;spreadsheetId&gt; &lt;command&gt; [args...]
 *
 * Commands:
 *   context                                  resolve and print the session for this sheet
 *   headers &lt;main|intake&gt;                    print every header and its column number
 *   row &lt;main|intake&gt; &lt;row&gt;                  print every non-blank cell of one row
 *   find &lt;main|intake&gt; &lt;text&gt;                print rows containing text (case-insensitive)
 *   get &lt;main|intake&gt; &lt;row&gt; &lt;header&gt;         read one cell
 *   set &lt;main|intake&gt; &lt;row&gt; &lt;header&gt; &lt;value&gt; write one cell
 *   clear &lt;main|intake&gt; &lt;rows&gt; &lt;headers&gt;     blank the named columns over a row range
 *   reset &lt;rows&gt;                             blank every machine column over a row range
 *   dump &lt;main|intake&gt; &lt;out.csv&gt;             snapshot a tab to CSV
 *   workflows                                list runnable workflow ids
 *   plan &lt;workflowId&gt; [k=v ...]              dry-run a workflow (read-only, where supported)
 *   run &lt;workflowId&gt; [k=v ...]               run a workflow against this sheet
 *
 * &lt;rows&gt; is "12" or "12-18". &lt;headers&gt; is a comma-separated list of header names.
 */
public class LiminerHarness
{
    private static final int MAX_COLUMNS0 = 250;
    private static final int MAX_ROWS0 = 2000;

    public static void main(String[] args0) throws Exception
    {
        if (args0.length < 2)
        {
            printUsage();
            System.exit(2);
            return;
        }

        String spreadsheetId0 = args0[0];
        String command0 = args0[1].toLowerCase();
        String[] rest0 = java.util.Arrays.copyOfRange(args0, 2, args0.length);

        SessionContext context0 = resolveSessionBySpreadsheetId(spreadsheetId0);

        if (context0 == null)
        {
            System.out.println("HARNESS ERROR: no user in the CRM user database owns spreadsheet " + spreadsheetId0);
            System.exit(1);
            return;
        }

        switch (command0)
        {
            case "context":    cmdContext(context0); break;
            case "headers":    cmdHeaders(context0, arg(rest0, 0, "main")); break;
            case "row":        cmdRow(context0, arg(rest0, 0, "main"), Integer.parseInt(arg(rest0, 1, "2"))); break;
            case "find":       cmdFind(context0, arg(rest0, 0, "main"), arg(rest0, 1, "")); break;
            case "get":        cmdGet(context0, arg(rest0, 0, "main"), Integer.parseInt(arg(rest0, 1, "2")), arg(rest0, 2, "")); break;
            case "set":        cmdSet(context0, arg(rest0, 0, "main"), Integer.parseInt(arg(rest0, 1, "2")), arg(rest0, 2, ""), arg(rest0, 3, "")); break;
            case "clear":      cmdClear(context0, arg(rest0, 0, "main"), arg(rest0, 1, ""), splitHeaders(arg(rest0, 2, ""))); break;
            case "reset":      cmdReset(context0, arg(rest0, 0, "")); break;
            case "dump":       cmdDump(context0, arg(rest0, 0, "main"), arg(rest0, 1, "")); break;
            case "workflows":  cmdWorkflows(); break;
            case "plan":       cmdRunOrPlan(context0, rest0, true); break;
            case "run":        cmdRunOrPlan(context0, rest0, false); break;
            default:
                System.out.println("HARNESS ERROR: unknown command \"" + command0 + "\"");
                printUsage();
                System.exit(2);
        }
    }

    // ============================================================
    // SESSION RESOLUTION
    // ============================================================

    /**
     * Finds the user who owns this spreadsheet and logs them in.
     *
     * The registry is keyed by email, so this walks the CRM Configs tab for the
     * matching Client Spreadsheet ID, maps its User ID back to an email in the Users
     * tab, and then goes through the ordinary CRMRegistry.login path so the harness
     * session is byte-for-byte the session a workflow would get in production
     * (including the column-provisioning that login performs).
     */
    public static SessionContext resolveSessionBySpreadsheetId(String spreadsheetId0) throws Exception
    {
        String userId0 = findConfigUserId(spreadsheetId0);

        if (isBlank(userId0))
        {
            return null;
        }

        String email0 = findUserEmail(userId0);

        if (isBlank(email0))
        {
            System.out.println("HARNESS ERROR: config for " + spreadsheetId0
                + " points at user id \"" + userId0 + "\" which has no row in the Users tab.");
            return null;
        }

        System.out.println("Harness session: " + email0 + " (user id " + userId0 + ")");

        return CRMRegistry.login(email0);
    }

    private static String findConfigUserId(String spreadsheetId0) throws Exception
    {
        String[][] rows0 = SheetsApp.readRangeMatrixA1(
            CRMRegistry.CRM_USER_DATABASE_SPREADSHEET_ID,
            CRMRegistry.CONFIGS_TAB,
            CRMRegistry.CONFIGS_READ_RANGE0 + CRMRegistry.MAX_ROWS
        );

        if (rows0 == null || rows0.length < 2)
        {
            return "";
        }

        HashMap<String, Integer> headerMap0 = headerIndexes(rows0[0]);
        int sheetIdCol0 = index(headerMap0, CRMRegistry.CLIENT_SPREADSHEET_ID_HEADER);
        int userIdCol0 = index(headerMap0, CRMRegistry.CONFIG_USER_ID_HEADER);

        for (int i0 = 1; i0 < rows0.length; i0++)
        {
            if (cell(rows0[i0], sheetIdCol0).trim().equals(spreadsheetId0.trim()))
            {
                return cell(rows0[i0], userIdCol0).trim();
            }
        }

        return "";
    }

    private static String findUserEmail(String userId0) throws Exception
    {
        String[][] rows0 = SheetsApp.readRangeMatrixA1(
            CRMRegistry.CRM_USER_DATABASE_SPREADSHEET_ID,
            CRMRegistry.USERS_TAB,
            CRMRegistry.USERS_READ_RANGE0 + CRMRegistry.MAX_ROWS
        );

        if (rows0 == null || rows0.length < 2)
        {
            return "";
        }

        HashMap<String, Integer> headerMap0 = headerIndexes(rows0[0]);
        int userIdCol0 = index(headerMap0, CRMRegistry.USER_ID_HEADER);
        int emailCol0 = index(headerMap0, CRMRegistry.EMAIL_HEADER);

        for (int i0 = 1; i0 < rows0.length; i0++)
        {
            if (cell(rows0[i0], userIdCol0).trim().equals(userId0))
            {
                return cell(rows0[i0], emailCol0).trim();
            }
        }

        return "";
    }

    // ============================================================
    // COMMANDS
    // ============================================================

    private static void cmdContext(SessionContext context0)
    {
        context0.printSummary();
    }

    private static void cmdHeaders(SessionContext context0, String tabKey0) throws Exception
    {
        Tab tab0 = tab(context0, tabKey0);
        HashMap<String, Integer> headerMap0 = headerMap(context0, tab0);

        ArrayList<Map.Entry<String, Integer>> entries0 = new ArrayList<>(headerMap0.entrySet());
        entries0.sort((a0, b0) -> Integer.compare(a0.getValue(), b0.getValue()));

        System.out.println("=== " + tab0.name + " headers (header row " + tab0.headerRow + ") ===");

        for (Map.Entry<String, Integer> entry0 : entries0)
        {
            System.out.println("  " + entry0.getValue() + "\t" + entry0.getKey());
        }
    }

    private static void cmdRow(SessionContext context0, String tabKey0, int rowNumber0) throws Exception
    {
        Tab tab0 = tab(context0, tabKey0);
        HashMap<String, Integer> headerMap0 = headerMap(context0, tab0);
        String[][] rowData0 = SheetsApp.readRangeMatrix(
            context0.config.spreadsheetId, tab0.name, rowNumber0, 1, rowNumber0, MAX_COLUMNS0);

        System.out.println("=== " + tab0.name + " row " + rowNumber0 + " ===");

        ArrayList<Map.Entry<String, Integer>> entries0 = new ArrayList<>(headerMap0.entrySet());
        entries0.sort((a0, b0) -> Integer.compare(a0.getValue(), b0.getValue()));

        for (Map.Entry<String, Integer> entry0 : entries0)
        {
            String value0 = valueAt(rowData0, 0, entry0.getValue());

            if (!isBlank(value0))
            {
                System.out.println("  [" + entry0.getValue() + "] " + entry0.getKey() + " = " + value0);
            }
        }
    }

    private static void cmdFind(SessionContext context0, String tabKey0, String needle0) throws Exception
    {
        Tab tab0 = tab(context0, tabKey0);
        String[][] data0 = SheetsApp.readRangeMatrix(
            context0.config.spreadsheetId, tab0.name, 1, 1, MAX_ROWS0, MAX_COLUMNS0);

        String lowered0 = needle0.toLowerCase();
        int hits0 = 0;

        for (int r0 = tab0.dataStartRow - 1; r0 < data0.length; r0++)
        {
            StringBuilder joined0 = new StringBuilder();

            for (int c0 = 0; c0 < data0[r0].length; c0++)
            {
                joined0.append(safe(data0[r0][c0])).append(' ');
            }

            if (joined0.toString().toLowerCase().contains(lowered0))
            {
                System.out.println("row " + (r0 + 1) + ": " + truncate(joined0.toString().trim(), 200));
                hits0++;
            }
        }

        System.out.println(hits0 + " matching row(s) in " + tab0.name + ".");
    }

    private static void cmdGet(SessionContext context0, String tabKey0, int rowNumber0, String header0) throws Exception
    {
        Tab tab0 = tab(context0, tabKey0);
        int column0 = requireColumn(context0, tab0, header0);
        String[][] cell0 = SheetsApp.readRangeMatrix(
            context0.config.spreadsheetId, tab0.name, rowNumber0, column0, rowNumber0, column0);

        System.out.println(header0 + " @ " + tab0.name + "!" + rowNumber0 + " = " + valueAt(cell0, 0, 1));
    }

    private static void cmdSet(
        SessionContext context0, String tabKey0, int rowNumber0, String header0, String value0) throws Exception
    {
        Tab tab0 = tab(context0, tabKey0);
        int column0 = requireColumn(context0, tab0, header0);

        SheetsApp.updateRangeMatrix(
            context0.config.spreadsheetId, tab0.name, rowNumber0, column0,
            new String[][] { { value0 } });

        System.out.println("SET " + tab0.name + "!" + rowNumber0 + " " + header0 + " = " + value0);
    }

    private static void cmdClear(
        SessionContext context0, String tabKey0, String rowSpec0, List<String> headers0) throws Exception
    {
        if (headers0.isEmpty())
        {
            System.out.println("HARNESS ERROR: clear needs at least one header name.");
            return;
        }

        Tab tab0 = tab(context0, tabKey0);
        int[] rows0 = parseRowSpec(rowSpec0);
        clearColumns(context0, tab0, rows0[0], rows0[1], headers0);
    }

    /**
     * Blanks every machine-side column (everything Liminer writes, i.e. right of the
     * "|| Liminer ||" divider) over a row range on the main tab, so the enrichment
     * workflows can be re-run from a clean slate while the GP's own human columns —
     * names, emails, status — are left exactly as they are.
     */
    private static void cmdReset(SessionContext context0, String rowSpec0) throws Exception
    {
        Tab tab0 = tab(context0, "main");
        int[] rows0 = parseRowSpec(rowSpec0);

        LinkedHashSet<String> headers0 = new LinkedHashSet<>();

        for (CRMField field0 : CRMFieldRegistry.getMainMachineFields())
        {
            String columnName0 = context0.config.getCol(field0.key);
            headers0.add(isBlank(columnName0) ? field0.columnName : columnName0);
        }

        System.out.println("Resetting " + headers0.size() + " machine column(s) on rows "
            + rows0[0] + "-" + rows0[1] + " (human columns untouched).");

        clearColumns(context0, tab0, rows0[0], rows0[1], new ArrayList<>(headers0));
    }

    private static void cmdDump(SessionContext context0, String tabKey0, String outPath0) throws Exception
    {
        if (isBlank(outPath0))
        {
            System.out.println("HARNESS ERROR: dump needs an output file path.");
            return;
        }

        Tab tab0 = tab(context0, tabKey0);
        String[][] data0 = SheetsApp.readRangeMatrix(
            context0.config.spreadsheetId, tab0.name, 1, 1, MAX_ROWS0, MAX_COLUMNS0);

        int lastRow0 = lastPopulatedRow(data0);

        try (BufferedWriter writer0 = Files.newBufferedWriter(
            Paths.get(outPath0), StandardCharsets.UTF_8))
        {
            for (int r0 = 0; r0 <= lastRow0; r0++)
            {
                writeCsvRow(writer0, data0[r0]);
            }
        }

        System.out.println("Wrote " + (lastRow0 + 1) + " row(s) of " + tab0.name + " to " + outPath0);
    }

    private static void cmdWorkflows()
    {
        WorkflowRegistry registry0 = WorkflowRegistry.buildProductionRegistry();

        System.out.println("=== workflows ===");

        for (WorkflowRegistry.WorkflowInfo info0 : registry0.list())
        {
            System.out.println("  " + info0.id + "\t" + info0.name
                + (info0.planner != null ? "\t(plan supported)" : ""));
        }
    }

    private static void cmdRunOrPlan(SessionContext context0, String[] rest0, boolean planOnly0) throws Exception
    {
        if (rest0.length < 1)
        {
            System.out.println("HARNESS ERROR: " + (planOnly0 ? "plan" : "run") + " needs a workflow id.");
            return;
        }

        String workflowId0 = rest0[0];
        WorkflowRegistry registry0 = WorkflowRegistry.buildProductionRegistry();
        WorkflowRegistry.WorkflowInfo info0 = registry0.get(workflowId0);

        if (info0 == null)
        {
            System.out.println("HARNESS ERROR: no workflow with id \"" + workflowId0 + "\".");
            cmdWorkflows();
            return;
        }

        JSONObject params0 = parseParams(rest0);

        if (planOnly0)
        {
            if (info0.planner == null)
            {
                System.out.println("HARNESS ERROR: workflow \"" + workflowId0 + "\" has no dry run.");
                return;
            }

            System.out.println("=== plan " + workflowId0 + " " + params0 + " ===");
            System.out.println(info0.planner.plan(context0, params0).toString(2));
            return;
        }

        System.out.println("=== run " + workflowId0 + " " + params0 + " ===");
        long startedAt0 = System.currentTimeMillis();
        String result0 = info0.handler.run(context0, params0);
        long elapsedMs0 = System.currentTimeMillis() - startedAt0;

        System.out.println(result0);
        System.out.println("=== " + workflowId0 + " finished in " + (elapsedMs0 / 1000) + "s ===");
    }

    // ============================================================
    // COLUMN-BY-COLUMN WRITES
    // ============================================================

    /**
     * Blanks the named columns across [firstRow0, lastRow0]. Each column is read,
     * modified and written on its own so no unrelated column inside the enclosing
     * rectangle is ever touched.
     */
    private static void clearColumns(
        SessionContext context0,
        Tab tab0,
        int firstRow0,
        int lastRow0,
        List<String> headers0) throws Exception
    {
        HashMap<String, Integer> headerMap0 = headerMap(context0, tab0);
        int rowCount0 = lastRow0 - firstRow0 + 1;
        int cleared0 = 0;

        for (String header0 : headers0)
        {
            int column0 = SheetsApp.findColumnInHeaderMap(headerMap0, header0);

            if (column0 == -1)
            {
                System.out.println("  skip (no such column): " + header0);
                continue;
            }

            String[][] columnData0 = new String[rowCount0][1];

            for (int i0 = 0; i0 < rowCount0; i0++)
            {
                columnData0[i0][0] = "";
            }

            SheetsApp.updateRangeMatrix(
                context0.config.spreadsheetId, tab0.name, firstRow0, column0, columnData0);

            System.out.println("  cleared: " + header0);
            cleared0++;
        }

        System.out.println("Cleared " + cleared0 + " column(s) over rows " + firstRow0 + "-" + lastRow0
            + " on " + tab0.name + ".");
    }

    // ============================================================
    // HELPERS
    // ============================================================

    private static class Tab
    {
        String name;
        int headerRow;
        int dataStartRow;
    }

    private static Tab tab(SessionContext context0, String tabKey0)
    {
        Tab tab0 = new Tab();

        if ("intake".equalsIgnoreCase(tabKey0))
        {
            tab0.name = context0.config.intakeTabName;
            tab0.headerRow = context0.config.intakeTabHeaderRow;
            tab0.dataStartRow = context0.config.intakeTabDataStartRow;
        }
        else
        {
            tab0.name = context0.config.mainTabName;
            tab0.headerRow = context0.config.mainTabHeaderRow;
            tab0.dataStartRow = context0.config.mainTabDataStartRow;
        }

        return tab0;
    }

    private static HashMap<String, Integer> headerMap(SessionContext context0, Tab tab0) throws Exception
    {
        return SheetsApp.buildHeaderMap(
            context0.config.spreadsheetId, tab0.name, tab0.headerRow, MAX_COLUMNS0);
    }

    private static int requireColumn(SessionContext context0, Tab tab0, String header0) throws Exception
    {
        int column0 = SheetsApp.findColumnInHeaderMap(headerMap(context0, tab0), header0);

        if (column0 == -1)
        {
            throw new IllegalArgumentException("No column named \"" + header0 + "\" on tab " + tab0.name);
        }

        return column0;
    }

    private static int[] parseRowSpec(String rowSpec0)
    {
        String spec0 = safe(rowSpec0).trim();
        int dashIdx0 = spec0.indexOf('-');

        if (dashIdx0 > 0)
        {
            int first0 = Integer.parseInt(spec0.substring(0, dashIdx0).trim());
            int last0 = Integer.parseInt(spec0.substring(dashIdx0 + 1).trim());
            return new int[] { Math.min(first0, last0), Math.max(first0, last0) };
        }

        int only0 = Integer.parseInt(spec0);
        return new int[] { only0, only0 };
    }

    private static List<String> splitHeaders(String csv0)
    {
        ArrayList<String> headers0 = new ArrayList<>();

        for (String part0 : safe(csv0).split(","))
        {
            if (!isBlank(part0))
            {
                headers0.add(part0.trim());
            }
        }

        return headers0;
    }

    private static JSONObject parseParams(String[] rest0)
    {
        JSONObject params0 = new JSONObject();

        for (int i0 = 1; i0 < rest0.length; i0++)
        {
            int eqIdx0 = rest0[i0].indexOf('=');

            if (eqIdx0 <= 0)
            {
                continue;
            }

            String key0 = rest0[i0].substring(0, eqIdx0);
            String raw0 = rest0[i0].substring(eqIdx0 + 1);

            if (raw0.matches("-?\\d+"))
            {
                params0.put(key0, Integer.parseInt(raw0));
            }
            else if ("true".equalsIgnoreCase(raw0) || "false".equalsIgnoreCase(raw0))
            {
                params0.put(key0, Boolean.parseBoolean(raw0));
            }
            else
            {
                params0.put(key0, raw0);
            }
        }

        return params0;
    }

    private static void writeCsvRow(BufferedWriter writer0, String[] row0) throws IOException
    {
        StringBuilder line0 = new StringBuilder();

        for (int c0 = 0; c0 < row0.length; c0++)
        {
            if (c0 > 0)
            {
                line0.append(',');
            }

            line0.append('"').append(safe(row0[c0]).replace("\"", "\"\"")).append('"');
        }

        writer0.write(line0.toString());
        writer0.newLine();
    }

    private static int lastPopulatedRow(String[][] data0)
    {
        int last0 = 0;

        for (int r0 = 0; r0 < data0.length; r0++)
        {
            for (int c0 = 0; c0 < data0[r0].length; c0++)
            {
                if (!isBlank(data0[r0][c0]))
                {
                    last0 = r0;
                    break;
                }
            }
        }

        return last0;
    }

    private static HashMap<String, Integer> headerIndexes(String[] headerRow0)
    {
        HashMap<String, Integer> map0 = new HashMap<>();

        for (int c0 = 0; c0 < headerRow0.length; c0++)
        {
            String header0 = safe(headerRow0[c0]).trim();

            if (!header0.isEmpty())
            {
                map0.put(header0, c0);
            }
        }

        return map0;
    }

    private static int index(HashMap<String, Integer> map0, String header0)
    {
        Integer value0 = map0.get(header0);
        return value0 == null ? -1 : value0;
    }

    private static String cell(String[] row0, int index0)
    {
        if (index0 < 0 || row0 == null || index0 >= row0.length)
        {
            return "";
        }

        return safe(row0[index0]);
    }

    private static String valueAt(String[][] data0, int rowIdx0, int column0)
    {
        if (data0 == null || rowIdx0 >= data0.length)
        {
            return "";
        }

        int colIdx0 = column0 - 1;

        if (colIdx0 < 0 || colIdx0 >= data0[rowIdx0].length)
        {
            return "";
        }

        return safe(data0[rowIdx0][colIdx0]);
    }

    private static String arg(String[] args0, int index0, String defaultValue0)
    {
        if (index0 >= args0.length)
        {
            return defaultValue0;
        }

        return args0[index0];
    }

    private static String truncate(String value0, int max0)
    {
        if (value0 == null || value0.length() <= max0)
        {
            return safe(value0);
        }

        return value0.substring(0, max0) + "...";
    }

    private static String safe(String value0)
    {
        return value0 == null ? "" : value0;
    }

    private static boolean isBlank(String value0)
    {
        return value0 == null || value0.trim().isEmpty();
    }

    private static void printUsage()
    {
        System.out.println("Usage: LiminerHarness <spreadsheetId> <command> [args...]");
        System.out.println("  context");
        System.out.println("  headers <main|intake>");
        System.out.println("  row <main|intake> <row>");
        System.out.println("  find <main|intake> <text>");
        System.out.println("  get <main|intake> <row> <header>");
        System.out.println("  set <main|intake> <row> <header> <value>");
        System.out.println("  clear <main|intake> <row|row-row> <header,header,...>");
        System.out.println("  reset <row|row-row>");
        System.out.println("  dump <main|intake> <out.csv>");
        System.out.println("  workflows");
        System.out.println("  plan <workflowId> [k=v ...]");
        System.out.println("  run <workflowId> [k=v ...]");
    }
}
