package com.liminer.enrich;

import com.liminer.scout.ScoutUniverseRecord;

import java.io.IOException;
import java.io.InputStream;
import java.io.PushbackReader;
import java.io.Reader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/*
 * Irs990BulkIndexClient — reader for the IRS's published bulk 990/990-PF
 * filing index (the "e-file" index CSVs at
 * https://www.irs.gov/charities-non-profits/form-990-series-downloads, one
 * of the machine-readable index files listing every e-filed return with
 * balance-sheet summary figures). Mirrors AdvBulkClient's split between a
 * network-fetch layer and a pure, offline-testable parser/filter.
 *
 * Exact column headers vary by index vintage, so parseIndex resolves columns
 * by case-insensitive header-name matching, not by index, and throws loudly
 * (naming the missing header) when a required column is absent.
 *
 * Qualifying rows (totassetsend/invstmntinc at or above the given thresholds)
 * become discovery candidate ScoutUniverseRecords: crd stays 0,
 * externalRegisterId is the EIN, sourceRegister is "IRS_990_BULK".
 */
public class Irs990BulkIndexClient
{
    private static final HttpClient CLIENT0 = HttpClient.newHttpClient();
    private static final String USER_AGENT0 = HttpContact.USER_AGENT0;
    private static final int TIMEOUT_SECS0 = 60;

    // Sensible defaults for the foundation/endowment class this feeds into
    // the Scout universe: large total assets and meaningful investment
    // income, not merely "filed a 990". Callers may override either.
    public static final double DEFAULT_MIN_TOTAL_ASSETS = 10_000_000.0;
    public static final double DEFAULT_MIN_INVESTMENT_INCOME = 100_000.0;

    public Irs990BulkIndexClient() {}

    // -----------------------------------------------------------------------
    // Public API
    // -----------------------------------------------------------------------

    /**
     * Downloads the IRS bulk 990 index CSV at indexUrl0 into destPath0 and
     * parses it via parseIndex with the given thresholds. Network layer only;
     * not exercised by the offline test (Nonprofit990DiscoveryTestMain feeds
     * parseIndex directly).
     */
    public List<ScoutUniverseRecord> fetchAndParse(String indexUrl0, Path destPath0,
        double minTotalAssets0, double minInvestmentIncome0) throws Exception
    {
        if (isBlank(indexUrl0)) throw new IllegalArgumentException("indexUrl0 is required");
        downloadFile(indexUrl0, destPath0);
        try (Reader r0 = Files.newBufferedReader(destPath0, StandardCharsets.UTF_8))
        {
            return parseIndex(r0, minTotalAssets0, minInvestmentIncome0);
        }
    }

    /**
     * Pure parser + filter: bulk 990 index CSV -> discovery candidate
     * ScoutUniverseRecords whose totassetsend and invstmntinc both meet the
     * given thresholds. Columns are resolved by case-insensitive header-name
     * matching; throws IllegalArgumentException naming the missing header
     * when a required column ("ein", "organization name"/"name",
     * "totassetsend"/"total assets", "invstmntinc"/"investment income")
     * cannot be found.
     */
    public List<ScoutUniverseRecord> parseIndex(Reader csv0, double minTotalAssets0,
        double minInvestmentIncome0) throws IOException
    {
        List<String[]> rows0 = parseCsvRows(csv0);
        List<ScoutUniverseRecord> out0 = new ArrayList<ScoutUniverseRecord>();
        if (rows0.isEmpty()) return out0;

        String[] headers0 = rows0.get(0);
        int colEin0 = requireColumn(headers0, "ein");
        int colName0 = requireColumn(headers0, "name");
        int colAssets0 = requireColumn(headers0, "totassetsend");
        int colInvInc0 = requireColumn(headers0, "invstmntinc");
        int colCity0 = findColumn(headers0, "city");
        int colState0 = findColumn(headers0, "state");
        int colNtee0 = findColumn(headers0, "ntee");
        int colTaxYr0 = findColumn(headers0, "tax_prd_yr");

        for (int i0 = 1; i0 < rows0.size(); i0++)
        {
            String[] row0 = rows0.get(i0);
            if (row0.length <= colEin0) continue;

            String ein0 = cell(row0, colEin0);
            if (isBlank(ein0)) continue;

            double totalAssets0 = parseDoubleSafe(cell(row0, colAssets0));
            double invInc0 = parseDoubleSafe(cell(row0, colInvInc0));
            if (totalAssets0 < minTotalAssets0 || invInc0 < minInvestmentIncome0) continue;

            ScoutUniverseRecord rec0 = new ScoutUniverseRecord();
            rec0.externalRegisterId = ein0;
            rec0.sourceRegister = "IRS_990_BULK";
            rec0.firmName = cell(row0, colName0);
            rec0.city = colCity0 >= 0 ? cell(row0, colCity0) : "";
            rec0.state = colState0 >= 0 ? cell(row0, colState0) : "";
            rec0.country = "United States";
            rec0.nteeCode = colNtee0 >= 0 ? cell(row0, colNtee0) : "";
            rec0.nonprofitTotalAssets990 = totalAssets0;
            if (colTaxYr0 >= 0) rec0.snapshotMonth = cell(row0, colTaxYr0);
            out0.add(rec0);
        }
        return out0;
    }

