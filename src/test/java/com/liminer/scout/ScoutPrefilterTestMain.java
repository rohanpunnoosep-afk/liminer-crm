package com.liminer.scout;

import com.liminer.core.UserAccount;

import java.util.ArrayList;
import java.util.List;

/*
 * ScoutPrefilterTestMain — fully offline verification for ScoutPrefilter's
 * four ordered filters (allocator test, resources band, geography gate,
 * dedupe) using synthetic ScoutUniverseRecords covering every branch. Also
 * spot-checks ScoutClientProfile.fromUserAccount and the resources-band
 * static formula. Prints SCOUT_PREFILTER_OK on success.
 */
public class ScoutPrefilterTestMain
{
    public static void main(String[] args0)
    {
        try
        {
            run();
            System.out.println("SCOUT_PREFILTER_OK");
        }
        catch (Throwable t0)
        {
            System.err.println("SCOUT_PREFILTER_FAILED: " + t0);
            t0.printStackTrace();
            System.exit(1);
        }
    }

    private static void run() throws Exception
    {
        // ---- Resources-band formula sanity check --------------------------------
        double[] band0 = ScoutPrefilter.computeResourcesBand(7_500_000.0);
        assertTrue(approx(band0[0], 300_000_000.0), "band min should be 300M, got " + band0[0]);
        assertTrue(approx(band0[1], 3_000_000_000.0), "band max should be 3B, got " + band0[1]);

        double[] floorBand0 = ScoutPrefilter.computeResourcesBand(1.0);
        assertTrue(floorBand0[0] == 50_000_000.0, "band min should clamp to 50M floor, got " + floorBand0[0]);

        double[] capBand0 = ScoutPrefilter.computeResourcesBand(1_000_000_000.0);
        assertTrue(capBand0[1] == 50_000_000_000.0, "band max should clamp to 50B cap, got " + capBand0[1]);

        // ---- ScoutClientProfile.fromUserAccount ----------------------------------
        ArrayList<String> internalNames0 = new ArrayList<String>();
        internalNames0.add("Ada Lovelace");
        ArrayList<String> internalEmails0 = new ArrayList<String>();
        internalEmails0.add("partner@fund.example");

        UserAccount user0 = new UserAccount(
            "u1", "u1@fund.com", "Test Fund", "crm1",
            internalNames0, internalEmails0,
            "Test Fund", "testfund.com", "",
            "Fintech,SaaS", "", "United States", "Seed",
            "", "");

        ScoutClientProfile profileFromUser0 = ScoutClientProfile.fromUserAccount(user0);
        assertTrue(profileFromUser0.geography.equals("United States"),
            "fromUserAccount should map clientGeography, got " + profileFromUser0.geography);
        assertTrue(profileFromUser0.sectorTags.size() == 2 && profileFromUser0.sectorTags.contains("Fintech"),
            "fromUserAccount should split clientSectorTags, got " + profileFromUser0.sectorTags);

        // ---- Build synthetic universe covering every branch ----------------------
        ScoutClientProfile client0 = new ScoutClientProfile();
        client0.targetFundSize = 100_000_000.0;
        client0.geography = "United States";
        // checkSize left unset -> 7.5% default -> band [300M, 3B]

        List<ScoutUniverseRecord> universe0 = new ArrayList<ScoutUniverseRecord>();

        // 1. FoF passing all gates.
        universe0.add(buildRecord(2001, "Global Allocators Fund of Funds LLC", "globalallocators.com",
            "USA", 500_000_000.0, fund("Fund of Funds"), "Institutional"));

        // 2. Pooled-vehicle-clients adviser passing all gates (no FoF fund).
        universe0.add(buildRecord(2002, "Beacon Institutional Partners", "beaconinstitutional.com",
            "USA", 1_000_000_000.0, null, "Pooled Investment Vehicles"));

        // 3. Non-allocator fails filter 1.
        universe0.add(buildRecord(2003, "Retail Wealth Advisors", "retailwealth.com",
            "USA", 500_000_000.0, null, "High Net Worth Individuals"));

        // 4. Too-small RAUM fails filter 2.
        universe0.add(buildRecord(2004, "Tiny Fund of Funds LLC", "tinyfof.com",
            "USA", 100_000_000.0, fund("Fund of Funds"), "Institutional"));

        // 5. Too-big RAUM fails filter 2.
        universe0.add(buildRecord(2005, "Mega Fund of Funds LP", "megafof.com",
            "USA", 10_000_000_000.0, fund("Fund of Funds"), "Institutional"));

        // 6. Clear geography mismatch fails filter 3.
        universe0.add(buildRecord(2006, "London Fund of Funds Ltd", "londonfof.co.uk",
            "United Kingdom", 500_000_000.0, fund("Fund of Funds"), "Institutional"));

        // 7. Name-duplicate fails filter 4.
        universe0.add(buildRecord(2007, "Acme Capital Partners", "acme-unique-domain.com",
            "USA", 500_000_000.0, fund("Fund of Funds"), "Institutional"));

        // 8. Domain-duplicate fails filter 4.
        universe0.add(buildRecord(2008, "Cascade Allocators Fund of Funds", "cascade-allocators.com",
            "USA", 500_000_000.0, fund("Fund of Funds"), "Institutional"));

        // 9. CRD-duplicate fails filter 4.
        universe0.add(buildRecord(2009, "Echo Fund of Funds Partners", "echofof.com",
            "USA", 500_000_000.0, fund("Fund of Funds"), "Institutional"));

        ScoutDedupeIndex dedupe0 = new ScoutDedupeIndex();
        dedupe0.addFirmName("Acme Capital, LLC"); // normalizes to same key as "Acme Capital Partners"
        dedupe0.addWebsite("http://www.cascade-allocators.com/");
        dedupe0.addCrd(2009);

        ScoutPrefilter prefilter0 = new ScoutPrefilter();
        ScoutPrefilterResult result0 = prefilter0.filter(universe0, client0, dedupe0);

        assertTrue(result0.totalInput == 9, "totalInput should be 9, got " + result0.totalInput);
        assertTrue(result0.droppedAllocatorTest == 1,
            "droppedAllocatorTest should be 1, got " + result0.droppedAllocatorTest);
        assertTrue(result0.droppedResourcesBand == 2,
            "droppedResourcesBand should be 2, got " + result0.droppedResourcesBand);
        assertTrue(result0.droppedGeography == 1,
            "droppedGeography should be 1, got " + result0.droppedGeography);
        assertTrue(result0.droppedDedupe == 3,
            "droppedDedupe should be 3, got " + result0.droppedDedupe);
        assertTrue(result0.keptCount() == 2,
            "keptCount should be 2, got " + result0.keptCount());

        List<Integer> keptCrds0 = new ArrayList<Integer>();
        for (ScoutUniverseRecord r0 : result0.kept) keptCrds0.add(r0.crd);
        assertTrue(keptCrds0.contains(2001), "kept should include CRD 2001, got " + keptCrds0);
        assertTrue(keptCrds0.contains(2002), "kept should include CRD 2002, got " + keptCrds0);

        // ---- Blank client geography must keep the record that failed geography ----
        ScoutClientProfile blankGeoClient0 = new ScoutClientProfile();
        blankGeoClient0.targetFundSize = 100_000_000.0;
        blankGeoClient0.geography = "";

        List<ScoutUniverseRecord> londonOnly0 = new ArrayList<ScoutUniverseRecord>();
        londonOnly0.add(buildRecord(2006, "London Fund of Funds Ltd", "londonfof.co.uk",
            "United Kingdom", 500_000_000.0, fund("Fund of Funds"), "Institutional"));

        ScoutPrefilterResult blankGeoResult0 = prefilter0.filter(londonOnly0, blankGeoClient0, new ScoutDedupeIndex());
        assertTrue(blankGeoResult0.droppedGeography == 0,
            "blank client geography should not drop any record on geography, got " + blankGeoResult0.droppedGeography);
        assertTrue(blankGeoResult0.keptCount() == 1,
            "blank client geography should keep the record, got " + blankGeoResult0.keptCount());
    }

