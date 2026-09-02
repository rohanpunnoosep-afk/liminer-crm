package com.liminer.sheets;

import com.google.api.client.googleapis.javanet.GoogleNetHttpTransport;
import com.google.api.client.http.javanet.NetHttpTransport;
import com.google.api.client.json.gson.GsonFactory;
import com.google.api.client.json.JsonFactory;
import com.google.api.services.sheets.v4.Sheets;
import com.google.api.services.sheets.v4.SheetsScopes;
import com.google.api.services.sheets.v4.model.ValueRange;
import com.google.api.client.googleapis.auth.oauth2.GoogleClientSecrets;
import com.google.api.client.googleapis.auth.oauth2.GoogleAuthorizationCodeFlow;
import com.google.api.client.auth.oauth2.Credential;
import com.google.api.client.extensions.java6.auth.oauth2.AuthorizationCodeInstalledApp;
import com.google.api.client.extensions.jetty.auth.oauth2.LocalServerReceiver;
import com.google.api.client.util.store.FileDataStoreFactory;
import com.google.api.client.auth.oauth2.TokenResponseException;
import com.google.api.client.googleapis.services.AbstractGoogleClientRequest;
import com.google.api.client.googleapis.json.GoogleJsonResponseException;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.Collections;
import java.util.List;

public class SheetsApp {

    private static final String APPLICATION_NAME = "Sheets Test";
    private static final JsonFactory JSON_FACTORY = GsonFactory.getDefaultInstance();
    private static final String TOKENS_DIRECTORY_PATH = "tokens";
    private static final List<String> SCOPES = Collections.singletonList(SheetsScopes.SPREADSHEETS);

    private static final String SPREADSHEET_ID = "1H_jXL8BA_GqCHizKDgcurkPIeXPLRWAzQ_p1epZ-d1o";
    private static final String SPREADSHEET_ID2 = "1WFLCOoYqwQtz17GNqAt91I6i2PpdQxhkZRHeWRE0Kf8";
    private static final String TAB_NAME = "Sheet1";
    private static final String TAB_NAME2 = "StudyOptimization";

    public static void main(String[] args) throws Exception 
    {
    }

    public static void ensureValidGoogleToken() throws Exception
    {
        final NetHttpTransport httpTransport = GoogleNetHttpTransport.newTrustedTransport();

        try
        {
            Credential credential0 = getCredentials(httpTransport);

            if (credential0.getExpiresInSeconds() == null ||
                credential0.getExpiresInSeconds() <= 60)
            {
                credential0.refreshToken();
            }
        }
        catch (TokenResponseException exception0)
        {
            String content0 = exception0.getContent();

            if (content0 != null && content0.contains("invalid_grant"))
            {
                System.out.println("Google token expired or revoked.");
                System.out.println("Deleting saved token and reopening Google login...");

                deleteTokensFolder();

                Credential newCredential0 = getCredentials(httpTransport);

                if (newCredential0.getExpiresInSeconds() == null ||
                    newCredential0.getExpiresInSeconds() <= 60)
                {
                    newCredential0.refreshToken();
                }

                return;
            }

            throw exception0;
        }
    }

    public static java.util.HashMap<String, Integer> buildHeaderMap(
    String spreadsheetId0,
    String tabName0,
    int headerRow0,
    int maxColumns0) throws Exception
    {
        String[][] headerData0 = readRangeMatrix(
            spreadsheetId0,
            tabName0,
            headerRow0,
            1,
            headerRow0,
            maxColumns0
        );

        java.util.HashMap<String, Integer> headerMap0 = new java.util.HashMap<>();

        if (headerData0 == null || headerData0.length == 0)
        {
            return headerMap0;
        }

        for (int colIndex0 = 0; colIndex0 < headerData0[0].length; colIndex0++)
        {
            String header0 = headerData0[0][colIndex0];

            if (header0 != null && header0.trim().length() > 0)
            {
                headerMap0.put(header0.trim(), colIndex0 + 1);
            }
        }

        return headerMap0;
    }

    public static int findColumnInHeaderMap(
        java.util.HashMap<String, Integer> headerMap0,
        String header0)
    {
        if (headerMap0 == null || header0 == null || header0.trim().length() == 0)
        {
            return -1;
        }

        Integer column0 = headerMap0.get(header0.trim());

        if (column0 == null)
        {
            return -1;
        }

        return column0;
    }

    public static int findLastRow(
    String spreadsheetId0,
    String tabName0,
    int col1,
    int col2,
    int rowMax0) throws Exception
    {
        String[][] data0 = readRangeMatrix(
            spreadsheetId0,
            tabName0,
            1,
            col1,
            rowMax0,
            col2
        );

        for (int rowIndex0 = data0.length - 1; rowIndex0 >= 0; rowIndex0--)
        {
            for (int colIndex0 = 0; colIndex0 < data0[rowIndex0].length; colIndex0++)
            {
                String value0 = data0[rowIndex0][colIndex0];

                if (value0 != null && value0.trim().length() > 0)
                {
                    return rowIndex0 + 1;
                }
            }
        }

        return 0;
    }

