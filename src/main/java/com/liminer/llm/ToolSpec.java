package com.liminer.llm;

import java.util.List;

public class ToolSpec
{
    public String name;
    public String purpose;
    public List<ToolArgSpec> args;
    public ToolExecutor executor;

    public ToolSpec(String name, String purpose, List<ToolArgSpec> args, ToolExecutor executor)
    {
        this.name = name;
        this.purpose = purpose;
        this.args = args;
        this.executor = executor;
    }
}