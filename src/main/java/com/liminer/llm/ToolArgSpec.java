package com.liminer.llm;

public class ToolArgSpec
{
    public String name;
    public String description;
    public String type;

    public ToolArgSpec(String name, String description, String type)
    {
        this.name = name;
        this.description = description;
        this.type = type;
    }
}