    public static int findLastRow(String[][] data0) throws Exception
    {
        for (int rowIndex0 = data0.length - 1; rowIndex0 >= 0; rowIndex0--)
        {
            for (int colIndex0 = 0; colIndex0 < data0[rowIndex0].length; colIndex0++)
            {
                String value0 = data0[rowIndex0][colIndex0];

                if (value0 != null && value0.trim().length() > 0)
                {
                    return rowIndex0 + 1;
                }
            }
        }

        return 0;
    }

    public static void appendRow(
    String spreadsheetId0,
    String tabName0,
    String[] rowData0) throws Exception
    {
        int startColumn0 = 1;
        int endColumn0 = rowData0.length;

        appendRow(
            spreadsheetId0,
            tabName0,
            startColumn0,
            endColumn0,
            rowData0
        );
    }

    public static void appendRow(
    String spreadsheetId0,
    String tabName0,
    int startColumn0,
    int endColumn0,
    String[] rowData0) throws Exception
    {
        ensureValidGoogleToken();

        Sheets service = getSheetsService();

        List<List<Object>> values0 = new java.util.ArrayList<>();
        List<Object> row0 = new java.util.ArrayList<>();

        for (int i0 = 0; i0 < rowData0.length; i0++)
        {
            row0.add(rowData0[i0]);
        }

        values0.add(row0);

        ValueRange body0 = new ValueRange().setValues(values0);

        String range0 = "'"
            + tabName0
            + "'!"
            + columnToLetters(startColumn0)
            + "1:"
            + columnToLetters(endColumn0)
            + "1";

        executeWithRetry(
            service.spreadsheets()
                .values()
                .append(
                    spreadsheetId0,
                    range0,
                    body0
                )
                .setValueInputOption("USER_ENTERED")
                .setInsertDataOption("INSERT_ROWS")
        );

        System.out.println("Appended new row to range: " + range0);
    }

    public static void updateCell(int row, int column, String input) throws Exception
    {
        updateCell(SPREADSHEET_ID, TAB_NAME, row, column, input);
    }

    public static void updateCell(
        String spreadsheetId0,
        String tabName0,
        int row,
        int column,
        String input) throws Exception
    {
        updateRange(spreadsheetId0, tabName0, row, column, row, column, input);
    }

    public static void updateRange(int row1, int col1, int row2, int col2, String input) throws Exception
    {
        updateRange(SPREADSHEET_ID, TAB_NAME, row1, col1, row2, col2, input);
    }

    public static void updateRange(
        String spreadsheetId0,
        String tabName0,
        int row1,
        int col1,
        int row2,
        int col2,
        String input) throws Exception
    {
        ensureValidGoogleToken();

        Sheets service = getSheetsService();

        int rowSize = row2 - row1 + 1;
        int colSize = col2 - col1 + 1;

        String range = toA1Range(tabName0, row1, col1, row2, col2);

        List<List<Object>> values = new java.util.ArrayList<>();

        for (int rowIndex = 0; rowIndex < rowSize; rowIndex++)
        {
            List<Object> row = new java.util.ArrayList<>();

            for (int colIndex = 0; colIndex < colSize; colIndex++)
            {
                row.add(input);
            }

            values.add(row);
        }

        ValueRange body = new ValueRange().setValues(values);

        executeWithRetry(
            service.spreadsheets()
                .values()
                .update(spreadsheetId0, range, body)
                .setValueInputOption("USER_ENTERED")
        );

        System.out.println("Updated range: " + range);
    }

    public static String readCell(int row, int column) throws Exception
    {
        return readCell(SPREADSHEET_ID, TAB_NAME, row, column);
    }

    public static String readCell(
        String spreadsheetId0,
        String tabName0,
        int row,
        int column) throws Exception
    {
        String[][] values = readRangeMatrix(spreadsheetId0, tabName0, row, column, row, column);
        return values[0][0];
    }

    public static String[][] readRangeMatrixA1(String range0) throws Exception
    {
        return readRangeMatrixA1(SPREADSHEET_ID, TAB_NAME, range0);
    }

    public static String[][] readRangeMatrixA1(
        String spreadsheetId0,
        String tabName0,
        String range0) throws Exception
    {
        int[] coordinates0 = parseA1Range(range0);

        return readRangeMatrix(
            spreadsheetId0,
            tabName0,
            coordinates0[0],
            coordinates0[1],
            coordinates0[2],
            coordinates0[3]
        );
    }

    public static String[][] readRangeMatrix(int row1, int col1, int row2, int col2) throws Exception
    {
        return readRangeMatrix(SPREADSHEET_ID, TAB_NAME, row1, col1, row2, col2);
    }

    public static String[][] readRangeMatrix(
        String spreadsheetId0,
        String tabName0,
        int row1,
        int col1,
        int row2,
        int col2) throws Exception
    {
        ensureValidGoogleToken();

        Sheets service = getSheetsService();

        int rowSize = row2 - row1 + 1;
        int colSize = col2 - col1 + 1;

        String range = toA1Range(tabName0, row1, col1, row2, col2);

        ValueRange response = executeWithRetry(
            service.spreadsheets()
                .values()
                .get(spreadsheetId0, range)
        );

        List<List<Object>> values = response.getValues();
        String[][] array = new String[rowSize][colSize];

        for (int rowIndex = 0; rowIndex < rowSize; rowIndex++)
        {
            for (int colIndex = 0; colIndex < colSize; colIndex++)
            {
                if (values == null || rowIndex >= values.size())
                {
                    array[rowIndex][colIndex] = "";
                }
                else if (colIndex >= values.get(rowIndex).size())
                {
                    array[rowIndex][colIndex] = "";
                }
                else
                {
                    array[rowIndex][colIndex] = values.get(rowIndex).get(colIndex).toString();
                }
            }
        }

        return array;
    }

