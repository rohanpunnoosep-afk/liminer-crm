package com.liminer.core;

/*
 * ConnectionPoint describes the basis of trust between a GP and an LP -- how they came
 * to know each other. Ranks are 1-based and ascend by strength of tie, from a cold
 * outbound contact (weakest) to a prior LP who has already committed capital
 * (strongest). UNKNOWN is not a rank; it encodes as the zero vector in RbfEncoder.
 */
public enum ConnectionPoint
{
    COLD_OUTBOUND(1, "Cold Outbound"),
    SHARED_INSTITUTION(2, "Shared Institution"),
    PLATFORM_INTRODUCTION(3, "Platform Introduction"),
    EVENT_ENCOUNTER(4, "Event Encounter"),
    INBOUND(5, "Inbound"),
    WEAK_REFERRAL(6, "Weak Referral"),
    PAST_WORK_COLLEAGUE(7, "Past Work Colleague"),
    STRONG_REFERRAL(8, "Strong Referral"),
    PAST_COINVESTOR(9, "Past Coinvestor"),
    PRIOR_LP(10, "Prior LP"),
    UNKNOWN(0, "Unknown");

    private final int rank0;
    private final String label0;

    ConnectionPoint(int rank0, String label0)
    {
        this.rank0 = rank0;
        this.label0 = label0;
    }

    public int rank()
    {
        return rank0;
    }

    public String label()
    {
        return label0;
    }

    public static ConnectionPoint fromLabel(String label0)
    {
        if (label0 == null)
        {
            return UNKNOWN;
        }

        String normalized0 = label0.trim().toUpperCase().replace('-', '_').replace(' ', '_');

        for (ConnectionPoint value0 : values())
        {
            if (value0.name().equals(normalized0))
            {
                return value0;
            }
        }

        return UNKNOWN;
    }
}
