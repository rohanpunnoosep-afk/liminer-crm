package com.liminer.ask;

import java.util.List;

public class AskToolSpec
{
    public String name;
    public String purpose;
    public List<AskToolArgSpec> args;
    public AskToolExecutor executor;

    public AskToolSpec(String name, String purpose, List<AskToolArgSpec> args, AskToolExecutor executor)
    {
        this.name = name;
        this.purpose = purpose;
        this.args = args;
        this.executor = executor;
    }
}
