package com.liminer.billing;

import org.json.JSONObject;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Per-run token/cost meter for OpenAI calls. One instance scopes one "run" (a web
 * job, or nothing at all for terminal/AgentMain workflows, which never bind a
 * meter and so pay no metering cost).
 *
 * Every counter is an AtomicLong so concurrent record() calls from a bounded
 * thread pool (see the row-parallel processors) are safe. Spend is tracked as an
 * integer count of micro-dollars (USD * 1,000,000) rather than a summed double,
 * so concurrent additions land on an exact total instead of accumulating float
 * rounding error.
 */
public class CostMeter
{
    // ------------------------------------------------------------------------
    // HARDCODED OPENAI PRICING (USD per 1,000,000 tokens). These are the rates
    // in effect as of when this file was written. OpenAI changes prices without
    // notice -- check https://openai.com/api/pricing/ before trusting these for
    // anything beyond a rough ceiling check.
    // ------------------------------------------------------------------------
    private static final double GPT_4_1_MINI_PROMPT_USD_PER_1M     = 0.40;
    private static final double GPT_4_1_MINI_COMPLETION_USD_PER_1M = 1.60;
    private static final double GPT_4_1_PROMPT_USD_PER_1M          = 2.00;
    private static final double GPT_4_1_COMPLETION_USD_PER_1M      = 8.00;

    private static final Map<String, double[]> PRICES_PER_1M_TOKENS = new ConcurrentHashMap<>();

    static
    {
        PRICES_PER_1M_TOKENS.put("gpt-4.1-mini",
            new double[] { GPT_4_1_MINI_PROMPT_USD_PER_1M, GPT_4_1_MINI_COMPLETION_USD_PER_1M });
        PRICES_PER_1M_TOKENS.put("gpt-4.1",
            new double[] { GPT_4_1_PROMPT_USD_PER_1M, GPT_4_1_COMPLETION_USD_PER_1M });
    }

    public static final double DEFAULT_CEILING_USD = 2.00;

    // Env var LIMINER_MAX_RUN_USD overrides the default per-run hard cost ceiling
    // (in USD). Unset or unparsable -> DEFAULT_CEILING_USD.
    public static double defaultCeilingUsdFromEnv()
    {
        String raw = System.getenv("LIMINER_MAX_RUN_USD");

        if (raw == null || raw.trim().isEmpty())
        {
            return DEFAULT_CEILING_USD;
        }

        try
        {
            return Double.parseDouble(raw.trim());
        }
        catch (NumberFormatException e)
        {
            return DEFAULT_CEILING_USD;
        }
    }

    private final double ceilingUsd;
    private final AtomicLong calls = new AtomicLong();
    private final AtomicLong promptTokens = new AtomicLong();
    private final AtomicLong completionTokens = new AtomicLong();
    private final AtomicLong microDollars = new AtomicLong();
    private final AtomicLong unknownModelCalls = new AtomicLong();

    public CostMeter(double ceilingUsd)
    {
        this.ceilingUsd = ceilingUsd;
    }

    public CostMeter()
    {
        this(defaultCeilingUsdFromEnv());
    }

    /**
     * Records one API call's token usage against this meter. Unknown model ids
     * still count tokens and calls (visible via unknownModelCalls/toJson) but
     * contribute zero USD, rather than being silently dropped or throwing.
     */
    public void record(String model, long promptTok, long completionTok)
    {
        calls.incrementAndGet();
        promptTokens.addAndGet(promptTok);
        completionTokens.addAndGet(completionTok);

        double[] price = model == null ? null : PRICES_PER_1M_TOKENS.get(model);

        if (price == null)
        {
            unknownModelCalls.incrementAndGet();
            return;
        }

        double usd = (promptTok * price[0] + completionTok * price[1]) / 1_000_000.0;
        microDollars.addAndGet(Math.round(usd * 1_000_000.0));
    }

    public double usd()
    {
        return microDollars.get() / 1_000_000.0;
    }

    public double ceilingUsd()
    {
        return ceilingUsd;
    }

    public void checkCeiling() throws CostCeilingExceededException
    {
        double spent = usd();

        if (spent > ceilingUsd)
        {
            throw new CostCeilingExceededException(ceilingUsd, spent);
        }
    }

    public JSONObject toJson()
    {
        JSONObject json = new JSONObject();
        json.put("calls", calls.get());
        json.put("promptTokens", promptTokens.get());
        json.put("completionTokens", completionTokens.get());
        json.put("usd", usd());
        json.put("ceilingUsd", ceilingUsd);
        json.put("unknownModelCalls", unknownModelCalls.get());
        return json;
    }

    // ------------------------------------------------------------------------
    // Run-scoped binding.
    //
    // Stored in an InheritableThreadLocal so a freshly-created pool (the common
    // case: InvestorBriefJsonProcessor/LPScoreProcessor/BasicBackgroundChecker
    // all call Executors.newFixedThreadPool(...) per run) picks up the meter
    // automatically when its worker threads are first spawned from a thread
    // that already has one bound.
    //
    // That alone is NOT enough for a pool whose threads pre-date/outlive the
    // run that created this meter (e.g. BasicBackgroundChecker's static shared
    // SCRAPE_POOL): those worker threads captured whatever meter was bound (or
    // none) at thread-creation time and never see a later bind(). wrap(...)
    // below fixes that case by re-binding the captured meter for the duration
    // of each task regardless of which thread -- new or reused -- executes it.
    // ------------------------------------------------------------------------
    private static final InheritableThreadLocal<CostMeter> HOLDER = new InheritableThreadLocal<>();

    public static CostMeter current()
    {
        return HOLDER.get();
    }

    public static void bind(CostMeter meter)
    {
        HOLDER.set(meter);
    }

    public static void unbind()
    {
        HOLDER.remove();
    }

    /** Wraps a task so the meter bound on the submitting thread stays bound wherever it runs. */
    public static Runnable wrap(Runnable task)
    {
        CostMeter captured = current();

        return () ->
        {
            CostMeter previous = current();
            bind(captured);

            try
            {
                task.run();
            }
            finally
            {
                if (previous == null)
                {
                    unbind();
                }
                else
                {
                    bind(previous);
                }
            }
        };
    }
}
