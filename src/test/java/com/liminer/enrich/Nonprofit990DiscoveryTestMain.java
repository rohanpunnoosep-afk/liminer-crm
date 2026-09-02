package com.liminer.enrich;

import com.liminer.scout.ScoutUniverseRecord;

import java.io.StringReader;
import java.util.List;

/*
 * Nonprofit990DiscoveryTestMain — fully offline test for the nonprofit 990
 * DISCOVERY mode added as Phase 2 task 2 of the Investor Scout plan:
 * ProPublicaNonprofitClient.mapDiscoveryResults (NTEE/state search ->
 * ScoutUniverseRecord candidates) and Irs990BulkIndexClient.parseIndex
 * (bulk 990-index CSV, threshold-filtered -> ScoutUniverseRecord candidates).
 * Canned response fixtures are embedded as string literals; no network
 * access. Prints NONPROFIT_990_OK and exits 0 on success; exits 1 on any
 * assertion failure.
 */
public class Nonprofit990DiscoveryTestMain
{
    private static int failures0 = 0;

    public static void main(String[] args0) throws Exception
    {
        testDiscoverySearchMapsOrganizations();
        testDiscoverySearchSkipsMissingEin();
        testDiscoverySearchEmptyIsEmptyList();

        testBulkIndexKeepsQualifyingRows();
        testBulkIndexDropsBelowThreshold();
        testBulkIndexMissingHeaderThrows();

        if (failures0 > 0)
        {
            System.err.println("Nonprofit990DiscoveryTestMain: " + failures0 + " failure(s)");
            System.exit(1);
        }
        System.out.println("NONPROFIT_990_OK");
    }

    // -----------------------------------------------------------------------
    // ProPublicaNonprofitClient.mapDiscoveryResults
    // -----------------------------------------------------------------------

    private static void testDiscoverySearchMapsOrganizations()
    {
        String json0 = "{\"organizations\":["
            + "{\"ein\":123456789,\"name\":\"Example Family Foundation\","
            + "\"city\":\"Boston\",\"state\":\"MA\",\"ntee_code\":\"T20\"},"
            + "{\"ein\":987654321,\"name\":\"Sample Charitable Trust\","
            + "\"city\":\"Chicago\",\"state\":\"IL\",\"ntee_code\":null}"
            + "]}";

        ProPublicaNonprofitClient client0 = new ProPublicaNonprofitClient();
        List<ScoutUniverseRecord> recs0 = client0.mapDiscoveryResults(json0);

        assertEquals(2, recs0.size(), "Discovery: expected 2 mapped records");

        ScoutUniverseRecord rec0 = recs0.get(0);
        assertEquals(0, rec0.crd, "Discovery: crd should stay 0 (no CRD concept)");
        assertEquals("123456789", rec0.externalRegisterId, "Discovery: externalRegisterId should be the EIN");
        assertEquals("IRS_990", rec0.sourceRegister, "Discovery: sourceRegister should be IRS_990");
        assertEquals("Example Family Foundation", rec0.firmName, "Discovery: firmName mismatch");
        assertEquals("Boston", rec0.city, "Discovery: city mismatch");
        assertEquals("MA", rec0.state, "Discovery: state mismatch");
        assertEquals("United States", rec0.country, "Discovery: country mismatch");
        assertEquals("T20", rec0.nteeCode, "Discovery: nteeCode mismatch");

        ScoutUniverseRecord rec1 = recs0.get(1);
        assertEquals("", rec1.nteeCode, "Discovery: null ntee_code should map to empty string");
    }

    private static void testDiscoverySearchSkipsMissingEin()
    {
        String json0 = "{\"organizations\":["
            + "{\"name\":\"No EIN Org\",\"city\":\"Miami\",\"state\":\"FL\"},"
            + "{\"ein\":555555555,\"name\":\"Has EIN Org\",\"city\":\"Dallas\",\"state\":\"TX\"}"
            + "]}";

        ProPublicaNonprofitClient client0 = new ProPublicaNonprofitClient();
        List<ScoutUniverseRecord> recs0 = client0.mapDiscoveryResults(json0);

        assertEquals(1, recs0.size(), "Discovery: org missing ein should be skipped");
        assertEquals("Has EIN Org", recs0.get(0).firmName, "Discovery: remaining record should be the one with an EIN");
    }