    private static ScoutFundRecord fund(String type0)
    {
        ScoutFundRecord f0 = new ScoutFundRecord();
        f0.name = type0 + " Vehicle";
        f0.type = type0;
        f0.grossAssetValue = 50_000_000.0;
        f0.ownerCount = 10;
        return f0;
    }

    private static ScoutUniverseRecord buildRecord(
        int crd0, String firmName0, String website0, String country0,
        double raumTotal0, ScoutFundRecord fund0, String clientType0)
    {
        ScoutUniverseRecord r0 = new ScoutUniverseRecord();
        r0.crd = crd0;
        r0.firmName = firmName0;
        r0.website = website0;
        r0.city = "";
        r0.state = "";
        r0.country = country0;
        r0.raumTotal = raumTotal0;
        r0.raumDiscretionary = raumTotal0;
        r0.employees = 10;
        r0.clientTypes = new ArrayList<String>();
        r0.clientTypes.add(clientType0);
        r0.contactEmail = "";
        r0.phone = "";
        r0.funds = new ArrayList<ScoutFundRecord>();
        if (fund0 != null)
        {
            r0.funds.add(fund0);
        }
        r0.snapshotMonth = "2026-06";
        return r0;
    }

    private static boolean approx(double a0, double b0)
    {
        return Math.abs(a0 - b0) < 0.01;
    }

    private static void assertTrue(boolean condition0, String message0)
    {
        if (!condition0) throw new AssertionError(message0);
    }
}
