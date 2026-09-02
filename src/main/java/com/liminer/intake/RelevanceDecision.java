package com.liminer.intake;

public class RelevanceDecision
{
    public boolean include = false;
    public String reason = "";
    public String detail = "";

    public RelevanceDecision(boolean include0, String reason0, String detail0)
    {
        this.include = include0;
        this.reason = reason0;
        this.detail = detail0 == null ? "" : detail0;
    }
}