    private static void testDiscoverySearchEmptyIsEmptyList()
    {
        ProPublicaNonprofitClient client0 = new ProPublicaNonprofitClient();
        assertEquals(0, client0.mapDiscoveryResults("").size(), "Discovery: blank body -> empty list");
        assertEquals(0, client0.mapDiscoveryResults("{}").size(), "Discovery: no \"organizations\" key -> empty list");
    }

    // -----------------------------------------------------------------------
    // Irs990BulkIndexClient.parseIndex
    // -----------------------------------------------------------------------

    private static void testBulkIndexKeepsQualifyingRows() throws Exception
    {
        String csv0 =
            "EIN,ORGANIZATION_NAME,CITY,STATE,NTEE_CD,TAX_PRD_YR,TOTASSETSEND,INVSTMNTINC\n"
          + "111111111,Big Endowment Foundation,New York,NY,T31,2024,50000000,500000\n"
          + "222222222,Small Local Charity,Reno,NV,P20,2024,200000,1000\n";

        Irs990BulkIndexClient client0 = new Irs990BulkIndexClient();
        List<ScoutUniverseRecord> recs0;
        try (StringReader r0 = new StringReader(csv0))
        {
            recs0 = client0.parseIndex(r0,
                Irs990BulkIndexClient.DEFAULT_MIN_TOTAL_ASSETS,
                Irs990BulkIndexClient.DEFAULT_MIN_INVESTMENT_INCOME);
        }

        assertEquals(1, recs0.size(), "BulkIndex: only the qualifying row should survive the default thresholds");
        ScoutUniverseRecord rec0 = recs0.get(0);
        assertEquals("111111111", rec0.externalRegisterId, "BulkIndex: externalRegisterId should be the EIN");
        assertEquals("IRS_990_BULK", rec0.sourceRegister, "BulkIndex: sourceRegister should be IRS_990_BULK");
        assertEquals("Big Endowment Foundation", rec0.firmName, "BulkIndex: firmName mismatch");
        assertEquals("New York", rec0.city, "BulkIndex: city mismatch");
        assertEquals("NY", rec0.state, "BulkIndex: state mismatch");
        assertEquals("T31", rec0.nteeCode, "BulkIndex: nteeCode mismatch");
        assertEquals(50000000.0, rec0.nonprofitTotalAssets990, "BulkIndex: nonprofitTotalAssets990 mismatch");
        assertEquals("2024", rec0.snapshotMonth, "BulkIndex: snapshotMonth should carry the tax year");
    }

    private static void testBulkIndexDropsBelowThreshold() throws Exception
    {
        String csv0 =
            "EIN,ORGANIZATION_NAME,TOTASSETSEND,INVSTMNTINC\n"
          + "333333333,Just Under Assets,9999999,500000\n"
          + "444444444,Just Under Income,50000000,99999\n";

        Irs990BulkIndexClient client0 = new Irs990BulkIndexClient();
        List<ScoutUniverseRecord> recs0;
        try (StringReader r0 = new StringReader(csv0))
        {
            recs0 = client0.parseIndex(r0, 10_000_000.0, 100_000.0);
        }
        assertEquals(0, recs0.size(), "BulkIndex: rows under either threshold should be dropped");
    }

    private static void testBulkIndexMissingHeaderThrows()
    {
        String csv0 = "EIN,ORGANIZATION_NAME,TOTASSETSEND\n111111111,Missing Income Column,50000000\n";
        Irs990BulkIndexClient client0 = new Irs990BulkIndexClient();
        boolean threw0 = false;
        try (StringReader r0 = new StringReader(csv0))
        {
            client0.parseIndex(r0, 10_000_000.0, 100_000.0);
        }
        catch (IllegalArgumentException e0)
        {
            threw0 = e0.getMessage() != null && e0.getMessage().contains("invstmntinc");
        }
        catch (Exception e0) { /* fallthrough to assertion below */ }
        assertTrue(threw0, "BulkIndex: missing \"invstmntinc\" column should throw naming the column");
    }

    // -----------------------------------------------------------------------
    // Assertion helpers
    // -----------------------------------------------------------------------

    private static void assertTrue(boolean cond0, String msg0)
    {
        if (!cond0)
        {
            System.err.println("FAIL: " + msg0);
            failures0++;
        }
    }

    private static void assertEquals(Object expected0, Object actual0, String msg0)
    {
        boolean eq0 = expected0 == null ? actual0 == null : expected0.equals(actual0);
        if (!eq0)
        {
            System.err.println("FAIL: " + msg0 + " (expected=" + expected0 + ", actual=" + actual0 + ")");
            failures0++;
        }
    }
}