    public static void updateRangeMatrix(int row1, int col1, String[][] data) throws Exception
    {
        updateRangeMatrix(SPREADSHEET_ID, TAB_NAME, row1, col1, data);
    }

    public static void updateRangeMatrix(
        String spreadsheetId0,
        String tabName0,
        int row1,
        int col1,
        String[][] data) throws Exception
    {
        ensureValidGoogleToken();

        Sheets service = getSheetsService();

        int rowCount = data.length;
        int colCount = data[0].length;

        int row2 = row1 + rowCount - 1;
        int col2 = col1 + colCount - 1;

        String range = toA1Range(tabName0, row1, col1, row2, col2);

        List<List<Object>> values = new java.util.ArrayList<>();

        for (int r = 0; r < rowCount; r++)
        {
            List<Object> row = new java.util.ArrayList<>();

            for (int c = 0; c < colCount; c++)
            {
                row.add(data[r][c]);
            }

            values.add(row);
        }

        ValueRange body = new ValueRange().setValues(values);

        executeWithRetry(
            service.spreadsheets()
                .values()
                .update(spreadsheetId0, range, body)
                .setValueInputOption("USER_ENTERED")
        );

        System.out.println("Updated matrix range: " + range);
    }

    public static int findValueInCol(int column, String value) throws Exception
    {
        return findValueInCol(SPREADSHEET_ID, TAB_NAME, column, value);
    }

    public static int findValueInCol(
        String spreadsheetId0,
        String tabName0,
        int column,
        String value) throws Exception
    {
        ensureValidGoogleToken();

        Sheets service = getSheetsService();

        String range = toA1RangeColumn(tabName0, column);

        ValueRange response = executeWithRetry(
            service.spreadsheets()
                .values()
                .get(spreadsheetId0, range)
        );

        List<List<Object>> values = response.getValues();

        if (values == null)
        {
            return -1;
        }

        for (int i = 0; i < values.size(); i++)
        {
            List<Object> row = values.get(i);

            if (!row.isEmpty())
            {
                String cellValue = row.get(0).toString();

                if (cellValue.equalsIgnoreCase(value))
                {
                    return i + 1;
                }
            }
        }

        return -1;
    }

    public static int findValueInRow(int row, String value) throws Exception
    {
        return findValueInRow(SPREADSHEET_ID, TAB_NAME, row, value);
    }

    public static int findValueInRow(
        String spreadsheetId0,
        String tabName0,
        int row,
        String value) throws Exception
    {
        ensureValidGoogleToken();

        Sheets service = getSheetsService();

        String range = toA1RangeRow(tabName0, row);

        ValueRange response = executeWithRetry(
            service.spreadsheets()
                .values()
                .get(spreadsheetId0, range)
        );

        List<List<Object>> values = response.getValues();

        if (values == null || values.isEmpty())
        {
            return -1;
        }

        List<Object> rowValues = values.get(0);

        for (int i = 0; i < rowValues.size(); i++)
        {
            String cellValue = rowValues.get(i).toString();

            if (cellValue.equalsIgnoreCase(value))
            {
                return i + 1;
            }
        }

        return -1;
    }

    public static int findColumnByHeader(String header) throws Exception
    {
        return findColumnByHeader(SPREADSHEET_ID, TAB_NAME, header);
    }

    public static int findColumnByHeader(
        String spreadsheetId0,
        String tabName0,
        String header) throws Exception
    {
        int headerRow = 1;

        int column = findValueInRow(spreadsheetId0, tabName0, headerRow, header);

        if (column == -1)
        {
            System.out.println("Header not found: " + header);
        }

        return column;
    }

    public static String readByHeaderAndLookup(
        String lookupHeader,
        String lookupVal,
        String targetHeader) throws Exception
    {
        return readByHeaderAndLookup(SPREADSHEET_ID, TAB_NAME, lookupHeader, lookupVal, targetHeader);
    }

    public static String readByHeaderAndLookup(
        String spreadsheetId0,
        String tabName0,
        String lookupHeader,
        String lookupVal,
        String targetHeader) throws Exception
    {
        int lookupCol = findColumnByHeader(spreadsheetId0, tabName0, lookupHeader);

        if (lookupCol == -1)
        {
            return "";
        }

        int row = findValueInCol(spreadsheetId0, tabName0, lookupCol, lookupVal);

        if (row == -1)
        {
            return "";
        }

        int targetCol = findColumnByHeader(spreadsheetId0, tabName0, targetHeader);

        if (targetCol == -1)
        {
            return "";
        }

        return readCell(spreadsheetId0, tabName0, row, targetCol);
    }

    public static String updateByHeaderAndLookup(
        String lookupHeader,
        String lookupVal,
        String targetHeader,
        String newValue) throws Exception
    {
        return updateByHeaderAndLookup(SPREADSHEET_ID, TAB_NAME, lookupHeader, lookupVal, targetHeader, newValue);
    }

