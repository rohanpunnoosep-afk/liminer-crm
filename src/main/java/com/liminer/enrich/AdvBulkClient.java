package com.liminer.enrich;

import com.liminer.scout.ScoutFundRecord;
import com.liminer.scout.ScoutUniverseRecord;
import com.liminer.scout.ScoutWorkflowGate;

import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.io.PushbackReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/*
 * AdvBulkClient — downloads and parses the SEC "Information About Registered
 * Investment Advisers and Exempt Reporting Advisers" monthly bulk report
 * (published via the SEC FOIA bulk-data page / adviserinfo.sec.gov downloads;
 * no auth/key). Replaces IapdClient.fetchPart1, which is a stub blocked by a
 * Cloudflare SPA — do not use that endpoint.
 *
 * The bulk report ships as a ZIP containing (at minimum) an adviser-level CSV
 * (Form ADV Part 1 items) and a Schedule D Section 7.B.1 private-funds CSV.
 * Exact column headers vary by report vintage, so parseCsv resolves columns by
 * case-insensitive header-name matching, not by index, and throws loudly
 * (naming the missing header) when a required column is absent.
 *
 * fetchAndParse does the network/zip work; parseCsv is the pure, offline-testable
 * core — keep it that way so later tasks can unit test without hitting sec.gov.
 */
public class AdvBulkClient
{
    private static final HttpClient CLIENT0 = HttpClient.newHttpClient();
    private static final String USER_AGENT0 = HttpContact.USER_AGENT0;
    private static final int TIMEOUT_SECS0 = 60;

    // Documented SEC bulk-data pattern (per https://www.sec.gov/foia/docs/invafoiadata
    // and the adviserinfo.sec.gov compilation reports). Vintage-specific file naming
    // may require adjustment; this is the pure network layer and is not exercised by
    // the offline test (ScoutUniverseTestMain feeds parseCsv directly).
    private static final String BULK_BASE0 =
        "https://reports.adviserinfo.sec.gov/reports/CompilationReports";

    public AdvBulkClient() {}

    // -----------------------------------------------------------------------
    // Public API
    // -----------------------------------------------------------------------

    /**
     * Downloads the monthly ADV bulk ZIP for yyyyMm0 (format "YYYY-MM") into
     * downloadDir0, extracts the adviser CSV and the Schedule D CSV, and parses
     * them via parseCsv. downloadDir0 is created if missing.
     */
    public List<ScoutUniverseRecord> fetchAndParse(String yyyyMm0, Path downloadDir0) throws Exception
    {
        ScoutWorkflowGate.requireEnabled("ADV bulk download (~250 MB)");

        if (isBlank(yyyyMm0)) throw new IllegalArgumentException("yyyyMm0 is required (format YYYY-MM)");
        Files.createDirectories(downloadDir0);

        String monthTag0 = yyyyMm0.replace("-", "");
        String zipUrl0 = BULK_BASE0 + "/IA_ADV_BULK_" + monthTag0 + ".zip";
        Path zipPath0 = downloadDir0.resolve("adv_bulk_" + yyyyMm0 + ".zip");
        downloadFile(zipUrl0, zipPath0);

        Path iaCsvPath0 = null;
        Path sdCsvPath0 = null;
        try (ZipFile zf0 = new ZipFile(zipPath0.toFile()))
        {
            Enumeration<? extends ZipEntry> entries0 = zf0.entries();
            while (entries0.hasMoreElements())
            {
                ZipEntry entry0 = entries0.nextElement();
                if (entry0.isDirectory()) continue;
                String lower0 = entry0.getName().toLowerCase();
                if (!lower0.endsWith(".csv")) continue;

                Path extracted0 = downloadDir0.resolve(Path.of(entry0.getName()).getFileName());
                try (InputStream in0 = zf0.getInputStream(entry0))
                {
                    Files.copy(in0, extracted0, StandardCopyOption.REPLACE_EXISTING);
                }

                if (lower0.contains("schedule") && lower0.contains("d")) sdCsvPath0 = extracted0;
                else iaCsvPath0 = extracted0;
            }
        }

        if (iaCsvPath0 == null)
            throw new IllegalStateException("ADV bulk ZIP did not contain an adviser CSV: " + zipUrl0);
        if (sdCsvPath0 == null)
            throw new IllegalStateException("ADV bulk ZIP did not contain a Schedule D CSV: " + zipUrl0);

        List<ScoutUniverseRecord> records0;
        try (Reader iaReader0 = Files.newBufferedReader(iaCsvPath0, StandardCharsets.UTF_8);
             Reader sdReader0 = Files.newBufferedReader(sdCsvPath0, StandardCharsets.UTF_8))
        {
            records0 = parseCsv(iaReader0, sdReader0);
            for (ScoutUniverseRecord r0 : records0) r0.snapshotMonth = yyyyMm0;
        }

        cleanupBulkFiles(zipPath0, iaCsvPath0, sdCsvPath0);
        return records0;
    }

