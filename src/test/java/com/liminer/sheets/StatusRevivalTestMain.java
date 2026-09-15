package com.liminer.sheets;

import com.liminer.intake.InteractionSignalExtractor;
import com.liminer.intake.InteractionSignalExtractor.InteractionSignals;

import java.time.LocalDate;
import org.json.JSONArray;
import org.json.JSONObject;

/*
 * Offline verification of the Rejected-revival rule: an allocator who passed for lack
 * of capital can come back with a new fund, so First Interest and above overwrite a
 * Rejected Conversation Status -- provided the interaction is not older than the row's
 * last contact. Covers both halves of the rule: the write gate
 * (CrmUpdater.shouldUpdateStatus) and the read signal
 * (InteractionSignalExtractor.rejected). No network call may occur. Prints
 * STATUS_REVIVAL_OK as the final line only if every assertion passes.
 */
public class StatusRevivalTestMain
{
    private static int failures0 = 0;

    public static void main(String[] args0) throws Exception
    {
        testRejectedIsNoLongerAbsorbing();
        testReachedOutDoesNotRevive();
        testStaleInteractionDoesNotRevive();
        testUnchangedRulesBelowRejected();
        testExtractorClearsStaleRejection();

        if (failures0 > 0)
        {
            System.out.println("STATUS_REVIVAL_FAILED: " + failures0 + " check(s) failed");
            System.exit(1);
        }

        System.out.println("STATUS_REVIVAL_OK");
    }

    // ---------- checks ----------

    private static void testRejectedIsNoLongerAbsorbing()
    {
        check("First Interest revives Rejected",
            CrmUpdater.shouldUpdateStatus("Rejected", "First Interest", "2026-01-10", "2026-06-01"));

        check("Meetings revives Rejected",
            CrmUpdater.shouldUpdateStatus("Rejected", "Meetings", "2026-01-10", "2026-06-01"));

        check("Prospective Close revives Rejected",
            CrmUpdater.shouldUpdateStatus("Rejected", "Prospective Close", "2026-01-10", "2026-06-01"));

        check("same-day re-engagement revives Rejected",
            CrmUpdater.shouldUpdateStatus("Rejected", "Meetings", "2026-01-10", "2026-01-10"));

        check("missing timestamps fall back to the rank rule",
            CrmUpdater.shouldUpdateStatus("Rejected", "Meetings"));
    }

    private static void testReachedOutDoesNotRevive()
    {
        check("Reached Out does not revive Rejected",
            !CrmUpdater.shouldUpdateStatus("Rejected", "Reached Out", "2026-01-10", "2026-06-01"));

        check("Rejected does not re-write Rejected",
            !CrmUpdater.shouldUpdateStatus("Rejected", "Rejected", "2026-01-10", "2026-06-01"));

        check("an unknown label does not revive Rejected",
            !CrmUpdater.shouldUpdateStatus("Rejected", "Warm", "2026-01-10", "2026-06-01"));

        check("a blank label never writes",
            !CrmUpdater.shouldUpdateStatus("Rejected", "   ", "2026-01-10", "2026-06-01"));
    }

    private static void testStaleInteractionDoesNotRevive()
    {
        check("an interaction older than the rejection does not revive it",
            !CrmUpdater.shouldUpdateStatus("Rejected", "Meetings", "2026-06-01", "2026-01-10"));
    }

    private static void testUnchangedRulesBelowRejected()
    {
        check("Rejected still overwrites Meetings",
            CrmUpdater.shouldUpdateStatus("Meetings", "Rejected", "2026-01-10", "2026-06-01"));

        check("Meetings still promotes First Interest",
            CrmUpdater.shouldUpdateStatus("First Interest", "Meetings", null, null));

        check("Reached Out still does not demote Meetings",
            !CrmUpdater.shouldUpdateStatus("Meetings", "Reached Out", null, null));

        check("a blank current status still accepts any label",
            CrmUpdater.shouldUpdateStatus("", "Reached Out", null, null));
    }

    // The read side: a row whose Conversation Status column still says Rejected but
    // whose newest record is a meeting must not score as dead.
    private static void testExtractorClearsStaleRejection()
    {
        String records0 = wrap(
            record("2026-01-10", "inbound", "Rejected"),
            record("2026-06-01", "inbound", "Meetings"));

        InteractionSignals revived0 =
            InteractionSignalExtractor.extract(records0, "Rejected", "", LocalDate.of(2026, 6, 15));

        check("newest record at Meetings clears a stale Rejected column", !revived0.rejected);

        String rejectedLast0 = wrap(
            record("2026-01-10", "inbound", "Meetings"),
            record("2026-06-01", "inbound", "Rejected"));

        InteractionSignals rejected0 =
            InteractionSignalExtractor.extract(rejectedLast0, "Meetings", "", LocalDate.of(2026, 6, 15));

        check("newest record at Rejected still rejects", rejected0.rejected);

        String neutral0 = wrap(record("2026-06-01", "inbound", "Reached Out"));

        InteractionSignals fallback0 =
            InteractionSignalExtractor.extract(neutral0, "Rejected", "", LocalDate.of(2026, 6, 15));

        check("a Reached Out record leaves the Rejected column standing", fallback0.rejected);

        InteractionSignals noRecords0 =
            InteractionSignalExtractor.extract("", "Rejected", "2026-01-10", LocalDate.of(2026, 6, 15));

        check("with no records the Rejected column still governs", noRecords0.rejected);
    }

    // ---------- fixtures ----------

    private static String wrap(JSONObject... records0)
    {
        JSONArray arr0 = new JSONArray();

        for (int i0 = 0; i0 < records0.length; i0++)
        {
            arr0.put(records0[i0]);
        }

        JSONObject wrapper0 = new JSONObject();
        wrapper0.put("asOfDate", "2026-06-15");
        wrapper0.put("records", arr0);
        return wrapper0.toString();
    }

    private static JSONObject record(String date0, String direction0, String label0)
    {
        JSONObject obj0 = new JSONObject();
        obj0.put("date", date0);
        obj0.put("direction", direction0);
        obj0.put("type", "EMAIL");
        obj0.put("oneSentenceSummary", "test");
        obj0.put("conversationLabel", label0);
        obj0.put("keyTopicsDiscussed", new JSONArray());
        obj0.put("lpQuestionsAsked", new JSONArray());
        obj0.put("commitmentsMadeByGP", new JSONArray());
        obj0.put("lpSentiment", "");
        obj0.put("relationshipSignals", new JSONArray());
        return obj0;
    }

    private static void check(String label0, boolean condition0)
    {
        if (condition0)
        {
            System.out.println("  ok   " + label0);
            return;
        }

        System.out.println("  FAIL " + label0);
        failures0++;
    }
}