    public static String updateByHeaderAndLookup(
        String spreadsheetId0,
        String tabName0,
        String lookupHeader,
        String lookupVal,
        String targetHeader,
        String newValue) throws Exception
    {
        int lookupCol = findColumnByHeader(spreadsheetId0, tabName0, lookupHeader);

        if (lookupCol == -1)
        {
            return "ERROR: Lookup header not found: " + lookupHeader;
        }

        int row = findValueInCol(spreadsheetId0, tabName0, lookupCol, lookupVal);

        if (row == -1)
        {
            return "ERROR: Lookup value not found: " + lookupVal;
        }

        int targetCol = findColumnByHeader(spreadsheetId0, tabName0, targetHeader);

        if (targetCol == -1)
        {
            return "ERROR: Target header not found: " + targetHeader;
        }

        updateCell(spreadsheetId0, tabName0, row, targetCol, newValue);

        return "SUCCESS: Updated "
            + toA1(tabName0, row, targetCol)
            + " (row=" + row + ", col=" + targetCol + ") to \""
            + newValue + "\"";
    }

    public static String updateCellByColumnAndFind(
        int col,
        String lookupVal,
        String updateVal) throws Exception
    {
        return updateCellByColumnAndFind(SPREADSHEET_ID, TAB_NAME, col, lookupVal, updateVal);
    }

    public static String updateCellByColumnAndFind(
        String spreadsheetId0,
        String tabName0,
        int col,
        String lookupVal,
        String updateVal) throws Exception
    {
        int row = findValueInCol(spreadsheetId0, tabName0, col, lookupVal);

        if (row == -1)
        {
            return "ERROR: Lookup value not found in column " + col + ": " + lookupVal;
        }

        updateCell(spreadsheetId0, tabName0, row, col, updateVal);

        return "SUCCESS: Updated "
            + toA1(tabName0, row, col)
            + " (row=" + row + ", col=" + col + ") from \""
            + lookupVal + "\" to \"" + updateVal + "\"";
    }

    public static String updateCellByHeaderandFind(
        String header,
        String lookupVal,
        String updateVal) throws Exception
    {
        return updateCellByHeaderandFind(SPREADSHEET_ID, TAB_NAME, header, lookupVal, updateVal);
    }

    public static String updateCellByHeaderandFind(
        String spreadsheetId0,
        String tabName0,
        String header,
        String lookupVal,
        String updateVal) throws Exception
    {
        int col = findColumnByHeader(spreadsheetId0, tabName0, header);

        if (col == -1)
        {
            return "ERROR: Header not found: " + header;
        }

        return updateCellByColumnAndFind(spreadsheetId0, tabName0, col, lookupVal, updateVal);
    }

    public static String updateCellByRowAndFind(
        int row,
        String lookupVal,
        String updateVal) throws Exception
    {
        return updateCellByRowAndFind(SPREADSHEET_ID, TAB_NAME, row, lookupVal, updateVal);
    }

    public static String updateCellByRowAndFind(
        String spreadsheetId0,
        String tabName0,
        int row,
        String lookupVal,
        String updateVal) throws Exception
    {
        int col = findValueInRow(spreadsheetId0, tabName0, row, lookupVal);

        if (col == -1)
        {
            return "ERROR: Lookup value not found in row " + row + ": " + lookupVal;
        }

        updateCell(spreadsheetId0, tabName0, row, col, updateVal);

        return "SUCCESS: Updated "
            + toA1(tabName0, row, col)
            + " (row=" + row + ", col=" + col + ") from \""
            + lookupVal + "\" to \"" + updateVal + "\"";
    }

    public static String updateCellByHeaderRowAndFind(
        String rowHeader,
        String lookupVal,
        String updateVal) throws Exception
    {
        return updateCellByHeaderRowAndFind(SPREADSHEET_ID, TAB_NAME, rowHeader, lookupVal, updateVal);
    }

    public static String updateCellByHeaderRowAndFind(
        String spreadsheetId0,
        String tabName0,
        String rowHeader,
        String lookupVal,
        String updateVal) throws Exception
    {
        int row = findValueInCol(spreadsheetId0, tabName0, 1, rowHeader);

        if (row == -1)
        {
            return "ERROR: Row header not found in column 1: " + rowHeader;
        }

        return updateCellByRowAndFind(spreadsheetId0, tabName0, row, lookupVal, updateVal);
    }

    public static void printValues(List<List<Object>> values) {
        if (values == null) {
            System.out.println("No data returned.");
            return;
        }

        for (int i = 0; i < values.size(); i++) {
            List<Object> row = values.get(i);

            System.out.print("Row " + (i + 1) + ": ");

            for (int j = 0; j < row.size(); j++) {
                System.out.print(row.get(j) + " | ");
            }

            System.out.println();
        }
    }

    static final int RETRY_MAX_ATTEMPTS = 6;
    static final long RETRY_BASE_DELAY_MS = 2000L;
    static final long RETRY_MAX_DELAY_MS = 32000L;

    // Swapped by SheetsRetryTestMain so the offline test does not really sleep.
    static RetrySleeper retrySleeper = Thread::sleep;

    private static <T> T executeWithRetry(AbstractGoogleClientRequest<T> request0) throws Exception
    {
        return executeWithRetry((SheetsCall<T>) request0::execute);
    }

