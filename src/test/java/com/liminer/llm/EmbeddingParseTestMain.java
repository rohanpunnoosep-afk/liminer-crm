package com.liminer.llm;

import com.liminer.billing.CostMeter;

import org.json.JSONObject;

import java.util.Collections;

/**
 * Offline verification of OpenAIClient's embeddings response parsing and metering
 * (task 0172). No network call is made -- responses are canned JSON strings.
 */
public class EmbeddingParseTestMain
{
    private static int failures0 = 0;

    public static void main(String[] args0) throws Exception
    {
        testParsesTwoElementResponse();
        testOutOfOrderDataIndex();
        testWrongDimsThrows();
        testMissingDataThrows();
        testPromptTokensParsing();
        testMetering();
        testEmptyInputShortCircuits();

        if (failures0 > 0)
        {
            System.out.println("EMBEDDING_PARSE_FAILED: " + failures0 + " check(s) failed");
            System.exit(1);
        }

        System.out.println("EMBEDDING_PARSE_OK");
    }

    private static void testParsesTwoElementResponse() throws Exception
    {
        JSONObject json0 = new JSONObject(
            "{ \"object\": \"list\", \"data\": ["
                + "{ \"object\": \"embedding\", \"index\": 0, \"embedding\": [0.1, 0.2, 0.3] },"
                + "{ \"object\": \"embedding\", \"index\": 1, \"embedding\": [0.4, 0.5, 0.6] }"
                + "], \"model\": \"text-embedding-3-small\", \"usage\": { \"prompt_tokens\": 8, \"total_tokens\": 8 } }"
        );

        float[][] vectors0 = OpenAIClient.parseEmbeddingResponse(json0, 2, 3);

        check("two vectors returned", vectors0.length == 2);
        check("vector 0 length", vectors0[0].length == 3);
        check("vector 0 values", close(vectors0[0][0], 0.1f) && close(vectors0[0][1], 0.2f) && close(vectors0[0][2], 0.3f));
        check("vector 1 values", close(vectors0[1][0], 0.4f) && close(vectors0[1][1], 0.5f) && close(vectors0[1][2], 0.6f));
    }

    private static void testOutOfOrderDataIndex() throws Exception
    {
        JSONObject json0 = new JSONObject(
            "{ \"object\": \"list\", \"data\": ["
                + "{ \"object\": \"embedding\", \"index\": 1, \"embedding\": [9.0, 9.0] },"
                + "{ \"object\": \"embedding\", \"index\": 0, \"embedding\": [1.0, 1.0] }"
                + "], \"model\": \"text-embedding-3-small\" }"
        );

        float[][] vectors0 = OpenAIClient.parseEmbeddingResponse(json0, 2, 2);

        check("out-of-order index 0 placed correctly", close(vectors0[0][0], 1.0f));
        check("out-of-order index 1 placed correctly", close(vectors0[1][0], 9.0f));
    }

    private static void testWrongDimsThrows()
    {
        JSONObject json0 = new JSONObject(
            "{ \"data\": [ { \"index\": 0, \"embedding\": [0.1, 0.2] } ] }"
        );

        try
        {
            OpenAIClient.parseEmbeddingResponse(json0, 1, 3);
            check("wrong dims throws", false);
        }
        catch (Exception exception0)
        {
            check("wrong dims throws", true);
        }
    }

    private static void testMissingDataThrows()
    {
        JSONObject json0 = new JSONObject("{ \"object\": \"list\" }");

        try
        {
            OpenAIClient.parseEmbeddingResponse(json0, 1, 3);
            check("missing data throws", false);
        }
        catch (Exception exception0)
        {
            check("missing data message descriptive", exception0.getMessage() != null
                && exception0.getMessage().toLowerCase().contains("data"));
        }
    }

    private static void testPromptTokensParsing()
    {
        JSONObject withUsage0 = new JSONObject("{ \"usage\": { \"prompt_tokens\": 42 } }");
        check("prompt tokens read from usage", OpenAIClient.parseEmbeddingPromptTokens(withUsage0) == 42);

        JSONObject withoutUsage0 = new JSONObject("{}");
        check("prompt tokens default to 0", OpenAIClient.parseEmbeddingPromptTokens(withoutUsage0) == 0);
    }

    private static void testMetering()
    {
        CostMeter meter0 = new CostMeter();
        CostMeter.bind(meter0);

        try
        {
            meter0.record(OpenAIClient.EMBED_MODEL0, 1_000_000, 0);

            check("1M embedding tokens costs 0.02 USD", Math.abs(meter0.usd() - 0.02) < 1e-9);
            check("embedding model is known", meter0.toJson().getLong("unknownModelCalls") == 0);
        }
        finally
        {
            CostMeter.unbind();
        }
    }

    private static void testEmptyInputShortCircuits() throws Exception
    {
        check("empty input returns empty list without HTTP call",
            OpenAIClient.getEmbeddings(Collections.emptyList(), 512).isEmpty());
    }

    private static boolean close(float a0, float b0)
    {
        return Math.abs(a0 - b0) < 1e-6;
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