    // -----------------------------------------------------------------------
    // HTTP
    // -----------------------------------------------------------------------

    private void downloadFile(String url0, Path dest0) throws Exception
    {
        HttpRequest req0 = HttpRequest.newBuilder()
            .uri(URI.create(url0))
            .timeout(Duration.ofSeconds(TIMEOUT_SECS0))
            .header("User-Agent", USER_AGENT0)
            .GET()
            .build();

        HttpResponse<Path> resp0 = CLIENT0.send(req0, HttpResponse.BodyHandlers.ofFile(dest0));
        int status0 = resp0.statusCode();
        if (status0 < 200 || status0 >= 300)
            throw new RuntimeException("HTTP " + status0 + " downloading " + url0);
    }

    // -----------------------------------------------------------------------
    // CSV parsing (RFC4180-ish: quoted fields, embedded commas/newlines, "" escape)
    // Mirrors AdvBulkClient's parser.
    // -----------------------------------------------------------------------

    private static List<String[]> parseCsvRows(Reader reader0) throws IOException
    {
        List<String[]> rows0 = new ArrayList<String[]>();
        PushbackReader pr0 = new PushbackReader(reader0, 1);
        List<String> current0 = new ArrayList<String>();
        StringBuilder field0 = new StringBuilder();
        boolean inQuotes0 = false;
        boolean rowHasContent0 = false;
        int ic0;

        while ((ic0 = pr0.read()) != -1)
        {
            char c0 = (char) ic0;
            rowHasContent0 = true;
            if (inQuotes0)
            {
                if (c0 == '"')
                {
                    int next0 = pr0.read();
                    if (next0 == '"') field0.append('"');
                    else
                    {
                        inQuotes0 = false;
                        if (next0 != -1) pr0.unread(next0);
                    }
                }
                else field0.append(c0);
            }
            else
            {
                if (c0 == '"') inQuotes0 = true;
                else if (c0 == ',')
                {
                    current0.add(field0.toString());
                    field0.setLength(0);
                }
                else if (c0 == '\r')
                {
                    int next0 = pr0.read();
                    if (next0 != '\n' && next0 != -1) pr0.unread(next0);
                    current0.add(field0.toString());
                    field0.setLength(0);
                    rows0.add(current0.toArray(new String[0]));
                    current0 = new ArrayList<String>();
                    rowHasContent0 = false;
                }
                else if (c0 == '\n')
                {
                    current0.add(field0.toString());
                    field0.setLength(0);
                    rows0.add(current0.toArray(new String[0]));
                    current0 = new ArrayList<String>();
                    rowHasContent0 = false;
                }
                else field0.append(c0);
            }
        }

        if (rowHasContent0 || field0.length() > 0 || !current0.isEmpty())
        {
            current0.add(field0.toString());
            rows0.add(current0.toArray(new String[0]));
        }

        return rows0;
    }

    private static int findColumn(String[] headers0, String keyword0)
    {
        String kw0 = keyword0.toLowerCase();
        for (int i0 = 0; i0 < headers0.length; i0++)
        {
            if (headers0[i0] != null && headers0[i0].toLowerCase().contains(kw0)) return i0;
        }
        return -1;
    }

    private static int requireColumn(String[] headers0, String keyword0)
    {
        int idx0 = findColumn(headers0, keyword0);
        if (idx0 < 0)
            throw new IllegalArgumentException(
                "Irs990BulkIndexClient: missing required 990-index CSV column matching \"" + keyword0 + "\"");
        return idx0;
    }

    private static String cell(String[] row0, int idx0)
    {
        return (idx0 >= 0 && idx0 < row0.length && row0[idx0] != null) ? row0[idx0].trim() : "";
    }

    private static double parseDoubleSafe(String s0)
    {
        if (isBlank(s0)) return 0.0;
        try
        {
            String cleaned0 = s0.replaceAll("[^0-9.\\-]", "");
            if (isBlank(cleaned0)) return 0.0;
            return Double.parseDouble(cleaned0);
        }
        catch (Exception e0) { return 0.0; }
    }

    private static boolean isBlank(String s0) { return s0 == null || s0.trim().isEmpty(); }
}