    static <T> T executeWithRetry(SheetsCall<T> call0) throws Exception
    {
        for (int attempt0 = 1; attempt0 <= RETRY_MAX_ATTEMPTS; attempt0++)
        {
            try
            {
                return call0.execute();
            }
            catch (Exception exception0)
            {
                if (!isRetryable(exception0) || attempt0 == RETRY_MAX_ATTEMPTS)
                {
                    throw exception0;
                }

                double jitterFactor0 = 0.8 + (Math.random() * 0.4);
                long sleepMs0 = computeBackoffMs(attempt0, jitterFactor0);

                System.out.println(
                    "Sheets " + describeFailure(exception0)
                    + " — retry " + (attempt0 + 1) + "/" + RETRY_MAX_ATTEMPTS
                    + " in " + (sleepMs0 / 1000.0) + "s"
                );

                try
                {
                    retrySleeper.sleep(sleepMs0);
                }
                catch (InterruptedException interrupted0)
                {
                    Thread.currentThread().interrupt();
                    throw interrupted0;
                }
            }
        }

        throw new Exception("executeWithRetry: exhausted attempts without result");
    }

    /** Exponential backoff (2s/4s/8s/16s/32s, capped) scaled by a jitter factor. */
    static long computeBackoffMs(int attempt0, double jitterFactor0)
    {
        long delayMs0 = Math.min(
            RETRY_BASE_DELAY_MS * (1L << (attempt0 - 1)),
            RETRY_MAX_DELAY_MS
        );

        return (long) (delayMs0 * jitterFactor0);
    }

    /** Transient HTTP statuses worth retrying. 504 included: Sheets returns it under load. */
    static boolean isRetryableStatus(int statusCode0)
    {
        return statusCode0 == 429
            || statusCode0 == 500
            || statusCode0 == 502
            || statusCode0 == 503
            || statusCode0 == 504;
    }

    // Sheets reports quota exhaustion as 403 with one of these reasons as often as it
    // does 429. A 403 for any other reason (lost access, revoked scope) must fail fast.
    static boolean isRateLimit403(GoogleJsonResponseException exception0)
    {
        if (exception0.getStatusCode() != 403)
        {
            return false;
        }

        com.google.api.client.googleapis.json.GoogleJsonError details0 = exception0.getDetails();

        if (details0 == null || details0.getErrors() == null)
        {
            return false;
        }

        for (com.google.api.client.googleapis.json.GoogleJsonError.ErrorInfo errorInfo0
                : details0.getErrors())
        {
            String reason0 = errorInfo0.getReason();

            if ("rateLimitExceeded".equals(reason0)
                || "userRateLimitExceeded".equals(reason0)
                || "quotaExceeded".equals(reason0))
            {
                return true;
            }
        }

        return false;
    }

    static boolean isRetryable(Exception exception0)
    {
        // TokenResponseException is an HttpResponseException too; its 400 invalid_grant
        // is not in the retryable set, so auth failures still surface immediately.
        if (exception0 instanceof GoogleJsonResponseException)
        {
            GoogleJsonResponseException googleException0 = (GoogleJsonResponseException) exception0;

            return isRetryableStatus(googleException0.getStatusCode())
                || isRateLimit403(googleException0);
        }

        if (exception0 instanceof com.google.api.client.http.HttpResponseException)
        {
            return isRetryableStatus(
                ((com.google.api.client.http.HttpResponseException) exception0).getStatusCode()
            );
        }

        // Socket timeouts, connection resets and TLS hiccups are exactly what a long
        // workflow hits mid-run; these carry no status code but are worth retrying.
        return exception0 instanceof java.io.IOException;
    }

    private static String describeFailure(Exception exception0)
    {
        if (exception0 instanceof com.google.api.client.http.HttpResponseException)
        {
            return String.valueOf(
                ((com.google.api.client.http.HttpResponseException) exception0).getStatusCode()
            );
        }

        return exception0.getClass().getSimpleName();
    }

    private static Sheets getSheetsService() throws Exception
    {
        final NetHttpTransport httpTransport = GoogleNetHttpTransport.newTrustedTransport();

        Credential credential = getCredentials(httpTransport);

        return new Sheets.Builder(httpTransport, JSON_FACTORY, credential)
            .setApplicationName(APPLICATION_NAME)
            .build();
    }

    private static Credential getCredentials(final NetHttpTransport httpTransport) throws Exception {
        InputStream in = SheetsApp.class.getResourceAsStream("/credentials.json");

        GoogleClientSecrets clientSecrets = GoogleClientSecrets.load(
                JSON_FACTORY,
                new InputStreamReader(in)
        );

        GoogleAuthorizationCodeFlow flow = new GoogleAuthorizationCodeFlow.Builder(
                httpTransport,
                JSON_FACTORY,
                clientSecrets,
                SCOPES
        )
        .setDataStoreFactory(new FileDataStoreFactory(new java.io.File(TOKENS_DIRECTORY_PATH)))
        .setAccessType("offline")
        .build();

        LocalServerReceiver receiver = new LocalServerReceiver.Builder().setPort(8888).build();
        return new AuthorizationCodeInstalledApp(flow, receiver).authorize("user");
    }

    public static String toA1(String tab, int row, int column) {
        return "'" + tab + "'!" + columnToLetters(column) + row;
    }

