package com.liminer.billing;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Offline unit test for CostMeter: exact pricing arithmetic, ceiling enforcement,
 * thread-safety under concurrent record() calls, propagation of a bound meter into
 * ExecutorService pool threads, and graceful handling of unknown model ids. Makes
 * NO real OpenAI API call. Prints COST_METER_OK on success; exits 1 on failure.
 */
public class CostMeterTest
{
    @Test
    void exactPricing() throws Exception
    {
        CostMeter meter = new CostMeter(1000.0);

        // gpt-4.1-mini: $0.40 / 1M prompt, $1.60 / 1M completion
        meter.record("gpt-4.1-mini", 1_000_000, 1_000_000);

        // 0.40 + 1.60 = 2.00 exactly.
        check("gpt-4.1-mini 1M/1M -> $2.00 exactly", meter.usd() == 2.00);

        CostMeter meter2 = new CostMeter(1000.0);
        // gpt-4.1: $2.00 / 1M prompt, $8.00 / 1M completion
        meter2.record("gpt-4.1", 500_000, 250_000);
        // 500000*2.00/1e6 = 1.00 ; 250000*8.00/1e6 = 2.00 -> 3.00
        check("gpt-4.1 500k/250k -> $3.00 exactly", meter2.usd() == 3.00);

        check("calls counted", meter.toJson().getLong("calls") == 1);
        check("promptTokens counted", meter.toJson().getLong("promptTokens") == 1_000_000);
        check("completionTokens counted", meter.toJson().getLong("completionTokens") == 1_000_000);
    }

    @Test
    void ceilingThrows() throws Exception
    {
        CostMeter meter = new CostMeter(1.00);

        // gpt-4.1-mini prompt-only calls: each 1_000_000 tokens = $0.40
        meter.record("gpt-4.1-mini", 1_000_000, 0);
        meter.checkCeiling(); // 0.40 <= 1.00, must not throw

        meter.record("gpt-4.1-mini", 1_000_000, 0);
        meter.checkCeiling(); // 0.80 <= 1.00, must not throw

        meter.record("gpt-4.1-mini", 1_000_000, 0);
        // 1.20 > 1.00, must throw
        boolean threw = false;
        try
        {
            meter.checkCeiling();
        }
        catch (CostCeilingExceededException e)
        {
            threw = true;
            check("exception carries ceiling", e.ceilingUsd == 1.00);
            check("exception carries spend", e.spentUsd == 1.20);
        }
        check("checkCeiling throws once over budget", threw);
    }

    @Test
    void concurrentRecordIsExact() throws Exception
    {
        CostMeter meter = new CostMeter(1_000_000.0);
        int threadCount = 8;
        int callsPerThread = 100;

        ExecutorService pool = Executors.newFixedThreadPool(threadCount);
        CountDownLatch latch = new CountDownLatch(threadCount);

        for (int i = 0; i < threadCount; i++)
        {
            pool.submit(() ->
            {
                try
                {
                    for (int j = 0; j < callsPerThread; j++)
                    {
                        meter.record("gpt-4.1-mini", 1000, 1000);
                    }
                }
                finally
                {
                    latch.countDown();
                }
            });
        }

        check("all threads finished", latch.await(30, TimeUnit.SECONDS));
        pool.shutdown();

        long expectedCalls = threadCount * callsPerThread;
        long expectedPromptTokens = expectedCalls * 1000;
        // per-call usd = (1000*0.40 + 1000*1.60) / 1e6 = 0.000002 ; * 800 calls = 0.0016
        double expectedUsd = expectedCalls * ((1000 * 0.40 + 1000 * 1.60) / 1_000_000.0);

        check("concurrent calls exact", meter.toJson().getLong("calls") == expectedCalls);
        check("concurrent promptTokens exact", meter.toJson().getLong("promptTokens") == expectedPromptTokens);
        check("concurrent usd exact", meter.usd() == expectedUsd);
    }

    @Test
    void executorPoolPropagation() throws Exception
    {
        CostMeter meter = new CostMeter(1_000_000.0);
        CostMeter.bind(meter);

        try
        {
            ExecutorService pool = Executors.newFixedThreadPool(4);

            Runnable task = CostMeter.wrap(() ->
            {
                CostMeter boundInPool = CostMeter.current();
                if (boundInPool != null)
                {
                    boundInPool.record("gpt-4.1", 100, 100);
                }
            });

            pool.submit(task).get(10, TimeUnit.SECONDS);
            pool.submit(task).get(10, TimeUnit.SECONDS);
            pool.shutdown();

            check("meter visible in pool thread and updates propagate back",
                meter.toJson().getLong("calls") == 2);
        }
        finally
        {
            CostMeter.unbind();
        }
    }

    @Test
    void unknownModelDoesNotThrow() throws Exception
    {
        CostMeter meter = new CostMeter(1000.0);
        meter.record("some-future-model-nobody-priced-yet", 500, 500);

        check("unknown model does not throw and counts tokens",
            meter.toJson().getLong("promptTokens") == 500);
        check("unknown model contributes zero USD", meter.usd() == 0.0);
        check("unknown model call is visible via unknownModelCalls",
            meter.toJson().getLong("unknownModelCalls") == 1);
        check("unknown model call still counted in calls",
            meter.toJson().getLong("calls") == 1);
    }

    private static void check(String label, boolean condition)
    {
        assertTrue(condition, label);
    }
}
