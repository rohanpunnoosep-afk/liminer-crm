package com.liminer.scout;

import com.liminer.enrich.AdvBulkClient;

import java.io.StringReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/*
 * ScoutAdvParseTestMain — fully offline verification for AdvBulkClient's streaming
 * CSV parse and cleanupBulkFiles helper. Confirms the RFC4180 quoting/multiline
 * rework produces identical records to the pre-refactor behavior, that a large
 * synthetic file parses without error, and that cleanup deletes temp files (and
 * no-ops on missing ones). Prints SCOUT_ADV_PARSE_OK on success.
 */
public class ScoutAdvParseTestMain
{
    public static void main(String[] args0)
    {
        try
        {
            testQuotingAndMultiline();
            testLargeFixture();
            testCleanupBulkFiles();
            System.out.println("SCOUT_ADV_PARSE_OK");
        }
        catch (Throwable t0)
        {
            System.err.println("SCOUT_ADV_PARSE_FAILED: " + t0);
            t0.printStackTrace();
            System.exit(1);
        }
    }

    private static void testQuotingAndMultiline() throws Exception
    {
        AdvBulkClient client0 = new AdvBulkClient();

        String iaHeader0 =
            "CRD Number,Legal Name,Website,Main Office City,Main Office State,Main Office Country,"
            + "Main Office Phone Number,Contact Email,Total Regulatory Assets Under Management,"
            + "Discretionary Regulatory Assets Under Management,Total Employees,Client Types\n";

        // Row 1: quoted field with an embedded comma and an embedded newline plus a
        // "" escaped quote. Row 2: plain unquoted row for contrast.
        String iaCsv0 = iaHeader0
            + "1001,\"Acme, Capital \"\"Partners\"\"\nGroup\",acme.com,New York,NY,USA,212-555-0100,"
            + "ir@acme.com,500000000,450000000,25,Pension Funds|Endowments\n"
            + "1002,Beacon Advisors,beacon.com,Boston,MA,USA,617-555-0101,info@beacon.com,"
            + "200000000,180000000,12,High Net Worth Individuals\n";

        String sdHeader0 = "CRD Number,Fund Name,Fund Type,Gross Asset Value,Number of Beneficial Owners\n";
        String sdCsv0 = sdHeader0
            + "1001,\"Acme Multi-Strategy, Fund of Funds\",Fund of Funds,100000000,45\n";

        List<ScoutUniverseRecord> records0 = client0.parseCsv(new StringReader(iaCsv0), new StringReader(sdCsv0));
        assertTrue(records0.size() == 2, "expected 2 records, got " + records0.size());

        ScoutUniverseRecord rec1001 = null;
        ScoutUniverseRecord rec1002 = null;
        for (ScoutUniverseRecord r0 : records0)
        {
            if (r0.crd == 1001) rec1001 = r0;
            if (r0.crd == 1002) rec1002 = r0;
        }
        assertTrue(rec1001 != null, "missing CRD 1001");
        assertTrue(rec1002 != null, "missing CRD 1002");

        assertTrue(eq(rec1001.firmName, "Acme, Capital \"Partners\"\nGroup"),
            "quoted/multiline/escaped firmName mismatch, got [" + rec1001.firmName + "]");
        assertTrue(eq(rec1001.website, "acme.com"), "website mismatch for 1001");
        assertTrue(rec1001.raumTotal == 500000000.0, "raumTotal mismatch for 1001");
        assertTrue(rec1001.funds.size() == 1, "expected 1 fund for 1001, got " + rec1001.funds.size());
        assertTrue(eq(rec1001.funds.get(0).name, "Acme Multi-Strategy, Fund of Funds"),
            "quoted fund name mismatch, got [" + rec1001.funds.get(0).name + "]");

        assertTrue(eq(rec1002.firmName, "Beacon Advisors"), "firmName mismatch for 1002");
        assertTrue(rec1002.funds.isEmpty(), "expected no funds for 1002");

        // Missing-header failure path still throws, naming the missing header.
        String iaCsvMissingHeader0 =
            "Legal Name,Website,Main Office City,Main Office State,Main Office Country,"
            + "Main Office Phone Number,Contact Email,Total Regulatory Assets Under Management,"
            + "Discretionary Regulatory Assets Under Management,Total Employees,Client Types\n"
            + "Acme Capital Partners,acme.com,New York,NY,USA,212-555-0100,ir@acme.com,500000000,450000000,25,Pension Funds\n";
        boolean threw0 = false;
        try
        {
            client0.parseCsv(new StringReader(iaCsvMissingHeader0), new StringReader(sdHeader0));
        }
        catch (IllegalArgumentException e0)
        {
            threw0 = e0.getMessage() != null && e0.getMessage().toLowerCase().contains("crd number");
        }
        assertTrue(threw0, "parseCsv should throw naming the missing 'crd number' header");
    }

