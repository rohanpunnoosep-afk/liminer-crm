package com.liminer.intake;

import com.liminer.core.ConnectionPoint;

import org.json.JSONArray;
import org.json.JSONObject;

/*
 * Offline verification of ConnectionPointExtractor (task 0175). Uses a stubbed
 * LlmSeam -- no network call may occur. Prints CONNECTION_POINT_OK as the final line
 * only if every assertion passes.
 */
public class ConnectionPointTestMain
{
    private static int failures0 = 0;

    public static void main(String[] args0) throws Exception
    {
        testNeverDowngradeRule();
        testMergeAdoptsHigherConfidenceDifferentCategory();
        testMergeNullSafety();
        testEarliestRecordSelection();
        testBlankMalformedOrEmptyRecordsYieldUnknown();
        testNonJsonStubResponseYieldsUnknown();
        testClassificationRoundTrip();
        testResultJsonRoundTrip();

        if (failures0 > 0)
        {
            System.out.println("CONNECTION_POINT_FAILED: " + failures0 + " check(s) failed");
            System.exit(1);
        }

        System.out.println("CONNECTION_POINT_OK");
    }

    // ---------- checks ----------

    private static void testNeverDowngradeRule()
    {
        ConnectionPointExtractor.Result stored0 = new ConnectionPointExtractor.Result();
        stored0.category = ConnectionPoint.STRONG_REFERRAL;
        stored0.confidence = 0.9;

        ConnectionPointExtractor.Result fresh0 = new ConnectionPointExtractor.Result();
        fresh0.category = ConnectionPoint.UNKNOWN;
        fresh0.confidence = 0.0;

        ConnectionPointExtractor.Result merged0 = ConnectionPointExtractor.merge(stored0, fresh0);

        check("never-downgrade: fresh UNKNOWN cannot erase stored category",
            merged0.category == ConnectionPoint.STRONG_REFERRAL);
    }

    private static void testMergeAdoptsHigherConfidenceDifferentCategory()
    {
        ConnectionPointExtractor.Result stored0 = new ConnectionPointExtractor.Result();
        stored0.category = ConnectionPoint.WEAK_REFERRAL;
        stored0.confidence = 0.5;

        ConnectionPointExtractor.Result fresh0 = new ConnectionPointExtractor.Result();
        fresh0.category = ConnectionPoint.PRIOR_LP;
        fresh0.confidence = 0.8;

        ConnectionPointExtractor.Result merged0 = ConnectionPointExtractor.merge(stored0, fresh0);

        check("merge adopts higher-confidence different known category",
            merged0.category == ConnectionPoint.PRIOR_LP);
    }

    private static void testMergeNullSafety()
    {
        ConnectionPointExtractor.Result result0 = new ConnectionPointExtractor.Result();
        result0.category = ConnectionPoint.EVENT_ENCOUNTER;

        check("merge with null stored returns fresh",
            ConnectionPointExtractor.merge(null, result0) == result0);
        check("merge with null fresh returns stored",
            ConnectionPointExtractor.merge(result0, null) == result0);
        check("merge with both null returns null",
            ConnectionPointExtractor.merge(null, null) == null);
    }

    private static void testEarliestRecordSelection()
    {
        String[] capturedPrompt0 = { null };
        ConnectionPointExtractor.LlmSeam stub0 = prompt0 -> {
            capturedPrompt0[0] = prompt0;
            return "{\"category\": \"UNKNOWN\", \"confidence\": 0.0}";
        };

        JSONArray records0 = new JSONArray();
        records0.put(record("2024-05-01", "OUTBOUND", "MIDDLE_MARKER_SUMMARY"));
        records0.put(record("2024-01-15", "OUTBOUND", "OLDEST_MARKER_SUMMARY"));
        records0.put(record("2024-09-20", "INBOUND", "NEWEST_MARKER_SUMMARY"));

        JSONObject wrapper0 = new JSONObject();
        wrapper0.put("asOfDate", "2024-09-20");
        wrapper0.put("records", records0);

        ConnectionPointExtractor extractor0 = new ConnectionPointExtractor(stub0);
        extractor0.extractFromInteractionRecords(wrapper0.toString());

        check("earliest-record selection: stub receives oldest record text",
            capturedPrompt0[0] != null && capturedPrompt0[0].contains("OLDEST_MARKER_SUMMARY"));
        check("earliest-record selection: stub does not receive the newest record alone as the primary",
            capturedPrompt0[0] != null && !capturedPrompt0[0].contains("NEWEST_MARKER_SUMMARY"));
    }