    public static String toA1Range(String tab, int row1, int col1, int row2, int col2) {
        return "'" + tab + "'!" + columnToLetters(col1) + row1 + ":" + columnToLetters(col2) + row2;
    }

    public static String toA1RangeColumn(String tab, int col){
        return "'" + tab + "'!" + columnToLetters(col) + ":" + columnToLetters(col);
    }

    public static String toA1RangeRow(String tab, int row){
        return "'" + tab + "'!" + row + ":" + row;
    }

    private static String columnToLetters(int column) {
        StringBuilder result = new StringBuilder();

        while (column > 0) {
            column--;
            result.insert(0, (char) ('A' + (column % 26)));
            column /= 26;
        }

        return result.toString();
    }

    private static String getRange(int row, int column, String tab) {
        return toA1(tab, row, column);
    }

    private static int columnLettersToNumber(String letters0)
    {
        int result0 = 0;

        for (int i0 = 0; i0 < letters0.length(); i0++)
        {
            char c0 = Character.toUpperCase(letters0.charAt(i0));
            result0 = result0 * 26 + (c0 - 'A' + 1);
        }

        return result0;
    }

    private static int extractRow(String cell0)
    {
        StringBuilder number0 = new StringBuilder();

        for (int i0 = 0; i0 < cell0.length(); i0++)
        {
            char c0 = cell0.charAt(i0);

            if (Character.isDigit(c0))
            {
                number0.append(c0);
            }
        }

        return Integer.parseInt(number0.toString());
    }

    private static int extractColumn(String cell0)
    {
        StringBuilder letters0 = new StringBuilder();

        for (int i0 = 0; i0 < cell0.length(); i0++)
        {
            char c0 = cell0.charAt(i0);

            if (Character.isLetter(c0))
            {
                letters0.append(c0);
            }
        }

        return columnLettersToNumber(letters0.toString());
    }

    private static int[] parseA1Range(String range0)
    {
        String[] parts0 = range0.split(":");

        String start0 = parts0[0];
        String end0 = parts0.length > 1 ? parts0[1] : parts0[0];

        int row1 = extractRow(start0);
        int col1 = extractColumn(start0);

        int row2 = extractRow(end0);
        int col2 = extractColumn(end0);

        return new int[] { row1, col1, row2, col2 };
    }

    private static void deleteTokensFolder() throws Exception
    {
        java.io.File tokensFolder0 = new java.io.File(TOKENS_DIRECTORY_PATH);

        if (!tokensFolder0.exists())
        {
            return;
        }

        java.io.File[] files0 = tokensFolder0.listFiles();

        if (files0 != null)
        {
            for (java.io.File file0 : files0)
            {
                file0.delete();
            }
        }

        tokensFolder0.delete();
    }

    public static void deleteRow(
        String spreadsheetId0,
        String tabName0,
        int rowNumber0) throws Exception
    {
        ensureValidGoogleToken();

        Sheets service = getSheetsService();

        int sheetId0 = getSheetIdByName(spreadsheetId0, tabName0);

        com.google.api.services.sheets.v4.model.DeleteDimensionRequest deleteRequest0 =
            new com.google.api.services.sheets.v4.model.DeleteDimensionRequest()
                .setRange(
                    new com.google.api.services.sheets.v4.model.DimensionRange()
                        .setSheetId(sheetId0)
                        .setDimension("ROWS")
                        .setStartIndex(rowNumber0 - 1)
                        .setEndIndex(rowNumber0)
                );

        com.google.api.services.sheets.v4.model.Request request0 =
            new com.google.api.services.sheets.v4.model.Request()
                .setDeleteDimension(deleteRequest0);

        java.util.List<com.google.api.services.sheets.v4.model.Request> requests0 =
            new java.util.ArrayList<>();

        requests0.add(request0);

        com.google.api.services.sheets.v4.model.BatchUpdateSpreadsheetRequest body0 =
            new com.google.api.services.sheets.v4.model.BatchUpdateSpreadsheetRequest()
                .setRequests(requests0);

        executeWithRetry(
            service.spreadsheets()
                .batchUpdate(spreadsheetId0, body0)
        );
    }

    private static int getSheetIdByName(
        String spreadsheetId0,
        String tabName0) throws Exception
    {
        Sheets service = getSheetsService();

        com.google.api.services.sheets.v4.model.Spreadsheet spreadsheet0 =
            executeWithRetry(
                service.spreadsheets()
                    .get(spreadsheetId0)
            );

        java.util.List<com.google.api.services.sheets.v4.model.Sheet> sheets0 =
            spreadsheet0.getSheets();

        for (int i = 0; i < sheets0.size(); i++)
        {
            com.google.api.services.sheets.v4.model.Sheet sheet0 = sheets0.get(i);

            String title0 = sheet0.getProperties().getTitle();

            if (title0.equals(tabName0))
            {
                return sheet0.getProperties().getSheetId();
            }
        }

        throw new Exception("Sheet tab not found: " + tabName0);
    }

