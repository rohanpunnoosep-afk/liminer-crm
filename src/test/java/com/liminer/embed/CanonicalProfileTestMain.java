package com.liminer.embed;

/*
 * Offline verification of CanonicalProfile + CanonicalProfileBuilder (task 0171).
 * Uses a stubbed LlmSeam -- no network call occurs.
 */
public class CanonicalProfileTestMain
{
    private static int failures0 = 0;

    public static void main(String[] args0) throws Exception
    {
        testOrderInvariance();
        testDedupe();
        testHashSensitivity();
        testMissingNumbers();
        testRoundTrip();
        testEnumSnapping();
        testMalformedLlmOutput();

        if (failures0 > 0)
        {
            System.out.println("CANONICAL_PROFILE_FAILED: " + failures0 + " check(s) failed");
            System.exit(1);
        }

        System.out.println("CANONICAL_PROFILE_OK");
    }

    private static void testOrderInvariance() throws Exception
    {
        String responseA0 = "{"
            + "\"thesis\": [\"backed fund a | ai | seed | us\", \"backed fund b | fintech | series a | eu\"],"
            + "\"pastInvestments\": [], \"newInvestmentAreas\": [], \"allocatorType\": \"VENTURE_CAPITAL\","
            + "\"aumUsd\": 1000000, \"capitalAllocatableUsd\": null, \"pastInvestmentAmountUsd\": null,"
            + "\"timingMonthsSinceLastClose\": null}";

        String responseB0 = "{"
            + "\"thesis\": [\"backed fund b | fintech | series a | eu\", \"backed fund a | ai | seed | us\"],"
            + "\"pastInvestments\": [], \"newInvestmentAreas\": [], \"allocatorType\": \"VENTURE_CAPITAL\","
            + "\"aumUsd\": 1000000, \"capitalAllocatableUsd\": null, \"pastInvestmentAmountUsd\": null,"
            + "\"timingMonthsSinceLastClose\": null}";

        CanonicalProfile profileA0 = buildFromStub(responseA0);
        CanonicalProfile profileB0 = buildFromStub(responseB0);

        check("order invariance: identical JSON string", profileA0.toJson().toString().equals(profileB0.toJson().toString()));
        check("order invariance: identical hash", profileA0.canonicalHash().equals(profileB0.canonicalHash()));
    }

    private static void testDedupe() throws Exception
    {
        String response0 = "{"
            + "\"thesis\": [\"backed fund a | ai | seed | us\", \"  Backed Fund A | AI | Seed | US  \", \"backed fund a | ai | seed | us\"],"
            + "\"pastInvestments\": [], \"newInvestmentAreas\": [], \"allocatorType\": \"\","
            + "\"aumUsd\": null, \"capitalAllocatableUsd\": null, \"pastInvestmentAmountUsd\": null,"
            + "\"timingMonthsSinceLastClose\": null}";

        CanonicalProfile profile0 = buildFromStub(response0);

        check("dedupe: collapses case/whitespace variants to one atom", profile0.thesis.size() == 1);
    }

    private static void testHashSensitivity() throws Exception
    {
        String response0 = "{\"thesis\": [\"backed fund a | ai | seed | us\"], \"pastInvestments\": [], "
            + "\"newInvestmentAreas\": [], \"allocatorType\": \"\", \"aumUsd\": null, "
            + "\"capitalAllocatableUsd\": null, \"pastInvestmentAmountUsd\": null, \"timingMonthsSinceLastClose\": null}";

        String responseChanged0 = "{\"thesis\": [\"backed fund c | climate | growth | us\"], \"pastInvestments\": [], "
            + "\"newInvestmentAreas\": [], \"allocatorType\": \"\", \"aumUsd\": null, "
            + "\"capitalAllocatableUsd\": null, \"pastInvestmentAmountUsd\": null, \"timingMonthsSinceLastClose\": null}";

        CanonicalProfile profile0 = buildFromStub(response0);
        CanonicalProfile changed0 = buildFromStub(responseChanged0);

        check("hash sensitivity: changing one atom changes the hash", !profile0.canonicalHash().equals(changed0.canonicalHash()));
    }

    private static void testMissingNumbers() throws Exception
    {
        String response0 = "{\"thesis\": [], \"pastInvestments\": [], \"newInvestmentAreas\": [], "
            + "\"allocatorType\": \"\", \"capitalAllocatableUsd\": null, \"pastInvestmentAmountUsd\": null, "
            + "\"timingMonthsSinceLastClose\": null}";

        CanonicalProfile profile0 = buildFromStub(response0);

        check("missing numbers: aumUsd is NaN", Double.isNaN(profile0.aumUsd));
        check("missing numbers: aumUsd key absent from JSON", !profile0.toJson().has("aumUsd"));
    }

    private static void testRoundTrip() throws Exception
    {
        String response0 = "{\"thesis\": [\"backed fund a | ai | seed | us\", \"backed fund b | fintech | series a | eu\"], "
            + "\"pastInvestments\": [\"led round in fund c\"], \"newInvestmentAreas\": [\"climate\"], "
            + "\"allocatorType\": \"FAMILY_OFFICE\", \"aumUsd\": 5000000, \"capitalAllocatableUsd\": 250000, "
            + "\"pastInvestmentAmountUsd\": 100000, \"timingMonthsSinceLastClose\": 6}";

        CanonicalProfile original0 = buildFromStub(response0);
        CanonicalProfile roundTripped0 = CanonicalProfile.fromJson(original0.toJson());

        check("round trip: identical JSON string", original0.toJson().toString().equals(roundTripped0.toJson().toString()));
        check("round trip: identical hash", original0.canonicalHash().equals(roundTripped0.canonicalHash()));
    }

    private static void testEnumSnapping() throws Exception
    {
        String response0 = "{\"thesis\": [], \"pastInvestments\": [], \"newInvestmentAreas\": [], "
            + "\"allocatorType\": \"HEDGE_FUND\", \"aumUsd\": null, \"capitalAllocatableUsd\": null, "
            + "\"pastInvestmentAmountUsd\": null, \"timingMonthsSinceLastClose\": null}";

        CanonicalProfile profile0 = buildFromStub(response0);

        check("enum snapping: unknown allocatorType becomes empty string", "".equals(profile0.allocatorType));
        check("enum snapping: allocatorType key absent from JSON", !profile0.toJson().has("allocatorType"));
    }

    private static void testMalformedLlmOutput() throws Exception
    {
        CanonicalProfile profile0 = buildFromStub("not json at all");

        check("malformed output: empty thesis", profile0.thesis.isEmpty());
        check("malformed output: empty pastInvestments", profile0.pastInvestments.isEmpty());
        check("malformed output: empty newInvestmentAreas", profile0.newInvestmentAreas.isEmpty());
        check("malformed output: allocatorType empty", "".equals(profile0.allocatorType));
        check("malformed output: aumUsd NaN", Double.isNaN(profile0.aumUsd));
    }

    private static CanonicalProfile buildFromStub(String stubResponse0) throws Exception
    {
        CanonicalProfileBuilder builder0 = new CanonicalProfileBuilder(prompt0 -> stubResponse0);
        return builder0.build(new CanonicalProfileBuilder.Input());
    }

    private static void check(String label0, boolean condition0)
    {
        if (!condition0)
        {
            failures0++;
            System.out.println("FAILED: " + label0);
        }
    }
}