    private static void testBlankMalformedOrEmptyRecordsYieldUnknown()
    {
        ConnectionPointExtractor.LlmSeam neverCalled0 = prompt0 -> {
            throw new AssertionError("LLM should not be called for unusable records");
        };
        ConnectionPointExtractor extractor0 = new ConnectionPointExtractor(neverCalled0);

        ConnectionPointExtractor.Result blank0 = extractor0.extractFromInteractionRecords("");
        check("blank records string yields UNKNOWN", blank0.category == ConnectionPoint.UNKNOWN);
        check("blank records string yields 0 confidence", blank0.confidence == 0.0);

        ConnectionPointExtractor.Result malformed0 = extractor0.extractFromInteractionRecords("{not json");
        check("malformed records string yields UNKNOWN", malformed0.category == ConnectionPoint.UNKNOWN);

        ConnectionPointExtractor.Result empty0 = extractor0.extractFromInteractionRecords("[]");
        check("empty records array yields UNKNOWN", empty0.category == ConnectionPoint.UNKNOWN);
    }

    private static void testNonJsonStubResponseYieldsUnknown()
    {
        ConnectionPointExtractor.LlmSeam stub0 = prompt0 -> "not json at all";
        ConnectionPointExtractor extractor0 = new ConnectionPointExtractor(stub0);

        ConnectionPointExtractor.Result result0 = extractor0.extractFromEmailBody(
            "Hi there, reaching out cold.", "OUTBOUND", "gp@fund.com", "Intro");

        check("non-JSON stub response yields UNKNOWN", result0.category == ConnectionPoint.UNKNOWN);
        check("non-JSON stub response yields 0 confidence", result0.confidence == 0.0);
    }

    private static void testClassificationRoundTrip()
    {
        String body0 = "We connected briefly during my time at my previous fund";

        ConnectionPointExtractor.LlmSeam stub0 = prompt0 -> {
            JSONObject response0 = new JSONObject();
            response0.put("category", "PAST_WORK_COLLEAGUE");
            response0.put("confidence", 0.85);
            response0.put("referrer_name", "");
            response0.put("referrer_relation_to_gp", "");
            response0.put("evidence_quote", body0);
            return response0.toString();
        };

        ConnectionPointExtractor extractor0 = new ConnectionPointExtractor(stub0);
        ConnectionPointExtractor.Result result0 =
            extractor0.extractFromEmailBody(body0, "INBOUND", "lp@fund.com", "Following up");

        check("classification round-trip: category", result0.category == ConnectionPoint.PAST_WORK_COLLEAGUE);
        check("classification round-trip: evidence quote preserved",
            body0.equals(result0.evidenceQuote));
    }

    private static void testResultJsonRoundTrip()
    {
        ConnectionPointExtractor.Result result0 = new ConnectionPointExtractor.Result();
        result0.category = ConnectionPoint.PLATFORM_INTRODUCTION;
        result0.confidence = 0.42;
        result0.referrerName = "Jane Doe";
        result0.referrerRelationToGp = "Prior LP";
        result0.evidenceQuote = "quote text";
        result0.sourceMessageId = "msg-123";
        result0.extractedAt = "2024-01-01T00:00:00Z";

        ConnectionPointExtractor.Result roundTripped0 =
            ConnectionPointExtractor.Result.fromJson(result0.toJson());

        check("Result.fromJson round-trip: category", roundTripped0.category == result0.category);
        check("Result.fromJson round-trip: confidence", roundTripped0.confidence == result0.confidence);
        check("Result.fromJson round-trip: referrer",
            result0.referrerName.equals(roundTripped0.referrerName));
    }

    // ---------- fixtures ----------

    private static JSONObject record(String date0, String direction0, String summary0)
    {
        JSONObject obj0 = new JSONObject();
        obj0.put("date", date0);
        obj0.put("direction", direction0);
        obj0.put("type", "EMAIL");
        obj0.put("oneSentenceSummary", summary0);
        obj0.put("conversationLabel", "");
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