    /**
     * Deletes the downloaded ZIP and extracted CSVs after a successful parse.
     * No-op for any path that is null or already missing. Package-visible so
     * it is directly testable offline without a real download.
     */
    public static void cleanupBulkFiles(Path zip0, Path iaCsv0, Path sdCsv0) throws IOException
    {
        deleteIfExistsQuiet(zip0);
        deleteIfExistsQuiet(iaCsv0);
        deleteIfExistsQuiet(sdCsv0);
    }

    private static void deleteIfExistsQuiet(Path p0) throws IOException
    {
        if (p0 != null) Files.deleteIfExists(p0);
    }

    /**
     * Pure parser: adviser-level CSV (Part 1 items) + Schedule D 7.B.1 private-funds
     * CSV -> merged ScoutUniverseRecord list. Columns are resolved by case-insensitive
     * header-name matching; throws IllegalArgumentException naming the missing header
     * when a required column cannot be found.
     */
    public List<ScoutUniverseRecord> parseCsv(Reader iaCsv0, Reader scheduleDCsv0) throws IOException
    {
        final Map<Integer, ScoutUniverseRecord> byCrd0 = new LinkedHashMap<Integer, ScoutUniverseRecord>();
        final int[] colCrd0 = new int[1];
        final int[] colName0 = new int[1];
        final int[] colWebsite0 = new int[1];
        final int[] colCity0 = new int[1];
        final int[] colState0 = new int[1];
        final int[] colCountry0 = new int[1];
        final int[] colPhone0 = new int[1];
        final int[] colEmail0 = new int[1];
        final int[] colRaumTotal0 = new int[1];
        final int[] colRaumDisc0 = new int[1];
        final int[] colEmployees0 = new int[1];
        final int[] colClientTypes0 = new int[1];
        final boolean[] iaHeaderSeen0 = new boolean[1];

        // Optional (non-required) columns: absent -> stays -1 -> field stays null/blank.
        // Header keywords are UNVERIFIED against the real bulk CSV; see
        // ExtraDocuments/adv-column-audit.md for the audit and rationale.
        final int[] colLinkedin0 = new int[1];
        final int[] colSecFileNumber0 = new int[1];
        final int[] colLei0 = new int[1];
        final int[] colUmbrella0 = new int[1];
        final int[] colPctPooled0 = new int[1];
        final int[] colPctPension0 = new int[1];
        final int[] colPctCharitable0 = new int[1];

        parseCsvRows(iaCsv0, new Consumer<String[]>()
        {
            @Override
            public void accept(String[] row0)
            {
                if (!iaHeaderSeen0[0])
                {
                    iaHeaderSeen0[0] = true;
                    colCrd0[0] = requireColumn(row0, "crd number");
                    colName0[0] = requireColumn(row0, "legal name");
                    colWebsite0[0] = requireColumn(row0, "website");
                    colCity0[0] = requireColumn(row0, "city");
                    colState0[0] = requireColumn(row0, "state");
                    colCountry0[0] = requireColumn(row0, "country");
                    colPhone0[0] = requireColumn(row0, "phone");
                    colEmail0[0] = requireColumn(row0, "email");
                    colRaumTotal0[0] = requireColumn(row0, "total regulatory assets");
                    colRaumDisc0[0] = requireColumn(row0, "discretionary regulatory assets");
                    colEmployees0[0] = requireColumn(row0, "total employees");
                    colClientTypes0[0] = requireColumn(row0, "client types");

                    colLinkedin0[0] = findColumn(row0, "linkedin");
                    colSecFileNumber0[0] = findColumn(row0, "sec file number");
                    colLei0[0] = findColumn(row0, "legal entity identifier");
                    colUmbrella0[0] = findColumn(row0, "umbrella registration");
                    colPctPooled0[0] = findColumn(row0, "% pooled investment vehicles");
                    colPctPension0[0] = findColumn(row0, "% pension");
                    colPctCharitable0[0] = findColumn(row0, "% charitable");
                    return;
                }

                if (row0.length <= colCrd0[0]) return;
                int crd0 = parseIntSafe(cell(row0, colCrd0[0]));
                if (crd0 == 0) return;

                ScoutUniverseRecord rec0 = new ScoutUniverseRecord();
                rec0.crd = crd0;
                rec0.firmName = cell(row0, colName0[0]);
                rec0.website = cell(row0, colWebsite0[0]);
                rec0.city = cell(row0, colCity0[0]);
                rec0.state = cell(row0, colState0[0]);
                rec0.country = cell(row0, colCountry0[0]);
                rec0.phone = cell(row0, colPhone0[0]);
                rec0.contactEmail = cell(row0, colEmail0[0]);
                rec0.raumTotal = parseDoubleSafe(cell(row0, colRaumTotal0[0]));
                rec0.raumDiscretionary = parseDoubleSafe(cell(row0, colRaumDisc0[0]));
                rec0.employees = parseIntSafe(cell(row0, colEmployees0[0]));
                rec0.clientTypes = splitList(cell(row0, colClientTypes0[0]));

                rec0.linkedinUrl = optionalCell(row0, colLinkedin0[0]);
                rec0.secFileNumber = optionalCell(row0, colSecFileNumber0[0]);
                rec0.lei = optionalCell(row0, colLei0[0]);
                rec0.umbrellaRegistration = optionalBoolean(row0, colUmbrella0[0]);
                rec0.pctAssetsPooledVehicles = optionalDouble(row0, colPctPooled0[0]);
                rec0.pctAssetsPensionPlans = optionalDouble(row0, colPctPension0[0]);
                rec0.pctAssetsCharitableOrgs = optionalDouble(row0, colPctCharitable0[0]);

                byCrd0.put(crd0, rec0);
            }
        });

        if (!iaHeaderSeen0[0]) return new ArrayList<ScoutUniverseRecord>();

        final int[] sColCrd0 = new int[1];
        final int[] sColFund0 = new int[1];
        final int[] sColType0 = new int[1];
        final int[] sColGav0 = new int[1];
        final int[] sColOwners0 = new int[1];

        // Optional Schedule D 7.B.1 per-fund columns; UNVERIFIED, see audit doc.
        final int[] sColFundCik0 = new int[1];
        final int[] sColFormDFileNumber0 = new int[1];
        final int[] sColMasterFeeder0 = new int[1];
        final int[] sColMinInvestment0 = new int[1];
        final int[] sColPctFoF0 = new int[1];

        parseCsvRows(scheduleDCsv0, new Consumer<String[]>()
        {
            boolean headerSeen0 = false;

            @Override
            public void accept(String[] row0)
            {
                if (!headerSeen0)
                {
                    headerSeen0 = true;
                    sColCrd0[0] = requireColumn(row0, "crd number");
                    sColFund0[0] = requireColumn(row0, "fund name");
                    sColType0[0] = requireColumn(row0, "fund type");
                    sColGav0[0] = requireColumn(row0, "gross asset value");
                    sColOwners0[0] = requireColumn(row0, "beneficial owners");

                    sColFundCik0[0] = findColumn(row0, "fund cik");
                    sColFormDFileNumber0[0] = findColumn(row0, "form d file number");
                    sColMasterFeeder0[0] = findColumn(row0, "master/feeder");
                    sColMinInvestment0[0] = findColumn(row0, "minimum investment");
                    sColPctFoF0[0] = findColumn(row0, "% owned by fund of funds");
                    return;
                }

                if (row0.length <= sColCrd0[0]) return;
                int crd0 = parseIntSafe(cell(row0, sColCrd0[0]));
                ScoutUniverseRecord rec0 = byCrd0.get(crd0);
                if (rec0 == null) return;

                ScoutFundRecord fund0 = new ScoutFundRecord();
                fund0.name = cell(row0, sColFund0[0]);
                fund0.type = cell(row0, sColType0[0]);
                fund0.grossAssetValue = parseDoubleSafe(cell(row0, sColGav0[0]));
                fund0.ownerCount = parseIntSafe(cell(row0, sColOwners0[0]));

                fund0.fundCik = optionalCell(row0, sColFundCik0[0]);
                fund0.formDFileNumber = optionalCell(row0, sColFormDFileNumber0[0]);
                fund0.masterFeederFlag = optionalCell(row0, sColMasterFeeder0[0]);
                fund0.minimumInvestment = optionalDouble(row0, sColMinInvestment0[0]);
                fund0.pctOwnedByFundOfFunds = optionalDouble(row0, sColPctFoF0[0]);

                rec0.funds.add(fund0);
            }
        });

        return new ArrayList<ScoutUniverseRecord>(byCrd0.values());
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
    // -----------------------------------------------------------------------

    /**
     * Streams rows from reader0 one at a time into rowConsumer0 (first row = headers)
     * instead of materializing the whole CSV in memory, so peak memory for a
     * multi-hundred-MB bulk file stays O(one row), not O(file). RFC4180-ish quoting/
     * multiline state machine is unchanged from the original list-returning version.
     */
    private static void parseCsvRows(Reader reader0, Consumer<String[]> rowConsumer0) throws IOException
    {
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
                    rowConsumer0.accept(current0.toArray(new String[0]));
                    current0 = new ArrayList<String>();
                    rowHasContent0 = false;
                }
                else if (c0 == '\n')
                {
                    current0.add(field0.toString());
                    field0.setLength(0);
                    rowConsumer0.accept(current0.toArray(new String[0]));
                    current0 = new ArrayList<String>();
                    rowHasContent0 = false;
                }
                else field0.append(c0);
            }
        }

        if (rowHasContent0 || field0.length() > 0 || !current0.isEmpty())
        {
            current0.add(field0.toString());
            rowConsumer0.accept(current0.toArray(new String[0]));
        }
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
                "AdvBulkClient: missing required ADV CSV column matching \"" + keyword0 + "\"");
        return idx0;
    }

    private static String cell(String[] row0, int idx0)
    {
        return (idx0 >= 0 && idx0 < row0.length && row0[idx0] != null) ? row0[idx0].trim() : "";
    }

    /**
     * Like cell(), but for optional (non-required) columns: idx0 of -1 (column not
     * found at all in this vintage) or an out-of-range row returns "" rather than
     * ever throwing. Never call requireColumn for these.
     */
    private static String optionalCell(String[] row0, int idx0)
    {
        if (idx0 < 0) return "";
        return cell(row0, idx0);
    }

    private static Double optionalDouble(String[] row0, int idx0)
    {
        if (idx0 < 0) return null;
        String s0 = cell(row0, idx0);
        if (isBlank(s0)) return null;
        return parseDoubleSafe(s0);
    }

    private static Boolean optionalBoolean(String[] row0, int idx0)
    {
        if (idx0 < 0) return null;
        String s0 = cell(row0, idx0);
        if (isBlank(s0)) return null;
        String lower0 = s0.trim().toLowerCase();
        return lower0.equals("y") || lower0.equals("yes") || lower0.equals("true") || lower0.equals("1");
    }

    private static int parseIntSafe(String s0)
    {
        if (isBlank(s0)) return 0;
        try
        {
            String cleaned0 = s0.replaceAll("[^0-9.\\-]", "");
            if (isBlank(cleaned0)) return 0;
            return (int) Double.parseDouble(cleaned0);
        }
        catch (Exception e0) { return 0; }
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

    private static List<String> splitList(String s0)
    {
        List<String> out0 = new ArrayList<String>();
        if (isBlank(s0)) return out0;
        for (String part0 : s0.split("[|;]"))
        {
            String trimmed0 = part0.trim();
            if (!trimmed0.isEmpty()) out0.add(trimmed0);
        }
        return out0;
    }

    private static boolean isBlank(String s0) { return s0 == null || s0.trim().isEmpty(); }
}
