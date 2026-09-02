package com.liminer.scout;

import com.liminer.enrich.AdvBulkClient;

import java.io.StringReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/*
 * ScoutUniverseTestMain — fully offline verification for AdvBulkClient.parseCsv,
 * FileScoutUniverseStore round-tripping, and ScoutUniverseStore.diff. Builds two
 * small synthetic ADV bulk CSV fixtures (month A / month B) in-memory, parses
 * them, saves+reloads via the store in a temp dir, and asserts the diff detects
 * all three timing-signal categories. Prints SCOUT_UNIVERSE_OK on success.
 */
public class ScoutUniverseTestMain
{
    public static void main(String[] args0)
    {
        try
        {
            run();
            System.out.println("SCOUT_UNIVERSE_OK");
        }
        catch (Throwable t0)
        {
            System.err.println("SCOUT_UNIVERSE_FAILED: " + t0);
            t0.printStackTrace();
            System.exit(1);
        }
    }

    private static void run() throws Exception
    {
        AdvBulkClient client0 = new AdvBulkClient();

        // ---- Month A fixture --------------------------------------------------
        String iaHeader0 =
            "CRD Number,Legal Name,Website,Main Office City,Main Office State,Main Office Country,"
            + "Main Office Phone Number,Contact Email,Total Regulatory Assets Under Management,"
            + "Discretionary Regulatory Assets Under Management,Total Employees,Client Types,"
            + "LinkedIn URL,SEC File Number\n";

        String iaCsvA0 = iaHeader0
            + "1001,Acme Capital Partners,acme.com,New York,NY,USA,212-555-0100,ir@acme.com,500000000,450000000,25,Pension Funds|Endowments,https://www.linkedin.com/company/acme-capital,801-12345\n"
            + "1002,Beacon Advisors,beacon.com,Boston,MA,USA,617-555-0101,info@beacon.com,200000000,180000000,12,High Net Worth Individuals,,\n"
            + "1003,Cascade Capital,cascade.com,Seattle,WA,USA,206-555-0102,contact@cascade.com,750000000,700000000,40,Institutional,,\n"
            + "1004,Dune Investment Group,dune.com,Chicago,IL,USA,312-555-0103,ir@dune.com,300000000,275000000,18,Foundations,,\n"
            + "1005,Echo Wealth Management,echo.com,Denver,CO,USA,303-555-0104,hello@echo.com,150000000,140000000,9,Family Offices,,\n";

        String sdHeader0 = "CRD Number,Fund Name,Fund Type,Gross Asset Value,Number of Beneficial Owners,Fund CIK\n";

        String sdCsvA0 = sdHeader0
            + "1001,Acme Multi-Strategy Fund of Funds,Fund of Funds,100000000,45,0001234567\n"
            + "1003,Cascade Growth Fund,Private Equity Fund,50000000,20,\n";

        List<ScoutUniverseRecord> monthA0 = client0.parseCsv(new StringReader(iaCsvA0), new StringReader(sdCsvA0));
        assertTrue(monthA0.size() == 5, "month A should parse 5 advisers, got " + monthA0.size());

        ScoutUniverseRecord acme0 = findByCrd(monthA0, 1001);
        assertTrue("https://www.linkedin.com/company/acme-capital".equals(acme0.linkedinUrl),
            "linkedinUrl should parse from optional column, got " + acme0.linkedinUrl);
        assertTrue("801-12345".equals(acme0.secFileNumber),
            "secFileNumber should parse from optional column, got " + acme0.secFileNumber);
        assertTrue(!acme0.funds.isEmpty() && "0001234567".equals(acme0.funds.get(0).fundCik),
            "fund CIK should parse from optional Schedule D column, got "
                + (acme0.funds.isEmpty() ? "<no funds>" : acme0.funds.get(0).fundCik));

        ScoutUniverseRecord beacon0 = findByCrd(monthA0, 1002);
        assertTrue(beacon0.linkedinUrl != null && beacon0.linkedinUrl.isEmpty(),
            "linkedinUrl should be blank (not throw) when the optional column is empty for a row");

        // ---- Month B fixture: new registrant (1006), RAUM jump (1002 +30%), ----
        // ---- new Fund of Funds fund (1004) --------------------------------------
        String iaCsvB0 = iaHeader0
            + "1001,Acme Capital Partners,acme.com,New York,NY,USA,212-555-0100,ir@acme.com,500000000,450000000,25,Pension Funds|Endowments,https://www.linkedin.com/company/acme-capital,801-12345\n"
            + "1002,Beacon Advisors,beacon.com,Boston,MA,USA,617-555-0101,info@beacon.com,260000000,235000000,13,High Net Worth Individuals,,\n"
            + "1003,Cascade Capital,cascade.com,Seattle,WA,USA,206-555-0102,contact@cascade.com,750000000,700000000,40,Institutional,,\n"
            + "1004,Dune Investment Group,dune.com,Chicago,IL,USA,312-555-0103,ir@dune.com,300000000,275000000,18,Foundations,,\n"
            + "1005,Echo Wealth Management,echo.com,Denver,CO,USA,303-555-0104,hello@echo.com,150000000,140000000,9,Family Offices,,\n"
            + "1006,Fjord Capital,fjord.com,Portland,OR,USA,503-555-0105,ir@fjord.com,120000000,110000000,7,Endowments,,\n";

        String sdCsvB0 = sdHeader0
            + "1001,Acme Multi-Strategy Fund of Funds,Fund of Funds,100000000,45,0001234567\n"
            + "1003,Cascade Growth Fund,Private Equity Fund,50000000,20,\n"
            + "1004,Dune Fund of Funds II,Fund of Funds,80000000,30,\n";

        List<ScoutUniverseRecord> monthB0 = client0.parseCsv(new StringReader(iaCsvB0), new StringReader(sdCsvB0));
        assertTrue(monthB0.size() == 6, "month B should parse 6 advisers, got " + monthB0.size());

        // Required-header failure path: drop the "CRD Number" column and expect a throw.
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

        // ---- Store round-trip ---------------------------------------------------
        Path tempDir0 = Files.createTempDirectory("scout-universe-test");
        ScoutUniverseStore store0 = new FileScoutUniverseStore(tempDir0);

        store0.save("2026-05", monthA0);
        store0.save("2026-06", monthB0);

        List<String> months0 = store0.availableMonths();
        assertTrue(months0.contains("2026-05") && months0.contains("2026-06"),
            "availableMonths should list both saved months, got " + months0);

        List<ScoutUniverseRecord> reloadedA0 = store0.load("2026-05");
        assertRoundTripEquals(monthA0, reloadedA0);

        List<ScoutUniverseRecord> reloadedB0 = store0.load("2026-06");
        assertRoundTripEquals(monthB0, reloadedB0);

        // ---- Diff ----------------------------------------------------------------
        ScoutUniverseDiff diff0 = store0.diff("2026-05", "2026-06");

        assertTrue(diff0.newCrds.contains(1006), "diff should detect new CRD 1006, got " + diff0.newCrds);
        assertTrue(diff0.raumChangeCrds.contains(1002),
            "diff should detect RAUM change for CRD 1002, got " + diff0.raumChangeCrds);
        assertTrue(diff0.newFundOfFundsCrds.contains(1004),
            "diff should detect new Fund of Funds for CRD 1004, got " + diff0.newFundOfFundsCrds);

        // Records unchanged between months should NOT trigger any diff category.
        assertTrue(!diff0.raumChangeCrds.contains(1001), "CRD 1001 RAUM unchanged, should not be flagged");
        assertTrue(!diff0.newFundOfFundsCrds.contains(1003),
            "CRD 1003 has a non-FoF fund, should not be flagged as new FoF");
    }