    private static void testLargeFixture() throws Exception
    {
        AdvBulkClient client0 = new AdvBulkClient();

        String iaHeader0 =
            "CRD Number,Legal Name,Website,Main Office City,Main Office State,Main Office Country,"
            + "Main Office Phone Number,Contact Email,Total Regulatory Assets Under Management,"
            + "Discretionary Regulatory Assets Under Management,Total Employees,Client Types\n";

        int rowCount0 = 50000;
        StringBuilder iaCsv0 = new StringBuilder(iaHeader0);
        for (int i0 = 1; i0 <= rowCount0; i0++)
        {
            iaCsv0.append(i0).append(",Firm ").append(i0).append(",firm").append(i0).append(".com,")
                .append("City").append(i0 % 100).append(",NY,USA,212-555-0100,ir").append(i0)
                .append("@firm.com,").append(1000000 + i0).append(',').append(900000 + i0)
                .append(',').append(i0 % 50).append(",Endowments\n");
        }

        String sdHeader0 = "CRD Number,Fund Name,Fund Type,Gross Asset Value,Number of Beneficial Owners\n";
        StringBuilder sdCsv0 = new StringBuilder(sdHeader0);
        for (int i0 = 1; i0 <= rowCount0; i0 += 10)
        {
            sdCsv0.append(i0).append(",Fund ").append(i0).append(",Private Equity Fund,5000000,10\n");
        }

        List<ScoutUniverseRecord> records0 =
            client0.parseCsv(new StringReader(iaCsv0.toString()), new StringReader(sdCsv0.toString()));
        assertTrue(records0.size() == rowCount0,
            "expected " + rowCount0 + " records from large fixture, got " + records0.size());
    }

    private static void testCleanupBulkFiles() throws Exception
    {
        Path tempDir0 = Files.createTempDirectory("scout-adv-parse-test");
        Path zip0 = tempDir0.resolve("adv_bulk.zip");
        Path ia0 = tempDir0.resolve("ia.csv");
        Path sd0 = tempDir0.resolve("sd.csv");

        Files.writeString(zip0, "zip-bytes");
        Files.writeString(ia0, "ia-bytes");
        Files.writeString(sd0, "sd-bytes");
        assertTrue(Files.exists(zip0) && Files.exists(ia0) && Files.exists(sd0),
            "fixture temp files should exist before cleanup");

        AdvBulkClient.cleanupBulkFiles(zip0, ia0, sd0);

        assertTrue(!Files.exists(zip0), "zip should be deleted after cleanup");
        assertTrue(!Files.exists(ia0), "ia csv should be deleted after cleanup");
        assertTrue(!Files.exists(sd0), "sd csv should be deleted after cleanup");

        // No-op for already-missing paths (must not throw).
        AdvBulkClient.cleanupBulkFiles(zip0, ia0, sd0);
    }

    private static boolean eq(String a0, String b0)
    {
        return a0 == null ? b0 == null : a0.equals(b0);
    }

    private static void assertTrue(boolean condition0, String message0)
    {
        if (!condition0) throw new AssertionError(message0);
    }
}