    public static void expandSheetColumnsIfNeeded(
        String spreadsheetId0,
        String tabName0,
        int neededColumns0) throws Exception
    {
        ensureValidGoogleToken();

        Sheets service = getSheetsService();

        com.google.api.services.sheets.v4.model.Spreadsheet spreadsheet0 =
            executeWithRetry(
                service.spreadsheets()
                    .get(spreadsheetId0)
            );

        java.util.List<com.google.api.services.sheets.v4.model.Sheet> sheets0 =
            spreadsheet0.getSheets();

        for (int i = 0; i < sheets0.size(); i++)
        {
            com.google.api.services.sheets.v4.model.Sheet sheet0 = sheets0.get(i);

            if (!sheet0.getProperties().getTitle().equals(tabName0))
            {
                continue;
            }

            int currentCols0 = sheet0.getProperties().getGridProperties().getColumnCount();

            if (currentCols0 >= neededColumns0)
            {
                return;
            }

            int sheetId0 = sheet0.getProperties().getSheetId();

            com.google.api.services.sheets.v4.model.Request request0 =
                new com.google.api.services.sheets.v4.model.Request()
                    .setUpdateSheetProperties(
                        new com.google.api.services.sheets.v4.model.UpdateSheetPropertiesRequest()
                            .setProperties(
                                new com.google.api.services.sheets.v4.model.SheetProperties()
                                    .setSheetId(sheetId0)
                                    .setGridProperties(
                                        new com.google.api.services.sheets.v4.model.GridProperties()
                                            .setColumnCount(neededColumns0)
                                    )
                            )
                            .setFields("gridProperties.columnCount")
                    );

            java.util.List<com.google.api.services.sheets.v4.model.Request> requests0 =
                new java.util.ArrayList<>();
            requests0.add(request0);

            com.google.api.services.sheets.v4.model.BatchUpdateSpreadsheetRequest body0 =
                new com.google.api.services.sheets.v4.model.BatchUpdateSpreadsheetRequest()
                    .setRequests(requests0);

            executeWithRetry(
                service.spreadsheets()
                    .batchUpdate(spreadsheetId0, body0)
            );

            System.out.println("Expanded " + tabName0 + " to " + neededColumns0 + " columns.");
            return;
        }

        throw new Exception("Sheet tab not found: " + tabName0);
    }

    /** Returns true when a tab with the given name exists in the spreadsheet. */
    public static boolean tabExists(String spreadsheetId0, String tabName0) throws Exception
    {
        ensureValidGoogleToken();
        Sheets service = getSheetsService();
        com.google.api.services.sheets.v4.model.Spreadsheet ss0 =
            executeWithRetry(service.spreadsheets().get(spreadsheetId0));
        for (com.google.api.services.sheets.v4.model.Sheet sh0 : ss0.getSheets())
        {
            if (tabName0.equals(sh0.getProperties().getTitle())) return true;
        }
        return false;
    }

    /** Create a new tab. Pass hidden=true for the snapshot store's hidden tab. */
    public static void createTab(String spreadsheetId0, String tabName0,
                                 boolean hidden0) throws Exception
    {
        ensureValidGoogleToken();
        Sheets service = getSheetsService();
        com.google.api.services.sheets.v4.model.SheetProperties props0 =
            new com.google.api.services.sheets.v4.model.SheetProperties()
                .setTitle(tabName0)
                .setHidden(hidden0);
        com.google.api.services.sheets.v4.model.AddSheetRequest addReq0 =
            new com.google.api.services.sheets.v4.model.AddSheetRequest()
                .setProperties(props0);
        com.google.api.services.sheets.v4.model.Request req0 =
            new com.google.api.services.sheets.v4.model.Request().setAddSheet(addReq0);
        java.util.List<com.google.api.services.sheets.v4.model.Request> reqs0 =
            new java.util.ArrayList<>();
        reqs0.add(req0);
        com.google.api.services.sheets.v4.model.BatchUpdateSpreadsheetRequest body0 =
            new com.google.api.services.sheets.v4.model.BatchUpdateSpreadsheetRequest()
                .setRequests(reqs0);
        executeWithRetry(service.spreadsheets().batchUpdate(spreadsheetId0, body0));
        System.out.println("Created tab: " + tabName0 + (hidden0 ? " (hidden)" : ""));
    }

    /**
     * Insert one empty column immediately BEFORE the given 1-indexed column,
     * shifting that column and everything to its right one position right (data
     * moves with its column). Used to drop the onboarding divider between the GP's
     * human columns and pre-existing machine columns on legacy sheets without
     * overwriting anything.
     */
    public static void insertColumn(
        String spreadsheetId0,
        String tabName0,
        int column0) throws Exception
    {
        ensureValidGoogleToken();

        Sheets service = getSheetsService();

        int sheetId0 = getSheetIdByName(spreadsheetId0, tabName0);

        com.google.api.services.sheets.v4.model.DimensionRange dimensionRange0 =
            new com.google.api.services.sheets.v4.model.DimensionRange()
                .setSheetId(sheetId0)
                .setDimension("COLUMNS")
                .setStartIndex(column0 - 1)
                .setEndIndex(column0);

        com.google.api.services.sheets.v4.model.InsertDimensionRequest insertRequest0 =
            new com.google.api.services.sheets.v4.model.InsertDimensionRequest()
                .setRange(dimensionRange0)
                .setInheritFromBefore(false);

        com.google.api.services.sheets.v4.model.Request request0 =
            new com.google.api.services.sheets.v4.model.Request()
                .setInsertDimension(insertRequest0);

        java.util.List<com.google.api.services.sheets.v4.model.Request> requests0 =
            new java.util.ArrayList<>();
        requests0.add(request0);

        com.google.api.services.sheets.v4.model.BatchUpdateSpreadsheetRequest body0 =
            new com.google.api.services.sheets.v4.model.BatchUpdateSpreadsheetRequest()
                .setRequests(requests0);

        executeWithRetry(
            service.spreadsheets()
                .batchUpdate(spreadsheetId0, body0)
        );

        System.out.println("Inserted column before column " + column0 + ".");
    }