    private static ScoutUniverseRecord findByCrd(List<ScoutUniverseRecord> records0, int crd0)
    {
        for (ScoutUniverseRecord r0 : records0) if (r0.crd == crd0) return r0;
        throw new AssertionError("no record found for CRD " + crd0);
    }

    private static void assertRoundTripEquals(List<ScoutUniverseRecord> expected0, List<ScoutUniverseRecord> actual0)
    {
        assertTrue(expected0.size() == actual0.size(),
            "round-trip size mismatch: expected " + expected0.size() + " got " + actual0.size());

        Map<Integer, ScoutUniverseRecord> byCrd0 = new HashMap<Integer, ScoutUniverseRecord>();
        for (ScoutUniverseRecord r0 : actual0) byCrd0.put(r0.crd, r0);

        for (ScoutUniverseRecord exp0 : expected0)
        {
            ScoutUniverseRecord act0 = byCrd0.get(exp0.crd);
            assertTrue(act0 != null, "round-trip missing CRD " + exp0.crd);

            assertTrue(eq(exp0.firmName, act0.firmName), "firmName mismatch for CRD " + exp0.crd);
            assertTrue(eq(exp0.website, act0.website), "website mismatch for CRD " + exp0.crd);
            assertTrue(eq(exp0.city, act0.city), "city mismatch for CRD " + exp0.crd);
            assertTrue(eq(exp0.state, act0.state), "state mismatch for CRD " + exp0.crd);
            assertTrue(eq(exp0.country, act0.country), "country mismatch for CRD " + exp0.crd);
            assertTrue(exp0.raumTotal == act0.raumTotal, "raumTotal mismatch for CRD " + exp0.crd);
            assertTrue(exp0.raumDiscretionary == act0.raumDiscretionary, "raumDiscretionary mismatch for CRD " + exp0.crd);
            assertTrue(exp0.employees == act0.employees, "employees mismatch for CRD " + exp0.crd);
            assertTrue(exp0.clientTypes.equals(act0.clientTypes), "clientTypes mismatch for CRD " + exp0.crd);
            assertTrue(eq(exp0.contactEmail, act0.contactEmail), "contactEmail mismatch for CRD " + exp0.crd);
            assertTrue(eq(exp0.phone, act0.phone), "phone mismatch for CRD " + exp0.crd);
            assertTrue(eq(exp0.linkedinUrl, act0.linkedinUrl), "linkedinUrl mismatch for CRD " + exp0.crd);
            assertTrue(eq(exp0.secFileNumber, act0.secFileNumber), "secFileNumber mismatch for CRD " + exp0.crd);
            assertTrue(exp0.funds.size() == act0.funds.size(), "funds size mismatch for CRD " + exp0.crd);

            for (int i0 = 0; i0 < exp0.funds.size(); i0++)
            {
                ScoutFundRecord ef0 = exp0.funds.get(i0);
                ScoutFundRecord af0 = act0.funds.get(i0);
                assertTrue(eq(ef0.name, af0.name), "fund name mismatch for CRD " + exp0.crd);
                assertTrue(eq(ef0.type, af0.type), "fund type mismatch for CRD " + exp0.crd);
                assertTrue(ef0.grossAssetValue == af0.grossAssetValue, "fund GAV mismatch for CRD " + exp0.crd);
                assertTrue(ef0.ownerCount == af0.ownerCount, "fund ownerCount mismatch for CRD " + exp0.crd);
                assertTrue(eq(ef0.fundCik, af0.fundCik), "fund CIK mismatch for CRD " + exp0.crd);
            }
        }
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
