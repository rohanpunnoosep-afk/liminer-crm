package com.liminer.billing;

/**
 * Thrown by {@link CostMeter#checkCeiling()} when a run's accumulated OpenAI spend
 * has passed its per-run ceiling. Callers (WebServer.runJob) surface this as a
 * FAILED job naming the ceiling and the spend, rather than letting the run burn
 * further tokens.
 */
public class CostCeilingExceededException extends Exception
{
    public final double ceilingUsd;
    public final double spentUsd;

    public CostCeilingExceededException(double ceilingUsd, double spentUsd)
    {
        super("Cost ceiling exceeded: spent $" + String.format("%.4f", spentUsd)
            + " > ceiling $" + String.format("%.2f", ceilingUsd));

        this.ceilingUsd = ceilingUsd;
        this.spentUsd = spentUsd;
    }
}