    public static void columnBorder(int column0) throws Exception
    {
        columnBorder(SPREADSHEET_ID, TAB_NAME, column0);
    }

    public static void columnBorder(
        String spreadsheetId0,
        String tabName0,
        int column0) throws Exception
    {
        columnBorder(spreadsheetId0, tabName0, column0, "SOLID");
    }

    /**
     * Draws a vertical border immediately after the given 1-indexed column,
     * spanning every row of the tab. The border is placed on the right edge
     * of that column via the Sheets UpdateBorders request.
     */
    public static void columnBorder(
        String spreadsheetId0,
        String tabName0,
        int column0,
        String style0) throws Exception
    {
        ensureValidGoogleToken();

        Sheets service = getSheetsService();

        int sheetId0 = getSheetIdByName(spreadsheetId0, tabName0);

        com.google.api.services.sheets.v4.model.GridRange range0 =
            new com.google.api.services.sheets.v4.model.GridRange()
                .setSheetId(sheetId0)
                .setStartColumnIndex(column0 - 1)
                .setEndColumnIndex(column0);

        com.google.api.services.sheets.v4.model.Border border0 =
            new com.google.api.services.sheets.v4.model.Border()
                .setStyle(style0)
                .setColor(
                    new com.google.api.services.sheets.v4.model.Color()
                        .setRed(0f)
                        .setGreen(0f)
                        .setBlue(0f)
                );

        com.google.api.services.sheets.v4.model.UpdateBordersRequest bordersRequest0 =
            new com.google.api.services.sheets.v4.model.UpdateBordersRequest()
                .setRange(range0)
                .setRight(border0);

        com.google.api.services.sheets.v4.model.Request request0 =
            new com.google.api.services.sheets.v4.model.Request()
                .setUpdateBorders(bordersRequest0);

        java.util.List<com.google.api.services.sheets.v4.model.Request> requests0 =
            new java.util.ArrayList<>();

        requests0.add(request0);

        com.google.api.services.sheets.v4.model.BatchUpdateSpreadsheetRequest body0 =
            new com.google.api.services.sheets.v4.model.BatchUpdateSpreadsheetRequest()
                .setRequests(requests0);

        executeWithRetry(
            service.spreadsheets()
                .batchUpdate(spreadsheetId0, body0)
        );

        System.out.println("Added border after column " + column0 + " (" + style0 + ").");
    }

    public static void rowBorder(int row0) throws Exception
    {
        rowBorder(SPREADSHEET_ID, TAB_NAME, row0);
    }

    public static void rowBorder(
        String spreadsheetId0,
        String tabName0,
        int row0) throws Exception
    {
        rowBorder(spreadsheetId0, tabName0, row0, "SOLID");
    }

    /**
     * Draws a horizontal border immediately after the given 1-indexed row,
     * spanning every column of the tab. The border is placed on the bottom
     * edge of that row via the Sheets UpdateBorders request.
     */
    public static void rowBorder(
        String spreadsheetId0,
        String tabName0,
        int row0,
        String style0) throws Exception
    {
        ensureValidGoogleToken();

        Sheets service = getSheetsService();

        int sheetId0 = getSheetIdByName(spreadsheetId0, tabName0);

        com.google.api.services.sheets.v4.model.GridRange range0 =
            new com.google.api.services.sheets.v4.model.GridRange()
                .setSheetId(sheetId0)
                .setStartRowIndex(row0 - 1)
                .setEndRowIndex(row0);

        com.google.api.services.sheets.v4.model.Border border0 =
            new com.google.api.services.sheets.v4.model.Border()
                .setStyle(style0)
                .setColor(
                    new com.google.api.services.sheets.v4.model.Color()
                        .setRed(0f)
                        .setGreen(0f)
                        .setBlue(0f)
                );

        com.google.api.services.sheets.v4.model.UpdateBordersRequest bordersRequest0 =
            new com.google.api.services.sheets.v4.model.UpdateBordersRequest()
                .setRange(range0)
                .setBottom(border0);

        com.google.api.services.sheets.v4.model.Request request0 =
            new com.google.api.services.sheets.v4.model.Request()
                .setUpdateBorders(bordersRequest0);

        java.util.List<com.google.api.services.sheets.v4.model.Request> requests0 =
            new java.util.ArrayList<>();

        requests0.add(request0);

        com.google.api.services.sheets.v4.model.BatchUpdateSpreadsheetRequest body0 =
            new com.google.api.services.sheets.v4.model.BatchUpdateSpreadsheetRequest()
                .setRequests(requests0);

        executeWithRetry(
            service.spreadsheets()
                .batchUpdate(spreadsheetId0, body0)
        );

        System.out.println("Added border after row " + row0 + " (" + style0 + ").");
    }
}