package com.liminer.scout;

import java.time.LocalDate;

/*
 * ScoutLedgerTestMain — fully offline test of ScoutLedger.eligible(...): no
 * Sheets access, LedgerEntry objects are constructed directly in memory.
 * Prints SCOUT_LEDGER_OK on success; System.exit(1) on any failure.
 */
public class ScoutLedgerTestMain
{
    public static void main(String[] args)
    {
        LocalDate today0 = LocalDate.of(2026, 7, 5);

        // (a) APPENDED entries are never eligible, regardless of probabilityNow change.
        ScoutLedger.LedgerEntry appended0 = newEntry(ScoutLedger.OUTCOME_APPENDED, today0.minusDays(400), 10);
        assertTrue(!ScoutLedger.eligible(appended0, 90, today0), "APPENDED must never be eligible");

        // (b) BELOW_THRESHOLD scored 10 days ago with unchanged probabilityNow is NOT eligible.
        ScoutLedger.LedgerEntry recentUnchanged0 = newEntry(ScoutLedger.OUTCOME_BELOW_THRESHOLD, today0.minusDays(10), 20);
        assertTrue(!ScoutLedger.eligible(recentUnchanged0, 20, today0),
            "BELOW_THRESHOLD scored 10 days ago with unchanged probabilityNow must not be eligible");

        // (c) The same entry scored 45 days ago IS eligible (cooldown expired).
        ScoutLedger.LedgerEntry oldUnchanged0 = newEntry(ScoutLedger.OUTCOME_BELOW_THRESHOLD, today0.minusDays(45), 20);
        assertTrue(ScoutLedger.eligible(oldUnchanged0, 20, today0),
            "BELOW_THRESHOLD scored 45 days ago must be eligible (cooldown expired)");

        // (d) BELOW_THRESHOLD scored 10 days ago whose probabilityNow rose (20 -> 65) IS eligible (re-surface rule).
        ScoutLedger.LedgerEntry roseRecent0 = newEntry(ScoutLedger.OUTCOME_BELOW_THRESHOLD, today0.minusDays(10), 20);
        assertTrue(ScoutLedger.eligible(roseRecent0, 65, today0),
            "BELOW_THRESHOLD whose probabilityNow rose must be eligible even within the cooldown window");

        // (e) REJECTED_BY_USER is never eligible.
        ScoutLedger.LedgerEntry rejected0 = newEntry(ScoutLedger.OUTCOME_REJECTED_BY_USER, today0.minusDays(400), 0);
        assertTrue(!ScoutLedger.eligible(rejected0, 100, today0), "REJECTED_BY_USER must never be eligible");

        // (f) APPENDED_NO_CONTACT entries are never eligible, regardless of probabilityNow change (task 0085).
        ScoutLedger.LedgerEntry appendedNoContact0 = newEntry(ScoutLedger.OUTCOME_APPENDED_NO_CONTACT, today0.minusDays(400), 10);
        assertTrue(!ScoutLedger.eligible(appendedNoContact0, 90, today0), "APPENDED_NO_CONTACT must never be eligible");

        // (g) InvestorScoutProcessor.appendableWithoutContact pure gate (task 0085).
        assertTrue(InvestorScoutProcessor.appendableWithoutContact(70, 60), "resources=70,probabilityNow=60 must be appendable without contact");
        assertTrue(!InvestorScoutProcessor.appendableWithoutContact(69, 60), "resources=69,probabilityNow=60 must NOT be appendable without contact");
        assertTrue(!InvestorScoutProcessor.appendableWithoutContact(70, 59), "resources=70,probabilityNow=59 must NOT be appendable without contact");

        // Sanity: an unseen candidate (null entry) is always eligible.
        assertTrue(ScoutLedger.eligible(null, 0, today0), "A never-scored candidate (null entry) must be eligible");

        // Sanity: LedgerEntry round-trips through toRow/fromRow.
        ScoutLedger.LedgerEntry roundTrip0 = new ScoutLedger.LedgerEntry();
        roundTrip0.crdOrEin = "123456";
        roundTrip0.firmName = "Acme Capital Partners";
        roundTrip0.firstSeen = today0.minusDays(100);
        roundTrip0.lastScored = today0;
        roundTrip0.resources = 55;
        roundTrip0.probabilityNow = 40;
        roundTrip0.fit = 88;
        roundTrip0.outcome = ScoutLedger.OUTCOME_BELOW_THRESHOLD;

        ScoutLedger.LedgerEntry parsed0 = ScoutLedger.LedgerEntry.fromRow(roundTrip0.toRow());
        assertTrue(parsed0.crdOrEin.equals("123456"), "round-trip crdOrEin");
        assertTrue(parsed0.firmName.equals("Acme Capital Partners"), "round-trip firmName");
        assertTrue(parsed0.firstSeen.equals(today0.minusDays(100)), "round-trip firstSeen");
        assertTrue(parsed0.lastScored.equals(today0), "round-trip lastScored");
        assertTrue(parsed0.resources == 55, "round-trip resources");
        assertTrue(parsed0.probabilityNow == 40, "round-trip probabilityNow");
        assertTrue(parsed0.fit == 88, "round-trip fit");
        assertTrue(parsed0.outcome.equals(ScoutLedger.OUTCOME_BELOW_THRESHOLD), "round-trip outcome");

        System.out.println("SCOUT_LEDGER_OK");
    }

    private static ScoutLedger.LedgerEntry newEntry(String outcome0, LocalDate lastScored0, int probabilityNow0)
    {
        ScoutLedger.LedgerEntry entry0 = new ScoutLedger.LedgerEntry();
        entry0.crdOrEin = "999999";
        entry0.firmName = "Test Fund";
        entry0.firstSeen = lastScored0;
        entry0.lastScored = lastScored0;
        entry0.resources = 50;
        entry0.probabilityNow = probabilityNow0;
        entry0.fit = 60;
        entry0.outcome = outcome0;
        return entry0;
    }

    private static void assertTrue(boolean condition0, String message0)
    {
        if (!condition0)
        {
            System.out.println("FAILED: " + message0);
            System.exit(1);
        }
    }
}
